package io.opencubes.db

import io.opencubes.db.interfaces.IDatabaseList
import io.opencubes.db.loaders.sqlite.SQLiteModelDriver
import io.opencubes.db.sql.ISQLModelDriver
import io.opencubes.db.sql.ISerializableDefault
import io.opencubes.db.sql.select.SelectPlaceholder
import io.opencubes.db.sql.select.asSelectItem
import io.opencubes.db.sql.table.*
import io.opencubes.db.values.*
import kotlin.reflect.KClass
import kotlin.reflect.KProperty0
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaMethod

/**
 * The model class is the base of anything in this library. It is a active record based class with a
 * [save] and [delete] method. It makes use of all values and references declared on the model class
 * to give you
 */
interface Model {
  /**
   * The name of this model in the database.
   */
  val table: String
    get() = nameCache.computeIfAbsent(this::class.java) { Words.pluralize(this::class.java.simpleName.toSnakeCase()) }
  /**
   * The model driver that the model uses.
   */
  val driver: ISQLModelDriver
    get() = ISQLModelDriver.global!!


  /**
   * Create a many to many reference to many of the specified type of model.
   *
   * In the background it creates a table that connects one to the other. You can specify
   * what the link items are called with the
   *
   * @param otherModelClass The other model that will be linked with this model.
   * @param customTableName The name of the table that links the two tables together. If
   * null the name is determined by the property name that has this value and the name of
   * the two different or same models.
   * @param customFromPropertyName Here you can specify the name of the property
   * representing this model in the link table. If null it is determined by this model
   * name and the name of the property representing this model.
   * @param customToPropertyName Here you can specify the name of the property
   * representing the other model in the link table. If null it is determined by the other
   * model name and the name of the property representing the other model.
   */
  fun <M : Model> Model.referenceMany(
    otherModelClass: Class<M>,
    customTableName: String? = null,
    customFromPropertyName: String? = null,
    customToPropertyName: String? = null
  ) = ReferenceManyToMany(this, otherModelClass, { customTableName }, { customFromPropertyName }, { customToPropertyName })

  /**
   * Creates a one to many reference by the others property that references this model.
   *
   * @param by The property that references this model.
   */
  fun <T : Model> Model.referenceMany(by: KProperty1<T, Model>): ReferenceOneToMany<T> =
    ReferenceOneToMany(this, ModelField.construct(by.javaField)!!)

  /**
   * Create a many to many reference that inverts the parameters for a different many to
   * many reference.
   */
  @Suppress("UNCHECKED_CAST")
  fun <T : Model, M : Model> Model.referenceMany(reverse: KProperty1<T, IDatabaseList<M>>): ReferenceManyToMany<M, T> {
    val instance = this as? M
    checkNotNull(instance)
    val otherModelClass = reverse.getter.javaMethod!!.declaringClass as? Class<T>
    checkNotNull(otherModelClass)
    val delegate by lazy {
      reverse.javaField.accessibleOrNull {
        if (otherModelClass.isInstance(instance))
          get(instance as T) as ReferenceManyToMany<T, M>
        else
          get(obtainEmpty(otherModelClass) as T) as ReferenceManyToMany<T, M>
      }!!
    }
    return ReferenceManyToMany(
      instance,
      otherModelClass,
      { delegate.customTableName() ?: delegate.list.sqlTable.name },
      {
        delegate.customToPropertyName() ?: run {
          if (delegate.list.sqlTable.properties[0] == delegate.list.primaryProperty)
            delegate.list.sqlTable.properties[1].name
          else
            delegate.list.sqlTable.properties[0].name
        }
      },
      {
        delegate.customFromPropertyName() ?: run {
          if (delegate.list.sqlTable.properties[0] == delegate.list.primaryProperty)
            delegate.list.sqlTable.properties[0].name
          else
            delegate.list.sqlTable.properties[1].name
        }
      }
    )
  }

  /**
   * Have the ability to set the value of a delegated property even if the property is
   * marked `val`.
   */
  fun <T> set(property: KProperty1<out Model, T>, value: T) =
    ModelField.construct(property.javaField)?.set(this, value)

