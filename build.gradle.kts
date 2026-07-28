// Deliberately empty of `plugins {}` declarations.
//
// Declaring the Android Gradle Plugin here — even with `apply false` — makes Gradle
// resolve it from Google's Maven repository during configuration of *every* build,
// including the core-only build that is supposed to work without the Android SDK.
// Each module declares the plugins it needs instead.

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
