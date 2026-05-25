#include <jni.h>
#include "fnv.hpp"

// Stateful-handle bridge: a heap Fnv referenced by an opaque jlong pointer.
extern "C" {

JNIEXPORT jlong JNICALL Java_dev_sweety_Hasher_create(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new Fnv());
}

JNIEXPORT void JNICALL Java_dev_sweety_Hasher_destroy(JNIEnv*, jclass, jlong h) {
    delete reinterpret_cast<Fnv*>(h);
}

JNIEXPORT void JNICALL Java_dev_sweety_Hasher_update(
        JNIEnv* env, jclass, jlong h, jbyteArray data, jint len) {
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return;
    reinterpret_cast<Fnv*>(h)->update(reinterpret_cast<const uint8_t*>(bytes),
                                      static_cast<size_t>(len));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}

JNIEXPORT jlong JNICALL Java_dev_sweety_Hasher_digest(JNIEnv*, jclass, jlong h) {
    return static_cast<jlong>(reinterpret_cast<Fnv*>(h)->digest());
}

JNIEXPORT void JNICALL Java_dev_sweety_Hasher_reset(JNIEnv*, jclass, jlong h) {
    reinterpret_cast<Fnv*>(h)->reset();
}

}
