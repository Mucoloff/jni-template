package dev.sweety.bench;

import dev.sweety.Backend;
import dev.sweety.Binding;
import dev.sweety.HashEngine;
import dev.sweety.mem.SegmentPool;
import org.openjdk.jmh.annotations.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;

/**
 * Off-heap allocation strategy per operation: a fresh confined {@link Arena}
 * every time vs reusing a pooled segment. Run with {@code -prof gc} to see the
 * difference in allocation rate / GC pressure, not just wall time.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class AllocBench {

    private static final int SIZE = 4096;

    private HashEngine engine;
    private SegmentPool pool;

    @Setup
    public void setup() {
        engine = HashEngine.of(Binding.FFM, Backend.CPP);
        pool = new SegmentPool(SIZE, 16);
    }

    @Benchmark
    public long freshArena() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment seg = a.allocate(SIZE);
            return engine.hash(seg, SIZE);
        }
    }

    @Benchmark
    public long pooled() {
        try (SegmentPool.Lease lease = pool.acquire()) {
            return engine.hash(lease.segment(), SIZE);
        }
    }
}
