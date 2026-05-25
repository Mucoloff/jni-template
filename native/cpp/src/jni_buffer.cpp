#include <jni.h>
#include <cstdlib>
#include <cstdint>
#include "fnv.hpp"

// Direct-buffer bridge: native memory ownership + zero-copy hashing.
extern "C" {

JNIEXPORT jlong JNICALL Java_dev_sweety_NativeBuffer_hash(
        JNIEnv* env, jclass, jobject buffer, jint len) {
    void* ptr = env->GetDirectBufferAddress(buffer);
    if (!ptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                      "not a direct buffer");
        return 0;
    }
    return static_cast<jlong>(
            Fnv::hash(static_cast<const uint8_t*>(ptr), static_cast<size_t>(len)));
}

JNIEXPORT jobject JNICALL Java_dev_sweety_NativeBuffer_allocate(
        JNIEnv* env, jclass, jint size) {
    void* mem = std::malloc(static_cast<size_t>(size));
    if (!mem) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "malloc failed");
        return nullptr;
    }
    return env->NewDirectByteBuffer(mem, size);
}

JNIEXPORT void JNICALL Java_dev_sweety_NativeBuffer_free(
        JNIEnv* env, jclass, jobject buffer) {
    void* ptr = env->GetDirectBufferAddress(buffer);
    if (ptr) std::free(ptr);
}

}
