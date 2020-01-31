package io.opencubes.db

import io.opencubes.db.loaders.IDBLoader
import io.opencubes.db.sql.IResultSetWrapper
import io.opencubes.db.sql.UnsupportedDriver
import io.opencubes.db.sql.select.*
import io.opencubes.db.sql.table.SQLForeignKey
import io.opencubes.db.sql.table.SQLTable
import io.opencubes.db.sql.table.SQLTableProperty
import io.opencubes.db.values.ValueWrapperPreferences
import org.intellij.lang.annotations.Language
import java.sql.Blob
import java.sql.Clob
import java.sql.PreparedStatement
import java.util.*
import java.util.function.Supplier

/**
 * A generic interface for every kind of driver that connects to a database
 * and that can be used in a [Model].
 */
interface IModelDriver {
  /**
   * Execute the provided [SQL][sql] with the [params] provided.
   */
  fun execute(@Language("sql") sql: String, vararg params: Any?): IResultSetWrapper

  /**
   * Execute the provided [PreparedStatement][stmt] with the [params] provided.
   */
  fun execute(stmt: PreparedStatement, vararg params: Any?): IResultSetWrapper

  /**
   * Do a lot of actions at once. If anything bad happens in the meantime a
   * rollback will happen to before this function.
   */
  fun <R> transaction(transaction: Supplier<R>): R

  /**
   * Do a lot of actions at once. If anything bad happens in the meantime a
   * rollback will happen to before this function.
   */
  fun <R> transaction(transaction: () -> R): R = transaction(Supplier(transaction))

  /**
   * From a generic class get the SQL representation of the type.
   *
   * @param type The type class to convert.
   * @param preferences Any kind of preferences the type has.
   * @return A pair where the first is the sql type and the second are any
   *  type parameters.
   */
  fun getSQLTypeFromClass(type: Class<*>, preferences: ValueWrapperPreferences<*>?): Pair<String, List<String>>

  /**
   * Get all tables currently in the database.
   */
  fun getCurrentTables(): List<SQLTable>

  /**
   * Generate the creation sql for the [table] passed.
   */
  fun toSQL(table: SQLTable): String

  /**
   * Generate SQL code that can be ran to synchronize the in code model to the
   * table in the database. These actions include creating, modifying, and
   * dropping indices, unique indices, primary keys, and foreign keys. The
   * actions that impossible is renaming properties and tables; and dropping
   * unused tables. To get those features use [completeMigrateSQL].
   */
  fun migrateSQL(vararg tables: List<SQLTable>): String

  /**
   * Generate SQL code that can be ran to synchronize the in code model to the
   * table in the database. These actions include creating, modifying, and
   * dropping indices, unique indices, primary keys, and foreign keys. These
   * actions also include renaming properties and tables; and dropping unused
   * tables. To get those features use [completeMigrateSQL].
   */
  fun completeMigrateSQL(vararg tables: List<SQLTable>): String

  /** Create a generic blob of data. */
  fun createBlob(): Blob

  /** Create a generic blob of data with the specified data. */
  fun createBlob(bytes: ByteArray?) = createBlob().apply { if (bytes != null) setBytes(1, bytes) }

  /** Create a generic blob of data with data from a function call. */
  fun createBlob(bytesSupplier: Supplier<ByteArray>) = createBlob(bytesSupplier.get())

  /** Create a generic blob of data with data from a function call. */
  fun createBlob(bytesSupplier: () -> ByteArray) = createBlob(Supplier(bytesSupplier))

  /** Create a generic blob of data with specified data if not null. */
  fun createBlobOrNull(bytes: ByteArray?) = if (bytes != null) createBlob(bytes) else null

  /** Create a generic clob of data. */
  fun createClob(): Clob

  /** Create a generic clob of data with the specified data. */
  fun createClob(data: String?) = createClob().apply { if (data != null) setString(1, data) }

  /** Create a generic clob of data with data from a function call. */
  fun createClob(dataSupplier: Supplier<String>) = createClob(dataSupplier.get())

  /** Create a generic clob of data with data from a function call. */
  fun createClob(dataSupplier: () -> String) = createClob(Supplier(dataSupplier))

  /** Create a generic clob of data with specified data if not null. */
  fun createClobOrNull(data: String?) = if (data != null) createClob(data) else null

  /**
   * Correct a [SQLTable] for the database this model driver represents.
   */
  fun correctTable(table: SQLTable): SQLTable

  /**
   * Make this model driver the globally accessible from the [IModelDriver.global] property.
   */
  fun setGlobal(): IModelDriver {
    global = this
    return this
  }


  /**
   * Insert into the table and set the columns to these values.
   */
  fun insert(table: String, columns: List<String>, values: List<Any?>): IResultSetWrapper {
    val columnNames = columns.joinToString { "`$it`" }
    val insertPoints = values.joinToString { "?" }
    @Suppress("SqlDialectInspection")
    return execute("INSERT INTO $table ($columnNames) VALUES ($insertPoints);", *values.toTypedArray())
  }

