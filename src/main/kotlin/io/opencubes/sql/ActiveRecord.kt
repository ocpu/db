package io.opencubes.sql

import io.opencubes.sql.select.CompiledSelectQuery
import io.opencubes.sql.select.Order
import io.opencubes.sql.select.SelectQueryBuilder
import java.io.InvalidClassException
import java.sql.Date
import java.sql.SQLException
import java.sql.Time
import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.util.*
import kotlin.properties.ReadOnlyProperty
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.jvmErasure
import kotlin.reflect.jvm.kotlinProperty

/**
 * This is a implementation of the [Active Record Pattern](https://en.wikipedia.org/wiki/Active_record_pattern)
 * with the feature of creating a table from the implementing class. You have declarative ways of dealing with
 * references and complex relationships between your models. [ActiveRecord.value], [ActiveRecord.reference],
 * [ActiveRecord.referenceMany], [ActiveRecord.receiveMany]
 */
abstract class ActiveRecord private constructor(table: String?, private val _database: Database?) {
  val table: String = table ?: tableCache.computeIfAbsent(this::class, ::resolveTable)
  val database: Database
    get() = _database ?: Database.global
    ?: throw Exception(
      "The model requires a database connection to use when executing queries. Either set the database " +
        "by setting it in the constructor or set the Database.global variable"
    )

  /**
   * Creates a database model with a specified table name and database connection.
   *
   * @param database The database connection to use when executing queries.
   * @param table The table this class represents.
   */
  constructor(database: Database, table: String) : this(table, database)

  /**
   * Creates a database model with a generated table name from the declaring class.
   *
   * @param database The database connection to use when executing queries.
   */
  constructor(database: Database) : this(null, database)

  /**
   * Creates a database model with a specified table name. Queries are executed
   * with the [Database.global] database.
   *
   * @param table The table this class represents.
   */
  constructor(table: String) : this(table, null)

  /**
   * Creates a database model with a generated name (from the class name) and that
   * uses the [Database.global] database to execute queries.
   */
  constructor() : this(null, null)

  /**
   * All indices in the model.
   */
  val indices by lazy {
    this::class.memberProperties.asSequence()
      .filter { PropertyWithType::class.java.isAssignableFrom(it.javaField?.type) }
      .filter { it.findAnnotation<Volatile>() == null }
      .filter { it.findAnnotation<Exclude>() == null }
      .filter { it !is KMutableProperty<*> || it.findAnnotation<Index>() != null }
      .toList()
  }

  /**
   * All fields in the model excluding indices.
   */
  val fields by lazy {
    this::class.memberProperties.asSequence()
      .filter { PropertyWithType::class.java.isAssignableFrom(it.javaField?.type) }
      .filter { it.findAnnotation<Volatile>() == null }
      .filter { it.findAnnotation<Exclude>() == null }
      .filter { it.findAnnotation<Index>() == null }
      .filter { it.findAnnotation<Field>() ?: it is KMutableProperty<*> }
      .toList()
  }

