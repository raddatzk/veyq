import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
}

// The core's JavaScript target (ADR 0155 decisions 3 and 6) is a library with no JavaScript environment: Gradle compiles,
// pnpm installs, bundles and tests (`src/packages/core-js/`). A `nodejs()` or `browser()` environment would make even
// the link task depend on Kotlin's own Node, Yarn and npm install (measured 2026-11-20 on Kotlin 2.4.21), so
// none is chosen, and the root plugin the environment would have applied — whose extension the link's sync task reads —
// is applied here. Its setup tasks never download (the settings refuse the repository a download adds,
// `FAIL_ON_PROJECT_REPOS`), and a task graph that reaches any of Kotlin's Node, Yarn or npm tasks fails before it runs.
apply<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin>()
the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().download.set(false)
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec>().download.set(false)
}
val kotlinJsTooling = setOf("kotlinNodeJsSetup", "kotlinYarnSetup", "kotlinNpmInstall", "kotlinStoreYarnLock", "kotlinRestoreYarnLock", "kotlinUpgradeYarnLock", "kotlinNpmCachesSetup", "kotlinKotlinNpmCachesSetup", "rootPackageJson", "kotlinPackageJsonUmbrella")
gradle.taskGraph.whenReady {
    val reached = allTasks.filter { it.name in kotlinJsTooling || it.name.endsWith("PackageJson") }.map { it.path }
    if (reached.isNotEmpty()) throw GradleException("Kotlin's own Node/Yarn/npm tooling never runs (ADR 0155 decision 6); this build reached $reached")
}


// ---- The core's modules (ADR 0155 decision 7.1): the platform `:core`, one `:apps:<application>` per `ApplicationId`,
// `:composition` (the one place naming every application: the registry's install, the Apple framework, the JavaScript
// package) and `:testing` (the test support the modules' tests share). Each declares the same targets and the same
// source-set hierarchy, so `commonMain`, `threadedMain`, `bridgedMain`, `posixMain` and `appleMain` mean the same in every
// one — declared once here, applied when a module applies the multiplatform plugin. A convention in this file rather
// than a `buildSrc` plugin: `buildSrc` would put a second Kotlin Gradle plugin on the build's classpath beside the one
// the version catalog applies, and the configuration cache is not enabled for this build either way.
fun Project.isCoreModule() = path == ":core" || path == ":composition" || path == ":testing" || path.startsWith(":apps:")

/** The platform's specs, the ones a member's app calls; an application's are every spec in `contracts/api/<application>/`. */
val coreSpecs = listOf("setup", "auth", "account", "scopes", "content", "favorites", "tenants", "blob", "sharing", "moderation")
val contracts = rootDir.resolve("../../contracts/api").canonicalFile
// The conformance suite reads the vectors from where every client reads them: contracts/crypto/vectors/v1.json.
val vectors = rootDir.resolve("../../contracts/crypto/vectors/v1.json").canonicalFile
// The web's Emergency Kit fixtures, which the kit's read order is held to (KitFixtureCases), on the JVM and Apple.
val kitFixtures = rootDir.resolve("../web/src/features/account/emergencyKit/__fixtures__").canonicalFile
// The import cases and the web's download both clients answer to (ADR 0152 decision 4), on the JVM and Apple.
val importCases = rootDir.resolve("../../contracts/export/import-cases").canonicalFile
// The kinds' facts the registry answers (`KindsContractTest`, inbox #1061), on the JVM.
val kinds = rootDir.resolve("../../contracts/platform/kinds").canonicalFile
val utf8procNormalizationTest = rootDir.resolve("build/utf8proc/NormalizationTest.txt")

