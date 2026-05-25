val cppBuildDir = file("$projectDir/cmake-build")
val nativeOutputDir = rootProject.file("build/natives")
val cmakePath = listOf(
    "/Users/francesco/.cmake-deps/cmake/mac/aarch64/bin/cmake",
    "/usr/local/bin/cmake",
    "/opt/homebrew/bin/cmake"
).firstOrNull { File(it).exists() } ?: "cmake"

tasks.register<Exec>("configureCpp") {
    description = "Configure the C++ native library using CMake"
    val javaHome = System.getProperty("java.home")
    commandLine(
        cmakePath,
        "-DJAVA_HOME=$javaHome",
        "-DJAVA_INCLUDE_PATH=$javaHome/include",
        "-DJAVA_INCLUDE_PATH2=$javaHome/include/darwin",
        "-DJAVA_AWT_LIBRARY=$javaHome/lib/libawt.dylib",
        "-DJAVA_JVM_LIBRARY=$javaHome/lib/libjvm.dylib",
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
