package io.opencubes.db.loaders.sqlite

import io.opencubes.db.IModelDriver
import io.opencubes.db.Model
import io.opencubes.db.getEnumName
import io.opencubes.db.loaders.GenericSQLModelDriver
import io.opencubes.db.sql.IResultSetWrapper
import io.opencubes.db.sql.ResultSetWrapper
import io.opencubes.db.sql.parseSQLToTables
import io.opencubes.db.sql.table.SQLForeignKey
import io.opencubes.db.sql.table.SQLTable
import io.opencubes.db.sql.table.SQLTableProperty
import io.opencubes.db.values.ValueWrapper
import io.opencubes.db.values.ValueWrapperPreferences
import java.sql.*

/**
 * @constructor
 * Create or use a SQLite database based on a filepath.
 *
 * @param filepath Where the SQLite file is located.
 * @property filepath Where this current SQLite model driver saves its data.
 */
class SQLiteModelDriver(val filepath: String) : GenericSQLModelDriver() {
  /**
   * Create a in memory SQLite database.
   */
  constructor() : this(":memory:")

  override val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$filepath")

  override fun setParam(stmt: PreparedStatement, index: Int, param: Any?) {
    when (val o = if (param !is Enum<*>) param else getEnumName(param)) {
      is Blob -> stmt.setBytes(index, o.getBytes(1, o.length().toInt()))
      is Enum<*> -> stmt.setInt(index, o.ordinal)
      else -> stmt.setObject(index, o)
    }
  }

  override fun getResultSet(resultSet: ResultSet?, stmt: PreparedStatement): IResultSetWrapper =
    SQLiteResultWrapper(resultSet, stmt, this)

  override fun getSQLTypeFromClass(type: Class<*>, preferences: ValueWrapperPreferences?): Pair<String, List<String>> {
    return when {
      String::class.java.isAssignableFrom(type) -> "TEXT" to emptyList()
      Int::class.java.isAssignableFrom(type) || Integer::class.java.isAssignableFrom(type) -> "INTEGER" to emptyList()
      Long::class.java.isAssignableFrom(type) || java.lang.Long::class.java.isAssignableFrom(type) -> "INTEGER" to emptyList()
      Short::class.java.isAssignableFrom(type) || java.lang.Short::class.java.isAssignableFrom(type) -> "INTEGER" to emptyList()
      Byte::class.java.isAssignableFrom(type) || java.lang.Byte::class.java.isAssignableFrom(type) -> "INTEGER" to emptyList()
      Float::class.java.isAssignableFrom(type) || java.lang.Float::class.java.isAssignableFrom(type) -> "REAL" to emptyList()
      Double::class.java.isAssignableFrom(type) || java.lang.Double::class.java.isAssignableFrom(type) -> "REAL" to emptyList()
      Timestamp::class.java.isAssignableFrom(type) -> "TIMESTAMP" to emptyList()
      Time::class.java.isAssignableFrom(type) -> "TIME" to emptyList()
      Date::class.java.isAssignableFrom(type) -> "DATE" to emptyList()
      Blob::class.java.isAssignableFrom(type) -> "BLOB" to emptyList()
      Clob::class.java.isAssignableFrom(type) -> "TEXT" to emptyList()
      java.lang.Boolean::class.java.isAssignableFrom(type) -> "INTEGER" to emptyList()
      Model::class.java.isAssignableFrom(type) -> {
        @Suppress("UNCHECKED_CAST")
        val valueWrapper: ValueWrapper<*> = Model.obtainId(type as Class<out Model>).value(Model.obtainEmpty(type))
        getSQLTypeFromClass(valueWrapper.type, valueWrapper.preferences)
      }
      else -> "INTEGER" to emptyList()
    }
  }

  override fun getCurrentTables(): List<SQLTable> {
    var resultSQL = ""
    execute("SELECT `sql` FROM sqlite_master").use { res ->
      res.forEach {
        val sql = it["sql"] as String? ?: return@forEach
        resultSQL += sql + if (!sql.endsWith(';')) ";\n" else "\n"
      }
    }
    return parseSQLToTables(resultSQL)
  }

  override fun getPropertyAddition(property: SQLTableProperty): String =
    if (property.autoIncrement) " DEFAULT rowid" else ""

  override fun createIndex(table: String, name: String?, properties: String): String {
    checkNotNull(name) { "Indices must have a name" }
    return "CREATE INDEX `$name` ON `$table` ($properties)"
  }

