package dev.sweety.nativegen.gradle

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test

/**
 * Base convention plugin `dev.sweety.nativegen`: the parts common to every backend — applies
 * Kotlin + KSP, adds the framework dependencies, points KSP at the native descriptor, and sets
 * up the `buildNatives` / `scaffoldNative` aggregators and the run/test JVM args.
 *
 * Apply a backend plugin too: `dev.sweety.nativegen.cpp` and/or `dev.sweety.nativegen.rust`.
 * The native core lives in `native/cpp` / `native/rust`; libs are copied to `build/natives`.
 */
class NativegenPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("com.google.devtools.ksp")

        repositories.mavenLocal()
        repositories.mavenCentral()
        repositories.maven { setUrl("https://jitpack.io") }

        // Framework coords from project properties (known at apply() time → deps added EAGERLY,
        // which KSP's onlyIf requires). Defaults = mavenLocal/Central; for JitPack set
        // nativegen.group=com.github.Mucoloff.jni-ffm-api, nativegen.version=vX in gradle.properties.
        val fwGroup = (findProperty("nativegen.group") as String?) ?: "dev.sweety.nativegen"
        val fwVersion = (findProperty("nativegen.version") as String?) ?: "0.1.4"
        dependencies.add("implementation", "$fwGroup:annotations:$fwVersion")
        dependencies.add("implementation", "$fwGroup:nativegen-runtime:$fwVersion")
        dependencies.add("ksp", "$fwGroup:processor:$fwVersion")

        val descriptor = layout.buildDirectory.file("generated/native-api.json").get().asFile
        extensions.getByType(KspExtension::class.java).arg("native.descriptor", descriptor.absolutePath)

        // Aggregators the backend plugins hook into (configured via tasks.named on their side).
        tasks.register("buildNatives") {
            group = NATIVEGEN_GROUP
            description = "Build all native libraries for this module"
        }
        tasks.register("scaffoldNative") {
            group = NATIVEGEN_GROUP
            description = "Generate the hand-written native skeleton (build files + C-ABI stubs)"
        }

        val nativeOut = layout.buildDirectory.dir("natives").get().asFile
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

    companion object {
        const val NATIVEGEN_GROUP = "nativegen"
    }
}
