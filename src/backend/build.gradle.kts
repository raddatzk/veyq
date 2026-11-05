// The root `src/backend/` — the composition (ADR 0079): the Quarkus application whose `quarkusBuild` produces the one
// fast-jar the Dockerfile copies. The modules it depends on (`:core`, `:apps:memories`, `:apps:depot`, `:apps:expenses`, settings.gradle.kts) land in
// that fast-jar's `quarkus-app/lib/main`, never deployed on their own. The root holds no main code of its own: its
// `src/main/resources` carries the profile files beside their anchor, and its tests are the genuinely whole-app ones —
// they sweep or probe every module at once (`OperatorBlindProbeTest`, the posture sweeps, the entity sweep, the module
// composition); every other test lives in the module whose code it exercises.
plugins {
    // No version: `buildSrc` puts the Kotlin, all-open and Quarkus plugins on every build script's classpath, their
    // versions declared once in `gradle/libs.versions.toml`.
    kotlin("jvm")
    kotlin("plugin.allopen")
    id("io.quarkus")
    id("info.solidsoft.pitest") version "1.19.0" apply false // applied by :core, whose classes it mutates
}

repositories {
    mavenCentral()
}

// Versions lifted above the Quarkus BOM, each for a published advisory the BOM's own version carries and no Quarkus 3.x
// release fixes yet (measured 2026-11-05: 3.40.1.1 is the newest 3.x platform BOM). **Remove an entry, and its version in
// gradle/libs.versions.toml, once the Quarkus BOM pins that version or later.**
//   - `io.opentelemetry` (GHSA-ccrj-gw8r-854m, quadratic CPU in W3C `tracestate` parsing, fixed in opentelemetry-api
//     1.67.0; the BOM pins 1.62.0). veyq ships no OpenTelemetry extension — the API reaches the fast-jar only through
//     quarkus-scheduler's opentelemetry-instrumentation-api — so no request header reaches the propagator today; it is
//     lifted anyway, so the shipped jar carries no published advisory. The whole group moves together (released in
//     lockstep; `-alpha` is the incubator's suffix), and only that group: instrumentation and semconv keep the BOM's.
//   - `mariadb-java-client` (GHSA-h599-m3w5-9m7x, GHSA-4jq2-8v3c-97w8, GHSA-fhx5-2xqw-2cgm, GHSA-grfq-r75j-rw9x, fixed in
//     3.5.11; the BOM pins 3.5.10). It ships: quarkus-jdbc-mariadb puts it in the fast-jar's lib.
//   - `guava` (GHSA-xxph-c9ww-hj94, deserialization of its compact collections, fixed in 33.7.2; the BOM pins
//     33.6.0-jre). Nothing that ships carries it — only test, native-test and dev-mode deployment classpaths do (measured
//     2026-11-05: `runtimeClasspath` and `quarkusProdRuntimeClasspathConfiguration` hold none) — but those run on the
//     developer's and CI's machines. (buildSrc reads `guava` too, for a classpath no BOM reaches.)
// The BOM's own constraint is rewritten — a component metadata rule on `quarkus-bom` — rather than each configuration's
// resolution: the BOM is an `enforcedPlatform`, whose forced versions a plain constraint loses to, and a rule reaches
// every resolution in the project, Quarkus' own (app model, dev-mode bootstrap) and a plugin's detached configuration
// included. A per-configuration substitution, the first form of this block, reaches no detached configuration: the PIT
// plugin's (`addJUnitPlatformLauncherDependencyIfNeeded` resolves a detached copy of `:core`'s `testImplementation` to
// find the JUnit Platform version) kept the BOM's opentelemetry-api 1.62.0 in the graph `dependency-submission.yml`
// reports, and so its alert open after the jar no longer carried it (measured 2026-11-05 with a
// `DependencyResolutionListener` init script: `:core:detachedConfiguration12`).
val liftedAboveBom = libs.versions.opentelemetry.get().let { otel ->
    mapOf(
        "io.opentelemetry:opentelemetry-api" to otel,
        "io.opentelemetry:opentelemetry-context" to otel,
        "io.opentelemetry:opentelemetry-common" to otel,
        "io.opentelemetry:opentelemetry-api-incubator" to "$otel-alpha",
        "org.mariadb.jdbc:mariadb-java-client" to libs.versions.mariadb.jdbc.get(),
        "com.google.guava:guava" to libs.versions.guava.get(),
    )
}
val quarkusBomModule = libs.quarkus.bom.get().module.toString() // read here: inside `allprojects`, `libs` is the subproject's
allprojects {
    dependencies.components.withModule(quarkusBomModule) {
        allVariants {
            withDependencyConstraints {
                forEach { constraint ->
                    liftedAboveBom["${constraint.group}:${constraint.name}"]?.let { lifted ->
                        constraint.version { require(lifted) }
                        constraint.because("lifted above the Quarkus BOM for a published advisory (build.gradle.kts)")
                    }
                }
            }
        }
    }
}

// The Java version is the catalog's (gradle/libs.versions.toml), the same one every module's `veyq.module` reads.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get().toInt()))
    }
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))
    // Every Quarkus extension, the S3 client and the mailer reach the application through `:core`'s `api`.
    implementation(project(":core"))
    // The Memories application (`apps/memories/build.gradle.kts`): its code, generated API and migrations.
    implementation(project(":apps:memories"))
    // The Depot application (`apps/depot/build.gradle.kts`): its code (a skeleton: the `FILE` kind's placement rule).
    implementation(project(":apps:depot"))
    // The Expenses application (`apps/expenses/build.gradle.kts`): a Pot, its sealed revisions and their migration.
    implementation(project(":apps:expenses"))

    // The test helpers every module's tests use: `:core`'s test fixtures (`core/build.gradle.kts`).
    testImplementation(testFixtures(project(":core")))
    // Memories' content in the scope-access world the probe builds (`MemoriesWorldContent`, inbox #335).
    testImplementation(testFixtures(project(":apps:memories")))
    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation(kotlin("test"))
}

