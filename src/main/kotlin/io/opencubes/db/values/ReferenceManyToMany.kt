package io.opencubes.db.values

import io.opencubes.db.*
import io.opencubes.db.interfaces.IDatabaseList
import io.opencubes.db.interfaces.IReadOnlyDelegate
import io.opencubes.db.sql.select.from
import io.opencubes.db.sql.table.*
import java.lang.reflect.Field
import java.sql.SQLException
import java.util.*

class ReferenceManyToMany<T : Model, M : Model>(val thisModel: T, val otherModelClass: Class<M>, val customTableName: () -> String?, val customFromPropertyName: () -> String?, val customToPropertyName: () -> String?) : IReadOnlyDelegate<T, IDatabaseList<M>> {
  @Suppress("UNCHECKED_CAST")
  val list: IDatabaseList<M> by lazy(LazyThreadSafetyMode.NONE) {
    val thisField = thisModel::class.java.declaredFields.firstOrNull {
      it.accessible {
        val ref = it.get(thisModel)
        ref == this@ReferenceManyToMany
      }
    }
    checkNotNull(thisField)

    DatabaseList(thisModel, Model.obtainEmpty(otherModelClass) as M, thisField, customTableName(), customFromPropertyName(), customToPropertyName())
  }

  override fun get(): IDatabaseList<M> = list

  class DatabaseList<T : Model>(private val fromTable: Model,
                                private val toTable: T,
                                private val listField: Field,
                                private val customTableName: String?,
                                private val customFromPropertyName: String?,
                                private val customToPropertyName: String?) : IDatabaseList<T> {
    private val cache = LinkedList<T>()
    private var created = false
    override val liveMode = false
    private var firstTimeAccess = true
    /**
     * The name of the link table the links are stored.
     */
    val table by lazy { getLinkTableName(customTableName, ModelField(listField), fromTable, toTable) }
    private val toTableName by lazy {
      val toTableId = Model.obtainId(toTable::class.java)

      customToPropertyName ?: "${
      if (toTable.table == fromTable.table) table
      else toTable.javaClass.simpleName.toSnakeCase()
      }_${toTableId.name}"
    }
    override val sqlTable by lazy {
      var properties = mutableListOf<SQLTableProperty>()

      val fromTableId = Model.obtainId(fromTable::class.java)
      val fromTableIdValue = fromTableId.value(Model.obtainEmpty(fromTable::class.java))
      val (fromTableType, fromTableTypeParams) = fromTable.driver.getSQLTypeFromClass(fromTableIdValue.type, fromTableIdValue.preferences)

      properties.add(SQLTableProperty(
        customFromPropertyName ?: "${fromTable.javaClass.simpleName.toSnakeCase()}_${fromTableId.name}",
        fromTableType, fromTableTypeParams, false, null, false
      ))

      val toTableId = Model.obtainId(toTable::class.java)
      val toTableIdValue = toTableId.value(Model.obtainEmpty(toTable::class.java))
      val (toTableType, toTableTypeParams) = fromTable.driver.getSQLTypeFromClass(toTableIdValue.type, toTableIdValue.preferences)

      properties.add(
        SQLTableProperty(toTableName, toTableType, toTableTypeParams, false, null, false)
      )

      properties = properties.sortedBy(SQLTableProperty::name).toMutableList()

      val adds = if (properties[0].name == toTableName)
        listOf(toTableId to toTable, fromTableId to fromTable)
      else
        listOf(fromTableId to fromTable, toTableId to toTable)

      SQLTable(
        table,
        properties,
        SQLIndex("${table}_pk", properties.map(SQLTableProperty::name)),
        properties.mapIndexed { _, prop ->
          SQLIndex("${table}_ix_${prop.name}", listOf(prop.name))
        },
        listOf(),
        properties.mapIndexed { i, prop ->
          SQLForeignKey(
            "${table}_fk_${prop.name}",
            listOf(prop.name),
            SQLTableReference(adds[i].second.table, listOf(adds[i].first.name)),
            ForeignKeyAction.CASCADE,
            ForeignKeyAction.NO_ACTION
          )
        }
      )
    }
    val sortedIdProperties by lazy {
      if (sqlTable.properties[0].name == toTableName) listOf(
        Triple(sqlTable.properties[1], Model.obtainId(fromTable::class.java), fromTable),
        Triple(sqlTable.properties[0], Model.obtainId(toTable::class.java), toTable)
      )
      else listOf(
        Triple(sqlTable.properties[0], Model.obtainId(fromTable::class.java), fromTable),
        Triple(sqlTable.properties[1], Model.obtainId(toTable::class.java), toTable)
      )
    }

    override val primaryProperty by lazy { sortedIdProperties[0].first }
    /**
     * The columns the link table has.
     */
    val columns by lazy {
      sortedIdProperties.map(Triple<SQLTableProperty, ModelField, Model>::first).map(SQLTableProperty::name)
    }
    /**
     * The sql link table creation definition.
     */
    private val select by lazy {
      val other = sortedIdProperties[0].first.name

      fromTable.driver
        .select(Model.obtainFields(toTable::class.java).map { it.name from "t1" })
        .from(toTable.table, "t1")
        .join(
          table = table,
          alias = "t2",
          tableColumn = toTableName,
          other = Model.obtainId(toTable::class.java).name from "t1"
        )
        .where(other from "t2")
    }

