// `:apps:files` — the Files application (ADR 0079; docs/apps/files.md): every `me.raddatz.veyq.files.*` class. It
// depends on `:core`, and never the reverse (Gradle refuses the cycle); no application depends on another. The root
// `backend/` composes it into the one fast-jar, where its jar is `lib/main/me.raddatz.veyq.files.jar` — never deployed
// on its own.
//
// A skeleton today (cr-files-application F0): the node kind `FILE` and its placement rule. No `veyq.openapi` yet — Files
// has no spec until F1; its specs will go in `contracts/api/files/` (the directory name is the owner), and the build then
// refuses that directory until this file applies `id("veyq.openapi")` and calls `veyqOpenApi { generateOwnSpecs() }`.
// No migration either: `veyq_node.kind` (`VARCHAR(16)`, core's baseline) takes `FILE` as it is.
//
// Its tests (`./gradlew :apps:files:test`) boot core plus Files — not Memories: its own `%test` keys sit in
// `src/test/resources/application-test.properties` beside a comment-only `application.properties` anchor.
plugins {
    id("veyq.module") // buildSrc: the Kotlin, all-open and java-library plugins and what every module repeats
    // Only for this module's own `@QuarkusTest`s: a library `@QuarkusTest` fails without the plugin. Its application
    // tasks are disabled by `veyq.module` — the one fast-jar is the root's.
    id("io.quarkus")
}

dependencies {
    implementation(project(":core"))

    // The shared test helpers: `:core`'s test fixtures (`core/build.gradle.kts` says why they work from there).
    testImplementation(testFixtures(project(":core")))
    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation(kotlin("test"))
}
