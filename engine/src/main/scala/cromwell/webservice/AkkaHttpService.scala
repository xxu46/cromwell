package cromwell.webservice

import akka.actor.ActorRef
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, Multipart}
import akka.http.scaladsl.server.Directives._
import cromwell.engine.backend.BackendConfiguration
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.Multipart.BodyPart
import cromwell.engine.workflow.WorkflowManagerActor
import spray.json.DefaultJsonProtocol._
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import cromwell.core.WorkflowSourceFiles
import cromwell.engine.workflow.workflowstore.WorkflowStoreActor

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

trait AkkaHttpService {
  import cromwell.webservice.AkkaHttpService._

  val workflowManagerActor: ActorRef
  val workflowStoreActor: ActorRef
  val serviceRegistryActor: ActorRef

  implicit val materializer: ActorMaterializer
  implicit val ec: ExecutionContext

  // FIXME: make this bigger and elsewhere?
  val duration = 5.seconds
  implicit val timeout: Timeout = duration

  // FIXME: these should live elsewhere (WorkflowJsonSupport currently)
  implicit val BackendResponseFormat = jsonFormat2(BackendResponse)
  implicit val EngineStatsFormat = jsonFormat2(EngineStatsActor.EngineStats)

  val backendResponse = BackendResponse(BackendConfiguration.AllBackendEntries.map(_.name).sorted, BackendConfiguration.DefaultBackendEntry.name)

  val possibleRoutes =
    path("hello") {
      get {
        complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
      }
    } ~
      path("workflows" / Segment / "backends") { version =>
        get {
          complete(backendResponse)
        }
      } ~
      path("engine" / Segment / "stats") { version =>
        get {
          val stats = workflowManagerActor.ask(WorkflowManagerActor.EngineStatsCommand).mapTo[EngineStatsActor.EngineStats]
          complete(stats)
        }
      } ~
      path("workflows" / Segment) { version =>
        post {
          entity(as[Multipart.FormData]) { shite =>
            val allParts: Future[Map[String, String]] = shite.parts.mapAsync[(String, String)](1) { 
              case b: BodyPart => b.toStrict(duration).map(strict => b.name -> strict.entity.data.utf8String)
            }.runFold(Map.empty[String, String])((map, tuple) => map + tuple)

            onSuccess(allParts) { files =>
              val wdlSource = files("wdlSource")
              val workflowInputs = files.getOrElse("workflowInputs", "{}")
              val workflowOptions = files.getOrElse("workflowOptions", "{}")
              val workflowSourceFiles = WorkflowSourceFiles(wdlSource, workflowInputs, workflowOptions)
              // FIXME: blows up on wdlSource, doesn't check for other inputs
              workflowStoreActor.ask(WorkflowStoreActor.SubmitWorkflow(workflowSourceFiles)).mapTo[WorkflowStoreActor.WorkflowSubmittedToStore] // FIXME: unassigned
              complete("submitted") // FIXME: not the right response
            }
          }
        }
      }

        //        post {
//          //formFields("wdlSource", "workflowInputs".?, "workflowOptions".?) { (wdlSource, workflowInputs, workflowOptions) =>
//          formFields('wdlSource, 'workflowInputs) { (wdlSource, workflowInputs) =>
//            //val workflowSourceFiles = WorkflowSourceFiles(wdlSource, workflowInputs.getOrElse("{}"), workflowOptions.getOrElse("{}"))
//            complete(StatusCodes.Created -> "hi" + workflowInputs)
//            // FIXME: Need this! RequestComplete(StatusCodes.Created, WorkflowSubmitResponse(id.toString, WorkflowSubmitted.toString))
//            //complete(workflowStoreActor.ask(WorkflowStoreActor.SubmitWorkflow(workflowSourceFiles)).mapTo[WorkflowStoreActor.WorkflowSubmittedToStore])
//          }
}

object AkkaHttpService {
  case class BackendResponse(supportedBackends: List[String], defaultBackend: String)
}

