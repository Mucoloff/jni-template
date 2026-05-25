package dev.sweety

import dev.sweety.ffm.FfmHashEngine
import dev.sweety.jni.JniHashEngine
import dev.sweety.pool.Acquire
import java.lang.foreign.MemorySegment

/**
 * FNV-1a 64-bit hashing over native code, with one API surface that runs on
 * either [Binding.JNI] or [Binding.FFM], against either native
 * [Backend]. Obtain an instance via [.of].
 * 
 * 
 * The zero-copy paths take a native [MemorySegment] (off-heap) and never
 * copy it across the boundary — the cheapest way to feed large payloads to native
 * code regardless of binding.
 */
interface HashEngine {
    /** Convenience one-shot hash of a heap array (copies across the boundary).  */
    fun hash(data: ByteArray): Long

    /** Zero-copy one-shot hash of the first `len` bytes of a native segment.  */
    fun hash(data: MemorySegment, len: Long): Long

    /** Hash `data.length` native segments in a single crossing.  */
    fun hashBatch(data: Array<MemorySegment>, lens: LongArray): LongArray

    /** Memory-bound in-place transform: each byte += `add`.  */
    fun transform(data: MemorySegment, len: Long, add: Byte)

    /** Open a streaming digest session; close it to return resources to the pool.  */
    @Acquire
    fun open(): HashSession

    /** Which binding / backend this engine uses.  */
    fun binding(): Binding
    fun backend(): Backend

    companion object {
        @JvmStatic
        fun of(binding: Binding, backend: Backend): HashEngine {
            return when (binding) {
                Binding.JNI -> JniHashEngine(backend)
                Binding.FFM -> FfmHashEngine(backend)
            }
        }
    }
}
