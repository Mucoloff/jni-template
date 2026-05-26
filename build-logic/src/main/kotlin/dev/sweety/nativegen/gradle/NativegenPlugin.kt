package dev.sweety.nativegen.gradle

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import java.io.File

/**
 * Convention plugin `dev.sweety.nativegen`: from one `@NativeApi` spec + a native core, wires
 * the whole pipeline (KSP processor, framework deps, per-project C++/Rust native builds, and the
 * run/test JVM args) behind a `nativegen { }` block — collapsing ~50 lines of boilerplate.
 *
 * Conventions: native core in `native/cpp` (CMake) and `native/rust` (Cargo); generated sources
 * land next to them; libs are copied to `build/natives`.
 */
class NativegenPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        val ext = extensions.create("nativegen", NativegenExtension::class.java)

        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("com.google.devtools.ksp")

        repositories.mavenLocal()
        repositories.mavenCentral()
        repositories.maven { setUrl("https://jitpack.io") }

        val cppDir = file("native/cpp")
        val cppBuildDir = file("native/cpp/cmake-build")
        val cppGen = file("native/cpp/generated/native.generated.cpp")
        val rustDir = file("native/rust")
        val rustGen = file("native/rust/src/generated/native_generated.rs")
        val nativeOut = layout.buildDirectory.dir("natives").get().asFile
        val descriptor = layout.buildDirectory.file("generated/native-api.json").get().asFile
        val cmake = (findProperty("cmake") as String?) ?: System.getenv("CMAKE") ?: "cmake"
        val cargo = listOf("/usr/local/bin/cargo", "${System.getenv("HOME")}/.cargo/bin/cargo")
            .firstOrNull { File(it).exists() } ?: "cargo"

        afterEvaluate {
            // In-repo these resolve to the sibling projects (matching group:name:version);
            // externally they come from mavenLocal / JitPack (set nativegen.group/version).
            dependencies.add("implementation", "${ext.group}:annotations:${ext.version}")
            dependencies.add("implementation", "${ext.group}:nativegen-runtime:${ext.version}")
            dependencies.add("ksp", "${ext.group}:processor:${ext.version}")

            val ksp = extensions.getByType(KspExtension::class.java)
            ksp.arg("native.descriptor", descriptor.absolutePath)
            if (ext.cpp) ksp.arg("native.cpp.out", cppGen.absolutePath)
            if (ext.rust) ksp.arg("native.rust.out", rustGen.absolutePath)

            // Re-run KSP if the native sources it writes outside its tracked outputs go missing.
            tasks.matching { it.name == "kspKotlin" }.configureEach {
                outputs.upToDateWhen {
                    (!ext.cpp || cppGen.exists()) && (!ext.rust || rustGen.exists())
                }
            }

            val nativeTasks = mutableListOf<String>()

            if (ext.cpp) {
                tasks.register("configureCpp", Exec::class.java) {
                    dependsOn("kspKotlin")
                    commandLine(cmake, "-DJAVA_HOME=${System.getProperty("java.home")}",
                        "-B", cppBuildDir.absolutePath, "-S", cppDir.absolutePath)
                }
                tasks.register("buildCpp", Exec::class.java) {
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
                nativeTasks += "buildCpp"
            }

            if (ext.rust) {
                tasks.register("buildRust", Exec::class.java) {
                    dependsOn("kspKotlin")
                    workingDir = rustDir
                    commandLine(cargo, "build", "--release")
                    onlyIf { File(cargo).exists() || cargo == "cargo" }
                    doLast {
                        nativeOut.mkdirs()
                        copy {
                            from(fileTree("$rustDir/target/release")
                                .matching { include("libnative_rust.so", "libnative_rust.dylib", "native_rust.dll") })
                            into(nativeOut)
                        }
                    }
                }
                nativeTasks += "buildRust"
            }

            tasks.register("buildNatives") {
                group = "build"
                description = "Build all native libraries for this module"
                dependsOn(nativeTasks)
            }

            val libPath = "-Djava.library.path=${nativeOut.absolutePath}"
            val nativeAccess = "--enable-native-access=ALL-UNNAMED"
            tasks.withType(JavaExec::class.java).configureEach {
                val self = this
                dependsOn("buildNatives")
                doFirst { self.jvmArgs(libPath, nativeAccess) }
            }
            tasks.withType(Test::class.java).configureEach {
                dependsOn("buildNatives")
                jvmArgs(libPath, nativeAccess)
            }
        }
    }
}
