package io.opencubes.db.sql

import io.opencubes.db.ForeignKeyAction
import io.opencubes.db.split
import io.opencubes.db.sql.table.*

//private tailrec fun String.splitWithSpecialChars(specialChars: String, current: List<String> = listOf(this)): List<String> {
//  val specialCharsArray = specialChars.toCharArray()
//  if (!current.any { it.indexOfAny(specialCharsArray) != -1 && it.length > 1 })
//    return current
//  return splitWithSpecialChars(specialChars, current.flatMap {
//    val index = it.indexOfAny(specialCharsArray)
//    if (index == -1) return listOf(it)
//    val specialChar = it[index]
//    val split = it.split(specialChar)
//    split.zip(Array(split.lastIndex) { "$specialChar" }) { a, b -> listOf(a, b) }.flatten()
//  })
//}

private fun String.parseSQLTokens(stringChars: String = "\"'", specialChars: String = "", keepStringChars: Boolean = false): MutableList<String> {
  val split = mutableListOf<String>()
  var isString = ' '
  var lastCursor = 0
  var cursor = 0
  discovery@ while (cursor < length) {
    if (isString != ' ') {
      if (isString == get(cursor)) {
        isString = ' '
        if (keepStringChars)
          split.add(substring((lastCursor - 1)..cursor))
        else
          split.add(substring(lastCursor until cursor))
        cursor++
        lastCursor = cursor
        continue@discovery
      }
      cursor++
      continue@discovery
    }
    val char = get(cursor)
    if (lastCursor == cursor) {
      when (char) {
        ' ' -> {
          cursor++
          lastCursor = cursor
          continue@discovery
        }
        in stringChars -> {
          isString = char
          cursor++
          lastCursor = cursor
          continue@discovery
        }
        in specialChars -> {
          split.add("$char")
          cursor++
          lastCursor = cursor
          continue@discovery
        }
        else -> {
          cursor++
          continue@discovery
        }
      }
    }
    when (char) {
      ' ' -> {
        split.add(substring(lastCursor until cursor))
        cursor++
        lastCursor = cursor
        continue@discovery
      }
      in specialChars -> {
        split.add(substring(lastCursor until cursor))
        split.add("$char")
        cursor++
        lastCursor = cursor
        continue@discovery
      }
      else -> {
        cursor++
        continue@discovery
      }
    }
  }
  if (lastCursor != cursor)
    split.add(substring(lastCursor until cursor))
  return split
}

/**
 * All reserved or meaningful words in SQL.
 */
val reservedSQLWords = listOf(
  "ADD",
  "ALTER",
  "CONSTRAINT",
  "COLUMN",
  "TABLE",
  "ALL",
  "AND",
  "ANY",
  "AS",
  "ASC",
  "BACKUP",
  "DATABASE",
  "BETWEEN",
  "CASE",
  "CHECK",
  "CREATE",
  "INDEX",
  "OR",
  "REPLACE",
  "VIEW",
  "PROCEDURE",
  "UNIQUE",
  "VIEW",
  "DEFAULT",
  "DELETE",
  "DESC",
  "DISTINCT",
  "DROP",
  "EXEC",
  "EXISTS",
  "FOREIGN",
  "KEY",
  "FROM",
  "FULL",
  "OUTER",
  "JOIN",
  "GROUP",
  "BY",
  "HAVING",
  "IN",
  "IF",
  "ELSE",
  "ELIF",
  "INNER",
  "INSERT",
  "INTO",
  "SELECT",
  "IS",
  "NULL",
  "NOT",
  "LEFT",
  "LIKE",
  "LIMIT",
  "ORDER",
  "OUTER",
  "PRIMARY",
  "RIGHT", "ROWNUM",
  "TOP",
  "SET",
  "TRUNCATE",
  "UNION",
  "UPDATE",
  "VALUES",
  "WHERE"
)

/**
 * The words that are reserved in a new table declaration.
 */
val reservedTableWords = listOf("CONSTRAINT", "UNIQUE", "FOREIGN", "PRIMARY", "INDEX")

private fun List<String>.getSQLIndexProperties(): List<String> {
  return slice((indexOf("(") + 1) until indexOf(")"))
    .split(",")
    .flatten()
    .map {
      it.removeSurrounding("`").removeSurrounding("\"").removeSurrounding("'")
    }
}

