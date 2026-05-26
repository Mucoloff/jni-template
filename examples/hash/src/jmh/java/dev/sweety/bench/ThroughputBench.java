package dev.sweety.bench;

import dev.sweety.HashEngine;
import org.openjdk.jmh.annotations.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;

/**
 * Throughput on a 1 MiB payload: native work dominates, so JNI and FFM should be
 * close and the C++/Rust difference reflects the actual compute. Contrasts a
 * compute-bound hash against a memory-bound transform.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class ThroughputBench {

    @Param({"JNI", "FFM"})
    public dev.sweety.Binding binding;
    @Param({"CPP", "RUST"})
    public dev.sweety.Backend backend;

    private static final long SIZE = 1 << 20; // 1 MiB

    private HashEngine engine;
    private Arena arena;
    private MemorySegment seg;

    @Setup
    public void setup() {
        engine = HashEngine.of(binding, backend);
        arena = Arena.ofConfined();
        seg = arena.allocate(SIZE);
        for (long i = 0; i < SIZE; i += 64) seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i, (byte) i);
    }

    @TearDown
    public void tearDown() { arena.close(); }

    @Benchmark
    public long hashLarge() {
        return engine.hash(seg, SIZE);
    }

    @Benchmark
    public void transformLarge() {
        engine.transform(seg, SIZE, (byte) 1);
    }
}
