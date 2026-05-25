# jni-template

A small, reusable **library for calling native code from the JVM** through **one
API that runs on either binding** — classic **JNI** or the modern **Foreign
Function & Memory API (FFM / Panama)** — against either of two native backends
(**C++** and **Rust**). It ships the low-overhead patterns you actually want when
crossing into native code, plus JMH benchmarks that measure the trade-offs.

The example workload is **FNV-1a 64-bit hashing** (dependency-free, CPU-bound) plus
a memory-bound `transform`. Swap the native core for your own logic; keep the wiring.

## One API, two bindings, two backends

```java
HashEngine e = HashEngine.of(Binding.FFM, Backend.RUST); // or JNI / CPP
long h = e.hash(segment, len);          // zero-copy over a MemorySegment
try (HashSession s = e.open()) {        // pooled streaming digest
    s.update(segment, len);
    long d = s.digest();
}
long[] hs = e.hashBatch(segs, lens);    // one crossing for N payloads
e.transform(segment, len, (byte) 1);    // memory-bound, in-place
```

- **JNI binding** (`dev.sweety.jni`): native methods bound via **RegisterNatives** in
  `JNI_OnLoad` (no name-based lookup). Zero-copy paths pass a `MemorySegment`'s native
  address as a `long`. Includes a `GetPrimitiveArrayCritical` path for heap `byte[]`.
- **FFM binding** (`dev.sweety.ffm`): the library is linked by file path; each C-ABI
  symbol becomes a cached `MethodHandle` invoked with `invokeExact`. No JNI stubs.

Both bindings call the **same flat C-ABI core** exported by each native lib
(`nat_fnv_*`, `nat_transform`), so C++ and Rust stay bit-identical and reusable from
either binding.

## Overhead / GC reduction toolkit

| Technique | Where | Purpose |
|---|---|---|
| Buffer/segment pool | `dev.sweety.mem.SegmentPool` | reuse off-heap memory; no malloc/free or GC garbage per call |
| Object pooling | `HashSession` via `dev.sweety.pool.ObjectPool` | recycle native handles + wrappers instead of create/destroy |
| RegisterNatives | native `JNI_OnLoad` | explicit binding, no per-call name resolution |
| Critical array access | `JniHashEngine#hashCritical` | zero-copy heap `byte[]` (with GC-pin caveat) |
| Batch API | `hashBatch` | amortize the crossing across N payloads |

`ObjectPool` (thread-local / shared) and the `@Acquire/@Release/@Borrows/@Pooled`
ownership annotations mirror `dev.sweety.math.pool` / `dev.sweety.data.buffer`, copied
in so the template stays self-contained.

## Build, run, benchmark

Prerequisites: **JDK 25**, **CMake** + a C++ toolchain, **Rust/cargo** (optional — the
Rust backend is skipped if cargo is absent; the Gradle toolchain auto-provisions the JDK
via the foojay resolver).

```sh
./gradlew run     # exercises every available Binding x Backend, asserts parity
./gradlew test    # JUnit parity check across bindings/backends
./gradlew jmh      # full benchmark suite
./gradlew jmh -Pjmh.includes=CrossingBench   # one benchmark
```

Benchmarks (`src/jmh/java/dev/sweety/bench/`): `CrossingBench` (per-call overhead),
`ThroughputBench` (large payload), `BatchBench` (batch vs loop), `ArrayAccessBench`
(copy vs critical vs segment), `AllocBench` (pooled vs fresh arena; run with `-prof gc`).

## Adapting

1. Replace the FNV core (`native/cpp/include/fnv.hpp`, `native/rust/src/fnv.rs`) and the
   C-ABI surface (`cabi.cpp` / `cabi.rs`).
2. Mirror the new symbols in the JNI bridge (`jni_hashengine.*`, the RegisterNatives
   table) and the FFM `MethodHandle`s (`FfmHashEngine`).
3. Update the `HashEngine` interface to your operations; keep `ObjectPool` / `SegmentPool`.
