#include <jni.h>
#include "engine.hpp"

extern "C" {

JNIEXPORT jlong JNICALL Java_dev_sweety_Engine_create(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new Engine());
}

JNIEXPORT void JNICALL Java_dev_sweety_Engine_destroy(JNIEnv*, jclass, jlong h) {
    delete reinterpret_cast<Engine*>(h);
}

JNIEXPORT jint JNICALL Java_dev_sweety_Engine_sum(JNIEnv*, jclass, jlong h, jint a, jint b) {
    return reinterpret_cast<Engine*>(h)->sum(a, b);
}

JNIEXPORT jint JNICALL Java_dev_sweety_Engine_subtract(JNIEnv*, jclass, jlong h, jint a, jint b) {
    return reinterpret_cast<Engine*>(h)->subtract(a, b);
}

}
