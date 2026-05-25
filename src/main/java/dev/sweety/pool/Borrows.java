package dev.sweety.pool;

import java.lang.annotation.*;

/** Marks a method/parameter that borrows a resource without taking ownership; the caller stays responsible. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.PARAMETER})
public @interface Borrows {
}
