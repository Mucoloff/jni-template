package dev.sweety;

public class NativeApi {
    static {
        System.loadLibrary("nativelib");
    }

    public native int sum(int a, int b);

    public native int subtract(int a, int b);
}
