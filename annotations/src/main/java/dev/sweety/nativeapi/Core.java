package dev.sweety.nativeapi;

import java.lang.annotation.*;

/**
 * Marks a {@link Cabi} method whose flat C-ABI body is a 1:1 delegation to the native core
 * type (see {@link NativeApi#coreType()}), so the body can be generated in C++ and Rust.
 *
 * <p>The {@link Op} says which core operation it is. Methods without {@code @Core}
 * (e.g. an in-place transform or a batch loop) keep a hand-written C-ABI body.
 *
 * <p>Lowering convention for the generated body (first {@code @Ptr} param is the handle for
 * the stateful ops): {@code NEW} → construct, {@code FREE} → destroy, {@code UPDATE} →
 * {@code handle.update(ptr, len)}, {@code DIGEST}/{@code RESET} → {@code handle.op()},
 * {@code HASH} → static {@code hash(ptr, len)}.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Core {
    Op value();

    enum Op { NEW, FREE, UPDATE, DIGEST, RESET, HASH }
}
