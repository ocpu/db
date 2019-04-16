package io.opencubes.sql.select

import io.opencubes.sql.ActiveRecord
import io.opencubes.sql.IInjectable
import io.opencubes.sql.SerializedName
import io.opencubes.sql.useIfPresent
import java.util.*
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField

/**
 * This is a wrapper to make it easier to integrate the result into a instance or such.
 *
 * @param resultSet The result set to wrap.
 */
class FetchableResult(val resultSet: Optional<ResultSetWrapper>) {
  /** Whether or not there is a result or not */
  val hasResult get() = resultSet.isPresent

  /**
   * If you expect to get a single value from the result you can call this to
   * get it.
   *
   * The transformer takes a [ResultSetWrapper] as its first parameter and it
   * should return a [T] instance or null.
   *
   * @param transformer The function that transforms the row into a value.
   * @param T The type of object you expect.
   * @return The transform row or null.
   */
  fun <T> fetch(transformer: (ResultSetWrapper) -> T?): T? = resultSet.useIfPresent(transformer)

  /**
   * If you expect the result to be a list of [T] you can bundle them up with
   * this.
   *
   * The transformer takes a [ResultSetWrapper] as its first parameter and it
   * should return a [T] instance.
   *
   * @param transformer The function that transforms rows into values.
   * @param T The type of objects in the list.
   * @return The list with the transformed rows or an empty list.
   */
  fun <T> fetchAll(transformer: (ResultSetWrapper) -> T): List<T> =
    resultSet.useIfPresent { it.asSequence().map(transformer).toList() } ?: emptyList()

  /**
   * Inserts all possible result columns into the [instance] specified. This
   * respects if the field is annotated with [SerializedName] to use that name
   * instead.
   *
   * If a field name in the [instance] matches a result column this will override
   * that value to match the one in the result set.
   *
   * If the value being overridden is a enum it will try its best to get the
   * appropriate enum value of that type. If the enum field differs form the one
   * in the database use [SerializedName] to specify the name that is represented
   * in the database.
   *
   * If the field is a delegate to a [ReadWriteProperty] it will delegate the
   * setting of the value to that instance.
   *
   * @param instance The instance you want to override the result set values in.
   * @param M The type of the instance.
   * @return The [instance] as it could be helpful for chaining purposes.
   */
  fun <M : Any> fetchInto(instance: M): M =
    resultSet.useIfPresent { it.inject(instance) } ?: instance

  /**
   * Fetch the result into a [model] of a active record.
   */
  fun <M : ActiveRecord> fetchInto(model: KClass<M>): M? =
    if (resultSet.isPresent) fetchInto(ActiveRecord.getInjectableInstance(model))
    else null

  /**
   * Fetch the result into a model [M] of a active record.
   */
  inline fun <reified M : ActiveRecord> fetchInto() = fetchInto(M::class)

  /**
   * Fetch the result into a list of [model] that is a active record.
   */
  fun <M : ActiveRecord> fetchAllInto(model: KClass<M>): List<M> =
    if (resultSet.isPresent) fetchAll { it.inject(ActiveRecord.getInjectableInstance(model)) }
    else emptyList()

  /**
   * Fetch the result into a list of model [M] that is a active record.
   */
  inline fun <reified M : ActiveRecord> fetchAllInto() = fetchAllInto(M::class)
}
