// JNI bridge for dev.sweety.jni.CppNatives.
//
// This file defines only the native function BODIES (the jni_* thunks). The
// RegisterNatives table (name + JNI signature + fn-ptr) is generated from
// native-api.json into registrations.generated.h, so it can never drift from the
// Java declarations. Zero-copy paths take a native address as jlong; the byte[]
// paths (copy vs critical) contrast against that.
#include <jni.h>
#include <cstdint>
#include "fnv.hpp"

namespace {
    const uint8_t *as_ptr(jlong addr) {
        return reinterpret_cast<const uint8_t *>(addr);
    }

    // --- byte[] paths: copy vs critical ------------------------------------------

    jlong jni_hash_array(JNIEnv *env, jclass, jbyteArray arr) {
        jsize len = env->GetArrayLength(arr);
        jbyte *b = env->GetByteArrayElements(arr, nullptr); // may copy
        if (!b) return 0;
        jlong h = static_cast<jlong>(Fnv::hash(reinterpret_cast<const uint8_t *>(b), len));
        env->ReleaseByteArrayElements(arr, b, JNI_ABORT);
        return h;
    }

    jlong jni_hash_array_crit(JNIEnv *env, jclass, jbyteArray arr) {
        jsize len = env->GetArrayLength(arr);
        // GetPrimitiveArrayCritical avoids the copy but may pause the GC for its
        // duration — keep the critical region short and allocation-free.
        void *b = env->GetPrimitiveArrayCritical(arr, nullptr);
        if (!b) return 0;
        jlong h = static_cast<jlong>(Fnv::hash(static_cast<const uint8_t *>(b), len));
        env->ReleasePrimitiveArrayCritical(arr, b, JNI_ABORT);
        return h;
    }

    // --- address paths: zero-copy ------------------------------------------------

    jlong jni_hash(JNIEnv *, jclass, jlong addr, jlong len) {
        return static_cast<jlong>(Fnv::hash(as_ptr(addr), static_cast<size_t>(len)));
    }

    void jni_transform(JNIEnv *, jclass, jlong addr, jlong len, jbyte add) {
        auto *p = reinterpret_cast<uint8_t *>(addr);
        uint8_t a = static_cast<uint8_t>(add);
        for (jlong i = 0; i < len; ++i) p[i] = static_cast<uint8_t>(p[i] + a);
    }

    jlongArray jni_hash_batch(JNIEnv *env, jclass, jlongArray addrs, jlongArray lens) {
        jsize n = env->GetArrayLength(addrs);
        jlong *a = env->GetLongArrayElements(addrs, nullptr);
        jlong *l = env->GetLongArrayElements(lens, nullptr);
        jlongArray out = env->NewLongArray(n);
        if (a && l && out) {
            jlong *o = env->GetLongArrayElements(out, nullptr);
            for (jsize i = 0; i < n; ++i)
                o[i] = static_cast<jlong>(Fnv::hash(as_ptr(a[i]), static_cast<size_t>(l[i])));
            env->ReleaseLongArrayElements(out, o, 0); // commit
        }
        if (a) env->ReleaseLongArrayElements(addrs, a, JNI_ABORT);
        if (l) env->ReleaseLongArrayElements(lens, l, JNI_ABORT);
        return out;
    }

    // --- streaming handle --------------------------------------------------------

    jlong jni_create(JNIEnv *, jclass) { return reinterpret_cast<jlong>(new Fnv()); }
    void jni_free(JNIEnv *, jclass, jlong h) { delete reinterpret_cast<Fnv *>(h); }

    void jni_update(JNIEnv *, jclass, jlong h, jlong addr, jlong len) {
        reinterpret_cast<Fnv *>(h)->update(as_ptr(addr), static_cast<size_t>(len));
    }

    jlong jni_digest(JNIEnv *, jclass, jlong h) {
        return static_cast<jlong>(reinterpret_cast<Fnv *>(h)->digest());
    }

    void jni_reset(JNIEnv *, jclass, jlong h) { reinterpret_cast<Fnv *>(h)->reset(); }

    // kHolderClass + kRegistrations[], generated from native-api.json.
#include "registrations.generated.h"
} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_24) != JNI_OK) return -1;
    jclass cls = env->FindClass(kHolderClass);
    if (!cls) return -1;
    if (env->RegisterNatives(cls, kRegistrations,
                             sizeof(kRegistrations) / sizeof(kRegistrations[0])) < 0)
        return -1;
    return JNI_VERSION_24;
}