  val primaryKeyProperty: KProperty<*> by lazy {
    this::class.memberProperties.find { it.javaField?.type?.name == "io.opencubes.sql.ActiveRecord\$DBPrimaryKey" }
      ?: throw Exception(
        "The ${this::class.java.simpleName} ($table) model needs to have one primary key like: val id by primaryKey()"
      )
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
      if (database
          .select(*getAsNames(indices + fields).toTypedArray())
          .from(table)
          .where(getAsNames(indices))
          .execute(valueFields.map(::getValue))
          .hasResult) {
        try {
          database.update(
            table = table,
            columns = getAsNames(valueFields),
            whereColumns = getAsNames(indices),
            values = (valueFields + indices).map(::getValue)
          )
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
      .select(*getAsNames(indices + fields).toTypedArray())
      .from(table)
      .where(getAsNames(filteredFields))
      .execute(filteredFields.map(::getValue))
      .fetchInto(this)
  }

  /**
   * Remove current entry from the database.
   */
  open fun delete() = database.deleteFrom(table, indices.map(::getAsName), indices.map(::getValue))

  private fun getValue(property: KProperty<*>) = property.getter.accessible { call(this@ActiveRecord) }

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

    private val tableCache = mutableMapOf<KClass<*>, String>()
    private val nameCache = mutableMapOf<KProperty<*>, String>()

    @Suppress("UNCHECKED_CAST")
    private fun resolveName(property: KProperty<*>): String {
      val name = property.findAnnotation<SerializedName>()?.value
      if (name != null) return name

      val instance = ActiveRecord.getInjectableInstance(property.javaField!!.declaringClass!!.kotlin as KClass<out ActiveRecord>)
      return when (val delegate = property.javaField?.accessible { get(instance) }) {
        is DBReference<*> -> "${property.name}_${getAsName(delegate.primaryKey)}"
        is DBReferenceNull<*> -> "${property.name}_${getAsName(delegate.primaryKey)}"
        else -> Regex("([a-z])([A-Z])").replace(property.name) { res ->
          val (fc, nc) = res.destructured
          "${fc}_$nc"
        }.toLowerCase()
      }
    }

    private fun resolveTable(klass: KClass<*>): String {
      val name = Regex("([a-z])([A-Z])").replace(klass.java.simpleName) { res ->
        val (fc, nc) = res.destructured
        "${fc}_$nc"
      }.toLowerCase()
      return if (
        name.endsWith("ss") || name.endsWith("s") ||
        name.endsWith("sh") || name.endsWith("x") ||
        name.endsWith("z")
      ) "${name}es"
      else "${name}s"
    }

    /**
     * Converts a property name into a database name.
     */
    fun getAsName(property: KProperty<*>): String = nameCache.computeIfAbsent(property, ::resolveName)

    /**
     * Converts a whole list of properties into database names
     */
    fun getAsNames(list: List<KProperty<*>>) = list.map(::getAsName)

    // ----------------------------- Instance creations --------------------------------------

    /**
     * This function will create a instance of the specified [kClass].
     */
    @JvmStatic
    fun <AR : ActiveRecord> getInjectableInstance(kClass: KClass<out AR>): AR {
      return try {
        kClass.java.newInstance()
      } catch (e: InstantiationException) {
        kClass.primaryConstructor?.accessible {
          call()
        } ?: kClass.java.newInstance() as AR
      }
    }

    /**
     * Find a specific row by the [column] that has the provided [value]. It all
     * should be taken from the [model].
     *
     * `ActiveRecord.find(User::class, "id", 1)`
     *
     * @param model The model to put the found row into.
     * @param column The column name to search in.
     * @param value The value to look for.
     * @return The encapsulated row in the model specified or null if
     *    it was not found.
     */
    @JvmStatic
    fun <AR : ActiveRecord> find(model: KClass<out AR>, column: String, value: Any?): AR? {
      val instance = getInjectableInstance(model)

      return instance.database
        .select(*getAsNames(instance.indices + instance.fields).toTypedArray())
        .from(instance.table)
        .where("$column = ?")
        .execute(value)
        .fetchInto(instance)
    }

    /**
     * Find a specific row by the [column] that has the provided [value]. It all
     * should be taken from the [AR] model.
     *
     * `ActiveRecord.find<User>("id", 2)`
     *
     * @param column The column name to search in.
     * @param value The value to look for.
     * @return The encapsulated row in the model specified or null if
     *    it was not found.
     */
    @JvmStatic
    inline fun <reified AR : ActiveRecord> find(column: String, value: Any?) = find(AR::class, column, value)

    /**
     * Find a specific row by the [column] that has the provided [value]. It all
     * should be taken from the [AR] model.
     *
     * `ActiveRecord.find(User::id, 3)`
     *
     * @param column The column name to search in.
     * @param value The value to look for.
     * @return The encapsulated row in the model specified or null if
     *    it was not found.
     */
    @JvmStatic
    inline fun <reified AR : ActiveRecord> find(column: KProperty1<AR, *>, value: Any?): AR? =
      find(AR::class, getAsName(column), value)

    /**
     * Find a specific [model] row by the primary key column with the provided [value].
     *
     * `ActiveRecord.find(User::class, 4)`
     *
     * @param model The model to put the found row into.
     * @param value The value to look for.
     * @return The encapsulated row in the model specified or null if
     *    it was not found.
     */
    @JvmStatic
    fun <AR : ActiveRecord> find(model: KClass<AR>, value: Any?): AR? {
      val instance = getInjectableInstance(model)
      return find(model, getAsName(instance.primaryKeyProperty), value)
    }

    /**
     * Find a specific [AR] model row by the primary key column with the provided [value].
     *
     * `ActiveRecord.find(User::class, 5)`
     *
     * @param value The value to look for.
     * @return The encapsulated row in the model specified or null if
     *    it was not found.
     */
    @JvmStatic
    inline fun <reified AR : ActiveRecord> find(value: Any?) = find(AR::class, value)

    /**
     * Find all rows in a [column] that has the provided [value]. All the rows found will
     * be injected into the [model] specified.
     *
     * @param model The model to put the found row into.
     * @param column The column to search in.
     * @param value The value to look for.
     * @return All the found rows mapped to the [model].
     */
    @JvmStatic
    fun <AR : ActiveRecord> findAll(model: KClass<AR>, column: KProperty1<AR, *>, value: Any?): List<AR> {
      val instance = getInjectableInstance(model)
      return instance.database
        .select(*getAsNames(instance.indices + instance.fields).toTypedArray())
        .from(instance.table)
        .where("`${getAsName(column)}` = ?")
        .execute(value)
        .fetchAllInto(model)
    }

    /**
     * Map a list of [values] to a specific [model] or leave null. The [values] will be
     * looked up with the specified [column].
     *
     * @param model The model to put the found row into.
     * @param column The column to search in.
     * @param values The values to look for.
     * @return All the found rows mapped to the [model] or `null` in the same indices the
     *    original [values] where in. If the resulting query did not return any values the
     *    list will be empty.
     */
    @JvmStatic
    fun <AR : ActiveRecord> findAll(model: KClass<AR>, column: KProperty1<AR, *>, values: List<Any?>): List<AR?> {
      val instance = getInjectableInstance(model)
      val columnName = getAsName(column)
      return instance.database
        .select(*getAsNames(instance.indices + instance.fields).toTypedArray())
        .from(instance.table)
        .where("`$columnName` IN (${"?, ".repeat(values.size).dropLast(2)})")
        .execute(values)
        .fetch { result ->
          val list = (0 until values.size).map { null as AR? }.toMutableList()
          for (res in result) {
            val value = res[columnName]
            val index = values.indexOf(value)
            if (index == -1)
              continue
            list[index] = res.inject(getInjectableInstance(model))
          }
          list
        } ?: emptyList()
    }

    /**
     * Map a list of [values] to a specific [AR] model or leave null. The [values] will be
     * looked up with the specified [column].
     *
     * @param column The column to search in.
     * @param values The values to look for.
     * @return All the found rows mapped to the [AR] model or `null` in the same indices the
     *    original [values] where in. If the resulting query did not return any values the
     *    list will be empty.
     */
    @JvmStatic
    inline fun <reified AR : ActiveRecord> findAll(column: KProperty1<AR, *>, values: List<Any?>): List<AR?> =
      findAll(AR::class, column, values)

    /**
     * Find all rows in a [column] that is like the provided [value]. All the rows found will
     * be injected into the [model] specified.
     *
     * @param model The model to put the found row into.
     * @param column The column to search in.
     * @param value The value to look for.
     * @return All the found rows mapped to the [model].
     */
    @JvmStatic
    fun <AR : ActiveRecord> findAllLike(model: KClass<AR>, column: KProperty1<AR, *>, value: Any?): List<AR> {
      val instance = getInjectableInstance(model)
      val columnName = getAsName(column)
      return instance.database
        .select(*getAsNames(instance.indices + instance.fields).toTypedArray())
        .from(instance.table)
        .where("`$columnName` LIKE ?")
        .execute(value)
        .fetchAllInto(model)
    }

    /**
     * Find all rows in a [column] that is like the provided [value]. All the rows found will
     * be injected into the [AR] model specified.
     *
     * @param column The column to search in.
     * @param value The value to look for.
     * @return All the found rows mapped to the [AR] model.
     */
    @JvmStatic
    inline fun <reified AR : ActiveRecord> findAllLike(column: KProperty1<AR, *>, value: Any?): List<AR> =
      findAllLike(AR::class, column, value)

    // -------------------------------- Table Helpers ----------------------------------------

    private fun findType(klass: KClass<*>, database: Database): String {
      return when (klass) {
        String::class -> if (database.dialect.startsWith("sqlite")) "TEXT" else "MEDIUMTEXT"
        Int::class, Integer::class -> "INTEGER"
        Long::class -> "BIGINT"
        Short::class -> "SMALLINT"
        Byte::class -> "TINYINT"
        Timestamp::class -> "TIMESTAMP"
        Time::class -> "TIME"
        Date::class -> "DATE"
        else -> {
          val values = klass.java.enumConstants
          when {
            values is Array<*> -> values.joinToString(" ", "ENUM (", ")") value@{
              if (it !is Enum<*>) return@value "'$it'"
              klass.java.getField(it.name)
                .getAnnotation(SerializedName::class.java)?.value ?: "'$it'"
            }
            klass.isSubclassOf(ActiveRecord::class) -> {
              @Suppress("UNCHECKED_CAST")
              val ii = ActiveRecord.getInjectableInstance(klass as KClass<ActiveRecord>)
              findType(ii.primaryKeyProperty.returnType.classifier as? KClass<*> ?: return "", database)
            }
            else -> ""
          }
        }
      }
    }

    private fun getPropertyTypeSQL(property: KProperty<*>, database: Database): String {
      val isSqlite = database.dialect.startsWith("sqlite")
      return property.findAnnotation<Type>()?.let type@{
        val base = if (isSqlite) it.type.sqliteType else it.type.sqlType
        val usesSize = if (isSqlite) it.type.sqliteUsesSize else it.type.usesSize
        if (usesSize) {
          return@type if (it.size2 != -1)
            "$base(${it.size1},${it.size2})"
          else "$base(${it.size1})"
        } else return@type base
      } ?: findType(property.returnType.jvmErasure, database)
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
     */
    @JvmStatic
    fun create(model: KClass<out ActiveRecord>) {
      getInjectableInstance(model).database.execute(genSQL(model))
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
     */
    @JvmStatic
    inline fun <reified AR : ActiveRecord> create() = create(AR::class)

    @JvmStatic
    fun create(vararg tables: KClass<out ActiveRecord>) {
      if (tables.isNotEmpty())
        getInjectableInstance(tables[0]).database.execute(genSQL(*tables))
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun genSQL(model: KClass<out ActiveRecord>): String {
      val scheme: String? = null
      val instance = getInjectableInstance(model)

      val table = if (scheme == null) instance.table else "$scheme.${instance.table}"
      val allFields = instance.indices + instance.fields

      val index = instance.indices.joinToString { getAsName(it) }.trim()

      val sqlFields = allFields
        .joinToString(", ") {
          val delegate = (it as KProperty1<ActiveRecord, *>).accessible {
            getDelegate(instance) as ICreateSQL
          }
          delegate.getCreationSQL(instance, it)
        }
        .trim()

      val foreign = allFields
        .map foreign@{
          val ref = (it as KProperty1<ActiveRecord, *>).accessible {
            getDelegate(instance)
          }
          val keyName = getAsName(it)
          return@foreign when (ref) {
            is DBReference<*> -> {
              val res = ActiveRecord.getInjectableInstance(ref.klass)
              val keyTable = if (scheme == null) res.table else "$scheme.${res.table}"
              val refName = getAsName(ref.primaryKey)
              "FOREIGN KEY ($keyName) REFERENCES $keyTable ($refName)"
            }
            is DBReferenceNull<*> -> {
              val res = ActiveRecord.getInjectableInstance(ref.klass)
              val keyTable = if (scheme == null) res.table else "$scheme.${res.table}"
              val refName = getAsName(ref.primaryKey)
              "FOREIGN KEY ($keyName) REFERENCES $keyTable ($refName)"
            }
            else -> ""
          }
        }
        .filter { it.isNotBlank() }
        .joinToString(",\n  ")

      val foreignLine =
        if (foreign.isBlank()) ""
        else ",\n  \n  $foreign"

      val indicesLine =
        if (index.isBlank() || instance.database.dialect.startsWith("sqlite")) ""
        else ",\n  \n  INDEX ($index)"

      val additional = model.memberProperties.mapNotNull { property ->
        if (property in allFields) return@mapNotNull null
        val delegate = (property as KProperty1<ActiveRecord, *>).accessible {
          getDelegate(instance) as? ICreateSQL?
        } ?: return@mapNotNull null

        delegate.getCreationSQL(instance, property)
      }.toSet()

      return "CREATE TABLE $table (\n  ${sqlFields.replace(", ", ",\n  ")}$indicesLine$foreignLine\n);" +
        (if (additional.isNotEmpty()) "\n\n" else "") + additional.joinToString("\n\n")
    }

    @JvmStatic
    inline fun <reified AR : ActiveRecord> genSQL() = genSQL(AR::class)

    @JvmStatic
    fun genSQL(vararg tables: KClass<out ActiveRecord>): String {
      return if (tables.isEmpty()) "" else tables
        .map(::genSQL)
        .flatMap { it.split("\n\n") }
        .toSet()
        .sortedWith(Comparator { a, b ->
          val aLink = a.endsWith("-- Link table")
          val bLink = b.endsWith("-- Link table")
          if (aLink && !bLink) 1
          else if (!aLink && bLink) -1
          else 0
        })
        .joinToString("\n\n")
    }

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
        ReadOnlyProperty<*, *>::getValue.call(get(instance), instance, kotlinProperty!!)
      } else get(instance)
      if (!a)
        isAccessible = false
    }
  }

  // -------------------------------- Object overrides ---------------------------------------

  override fun hashCode() = fields.fold(31) { acc, f -> acc * (f.getter.call(this)?.hashCode() ?: 1) }

  override fun toString() = this::class.java.simpleName + (indices + fields)
    .joinToString(",", prefix = "(", postfix = ")") {
      if (!hasValue(it))
        return@joinToString "${it.name}=<unknown>"
      @Suppress("UNCHECKED_CAST")
      val prop = it as KProperty1<Any, Any>
      val a = prop.isAccessible
      if (!a) prop.isAccessible = true
      val delegate = prop.getDelegate(this@ActiveRecord)
      if (!a) prop.isAccessible = false
      delegate ?: "${it.name}=<unknown>"
      if (delegate !is PropertyWithType<*> || delegate.type == PropertyWithType.Type.REFERENCE)
        return@joinToString "${it.name}=<abbreviated>"
      "${it.name}=${getValue(it)}".replace("\n", "\\n").replace("\t", "\\t")
    }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ActiveRecord) return false

    if (table != other.table) return false
    if (indices != other.indices) return false
    if (fields != other.fields) return false

    if (!this.hasValue(primaryKeyProperty)) return false
    if (!other.hasValue(primaryKeyProperty)) return false

    return this.getValue(primaryKeyProperty) == other.getValue(primaryKeyProperty)
  }

// -------------------------------- Delegate functions -------------------------------------

