package io.opencubes.sql.delegates

import io.opencubes.sql.ActiveRecord
import io.opencubes.sql.Field
import kotlin.reflect.KClass

interface IReferenceType<T> : IPropertyWithType<T> {
  override val type: IPropertyWithType.Type get() = IPropertyWithType.Type.REFERENCE
  val klass: KClass<out ActiveRecord>
  val field: Field
  var action: DeleteAction

  fun onDelete(action: DeleteAction): IReferenceType<T>
}