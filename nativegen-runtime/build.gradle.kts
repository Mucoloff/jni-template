plugins {
    `java-library`
    kotlin("jvm") version "2.3.20"
}

kotlin {
    jvmToolchain(25)
}

// Runtime support shared by every project using the framework: binding/backend
// selection, native library loading, off-heap memory helpers and object pooling.
// No project-specific (e.g. FNV) code lives here.
