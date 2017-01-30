package cromwell.backend.impl.jes

import cromwell.backend.impl.jes.errors.JesError
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
    def errorMessage: Option[String]
    def errorCode: Int
  }

  case class Success(eventList: Seq[ExecutionEvent], machineType: Option[String], zone: Option[String], instanceName: Option[String], errorMessage: Option[String] = None) extends RunStatus {
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
  }

  //RUCHI:: Added preempted runStatus --> to be added to the list of BackendStatuses
  final case class Preempted(errorCode: Int,
                          errorMessage: Option[String],
                          eventList: Seq[ExecutionEvent],
                          machineType: Option[String],
                          zone: Option[String],
                          instanceName: Option[String]) extends TerminalRunStatus {

    override def toString = "Preempted"
  }
}
