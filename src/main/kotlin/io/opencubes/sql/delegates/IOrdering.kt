package io.opencubes.sql.delegates

import io.opencubes.sql.ActiveRecord
import io.opencubes.sql.select.Order
import kotlin.reflect.KProperty1

/**
 * A grouping interface that defines a property that can order items.
 */
interface IOrdering<T : ActiveRecord, C : IOrdering<T, C>> {
  /**
   * The all ordering definitions.
   */
  val orders: MutableSet<Pair<KProperty1<T, *>, Order>>

  /**
   * Add a ordering to the [list][orders].
   */
  fun orderBy(property: KProperty1<T, *>, order: Order = Order.NONE): C
}