  /**
   * The primary key value of the table.
   */
  protected fun primaryKey() = DBPrimaryKey()

  /**
   * If you want to specify a value column in a table.
   *
   * This will define a value that can be translated to a table column.
   * It could be any regular type like [String], [Int], enum, etc.
   *
   * If the underlying name of the column is unsatisfactory you can
   * specify it with the [SerializedName] annotation.
   *
   * @param T The type of the value.
   * @param default The default value factory function.
   */
  @Suppress("UNCHECKED_CAST")
  protected inline fun <reified T : Any?> value(noinline default: (() -> T)? = null): PropertyWithType<T> =
    if (null is T) DBValueNullable<T>() as PropertyWithType<T>
    else DBValue(T::class.java, default) as PropertyWithType<T>

  /**
   * Reference a column from another table where the reference can be null
   * if specified.
   *
   * An example is if you have users and posts and you want to link the
   * post author with a user. Then you should specify the relation like
   * this: `var author by reference<User>()`. If a post can be a reply
   * to another post you would want to link a post to a post. Like this:
   * `var parent by reference<Post?>()`. It is specifying a nullable type
   * as the post might not be a reply to another.
   *
   * If the underlying name of the column is unsatisfactory you can
   * specify it with the [SerializedName] annotation.
   *
   * @param T The type of the referenced ActiveRecord.
   */
  @Suppress("UNCHECKED_CAST")
  protected inline fun <reified T : ActiveRecord?> reference(): PropertyWithType<T> =
    if (null is T) DBReferenceNull(T::class as KClass<out ActiveRecord>) as PropertyWithType<T>
    else DBReference(T::class as KClass<out ActiveRecord>) as PropertyWithType<T>

