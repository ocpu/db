package io.opencubes.db.sql

import java.sql.SQLException

/**
 * A database exception for when anything bad happens in a query to the database.
 *
 * This exception will with high likelihood have the sql that threw the exception.
 */
class ConnectionException(
  e: SQLException,
  /**
   * The sql that threw the exception.
   */
  val sql: String?
) : Exception("SQL error: ${sql ?: "[Unknown]"}", e)
