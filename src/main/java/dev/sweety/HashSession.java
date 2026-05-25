package dev.sweety;

import dev.sweety.pool.Pooled;
import dev.sweety.pool.Release;

import java.lang.foreign.MemorySegment;

/**
 * Incremental FNV-1a digest backed by native state. Feed bytes over many
 * {@link #update} calls, read {@link #digest}, then {@link #close} to recycle.
 * Instances are pooled, so {@code close()} returns the session (and its native
 * handle) to a pool rather than freeing it.
 */
@Pooled
public interface HashSession extends AutoCloseable {

    /** Feed {@code len} bytes from a native segment into the running hash. */
    void update(MemorySegment data, long len);

    /** Current 64-bit FNV-1a value of everything fed so far. */
    long digest();

    /** Discard accumulated state, start fresh. */
    void reset();

    @Release
    @Override
    void close();
}
