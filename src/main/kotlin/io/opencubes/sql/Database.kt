package io.opencubes.sql

import org.intellij.lang.annotations.Language
import java.nio.ByteBuffer
import java.sql.*
import java.sql.Date
import java.util.*
import kotlin.NoSuchElementException
import kotlin.reflect.KClass
import java.sql.ResultSet
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible


/**
 * A super powered database connection.
 *
 * @author Martin Hövre
 */
class Database {
  /**
   * Create a database connection with the provided [dsn].
   *
   * @param dsn The database url.
   */
  constructor(dsn: String) {
    this.dsn = dsn
    connection = createConnection(dsn)
  }

  /**
   * Create a database connection with the provided [dsn] and
   * login with [user] and [password].
   *
   * @param dsn The database url.
   * @param user The user to login as.
   * @param password The password for the user.
   */
  constructor(dsn: String, user: String, password: String) {
    this.dsn = dsn
    connection = createConnection(dsn, user, password)
  }

  /**
   * The dsn used to create the connection.
   */
  val dsn: String

  /**
   * The raw connection to the database.
   */
  val connection: Connection

  /**
   * Get all schemas/databases in the database.
   */
  val catalogs: List<String>
    get() =
      if (dialect.startsWith("sqlite")) listOf("main")
      else executeToList("SHOW DATABASES") {
        it.getString(1)
      }

  /**
   * A property to see the current database catalog/schema you are
   * working towards. If you set the property you change the working
   * catalog/schema.
   */
  var catalog: String
    get() = connection.catalog
    set(value) {
      connection.catalog = value
    }

  /**
   * Get the dialect of the connection
   */
  val dialect get() = dsn.split(':', limit = 2)[0].toLowerCase()

  /**
   * Executes a SQL query to the database.
   *
   * This function returns a [ResultSet] wrapped in a [Optional] just to easily see
   * if there was anything returned from the SQL query.
   *
   * @param sql The SQL query to execute.
   * @param params The params that corresponds to the '?' in the [sql] query.
   * @return The [ResultSet] returned from the [sql] query wrapped in a [Optional].
   */
  fun execute(@Language("sql") sql: String, vararg params: Any?): Optional<ResultSet> {
    val stmt = try {
      connection.prepareStatement(sql)
    } catch (e: SQLException) {
      throw DatabaseException(e, sql)
    }
    for ((i, param) in params.withIndex()) {
      val value =
          if (param !is Enum<*>) param
          else getEnumName(param)
      stmt.setObject(i + 1, value)
    }

    return try {
      if (stmt.execute()) {
        val res = stmt.resultSet
        if (res.type == ResultSet.TYPE_FORWARD_ONLY)
          return Optional.ofNullable(res)
        if (!res.first()) Optional.empty()
        else Optional.ofNullable(res)
      } else Optional.empty()
    } catch (_: NoSuchElementException) {
      Optional.empty()
    }
  }

  /**
   * Executes a SQL query with [params] and calls the [result] function if the query
   * returns a non empty [ResultSet].
   *
   * @param sql The SQL query to execute.
   * @param params The params that corresponds to the '?' in the [sql] query.
   * @return The result of the [result] function.
   * @see execute
   */
  inline fun <T> execute(@Language("sql") sql: String, vararg params: Any?, crossinline result: (ResultSet) -> T): T? {
    val resultSet = execute(sql, *params)
    return if (resultSet.isPresent) {
      val set = resultSet.get()
      val resultValue = result(set)
      set.close()
      resultValue
    } else null
  }

  /**
   * Executes a SQL query with [params] and calls the [result] function regardless if
   * the query returns a non empty [ResultSet] or not.
   *
   * @param sql The SQL query to execute.
   * @param params The params that corresponds to the '?' in the [sql] query.
   * @return The result of the [result] function.
   * @see execute
   */
  inline fun <T> executeRegardless(@Language("sql") sql: String, vararg params: Any?, crossinline result: (ResultSet) -> T): T {
    val set = execute(sql, *params).get()
    val resultValue = result(set)
    set.close()
    return resultValue
  }

