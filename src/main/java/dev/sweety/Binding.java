package dev.sweety;

/** How the JVM reaches the native code: classic JNI stubs, or the Foreign Function & Memory API. */
public enum Binding {
    /** JNI: native methods bound via RegisterNatives. */
    JNI,
    /** FFM / Panama: {@code java.lang.foreign} downcall handles, no JNI stubs. */
    FFM
}