  /**
   * Reference a list of possible values. This can be anything that could
   * be in a link table between two tables. Like posts a user has made,
   * messages in a chat channel, or if a user follows/subscribes to another
   * user.
   *
   * The resulting list is a [DatabaseList] that has a [DatabaseList.refresh]
   * method that can be used to have the list update its contents.
   *
   * @param klass The kotlin class type of the reference class.
   * @param table The the name of the link table.
   * @param key The column name that references this table.
   * @param referenceKey The column name that references the other table.
   * @param T The type of the reference ActiveRecord.
   * @return A delegate for a semi live list of the values.
   */
  protected fun <T : ActiveRecord> referenceMany(klass: KClass<T>, table: String? = null, key: String? = null, referenceKey: String? = null) =
    DBManyReference(
      lazy { klass },
      lazy { table },
      lazy { key },
      lazy { referenceKey },
      lazy { ActiveRecord.getInjectableInstance(klass) }
    )

  /**
   * Reference a list of possible values. This can be anything that could
   * be in a link table between two tables. Like posts a user has made,
   * messages in a chat channel, or if a user follows/subscribes to another
   * user.
   *
   * The resulting list is a [DatabaseList] that has a [DatabaseList.refresh]
   * method that can be used to have the list update its contents.
   *
   * @param table The the name of the link table.
   * @param key The column name that references this table.
   * @param referenceKey The column name that references the other table.
   * @param T The type of the reference ActiveRecord.
   * @return A delegate for a semi live list of the values.
   */
  protected inline fun <reified T : ActiveRecord> referenceMany(table: String? = null, key: String? = null, referenceKey: String? = null) =
    DBManyReference(
      lazy { T::class },
      lazy { table },
      lazy { key },
      lazy { referenceKey },
      lazy { ActiveRecord.getInjectableInstance(T::class) }
    )

