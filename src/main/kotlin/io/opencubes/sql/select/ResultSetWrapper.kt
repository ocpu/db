package io.opencubes.sql.select

import io.opencubes.sql.ActiveRecord
import io.opencubes.sql.IInjectable
import io.opencubes.sql.SerializedName
import java.sql.ResultSet
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField

/**
 * Wraps a result set to give easy access to extra metadata.
 *
 * @param resultSet The initial result set.
 */
class ResultSetWrapper(val resultSet: ResultSet) : Iterator<ResultSetWrapper>, AutoCloseable {
  /**
   * The column names in this result set.
   */
  val columns: List<String> by lazy {
    val meta = resultSet.metaData
    val columnCount = meta.columnCount

    List(columnCount) {
      meta.getColumnName(it + 1)
    }
  }

  val closed get() = resultSet.isClosed
  val currentRow get() = resultSet.row

  private var lastNext = true

  override fun close() = resultSet.close()

  /**
   * Go to the next result row.
   */
  override fun next(): ResultSetWrapper = this

  /**
   * Test if there are more rows.
   */
  override fun hasNext() = !resultSet.isClosed && !resultSet.isAfterLast && resultSet.next()

  /**
   * Get a column value based on a name.
   *
   * @param columnName The column name.
   */
  operator fun get(columnName: String): Any? = resultSet.getObject(columnName)

  /**
   * Get a column value based on column index.
   *
   * @param columnIndex The column index.
   */
  operator fun get(columnIndex: Int): Any? = resultSet.getObject(columnIndex - 1)

  /** Get the 0 column index value */
  operator fun component1() = get(0)

  /** Get the 1 column index value */
  operator fun component2() = get(1)

  /** Get the 2 column index value */
  operator fun component3() = get(2)

  /** Get the 3 column index value */
  operator fun component4() = get(3)

  /** Get the 4 column index value */
  operator fun component5() = get(4)

  /** Get the 5 column index value */
  operator fun component6() = get(5)

  /** Get the 6 column index value */
  operator fun component7() = get(6)

  /** Get the 7 column index value */
  operator fun component8() = get(7)

  /** Get the 8 column index value */
  operator fun component9() = get(8)

  fun <T: Any> inject(instance: T): T {
    instance::class.memberProperties
      .asSequence()
      .filter { ActiveRecord.getAsName(it) in columns }
      .forEach { prop ->
        val name = ActiveRecord.getAsName(prop)
        val a = prop.isAccessible
        if (!a) prop.isAccessible = true

        try {
          val value = prop.javaField?.get(instance)
          when {
            value is IInjectable -> value.inject(this[name])
            value is ReadWriteProperty<*, *> ->
              ReadWriteProperty<Any?, Any?>::setValue.call(value, instance, prop, this[name])
            prop is KMutableProperty<*> -> {
              var newValue = this[name]
              if (value is Enum<*>)
                newValue = value::class.java.enumConstants
                  ?.asSequence()
                  ?.filterIsInstance<Enum<*>>()
                  ?.find {
                    (it::class.java
                      .getField(it.name)
                      .getAnnotation(SerializedName::class.java)
                      ?.value ?: it.name) == this[name]
                  } ?: this[name]

              prop.setter.call(instance, newValue)
            }
          }
        } catch (e: Exception) {
          if (prop is KMutableProperty<*>)
            prop.setter.call(instance, this[name])
          else throw e
        }

        if (!a) prop.isAccessible = false
      }
    return instance
  }
}
