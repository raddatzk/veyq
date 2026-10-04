// What every backend module repeats (ADR 0079; cr-platform-gradle-modules step 6). A module is a subproject below
// `backend/` (`:core`, `:apps:memories`) whose jar the root's `quarkusBuild` puts into `quarkus-app/lib/main` — it is never built,
// published or deployed on its own: the one deliverable is the root's fast-jar.
//
// Applied with `apply(from = …)` AFTER the module's own `plugins {}` block, which applies the Kotlin, all-open and
// Quarkus plugins without a version: the versions are declared once, in the root's `plugins {}`. A script plugin
// cannot see the Kotlin Gradle plugin's types, so the Kotlin extensions are configured through their names.
//
// **The jar is named, not left to Gradle.** Without a group and an archivesName the module's jar would be
// `core-unspecified.jar` in `quarkus-app/lib/main`, a name a third-party `core` artifact can collide with. So the
// group is `com.veyq` and the archivesName `veyq-<module>`, set here and nowhere else —
// `scripts/validate_backend_module_graph.py` rule (f) holds both.

group = "com.veyq"
extensions.configure<org.gradle.api.plugins.BasePluginExtension> { archivesName.set("veyq-${project.name}") }

repositories {
    mavenCentral()
}

extensions.configure<JavaPluginExtension> {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

// Hibernate needs entity classes to be open + have a no-arg constructor (the root's all-open list, the same three).
extensions.getByName("allOpen").withGroovyBuilder {
    "annotation"("jakarta.persistence.Entity")
    "annotation"("jakarta.persistence.MappedSuperclass")
    "annotation"("jakarta.enterprise.context.ApplicationScoped")
}

extensions.getByName("kotlin").withGroovyBuilder {
    @Suppress("UNCHECKED_CAST")
    ((getProperty("compilerOptions") as Any).withGroovyBuilder { getProperty("javaParameters") } as Property<Boolean>).set(true)
}

// A module is a library: what it builds on reaches its dependants through `api`, the BOM (the root's version) too,
// so a dependant and the module's own test fixtures resolve the same versions.
apply(plugin = "java-library")
dependencies {
    "api"(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.36.0"))
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
// run from `backend/` as the Dockerfile, CI and the IntelliJ `Run Backend` configuration do, select the task in
// every project that has it — so a module's would build a second `quarkus-app` or start a second dev server. A
// disabled task's dependencies still run, so the chain below `quarkusBuild` is disabled too (measured: the
// module's `quarkusAppPartsBuild` ran, and failed, under a disabled `quarkusBuild`).
plugins.withId("io.quarkus") {
    val applicationTasks = setOf(
        "quarkusBuild", "quarkusAppPartsBuild", "quarkusDependenciesBuild", "quarkusDev", "quarkusRun",
        "quarkusRemoteDev", "quarkusTest", "quarkusIntTest", "imageBuild", "imagePush", "deploy",
        "buildAotEnhancedImage", "buildNative", "testNative",
    )
    tasks.matching { it.name in applicationTasks }.configureEach {
        enabled = false
        setDependsOn(emptyList<Any>())
    }
}
