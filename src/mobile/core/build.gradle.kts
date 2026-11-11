import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate
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
// model. Its targets are the JVM (which the Android app consumes, and where the conformance suite runs fastest), the
// Apple platforms the Apple app runs on, each packed into one XCFramework, `VeyqCore`, and Linux on arm64 and x86-64
// (ADR 0127 decision 4) and Windows on x86-64 (decision 5), cross-compiled from this build's host by Kotlin/Native's own
// toolchain.
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

// Every word the apps show, from the one catalog (ADR 0127 decision 3): the core's `Msg` keys.
val generatedWords = layout.buildDirectory.dir("generated/words")
val generateWords = tasks.register<Exec>("generateWords") {
    group = "veyq"
    description = "Generates the catalog's keys as Kotlin (src/mobile/words/generate_words.py)."
    inputs.file(rootDir.resolve("words/generate_words.py")).withPropertyName("script")
    inputs.file(rootDir.resolve("words/words.json")).withPropertyName("catalog")
    outputs.dir(generatedWords)
    commandLine("python3", rootDir.resolve("words/generate_words.py").absolutePath, "--kotlin", generatedWords.get().asFile.absolutePath)
}

// libsodium for the Apple, Linux and Windows targets, built from its pinned release tarball (core/sodium/build_libsodium.py;
// the Linux and Windows slices by Kotlin/Native's own clang against the C library the core links, core/konan_toolchain.py);
// the script skips a slice already built, so this costs nothing after the first run.
val libsodium = rootDir.resolve("build/libsodium")
val buildLibsodium = tasks.register<Exec>("buildLibsodium") {
    group = "veyq"
    description = "Builds libsodium for macOS, iOS, the iOS simulator (arm64), Linux (arm64, x86-64) and Windows (x86-64) into src/mobile/build/libsodium."
    inputs.file("sodium/build_libsodium.py").withPropertyName("script")
    inputs.file("konan_toolchain.py").withPropertyName("toolchain")
    outputs.dir(libsodium)
    commandLine("python3", file("sodium/build_libsodium.py").absolutePath, libsodium.absolutePath)
}

// utf8proc for Linux's and Windows' `nfc`/`nfkd` (ADR 0123's Consequences, the core on Linux), from its pinned release tarball, with the Unicode
// NormalizationTest.txt of the version it implements (core/utf8proc/build_utf8proc.py).
val utf8proc = rootDir.resolve("build/utf8proc")
val buildUtf8proc = tasks.register<Exec>("buildUtf8proc") {
    group = "veyq"
    description = "Builds utf8proc for Linux (arm64, x86-64) and Windows (x86-64) into src/mobile/build/utf8proc and fetches its NormalizationTest.txt."
    inputs.file("utf8proc/build_utf8proc.py").withPropertyName("script")
    inputs.file("konan_toolchain.py").withPropertyName("toolchain")
    outputs.dir(utf8proc)
    commandLine("python3", file("utf8proc/build_utf8proc.py").absolutePath, utf8proc.absolutePath)
}

/**
 * A cinterop task packs the static library its `-libraryPath` names into the klib but does not take it as an input: a
 * rebuilt library left the old one inside the klib, and the test binary red, until `--rerun` (measured 2026-11-10, the
 * mingw slice of libsodium). The library is therefore declared its input.
 */
fun libraryInput(taskName: String, library: File) =
    tasks.matching { it.name == taskName }.configureEach { inputs.file(library).withPropertyName("staticLibrary") }

