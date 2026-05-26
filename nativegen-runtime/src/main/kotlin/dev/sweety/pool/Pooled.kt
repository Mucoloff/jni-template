package dev.sweety.pool

import kotlin.reflect.KClass

/** Marks a type whose instances are managed by an [ObjectPool] or allocator.  */
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class Pooled(val pool: KClass<*> = Void::class)
