package io.opencubes.sql

import io.opencubes.sql.select.ResultSetWrapper
import io.opencubes.sql.select.SelectQueryBuilder
import org.intellij.lang.annotations.Language
import java.nio.ByteBuffer
import java.sql.*
import java.sql.Date
import java.util.*

/**
 * A super powered database connection that emphasises ease of
 * use with powerful SQL server agnostic functions.
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
        it[0] as String
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
   * Sets the [Database.global] database to this one and returns
   * the same connection.
   */
  val asGlobal: Database get() {
    Database.global = this
    return this
  }

  /**
   * Executes a SQL query to the database.
   *
   * This function returns a [ResultSet] wrapped in a [Optional] just to easily see
   * if there was anything returned from the SQL query.
   *
   * @param sql The SQL query to execute.
   * @param params The params that corresponds to the '?' in the [sql] query.
   * @return The [ResultSet] returned from the [sql] query wrapped in a [Optional].
   * @exception DatabaseException If these is an error in the [sql], database access
   *    or the connection is closed
   */
  @Throws(DatabaseException::class)
  fun execute(@Language("sql") sql: String, vararg params: Any?): Optional<ResultSetWrapper> {
    val stmt = try {
      connection.prepareStatement(sql)
    } catch (e: SQLException) {
      throw DatabaseException(e, sql)
    }
    return execute(stmt, *params)
  }

  /**
   * Execute a [PreparedStatement] with the specified [params] to the statement
   * and get the [ResultSetWrapper] representing the result of the operation.
   */
  fun execute(stmt: PreparedStatement, vararg params: Any?): Optional<ResultSetWrapper> {
    for ((i, param) in params.withIndex()) stmt.setObject(
      i + 1,
      if (param !is Enum<*>) param else getEnumName(param)
    )

    return if (stmt.execute()) {
      val res = stmt.resultSet
      if (res.isAfterLast)
        return Optional.empty()
      return Optional.of(ResultSetWrapper(res))
    } else Optional.empty()
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
  fun <T> execute(@Language("sql") sql: String, vararg params: Any?, result: (ResultSetWrapper) -> T): T? =
    execute(sql, *params).useIfPresent(result)

  /**
   * Executes a SQL query with [params] and calls the [result] function regardless if
   * the query returns a non empty [ResultSet] or not.
   *
   * @param sql The SQL query to execute.
   * @param params The params that corresponds to the '?' in the [sql] query.
   * @return The result of the [result] function.
   * @see execute
   */
  fun <T> executeRegardless(@Language("sql") sql: String, vararg params: Any?, result: (ResultSetWrapper) -> T): T =
    execute(sql, *params).get().use(result)

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
  fun <T> executeToList(@Language("sql") sql: String, vararg params: Any?, result: (ResultSetWrapper) -> T): List<T> =
    execute(sql, *params).useIfPresent {
      it.asSequence().map(result).toList()
    } ?: emptyList()

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
  fun lastInsertId(): Int = execute("SELECT LAST_INSERT_ID() id").useIfPresent {
    it["id"] as Int
  } ?: -1

  /**
   * Insert into the [table] and set the [columns] to these [values].
   */
  fun insertInto(table: String, columns: Array<String>, values: Array<out Any?>) {
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
   * Delete from the [table] where these [columns] have these [values].
   */
  fun deleteFrom(table: String, columns: Array<String>, values: Array<Any?>) {
    val preparedColumns = columns.joinToString(" AND ", transform = ::prepare)
    execute("DELETE FROM $table WHERE $preparedColumns", *values)
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

    /**
     * The global database connection. Can be used by a [ActiveRecord] model
     * when not specifying the connection to use when constructing objects.
     */
    var global: Database? = null
  }

  /**
   * Get the current timestamp.
   *
   * Can be used with [ActiveRecord.value] to provide a default value.
   */
  class CurrentTimestamp : Timestamp(java.util.Calendar.getInstance().time.time)

  /**
   * Get the current date.
   *
   * Can be used with [ActiveRecord.value] to provide a default value.
   */
  class CurrentDate : Date(java.util.Calendar.getInstance().time.time)

  /**
   * Get the current time.
   *
   * Can be used with [ActiveRecord.value] to provide a default value.
   */
  class CurrentTime : Time(java.util.Calendar.getInstance().time.time)
}
