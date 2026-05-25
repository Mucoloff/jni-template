val cppBuildDir = file("$projectDir/cmake-build")
val nativeOutputDir = rootProject.file("build/natives")

tasks.register<Exec>("configureCpp") {
    val javaHome = System.getProperty("java.home")
    commandLine(
        "cmake",
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
    dependsOn("configureCpp")
    commandLine("cmake", "--build", cppBuildDir.absolutePath)
    doFirst { nativeOutputDir.mkdirs() }
}

tasks.register<Delete>("cleanCpp") {
    delete(cppBuildDir)
}