  /**
   * Reference a list of possible values. This can be anything that could
   * be in a link table between two tables. Like posts a user has made,
   * messages in a chat channel, or if a user follows/subscribes to another
   * user.
   *
   * The resulting list is a [DatabaseList] that has a [DatabaseList.refresh]
   * method that can be used to have the list update its contents.
   *
   * @param reverse The property to use as a reference but reverse the key
   *      and reference key
   * @param T The type of the reference ActiveRecord.
   * @return A delegate for a semi live list of the values.
   */
  protected fun <T : ActiveRecord, R : ActiveRecord> referenceMany(reverse: KProperty1<T, DatabaseList<R>>): DBManyReference<T> {
    val klass = reverse.javaField!!.declaringClass!!.kotlin as KClass<T>
    val delegate by lazy {
      val instance = ActiveRecord.getInjectableInstance(klass)
      reverse.accessible { getDelegate(instance) } as DBManyReference<R>
    }
    return DBManyReference(
      lazy { klass },
      lazy { delegate.table.value },
      lazy { delegate.referenceKey.value },
      lazy { delegate.key.value },
      lazy { ActiveRecord.getInjectableInstance(klass) }
    )
  }

  /**
   * Reference all/many of the records the [reference] is referencing by the
   * id column. Like if you want to get all replies of a post you may have used
   * the advise from the above [ActiveRecord.reference] doc and used a parent
   * column. You could get all the replies like this: `val replies by
   * referenceMany(Post::parent)`.
   *
   * @param reference The reference column.
   * @param limit Limit the amount of items in the list.
   * @return A delegate for a semi live list of the values.
   */
  protected fun <T : ActiveRecord> referenceMany(reference: KProperty1<T, Any?>, limit: Int? = null) =
    DBOneToManyReference(reference, limit)

  /**
   * Retrieve a list of [R] from the property that has a DatabaseList ([by])
   * resolved by the property in the [from] parameter.
   *
   * A example is if you have users that can follow eachother and that the users
   * can make posts. How do you retrieve the posts of all you follow? You can do
   * it like this: `val timeline by receiveMany(from = Post::author, by =
   * User::follows)`.
   *
   * @param from The property that will resolve the results into the correct model.
   * @param by Where you can get the list of values.
   * @param R The return type.
   */
  protected inline fun <reified R, reified T> receiveMany(
    from: KMutableProperty1<in R, in T>,
    by: KProperty1<out ActiveRecord, DatabaseList<in T>>
  ) where R : ActiveRecord, T : ActiveRecord =
    DBReceiveList(from, by)

// -------------------------------- Delegate classes ---------------------------------------

  protected class DBPrimaryKey : DBValue<Int>(Int::class.java, null) {
    override fun getCreationSQL(instance: ActiveRecord, property: KProperty<*>): String {
      val auto =
        if (property.findAnnotation<Auto>() != null && !instance.database.dialect.startsWith("sqlite")) " AUTO_INCREMENT"
        else ""
      return super.getCreationSQL(instance, property) + "$auto PRIMARY KEY"
    }
  }

  protected open class DBValue<T>(
    val valueClass: Class<T>,
    val default: (() -> T)? = null
  ) : PropertyWithType<T?>, IInjectable, ICreateSQL {
    /** The current value */
    private var value: T? = null
    override val type = PropertyWithType.Type.VALUE

    /***/
    override fun getValue(thisRef: ActiveRecord, property: KProperty<*>): T = (value ?: default?.invoke())!!

    /***/
    override fun setValue(thisRef: ActiveRecord, property: KProperty<*>, value: T?) {
      if (value == null)
        throw NullPointerException("value was null")
      if (this.value is Enum<*> && value is String) {
        val new = (this.value as Enum<*>)::class.java.getDeclaredMethod("valueOf").invoke(null, value)
        DBValue<*>::value.set(this, new)
      } else this.value = value
    }

    companion object {
      val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
      val dateFormat = SimpleDateFormat("yyyy-MM-dd")
      val timeFormat = SimpleDateFormat("HH:mm:ss")
    }

    @Suppress("UNCHECKED_CAST")
    override fun inject(value: Any?) {
      if (this.value is Enum<*> && value is String) {
        val new = (this.value as Enum<*>)::class.java.getDeclaredMethod("valueOf").invoke(null, value)
        DBValue<*>::value.set(this, new)
      } else if (value != null) this.value = when (valueClass) {
        Timestamp::class.java -> Timestamp(timestampFormat.parse(value as String).time) as T
        Date::class.java -> Date(dateFormat.parse(value as String).time) as T
        Time::class.java -> Time(timeFormat.parse(value as String).time) as T
        else -> value as T
      }
    }

    override fun getCreationSQL(instance: ActiveRecord, property: KProperty<*>): String =
      "${getAsName(property)} ${getPropertyTypeSQL(property, instance.database)} NOT NULL"
  }

  protected class DBValueNullable<T> : PropertyWithType<T?>, ICreateSQL {
    /** The current value */
    private var value: T? = null
    override val type = PropertyWithType.Type.VALUE

    /***/
    override fun getValue(thisRef: ActiveRecord, property: KProperty<*>): T? = value

    /***/
    override fun setValue(thisRef: ActiveRecord, property: KProperty<*>, value: T?) {
      if (value == null) {
        this.value = null
        return
      }
      if (this.value is Enum<*> && value is String) {
        val new = (this.value as Enum<*>)::class.java.getDeclaredMethod("valueOf").invoke(null, value)
        DBValueNullable<*>::value.set(this, new)
      } else this.value = value
    }

    override fun getCreationSQL(instance: ActiveRecord, property: KProperty<*>): String =
      "${getAsName(property)} ${getPropertyTypeSQL(property, instance.database)}"
  }

