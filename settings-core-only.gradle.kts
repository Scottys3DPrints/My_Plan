// Core-only build.
//
// The :core module is plain Kotlin/JVM with no Android dependencies, so it can be
// compiled and unit-tested on any machine that has a JDK — no Android SDK, no
// emulator, no access to Google's Maven repository.
//
//     ./gradlew -c settings-core-only.gradle.kts :core:test
//
// This is what CI runs as its fast feedback job before the (much slower) APK build.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "Aegis"

include(":core")
