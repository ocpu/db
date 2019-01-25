package io.opencubes.sql

import java.lang.reflect.Field as JField
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.*
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.jvmErasure
import kotlin.reflect.jvm.kotlinProperty

/**
 * This is a implementation of the [Active Record Pattern](https://en.wikipedia.org/wiki/Active_record_pattern)
 * with the feature of creating a table from the implementing class.
 *
 * @param database The database to use when executing queries.
 * @param table The table this class represents.
 */
abstract class ActiveRecord(private val database: Database, private val table: String) {
  /**
   * All indices in the model.
   */
  private val indices by lazy {
    val ids = this::class.memberProperties.toMutableList()
        .filterNotHasAnnotation<Exclude>()
        .filterNotHasAnnotation<Volatile>()
        .filter { it !is KMutableProperty<*> || it.findAnnotation<Index>() != null }

    if (ids.isEmpty())
      throw IndexOutOfBoundsException("there has to be at least 1 index")

    return@lazy ids
  }

  /**
   * All fields in the model excluding indices.
   */
  private val fields by lazy {
    this::class.memberProperties.toMutableList()
        .filter {
          when {
            it.findAnnotation<Exclude>() != null -> false
            it.findAnnotation<Volatile>() != null -> false
            it.findAnnotation<Index>() != null -> false
            it is KMutableProperty<*> -> true
            it.findAnnotation<Field>() != null -> true
            else -> false
          }
        }
        .filterIsInstance<KMutableProperty<*>>()
  }

  /**
   * Save the current state of the object to the database. Whether it is to
   * update the existing entry or create a new one.
   */
  open fun save() {
    val filteredIds = indices.filter(::hasValue)

    if (filteredIds.size == indices.size) {
      // Update row
      val valueFields = fields.filter(::hasValue)
      if (database.select("*").from(table).where(getAsNames(indices)).execute(valueFields.map(::getValue)).hasResult) {
        try {
          database.update(table, getAsNames(valueFields), getAsNames(indices), (valueFields + indices).map(::getValue))
          return
        } catch (_: NoSuchElementException) {
          // There was no element to update so lets create it.
          // (Fallthrough to create stage)
        }
      }
    }
    // Create row
    val keys = filteredIds + fields.filter(::hasValue)
    database.insertInto(table, getAsNames(keys), keys.map(::getValue))
    // Apply any unforeseen changes by the database
    val filteredFields = (indices + fields).filter(::hasValue)
    database
        .select("*")
        .from(table)
        .where(getAsNames(filteredFields))
        .execute(filteredFields.map(::getValue))
        .fetchInto(this)
  }

  /**
   * Remove current entry from the database.
   */
  open fun delete() = database.deleteFrom(table, getAsNames(indices), indices.map(::getValue))


  private fun getValue(property: KProperty<*>) = property.getter.call(this)

  /**
   * Checks if the property has a value.
   */
  private fun hasValue(property: KProperty<*>) =
      try {
        property.getter.call(this)
        true
      } catch (_: Exception) {
        false
      }

