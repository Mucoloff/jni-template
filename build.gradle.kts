plugins {
    id("java")
    application
    kotlin("jvm") version "2.3.20"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
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

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.sweety.Main")
}

tasks.test {
    useJUnitPlatform()
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
