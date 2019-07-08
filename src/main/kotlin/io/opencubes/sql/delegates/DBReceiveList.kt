package io.opencubes.sql.delegates

import io.opencubes.sql.ActiveRecord
import io.opencubes.sql.DatabaseList
import io.opencubes.sql.Field
import io.opencubes.sql.select.Order
import io.opencubes.sql.toSnakeCase
import java.lang.reflect.InvocationTargetException
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1

open class DBReceiveList<R : ActiveRecord, T : ActiveRecord>(
  from: KProperty1<out ActiveRecord, DatabaseList<in T>>,
  mapper: KProperty1<in R, T>
) : ReadOnlyProperty<ActiveRecord, List<R>>, IOrdering<R, DBReceiveList<R, T>> {

  val from = Field(mapper)
  val by = Field(from)

  @Suppress("UNCHECKED_CAST")
  val select by lazy {
    val listDelegate = this.by.getDelegate() as IManyReference<T>

    val fromTable = this.from.instance
    val toTable = ActiveRecord.getShallowInstance(listDelegate.klass)
    val linkTable = ActiveRecord.getLinkTableName(listDelegate.table, this.by.property, this.by.instance, this.from.instance)

    val toName = listDelegate.key ?: "${
    toTable.javaClass.simpleName.toSnakeCase()
    }_${toTable.idField.name}"

    val fromName = listDelegate.referenceKey ?: "${
    if (linkTable != fromTable.table) linkTable
    else fromTable.javaClass.simpleName.toSnakeCase()
    }_${fromTable.idField.name}"

    val select = this.from.instance.database
      .select(*Field.nameArray(this.from.instance.fields) { "t2.$it" })
      .from("$linkTable t1")
      .join("${fromTable.table} t2", "t2.${this.from.name} = t1.$toName")
      .join("${this.by.table} t3", "t2.${this.from.name} = t3.${this.by.instance.idField.name}")
      .where("t1.$fromName = ?")

    select.compile()
  }

  override fun getValue(thisRef: ActiveRecord, property: KProperty<*>): List<R> =
    try {
      select.execute(thisRef.idField.getValue(thisRef)).fetchAllInto(from.klass())
    } catch (_: InvocationTargetException) {
      throw Exception("${thisRef.javaClass.simpleName} primary key property is not defined")
    }

  override val orders = mutableSetOf<Pair<KProperty1<R, *>, Order>>()
  private var limit: Int? = null

  override fun orderBy(property: KProperty1<R, *>, order: Order): DBReceiveList<R, T> {
    orders += property to order
    return this
  }

  fun limit(amount: Int): DBReceiveList<R, T> {
    limit = amount
    return this
  }
}