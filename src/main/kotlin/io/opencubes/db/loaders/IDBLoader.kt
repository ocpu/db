package io.opencubes.db.loaders

import io.opencubes.db.IModelDriver
import java.sql.Driver
import java.util.*

/**
 * Describes a generic loader for a database connector for use with [IModelDriver].
 */
interface IDBLoader {
  /**
   * Choose what connections this loader supports.
   */
  fun accepts(dsn: String, properties: Map<String, String>): Boolean

  /**
   * Create a new connection for the specified dsn and properties. [accepts] has been
   * called before this point.
   */
  fun load(dsn: String, properties: Map<String, String>): IModelDriver

  companion object {
    /**
     * The service loader for this interface.
     */
    @JvmField
    val driverLoader: ServiceLoader<Driver> = ServiceLoader.load(Driver::class.java)
  }
}
