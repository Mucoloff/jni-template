package dev.sweety.jni;

import dev.sweety.Backend;
import dev.sweety.NativeLib;
import org.jetbrains.annotations.NotNull;

/**
 * JNI holder for the C++ backend. {@code libnative_cpp}'s JNI_OnLoad registers these.
 */
final class CppNatives implements RawNatives {

    static {
        NativeLib.loadForJni(Backend.CPP);
    }

    static final CppNatives INSTANCE = new CppNatives();

    private CppNatives() {
    }

    @Override
    public native long hashArray(byte @NotNull [] data);

    @Override
    public native long hashArrayCritical(byte @NotNull [] data);

    @Override
    public native long hash(long addr, long len);

    @Override
    public native long @NotNull [] hash(long @NotNull [] addrs, long @NotNull [] lens);

    @Override
    public native void transform(long addr, long len, byte add);

    @Override
    public native long create();

    @Override
    public native void free(long handle);

    @Override
    public native void update(long handle, long addr, long len);

    @Override
    public native long digest(long handle);

    @Override
    public native void reset(long handle);
}
