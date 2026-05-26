plugins {
    application
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

// Example with its own per-example native build: the framework processor generates the
// C++ JNI surface; a tiny CMake build compiles it with the hand-written core into this
// module's own libnative_cpp.so, loaded via this module's java.library.path.
val cppDir = file("native/cpp")
val cppBuildDir = file("native/cpp/cmake-build")
val nativeOutputDir = layout.buildDirectory.dir("natives").get().asFile
val cmakePath = (project.findProperty("cmake") as String?) ?: System.getenv("CMAKE") ?: "cmake"

ksp {
    arg("native.cpp.out", file("native/cpp/generated/math.generated.cpp").absolutePath)
}

application {
    mainClass.set("dev.sweety.example.mathops.Main")
}

tasks.register<Exec>("configureCpp") {
    description = "Configure the mathops native library"
    dependsOn("kspKotlin")
    commandLine(cmakePath, "-DJAVA_HOME=${System.getProperty("java.home")}",
        "-B", cppBuildDir.absolutePath, "-S", cppDir.absolutePath)
}

tasks.register<Exec>("buildCpp") {
    description = "Build the mathops native library"
    dependsOn("configureCpp")
    commandLine(cmakePath, "--build", cppBuildDir.absolutePath)
    doFirst { nativeOutputDir.mkdirs() }
    doLast {
        copy {
            from(fileTree(cppBuildDir).matching { include("*.dylib", "*.so", "*.dll") })
            into(nativeOutputDir)
        }
    }
}

tasks.named<JavaExec>("run") {
    dependsOn("buildCpp")
    doFirst {
        jvmArgs("-Djava.library.path=${nativeOutputDir.absolutePath}", "--enable-native-access=ALL-UNNAMED")
    }
}
