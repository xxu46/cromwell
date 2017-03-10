package cromwell.backend.impl.jes

import java.net.SocketTimeoutException

import akka.actor.ActorRef
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.genomics.model.RunPipelineRequest
import cromwell.backend._
import cromwell.backend.impl.jes.RunStatus.UnsuccessfulRunStatus
import cromwell.backend.async.{AbortedExecutionHandle, ExecutionHandle, FailedNonRetryableExecutionHandle, FailedRetryableExecutionHandle, PendingExecutionHandle}
import cromwell.backend.impl.jes.RunStatus.TerminalRunStatus
import cromwell.backend.impl.jes.errors.FailedToDelocalizeFailure
import cromwell.backend.impl.jes.io._
import cromwell.backend.impl.jes.statuspolling.{JesRunCreationClient, JesStatusRequestClient}
import cromwell.backend.standard.{StandardAsyncExecutionActor, StandardAsyncExecutionActorParams, StandardAsyncJob}
import cromwell.core._
import cromwell.core.logging.JobLogger
import cromwell.core.path.{DefaultPathBuilder, Path}
import cromwell.core.retry.SimpleExponentialBackoff
import cromwell.services.keyvalue.KeyValueServiceActor._
import cromwell.filesystems.gcs.GcsPath
import org.slf4j.LoggerFactory
import wdl4s._
import wdl4s.expression.NoFunctions
import wdl4s.values._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}
import scala.collection.JavaConverters._

object JesAsyncBackendJobExecutionActor {
  val JesOperationIdKey = "__jes_operation_id"

  object WorkflowOptionKeys {
    val MonitoringScript = "monitoring_script"
    val GoogleProject = "google_project"
    val GoogleComputeServiceAccount = "google_compute_service_account"
  }

  type JesPendingExecutionHandle = PendingExecutionHandle[StandardAsyncJob, Run, RunStatus]

  private val ExtraConfigParamName = "__extra_config_gcs_path"

  private def stringifyMap(m: Map[String, String]): String = m map { case(k, v) => s"  $k -> $v"} mkString "\n"

  val maxUnexpectedRetries = 2

  val GoogleCancelledRpc = 1
  val GoogleNotFoundRpc = 5
  val GoogleAbortedRpc = 10 // Note "Aborted" here is not the same as our "abort"
  val JesFailedToDelocalize = 5
  val JesUnexpectedTermination = 13
  val JesPreemption = 14

  def StandardException(errorCode: Int, message: String, jobTag: String) = {
    new RuntimeException(s"Task $jobTag failed: error code $errorCode.$message")
  }
}

