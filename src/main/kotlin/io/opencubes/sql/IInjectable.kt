package io.opencubes.sql

/**
 * Represents a object that has a value and want to handle
 * any object that is not of the type it represents.
 */
interface IInjectable {
  /**
   * Inject the [value] into this object.
   */
  fun inject(value: Any?)
}
