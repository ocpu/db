package io.opencubes.sql.delegates

import io.opencubes.sql.ActiveRecord
import kotlin.reflect.KClass

interface IManyReference<T : ActiveRecord> : ITableReference<T> {
  val table: String?
  val key: String?
  val referenceKey: String?
}
