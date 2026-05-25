package dev.sweety.jni;

import dev.sweety.Backend;
import dev.sweety.NativeLib;

/** JNI holder for the Rust backend. {@code libnative_rust}'s JNI_OnLoad registers these. */
final class RustNatives implements RawNatives {
    static final RustNatives INSTANCE = new RustNatives();

    private RustNatives() {
        NativeLib.loadForJni(Backend.RUST);
    }

    static native long nHashArray(byte[] data);
    static native long nHashArrayCritical(byte[] data);
    static native long nHashAddr(long addr, long len);
    static native void nTransform(long addr, long len, byte add);
    static native long[] nHashBatch(long[] addrs, long[] lens);
    static native long nsCreate();
    static native void nsFree(long handle);
    static native void nsUpdate(long handle, long addr, long len);
    static native long nsDigest(long handle);
    static native void nsReset(long handle);

    @Override public long hashArray(byte[] d) { return nHashArray(d); }
    @Override public long hashArrayCritical(byte[] d) { return nHashArrayCritical(d); }
    @Override public long hashAddr(long a, long l) { return nHashAddr(a, l); }
    @Override public void transformAddr(long a, long l, byte add) { nTransform(a, l, add); }
    @Override public long[] hashBatch(long[] a, long[] l) { return nHashBatch(a, l); }
    @Override public long sCreate() { return nsCreate(); }
    @Override public void sFree(long h) { nsFree(h); }
    @Override public void sUpdate(long h, long a, long l) { nsUpdate(h, a, l); }
    @Override public long sDigest(long h) { return nsDigest(h); }
    @Override public void sReset(long h) { nsReset(h); }
}