  /**
   * Update the a table row where the whereColumns and set these columns with these values.
   */
  fun update(table: String, columns: List<String>, whereColumns: List<String>, values: List<Any?>): IResultSetWrapper {
    val preparedWhereColumns = whereColumns.joinToString(" AND ") { "`$it`" }
    val preparedColumns = columns.joinToString { "`$it`" }
    @Suppress("SqlDialectInspection")
    return execute("UPDATE $table SET $preparedColumns WHERE $preparedWhereColumns;", *values.toTypedArray())
  }

  /**
   * Delete from the table where these columns have these values.
   */
  fun deleteFrom(table: String, columns: List<String>, values: List<Any?>): IResultSetWrapper {
    val preparedColumns = columns.joinToString(" AND ") { "`$it`" }
    @Suppress("SqlDialectInspection")
    return execute("DELETE FROM $table WHERE $preparedColumns;", *values.toTypedArray())
  }

  /**
   * Prepare a sql query with the specified sql.
   */
  fun prepare(sql: String): PreparedStatement

  fun select(vararg items: SelectItem) = SelectBuilder(this, items.toList())
  fun select(items: Collection<SelectItem>) = SelectBuilder(this, items)
  fun executeSQL(items: Collection<SelectItem>, from: Pair<String, String>?, joins: List<Join>, conditions: List<WhereCondition>, orderings: List<Pair<SelectItem, Order?>>, groupings: List<SelectItem>, limit: Int?, offset: Int?, params: List<Any?>): FetchableResult
  fun toSQL(items: Collection<SelectItem>, from: Pair<String, String>?, joins: List<Join>, conditions: List<WhereCondition>, orderings: List<Pair<SelectItem, Order?>>, groupings: List<SelectItem>, limit: Int?, offset: Int?): String

  /** Statics */
  companion object {
    /**
     * The globally provided [IModelDriver].
     */
    @JvmStatic
    var global: IModelDriver? = null

    private val driverLoader = ServiceLoader.load(IDBLoader::class.java)
    private fun connect(dsn: String, info: Map<String, String>): IModelDriver {
      val iterator = driverLoader.iterator()
      while (iterator.hasNext()) {
        val loader = try {
          iterator.next()
        } catch (e: ServiceConfigurationError) {
          if (e.cause is UnsupportedDriver)
            continue
          throw e
        }

        if (loader.accepts(dsn, info))
          return loader.load(dsn, info)
      }
      throw IllegalArgumentException("Cannot find a database loader for: $dsn")
    }
//      try {
//        driverLoader.find {
//          try {
//            it.accepts(dsn, info)
//          } catch (e: Throwable) {
//            false
//          }
//        }?.load(dsn, info)
//          ?: throw IllegalArgumentException("Cannot find a database loader for: $dsn")
//      } catch (e: ServiceConfigurationError) {
//        if (e.cause is UnsupportedDriver)
//          throw e.cause as UnsupportedDriver
//        throw e
//      }
//    {
//      val url = if (dsn.startsWith("jdbc:")) dsn.drop(5) else dsn
//      return when {
//        url.startsWith("sqlite:") -> SQLiteModelDriver(url.drop(7))
////        url.startsWith("mysql:") -> MySQLModelDriver(url)
//        else -> throw IllegalArgumentException("The protocol used is not supported '$url'")
//      }
//    }

    /**
     * @param dsn The URI to connect to.
     * @param user The name of the user to login as. (please read this from a file)
     * @param password The password of the user to login as. (please read this from a file)
     */
    @JvmStatic
    fun connect(dsn: String, user: String? = null, password: String? = null): IModelDriver =
      connect(dsn, mutableMapOf<String, String>().apply {
        if (user != null) put("user", user)
        if (password != null) put("password", password)
      })

    /**
     * Get the stringified version of a [SQLTableProperty].
     */
    @JvmStatic
    fun propertyToSQL(property: SQLTableProperty, ads: String): String {
      val type = property.type +
        (if (property.typeParams.isNotEmpty()) "(${property.typeParams.joinToString()})" else "")
      val nullable = if (!property.nullable) " NOT NULL" else ""
      val default = if ("DEFAULT" !in ads.toUpperCase()) when (property.default) {
        null -> if (property.nullable) " DEFAULT NULL" else ""
        else -> " DEFAULT ${property.default}"
      } else ""

      return "`${property.name}` $type$nullable$default$ads"
    }

    /**
     * Get the stringified version of a [SQLForeignKey].
     */
    @JvmStatic
    fun foreignKeyToSQL(foreignKey: SQLForeignKey): String {
      val name = if (foreignKey.name != null) "CONSTRAINT `${foreignKey.name}` " else ""
      val props = foreignKey.properties.joinToString { "`$it`" }
      val otherTable = foreignKey.reference.table
      val otherProps = foreignKey.reference.properties.joinToString { "`$it`" }
      val change =
        if (foreignKey.changeAction != ForeignKeyAction.NO_ACTION)
          " ON CHANGE ${foreignKey.changeAction}"
        else ""
      val delete =
        if (foreignKey.deleteAction != ForeignKeyAction.NO_ACTION)
          " ON DELETE ${foreignKey.deleteAction}"
        else ""

      return "${name}FOREIGN KEY ($props) REFERENCES `$otherTable` ($otherProps)$change$delete"
    }
  }
}
