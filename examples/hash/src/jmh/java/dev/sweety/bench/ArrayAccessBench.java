package dev.sweety.bench;

import dev.sweety.Backend;
import dev.sweety.jni.JniHashEngine;
import dev.sweety.mem.NativeArena;
import org.openjdk.jmh.annotations.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Three ways to feed a heap {@code byte[]} to native code (JNI only):
 *   - GetByteArrayElements: simple, may copy the whole array
 *   - GetPrimitiveArrayCritical: no copy, but may pause the GC
 *   - copy to an off-heap MemorySegment once, then hash zero-copy
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class ArrayAccessBench {

    @Param({"CPP", "RUST"})
    public Backend backend;

    @Param({"4096", "65536"})
    public int size;

    private JniHashEngine engine;
    private byte[] heap;
    private Arena arena;
    private MemorySegment seg;

    @Setup
    public void setup() {
        engine = new JniHashEngine(backend);
        final int finalSize = size;
        heap = new byte[finalSize];
        new Random(42).nextBytes(heap);
        arena = Arena.ofConfined();
        seg = arena.allocate(finalSize);
        NativeArena.fill(seg, heap);
    }

    @TearDown
    public void tearDown() { arena.close(); }

    @Benchmark
    public long getElements() { return engine.hash(heap); }

    @Benchmark
    public long critical() { return engine.hashCritical(heap); }

    @Benchmark
    public long segmentZeroCopy() { return engine.hash(seg, size); }
}