  protected class DBReference<T : ActiveRecord>(val klass: KClass<out T>) : PropertyWithType<T?>, IInjectable, ICreateSQL {
    val primaryKey: KProperty<*>
    private val select: CompiledSelectQuery
    private var value: Any? = null
    override val type = PropertyWithType.Type.REFERENCE

    init {
      val instance = ActiveRecord.getInjectableInstance(klass)
      primaryKey = instance.primaryKeyProperty
      select = instance.database
        .select(*getAsNames(instance.indices + instance.fields).map { "t1.$it" }.toTypedArray())
        .from("${instance.table} t1")
        .where("t1.${getAsName(primaryKey)} = ?")
        .limit(1)
        .compile()
    }

    /***/
    override fun getValue(thisRef: ActiveRecord, property: KProperty<*>): T = select.execute(value).fetchInto(klass)!!

    /***/
    override fun setValue(thisRef: ActiveRecord, property: KProperty<*>, value: T?) {
      this.value = value?.getValue(primaryKey) ?: throw NullPointerException("value was null")
    }

    override fun inject(value: Any?) {
      this.value = value
    }

    override fun getCreationSQL(instance: ActiveRecord, property: KProperty<*>): String =
      "${getAsName(property)} ${getPropertyTypeSQL(property, instance.database)} NOT NULL"
  }

  protected class DBReferenceNull<T : ActiveRecord>(val klass: KClass<out T>) : PropertyWithType<T?>, IInjectable, ICreateSQL {
    override val type = PropertyWithType.Type.REFERENCE
    var value: Any? = null
    val instance by lazy { ActiveRecord.getInjectableInstance(klass) }
    val primaryKey by lazy { instance.primaryKeyProperty }
    val select by lazy {
      instance.database
        .select(*getAsNames(instance.indices + instance.fields).map { "t1.$it" }.toTypedArray())
        .from("${instance.table} t1")
        .where("t1.${getAsName(primaryKey)} = ?")
        .limit(1)
        .compile()
    }

    /***/
    override fun getValue(thisRef: ActiveRecord, property: KProperty<*>): T? =
      if (value == null) null else select.execute(value).fetchInto(klass)

    /***/
    override fun setValue(thisRef: ActiveRecord, property: KProperty<*>, value: T?) {
      this.value = value?.getValue(primaryKey)
    }

    override fun inject(value: Any?) {
      this.value = value
    }

    override fun getCreationSQL(instance: ActiveRecord, property: KProperty<*>): String =
      "${getAsName(property)} ${getPropertyTypeSQL(property, instance.database)}"
  }

  class DatabaseList<T : ActiveRecord>(private val ar: ActiveRecord,
                                       private val select: SelectQueryBuilder,
                                       private val linkTable: String,
                                       private val fromName: String,
                                       private val columns: Array<String>,
                                       private val fromProperty: KProperty<*>,
                                       private val toProperty: KProperty<*>,
                                       private val resultClass: KClass<T>) : MutableList<T> {

    private val cache = LinkedList<T>()

    init {
//      // Create the link table if not exists
//      val sql = "CREATE TABLE IF NOT EXISTS $linkTable (${getPropertySQL(fromName, from)},${getPropertySQL(toName, to)})"
//      ar.database.execute(sql)
      // Get all initial elements
      refresh()
    }

    /**
     * Refreshes the element cache to what it currently is in the database.
     * Does not run if the primary property has no value.
     */
    fun refresh() {
      if (!ar.hasValue(fromProperty))
        return
      cache.clear()
      cache.addAll(
        select
          .execute(ar.getValue(fromProperty))
          .fetchAllInto(resultClass)
      )
    }

    override val size: Int get() = cache.size

    override fun contains(element: T) = cache.contains(element)

    override fun containsAll(elements: Collection<T>) = cache.containsAll(elements)

    override fun isEmpty() = cache.isEmpty()

    override fun add(element: T): Boolean {
      return try {
        ar.database.insertInto(linkTable, columns, arrayOf(ar.getValue(fromProperty), element.getValue(toProperty)))
        cache.add(element)
      } catch (_: SQLException) {
        false
      }
    }

    override fun addAll(elements: Collection<T>): Boolean {
      return try {
        ar.database.transaction {
          val value = ar.getValue(fromProperty)
          for (element in elements)
            ar.database.insertInto(linkTable, columns, arrayOf(value, element.getValue(toProperty)))
        }
        cache.addAll(elements)
      } catch (_: SQLException) {
        false
      }
    }

    override fun clear() {
      ar.database.deleteFrom(linkTable, listOf(fromName), listOf(ar.getValue(fromProperty)))
      cache.clear()
    }

    override fun iterator() = cache.iterator()

    override fun remove(element: T): Boolean {
      return try {
        ar.database.deleteFrom(linkTable, columns, arrayOf(ar.getValue(fromProperty), element.getValue(toProperty)))
        cache.remove(element)
      } catch (_: SQLException) {
        false
      }
    }

    override fun removeAll(elements: Collection<T>): Boolean {
      return try {
        ar.database.transaction {
          val value = ar.getValue(fromProperty)
          for (element in elements)
            ar.database.deleteFrom(linkTable, columns, arrayOf(value, element.getValue(toProperty)))
        }
        cache.removeAll(elements)
      } catch (_: SQLException) {
        false
      }
    }

    override fun retainAll(elements: Collection<T>) = removeAll(cache.filter { it !in elements })
    override fun add(index: Int, element: T) {
      add(element)
      cache.add(index, element)
      cache.removeLast()
    }

    override fun addAll(index: Int, elements: Collection<T>): Boolean = addAll(elements)

    override fun get(index: Int): T = cache[index]

    override fun indexOf(element: T): Int = cache.indexOf(element)

    override fun lastIndexOf(element: T): Int = cache.lastIndexOf(element)

    override fun listIterator(): MutableListIterator<T> = cache.listIterator()

    override fun listIterator(index: Int): MutableListIterator<T> = cache.listIterator(index)

    override fun removeAt(index: Int): T = cache.removeAt(index).also(ActiveRecord::delete)

    override fun set(index: Int, element: T): T {
      val item = cache.set(index, element)
      item.database.transaction {
        item.delete()
        val id = item.primaryKeyProperty.getter.call(item)
        val delegate = element.primaryKeyProperty.javaField!!.accessible {
          get(element)
        } as ReadWriteProperty<*, *>
        ReadWriteProperty<*, *>::setValue.call(delegate, element, element.primaryKeyProperty, id)
        element.save()
      }
      return item
    }

    override fun subList(fromIndex: Int, toIndex: Int): MutableList<T> = throw Exception("Not supported")

    override fun toString() = cache.toString()
  }

