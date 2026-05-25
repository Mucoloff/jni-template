package dev.sweety.jni

/**
 * The native call surface registered via RegisterNatives. Implemented once per
 * backend ([CppNatives], [RustNatives]) so two native libraries can
 * be loaded at once — each registers its methods on its own holder class, since
 * RegisterNatives binds to a specific class.
 */
internal interface RawNatives {
    fun hashArray(data: ByteArray): Long // GetByteArrayElements (copy)
    fun hashArrayCritical(data: ByteArray): Long // GetPrimitiveArrayCritical (no copy)
    fun hash(addr: Long, len: Long): Long // zero-copy native address
    fun transform(addr: Long, len: Long, add: Byte)
    fun hash(addrs: LongArray, lens: LongArray): LongArray

    fun create(): Long
    fun free(handle: Long)
    fun update(handle: Long, addr: Long, len: Long)
    fun digest(handle: Long): Long
    fun reset(handle: Long)
}