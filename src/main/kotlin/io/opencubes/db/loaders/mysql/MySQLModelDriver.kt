package io.opencubes.db.loaders.mysql

import io.opencubes.db.*
import io.opencubes.db.loaders.GenericSQLModelDriver
import io.opencubes.db.sql.select.*
import io.opencubes.db.sql.table.*
import io.opencubes.db.values.ValueWrapper
import io.opencubes.db.values.ValueWrapperPreferences
import java.sql.*

/**
 * A MySQL based model driver.
 */
class MySQLModelDriver(override val connection: Connection) : GenericSQLModelDriver() {
//  override val connection: Connection

//  /**
//   * Create a MySQL model driver with separate username and password.
//   *
//   * Sample: `mysql://host/database`
//   *
//   * @param uri The uri where the MySQL database exists
//   */
//  constructor(uri: String, user: String, password: String) : super() {
//    connection = DriverManager.getConnection("jdbc:${fixURL(uri)}", user, password)
//  }
//
//  /**
//   * Create a MySQL model driver with integrated username and password.
//   *
//   * Sample: `mysql://username@host/database`
//   *
//   * @param uri The uri where the MySQL database exists
//   */
//  constructor(uri: String, password: String) : super() {
//    connection = DriverManager.getConnection("jdbc:${fixURL(uri)}")
//  }
//
//  /**
//   * Create a MySQL model driver with integrated username and password.
//   *
//   * Sample: `mysql://username:password@host/database`
//   *
//   * @param uri The uri where the MySQL database exists
//   */
//  constructor(uri: String) : super() {
//    connection = DriverManager.getConnection("jdbc:${fixURL(uri)}")
//  }

  override fun setParam(stmt: PreparedStatement, index: Int, param: Any?) = stmt.setObject(index, param)

  override fun getPropertyAddition(property: SQLTableProperty): String =
    if (property.autoIncrement) " AUTO_INCREMENT" else ""

  override fun dropForeignKey(existingTable: SQLTable, modelTable: SQLTable, foreignKey: SQLForeignKey): Pair<String, Boolean> {
    return "ALTER TABLE ${existingTable.name} DROP FOREIGN KEY ${foreignKey.name}" to false
  }

  override fun alterPrimaryKey(existingTable: SQLTable, modelTable: SQLTable): Pair<String, Boolean> = buildString {
    if (existingTable.primaryKey != null)
      append("ALTER TABLE `${existingTable.name}` DROP PRIMARY KEY;\n")
    if (modelTable.primaryKey != null) {
      append("ALTER TABLE `${modelTable.name}` ")
      val properties = modelTable.primaryKey.properties.joinToString { "`$it`" }
      append("ADD CONSTRAINT `${modelTable.name}_pk` PRIMARY KEY ($properties);")
    }
  } to false

  override fun addProperty(existingTable: SQLTable, modelTable: SQLTable, property: SQLTableProperty): Pair<String, Boolean> {
    val prop = IModelDriver.propertyToSQL(property, getPropertyAddition(property))
    return "ALTER TABLE `${existingTable.name}` ADD COLUMN $prop;" to false
  }

  override fun alterProperty(existingTable: SQLTable, modelTable: SQLTable, oldProperty: SQLTableProperty, newProperty: SQLTableProperty): Pair<String, Boolean> {
    val property = IModelDriver.propertyToSQL(newProperty, getPropertyAddition(newProperty))
    return "ALTER TABLE `${existingTable.name}` MODIFY COLUMN $property;" to false
  }

  override fun dropProperty(existingTable: SQLTable, modelTable: SQLTable, property: SQLTableProperty): Pair<String, Boolean> {
    return "ALTER TABLE `${existingTable.name}` DROP COLUMN `${property.name}`;" to false
  }

