package io.opencubes.sql

import kotlin.reflect.KProperty

/**
 * A interface representing a class that can give a string that
 * represents the creation of the property in a table.
 */
interface ICreateSQL {
  /**
   * The creating SQL that represents the instance.
   */
  fun getCreationSQL(instance: ActiveRecord, property: KProperty<*>): String
}
