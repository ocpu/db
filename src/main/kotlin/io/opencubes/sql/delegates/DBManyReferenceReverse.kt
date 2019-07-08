package io.opencubes.sql.delegates

import io.opencubes.sql.ActiveRecord
import io.opencubes.sql.DatabaseList
import io.opencubes.sql.Field
import io.opencubes.sql.ICreateSQL
import io.opencubes.sql.select.Order
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1

open class DBManyReferenceReverse<F : ActiveRecord, T : ActiveRecord>(
  val reverseTarget: KProperty1<F, DatabaseList<T>>
) : ReadOnlyProperty<T, DatabaseList<F>>, ICreateSQL, IManyReference<F>, IOrdering<F, DBManyReferenceReverse<F, T>> {


  val reverseField by lazy { Field(reverseTarget) }
  @Suppress("UNCHECKED_CAST")
  val delegate by lazy { reverseField.getDelegate() as IManyReference<T> }
  override val table: String? by lazy { delegate.table }
  override val key: String? by lazy { delegate.referenceKey }
  override val referenceKey: String? by lazy { delegate.key }
  @Suppress("UNCHECKED_CAST")
  override val klass: KClass<F> by lazy { reverseField.klass<F>() }

  lateinit var list: DatabaseList<F>

  override fun getValue(thisRef: T, property: KProperty<*>): DatabaseList<F> {
    if (!::list.isInitialized)
      list = DatabaseList(thisRef.database, thisRef, ActiveRecord.getShallowInstance(klass), reverseField, table, key, referenceKey)
    return list
  }

  override val orders: MutableSet<Pair<KProperty1<F, *>, Order>> = mutableSetOf()
  private var limit: Int? = null

  override fun orderBy(property: KProperty1<F, *>, order: Order): DBManyReferenceReverse<F, T> {
    orders += property to order
    return this
  }

  fun limit(amount: Int): DBManyReferenceReverse<F, T> {
    limit = amount
    return this
  }

  @Suppress("UNCHECKED_CAST")
  override fun getCreationSQL(instance: ActiveRecord, property: KProperty<*>): String =
    getValue(instance as T, property).tableSQL
}