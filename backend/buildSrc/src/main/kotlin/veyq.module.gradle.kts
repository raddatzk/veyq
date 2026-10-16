// `veyq.module` — what every backend module repeats (ADR 0079). A module is a subproject below `backend/` (`:core`,
// `:apps:memories`) whose jar the root's `quarkusBuild` puts into `quarkus-app/lib/main` — it is never
// built, published or deployed on its own: the one deliverable is the root's fast-jar.
//
// The Kotlin, all-open and Quarkus plugins come from `buildSrc`'s classpath, so no build file names their version:
// every version is declared once, in `gradle/libs.versions.toml`.
//
// **The jar is named by the group, never renamed.** Quarkus names a `quarkus-app/lib/main` entry
// `<group>.<project name>.jar` — `me.raddatz.veyq.core.jar`, `me.raddatz.veyq.memories.jar` — so the group is set here and nowhere
// else. Never set an `archivesName`: Quarkus cannot map a renamed project's test-fixtures jar, and a
// `testFixtures(project(…))` dependency then silently drops out of a `@QuarkusTest`'s application (measured
// 2026-10-05, Quarkus 3.36.0). `scripts/validate_backend_module_graph.py` rule (f) holds both.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.allopen")
    `java-library`
}

group = "me.raddatz.veyq"

repositories {
    mavenCentral()
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

// Hibernate needs entity classes to be open + have a no-arg constructor (the root's all-open list, the same three).
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.enterprise.context.ApplicationScoped")
}

kotlin {
    compilerOptions {
        javaParameters.set(true)
        // The generated lists carry their cascade as `List<@Valid Item>` (`ModelCascade`, HV000271): Kotlin writes a
        // type-argument annotation into the bytecode only with this flag — drop it and the cascade silently vanishes.
        freeCompilerArgs.add("-Xemit-jvm-type-annotations")
    }
}

// A module is a library: what it builds on reaches its dependants through `api`, the BOM (the root's version) too,
// so a dependant and the module's own test fixtures resolve the same versions.
val quarkusBom = the<VersionCatalogsExtension>().named("libs").findLibrary("quarkus-bom").get()
dependencies {
    api(enforcedPlatform(quarkusBom))
}

// **Every module is indexed.** A module's jar carries an empty `src/main/resources/META-INF/beans.xml`: without it
// Quarkus does not index the jar, its injected beans fail the build, and — silently — an `@All` list loses the
// module's implementations and its `@Path` interfaces answer 404 (validate_backend_module_graph.py rule (c)).

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    // The root's reason, unchanged: a forked test JVM's default heap OOMs partway through a Quarkus suite.
    maxHeapSize = "2g"
    // Every backend test runs from `backend/`, as it did before the split: tests read `../contracts/…` and write
    // under `build/` relative to the working directory, and a module's test must not see a different tree.
    workingDir = rootProject.projectDir
}

// The Quarkus plugin is applied to a module only so its own `@QuarkusTest`s can boot (a library `@QuarkusTest`
// fails without it). Its application tasks belong to the root alone: `./gradlew quarkusBuild` and `quarkusDev`,
// run from `backend/` as the Dockerfile, CI and the root launcher's `backend:run` do, select the task in
// every project that has it — so a module's would build a second `quarkus-app` or start a second dev server. A
// disabled task's dependencies still run, so the chain below `quarkusBuild` is cut too (measured: the module's
// `quarkusAppPartsBuild` ran, and failed, under a disabled `quarkusBuild`).
plugins.withId("io.quarkus") {
    val applicationTasks = setOf(
        "quarkusBuild", "quarkusAppPartsBuild", "quarkusDependenciesBuild", "quarkusDev", "quarkusRun",
        "quarkusRemoteDev", "quarkusTest", "quarkusIntTest", "imageBuild", "imagePush", "deploy",
        "buildAotEnhancedImage", "buildNative", "testNative",
    )
    tasks.named { it in applicationTasks }.configureEach {
        enabled = false
        setDependsOn(emptyList<Any>())
    }
}