  protected open class DBManyReference<T : ActiveRecord>(val klass: Lazy<KClass<T>>,
                                                         val table: Lazy<String?>,
                                                         val key: Lazy<String?>,
                                                         val referenceKey: Lazy<String?>,
                                                         val instance: Lazy<T>) : ReadOnlyProperty<ActiveRecord, DatabaseList<T>>, ICreateSQL {
    lateinit var list: DatabaseList<T>

    @Suppress("UNCHECKED_CAST")
    override fun getValue(thisRef: ActiveRecord, property: KProperty<*>): DatabaseList<T> {
      if (!::list.isInitialized) {
        val table = table.getValue(this, ::table)
        val key = key.getValue(this, ::key)
        val referenceKey = referenceKey.getValue(this, ::referenceKey)
        val klass = klass.getValue(this, ::klass)
        val instance = instance.getValue(this, ::instance)

        val linkTable = table ?: property.name
        val fromNamePrefix = thisRef.javaClass.simpleName.toLowerCase()
        val fromName = key ?: "${fromNamePrefix}_${getAsName(thisRef.primaryKeyProperty)}"
        val toNamePrefix = if (instance.table == thisRef.table) linkTable else klass.java.simpleName.toLowerCase()
        val toName = referenceKey ?: "${toNamePrefix}_${getAsName(instance.primaryKeyProperty)}"
        val from = thisRef.primaryKeyProperty as KProperty1<ActiveRecord, Any>
        val to = instance.primaryKeyProperty as KProperty1<T, Any>

        val allFields = instance.indices + instance.fields
        val select = instance.database
          .select(*getAsNames(allFields).map { "t1.$it" }.toTypedArray())
          .from("${instance.table} t1")
          .join("$linkTable t2", "t1.${getAsName(to)} = t2.$toName")
          .where("t2.$fromName = ?")

        for ((column, order) in orders)
          select.orderBy(column(), order)

        if (limit != null)
          select.limit(limit!!)

        // Create link table if not exists
        thisRef.database.execute(getCreationSQL(thisRef, property))

        list = DatabaseList(thisRef, select, linkTable, fromName, arrayOf(fromName, toName), from, to, klass)
      }
      return list
    }

    private val orders = mutableSetOf<Pair<() -> String, Order>>()
    private var limit: Int? = null

    fun orderBy(property: KProperty1<T, *>, order: Order = Order.NONE): DBManyReference<T> {
      orders += { getAsName(property) } to order
      return this
    }

    fun limit(amount: Int): DBManyReference<T> {
      limit = amount
      return this
    }

    override fun getCreationSQL(thisRef: ActiveRecord, property: KProperty<*>): String {
      val table = table.getValue(this, ::table)
      val key = key.getValue(this, ::key)
      val referenceKey = referenceKey.getValue(this, ::referenceKey)
      val klass = klass.getValue(this, ::klass)
      val instance = instance.getValue(this, ::instance)

      val linkTable = table ?: property.name

      val properties = listOf(

        Triple(
          key ?: "${
          thisRef.javaClass.simpleName.toLowerCase()
          }_${getAsName(thisRef.primaryKeyProperty)}",
          thisRef.primaryKeyProperty,
          thisRef
        ),

        Triple(
          referenceKey ?: "${
          if (instance.table == thisRef.table) linkTable
          else klass.java.simpleName.toLowerCase()
          }_${getAsName(instance.primaryKeyProperty)}",
          instance.primaryKeyProperty,
          instance
        )

      ).sortedBy(Triple<String, KProperty<*>, ActiveRecord>::first)

      val foreignKeys =
        if (thisRef.database.dialect.startsWith("sqlite")) ""
        else ",\n  \n" +
          "  FOREIGN KEY (${properties[0].first}) REFERENCES ${properties[1].third.table} (${getAsName(properties[1].second)}),\n" +
          "  FOREIGN KEY (${properties[1].first}) REFERENCES ${properties[0].third.table} (${getAsName(properties[0].second)})"

