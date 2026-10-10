// The root `backend/` — the composition (ADR 0079): the Quarkus application whose `quarkusBuild` produces the one
// fast-jar the Dockerfile copies. The modules it depends on (`:core`, `:apps:memories`, `:apps:files`, settings.gradle.kts) land in
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

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))
    // Every Quarkus extension, the S3 client and the mailer reach the application through `:core`'s `api`.
    implementation(project(":core"))
    // The Memories application (`apps/memories/build.gradle.kts`): its code, generated API and migrations.
    implementation(project(":apps:memories"))
    // The Files application (`apps/files/build.gradle.kts`): its code (a skeleton: the `FILE` kind's placement rule).
    implementation(project(":apps:files"))

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
// (`SpecSets`: core plus every `contracts/api/<app>/`), handed over here rather than listed in the test, so a new
// application's operations are in its set the day its spec directory exists (inbox #368). The test also scans its
// classpath for generated API packages and is red when the two disagree either way. The key stays outside `veyq.`:
// Quarkus refuses to start on a system property under a config mapping's prefix that maps to nothing (SRCFG00050).
tasks.named<Test>("test") {
    val sets = me.raddatz.veyq.buildlogic.SpecSets(
        rootDir.resolve("../contracts/api"),
        me.raddatz.veyq.buildlogic.SpecSets.applicationModules(allprojects.map { it.path }),
    )
    val owners = listOf(me.raddatz.veyq.buildlogic.SpecSets.coreOwner) + sets.applications
    systemProperty("operatorBlindProbe.apiPackages", owners.joinToString(",") { sets.of(it).apiPackage })
}

kotlin {
    compilerOptions {
        javaParameters = true
    }
}
