package dev.sweety;

public final class Scalar {
    static { NativeLib.ensureLoaded(); }

    private Scalar() {}

    public static native int sum(int a, int b);
    public static native int subtract(int a, int b);
}
