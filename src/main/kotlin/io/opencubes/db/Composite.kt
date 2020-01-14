package io.opencubes.db

import io.opencubes.db.interfaces.IDatabaseList
import io.opencubes.db.interfaces.IReadOnlyDelegate
import io.opencubes.db.sql.select.FetchableResult
import io.opencubes.db.sql.select.SelectBuilder
import io.opencubes.db.sql.select.SelectItem
import io.opencubes.db.sql.select.from
import io.opencubes.db.values.ValueWrapper
import io.opencubes.db.values.ReferenceManyToMany
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.javaField

interface Composite {
  fun count(property: KProperty1<out Model, IDatabaseList<*>>) = ListCountCompositeField(property)

  operator fun <V> KProperty1<out Model, V>.provideDelegate(thisRef: Composite, prop: KProperty<*>) = PropertyCompositeField<V>(ModelField.construct(this@provideDelegate.javaField)!!)

  class ListCountCompositeField(property: KProperty1<out Model, IDatabaseList<*>>) : CompositeField<Int> {
    private val field = ModelField.construct(property.javaField)!!
    override val modelClass = field.modelClass
    private val list by lazy { field.delegate<ReferenceManyToMany<*, *>>(field.emptyModel).list }

    override val table: String?
      get() = list.sqlTable.name

    override fun item(tableName: String, alias: String): SelectItem {
      return SelectItem("COUNT($tableName.${list.primaryProperty.name})", null, alias, simple = false)
    }

    override fun contributeLinks(links: MutableList<Pair<String, MutableSet<CompositeLink>>>) {
      val link = links.find { it.first == list.sqlTable.name }
      val compositeLink = CompositeLink(list.primaryProperty.name, field.table, Model.obtainId(field.modelClass).name, CompositeLink.Type.LEFT)
      if (link == null) {
        links.add(list.sqlTable.name to mutableSetOf(compositeLink))
      } else {
        link.second.add(compositeLink)
      }
    }

    override fun contribute(builder: SelectBuilder, links: List<Pair<String, Set<CompositeLink>>>) {
      builder.groupBy(list.primaryProperty.name, "t" + links.indexOfFirst {
        it.first == list.sqlTable.name
      })
    }

    @Volatile
    private var count: Int = 0
    @Volatile
    private var initialized = false

    override fun get(): Int {
      check(initialized) { "Property is not initialized" }
      return count
    }

    override fun inject(value: Any?) {
      if (value !is Int) return
      count = value
      initialized = true
    }
  }

  @Suppress("UNCHECKED_CAST")
  class PropertyCompositeField<V>(private val field: ModelField) : CompositeField<V> {
    override val modelClass = field.modelClass

    override fun item(tableName: String, alias: String) = field.name from tableName `as` alias
    private val modelValue by lazy { field.value(field.emptyModel) as ValueWrapper<V> }

    private val value by lazy {
      ValueWrapper(modelValue.type, modelValue.nullable, modelValue.default).apply {
        preferences(modelValue.preferences)
      }
    }

    override fun get(): V = value.get()

    override fun inject(value: Any?) = this.value.inject(value)
  }

  data class CompositeLink(val tableColumn: String, val otherTable: String, val otherTableColumn: String, val type: Type = Type.INNER) {
    enum class Type {
      INNER, LEFT
    }
  }

  interface CompositeField<T> : IReadOnlyDelegate<Composite, T>, IInjectable {
    val modelClass: Class<out Model>?
    val table: String? get() = null
    fun item(tableName: String, alias: String): SelectItem
    fun contribute(builder: SelectBuilder, links: List<Pair<String, Set<CompositeLink>>>) = Unit
    fun contributeLinks(links: MutableList<Pair<String, MutableSet<CompositeLink>>>) = Unit
  }

  companion object {
    private fun fetchCompositeRows(compositeClass: Class<out Composite>, filters: Array<out Pair<KProperty1<out Composite, *>, *>>, add: SelectBuilder.() -> Unit = {}): FetchableResult {
      // TODO Cache results
      val composite = compositeClass.newInstance()
      // Get all composite fields for query
      val fields = composite::class.java.declaredFields
        .filterNotNull()
        .filter { CompositeField::class.java.isAssignableFrom(it.type) }
        .map { Pair(it.accessible { get(composite) as CompositeField<*> }, it) }

      // Figure out table links
      val modelClasses = fields.mapNotNull { (it, _) -> it.modelClass }.toSet().toList()
      val modelNames = modelClasses.map { Model.obtainTableName(it) }

      val links: MutableList<Pair<String, MutableSet<CompositeLink>>> = modelClasses
        .map {
          val table = Model.obtainSQLTable(it)[0]
          return@map Pair(
            table.name,
            table.foreignKeys
              .filter { key -> key.reference.table != table.name && key.reference.table in modelNames }
              .map { key -> CompositeLink(key.properties[0], key.reference.table, key.reference.properties[0]) }
              .toMutableSet()
          )
        }
        .sortedBy { it.second.size }
        .toMutableList()

      fields.forEach { (field, _) -> field.contributeLinks(links) }
      // TODO Look into throwing on incomplete links (a node that does not have any links to the others)
      val fieldToSelectItem = fields.map { (field, jField) ->
        val tableName = field.table ?: Model.obtainTableName(field.modelClass!!)
        val table =
          if (field.modelClass != null) "t" + links.indexOfFirst { it.first == tableName }
          else ""
        jField to field.item(table, ModelField.getName(jField))
      }.toMap()

      // Start building
      val select = Model
        .obtainEmpty(modelClasses[0]) // TODO Select primary table / model driver better?
        .driver
        .select(fieldToSelectItem.values)
        .from(links[0].first, "t0")

      fields.forEach { (field, _) -> field.contribute(select, links) }

      // Build in links
      links.slice(1..links.lastIndex).forEachIndexed { index, (table, compositeLinks) ->
        for (link in compositeLinks) {
          val item = SelectItem(
            link.otherTableColumn,
            "t" + links.indexOfFirst { it.first == link.otherTable },
            null
          )
          val alias = "t${index + 1}"
          when (link.type) {
            CompositeLink.Type.INNER -> select.join(table, link.tableColumn, item, alias)
            CompositeLink.Type.LEFT -> select.leftJoin(table, link.tableColumn, item, alias)
          }
        }
      }

      val values = mutableListOf<Any?>()

      // Apply filters
      for ((field, value) in filters) {
        select.andWhere(fieldToSelectItem[field.javaField ?: continue] ?: continue)
        values += value
      }

      add(select)

      return select.execute(values)
    }

    @JvmStatic
    fun <C : Composite> find(compositeClass: Class<C>, vararg filter: Pair<KProperty1<C, *>, *>): C? =
      fetchCompositeRows(compositeClass, filter) { limit(1) }.fetchInto(compositeClass)

    @JvmStatic
    inline fun <reified C : Composite> find(vararg filter: Pair<KProperty1<C, *>, *>) = find(C::class.java, *filter)

    @JvmStatic
    fun <C : Composite> findAll(compositeClass: Class<C>, vararg filter: Pair<KProperty1<C, *>, *>): List<C> =
      fetchCompositeRows(compositeClass, filter).fetchAllInto(compositeClass)

    @JvmStatic
    inline fun <reified C : Composite> findAll(vararg filter: Pair<KProperty1<C, *>, *>) = findAll(C::class.java, *filter)
  }
}
