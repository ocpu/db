package io.opencubes.sql

import kotlin.reflect.KProperty

interface ICreateSQL {
  fun getCreationSQL(instance: ActiveRecord, property: KProperty<*>): String
}
