package dev.sweety.nativegen.gradle

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec

/** C++ backend for `dev.sweety.nativegen`: CMake build of `native/cpp` + `scaffoldCpp`. */
class NativegenCppPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        pluginManager.apply(NativegenPlugin::class.java)   // ensure the base is applied

        val cppDir = file("native/cpp")
        val cppBuildDir = file("native/cpp/cmake-build")
        val cppGen = file("native/cpp/generated/native.generated.cpp")
        val descriptor = layout.buildDirectory.file("generated/native-api.json").get().asFile
        val nativeOut = layout.buildDirectory.dir("natives").get().asFile
        val cmake = (findProperty("cmake") as String?) ?: System.getenv("CMAKE") ?: "cmake"

        extensions.getByType(KspExtension::class.java).arg("native.cpp.out", cppGen.absolutePath)

        // Re-run KSP if the generated C++ source went missing (it's written outside KSP's outputs).
        tasks.matching { it.name == "kspKotlin" }.configureEach {
            outputs.upToDateWhen { cppGen.exists() }
        }

        tasks.register("configureCpp", Exec::class.java) {
            group = NativegenPlugin.NATIVEGEN_GROUP
            description = "Configure the C++ native library (CMake)"
            dependsOn("kspKotlin")
            commandLine(cmake, "-DJAVA_HOME=${System.getProperty("java.home")}",
                "-B", cppBuildDir.absolutePath, "-S", cppDir.absolutePath)
        }
        tasks.register("buildCpp", Exec::class.java) {
            group = NativegenPlugin.NATIVEGEN_GROUP
            description = "Build the C++ native library"
            dependsOn("configureCpp")
            commandLine(cmake, "--build", cppBuildDir.absolutePath)
            doFirst { nativeOut.mkdirs() }
            doLast {
                copy {
                    from(fileTree(cppBuildDir).matching { include("*.so", "*.dylib", "*.dll") })
                    into(nativeOut)
                }
            }
        }
        tasks.named("buildNatives") { dependsOn("buildCpp") }

        tasks.register("scaffoldCpp") {
            group = NativegenPlugin.NATIVEGEN_GROUP
            description = "Generate native/cpp skeleton (CMakeLists + C-ABI stub)"
            dependsOn("kspKotlin")
            doLast {
                Scaffold.writeIfAbsent(file("native/cpp/CMakeLists.txt"), Scaffold.cppCMake())
                Scaffold.writeIfAbsent(file("native/cpp/src/cabi.cpp"), Scaffold.cppCabi(descriptor))
            }
        }
        tasks.named("scaffoldNative") { dependsOn("scaffoldCpp") }
    }
}
