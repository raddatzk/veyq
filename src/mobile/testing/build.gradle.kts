// The test support the core modules' tests share (ADR 0155 decision 7.1): the in-memory device keys and device every
// flow test runs over (`SoftwareDeviceKeys`, `TestDevice`), the played server's halves (`PlayedShare`,
// `PlayedGuestContent`, `PlayedPairing`, the offline tests' `PlayedTenant` and `PlayedUploads` — naming no application)
// and, on the JVM, what a live run (`VEYQ_LIVE`) signs in with and takes off the account again (`LiveRun`, `Throwaway`).
// Kotlin Multiplatform has no test fixtures, so a module of its own, which the modules' and :bridge's tests depend on; it
// ships in nothing.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmMain.dependencies {
            implementation(kotlin("test-junit"))
            implementation(libs.ktor.client.okhttp)
        }
    }
}
