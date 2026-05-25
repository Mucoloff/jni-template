#include <jni.h>
#include <cstdlib>
#include <cstdint>

extern "C" {

static void do_process(JNIEnv* env, jobject buffer, jint len) {
    void* ptr = env->GetDirectBufferAddress(buffer);
    if (!ptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                      "not a direct buffer");
        return;
    }
    auto* data = static_cast<uint8_t*>(ptr);
    for (int i = 0; i < len; ++i) data[i] ^= 0xFF;
}

static jobject do_allocate(JNIEnv* env, jint size) {
    void* mem = std::malloc(static_cast<size_t>(size));
    if (!mem) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "malloc failed");
        return nullptr;
    }
    return env->NewDirectByteBuffer(mem, size);
}

static void do_free(JNIEnv* env, jobject buffer) {
    void* ptr = env->GetDirectBufferAddress(buffer);
    if (ptr) std::free(ptr);
}

JNIEXPORT void JNICALL Java_dev_sweety_Buffer_process(
        JNIEnv* env, jclass, jobject buffer, jint len) {
    do_process(env, buffer, len);
}

JNIEXPORT jobject JNICALL Java_dev_sweety_Buffer_allocate(
        JNIEnv* env, jclass, jint size) {
    return do_allocate(env, size);
}

JNIEXPORT void JNICALL Java_dev_sweety_Buffer_free(
        JNIEnv* env, jclass, jobject buffer) {
    do_free(env, buffer);
}

}
