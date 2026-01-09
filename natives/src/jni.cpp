#include <jni.h>
#include "engine.h"
#include "dev_sweety_NativeApi.h"

extern "C" {
Engine engine;

JNIEXPORT jint JNICALL Java_dev_sweety_NativeApi_sum(JNIEnv* env, jobject thiz, jint a, jint b) {
    return static_cast<jint>(engine.sum(static_cast<int>(a), static_cast<int>(b)));
}
}

JNIEXPORT jint JNICALL Java_dev_sweety_NativeApi_subtract(JNIEnv*, jobject thiz, jint a, jint b) {
    return static_cast<jint>(engine.subtract(static_cast<int>(a), static_cast<int>(b)));
}
