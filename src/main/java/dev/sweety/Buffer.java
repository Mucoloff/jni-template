package dev.sweety;

import java.nio.ByteBuffer;

public final class Buffer {
    static { NativeLib.ensureLoaded(); }

    private Buffer() {}

    public static native void process(ByteBuffer buffer, int len);
    public static native ByteBuffer allocate(int size);
    public static native void free(ByteBuffer buffer);
}
