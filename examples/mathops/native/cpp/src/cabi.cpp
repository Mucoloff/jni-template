// Hand-written native core for the mathops example: the flat C-ABI the generated
// JNI thunks (and the FFM downcalls) route through. Pointer-free, pure arithmetic.
#include <cstddef>
#include <cstdint>

extern "C" {
uint64_t nat_add(size_t a, size_t b) { return static_cast<uint64_t>(a + b); }
int32_t nat_imul(int32_t a, int32_t b) { return a * b; }
uint8_t nat_neg(uint8_t x) { return static_cast<uint8_t>(-static_cast<int>(x)); }
}
