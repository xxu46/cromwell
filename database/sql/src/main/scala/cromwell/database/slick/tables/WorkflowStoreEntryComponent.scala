package cromwell.database.slick.tables

import java.sql.{Blob, Clob, Timestamp}

import cromwell.database.sql.tables.WorkflowStoreEntry

trait WorkflowStoreEntryComponent {

  this: DriverComponent =>

  import driver.api._

  class WorkflowStoreEntries(tag: Tag) extends Table[WorkflowStoreEntry](tag, "WORKFLOW_STORE_ENTRY") {
    def workflowStoreEntryId = column[Int]("WORKFLOW_STORE_ENTRY_ID", O.PrimaryKey, O.AutoInc)

    def workflowExecutionUuid = column[String]("WORKFLOW_EXECUTION_UUID")

    def workflowDefinition = column[Option[Clob]]("WORKFLOW_DEFINITION")

    def workflowInputs = column[Option[Clob]]("WORKFLOW_INPUTS")

    def workflowOptions = column[Option[Clob]]("WORKFLOW_OPTIONS")

    def customLabels = column[Option[Clob]]("CUSTOM_LABELS")

    def workflowState = column[String]("WORKFLOW_STATE", O.Length(15))

    def submissionTime = column[Timestamp]("SUBMISSION_TIME")

    def importsZip = column[Option[Blob]]("IMPORTS_ZIP")

    override def * = (workflowExecutionUuid, workflowDefinition, workflowInputs, workflowOptions, workflowState,
      submissionTime, importsZip, customLabels, workflowStoreEntryId.?) <> (WorkflowStoreEntry.tupled, WorkflowStoreEntry.unapply)

    def ucWorkflowStoreEntryWeu = index("UC_WORKFLOW_STORE_ENTRY_WEU", workflowExecutionUuid, unique = true)

    def ixWorkflowStoreEntryWs = index("IX_WORKFLOW_STORE_ENTRY_WS", workflowState, unique = false)
  }

  protected val workflowStoreEntries = TableQuery[WorkflowStoreEntries]

  val workflowStoreEntryIdsAutoInc = workflowStoreEntries returning workflowStoreEntries.map(_.workflowStoreEntryId)

  /**
    * Useful for finding the workflow store for a given workflow execution UUID
    */
  val workflowStoreEntriesForWorkflowExecutionUuid = Compiled(
    (workflowExecutionUuid: Rep[String]) => for {
      workflowStoreEntry <- workflowStoreEntries
      if workflowStoreEntry.workflowExecutionUuid === workflowExecutionUuid
    } yield workflowStoreEntry
  )

  /**
    * Useful for selecting workflow stores with a given state.
    */
  val workflowStoreEntriesForWorkflowState = Compiled(
    (workflowState: Rep[String], limit: ConstColumn[Long]) => {
      val query = for {
        workflowStoreEntryRow <- workflowStoreEntries
        if workflowStoreEntryRow.workflowState === workflowState
      } yield workflowStoreEntryRow
      query.sortBy(_.submissionTime.asc).take(limit)
    }
  )

  /**
    * Useful for updating state for all entries matching a given UUID
    */
  val workflowStateForWorkflowExecutionUuid = Compiled(
    (workflowExecutionUuid: Rep[String]) => for {
      workflowStoreEntry <- workflowStoreEntries
      if workflowStoreEntry.workflowExecutionUuid === workflowExecutionUuid
    } yield workflowStoreEntry.workflowState
  )

  /**
    * Useful for updating state for all entries matching a given state
    */
  val workflowStateForWorkflowState = Compiled(
    (workflowState: Rep[String]) => for {
      workflowStoreEntry <- workflowStoreEntries
      if workflowStoreEntry.workflowState === workflowState
    } yield workflowStoreEntry.workflowState
  )
}