kotlin {
    jvmToolchain(21)
    jvm()

    // The targets reached through the bridge (ADR 0127 decision 5), Linux and Windows, share `bridgedMain`/`bridgedTest`
    // between `nativeMain` and their own sets: `nfc`/`nfkd` over utf8proc and the wall clock over kotlinx-datetime. The
    // default template is extended, not replaced; only the HTTP engine differs (`linuxMain` Curl, `mingwMain` WinHttp).
    // Apple and Linux share `posixMain` beside it: a file opened by its path as the bytes it is (`blob/Files.posix.kt`),
    // where Windows takes the path as UTF-16 (`mingwMain`).
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyHierarchyTemplate(KotlinHierarchyTemplate.default) {
        group("native") {
            group("bridged") {
                group("linux")
                group("mingw")
            }
            group("posix") {
                group("apple")
                group("linux")
            }
        }
    }

    // The Apple primitives are libsodium (ADR 0123 decision 3): one slice of build_libsodium.py's output per target.
    val sodiumSlices = mapOf("iosArm64" to "ios_arm64", "iosSimulatorArm64" to "ios_simulator_arm64", "macosArm64" to "macos_arm64")
    val xcf = XCFramework("VeyqCore")
    listOf(iosArm64(), iosSimulatorArm64(), macosArm64()).forEach { target ->
        val slice = libsodium.resolve(sodiumSlices.getValue(target.name))
        target.compilations.getByName("main").cinterops.create("sodium") {
            definitionFile.set(file("sodium/sodium.def"))
            includeDirs(slice.resolve("include"))
            extraOpts("-libraryPath", slice.resolve("lib").absolutePath)
            libraryInput(interopProcessingTaskName, slice.resolve("lib/libsodium.a"))
        }
        target.binaries.framework {
            baseName = "VeyqCore"
            isStatic = true
            xcf.add(this)
        }
    }

    // Linux (ADR 0127 decision 4) and Windows (decision 5): libsodium as on Apple — the `sodium` interop commonizes into
    // `nativeMain` — and utf8proc for the normalization forms, which neither libc has; it commonizes into `bridgedMain`.
    val bridgedSlices = mapOf("linuxArm64" to "linux_arm64", "linuxX64" to "linux_x64", "mingwX64" to "mingw_x64")
    listOf(linuxArm64(), linuxX64(), mingwX64()).forEach { target ->
        val slice = bridgedSlices.getValue(target.name)
        target.compilations.getByName("main").cinterops.create("sodium") {
            definitionFile.set(file("sodium/sodium.def"))
            includeDirs(libsodium.resolve("$slice/include"))
            extraOpts("-libraryPath", libsodium.resolve("$slice/lib").absolutePath)
            libraryInput(interopProcessingTaskName, libsodium.resolve("$slice/lib/libsodium.a"))
        }
        target.compilations.getByName("main").cinterops.create("utf8proc") {
            definitionFile.set(file("utf8proc/utf8proc.def"))
            includeDirs(utf8proc.resolve("$slice/include"))
            extraOpts("-libraryPath", utf8proc.resolve("$slice/lib").absolutePath)
            libraryInput(interopProcessingTaskName, utf8proc.resolve("$slice/lib/libutf8proc.a"))
        }
    }

    // Windows' device keys (session/WindowsDeviceKeys.kt): DPAPI and the Passport key storage provider, through the
    // helpers of wincred/veyq_wincred.h; Windows' own import libraries, so no library of ours. The header is declared the
    // task's input, as libraryInput declares a static library: its includeDirs is not one.
    mingwX64().compilations.getByName("main").cinterops.create("wincred") {
        definitionFile.set(file("wincred/wincred.def"))
        includeDirs(file("wincred"))
        val header = file("wincred/veyq_wincred.h")
        tasks.matching { it.name == interopProcessingTaskName }.configureEach { inputs.file(header).withPropertyName("header") }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generatedApi.map { it.dir("src/commonMain/kotlin") })
            kotlin.srcDir(generatedMajor)
            kotlin.srcDir(generatedTheme)
            kotlin.srcDir(generatedWords)
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
        getByName("bridgedMain").dependencies {
            implementation(libs.kotlinx.datetime)
        }
        linuxMain.dependencies {
            // CIO has no TLS on Kotlin/Native; Curl is linked in statically (ADR 0123's Consequences, the core on Linux).
            implementation(libs.ktor.client.curl)
        }
        mingwMain.dependencies {
            // WinHTTP: TLS is Schannel's and the CA store the system's, so the core carries no OpenSSL on Windows.
            implementation(libs.ktor.client.winhttp)
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
    environment("VEYQ_REPO_ROOT", rootDir.resolve("../..").canonicalPath)
    environment("VEYQ_NORMALIZATION_TEST", utf8proc.resolve("NormalizationTest.txt").absolutePath)
    System.getenv("VEYQ_LIVE")?.let { environment("VEYQ_LIVE", it) }
    testLogging { showStandardStreams = System.getenv("VEYQ_LIVE") != null }
    inputs.file(vectors).withPropertyName("vectors")
}

tasks.matching { it.name.startsWith("compile") || it.name.endsWith("SourcesJar") }.configureEach { dependsOn(generateApi, generateMajor, generateTheme, generateWords) }
tasks.matching { it.name.startsWith("cinteropSodium") }.configureEach { dependsOn(buildLibsodium) }
// The Linux slices are compiled by Kotlin/Native's own toolchain, which this task downloads (core/konan_toolchain.py).
listOf(buildLibsodium, buildUtf8proc).forEach { it.configure { dependsOn("downloadKotlinNativeDistribution") } }
tasks.matching { it.name.startsWith("cinteropUtf8proc") }.configureEach { dependsOn(buildUtf8proc) }
