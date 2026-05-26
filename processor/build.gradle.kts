plugins {
    kotlin("jvm") version "2.3.20"
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":annotations"))
    implementation(project(":nativegen-spi"))
    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.8")
}
