package io.opencubes.sql.table

import io.opencubes.sql.delegates.DeleteAction

data class AbstractTable(
  val name: String,
  val properties: List<AbstractTableProperty>,
  val primaryKey: AbstractTableIndex?,
  val indices: List<AbstractTableIndex>,
  val uniques: List<AbstractTableIndex>,
  val foreignKeys: List<AbstractForeignKey>
) {
  companion object {
    private val escapees = Regex("^[\"'`]|[\"'`]$")

    private fun splitParenList(str: String) = Regex("""\(([^)]+)\)""").find(str)?.destructured?.component1()?.split(Regex(",\\s*"))
      ?: emptyList()

    fun fromSQLSource(source: String): AbstractTable {
      val split = source.split(Regex("[ \\n]+"), limit = 7)
      val lines = run {
        val part = source.split(",", "\n")
          .map(String::trim)
          .filter(String::isNotBlank)
          .toMutableList()
        part.removeAt(0)
        part.removeAt(part.lastIndex)
        val res = mutableListOf<String>()
        var inlineLevel = 0
        for (line in part) {
          val openBraces = line.count { it == '(' }
          val closeBraces = line.count { it == ')' }
          when {
            openBraces < closeBraces -> {
              res[res.lastIndex] += ",$line"
              inlineLevel -= closeBraces - openBraces
            }
            openBraces > closeBraces -> {
              res.add(line)
              inlineLevel += openBraces - closeBraces
            }
            inlineLevel != 0 -> res[res.lastIndex] += "$line,"
            else -> res.add(line)
          }
        }
        res
      }
      val name = (if (split[2].toUpperCase() == "IF") split[5] else split[2]).replace(escapees, "")
      val properties = mutableListOf<AbstractTableProperty>()
      var primaryKey: AbstractTableIndex? = null
      val indices = mutableListOf<AbstractTableIndex>()
      val uniques = mutableListOf<AbstractTableIndex>()
      val foreignKeys = mutableListOf<AbstractForeignKey>()
      for (line in lines) {
        val (propertyName, rest) = line.split(Regex(" +"), limit = 2)
        when (propertyName.toUpperCase()) {
          "FOREIGN" -> {
            val (thisProperty, foreignTable, foreignProperty) =
              Regex("""KEY\s+\(([^)]+)\)\s+REFERENCES\s+([^ (]+)\s*\(([^)]+)\)""", RegexOption.IGNORE_CASE)
                .find(rest)!!.destructured
            val deleteAction = if ("ON DELETE" in rest) {
              val i = rest.indexOf("ON DELETE")
              val end = rest.indexOf(',', i)
              DeleteAction.fromString(rest.substring(i + 10, (if (end != -1) end else rest.lastIndex) + 1))
            } else DeleteAction.NO_ACTION
            foreignKeys.add(AbstractForeignKey("<unknown>", thisProperty, foreignTable, foreignProperty, deleteAction))
          }
          "KEY", "INDEX" -> {
            val (xName, list) = Regex("""(?:KEY|INDEX)\s+([^ (]+)?\s*\(([^)]+)\)""").find(line)!!.destructured
            indices.add(AbstractTableIndex(
              if (xName.isBlank()) "<unknown>" else xName,
              list.split(", ", ",")
            ))
          }
          "PRIMARY" -> primaryKey = AbstractTableIndex("<unknown>", splitParenList(rest))
          "UNIQUE" -> {
            val (xName, list) = Regex("""UNIQUE\s+(?:KEY\s+)?([^ (]+)?\s*\(([^)]+)\)""").find(line)!!.destructured
            uniques.add(AbstractTableIndex(if (xName.isBlank()) "<unknown>" else xName, list.split(", ", ",")))
          }
          "CONSTRAINT" -> {
            val (constraintName, constraintType, restOfConstraint) = rest.split(Regex(" +"), limit = 3)
            when (constraintType.toUpperCase()) {
              "PRIMARY" -> primaryKey = AbstractTableIndex(constraintName, splitParenList(restOfConstraint))
              "UNIQUE" -> uniques.add(AbstractTableIndex(constraintName, splitParenList(restOfConstraint)))
              "FOREIGN" -> {
                val (thisProperty, foreignTable, foreignProperty) =
                  Regex("""KEY\s+\(([^)]+)\)\s+REFERENCES\s+([^ (]+)\s*\(([^)]+)\)""", RegexOption.IGNORE_CASE)
                    .find(rest)!!.destructured
                val deleteAction = if ("ON DELETE" in rest) {
                  val i = rest.indexOf("ON DELETE")
                  val end = rest.indexOf(',', i)
                  DeleteAction.fromString(rest.substring(i + 10, (if (end != -1) end else rest.lastIndex) + 1))
                } else DeleteAction.NO_ACTION
                foreignKeys.add(AbstractForeignKey(constraintName, thisProperty, foreignTable, foreignProperty, deleteAction))
              }
            }
          }
          else -> {
            val type =
              try {
                if (')' in rest) rest.substring(0..rest.indexOf(')'))
                else rest.substring(0 until rest.indexOf(' '))
              } catch (_: StringIndexOutOfBoundsException) {
                if (rest.endsWith(",")) rest.dropLast(1)
                else rest
              }
            properties += AbstractTableProperty(
              propertyName,
              when {
                type == "int(11)" -> "INTEGER"
                ')' in type ->
                  type.substring(0 until type.indexOf('(')).toUpperCase() +
                    type.substring(type.indexOf('('))
                else -> type.toUpperCase()
              },
              rest.indexOf("NOT NULL", type.length) == -1
            )
            if (rest.indexOf("PRIMARY KEY", type.length) != -1)
              primaryKey = AbstractTableIndex("<unknown>", listOf(propertyName))
            // TODO Handle auto increment
          }
        }
      }
      return AbstractTable(name, properties, primaryKey, indices, uniques, foreignKeys)
    }
  }
}
