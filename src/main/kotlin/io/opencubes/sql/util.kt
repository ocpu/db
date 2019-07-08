package io.opencubes.sql

import java.sql.Blob
import java.util.*
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.reflect.KCallable
import kotlin.reflect.jvm.isAccessible

/**
 * Maybe use a [AutoCloseable] object if it is present in the [Optional].
 */
fun <T, R> Optional<T>.useIfPresent(block: (T) -> R): R? where T : AutoCloseable {
  if (!isPresent) return null
  return get().use(block)
}

/**
 * Make a string into snake case
 */
fun String.toSnakeCase(): String {
  return this
    .replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]}_${it.groupValues[2]}" }
    .replace(Regex("([a-z])-([a-z])")) { "${it.groupValues[1]}_${it.groupValues[2]}" }
    .replace(Regex("([a-z])\\s+([a-z])")) { "${it.groupValues[1]}_${it.groupValues[2]}" }
    .toLowerCase()
}

/**
 * Have a property be accessible for a moment and then turn it back.
 */
fun <R, V, T : KCallable<V>> T?.accessible(block: T.() -> R): R? {
  if (this == null) return null
  val a = isAccessible
  if (!a) isAccessible = true
  val res = block(this)
  if (!a) isAccessible = false
  return res
}

/**
 * Have a [Field][java.lang.reflect.Field] be accessible for a moment and then turn it back.
 */
fun <R> java.lang.reflect.Field?.accessible(block: java.lang.reflect.Field.() -> R): R? {
  if (this == null) return null
  val a = isAccessible
  if (!a) isAccessible = true
  val res = block(this)
  if (!a) isAccessible = false
  return res
}

/**
 * Get all data from a [Blob].
 */
val Blob.bytes: ByteArray get() = getBytes(1, length().toInt()) ?: byteArrayOf()
