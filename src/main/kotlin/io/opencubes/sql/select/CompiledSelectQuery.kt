package io.opencubes.sql.select

import io.opencubes.sql.Database
import io.opencubes.sql.DatabaseException
import java.sql.SQLException

class CompiledSelectQuery(private val db: Database, val sql: String) {
  private val statement = try {
    db.connection.prepareStatement(sql)
  } catch (e: SQLException) {
    throw DatabaseException(e, sql)
  }
  /**
   * Execute the built query with the [values].
   */
  fun execute(values: List<Any?>) = FetchableResult(db.execute(statement, *values.toTypedArray()))

  /**
   * Execute the built query with the [values].
   */
  fun execute(vararg values: Any?) = FetchableResult(db.execute(statement, *values))
}