  override fun getSQLTypeFromClass(type: Class<*>, preferences: ValueWrapperPreferences?): Pair<String, List<String>> {
    return when {
      preferences is ValueWrapperPreferences.Number && preferences.type != ValueWrapperPreferences.Number.Type.DYNAMIC -> {
        return when (preferences.type) {
          ValueWrapperPreferences.Number.Type.DECIMAL -> "DECIMAL" to preferences.params.map { "$it" }
          else -> "" to emptyList()
        }
      }
      String::class.java.isAssignableFrom(type) -> if (preferences is ValueWrapperPreferences.String) {
        when {
          preferences.maxLength > 0 -> {
            when {
              preferences.binary && preferences.pad -> "BINARY"
              preferences.binary && !preferences.pad -> "VARBINARY"
              !preferences.binary && preferences.pad -> "CHAR"
              !preferences.binary && !preferences.pad -> "VARCHAR"
              else -> throw IllegalStateException("impossible")
            } to listOf("${preferences.maxLength}")
          }
          preferences.binary -> throw IllegalStateException("Cannot specify that a column should be binary when no max length is specified.")
          else -> "MEDIUMTEXT" to emptyList()
        }
      } else "MEDIUMTEXT" to emptyList()
      Int::class.java.isAssignableFrom(type) || Integer::class.java.isAssignableFrom(type) -> "INTEGER" to emptyList()
      Long::class.java.isAssignableFrom(type) || java.lang.Long::class.java.isAssignableFrom(type) -> "BIGINT" to emptyList()
      Short::class.java.isAssignableFrom(type) || java.lang.Short::class.java.isAssignableFrom(type) -> "SMALLINT" to emptyList()
      Byte::class.java.isAssignableFrom(type) || java.lang.Byte::class.java.isAssignableFrom(type) -> "TINYINT" to emptyList()
      Float::class.java.isAssignableFrom(type) || java.lang.Float::class.java.isAssignableFrom(type) -> "FLOAT" to emptyList()
      Double::class.java.isAssignableFrom(type) || java.lang.Double::class.java.isAssignableFrom(type) -> "DOUBLE" to emptyList()
      Timestamp::class.java.isAssignableFrom(type) -> "TIMESTAMP" to emptyList()
      Time::class.java.isAssignableFrom(type) -> "TIME" to emptyList()
      Date::class.java.isAssignableFrom(type) -> "DATE" to emptyList()
      Blob::class.java.isAssignableFrom(type) -> "BLOB" to emptyList()
      Clob::class.java.isAssignableFrom(type) -> "CLOB" to emptyList()
      java.lang.Boolean::class.java.isAssignableFrom(type) -> "BOOLEAN" to emptyList()
      Model::class.java.isAssignableFrom(type) -> {
        @Suppress("UNCHECKED_CAST")
        val valueWrapper: ValueWrapper<*> = Model.obtainId(type as Class<out Model>).value(Model.obtainEmpty(type))
        getSQLTypeFromClass(valueWrapper.type, valueWrapper.preferences)
      }
      else -> {
        val values = type.enumConstants as? Array<*> ?: return "" to emptyList()
        return "ENUM" to values.map value@{
          if (it !is Enum<*>) return@value "'$it'"
          type.getField(it.name)
            .getAnnotation(SerializedName::class.java)?.value ?: "'$it'"
        }
      }
    }
  }

