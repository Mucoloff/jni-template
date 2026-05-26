// Hand-written native core for the buffer example: off-heap buffer ops over void*
// pointers (the generated thunks pass MemorySegment addresses as void*).
#include <cstddef>
#include <cstdint>

extern "C" {
void nat_fill(void *buf, size_t len, uint8_t v) {
    auto *p = static_cast<uint8_t *>(buf);
    for (size_t i = 0; i < len; ++i) p[i] = v;
}

uint64_t nat_sum(void *buf, size_t len) {
    auto *p = static_cast<const uint8_t *>(buf);
    uint64_t s = 0;
    for (size_t i = 0; i < len; ++i) s += p[i];
    return s;
}

void nat_copy(void *dst, void *src, size_t len) {
    auto *d = static_cast<uint8_t *>(dst);
    auto *s = static_cast<const uint8_t *>(src);
    for (size_t i = 0; i < len; ++i) d[i] = s[i];
}
}
