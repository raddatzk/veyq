// The native bridge (ADR 0129 decision 5; ADR 0123's Consequences, the bridge): the core as one shared library,
// `libveyq.so` on Linux and `veyq.dll` on Windows, whose only public surface is the six C functions of `bridge.h`. It
// depends on `:core` as an implementation dependency, so the library exports none of the core's classes — only the
// `@CName` functions of `src/bridgedMain` (Linux and Windows, as in `:core`; each brings only its HTTP engine). The JVM
// target exists for the tests and for the generator of the Rust and the C# types (`generateBindings`).
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    jvm()
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyHierarchyTemplate(KotlinHierarchyTemplate.default) {
        group("native") {
            group("bridged") {
                group("linux")
                group("mingw")
            }
        }
    }
    mingwX64 {
        binaries.sharedLib {
            baseName = "veyq"
            // veyq.dll exports what the .def Kotlin/Native writes beside it names — bridge.h's six and veyq_symbols —
            // and nothing else: without a .def, lld exports every symbol of the link (winpthreads', the unwinder's,
            // iconv's; 185 measured 2026-11-10), as a MinGW link does when nothing is marked dllexport.
            // `windows/run.py bridge` reads the export table.
            linkerOpts(outputDirectory.resolve("veyq.def").absolutePath)
        }
    }
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
        mingwMain.dependencies {
            implementation(libs.ktor.client.winhttp)
        }
        // core's in-memory device keys (SoftwareDeviceKeys), the fake every flow test runs over, compiled here too.
        jvmTest {
            kotlin.srcDir(project(":core").file("src/testDevice/kotlin"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.okhttp)
        }
    }
}

// The hand-written C surface and where the generated Rust and C# go: never committed (as the theme and the words).
val header = file("bridge.h")
val generatedBridge = rootDir.resolve("build/generated/bridge")

val jvmMain = kotlin.jvm().compilations.getByName("main")
val generateBindings = tasks.register<JavaExec>("generateBindings") {
    group = "veyq"
    description = "Writes bridge.rs and Bridge.g.cs (the Rust and the C# types of the bridge's wire, and its six C functions) into src/mobile/build/generated/bridge."
    dependsOn(jvmMain.compileTaskProvider)
    classpath = files(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
    mainClass.set("me.raddatz.veyq.mobile.bridge.BindingsKt")
    inputs.file(header).withPropertyName("header")
    outputs.dir(generatedBridge)
    args(header.absolutePath, generatedBridge.absolutePath)
}

// The header test reads the header Kotlin/Native writes beside each library it links, and the samples WireTest writes
// are the Rust and the C# half's input (ADR 0123's Consequences, the bridge: the generated bridge.rs and Bridge.g.cs
// deserialize what the JVM wrote).
tasks.named<Test>("jvmTest") {
    dependsOn("linkDebugSharedLinuxArm64", "linkDebugSharedMingwX64")
    inputs.file(header).withPropertyName("header")
    systemProperty("veyq.bridgeHeader", header.absolutePath)
    // Kotlin/Native's header of each bridged family, which BridgeHeaderTest holds bridge.h against: Linux's and Windows'
    systemProperty(
        "veyq.knHeaders",
        listOf("bin/linuxArm64/debugShared/libveyq_api.h", "bin/mingwX64/debugShared/veyq_api.h")
            .joinToString(File.pathSeparator) { layout.buildDirectory.file(it).get().asFile.absolutePath },
    )
    systemProperty("veyq.samples", generatedBridge.resolve("samples").absolutePath)
    systemProperty("veyq.repoRoot", rootDir.resolve("../..").canonicalPath)
    System.getenv("VEYQ_LIVE")?.let { environment("VEYQ_LIVE", it) }
    System.getenv("VEYQ_ENV_FILE")?.let { environment("VEYQ_ENV_FILE", it) }
    testLogging { showStandardStreams = System.getenv("VEYQ_LIVE") != null }
}
