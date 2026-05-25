#pragma once
#ifndef FNV_HPP
#define FNV_HPP

#include <cstddef>
#include <cstdint>

// FNV-1a 64-bit hash. Tiny, dependency-free, CPU-bound — a realistic reason to
// drop into native code. Used by all three JNI bridges (Checksum, NativeBuffer,
// Hasher) so the C++ and Rust backends share one definition.
struct Fnv {
    static constexpr uint64_t OFFSET_BASIS = 0xcbf29ce484222325ULL;
    static constexpr uint64_t PRIME        = 0x100000001b3ULL;

    uint64_t state = OFFSET_BASIS;

    // Incremental update — feed a chunk of bytes into the running hash.
    void update(const uint8_t* data, size_t len) {
        uint64_t h = state;
        for (size_t i = 0; i < len; ++i) {
            h ^= data[i];
            h *= PRIME;
        }
        state = h;
    }

    uint64_t digest() const { return state; }

    void reset() { state = OFFSET_BASIS; }

    // One-shot convenience: hash a buffer in a single call.
    static uint64_t hash(const uint8_t* data, size_t len) {
        Fnv f;
        f.update(data, len);
        return f.digest();
    }
};

#endif
