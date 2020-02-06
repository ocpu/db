package io.opencubes.db.loaders

import io.opencubes.db.IModelDriver
import io.opencubes.db.Model
import io.opencubes.db.SimpleBlob
import io.opencubes.db.sql.ConnectionException
import io.opencubes.db.sql.IResultSetWrapper
import io.opencubes.db.sql.ResultSetWrapper
import io.opencubes.db.sql.select.*
import io.opencubes.db.sql.table.SQLForeignKey
import io.opencubes.db.sql.table.SQLIndex
import io.opencubes.db.sql.table.SQLTable
import io.opencubes.db.sql.table.SQLTableProperty
import io.opencubes.db.sqlEscape
import org.intellij.lang.annotations.Language
import java.sql.*
import java.util.function.Supplier

/**
 * The basic representation of a SQL based model driver.
 */
abstract class GenericSQLModelDriver : IModelDriver {

  /**
   * The connection this model driver has to the database.
   */
  protected abstract val connection: Connection

  private val statementMap = mutableMapOf<PreparedStatement, String>()
  private val sqlMap = mutableMapOf<String, PreparedStatement>()

  final override fun prepare(sql: String): PreparedStatement = try {
    sqlMap.computeIfAbsent(sql) {
      val stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
      statementMap[stmt] = sql
      stmt
    }
  } catch (e: SQLException) {
    throw ConnectionException(e, sql)
  }

  final override fun execute(@Language("sql") sql: String, vararg params: Any?): IResultSetWrapper =
    execute(prepare(sql), *params)

  /**
   * Set a parameter on a prepared statement.
   *
   * @param stmt The statement to set value on.
   * @param index The index of the statment to set.
   * @param param The value to set.
   */
  protected abstract fun setParam(stmt: PreparedStatement, index: Int, param: Any?)

  /**
   * Get the result set wrapper for the result set.
   */
  protected open fun getResultSet(resultSet: ResultSet?, stmt: PreparedStatement): IResultSetWrapper =
    ResultSetWrapper(resultSet, stmt)

  final override fun execute(stmt: PreparedStatement, vararg params: Any?): IResultSetWrapper {
    return try {
      for ((i, param) in params.withIndex()) {
        setParam(stmt, i + 1, param)
      }

      if (stmt.execute()) getResultSet(stmt.resultSet, stmt)
      else getResultSet(null, stmt)
    } catch (e: SQLException) {
      throw ConnectionException(e, statementMap[stmt])
    }
  }

  @Suppress("UNCHECKED_CAST")
  final override fun executeSQL(items: Collection<SelectItem>,
                                from: Pair<String, String>?,
                                joins: List<Join>,
                                conditions: List<WhereCondition>,
                                orderings: List<Pair<SelectItem, Order?>>,
                                groupings: List<SelectItem>,
                                limit: Int?,
                                offset: Int?,
                                params: List<Any?>): FetchableResult {
    val (sql, indexesToRemove) = getSQL(items, from, joins, conditions, orderings, groupings, limit, offset, params)
    return FetchableResult(execute(sql, *params.toMutableList().apply {
      for (index in indexesToRemove)
        removeAt(index)
    }.toTypedArray()))
  }

  final override fun toSQL(
    items: Collection<SelectItem>,
    from: Pair<String, String>?,
    joins: List<Join>,
    conditions: List<WhereCondition>,
    orderings: List<Pair<SelectItem, Order?>>,
    groupings: List<SelectItem>,
    limit: Int?,
    offset: Int?
  ): String = getSQL(items, from, joins, conditions, orderings, groupings, limit, offset).first

