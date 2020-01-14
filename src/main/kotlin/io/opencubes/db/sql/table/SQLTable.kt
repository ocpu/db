package io.opencubes.db.sql.table

class SQLTable(
  val name: String,
  properties: List<SQLTableProperty>,
  val primaryKey: SQLIndex?,
  indices: List<SQLIndex>,
  uniques: List<SQLIndex>,
  foreignKeys: List<SQLForeignKey>
) {
  private val _properties: MutableList<SQLTableProperty> = properties.toMutableList()
  val properties: List<SQLTableProperty> get() = _properties
  private val _indices: MutableList<SQLIndex> = indices.toMutableList()
  val indices: List<SQLIndex> get() = _indices
  private val _uniques: MutableList<SQLIndex> = uniques.toMutableList()
  val uniques: List<SQLIndex> get() = _uniques
  private val _foreignKeys: MutableList<SQLForeignKey> = foreignKeys.toMutableList()
  val foreignKeys: List<SQLForeignKey> get() = _foreignKeys

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

  fun addForeignKey(foreignKey: SQLForeignKey) {
    _foreignKeys.add(foreignKey)
  }

  fun dropForeignKey(foreignKey: String) {
    _foreignKeys.removeIf { it.name == foreignKey }
  }

  fun modifyForeignKey(foreignKey: String, replacement: SQLForeignKey) {
    val index = _foreignKeys.indexOfFirst { it.name == foreignKey }
    check(index != -1) { "Cannot modify a nonexistent foreign key" }
    _foreignKeys[index] = replacement
  }

  fun addProperty(property: SQLTableProperty) {
    _properties.add(property)
  }

  fun dropProperty(property: String) {
    _properties.removeIf { it.name == property }
  }

  fun modifyProperty(property: String, replacement: SQLTableProperty) {
    val index = _properties.indexOfFirst { it.name == property }
    check(index != -1) { "Cannot modify a nonexistent property" }
    _properties[index] = replacement
  }

  fun copy(name: String = this.name,
           properties: List<SQLTableProperty> = this.properties,
           primaryKey: SQLIndex? = this.primaryKey,
           indices: List<SQLIndex> = this.indices,
           uniques: List<SQLIndex> = this.uniques,
           foreignKeys: List<SQLForeignKey> = this.foreignKeys): SQLTable =
    SQLTable(name, properties, primaryKey, indices, uniques, foreignKeys)
}
