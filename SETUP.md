# Setting up a nativegen project from scratch

This walks through creating a brand-new Gradle project that uses the `dev.sweety.nativegen`
plugin to generate JNI **and** FFM bindings (JVM side + the C++/Rust JNI thunks) from a single
annotated interface. You write the spec and the native algorithm; the plugin wires KSP, the
framework dependencies, the native builds, and the JVM args.

## Prerequisites

- **JDK 25** (the Gradle toolchain auto-provisions it; you still need a JDK to launch Gradle).
- **CMake** + a C++ toolchain — required for the C++ backend.
- **Rust / cargo** — optional; the Rust backend is skipped if cargo is absent.
- **Gradle 8.x+** (or the wrapper).

## 1. Create the project skeleton

```sh
mkdir my-native-app && cd my-native-app
gradle init --type basic --dsl kotlin   # or copy an existing wrapper
mkdir -p src/main/java/com/acme
```

You want this layout:

```
my-native-app/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── src/main/java/com/acme/
    ├── Main.java
    └── MathNative.java
```

## 2. Make the framework available

Pick **one** of the two delivery channels.

### Option A — JitPack (no local publish, recommended)

JitPack builds the framework from a git tag and serves it under the group
`com.github.Mucoloff.jni-ffm-api`. The plugin is resolved from the same group via
`resolutionStrategy`.

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        maven("https://jitpack.io")
        gradlePluginPortal()
        mavenCentral()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "dev.sweety.nativegen")
                useModule("com.github.Mucoloff.jni-ffm-api:build-logic:${requested.version}")
        }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "my-native-app"
