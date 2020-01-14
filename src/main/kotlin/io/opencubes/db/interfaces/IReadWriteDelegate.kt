package io.opencubes.db.interfaces

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * A basic read write property that does not use the thisRef
 * and kotlin property to get the value of the delegate.
 */
interface IReadWriteDelegate<R, V> : ISetAndSupply<V>, IReadOnlyDelegate<R, V>, ReadWriteProperty<R, V> {
  /** The delegate getter */
  override fun getValue(thisRef: R, property: KProperty<*>): V = get()
  /** The delegate setter */
  override fun setValue(thisRef: R, property: KProperty<*>, value: V) = set(value)
}
