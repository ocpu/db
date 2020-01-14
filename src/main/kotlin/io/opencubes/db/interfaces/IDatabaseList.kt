package io.opencubes.db.interfaces

import io.opencubes.db.sql.table.SQLTable
import io.opencubes.db.sql.table.SQLTableProperty

/**
 * This is a representation of a list coupled with a database query.
 */
interface IDatabaseList<E> : MutableList<E> {
// TODO Think about live mode
// Should there be a cache that stores all objects and the user has to refresh to get new values. Or.
// Should there all functions directly make database queries.
// The first avoids making database queries while the other loves them.
// Always making database requests can have the server queue up a lot of requests.
// Maybe have a "live" model?
// Maybe have a automatic "live" mode switch
  /**
   * See if this list is in live mode or not.
   *
   * Live mode is if the objects are cached or directly taken
   * from the database. If this is false then the objects are
   * cached; i.e not live.
   */
  val liveMode: Boolean

  /**
   * The underlying table that this list interacts with to
   * modify items.
   */
  val sqlTable: SQLTable

  /**
   * The property this database list uses as the primary one
   * for getting, setting, and inserting items.
   */
  val primaryProperty: SQLTableProperty

  /**
   * This method when called fill re-fetch all objects if not
   * in live mode.
   *
   * @see [liveMode]
   */
  fun refresh()
}
