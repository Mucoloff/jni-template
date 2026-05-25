package dev.sweety.nativeapi;

import java.lang.annotation.*;

/**
 * Per-method native binding metadata. The JNI registration name defaults to the
 * Java method name; {@link #thunk()} is the native C++/Rust function symbol that
 * implements it (identical in both languages, so the generated tables can never
 * drift). The JNI signature is derived from the method's parameter/return types.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Jni {
    /** Native function symbol implementing this method (same name in C++ and Rust). */
    String thunk();

    /** Documentation hint: uses GetPrimitiveArrayCritical. Informational only. */
    boolean critical() default false;
}