  @Suppress("UNCHECKED_CAST")
  override fun executeSQL(items: Collection<SelectItem>, from: Pair<String, String>?, joins: List<Join>, conditions: List<WhereCondition>, orderings: List<Pair<SelectItem, Order?>>, groupings: List<SelectItem>, limit: Int?, offset: Int?, params: List<Any?>): FetchableResult {
    val indexesToRemove = mutableListOf<Int>()
    return FetchableResult(execute(buildString {
      fun append(item: SelectItem) {
        if (!item.simple) check(item.`as` != null) { "Complex SelectItem did not provide a alias" }
        append(
          item.`as`?.sqlEscape ?:
          if (item.table != null) "${item.table.sqlEscape}.${item.column.sqlEscape}"
          else item.column.sqlEscape
        )
      }

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
      checkNotNull(from)
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
                } else
                  append(" = ?")
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
        if (index < orderings.size - 1)
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

      check(placeholders == params.size) {
        if (placeholders < params.size)
          "Too many params for query. Placeholders: $placeholders, params: ${params.size}"
        else
          "Too few params for query. Placeholders: $placeholders, params: ${params.size}"
      }
    }, *params.toMutableList().apply {
      indexesToRemove.sortDescending()
      for (index in indexesToRemove)
        removeAt(index)
    }.toTypedArray()))
  }

  override fun getCurrentTables(): List<SQLTable> {
    val tables = mutableSetOf<SQLTable>()

    execute("""
select index_schema,
       index_name,
       group_concat(column_name order by seq_in_index) as index_columns,
       index_type,
       non_unique                                      as is_unique,
       table_name
from information_schema.statistics
where table_schema = ?
group by index_schema,
         index_name,
         index_type,
         non_unique,
         table_name
order by index_schema,
         index_name;
    """.trimIndent(), connection.catalog).useIfPresent { res ->
      for (row in res) {
        val isUnique = try {
          row["is_unique"] as Long != 1L
        } catch (_: ClassCastException) {
          row["is_unique"] as Int != 1
        }
        val table = row["table_name"] as String
        val index = SQLIndex(row["index_name"] as String, (row["index_columns"] as String).split(','))

        val setTable = tables.find { it.name == table }
        when {
          setTable == null -> tables.add(SQLTable(
            table,
            mutableListOf(),
            if (index.name == "PRIMARY") index else null,
            if (index.name != "PRIMARY" && !isUnique) mutableListOf(index) else mutableListOf(),
            if (index.name != "PRIMARY" && isUnique) mutableListOf(index) else mutableListOf(),
            mutableListOf()
          ))
          index.name == "PRIMARY" -> {
            tables.remove(setTable)
            tables.add(setTable.copy(primaryKey = index))
          }
          else -> setTable.addIndex(isUnique, index)
        }
      }
    }
    execute("""
SELECT c.TABLE_NAME,
       c.COLUMN_NAME,
       c.COLUMN_TYPE,
       IF(c.IS_NULLABLE = 'YES', TRUE, FALSE) IS_NULLABLE,
       c.COLUMN_DEFAULT,
       c.EXTRA
FROM information_schema.COLUMNS c
WHERE c.TABLE_SCHEMA = ?;
    """.trimIndent(), connection.catalog).useIfPresent { res ->
      for (row in res) {
        val table = row["TABLE_NAME"] as String

        val setTable = tables.find { it.name == table } ?: run {
          val new = SQLTable(table, mutableListOf(), null, mutableListOf(), mutableListOf(), mutableListOf())
          tables += new
          new
        }

        val (propertyType, propertyParams) =
          when {
            row["COLUMN_TYPE"] == "int(11)" -> "INTEGER" to listOf()
            row["COLUMN_TYPE"] == "tinyint(1)" -> "BOOLEAN" to listOf()
            '(' in row["COLUMN_TYPE"] as String -> {
              val (type, params) = (row["COLUMN_TYPE"] as String).dropLast(1).split('(')

              type.toUpperCase() to params.split(", ", ",")
            }
            else -> (row["COLUMN_TYPE"] as String).toUpperCase() to listOf()
          }

        setTable.addProperty(SQLTableProperty(
          row["COLUMN_NAME"] as String,
          propertyType,
          propertyParams,
          try {
            row["IS_NULLABLE"] as Long == 1L
          } catch (_: ClassCastException) {
            row["IS_NULLABLE"] as Int == 1
          },
          run {
            val value = row["COLUMN_DEFAULT"] as String?
            if (propertyType == "BOOLEAN") when (value) {
              "1" -> "true"
              "0" -> "false"
              else -> null
            } else value
          },
          "AUTO_INCREMENT" in (row["EXTRA"] as String).toUpperCase()
        ))
      }
    }
    execute("""
SELECT tc.TABLE_NAME,
       tc.CONSTRAINT_NAME,
       kcu.COLUMN_NAME,
       kcu.REFERENCED_TABLE_NAME,
       kcu.REFERENCED_COLUMN_NAME,
       rc.DELETE_RULE,
       rc.UPDATE_RULE
FROM information_schema.TABLE_CONSTRAINTS tc
         JOIN information_schema.KEY_COLUMN_USAGE kcu on tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME
         JOIN information_schema.REFERENTIAL_CONSTRAINTS rc ON rc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
WHERE tc.TABLE_SCHEMA = ?
  AND CONSTRAINT_TYPE = 'FOREIGN KEY';
    """.trimIndent(), connection.catalog).useIfPresent { res ->
      for (row in res) {
        val table = row["TABLE_NAME"]
        val setTable = tables.find { it.name == table } ?: continue
        setTable.addForeignKey(SQLForeignKey(
          row["CONSTRAINT_NAME"] as String?,
          listOf(row["COLUMN_NAME"] as String),
          SQLTableReference(row["REFERENCED_TABLE_NAME"] as String, listOf(row["REFERENCED_COLUMN_NAME"] as String)),
          ForeignKeyAction.fromString(row["DELETE_RULE"] as String?),
          ForeignKeyAction.fromString(row["UPDATE_RULE"] as String?)
        ))
      }
    }

    return tables.toList()
  }

  override fun completeMigrateSQL(vararg tables: List<SQLTable>): String {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  override fun correctTable(table: SQLTable): SQLTable {
    if (table.primaryKey != null)
      if (table.primaryKey.name != "PRIMARY")
        return table.copy(primaryKey = SQLIndex("PRIMARY", table.primaryKey.properties))
    return table
  }

  override fun createClob(): Clob = connection.createClob()

  /** Statics */
  companion object {
    private fun fixURL(url: String): String {
      val unicode = if ("useUnicode" in url) "" else "&useUnicode=true"
      val jdbcTimeZone = if ("useJDBCCompliantTimezoneShift" in url) "" else "&useJDBCCompliantTimezoneShift=true"
//      val serverTimezone = if ("serverTimezone" in url) "" else run {
//        val offset = TimeZone.getDefault().toZoneId().rules.getOffset(Instant.now())
//        "&serverTimezone=UTC$offset"
//      }

      val fix =
        if ("?" in url) "$unicode$jdbcTimeZone"
        else "?" + ("$unicode$jdbcTimeZone".drop(1))

      return "$url$fix"
    }
  }
}
