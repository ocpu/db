package io.opencubes.db

import io.opencubes.db.interfaces.ISetAndSupply
import io.opencubes.db.values.*
import java.lang.reflect.Field
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.javaField

/**
 * A model field is a representation of a field of a [Model] class.
 *
 * @constructor
 * Create a model field from a java field.
 *
 * @param backendField The field this model field instance is going to use.
 * @property backendField The underlying field this model field uses to resolve queries.
 */
class ModelField(val backendField: Field) {
  /**
   * Construct a model field instance from a model value.
   *
   * @param model The model the value is from
   * @param value The value to be resolved into a field
   */
  constructor(model: Model, value: ValueWrapper<*>) : this(
    Model.obtainFields(model::class.java).first { it.value(model) == value }.backendField
  )

  /**
   * Get the [model value][ValueWrapper] from a instance.
   */
  fun value(instance: Model): ValueWrapper<*> = delegate(instance)

  /**
   * Get the empty model representation of this model field.
   */
  val emptyModel: Model by lazy { Model.obtainEmpty(modelClass) }

  /**
   * Get the class this model field is from.
   */
  @Suppress("UNCHECKED_CAST")
  val modelClass by lazy { backendField.declaringClass as Class<out Model> }

  /**
   * Get the database name of this model field model
   */
  val table by lazy { emptyModel.table }
  /**
   * Get the name that this model field has as a database column.
   */
  val name by lazy { getName(backendField) }

  /**
   * Set a value for this model field on a model instance.
   */
  fun <T> set(model: Model, value: T) = value(model).inject(value)

  /**
   * Get the value for a model instance represented by this model field.
   */
  fun get(model: Model): Any? = value(model).get()

  /**
   * Get the raw value this model field has on a model.
   */
  fun getActual(model: Model): Any? {
    val res = value(model).get()
    @Suppress("UNCHECKED_CAST")
    if (res is Model)
      return Model.obtainId(res::class.java as Class<out Model>).get(res)
    return res
  }

  /**
   * Check to see if this model filed has any value for a model instance.
   */
  fun hasValue(model: Model): Boolean {
    try {
      val modelValue = value(model)
      if (modelValue.nullable) return true
//      @Suppress("UNCHECKED_CAST")
//      if (Model2::class.java.isAssignableFrom(modelValue.type))
//        return Model2.obtainId(modelValue::class.java as Class<out Model2>).getActual(modelValue) != null

      return modelValue.get() != null
    } catch (e: Throwable) {
      return false
    }
  }

  /**
   * Check if this model field has changed on a model since the last time it refreshed.
   */
  fun hasChanged(model: Model): Boolean = value(model).changed

  /**
   * Get the delegated value of this model field from a model instance.
   */
  @Suppress("UNCHECKED_CAST")
  fun <T> delegate(model: Model) = backendField.accessible { backendField.get(model) as T }

  /** statics */
  companion object {
    /**
     * Get a new model field from a field or null if it is not possible.
     */
    @JvmStatic
    fun construct(backendField: Field?): ModelField? = when (backendField?.type) {
      ValueWrapper::class.java,
      ReferenceManyToMany::class.java,
      ReferenceOneToMany::class.java -> ModelField(backendField)
      else -> null
    }

    @JvmStatic
    fun construct(clazz: Class<out Model>, fieldName: String): ModelField = construct(clazz::class.java.getDeclaredField(fieldName))!!

    private fun getForeignName(getValue: () -> Any?): String? {
      return try {
        val value = getValue() ?: return null
        if (value !is ValueWrapper<*>) return null
        if (!Model::class.java.isAssignableFrom(value.type)) return null

        @Suppress("UNCHECKED_CAST")
        val otherModel = value.type as Class<out Model>
        Model.obtainId(otherModel).name
      } catch (_: Throwable) {
        null
      }
    }

    private val nameCache = mutableMapOf<Field, String>()

    /**
     * Get the name the generated name for a field when it should be a database column.
     */
    fun getName(field: Field): String {
      if (field in nameCache)
        return nameCache[field]!!
      if (field.isAnnotationPresent(SerializedName::class.java))
        return field.getDeclaredAnnotation(SerializedName::class.java).value
      val baseName = field.name.replace("\$delegate", "").toSnakeCase()
      @Suppress("UNCHECKED_CAST")
      val foreignName = getForeignName {
        field.accessible {
          field.get(Model.obtainEmpty(field.declaringClass as Class<out Model>))
        }
      }
      val name = if (foreignName == null) baseName else "${baseName}_$foreignName"
      nameCache[field] = name
      return name
    }

    /**
     * Get the name the generated name for a field when it should be a database column.
     */
    fun getName(field: KProperty1<Any?, Any?>): String {
      val annotation = field.findAnnotation<SerializedName>()
      if (annotation != null) return annotation.value
      val baseName = field.name.replace("\$delegate", "").toSnakeCase()
      val foreignName = getForeignName {
        field.accessible {
          @Suppress("UNCHECKED_CAST")
          val clazz = field.javaField?.declaringClass as? Class<out Model> ?: return@accessible null
          val empty = Model.obtainEmpty(clazz)
          val delegate = field.getDelegate(empty) ?: return@accessible field.get(empty)
          (delegate as ISetAndSupply<*>).get()
        }
      } ?: return baseName
      return "${baseName}_$foreignName"
    }
  }
}
