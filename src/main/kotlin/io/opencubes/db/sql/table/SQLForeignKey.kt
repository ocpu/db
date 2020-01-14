package io.opencubes.db.sql.table

import io.opencubes.db.ForeignKeyAction

data class SQLForeignKey(
  val name: String?,
  val properties: List<String>,
  val reference: SQLTableReference,
  val deleteAction: ForeignKeyAction,
  val changeAction: ForeignKeyAction
)
