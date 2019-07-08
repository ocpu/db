package io.opencubes.sql.delegates

enum class DeleteAction(val sql: String) {
  SET_NULL("SET NULL"),
  SET_DEFAULT("SET DEFAULT"),
  CASCADE("CASCADE"),
  RESTRICT("RESTRICT"),
  NO_ACTION("NO ACTION");

  override fun toString() = sql

  companion object {
    fun fromString(string: String): DeleteAction = when (string.toUpperCase()) {
      SET_DEFAULT.sql -> SET_DEFAULT
      SET_NULL.sql -> SET_NULL
      RESTRICT.sql -> RESTRICT
      CASCADE.sql -> CASCADE
      else -> NO_ACTION
    }
  }
}
