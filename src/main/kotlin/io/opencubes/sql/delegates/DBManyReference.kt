package io.opencubes.sql.delegates

import io.opencubes.sql.ActiveRecord
import io.opencubes.sql.ActiveRecord.Companion.getShallowInstance
import io.opencubes.sql.DatabaseList
import io.opencubes.sql.Field
import io.opencubes.sql.ICreateSQL
import io.opencubes.sql.select.Order
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1

open class DBManyReference<T : ActiveRecord>(
  override val table: String?,
  override val key: String?,
  override val referenceKey: String?,
  override val kClass: KClass<T>
) : ReadOnlyProperty<ActiveRecord, DatabaseList<T>>, ICreateSQL, IManyReference<T>, IOrdering<T, DBManyReference<T>> {
  lateinit var list: DatabaseList<T>

  override fun getValue(thisRef: ActiveRecord, property: KProperty<*>): DatabaseList<T> {
    if (!::list.isInitialized)
      list = DatabaseList(thisRef.database, thisRef, getShallowInstance(kClass), Field(property), table, key, referenceKey)
    return list
  }

  override val orders: MutableSet<Pair<KProperty1<T, *>, Order>> = mutableSetOf()
  private var limit: Int? = null

  override fun orderBy(property: KProperty1<T, *>, order: Order): DBManyReference<T> {
    orders += property to order
    return this
  }

  fun limit(amount: Int): DBManyReference<T> {
    limit = amount
    return this
  }

  override fun getCreationSQL(instance: ActiveRecord, property: KProperty<*>): String =
    getValue(instance, property).tableSQL
}
