package dev.sweety.jni;

import dev.sweety.Backend;
import dev.sweety.Binding;
import dev.sweety.HashEngine;
import dev.sweety.HashSession;
import dev.sweety.pool.ObjectPool;

import java.lang.foreign.MemorySegment;

/**
 * {@link HashEngine} over JNI. All the segment↔address plumbing and the native
 * method declarations are generated ({@link JniBindings}, {@code CppNatives} /
 * {@code RustNatives}); this class only adds the ergonomic bits: the {@code byte[]}
 * convenience, batch marshalling, and pooled streaming sessions.
 */
public final class JniHashEngine implements HashEngine {

    private final Backend backend;
    private final JniBindings b;
    private final ObjectPool<JniSession> sessions;

    public JniHashEngine(Backend backend) {
        this.backend = backend;
        this.b = JniBindings.of(backend);
        this.sessions = ObjectPool.threadLocal(
                () -> new JniSession(b.create()),
                s -> b.reset(s.state),
                s -> b.free(s.state),
                16);
    }

    @Override
    public long hash(byte[] data) {
        return b.hashArray(data);
    }

    /** Hashes a heap array without copying, via GetPrimitiveArrayCritical. */
    public long hashCritical(byte[] data) {
        return b.hashArrayCritical(data);
    }

    @Override
    public long hash(MemorySegment data, long len) {
        return b.hash(data, len);
    }

    @Override
    public long[] hashBatch(MemorySegment[] data, long[] lens) {
        long[] addrs = new long[data.length];
        for (int i = 0; i < data.length; i++) addrs[i] = data[i].address();
        return b.hashBatch(addrs, lens);
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
        return Binding.JNI;
    }

    final class JniSession implements HashSession {
        final MemorySegment state;

        JniSession(MemorySegment state) {
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
