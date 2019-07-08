package io.opencubes.sql.delegates

import io.opencubes.sql.ActiveRecord
import io.opencubes.sql.Field
import io.opencubes.sql.ICreateSQL
import kotlin.reflect.KProperty

open class DBValueNullable<T> : IPropertyWithType<T?>, ICreateSQL {
  /** The current value */
  private var value: T? = null
  override val type = IPropertyWithType.Type.VALUE

  /***/
  override fun getValue(thisRef: ActiveRecord, property: KProperty<*>): T? = value

  /***/
  override fun setValue(thisRef: ActiveRecord, property: KProperty<*>, value: T?) {
    if (value == null) {
      this.value = null
      return
    }
    if (this.value is Enum<*> && value is String) {
      val new = (this.value as Enum<*>)::class.java.getDeclaredMethod("valueOf").invoke(null, value)
      DBValueNullable<*>::value.set(this, new)
    } else this.value = value
  }

  override fun getCreationSQL(instance: ActiveRecord, property: KProperty<*>): String {
    val primaryKey = if (
      instance.fields.find {
        instance.metadata?.digest?.primaryKeys?.contains(it.property) == true
      } != null || property.name == "id"
    ) " PRIMARY KEY" else ""
    return "${Field.getName(property)} ${Field.getSQLType(property, instance.database)} NULL$primaryKey"
  }
}