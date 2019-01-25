package io.opencubes.sql

/**
 * If the property name and the name in the database differs
 * use this annotation to specify what it is.
 *
 * @param value The serialized value
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@MustBeDocumented
annotation class SerializedName(val value: String)
