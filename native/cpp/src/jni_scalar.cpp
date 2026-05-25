#include <jni.h>
#include "engine.hpp"

extern "C" {

JNIEXPORT jint JNICALL Java_dev_sweety_Scalar_sum(
        JNIEnv*, jclass, jint a, jint b) {
    return Engine::sum(a, b);
}

JNIEXPORT jint JNICALL Java_dev_sweety_Scalar_subtract(
        JNIEnv*, jclass, jint a, jint b) {
    return Engine::subtract(a, b);
}

}
