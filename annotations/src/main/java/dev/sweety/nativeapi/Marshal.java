package dev.sweety.nativeapi;

import java.lang.annotation.*;

/**
 * Declares how a {@code @NativeApi} method surfaces in the generated <em>engine</em>
 * layer (the ergonomic public API impl), using one of the framework's <em>built-in</em>
 * generic strategies. For project-specific marshalling (custom argument shapes) use
 * {@link Strategy @Strategy} with a plugin instead.
 *
 * <p>{@code DIRECT}: the engine method mirrors the binding 1:1. The {@code SESSION_*}
 * roles are wired into a single pooled session type (see {@link Engine}): the first
 * parameter of {@code SESSION_UPDATE/DIGEST/RESET} is the native state handle (kept as
 * the session field); the remaining parameters become the public session-method params.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Marshal {
    Op value();

    enum Op {
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
    }
}
