val cppBuildDir = file("$projectDir/cmake-build")
val nativeOutputDir = rootProject.file("build/natives")

// Override with -Pcmake=/path/to/cmake or the CMAKE env var; otherwise use PATH.
val cmakePath: String =
    (project.findProperty("cmake") as String?)
        ?: System.getenv("CMAKE")
        ?: "cmake"

val descriptor = rootProject.layout.buildDirectory.file("generated/native-api.json")
val genHeader = file("$projectDir/generated/registrations.generated.h")

// Turn the JSON descriptor (emitted by the annotation processor) into the
// JNINativeMethod table, so the registration is never hand-written here.
tasks.register("genCppRegistrations") {
    description = "Generate the C++ RegisterNatives table from native-api.json"
    dependsOn(":kspKotlin")
    inputs.file(descriptor)
    outputs.file(genHeader)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val json = groovy.json.JsonSlurper().parse(descriptor.get().asFile) as Map<String, Any>
        val backends = json["backends"] as List<Map<String, String>>
        val holder = backends.first { it["name"] == "Cpp" }["holder"]
        val methods = json["methods"] as List<Map<String, Any>>
        val sb = StringBuilder()
        sb.appendLine("// Generated from native-api.json. Do not edit.")
        sb.appendLine("static const char* kHolderClass = \"$holder\";")
        sb.appendLine("static const JNINativeMethod kRegistrations[] = {")
        for (m in methods) {
            sb.appendLine("    {const_cast<char*>(\"${m["name"]}\"), " +
                    "const_cast<char*>(\"${m["sig"]}\"), " +
                    "reinterpret_cast<void*>(${m["thunk"]})},")
        }
        sb.appendLine("};")
        genHeader.parentFile.mkdirs()
        genHeader.writeText(sb.toString())
    }
}

tasks.register<Exec>("configureCpp") {
    description = "Configure the C++ native library using CMake"
    dependsOn("genCppRegistrations")
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