  /**
   * Executes a SQL query with [params] and calls the [result] function for every
   * [ResultSet] in the query result.
   *
   * If the you expect your query to result in a array of [ResultSet]s and want
   * them in a list of a specific type ([T]).
   *
   * @param sql The SQL query to execute.
   * @param params The params that corresponds to the '?' in the [sql] query.
   * @return The result list of [T] from [result] or empty.
   * @see execute
   */
  inline fun <T> executeToList(@Language("sql") sql: String, vararg params: Any?, crossinline result: (ResultSet) -> T): List<T> {
    val execResult = execute(sql, *params)
    if (!execResult.isPresent) return emptyList()
    val res = execResult.get()
    if (!res.last()) return emptyList()
    val size = res.row
    res.beforeFirst()
    return List(size) {
      res.next()
      result(res)
    }.also { res.close() }
  }

  /**
   * Creates a new [Blob].
   */
  fun createBlob(): Blob = connection.createBlob()

  /**
   * Creates a new [Clob].
   */
  fun createClob(): Clob = connection.createClob()

  /**
   * Creates a new [Blob] with [bytes].
   */
  fun createBlob(bytes: ByteArray): Blob = connection.createBlob().also { it.setBytes(0, bytes) }

  /**
   * Creates a new [Clob] with [str].
   */
  fun createClob(str: String): Clob = connection.createClob().also { it.setString(0, str) }

  /**
   * Creates a new [Blob] with the [builder].
   */
  fun createBlob(size: Int, builder: ByteBuffer.() -> Unit): Blob =
      connection.createBlob().also { it.setBytes(0, ByteBuffer.allocate(size).apply(builder).array()) }

  /**
   * Creates a new [Clob] with the [builder].
   */
  fun createClob(builder: StringBuilder.() -> Unit): Clob =
      connection.createClob().also { it.setString(0, buildString(builder)) }

  /**
   * Starts and ends a database transaction. This is good if you want to execute
   * a series of commands and if any of them go wrong terminate all the changes
   * that should have been made.
   *
   * @param block A block to make your commands in.
   * @return If it is desired returns the result of the [block].
   * @throws SQLException If any problems occur.
   */
  inline fun <T> transaction(crossinline block: () -> T): T {
    val auto = try {
      connection.autoCommit
    } catch (_: NullPointerException) {
      true
    }
    return try {
      if (auto)
        connection.autoCommit = false
      connection.transactionIsolation = Connection.TRANSACTION_SERIALIZABLE
      val ret = block()
      connection.commit()
      ret
    } catch (e: SQLException) {
      connection.rollback()
      throw e
    } finally {
      try {
        if (auto)
          connection.autoCommit = true
      } catch (_: NullPointerException) {
      } catch (e: Throwable) {
        throw e
      }
    }
  }

  /**
   * Get the last inserted id.
   */
  fun lastInsertId(): Int {
    val res = execute("SELECT LAST_INSERT_ID() id")
    if (!res.isPresent) return -1
    return res.get().getInt("id")
  }

  /**
   * Insert into the [table] and set the [columns] to these [values].
   */
  fun insertInto(table: String, columns: Array<String>, values: Array<Any?>) {
    val columnNames = columns.joinToString(transform = ::escape)
    val insertPoints = values.joinToString { "?" }
    execute("INSERT INTO $table ($columnNames) VALUES ($insertPoints)", *values)
  }

  /**
   * Insert into the [table] and set the [columns] to these [values].
   */
  fun insertInto(table: String, columns: List<String>, values: List<Any?>) {
    val columnNames = columns.joinToString(transform = ::escape)
    val insertPoints = values.joinToString { "?" }
    execute("INSERT INTO $table ($columnNames) VALUES ($insertPoints)", *values.toTypedArray())
  }

  /**
   * Delete from the [table] where these [columns] have these [values].
   */
  fun deleteFrom(table: String, columns: List<String>, values: List<Any?>) {
    val preparedColumns = columns.joinToString(" AND ", transform = ::prepare)
    execute("DELETE FROM $table WHERE $preparedColumns", *values.toTypedArray())
  }

  /**
   * Update the a [table] row where [whereColumns] and set these [columns] with these [values].
   */
  fun update(table: String, columns: List<String>, whereColumns: List<String>, values: List<Any?>) {
    val preparedWhereColumns = whereColumns.joinToString(" AND ", transform = ::prepare)
    val preparedColumns = columns.joinToString(transform = ::prepare)
    execute("UPDATE $table SET $preparedColumns WHERE $preparedWhereColumns", *values.toTypedArray())
  }

