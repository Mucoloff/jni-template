package dev.sweety.pool;

import java.lang.annotation.*;

/** Marks a type whose instances are managed by an {@link ObjectPool} or allocator. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Pooled {
    Class<?> pool() default Void.class;
}
