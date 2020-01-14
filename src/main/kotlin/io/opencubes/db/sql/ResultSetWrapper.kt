package io.opencubes.db.sql

import io.opencubes.db.IInjectable
import io.opencubes.db.ModelField
import io.opencubes.db.SerializedName
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.properties.ReadWriteProperty

/**
 * This is the basic implementation of the wrapper but some databases need a more
 * specific implementation, but this one catches most cases.
 *
 * @constructor
 * Create a new result set wrapper.
 *
 * @param resultSet The result set to represent.
 * @param stmt The prepared statement the result set is a part of.
 *
 * @property resultSet The base result set this wrapper is wrapping.
 * @property stmt The prepared statement the result set is apart of.
 */
open class ResultSetWrapper(val resultSet: ResultSet?, val stmt: PreparedStatement) : IResultSetWrapper {
  override val columns: List<String> by lazy {
    val meta = resultSet?.metaData ?: return@lazy emptyList<String>()
    val columnCount = meta.columnCount

    List(columnCount) {
      meta.getColumnName(it + 1)
    }
  }

  override val generatedKeys by lazy {
    val stmtGen = stmt.generatedKeys ?: return@lazy emptyMap<String, Any>()
    val meta = stmtGen.metaData ?: return@lazy emptyMap<String, Any>()
    val columnCount = meta.columnCount

    val list = List(columnCount) {
      meta.getColumnName(it + 1)
    }
    list.map { it to stmtGen.getObject(it) }.toMap()
  }

  override val closed get() = resultSet?.isClosed == true
  override val currentRow get() = resultSet?.row ?: 0

  private var lastNext = true

  /** @see AutoCloseable.close */
  override fun close() = if (resultSet != null && !resultSet.isClosed) resultSet.close() else Unit

  private var hasCalledNext = true

  /** @see Iterator.next */
  override fun next(): ResultSetWrapper {
    hasCalledNext = true
    return this
  }

  /** @see Iterator.hasNext */
  override fun hasNext(): Boolean {
    if (closed)
      return false
    if (resultSet?.isAfterLast != false)
      return false
    if (hasCalledNext) {
      hasCalledNext = false
      return resultSet.next()
    }
    return true
  }

  override fun get(columnName: String): Any? =
    if (resultSet != null) {
      if (resultSet.isBeforeFirst)
        resultSet.next()
      resultSet.getObject(columnName)
    } else throw Exception("No row found")

  override fun get(columnIndex: Int): Any? =
    if (resultSet != null) {
      if (resultSet.isBeforeFirst)
        resultSet.next()
      resultSet.getObject(columnIndex)
    } else throw Exception("No row found")

  override fun <T : Any> inject(instance: T): T {
    instance::class.java.declaredFields
      .asSequence()
      .filter { ModelField.getName(it) in columns }
      .forEach { field ->
        val name = ModelField.getName(field)
        val a = field.isAccessible
        if (!a) field.isAccessible = true

        when (val value = field.get(instance)) {
          is IInjectable -> value.inject(this[name])
          is ReadWriteProperty<*, *> -> ReadWriteProperty<Any?, Any?>::setValue.call(value, instance, field, this[name])
          else -> {
            val setMethod = instance::class.java.getMethod(
              "set" + field.name[0].toUpperCase() + field.name.drop(1),
              field.type
            )
            if (setMethod != null) {
              setMethod.invoke(instance, this[name])
            } else {
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

              field.set(instance, newValue)
            }
          }
        }

        if (!a) field.isAccessible = false
      }
    return instance
  }

  override fun <R> map(transform: (row: IResultSetWrapper) -> R): List<R> {
    val res = mutableListOf<R>()
    for (row in this)
      res.add(transform(row))
    return res.toList()
  }
}