  /**
   * Generate the a SQL select query based on the parameters provided.
   *
   * @param items These are the items that are left in the result of the query
   * @param from A pair of with the first item is the actual table name and them the alias for the table. The two values
   * can be the same value and if null it was not specified.
   * @param joins This is a list of connections to make to other tables to resolve the query items.
   * @param conditions This is a list of conditions for the query to follow. The conditions can describe a value or one
   * of the two combining key words AND or OR.
   * @param orderings This is a list describing how the results should be ordered.
   * @param groupings This is a list of what items that can be grouped together to form aggregates.
   * @param limit The maximum amount of items that should be returned.
   * @param offset From all the results start from this offset when returning results.
   * @param params All the params that will fill in all [SelectPlaceholder] items.
   * @return A pair of values that has the first item as the generated SQL and the second as a list of indexes from the
   * [params] to remove before executing.
   */
  @Suppress("UNCHECKED_CAST")
  protected open fun getSQL(items: Collection<SelectItem>, from: Pair<String, String>?, joins: List<Join>, conditions: List<WhereCondition>, orderings: List<Pair<SelectItem, Order?>>, groupings: List<SelectItem>, limit: Int?, offset: Int?, params: List<Any?> = listOf()): Pair<String, List<Int>> {
    val indexesToRemove = mutableListOf<Int>()
    val sql = buildString {
      append("SELECT ")
      for ((index, item) in items.withIndex()) {
        if (item.simple) {
          if (item.table != null)
            append("${item.table.sqlEscape}.")
          append(item.column.sqlEscape)
          if (item.`as` != null)
            append(" AS ${item.`as`.sqlEscape}")
        } else {
          append(item.column)
          if (item.`as` != null)
            append(" AS ${item.`as`.sqlEscape}")
        }

        if (index < items.size - 1)
          append(", ")
      }
      checkNotNull(from) { "Cannot select anything from a null table" }
      append(" FROM ${from.first.sqlEscape}")
      if (from.second != from.first)
        append(" AS ${from.second.sqlEscape}")

      for (join in joins) {
        when (join) {
          is InnerJoin -> append(" INNER JOIN ")
          is LeftJoin -> append(" LEFT JOIN ")
        }
        append("${join.table.sqlEscape} ")
        if (join.alias != join.table)
          append("AS ${join.alias.sqlEscape} ")
        val table = join.alias.sqlEscape
        append("ON ")
        for (condition in join.conditions) {
          when (condition) {
            JoinAndCondition -> append(" AND ")
            JoinOrCondition -> append(" OR ")
            is JoinCondition -> {
              append("$table.${condition.column.sqlEscape} = ")
              append("${condition.other.table!!.sqlEscape}.${condition.other.column.sqlEscape}")
            }
          }
        }
      }

      if (conditions.isNotEmpty())
        append(" WHERE ")
      var placeholders = 0
      for (condition in conditions) {
        when (condition) {
          WhereOrCondition -> append(" OR ")
          WhereAndCondition -> append(" AND ")
          is Condition -> {
            append(condition.item)
            when (val value = condition.value) {
              SelectPlaceholder -> {
                if (placeholders < params.size && params[placeholders] == null) {
                  append(" IS NULL")
                  indexesToRemove += placeholders
                } else append(" = ?")
                placeholders++
              }
              is String -> append(" = '$value'")
              is Number -> append(" = $value")
              is Model -> when (val id = Model.obtainId(value::class.java as Class<out Model>).getActual(value)) {
                is String -> append(" = '$id'")
                is Number -> append(" = $id")
              }
              null -> append(" IS NULL")
              else -> append(" = $value")
            }
          }
        }
      }

      if (orderings.isNotEmpty())
        append(" ORDER BY ")
      for ((index, ordering) in orderings.withIndex()) {
        val (item, order) = ordering
        append(item)
        if (order != null)
          append(" ${order.name}")
        if (index < orderings.lastIndex)
          append(", ")
      }

      if (groupings.isNotEmpty())
        append(" GROUP BY ")
      for ((index, item) in groupings.withIndex()) {
        append(item)
        if (index < groupings.lastIndex)
          append(", ")
      }

      if (limit != null)
        append(" LIMIT $limit")

      if (offset != null)
        append(" OFFSET $offset")
    }

    return sql to indexesToRemove.apply { sortDescending() }
  }

  final override fun <R> transaction(transaction: Supplier<R>): R {
    val auto = connection.autoCommit
    if (auto) connection.autoCommit = false
    val savepoint = connection.setSavepoint()
    try {
      val res = transaction.get()
      connection.commit()
      return res
    } catch (e: Throwable) {
      connection.rollback(savepoint)
      throw e
    } finally {
      if (auto) connection.autoCommit = true
    }
  }

  /**
   * Any additions to make on a SQL representation of a [SQLTableProperty].
   */
  protected abstract fun getPropertyAddition(property: SQLTableProperty): String

  /**
   * How to create a index in the SQL database.
   */
  protected open fun createIndex(table: String, name: String?, properties: String): String =
    "INDEX ${if (name != null) "`$name`" else ""}($properties)"

  override fun toSQL(table: SQLTable): String {
    val properties = table.properties.joinToString(",\n  ") {
      IModelDriver.propertyToSQL(it, getPropertyAddition(it))
    }

    val foreignKeys =
      if (table.foreignKeys.isNotEmpty())
        table.foreignKeys.joinToString(",\n  ", transform = IModelDriver.Companion::foreignKeyToSQL)
      else ""

    val primaryKey =
      if (table.primaryKey != null) {
        val props = table.primaryKey.properties.joinToString { "`$it`" }
        "CONSTRAINT `${table.primaryKey.name}` PRIMARY KEY ($props)"
      } else ""


    val uniques = if (table.uniques.isNotEmpty()) {
      table.uniques.joinToString(",\n  ") { index ->
        val name = if (index.name != null) "CONSTRAINT `${index.name}` " else ""
        "${name}UNIQUE (${index.properties.joinToString { "`$it`" }})"
      }
    } else ""

    val indices = if (table.indices.isNotEmpty())
      table.indices.joinToString(",\n  ") { index ->
        createIndex(table.name, index.name, index.properties.joinToString { "`$it`" })
      }
    else ""

    return toSQL(table.name, properties, foreignKeys, primaryKey, uniques, indices)
  }

