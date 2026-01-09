#include <jni.h>
#include "engine.h"
#include "dev_sweety_NativeApi.h"

extern "C" {
JNIEXPORT jint JNICALL Java_dev_sweety_NativeApi_sum(JNIEnv* env, jobject thiz, const jint a, const jint b) {
    return Engine::sum(a, b);
}

JNIEXPORT jint JNICALL Java_dev_sweety_NativeApi_subtract(JNIEnv*, jobject thiz, const jint a, const jint b) {
    return Engine::subtract(a, b);
}
}
