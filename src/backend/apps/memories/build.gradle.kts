// `:apps:memories` — the Memories application (ADR 0079): every `me.raddatz.veyq.memories.*` class, the Memories specs'
// generated API (`openApiGenerateMemories`, `contracts/api/memories/`) and its migrations. It depends on `:core`, and
// never the reverse (Gradle refuses the cycle); no application depends on another. The root `src/backend/` composes it
// into the one fast-jar, where its jar is `lib/main/me.raddatz.veyq.memories.jar` — never deployed on its own.
//
// Its tests (`./gradlew :apps:memories:test`) are the Memories application's own and the core behaviour that needs an
// application at runtime — every share creation (core refuses a share on a kind no `ShareGuestWrites` supports), every
// `MEMORY`/`CHAPTER` placement, the seams' registered partitions. They boot core plus Memories: its own `%test` keys sit
// in `src/test/resources/application-test.properties` beside a comment-only `application.properties` anchor.
plugins {
    id("veyq.module") // buildSrc: the Kotlin, all-open and java-library plugins and what every module repeats
    id("veyq.openapi")
    // Only for this module's own `@QuarkusTest`s: a library `@QuarkusTest` fails without the plugin. Its application
    // tasks are disabled by `veyq.module` — the one fast-jar is the root's.
    id("io.quarkus")
    // Memories' own test helpers that another project's tests need (`src/testFixtures`): its content in the
    // scope-access world (`MemoriesWorldContent`, a CDI bean beside its own `META-INF/beans.xml`), consumed by this
    // module's tests and the root's `OperatorBlindProbeTest` as `testImplementation(testFixtures(project(":apps:memories")))`
    // (inbox #335). Like `:core`'s, they work from a fixtures jar only while no jar is renamed.
    `java-test-fixtures`
}

veyqOpenApi { generateOwnSpecs() } // `contracts/api/memories/`, by its path `:apps:memories`

dependencies {
    implementation(project(":core"))

    // The shared test helpers: `:core`'s test fixtures (`core/build.gradle.kts` says why they work from there).
    testImplementation(testFixtures(project(":core")))
    // The fixtures' public types are core's fixtures' (`WorldContent`, `ScopeAccessWorld.Space`): `api`, so a consumer
    // compiles against them.
    testFixturesApi(testFixtures(project(":core")))
    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation(kotlin("test"))
}