class JesAsyncBackendJobExecutionActor(override val standardParams: StandardAsyncExecutionActorParams)
  extends BackendJobLifecycleActor with StandardAsyncExecutionActor with JesJobCachingActorHelper
    with JesStatusRequestClient with JesRunCreationClient  {

  import JesAsyncBackendJobExecutionActor._

  val slf4jLogger = LoggerFactory.getLogger(JesAsyncBackendJobExecutionActor.getClass)
  val logger = new JobLogger("JesRun", jobDescriptor.workflowDescriptor.id, jobDescriptor.key.tag, None, Set(slf4jLogger))

  val jesBackendSingletonActor: ActorRef =
    standardParams.backendSingletonActorOption.getOrElse(
      throw new RuntimeException("JES Backend actor cannot exist without the JES backend singleton actor"))

  override type StandardAsyncRunInfo = Run

  override type StandardAsyncRunStatus = RunStatus

  override val pollingActor: ActorRef = jesBackendSingletonActor

  override lazy val pollBackOff = SimpleExponentialBackoff(
    initialInterval = 30 seconds, maxInterval = jesAttributes.maxPollingInterval seconds, multiplier = 1.1)

  override lazy val executeOrRecoverBackOff = SimpleExponentialBackoff(
    initialInterval = 3 seconds, maxInterval = 20 seconds, multiplier = 1.1)

  private lazy val attempt: Int = jobDescriptor.key.attempt

  private lazy val cmdInput =
    JesFileInput(ExecParamName, jesCallPaths.script.pathAsString, DefaultPathBuilder.get(jesCallPaths.scriptFilename), workingDisk)
  private lazy val jesCommandLine = s"/bin/bash ${cmdInput.containerPath}"
  private lazy val rcJesOutput = JesFileOutput(returnCodeFilename, returnCodeGcsPath.pathAsString, DefaultPathBuilder.get(returnCodeFilename), workingDisk)

  private lazy val standardParameters = Seq(rcJesOutput)

  private lazy val dockerConfiguration = jesConfiguration.dockerCredentials

  /**
    * How many preemptions have we already seen?
    */
  private var preemptionCount: Option[Int] = None
  /**
    * How many unexpected retries have we already suffered through?
    */
  private var unexpectedRetryCount: Option[Int] = None

  override lazy val retryable: Boolean = preemptible || unexpectedRetryCount.get < maxUnexpectedRetries

  private lazy val preemptible: Boolean = preemptionCount.get < maxPreemption

  override def tryAbort(job: StandardAsyncJob): Unit = {
    Run(job, initializationData.genomics).abort()
  }

  override def requestsAbortAndDiesImmediately: Boolean = true

  override def preStart(): Unit = {
    if(attempt.equals(1)) {
      initializeRetryableCounts()
    }
    else getRetryCounts()
  }

  private def initializeRetryableCounts() = {
    if (maxPreemption > 0) preemptionCount = Some(1)
    else preemptionCount = Some(0)
    unexpectedRetryCount = Some(0)

    tellKvStore(kvJobKey, preemptionCountKey, preemptionCount.get.toString)
    tellKvStore(kvJobKey, unexpectedRetryCountKey, unexpectedRetryCount.get.toString  )
  }

  override def receive: Receive = kvServiceActorReceive orElse pollingActorClientReceive orElse super.receive

  //RUCHI:: remove extra logging
  private def kvServiceActorReceive: Receive = {
    case KvPair(k,Some(v)) if k.key.equals(preemptionCountKey) =>
      jobLogger.info(s"For key $k found the preemption count $v")
      preemptionCount = Some(v.toInt)
    case KvPair(k,Some(v)) if k.key.equals(unexpectedRetryCountKey) =>
      jobLogger.info(s"For key $k found the generic retry count $v")
      unexpectedRetryCount = Some(v.toInt)
    case KvKeyLookupFailed =>
      jobLogger.info(s"Failed to find retry key of $jobTag") //this should never happen?
    case KvFailure => jobLogger.info(s"Failed to look up the retry key of $jobTag")
  }

  private def gcsAuthParameter: Option[JesInput] = {
    if (jesAttributes.auths.gcs.requiresAuthFile || dockerConfiguration.isDefined)
      Option(JesLiteralInput(ExtraConfigParamName, jesCallPaths.gcsAuthFilePath.pathAsString))
    else None
  }

  /**
    * Takes two arrays of remote and local WDL File paths and generates the necessary JesInputs.
    */
  private def jesInputsFromWdlFiles(jesNamePrefix: String,
                                    remotePathArray: Seq[WdlFile],
                                    localPathArray: Seq[WdlFile],
                                    jobDescriptor: BackendJobDescriptor): Iterable[JesInput] = {
    (remotePathArray zip localPathArray zipWithIndex) flatMap {
      case ((remotePath, localPath), index) =>
        Seq(JesFileInput(s"$jesNamePrefix-$index", remotePath.valueString, DefaultPathBuilder.get(localPath.valueString), workingDisk))
    }
  }

  /**
    * Turns WdlFiles into relative paths.  These paths are relative to the working disk
    *
    * relativeLocalizationPath("foo/bar.txt") -> "foo/bar.txt"
    * relativeLocalizationPath("gs://some/bucket/foo.txt") -> "some/bucket/foo.txt"
    */
  private def relativeLocalizationPath(file: WdlFile): WdlFile = {
    getPath(file.value) match {
      case Success(path) => WdlFile(path.pathWithoutScheme, file.isGlob)
      case _ => file
    }
  }

  private[jes] def generateJesInputs(jobDescriptor: BackendJobDescriptor): Set[JesInput] = {

    val writeFunctionFiles = call.task.evaluateFilesFromCommand(jobDescriptor.fullyQualifiedInputs, backendEngineFunctions) map {
      case (expression, file) =>  expression.toWdlString.md5SumShort -> Seq(file)
    }

    /* Collect all WdlFiles from inputs to the call */
    val callInputFiles: Map[FullyQualifiedName, Seq[WdlFile]] = jobDescriptor.fullyQualifiedInputs mapValues { _.collectAsSeq { case w: WdlFile => w } }

    val inputs = (callInputFiles ++ writeFunctionFiles) flatMap {
      case (name, files) => jesInputsFromWdlFiles(name, files, files.map(relativeLocalizationPath), jobDescriptor)
    }

    inputs.toSet
  }

  /**
    * Given a path (relative or absolute), returns a (Path, JesAttachedDisk) tuple where the Path is
    * relative to the AttachedDisk's mount point
    *
    * @throws Exception if the `path` does not live in one of the supplied `disks`
    */
  private def relativePathAndAttachedDisk(path: String, disks: Seq[JesAttachedDisk]): (Path, JesAttachedDisk) = {
    val absolutePath = DefaultPathBuilder.get(path) match {
      case p if !p.isAbsolute => JesWorkingDisk.MountPoint.resolve(p)
      case p => p
    }

    disks.find(d => absolutePath.startsWith(d.mountPoint)) match {
      case Some(disk) => (disk.mountPoint.relativize(absolutePath), disk)
      case None =>
        throw new Exception(s"Absolute path $path doesn't appear to be under any mount points: ${disks.map(_.toString).mkString(", ")}")
    }
  }

  /**
    * If the desired reference name is too long, we don't want to break JES or risk collisions by arbitrary truncation. So,
    * just use a hash. We only do this when needed to give better traceability in the normal case.
    */
  private def makeSafeJesReferenceName(referenceName: String) = {
    if (referenceName.length <= 127) referenceName else referenceName.md5Sum
  }

  private[jes] def generateJesOutputs(jobDescriptor: BackendJobDescriptor): Set[JesFileOutput] = {
    val wdlFileOutputs = call.task.findOutputFiles(jobDescriptor.fullyQualifiedInputs, NoFunctions) map relativeLocalizationPath

    val outputs = wdlFileOutputs.distinct flatMap { wdlFile =>
      wdlFile match {
        case singleFile: WdlSingleFile => List(generateJesSingleFileOutputs(singleFile))
        case globFile: WdlGlobFile => generateJesGlobFileOutputs(globFile)
      }
    }

    outputs.toSet
  }

  private def generateJesSingleFileOutputs(wdlFile: WdlSingleFile): JesFileOutput = {
    val destination = callRootPath.resolve(wdlFile.value.stripPrefix("/")).pathAsString
    val (relpath, disk) = relativePathAndAttachedDisk(wdlFile.value, runtimeAttributes.disks)
    JesFileOutput(makeSafeJesReferenceName(wdlFile.value), destination, relpath, disk)
  }

  private def generateJesGlobFileOutputs(wdlFile: WdlGlobFile): List[JesFileOutput] = {
    val globName = backendEngineFunctions.globName(wdlFile.value)
    val globDirectory = globName + "/"
    val globListFile = globName + ".list"
    val gcsGlobDirectoryDestinationPath = callRootPath.resolve(globDirectory).pathAsString
    val gcsGlobListFileDestinationPath = callRootPath.resolve(globListFile).pathAsString

    val (_, globDirectoryDisk) = relativePathAndAttachedDisk(wdlFile.value, runtimeAttributes.disks)

    // We need both the glob directory and the glob list:
    List(
      // The glob directory:
      JesFileOutput(makeSafeJesReferenceName(globDirectory), gcsGlobDirectoryDestinationPath, DefaultPathBuilder.get(globDirectory + "*"), globDirectoryDisk),
      // The glob list file:
      JesFileOutput(makeSafeJesReferenceName(globListFile), gcsGlobListFileDestinationPath, DefaultPathBuilder.get(globListFile), globDirectoryDisk)
    )
  }

  override lazy val commandDirectory: Path = JesWorkingDisk.MountPoint

  override def commandScriptPreamble: String = {
    if (monitoringOutput.isDefined) {
      s"""|touch $JesMonitoringLogFile
          |chmod u+x $JesMonitoringScript
          |$JesMonitoringScript > $JesMonitoringLogFile &""".stripMargin
    } else ""
  }

  override def globParentDirectory(wdlGlobFile: WdlGlobFile): Path = {
    val (_, disk) = relativePathAndAttachedDisk(wdlGlobFile.value, runtimeAttributes.disks)
    disk.mountPoint
  }

  private def googleProject(descriptor: BackendWorkflowDescriptor): String = {
    descriptor.workflowOptions.getOrElse(WorkflowOptionKeys.GoogleProject, jesAttributes.project)
  }

  private def computeServiceAccount(descriptor: BackendWorkflowDescriptor): String = {
    descriptor.workflowOptions.getOrElse(WorkflowOptionKeys.GoogleComputeServiceAccount, jesAttributes.computeServiceAccount)
  }

  override def isTerminal(runStatus: RunStatus): Boolean = {
    runStatus match {
      case _: TerminalRunStatus => true
      case _ => false
    }
  }

  private def createJesRunPipelineRequest(jesParameters: Seq[JesParameter]): RunPipelineRequest = {
    val runPipelineParameters = Run.makeRunPipelineRequest(
      jobDescriptor = jobDescriptor,
      runtimeAttributes = runtimeAttributes,
      callRootPath = callRootPath.pathAsString,
      commandLine = jesCommandLine,
      logFileName = jesLogFilename,
      jesParameters,
      googleProject(jobDescriptor.workflowDescriptor),
      computeServiceAccount(jobDescriptor.workflowDescriptor),
      preemptible,
      initializationData.genomics
    )
    logger.debug(s"Inputs:\n${stringifyMap(runPipelineParameters.getPipelineArgs.getInputs.asScala.toMap)}")
    logger.debug(s"Outputs:\n${stringifyMap(runPipelineParameters.getPipelineArgs.getOutputs.asScala.toMap)}")
    runPipelineParameters
  }

  override def isFatal(throwable: Throwable): Boolean = super.isFatal(throwable) || isFatalJesException(throwable)

  override def isTransient(throwable: Throwable): Boolean = isTransientJesException(throwable)

  override def executeAsync()(implicit ec: ExecutionContext): Future[ExecutionHandle] = {
    runWithJes(None)
  }

  //RUCHI:: Needs serious Cleanup
  val preemptionCountKey = "PreemptionCount"
  val unexpectedRetryCountKey = "UnexpectedRetryCount"
  val kvJobKey = KvJobKey(jobDescriptor.key.call.fullyQualifiedName, jobDescriptor.key.index, jobDescriptor.key.attempt)
  val futureKvJobKey = KvJobKey(jobDescriptor.key.call.fullyQualifiedName, jobDescriptor.key.index, jobDescriptor.key.attempt + 1)

  def getRetryCounts() = {
    askKvStore(kvJobKey, preemptionCountKey)
    askKvStore(kvJobKey, unexpectedRetryCountKey)
  }

  def tellKvStore(kvJobKey: KvJobKey, key: String, value: String): Unit = {
    val scopedKey = ScopedKey(workflowId, kvJobKey, key)
    val kvValue = Some(value)
    val kvPair = KvPair(scopedKey, kvValue)
    val kvPut = KvPut(kvPair)
    serviceRegistryActor ! kvPut
  }

  def askKvStore(kvJobKey: KvJobKey, key: String): Unit = {
    val scopedKey = ScopedKey(workflowId, kvJobKey, key)
    val kvGet = KvGet(scopedKey)
    serviceRegistryActor ! kvGet
  }

  protected def runWithJes(runIdForResumption: Option[String]): ExecutionHandle = {
    // Force runtimeAttributes to evaluate so we can fail quickly now if we need to:
    Try(runtimeAttributes) match {
      case Success(_) =>
        val command = instantiatedCommand
        val jesInputs: Set[JesInput] = generateJesInputs(jobDescriptor) ++ monitoringScript + cmdInput
        val jesOutputs: Set[JesFileOutput] = generateJesOutputs(jobDescriptor) ++ monitoringOutput
        val withMonitoring = monitoringOutput.isDefined

        uploadCommandScript(command, withMonitoring, backendEngineFunctions.findGlobOutputs(call, jobDescriptor))
        val run: Run = createJesRun(jesParameters, runIdForResumption)

          PendingExecutionHandle(jobDescriptor, StandardAsyncJob(run.runId), Option(run), previousStatus = None)
      case Failure(e) => FailedNonRetryableExecutionHandle(e)
    }
  }

  override def recoverAsync(jobId: StandardAsyncJob)(implicit ec: ExecutionContext): Future[ExecutionHandle] = {
    runWithJes(Option(jobId))
  }

  private def runWithJes(jobForResumption: Option[StandardAsyncJob]): Future[ExecutionHandle] = {

    // Want to force runtimeAttributes to evaluate so we can fail quickly now if we need to:
    def makeRpr: Try[RunPipelineRequest] = Try(runtimeAttributes) flatMap { _ => Try {
      val jesInputs: Set[JesInput] = generateJesInputs(jobDescriptor) ++ monitoringScript + cmdInput
      val jesOutputs: Set[JesFileOutput] = generateJesOutputs(jobDescriptor) ++ monitoringOutput

      val jesParameters = standardParameters ++ gcsAuthParameter ++ jesInputs ++ jesOutputs

      jobPaths.script.writeAsText(commandScriptContents)
      createJesRunPipelineRequest(jesParameters)
    }}

    jobForResumption match {
      case Some(job) =>
        val run = Run(job, initializationData.genomics)
        Future.successful(PendingExecutionHandle(jobDescriptor, job, Option(run), previousStatus = None))
      case None =>
        for {
          rpr <- Future.fromTry(makeRpr)
          runId <- runPipeline(initializationData.genomics, rpr)
          run = Run(runId, initializationData.genomics)
        } yield PendingExecutionHandle(jobDescriptor, runId, Option(run), previousStatus = None)
        jobPaths.script.writeAsText(commandScriptContents)

        val run = createJesRun(jesParameters, runIdForResumption)

        PendingExecutionHandle(jobDescriptor, StandardAsyncJob(run.runId), Option(run), previousStatus = None)
      case Failure(e) => FailedNonRetryableExecutionHandle(e)
    }
  }


  override def pollStatusAsync(handle: JesPendingExecutionHandle)
                              (implicit ec: ExecutionContext): Future[RunStatus] = {
    super[JesStatusRequestClient].pollStatus(handle.runInfo.get)
  }


  override def customPollStatusFailure: PartialFunction[(ExecutionHandle, Exception), ExecutionHandle] = {
    case (oldHandle: JesPendingExecutionHandle@unchecked, e: GoogleJsonResponseException) if e.getStatusCode == 404 =>
      jobLogger.error(s"JES Job ID ${oldHandle.runInfo.get.job} has not been found, failing call")
      FailedNonRetryableExecutionHandle(e)
  }

  override lazy val startMetadataKeyValues: Map[String, Any] = super[JesJobCachingActorHelper].startMetadataKeyValues

  override def getTerminalMetadata(runStatus: RunStatus): Map[String, Any] = {
    runStatus match {
      case terminalRunStatus: TerminalRunStatus =>
        Map(
          JesMetadataKeys.MachineType -> terminalRunStatus.machineType.getOrElse("unknown"),
          JesMetadataKeys.InstanceName -> terminalRunStatus.instanceName.getOrElse("unknown"),
          JesMetadataKeys.Zone -> terminalRunStatus.zone.getOrElse("unknown")
        )
      case unknown => throw new RuntimeException(s"Attempt to get terminal metadata from non terminal status: $unknown")
    }
  }

  override def mapOutputWdlFile(wdlFile: WdlFile): WdlFile = {
    wdlFileToGcsPath(generateJesOutputs(jobDescriptor))(wdlFile)
  }

  private[jes] def wdlFileToGcsPath(jesOutputs: Set[JesFileOutput])(wdlFile: WdlFile): WdlFile = {
    jesOutputs collectFirst {
      case jesOutput if jesOutput.name == makeSafeJesReferenceName(wdlFile.valueString) => WdlFile(jesOutput.gcs)
    } getOrElse wdlFile
  }

  override def isSuccess(runStatus: RunStatus): Boolean = {
    runStatus match {
      case _: RunStatus.Success => true
      case _: RunStatus.Failed => false
      case unknown =>
        throw new RuntimeException("isSuccess not called with RunStatus.Success or RunStatus.Failed. " +
          s"Instead got $unknown")
    }
  }

  override def getTerminalEvents(runStatus: RunStatus): Seq[ExecutionEvent] = {
    runStatus match {
      case successStatus: RunStatus.Success => successStatus.eventList
      case unknown =>
        throw new RuntimeException(s"handleExecutionSuccess not called with RunStatus.Success. Instead got $unknown")
    }
  }

  override def retryEvaluateOutputs(exception: Exception): Boolean = {
    exception match {
      case aggregated: CromwellAggregatedException =>
        aggregated.throwables.collectFirst { case s: SocketTimeoutException => s }.isDefined
      case _ => false
    }
  }

  override def retryEvaluateOutputs(exception: Exception): Boolean = {
    exception match {
      case aggregated: CromwellAggregatedException =>
        aggregated.throwables.collectFirst { case s: SocketTimeoutException => s }.isDefined
      case _ => false
    }
  }

  // FIXME: all mods need a good dose of cleanup & dry
  override def handleExecutionFailure(runStatus: RunStatus,
                             handle: StandardAsyncPendingExecutionHandle,
                             returnCode: Option[Int]): ExecutionHandle = {
    // If one exists, extract the JES error code (not the google RPC) from the error message
    def getJesErrorCode(errorMessage: String): Option[Int] = {
      Try { errorMessage.substring(0, errorMessage.indexOf(':')).toInt } toOption
    }

    val failedStatus: UnsuccessfulRunStatus = runStatus match {
      case failedStatus: RunStatus.Failed => failedStatus
      case preemptedStatus: RunStatus.Preempted => preemptedStatus
      case unknown => throw new RuntimeException(s"handleExecutionFailure not called with RunStatus.Failed or RunStatus.Preempted. Instead got $unknown")
    }

    val prettyPrintedError: String = failedStatus.errorMessage map { e => s" Message: $e" } getOrElse ""
    val jesCode: Option[Int] = failedStatus.errorMessage flatMap getJesErrorCode

    (failedStatus.errorCode, jesCode) match {
      case (GoogleCancelledRpc, None) => AbortedExecutionHandle
      case (GoogleNotFoundRpc, Some(JesFailedToDelocalize)) => FailedNonRetryableExecutionHandle(FailedToDelocalizeFailure(prettyPrintedError, jobTag, Option(jobPaths.stderr)))
      case (GoogleAbortedRpc, Some(JesUnexpectedTermination)) => handleUnexpectedTermination(failedStatus.errorCode, prettyPrintedError, returnCode)
      case (GoogleAbortedRpc, Some(JesPreemption)) => handlePreemption(failedStatus.errorCode, prettyPrintedError, returnCode)
      case _ => FailedNonRetryableExecutionHandle(StandardException(failedStatus.errorCode, prettyPrintedError, jobTag), returnCode)
    }
  }

  private def handleUnexpectedTermination(errorCode: Int, errorMessage: String, jobReturnCode: Option[Int]): ExecutionHandle = {

    val msg = s"Retrying. $errorMessage"

    if (unexpectedRetryCount.get < maxUnexpectedRetries) {

      //Increment unexpected retry count and preemption count stays the same
      tellKvStore(futureKvJobKey, unexpectedRetryCountKey, (unexpectedRetryCount.get + 1).toString)
      tellKvStore(futureKvJobKey, preemptionCountKey, preemptionCount.get.toString )

      FailedRetryableExecutionHandle(StandardException(errorCode, msg, jobTag), jobReturnCode)
    }
    else {
      FailedNonRetryableExecutionHandle(StandardException(errorCode, errorMessage, jobTag), jobReturnCode)
    }
  }

  private def handlePreemption(errorCode: Int, errorMessage: String, jobReturnCode: Option[Int]): ExecutionHandle = {
    import lenthall.numeric.IntegerUtil._

    val taskName = s"${workflowDescriptor.id}:${call.unqualifiedName}"
    val baseMsg = s"Task $taskName was preempted for the ${preemptionCount.get.toOrdinal} time."

    /*
      FIXME: This is wrong! These are both retryable errors, the only difference here is in determining what message
      we're handing back to the user. For preemption even when we've exhausted our retries we want to kick it back
      with a non-preemptible so just hand back a Retryable handle for both cases.

      That means that the "if" is just building the string and we can have the same return value outside of the if
     */
    if (preemptionCount.get < maxPreemption) {

      //Increment preemmption count and unexpectedRetryCount stays the same
      tellKvStore(futureKvJobKey, preemptionCountKey, (preemptionCount.get+1).toString)
      tellKvStore(futureKvJobKey, unexpectedRetryCountKey, unexpectedRetryCount.get.toString )

      val msg = s"""$baseMsg The call will be restarted with another preemptible VM (max preemptible attempts number is $maxPreemption).
                    | Error code $errorCode.$errorMessage""".stripMargin
      FailedRetryableExecutionHandle(StandardException(errorCode, msg, jobTag), jobReturnCode)
    } else {
      val msg = s"""$baseMsg The maximum number of preemptible attempts ($maxPreemption) has been reached.
                   | The call will be restarted with a non-preemptible VM.
                   |Error code $errorCode.$errorMessage)""".stripMargin
      FailedRetryableExecutionHandle(StandardException(errorCode, msg, jobTag), jobReturnCode)
    }
  }

  override def mapCommandLineWdlFile(wdlFile: WdlFile): WdlFile = {
    getPath(wdlFile.valueString) match {
      case Success(gcsPath: GcsPath) =>
        val localPath = workingDisk.mountPoint.resolve(gcsPath.pathWithoutScheme).pathAsString
        WdlFile(localPath, wdlFile.isGlob)
      case _

      => wdlFile
    }
  }
}
