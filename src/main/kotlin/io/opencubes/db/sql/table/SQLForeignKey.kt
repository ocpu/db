package io.opencubes.db.sql.table

import io.opencubes.db.ForeignKeyAction

/**
 * A class that describes a foreign constraint in SQL.
 * @constructor
 * @property name The name of the constraint.
 * @property properties The properties from the table that has them that describe this constraint.
 * @property reference The reference details for the other table.
 * @property deleteAction Specifies what will happen when the referenced value(s) is / are deleted.
 * @property changeAction Specifies what will happen when the referenced value(s) is / are modified.
 */
data class SQLForeignKey(
  val name: String?,
  val properties: List<String>,
  val reference: SQLTableReference,
  val deleteAction: ForeignKeyAction,
  val changeAction: ForeignKeyAction
)
