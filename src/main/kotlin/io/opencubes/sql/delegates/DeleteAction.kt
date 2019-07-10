package io.opencubes.sql.delegates

/**
 * Actions that can be taken when a object is deleted.
 *
 * @param sql The sql representation of the action.
 */
enum class DeleteAction(val sql: String) {
  /**
   * When this object is deleted set the references to null.
   */
  SET_NULL("SET NULL"),
  /**
   * When this object is deleted set the references to their default value.
   */
  SET_DEFAULT("SET DEFAULT"),
  /**
   * When this object is deleted delete the referenced object.
   */
  CASCADE("CASCADE"),
  @Deprecated("This action is virtually indistinguishable from the 'NO ACTION' action", ReplaceWith("NO_ACTION"))
  RESTRICT("RESTRICT"),
  /**
   * When this object is deleted do nothing to the references.
   */
  NO_ACTION("NO ACTION");

  /**
   * Show the [sql] string instead of the enum name.
   */
  override fun toString() = sql

  companion object {
    /**
     * Get a enum value from a string.
     */
    fun fromString(string: String): DeleteAction = when (string.toUpperCase()) {
      SET_DEFAULT.sql -> SET_DEFAULT
      SET_NULL.sql -> SET_NULL
      RESTRICT.sql -> NO_ACTION
      CASCADE.sql -> CASCADE
      else -> NO_ACTION
    }
  }
}
