package dev.sweety;

import dev.sweety.ffm.FfmHashEngine;
import dev.sweety.jni.JniHashEngine;

/**
 * Dispatches {@link Binding} to a concrete {@link HashEngine} impl. Kept in Java so
 * the Kotlin compiler (which runs before the Java annotation processor) never has to
 * resolve the impls' generated {@code *HashEngineBase} supertypes — it only sees this
 * factory's {@code HashEngine} signature.
 */
public final class EngineFactory {
    private EngineFactory() {}

    public static HashEngine of(Binding binding, Backend backend) {
        return switch (binding) {
            case JNI -> new JniHashEngine(backend);
            case FFM -> new FfmHashEngine(backend);
        };
    }
}
