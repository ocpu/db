package io.opencubes.sql.table

import io.opencubes.sql.delegates.DeleteAction

data class AbstractForeignKey(
  val name: String,
  val tableProperty: String,
  val foreignTable: String,
  val foreignProperty: String,
  val onDeleteAction: DeleteAction
)
