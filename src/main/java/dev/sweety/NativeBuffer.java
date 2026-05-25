package dev.sweety;

import java.nio.ByteBuffer;

/**
 * Direct-buffer / zero-copy JNI pattern: native code reads memory the JVM never
 * copies. {@link #hash} resolves the buffer's address with
 * {@code GetDirectBufferAddress} and walks it in place — the real performance
 * win of JNI for large payloads.
 *
 * <p>Memory returned by {@link #allocate} is owned by native code (malloc /
 * Rust allocator) and lives outside the Java heap, so it must be released with
 * {@link #free}. The GC will not reclaim it.
 */
public final class NativeBuffer {
    static { NativeLib.ensureLoaded(); }

    private NativeBuffer() {}

    /** FNV-1a 64-bit hash over the first {@code len} bytes of a direct buffer, zero-copy. */
    public static native long hash(ByteBuffer buffer, int len);

    /** Allocate {@code size} bytes of native (off-heap) memory as a direct ByteBuffer. */
    public static native ByteBuffer allocate(int size);

    /** Release memory previously returned by {@link #allocate}. */
    public static native void free(ByteBuffer buffer);
}
