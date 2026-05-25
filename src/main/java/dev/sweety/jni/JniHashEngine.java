package dev.sweety.jni;

import dev.sweety.Backend;
import dev.sweety.HashEngine;
import dev.sweety.HashSession;
import dev.sweety.pool.ObjectPool;
import org.jetbrains.annotations.NotNull;

import java.lang.foreign.MemorySegment;

/**
 * {@link HashEngine} over JNI. Native methods are bound via RegisterNatives (see
 * the native JNI_OnLoad), and zero-copy paths pass a {@link MemorySegment}'s
 * native address as a {@code long}. Streaming sessions are pooled so their native
 * handles are reused instead of allocated/freed per use.
 */
public final class JniHashEngine implements HashEngine {

    private final Backend backend;
    private final RawNatives n;
    private final ObjectPool<JniSession> sessions;

    public JniHashEngine(Backend backend) {
        this.backend = backend;
        this.n = switch (backend) {
            case CPP -> CppNatives.get();
            case RUST -> RustNatives.get();
        };
        this.sessions = ObjectPool.threadLocal(
                () -> new JniSession(n.create()),
                s -> n.reset(s.handle),    // reset native state on return
                s -> n.free(s.handle),     // free on pool overflow
                16);
    }

    @Override
    public long hash(byte @NotNull [] data) {
        return n.hashArray(data);
    }

    @Override
    public long hash(MemorySegment data, long len) {
        return n.hash(data.address(), len);
    }

    @Override
    public long @NotNull [] hashBatch(MemorySegment[] data, long @NotNull [] lens) {
        long[] addrs = new long[data.length];
        for (int i = 0; i < data.length; i++) addrs[i] = data[i].address();
        return n.hash(addrs, lens);
    }

    @Override
    public void transform(MemorySegment data, long len, byte add) {
        n.transform(data.address(), len, add);
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
    public dev.sweety.@NotNull Binding binding() {
        return dev.sweety.Binding.JNI;
    }

    /**
     * Hashes a heap array without copying, via GetPrimitiveArrayCritical.
     */
    public long hashCritical(byte[] data) {
        return n.hashArrayCritical(data);
    }

    final class JniSession implements HashSession {
        final long handle;

        JniSession(long handle) {
            this.handle = handle;
        }

        @Override
        public void update(MemorySegment data, long len) {
            n.update(handle, data.address(), len);
        }

        @Override
        public long digest() {
            return n.digest(handle);
        }

        @Override
        public void reset() {
            n.reset(handle);
        }

        @Override
        public void close() {
            sessions.release(this);
        }
    }
}
