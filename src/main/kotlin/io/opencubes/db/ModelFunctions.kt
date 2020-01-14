//@file:JvmName("Model2Util")

package io.opencubes.db

import io.opencubes.db.sql.ISerializableDefault
import io.opencubes.db.values.ValueWrapper
import java.util.function.Supplier


/** Specify a database column value with only a type. */
inline fun <reified T> value() = ValueWrapper(T::class.java, null is T, null)
/** Specify a database column value with a type and a static default value. */
inline fun <reified T> value(default: T) = ValueWrapper(T::class.java, null is T, default)
/** Specify a database column value with a type and a dynamic default value. */
inline fun <reified T> value(default: Supplier<T>) = ValueWrapper(T::class.java, null is T, default)
/** Specify a database column value with a type and a dynamic default value. */
inline fun <reified T> value(noinline default: () -> T) = value(Supplier(default))
/**
 * Specify a database column value with a type and a dynamic default value based on
 * the [ISerializableDefault] interface. This is to have a database serialized dynamic
 * default. This also has the effect of enabling you to use the dynamic default even
 * if you do not use this library.
 *
 * @see io.opencubes.db.sql.CurrentTimestamp
 * @see io.opencubes.db.sql.CurrentDate
 * @see io.opencubes.db.sql.CurrentTime
 */
inline fun <reified T> value(default: ISerializableDefault<T>) = ValueWrapper(T::class.java, null is T, default)

/**
 * Create a many to many reference to many of the specified type of model.
 *
 * Shortcut for [referenceMany].
 *
 * @param M The other model that will be linked with this model.
 * @param customTableName The name of the table that links the two tables together. If
 * null the name is determined by the property name that has this value and the name of
 * the two different or same models.
 * @param customFromPropertyName Here you can specify the name of the property
 * representing this model in the link table. If null it is determined by this model
 * name and the name of the property representing this model.
 * @param customToPropertyName Here you can specify the name of the property
 * representing the other model in the link table. If null it is determined by the other
 * model name and the name of the property representing the other model.
 * @see [referenceMany]
 */
inline fun <reified M : Model> Model.referenceMany(
  customTableName: String? = null,
  customFromPropertyName: String? = null,
  customToPropertyName: String? = null
) = referenceMany(M::class.java, customTableName, customFromPropertyName, customToPropertyName)
