package dev.sweety.jni;

import dev.sweety.Backend;
import dev.sweety.Binding;
import org.jetbrains.annotations.NotNull;

import java.lang.foreign.MemorySegment;

/**
 * {@link dev.sweety.HashEngine} over JNI. The DIRECT delegates, pooled streaming
 * sessions, and {@code backend()/binding()} are generated ({@link HashEngineBase});
 * this class only adds what needs custom marshalling: the {@code byte[]} convenience
 * (copy + critical) and batch address extraction.
 */
public final class JniHashEngine extends HashEngineBase<JniBindings> {

    public JniHashEngine(Backend backend) {
        super(JniBindings.of(backend), backend, Binding.JNI);
    }

    @Override
    public long hash(byte @NotNull [] data) {
        return bindings.hashArray(data);
    }

    /** Hashes a heap array without copying, via GetPrimitiveArrayCritical. */
    public long hashCritical(byte @NotNull [] data) {
        return bindings.hashArrayCritical(data);
    }

    @Override
    public long @NotNull [] hashBatch(@NotNull MemorySegment @NotNull [] data, long @NotNull [] lens) {
        long[] addrs = new long[data.length];
        for (int i = 0; i < data.length; i++) addrs[i] = data[i].address();
        return bindings.hashBatch(addrs, lens);
    }
}