  /**
   * Start a select query and these [columns] will be returned.
   */
  fun select(vararg columns: String) = SelectQueryBuilder(this, columns)

  /**
   * A query builder.
   *
   * @param db The database connection to use.
   * @param columns The columns to select.
   */
  class SelectQueryBuilder(private val db: Database, columns: Array<out String>) {
    /** The columns to select. */
    private val columns: Array<out String>

    /** The initial table to take the values from. */
    private lateinit var fromTable: String

    /** The joins that are going to be made. */
    private val joins = mutableSetOf<Triple<JoinType, String, String>>()

    /** The narrowing of the query values. */
    private val wheres = mutableSetOf<Pair<WhereType, String>>()

    init {
      if (columns.isNotEmpty()) {
        this.columns = columns
      } else {
        this.columns = arrayOf("*")
      }
    }

    /**
     * Set initial table to take the column values from.
     */
    fun from(table: String): SelectQueryBuilder {
      fromTable = table
      return this
    }

    /**
     * Start a inner join sequence.
     */
    fun join(table: String, on: String): SelectQueryBuilder {
      joins += Triple(JoinType.INNER, table, on)
      return this
    }

    /**
     * Left join the results.
     */
    fun leftJoin(table: String, on: String): SelectQueryBuilder {
      joins += Triple(JoinType.LEFT, table, on)
      return this
    }

    /**
     * Right join the results.
     */
    fun rightJoin(table: String, on: String): SelectQueryBuilder {
      joins += Triple(JoinType.RIGHT, table, on)
      return this
    }

    /**
     * Add a condition to narrow down the results.
     */
    fun where(condition: String): SelectQueryBuilder {
      wheres += WhereType.NONE to condition
      return this
    }

    /**
     * Add multiple conditions to narrow down the results.
     * The [columns] are AND:ed together.
     */
    fun where(columns: List<String>): SelectQueryBuilder {
      if (columns.isEmpty()) return this
      if (wheres.isEmpty()) {
        if (columns.size == 1) {
          wheres += WhereType.NONE to prepare(columns[0])
          return this
        } else
          wheres += WhereType.NONE to prepare(columns.drop(1)[0])
      }
      for (column in columns)
        wheres += WhereType.AND to prepare(column)
      return this
    }

    /**
     * Add multiple conditions to narrow down the results.
     * The [map] is AND:ed together.
     */
    fun where(map: Map<String, String>): SelectQueryBuilder {
      if (map.isEmpty()) return this
      val entries = map.entries
      if (wheres.isEmpty()) {
        val (name, value) = entries.drop(1)[0]
        wheres += WhereType.NONE to "${escape(name)} = $value"
      }
      for ((name, value) in entries)
        wheres += WhereType.AND to "${escape(name)} = $value"
      return this
    }

    /**
     * Add a and condition the query.
     */
    fun andWhere(condition: String): SelectQueryBuilder {
      wheres += WhereType.AND to condition
      return this
    }

    /**
     * Add a or condition the query.
     */
    fun orWhere(condition: String): SelectQueryBuilder {
      wheres += WhereType.OR to condition
      return this
    }

    /**
     * This will build the query and return it.
     */
    fun build(): String {
      if (!this::fromTable.isInitialized)
        throw Exception()

      val dialect = db.dialect

      return buildString {
        append("SELECT ")
        for ((i, column) in columns.withIndex()) {
          append(column)

          if (i != columns.size - 1) {
            append(", ")
          }
        }
        append("FROM $fromTable ")
        for ((type, table, condition) in joins) {
          append(when (type) {
            JoinType.INNER -> "INNER JOIN"
            JoinType.LEFT -> "LEFT JOIN"
            JoinType.RIGHT -> if (dialect.startsWith("sqlite")) "JOIN" else "RIGHT JOIN"
            else -> ""
          })
          append(" $table ON $condition ")
        }

        if (wheres.isNotEmpty()) {
          append("WHERE ")
          for ((type, condition) in wheres) {
            append(when (type) {
              WhereType.NONE -> ""
              WhereType.AND -> "AND "
              WhereType.OR -> "OR "
            })

            append("$condition ")
          }
        }
      }
    }

    /**
     * Execute the built query with the [values].
     */
    fun execute(values: List<Any?>) = FetchableResult(db.execute(build(), *values.toTypedArray()))
    /**
     * Execute the built query with the [values].
     */
    fun execute(vararg values: Any?) = FetchableResult(db.execute(build(), *values))

