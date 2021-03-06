package cromwell.core.callcaching.docker.registryv2.flows.gcr

import akka.stream.ActorMaterializer
import cromwell.core.callcaching.docker.registryv2.DockerRegistryV2AbstractFlow.HttpDockerFlow

import scala.concurrent.ExecutionContext

private class GcrUsFlow(httpClientFlow: HttpDockerFlow)(implicit ec: ExecutionContext, materializer: ActorMaterializer) extends GcrAbstractFlow(httpClientFlow, "us.gcr.io")
