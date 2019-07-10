package io.opencubes.sql.table

import io.opencubes.sql.delegates.DeleteAction

/**
 * A abstract foreign key for a table.
 */
data class AbstractForeignKey(
  /**
   * The name for the foreign key.
   */
  val name: String,
  /**
   * The internal column that this foreign key uses
   * to reference the other row.
   */
  val tableProperty: String,
  /**
   * The table this foreign key refers to.
   */
  val foreignTable: String,
  /**
   * The column this foreign key refers to in the specified [foreign table][foreignTable]
   */
  val foreignProperty: String,
  /**
   * What this object will do to the references when it is deleted
   */
  val onDeleteAction: DeleteAction
)