  @Suppress("DuplicatedCode")
  override fun toSQL(table: String, properties: String, foreignKeys: String, primaryKey: String, uniques: String, indices: String): String {
    val props = if (properties.isNotEmpty()) "\n  $properties" else properties
    val fKeys = if (foreignKeys.isNotEmpty()) ",\n\n  $foreignKeys" else foreignKeys
    val us = if (uniques.isNotEmpty()) ",\n\n  $uniques" else uniques
    val `is` = if (indices.isNotEmpty()) "\n\n${indices.replace(",\n  ", ";\n")};" else indices
    val pKey = if (primaryKey.isNotEmpty()) ",\n\n  $primaryKey" else primaryKey
    return "CREATE TABLE `$table` ($props$pKey$us$fKeys\n);$`is`"
  }

  override fun completeMigrateSQL(vararg tables: List<SQLTable>): String {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  override fun alterProperty(existingTable: SQLTable, modelTable: SQLTable, oldProperty: SQLTableProperty, newProperty: SQLTableProperty): Pair<String, Boolean> {
    return recreate(existingTable, modelTable) to true
  }

  override fun dropProperty(existingTable: SQLTable, modelTable: SQLTable, property: SQLTableProperty): Pair<String, Boolean> {
    return recreate(existingTable, modelTable) to true
  }

  override fun addProperty(existingTable: SQLTable, modelTable: SQLTable, property: SQLTableProperty): Pair<String, Boolean> {
    return recreate(existingTable, modelTable) to true
  }

  override fun dropForeignKey(existingTable: SQLTable, modelTable: SQLTable, foreignKey: SQLForeignKey): Pair<String, Boolean> {
    return recreate(existingTable, modelTable) to true
  }

  override fun alterPrimaryKey(existingTable: SQLTable, modelTable: SQLTable): Pair<String, Boolean> {
    return recreate(existingTable, modelTable) to true
  }


  private fun recreate(fromTable: SQLTable, toTable: SQLTable): String {
    val tempName = "${fromTable.name}_to_${toTable.name}_temp_table"
    val columns = fromTable.properties
      .map(SQLTableProperty::name)
      .intersect(toTable.properties.map(SQLTableProperty::name))
      .joinToString { "`$it`" }


    val split = toSQL(toTable).replaceFirst(toTable.name, tempName)
      .replace("CREATE INDEX", "CREATE INDEX IF NOT EXISTS")
      .replace("CREATE UNIQUE INDEX", "CREATE UNIQUE INDEX IF NOT EXISTS")
      .split("\n\n")
      .toMutableList()

    val indices =
      if (split[split.lastIndex].split("\n").any { it.startsWith("CREATE") })
        "\n-- separator\n" +
          split.removeAt(split.lastIndex).split(";\n").joinToString("\n-- separator\n") { "$it;\n" }
      else ""
    val tempTable = split.joinToString("\n\n")

    val insertion = if (columns.isNotEmpty())
      "\n-- separator\n" +
        "INSERT INTO `$tempName` ($columns) SELECT $columns FROM ${fromTable.name};\n"
    else ""

    return "DROP TABLE IF EXISTS `$tempName`\n" +
      "\n-- separator\n" +
      "$tempTable\n" +
      insertion +
      "\n-- separator\n" +
      "DROP TABLE `${fromTable.name}`;\n" +
      "\n-- separator\n" +
      "ALTER TABLE `$tempName` RENAME TO `${toTable.name}`;" +
      indices
  }

  override fun createClob(): Clob {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  /** builtin */
  override fun toString(): String = "sqlite:$filepath"

  private class SQLiteResultWrapper(resultSet: ResultSet?, stmt: PreparedStatement, val driver: IModelDriver) : ResultSetWrapper(resultSet, stmt) {
    override fun get(columnName: String): Any? =
      if (resultSet != null) {
        if (resultSet.isBeforeFirst)
          resultSet.next()
        val res = resultSet.getObject(columnName)
        if (res is ByteArray)
          driver.createBlob(res)
        else res
      } else throw Exception("No row found")

    override fun get(columnIndex: Int): Any? =
      if (resultSet != null) {
        if (resultSet.isBeforeFirst)
          resultSet.next()
        val res = resultSet.getObject(columnIndex)
        if (res is ByteArray)
          driver.createBlob(res)
        else res
      } else throw Exception("No row found")
  }
}
