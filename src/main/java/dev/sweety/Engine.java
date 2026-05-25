package dev.sweety;

public final class Engine implements Calculator, AutoCloseable {
    static {
        NativeLib.ensureLoaded();
    }

    private long handle;

    public Engine() {
        handle = create();
    }

    @Override
    public int sum(int a, int b) {
        return sum(handle, a, b);
    }

    @Override
    public int subtract(int a, int b) {
        return subtract(handle, a, b);
    }

    @Override
    public void close() {
        if (handle == 0L) return;
        destroy(handle);
        handle = 0L;
    }

    private static native long create();

    private static native void destroy(long handle);

    private static native int sum(long handle, int a, int b);

    private static native int subtract(long handle, int a, int b);
}
