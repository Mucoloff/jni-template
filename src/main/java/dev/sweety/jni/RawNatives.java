package dev.sweety.jni;

import dev.sweety.nativeapi.Jni;
import dev.sweety.nativeapi.NativeApi;

/**
 * Single source of truth for the JNI native surface. The annotation processor
 * derives each method's JNI signature from its types and generates the per-backend
 * holder classes ({@code CppNatives}, {@code RustNatives}) plus a JSON descriptor
 * that the C++/Rust builds turn into their RegisterNatives tables. {@code thunk}
 * is the native function symbol (identical in both languages) implementing it.
 *
 * <p>Zero-copy paths take a native address as {@code long}; the {@code byte[]}
 * paths are JNI-only (copy / critical).
 */
@NativeApi
interface RawNatives {

    @Jni(thunk = "jni_hash_array")
    long hashArray(byte[] data);

    @Jni(thunk = "jni_hash_array_crit", critical = true)
    long hashArrayCritical(byte[] data);

    @Jni(thunk = "jni_hash")
    long hash(long addr, long len);

    @Jni(thunk = "jni_transform")
    void transform(long addr, long len, byte add);

    @Jni(thunk = "jni_hash_batch")
    long[] hash(long[] addrs, long[] lens);

    @Jni(thunk = "jni_create")
    long create();

    @Jni(thunk = "jni_free")
    void free(long handle);

    @Jni(thunk = "jni_update")
    void update(long handle, long addr, long len);

    @Jni(thunk = "jni_digest")
    long digest(long handle);

    @Jni(thunk = "jni_reset")
    void reset(long handle);
}