    override fun refresh() {
      firstTimeAccess = false
      if (!created) {
        created = true
        fromTable.driver.execute(fromTable.driver.toSQL(sqlTable).replaceFirst("CREATE TABLE", "CREATE TABLE IF NOT EXISTS"))
      }

      if (!Model.obtainId(fromTable::class.java).hasValue(fromTable))
        return
      cache.clear()
      cache.addAll(select
        .execute(Model.obtainId(fromTable::class.java).get(fromTable))
        .fetchAllInto(toTable::class.java))
    }

    /** @see MutableList.size */
    override val size: Int get() = cache.size

    /** @see MutableList.contains */
    override fun contains(element: T): Boolean {
      if (firstTimeAccess) refresh()
      return cache.contains(element)
    }

    /** @see MutableList.containsAll */
    override fun containsAll(elements: Collection<T>): Boolean {
      if (firstTimeAccess) refresh()
      return cache.containsAll(elements)
    }

    /** @see MutableList.isEmpty */
    override fun isEmpty(): Boolean {
      if (firstTimeAccess) refresh()
      return cache.isEmpty()
    }

    /** @see MutableList.add */
    override fun add(element: T): Boolean {
      if (firstTimeAccess) refresh()
      return try {
        fromTable.driver.insert(table, columns, getValues(element))
        cache.add(element)
      } catch (_: SQLException) {
        false
      }
    }

    private fun getValues(element: T): List<Any?> {
      return listOf(
        sortedIdProperties[0].second.getActual(sortedIdProperties[0].third),
        sortedIdProperties[1].second.getActual(element)
      )
    }

    /** @see MutableList.addAll */
    override fun addAll(elements: Collection<T>): Boolean {
      if (firstTimeAccess) refresh()
      return try {
        fromTable.driver.transaction {
          for (element in elements)
            fromTable.driver.insert(table, columns, getValues(element))
        }
        cache.addAll(elements)
      } catch (_: SQLException) {
        false
      }
    }

    /** @see MutableList.clear */
    override fun clear() {
      val other = sortedIdProperties[0]
      fromTable.driver.deleteFrom(table, listOf(other.first.name), listOf(other.second.getActual(other.third)))
      cache.clear()
    }

    /** @see MutableList.iterator */
    override fun iterator() = cache.iterator()

    /** @see MutableList.remove */
    override fun remove(element: T): Boolean {
      if (firstTimeAccess) refresh()
      return try {
        fromTable.driver.deleteFrom(table, columns, getValues(element))
        cache.remove(element)
      } catch (_: SQLException) {
        false
      }
    }

    /** @see MutableList.removeAll */
    override fun removeAll(elements: Collection<T>): Boolean {
      if (firstTimeAccess) refresh()
      return try {
        fromTable.driver.transaction {
          for (element in elements)
            fromTable.driver.deleteFrom(table, columns, getValues(element))
        }
        cache.removeAll(elements)
      } catch (_: SQLException) {
        false
      }
    }

    /** @see MutableList.retainAll */
    override fun retainAll(elements: Collection<T>) = removeAll(cache.filter { it !in elements })

    /** @see MutableList.add */
    override fun add(index: Int, element: T) {
      if (firstTimeAccess) refresh()
      add(element)
      cache.add(index, element)
      cache.removeLast()
    }

    /** @see MutableList.addAll */
    override fun addAll(index: Int, elements: Collection<T>): Boolean = addAll(elements)

    /** @see MutableList.get */
    override fun get(index: Int): T {
      if (firstTimeAccess) refresh()
      return cache[index]
    }

    /** @see MutableList.indexOf */
    override fun indexOf(element: T): Int {
      if (firstTimeAccess) refresh()
      return cache.indexOf(element)
    }

    /** @see MutableList.lastIndexOf */
    override fun lastIndexOf(element: T): Int {
      if (firstTimeAccess) refresh()
      return cache.lastIndexOf(element)
    }

    /** @see MutableList.listIterator */
    override fun listIterator(): MutableListIterator<T> {
      if (firstTimeAccess) refresh()
      return cache.listIterator()
    }

    /** @see MutableList.listIterator */
    override fun listIterator(index: Int): MutableListIterator<T> {
      if (firstTimeAccess) refresh()
      return cache.listIterator(index)
    }

    /** @see MutableList.removeAt */
    override fun removeAt(index: Int): T = cache.removeAt(index).also(Model::delete)

    /** @see MutableList.set */
    override fun set(index: Int, element: T): T {
      if (firstTimeAccess) refresh()
      val item = cache.set(index, element)
      item.driver.transaction {
        item.delete()
        val id = Model.obtainId(item::class.java).get(item)
        Model.obtainId(element::class.java).set(element, id)
        element.save()
      }
      return item
    }

    /** @see MutableList.subList */
    override fun subList(fromIndex: Int, toIndex: Int): MutableList<T> = throw Exception("Not supported")

    /** @see MutableList.toString */
    override fun toString() = cache.toString()

    companion object {

      /**
       * Get a name of a link table based on a might be specified [table] name variable,
       * the [property] requiring the table, and the linking tables.
       *
       * @param table The possibly specified table name.
       * @param property The property that requires the table.
       * @param first The first table to connect.
       * @param second The second table to connect.
       */
      fun getLinkTableName(table: String?, property: ModelField, first: Model, second: Model): String {
        return when {
          table != null -> table
          first.table == property.name || second.table == property.name -> {
            val (f, l) = listOf(first, second).sortedBy(Model::table)

            "${f::class.java.simpleName.toSnakeCase()}_${l.table}"
          }
          else -> property.name.toSnakeCase()
        }
      }
    }
  }
}
