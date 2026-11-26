// The veyq app (ADR 0123). The Kotlin Multiplatform core every platform shares is split as the backend is (ADR 0155
// decision 7.1, ADR 0079): `core` the platform (the crypto suite, the API client, the session, the navigation model, the
// registry of kinds), `apps:<application>` one module per application, `composition` the one that names them all and
// that every client links, `testing` the test support the modules' tests share. `bridge` is the core as one shared
// library for Linux and Windows behind six C functions (ADR 0161 decision 5); `android` is the Android app on it. The
// Apple app (`apple/`, iOS and macOS) is an Xcode project that links `composition`'s framework `VeyqCore`; it is not a
// Gradle project. scripts/validate_mobile_module_graph.py holds the includes to the directories and the edges to the
// direction (an application uses the platform, never the reverse, never another application).
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

include(":core", ":apps:depot", ":apps:expenses", ":apps:memories", ":composition", ":testing", ":bridge", ":android")