    private enum class JoinType { LEFT, RIGHT, OUTER_LEFT, OUTER_RIGHT, INNER, INNER_LEFT, INNER_RIGHT }
    private enum class WhereType { NONE, OR, AND }
  }

  /**
   * Wraps a result set to give easy access to extra metadata.
   *
   * @param resultSet The initial result set.
   */
  class ResultSetWrapper(val resultSet: ResultSet) {
    /**
     * The column names in this result set.
     */
    val columns by lazy {
      val meta = resultSet.metaData
      val columnCount = meta.columnCount

      List(columnCount) {
        meta.getColumnName(it + 1)
      }
    }

    /**
     * Go to the next result row.
     */
    operator fun next(): ResultSetWrapper {
      resultSet.next()
      return this
    }

    /**
     * Test if there are more rows.
     */
    operator fun hasNext() = resultSet.isAfterLast

    /**
     * Get a column value based on a name.
     *
     * @param columnName The column name.
     */
    operator fun get(columnName: String): Any? = resultSet.getObject(columnName)
    /**
     * Get a column value based on column index.
     *
     * @param columnIndex The column index.
     */
    operator fun get(columnIndex: Int): Any? = resultSet.getObject(columnIndex - 1)

    /** Get the 0 column index value */operator fun component1() = get(0)
    /** Get the 1 column index value */operator fun component2() = get(1)
    /** Get the 2 column index value */operator fun component3() = get(2)
    /** Get the 3 column index value */operator fun component4() = get(3)
    /** Get the 4 column index value */operator fun component5() = get(4)
    /** Get the 5 column index value */operator fun component6() = get(5)
    /** Get the 6 column index value */operator fun component7() = get(6)
    /** Get the 7 column index value */operator fun component8() = get(7)
    /** Get the 8 column index value */operator fun component9() = get(8)
  }

  /**
   * This is a wrapper to make it easier to integrate the result into a instance or such.
   *
   * @param resultSet The result set to wrap.
   */
  class FetchableResult(val resultSet: Optional<ResultSet>) {
    /** Whether or not there is a result or not */
    val hasResult get() = resultSet.isPresent

    /**
     * If you expect to get a single value from the result you can call this to
     * get it.
     *
     * The transformer takes a [ResultSetWrapper] as its first parameter and it
     * should return a [T] instance or null.
     *
     * @param transformer The function that transforms the row into a value.
     * @param T The type of object you expect.
     * @return The transform row or null.
     */
    fun <T> fetch(transformer: (ResultSetWrapper) -> T?): T? {
      if (!resultSet.isPresent) return null
      return transformer(ResultSetWrapper(resultSet.get())).also { resultSet.get().close() }
    }

    /**
     * If you expect the result to be a list of [T] you can bundle them up with
     * this.
     *
     * The transformer takes a [ResultSetWrapper] as its first parameter and it
     * should return a [T] instance.
     *
     * @param transformer The function that transforms rows into values.
     * @param T The type of objects in the list.
     * @return The list with the transformed rows or an empty list.
     */
    fun <T> fetchAll(transformer: (ResultSetWrapper) -> T): List<T> {
      if (!resultSet.isPresent) return emptyList()
      val res = resultSet.get()
      if (!res.last()) return emptyList()
      val size = res.row
      res.beforeFirst()
      val wrapper = ResultSetWrapper(resultSet.get())
      return List(size) {
        res.next()
        transformer(wrapper)
      }.also { res.close() }
    }

    private fun setValuesInInstance(instance: Any, resultSet: ResultSet) {
      val wrapper = ResultSetWrapper(resultSet)

      instance::class.memberProperties.asSequence().forEach { prop ->
        val name = prop.findAnnotation<SerializedName>()?.value ?: prop.name
        if (name in wrapper.columns) {
          val a = prop.isAccessible
          if (!a) prop.isAccessible = true

          try {
            val value = prop.getter.call(instance)
            when {
              value is ReadWriteProperty<*, *> ->
                ReadWriteProperty<Any?, Any?>::setValue.call(value, instance, prop, wrapper[name])
              prop is KMutableProperty<*> -> {
                val newValue =
                    if (value is Enum<*>) {
                      val enum = value::class.java
                      @Suppress("UNCHECKED_CAST")
                      val constants = enum.enumConstants as Array<Enum<*>>

                      constants.find {
                        val enumName = enum.getField(it.name)
                            .getAnnotation(SerializedName::class.java)
                            ?.value
                            ?: it.name
                        enumName == wrapper[name]
                      }
                    } else {
                      wrapper[name]
                    }

                prop.setter.call(instance, newValue)
              }
            }
          } catch (e: Exception) {
            if (prop is KMutableProperty<*>) {
              prop.setter.call(instance, wrapper[name])
            }
          }

          if (!a) prop.isAccessible = false
        }
      }
    }

