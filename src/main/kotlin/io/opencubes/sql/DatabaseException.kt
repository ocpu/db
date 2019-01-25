package io.opencubes.sql

import java.sql.SQLException

/**
 * A database exception
 */
class DatabaseException(e: SQLException, sql: String) : Exception("SQL error: $sql", e)
