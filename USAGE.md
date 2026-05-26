# Using nativegen in a project

`nativegen` generates the JNI **and** FFM binding boilerplate — JVM side *and* the C++/Rust
JNI thunks — from one annotated interface. You write the spec + the native algorithm; the
`dev.sweety.nativegen` Gradle plugin wires the rest (KSP, deps, native builds, JVM args).

## Prerequisites

- **JDK 25** (the Gradle toolchain auto-provisions it).
- **CMake** + a C++ toolchain (for the C++ backend).
- **Rust / cargo** (optional — the Rust backend is skipped if cargo is absent).

## 1. Make the framework available

The framework artifacts are `dev.sweety.nativegen:{annotations,nativegen-runtime,nativegen-spi,processor,fnv-plugin}:0.1.0`
and the Gradle plugin `dev.sweety.nativegen`.

**Local (recommended for now):** from this repo, publish to your local Maven repo:

```sh
./gradlew publishToMavenLocal          # framework modules
./gradlew -p build-logic publishToMavenLocal   # the Gradle plugin
```

In the **consumer** `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories { mavenLocal(); gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositories { mavenLocal(); mavenCentral(); maven("https://jitpack.io") }
}
```

> JitPack: depend on `com.github.Mucoloff.jni-ffm-api:<module>:<tag>`; for the plugin add a
> `resolutionStrategy.eachPlugin { if (requested.id.id == "dev.sweety.nativegen") useModule(...) }`.

## 2. Apply the plugin

`build.gradle.kts`:

```kotlin
plugins {
    application
    id("dev.sweety.nativegen") version "0.1.0"
}

nativegen {
    coreType = "Fnv"   // core type for generated @Core C-ABI bodies (optional)
    cpp = true          // build native/cpp via CMake
    rust = true         // build native/rust via Cargo
}

application { mainClass.set("com.acme.Main") }

// only if you use custom @Strategy marshalling:
// dependencies { ksp("dev.sweety.nativegen:fnv-plugin:0.1.0") }
```

That's the whole build wiring — the plugin adds the framework deps, runs KSP, builds the
native libs into `build/natives`, and sets `-Djava.library.path` + `--enable-native-access`
on `run`/`test`.

## 3. Declare the native surface

`src/main/java/com/acme/MathNative.java`:

```java
@NativeApi
interface MathNative {
    @Jni(thunk = "jni_add") @Cabi("nat_add") long add(long a, long b);
    @Jni(thunk = "jni_fill") @Cabi("nat_fill") void fill(@Ptr MemorySegment buf, long len, byte v);
}
```

`@Ptr MemorySegment` → `long` address (JNI) / `ADDRESS` (FFM). KSP generates `JniBindings`,
`FfmBindings`, the per-backend holders, and the native thunks/registration.

## 4. Write the native core (the only hand-written native code)

**C++** — `native/cpp/src/cabi.cpp` (the flat C-ABI), `native/cpp/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22)
project(native_cpp LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
find_package(JNI REQUIRED)
add_library(native_cpp SHARED
    src/cabi.cpp
    generated/native.generated.cpp        # written by the processor
)
target_include_directories(native_cpp PRIVATE ${JNI_INCLUDE_DIRS})
```

**Rust** — `native/rust/Cargo.toml` (`crate-type=["cdylib"]`, `name="native_rust"`, `jni="0.21"`),
`native/rust/src/cabi.rs` (the C-ABI), and `native/rust/src/lib.rs`:

```rust
mod cabi;
include!("generated/native_generated.rs");   // written by the processor
```

Implement `nat_*` (and, if not using `@Core`, the bodies) in `cabi.{cpp,rs}`. Gitignore the
generated dirs (`native/cpp/generated/`, `native/rust/src/generated/`, `*/cmake-build`, `target/`).

## 5. Run

```sh
./gradlew run                 # builds native libs, runs both bindings on both backends
./gradlew test
./gradlew build --no-parallel  # see note
```

A `Main` typically does `new FfmBindings(Backend.CPP).add(2, 3)` or
`JniBindings.of(Backend.RUST)`. For an ergonomic API, add `@Engine` to the spec (generates a
pooled engine + factory — see `:examples:hash`).

## Optional: ergonomics & custom shapes

- **`@Engine`**: generates a public engine interface impl (DIRECT delegates + pooled `@Session`)
  + an `EngineFactory`. See `examples/hash` (`HashEngine`/`HashSession`).
- **`@Strategy(id="…")` + `MarshalStrategy`/`NativeShape` SPI**: for argument shapes the core
  doesn't know (heap `byte[]`, batch). Implement the SPI in a module on the `ksp(...)` classpath
  (registered via `META-INF/services`). See `:fnv-plugin`.

## Examples in this repo

| Example | Setup | Shows |
|---|---|---|
| `examples/mathops` | plugin (`~7` lines) | primitives, FFM+JNI × C+++Rust |
| `examples/buffer`  | plugin | `@Ptr` buffers, FFM+JNI × C+++Rust |
| `examples/hash`    | manual wiring | full: `@Engine`, `@Strategy` plugin, jmh, parity tests |

## Troubleshooting

- Build with `--no-parallel`: KSP2's analysis-API worker can clash with many parallel `ksp` tasks.
- Plugin pins **Kotlin 2.3.20 / KSP 2.3.8**; align your project.
- `cargo` absent → Rust backend skipped (C++ still runs).
