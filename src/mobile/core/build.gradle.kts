import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.openapitools.codegen.DefaultGenerator
import org.openapitools.codegen.config.CodegenConfigurator

buildscript {
    dependencies {
        // The generator the backend and both web clients run, at the one version the repository pins
        // (scripts/validate_build_input_pins.py), with the same advisory floors as src/backend/buildSrc/build.gradle.kts.
        classpath(libs.openapi.generator)
        constraints {
            classpath("com.fasterxml.jackson.core:jackson-core:2.22.4")
            classpath("com.fasterxml.jackson.core:jackson-databind:2.22.4")
            classpath("com.google.guava:guava:33.7.2-jre")
            classpath("com.github.jknack:handlebars:4.5.8")
        }
    }
}

// The base every veyq app shares (ADR 0123): the v1 crypto suite, the API client, the session and the navigation
// model. Its targets are the JVM (which the Android app consumes, and where the conformance suite runs fastest) and
// the Apple platforms the Apple app runs on, each packed into one XCFramework, `VeyqCore`.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// The API client is generated from contracts/api, never written by hand (CLAUDE.md: API-first). Core's specs only, the
// ones a member's app calls; an application's specs join with that application's screens.
val contracts = rootDir.resolve("../../contracts/api").canonicalFile
val coreSpecs = listOf("setup", "auth", "account", "scopes", "content", "favorites", "tenants", "blob", "sharing", "moderation")

/** An application's spec, generated into packages of its own (`api.<application>`), so its models never meet core's. */
val applicationSpecs = listOf("expenses/expenses", "depot/files", "memories/media")
val generatedApi = layout.buildDirectory.dir("generated/openapi")

val generateApi = tasks.register("generateApi") {
    group = "veyq"
    description = "Generates the Kotlin Multiplatform API client from contracts/api (openapi-generator, kotlin/multiplatform)."
    val specs = coreSpecs.map { contracts.resolve("$it.openapi.yaml") to "" } +
        applicationSpecs.map { contracts.resolve("$it.openapi.yaml") to "." + it.substringBefore('/') }
    inputs.files(specs.map { it.first } + contracts.resolve("_common.openapi.yaml")).withPropertyName("specs")
    outputs.dir(generatedApi)
    doLast {
        val out = generatedApi.get().asFile
        out.deleteRecursively()
        specs.forEach { (spec, application) ->
            val config = CodegenConfigurator()
                .setGeneratorName("kotlin")
                .setLibrary("multiplatform")
                .setInputSpec(spec.absolutePath)
                .setOutputDir(out.absolutePath)
                .setPackageName("me.raddatz.veyq.mobile.api")
                .setApiPackage("me.raddatz.veyq.mobile.api$application.apis")
                .setModelPackage("me.raddatz.veyq.mobile.api$application.models")
                .addAdditionalProperty("dateLibrary", "string")
                .addAdditionalProperty("enumUnknownDefaultCase", "true")
                .addAdditionalProperty("hideGenerationTimestamp", "true")
                .addAdditionalProperty("omitGradleWrapper", "true")
                .addAdditionalProperty("omitGradlePluginVersions", "true")
                .addGlobalProperty("apiTests", "false")
                .addGlobalProperty("modelTests", "false")
                .addGlobalProperty("apiDocs", "false")
                .addGlobalProperty("modelDocs", "false")
            DefaultGenerator().opts(config.toClientOptInput()).generate()
        }
        // The specs carry the backend's server-side annotations as `x-*-extra-annotation` (Jakarta validation, Jackson
        // deserializers): the server's checks, which a client neither has nor needs. A line that is nothing but such
        // an annotation is dropped; anything else naming the backend's packages fails the build rather than ship.
        out.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            // (sharing's `JsonInclude` is the server's serializer's choice, as a getter annotation)
            val serverOnly = listOf("@field:", "@jakarta.", "@me.raddatz.veyq.", "@get:com.fasterxml.jackson.annotation.JsonInclude(")
            val kept = f.readLines().filterNot { line -> serverOnly.any { line.trimStart().startsWith(it) } }
            kept.firstOrNull { "jakarta." in it || "fasterxml" in it || Regex("""me\.raddatz\.veyq\.(?!mobile\b)""").containsMatchIn(it) }?.let {
                throw GradleException("${f.name}: a server-side annotation survived the strip: ${it.trim().take(120)}")
            }
            f.writeText(kept.joinToString("\n", postfix = "\n"))
        }
    }
}