  companion object {
    /**
     * Converts a property name into a database name.
     */
    fun getAsName(property: KProperty<*>) =
        property.findAnnotation<SerializedName>()?.value ?: property.name

    /**
     * Converts a whole list of properties into database names
     */
    fun getAsNames(list: List<KProperty<*>>) = list.map(::getAsName)

    // ----------------------------- Instance creations --------------------------------------

    /**
     * This function will create a instance of the specified [kClass].
     */
    fun <AR : ActiveRecord> getInjectableInstance(kClass: KClass<out AR>): AR {
      return kClass.primaryConstructor?.accessible {
        call()
      } ?: kClass.java.newInstance() as AR
    }

    /**
     * Given a [column]name this will search for one row that has the specified [value].
     * If it finds a row it will make a [AR] instance from the specified [model].
     */
    fun <AR : ActiveRecord> find(model: KClass<out AR>, column: String, value: Any?): AR? {
      val instance = getInjectableInstance(model)

      ActiveRecord::table.javaField!!.kotlinProperty!!

      return instance.database
          .select("*")
          .from(instance.table)
          .where("$column = ?")
          .execute(value)
          .fetchInto(instance)
    }

    /**
     * Given a [column]name this will search for one row that has the specified [value].
     */
    inline fun <reified AR : ActiveRecord> find(column: String, value: Any?) = find(AR::class, column, value)
    /**
     * Given a [column] this will search for one row that has the specified [value].
     */
    inline fun <reified AR : ActiveRecord> find(column: KProperty<*>, value: Any?) = find(AR::class, getAsName(column), value)
    /**
     * Given a [column] this will search for one row that has the specified [value].
     */
    inline fun <reified AR : ActiveRecord> find(column: JField, value: Any?) = find(AR::class, getAsName(column.kotlinProperty!!), value)

    // -------------------------------- Table Helpers ----------------------------------------

    /**
     * Creates a table from the specified model class. This will analyze the model
     * and search for indices, fields, auto increment values and references.
     *
     * - Properties marked by annotation [Exclude] will not be included.
     * - All fields marked with val/only has a getter or has a annotation [Index]
     *   will be considered to be a indexable value.
     * - Any field delegated by a [DBReference] will become a foreign key
     *   reference.
     *
     * @param model The model class to create the table for.
     * @param scheme If no scheme/database has been specified for the connection.
     */
    @JvmStatic
    fun createTable(model: KClass<out ActiveRecord>, scheme: String?) {
      val instance = model.primaryConstructor?.accessible {
        model.primaryConstructor?.call()
      }
      //if (instance == null)
      //  throw IllegalStateException("Models must have a constructor without any arguments.")

      instance as ActiveRecord

      val fullTableName = if (scheme == null) instance.table else "$scheme.${instance.table}"
      val allFields = instance.indices + instance.fields

      val index = instance.indices.joinToString { getAsName(it).replace("\$delegate", "") }.trim()
      val sqlFields = allFields.joinToString(", ") { prop ->
        val name = getAsName(prop)
        val type = prop.returnType.jvmErasure
        val nullable = if (prop.returnType.isMarkedNullable) "" else " NOT NULL"
        val auto =
            if (prop.findAnnotation<Auto>() != null)
              if (instance.database.dialect.startsWith("sqlite")) " PRIMARY KEY"
              else " PRIMARY KEY AUTO_INCREMENT"
            else ""
        val typeName = " " + when (type) {
          String::class -> "TEXT"
          Int::class, Integer::class -> if (auto.isNotBlank()) "INTEGER" else "INT"
          Long::class -> "BIGINT"
          Short::class -> "SMALLINT"
          Byte::class -> "TINYINT"
          else -> {
            val values = type.java.enumConstants
            if (values is Array<*>) {
              values.joinToString(" ", "ENUM (", ")") value@{
                if (it !is Enum<*>) return@value "'$it'"
                type.java.getField(it.name)
                    .getAnnotation(SerializedName::class.java)?.value ?: "'$it'"
              }
            } else ""
          }
        }
        "$name$typeName$nullable$auto"
      }.trim()
      val foreignKeys = allFields
          .mapNotNull(KProperty<*>::javaField)
          .filter { it.type == DBReference::class.java }
          .fold("") { acc, field ->
            val ref = field.accessible {
              get(instance) as DBReference<*>
            }
            val ctor = ref.field.javaField?.declaringClass?.kotlin?.primaryConstructor ?: return@fold acc
            val res = ctor.accessible {
              call() as ActiveRecord
            }

            val refFullTable = if (scheme == null) res.table else "$scheme.${res.table}"
            val keyName = getAsName(field.kotlinProperty!!)
            val refName = getAsName(ref.field)

            return@fold ", FOREIGN KEY ($keyName) REFERENCES $refFullTable ($refName)"
          }.trim()

      val indices = if (instance.database.dialect.startsWith("sqlite")) "" else ", INDEX ($index)"
      instance.database.execute("CREATE TABLE $fullTableName ($sqlFields$indices$foreignKeys)")
    }


    /**
     * Creates a table for the implicitly determined class [AR].
     *
     * @see createTable
     */
    @JvmStatic
    inline fun <reified AR : ActiveRecord> createTable(scheme: String? = null) = createTable(AR::class, scheme)

    // ---------------------------- Reflection Extensions ------------------------------------

    /**
     * Filter a collection on if the property has a specific annotation ([A])
     */
    private inline fun <reified A : Annotation> Collection<KProperty<*>>.filterNotHasAnnotation() =
        filter { f -> f.annotations.firstOrNull { it is A } == null }

    private fun <R, V, T : KCallable<V>> T.accessible(block: T.() -> R): R {
      val a = isAccessible
      if (!a) isAccessible = true
      val res = block(this)
      if (!a) isAccessible = false
      return res
    }

    private fun <R> java.lang.reflect.Field.accessible(block: java.lang.reflect.Field.() -> R): R {
      val a = isAccessible
      if (!a) isAccessible = true
      val res = block(this)
      if (!a) isAccessible = false
      return res
    }

    /**
     * Set a value on the field or delegated field on a instance.
     */
    fun java.lang.reflect.Field.setValue(instance: Any, value: Any?) {
      val a = isAccessible
      if (!a)
        isAccessible = true
      if (name.endsWith("\$delegate")) {
        val delegate = get(instance)
        val method = delegate::class.java.declaredMethods.findLast { it.name == "setValue" }!!
        method.invoke(delegate, instance, kotlinProperty!!, value)
      } else set(instance, value)
      if (!a)
        isAccessible = false
    }

    /**
     * Get a value from a field or delegated field from a instance.
     */
    fun java.lang.reflect.Field.getValue(instance: Any) {
      val a = isAccessible
      if (!a)
        isAccessible = true
      if (name.endsWith("\$delegate")) {
        val delegate = get(instance)
        val method = delegate::class.java.declaredMethods.findLast { it.name == "getValue" }!!
        method.invoke(delegate, instance, kotlinProperty!!)
      } else get(instance)
      if (!a)
        isAccessible = false
    }
  }

