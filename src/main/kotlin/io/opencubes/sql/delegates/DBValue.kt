package io.opencubes.sql.delegates

import io.opencubes.sql.ActiveRecord
import io.opencubes.sql.Field
import io.opencubes.sql.ICreateSQL
import io.opencubes.sql.IInjectable
import java.sql.Date
import java.sql.Time
import java.sql.Timestamp
import java.text.SimpleDateFormat
import kotlin.reflect.KProperty

/**
 * A basic value column with a value.
 *
 * @param valueClass The class the value has.
 * @param default The factory function creating a default value.
 */
open class DBValue<T>(val valueClass: Class<T>, val default: (() -> T)? = null) : IPropertyWithType<T?>, IInjectable, ICreateSQL {
  /** The current value */
  private var value: T? = null
  override val type = IPropertyWithType.Type.VALUE

  /** @see kotlin.properties.ReadWriteProperty.getValue */
  override fun getValue(thisRef: ActiveRecord, property: KProperty<*>): T {
    if (value == null)
      value = default?.invoke()
    else if (valueClass.isEnum && value is Int) {
      value = valueClass.enumConstants[value as Int]
    }

    return value!!
  }

  /** @see kotlin.properties.ReadWriteProperty.setValue */
  override fun setValue(thisRef: ActiveRecord, property: KProperty<*>, value: T?) {
    if (value == null)
      throw NullPointerException("value was null")
    if (this.value is Enum<*> && value is String) {
      val new = (this.value as Enum<*>)::class.java.getDeclaredMethod("valueOf").invoke(null, value)
      DBValue<*>::value.set(this, new)
    } else if (valueClass.isEnum && value is Int) {
      DBValue<*>::value.set(this, valueClass.enumConstants[value])
    } else this.value = value
  }

  companion object {
    /**
     * The format of a timestamp (ISO 8601).
     */
    val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    /**
     * The format of a date.
     */
    val dateFormat = SimpleDateFormat("yyyy-MM-dd")
    /**
     * The format of time.
     */
    val timeFormat = SimpleDateFormat("HH:mm:ss")
  }

  @Suppress("UNCHECKED_CAST")
  override fun inject(value: Any?) {
    if (this.value is Enum<*> && value is String) {
      val new = (this.value as Enum<*>)::class.java.getDeclaredMethod("valueOf").invoke(null, value)
      DBValue<*>::value.set(this, new)
    } else if (value != null) this.value = when (valueClass) {
      Timestamp::class.java -> Timestamp(timestampFormat.parse(value as String).time) as T
      Date::class.java -> Date(dateFormat.parse(value as String).time) as T
      Time::class.java -> Time(timeFormat.parse(value as String).time) as T
      else -> value as T
    }
  }

  override fun getCreationSQL(instance: ActiveRecord, property: KProperty<*>): String {
    val primaryKey = if (
      instance.fields.find {
        instance.metadata?.digest?.primaryKeys?.contains(it.property) == true
      } != null || property.name == "id"
    ) " PRIMARY KEY" else ""
    @Suppress("SpellCheckingInspection")
    val autoIncrement =
      if (instance.metadata?.digest?.auto?.any { it == property } == true)
        if (instance.database.isSQLite) " DEFAULT rowid"
        else " AUTO_INCREMENT"
      else ""
    return "${Field.getName(property)} ${Field.getSQLType(property, instance.database)} NOT NULL$primaryKey$autoIncrement"
  }
}
