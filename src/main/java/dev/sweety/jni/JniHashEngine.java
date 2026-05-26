package dev.sweety.jni;

import dev.sweety.Backend;

import java.lang.foreign.MemorySegment;

/**
 * {@link dev.sweety.HashEngine} over JNI. The DIRECT delegates, pooled streaming
 * sessions, and {@code backend()/binding()} are generated ({@link JniHashEngineBase});
 * this class only adds what needs custom marshalling: the {@code byte[]} convenience
 * (copy + critical) and batch address extraction.
 */
public final class JniHashEngine extends JniHashEngineBase {

    public JniHashEngine(Backend backend) {
        super(backend);
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
    public long[] hashBatch(MemorySegment[] data, long[] lens) {
        long[] addrs = new long[data.length];
        for (int i = 0; i < data.length; i++) addrs[i] = data[i].address();
        return b.hashBatch(addrs, lens);
    }
}
