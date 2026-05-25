package dev.sweety;

/**
 * Stateful-handle JNI pattern: a long-lived native object referenced by an
 * opaque {@code long} handle (a raw pointer). {@link #create} allocates it,
 * {@link #close} frees it — classic RAII across the boundary.
 *
 * <p>This is the idiom to reach for when native state must persist across calls.
 * Here it backs an incremental FNV-1a digest: feed bytes over many
 * {@link #update} calls, then read {@link #digest}.
 */
public final class Hasher implements Digest, AutoCloseable {
    static { NativeLib.ensureLoaded(); }

    private long handle;

    public Hasher() {
        handle = create();
    }

    @Override
    public void update(byte[] data) {
        update(handle, data, data.length);
    }

    @Override
    public long digest() {
        return digest(handle);
    }

    @Override
    public void reset() {
        reset(handle);
    }

    @Override
    public void close() {
        if (handle == 0L) return;
        destroy(handle);
        handle = 0L;
    }

    private static native long create();

    private static native void destroy(long handle);

    private static native void update(long handle, byte[] data, int len);

    private static native long digest(long handle);

    private static native void reset(long handle);
}
