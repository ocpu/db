package io.opencubes.db.interfaces

import java.util.function.Supplier

/**
 * Have a basic way to represent something that can be set and retrieved.
 */
interface ISetAndSupply<V> : Supplier<V> {
  /**
   * Set the value.
   */
  fun set(value: V)
}
