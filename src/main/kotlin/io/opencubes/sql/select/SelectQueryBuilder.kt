package io.opencubes.sql.select

import io.opencubes.sql.Database
import io.opencubes.sql.Database.Companion.escape
import io.opencubes.sql.Database.Companion.prepare

/**FIXME Look over and see if correct usages and stuff.
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

  private val groups = mutableSetOf<String>()

  private val orders = mutableSetOf<Pair<String, Order>>()

  private var limit: Int? = null

  private var offset: Int? = null

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
    val copy = columns.slice(columns.indices).toMutableList()
    if (wheres.isEmpty()) {
      if (copy.size == 1) {
        wheres += WhereType.NONE to prepare(copy[0])
        return this
      } else {
        val value = copy.removeAt(0)
        wheres += WhereType.NONE to prepare(value)
      }
    }
    for (column in copy)
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

  fun groupBy(vararg columns: String): SelectQueryBuilder {
    groups += columns
    return this
  }

  @JvmOverloads
  fun orderBy(column: String, order: Order = Order.NONE): SelectQueryBuilder {
    orders += column to order
    return this
  }

  fun limit(amount: Int): SelectQueryBuilder {
    this.limit = amount
    return this
  }

  fun offset(amount: Int): SelectQueryBuilder {
    this.offset = amount
    return this
  }

  /**
   * This will build the query and return it.
   */
  fun build(): String {
    if (!this::fromTable.isInitialized)
      throw Exception()

    return buildString {
      append("SELECT ")
      for ((i, column) in columns.withIndex()) {
        append(column)

        if (i != columns.size - 1) {
          append(", ")
        }
      }
      append(" FROM $fromTable ")
      for ((type, table, condition) in joins) {
        append(when (type) {
          // TODO More join types
          JoinType.INNER -> "INNER JOIN"
          JoinType.LEFT -> "LEFT JOIN"
          JoinType.RIGHT -> if (db.isSQLite) "JOIN" else "RIGHT JOIN"
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

      if (orders.isNotEmpty()) {
        append("ORDER BY ")
        append(orders.joinToString(", ", postfix = " ") { (column, order) ->
          val sOrder = when (order) {
            Order.ASCENDING -> " ASC"
            Order.DESCENDING -> " DESC"
            else -> ""
          }
          "$column$sOrder"
        })
      }

      if (groups.isNotEmpty()) {
        append("GROUP BY ")
        append(orders.joinToString(", ", postfix = " "))
      }

      limit?.let {
        append("LIMIT $it ")
      }

      offset?.let {
        append("OFFSET $it ")
      }
    }
  }

  fun compile() = CompiledSelectQuery(db, build())

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
