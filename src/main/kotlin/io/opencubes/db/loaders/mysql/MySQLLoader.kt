package io.opencubes.db.loaders.mysql

import io.opencubes.db.sql.IDBLoader
import io.opencubes.db.sql.IDBLoader.Companion.driverLoader
import io.opencubes.db.sql.ISQLModelDriver
import io.opencubes.db.sql.UnsupportedDriver

class MySQLLoader : IDBLoader {
  override val driver = driverLoader.find { it.acceptsURL("jdbc:mysql://") } ?: throw UnsupportedDriver("MySQL")
  override fun accepts(dsn: String, properties: Map<String, String>): Boolean = dsn.startsWith("mysql")
  override fun load(dsn: String, properties: Map<String, String>): ISQLModelDriver =
    MySQLModelDriver(driver.connect("jdbc:" + dsn.removePrefix("jdbc:"), properties.toProperties()) ?: throw IllegalStateException("connection failed"))
}
