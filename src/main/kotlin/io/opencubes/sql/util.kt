package io.opencubes.sql

import java.util.*

/**
 * Maybe use a [AutoCloseable] object if it is present in the [Optional].
 */
fun <T, R> Optional<T>.useIfPresent(block: (T) -> R): R? where T : AutoCloseable {
  if (!isPresent) return null
  return get().use(block)
}
