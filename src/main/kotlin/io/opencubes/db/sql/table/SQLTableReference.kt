package io.opencubes.db.sql.table

/**
 * A class describing a table reference in a [SQLForeignKey].
 * @constructor
 * @property table The name of the table this reference references.
 * @property properties The properties that are referenced by this [SQLForeignKey] reference.
 */
data class SQLTableReference(val table: String, val properties: List<String>)
