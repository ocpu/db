package io.opencubes.sql.table

/**
 * A abstract way to represent a table property.
 */
data class AbstractTableProperty(
  /**
   * The name of the column.
   */
  val name: String,
  /**
   * The type of the column.
   */
  val type: String,
  /**
   * Is this column nullable.
   */
  val nullable: Boolean
)
