package io.opencubes.db.sql

/**
 * A exception that indicate that the dsn string specified has no driver that can handle it.
 *
 * @param name The name of database.
 */
class UnsupportedDriver(name: String) : Exception("There is no driver for $name")
