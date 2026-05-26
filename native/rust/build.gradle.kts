import org.gradle.internal.os.OperatingSystem
val rustTarget = file("$projectDir/target/release")
val hash = project(":examples:hash")
val nativeOutputDir = hash.layout.buildDirectory.dir("natives").get().asFile

val cargoPath: String = listOf(
    "/usr/local/bin/cargo",
    "${System.getenv("HOME")}/.cargo/bin/cargo"
).firstOrNull { File(it).exists() } ?: "cargo"
val cargoAvailable = File(cargoPath).exists()

val descriptor = hash.layout.buildDirectory.file("generated/native-api.json")

tasks.register<Exec>("buildRust") {
    description = "Build the Rust native library"
    dependsOn(":examples:hash:kspKotlin")  // produces the native-api.json descriptor
    workingDir = projectDir
    commandLine(cargoPath, "build", "--release")
    // build.rs turns the descriptor into the RegisterNatives array.
    environment("NATIVE_DESCRIPTOR", descriptor.get().asFile.absolutePath)
    inputs.file(descriptor)
    onlyIf {
        if (!cargoAvailable) {
            logger.warn("WARNING: cargo not found — skipping Rust build. Install Rust via https://rustup.rs/")
            false
        } else {
            true
        }
    }
    doLast {
        nativeOutputDir.mkdirs()
        val os = OperatingSystem.current()
        val ext = when {
            os.isMacOsX -> "dylib"
            os.isWindows -> "dll"
            else -> "so"
        }
        val prefix = if (os.isWindows) "" else "lib"
        copy {
            from(file("$rustTarget/${prefix}native_rust.$ext"))
            into(nativeOutputDir)
        }
    }
}

tasks.register<Exec>("cleanRust") {
    description = "Clean the Rust native library"
    workingDir = projectDir
    commandLine(cargoPath, "clean")
    onlyIf {
        if (!cargoAvailable) {
            logger.warn("WARNING: cargo not found — skipping Rust clean.")
            false
        } else true
    }
}
