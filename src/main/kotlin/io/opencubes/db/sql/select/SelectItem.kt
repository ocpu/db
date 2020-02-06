package io.opencubes.db.sql.select

import io.opencubes.db.sqlEscape

data class SelectItem(val column: String, val table: String?, val `as`: String?, val simple: Boolean = true) {
  infix fun `as`(name: String) = copy(`as` = name)

  override fun toString(): String {
    if (!simple) checkNotNull(`as`) { "Complex SelectItem did not provide a alias" }
    return `as`?.sqlEscape
      ?: if (table != null) "${table.sqlEscape}.${column.sqlEscape}"
      else column.sqlEscape
  }
}
fun String.asSelectItem() = SelectItem(this, null, null)
infix fun String.from(table: String) = SelectItem(this, table, null)
infix fun String.`as`(name: String) = SelectItem(this, null, name)
