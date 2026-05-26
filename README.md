# nativegen

A small **framework for calling native code from the JVM** that generates the binding
boilerplate — both sides of the boundary — from **one annotated interface**:

- **JVM side**: JNI bindings *and* FFM (Panama) bindings, in Java/Kotlin.
- **Native side**: the JNI thunks, the `RegisterNatives` table + `JNI_OnLoad`, and (by
  convention) the flat C-ABI lifecycle, in **C++ and Rust**.

You declare the native surface once; the framework generates the marshalling boundary for
both bindings and both languages. **You write only the native algorithm** — the framework
never generates that (the body *is* your logic).

It's a framework, not a template: the core processor knows nothing about any specific API.
Project-specific marshalling shapes plug in via an SPI. The repo ships three independent
example specs proving exactly that.

## Modules

| Module | Role |
|---|---|
| `:annotations` | the spec annotations — `@NativeApi`, `@Jni`, `@Cabi`, `@Ptr`, `@Marshal`, `@Strategy`, `@Engine`, `@Core` |
| `:nativegen-spi` | stable IR (`NativeMethod`/`NativeType`) + extension SPI (`MarshalStrategy`) |
| `:processor` | the KSP processor: spec → JVM bindings + native descriptor; discovers plugins via `ServiceLoader` |
| `:nativegen-runtime` | runtime support: `Binding`, `Backend`, `NativeLib`, `pool/*`, `mem/*` |
| `:fnv-plugin` | example plugin: the `"heap"` / `"batch"` engine strategies |
| `:examples:hash` | full runnable demo — FNV-1a over JNI×FFM × C++×Rust |
| `:examples:mathops`, `:examples:buffer` | further specs proving the codegen generalizes |
| `:native:cpp`, `:native:rust` | the hash example's native cores + generated boundary |

## How it works

Declare the native surface at the `MemorySegment`/primitive level on a `@NativeApi`
interface. `@Ptr` marks a pointer (→ `long` address under JNI, `ADDRESS` under FFM);
`@Jni(thunk=…)` names the JNI symbol; `@Cabi(…)` names the flat C-ABI symbol used by FFM.

```java
@NativeApi
interface MathNative {
    @Jni(thunk = "jni_add") @Cabi("nat_add") long add(long a, long b);
    @Jni(thunk = "jni_fill") @Cabi("nat_fill") void fill(@Ptr MemorySegment buf, long len, byte v);
}
```

The KSP processor generates, from that alone:

- **JVM**: `RawNatives` + per-backend holders (`CppNatives`/`RustNatives`), `JniBindings`
  (segment↔address glue), `FfmBindings` (cached downcall handles + `invokeExact`), and a
  JSON descriptor `build/generated/native-api.json`.
- **Native** (emitted by the processor too): the `jni_*` thunks (which route through the
  C-ABI), the `RegisterNatives` table + `JNI_OnLoad`, and — for methods marked `@Core` — the
  flat C-ABI lifecycle bodies, in **both C++ and Rust**. Hand-written: the algorithm core
  and any loop C-ABI (e.g. `transform`, `batch`).

Signatures are validated at compile time (`@Ptr` only on `MemorySegment`; FFM methods take
no arrays).

### Ergonomic layer (optional, `@Engine`)

`@Engine` additionally generates a public engine: a generic base (`DIRECT` 1:1 delegates +
a pooled `SESSION`), the concrete per-binding impls, and an `EngineFactory`. See
`:examples:hash` (`HashEngine`/`HashSession`).

### Extending: custom marshalling (`@Strategy` + SPI)

The core handles only the generic `@Marshal` strategies (`DIRECT`, `SESSION_*`). For a
project-specific argument shape, tag the method `@Strategy(id = "…")` and implement
`dev.sweety.nativegen.spi.MarshalStrategy` in a module on the `ksp(…)` classpath, registered
via `META-INF/services`. The processor looks it up by id — **no core change**. `:fnv-plugin`
does this for `"heap"` (byte[] convenience) and `"batch"`.

## Use it for a new project

1. Add a `@NativeApi` interface declaring your native functions (`@Jni`/`@Cabi`/`@Ptr`).
2. Depend on `:annotations` + `:nativegen-runtime`, and `ksp(:processor)` (+ any strategy
   plugin). Pass `ksp { arg("native.descriptor", …) }` if you want the native descriptor.
3. Write your native core + C-ABI; the JNI thunks/registration regenerate from the spec.
4. Optionally add `@Engine` for an ergonomic API, and `@Strategy` + a plugin for custom shapes.

`:examples:mathops` and `:examples:buffer` are minimal specs that generate JNI+FFM bindings
with **zero** processor changes — copy one as a starting point.

## Build & run

Prerequisites: **JDK 25**, **CMake** + a C++ toolchain, optional **Rust/cargo** (its backend
is skipped if cargo is absent).

```sh
./gradlew build --no-parallel              # whole project (see note)
./gradlew :examples:hash:run               # every available Binding x Backend, asserts parity
./gradlew :examples:hash:run -Djni.backend=rust
./gradlew :examples:hash:test              # JUnit parity check
./gradlew :examples:hash:jmh -PjmhInclude=CrossingBench
```

> Note: build with `--no-parallel` — KSP2's analysis-API worker can clash when many `ksp`
> tasks run concurrently.

## Status / boundaries

- The framework generates the **boundary** (JVM bindings + JNI thunks + registration +
  C-ABI lifecycle). It does **not** generate the native algorithm — that's yours.
- Both the JVM and the **native** (C++ *and* Rust) code are emitted by the KSP processor:
  PLAIN thunks + lifecycle + registration are built in; custom thunk shapes plug in via the
  `NativeShape` SPI (the native counterpart of `MarshalStrategy`). The native build scripts
  just compile the generated sources (no `genCppNative`/`build.rs` codegen).
- `mathops`/`buffer` demonstrate binding generation + compilation; running them would need
  their own native libs (per-example native build is future work).
