import java.net.URI
import java.security.MessageDigest

// The platform module (ADR 0155 decision 7.1): what every veyq app shares and every application builds on (ADR 0123,
// ADR 0079) — the v1 crypto suite, the platform's API client, the session, the navigation model, the registry of kinds
// (`AppKinds`) the composition fills. It names no application: `scripts/validate_mobile_module_graph.py` holds it to no
// project dependency. Its targets and hierarchy are every core module's (src/mobile/build.gradle.kts): the JVM (which
// the Android app consumes, and where the conformance suite runs fastest), JavaScript, the Apple platforms (macOS, iOS and tvOS, packed by
// `:composition` into one XCFramework, `VeyqCore`), Linux on arm64 and x86-64 (ADR 0136 decision 4) and Windows on
// x86-64 (decision 5), cross-compiled from this build's host by Kotlin/Native's own toolchain.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
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
    description = "Builds libsodium for macOS, iOS, the iOS simulator, tvOS, the tvOS simulator (arm64), Linux (arm64, x86-64) and Windows (x86-64) into src/mobile/build/libsodium."
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
    description = "Builds libwebp for Linux (arm64, x86-64), Windows (x86-64) and Apple (macOS, iOS, tvOS, their simulators) into src/mobile/build/libwebp."
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
    // JavaScript (ADR 0155 decision 3): the suite's conformance runs under Node through `@veyq/core-js`, from the
    // `conformance` compilation's binary of its own — never the shipped package's export, which `:composition` links.
    // No environment is chosen (src/mobile/build.gradle.kts says why), so no Kotlin/JS test task exists: the common
    // tests compile for JavaScript (`compileTestKotlinJs`).
    js {
        val main = compilations.getByName("main")
        val conformance = compilations.create("conformance") { associateWith(main) }
        binaries.library(conformance)
    }

    // The Apple primitives are libsodium (ADR 0123 decision 3): one slice of build_libsodium.py's output per target.
    val sodiumSlices = mapOf(
        "iosArm64" to "ios_arm64", "iosSimulatorArm64" to "ios_simulator_arm64", "macosArm64" to "macos_arm64",
        "tvosArm64" to "tvos_arm64", "tvosSimulatorArm64" to "tvos_simulator_arm64",
    )
    listOf(iosArm64(), iosSimulatorArm64(), macosArm64(), tvosArm64(), tvosSimulatorArm64()).forEach { target ->
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
            kotlin.srcDir(generatedMajor)
            kotlin.srcDir(generatedTheme)
            kotlin.srcDir(generatedWords)
        }
        commonMain.dependencies {
            implementation(libs.kotlincrypto.sha3)
        }
        // The in-memory device keys and device of the flows' tests (`SoftwareDeviceKeys`, `testDevice`) and the live runs'
        // sign-in and clean-up (`LiveRun`): the shared test support, which every module's tests and :bridge's compile.
        commonTest.dependencies {
            implementation(project(":testing"))
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

tasks.matching { it.name.startsWith("compile") || it.name.endsWith("SourcesJar") }.configureEach { dependsOn(generateMajor, generateTheme, generateWords) }
tasks.matching { it.name.startsWith("cinteropSodium") }.configureEach { dependsOn(buildLibsodium) }
// The Linux slices are compiled by Kotlin/Native's own toolchain, which this task downloads (core/konan_toolchain.py).
listOf(buildLibsodium, buildUtf8proc, buildLibwebp).forEach { it.configure { dependsOn("downloadKotlinNativeDistribution") } }
tasks.matching { it.name.startsWith("cinteropUtf8proc") }.configureEach { dependsOn(buildUtf8proc) }
tasks.matching { it.name.startsWith("cinteropWebp") }.configureEach { dependsOn(buildLibwebp) }

// The plugin's check of the generated `.d.ts` runs `tsc` under Kotlin's own Node (ignored by default today); the package's
// own typecheck reads them instead, so the task never runs.
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.typescript.TypeScriptValidationTask>().configureEach { enabled = false }
