package io.opencubes.sql

import java.sql.SQLException
import java.util.*
import kotlin.properties.ReadWriteProperty

class DatabaseList<T : ActiveRecord>(private val database: Database,
                                     private val fromTable: ActiveRecord,
                                     private val toTable: T,
                                     private val listField: Field,
                                     private val customTableName: String?,
                                     private val customFromPropertyName: String?,
                                     private val customToPropertyName: String?) : MutableList<T> {
  private val cache = LinkedList<T>()
  private var created = false
  private var firstTimeAccess = true
  val linkTable by lazy { ActiveRecord.getLinkTableName(customTableName, listField.property, fromTable, toTable) }
  val toName by lazy {
    customToPropertyName ?: "${
    if (toTable.table == fromTable.table) linkTable
    else toTable.javaClass.simpleName.toSnakeCase()
    }_${toTable.idField.name}"
  }
  val properties by lazy {
    listOf(

      Triple(
        customFromPropertyName ?: "${
        fromTable.javaClass.simpleName.toSnakeCase()
        }_${fromTable.idField.name}",
        fromTable.idField,
        fromTable
      ),

      Triple(
        toName,
        toTable.idField,
        toTable
      )

    ).sortedBy(Triple<String, Field, ActiveRecord>::first)
  }
  val columns by lazy { properties.map(Triple<String, Field, ActiveRecord>::first).toTypedArray() }
  val tableSQL by lazy {
    val fpn = properties[0].first
    val spn = properties[1].first

    "CREATE TABLE IF NOT EXISTS $linkTable (\n" +
      "  $fpn ${properties[0].second.getSQLType(database)} NOT NULL,\n" +
      "  $spn ${properties[1].second.getSQLType(database)} NOT NULL,\n\n" +
      (if (database.isSQLite) "" else (
        "  INDEX ${linkTable}_ix_$fpn ($fpn),\n" +
        "  INDEX ${linkTable}_ix_$spn ($spn),\n\n"
        )) +
      "  CONSTRAINT ${linkTable}_fk_$fpn FOREIGN KEY ($fpn) REFERENCES ${properties[1].third.table} (${properties[1].second.name}),\n" +
      "  CONSTRAINT ${linkTable}_fk_$spn FOREIGN KEY ($spn) REFERENCES ${properties[0].third.table} (${properties[0].second.name})\n" +
      "); -- Link table"
  }
  val select by lazy {
    val other = if (properties[0].first == toName) properties[1].first else properties[0].first

    database
      .select(*Field.nameArray(toTable.fields) { "t1.$it" })
      .from("${toTable.table} t1")
      .join("$linkTable t2", "t1.${toTable.idField.name} = t2.$toName")
      .where("t2.$other = ?")
  }
  val generalTable = Compound.GeneralTable(
    name = linkTable,
    properties = listOf(
      Compound.GeneralTableProperty(properties[0].first, properties[0].second.getSQLType(database)),
      Compound.GeneralTableProperty(properties[1].first, properties[1].second.getSQLType(database))
    ),
    links = listOf()
  )

  /**
   * Refreshes the element cache to what it currently is in the database.
   * Does not run if the primary property has no value.
   */
  fun refresh() {
    firstTimeAccess = false
    if (!created) {
      created = true
      database.execute(tableSQL)
    }

    if (!fromTable.idField.hasValue(fromTable))
      return
    cache.clear()
    cache.addAll(
      select
        .execute(fromTable.idField.getValue(fromTable))
        .fetchAllInto(toTable::class)
    )
  }

  override val size: Int get() = cache.size

  override fun contains(element: T): Boolean {
    if (firstTimeAccess) refresh()
    return cache.contains(element)
  }

  override fun containsAll(elements: Collection<T>): Boolean {
    if (firstTimeAccess) refresh()
    return cache.containsAll(elements)
  }

  override fun isEmpty(): Boolean {
    if (firstTimeAccess) refresh()
    return cache.isEmpty()
  }

  override fun add(element: T): Boolean {
    if (firstTimeAccess) refresh()
    return try {
      database.insertInto(linkTable, columns, properties.map { (_, f, i) -> f.getValue(i) }.toTypedArray())
      cache.add(element)
    } catch (_: SQLException) {
      false
    }
  }

  override fun addAll(elements: Collection<T>): Boolean {
    if (firstTimeAccess) refresh()
    return try {
      database.transaction {
        for (element in elements)
          database.insertInto(linkTable, columns, properties.map { (_, f, i) -> f.getValue(i) }.toTypedArray())
      }
      cache.addAll(elements)
    } catch (_: SQLException) {
      false
    }
  }

  override fun clear() {
    val other = if (properties[0].first == toName) properties[1] else properties[0]
    database.deleteFrom(linkTable, listOf(other.first), listOf(other.second.getValue(other.third)))
    cache.clear()
  }

  override fun iterator() = cache.iterator()

  override fun remove(element: T): Boolean {
    if (firstTimeAccess) refresh()
    return try {
      database.deleteFrom(linkTable, columns, properties.map { (_, f, i) -> f.getValue(i) }.toTypedArray())
      cache.remove(element)
    } catch (_: SQLException) {
      false
    }
  }

  override fun removeAll(elements: Collection<T>): Boolean {
    if (firstTimeAccess) refresh()
    return try {
      database.transaction {
        for (element in elements)
          database.deleteFrom(linkTable, columns, properties.map { (_, f, i) -> f.getValue(i) }.toTypedArray())
      }
      cache.removeAll(elements)
    } catch (_: SQLException) {
      false
    }
  }

  override fun retainAll(elements: Collection<T>) = removeAll(cache.filter { it !in elements })

  override fun add(index: Int, element: T) {
    if (firstTimeAccess) refresh()
    add(element)
    cache.add(index, element)
    cache.removeLast()
  }

  override fun addAll(index: Int, elements: Collection<T>): Boolean = addAll(elements)

  override fun get(index: Int): T {
    if (firstTimeAccess) refresh()
    return cache[index]
  }

  override fun indexOf(element: T): Int {
    if (firstTimeAccess) refresh()
    return cache.indexOf(element)
  }

  override fun lastIndexOf(element: T): Int {
    if (firstTimeAccess) refresh()
    return cache.lastIndexOf(element)
  }

  override fun listIterator(): MutableListIterator<T> {
    if (firstTimeAccess) refresh()
    return cache.listIterator()
  }

  override fun listIterator(index: Int): MutableListIterator<T> {
    if (firstTimeAccess) refresh()
    return cache.listIterator(index)
  }

  override fun removeAt(index: Int): T = cache.removeAt(index).also(ActiveRecord::delete)

  override fun set(index: Int, element: T): T {
    if (firstTimeAccess) refresh()
    val item = cache.set(index, element)
    item.database.transaction {
      item.delete()
      val id = item.idField.getValue(item)
      val delegate = element.idField.getDelegate(element) as ReadWriteProperty<*, *>
      ReadWriteProperty<*, *>::setValue.call(delegate, element, element.idField, id)
      element.save()
    }
    return item
  }

  override fun subList(fromIndex: Int, toIndex: Int): MutableList<T> = throw Exception("Not supported")

  override fun toString() = cache.toString()
}