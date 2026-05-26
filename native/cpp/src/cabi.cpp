// Hand-written flat C-ABI over the FNV core: only the parts whose body is a loop,
// not a 1:1 core delegation. The lifecycle/hash symbols (nat_fnv_new/free/update/
// digest/reset/hash) are generated into native.generated.cpp from native-api.json.
// Pointers are void* so the generated thunks can pass addresses uniformly; the FFM
// downcalls (ADDRESS layout) are unaffected.
#include <cstddef>
#include <cstdint>
#include "fnv.hpp"

extern "C" {

// Hash n buffers in a single call — amortizes the JVM<->native crossing cost.
    void nat_fnv_hash_batch(const void *ptrs, const void *lens, void *out, size_t n) {
        auto p = static_cast<const uint8_t *const *>(ptrs);
        auto l = static_cast<const size_t *>(lens);
        auto o = static_cast<uint64_t *>(out);
        for (size_t i = 0; i < n; ++i) o[i] = Fnv::hash(p[i], l[i]);
    }

    // Memory-bound, in-place transform: data[i] += add. Stresses bandwidth rather
    // than per-call overhead — useful to contrast with hashing in benchmarks.
    void nat_transform(void *data, size_t len, uint8_t add) {
        auto d = static_cast<uint8_t *>(data);
        for (size_t i = 0; i < len; ++i) d[i] = static_cast<uint8_t>(d[i] + add);
    }
}
