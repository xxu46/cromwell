package cromwell.backend.impl.jes

import cromwell.backend.impl.jes.errors.{JesError, KnownJesError}
import cromwell.core.ExecutionEvent
import cromwell.core.path.Path

sealed trait RunStatus {
  import RunStatus._

  // Could be defined as false for Initializing and true otherwise, but this is more defensive.
  def isRunningOrComplete = this match {
    case Running | _: TerminalRunStatus => true
    case _ => false
  }
}

object RunStatus {
  case object Initializing extends RunStatus
  case object Running extends RunStatus

  sealed trait TerminalRunStatus extends RunStatus {
    def eventList: Seq[ExecutionEvent]
    def machineType: Option[String]
    def zone: Option[String]
    def instanceName: Option[String]
  }

  case class Success(eventList: Seq[ExecutionEvent], machineType: Option[String], zone: Option[String], instanceName: Option[String]) extends TerminalRunStatus {
    override def toString = "Success"
  }

  final case class Failed(errorCode: Int,
                          errorMessage: Option[String],
                          eventList: Seq[ExecutionEvent],
                          machineType: Option[String],
                          zone: Option[String],
                          instanceName: Option[String]) extends TerminalRunStatus {
    // Don't want to include errorMessage or code in the snappy status toString:
    override def toString = "Failed"
    def toJesError: Option[KnownJesError] = KnownJesError.fromFailedStatus(this)

    def toException(jobTag: String, stderrPath: Option[Path]): Exception = {
      toJesError map { _.toException(errorMessage.getOrElse(""), jobTag, stderrPath) } getOrElse unknownError(jobTag)
    }

    private def unknownError(jobTag: String): RuntimeException = {
      val baseMessage = s"Task $jobTag failed: error code $errorCode."
      val message = errorMessage map { e => s"$baseMessage Message: $e" } getOrElse baseMessage
      new RuntimeException(message)
    }
  }
}
