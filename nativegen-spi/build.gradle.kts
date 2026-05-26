plugins {
    kotlin("jvm") version "2.3.20"
    id("maven-publish")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // The IR exposes the annotation instances (@Jni/@Cabi/...) it was parsed from.
    api(project(":annotations"))
}

publishing {
    publications {
        create<MavenPublication>("maven") { from(components["java"]) }
    }
}

// Stable intermediate representation + extension SPI shared between the core
// processor and any strategy/emitter plugins. No KSP dependency here.
