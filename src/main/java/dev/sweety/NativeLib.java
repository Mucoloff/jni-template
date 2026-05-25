package dev.sweety;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.Map;

public final class NativeLib {
    private static final Map<Backend, Boolean> jniLoaded = new EnumMap<>(Backend.class);

    private NativeLib() {}

    /**
     * Load a backend's library into the JVM for JNI use (idempotent per backend).
     * Triggers RegisterNatives via the library's JNI_OnLoad.
     */
    public static synchronized void loadForJni(Backend backend) {
        if (jniLoaded.getOrDefault(backend, false)) return;
        System.loadLibrary(backend.getLibName());
        jniLoaded.put(backend, true);
    }

    /**
     * Resolve the on-disk path of a backend's shared library by scanning
     * {@code java.library.path}. Needed by the FFM binding, which links the
     * library by file path rather than loading it into the JVM.
     */
    public static Path libraryPath(Backend backend) {
        String file = System.mapLibraryName(backend.getLibName()); // e.g. libnative_cpp.so
        for (String dir : System.getProperty("java.library.path", "").split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            Path p = Paths.get(dir, file);
            if (Files.isRegularFile(p)) return p;
        }
        throw new IllegalStateException(
                "native library " + file + " not found on java.library.path");
    }
}
