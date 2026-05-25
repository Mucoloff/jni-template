plugins {
    java
    application
    kotlin("jvm") version "2.1.10"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    targetCompatibility = JavaVersion.VERSION_23
    sourceCompatibility = JavaVersion.VERSION_23
}

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains:annotations:26.0.2")
}

application {
    mainClass.set("dev.sweety.Main")
}

val nativeOutputDir = layout.buildDirectory.dir("natives").get().asFile

tasks.named<JavaExec>("run") {
    dependsOn("buildNatives")
    doFirst {
        jvmArgs("-Djava.library.path=${nativeOutputDir.absolutePath}")
    }
}

tasks.register("buildNatives") {
    group = "build"
    description = "Build both native libraries (C++ and Rust)"
    dependsOn(":native:cpp:buildCpp", ":native:rust:buildRust")
}

tasks.register<Delete>("cleanNatives") {
    delete(nativeOutputDir)
}

subprojects {
    repositories {
        mavenCentral()
    }
}
