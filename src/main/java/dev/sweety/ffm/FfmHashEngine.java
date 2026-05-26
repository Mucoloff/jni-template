package dev.sweety.ffm;

import dev.sweety.Backend;
import dev.sweety.Binding;
import dev.sweety.jni.FfmBindings;
import dev.sweety.jni.HashEngineBase;
import dev.sweety.mem.NativeArena;
import org.jetbrains.annotations.NotNull;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * {@link dev.sweety.HashEngine} over the Foreign Function & Memory API. The DIRECT
 * delegates, pooled streaming sessions, and {@code backend()/binding()} are generated
 * ({@link HashEngineBase}); this class only adds what needs custom marshalling:
 * the {@code byte[]} convenience and batch segment marshalling.
 */
public final class FfmHashEngine extends HashEngineBase<FfmBindings> {

    public FfmHashEngine(Backend backend) {
        super(new FfmBindings(backend), backend, Binding.FFM);
    }

    @Override
    public long hash(byte @NotNull [] data) {
        try (Arena a = Arena.ofConfined()) {
            return bindings.hash(NativeArena.copyOf(a, data), data.length);
        }
    }

    @Override
    public long @NotNull [] hashBatch(@NotNull MemorySegment[] data, long @NotNull [] lens) {
        int n = data.length;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrs = a.allocate(ADDRESS.byteSize() * n);
            MemorySegment lensSeg = a.allocate(JAVA_LONG.byteSize() * n);
            MemorySegment out = a.allocate(JAVA_LONG.byteSize() * n);
            for (int i = 0; i < n; i++) {
                ptrs.setAtIndex(ADDRESS, i, data[i]);
                lensSeg.setAtIndex(JAVA_LONG, i, lens[i]);
            }
            bindings.hashBatchRaw(ptrs, lensSeg, out, n);
            long[] result = new long[n];
            for (int i = 0; i < n; i++) result[i] = out.getAtIndex(JAVA_LONG, i);
            return result;
        }
    }
}
