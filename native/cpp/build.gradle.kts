val cppBuildDir = file("$projectDir/cmake-build")
val nativeOutputDir = rootProject.file("build/natives")

// Override with -Pcmake=/path/to/cmake or the CMAKE env var; otherwise use PATH.
val cmakePath: String =
    (project.findProperty("cmake") as String?)
        ?: System.getenv("CMAKE")
        ?: "cmake"

val descriptor = rootProject.layout.buildDirectory.file("generated/native-api.json")
val genCpp = file("$projectDir/generated/native.generated.cpp")

// Turn native-api.json into the whole JNI surface: the flat C-ABI lifecycle bodies,
// the jni_* thunks (which route through the C-ABI), the RegisterNatives table and
// JNI_OnLoad. Only the FNV core (fnv.hpp) and the loop C-ABI (transform, batch) are
// hand-written, in cabi.cpp.
tasks.register("genCppNative") {
    description = "Generate the C++ JNI thunks + C-ABI lifecycle from native-api.json"
    dependsOn(":kspKotlin")
    inputs.file(descriptor)
    outputs.file(genCpp)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val json = groovy.json.JsonSlurper().parse(descriptor.get().asFile) as Map<String, Any>
        val core = json["coreType"] as String
        val backends = json["backends"] as List<Map<String, String>>
        val holder = backends.first { it["name"] == "Cpp" }["holder"]
        val methods = json["methods"] as List<Map<String, Any>>
        val cabi = json["cabi"] as List<Map<String, Any>>

        // type maps -------------------------------------------------------------
        fun cParam(k: String) = when (k) { "ptr" -> "void*"; "long" -> "size_t"; "byte" -> "uint8_t"; else -> error("cParam $k") }
        fun cRet(k: String) = when (k) { "void" -> "void"; "ptr" -> "void*"; "long" -> "uint64_t"; else -> error("cRet $k") }
        fun jniType(k: String) = when (k) {
            "ptr", "long" -> "jlong"; "byte" -> "jbyte"; "byteArray" -> "jbyteArray"; "longArray" -> "jlongArray"; "void" -> "void"
            else -> error("jniType $k")
        }
        fun callArg(k: String, v: String) = when (k) {
            "ptr" -> "reinterpret_cast<void*>($v)"; "long" -> "static_cast<size_t>($v)"; "byte" -> "static_cast<uint8_t>($v)"
            else -> error("callArg $k")
        }
        fun sig(symbol: String, ret: String, params: List<String>, named: Boolean = false) =
            "${cRet(ret)} $symbol(${params.mapIndexed { i, k -> cParam(k) + if (named) " p$i" else "" }.joinToString(", ")})"

        val sb = StringBuilder()
        sb.appendLine("// Generated from native-api.json by genCppNative. Do not edit.")
        sb.appendLine("#include <jni.h>")
        sb.appendLine("#include <cstddef>")
        sb.appendLine("#include <cstdint>")
        sb.appendLine("#include \"fnv.hpp\"")
        sb.appendLine()

        // forward declarations of every flat C-ABI symbol (some defined below, some in cabi.cpp)
        sb.appendLine("extern \"C\" {")
        for (c in cabi) {
            @Suppress("UNCHECKED_CAST")
            sb.appendLine("${sig(c["symbol"] as String, c["ret"] as String, c["params"] as List<String>)};")
        }
        sb.appendLine("}")
        sb.appendLine()

        // C-ABI lifecycle bodies (op != null) ----------------------------------
        sb.appendLine("extern \"C\" {")
        for (c in cabi) {
            val op = c["op"] as String? ?: continue
            @Suppress("UNCHECKED_CAST")
            val ps = c["params"] as List<String>
            val symbol = c["symbol"] as String
            val head = "${sig(symbol, c["ret"] as String, ps, named = true)} {"
            val body = when (op) {
                "NEW" -> "return new $core();"
                "FREE" -> "delete static_cast<$core*>(p0);"
                "UPDATE" -> "static_cast<$core*>(p0)->update(static_cast<const uint8_t*>(p1), p2);"
                "DIGEST" -> "return static_cast<const $core*>(p0)->digest();"
                "RESET" -> "static_cast<$core*>(p0)->reset();"
                "HASH" -> "return $core::hash(static_cast<const uint8_t*>(p0), p1);"
                else -> error("unknown core op $op")
            }
            sb.appendLine("$head $body }")
        }
        sb.appendLine("}")
        sb.appendLine()

        // jni_* thunks ----------------------------------------------------------
        sb.appendLine("namespace {")
        for (m in methods) {
            val thunk = m["thunk"] as String
            val target = m["target"] as String
            val ret = m["ret"] as String
            @Suppress("UNCHECKED_CAST")
            val params = m["params"] as List<String>
            when (m["shape"]) {
                "plain" -> {
                    val sigParams = params.mapIndexed { i, k -> "${jniType(k)} p$i" }
                    val args = params.mapIndexed { i, k -> callArg(k, "p$i") }.joinToString(", ")
                    val envParam = "JNIEnv*"
                    val head = "${jniType(ret)} $thunk($envParam, jclass${if (sigParams.isEmpty()) "" else ", " + sigParams.joinToString(", ")}) {"
                    val call = "$target($args)"
                    val stmt = when (ret) {
                        "void" -> "$call;"
                        "ptr" -> "return reinterpret_cast<jlong>($call);"
                        else -> "return static_cast<jlong>($call);"
                    }
                    sb.appendLine("    $head $stmt }")
                }
                "heap" -> {
                    val crit = m["critical"] == true
                    val getE = if (crit) "void* b = env->GetPrimitiveArrayCritical(a0, nullptr);" else "jbyte* b = env->GetByteArrayElements(a0, nullptr);"
                    val relE = if (crit) "env->ReleasePrimitiveArrayCritical(a0, b, JNI_ABORT);" else "env->ReleaseByteArrayElements(a0, b, JNI_ABORT);"
                    sb.appendLine("    jlong $thunk(JNIEnv* env, jclass, jbyteArray a0) {")
                    sb.appendLine("        jsize len = env->GetArrayLength(a0);")
                    sb.appendLine("        $getE")
                    sb.appendLine("        if (!b) return 0;")
                    sb.appendLine("        jlong r = static_cast<jlong>($target(reinterpret_cast<void*>(b), static_cast<size_t>(len)));")
                    sb.appendLine("        $relE")
                    sb.appendLine("        return r;")
                    sb.appendLine("    }")
                }
                "batch" -> {
                    sb.appendLine("    jlongArray $thunk(JNIEnv* env, jclass, jlongArray a0, jlongArray a1) {")
                    sb.appendLine("        jsize n = env->GetArrayLength(a0);")
                    sb.appendLine("        jlong* p = env->GetLongArrayElements(a0, nullptr);")
                    sb.appendLine("        jlong* l = env->GetLongArrayElements(a1, nullptr);")
                    sb.appendLine("        jlongArray out = env->NewLongArray(n);")
                    sb.appendLine("        if (p && l && out) {")
                    sb.appendLine("            jlong* o = env->GetLongArrayElements(out, nullptr);")
                    sb.appendLine("            $target(reinterpret_cast<void*>(p), reinterpret_cast<void*>(l), reinterpret_cast<void*>(o), static_cast<size_t>(n));")
                    sb.appendLine("            env->ReleaseLongArrayElements(out, o, 0);")
                    sb.appendLine("        }")
                    sb.appendLine("        if (p) env->ReleaseLongArrayElements(a0, p, JNI_ABORT);")
                    sb.appendLine("        if (l) env->ReleaseLongArrayElements(a1, l, JNI_ABORT);")
                    sb.appendLine("        return out;")
                    sb.appendLine("    }")
                }
            }
        }
        sb.appendLine()
        sb.appendLine("    const char* kHolderClass = \"$holder\";")
        sb.appendLine("    const JNINativeMethod kRegistrations[] = {")
        for (m in methods) {
            sb.appendLine("        {const_cast<char*>(\"${m["name"]}\"), const_cast<char*>(\"${m["sig"]}\"), reinterpret_cast<void*>(${m["thunk"]})},")
        }
        sb.appendLine("    };")
        sb.appendLine("} // namespace")
        sb.appendLine()
        sb.appendLine("extern \"C\" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {")
        sb.appendLine("    JNIEnv* env = nullptr;")
        sb.appendLine("    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_24) != JNI_OK) return -1;")
        sb.appendLine("    jclass cls = env->FindClass(kHolderClass);")
        sb.appendLine("    if (!cls) return -1;")
        sb.appendLine("    if (env->RegisterNatives(cls, kRegistrations, sizeof(kRegistrations) / sizeof(kRegistrations[0])) < 0) return -1;")
        sb.appendLine("    return JNI_VERSION_24;")
        sb.appendLine("}")

        genCpp.parentFile.mkdirs()
        genCpp.writeText(sb.toString())
    }
}

tasks.register<Exec>("configureCpp") {
    description = "Configure the C++ native library using CMake"
    dependsOn("genCppNative")
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
