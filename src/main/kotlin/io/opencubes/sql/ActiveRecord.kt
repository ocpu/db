package io.opencubes.sql

import io.opencubes.sql.delegates.*
import io.opencubes.sql.table.AbstractForeignKey
import io.opencubes.sql.table.AbstractTable
import io.opencubes.sql.table.AbstractTableIndex
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty0
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMembers
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaField

/**
 * This is a implementation of the [Active Record Pattern](https://en.wikipedia.org/wiki/Active_record_pattern)
 * with the feature of creating a table from the implementing class. You have declarative ways of dealing with
 * references and complex relationships between your models. [ActiveRecord.value], [ActiveRecord.reference],
 * [ActiveRecord.referenceMany], [ActiveRecord.receiveMany]
 */
abstract class ActiveRecord private constructor(table: String?, private val _database: Database?) {
  /**
   * The name this model has in the database.
   */
  val table: String = table ?: tableCache.computeIfAbsent(this::class, ::resolveTable)
  /**
   * The database used when doing queries for the
   * instances of the model.
   */
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
   * The metadata object representing this instance.
   */
  open val metadata: Metadata? = null

  /**
   * All fields in the model that is recognised as a column. It
   * is also sorted to have the primary key first with references
   * after. They are first sorted by name.
   *
   * What disqualifies any property from being picked up is when
   * its value is not a delegate that implements the [IPropertyWithType]
   * interface.
   */
  @Suppress("UNCHECKED_CAST")
  val fields by lazy {
    this::class.declaredMembers
      .asSequence()
      .filterIsInstance<KProperty<*>>()
      .filter { it.javaField?.type?.kotlin?.isSubclassOf(IPropertyWithType::class) == true }
      .map(::Field)
      .sortedBy { it.property.name }
      .sortedBy { it.getDelegate(this) !is IReferenceType<*> }
      .sortedBy {
        metadata?.digest?.primaryKeys?.contains(it.property) == false && it.property.name != "id"
      }
      .toList()
  }

  /**
   * The field that is recognised as the id column.
   *
   * It is used mainly when being referenced from a different
   * table.
   */
  val idField: Field by lazy {
    fields.find {
      metadata?.digest?.primaryKeys?.contains(it.property) == true
    } ?: fields.find {
      it.property.name == "id"
    } ?: throw IllegalStateException(
      "The table does not have an id column. Make a property the primary key or create a property with the name `id`"
    )
  }

  // --------------------------------------------- Active Record functions ---------------------------------------------

  /**
   * Save the current state of the object to the database. Whether it is to
   * update the existing entry or create a new one.
   */
  open fun save(vararg assignments: Pair<KProperty1<out ActiveRecord, Any?>, Any?>) {
    for ((property, value) in assignments) {
      val field = Field(property)
      if (field.table != table)
        continue
      field.setValue(this, value)
    }
    if (idField.hasValue(this)) {
      // Update row
      val valueFields = Field.hasValue(this, fields - idField)
      try {
        database.update(
          table = table,
          columns = valueFields.map(Field::name),
          whereColumns = listOf(idField.name),
          values = Field.values(this, valueFields + idField)
        )
        return
      } catch (_: NoSuchElementException) {
        // There was no element to update so lets create it.
        // (Fallthrough to create stage)
      }
    }
    // Create row
    val keys = Field.hasValue(this, fields)
    val res = database.insertInto(table, keys.map(Field::name), Field.values(this, keys))
    (idField.getDelegate(this) as IInjectable).inject(res.get().generatedKeys[0])
  }

  /**
   * Remove current entry from the database.
   */
  open fun delete() {
    database.deleteFrom(table, listOf(idField.name), listOf(idField.getValue(this)))
  }

  /**
   * Set a value for the table that might be immutable.
   *
   * This method is supposed to only be called in the construction
   * of a model instance.
   *
   * @param value The value to set.
   */
  protected fun <R : Any?> KProperty0<R>.set(value: R) {
    val field = Field(this)
    field.setValue(this@ActiveRecord, value)
  }

