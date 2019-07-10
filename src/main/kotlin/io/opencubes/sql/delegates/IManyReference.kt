package io.opencubes.sql.delegates

import io.opencubes.sql.ActiveRecord
import kotlin.reflect.KClass

/**
 * A grouping interface to have a common interface to creating
 * properties referencing link tables that hold links.
 */
interface IManyReference<T : ActiveRecord> : ITableReference<T> {
  /**
   * A possibly explicit link table name.
   */
  val table: String?
  /**
   * A possibly explicit name of the column this property will
   * have as the primary one.
   */
  val key: String?
  /**
   * A possibly explicit name of the column this property will
   * have as the reference.
   */
  val referenceKey: String?
}