// The platform major the app speaks (ADR 0085), from the repository's one VERSION file, as the backend and the web take it.
val versionFile = rootDir.resolve("../../VERSION").canonicalFile
val generatedMajor = layout.buildDirectory.dir("generated/major")
val generateMajor = tasks.register("generateMajor") {
    inputs.file(versionFile).withPropertyName("version")
    outputs.dir(generatedMajor)
    doLast {
        val major = versionFile.readText().trim().substringBefore('.').toInt()
        generatedMajor.get().asFile.resolve("me/raddatz/veyq/mobile/session/PlatformMajor.kt").apply {
            parentFile.mkdirs()
            writeText("package me.raddatz.veyq.mobile.session\n\n/** Generated from VERSION. */\ninternal const val PLATFORM_MAJOR: Int = $major\n")
        }
    }
}

// The web's colour roles and radii as Kotlin (ADR 0123 decision 7), from src/web/src/styles/base.css.
val generatedTheme = layout.buildDirectory.dir("generated/theme")
val generateTheme = tasks.register<Exec>("generateTheme") {
    group = "veyq"
    description = "Generates the web's palette as Kotlin (src/mobile/theme/generate_theme.py)."
    inputs.file(rootDir.resolve("theme/generate_theme.py")).withPropertyName("script")
    inputs.file(rootDir.resolve("../web/src/styles/base.css")).withPropertyName("tokens")
    outputs.dir(generatedTheme)
    commandLine("python3", rootDir.resolve("theme/generate_theme.py").absolutePath, "--kotlin", generatedTheme.get().asFile.absolutePath)
}

// libsodium for the Apple targets, built from its pinned release tarball (core/sodium/build_libsodium.py); the script
// skips a slice already built, so this costs nothing after the first run.
val libsodium = rootDir.resolve("build/libsodium")
val buildLibsodium = tasks.register<Exec>("buildLibsodium") {
    group = "veyq"
    description = "Builds libsodium for macOS, iOS and the iOS simulator (arm64) into src/mobile/build/libsodium."
    inputs.file("sodium/build_libsodium.py").withPropertyName("script")
    outputs.dir(libsodium)
    commandLine("python3", file("sodium/build_libsodium.py").absolutePath, libsodium.absolutePath)
}

kotlin {
    jvmToolchain(21)
    jvm()

    // The Apple primitives are libsodium (ADR 0123 decision 3): one slice of build_libsodium.py's output per target.
    val sodiumSlices = mapOf("iosArm64" to "ios_arm64", "iosSimulatorArm64" to "ios_simulator_arm64", "macosArm64" to "macos_arm64")
    val xcf = XCFramework("VeyqCore")
    listOf(iosArm64(), iosSimulatorArm64(), macosArm64()).forEach { target ->
        val slice = libsodium.resolve(sodiumSlices.getValue(target.name))
        target.compilations.getByName("main").cinterops.create("sodium") {
            definitionFile.set(file("sodium/sodium.def"))
            includeDirs(slice.resolve("include"))
            extraOpts("-libraryPath", slice.resolve("lib").absolutePath)
        }
        target.binaries.framework {
            baseName = "VeyqCore"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generatedApi.map { it.dir("src/commonMain/kotlin") })
            kotlin.srcDir(generatedMajor)
            kotlin.srcDir(generatedTheme)
        }
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kotlincrypto.sha3)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.bouncycastle)
        }
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

// The conformance suite reads the vectors from where every client reads them: contracts/crypto/vectors/v1.json.
val vectors = rootDir.resolve("../../contracts/crypto/vectors/v1.json").canonicalFile
tasks.withType<Test>().configureEach {
    systemProperty("veyq.vectors", vectors.absolutePath)
    systemProperty("veyq.repoRoot", rootDir.resolve("../..").canonicalPath)
    System.getenv("VEYQ_LIVE")?.let { environment("VEYQ_LIVE", it) }
    System.getenv("VEYQ_SEED_TENANT")?.let { environment("VEYQ_SEED_TENANT", it) }
    System.getenv("VEYQ_ADMIN_BASE")?.let { environment("VEYQ_ADMIN_BASE", it) }
    testLogging { showStandardStreams = System.getenv("VEYQ_LIVE") != null }
    inputs.file(vectors).withPropertyName("vectors")
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    environment("VEYQ_VECTORS", vectors.absolutePath)
    environment("VEYQ_ENV_FILE", rootDir.resolve("../../.run/.env").canonicalPath)
    System.getenv("VEYQ_LIVE")?.let { environment("VEYQ_LIVE", it) }
    testLogging { showStandardStreams = System.getenv("VEYQ_LIVE") != null }
    inputs.file(vectors).withPropertyName("vectors")
}

tasks.matching { it.name.startsWith("compile") || it.name.endsWith("SourcesJar") }.configureEach { dependsOn(generateApi, generateMajor, generateTheme) }
tasks.matching { it.name.startsWith("cinteropSodium") }.configureEach { dependsOn(buildLibsodium) }
