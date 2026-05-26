package dev.sweety.nativegen.gradle

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import java.io.File

/** Rust backend for `dev.sweety.nativegen`: Cargo build of `native/rust` + `scaffoldRust`. */
class NativegenRustPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        pluginManager.apply(NativegenPlugin::class.java)   // ensure the base is applied

        val rustDir = file("native/rust")
        val rustGen = file("native/rust/src/generated/native_generated.rs")
        val descriptor = layout.buildDirectory.file("generated/native-api.json").get().asFile
        val nativeOut = layout.buildDirectory.dir("natives").get().asFile
        val cargo = listOf("/usr/local/bin/cargo", "${System.getenv("HOME")}/.cargo/bin/cargo")
            .firstOrNull { File(it).exists() } ?: "cargo"

        extensions.getByType(KspExtension::class.java).arg("native.rust.out", rustGen.absolutePath)

        tasks.matching { it.name == "kspKotlin" }.configureEach {
            outputs.upToDateWhen { rustGen.exists() }
        }

        tasks.register("buildRust", Exec::class.java) {
            group = NativegenPlugin.NATIVEGEN_GROUP
            description = "Build the Rust native library"
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
        tasks.named("buildNatives") { dependsOn("buildRust") }

        tasks.register("scaffoldRust") {
            group = NativegenPlugin.NATIVEGEN_GROUP
            description = "Generate native/rust skeleton (Cargo.toml + lib.rs + C-ABI stub)"
            dependsOn("kspKotlin")
            doLast {
                Scaffold.writeIfAbsent(file("native/rust/Cargo.toml"), Scaffold.rustCargo(project.name))
                Scaffold.writeIfAbsent(file("native/rust/src/lib.rs"), Scaffold.rustLib())
                Scaffold.writeIfAbsent(file("native/rust/src/cabi.rs"), Scaffold.rustCabi(descriptor))
            }
        }
        tasks.named("scaffoldNative") { dependsOn("scaffoldRust") }
    }
}
