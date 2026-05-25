package dev.sweety

/**
 * Streaming digest contract, implemented by the native-backed [Hasher].
 * Lives in the Kotlin layer to show JVM-language interop across the JNI boundary.
 */
interface Digest {
    /** Feed more bytes into the running hash. */
    fun update(data: ByteArray)

    /** Current FNV-1a 64-bit value of everything fed so far. */
    fun digest(): Long

    /** Discard accumulated state, start a fresh hash. */
    fun reset()
}
