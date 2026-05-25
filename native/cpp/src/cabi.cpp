// Flat C-ABI surface over the FNV core. These symbols carry no JNI mangling, so
// they can be called two ways: by the JNI bridges (jni_hashengine.cpp) and
// directly by the JVM's Foreign Function & Memory API (FfmHashEngine). One
// native implementation, two bindings.
#include <cstddef>
#include <cstdint>
#include "fnv.hpp"

extern "C" {

uint64_t nat_fnv_hash(const uint8_t* data, size_t len) {
    return Fnv::hash(data, len);
}

void* nat_fnv_new() { return new Fnv(); }

void nat_fnv_update(void* h, const uint8_t* data, size_t len) {
    static_cast<Fnv*>(h)->update(data, len);
}

uint64_t nat_fnv_digest(const void* h) {
    return static_cast<const Fnv*>(h)->digest();
}

void nat_fnv_reset(void* h) { static_cast<Fnv*>(h)->reset(); }

void nat_fnv_free(void* h) { delete static_cast<Fnv*>(h); }

// Hash n buffers in a single call — amortizes the JVM<->native crossing cost.
void nat_fnv_hash_batch(const uint8_t* const* ptrs, const size_t* lens,
                        uint64_t* out, size_t n) {
    for (size_t i = 0; i < n; ++i) out[i] = Fnv::hash(ptrs[i], lens[i]);
}

// Memory-bound, in-place transform: data[i] += add. Stresses bandwidth rather
// than per-call overhead — useful to contrast with hashing in benchmarks.
void nat_transform(uint8_t* data, size_t len, uint8_t add) {
    for (size_t i = 0; i < len; ++i) data[i] = static_cast<uint8_t>(data[i] + add);
}

}
