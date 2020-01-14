package io.opencubes.db.sql.table

data class SQLTableProperty(
  val name: String,
  val type: String,
  val typeParams: List<String>,
  val nullable: Boolean,
  val default: String?,
  val autoIncrement: Boolean
)
