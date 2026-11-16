import com.android.build.api.artifact.SingleArtifact
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing reads a git-ignored keystore.properties in the repo root:
//   storeFile=/absolute/or/relative/path/to/card-tracker.jks
//   storePassword=...
//   keyAlias=card-tracker
//   keyPassword=...
// Without it, assembleRelease still works but produces an UNSIGNED apk.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

android {
    namespace = "tracker.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "tracker.app"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Lint is advisory for this private build; run `./gradlew :app:lint` to see its report.
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    // Room writes the schema JSON here; commit it so future migrations can be tested.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.biometric)
    implementation(libs.sqlcipher.android)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

// ---------------------------------------------------------------------------------------
// Hard rule: this app has no network access. The task below reads the MERGED manifest of
// every variant (app + all libraries) and fails the build if any network permission is
// present, whoever added it. It runs before assemble*, install* and the unit tests.
// ---------------------------------------------------------------------------------------
abstract class VerifyNoNetworkPermissions : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val forbidden = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_NETWORK_STATE",
            "android.permission.CHANGE_WIFI_STATE",
            "android.permission.SEND_SMS",
            "android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.NFC",
        )
        val text = mergedManifest.get().asFile.readText()
        val declared = Regex("<uses-permission(?:-sdk-23)?\\b[^>]*android:name=\"([^\"]+)\"")
            .findAll(text).map { it.groupValues[1] }.toSet()
        val found = declared.intersect(forbidden)
        if (found.isNotEmpty()) {
            throw GradleException(
                "Forbidden permission(s) in the merged manifest: $found. " +
                    "This app must never have network access. Find the dependency that added it and remove it.",
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register<VerifyNoNetworkPermissions>("verify${cap}NoNetworkPermissions") {
            group = "verification"
            description = "Fails if the merged ${variant.name} manifest requests INTERNET or any other network permission."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        val gated = setOf("assemble$cap", "install$cap", "test${cap}UnitTest", "bundle$cap")
        tasks.matching { it.name in gated }.configureEach { dependsOn(verify) }
    }
}
