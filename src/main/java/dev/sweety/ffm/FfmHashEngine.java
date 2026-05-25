package dev.sweety.ffm;

import dev.sweety.Backend;
import dev.sweety.Binding;
import dev.sweety.HashEngine;
import dev.sweety.HashSession;
import dev.sweety.jni.FfmBindings;
import dev.sweety.mem.NativeArena;
import dev.sweety.pool.ObjectPool;
import org.jetbrains.annotations.NotNull;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * {@link HashEngine} over the Foreign Function & Memory API. The downcall handles
 * and {@code invokeExact} wrappers are generated ({@link FfmBindings}); this class
 * only adds the ergonomic bits: the {@code byte[]} convenience, batch marshalling,
 * and pooled streaming sessions.
 */
public final class FfmHashEngine implements HashEngine {

    private final Backend backend;
    private final FfmBindings bindings;
    private final ObjectPool<FfmSession> sessions;

    public FfmHashEngine(Backend backend) {
        this.backend = backend;
        this.bindings = new FfmBindings(backend);
        this.sessions = ObjectPool.threadLocal(
                () -> new FfmSession(bindings.create()),
                FfmSession::reset,
                FfmSession::free,
                16);
    }

    @Override
    public long hash(byte @NotNull [] data) {
        try (Arena a = Arena.ofConfined()) {
            return bindings.hash(NativeArena.copyOf(a, data), data.length);
        }
    }

    @Override
    public long hash(@NotNull MemorySegment data, long len) {
        return bindings.hash(data, len);
    }

    @Override
    public long @NotNull [] hashBatch(MemorySegment[] data, long @NotNull [] lens) {
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

    @Override
    public void transform(@NotNull MemorySegment data, long len, byte add) {
        bindings.transform(data, len, add);
    }

    @Override
    public @NotNull HashSession open() {
        return sessions.acquire();
    }

    @Override
    public @NotNull Backend backend() {
        return backend;
    }

    @Override
    public @NotNull Binding binding() {
        return Binding.FFM;
    }

    final class FfmSession implements HashSession {
        final MemorySegment state;

        FfmSession(MemorySegment state) {
            this.state = state;
        }

        @Override
        public void update(@NotNull MemorySegment data, long len) {
            bindings.update(state, data, len);
        }

        @Override
        public long digest() {
            return bindings.digest(state);
        }

        @Override
        public void reset() {
            bindings.reset(state);
        }
        public void free() {
            bindings.free(state);
        }

        @Override
        public void close() {
            sessions.release(this);
        }
    }
}
