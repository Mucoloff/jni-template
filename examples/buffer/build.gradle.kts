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

// Example: a native API over off-heap buffers (@Ptr MemorySegment). Exercises the
// pointer lowering (JNI long address / FFM ADDRESS) for an arbitrary spec, again with
// no processor changes. Demonstrates binding generation + compilation.
