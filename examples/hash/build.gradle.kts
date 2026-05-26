plugins {
    id("java")
    application
    kotlin("jvm") version "2.3.20"
    id("com.google.devtools.ksp") version "2.3.8"
    id("me.champeau.jmh") version "0.7.2"
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains:annotations:26.0.2")
    implementation(project(":annotations"))
    implementation(project(":nativegen-runtime"))
    ksp(project(":processor"))
    ksp(project(":fnv-plugin"))            // contributes the "heap"/"batch" strategies

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(kotlin("test"))
}

// The KSP processor writes the native API descriptor here (consumed by the C++/Rust builds).
val nativeDescriptor = layout.buildDirectory.file("generated/native-api.json").get().asFile
val nativeOutputDir = layout.buildDirectory.dir("natives").get().asFile

ksp {
    arg("native.descriptor", nativeDescriptor.absolutePath)
}

application {
    mainClass.set("dev.sweety.Main")
}

tasks.test {
    useJUnitPlatform()
    dependsOn("buildNatives")
    jvmArgs(
        "-Djava.library.path=${nativeOutputDir.absolutePath}",
        "--enable-native-access=ALL-UNNAMED"
    )
}

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

jmh {
    jvmArgs.set(
        listOf(
            "-Djava.library.path=${nativeOutputDir.absolutePath}",
            "--enable-native-access=ALL-UNNAMED"
        )
    )
    warmupIterations.set(3)
    iterations.set(5)
    warmup.set("1s")
    timeOnIteration.set("1s")
    fork.set(1)
    (project.findProperty("jmhInclude") as String?)?.let { includes.add(it) }
}
tasks.named("jmhRunBytecodeGenerator") { dependsOn("buildNatives") }
tasks.named("jmh") { dependsOn("buildNatives") }
