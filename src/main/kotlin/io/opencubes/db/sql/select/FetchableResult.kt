package io.opencubes.db.sql.select

import io.opencubes.db.sql.IResultSetWrapper

/**
 * This is a wrapper to make it easier to integrate the result into a instance or such.
 *
 * @param resultSet The result set to wrap.
 */
class FetchableResult(val resultSet: IResultSetWrapper) {
  /** Whether or not there is a result or not */
  val hasResult get() = resultSet.hasNext()

  /**
   * If you expect to get a single value from the result you can call this to
   * get it.
   *
   * The transformer takes a [IResultSetWrapper] as its first parameter and it
   * should return a [T] instance or null.
   *
   * @param transformer The function that transforms the row into a value.
   * @param T The type of object you expect.
   * @return The transform row or null.
   */
  fun <T> fetch(transformer: (IResultSetWrapper) -> T?): T? = resultSet.useIfPresent(transformer)

  /**
   * If you expect the result to be a list of [T] you can bundle them up with
   * this.
   *
   * The transformer takes a [IResultSetWrapper] as its first parameter and it
   * should return a [T] instance.
   *
   * @param transformer The function that transforms rows into values.
   * @param T The type of objects in the list.
   * @return The list with the transformed rows or an empty list.
   */
  fun <T> fetchAll(transformer: (IResultSetWrapper) -> T): List<T> =
    resultSet.useIfPresent { it.asSequence().map(transformer).toList() } ?: emptyList()

  /**
   * Inserts all possible result columns into the [instance] specified. This
   * respects if the field is annotated with [SerializedName][io.opencubes.db.SerializedName] to use that name
   * instead.
   *
   * If a field name in the [instance] matches a result column this will override
   * that value to match the one in the result set.
   *
   * If the value being overridden is a enum it will try its best to get the
   * appropriate enum value of that type. If the enum field differs form the one
   * in the database use [SerializedName][io.opencubes.db.SerializedName] to specify the name that is represented
   * in the database.
   *
   * If the field is a delegate to a [ReadWriteProperty][kotlin.properties.ReadWriteProperty] it will delegate the
   * setting of the value to that instance.
   *
   * @param instance The instance you want to override the result set values in.
   * @param T The type of the instance.
   * @return The [instance] as it could be helpful for chaining purposes.
   */
  fun <T : Any> fetchInto(instance: T): T =
    fetch { it.inject(instance) } ?: instance

  /**
   * Fetch the result into a [clazz] of a active record.
   */
  fun <T : Any> fetchInto(clazz: Class<T>): T? =
    fetch { it.inject(clazz.newInstance()) }

  /**
   * Fetch the result into a model [T].
   */
  inline fun <reified T : Any> fetchInto() = fetchInto(T::class.java)

  /**
   * Fetch the result into a list of [clazz].
   */
  fun <T : Any> fetchAllInto(clazz: Class<T>): List<T> =
    fetchAll { it.inject(clazz.newInstance()) }

  /**
   * Fetch the result into a list of model [T] that is a active record.
   */
  inline fun <reified T : Any> fetchAllInto() = fetchAllInto(T::class.java)

  /**
   * Iterate through all rows that are left.
   */
  fun forEach(function: (resultSet: IResultSetWrapper) -> Unit) {
    resultSet.useIfPresent {
      for (res in it)
        function(res)
    }
  }

  /**
   * Transform the iterator into a [Sequence].
   */
  fun asSequence(): Sequence<IResultSetWrapper> = resultSet.useIfPresent { it.asSequence() } ?: emptySequence()

  /**
   * Get each row into a map where the keys are the columns and the values are what was received.
   */
  fun toMap(): List<Map<String, Any?>> = resultSet.useIfPresent {
    it.map { row -> it.columns.map { column -> column to row[column] }.toMap() }
  } ?: listOf()
}
