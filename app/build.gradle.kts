import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing.
 *
 * Aegis is installed by sideloading, so the signing key is not a store credential — it is
 * the identity that lets one build upgrade the last one in place. Android refuses to
 * install an update signed by a different key, so a stable key is the difference between
 * "update" and "uninstall, losing every rule you set".
 *
 * Configuration comes from `keystore.properties` (local, git-ignored) or from environment
 * variables (CI). With neither, the release build falls back to the debug key so that
 * `./gradlew assembleRelease` always produces something installable.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

// Blank counts as absent. CI always defines these variables and leaves them empty when no
// signing key is configured, so a plain null check would hand `file("")` an empty path and
// fail the build at configuration time — before a line of the app is even compiled.
fun signingValue(propertyKey: String, environmentKey: String): String? =
    (keystoreProperties.getProperty(propertyKey) ?: System.getenv(environmentKey))
        ?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "AEGIS_KEYSTORE_FILE")
val hasReleaseSigning = releaseStoreFile != null && file(releaseStoreFile).exists()

android {
    namespace = "com.aegis.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aegis.app"
        minSdk = 26
        targetSdk = 34

        // Supplied by CI so the code climbs monotonically across builds. Android refuses
        // to install an update whose versionCode is lower than the installed one, so a
        // fixed value here would make in-place updates fail after the first release.
        versionCode = (System.getenv("AEGIS_VERSION_CODE") ?: "1").toIntOrNull() ?: 1
        versionName = System.getenv("AEGIS_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"

        vectorDrawables.useSupportLibrary = true

        // Where the in-app updater looks. Change this if you fork the repository.
        buildConfigField("String", "UPDATE_REPO", "\"Scottys3DPrints/My_Plan\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = signingValue("storePassword", "AEGIS_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "AEGIS_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "AEGIS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
}