  /**
   * Have the ability to set the value of a delegated property even if the property is
   * marked `val`.
   */
  fun <T> KProperty1<out Model, T>.set(value: T) {
    this@Model.set(this, value)
  }

  /**
   * Have the ability to set the value of a delegated property even if the property is
   * marked `val`.
   */
  fun <T> set(property: KProperty0<T>, value: T) =
    ModelField.construct(property.javaField)?.set(this, value)

  /**
   * Have the ability to set the value of a delegated property even if the property is
   * marked `val`.
   */
  fun <T> KProperty0<T>.set(value: T) {
    this@Model.set(this, value)
  }


  /*--------------------------------------------------*/
  /*             Active Record functions              */
  /*--------------------------------------------------*/


  /**
   * Either update a existing row or create a new one.
   */
  fun save() {
    val unchangedIdFields = obtainIdFields(this::class.java)
      .filter { !it.hasChanged(this) }
      .filter { it.hasValue(this) }
    val valueFields = obtainFields(this::class.java).filter { it.hasValue(this) }.toMutableList()
    if (unchangedIdFields.isNotEmpty()) {
      // Update row
      valueFields.removeAll(unchangedIdFields)
      try {
        driver.update(
          table = table,
          columns = valueFields.map(ModelField::name),
          whereColumns = unchangedIdFields.map(ModelField::name),
          values = (valueFields + unchangedIdFields).map { it.getActual(this) }
        )
        return
      } catch (_: NoSuchElementException) {
        // There was no element to update so lets create it.
        // (Fallthrough to create stage)
      }
    }
    // Create row
    driver.insert(
      table,
      valueFields.map(ModelField::name),
      valueFields.map { it.getActual(this) }
    ).close()

    refresh()
  }

  /**
   * Delete this instance from the database.
   */
  fun delete() {
    val unchangedIdFields = obtainIdFields(this::class.java)
      .filter { !it.hasChanged(this) }
      .filter { it.hasValue(this) }

    driver.deleteFrom(
      table = table,
      columns = unchangedIdFields.map(ModelField::name),
      values = unchangedIdFields.map { it.getActual(this) }
    )
  }

  /**
   * Refresh the current values with the once from the database.
   */
  fun refresh() {
    val valueFields = obtainFields(this::class.java).filter { it.hasValue(this) }.toMutableList()

    driver
      .select(obtainFields(this::class.java).map(ModelField::name).map { it.asSelectItem() })
      .from(table)
      .where(valueFields.map { it.name.asSelectItem() to SelectPlaceholder })
      .execute(valueFields.map { it.getActual(this) })
      .fetchInto(this)
  }

