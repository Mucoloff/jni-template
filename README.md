# jni-template

A multi-language **JNI template**: one Java/Kotlin frontend, two interchangeable
native backends (**C++** and **Rust**), switchable at runtime. It demonstrates the
three core JNI idioms on a single small, useful workload — **FNV-1a 64-bit hashing**
(dependency-free, CPU-bound, a realistic reason to call native code).

Clone it, replace the FNV core with your own native logic, keep the wiring.

## The three JNI patterns

| Class | Pattern | What it shows | Cost / when to use |
|---|---|---|---|
| `Checksum` | stateless static | `byte[]` crosses via `GetByteArrayElements` | simple; copies the array — fine for small inputs |
| `NativeBuffer` | direct buffer, **zero-copy** | hash off-heap memory in place via `GetDirectBufferAddress` | the real JNI win on large payloads; you own the memory (`allocate`/`free`) |
| `Hasher` | stateful handle (RAII) | opaque `long` pointer to a native object; incremental `update`/`digest` | when native state must persist across calls |

All three produce the **same hash** for the same bytes — and both backends agree.

## Layout

```
src/main/java/dev/sweety/    Checksum, NativeBuffer, Hasher, NativeLib, Main
src/main/kotlin/dev/sweety/  Backend (selector), Digest (interface)
native/cpp/                  fnv.hpp + jni_*.cpp, CMake build
native/rust/                 fnv.rs + *_jni/buffer/checksum, Cargo build
```

`NativeLib` + `Backend` are the template infra: they pick and load the library from
the `jni.backend` system property (`cpp` default, or `rust`). The native symbols
follow the JNI naming convention (`Java_dev_sweety_<Class>_<method>`), so no
`RegisterNatives` call is needed.

## Build & run

Prerequisites: **JDK 25**, **CMake** (for the C++ backend), **Rust/cargo** (optional —
the Rust build is skipped with a warning if cargo is absent).

```sh
./gradlew run                      # C++ backend (default)
./gradlew run -Djni.backend=rust   # Rust backend
```

Both print the three hashing paths, assert they agree, then run a throughput
benchmark over a 64 MiB off-heap buffer — run each backend to compare MiB/s.

If `cmake` is not on `PATH`: `./gradlew run -Pcmake=/path/to/cmake`.

## Adapting the template

1. Replace `Fnv` in `native/cpp/include/fnv.hpp` and `native/rust/src/fnv.rs` with your logic.
2. Adjust the JNI bridges (`jni_*.cpp`, `*.rs`) and matching `native` declarations in Java.
3. Keep `NativeLib`/`Backend` as-is, or drop one backend if you only need the other.
