package io.opencubes.db.values

import io.opencubes.db.interfaces.IReadWriteDelegate
import io.opencubes.db.sql.ISerializableDefault
import io.opencubes.db.IInjectable
import io.opencubes.db.Model
import io.opencubes.db.values.ValueWrapperPreferences.*
import java.sql.Date
import java.sql.Time
import java.sql.Timestamp
import java.util.function.Supplier
import kotlin.reflect.KProperty

@Suppress("UNCHECKED_CAST")
class ValueWrapper<V : Any?>
@Throws(IllegalArgumentException::class)
constructor(val type: Class<V>, val nullable: Boolean, val default: Any?) : IReadWriteDelegate<Model, V>, IInjectable {
  var preferences: ValueWrapperPreferences<V>? = null
    private set
  var changed: Boolean = false
    private set
  private var value: V? = getDefaultValue()
  private var tempValue: Any? = null
  private var retrieved = false
  private val modelSQL by lazy {
    val model = type as Class<out Model>
    val empty = Model.obtainEmpty(model)
    val fields = Model.obtainFields(model).joinToString { "`${it.name}`" }
    val idField = Model.obtainId(model)

    "SELECT $fields FROM ${empty.table} WHERE ${idField.name} LIKE ?"
  }

  var isPrimary = false
  var isAutoIncrement = false
  var indexGroups: MutableList<String?>? = null
  var uniqueIndexGroups: MutableList<String?>? = null

  private fun getDefaultValue(): V? {
    return when {
      default is Supplier<*> -> {
        val res = default.get()
        require(type.isAssignableFrom(res::class.java)) {
          "The default value has to be of the same type or nothing"
        }
        res as V?
      }
      default == null -> null
      default is ISerializableDefault<*> -> default.get() as V?
      type.isAssignableFrom(default::class.java) -> default as V?
      else -> error("The default value has to be of the same type or nothing")
    }
  }

  @Throws(IllegalStateException::class, IllegalArgumentException::class)
  override fun get(): V {
    if (Model::class.java.isAssignableFrom(type) && !retrieved) {
      val model = type as Class<out Model>
      val empty = Model.obtainEmpty(model)

      val result = empty.driver.execute(modelSQL, tempValue)
      value = if (result.hasNext())
        result.inject(model.newInstance()) as V?
      else null
      retrieved = true
    }
    return when {
      nullable -> value
      value == null -> {
        value = getDefaultValue()
        @Suppress("ReplaceGuardClauseWithFunctionCall")
        if (value == null)
          throw IllegalStateException("Value cannot be null if not nullable is set")
        value
      }
      else -> value
    } as V
  }

  override fun set(value: V) = inject(value)

  override fun inject(value: Any?) {
    if (preferences != null)
      require(preferences!!.test(value as V)) { "Value passed is not a valid value" }
    when {
      Model::class.java.isAssignableFrom(type) -> {
        when {
          value is Int -> {
            tempValue = value
            this.value = null
            retrieved = false
            changed = true
          }
          value == null -> {
            check(nullable) { "Cannot assign null to a not nullable value" }
            tempValue = null
            this.value = null
            retrieved = true
            changed = true
          }
          value is Model -> {
            val model = value::class.java as Class<out Model>
            val id = Model.obtainId(model)
            val modelValue = id.value(value).value
            if (modelValue != tempValue) {
              tempValue = modelValue
              this.value = value as V?
              retrieved = true
              changed = true
            }
          }
        }
      }
      !nullable && value == null -> error("Value cannot be null if not nullable is set")
      else -> {
        changed = this.value == value
        this.value = when {
          value is Number && Timestamp::class.java.isAssignableFrom(type) -> Timestamp(value.toLong())
          value is Number && Time::class.java.isAssignableFrom(type) -> Time(value.toLong())
          value is Number && Date::class.java.isAssignableFrom(type) -> Date(value.toLong())
          else -> value
        } as V
      }
    }
  }

  override fun getValue(thisRef: Model, property: KProperty<*>): V = get()
  override fun setValue(thisRef: Model, property: KProperty<*>, value: V) = set(value)

  /**
   * Set the auto increment flag on this property/column.
   */
  val autoIncrement get(): ValueWrapper<V> {
    check(!isAutoIncrement) { "Calling a the autoIncrement method more than once does not do anything" }
    isAutoIncrement = true
    return this
  }

  val primary get(): ValueWrapper<V> {
    check(!isPrimary) { "Calling a the primary method more than once does not do anything" }
    isPrimary = true
    return this
  }

  /**
   * Create a index in the table with this value.
   */
  val index get(): ValueWrapper<V> {
    if (indexGroups == null)
      indexGroups = mutableListOf()
    indexGroups!!.add(null)
    return this
  }

  /**
   * Create a group index in the table with this value.
   */
  fun index(group: String): ValueWrapper<V> {
    if (indexGroups == null)
      indexGroups = mutableListOf()
    indexGroups!!.add(group)
    return this
  }

  val unique get(): ValueWrapper<V> {
    if (uniqueIndexGroups == null)
      uniqueIndexGroups = mutableListOf()
    uniqueIndexGroups!!.add(null)
    return this
  }

  fun unique(group: String): ValueWrapper<V> {
    if (uniqueIndexGroups == null)
      uniqueIndexGroups = mutableListOf()
    uniqueIndexGroups!!.add(group)
    return this
  }

  fun preferences(preferences: ValueWrapperPreferences<V>?) {
    this.preferences = preferences
  }

  fun string(block: ValueWrapperPreferences.String.() -> Unit): ValueWrapper<V> {
    string().block()
    return this
  }

  fun string(): ValueWrapperPreferences.String {
    check(type == String::class.java) { "Tried to receive string preferences when the type is not string" }
    if (preferences == null)
      preferences = String(nullable) as ValueWrapperPreferences<V>
    return preferences as ValueWrapperPreferences.String
  }

  fun number(block: ValueWrapperPreferences.Number.() -> Unit): ValueWrapper<V> {
    number().block()
    return this
  }

  fun number(): ValueWrapperPreferences.Number {
    check(Number::class.java.isAssignableFrom(type)) {
      "Tried to receive number preferences when the type is not number"
    }
    if (preferences == null)
      preferences = ValueWrapperPreferences.Number() as ValueWrapperPreferences<V>
    return preferences as ValueWrapperPreferences.Number
  }

  fun reference(block: Reference.() -> Unit): ValueWrapper<V> {
    reference().block()
    return this
  }

  fun reference(): Reference {
    check(Model::class.java.isAssignableFrom(type)) {
      "Tried to receive reference preferences when the type is not a model"
    }
    if (preferences == null)
      preferences = Reference(type as Class<out Model>, nullable) as ValueWrapperPreferences<V>
    return preferences as Reference
  }
}