  // -------------------------------- Object overrides ---------------------------------------

  /***/
  override fun hashCode() = fields.fold(31) { acc, f -> acc * (f.getter.call(this)?.hashCode() ?: 1) }

  /***/
  override fun toString(): String {
    return this::class.java.simpleName +
        fields.joinToString(",", prefix = "(", postfix = ")") {
          if (hasValue(it))
            "${it.name}=${getValue(it)}"
          else
            "${it.name}=<unknown>"
        }
  }

  /***/
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ActiveRecord) return false

    if (table != other.table) return false
    if (indices != other.indices) return false
    if (fields != other.fields) return false

    return true
  }

  // ---------------------------- Value resolution Helpers -----------------------------------

  /**
   * Delegate a database value. This delegate can help in creating
   * the table from [createTable] function.
   */
  inline fun <reified T> value(noinline default: (() -> T)? = null) = DBValue(default)

  /**
   * A easy way to represent a value in the model.
   * @param default A possible default value
   */
  class DBValue<T>(val default: (() -> T)? = null) : ReadWriteProperty<ActiveRecord, T> {
    /** The current value */
    private var value: T? = null
    /***/
    override fun getValue(thisRef: ActiveRecord, property: KProperty<*>) = (value ?: default?.invoke())!!
    /***/
    override fun setValue(thisRef: ActiveRecord, property: KProperty<*>, value: T) {
      if (this.value is Enum<*> && value is String) {
        val new = (this.value as Enum<*>)::class.java.getDeclaredMethod("valueOf").invoke(null, value)
        DBValue<*>::value.set(this, new)
      } else this.value = value
    }
  }

  /**
   * Reference a property from a different model that this model depends on.
   */
  inline fun <reified T> reference(field: KProperty1<out ActiveRecord, T>) = DBReference(field)

  /**
   * A decorative delegate to explicitly say that the property references a
   * property form another table.
   *
   * @param field The field this delegate is referencing.
   */
  class DBReference<T>(val field: KProperty1<out ActiveRecord, T>) : ReadWriteProperty<ActiveRecord, T> {
    private var value: T? = null
    /***/
    override fun getValue(thisRef: ActiveRecord, property: KProperty<*>) = value!!
    /***/
    override fun setValue(thisRef: ActiveRecord, property: KProperty<*>, value: T) {
      if (this.value is Enum<*> && value is String) {
        val new = (this.value as Enum<*>)::class.java.getDeclaredMethod("valueOf").invoke(null, value)
        DBReference<*>::value.set(this, new)
      } else this.value = value
    }
  }

  // ----------------------------------- Annotations -----------------------------------------

  /**
   * Explicitly states that a property is a field in the database specification.
   */
  @Retention(AnnotationRetention.RUNTIME)
  @Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
  @MustBeDocumented
  annotation class Field

  /**
   * Explicitly say that a property is a database index.
   */
  @Retention(AnnotationRetention.RUNTIME)
  @Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
  @MustBeDocumented
  annotation class Index

  /**
   * Makes a property not be part of the model specification.
   */
  @Retention(AnnotationRetention.RUNTIME)
  @Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
  @MustBeDocumented
  annotation class Exclude

  /**
   * Defines a auto incremented value in the database.
   */
  @Retention(AnnotationRetention.RUNTIME)
  @Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
  @MustBeDocumented
  annotation class Auto
}