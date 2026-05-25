package dev.sweety.jni;

import dev.sweety.nativeapi.Cabi;
import dev.sweety.nativeapi.Jni;
import dev.sweety.nativeapi.NativeApi;
import dev.sweety.nativeapi.Ptr;

import java.lang.foreign.MemorySegment;

/**
 * Single source of truth for the native surface, expressed at the
 * {@link MemorySegment} level. The annotation processor lowers each method twice:
 *
 * <ul>
 *   <li><b>JNI</b>: {@code @Ptr MemorySegment} → {@code long} address. Generates the
 *       holder classes (CppNatives/RustNatives), a JSON descriptor for the native
 *       RegisterNatives tables, and {@code JniBindings} (the segment↔address glue).</li>
 *   <li><b>FFM</b>: {@code @Ptr MemorySegment} → {@code ADDRESS}. Generates
 *       {@code FfmBindings} (cached downcall handles + invokeExact wrappers) for
 *       every {@link Cabi}-annotated method.</li>
 * </ul>
 *
 * Array methods (no {@link Cabi}) are JNI-only. {@code thunk} is the native
 * function symbol (identical in C++ and Rust) implementing the JNI side.
 */
@NativeApi
interface FnvNative {

    // --- JNI-only: heap byte[] (copy vs critical) --------------------------------

    @Jni(thunk = "jni_hash_array")
    long hashArray(byte[] data);

    @Jni(thunk = "jni_hash_array_crit", critical = true)
    long hashArrayCritical(byte[] data);

    // --- dual binding (JNI + FFM) ------------------------------------------------

    @Jni(thunk = "jni_hash")
    @Cabi("nat_fnv_hash")
    long hash(@Ptr MemorySegment data, long len);

    @Jni(thunk = "jni_transform")
    @Cabi("nat_transform")
    void transform(@Ptr MemorySegment data, long len, byte add);

    @Jni(thunk = "jni_create")
    @Cabi("nat_fnv_new")
    @Ptr
    MemorySegment create();

    @Jni(thunk = "jni_free")
    @Cabi("nat_fnv_free")
    void free(@Ptr MemorySegment state);

    @Jni(thunk = "jni_update")
    @Cabi("nat_fnv_update")
    void update(@Ptr MemorySegment state, @Ptr MemorySegment data, long len);

    @Jni(thunk = "jni_digest")
    @Cabi("nat_fnv_digest")
    long digest(@Ptr MemorySegment state);

    @Jni(thunk = "jni_reset")
    @Cabi("nat_fnv_reset")
    void reset(@Ptr MemorySegment state);

    // --- batch: JNI takes arrays; FFM takes the raw C-ABI pointer form ------------

    @Jni(thunk = "jni_hash_batch")
    long[] hashBatch(long[] addrs, long[] lens);

    @Cabi("nat_fnv_hash_batch")
    void hashBatchRaw(@Ptr MemorySegment ptrs, @Ptr MemorySegment lens, @Ptr MemorySegment out, long n);
}
