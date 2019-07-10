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
 */
class Database {
  /**
   * Create a database connection with the provided [dsn].
   *
   * @param dsn The database url.
   */
  constructor(dsn: String) {
    this.connection = createConnection(dsn)
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
    this.connection = createConnection(dsn, user, password)
  }

  /**
   * Create a instance from a already existing connection.
   *
   * @param connection The connection to use.
   */
  constructor(connection: Connection) {
    this.connection = connection
  }

  /**
   * The raw connection to the database.
   */
  val connection: Connection

  /**
   * Get all schemas/databases in the database.
   */
  val catalogs: List<String>
    get() =
      if (isSQLite) listOf("main")
      else executeToList("SHOW DATABASES") {
        it[0] as String
      }

  /**
   * A property to see the current database catalog/schema you are
   * working towards. If you set the property you change the working
   * catalog/schema.
   */
  var catalog: String
    get() = if (isSQLite) "main" else connection.catalog
    set(value) {
      if (!isSQLite)
        connection.catalog = value
    }

  /**
   * Sets the [Database.global] database to this one and returns
   * the same connection.
   */
  val asGlobal: Database
    get() {
      global = this
      return this
    }

  /**
   * Returns true if the database connection is of type SQLite.
   */
  val isSQLite: Boolean
    get() = try {
      Class.forName("org.sqlite.SQLiteConnection")
        .isInstance(connection)
    } catch (_: Throwable) {
      false
    }

  /**
   * Returns true if the database connection is of type MySQL.
   */
  val isMySQL: Boolean
    get() = try {
      Class.forName("com.mysql.cj.jdbc.ConnectionImpl")
        .isInstance(connection)
    } catch (_: Throwable) {
      false
    }

  /**
   * Returns true if the database connection is of type SQL Server.
   */
  val isSQLServer: Boolean
    get() = try {
      Class.forName("com.microsoft.sqlserver.jdbc.SQLServerConnection")
        .isInstance(connection)
    } catch (_: Throwable) {
      false
    }

