val cppBuildDir = file("$projectDir/cmake-build")
val nativeOutputDir = rootProject.file("build/natives")

// Override with -Pcmake=/path/to/cmake or the CMAKE env var; otherwise use PATH.
val cmakePath: String =
    (project.findProperty("cmake") as String?)
        ?: System.getenv("CMAKE")
        ?: "cmake"

tasks.register<Exec>("configureCpp") {
    description = "Configure the C++ native library using CMake"
    val javaHome = System.getProperty("java.home")
    // find_package(JNI) locates the platform jni_md.h; only JAVA_HOME is needed
    // as a hint, keeping this portable across Linux / macOS / Windows.
    commandLine(
        cmakePath,
        "-DJAVA_HOME=$javaHome",
        "-B", cppBuildDir.absolutePath,
        "-S", projectDir.absolutePath
    )
}

tasks.register<Exec>("buildCpp") {
    description = "Build the C++ native library using CMake"
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

tasks.register<Delete>("cleanCpp") {
    description = "Clean the C++ build directory and output native library"
    delete(cppBuildDir)
}
