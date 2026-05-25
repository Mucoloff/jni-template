plugins {
    id("java")
    application
    kotlin("jvm") version "2.3.20"
    id("me.champeau.jmh") version "0.7.2"
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
    dependsOn("buildNatives")
    jvmArgs(
        "-Djava.library.path=${layout.buildDirectory.dir("natives").get().asFile.absolutePath}",
        "--enable-native-access=ALL-UNNAMED"
    )
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

// JMH benchmarks need the native libs and native-access enabled in the forked JVM.
jmh {
    jvmArgs.set(listOf(
        "-Djava.library.path=${nativeOutputDir.absolutePath}",
        "--enable-native-access=ALL-UNNAMED"
    ))
    warmupIterations.set(3)
    iterations.set(5)
    warmup.set("1s")
    timeOnIteration.set("1s")
    fork.set(1)
    // Filter to one class with -PjmhInclude=CrossingBench
    (project.findProperty("jmhInclude") as String?)?.let { includes.add(it) }
}
tasks.named("jmhRunBytecodeGenerator") { dependsOn("buildNatives") }
tasks.named("jmh") { dependsOn("buildNatives") }

tasks.register<Delete>("cleanNatives") {
    description = "Clean the native output directory"
    delete(nativeOutputDir)
}

subprojects {
    repositories {
        mavenCentral()
    }
}
