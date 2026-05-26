package dev.sweety;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Set;

public final class NativeLib {
    private static final Set<Backend> loaded = EnumSet.noneOf(Backend.class);

    private NativeLib() {
    }

    /**
     * Load a backend's library into the JVM for JNI use (idempotent per backend).
     * Triggers RegisterNatives via the library's JNI_OnLoad.
     */
    public static synchronized void loadForJni(Backend backend) {
        if (loaded.contains(backend)) return;
        System.loadLibrary(backend.libName());
        loaded.add(backend);
    }

    /**
     * Resolve the on-disk path of a backend's shared library by scanning
     * {@code java.library.path}. Needed by the FFM binding, which links the
     * library by file path rather than loading it into the JVM.
     */
    public static Path libraryPath(Backend backend) {
        String file = System.mapLibraryName(backend.libName()); // e.g. libnative_cpp.so
        for (String dir : System.getProperty("java.library.path", "").split(File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            Path p = Paths.get(dir, file);
            if (Files.isRegularFile(p)) return p;
        }
        throw new IllegalStateException(
                "native library " + file + " not found on java.library.path");
    }
}
