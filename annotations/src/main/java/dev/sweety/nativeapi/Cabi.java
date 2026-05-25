package dev.sweety.nativeapi;

import java.lang.annotation.*;

/**
 * The flat C-ABI symbol that implements this method, used to build the FFM
 * downcall handle. A method without {@code @Cabi} (or one taking arrays) is
 * JNI-only and is excluded from the generated FFM bindings.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Cabi {
    String value();
}
