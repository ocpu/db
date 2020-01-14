package io.opencubes.db.sql.select

data class SelectItem(val column: String, val table: String?, val `as`: String?, val simple: Boolean = true) {
  infix fun `as`(name: String) = copy(`as` = name)
}
fun String.asSelectItem() = SelectItem(this, null, null)
infix fun String.from(table: String) = SelectItem(this, table, null)
infix fun String.`as`(name: String) = SelectItem(this, null, name)
