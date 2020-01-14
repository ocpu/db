package io.opencubes.db.values

import io.opencubes.db.Model
import io.opencubes.db.ModelField
import io.opencubes.db.interfaces.IReadOnlyDelegate
import io.opencubes.db.sql.select.asSelectItem
import java.util.*

class ReferenceOneToMany<M : Model>(val thisModel: Model, val otherField: ModelField) : IReadOnlyDelegate<Model, ReferenceOneToMany.DatabaseList<M>> {
  val list by lazy { DatabaseList<M>(thisModel, otherField) }

  override fun get(): DatabaseList<M> = list

  class DatabaseList<T : Model>(val fromModel: Model, val refField: ModelField, val cache: MutableList<T> = LinkedList()) : List<T> by cache {
    private val selectAll =
      refField.emptyModel.driver.select(Model.obtainFields(refField.modelClass).map { it.name.asSelectItem() })
        .from(refField.table)
        .where("`${refField.name}` = ?")

    init { refresh() }

    fun refresh() {
      val id = Model.obtainId(fromModel::class.java)
      val res = selectAll.execute(id.getActual(fromModel))
      cache.clear()
      @Suppress("UNCHECKED_CAST")
      cache.addAll(res.fetchAllInto(refField.modelClass as Class<T>))
    }

    override fun toString() = cache.toString()
  }
}