  companion object {


    /*--------------------------------------------------*/
    /*           Table abstraction functions            */
    /*--------------------------------------------------*/


    private val fieldsCache = mutableMapOf<Class<out Model>, List<ModelField>>()
    /**
     * Get all fields for a table/model class.
     */
    @JvmStatic
    fun obtainFields(model: Class<out Model>): List<ModelField> = fieldsCache.computeIfAbsent(model) {
      val empty = obtainEmpty(model)
      model.declaredFields
        .filter { it.type == ValueWrapper::class.java }
        .mapNotNull(ModelField.Companion::construct)
        .sortedBy { it.name }
        .sortedBy { !Model::class.java.isAssignableFrom(it.value(empty).type) }
        .sortedBy { !it.value(empty).isPrimary }
    }

    private val idCache = mutableMapOf<Class<out Model>, Int>()
    /**
     * Get the id field for a specific table/model class.
     *
     * @throws IllegalStateException if the id field could not be determined.
     */
    @JvmStatic
    @Throws(IllegalStateException::class)
    fun obtainId(model: Class<out Model>): ModelField {
      return obtainFields(model)[idCache.computeIfAbsent(model, ::obtainIdField)]
    }

    private fun obtainIdField(model: Class<out Model>): Int {
      val fields = obtainFields(model)
      val empty = obtainEmpty(model)
      for ((i, field) in fields.withIndex())
        if (field.value(empty).isPrimary)
          return i
      fields.forEachIndexed { index, field ->
        if (field.name == "id")
          return index
      }
      val uniques = fields.filter {
        it.value(empty).uniqueIndexGroups != null
      }
      check(uniques.isNotEmpty()) {
        "Requested id of a Model that does not have either a primary key, column id, or unique indices"
      }
      var mostUnique = 0
      for ((i, field) in uniques.withIndex()) {
        if (i == 0)
          continue
        val uniqueFieldValue = uniques[0].value(empty)
        val value = field.value(empty)

        if (uniqueFieldValue.uniqueIndexGroups!!.size > value.uniqueIndexGroups!!.size)
          continue

        if (value.indexGroups == null)
          continue

        if ((uniqueFieldValue.indexGroups?.size ?: 0) > value.indexGroups!!.size)
          continue

        mostUnique = i
      }

      return mostUnique
    }

    private val idFieldsCache = mutableMapOf<Class<out Model>, List<ModelField>>()
    /**
     * Get all fields that identify a instance/row of the model.
     */
    fun obtainIdFields(model: Class<out Model>) = idFieldsCache.computeIfAbsent(model) {
      try {
        listOf(obtainId(model))
      } catch (e: IllegalStateException) {
        obtainFields(model)
      }
    }

    private val emptyCache = mutableMapOf<Class<out Model>, Model>()
    /**
     * Obtain a empty table/model class instance to reason about NOT to modify and use for
     * returning instances of tables/models.
     */
    @JvmStatic
    fun obtainEmpty(model: Class<out Model>) = emptyCache.computeIfAbsent(model) {
      try {
        it.newInstance()
      } catch (e: IllegalAccessException) {
        throw IllegalStateException("A model requires a accessible constructor to use", e)
      } catch (e: InstantiationException) {
        throw IllegalStateException("A model class requires a constructor with no arguments", e)
      }
    }

    private val nameCache = mutableMapOf<Class<out Model>, String>()
    @JvmStatic
    fun obtainTableName(model: Class<out Model>) = nameCache.computeIfAbsent(model) { obtainEmpty(it).table }

    private val sqlTableCache = mutableMapOf<Class<out Model>, List<SQLTable>>()
    /**
     * Obtain the abstract representation of a table/model class.
     */
    @JvmStatic
    fun obtainSQLTable(model: Class<out Model>) = sqlTableCache.computeIfAbsent(model) {
      val fields = obtainFields(model)
      val empty = obtainEmpty(model)
      val tables = mutableListOf<SQLTable>()

      val properties = mutableListOf<SQLTableProperty>()
      val foreignKeys = mutableListOf<SQLForeignKey>()
      var primary: String? = null
      val indicesMap = mutableMapOf<String?, MutableList<String>>()
      val uniquesMap = mutableMapOf<String?, MutableList<String>>()
      fields.forEach {
        val value = it.value(empty)
        val (type, params) = empty.driver.getSQLTypeFromClass(value.type, value.preferences)
        val pref = value.preferences

        if (value.isPrimary) {
          check(primary == null) { "A table cannot have multiple primary properties" }
          primary = it.name
        }

        value.indexGroups?.forEach { group ->
          indicesMap.computeIfAbsent(group) { mutableListOf() }.add(it.name)
          checkIndex(empty, it, type)
        }
        value.uniqueIndexGroups?.forEach { group ->
          uniquesMap.computeIfAbsent(group) { mutableListOf() }.add(it.name)
          checkIndex(empty, it, type)
        }

        val propertyDefault = when (val default = value.default) {
          is String -> "'$default'"
          is Number -> "$default"
          is Boolean -> if (empty.driver is SQLiteModelDriver) if (default) "1" else "0" else "$default"
          is Enum<*> ->
            if (empty.driver is SQLiteModelDriver) "${default.ordinal}"
            else "'${getEnumName(default)}'"
          is ISerializableDefault<*> -> default.serialize()
          else -> null
        }

        properties += SQLTableProperty(it.name, type, params, value.nullable, propertyDefault, value.isAutoIncrement)

        if (Model::class.java.isAssignableFrom(value.type)) {
          @Suppress("UNCHECKED_CAST")
          val otherModel = value.type as Class<out Model>
          val otherEmpty = obtainEmpty(otherModel)
          val otherId =
            if (pref is ValueWrapperPreferences.Reference)
              pref.column ?: obtainId(otherModel).name
            else obtainId(otherModel).name

          val preferences = value.reference()

          foreignKeys += SQLForeignKey(
            "${empty.table}_fk_${it.name}",
            listOf(it.name),
            SQLTableReference(otherEmpty.table, listOf(otherId)),
            preferences.deleteAction,
            preferences.changeAction
          )
        }
      }

      val indices = mutableListOf<SQLIndex>()
      val uniques = mutableListOf<SQLIndex>()

      for ((group, propertyList) in indicesMap) {
        if (group == null) {
          for (property in propertyList)
            indices += SQLIndex("${empty.table}_ix_$property", listOf(property))
        } else {
          indices += SQLIndex("${empty.table}_ix_$group", propertyList)
        }
      }

      for ((group, propertyList) in uniquesMap) {
        if (group == null) {
          for (property in propertyList)
            uniques += SQLIndex("${empty.table}_ux_$property", listOf(property))
        } else {
          uniques += SQLIndex("${empty.table}_ux_$group", propertyList)
        }
      }

      val modelTable = SQLTable(
        empty.table,
        properties,
        if (primary != null) SQLIndex("${empty.table}_pk", listOf(primary!!)) else null,
        indices,
        uniques,
        foreignKeys
      )

      tables.add(0, empty.driver.correctTable(modelTable))

      tables.addAll(
        empty::class.java.declaredFields
          .mapNotNull(ModelField.Companion::construct)
          .filter { it.delegate<Any?>(empty) is ReferenceManyToMany<*, *> }
          .map { it.delegate<ReferenceManyToMany<*, *>>(empty).list.sqlTable }
          .map(empty.driver::correctTable)
      )

      tables
    }

    private fun checkIndex(model: Model, field: ModelField, type: String) {
      check(type != "MEDIUMTEXT") {
        val property = "${model::class.java.simpleName}::${field.backendField.name.replace("\$delegate", "")}"
        "Unable to index $property without a max length. With .string { maxLength = X }"
      }
    }


    /*--------------------------------------------------*/
    /*          Table serialization functions           */
    /*--------------------------------------------------*/


    /**
     * Sort tables by SQL driver.
     */
    private fun tablesByDriver(tables: Array<out Class<out Model>>): Map<ISQLModelDriver, MutableList<Class<out Model>>> {
      val tablesByDriver = mutableMapOf<ISQLModelDriver, MutableList<Class<out Model>>>()
      for (clazz in setOf(*tables)) {
        val empty = obtainEmpty(clazz)
        tablesByDriver.computeIfAbsent(empty.driver) { mutableListOf() }.add(clazz)
      }
      return tablesByDriver
    }

    /**
     * Executes the required SQL code to synchronize the code table and the sql table.
     *
     * @param tables The tables to migrate.
     */
    @JvmStatic
    fun migrate(vararg tables: Class<out Model>) {
      for ((driver, driverClasses) in tablesByDriver(tables)) {
        val sql = driver.migrateSQL(*driverClasses.map(::obtainSQLTable).toTypedArray())

        if (sql.isNotEmpty())
          sql.split("\n-- separator").forEach { driver.execute(it) }
      }
    }

    /**
     * Executes the required SQL code to synchronize the code table and the sql table.
     *
     * @param tables The tables to migrate.
     * @see [migrate]
     */
    @JvmStatic
    fun migrate(vararg tables: KClass<out Model>) = migrate(*tables.map(KClass<out Model>::java).toTypedArray())

    /**
     * Get the DDL required to synchronize the code table classes and the SQL tables.
     * It will generate them in a map that has the driver that the table uses as a
     * key, and the value is the generated migration DDL.
     *
     * @param tables The tables to generate the migrations for.
     * @return A map that has the driver the table uses as the key to the migration DDL strings.
     */
    @JvmStatic
    fun migrateDDL(vararg tables: Class<out Model>): Map<ISQLModelDriver, String> {
      return tablesByDriver(tables).map { (driver, tables) ->
        driver to driver.migrateSQL(*tables.map(::obtainSQLTable).toTypedArray()).replace("-- separator", "")
      }.toMap()
    }

    /**
     * Get the DDL required to synchronize the code table classes and the SQL tables.
     * It will generate them in a map that has the driver that the table uses as a
     * key, and the value is the generated migration DDL.
     *
     * @param tables The tables to generate the migrations for.
     * @return A map that has the driver the table uses as the key to the migration DDL strings.
     * @see [migrateDDL]
     */
    @JvmStatic
    fun migrateDDL(vararg tables: KClass<out Model>) = migrateDDL(*tables.map(KClass<out Model>::java).toTypedArray())

    /**
     * Get the DDL for the tables passed in. It will generate them in a map that has
     * the driver that the table uses as a key, and the value is the generated table
     * creation DDL.
     *
     * This is basically how to create the tables in the SQL driver's native SQL.
     *
     * @param tables The tables to generate the definitions for.
     * @return A map that has the driver the table uses as the key to the tables DDL strings.
     */
    @JvmStatic
    fun ddl(vararg tables: Class<out Model>): Map<ISQLModelDriver, String> {
      return tablesByDriver(tables).map { (driver, tables) ->
        driver to if (tables.isEmpty()) "" else tables
          .flatMap(::obtainSQLTable)
          .sortedBy { it.foreignKeys.size == it.properties.size }
          .map(driver::toSQL)
          .toSet()
          .joinToString("\n\n")
      }.toMap()
    }

    /**
     * Get the DDL for the tables passed in. It will generate them in a map that has
     * the driver that the table uses as a key, and the value is the generated table
     * creation DDL.
     *
     * This is basically how to create the tables in the SQL driver's native SQL.
     *
     * @param tables The tables to generate the definitions for.
     * @return A map that has the driver the table uses as the key to the tables DDL strings.
     * @see [ddl]
     */
    @JvmStatic
    fun ddl(vararg tables: KClass<out Model>) = ddl(*tables.map(KClass<out Model>::java).toTypedArray())


    /*--------------------------------------------------*/
    /*         Retrieving of objects functions          */
    /*--------------------------------------------------*/


    /**
     * Get one item the model requested and searched by the property specified.
     */
    @JvmStatic
    fun <M : Model, T> get(modelClass: Class<M>, by: KProperty1<M, T>, param: T): M? {
      val empty = obtainEmpty(modelClass)
      val field = ModelField.construct(by.javaField)
      checkNotNull(field)

      return empty.driver.select(obtainFields(modelClass).map(ModelField::name).map { it.asSelectItem() })
        .from(empty.table)
        .where(field.name)
        .limit(1)
        .execute(param)
        .fetchInto(modelClass)
    }

    /**
     * Get one item the model requested and searched by the property specified.
     */
    @JvmStatic
    inline fun <reified M : Model, T> get(by: KProperty1<M, T>, param: T): M? = get(M::class.java, by, param)

    /**
     * Get all items matching a value searched by the property specified.
     */
    @JvmStatic
    fun <M : Model, T> all(modelClass: Class<M>, by: KProperty1<M, T>, param: T): List<M> {
      val empty = obtainEmpty(modelClass)
      val field = ModelField.construct(by.javaField)
      checkNotNull(field)

      return empty.driver.select(obtainFields(modelClass).map(ModelField::name).map { it.asSelectItem() })
        .from(empty.table)
        .where(field.name)
        .execute(param)
        .fetchAllInto(modelClass)
    }

    /**
     * Get all items matching a value searched by the property specified.
     */
    @JvmStatic
    inline fun <reified M : Model, T> all(by: KProperty1<M, T>, param: T) = all(M::class.java, by, param)
  }
}
