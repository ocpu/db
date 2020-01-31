package io.opencubes.db.loaders.sqlite

import io.opencubes.db.IModelDriver
import io.opencubes.db.loaders.IDBLoader
import io.opencubes.db.loaders.IDBLoader.Companion.driverLoader
import io.opencubes.db.sql.UnsupportedDriver

/**
 * A basic SQLite database loader.
 */
class SQLiteLoader : IDBLoader {
  companion object {
    /**
     * The usual prefix for SQLite connections.
     */
    const val PREFIX = "jdbc:sqlite:"
  }
  private val driver = driverLoader.find { it.acceptsURL(PREFIX) } ?: throw UnsupportedDriver("SQLite")
  override fun accepts(dsn: String, properties: Map<String, String>): Boolean = dsn.startsWith("sqlite")
  override fun load(dsn: String, properties: Map<String, String>): IModelDriver =
    SQLiteModelDriver(driver.connect("jdbc:" + dsn.replace(Regex("(?:jdbc:)?(sqlite2?:)"), "$1"), properties.toProperties()))
}
