package io.opencubes.db.values

import io.opencubes.db.ForeignKeyAction
import io.opencubes.db.Model
import io.opencubes.db.ModelField
import java.util.function.Predicate
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.javaField

interface ValueWrapperPreferences : Predicate<Any?> {
  class String(private val nullable: Boolean) : ValueWrapperPreferences {
    override fun test(value: Any?): Boolean {
      require(value is kotlin.String?)
      if (!nullable) {
        requireNotNull(value)
        return true
      }
      if (maxLength > 0 && value?.length ?: 0 > maxLength) return false
      return true
    }

    var maxLength = 0
    var pad = false
    var binary = false

    @Deprecated("Use the maxLength function on a value instead")
    fun maxLength(maxLength: Int): String {
      this.maxLength = maxLength
      return this
    }

    companion object : (ValueWrapper<*>) -> String {
      override fun invoke(wrapper: ValueWrapper<*>): String {
        require(kotlin.String::class.java.isAssignableFrom(wrapper.type))
        return String(wrapper.nullable)
      }
    }
  }

  class Number : ValueWrapperPreferences {
    override fun test(value: Any?): Boolean {
      require(value is kotlin.Number)
      return when (type) {
        Type.DYNAMIC -> true
        Type.DECIMAL -> {
          // TODO Implement stuff
          true
        }
        else -> true
      }
    }

    var type = Type.DYNAMIC
    var params = listOf<Int>()

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
    @Deprecated("Use the decimal function on a value instead")
    fun decimal(maxDigits: Int = 38, decimals: Int = 0) {
      require(maxDigits in 1..38)
      require(decimals in 0..maxDigits)
      type = Type.DECIMAL
      params = listOf(maxDigits, decimals)
    }

    companion object : (ValueWrapper<*>) -> Number {
      override fun invoke(wrapper: ValueWrapper<*>): Number {
        require(kotlin.Number::class.java.isAssignableFrom(wrapper.type))
        return Number()
      }
    }

    enum class Type {
      DECIMAL,
      INTEGER,
      FLOATING_POINT,
      DYNAMIC
    }
  }

  class Reference(val refType: Class<out Model>, val nullable: Boolean) : ValueWrapperPreferences {
    override fun test(value: Any?): Boolean {
      require(value is Model?)
      if (value == null) {
        check(nullable) { "Tried to set a not nullable reference to null" }
        return true
      }
      return refType.isInstance(value)
    }

    @Deprecated("Use the column function on a value instead")
    fun column(property: KProperty1<out Model, *>) {
      val field = ModelField.construct(property.javaField)
      checkNotNull(field)
      check(field.modelClass == refType)
      column = field.name
    }

    var column: kotlin.String? = null
    var deleteAction = ForeignKeyAction.NO_ACTION
    var changeAction = ForeignKeyAction.NO_ACTION

    companion object : (ValueWrapper<*>) -> Reference {
      override fun invoke(wrapper: ValueWrapper<*>): Reference {
        require(Model::class.java.isAssignableFrom(wrapper.type))
        return Reference(wrapper.type as Class<out Model?>, wrapper.nullable)
      }
    }
  }
}
