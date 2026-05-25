package dev.sweety.pool

/** Marks a method/parameter that borrows a resource without taking ownership; the caller stays responsible.  */
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.VALUE_PARAMETER
)
annotation class Borrows 
