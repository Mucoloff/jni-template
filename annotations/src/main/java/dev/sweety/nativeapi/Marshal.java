package dev.sweety.nativeapi;

import java.lang.annotation.*;

/**
 * Declares how a {@code @NativeApi} method surfaces in the generated <em>engine</em>
 * layer (the ergonomic public API impl), on top of the raw binding it already drives.
 *
 * <p>A method without {@code @Marshal} is binding-only: it appears in the generated
 * {@code JniBindings}/{@code FfmBindings} but the engine layer does not expose it
 * (the hand-written subclass may still call it — e.g. {@code hashArray}).
 *
 * <p>For {@link Strategy#DIRECT} the engine method mirrors the binding 1:1. The
 * {@code SESSION_*} roles are wired together into a single pooled session type
 * (see {@link Engine}): the first parameter of {@code SESSION_UPDATE/DIGEST/RESET}
 * is the native state handle (kept as the session field); the remaining parameters
 * become the public session-method parameters.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Marshal {
    Strategy value();

    /**
     * Name of the engine method this drives (for {@code HEAP_HASH}/{@code BATCH}, whose engine
     * signature differs from the native one). Empty = the annotated method's own name.
     */
    String engine() default "";

    /** Whether the generated engine method {@code @Override}s the public interface. */
    boolean iface() default true;

    enum Strategy {
        /** Engine method delegates 1:1 to the binding wrapper (same signature). */
        DIRECT,
        /** Allocates native state; becomes the session factory (returns {@code @Ptr}). */
        SESSION_CREATE,
        /** Frees native state; becomes the pool discard hook. */
        SESSION_FREE,
        /** Feeds bytes into a session (first param = state handle). */
        SESSION_UPDATE,
        /** Reads a session's running value (first param = state handle). */
        SESSION_DIGEST,
        /** Resets a session; also the pool reset hook (first param = state handle). */
        SESSION_RESET,
        /** Heap {@code byte[]} hash — Phase 2 (engine method hand-written for now). */
        HEAP_HASH,
        /** Batch over segments — Phase 2 (engine method hand-written for now). */
        BATCH,
    }
}