```

`gradle.properties` (JitPack rewrites the group, so pointing the framework coords at it is
required):

```properties
nativegen.group=com.github.Mucoloff.jni-ffm-api
nativegen.version=v0.1.4
```

### Option B — Maven Local (for framework development)

From a checkout of the `jni-ffm-api` repo, publish everything to your local Maven repo:

```sh
./gradlew publishToMavenLocal               # framework modules
./gradlew -p build-logic publishToMavenLocal # the Gradle plugin
```

Then in the consumer `settings.gradle.kts` add `mavenLocal()` to both repository blocks:

```kotlin
pluginManagement {
    repositories { mavenLocal(); gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositories { mavenLocal(); mavenCentral(); maven("https://jitpack.io") }
}
```

With Option B the coords stay on the default group — leave `nativegen.group` unset and use the
plain version (e.g. `0.1.4`).

## 3. Apply the plugin

`build.gradle.kts`:

```kotlin
plugins {
    application
    id("dev.sweety.nativegen") version "v0.1.4"  // base: KSP + framework deps + native build + JVM args
    id("dev.sweety.nativegen.cpp")               // C++ backend (CMake)  — apply only what you need
    id("dev.sweety.nativegen.rust")              // Rust backend (Cargo)
}

application {
    mainClass.set("com.acme.Main")
}
```

(For Option B / Maven Local, use the plain version: `version "0.1.4"`.)

That is the entire build wiring. The base plugin **automatically adds these dependencies** — you
do not declare them yourself:

- `dev.sweety.nativegen:annotations` — the `@NativeApi`, `@Jni`, `@Cabi`, `@Ptr`, … annotations
- `dev.sweety.nativegen:nativegen-runtime` — `Backend`, `Binding`, `NativeLib`, the pool/arena
- `dev.sweety.nativegen:processor` — the KSP processor (on the `ksp` configuration)

The only dependency you add **by hand** is a custom marshalling plugin, and only if you use
`@Strategy` (see the optional section):

```kotlin
// dependencies { ksp("dev.sweety.nativegen:fnv-plugin:0.1.4") }
```

## 4. Declare the native surface

`src/main/java/com/acme/MathNative.java` — one interface is the single source of truth:

```java
package com.acme;

import dev.sweety.nativeapi.*;
import java.lang.foreign.MemorySegment;

@NativeApi
interface MathNative {
    @Jni(thunk = "jni_add") @Cabi("nat_add")
    long add(long a, long b);

    @Jni(thunk = "jni_fill") @Cabi("nat_fill")
    void fill(@Ptr MemorySegment buf, long len, byte v);
}
```

- `@Jni(thunk=...)` → the native symbol implementing the JNI side; generates the holder classes,
  the JSON descriptor for `RegisterNatives`, and `JniBindings`.
- `@Cabi("...")` → the flat C-ABI symbol; generates `FfmBindings` (cached downcall handles).
- `@Ptr MemorySegment` lowers to a `long` address (JNI) / `ADDRESS` layout (FFM).
- A method with both annotations is callable through either binding; the processor also emits a
  shared `Bindings` interface over the JNI∩CABI intersection plus a
  `Bindings.of(Binding, Backend)` factory.

The native **core type** for any `@Core` lifecycle bodies is set on the annotation:
`@NativeApi(coreType = "Fnv")`.

## 5. Scaffold the native core

```sh
./gradlew scaffoldNative
```

Generates the hand-written native skeleton (idempotent — never overwrites existing files):

- `native/cpp/CMakeLists.txt`, `native/cpp/src/cabi.cpp`
- `native/rust/Cargo.toml`, `native/rust/src/lib.rs`, `native/rust/src/cabi.rs`

Each gets a **signed stub** for every flat C-ABI symbol the processor does *not* generate (the
non-`@Core` ones), e.g.:

```cpp
uint64_t nat_add(size_t p0, size_t p1) { /* TODO */ return 0; }
```

**You only fill in the bodies.** The JNI thunks, `RegisterNatives`, `JNI_OnLoad`, and (for
`@Core` methods) the C-ABI lifecycle are all generated.

Gitignore the generated dirs:

```gitignore
native/cpp/generated/
native/rust/src/generated/
native/cpp/cmake-build/
native/rust/target/
```

## 6. Write Main and run

`src/main/java/com/acme/Main.java`:

```java
package com.acme;

import dev.sweety.Backend;
import dev.sweety.Binding;

public class Main {
    public static void main(String[] args) {
        for (Backend b : Backend.values()) {
            Bindings ffm = Bindings.of(Binding.FFM, b);
            Bindings jni = Bindings.of(Binding.JNI, b);
            System.out.printf("%s FFM add=%d  JNI add=%d%n", b, ffm.add(2, 3), jni.add(2, 3));
        }
    }
}
```

Then:

```sh
./gradlew run                  # builds native libs, runs both bindings on both backends
./gradlew test
./gradlew build --no-parallel  # see Troubleshooting
```

The plugin sets `-Djava.library.path` (pointing at `build/natives`) and
`--enable-native-access` on `run`/`test` for you.

## 7. (Optional) Ergonomics and custom shapes

- **`@Engine`** on the spec generates a public engine interface impl (DIRECT delegates + a pooled
  `@Session`) plus an `EngineFactory.of(Binding, Backend)`. See `examples/hash`
  (`HashEngine` / `HashSession`).
- **`@Strategy(id="…")` + the `MarshalStrategy` / `NativeShape` SPI** handle argument shapes the
  core doesn't know (heap `byte[]`, batch). Implement the SPI in a module on the `ksp(...)`
  classpath, registered via `META-INF/services`. See `:fnv-plugin`. This is the one case where
  you add a `ksp("…:fnv-plugin:0.1.4")` dependency manually.

## Troubleshooting

- Build with `--no-parallel`: KSP2's analysis-API worker can clash across many parallel `ksp`
  tasks.
- The plugin pins **Kotlin 2.3.20 / KSP 2.3.8** — align your project's Kotlin version.
- `cargo` absent → the Rust backend is skipped (C++ still runs).
- `scaffoldNative` is a one-time init. If a generated native file was deleted out-of-band,
  re-run with `--rerun-tasks` (or `clean`).
- `./gradlew tasks --group nativegen` lists the scaffold/build tasks.

## Reference: examples in the framework repo

| Example | Setup | Shows |
|---|---|---|
| `examples/mathops` | plugin (~7 lines) | primitives, FFM+JNI × C+++Rust, `Bindings.of` |
| `examples/buffer`  | plugin | `@Ptr` buffers, FFM+JNI × C+++Rust |
| `examples/hash`    | manual wiring | full: `@Engine`, `@Strategy` plugin, jmh, parity tests |
