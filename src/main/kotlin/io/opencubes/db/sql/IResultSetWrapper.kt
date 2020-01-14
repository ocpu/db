package io.opencubes.db.sql

import io.opencubes.db.IInjectable
import io.opencubes.db.SerializedName

/**
 * A wrapper that simplifies most things when dealing with database result sets.
 */
interface IResultSetWrapper : Iterator<IResultSetWrapper>, AutoCloseable {
  /**
   * The column names in this result set.
   */
  val columns: List<String>

  /**
   * The index of the current row.
   */
  val currentRow: Int

  /**
   * Is the result set closed.
   */
  val closed: Boolean

  /**
   * Is the result set closed.
   */
  val generatedKeys: Map<String, Any>

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

  /**
   * Get a column value based on a name.
   *
   * @param columnName The column name.
   */
  operator fun get(columnName: String): Any?

  /**
   * Get a column value based on column index.
   *
   * @param columnIndex The column index.
   */
  operator fun get(columnIndex: Int): Any?

  /**
   * Returns a list containing the results of applying the given [transform] function
   * to each element in the original collection.
   */
  fun <R> map(transform: (row: IResultSetWrapper) -> R): List<R>

  /**
   * Injects the values in the result into a object that has the same column name.
   * The name used can be influenced by the [SerializedName] annotation. Delegated
   * value setters are preferred. Delegated properties that implements the
   * [IInjectable] interface can be used to better control the value injected. Enum
   * values are supported.
   */
  fun <T : Any> inject(instance: T): T

  /**
   * Call a function if there are any results in the result set and later close the
   * result set.
   *
   * @return The result of the function or null.
   */
  fun <T> useIfPresent(transformer: (IResultSetWrapper) -> T?): T? = if (hasNext()) use(transformer) else null
}
