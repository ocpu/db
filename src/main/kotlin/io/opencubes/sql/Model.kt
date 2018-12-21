package io.opencubes.sql

import io.opencubes.sql.Model.*
import org.intellij.lang.annotations.Language
import java.lang.Exception
import java.lang.reflect.AccessibleObject
import java.lang.reflect.InvocationTargetException
import java.sql.Connection
import java.sql.ResultSet
import kotlin.properties.ObservableProperty
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.jvmErasure
import kotlin.reflect.jvm.kotlinProperty

/**
 * Create a model of a table in a database.
 *
 * What is a model? A model is a representation of a SQL table as a class in
 * Kotlin/Java.
 *
 * What makes something a index in the model? A property is defined with
 * val/has only a getter or is marked with the [Index] annotation.
 *
 * What makes something a normal field in the model? A property is defined
 * with var/has a getter and setter or marked with the [Field] annotation.
 *
 * If you have a auto incremented value mark it with [Auto].
 *
 * If any value in the class is not part of the database model mark it with
 * [Exclude].
 *
 * If any property name does not match the name in the database mark it with
 * [SerializedName] annotation and set the name there.
 */
abstract class Model {

  private val db: Database
  private val table: String

  constructor(database: Database, table: String) {
    this.db = database
    this.table = table
  }

  constructor(connection: Connection, table: String) {
    this.db = Database(connection)
    this.table = table
  }

  /**
   * All indices in the model.
   */
  private val indices by lazy {
    val ids = this::class.memberProperties.toMutableList()
        .filterNotHasAnnotation<Exclude>()
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
            it.findAnnotation<Index>() != null -> false
            it is KMutableProperty<*> -> true
            it.findAnnotation<Field>() != null -> true
            else -> false
          }
        }
        .filterIsInstance<KMutableProperty<*>>()
  }

