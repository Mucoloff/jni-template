package dev.sweety.nativeapi;

import java.lang.annotation.*;

/**
 * Opt-in: on a {@code @NativeApi} interface, asks the processor to also generate the
 * <em>engine</em> base classes — one per binding — implementing the ergonomic public
 * {@link #iface()} on top of the generated bindings.
 *
 * <p>The base classes are {@code abstract}: they implement the {@link Marshal.Strategy#DIRECT}
 * methods, the pooled session ({@code SESSION_*} roles + {@link #session()}), and
 * {@code backend()/binding()}. Methods the engine exposes but that need custom marshalling
 * (heap {@code byte[]}, batch) are left abstract for a hand-written {@code final} subclass.
 *
 * <p>Generated in the same package as the annotated interface, named {@code Jni<base>} /
 * {@code Ffm<base>} from {@link #baseSuffix()} (e.g. {@code JniHashEngineBase}).
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Engine {
    /** Fully-qualified public engine interface the bases implement (e.g. dev.sweety.HashEngine). */
    String iface();

    /** Fully-qualified pooled session interface (e.g. dev.sweety.HashSession). */
    String session();

    /** Name of the generated generic base class. */
    String baseSuffix() default "HashEngineBase";

    /** Per-thread pool size for sessions. */
    int poolSize() default 16;

    /**
     * FQN of the fully-generated JNI engine class (HEAP_HASH + BATCH marshalling). Empty =
     * do not generate the concrete impl (Phase 1: hand-written subclass).
     */
    String jniImpl() default "";

    /** FQN of the fully-generated FFM engine class. Empty = do not generate. */
    String ffmImpl() default "";
}
