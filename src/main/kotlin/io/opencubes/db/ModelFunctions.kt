//@file:JvmName("Model2Util")

package io.opencubes.db

import io.opencubes.db.sql.ISerializableDefault
import io.opencubes.db.values.ValueWrapper
import io.opencubes.db.values.ValueWrapperPreferences
import java.util.function.Supplier
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.javaField


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

/**
 * Makes a string value stored as binary in the database.
 * See https://dev.mysql.com/doc/refman/5.7/en/binary-varbinary.html
 *
 * @param maxLength The max length of the value.
 * @param pad If the length of the input string is less than the [maxLength] pad the value with null.
 */
fun <T : String?> ValueWrapper<T>.binary(maxLength: Int, pad: Boolean = false): ValueWrapper<T> {
  preferences(ValueWrapperPreferences.String) {
    binary = true
    this.maxLength = maxLength
    this.pad = pad
  }
  return this
}

/**
 * Set the max length for the column value.
 * See https://dev.mysql.com/doc/refman/5.7/en/char.html
 *
 * @param maxLength The max length of the value.
 * @param pad If the length of the input string is less than the [maxLength] pad the value with space.
 */
fun <T : String?> ValueWrapper<T>.maxLength(maxLength: Int, pad: Boolean = false): ValueWrapper<T> {
  preferences(ValueWrapperPreferences.String) {
    binary = false
    this.maxLength = maxLength
    this.pad = pad
  }
  return this
}

/**
 * @param maxDigits is the precision which is the maximum total number of
 * decimal digits that will be stored, both to the left and to the right
 * of the decimal point. The precision has a range from 1 to 38. The
 * default precision is 38.
 * @param decimals is the scale which is the number of decimal digits that
 * will be stored to the right of the decimal point. The scale has a range
 * from 0 to p (precision). The scale can be specified only if the
 * precision is specified. By default, the scale is zero.
 */
fun <T : Number?> ValueWrapper<T>.decimal(maxDigits: Int = 38, decimals: Int = 0): ValueWrapper<T> {
  require(maxDigits in 1..38)
  require(decimals in 0..maxDigits)
  preferences(ValueWrapperPreferences.Number) {
    type = ValueWrapperPreferences.Number.Type.DECIMAL
    params = listOf(maxDigits, decimals)
  }
  return this
}

/**
 * Specify which column in the referenced table the value references.
 */
fun <T : Model?> ValueWrapper<T>.column(property: KProperty1<T, *>): ValueWrapper<T> {
  val field = ModelField.construct(property.javaField)
  checkNotNull(field)
  preferences(ValueWrapperPreferences.Reference) {
    check(field.modelClass == refType)
    column = field.name
  }
  return this
}

/**
 * Set what to do to this value when the referenced object is deleted.
 */
fun <T : Model?> ValueWrapper<T>.onDelete(action: ForeignKeyAction): ValueWrapper<T> {
  preferences(ValueWrapperPreferences.Reference) {
    deleteAction = action
  }
  return this
}

/**
 * Set what to do to this value when the referenced object is modified in some way.
 */
fun <T : Model?> ValueWrapper<T>.onChange(action: ForeignKeyAction): ValueWrapper<T> {
  preferences(ValueWrapperPreferences.Reference) {
    changeAction = action
  }
  return this
}

/**
 * Ensures that the value wrapper preferences are of the specified type and a option to configure it as well.
 *
 * @param factory A value wrapper preference factory function that provides the correct type of preference.
 * @param config A configuration function that takes the current value wrapper preference.
 */
inline fun <reified T : ValueWrapperPreferences> ValueWrapper<*>.preferences(factory: (ValueWrapper<*>) -> T, config: T.() -> Unit) {
  if (preferences is T) (preferences as T).config()
  else preferences(factory(this).apply(config))
}

@Deprecated("Use binary or maxLength")
fun <T> ValueWrapper<T>.string(config: ValueWrapperPreferences.String.() -> Unit): ValueWrapper<T> {
  preferences(ValueWrapperPreferences.String, config)
  return this
}

@Deprecated("Use decimal")
fun <T> ValueWrapper<T>.number(config: ValueWrapperPreferences.Number.() -> Unit): ValueWrapper<T> {
  preferences(ValueWrapperPreferences.Number, config)
  return this
}

@Deprecated("Use column, onDelete, or onChange")
fun <T> ValueWrapper<T>.reference(config: ValueWrapperPreferences.Reference.() -> Unit): ValueWrapper<T> {
  preferences(ValueWrapperPreferences.Reference, config)
  return this
}
