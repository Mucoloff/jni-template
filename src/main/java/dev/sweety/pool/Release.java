package dev.sweety.pool;

import java.lang.annotation.*;

/** Marks a method that returns a resource to its pool; the reference must not be used afterwards. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Release {
}
