package io.opencubes.sql.delegates

import io.opencubes.sql.ActiveRecord
import kotlin.reflect.KClass

interface ITableReference<T : ActiveRecord> {
  val klass: KClass<T>
}
