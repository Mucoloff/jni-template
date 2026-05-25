package dev.sweety.pool

/** Marks a method that returns a resource to its pool; the reference must not be used afterwards.  */
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
annotation class Release 