// Hibernate needs entity classes to be open + have a no-arg constructor.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.enterprise.context.ApplicationScoped")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    // The forked test JVM's default heap (~512m) OOMs partway through the full ~77-class Quarkus suite in one
    // run (measured: G1 heap pinned at ~505-520MB used, 0 survivor regions — genuine thrashing, not one test's
    // leak), taking a real assertion down with it as a knock-on flake (AuthRateLimitRefillTest's real-clock
    // polling window blows under the GC pressure). 2g clears it with headroom (measured green at ~2m45s).
    maxHeapSize = "2g"
}

// `OperatorBlindProbeTest` probes every owner's generated API. Which packages those are is `buildSrc`'s own derivation
// (`SpecSets`: core plus every `contracts/api/<application>/`), handed over here rather than listed in the test, so a new
// application's operations are in its set the day its spec directory exists (inbox #368). The test also scans its
// classpath for generated API packages and is red when the two disagree either way. The key stays outside `veyq.`:
// Quarkus refuses to start on a system property under a config mapping's prefix that maps to nothing (SRCFG00050).
tasks.named<Test>("test") {
    val sets = me.raddatz.veyq.buildlogic.SpecSets(
        rootDir.resolve("../../contracts/api"),
        me.raddatz.veyq.buildlogic.SpecSets.applicationModules(allprojects.map { it.path }),
    )
    val owners = listOf(me.raddatz.veyq.buildlogic.SpecSets.coreOwner) + sets.applications
    systemProperty("operatorBlindProbe.apiPackages", owners.joinToString(",") { sets.of(it).apiPackage })
    // `KindsContractTest` holds every module's kind seams to `contracts/platform/kinds/`: an input of the task, so an edit
    // there re-runs the suite instead of reading as up to date (measured: without it, a changed fact stayed green).
    inputs.dir(rootDir.resolve("../../contracts/platform/kinds"))
        .withPropertyName("kindsContract")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

kotlin {
    compilerOptions {
        javaParameters = true
        // A compiler warning fails the build, as in every module (buildSrc's `veyq.module` says how to meet one).
        allWarningsAsErrors = true
    }
}

// The third-party notices of what the fast-jar ships (buildSrc's ThirdPartyLicenses): every `quarkusBuild` writes them,
// the Dockerfile's and CI's included, so an unlisted license fails the build that would ship it. The Dockerfile copies
// the report into the image beside veyq's own LICENSE and NOTICE. `tasks.register<T>(name)`, never the delegated
// `by tasks.registering(T::class)`: Gradle 9.6 deprecated the delegate, and a deprecation fails this script's compilation.
val thirdPartyLicenses = tasks.register<me.raddatz.veyq.buildlogic.ThirdPartyLicenses>("thirdPartyLicenses") {
    dependsOn(tasks.named("quarkusBuild"))
    libDir.set(layout.buildDirectory.dir("quarkus-app/lib"))
    licenseTexts.set(layout.projectDirectory.dir("licenses"))
    // What Quarkus packages, not `runtimeClasspath`: its conditional dependencies (quarkus-rest-kotlin,
    // quarkus-vertx-kotlin) reach lib/main only through this configuration.
    artifacts.set(
        configurations.named("quarkusProdRuntimeClasspathConfiguration").flatMap { it.incoming.artifacts.resolvedArtifacts },
    )
    // A module's POM, each already pinned in gradle/verification-metadata.xml: resolving the graph read them all. Online
    // through an artifact query; under `--offline` out of Gradle's file store, verified against that pin (buildSrc's
    // CachedPoms says why: an artifact query, and a `g:a:v@pom` dependency alike, reads the artifact index, which only an
    // online query fills — measured 2026-11-02: offline, 0 of 273 POMs resolved and the build failed after quarkusBuild).
    val deps = dependencies
    val offline = gradle.startParameter.isOffline
    val cachedPoms = me.raddatz.veyq.buildlogic.CachedPoms(
        gradle.gradleUserHomeDir.resolve("caches/modules-2/files-2.1"),
        layout.projectDirectory.file("gradle/verification-metadata.xml").asFile,
    )
    pomResolver.set(
        me.raddatz.veyq.buildlogic.PomResolver { g, a, v ->
            if (offline) return@PomResolver cachedPoms.pom(g, a, v)
            deps.createArtifactResolutionQuery().forModule(g, a, v)
                .withArtifacts(org.gradle.maven.MavenModule::class.java, org.gradle.maven.MavenPomArtifact::class.java)
                .execute().resolvedComponents.single()
                .getArtifacts(org.gradle.maven.MavenPomArtifact::class.java).single()
                .let {
                    (it as? org.gradle.api.artifacts.result.ResolvedArtifactResult)?.file
                        ?: throw GradleException("$g:$a:$v: no POM", (it as org.gradle.api.artifacts.result.UnresolvedArtifactResult).failure)
                }
        },
    )
    report.set(layout.buildDirectory.file("third-party-licenses/THIRD-PARTY-LICENSES.txt"))
}
tasks.named("quarkusBuild") { finalizedBy(thirdPartyLicenses) }
