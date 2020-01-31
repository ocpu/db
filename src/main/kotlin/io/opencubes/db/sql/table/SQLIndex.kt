package io.opencubes.db.sql.table

/**
 * A class that describe a single index.
 * @constructor
 * @property name The name of the index.
 * @property properties The properties this index includes.
 */
data class SQLIndex(val name: String?, val properties: List<String>)
