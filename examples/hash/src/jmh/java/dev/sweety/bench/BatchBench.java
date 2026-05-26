package dev.sweety.bench;

import dev.sweety.HashEngine;
import org.openjdk.jmh.annotations.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;

/**
 * Many small payloads: one batched crossing vs a per-call loop. Shows how
 * amortizing the JVM<->native transition pays off when work-per-call is small.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class BatchBench {

    @Param({"JNI", "FFM"})
    public dev.sweety.Binding binding;
    @Param({"CPP", "RUST"})
    public dev.sweety.Backend backend;

    private static final int N = 256;
    private static final int ITEM = 64;

    private HashEngine engine;
    private Arena arena;
    private MemorySegment[] segs;
    private long[] lens;

    @Setup
    public void setup() {
        engine = HashEngine.of(binding, backend);
        arena = Arena.ofConfined();
        segs = new MemorySegment[N];
        lens = new long[N];
        for (int i = 0; i < N; i++) {
            segs[i] = arena.allocate(ITEM);
            lens[i] = ITEM;
        }
    }

    @TearDown
    public void tearDown() { arena.close(); }

    @Benchmark
    public long[] batched() {
        return engine.hashBatch(segs, lens);
    }

    @Benchmark
    public long perCallLoop() {
        long acc = 0;
        for (int i = 0; i < N; i++) acc ^= engine.hash(segs[i], lens[i]);
        return acc;
    }
}
