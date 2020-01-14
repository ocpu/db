package io.opencubes.db

/**
 * Actions that can be taken when a object is deleted.
 *
 * @param sql The sql representation of the action.
 */
enum class ForeignKeyAction(val sql: String) {
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
  @Suppress("unused")
  @Deprecated("This action is virtually indistinguishable from the 'NO ACTION' action", ReplaceWith("NO_ACTION"))
  RESTRICT("RESTRICT"),
  /**
   * When this object is deleted do nothing to the references.
   */
  NO_ACTION("NO ACTION");

  /**
   * Show the sql string instead of the enum name.
   */
  override fun toString() = sql

  /** statics */
  companion object {
    /**
     * Get a enum value from a string.
     */
    @JvmStatic
    fun fromString(string: String?): ForeignKeyAction = when (string?.toUpperCase()) {
      SET_DEFAULT.sql -> SET_DEFAULT
      SET_NULL.sql -> SET_NULL
      "RESTRICT" -> NO_ACTION
      CASCADE.sql -> CASCADE
      else -> NO_ACTION
    }
  }
}