  override fun correctTable(table: SQLTable): SQLTable = table

  override fun createBlob(): Blob {
    return try {
      connection.createBlob()
    } catch (_: SQLFeatureNotSupportedException) {
      SimpleBlob()
    }
  }

  /**
   * Get the SQL representation of the creation of a table that this model driver represents.
   */
  @Suppress("DuplicatedCode")
  protected open fun toSQL(table: String, properties: String, foreignKeys: String, primaryKey: String, uniques: String, indices: String): String {
    val props = if (properties.isNotEmpty()) "\n  $properties" else properties
    val fKeys = if (foreignKeys.isNotEmpty()) ",\n\n  $foreignKeys" else foreignKeys
    val us = if (uniques.isNotEmpty()) ",\n\n  $uniques" else uniques
    val `is` = if (indices.isNotEmpty()) ",\n\n  $indices" else indices
    val pKey = if (primaryKey.isNotEmpty()) ",\n\n  $primaryKey" else primaryKey
    return "CREATE TABLE `$table` ($props$pKey$us$`is`$fKeys\n);"
  }

  override fun migrateSQL(vararg tables: List<SQLTable>): String {
    val driverTables = getCurrentTables()
    val outSQL = StringBuilder()
    for (all in tables) {

      table@ for (modelTable in all) {
        val existingTable = driverTables.find { modelTable.name == it.name }
        if (existingTable == null) {
          if ("CREATE TABLE ${modelTable.name}" !in outSQL && "CREATE TABLE IF NOT EXISTS ${modelTable.name}" !in outSQL) {
            if (outSQL.isNotBlank())
              outSQL.append("\n-- separator\n")

            val split = toSQL(modelTable)
              .replace("CREATE INDEX", "CREATE INDEX IF NOT EXISTS")
              .replace("CREATE UNIQUE INDEX", "CREATE UNIQUE INDEX IF NOT EXISTS")
              .split("\n\n")
              .toMutableList()

            val indices =
              if (split[split.lastIndex].split("\n").any { it.startsWith("CREATE") })
                "\n-- separator\n" +
                  split.removeAt(split.lastIndex).split(";\n").joinToString("\n-- separator\n") {
                    if (it.endsWith(';')) "$it\n" else "$it;\n"
                  }
              else ""
            val table = split.joinToString("\n\n")

            outSQL.append("$table$indices")
          }
        } else {
          val mods = StringBuilder()

          // Properties
          for (oldProperty in existingTable.properties) {
            val newProperty = modelTable.properties.find { it.name == oldProperty.name }
            if (newProperty == null) {
              val (sql, recreated) = dropProperty(existingTable, modelTable, oldProperty)
              val sb = if (recreated) outSQL else mods
              if (sb.isNotBlank())
                sb.append("\n-- separator\n")
              sb.append(sql)
              if (recreated)
                continue@table
            } else if (newProperty != oldProperty) {
              val (sql, recreated) = alterProperty(existingTable, modelTable, oldProperty, newProperty)
              val sb = if (recreated) outSQL else mods
              if (sb.isNotBlank())
                sb.append("\n-- separator\n")
              sb.append(sql)
              if (recreated)
                continue@table
            }
          }

          for (newProperty in modelTable.properties) {
            val oldProperty = existingTable.properties.find { it.name == newProperty.name }
            if (oldProperty != null)
              continue
            val (sql, recreated) = addProperty(existingTable, modelTable, newProperty)
            val sb = if (recreated) outSQL else mods
            if (sb.isNotBlank())
              sb.append("\n-- separator\n")
            sb.append(sql)
            if (recreated)
              continue@table
          }

          // Primary key
          if (existingTable.primaryKey?.properties != modelTable.primaryKey?.properties) {
            val (sql, recreated) = alterPrimaryKey(existingTable, modelTable)
            val sb = if (recreated) outSQL else mods
            if (sb.isNotBlank())
              sb.append("\n-- separator\n")
            sb.append(sql)
            if (recreated)
              continue@table
          }

          // Foreign keys
          val skipForeignKeys = mutableListOf<SQLForeignKey>()
          for (oldForeignKey in existingTable.foreignKeys) {
            if (oldForeignKey in modelTable.foreignKeys) {
              skipForeignKeys += oldForeignKey
              continue
            }
            val namesake = modelTable.foreignKeys.find {
              it.name == oldForeignKey.name &&
                it.properties == oldForeignKey.properties &&
                it.reference == oldForeignKey.reference &&
                it.changeAction == oldForeignKey.changeAction &&
                it.deleteAction == oldForeignKey.deleteAction
            }
            if (namesake != null)
              continue

            val (sql, recreated) = dropForeignKey(existingTable, modelTable, oldForeignKey)
            val sb = if (recreated) outSQL else mods
            if (sb.isNotBlank())
              sb.append("\n-- separator\n")
            sb.append(sql)
            if (recreated)
              continue@table
          }

          for (foreignKey in modelTable.foreignKeys) {
            if (foreignKey in skipForeignKeys)
              continue
            if (mods.isNotBlank())
              mods.append("\n-- separator\n")

            checkNotNull(foreignKey.name) { "Foreign key does not have a name" }

            mods.append(
              "ALTER TABLE `${modelTable.name}` ADD ${IModelDriver.foreignKeyToSQL(foreignKey)};"
            )
          }

          // Indices
          val indexSkip = mutableListOf<SQLIndex>()
          val oldIndices = existingTable.indices.toMutableList()
          oldIndices.removeAll(modelTable.indices)
          for (index in oldIndices) {
            val item = modelTable.indices.find { it.properties == index.properties }

            if (mods.isNotBlank())
              mods.append("\n-- separator\n")
            mods.append("DROP INDEX `${index.name}`;")

            if (item != null) {
              mods.append("\n-- separator\n")
              val properties = index.properties.joinToString { "`$it`" }
              mods.append("CREATE INDEX `${item.name}` ON `${existingTable.name}` ($properties);")
              indexSkip += item
            }
          }

          val newIndices = modelTable.indices.toMutableList()
          newIndices.removeAll(existingTable.indices)
          newIndices.removeAll(indexSkip)
          for (index in newIndices) {
            if (mods.isNotBlank())
              mods.append("\n-- separator\n")
            val properties = index.properties.joinToString { "`$it`" }
            mods.append("CREATE INDEX `${index.name}` ON `${modelTable.name}` ($properties);")
          }

          // Unique indices
          val uniqueSkip = mutableListOf<SQLIndex>()
          val oldUniques = existingTable.uniques.toMutableList()
          oldUniques.removeAll(modelTable.uniques)
          for (index in oldUniques) {
            val item = modelTable.uniques.find { it.properties == index.properties }

            if (mods.isNotBlank())
              mods.append("\n-- separator\n")
            mods.append("DROP INDEX `${index.name}`;\n")

            if (item != null) {
              mods.append("\n-- separator\n")
              val properties = index.properties.joinToString { "`$it`" }
              mods.append("CREATE UNIQUE INDEX `${item.name}` ON `${existingTable.name}` ($properties);")
              uniqueSkip += item
            }
          }

          val newUniques = modelTable.uniques.toMutableList()
          newUniques.removeAll(existingTable.uniques)
          newUniques.removeAll(uniqueSkip)
          for (index in newUniques) {
            if (mods.isNotBlank())
              mods.append("\n-- separator\n")
            val properties = index.properties.joinToString { "`$it`" }
            mods.append("CREATE UNIQUE INDEX `${index.name}` ON `${existingTable.name}` ($properties);")
          }

          // Add all modifications
          if (outSQL.isNotBlank())
            outSQL.append("\n-- separator\n")
          outSQL.append(mods)
        }
      }
    }

    return outSQL.toString()
      .split("\n-- separator\n")
      .asSequence()
      .map(String::trim)
      .toSet()
      .sortedBy { it.endsWith("-- Link table") }
      .filter(String::isNotBlank)
      .joinToString("\n-- separator\n")
      .trim()
  }

  /**
   * How do you drop a foreign key in SQL based on the model driver.
   */
  protected abstract fun dropForeignKey(existingTable: SQLTable, modelTable: SQLTable, foreignKey: SQLForeignKey): Pair<String, Boolean>

  /**
   * How do you alter / modify the primary key of a table in SQL based on the model driver.
   */
  protected abstract fun alterPrimaryKey(existingTable: SQLTable, modelTable: SQLTable): Pair<String, Boolean>

  /**
   * How do you add a table property to a table in SQL based on the model driver.
   */
  protected abstract fun addProperty(existingTable: SQLTable, modelTable: SQLTable, property: SQLTableProperty): Pair<String, Boolean>

  /**
   * How do you alter / modify a table property from a table in SQL based on the model driver.
   */
  protected abstract fun alterProperty(existingTable: SQLTable, modelTable: SQLTable, oldProperty: SQLTableProperty, newProperty: SQLTableProperty): Pair<String, Boolean>

  /**
   * How do you drop a table property from a table in SQL based on the model driver.
   */
  protected abstract fun dropProperty(existingTable: SQLTable, modelTable: SQLTable, property: SQLTableProperty): Pair<String, Boolean>
}
