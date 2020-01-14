package io.opencubes.db.sql

import java.sql.Date
import java.sql.Time
import java.sql.Timestamp
import java.util.Calendar
import kotlin.math.roundToLong

/**
 * Get the current timestamp and have a representation of it in SQL.
 */
object CurrentTimestamp : ISerializableDefault<Timestamp> {
  override fun get() = with (Calendar.getInstance()) {
    Timestamp((timeInMillis / 1000).toDouble().roundToLong() * 1000)
  }
  override fun serialize() = "CURRENT_TIMESTAMP"
}

/**
 * Get the current time and have a representation of it in SQL.
 */
object CurrentTime : ISerializableDefault<Time> {
  override fun get() = with (Calendar.getInstance()) {
    Time((timeInMillis / 1000).toDouble().roundToLong() * 1000)
  }
  override fun serialize() = "CURRENT_TIME"
}

/**
 * Get the current date and have a representation of it in SQL.
 */
object CurrentDate : ISerializableDefault<Date> {
  override fun get() = with (Calendar.getInstance()) {
    Date((timeInMillis / 1000).toDouble().roundToLong() * 1000)
  }
  override fun serialize() = "CURRENT_DATE"
}
