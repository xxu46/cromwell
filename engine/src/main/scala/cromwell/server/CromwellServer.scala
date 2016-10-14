package cromwell.server


import akka.actor.Props
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.util.Timeout
import cromwell.webservice.AkkaHttpService

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

// Note that as per the language specification, this is instantiated lazily and only used when necessary (i.e. server mode)
object CromwellServer {
  implicit val timeout = Timeout(5.seconds)
  import scala.concurrent.ExecutionContext.Implicits.global


  def run(cromwellSystem: CromwellSystem): Future[Any] = {
    // FIXME: implicit?
    implicit val actorSystem = cromwellSystem.actorSystem
    actorSystem.actorOf(CromwellServerActor.props(cromwellSystem), "cromwell-service")
    Future { Await.result(actorSystem.whenTerminated, Duration.Inf) }
  }
}

class CromwellServerActor(cromwellSystem: CromwellSystem) extends CromwellRootActor with AkkaHttpService { // with CromwellApiService with SwaggerService {
//  implicit def executionContext = actorRefFactory.dispatcher
//
//  override def actorRefFactory = context
//  override def receive = handleTimeouts orElse runRoute(possibleRoutes)
//
//  val possibleRoutes = workflowRoutes.wrapped("api", config.getBooleanOr("api.routeUnwrapped")) ~ swaggerUiResourceRoute
//  val timeoutError = APIResponse.error(new TimeoutException("The server was not able to produce a timely response to your request.")).toJson.prettyPrint
//
//  def handleTimeouts: Receive = {
//    case Timedout(_: HttpRequest) =>
//      sender() ! HttpResponse(StatusCodes.InternalServerError, HttpEntity(ContentType(MediaTypes.`application/json`), timeoutError))
//  }

  implicit val actorSystem = context.system
  override implicit val ec = context.dispatcher
  override implicit val materializer = ActorMaterializer()

  val webserviceConf = cromwellSystem.conf.getConfig("webservice")
  val interface = webserviceConf.getString("interface")
  val port = webserviceConf.getInt("port")

  Http().bindAndHandle(possibleRoutes, interface, port) onComplete {
    case Success(_) => actorSystem.log.info("Cromwell service started...")
    case Failure(regerts) =>
      /*
        TODO:
        If/when CromwellServer behaves like a better async citizen, we may be less paranoid about our async log messages
        not appearing due to the actor system shutdown. For now, synchronously print to the stderr so that the user has
        some idea of why the server failed to start up.
      */
      Console.err.println(s"Binding failed interface $interface port $port")
      regerts.printStackTrace(Console.err)
      cromwellSystem.shutdownActorSystem()

  }

  /*
    During testing it looked like not explicitly invoking the WMA in order to evaluate all of the lazy actors in
    CromwellRootActor would lead to weird timing issues the first time it was invoked organically
   */
  workflowManagerActor
}

object CromwellServerActor {
  def props(cromwellSystem: CromwellSystem): Props = {
    Props(new CromwellServerActor(cromwellSystem))
  }
}
