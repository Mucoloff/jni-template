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

// Per-example native build (see :examples:mathops for the pattern).
val cppDir = file("native/cpp")
val cppBuildDir = file("native/cpp/cmake-build")
val nativeOutputDir = layout.buildDirectory.dir("natives").get().asFile
val cmakePath = (project.findProperty("cmake") as String?) ?: System.getenv("CMAKE") ?: "cmake"

ksp {
    arg("native.cpp.out", file("native/cpp/generated/buffer.generated.cpp").absolutePath)
}

application {
    mainClass.set("dev.sweety.example.buffer.Main")
}

tasks.register<Exec>("configureCpp") {
    description = "Configure the buffer native library"
    dependsOn("kspKotlin")
    commandLine(cmakePath, "-DJAVA_HOME=${System.getProperty("java.home")}",
        "-B", cppBuildDir.absolutePath, "-S", cppDir.absolutePath)
}

tasks.register<Exec>("buildCpp") {
    description = "Build the buffer native library"
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