    /**
     * Inserts all possible result columns into the [instance] specified. This
     * respects if the field is annotated with [SerializedName] to use that name
     * instead.
     *
     * If a field name in the [instance] matches a result column this will override
     * that value to match the one in the result set.
     *
     * If the value being overridden is a enum it will try its best to get the
     * appropriate enum value of that type. If the enum field differs form the one
     * in the database use [SerializedName] to specify the name that is represented
     * in the database.
     *
     * If the field is a delegate to a [ReadWriteProperty] it will delegate the
     * setting of the value to that instance.
     *
     * @param instance The instance you want to override the result set values in.
     * @param M The type of the instance.
     * @return The [instance] as it could be helpful for chaining purposes.
     */
    fun <M : Any> fetchInto(instance: M): M {
      if (!resultSet.isPresent) return instance
      setValuesInInstance(instance, resultSet.get())
      resultSet.get().close()
      return instance
    }

    /**
     * Fetch the result into a [model] of a active record.
     */
    fun <M : ActiveRecord> fetchInto(model: KClass<M>): M? {
      if (!resultSet.isPresent) return null
      return fetchInto(ActiveRecord.getInjectableInstance(model))
    }

    /**
     * Fetch the result into a model [M] of a active record.
     */
    inline fun <reified M : ActiveRecord> fetchInto() = fetchInto(M::class)

    /**
     * Fetch the result into a list of [model] that is a active record.
     */
    fun <M : ActiveRecord> fetchAllInto(model: KClass<M>): List<M> {
      if (!resultSet.isPresent) return emptyList()
      return fetchAll {
        val instance = ActiveRecord.getInjectableInstance(model)
        setValuesInInstance(instance, it.resultSet)
        instance
      }
    }

    /**
     * Fetch the result into a list of model [M] that is a active record.
     */
    inline fun <reified M : ActiveRecord> fetchAllIntoModel() = fetchAllInto(M::class)
  }

  companion object {
    /**
     * Escape column names
     *
     * @param value The value to escape
     */
    fun escape(value: String) = "`$value`"

    /**
     * Escape column names
     *
     * @param column The value to escape
     */
    fun prepare(column: String) = "`$column` = ?"

    /**
     * Get the enum value name.
     */
    fun getEnumName(e: Enum<*>) = try {
      val f = e.javaClass.getField(e.name)
      val a = f.getAnnotation(SerializedName::class.java)
      a?.value ?: e.name
    } catch (ignored: NoSuchFieldException) {
      e.name
    }

    /**
     * Creates a connection to the desired database.
     *
     * @example
     * createConnection("mysql://localhost:3306")
     * createConnection("sqlite:/path/to/file")
     *
     * @param dsn The DSN to the database ('jdbc:' is already appended)
     * @return The connection to the database.
     */
    @JvmStatic
    fun createConnection(dsn: String) = DriverManager.getConnection("jdbc:$dsn")!!

    /**
     * Creates a connection to the desired database with a [user]name and [password].
     *
     * @example
     * createConnection("mysql://localhost:3306", "username", "password")
     *
     * @param dsn The DSN to the database ('jdbc:' is already appended)
     * @param user The the username
     * @param password The the user password.
     * @return The connection to the database.
     */
    @JvmStatic
    fun createConnection(dsn: String, user: String, password: String) =
        DriverManager.getConnection("jdbc:$dsn", user, password)!!
  }

  /** Get the current timestamp. */
  class CurrentTimestamp : Timestamp(java.util.Calendar.getInstance().time.time)
  /** Get the current date. */
  class CurrentDate : Date(java.util.Calendar.getInstance().time.time)
  /** Get the current time. */
  class CurrentTime : Time(java.util.Calendar.getInstance().time.time)
}
