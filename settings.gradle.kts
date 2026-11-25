pluginManagement {
    repositories {
        google()
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

rootProject.name = "flextunnel-android"

// The Android app, and the pure-Kotlin core library (destination, forward and
// settings model, the core's validation rules, FFI JSON shapes, snapshot
// decoding) it shares with its JVM unit tests.
include(":app", ":tunnelcore")
