plugins {
    `kotlin-dsl`
    `maven-publish`
}

group = "dev.sweety.nativegen"
version = "0.1.1"

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // On the plugin's runtime classpath so it can apply them to consumer projects.
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
    implementation("com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:2.3.8")
}

gradlePlugin {
    plugins {
        create("nativegen") {
            id = "dev.sweety.nativegen"
            implementationClass = "dev.sweety.nativegen.gradle.NativegenPlugin"
        }
    }
}
