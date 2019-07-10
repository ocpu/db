package io.opencubes.sql.delegates

import io.opencubes.sql.ActiveRecord
import kotlin.properties.ReadWriteProperty

/**
 * A interface that is the base for all properties that will result
 * in a column for a table.
 */
interface IPropertyWithType<T> : ReadWriteProperty<ActiveRecord, T> {
  /**
   * The type that represents the property's use; as a value or a reference.
   */
  val type: Type

  /**
   * Type of a property.
   */
  enum class Type {
    /**
     * Usage as a regular value.
     */
    VALUE,
    /**
     * Usage as a reference to a different object.
     *
     * Usually extends the [IReferenceType] interface.
     */
    REFERENCE
  }
}