  companion object {
    // ------------------------------------------- Name resolution functions -------------------------------------------

    private val tableCache = mutableMapOf<KClass<*>, String>()
    private const val vowels = "aouåeiyäö"

    /**
     * A function that generates the plural form of a word (the model name)
     * with great accuracy. Though there are exceptions.
     *
     * @param kClass The class to generate the name for
     */
    private fun resolveTable(kClass: KClass<*>): String {
      val name = kClass.java.simpleName.toSnakeCase()
      return if (
        name.endsWith("ss") || name.endsWith("s") ||
        name.endsWith("sh") || name.endsWith("ch") ||
        name.endsWith("x") || name.endsWith("z")
      ) "${name}es"
      else if (name.endsWith("f")) "${name.substring(0, name.lastIndex)}ves"
      else if (name.endsWith("fe")) "${name.substring(0, name.lastIndex - 1)}ves"
      else if (name.length > 1 && name[name.length - 1] == 'y' && name[name.length - 2] !in vowels)
        "${name.substring(0, name.lastIndex)}ies"
      else "${name}s"
    }

    /**
     * Get a name of a link table based on a might be specified [table] name variable,
     * the [property] requiring the table, and the linking tables.
     *
     * @param table The possibly specified table name.
     * @param property The property that requires the table.
     * @param first The first table to connect.
     * @param second The second table to connect.
     */
    fun getLinkTableName(table: String?, property: KProperty<*>, first: ActiveRecord, second: ActiveRecord): String {
      return when {
        table != null -> table
        first.table == property.name || second.table == property.name -> {
          val (f, l) = listOf(first, second).sortedBy(ActiveRecord::table)

          "${f::class.java.simpleName.toSnakeCase()}_${l.table}"
        }
        else -> property.name.toSnakeCase()
      }
    }

    // ---------------------------------------------- Instance creations -----------------------------------------------

    /**
     * This function will create a instance of the specified [kClass].
     */
    @JvmStatic
    fun <AR : ActiveRecord> getShallowInstance(kClass: KClass<out AR>): AR {
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
      val instance = getShallowInstance(model)

      return instance.database
        .select(*Field.nameArray(instance.fields))
        .from(instance.table)
        .where("$column = ?")
        .execute(value)
        .fetchInto(model)
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
      find(AR::class, Field.getName(column), value)

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
      val instance = getShallowInstance(model)
      return instance.database
        .select(*Field.nameArray(instance.fields))
        .from(instance.table)
        .where(instance.idField.name + " = ?")
        .execute(value)
        .fetchInto(model)
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
      val instance = getShallowInstance(model)
      return instance.database
        .select(*Field.nameArray(instance.fields))
        .from(instance.table)
        .where("`${Field.getName(column)}` = ?")
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
      val instance = getShallowInstance(model)
      val columnName = Field.getName(column)
      return instance.database
        .select(*Field.nameArray(instance.fields))
        .from(instance.table)
        .where("`$columnName` IN (${"?, ".repeat(values.size).dropLast(2)})")
        .execute(values)
        .fetch { result ->
          val list: MutableList<AR?> = (0 until values.size).map { null }.toMutableList()
          for (res in result) {
            val value = res[columnName]
            val index = values.indexOf(value)
            if (index == -1)
              continue
            list[index] = res.inject(getShallowInstance(model))
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
      val instance = getShallowInstance(model)
      val columnName = Field.getName(column)
      return instance.database
        .select(*Field.nameArray(instance.fields))
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

    /**
     * Get all available objects form the [AR] model specified.
     *
     * @param table Which objects to retrieve.
     */
    fun <AR : ActiveRecord> getAll(table: KClass<out AR>): List<AR> {
      val usableInstance = getShallowInstance(table)
      return usableInstance.database
        .select(*Field.nameArray(usableInstance.fields))
        .from(usableInstance.table)
        .execute()
        .fetchAllInto(table)
    }

    /**
     * Get all available objects form the [AR] model specified.
     *
     * @param AR Which objects to retrieve.
     */
    inline fun <reified AR : ActiveRecord> getAll() = getAll(AR::class)

    // --------------------------------------------- Table Helpers -----------------------------------------------------

    /**
     * Creates a table from the specified table class.
     *
     * @param table The table to create.
     */
    @JvmStatic
    fun create(table: KClass<out ActiveRecord>) {
      getShallowInstance(table).database.execute(genSQL(table))
    }

    /**
     * Creates a table from the specified model class.
     *
     * @param AR The table to create.
     */
    @JvmStatic
    inline fun <reified AR : ActiveRecord> create() = create(AR::class)

    /**
     * Creates the tables specified in the databases they are associated with.
     *
     * @param tables The tables to create.
     */
    @JvmStatic
    fun create(vararg tables: KClass<out ActiveRecord>) {
      if (tables.isNotEmpty()) {
        val dbToTable = mutableMapOf<Database, MutableList<KClass<out ActiveRecord>>>()
        for (table in tables)
          dbToTable.computeIfAbsent(getShallowInstance(table).database) { mutableListOf() }
            .add(table)

        dbToTable
          .map { (db, list) -> db to genSQL(*list.toTypedArray()) }
          .forEach { (db, sql) ->
            if (db.isSQLite) {
              sql
                .replace(" -- Link table", "")
                .split("\n);")
                .filter(String::isNotBlank)
                .map { "$it\n);" }
                .forEach { db.execute(it) }
            } else db.execute(sql)
          }

      }
    }

    /**
     * Generates the string used to create the table in question with
     * any link tables required.
     *
     * @param modelKClass The table to generate the ddl for.
     */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun genSQL(modelKClass: KClass<out ActiveRecord>): String {
      val instance = getShallowInstance(modelKClass)
      val table = instance.table

      val sqlFields = instance.fields
        .joinToString(",\n  ") {
          val delegate = it.getDelegate(instance) as ICreateSQL
          delegate.getCreationSQL(instance, it.property)
        }
        .trim()

      val foreign = with(instance.fields) {
        val res = mapNotNull {
          when (val ref = it.getDelegate(instance)) {
            is IReferenceType<*> -> {
              val ff = ref.field
              val fkName = "${it.table}_fk_${it.name}"

              "CONSTRAINT $fkName FOREIGN KEY (${it.name}) REFERENCES ${ff.table} (${ff.name}) ON DELETE ${ref.action}"
            }
            else -> null
          }
        }.joinToString(",\n  ")

        if (res.isBlank()) ""
        else ",\n\n  $res"
      }

      val primary = run {
        instance.fields.find {
          instance.metadata?.digest?.primaryKeys?.contains(it.property) == true
        } ?: instance.fields.find {
          it.property.name == "id"
        }
      }

      val indicesLine =
        if (instance.database.isSQLite) ""
        else {
          val digestIndices = instance.metadata?.digest?.indices
            ?.filter { it.fields[0].property in modelKClass.members }
            ?: emptyList()
          val modelIndices = instance.fields
            .filter { it.getDelegate(instance) is IReferenceType<*> }
            .map { Metadata.Item(it.name, listOf(it)) }
          val sortIndices = modelKClass.memberProperties
            .asSequence()
            .map(::Field)
            .map { it to (it.getDelegate(instance) as? IOrdering<*, *>) }
            .filter {
              it.second != null && it.second?.orders?.isNotEmpty() ?: false && it.second?.orders?.all { o ->
                o.first in modelKClass.memberProperties
              } ?: false
            }
            .map { (field, delegate) ->
              Metadata.Item(
                field.name + "_list",
                delegate!!.orders.map(Pair<KProperty<*>, *>::first).map(::Field)
              )
            }
            .toList()
          val outboundSortIndices = modelKClass.memberProperties
            .map(::Field)
            .mapNotNull { it.getDelegate(instance) as? ITableReference<*> }
            .flatMap {
              it.kClass.memberProperties
                .map(::Field)
                .mapNotNull { f ->
                  try {
                    f to (f.getDelegate(instance) as? IOrdering<*, *>)
                  } catch (_: Throwable) {
                    null
                  }
                }
            }
            .filter {
              it.second != null && it.second?.orders?.isNotEmpty() ?: false && it.second?.orders?.all { o ->
                o.first in modelKClass.memberProperties
              } ?: false
            }
            .map { (field, delegate) ->
              Metadata.Item(
                field.name + "_list",
                delegate!!.orders.map(Pair<KProperty<*>, *>::first).map(::Field)
              )
            }
          val primaryKey = if (primary != null) Metadata.Item(primary.name, listOf(primary)) else null
          val indicesList = (digestIndices + modelIndices + sortIndices + outboundSortIndices + primaryKey)
            .toSet()
            .filterNotNull()
          if (indicesList.isEmpty()) ""
          else {
            val index = indicesList
              .joinToString(",\n  ") { item ->
                "INDEX ${instance.table}_ix_${item.name} (${item.fields.joinToString { it.name }})"
              }
              .trim()
            ",\n\n  $index"
          }
        }
      val uniquesLine = run {
        val digestUniques = instance.metadata?.digest?.uniques
          ?.filter { it.fields[0].property in modelKClass.members }
          ?: emptyList()
        val uniquesList = digestUniques
          .toSet()
        if (uniquesList.isEmpty()) ""
        else {
          val unique = digestUniques
            .joinToString(",\n  ") { item ->
              "CONSTRAINT ${instance.table}_ux_${item.name} UNIQUE (${item.fields.joinToString { it.name }})"
            }
            .trim()
          ",\n\n  $unique"
        }
      }

      val additional = modelKClass.memberProperties.mapNotNull { property ->
        val field = Field(property)
        if (field in instance.fields) return@mapNotNull null
        val delegate = field.getDelegate(instance) as? ICreateSQL ?: return@mapNotNull null
        delegate.getCreationSQL(instance, property)
      }.toSet()

      return "CREATE TABLE $table (\n  " +
        sqlFields +
        indicesLine +
        uniquesLine +
        foreign +
        "\n);" +
        (if (additional.isNotEmpty()) "\n\n" else "") + additional.joinToString("\n\n")
    }

    /**
     * Generates the string used to create the table in question with
     * any link tables required.
     *
     * @param AR The table to generate the ddl for.
     */
    @JvmStatic
    inline fun <reified AR : ActiveRecord> genSQL() = genSQL(AR::class)

    /**
     * Generates the string used to create the tables in question with
     * any link tables required.
     *
     * @param tables The tables to generate the ddl for.
     */
    @JvmStatic
    fun genSQL(vararg tables: KClass<out ActiveRecord>): String {
      return if (tables.isEmpty()) "" else tables
        .map(::genSQL)
        .flatMap {
          val all = it.split(";\n\n", "-- Link table\n\n", "-- Link table").toMutableList()
          val table = all.removeAt(0)

          listOf(
            if (table.endsWith(";")) table else "$table;",
            *all
              .filter(String::isNotBlank)
              .map { link -> "$link-- Link table" }
              .toTypedArray()
          )
        }
        .toSet()
        .sortedBy { it.endsWith("-- Link table") }
        .joinToString("\n\n")
    }

    /**
     * Migrates a database schema/catalog to the new version. Calculating
     * differences and applying steps to get the model in the database up
     * to date.
     *
     * These differences can be dropping, creating, modifying properties,
     * indices, unique indices, and foreign keys. It can and will also
     * create missing tables, but not delete loose tables.
     *
     * When specifying tables to migrate this method does do it for every
     * unique database connection grouped by the connection on the models.
     *
     * @param tables The tables/models to migrate to the new version.
     */
    @JvmStatic
    fun migrate(vararg tables: KClass<out ActiveRecord>) {
      //TODO Rename tables?
      val dbToTables = mutableMapOf<Database, MutableSet<KClass<out ActiveRecord>>>()
      setOf(*tables).forEach { table ->
        @Suppress("UNCHECKED_CAST")
        val instance = getShallowInstance(table as KClass<ActiveRecord>)
        dbToTables.computeIfAbsent(instance.database) { mutableSetOf() }.add(table)
      }
      for ((db, kClasses) in dbToTables) {
        // Get all database tables and their creation string
        @Suppress("SqlResolve")
        val nameToSQL: Map<String, String> = if (db.isSQLite)
          db.execute("SELECT `name`, `sql` FROM sqlite_master WHERE type='table'").map {
            it.map { (name, sql) -> name as String to sql as String }.toMap()
          }.orElseGet(::emptyMap)
        else db.execute("SHOW TABLES").map { res ->
          val map = mutableMapOf<String, String>()
          for ((name) in res)
            db.execute("SHOW CREATE TABLE `$name`").ifPresent {
              var sql = it[1] as String
              sql = sql.split("\n)")[0] + "\n)"
              map += it[0] as String to sql
            }
          map.toMap()
        }.orElseGet(::emptyMap)

        // Go through all class for the database
        val outSQL = StringBuilder()
        for (kClass in kClasses) {
          val creationSQL = genSQL(kClass)
          val all = creationSQL.split(";\n\n", "; -- Link table\n\n", "; -- Link table").toMutableList()
          val table = all.removeAt(0)

          val sqlSources = listOf("$table;", *all
            .filter(String::isNotBlank)
            .map { it.replaceFirst("IF NOT EXISTS ", "") }
            .map { "$it; -- Link table" }
            .toTypedArray()).map { Pair(it.substring(13, it.indexOf(' ', 14)), it) }

          table@ for ((name, sql) in sqlSources) {
            if (name !in nameToSQL) {
              if ("CREATE TABLE $name" !in outSQL && "CREATE TABLE IF NOT EXISTS $name" !in outSQL) {
                if (outSQL.isNotBlank())
                  outSQL.append("\n-- separator\n")
                outSQL.append(sql)
              }
            } else {
              val oldTable = AbstractTable.fromSQLSource(nameToSQL.getValue(name))
              val newTable = AbstractTable.fromSQLSource(sql)
              val mods = StringBuilder()

              // Properties
              for (oldProperty in oldTable.properties) {
                val newProperty = newTable.properties.find { it.name == oldProperty.name }
                if (newProperty == null) {
                  if (db.isSQLite) {
                    if (outSQL.isNotBlank())
                      outSQL.append("\n-- separator\n")
                    outSQL.append(recreate(nameToSQL.getValue(name), sql))
                    continue@table
                  } else {
                    if (mods.isNotBlank())
                      mods.append("\n-- separator\n")
                    mods.append("ALTER TABLE ${oldTable.name} DROP COLUMN `${oldProperty.name}`;")
                  }
                } else if (newProperty != oldProperty) {
                  if (db.isSQLite) {
                    if (outSQL.isNotBlank())
                      outSQL.append("\n-- separator\n")
                    outSQL.append(recreate(nameToSQL.getValue(name), sql))
                    continue@table
                  } else {
                    if (mods.isNotBlank())
                      mods.append("\n-- separator\n")
                    mods.append(
                      "ALTER TABLE ${oldTable.name} ${if (db.isSQLServer) "ALTER" else "MODIFY"} COLUMN " +
                        "`${oldProperty.name}` ${newProperty.type} ${if (!newProperty.nullable) "NOT " else ""}NULL;"
                    )
                  }
                }
              }

              for (newProperty in newTable.properties) {
                val oldProperty = oldTable.properties.find { it.name == newProperty.name }
                if (oldProperty != null)
                  continue
                if (mods.isNotBlank())
                  mods.append("\n-- separator\n")
                mods.append(
                  "ALTER TABLE ${newTable.name} ADD COLUMN " +
                    "`${newProperty.name}` ${newProperty.type} ${if (!newProperty.nullable) "NOT " else ""}NULL;"
                )
              }

              // Indices
              val indexSkip = mutableListOf<AbstractTableIndex>()
              val oldIndices = oldTable.indices.toMutableList()
              oldIndices.removeAll(newTable.indices)
              for (index in oldIndices) {
                val item = newTable.indices.find { it.properties == index.properties }
                val prevIndex = when {
                  index.name != "<unknown>" -> index.name
                  db.isSQLite -> {
                    if (outSQL.isNotBlank())
                      outSQL.append("\n-- separator\n")
                    outSQL.append(recreate(nameToSQL.getValue(name), sql))
                    continue@table
                  }
                  else -> index.properties[0]
                }
                if (item != null) {
                  if (mods.isNotBlank())
                    mods.append("\n-- separator\n")
                  mods.append("DROP INDEX `$prevIndex`${if (!db.isSQLite) " ON ${oldTable.name}" else ""};\n")
                  mods.append("CREATE INDEX `${item.name}` ON ${oldTable.name} (${item.properties.joinToString()});")
                  indexSkip += item
                } else {
                  if (mods.isNotBlank())
                    mods.append("\n-- separator\n")
                  mods.append("DROP INDEX `$prevIndex`${if (!db.isSQLite) " ON ${oldTable.name}" else ""};")
                }
              }

              val newIndices = newTable.indices.toMutableList()
              newIndices.removeAll(oldTable.indices)
              newIndices.removeAll(indexSkip)
              for (index in newIndices) {
                val prevIndex = when {
                  index.name != "<unknown>" -> index.name
                  db.isSQLite -> {
                    if (outSQL.isNotBlank())
                      outSQL.append("\n-- separator\n")
                    outSQL.append(recreate(nameToSQL.getValue(name), sql))
                    continue@table
                  }
                  else -> index.properties[0]
                }
                if (mods.isNotBlank())
                  mods.append("\n-- separator\n")
                mods.append("CREATE INDEX `$prevIndex` ON ${newTable.name} (`${index.properties.joinToString()}`);")
              }

              // Unique indices
              val uniqueSkip = mutableListOf<AbstractTableIndex>()
              val oldUniques = oldTable.uniques.toMutableList()
              oldUniques.removeAll(newTable.uniques)
              for (unique in oldUniques) {
                val item = newTable.uniques.find { it.properties == unique.properties }
                val prevIndex = when {
                  unique.name != "<unknown>" -> unique.name
                  db.isSQLite -> {
                    if (outSQL.isNotBlank())
                      outSQL.append("\n-- separator\n")
                    outSQL.append(recreate(nameToSQL.getValue(name), sql))
                    continue@table
                  }
                  else -> unique.properties[0]
                }
                if (item != null) {
                  if (mods.isNotBlank())
                    mods.append("\n-- separator\n")
                  mods.append("DROP INDEX `$prevIndex`${if (!db.isSQLite) " ON ${oldTable.name}" else ""};\n")
                  mods.append("CREATE UNIQUE INDEX `${item.name}` ON ${oldTable.name} (${item.properties.joinToString()});")
                  uniqueSkip += item
                } else {
                  if (mods.isNotBlank())
                    mods.append("\n-- separator\n")
                  mods.append("DROP INDEX `$prevIndex`${if (!db.isSQLite) " ON ${oldTable.name}" else ""};")
                }
              }

              val newUniques = newTable.uniques.toMutableList()
              newUniques.removeAll(oldTable.uniques)
              newUniques.removeAll(uniqueSkip)
              for (unique in newUniques) {
                if (mods.isNotBlank())
                  mods.append("\n-- separator\n")
                mods.append("CREATE UNIQUE INDEX `${unique.name}` ON ${newTable.name} (${unique.properties.joinToString()});")
              }

              // Primary key
              if (oldTable.primaryKey != newTable.primaryKey) {
                if (db.isSQLite) {
                  if (oldTable.primaryKey?.properties != newTable.primaryKey?.properties) {
                    if (outSQL.isNotBlank())
                      outSQL.append("\n-- separator\n")
                    outSQL.append(recreate(nameToSQL.getValue(name), sql))
                    continue@table
                  }
                } else {
                  if (mods.isNotBlank())
                    mods.append("\n-- separator\n")
                  if (oldTable.primaryKey != null)
                    mods.append("ALTER TABLE users DROP PRIMARY KEY;\n")
                  if (newTable.primaryKey != null)
                    mods.append("ALTER TABLE users ADD CONSTRAINT users_pk PRIMARY KEY (${newTable.primaryKey.properties.joinToString()});")
                }
              }

              // Foreign keys
              val skipForeignKeys = mutableListOf<AbstractForeignKey>()
              for (oldForeignKey in oldTable.foreignKeys) {
                if (oldForeignKey in newTable.foreignKeys) {
                  skipForeignKeys += oldForeignKey
                  continue
                }
                if (db.isSQLite) {
                  if (outSQL.isNotBlank())
                    outSQL.append("\n-- separator\n")
                  outSQL.append(recreate(nameToSQL.getValue(name), sql))
                  continue@table
                }
                if (mods.isNotBlank())
                  mods.append("\n-- separator\n")
                mods.append("ALTER TABLE ${oldTable.name} DROP FOREIGN KEY ${oldForeignKey.name}")
              }

              for (newForeignKey in newTable.foreignKeys) {
                if (newForeignKey in skipForeignKeys)
                  continue
                if (mods.isNotBlank())
                  mods.append("\n-- separator\n")
                mods.append(
                  "ALTER TABLE ${newTable.name} ADD " +
                    "CONSTRAINT ${newForeignKey.name} FOREIGN KEY (${newForeignKey.tableProperty}) " +
                    "REFERENCES ${newForeignKey.foreignTable} (${newForeignKey.foreignProperty}) " +
                    "ON DELETE ${newForeignKey.onDeleteAction.sql};"
                )
              }

              // Add all modifications
              if (outSQL.isNotBlank())
                outSQL.append("\n-- separator\n")
              outSQL.append(mods)
            }
          }
        }

        // Execute all modifications if there are any to be made
        if (outSQL.isNotEmpty()) outSQL.toString()
          .split("\n-- separator")
          .map(String::trim)
          .toSet()
          .sortedBy { it.endsWith("-- Link table") }
          .filter(String::isNotBlank)
          .flatMap { it.split(";\n\n") }
          .map { "$it\n" }
          .forEach { db.execute(it) }
      }
    }

    private fun isColumnLine(line: String): Boolean {
      if (line.isBlank() || line[0] != ' ') return false
      val l = line.trimStart(' ')
      return !l.startsWith("FOREIGN") && !l.startsWith("UNIQUE") && !l.startsWith("INDEX")
    }
    private fun getNameOfColumnLine(line: String): String = line.split(' ').filter(String::isNotBlank)[0]
    private fun recreate(oldSQL: String, newSQL: String): String {
      val normalName = newSQL.substring(13, newSQL.indexOf(' ', 14))
      val tempName = "${normalName}_temp_table"
      val oldLines = oldSQL.split('\n').filter(::isColumnLine).map(::getNameOfColumnLine)
      val newLines = newSQL.split('\n').filter(::isColumnLine).map(::getNameOfColumnLine)
      val columns = oldLines.intersect(newLines).joinToString()

      return "DROP TABLE IF EXISTS $tempName;\n" +
        "\n" +
        "${newSQL.replaceFirst(normalName, tempName)};\n" +
        "\n" +
        "INSERT INTO $tempName ($columns) SELECT $columns FROM $normalName;\n" +
        "\n" +
        "DROP TABLE $normalName;\n" +
        "\n" +
        "ALTER TABLE $tempName RENAME TO $normalName;"
    }

    /**
     * Clears all data in the tables specified.
     *
     * @param tables The tables to erase the data for.
     */
    @JvmStatic
    fun clearAllDataFrom(vararg tables: KClass<out ActiveRecord>) {
      if (tables.isNotEmpty()) {
        val tableToInstance = mutableMapOf<KClass<out ActiveRecord>, ActiveRecord>()
        val dbToTable = mutableMapOf<Database, MutableSet<KClass<out ActiveRecord>>>()
        for (table in tables) {
          val instance = tableToInstance.computeIfAbsent(table) { getShallowInstance(table) }
          dbToTable.computeIfAbsent(instance.database) { mutableSetOf() }.add(table)
        }

        for ((db, dbTables) in dbToTable) {
          if (db.isSQLite) {
            @Suppress("SqlWithoutWhere")
            for (table in dbTables)
              db.execute("DELETE FROM ${tableToInstance[table]!!.table};")
          } else {
            db.execute(buildString {
              for (table in dbTables)
                append("DELETE FROM ${tableToInstance[table]!!.table};\n")
            })
          }
        }
      }
    }
  }

  // --------------------------------------------- Object overrides ----------------------------------------------------

  override fun hashCode() = fields.fold(31) { acc, f ->
    acc * (if (f.hasValue(this)) f.getValue(this)?.hashCode() ?: 1 else 1)
  }

  override fun toString() = this::class.java.simpleName + fields
    .joinToString(",", prefix = "(", postfix = ")") {
      if (!it.hasValue(this))
        return@joinToString "${it.property.name}=<unknown>"

      val delegate = it.getDelegate(this) as? IPropertyWithType<*>
        ?: return@joinToString "${it.property.name}=<unknown>"
      if (delegate.type == IPropertyWithType.Type.REFERENCE)
        return@joinToString "${it.property.name}=<abbreviated>"

      "${it.property.name}=${it.getValue(this)}".replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")
    }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ActiveRecord) return false

    if (table != other.table) return false
    if (idField.hasValue(this) != idField.hasValue(other)) return false

    return idField.getValue(this) == idField.getValue(other)
  }

  // --------------------------------------------- Delegate functions --------------------------------------------------

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
  protected inline fun <reified T : Any?> value(noinline default: (() -> T)? = null): IPropertyWithType<T> =
    if (null is T) DBValueNullable<T>() as IPropertyWithType<T>
    else DBValue(T::class.java, default) as IPropertyWithType<T>

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
  protected inline fun <reified T : ActiveRecord?> reference(): IReferenceType<T> =
    if (null is T) DBReferenceNull<ActiveRecord>(T::class as KClass<out ActiveRecord>, null) as IReferenceType<T>
    else DBReference<ActiveRecord>(T::class as KClass<out ActiveRecord>, null) as IReferenceType<T>

  /**
   * Reference a [column] from another table where the reference can be null
   * if specified.
   *
   * If the underlying name of the column is unsatisfactory you can
   * specify it with the [SerializedName] annotation.
   *
   * @param T The type of the referenced ActiveRecord.
   */
  @Suppress("UNCHECKED_CAST")
  protected inline fun <reified T : ActiveRecord?> reference(column: KProperty1<T, *>): IReferenceType<T> =
    if (null is T) DBReferenceNull(T::class as KClass<out ActiveRecord>, column as KProperty1<ActiveRecord, *>) as IReferenceType<T>
    else DBReference(T::class as KClass<out ActiveRecord>, column as KProperty1<ActiveRecord, *>) as IReferenceType<T>

  /**
   * Reference a list of possible values. This can be anything that could
   * be in a link table between two tables. Like posts a user has made,
   * messages in a chat channel, or if a user follows/subscribes to another
   * user.
   *
   * The resulting list is a [DatabaseList] that has a [DatabaseList.refresh]
   * method that can be used to have the list update its contents.
   *
   * @param kClass The kotlin class type of the reference class.
   * @param table The the name of the link table.
   * @param key The column name that references this table.
   * @param referenceKey The column name that references the other table.
   * @param T The type of the reference ActiveRecord.
   * @return A delegate for a semi live list of the values.
   */
  protected fun <T : ActiveRecord> referenceMany(kClass: KClass<T>, table: String? = null, key: String? = null, referenceKey: String? = null) =
    DBManyReference(table, key, referenceKey, kClass)

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
    DBManyReference(table, key, referenceKey, T::class)

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
   * @param F The type of the reference ActiveRecord.
   * @return A delegate for a semi live list of the values.
   */
  protected fun <F : ActiveRecord, T : ActiveRecord> referenceMany(reverse: KProperty1<F, DatabaseList<T>>): DBManyReferenceReverse<F, T> =
    DBManyReferenceReverse(reverse)

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
   * Retrieve a list of [R] mapper the property that has a DatabaseList ([from])
   * resolved from the property in the [mapper] parameter.
   *
   * A example is if you have users that can follow eachother and that the users
   * can make posts. How do you retrieve the posts of all you follow? You can do
   * it like this: `val timeline from receiveMany(mapper = Post::author, from =
   * User::follows)`.
   *
   * @param mapper The property that will resolve the results into the correct model.
   * @param from Where you can get the list of values.
   * @param R The return type.
   */
  protected inline fun <reified R, reified T> receiveMany(
    from: KProperty1<out ActiveRecord, DatabaseList<in T>>,
    mapper: KProperty1<in R, T>
  ) where R : ActiveRecord, T : ActiveRecord =
    DBReceiveList(from, mapper)

  // ------------------------------------------------- Metadata --------------------------------------------------------

  /**
   * A SQL type represented in normal sql and in SQLite.
   */
  enum class Type(val sqlType: String, val usesSize: Boolean, val sqliteType: String, val sqliteUsesSize: Boolean) {

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
    DATETIME("DATETIME", false, "NUMERIC", false);

    fun toString(database: Database?, param1: Int?, param2: Int?): String {
      val base = if (database?.isSQLite == true) sqliteType else sqlType
      val usesSize = if (database?.isSQLite == true) sqliteUsesSize else usesSize

      return when {
        usesSize && param2 != null -> "$base($param1,$param2)"
        usesSize -> "$base($param1)"
        else -> base
      }
    }
  }

  /**
   * Specifies metadata about a table or a group of tables. Here you can specify
   * what type a property is in more detail, what properties has unique values,
   * what indices the table has, and what properties should be automatically
   * incremented.
   */
  class Metadata private constructor(private val parent: Metadata?, private val init: Metadata.() -> Unit) {
    /**
     * Create a new metadata instance.
     */
    constructor(init: Metadata.() -> Unit) : this(null, init)

    /**
     * A digest instance representing this metadata.
     */
    val digest by lazy {
      parent?.init?.invoke(this)
      init()
      return@lazy Digest(types.toMap(), auto, indices, uniques, primaryKeys)
    }

    private val types = mutableListOf<Pair<KProperty1<out ActiveRecord, Any?>, TypeInfo>>()
    private val auto = mutableListOf<KProperty1<out ActiveRecord, Any?>>()
    private val primaryKeys = mutableListOf<KProperty1<out ActiveRecord, Any?>>()
    private val uniques = mutableListOf<Item>()
    private val indices = mutableListOf<Item>()

    /**
     * Specify a property to be automatically incremented when
     * new rows are created.
     */
    fun autoIncrement(vararg property: KProperty1<out ActiveRecord, Any?>) = auto.plusAssign(property)

    /**
     * Setup a context where unique index items can be added.
     */
    fun unique(block: CollectionContext.() -> Unit) = CollectionContext(uniques).block()

    /**
     * Add a unique index item group by a single property.
     */
    fun unique(property: KProperty1<out ActiveRecord, Any?>) {
      val field = Field(property)
      uniques.add(Item(field.name, listOf(field)))
    }

    /**
     * Add a unique index item group.
     */
    fun <T : ActiveRecord> uniqueGroup(name: String, vararg properties: KProperty1<T, Any?>) {
      uniques.add(Item(name, properties.map(::Field)))
    }

    /**
     * Setup a context where index items can be added.
     */
    fun index(block: CollectionContext.() -> Unit) = CollectionContext(indices).block()

    /**
     * Add a index item group by a single property.
     */
    fun index(property: KProperty1<out ActiveRecord, Any?>) {
      val field = Field(property)
      indices.add(Item(field.name, listOf(field)))
    }

    /**
     * Add a index item group.
     */
    fun <T : ActiveRecord> indexGroup(name: String, vararg properties: KProperty1<T, Any?>) {
      indices.add(Item(name, properties.map(::Field)))
    }

    /**
     * Set the primary key for a model with the specified property.
     */
    fun primaryKey(property: KProperty1<out ActiveRecord, Any?>) {
      primaryKeys += property
    }

    /**
     * A item representing a group with a name.
     */
    class Item(
      /** The name of the item. */
      val name: String,
      /** The fields the item represents. */
      val fields: List<Field>
    )

    /**
     * A context for a collection for adding [items][Item] to a collection.
     */
    class CollectionContext(val collection: MutableCollection<Item>) {
      /**
       * Add a item by one property.
       */
      operator fun KProperty1<out ActiveRecord, Any?>.unaryPlus() {
        val field = Field(this)
        collection.add(Item(field.name, listOf(field)))
      }

      /**
       * Add a item group by a some [properties] and represent the group by the [name].
       */
      fun <T : ActiveRecord> group(name: String, vararg properties: KProperty1<T, Any?>) {
        collection.add(Item(name, properties.map(::Field)))
      }
    }

    /** Setup a model property with a type. */
    infix fun KProperty1<out ActiveRecord, Any?>.use(type: TypeInfo) = types.plusAssign(this to type)
    /** Creates a [TypeInfo] from a [type] and no parameters. */
    fun type(type: Type) = TypeInfo(type, null, null)
    /** Creates a [TypeInfo] from a [type] and one parameter. */
    fun type(type: Type, param: Int) = TypeInfo(type, param, null)
    /** Creates a [TypeInfo] from a [type] and two parameters. */
    fun type(type: Type, param1: Int, param2: Int): TypeInfo = TypeInfo(type, param1, param2)

    /**
     * A representation of a type definition.
     */
    data class TypeInfo(
      /** The overall type */
      val type: Type,
      /** The first parameter for the type if required. */
      val param1: Int?,
      /** The second parameter for the type if required. */
      val param2: Int?
    ) {
      fun toString(database: Database?): String = type.toString(database, param1, param2)
    }

    /**
     * Create a new metadata instance with some extended data
     * about types, primary keys, indices, and unique indices.
     */
    fun extend(init: Metadata.() -> Unit) = Metadata(this, init)

    /**
     * A representation of a finalized metadata object.
     */
    class Digest(
      /** The model types this metadata digest represents. */
      val types: Map<KProperty1<out ActiveRecord, Any?>, TypeInfo>,
      /** The automatically incremented model column this metadata digest represents. */
      val auto: MutableList<KProperty1<out ActiveRecord, Any?>>,
      /** The indices this metadata digest represents. */
      val indices: MutableList<Item>,
      /** The unique indices this metadata digest represents. */
      val uniques: MutableList<Item>,
      /** The primary keys this metadata digest represents. */
      val primaryKeys: MutableList<KProperty1<out ActiveRecord, Any?>>
    )
  }
}