  /**
   * Executes a SQL query to the database.
   *
   * This function returns a [ResultSet] wrapped in a [Optional] just to easily see
   * if there was anything returned from the SQL query.
   *
   * **Attention**: SQLite only supports single query statements
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
      connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
    } catch (e: SQLException) {
      throw DatabaseException(e, sql)
    }
    stmtToSQL[stmt] = sql
    return execute(stmt, *params)
  }

  private val stmtToSQL = WeakHashMap<PreparedStatement, String>()

  /**
   * Execute a [PreparedStatement] with the specified [params] to the statement
   * and get the [ResultSetWrapper] representing the result of the operation.
   */
  fun execute(stmt: PreparedStatement, vararg params: Any?): Optional<ResultSetWrapper> {
    for ((i, param) in params.withIndex()) {
      val o = if (param !is Enum<*>) param else getEnumName(param)
      if (o is Blob && isSQLite)
        stmt.setBytes(i + 1, o.getBytes(1, o.length().toInt()))
      else if (o is Enum<*> && isSQLite)
        stmt.setInt(i + 1, o.ordinal)
      else stmt.setObject(i + 1, o)
    }

    try {
      return if (stmt.execute()) {
        val res = stmt.resultSet
        if (res.isAfterLast)
          return Optional.empty()
        return Optional.of(ResultSetWrapper(res, stmt, this))
      } else Optional.of(ResultSetWrapper(stmt.resultSet, stmt, this))
    } catch (e: SQLException) {
      throw DatabaseException(e, stmtToSQL[stmt])
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
  fun createBlob(): Blob = try {
    connection.createBlob()
  } catch (e: SQLException) {
    if (isSQLite) SimpleBlob()
    else throw e
  }

  /**
   * Creates a new [Clob].
   */
  fun createClob(): Clob = connection.createClob()

  /**
   * Creates a new [Blob] with [bytes].
   */
  fun createBlob(bytes: ByteArray): Blob = createBlob().also { it.setBytes(1, bytes) }

  /**
   * Creates a new [Clob] with [str].
   */
  fun createClob(str: String): Clob = connection.createClob().also { it.setString(1, str) }

  /**
   * Creates a new [Blob] with the [builder].
   */
  fun createBlob(size: Int, builder: ByteBuffer.() -> Unit): Blob =
    createBlob().also { it.setBytes(1, ByteBuffer.allocate(size).apply(builder).array()) }

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
   * Insert into the [table] and set the [columns] to these [values].
   */
  fun insertInto(table: String, columns: Array<String>, values: Array<out Any?>): Optional<ResultSetWrapper> {
    val columnNames = columns.joinToString(transform = ::escape)
    val insertPoints = values.joinToString { "?" }
    return execute("INSERT INTO $table ($columnNames) VALUES ($insertPoints)", *values)
  }

  /**
   * Insert into the [table] and set the [columns] to these [values].
   */
  fun insertInto(table: String, columns: List<String>, values: List<Any?>): Optional<ResultSetWrapper> {
    val columnNames = columns.joinToString(transform = ::escape)
    val insertPoints = values.joinToString { "?" }
    return execute("INSERT INTO $table ($columnNames) VALUES ($insertPoints)", *values.toTypedArray())
  }

  /**
   * Delete from the [table] where these [columns] have these [values].
   */
  fun deleteFrom(table: String, columns: List<String>, values: List<Any?>): Optional<ResultSetWrapper> {
    val preparedColumns = columns.joinToString(" AND ", transform = ::prepare)
    return execute("DELETE FROM $table WHERE $preparedColumns", *values.toTypedArray())
  }

  /**
   * Delete from the [table] where these [columns] have these [values].
   */
  fun deleteFrom(table: String, columns: Array<String>, values: Array<Any?>): Optional<ResultSetWrapper> {
    val preparedColumns = columns.joinToString(" AND ", transform = ::prepare)
    return execute("DELETE FROM $table WHERE $preparedColumns", *values)
  }

  /**
   * Update the a [table] row where [whereColumns] and set these [columns] with these [values].
   */
  fun update(table: String, columns: List<String>, whereColumns: List<String>, values: List<Any?>): Optional<ResultSetWrapper> {
    val preparedWhereColumns = whereColumns.joinToString(" AND ", transform = ::prepare)
    val preparedColumns = columns.joinToString(transform = ::prepare)
    return execute("UPDATE $table SET $preparedColumns WHERE $preparedWhereColumns", *values.toTypedArray())
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

    private fun fixDSN(dsn: String): String {
      return "jdbc:" + when {
        dsn.startsWith("mysql") -> {
          val unicode = if ("useUnicode" in dsn) "" else "&useUnicode=true"
          val jdbcTimeZone = if ("useJDBCCompliantTimezoneShift" in dsn) "" else "&useJDBCCompliantTimezoneShift=true"
          val serverTimezone = if ("serverTimezone" in dsn) "" else "&serverTimezone=UTC"

          val fix =
            if ("?" in dsn) "$unicode$jdbcTimeZone$serverTimezone"
            else "?" + ("$unicode$jdbcTimeZone$serverTimezone".drop(1))

          "$dsn$fix"
        }
        else -> dsn
      }
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
    fun createConnection(dsn: String) = DriverManager.getConnection(fixDSN(dsn))!!

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
      DriverManager.getConnection(fixDSN(dsn), user, password)!!

    /**
     * The global database connection. Can be used by a [ActiveRecord] model
     * when not specifying the connection to use when constructing objects.
     */
    var global: Database? = null

    fun transaction(database: Database? = global, action: () -> Unit) {
      if (database == null)
        throw IllegalStateException("The database to use for the transaction is null")
      database.transaction(action)
    }

    fun execute(@Language("sql") sql: String, vararg params: Any, database: Database? = global): Optional<ResultSetWrapper> {
      if (database == null)
        throw IllegalStateException("The database to use for the transaction is null")
      return database.execute(sql, *params)
    }
  }

  /**
   * A object specifying how to get latest timestamp, date, and time.
   */
  object Current {
    /**
     * Get the current timestamp.
     *
     * Can be used with [ActiveRecord.value] to provide a default value.
     */
    fun timestamp() = Timestamp(java.util.Calendar.getInstance().time.time)

    /**
     * Get the current date.
     *
     * Can be used with [ActiveRecord.value] to provide a default value.
     */
    fun date() = Date(java.util.Calendar.getInstance().time.time)

    /**
     * Get the current time.
     *
     * Can be used with [ActiveRecord.value] to provide a default value.
     */
    fun time() = Time(java.util.Calendar.getInstance().time.time)
  }
}
