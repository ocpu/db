package io.opencubes.sql.delegates

import io.opencubes.sql.ActiveRecord
import io.opencubes.sql.Field
import io.opencubes.sql.select.Order
import java.io.InvalidClassException
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1

open class DBOneToManyReference<T : ActiveRecord>(referenceKey: KProperty1<T, Any?>, limit: Int?) : ReadOnlyProperty<ActiveRecord, List<T>>, IOrdering<T, DBOneToManyReference<T>>, ITableReference<T> {
  val field = Field(referenceKey)
  override val kClass: KClass<T> = field.kClass()
  @Suppress("UNCHECKED_CAST")
  val instance by lazy {
    field.instance as? T
      ?: throw InvalidClassException("Could not find a kotlin class for field?")
  }
  override val orders: MutableSet<Pair<KProperty1<T, *>, Order>> = mutableSetOf()
  val selector by lazy {
    val selector = instance.database
      .select(*Field.nameArray(instance.fields))
      .from(instance.table)
      .where("${Field.getName(referenceKey)} = ?")
    if (limit != null)
      selector.limit(limit)
    selector.compile()
  }

  override fun getValue(thisRef: ActiveRecord, property: KProperty<*>): List<T> =
    selector.execute(thisRef.idField.getValue(thisRef)).fetchAllInto(instance::class)

  override fun orderBy(property: KProperty1<T, *>, order: Order): DBOneToManyReference<T> {
    orders += property to order
    return this
  }
}
