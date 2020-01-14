package io.opencubes.db

import java.util.function.Predicate
import kotlin.reflect.KCallable
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.isAccessible

/**
 * Split a list on a specific item.
 */
fun <T> Iterable<T>.split(item: T): List<List<T>> = split { it == item }

/**
 * Split list of a predicate
 */
fun <T> Iterable<T>.split(predicate: (T) -> Boolean): List<List<T>> = split(Predicate(predicate))

/**
 * Split list of a predicate
 */
fun <T> Iterable<T>.split(predicate: Predicate<T>): List<List<T>> {
  val res = mutableListOf<MutableList<T>>()
  var index = 0
  for (current in this) {
    if (predicate.test(current)) {
      index++
      continue
    }
    if (res.size == index) {
      res.add(index, mutableListOf(current))
      continue
    }
    res[index].add(current)
  }
  return res
}

/**
 * Get the name of the enum value in the database. First try to get the
 * [SerializedName] annotation value; if not get the name of the enum
 */
fun getEnumName(e: Enum<*>) = try {
  e.javaClass
    .getField(e.name)
    .getAnnotation(SerializedName::class.java)
    ?.value
    ?: e.name
} catch (ignored: NoSuchFieldException) {
  e.name
}

/**
 * All functions that touch the general concept of words.
 */
object Words {
  private const val vowels = "aouåeiyäö"
  /**
   * Pluralize the word specified
   */
  @JvmStatic
  fun pluralize(word: String): String {
    return when {
      word.endsWith("ss") -> "${word}es"
      word.endsWith("s") -> "${word}es"
      word.endsWith("sh") -> "${word}es"
      word.endsWith("ch") -> "${word}es"
      word.endsWith("x") -> "${word}es"
      word.endsWith("z") -> "${word}es"
      word.endsWith("f") -> "${word.substring(0, word.lastIndex)}ves"
      word.endsWith("fe") -> "${word.substring(0, word.lastIndex - 1)}ves"
      word.length > 1 && word[word.length - 1] == 'y' && word[word.length - 2] !in vowels ->
        "${word.substring(0, word.lastIndex)}ies"
      else -> "${word}s"
    }
  }
}

val String.sqlEscape: String get() = '`' + this.removeSurrounding("`").removeSurrounding("\"") + '`'


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
fun <R> java.lang.reflect.Field?.accessibleOrNull(block: java.lang.reflect.Field.() -> R): R? {
  if (this == null) return null
  val a = isAccessible
  if (!a) isAccessible = true
  val res = block(this)
  if (!a) isAccessible = false
  return res
}

/**
 * Make the field accessible if not already for the duration of the supplied function call.
 */
fun <R> java.lang.reflect.Field.accessible(block: java.lang.reflect.Field.() -> R): R {
  val a = isAccessible
  if (!a) isAccessible = true
  val res = block(this)
  if (!a) isAccessible = false
  return res
}
