package dev.sweety.pool

/** Marks a method that transfers ownership of a pooled resource to the caller, who must release it.  */
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
annotation class Acquire(val releaseMethod: String = "release")
