package dev.sweety;

public final class NativeLib {
    private static volatile boolean loaded = false;
    private static volatile Backend current;

    private NativeLib() {}

    public static synchronized void load(Backend backend) {
        if (loaded) return;
        System.loadLibrary(backend.getLibName());
        current = backend;
        loaded = true;
    }

    public static synchronized void ensureLoaded() {
        if (!loaded) load(Backend.Companion.fromProperty());
    }

    public static Backend current() { return current; }
}
