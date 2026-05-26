package dev.sweety.nativegen.gradle

/** Configuration for the `dev.sweety.nativegen` plugin. */
abstract class NativegenExtension {
    /** Native core type the generated C-ABI `@Core` bodies delegate to (e.g. "Fnv"). */
    var coreType: String = "Fnv"

    /** Build the C++ backend (native/cpp via CMake). */
    var cpp: Boolean = true

    /** Build the Rust backend (native/rust via Cargo). */
    var rust: Boolean = true
}
