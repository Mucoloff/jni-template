plugins {
    `java-library`
    kotlin("jvm") version "2.3.20"
    id("com.google.devtools.ksp") version "2.3.8"
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":annotations"))
    implementation(project(":nativegen-runtime"))
    ksp(project(":processor"))
}

// Example: a non-hash native API of pure primitives. Proves the framework generates
// JNI + FFM bindings for an arbitrary @NativeApi with ZERO changes to the processor.
// (Running it needs a native lib implementing nat_*/jni_* — out of scope here; this
// module demonstrates binding generation + compilation.)
