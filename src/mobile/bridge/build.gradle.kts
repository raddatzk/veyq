// The native bridge (ADR 0127 decision 5; ADR 0123's Consequences, the bridge): the core as one shared library,
// `libveyq.so`, whose only public surface is the six C functions of `bridge.h`. It depends on `:core` as an
// implementation dependency, so the library exports none of the core's classes — only the `@CName` functions of
// `src/linuxMain`. The JVM target exists for the tests and for the generator of the Rust types (`generateBindings`).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    jvm()
    listOf(linuxArm64(), linuxX64()).forEach { target ->
        target.binaries.sharedLib {
            baseName = "veyq"
            // The static libraries linked in (libcurl and its OpenSSL from Ktor's Curl engine, libsodium, utf8proc) stay
            // inside: exported, a host whose libraries load their own libcrypto would bind to ours, or ours
            // to its, whichever loaded first.
            linkerOpts("-Wl,--exclude-libs,ALL")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.ktor.client.core)
        }
        linuxMain.dependencies {
            implementation(libs.ktor.client.curl)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.okhttp)
        }
    }
}

// The hand-written C surface and where the generated Rust goes: never committed (as the theme and the words).
val header = file("bridge.h")
val generatedBridge = rootDir.resolve("build/generated/bridge")

val jvmMain = kotlin.jvm().compilations.getByName("main")
val generateBindings = tasks.register<JavaExec>("generateBindings") {
    group = "veyq"
    description = "Writes bridge.rs (the Rust types of the bridge's wire, and its six extern \"C\" functions) into src/mobile/build/generated/bridge."
    dependsOn(jvmMain.compileTaskProvider)
    classpath = files(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
    mainClass.set("me.raddatz.veyq.mobile.bridge.BindingsKt")
    inputs.file(header).withPropertyName("header")
    outputs.dir(generatedBridge)
    args(header.absolutePath, generatedBridge.absolutePath)
}

// The header test reads the header Kotlin/Native writes beside the library it links, and the samples it writes are the
// Rust half's input (ADR 0123's Consequences, the bridge: the generated bridge.rs deserializes what the JVM wrote).
tasks.named<Test>("jvmTest") {
    val linked = tasks.named("linkDebugSharedLinuxArm64")
    dependsOn(linked)
    inputs.file(header).withPropertyName("header")
    systemProperty("veyq.bridgeHeader", header.absolutePath)
    systemProperty("veyq.knHeader", layout.buildDirectory.file("bin/linuxArm64/debugShared/libveyq_api.h").get().asFile.absolutePath)
    systemProperty("veyq.samples", generatedBridge.resolve("samples").absolutePath)
    systemProperty("veyq.repoRoot", rootDir.resolve("../..").canonicalPath)
    System.getenv("VEYQ_LIVE")?.let { environment("VEYQ_LIVE", it) }
    System.getenv("VEYQ_ENV_FILE")?.let { environment("VEYQ_ENV_FILE", it) }
    testLogging { showStandardStreams = System.getenv("VEYQ_LIVE") != null }
}