//  /**
//   * Make a query to regather all values to the fields.
//   */
//  fun reset(useAuto: Boolean = true): E {
//    if (indices.isEmpty())
//      throw IndexOutOfBoundsException("there has to be at least 1 identifier")
//    val ids = (if (useAuto) indices else indices.filterNotHasAnnotation<Auto>())
//        .map { (it.findAnnotation<SerializedName>()?.value ?: it.name) to it }
//    val res = db.execute(
//            "SELECT * FROM $table WHERE ${ids.joinToString(" AND ") { "${it.first} = ?" }}",
//            *ids.map { it.second.call(this) }.toTypedArray()
//        ).get()
//
//    res.first()
//    for (field in (fields + indices))
//      setValue(field, res.getObject(getAsName(field)))
//    return (this as E)
//  }

  private fun setValue(field: KProperty<*>, value: Any) {
    val v = if (field.getter.call(this) is Enum<*>) try {
      (field.getter.call(this) as Enum<*>)::class.java.declaredMethods.last().invoke(null, value)
    } catch (_: InvocationTargetException) {
      ((field.getter.call(this) as Enum<*>)::class.java.declaredMethods.find { it.name == "values" }?.invoke(null) as Array<*>)
          .find { it.toString() == value }!!
    }
    else value
    val javaField = field.javaField
    if (javaField != null) {
      val obj = this
      val accessible = javaField.isAccessible
      if (!accessible)
        AccessibleObject.setAccessible(arrayOf(javaField), true)
      val c = javaField.get(obj)
      when (c) {
        is ObservableProperty<*> -> {
          val s = c::class.java.superClass<ObservableProperty<*>>()
              ?: throw Error("Couldn't get observable property or value")
          s.getDeclaredField("value").setValue(c, v)
        }
        is ReadWriteProperty<*, *> -> {
          val s = c::class.java
          s.getDeclaredMethod("setValue").invoke(c, this, field, v)
        }
        else -> javaField.set(obj, v)
      }
      if (!accessible)
        AccessibleObject.setAccessible(arrayOf(javaField), false)
    }
  }

  fun get(): ResultSet {
    val filteredIds = indices.filter {
      try {
        it.getter.call(); true
      } catch (_: Exception) {
        false
      }
    }
    val ids = getAsNames(filteredIds)
    return execute(
        this,
        "SELECT * FROM $table WHERE ${ids.joinToString(" AND ") { "$it = ?" }}",
        filteredIds
    )
  }

  /**
   * Use this to make a update query to the database with all values in the model.
   */
  open fun save() {
    val filteredIds = indices.filter(Model.Companion::hasValueFilter)

    if (filteredIds.size != indices.size) {
      // Create fields
      Model.insert(this, filteredIds.map { it to it.getter.call() }.toTypedArray())
      Model.applyResultSet(this, get())
    } else {
      // Update fields
      val fieldNames = getAsNames(fields).joinToString(", ") { "$it = ?" }
      val identifierNames = getAsNames(indices).joinToString(" AND ") { "$it = ?" }
      Model.execute(this, "UPDATE $table SET $fieldNames WHERE $identifierNames", fields + indices)
    }
  }

  /**
   * Remove current object from the database.
   */
  open fun delete() {
    val identifierNames = getAsNames(indices).joinToString(" AND ") { "$it = ?" }
    Model.execute(this, "DELETE FROM $table WHERE $identifierNames", indices)
  }

  companion object {
    /**
     * Converts a property name into a database name.
     */
    private fun getAsName(property: KProperty<*>) =
        property.findAnnotation<SerializedName>()?.value ?: property.name

    /**
     * Converts a whole list of properties into database names
     */
    private fun getAsNames(list: List<KProperty<*>>) = list.map(::getAsName)

    /**
     * Helper to execute a SQL query with a list of properties instead of values.
     */
    private fun execute(model: Model, @Language("sql") sql: String, params: List<KProperty<*>>) =
        model.db.execute(sql, *params.map { it.call(model) }.toTypedArray()).get()

    /**
     * Checks if the property has a value.
     */
    private fun hasValueFilter(property: KProperty<*>) =
        try {
          property.getter.call(); true
        } catch (_: Exception) {
          false
        }

    /**
     * Fill a the [instance] with the [resultSet] fields.
     *
     * @param instance The model instance to fill in.
     * @param resultSet The result set to use.
     * @param M The [Model] child type.
     * @return [instance]
     */
    fun <M : Model> applyResultSet(instance: M, resultSet: ResultSet): M {
      applyResultSet(instance as Model, resultSet)
      return instance
    }

    private fun applyResultSet(instance: Model, resultSet: ResultSet) {
      val meta = resultSet.metaData
      val columns = List(meta.columnCount) { meta.getColumnName(it + 1) }
      instance::class.memberProperties
          .filterNotHasAnnotation<Exclude>()
          .asSequence()
          .map { it to getAsName(it) }
          .filter { it.second in columns }
          .forEach {
            val (prop, name) = it
            instance.setValue(prop, resultSet.getObject(name))
          }
    }

    /**
     * If the primary constructor takes no arguments then you can use this to
     * createTable a model from a result set directly.
     */
    fun <M : Model> fromResultSet(clazz: KClass<M>, resultSet: ResultSet): M {
      val ctor = clazz.primaryConstructor
      val instance = if (ctor != null) {
        val a = ctor.isAccessible
        if (!a) ctor.isAccessible = true
        ctor.isAccessible = true
        val instance = ctor.call()
        if (!a) ctor.isAccessible = false
        instance
      } else {
        clazz.java.newInstance() as M
      }

      fromResultSet(instance, resultSet)
      return instance
    }

    private fun fromResultSet(instance: Model, resultSet: ResultSet) {
      val meta = resultSet.metaData
      val columns = List(meta.columnCount) { meta.getColumnName(it + 1) }
      (instance.fields + instance.indices)
          .filter { getAsName(it) in columns }
          .forEach {
            instance.setValue(it, resultSet.getObject(getAsName(it)))
          }
    }

    /**
     * If the primary constructor takes no arguments then you can use this to
     * createTable a model from a result set directly.
     */
    @JvmStatic
    inline fun <reified M : Model> fromResultSet(resultSet: ResultSet) = fromResultSet(M::class, resultSet)

    @JvmStatic
    inline fun <reified M : Model> insert(vararg identifiers: Pair<KProperty<*>, Any>, noinline block: (M.() -> Unit)? = null): M {
      val ctor = M::class.primaryConstructor ?: throw Error()
      val a = ctor.isAccessible
      if (!a) ctor.isAccessible = true
      val instance = ctor.call()
      if (!a) ctor.isAccessible = false
      block?.invoke(instance)
      for ((key, value) in identifiers)
        key.javaField?.setValue(instance, value)
      insert(instance)
      return instance
    }

    /**
     * Inserts a model instance into the database table.
     */
    @JvmStatic
    fun <M : Model> insert(model: M): M {
      insert(model as Model)
      return model
    }

    @JvmStatic
    private fun insert(model: Model, fields: Array<out Pair<KProperty<*>, Any?>> = emptyArray()) {
      for ((key, value) in fields)
        key.javaField?.setValue(model, value)
      val keys = listOf(
          *model.indices.filter(::hasValueFilter).toTypedArray(),
          *model.fields.toTypedArray()
      )
      val insertNames = getAsNames(keys).joinToString { "`$it`" }
      val insertPoints = "?, ".repeat(keys.size).dropLast(2)
      execute(model, "INSERT INTO ${model.table} ($insertNames) VALUES ($insertPoints)", keys)
    }

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
    fun createTable(model: KClass<out Model>, scheme: String?) {
      model.primaryConstructor?.isAccessible = true
      val instance = model.primaryConstructor?.call()
      model.primaryConstructor?.isAccessible = false
      if (instance == null)
        throw IllegalStateException("Models must have a constructor without any arguments.")

      val table = instance.table
      val whole = if (scheme == null) table else "$scheme.$table"
      val db = instance.db

      val indices = instance.indices
      val fields = instance.fields

      val index = indices.joinToString { getAsName(it).replace("\$delegate", "") }.trim()
      val sqlFields = (indices + fields).fold("") { acc, prop ->
        val name = getAsName(prop)
        val type = prop.returnType.jvmErasure
        val nullable = if (prop.returnType.isMarkedNullable) "" else " NOT NULL"
        val auto =
            if (prop.findAnnotation<Auto>() != null)
              if (db.dialect.startsWith("sqlite")) " PRIMARY KEY AUTOINCREMENT"
              else " PRIMARY KEY AUTO_INCREMENT"
            else ""
        val typeName = " " + when (type) {
          String::class -> "TEXT"
          Int::class, Integer::class -> "INT"
          Long::class -> "BIGINT"
          Short::class -> "SMALLINT"
          Byte::class -> "TINYINT"
          else -> {
            val values = type.members.find { it.name == "values" }
            if (values != null) {
              (values.call() as Array<*>).joinToString(" ", "ENUM (", ")") { "'$it'" }
            } else ""
          }
        }
        "$acc $name$typeName$nullable$auto,"
      }.trim()
      val foreignKeys = (indices + fields).mapNotNull { it.javaField }.filter { it.type === DBReference::class.java }.fold("") { acc, field ->
        field.isAccessible = true
        val ref = field.get(instance) as DBReference<*>
        field.isAccessible = false
        val ctor = ref.field.javaField?.declaringClass?.kotlin?.primaryConstructor ?: return@fold acc
        val a = ctor.isAccessible
        if (!a) ctor.isAccessible = true
        val res = ctor.call() as Model
        if (!a) ctor.isAccessible = false

        val wholeOther = if (scheme == null) res.table else "$scheme.${res.table}"
        val keyName = getAsName(field.kotlinProperty!!)
        val refName = getAsName(ref.field)

        return@fold ", FOREIGN KEY ($keyName) REFERENCES $wholeOther ($refName)"
      }.trim()
      db.execute("DROP TABLE IF EXISTS $whole")
      db.execute("CREATE TABLE $whole ($sqlFields INDEX ($index)$foreignKeys)")
    }

    /**
     * Creates a table for the implicitly determined class [M].
     *
     * @see createTable
     */
    @JvmStatic
    inline fun <reified M : Model> createTable(scheme: String? = null) = createTable(M::class, scheme)

    // ---------------------------- Reflection Extensions ------------------------------------

    /**
     * Filter a collection on if the property has a specific annotation ([A])
     */
    private inline fun <reified A : Annotation> Collection<KProperty<*>>.filterNotHasAnnotation() =
        filter { f -> f.annotations.firstOrNull { it is A } == null }

    /**
     * Search for a super class of a specific [T] class.
     *
     * @param maxDepth The max depth to search for the super class.
     */
    private inline fun <reified T> Class<*>.superClass(maxDepth: Int = 10): Class<T>? {
      var depth = 0
      var current = this
      val clazz = T::class.java
      do {
        if (depth == maxDepth)
          return null
        if (current == clazz)
          return current
        current = current.superclass
        depth++
      } while (true)
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

    // -------------------------- Reflection Extensions End ----------------------------------
  }

  override fun hashCode() = fields.fold(31) { acc, f -> acc * (f.getter.call(this)?.hashCode() ?: 1) }

  override fun toString(): String {
    val sb = StringBuilder(this::class.java.simpleName)
    sb.append('(')
    sb.append(fields.joinToString(",") {
      "${it.name}=${it.getter.call(this)}"
    })
    sb.append(')')
    return sb.toString()
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Model) return false

    if (table != other.table) return false
    if (indices != other.indices) return false
    if (fields != other.fields) return false

    return true
  }

  /**
   * Delegate a database value. This delegate can help in creating
   * the table from [Model.createTable] function.
   */
  inline fun <reified T> value(noinline default: (() -> T)? = null) = DBValue(default)

  class DBValue<T>(val default: (() -> T)? = null) : ReadWriteProperty<Model, T> {
    private var value: T? = null
    override fun getValue(thisRef: Model, property: KProperty<*>) = (value ?: default?.invoke())!!
    override fun setValue(thisRef: Model, property: KProperty<*>, value: T) {
      if (this.value is Enum<*> && value is String) {
        val new = (this.value as Enum<*>)::class.java.getDeclaredMethod("valueOf").invoke(null, value)
        DBValue<*>::value.set(this, new)
      } else this.value = value
    }
  }

  /**
   * Reference a property from a different model that this model depends on.
   */
  inline fun <reified T> reference(field: KProperty1<out Model, T>) = DBReference(field)

  class DBReference<T>(val field: KProperty1<out Model, T>) : ReadWriteProperty<Model, T> {
    private var value: T? = null
    override fun getValue(thisRef: Model, property: KProperty<*>) = value!!
    override fun setValue(thisRef: Model, property: KProperty<*>, value: T) {
      if (this.value is Enum<*> && value is String) {
        val new = (this.value as Enum<*>)::class.java.getDeclaredMethod("valueOf").invoke(null, value)
        DBReference<*>::value.set(this, new)
      } else this.value = value
    }
  }

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

  /**
   * If the property name and the name in the database differs
   * use this annotation to specify what it is.
   */
  @Retention(AnnotationRetention.RUNTIME)
  @Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
  @MustBeDocumented
  annotation class SerializedName(val value: String)
}
