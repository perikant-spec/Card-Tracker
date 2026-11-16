import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM. Must never depend on Android, on a network library, or on anything
// beyond the Kotlin standard library and the JDK.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
