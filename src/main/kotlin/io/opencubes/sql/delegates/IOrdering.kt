package io.opencubes.sql.delegates

import io.opencubes.sql.ActiveRecord
import io.opencubes.sql.select.Order
import kotlin.reflect.KProperty1

interface IOrdering<T : ActiveRecord, C : Any> {
  val orders: MutableSet<Pair<KProperty1<T, *>, Order>>

  fun orderBy(property: KProperty1<T, *>, order: Order = Order.NONE): C
}