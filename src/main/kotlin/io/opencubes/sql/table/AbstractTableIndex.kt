package io.opencubes.sql.table

/**
 * A abstraction of a table index be it the primary key, a unique index, or a normal index.
 */
data class AbstractTableIndex(
  /**
   * The name of this index.
   */
  val name: String,
  /**
   * The list of columns this index uses.
   */
  val properties: List<String>
)
