package dev.sweety.example.buffer;

import dev.sweety.nativeapi.Cabi;
import dev.sweety.nativeapi.Jni;
import dev.sweety.nativeapi.NativeApi;
import dev.sweety.nativeapi.Ptr;

import java.lang.foreign.MemorySegment;

/**
 * Example native surface over off-heap buffers. Every {@code @Ptr MemorySegment} lowers to
 * a {@code long} address for the JNI binding and to {@code ADDRESS} for the FFM binding —
 * the framework's generic pointer handling, driven by this spec with no processor changes.
 */
@NativeApi
interface BufferNative {

    @Jni(thunk = "jni_fill")
    @Cabi("nat_fill")
    void fill(@Ptr MemorySegment buf, long len, byte value);

    @Jni(thunk = "jni_sum")
    @Cabi("nat_sum")
    long sum(@Ptr MemorySegment buf, long len);

    @Jni(thunk = "jni_copy")
    @Cabi("nat_copy")
    void copy(@Ptr MemorySegment dst, @Ptr MemorySegment src, long len);
}
