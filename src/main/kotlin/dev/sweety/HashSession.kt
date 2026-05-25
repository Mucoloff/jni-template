package dev.sweety

import dev.sweety.pool.Pooled
import dev.sweety.pool.Release
import java.lang.AutoCloseable
import java.lang.foreign.MemorySegment

/**
 * Incremental FNV-1a digest backed by native state. Feed bytes over many
 * [.update] calls, read [.digest], then [.close] to recycle.
 * Instances are pooled, so `close()` returns the session (and its native
 * handle) to a pool rather than freeing it.
 */
@Pooled
interface HashSession : AutoCloseable {
    /** Feed `len` bytes from a native segment into the running hash.  */
    fun update(data: MemorySegment, len: Long)

    /** Current 64-bit FNV-1a value of everything fed so far.  */
    fun digest(): Long

    /** Discard accumulated state, start fresh.  */
    fun reset()

    @Release
    override fun close()
}
