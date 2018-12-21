package io.opencubes.sql

import com.mysql.cj.jdbc.ConnectionImpl
import org.intellij.lang.annotations.Language
import java.nio.ByteBuffer
import java.sql.*
import java.util.*

/**
 * A super powered database connection.
 *
 * @author Martin Hövre
 */
class Database(private var _connection: Connection) {
  constructor(dsn: String) : this(createConnection(dsn))
  constructor(user: String, password: String, dsn: String) :
      this(createConnection(dsn, user, password))

  /**
   * The raw connection to the database.
   */
  val connection get() = _connection

  /**
   * Get all schemas/databases in the database.
   * TODO Handle sqlite dialect.
   */
  val schemas: List<String>
    get() = executeToList("SHOW DATABASES") {
      it.getString(1)
    }

  /**
   * A property to see the current schema you are working towards.
   * If you set the property you change the working schema.
   */
  var schema: String
    get() = connection.schema
    set(value) {
      connection.schema = value
    }

  val dialect get() = (connection as ConnectionImpl).url.split(':', limit = 3)[1]

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
    val stmt = connection.prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)
    for ((i, param) in params.withIndex())
      stmt.setObject(i + 1, (param as? Enum<*>)?.toString() ?: param)
    return if (stmt.execute()) {
      val res = stmt.resultSet
      if (!res.first()) Optional.empty()
      else Optional.ofNullable(res)
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
   * Executes a SQL query with [params] and calls the [res] function for every
   * [ResultSet] in the query result.
   *
   * If the you expect your query to result in a array of [ResultSet]s and want
   * them in a list of a specific type ([T]).
   *
   * @param sql The SQL query to execute.
   * @param params The params that corresponds to the '?' in the [sql] query.
   * @return The result list of [T] from [res] or empty.
   * @see execute
   */
  inline fun <T> executeToList(@Language("sql") sql: String, vararg params: Any?, crossinline res: (ResultSet) -> T): List<T> {
    val result = execute(sql, *params).get()
    if (!result.last()) return emptyList()
    val size = result.row
    result.beforeFirst()
    return List(size) {
      result.next()
      res(result)
    }.also { result.close() }
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

  companion object {
    /**
     * Creates a connection to the desired database.
     *
     * @param dsn The DSN to the database ('jdbc:' is already appended)
     * @return The connection to the database.
     */
    @JvmStatic
    fun createConnection(dsn: String) = DriverManager.getConnection("jdbc:$dsn")!!

    /**
     * Creates a connection to the desired database with a [user]name and [password].
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
}
