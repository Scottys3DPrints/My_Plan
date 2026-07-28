// Full build: pure-Kotlin :core plus the Android :app module.
// Requires the Android SDK. If you only want to build and test the core logic
// (classifier, rules engine, cooling-off, DNS parser) without the Android SDK, use:
//
//     ./gradlew -c settings-core-only.gradle.kts :core:test
//
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Aegis"

include(":core")
include(":app")
