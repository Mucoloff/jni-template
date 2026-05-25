#include <jni.h>
#include "fnv.hpp"

// Stateless bridge: hash a Java byte[] in one call.
extern "C" {

JNIEXPORT jlong JNICALL Java_dev_sweety_Checksum_hash(
        JNIEnv* env, jclass, jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    // May copy the array into native memory (isCopy out-param omitted).
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return 0;
    uint64_t h = Fnv::hash(reinterpret_cast<const uint8_t*>(bytes),
                           static_cast<size_t>(len));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT); // read-only, no copy-back
    return static_cast<jlong>(h);
}

}
