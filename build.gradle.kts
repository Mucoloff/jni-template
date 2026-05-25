plugins {
    java
    application
    kotlin("jvm") version "2.2.0"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    // Kotlin 2.2 caps its JVM target at 24, so emit 24 bytecode on both sides
    // to keep compileJava and compileKotlin consistent.
    sourceCompatibility = JavaVersion.VERSION_24
    targetCompatibility = JavaVersion.VERSION_24
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_24)
    }
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
    System.getProperty("jni.backend")?.let { systemProperty("jni.backend", it) }
    doFirst {
        jvmArgs(
            "-Djava.library.path=${nativeOutputDir.absolutePath}",
            "--enable-native-access=ALL-UNNAMED"
        )
    }
}

tasks.register("buildNatives") {
    group = "build"
    description = "Build both native libraries (C++ and Rust)"
    dependsOn(":native:cpp:buildCpp", ":native:rust:buildRust")
}

tasks.register<Delete>("cleanNatives") {
    description = "Clean the native output directory"
    delete(nativeOutputDir)
}

subprojects {
    repositories {
        mavenCentral()
    }
}
