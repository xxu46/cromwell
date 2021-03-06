package cromwell.engine.workflow.lifecycle.execution

import cromwell.backend.BackendJobDescriptorKey
import cromwell.core.ExecutionStatus._
import cromwell.core.{CallKey, ExecutionStatus, JobKey}
import cromwell.engine.workflow.lifecycle.execution.ExecutionStore.ExecutionStoreEntry
import cromwell.engine.workflow.lifecycle.execution.WorkflowExecutionActor.{apply => _, _}
import wdl4s._


object ExecutionStore {
  def empty = ExecutionStore(Map.empty[JobKey, ExecutionStatus])
  type ExecutionStoreEntry = (JobKey, ExecutionStatus)
  def apply(workflow: Workflow, workflowCoercedInputs: WorkflowCoercedInputs) = {
    // Only add direct children to the store, the rest is dynamically created when necessary
    val keys = workflow.children map {
      case call: TaskCall => Option(BackendJobDescriptorKey(call, None, 1))
      case call: WorkflowCall => Option(SubWorkflowKey(call, None, 1))
      case scatter: Scatter => Option(ScatterKey(scatter))
      case conditional: If => Option(ConditionalKey(conditional, None))
      case declaration: Declaration => Option(DeclarationKey(declaration, None, workflowCoercedInputs))
      case _ => None
    }

    new ExecutionStore(keys.flatten.map(_ -> NotStarted).toMap)
  }
}

case class ExecutionStore(private val store: Map[JobKey, ExecutionStatus]) {

  // This is hugely ripe for optimization, if it becomes a bottleneck
  def isBypassedConditional(jobKey: JobKey, conditional: If): Boolean = {
    store.exists {
      case (key: ConditionalKey, status) =>
        key.scope.fullyQualifiedName.equals(conditional.fullyQualifiedName) &&
          key.index.equals(jobKey.index) &&
          status.equals(ExecutionStatus.Bypassed)
      case _ => false
    }
  }

  def hasActiveJob: Boolean = {
    def upstreamFailed(scope: Scope): Boolean = scope match {
      case node: GraphNode => node.upstreamAncestry exists hasFailedScope
    }

    store exists {
      case (jobKey, jobStatus) => (jobStatus == NotStarted && !upstreamFailed(jobKey.scope)) || jobStatus == QueuedInCromwell || jobStatus == Starting || jobStatus == Running
    }
  }

  def jobStatus(jobKey: JobKey): Option[ExecutionStatus] = store.get(jobKey)

  def unstartedJobs: List[BackendJobDescriptorKey] = {
    store.collect({case (k: BackendJobDescriptorKey, status) if status != ExecutionStatus.NotStarted => k }).toList
  }

  private def hasFailedScope(s: GraphNode): Boolean = {
    store.exists({ case (key, status) => status == Failed && key.scope == s })
  }

  def hasFailedJob: Boolean = {
    store.values.exists(_ == ExecutionStatus.Failed)
  }

  override def toString = store.map { case (j, s) => s"$j -> $s" } mkString System.lineSeparator()

  def add(values: Map[JobKey, ExecutionStatus]) = this.copy(store = store ++ values)

  // Convert the store to a `List` before `collect`ing to sidestep expensive and pointless hashing of `Scope`s when
  // assembling the result.
  def runnableScopes = store.toList collect { case entry if isRunnable(entry) => entry._1 }

  private def isRunnable(entry: ExecutionStoreEntry) = {
    entry match {
      case (key, ExecutionStatus.NotStarted) => arePrerequisitesDone(key)
      case _ => false
    }
  }

  def findCompletedShardsForOutput(key: CollectorKey): List[JobKey] = store.toList collect {
    case (k: CallKey, v) if v.isDoneOrBypassed && k.scope == key.scope && k.isShard => k
    case (k: DynamicDeclarationKey, v) if v.isDoneOrBypassed && k.scope == key.scope && k.isShard => k
  }

  // Just used to decide whether a collector can be run. In case the shard entries haven't been populated into the
  // execution store yet.
  case class TempJobKey(scope: Scope with GraphNode, index: Option[Int]) extends JobKey {
    // If these are ever used, we've done something wrong...
    override def attempt: Int = throw new NotImplementedError("We've done something wrong.")
    override def tag: String = throw new NotImplementedError("We've done something wrong.")
  }
  def emulateShardEntries(key: CollectorKey): List[JobKey] = {
    (0 until key.scatterWidth).toList flatMap { i => key.scope match {
      case c: Call => List(TempJobKey(c, Option(i)))
      case d: Declaration => List(TempJobKey(d, Option(i)))
      case _ => throw new RuntimeException("Don't collect that.")
    }}
  }

  private def arePrerequisitesDone(key: JobKey): Boolean = {
    val upstream = key.scope.upstream collect {
      case n: Call => upstreamEntry(key, n)
      case n: Scatter => upstreamEntry(key, n)
      case n: Declaration => upstreamEntry(key, n)
    }

    val shardEntriesForCollector: List[JobKey] = key match {
      case collector: CollectorKey => emulateShardEntries(collector)
      case _ => Nil
    }

    /*
      * We need to use an "exists" in this case because the execution store can contain a job attempt with the same
      * fqn and index but a preempted status. We wouldn't want that preempted attempt to count against the completion
      * of the scatter block.
      */
    def isDone(e: JobKey): Boolean = store exists {
      case (k, s) => k.scope.fullyQualifiedName == e.scope.fullyQualifiedName && k.index == e.index && s.isDoneOrBypassed
    }

    val dependencies = upstream.flatten.map(_._1) ++ shardEntriesForCollector
    val dependenciesResolved = dependencies forall { isDone }

    /*
      * We need to make sure that all prerequisiteScopes have been resolved to some entry before going forward.
      * If a scope cannot be resolved it may be because it is in a scatter that has not been populated yet,
      * therefore there is no entry in the executionStore for this scope.
      * If that's the case this prerequisiteScope has not been run yet, hence the (upstream forall {_.nonEmpty})
      */
    (upstream forall { _.nonEmpty }) && dependenciesResolved
  }

  private def upstreamEntry(entry: JobKey, prerequisiteScope: Scope): Option[ExecutionStoreEntry] = {
    prerequisiteScope.closestCommonAncestor(entry.scope) match {
      /*
        * If this entry refers to a Scope which has a common ancestor with prerequisiteScope
        * and that common ancestor is a Scatter block, then find the shard with the same index
        * as 'entry'.  In other words, if you're in the same scatter block as your pre-requisite
        * scope, then depend on the shard (with same index).
        *
        * NOTE: this algorithm was designed for ONE-LEVEL of scattering and probably does not
        * work as-is for nested scatter blocks
        */
      case Some(ancestor: Scatter) =>
        store find {
          case (k, _) => k.scope == prerequisiteScope && k.index == entry.index
        }

      /*
        * Otherwise, simply refer to the collector entry.  This means that 'entry' depends
        * on every shard of the pre-requisite scope to finish.
        */
      case _ =>
        store find {
          case (k, _) => k.scope == prerequisiteScope && k.index.isEmpty
        }
    }
  }
}