subprojects {
    if (!isCoreModule()) return@subprojects
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(21)
            jvm()
            // JavaScript (ADR 0155 decision 3): the web's target, IR, ES modules, es2015, with TypeScript definitions and
            // no experimental flag. A module's own binaries are its business: the composition links the package's library
            // (each module its own file, Kotlin's per-module granularity), the platform its conformance runner.
            js {
                useEsModules()
                generateTypeScriptDefinitions()
                compilerOptions { target.set("es2015") }
            }
            iosArm64()
            iosSimulatorArm64()
            macosArm64()
            // tvOS (ADR 0159 decision 1): the TV's app links the same framework; `appleMain` is shared, and what the tvOS SDK
            // lacks (LocalAuthentication) is branched below it, in `iosMain`/`macosMain` against `tvosMain`.
            tvosArm64()
            tvosSimulatorArm64()
            linuxArm64()
            linuxX64()
            mingwX64()
            // The targets reached through the bridge (ADR 0161 decision 5), Linux and Windows, share `bridgedMain`; Apple and
            // Linux share `posixMain` (a file opened by its path as the bytes it is, where Windows takes it as UTF-16); the
            // tests that block a thread or run on a pool of them live in `threadedTest`, between `commonTest` and the JVM and
            // native targets (in `commonTest` they would fail `compileTestKotlinJs`). The default template is extended.
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
                common {
                    group("threaded") {
                        withJvm()
                        group("native")
                    }
                }
            }
            sourceSets.all {
                // The platform's API for its applications and the composition (`PlatformApi`, held by
                // scripts/validate_mobile_module_graph.py to these modules), and Kotlin/Native's `HiddenFromObjC`, which
                // every such member carries (an API opt-in, no compiler flag).
                languageSettings.optIn("me.raddatz.veyq.mobile.PlatformApi")
                languageSettings.optIn("kotlin.experimental.ExperimentalObjCRefinement")
            }
            sourceSets.getByName("commonMain").dependencies {
                // what the platform's API hands its applications: flows, serializable models, the generated client's Ktor
                api(libs.coroutines.core)
                api(libs.serialization.json)
                api(libs.ktor.client.core)
                api(libs.ktor.client.content.negotiation)
                api(libs.ktor.serialization.json)
            }
            sourceSets.getByName("commonTest").dependencies {
                implementation(kotlin("test"))
                implementation(libs.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
            // a live run's client (`VEYQ_LIVE`) on the JVM
            sourceSets.getByName("jvmTest").dependencies {
                implementation(libs.ktor.client.okhttp)
            }
        }

        // The API client generated from contracts/api, never written by hand (CLAUDE.md: API-first): the platform's specs
        // into `…mobile.api`, an application's into `…mobile.api.<application>` in its own module, so its models never
        // meet the platform's.
        val application = path.removePrefix(":apps:").takeIf { path.startsWith(":apps:") }
        val specs = when {
            path == ":core" -> coreSpecs.map { contracts.resolve("$it.openapi.yaml") to "" }
            application != null -> contracts.resolve(application).listFiles { f -> f.name.endsWith(".openapi.yaml") }.orEmpty().sorted().map { it to ".$application" }
            else -> emptyList()
        }
        if (specs.isNotEmpty()) {
            // The generator the backend and both web clients run, at the one version the repository pins
            // (scripts/validate_build_input_pins.py), with the same advisory floors as src/backend/buildSrc/build.gradle.kts;
            // loaded from a configuration of its own, never onto the build's classpath, where its libraries would meet
            // the Android plugin's (the root classpath resolved them to other versions: measured 2026-11-22, a
            // `checker-qual` 2.5.8 jar no build had verified).
            val openApiGenerator = configurations.create("openApiGenerator") {
                isCanBeConsumed = false
                isCanBeResolved = true
                attributes {
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
                    attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
                    attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
                    attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
                }
            }
            dependencies {
                openApiGenerator(libs.openapi.generator)
                constraints {
                    openApiGenerator("com.fasterxml.jackson.core:jackson-core:2.22.4")
                    openApiGenerator("com.fasterxml.jackson.core:jackson-databind:2.22.4")
                    openApiGenerator("com.google.guava:guava:33.7.2-jre")
                    openApiGenerator("com.github.jknack:handlebars:4.5.8")
                }
            }
            val generatedApi = layout.buildDirectory.dir("generated/openapi")
            val generateApi = tasks.register("generateApi") {
                group = "veyq"
                description = "Generates the Kotlin Multiplatform API client from contracts/api (openapi-generator, kotlin/multiplatform)."
                inputs.files(specs.map { it.first } + contracts.resolve("_common.openapi.yaml")).withPropertyName("specs")
                inputs.files(openApiGenerator).withPropertyName("generator")
                outputs.dir(generatedApi)
                doLast {
                    val out = generatedApi.get().asFile
                    out.deleteRecursively()
                    // its own class loader over the configuration, the platform's classes its parent: nothing of the build's
                    val loader = java.net.URLClassLoader(openApiGenerator.files.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader())
                    val thread = Thread.currentThread()
                    val before = thread.contextClassLoader
                    thread.contextClassLoader = loader
                    try {
                        specs.forEach { (spec, owner) ->
                            val configurator = loader.loadClass("org.openapitools.codegen.config.CodegenConfigurator").getConstructor().newInstance()
                            fun set(name: String, vararg values: String) {
                                configurator.javaClass.getMethod(name, *values.map { String::class.java }.toTypedArray()).invoke(configurator, *values)
                            }
                            set("setGeneratorName", "kotlin")
                            set("setLibrary", "multiplatform")
                            set("setInputSpec", spec.absolutePath)
                            set("setOutputDir", out.absolutePath)
                            set("setPackageName", "me.raddatz.veyq.mobile.api")
                            set("setApiPackage", "me.raddatz.veyq.mobile.api$owner.apis")
                            set("setModelPackage", "me.raddatz.veyq.mobile.api$owner.models")
                            for ((k, v) in listOf("dateLibrary" to "string", "enumUnknownDefaultCase" to "true", "hideGenerationTimestamp" to "true", "omitGradleWrapper" to "true", "omitGradlePluginVersions" to "true")) {
                                configurator.javaClass.getMethod("addAdditionalProperty", String::class.java, Any::class.java).invoke(configurator, k, v)
                            }
                            for (k in listOf("apiTests", "modelTests", "apiDocs", "modelDocs")) set("addGlobalProperty", k, "false")
                            val input = configurator.javaClass.getMethod("toClientOptInput").invoke(configurator)
                            val generator = loader.loadClass("org.openapitools.codegen.DefaultGenerator").getConstructor().newInstance()
                            generator.javaClass.getMethod("opts", loader.loadClass("org.openapitools.codegen.ClientOptInput")).invoke(generator, input)
                            generator.javaClass.getMethod("generate").invoke(generator)
                        }
                    } finally {
                        thread.contextClassLoader = before
                        loader.close()
                    }
                    // The generator writes its infrastructure (`me.raddatz.veyq.mobile.api.infrastructure`, `.auth`) for
                    // every spec; the platform's module owns it, an application's client uses the platform's.
                    if (application != null) out.walkTopDown().filter { it.isDirectory && it.name in setOf("infrastructure", "auth") && it.parentFile.name == "api" }.toList().forEach { it.deleteRecursively() }
                    // The specs carry the backend's server-side annotations as `x-*-extra-annotation` (Jakarta validation,
                    // Jackson deserializers): the server's checks, which a client neither has nor needs. A line that is
                    // nothing but such an annotation is dropped; anything else naming the backend's packages fails the
                    // build rather than ship.
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
            extensions.configure<KotlinMultiplatformExtension> {
                sourceSets.getByName("commonMain").kotlin.srcDir(generatedApi.map { it.dir("src/commonMain/kotlin") })
            }
            tasks.matching { it.name.startsWith("compile") || it.name.endsWith("SourcesJar") }.configureEach { dependsOn(generateApi) }
        }

        // What every module's tests read: the crypto vectors, the kit's fixtures, the import cases, the kinds' facts, a live
        // run's environment (`VEYQ_LIVE`, …), the search's opt-in volume measurement (`VEYQ_MEASURE`).
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
            System.getenv("VEYQ_MEASURE")?.let { environment("VEYQ_MEASURE", it) }
            testLogging { showStandardStreams = System.getenv("VEYQ_LIVE") != null || System.getenv("VEYQ_MEASURE") != null }
            inputs.file(vectors).withPropertyName("vectors")
            inputs.dir(kitFixtures).withPropertyName("kitFixtures")
            inputs.dir(importCases).withPropertyName("importCases")
            inputs.dir(kinds).withPropertyName("kinds")
        }
        tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
            environment("VEYQ_VECTORS", vectors.absolutePath)
            environment("VEYQ_ENV_FILE", rootDir.resolve("../../.run/.env").canonicalPath)
            environment("VEYQ_REPO_ROOT", rootDir.resolve("../..").canonicalPath)
            environment("VEYQ_NORMALIZATION_TEST", utf8procNormalizationTest.absolutePath)
            System.getenv("VEYQ_LIVE")?.let { environment("VEYQ_LIVE", it) }
            System.getenv("VEYQ_MEASURE")?.let { environment("VEYQ_MEASURE", it) }
            testLogging { showStandardStreams = System.getenv("VEYQ_LIVE") != null || System.getenv("VEYQ_MEASURE") != null }
            inputs.file(vectors).withPropertyName("vectors")
            inputs.dir(kitFixtures).withPropertyName("kitFixtures")
            inputs.dir(importCases).withPropertyName("importCases")
        }
    }
}
