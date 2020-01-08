package io.opencubes.sql.delegates

import io.opencubes.sql.ActiveRecord
import io.opencubes.sql.Field
import io.opencubes.sql.ICreateSQL
import io.opencubes.sql.IInjectable
import io.opencubes.sql.select.CompiledSelectQuery
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1

/**
 * A basic reference to a different object/table.
 */
open class DBReference<T : ActiveRecord>(override val kClass: KClass<out ActiveRecord>, field: KProperty1<T, *>?) : IReferenceType<T?>, IInjectable, ICreateSQL {
  /** A instance of the referenced table. */
  val instance by lazy { ActiveRecord.getShallowInstance(kClass) }
  /** The current value */
  var value: Any? = null
  override val field by lazy { Field(field) ?: instance.idField }
  override var action: DeleteAction = DeleteAction.NO_ACTION
  private val select: CompiledSelectQuery by lazy {
    instance.database
      .select(*instance.fields.map { "t1.${it.name}" }.toTypedArray())
      .from("${instance.table} t1")
      .where("t1.${this.field.name} = ?")
      .limit(1)
      .compile()
  }

  /** @see kotlin.properties.ReadWriteProperty.getValue */
  @Suppress("UNCHECKED_CAST")
  override fun getValue(thisRef: ActiveRecord, property: KProperty<*>): T = select.execute(value).fetchInto(kClass)!! as T

  /** @see kotlin.properties.ReadWriteProperty.setValue */
  override fun setValue(thisRef: ActiveRecord, property: KProperty<*>, value: T?) {
    if (value == null) this.value = null
    else this.value = instance.idField.getValue(value) ?: throw NullPointerException("value was null")
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
    "${Field.getName(property)} ${Field.getSQLType(property, instance.database)} NOT NULL"
}
