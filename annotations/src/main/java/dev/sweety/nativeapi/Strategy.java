package dev.sweety.nativeapi;

import java.lang.annotation.*;

/**
 * Opts a method into a <em>custom</em> engine-layer marshalling strategy supplied by a
 * plugin (a {@code dev.sweety.nativegen.spi.MarshalStrategy} on the KSP classpath), keyed
 * by {@link #id()}. The framework core does not know the strategy's shape — it delegates
 * code generation to the registered plugin. Use {@link Marshal @Marshal} for the built-in
 * generic strategies instead.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Strategy {
    /** Plugin strategy id, e.g. "heap" or "batch". */
    String id();

    /** Public engine method name this contributes to. Empty = the annotated method's own name. */
    String engine() default "";

    /** Whether the generated engine method {@code @Override}s the public interface. */
    boolean iface() default true;

    /** Flat C-ABI symbol the JNI thunk routes to (for the native shape). Empty = the method's own {@code @Cabi}. */
    String target() default "";
}
