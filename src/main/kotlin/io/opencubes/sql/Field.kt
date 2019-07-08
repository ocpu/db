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

data class Field(val property: KProperty<*>) {
  val instance by lazy { getInjectableInstance(property) }
  val name by lazy { getName(property) }
  val klass by lazy { getKClass(property) }
  val table by lazy { instance.table }

  @Suppress("UNCHECKED_CAST")
  fun getDelegate(instance: ActiveRecord = this.instance) =
    (property as KProperty1<ActiveRecord, *>).accessible { getDelegate(instance) }

  fun hasValue(instance: ActiveRecord) = try {
    property.getter.accessible { call(instance) }
    true
  } catch (_: Throwable) {
    false
  }

  fun getValue(instance: ActiveRecord): Any? {
    return when (val delegate = getDelegate(instance)) {
      is DBReference<*> -> delegate.value
      is DBReferenceNull<*> -> delegate.value
      else -> property.accessible { getter.call(instance) }
    }
  }
  fun getSQLType(database: Database? = null) = getSQLType(property, database)
  @Suppress("UNCHECKED_CAST")
  fun <T : ActiveRecord> klass() = klass as KClass<T>

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

    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun getKClass(property: KProperty<*>) = property.javaField!!.declaringClass!!.kotlin as KClass<out ActiveRecord>

    @JvmStatic
    private fun computeInstance(property: KProperty<*>) = ActiveRecord.getShallowInstance(getKClass(property))

    @JvmStatic
    fun getInjectableInstance(property: KProperty<*>) =
      propertyToInstance.computeIfAbsent(property, ::computeInstance)

    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    private fun resolveName(property: KProperty<*>): String {
      val name = property.findAnnotation<SerializedName>()?.value
      if (name != null) return name

      return when (val delegate = property.javaField?.accessible { get(getInjectableInstance(property)) }) {
        is DBReference<*> -> "${property.name.toSnakeCase()}_${delegate.instance.idField.name}"
        is DBReferenceNull<*> -> "${property.name.toSnakeCase()}_${delegate.field.name}"
        else -> property.name.toSnakeCase()
      }
    }

    fun getName(property: KProperty<*>) =
      try {
        if (getKClass(property).isSubclassOf(ActiveRecord::class))
          nameCache.computeIfAbsent(property, ::resolveName)
        else property.findAnnotation<SerializedName>()?.value ?: property.name
      } catch (_: NullPointerException) {
        property.findAnnotation<SerializedName>()?.value ?: property.name
      }

    fun getSQLType(property: KProperty<*>, database: Database? = null) = typeCache.computeIfAbsent(property) { resolveSQLType(property, database) }
    fun hasValue(instance: ActiveRecord, list: List<Field>): List<Field> = list.filter { it.hasValue(instance) }
    fun values(instance: ActiveRecord, list: List<Field>): List<Any?> = list.map { it.getValue(instance) }
    fun nameArray(list: List<Field>) = list.map(Field::name).toTypedArray()
    inline fun <reified T> nameArray(list: List<Field>, mapper: (String) -> T) = list.map(Field::name).map(mapper).toTypedArray()

    private fun resolveSQLType(property: KProperty<*>, database: Database?): String {
      return getInjectableInstance(property).metadata?.let {
        it.digest.types[property]?.toString(database)
      } ?: findType(property.returnType.jvmErasure, database)
    }

    private fun findType(klass: KClass<*>, database: Database?): String {
      return when {
        klass.isSubclassOf(String::class) -> if (database?.isSQLite == true) "TEXT" else "MEDIUMTEXT"
        klass.isSubclassOf(Int::class) || klass.isSubclassOf(Integer::class) -> "INTEGER"
        klass.isSubclassOf(Long::class) -> "BIGINT"
        klass.isSubclassOf(Short::class) -> "SMALLINT"
        klass.isSubclassOf(Byte::class) -> "TINYINT"
        klass.isSubclassOf(Timestamp::class) -> "TIMESTAMP"
        klass.isSubclassOf(Time::class) -> "TIME"
        klass.isSubclassOf(Date::class) -> "DATE"
        klass.isSubclassOf(Blob::class) -> "BLOB"
        klass.isSubclassOf(Clob::class) -> "CLOB"
        klass.isSubclassOf(Boolean::class) -> if (database?.isSQLite == true) "INT" else "BOOLEAN"
        klass.isSubclassOf(ActiveRecord::class) -> {
          @Suppress("UNCHECKED_CAST")
          val ii = ActiveRecord.getShallowInstance(klass as KClass<ActiveRecord>)
          ii.idField.getSQLType(database)
        }
        else -> {
          val values = klass.java.enumConstants as? Array<*> ?: return ""
          return if (database?.isSQLite == true) "INT"
          else values.joinToString(" ", "ENUM (", ")") value@{
            if (it !is Enum<*>) return@value "'$it'"
            klass.java.getField(it.name)
              .getAnnotation(SerializedName::class.java)?.value ?: "'$it'"
          }
        }
      }
    }

    operator fun invoke(property: KProperty<*>?) = if (property != null) Field(property) else null
  }
}
