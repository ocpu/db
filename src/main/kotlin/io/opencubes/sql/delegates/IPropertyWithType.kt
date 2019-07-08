package io.opencubes.sql.delegates

import io.opencubes.sql.ActiveRecord
import kotlin.properties.ReadWriteProperty

interface IPropertyWithType<T> : ReadWriteProperty<ActiveRecord, T> {
  val type: Type

  enum class Type { VALUE, REFERENCE }
}