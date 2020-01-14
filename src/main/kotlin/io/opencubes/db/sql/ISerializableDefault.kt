package io.opencubes.db.sql

import java.util.function.Supplier

/**
 * Create a object or singleton that can be used to provide a default value for
 * a column in the database and in instantiation.
 *
 * An example is the `current_timestamp` function in most if not all databases.
 * Therefore do we have this wrapper over a [Supplier] to provide a function
 * that can give a database representation of the dynamic value in SQL.
 *
 * @see io.opencubes.db.sql.CurrentTimestamp
 */
interface ISerializableDefault<T> : Supplier<T> {
  /**
   * Get a new value.
   */
  override fun get(): T
  /**
   * The SQL serialized function the value it representing.
   */
  fun serialize(): String?
}
