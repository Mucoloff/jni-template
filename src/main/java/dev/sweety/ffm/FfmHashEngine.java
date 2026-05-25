package dev.sweety.ffm;

import dev.sweety.Backend;
import dev.sweety.Binding;
import dev.sweety.HashEngine;
import dev.sweety.HashSession;
import dev.sweety.NativeLib;
import dev.sweety.mem.NativeArena;
import dev.sweety.pool.ObjectPool;
import org.jetbrains.annotations.NotNull;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * {@link HashEngine} over the Foreign Function & Memory API. The native library
 * is linked by file path (no JNI stubs); each C-ABI symbol becomes a cached
 * {@link MethodHandle} invoked with {@code invokeExact}. {@link MemorySegment}s
 * are passed straight through — zero copy, no per-call JNI marshalling.
 */
public final class FfmHashEngine implements HashEngine {
    private static final Linker LINKER = Linker.nativeLinker();
    private final Backend backend;
    private final MethodHandle hash;       // long nat_fnv_hash(addr, len)
    private final MethodHandle transform;  // void nat_transform(addr, len, byte)
    private final MethodHandle batch;      // void nat_fnv_hash_batch(ptrs, lens, out, n)
    private final MethodHandle sNew, sUpdate, sDigest, sReset, sFree;
    private final ObjectPool<FfmSession> sessions;

    public FfmHashEngine(Backend backend) {
        this.backend = backend;
        SymbolLookup lib = SymbolLookup.libraryLookup(NativeLib.libraryPath(backend), Arena.global());
        this.hash = down(lib, "nat_fnv_hash", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
        this.transform = down(lib, "nat_transform", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_BYTE));
        this.batch = down(lib, "nat_fnv_hash_batch", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
        this.sNew = down(lib, "nat_fnv_new", FunctionDescriptor.of(ADDRESS));
        this.sUpdate = down(lib, "nat_fnv_update", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG));
        this.sDigest = down(lib, "nat_fnv_digest", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        this.sReset = down(lib, "nat_fnv_reset", FunctionDescriptor.ofVoid(ADDRESS));
        this.sFree = down(lib, "nat_fnv_free", FunctionDescriptor.ofVoid(ADDRESS));
        this.sessions = ObjectPool.threadLocal(
                () -> new FfmSession(newState()),
                FfmSession::doReset,
                FfmSession::doFree,
                16);
    }

    private static MethodHandle down(SymbolLookup lib, String name, FunctionDescriptor fd) {
        return LINKER.downcallHandle(
                lib.find(name).orElseThrow(() -> new IllegalStateException("missing symbol " + name)),
                fd);
    }

    @Override
    public long hash(byte @NotNull [] data) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment seg = NativeArena.copyOf(a, data);
            return (long) hash.invokeExact(seg, (long) data.length);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    @Override
    public long hash(@NotNull MemorySegment data, long len) {
        try {
            return (long) hash.invokeExact(data, len);
        } catch (Throwable t) {
            throw wrap(t);
        }
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
            batch.invokeExact(ptrs, lensSeg, out, (long) n);
            long[] result = new long[n];
            for (int i = 0; i < n; i++) result[i] = out.getAtIndex(JAVA_LONG, i);
            return result;
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    @Override
    public void transform(@NotNull MemorySegment data, long len, byte add) {
        try {
            transform.invokeExact(data, len, add);
        } catch (Throwable t) {
            throw wrap(t);
        }
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

    private MemorySegment newState() {
        try {
            return (MemorySegment) sNew.invokeExact();
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    private static RuntimeException wrap(Throwable t) {
        return t instanceof RuntimeException re ? re : new RuntimeException(t);
    }

    final class FfmSession implements HashSession {
        private final MemorySegment state;

        FfmSession(MemorySegment state) {
            this.state = state;
        }

        @Override
        public void update(@NotNull MemorySegment data, long len) {
            try {
                sUpdate.invokeExact(state, data, len);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        @Override
        public long digest() {
            try {
                return (long) sDigest.invokeExact((MemorySegment) state);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        @Override
        public void reset() {
            doReset();
        }

        @Override
        public void close() {
            sessions.release(this);
        }

        void doReset() {
            try {
                sReset.invokeExact(state);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        void doFree() {
            try {
                sFree.invokeExact(state);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }
    }
}
