package cromwell.webservice

import akka.actor.ActorRef
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.server.Directives._
import cromwell.engine.backend.BackendConfiguration
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import cromwell.engine.workflow.WorkflowManagerActor
import spray.json.DefaultJsonProtocol._
import akka.pattern.ask
import akka.util.Timeout
import cromwell.core
import cromwell.core.WorkflowSourceFiles
import cromwell.engine.workflow.workflowstore.WorkflowStoreActor

import scala.concurrent.duration._

trait AkkaHttpService {
  import cromwell.webservice.AkkaHttpService._

  val workflowManagerActor: ActorRef
  val workflowStoreActor: ActorRef
  val serviceRegistryActor: ActorRef

  // FIXME: make this bigger and elsewhere?
  implicit val timeout: Timeout = 5.seconds

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
          formFields('color, 'age.as[Int]) { (color, age) =>
            complete(s"The color is '$color' and the age ten years ago was ${age - 10}")
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
}

object AkkaHttpService {
  case class BackendResponse(supportedBackends: List[String], defaultBackend: String)
}

