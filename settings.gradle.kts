plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "jni-template"

include("annotations", "processor", "nativegen-runtime", "nativegen-spi", "fnv-plugin")
include("native:cpp", "native:rust")
include("examples:mathops", "examples:buffer")
