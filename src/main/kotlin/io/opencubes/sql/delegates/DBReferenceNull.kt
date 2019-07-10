package io.opencubes.sql.delegates

import io.opencubes.sql.ActiveRecord
import io.opencubes.sql.Field
import io.opencubes.sql.ICreateSQL
import io.opencubes.sql.IInjectable
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1

/**
 * A basic reference to a different object/table that can be null.
 */
open class DBReferenceNull<T : ActiveRecord>(override val kClass: KClass<out ActiveRecord>, field: KProperty1<T, *>?) : IReferenceType<T?>, IInjectable, ICreateSQL {
  /** A instance of the referenced table. */
  val instance by lazy { ActiveRecord.getShallowInstance(kClass) }
  /** The current value */
  var value: Any? = null
  override val field by lazy { Field(field) ?: instance.idField }
  override var action: DeleteAction = DeleteAction.SET_NULL
  private val select by lazy {
    instance.database
      .select(*instance.fields.map { "t1.${it.name}" }.toTypedArray())
      .from("${instance.table} t1")
      .where("t1.${this.field.name} = ?")
      .limit(1)
      .compile()
  }

  /** @see kotlin.properties.ReadWriteProperty.getValue */
  @Suppress("UNCHECKED_CAST")
  override fun getValue(thisRef: ActiveRecord, property: KProperty<*>): T? =
    if (value == null) null else select.execute(value).fetchInto(kClass) as T?

  /** @see kotlin.properties.ReadWriteProperty.setValue */
  override fun setValue(thisRef: ActiveRecord, property: KProperty<*>, value: T?) {
    if (value == null)
      this.value = null
    else
      this.value = field.getValue(value)
  }

  override fun onDelete(action: DeleteAction): IReferenceType<T?> {
    this.action = action
    return this
  }

  override fun inject(value: Any?) {
    if (value is ActiveRecord) {
      this.value = value.idField.getValue(value)
    } else this.value = value
  }

  override fun getCreationSQL(instance: ActiveRecord, property: KProperty<*>): String =
    "${Field.getName(property)} ${Field.getSQLType(property, instance.database)}"
}