/**
 * Parse sql into a list of table declarations based on the [SQLTable] class.
 *
 * @param sql The sql to parse.
 */
fun parseSQLToTables(sql: String): List<SQLTable> {
  val tables = mutableListOf<SQLTable>()

  val escapingChars = "`\"'"
  val sqlParts = sql.trim().parseSQLTokens(escapingChars, ";()\n,", true)
  val statements = sqlParts.split(";").map { list ->
    list
      .dropWhile { it.trim().isEmpty() }
      .dropLastWhile { it.trim().isEmpty() }
  }
  statements@ for (statement in statements) {
    if (statement[0].startsWith("--"))
      continue@statements
    when (statement[0].toUpperCase()) {
      "CREATE" -> {
        when (statement[1].toUpperCase()) {
          "TABLE" -> {
            val offset: Int
            val name: String
            if (statement[3] == "(") {
              offset = 0
              name = statement[2].removeSurrounding("`").removeSurrounding("\"").removeSurrounding("'")
              check(tables.none { it.name == name }) { "Cannot redefine table $name" }
            } else {
              offset = 3
              name = statement[5].removeSurrounding("`").removeSurrounding("\"").removeSurrounding("'")
              if (tables.any { it.name == name })
                continue@statements
            }
            check(statement[offset + 2].toUpperCase() !in reservedSQLWords) {
              "Cannot have a table named as a keyword"
            }
            var level = 0
            val lines = statement.slice((4 + offset) until statement.lastIndex).filter(String::isNotBlank).split {
              when (it) {
                "," -> level == 0
                "(" -> {
                  level++
                  false
                }
                ")" -> {
                  level--
                  false
                }
                else -> false
              }
            }
            val properties = mutableListOf<SQLTableProperty>()
            val indices = mutableListOf<SQLIndex>()
            val uniques = mutableListOf<SQLIndex>()
            val foreignKeys = mutableListOf<SQLForeignKey>()
            var primaryKey: SQLIndex? = null
            lines.forEach { line ->
              if (line[0][0] in escapingChars || line[0].toUpperCase() !in reservedTableWords) {
                val propertyName = line[0].removeSurrounding("`").removeSurrounding("\"").removeSurrounding("'")
                val type = line[1]
                val typeParams: List<String> =
                  if (line.size > 2 && line[2] == "(") line.slice(3 until line.indexOf(")"))
                  else emptyList()
                val lineAsUpperCaseString = line.joinToString(" ").toUpperCase()
                val nullable = "NOT NULL" !in lineAsUpperCaseString
                val defaultIndex = line.indexOfFirst { it.toUpperCase() == "DEFAULT" }
                var default: String? = null
                if (defaultIndex != -1) {
                  check(line.lastIndex > defaultIndex) { "Table property has no value after DEFAULT" }
                  val value =
                    if (line.lastIndex > defaultIndex + 1 && line[defaultIndex + 2] == "(") {
                      var defaultLevel = 0
                      line.subList(defaultIndex + 2, line.lastIndex).takeWhile {
                        if (it == "(")
                          defaultLevel++
                        else if (it == ")")
                          defaultLevel--
                        defaultLevel == 0
                      }.joinToString("")
                    } else line[defaultIndex + 1]
                  default = if (value == "NULL") null else value
                }
                if ("PRIMARY KEY" in lineAsUpperCaseString) {
                  check(primaryKey == null) { "Cannot have more than one primary key" }
                  primaryKey = SQLIndex(
                    null,
                    listOf(propertyName)
                  )
                }
                val markedAutoIncrement =
                  if (default?.toLowerCase() == "rowid") {
                    default = null
                    true
                  } else "AUTO_INCREMENT" in lineAsUpperCaseString
                properties += SQLTableProperty(propertyName, type, typeParams, nullable, default, markedAutoIncrement)
              } else {
                val constraintName: String?
                val constraintOffset: Int
                if (line[0].toUpperCase() == "CONSTRAINT") {
                  constraintOffset = 2
                  constraintName = line[1].removeSurrounding("`").removeSurrounding("\"").removeSurrounding("'")
                } else {
                  constraintOffset = 0
                  constraintName = null
                }
                when (line[constraintOffset].toUpperCase()) {
                  "FOREIGN" -> {
                    val localProps = line.slice((line.indexOf("(") + 1) until line.indexOf(")"))
                      .split(",")
                      .flatten()
                      .map {
                        it.removeSurrounding("`").removeSurrounding("\"").removeSurrounding("'")
                      }
                    val reference = run {
                      val index = line.lastIndexOf("(")
                      val referenceName = line[index - 1].removeSurrounding("`").removeSurrounding("\"").removeSurrounding("'")
                      val refProps = line.slice((index + 1) until line.lastIndexOf(")"))
                        .map {
                          it.removeSurrounding("`").removeSurrounding("\"").removeSurrounding("'")
                        }
                      SQLTableReference(referenceName, refProps)
                    }
                    var wasOn = false
                    val onDeleteIndex = line.indexOfFirst {
                      if (it.toUpperCase() == "DELETE" && wasOn)
                        return@indexOfFirst true
                      wasOn = false
                      if (it.toUpperCase() == "ON")
                        wasOn = true
                      false
                    }
                    wasOn = false
                    val onChangeIndex = line.indexOfFirst {
                      if (it.toUpperCase() == "CHANGE" && wasOn)
                        return@indexOfFirst true
                      wasOn = false
                      if (it.toUpperCase() == "ON")
                        wasOn = true
                      false
                    }
                    val deleteAction = when {
                      onDeleteIndex == -1 -> null
                      line[onDeleteIndex + 1] == "NO" -> "NO ACTION"
                      line[onDeleteIndex + 1] == "SET" -> "SET ${line[onDeleteIndex + 2].toUpperCase()}"
                      else -> line[onDeleteIndex + 1]
                    }
                    val changeAction = when {
                      onChangeIndex == -1 -> null
                      line[onChangeIndex + 1] == "NO" -> "NO ACTION"
                      line[onChangeIndex + 1] == "SET" -> "SET ${line[onChangeIndex + 2].toUpperCase()}"
                      else -> line[onChangeIndex + 1]
                    }
                    foreignKeys += SQLForeignKey(
                      constraintName,
                      localProps,
                      reference,
                      ForeignKeyAction.fromString(deleteAction),
                      ForeignKeyAction.fromString(changeAction)
                    )
                  }
                  "PRIMARY" -> {
                    check(primaryKey == null) { "Cannot have more than one primary key" }
                    primaryKey = SQLIndex(constraintName, line.getSQLIndexProperties())
                  }
                  "UNIQUE" -> {
                    val props = line.getSQLIndexProperties()
                    uniques += SQLIndex(constraintName, props)
                  }
                  "INDEX" -> {
                    val props = line.getSQLIndexProperties()
                    indices += SQLIndex(constraintName, props)
                  }
                  else -> throw IllegalStateException("Either not implemented or path does not exist")
                }
              }
            }
            tables += SQLTable(name, properties, primaryKey, indices, uniques, foreignKeys)
          }
          "UNIQUE" -> when (statement[2].toUpperCase()) {
            "INDEX" -> {
              val indexName = statement[3].removeSurrounding("`").removeSurrounding("\"").removeSurrounding("'")
              val tableName = statement[5].removeSurrounding("`").removeSurrounding("\"").removeSurrounding("'")
              val props = statement.getSQLIndexProperties()
              val table = tables.find { it.name == tableName }
              checkNotNull(table) { "Cannot add a index to a nonexistent table" }
              table.addIndex(true, SQLIndex(indexName, props))
            }
            else -> throw IllegalStateException("Either not implemented or path does not exist")
          }
          "INDEX" -> {
            val indexName = statement[2].removeSurrounding("`").removeSurrounding("\"").removeSurrounding("'")
            val tableName = statement[4].removeSurrounding("`").removeSurrounding("\"").removeSurrounding("'")
            val props = statement.getSQLIndexProperties()
            val table = tables.find { it.name == tableName }
            checkNotNull(table) { "Cannot add a index to a nonexistent table" }
            table.addIndex(false, SQLIndex(indexName, props))
          }
          else -> throw IllegalStateException("Either not implemented or path does not exist")
        }
      }
      else -> throw IllegalStateException("Either not implemented or path does not exist")
    }
  }

  return tables.toList()
}
