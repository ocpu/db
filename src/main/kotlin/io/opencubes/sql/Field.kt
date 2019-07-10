package io.opencubes.sql

import io.opencubes.sql.delegates.DBReference
import io.opencubes.sql.delegates.DBReferenceNull
import java.sql.*
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.*
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.jvmErasure

/**
 * A class representing a property of a model.
 *
 * @param property The property that this field represents.
 */
data class Field(val property: KProperty<*>) {
  /**
   * Get the shallow instance for the model containing this field.
   */
  val instance by lazy { getShallowInstance(property) }
  /**
   * Get the name that is used in sql queries and in database.
   */
  val name by lazy { getName(property) }
  /**
   * Get the [KClass] for the model containing this field.
   */
  val kClass by lazy { getKClass(property) }
  /**
   * Get the table name of the model containing this field.
   */
  val table by lazy { instance.table }

  /**
   * Get the delegate representing this field.
   */
  @Suppress("UNCHECKED_CAST")
  fun getDelegate(instance: ActiveRecord = this.instance) =
    (property as KProperty1<ActiveRecord, *>).accessible { getDelegate(instance) }

  /**
   * Tests to see if the field contains a value.
   */
  fun hasValue(instance: ActiveRecord) = try {
    property.getter.accessible { call(instance) }
    true
  } catch (_: Throwable) {
    false
  }

  /**
   * Gets the value of the field from the [instance].
   */
  fun getValue(instance: ActiveRecord): Any? {
    return when (val delegate = getDelegate(instance)) {
      is DBReference<*> -> delegate.value
      is DBReferenceNull<*> -> delegate.value
      else -> property.accessible { getter.call(instance) }
    }
  }

  /**
   * Get the type this field has/would have in the database.
   */
  fun getSQLType(database: Database? = null) = getSQLType(property, database)

  /**
   * Get the [KClass] for the model containing this field.
   */
  @Suppress("UNCHECKED_CAST")
  fun <T : ActiveRecord> kClass() = kClass as KClass<T>

  /**
   * Set the [value] of this field of the [instance] specified.
   */
  fun setValue(instance: ActiveRecord, value: Any?) {
    when (val delegate = getDelegate(instance)) {
      is IInjectable -> delegate.inject(value)
      is ReadWriteProperty<*, *> -> ReadWriteProperty<*, *>::setValue.call(delegate, instance, property, value)
      else -> if (property is KMutableProperty<*>) property.setter.call(value)
    }
  }

  companion object {
    private val nameCache = mutableMapOf<KProperty<*>, String>()
    private val typeCache = mutableMapOf<KProperty<*>, String>()
    private val propertyToInstance = mutableMapOf<KProperty<*>, ActiveRecord>()

    /**
     * Gets the [KClass] that is representing the [property] specified.
     */
    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun getKClass(property: KProperty<*>) = property.javaField!!.declaringClass!!.kotlin as KClass<out ActiveRecord>

    @JvmStatic
    private fun computeInstance(property: KProperty<*>) = ActiveRecord.getShallowInstance(getKClass(property))

    /**
     * Get a shallow instance for the model that is representing the [property].
     */
    @JvmStatic
    fun getShallowInstance(property: KProperty<*>) =
      propertyToInstance.computeIfAbsent(property, ::computeInstance)

    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    private fun resolveName(property: KProperty<*>): String {
      val name = property.findAnnotation<SerializedName>()?.value
      if (name != null) return name

      return when (val delegate = property.javaField?.accessible { get(getShallowInstance(property)) }) {
        is DBReference<*> -> "${property.name.toSnakeCase()}_${delegate.instance.idField.name}"
        is DBReferenceNull<*> -> "${property.name.toSnakeCase()}_${delegate.field.name}"
        else -> property.name.toSnakeCase()
      }
    }

    /**
     * Get the name that the [property] has/will have in the database.
     */
    fun getName(property: KProperty<*>) =
      try {
        if (getKClass(property).isSubclassOf(ActiveRecord::class))
          nameCache.computeIfAbsent(property, ::resolveName)
        else property.findAnnotation<SerializedName>()?.value ?: property.name
      } catch (_: NullPointerException) {
        property.findAnnotation<SerializedName>()?.value ?: property.name
      }

    /**
     * Get the sql type for the [property] in the specified [database].
     */
    fun getSQLType(property: KProperty<*>, database: Database? = null) = typeCache.computeIfAbsent(property) { resolveSQLType(property, database) }

    /**
     * Map all fields in a list and get all fields that has a value in the
     * specified [instance].
     */
    fun hasValue(instance: ActiveRecord, list: List<Field>): List<Field> = list.filter { it.hasValue(instance) }

    /**
     * Map all fields to their value in the [instance].
     */
    fun values(instance: ActiveRecord, list: List<Field>): List<Any?> = list.map { it.getValue(instance) }

    /**
     * Get all field names in the list.
     */
    fun nameArray(list: List<Field>) = list.map(Field::name).toTypedArray()

    /**
     * Get all field names in the list and map the result to something else.
     */
    inline fun <reified T> nameArray(list: List<Field>, mapper: (String) -> T) = list.map(Field::name).map(mapper).toTypedArray()

    private fun resolveSQLType(property: KProperty<*>, database: Database?): String {
      return getShallowInstance(property).metadata?.let {
        it.digest.types[property]?.toString(database)
      } ?: findType(property.returnType.jvmErasure, database)
    }

    private fun findType(kClass: KClass<*>, database: Database?): String {
      return when {
        kClass.isSubclassOf(String::class) -> if (database?.isSQLite == true) "TEXT" else "MEDIUMTEXT"
        kClass.isSubclassOf(Int::class) || kClass.isSubclassOf(Integer::class) -> "INTEGER"
        kClass.isSubclassOf(Long::class) -> "BIGINT"
        kClass.isSubclassOf(Short::class) -> "SMALLINT"
        kClass.isSubclassOf(Byte::class) -> "TINYINT"
        kClass.isSubclassOf(Timestamp::class) -> "TIMESTAMP"
        kClass.isSubclassOf(Time::class) -> "TIME"
        kClass.isSubclassOf(Date::class) -> "DATE"
        kClass.isSubclassOf(Blob::class) -> "BLOB"
        kClass.isSubclassOf(Clob::class) -> "CLOB"
        kClass.isSubclassOf(Boolean::class) -> if (database?.isSQLite == true) "INT" else "BOOLEAN"
        kClass.isSubclassOf(ActiveRecord::class) -> {
          @Suppress("UNCHECKED_CAST")
          val ii = ActiveRecord.getShallowInstance(kClass as KClass<ActiveRecord>)
          ii.idField.getSQLType(database)
        }
        else -> {
          val values = kClass.java.enumConstants as? Array<*> ?: return ""
          return if (database?.isSQLite == true) "INT"
          else values.joinToString(" ", "ENUM (", ")") value@{
            if (it !is Enum<*>) return@value "'$it'"
            kClass.java.getField(it.name)
              .getAnnotation(SerializedName::class.java)?.value ?: "'$it'"
          }
        }
      }
    }

    /**
     * Get a field instance from the property specified if it is not null.
     */
    operator fun invoke(property: KProperty<*>?) = if (property != null) Field(property) else null
  }
}
