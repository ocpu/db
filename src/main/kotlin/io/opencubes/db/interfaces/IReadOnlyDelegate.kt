package io.opencubes.db.interfaces

import java.util.function.Supplier
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * A basic read only property that does not use the thisRef
 * and kotlin property to get the value of the delegate.
 */
interface IReadOnlyDelegate<R, V> : Supplier<V>, ReadOnlyProperty<R, V> {
  /** The delegate getter */
  override fun getValue(thisRef: R, property: KProperty<*>): V = get()
}
