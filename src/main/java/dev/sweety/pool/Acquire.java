package dev.sweety.pool;

import java.lang.annotation.*;

/** Marks a method that transfers ownership of a pooled resource to the caller, who must release it. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Acquire {
    String releaseMethod() default "release";
}
