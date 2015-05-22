package cromwell

import java.util.UUID

/**
 * ==Cromwell Execution Engine==
 *
 * Given a WDL file and a backend to execute on, this package provides an API to launch a workflow
 * and query its progress.
 *
 * Internally, this package is built on top of [[cromwell.binding]].
 */

package object engine {
  type WorkflowId = UUID

  sealed trait WorkflowState
  case object WorkflowSubmitted extends WorkflowState
  case object WorkflowRunning extends WorkflowState
  case object WorkflowFailed extends WorkflowState
  case object WorkflowSucceeded extends WorkflowState
}
