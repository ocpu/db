package io.opencubes.db.sql.select

import io.opencubes.db.sql.ISQLModelDriver

class SelectBuilder(private val driver: ISQLModelDriver, val items: Collection<SelectItem>) {
  private var from: Pair<String, String>? = null
  fun from(table: String, alias: String = table): SelectBuilder {
    from = table to alias
    return this
  }

  private val joins = mutableListOf<Join>()
  fun join(table: String, tableColumn: String, other: SelectItem, alias: String = table, conditions: (JoinConditionContext.() -> Unit)? = null): SelectBuilder {
    val ctx = JoinConditionContext()
    conditions?.invoke(ctx)
    ctx.conditions.add(0, JoinCondition(tableColumn, other))
    joins += InnerJoin(table, alias, ctx.conditions)
    return this
  }

  fun leftJoin(table: String, tableColumn: String, other: SelectItem, alias: String = table, conditions: (JoinConditionContext.() -> Unit)? = null): SelectBuilder {
    val ctx = JoinConditionContext()
    conditions?.invoke(ctx)
    ctx.conditions.add(0, JoinCondition(tableColumn, other))
    joins += LeftJoin(table, alias, ctx.conditions)
    return this
  }

  fun join(join: Join): SelectBuilder {
    joins += join
    return this
  }

  private val conditions = mutableListOf<WhereCondition>()
  @JvmOverloads
  fun where(item: SelectItem, value: Any? = SelectPlaceholder): SelectBuilder {
    check(conditions.isEmpty())
    conditions += Condition(item, value)
    return this
  }
  fun where(column: String, table: String? = null, `as`: String? = null, value: Any? = SelectPlaceholder) =
    where(SelectItem(column, table, `as`), value)
  fun where(pair: Pair<SelectItem, Any?>) = where(pair.first, pair.second)

  fun where(items: List<Pair<SelectItem, Any?>>): SelectBuilder {
    check(conditions.isEmpty())
    for ((index, item) in items.withIndex())
      if (index == 0) where(item)
      else andWhere(item)
    return this
  }

  @JvmOverloads
  fun andWhere(item: SelectItem, value: Any? = SelectPlaceholder): SelectBuilder {
    if (conditions.isNotEmpty())
      conditions += WhereAndCondition
    conditions += Condition(item, value)
    return this
  }
  fun andWhere(column: String, table: String? = null, `as`: String? = null, value: Any? = SelectPlaceholder) =
    andWhere(SelectItem(column, table, `as`), value)
  fun andWhere(pair: Pair<SelectItem, Any?>) = andWhere(pair.first, pair.second)

  @JvmOverloads
  fun orWhere(item: SelectItem, value: Any? = SelectPlaceholder): SelectBuilder {
    if (conditions.isNotEmpty())
      conditions += WhereOrCondition
    conditions += Condition(item, value)
    return this
  }
  fun orWhere(column: String, table: String? = null, `as`: String? = null, value: Any? = SelectPlaceholder) =
    orWhere(SelectItem(column, table, `as`), value)
  fun orWhere(pair: Pair<SelectItem, Any?>) = orWhere(pair.first, pair.second)

  private val orderings = mutableListOf<Pair<SelectItem, Order?>>()
  @JvmOverloads
  fun orderBy(item: SelectItem, order: Order? = null): SelectBuilder {
    orderings += item to order
    return this
  }
  fun orderBy(column: String, table: String? = null, `as`: String? = null, order: Order? = null): SelectBuilder {
    orderings += SelectItem(column, table, `as`) to order
    return this
  }

  private val groupings = mutableListOf<SelectItem>()
  fun groupBy(item: SelectItem): SelectBuilder {
    groupings += item
    return this
  }
  fun groupBy(column: String, table: String? = null, `as`: String? = null): SelectBuilder {
    groupings += SelectItem(column, table, `as`)
    return this
  }

  private var limit: Int? = null
  fun limit(amount: Int): SelectBuilder {
    limit = amount
    return this
  }

  private var offset: Int? = null
  fun offset(amount: Int): SelectBuilder {
    offset = amount
    return this
  }

  fun execute(params: List<Any?>) = driver.executeSQL(items, from, joins, conditions, orderings, groupings, limit, offset, params)
  fun execute(vararg params: Any?) = driver.executeSQL(items, from, joins, conditions, orderings, groupings, limit, offset, params.toList())

  override fun toString() = driver.toSQL(items, from, joins, conditions, orderings, groupings, limit, offset)
}

sealed class WhereCondition
object WhereAndCondition : WhereCondition()
object WhereOrCondition : WhereCondition()
data class Condition(val item: SelectItem, val value: Any?) : WhereCondition() {}

sealed class JoinConditionBase
object JoinAndCondition : JoinConditionBase()
object JoinOrCondition : JoinConditionBase()
data class JoinCondition(val column: String, val other: SelectItem) : JoinConditionBase()

class JoinConditionContext {
  internal val conditions = mutableListOf<JoinConditionBase>()

  fun and(column: String, other: SelectItem) {
    conditions += JoinAndCondition
    conditions += JoinCondition(column, other)
  }

  fun or(column: String, other: SelectItem) {
    conditions += JoinOrCondition
    conditions += JoinCondition(column, other)
  }
}

sealed class Join(val table: String, val alias: String, val conditions: List<JoinConditionBase>)
class InnerJoin(table: String, alias: String, list: List<JoinConditionBase>) : Join(table, alias, list)
class LeftJoin(table: String, alias: String, list: List<JoinConditionBase>) : Join(table, alias, list)

object SelectPlaceholder
