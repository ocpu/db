package io.opencubes.db.sql.table

/**
 * Describes a SQL table column / property.
 * @constructor
 * @property name The name of the column.
 * @property type The base type of the column.
 * @property typeParams The params for the type.
 * @property nullable Defines if the column value can be null.
 * @property default A SQL string that describes a value that is used when no value is given.
 * @property autoIncrement Whether or not this column will automatically increment a integer value.
 */
data class SQLTableProperty(
  val name: String,
  val type: String,
  val typeParams: List<String>,
  val nullable: Boolean,
  val default: String?,
  val autoIncrement: Boolean
)
