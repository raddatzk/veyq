// The veyq app (ADR 0123): `core` is the Kotlin Multiplatform base every platform shares (the crypto suite, the API
// client, the session, the navigation model); `android` is the Android app on it. The Apple app (`apple/`, iOS and
// macOS) is an Xcode project that links `core` as a framework; it is not a Gradle project.
//
// A build of its own, like src/backend/: the root launcher triggers it, never includes it.
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

rootProject.name = "veyq-mobile"

include(":core", ":android")
