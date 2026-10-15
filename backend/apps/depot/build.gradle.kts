// `:apps:depot` — the Depot application (ADR 0079; docs/apps/files.md): every `me.raddatz.veyq.depot.*` class. It
// depends on `:core`, and never the reverse (Gradle refuses the cycle); no application depends on another. The root
// `backend/` composes it into the one fast-jar, where its jar is `lib/main/me.raddatz.veyq.depot.jar` — never deployed
// on its own.
//
// The node kind `FILE` and its placement rule (cr-files-application F0), a member's upload, replace and listing of a
// file's blob (F1a): the Depot specs' generated API (`openApiGenerateDepot`, `contracts/api/depot/`, package
// `me.raddatz.veyq.depot.generated`). No migration: `veyq_node.kind` (`VARCHAR(16)`) takes `FILE` as it is, and a
// file's blob lives in core's `veyq_node_attachment`.
//
// Its tests (`./gradlew :apps:depot:test`) boot core plus Depot — not Memories: its own `%test` keys sit in
// `src/test/resources/application-test.properties` beside a comment-only `application.properties` anchor.
plugins {
    id("veyq.module") // buildSrc: the Kotlin, all-open and java-library plugins and what every module repeats
    id("veyq.openapi")
    // Only for this module's own `@QuarkusTest`s: a library `@QuarkusTest` fails without the plugin. Its application
    // tasks are disabled by `veyq.module` — the one fast-jar is the root's.
    id("io.quarkus")
}

veyqOpenApi { generateOwnSpecs() } // `contracts/api/depot/`, by its path `:apps:depot`

dependencies {
    implementation(project(":core"))

    // The shared test helpers: `:core`'s test fixtures (`core/build.gradle.kts` says why they work from there).
    testImplementation(testFixtures(project(":core")))
    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation(kotlin("test"))
}
