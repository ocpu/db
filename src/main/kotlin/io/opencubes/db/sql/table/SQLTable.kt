package io.opencubes.db.sql.table

/**
 * A class that describes the structure of a SQL table.
 * @constructor
 * @property name The name of the table.
 * @property primaryKey A index that primarily describes the table.
 * @param properties The list of properties this table has.
 * @param indices Some indices that describe one or more properties.
 * @param uniques Some indices that describe one or more properties that MUST be unique for each row.
 * @param foreignKeys A list of foreign key connections this table has to other tables.
 */
class SQLTable(
  val name: String,
  properties: List<SQLTableProperty>,
  val primaryKey: SQLIndex?,
  indices: List<SQLIndex>,
  uniques: List<SQLIndex>,
  foreignKeys: List<SQLForeignKey>
) {
  private val _properties: MutableList<SQLTableProperty> = properties.toMutableList()
  private val _indices: MutableList<SQLIndex> = indices.toMutableList()
  private val _uniques: MutableList<SQLIndex> = uniques.toMutableList()
  private val _foreignKeys: MutableList<SQLForeignKey> = foreignKeys.toMutableList()

  /**The list of properties this table has.*/
  val properties: List<SQLTableProperty> get() = _properties
  /**Some indices that describe one or more properties.*/
  val indices: List<SQLIndex> get() = _indices
  /**Some indices that describe one or more properties that MUST be unique for each row.*/
  val uniques: List<SQLIndex> get() = _uniques
  /**A list of foreign key connections this table has to other tables.*/
  val foreignKeys: List<SQLForeignKey> get() = _foreignKeys

  /**
   * Add a index to the current table.
   *
   * @param unique Is this index describing a value that is unique to that / those columns.
   * @param index The index you want to add.
   */
  fun addIndex(unique: Boolean, index: SQLIndex) {
    if (unique) {
      val uniquesIndex = _uniques.indexOfFirst { it.properties == index.properties }
      check(uniquesIndex == -1) { "Cannot create duplicate indexes" }
      _uniques.add(index)
    } else {
      val indicesIndex = _indices.indexOfFirst { it.properties == index.properties }
      check(indicesIndex == -1) { "Cannot create duplicate indexes" }
      _indices.add(index)
    }
  }

  /**
   * Remove a index from the current table.
   *
   * @param unique Is this index describing a value that is unique to that / those columns.
   * @param index The index you want to remove.
   */
  fun dropIndex(unique: Boolean, index: String) {
    if (unique) {
      val indexToRemove = _uniques.indexOfFirst { it.name == index }
      check(indexToRemove == -1) { "Cannot drop a unique index that does not exist" }
      _uniques.removeAt(indexToRemove)
    } else {
      val indexToRemove = _indices.indexOfFirst { it.name == index }
      check(indexToRemove == -1) { "Cannot drop a index that does not exist" }
      _indices.removeAt(indexToRemove)
    }
  }

  /**
   * Add a foreign key connection to the current table.
   *
   * @param foreignKey The connection you want to add.
   */
  fun addForeignKey(foreignKey: SQLForeignKey) {
    _foreignKeys.add(foreignKey)
  }

  /**
   * Remove a foreign key connection from the current table.
   *
   * @param foreignKey The connection you want to remove.
   */
  fun dropForeignKey(foreignKey: String) {
    _foreignKeys.removeIf { it.name == foreignKey }
  }

  /**
   * Modify a foreign key connection from the current table.
   *
   * @param foreignKey The constraint name of the connection to replace.
   * @param replacement The connection you want to have as a replacement.
   */
  fun modifyForeignKey(foreignKey: String, replacement: SQLForeignKey) {
    val index = _foreignKeys.indexOfFirst { it.name == foreignKey }
    check(index != -1) { "Cannot modify a nonexistent foreign key" }
    _foreignKeys[index] = replacement
  }

  /**
   * Adds a new property to the table.
   *
   * @param property The property to add.
   */
  fun addProperty(property: SQLTableProperty) {
    _properties.add(property)
  }

  /**
   * Removes a property from the table.
   *
   * @param property The property to remove.
   */
  fun dropProperty(property: String) {
    _properties.removeIf { it.name == property }
  }

  /**
   * Replaces a property to the table.
   *
   * @param property The property to replace.
   * @param replacement The replacement property.
   */
  fun modifyProperty(property: String, replacement: SQLTableProperty) {
    val index = _properties.indexOfFirst { it.name == property }
    check(index != -1) { "Cannot modify a nonexistent property" }
    _properties[index] = replacement
  }

  /**
   * Copy the current table instance and replace the parts you want.
   */
  fun copy(name: String = this.name,
           properties: List<SQLTableProperty> = this.properties,
           primaryKey: SQLIndex? = this.primaryKey,
           indices: List<SQLIndex> = this.indices,
           uniques: List<SQLIndex> = this.uniques,
           foreignKeys: List<SQLForeignKey> = this.foreignKeys): SQLTable =
    SQLTable(name, properties, primaryKey, indices, uniques, foreignKeys)
}
