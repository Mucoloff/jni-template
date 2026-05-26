plugins {
    kotlin("jvm") version "2.3.20"
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":nativegen-spi"))
}

// Example plugin: contributes the FNV-specific engine marshalling strategies
// ("heap", "batch") to the framework, discovered via ServiceLoader on the KSP
// classpath. Demonstrates extending the framework without touching its core.
