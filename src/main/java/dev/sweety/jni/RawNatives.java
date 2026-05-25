package dev.sweety.jni;

/**
 * The native call surface registered via RegisterNatives. Implemented once per
 * backend ({@link CppNatives}, {@link RustNatives}) so two native libraries can
 * be loaded at once — each registers its methods on its own holder class, since
 * RegisterNatives binds to a specific class.
 */
interface RawNatives {
    long hashArray(byte[] data);          // GetByteArrayElements (copy)
    long hashArrayCritical(byte[] data);  // GetPrimitiveArrayCritical (no copy)
    long hashAddr(long addr, long len);   // zero-copy native address
    void transformAddr(long addr, long len, byte add);
    long[] hashBatch(long[] addrs, long[] lens);

    long sCreate();
    void sFree(long handle);
    void sUpdate(long handle, long addr, long len);
    long sDigest(long handle);
    void sReset(long handle);
}
