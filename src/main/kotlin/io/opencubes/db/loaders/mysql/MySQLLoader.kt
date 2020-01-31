package io.opencubes.db.loaders.mysql

import io.opencubes.db.IModelDriver
import io.opencubes.db.loaders.IDBLoader
import io.opencubes.db.loaders.IDBLoader.Companion.driverLoader
import io.opencubes.db.sql.UnsupportedDriver

/**
 * A basic MySQL database loader.
 */
class MySQLLoader : IDBLoader {
  private val driver = driverLoader.find { it.acceptsURL("jdbc:mysql://") } ?: throw UnsupportedDriver("MySQL")
  override fun accepts(dsn: String, properties: Map<String, String>): Boolean = dsn.startsWith("mysql")
  override fun load(dsn: String, properties: Map<String, String>): IModelDriver =
    MySQLModelDriver(driver.connect("jdbc:" + dsn.removePrefix("jdbc:"), properties.toProperties()) ?: throw IllegalStateException("connection failed"))
}
