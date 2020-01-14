package io.opencubes.db.sql

import java.sql.Driver
import java.util.*

interface IDBLoader {
  val driver: Driver
  fun accepts(dsn: String, properties: Map<String, String>): Boolean
  fun load(dsn: String, properties: Map<String, String>): ISQLModelDriver

  companion object {
    @JvmField
    val driverLoader: ServiceLoader<Driver> = ServiceLoader.load(Driver::class.java)
  }
}
