// JNI bridge for dev.sweety.jni.JniHashEngine.
//
// Two JNI performance techniques are demonstrated here:
//   1. RegisterNatives in JNI_OnLoad — the JVM binds Java native methods to these
//      functions by an explicit table instead of resolving them by mangled name on
//      first call. Function names below are therefore arbitrary.
//   2. Address-based zero-copy paths — MemorySegment/direct-buffer addresses are
//      passed as jlong and read in place, so no array copy crosses the boundary.
//      The byte[] paths (copy vs critical) exist to contrast against that.
#include <jni.h>
#include <cstdint>
#include "fnv.hpp"

namespace {

inline const uint8_t* as_ptr(jlong addr) {
    return reinterpret_cast<const uint8_t*>(addr);
}

// --- byte[] paths: copy vs critical ------------------------------------------

jlong hashArray(JNIEnv* env, jclass, jbyteArray arr) {
    jsize len = env->GetArrayLength(arr);
    jbyte* b = env->GetByteArrayElements(arr, nullptr); // may copy
    if (!b) return 0;
    jlong h = static_cast<jlong>(Fnv::hash(reinterpret_cast<const uint8_t*>(b), len));
    env->ReleaseByteArrayElements(arr, b, JNI_ABORT);
    return h;
}

jlong hashArrayCritical(JNIEnv* env, jclass, jbyteArray arr) {
    jsize len = env->GetArrayLength(arr);
    // GetPrimitiveArrayCritical avoids the copy but may pause the GC for its
    // duration — keep the critical region short and allocation-free.
    void* b = env->GetPrimitiveArrayCritical(arr, nullptr);
    if (!b) return 0;
    jlong h = static_cast<jlong>(Fnv::hash(static_cast<const uint8_t*>(b), len));
    env->ReleasePrimitiveArrayCritical(arr, b, JNI_ABORT);
    return h;
}

// --- address paths: zero-copy ------------------------------------------------

jlong hashAddr(JNIEnv*, jclass, jlong addr, jlong len) {
    return static_cast<jlong>(Fnv::hash(as_ptr(addr), static_cast<size_t>(len)));
}

void transformAddr(JNIEnv*, jclass, jlong addr, jlong len, jbyte add) {
    auto* p = reinterpret_cast<uint8_t*>(addr);
    uint8_t a = static_cast<uint8_t>(add);
    for (jlong i = 0; i < len; ++i) p[i] = static_cast<uint8_t>(p[i] + a);
}

jlongArray hashBatch(JNIEnv* env, jclass, jlongArray addrs, jlongArray lens) {
    jsize n = env->GetArrayLength(addrs);
    jlong* a = env->GetLongArrayElements(addrs, nullptr);
    jlong* l = env->GetLongArrayElements(lens, nullptr);
    jlongArray out = env->NewLongArray(n);
    if (a && l && out) {
        jlong* o = env->GetLongArrayElements(out, nullptr);
        for (jsize i = 0; i < n; ++i)
            o[i] = static_cast<jlong>(Fnv::hash(as_ptr(a[i]), static_cast<size_t>(l[i])));
        env->ReleaseLongArrayElements(out, o, 0); // commit
    }
    if (a) env->ReleaseLongArrayElements(addrs, a, JNI_ABORT);
    if (l) env->ReleaseLongArrayElements(lens, l, JNI_ABORT);
    return out;
}

// --- streaming handle --------------------------------------------------------

jlong sCreate(JNIEnv*, jclass) { return reinterpret_cast<jlong>(new Fnv()); }
void  sFree(JNIEnv*, jclass, jlong h) { delete reinterpret_cast<Fnv*>(h); }
void  sUpdate(JNIEnv*, jclass, jlong h, jlong addr, jlong len) {
    reinterpret_cast<Fnv*>(h)->update(as_ptr(addr), static_cast<size_t>(len));
}
jlong sDigest(JNIEnv*, jclass, jlong h) {
    return static_cast<jlong>(reinterpret_cast<Fnv*>(h)->digest());
}
void  sReset(JNIEnv*, jclass, jlong h) { reinterpret_cast<Fnv*>(h)->reset(); }

const JNINativeMethod METHODS[] = {
    {const_cast<char*>("nHashArray"),         const_cast<char*>("([B)J"),    reinterpret_cast<void*>(hashArray)},
    {const_cast<char*>("nHashArrayCritical"), const_cast<char*>("([B)J"),    reinterpret_cast<void*>(hashArrayCritical)},
    {const_cast<char*>("nHashAddr"),          const_cast<char*>("(JJ)J"),    reinterpret_cast<void*>(hashAddr)},
    {const_cast<char*>("nTransform"),         const_cast<char*>("(JJB)V"),   reinterpret_cast<void*>(transformAddr)},
    {const_cast<char*>("nHashBatch"),         const_cast<char*>("([J[J)[J"), reinterpret_cast<void*>(hashBatch)},
    {const_cast<char*>("nsCreate"),           const_cast<char*>("()J"),      reinterpret_cast<void*>(sCreate)},
    {const_cast<char*>("nsFree"),             const_cast<char*>("(J)V"),     reinterpret_cast<void*>(sFree)},
    {const_cast<char*>("nsUpdate"),           const_cast<char*>("(JJJ)V"),   reinterpret_cast<void*>(sUpdate)},
    {const_cast<char*>("nsDigest"),           const_cast<char*>("(J)J"),     reinterpret_cast<void*>(sDigest)},
    {const_cast<char*>("nsReset"),            const_cast<char*>("(J)V"),     reinterpret_cast<void*>(sReset)},
};

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK) return -1;
    jclass cls = env->FindClass("dev/sweety/jni/CppNatives");
    if (!cls) return -1;
    if (env->RegisterNatives(cls, METHODS, sizeof(METHODS) / sizeof(METHODS[0])) < 0)
        return -1;
    return JNI_VERSION_1_8;
}
