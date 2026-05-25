package dev.sweety.nativeapi;

import java.lang.annotation.*;

/**
 * Marks a value as a native pointer. On a parameter it applies to that
 * parameter; on a method it applies to the return value. A {@code @Ptr}
 * {@code MemorySegment} lowers to a {@code long} address for the JNI binding and
 * to an {@code ADDRESS} layout for the FFM binding.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.PARAMETER, ElementType.METHOD})
public @interface Ptr {
}
