package io.opencubes.sql.delegates

import io.opencubes.sql.ActiveRecord
import kotlin.reflect.KClass

/**
 * A grouping interface representing the properties that reference
 * other tables.
 */
interface ITableReference<T : ActiveRecord> {
  /**
   * The referenced table class.
   */
  val kClass: KClass<T>
}
