// The backend's convention plugins (`veyq.module`, `veyq.openapi`), precompiled from `src/main/kotlin/*.gradle.kts`.
// `buildSrc` rather than an included `build-logic` build: Gradle builds it first and puts its classpath under every
// build script of the backend, so the root and the modules apply the Kotlin, all-open and Quarkus plugins with no
// version, and it shares the root's `gradle/verification-metadata.xml` — one metadata file for the whole build.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

// A compiler warning in the build logic fails it, as in every module (`veyq.module` says how to meet one).
kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

// What the convention plugins apply or call, each version once, in `../gradle/libs.versions.toml` (this build reads
// the same catalog, `settings.gradle.kts`).
dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen)
    implementation(libs.quarkus.gradle.plugin)
    implementation(libs.openapi.generator)
}
