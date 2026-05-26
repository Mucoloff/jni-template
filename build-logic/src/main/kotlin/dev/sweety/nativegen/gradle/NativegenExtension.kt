package dev.sweety.nativegen.gradle

/** Configuration for the `dev.sweety.nativegen` plugin. */
abstract class NativegenExtension {
    /** Native core type the generated C-ABI `@Core` bodies delegate to (e.g. "Fnv"). */
    var coreType: String = "Fnv"

    /** Build the C++ backend (native/cpp via CMake). */
    var cpp: Boolean = true

    /** Build the Rust backend (native/rust via Cargo). */
    var rust: Boolean = true

    /**
     * Maven group of the framework artifacts to depend on. Default = mavenLocal/Central coords;
     * for JitPack set to "com.github.Mucoloff.jni-ffm-api" (JitPack rewrites the group).
     */
    var group: String = "dev.sweety.nativegen"

    /** Version of the framework artifacts (a JitPack tag, e.g. "v0.1.1", or "0.1.1"). */
    var version: String = "0.1.1"
}
