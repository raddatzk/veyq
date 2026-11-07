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

    // Floors above what the plugins ask for, each the first release that fixes an advisory — none of these ships (this
    // classpath runs the build, never the app), but a compromised build is the same breach one step earlier. A
    // constraint, not a bump, because no release of the plugin that brings each asks for the fix (measured
    // 2026-11-05): openapi-generator 7.27.0, the latest, still pins guava 32.1.3-jre and handlebars 4.3.1 (and jackson
    // 2.21.8 against the Quarkus plugin's 2.22.2); the Quarkus plugin 3.40.1.1 is the newest 3.x. A plain constraint is
    // a floor, never a downgrade: when a plugin bump asks for more, it wins — so **drop a line once nothing here
    // resolves below it without it** (`./gradlew -p buildSrc dependencyInsight --configuration runtimeClasspath
    // --dependency <name>`). Handlebars is the generator's opt-in template engine (`templateEngine=handlebars`);
    // `veyq.openapi` runs the default, Mustache, so the 4.3 -> 4.5 jump is not on a path the build takes (nor is the
    // generator's `handlebars-jackson2` 4.3.1, a helper module with no release after 4.3.1 to move with it).
    constraints {
        implementation("com.fasterxml.jackson.core:jackson-core:2.22.4") {
            because("GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89: 2.21.1 (generator) and 2.22.2 (Quarkus plugin), fixed in 2.22.3")
        }
        implementation("com.fasterxml.jackson.core:jackson-databind:2.22.4") {
            because("GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54 and the other 2.21.x ones, fixed in 2.22.3")
        }
        implementation("com.google.guava:guava:${libs.versions.guava.get()}") {
            because("GHSA-xxph-c9ww-hj94: 32.1.3-jre (generator) and 33.6.0-jre (Quarkus plugin), fixed in 33.7.2")
        }
        implementation("com.github.jknack:handlebars:4.5.8") {
            because("GHSA-r4gv-qr8j-p3pg: 4.3.1 (generator), fixed in 4.5.2")
        }
        implementation("org.codehaus.plexus:plexus-utils:4.1.0") {
            because("GHSA-6fmv-xxpf-w3cw: 4.0.2 (Quarkus plugin), fixed in 4.0.3")
        }
    }
}
