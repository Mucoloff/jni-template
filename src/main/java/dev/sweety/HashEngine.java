package dev.sweety;

import dev.sweety.ffm.FfmHashEngine;
import dev.sweety.jni.JniHashEngine;
import dev.sweety.pool.Acquire;

import java.lang.foreign.MemorySegment;

/**
 * FNV-1a 64-bit hashing over native code, with one API surface that runs on
 * either {@link Binding#JNI} or {@link Binding#FFM}, against either native
 * {@link Backend}. Obtain an instance via {@link #of(Binding, Backend)}.
 *
 * <p>The zero-copy paths take a native {@link MemorySegment} (off-heap) and never
 * copy it across the boundary — the cheapest way to feed large payloads to native
 * code regardless of binding.
 */
public interface HashEngine {

    /** Convenience one-shot hash of a heap array (copies across the boundary). */
    long hash(byte[] data);

    /** Zero-copy one-shot hash of the first {@code len} bytes of a native segment. */
    long hash(MemorySegment data, long len);

    /** Hash {@code data.length} native segments in a single crossing. */
    long[] hashBatch(MemorySegment[] data, long[] lens);

    /** Memory-bound in-place transform: each byte += {@code add}. */
    void transform(MemorySegment data, long len, byte add);

    /** Open a streaming digest session; close it to return resources to the pool. */
    @Acquire
    HashSession open();

    /** Which binding / backend this engine uses. */
    Binding binding();
    Backend backend();

    static HashEngine of(Binding binding, Backend backend) {
        return switch (binding) {
            case JNI -> new JniHashEngine(backend);
            case FFM -> new FfmHashEngine(backend);
        };
    }
}