      return "CREATE TABLE IF NOT EXISTS $linkTable (\n" +
        "  ${properties[0].first} ${getPropertyTypeSQL(properties[0].second, properties[0].third.database)} NOT NULL,\n" +
        "  ${properties[1].first} ${getPropertyTypeSQL(properties[1].second, properties[1].third.database)} NOT NULL" +
        foreignKeys +
        "\n); -- Link table"
    }
  }

  protected class DBOneToManyReference<T : ActiveRecord>(referenceKey: KProperty1<T, Any?>, limit: Int?) : ReadOnlyProperty<ActiveRecord, List<T>> {
    @Suppress("UNCHECKED_CAST")
    val klass = referenceKey.javaField?.declaringClass?.kotlin as? KClass<T>
      ?: throw InvalidClassException("Could not find a kotlin class for field?")
    val selector by lazy {
      val instance = ActiveRecord.getInjectableInstance(klass)
      val selector = instance.database
        .select(*getAsNames(instance.indices + instance.fields).toTypedArray())
        .from(instance.table)
        .where("${getAsName(referenceKey)} = ?")
      if (limit != null)
        selector.limit(limit)
      selector.compile()
    }

    override fun getValue(thisRef: ActiveRecord, property: KProperty<*>): List<T> =
      selector.execute(thisRef.getValue(thisRef.primaryKeyProperty)).fetchAllInto(klass)
  }

  protected class DBReceiveList<R : ActiveRecord, T : ActiveRecord>(
    val from: KMutableProperty1<in R, in T>,
    val by: KProperty1<out ActiveRecord, DatabaseList<in T>>
  ) : ReadOnlyProperty<ActiveRecord, List<R>> {

    @Suppress("UNCHECKED_CAST")
    val resKlass by lazy { from.javaField!!.declaringClass.kotlin as KClass<R> }
    val res by lazy { ActiveRecord.getInjectableInstance(resKlass) }

    @Suppress("UNCHECKED_CAST")
    val select by lazy {
      val intermediate = ActiveRecord.getInjectableInstance(by.javaField!!.declaringClass.kotlin as KClass<out ActiveRecord>)
      val listDelegate = (by as KProperty1<Any, Any>).accessible { getDelegate(intermediate) } as DBManyReference<T>

      val instance = listDelegate.instance.getValue(listDelegate, DBManyReference<T>::instance)
      val klass = listDelegate.klass.getValue(listDelegate, DBManyReference<T>::klass)
      val table = listDelegate.table.getValue(listDelegate, DBManyReference<T>::table)
      val key = listDelegate.key.getValue(listDelegate, DBManyReference<T>::key)
      val referenceKey = listDelegate.referenceKey.getValue(listDelegate, DBManyReference<T>::referenceKey)

      val linkTable = table ?: by.name
      val fromNamePrefix = intermediate.javaClass.simpleName.toLowerCase()
      val fromName = key ?: "${fromNamePrefix}_${getAsName(intermediate.primaryKeyProperty)}"
      val toNamePrefix = if (instance.table == intermediate.table) linkTable else klass.java.simpleName.toLowerCase()
      val toName = referenceKey ?: "${toNamePrefix}_${getAsName(instance.primaryKeyProperty)}"
      val select = res.database
        .select(*getAsNames(res.indices + res.fields).map { "t2.$it" }.toTypedArray())
        .from("$linkTable t1")
        .join("${res.table} t2", "t2.${getAsName(from)} = t1.$toName")
        .join("${intermediate.table} t3", "t2.${getAsName(from)} = t3.${getAsName(intermediate.primaryKeyProperty)}")
        .where("t1.$fromName = ?")

      for ((column, order) in orders)
        select.orderBy(column(), order)

      if (limit != null)
        select.limit(limit!!)

      select.compile()
    }

    override fun getValue(thisRef: ActiveRecord, property: KProperty<*>): List<R> =
      select.execute(thisRef.getValue(thisRef.primaryKeyProperty)).fetchAllInto(resKlass)

    private val orders = mutableSetOf<Pair<() -> String, Order>>()
    private var limit: Int? = null

    fun orderBy(property: KProperty1<R, *>, order: Order = Order.NONE): DBReceiveList<R, T> {
      orders += { getAsName(property) } to order
      return this
    }

    fun limit(amount: Int): DBReceiveList<R, T> {
      limit = amount
      return this
    }
  }

  protected interface PropertyWithType<T> : ReadWriteProperty<ActiveRecord, T> {
    val type: Type

    enum class Type { VALUE, REFERENCE }
  }

  protected interface ICreateSQL {
    fun getCreationSQL(instance: ActiveRecord, property: KProperty<*>): String
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

  /**
   * Defines the type of the database column when using [ActiveRecord.create].
   */
  @Retention(AnnotationRetention.RUNTIME)
  @Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
  @MustBeDocumented
  annotation class Type(val type: SQL, val size1: Int = -1, val size2: Int = -1) {
    enum class SQL(val sqlType: String, val usesSize: Boolean, val sqliteType: String, val sqliteUsesSize: Boolean) {

      INT("INT", false, "INTEGER", false),
      INTEGER("INTEGER", false, "INTEGER", false),
      TINYINT("TINYINT", false, "INTEGER", false),
      SMALLINT("SMALLINT", false, "INTEGER", false),
      MEDIUMINT("MEDIUMINT", false, "INTEGER", false),
      BIGINT("BIGINT", false, "INTEGER", false),
      UNSIGNED_BIG_INT("UNSIGNED BIG INT", false, "INTEGER", false),
      INT2("INT2", false, "INTEGER", false),
      INT8("INT8 ", false, "INTEGER", false),

      CHARACTER("CHARACTER", true, "TEXT", false),
      VARCHAR("VARCHAR", true, "TEXT", false),
      VARYING_CHARACTER("VARYING CHARACTER", true, "TEXT", false),
      NCHAR("NCHAR", true, "TEXT", false),
      NATIVE_CHARACTER("NATIVE CHARACTER", true, "TEXT", false),
      NVARCHAR("NVARCHAR", true, "TEXT", false),
      TEXT("TEXT", false, "TEXT", false),
      CLOB("CLOB", false, "TEXT", false),

      REAL("REAL", false, "REAL", false),
      DOUBLE("DOUBLE", false, "REAL", false),
      DOUBLE_PRECISION("DOUBLE PRECISION", false, "REAL", false),
      FLOAT("FLOAT ", false, "REAL", false),

      NUMERIC("NUMERIC", false, "NUMERIC", false),
      DECIMAL("DECIMAL", true, "NUMERIC", false),
      BOOLEAN("BOOLEAN", false, "NUMERIC", false),
      DATE("DATE", false, "NUMERIC", false),
      DATETIME("DATETIME", false, "NUMERIC", false),

    }
  }
}