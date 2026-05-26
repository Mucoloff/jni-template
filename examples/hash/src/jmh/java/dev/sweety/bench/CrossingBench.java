package dev.sweety.bench;

import dev.sweety.HashEngine;
import org.openjdk.jmh.annotations.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;

/**
 * Per-call crossing overhead: a tiny 16-byte payload, so the native work is
 * negligible and the cost is dominated by the JVM<->native transition. Isolates
 * JNI stub overhead vs FFM downcall overhead.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class CrossingBench {

    @Param({"JNI", "FFM"})
    public dev.sweety.Binding binding;
    @Param({"CPP", "RUST"})
    public dev.sweety.Backend backend;

    private HashEngine engine;
    private Arena arena;
    private MemorySegment seg;

    @Setup
    public void setup() {
        engine = HashEngine.of(binding, backend);
        arena = Arena.ofConfined();
        seg = arena.allocate(16);
    }

    @TearDown
    public void tearDown() {
        arena.close();
    }

    @Benchmark
    public long hashSmall() {
        return engine.hash(seg, 16);
    }
}
