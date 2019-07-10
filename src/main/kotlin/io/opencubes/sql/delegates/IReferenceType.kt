package io.opencubes.sql.delegates

import io.opencubes.sql.ActiveRecord
import io.opencubes.sql.Field
import kotlin.reflect.KClass

/**
 * A group interface representing a property reference to a different table.
 */
interface IReferenceType<T> : IPropertyWithType<T> {
  override val type: IPropertyWithType.Type get() = IPropertyWithType.Type.REFERENCE
  /**
   * The [KClass] representing the referenced class.
   */
  val kClass: KClass<out ActiveRecord>
  /**
   * The field this property is referencing from a table.
   */
  val field: Field
  /**
   * The action that will be taken when the object using this
   * property is deleted.
   */
  val action: DeleteAction
  // TODO: Add on update action?

  /**
   * Set the action that will be taken when this object is
   * deleted.
   */
  fun onDelete(action: DeleteAction): IReferenceType<T>
}
