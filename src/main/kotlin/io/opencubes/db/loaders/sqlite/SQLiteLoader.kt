package io.opencubes.db.loaders.sqlite

import io.opencubes.db.IModelDriver
import io.opencubes.db.loaders.IDBLoader
import io.opencubes.db.loaders.IDBLoader.Companion.driverLoader
import io.opencubes.db.sql.UnsupportedDriver

class SQLiteLoader : IDBLoader {
  companion object {
    const val PREFIX = "jdbc:sqlite:"
  }
  override val driver = driverLoader.find { it.acceptsURL(PREFIX) } ?: throw UnsupportedDriver("SQLite")
  override fun accepts(dsn: String, properties: Map<String, String>): Boolean = dsn.startsWith("sqlite")
  override fun load(dsn: String, properties: Map<String, String>): IModelDriver =
    SQLiteModelDriver(dsn.replace(Regex("(?:jdbc:)?sqlite2?:"), ""))
}
