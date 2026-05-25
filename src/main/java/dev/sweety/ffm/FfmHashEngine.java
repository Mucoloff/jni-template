package dev.sweety.ffm;

import dev.sweety.Backend;
import dev.sweety.Binding;
import dev.sweety.HashEngine;
import dev.sweety.HashSession;
import dev.sweety.jni.FfmBindings;
import dev.sweety.mem.NativeArena;
import dev.sweety.pool.ObjectPool;

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
    private final FfmBindings b;
    private final ObjectPool<FfmSession> sessions;

    public FfmHashEngine(Backend backend) {
        this.backend = backend;
        this.b = new FfmBindings(backend);
        this.sessions = ObjectPool.threadLocal(
                () -> new FfmSession(b.create()),
                s -> b.reset(s.state),
                s -> b.free(s.state),
                16);
    }

    @Override
    public long hash(byte[] data) {
        try (Arena a = Arena.ofConfined()) {
            return b.hash(NativeArena.copyOf(a, data), data.length);
        }
    }

    @Override
    public long hash(MemorySegment data, long len) {
        return b.hash(data, len);
    }

    @Override
    public long[] hashBatch(MemorySegment[] data, long[] lens) {
        int n = data.length;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ptrs = a.allocate(ADDRESS.byteSize() * n);
            MemorySegment lensSeg = a.allocate(JAVA_LONG.byteSize() * n);
            MemorySegment out = a.allocate(JAVA_LONG.byteSize() * n);
            for (int i = 0; i < n; i++) {
                ptrs.setAtIndex(ADDRESS, i, data[i]);
                lensSeg.setAtIndex(JAVA_LONG, i, lens[i]);
            }
            b.hashBatchRaw(ptrs, lensSeg, out, n);
            long[] result = new long[n];
            for (int i = 0; i < n; i++) result[i] = out.getAtIndex(JAVA_LONG, i);
            return result;
        }
    }

    @Override
    public void transform(MemorySegment data, long len, byte add) {
        b.transform(data, len, add);
    }

    @Override
    public HashSession open() {
        return sessions.acquire();
    }

    @Override
    public Backend backend() {
        return backend;
    }

    @Override
    public Binding binding() {
        return Binding.FFM;
    }

    final class FfmSession implements HashSession {
        final MemorySegment state;

        FfmSession(MemorySegment state) {
            this.state = state;
        }

        @Override
        public void update(MemorySegment data, long len) {
            b.update(state, data, len);
        }

        @Override
        public long digest() {
            return b.digest(state);
        }

        @Override
        public void reset() {
            b.reset(state);
        }

        @Override
        public void close() {
            sessions.release(this);
        }
    }
}
