import java.net.URI
import java.security.MessageDigest
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
// (ADR 0136 decision 4) and Windows on x86-64 (decision 5), cross-compiled from this build's host by Kotlin/Native's own
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
val applicationSpecs = listOf("expenses/expenses", "depot/files", "memories/media", "memories/memory-banner", "memories/item-favorites", "memories/share-media")
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

// Every word the apps show, from the one catalog (ADR 0136 decision 3): the core's `Msg` keys.
val generatedWords = layout.buildDirectory.dir("generated/words")
val generateWords = tasks.register<Exec>("generateWords") {
    group = "veyq"
    description = "Generates the catalog's keys and the core's own texts as Kotlin (src/mobile/words/generate_words.py)."
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

// libwebp for every native target (core/webp/build_libwebp.py): a member's picture decoded where the platform cannot
// (`media/WebpPicture.kt`, Linux and Windows) and one's own encoded (`media/LibwebpEncoder.kt`, ADR 0148 decision 2), from
// the web encoder's pin (src/web/webp-encoder/libwebp.pin): one libwebp in the repository.
val libwebp = rootDir.resolve("build/libwebp")
val webpPin = rootDir.resolve("../web/webp-encoder/libwebp.pin").canonicalFile
val buildLibwebp = tasks.register<Exec>("buildLibwebp") {
    group = "veyq"
    description = "Builds libwebp for Linux (arm64, x86-64), Windows (x86-64) and Apple (macOS, iOS, the simulator) into src/mobile/build/libwebp."
    inputs.file("webp/build_libwebp.py").withPropertyName("script")
    inputs.file("konan_toolchain.py").withPropertyName("toolchain")
    inputs.file(webpPin).withPropertyName("pin")
    outputs.dir(libwebp)
    commandLine("python3", file("webp/build_libwebp.py").absolutePath, libwebp.absolutePath)
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
    // JavaScript (ADR 0155 decision 3): the web's target, IR, ES modules, es2015, with TypeScript definitions and no
    // experimental flag. Its primitives are the web's own @noble/* (`jsMain`'s `JsPrimitives`), resolved by pnpm from the
    // lockfile, never by Gradle. No environment is chosen (src/mobile/build.gradle.kts says why), so no Kotlin/JS test task
    // exists: the common tests compile for JavaScript (`compileTestKotlinJs`) and the suite's conformance runs under Node
    // through `@veyq/core-js`, from the `conformance` compilation's binary of its own — never the shipped module's export.
    js {
        binaries.library()
        useEsModules()
        generateTypeScriptDefinitions()
        compilerOptions { target.set("es2015") }
        val main = compilations.getByName("main")
        val conformance = compilations.create("conformance") { associateWith(main) }
        binaries.library(conformance)
    }

    // The targets reached through the bridge (ADR 0136 decision 5), Linux and Windows, share `bridgedMain`/`bridgedTest`
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
        // The tests that block a thread or run on a pool of them (`runBlocking`, `newFixedThreadPoolContext`: the JVM's and
        // native's coroutines API, none on JavaScript) live in `threadedTest`, between `commonTest` and the JVM and native
        // targets: in `commonTest` they would fail `compileTestKotlinJs` (44 errors in three files, measured 2026-11-20).
        // A new test that blocks a thread goes here, never into `commonTest` behind a platform `expect`.
        common {
            group("threaded") {
                withJvm()
                group("native")
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
        // libwebp's encoder for one's own picture (ADR 0148 decision 2): ImageIO decodes WebP but writes none
        target.compilations.getByName("main").cinterops.create("webp") {
            definitionFile.set(file("webp/webp.def"))
            includeDirs(libwebp.resolve("${sodiumSlices.getValue(target.name)}/include"))
            extraOpts("-libraryPath", libwebp.resolve("${sodiumSlices.getValue(target.name)}/lib").absolutePath)
            libraryInput(interopProcessingTaskName, libwebp.resolve("${sodiumSlices.getValue(target.name)}/lib/libwebp.a"))
        }
        target.binaries.framework {
            baseName = "VeyqCore"
            isStatic = true
            xcf.add(this)
        }
    }

    // Linux (ADR 0136 decision 4) and Windows (decision 5): libsodium as on Apple — the `sodium` interop commonizes into
    // `nativeMain` — and utf8proc for the normalization forms, which neither libc has, commonized into `bridgedMain`; and
    // libwebp, on Apple too, so it commonizes into `nativeMain` (its decoder used by `bridgedMain`'s `WebpPicture`).
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
        target.compilations.getByName("main").cinterops.create("webp") {
            definitionFile.set(file("webp/webp.def"))
            includeDirs(libwebp.resolve("$slice/include"))
            extraOpts("-libraryPath", libwebp.resolve("$slice/lib").absolutePath)
            libraryInput(interopProcessingTaskName, libwebp.resolve("$slice/lib/libwebp.a"))
        }
    }

    // Windows' device keys (session/WindowsDeviceKeys.kt): DPAPI and the Passport key storage provider, and a received
    // link's guest sessions in the Credential Locker (sharing/WindowsGuestSessions.kt), through the helpers of
    // wincred/veyq_wincred.h (its WinRT internals in veyq_wincred_vault.h); Windows' own import libraries, so no library
    // of ours. The headers are declared the task's input, as libraryInput declares a static library: its includeDirs is
    // not one.
    mingwX64().compilations.getByName("main").cinterops.create("wincred") {
        definitionFile.set(file("wincred/wincred.def"))
        includeDirs(file("wincred"))
        val headers = files("wincred/veyq_wincred.h", "wincred/veyq_wincred_vault.h")
        tasks.matching { it.name == interopProcessingTaskName }.configureEach { inputs.files(headers).withPropertyName("wincredHeaders") }
    }

    // Linux's device keys (session/LinuxDeviceKeys.kt): the Secret Service through libsecret, which secret/veyq_secret.h
    // loads with dlopen at run time, so no library here and none at link time. The header is the task's input, as above.
    listOf(linuxArm64(), linuxX64()).forEach { target ->
        target.compilations.getByName("main").cinterops.create("secret") {
            definitionFile.set(file("secret/secret.def"))
            includeDirs(file("secret"))
            val header = file("secret/veyq_secret.h")
            tasks.matching { it.name == interopProcessingTaskName }.configureEach { inputs.file(header).withPropertyName("header") }
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generatedApi.map { it.dir("src/commonMain/kotlin") })
            kotlin.srcDir(generatedMajor)
            kotlin.srcDir(generatedTheme)
            kotlin.srcDir(generatedWords)
        }
        // The in-memory device keys and device of the flows' tests (`SoftwareDeviceKeys`, `testDevice`): a directory of
        // their own, so :bridge's JVM tests compile the same fake (bridge/build.gradle.kts) and no copy drifts.
        commonTest {
            kotlin.srcDir("src/testDevice/kotlin")
        }
        // What a live run (`VEYQ_LIVE`) signs in with and takes off the account again (`LiveRun`): JVM only, compiled into
        // :bridge's JVM tests too (bridge/build.gradle.kts), so every live case cleans up through the one helper.
        jvmTest {
            kotlin.srcDir("src/liveTest/kotlin")
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
        jvmTest.dependencies {
            // Test-only: draws the web's kit fixtures' pages so the kit's whole read order runs on them (`KitFixtureTest`).
            implementation(libs.pdfbox)
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

// ZXing's QR black-box corpus (qr/zxing-corpus.sha256: the upstream commit and each file's SHA-256), fetched into build/
// and never committed; `QrCorpusTest` holds the core's decoder to upstream's thresholds over it (ADR 0136 decision 1).
// A file already there at its hash is not fetched again; one whose bytes differ fails the build.
val qrCorpusManifest = file("qr/zxing-corpus.sha256")
val qrCorpus = layout.buildDirectory.dir("qr-corpus")
val fetchQrCorpus = tasks.register("fetchQrCorpus") {
    group = "veyq"
    description = "Fetches ZXing's QR black-box images into build/qr-corpus, each held to its pinned SHA-256."
    inputs.file(qrCorpusManifest).withPropertyName("manifest")
    outputs.dir(qrCorpus)
    doLast {
        val lines = qrCorpusManifest.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val commit = lines.first().removePrefix("commit ").trim()
        val out = qrCorpus.get().asFile
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        lines.drop(1).forEach { line ->
            val (hash, path) = line.split(Regex("\\s+"), limit = 2)
            val target = out.resolve(path)
            if (target.isFile && sha256(target.readBytes()) == hash) return@forEach
            val url = URI("https://raw.githubusercontent.com/zxing/zxing/$commit/core/src/test/resources/blackbox/$path").toURL()
            val bytes = url.openStream().use { it.readBytes() }
            if (sha256(bytes) != hash) throw GradleException("$path: SHA-256 ${sha256(bytes)}, pinned $hash")
            target.parentFile.mkdirs()
            target.writeBytes(bytes)
        }
    }
}
tasks.named<Test>("jvmTest") {
    dependsOn(fetchQrCorpus)
    systemProperty("veyq.qrCorpus", qrCorpus.get().asFile.absolutePath)
    inputs.dir(qrCorpus).withPropertyName("qrCorpus")
    // The web's interop fixture, which `QrPngImageTest` holds to `QrPng`'s output (and writes anew with this set).
    inputs.file(rootDir.resolve("../web/src/features/account/pairing/__fixtures__/core-pairing-code.png")).withPropertyName("qrWebFixture")
    System.getenv("VEYQ_WRITE_QR_FIXTURE")?.let { environment("VEYQ_WRITE_QR_FIXTURE", it); outputs.upToDateWhen { false } }
}

// The conformance suite reads the vectors from where every client reads them: contracts/crypto/vectors/v1.json.
val vectors = rootDir.resolve("../../contracts/crypto/vectors/v1.json").canonicalFile
// The web's Emergency Kit fixtures, which the kit's read order is held to (KitFixtureCases), on the JVM and Apple.
val kitFixtures = rootDir.resolve("../web/src/features/account/emergencyKit/__fixtures__").canonicalFile
// The import cases and the web's download both clients answer to (ADR 0152 decision 4), on the JVM and Apple.
val importCases = rootDir.resolve("../../contracts/export/import-cases").canonicalFile
tasks.withType<Test>().configureEach {
    systemProperty("veyq.vectors", vectors.absolutePath)
    systemProperty("veyq.repoRoot", rootDir.resolve("../..").canonicalPath)
    System.getenv("VEYQ_LIVE")?.let { environment("VEYQ_LIVE", it) }
    System.getenv("VEYQ_SEED_TENANT")?.let { environment("VEYQ_SEED_TENANT", it) }
    System.getenv("VEYQ_ADMIN_BASE")?.let { environment("VEYQ_ADMIN_BASE", it) }
    // `.run/.env` of another checkout (a worktree has none): read by DeviceTrustLiveTest.
    System.getenv("VEYQ_ENV_FILE")?.let { environment("VEYQ_ENV_FILE", it) }
    // the throwaway a destructive live case runs as (ADR 0148 decision 12; `src/web`'s `throwaway:account`)
    System.getenv("VEYQ_LIVE_THROWAWAY_ENV")?.let { environment("VEYQ_LIVE_THROWAWAY_ENV", it) }
    // the search's volume measurement (ADR 0146 § Consequences), opt-in: a timing is no check of every run
    System.getenv("VEYQ_MEASURE")?.let { environment("VEYQ_MEASURE", it) }
    testLogging { showStandardStreams = System.getenv("VEYQ_LIVE") != null || System.getenv("VEYQ_MEASURE") != null }
    inputs.file(vectors).withPropertyName("vectors")
    inputs.dir(kitFixtures).withPropertyName("kitFixtures")
    inputs.dir(importCases).withPropertyName("importCases")
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    environment("VEYQ_VECTORS", vectors.absolutePath)
    environment("VEYQ_ENV_FILE", rootDir.resolve("../../.run/.env").canonicalPath)
    environment("VEYQ_REPO_ROOT", rootDir.resolve("../..").canonicalPath)
    environment("VEYQ_NORMALIZATION_TEST", utf8proc.resolve("NormalizationTest.txt").absolutePath)
    System.getenv("VEYQ_LIVE")?.let { environment("VEYQ_LIVE", it) }
    System.getenv("VEYQ_MEASURE")?.let { environment("VEYQ_MEASURE", it) }
    testLogging { showStandardStreams = System.getenv("VEYQ_LIVE") != null || System.getenv("VEYQ_MEASURE") != null }
    inputs.file(vectors).withPropertyName("vectors")
    inputs.dir(kitFixtures).withPropertyName("kitFixtures")
    inputs.dir(importCases).withPropertyName("importCases")
}

tasks.matching { it.name.startsWith("compile") || it.name.endsWith("SourcesJar") }.configureEach { dependsOn(generateApi, generateMajor, generateTheme, generateWords) }
tasks.matching { it.name.startsWith("cinteropSodium") }.configureEach { dependsOn(buildLibsodium) }
// The Linux slices are compiled by Kotlin/Native's own toolchain, which this task downloads (core/konan_toolchain.py).
listOf(buildLibsodium, buildUtf8proc, buildLibwebp).forEach { it.configure { dependsOn("downloadKotlinNativeDistribution") } }
tasks.matching { it.name.startsWith("cinteropUtf8proc") }.configureEach { dependsOn(buildUtf8proc) }
tasks.matching { it.name.startsWith("cinteropWebp") }.configureEach { dependsOn(buildLibwebp) }

// The compiled module, its TypeScript definitions and source maps, into `@veyq/core-js`'s `dist/` (ADR 0155 decision 6):
// the one directory where Gradle and pnpm meet. The conformance binary lands in `dist/conformance/`, beside and never
// inside the shipped module. The package's `build` runs this task by name (src/packages/core-js/scripts/build.py).
val coreJsDist = rootDir.resolve("../packages/core-js/dist")
val jsPackage = tasks.register<Sync>("jsPackage") {
    group = "veyq"
    description = "Compiles the core for JavaScript and writes the module and its conformance runner into src/packages/core-js/dist."
    from(tasks.named<org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink>("compileProductionLibraryKotlinJs").flatMap { it.destinationDirectory })
    from(tasks.named<org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink>("compileConformanceProductionLibraryKotlinJs").flatMap { it.destinationDirectory }) {
        into("conformance")
    }
    into(coreJsDist)
}
// The plugin's check of the generated `.d.ts` runs `tsc` under Kotlin's own Node (ignored by default today); the package's
// own typecheck reads them instead, so the task never runs.
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.typescript.TypeScriptValidationTask>().configureEach { enabled = false }
