// `:apps:memories` — the Memories application (ADR 0079): every `com.veyq.memories.*` class, the Memories specs'
// generated API (`openApiGenerateMemories`, `contracts/api/memories/`) and its migrations. It depends on `:core`, and
// never the reverse (Gradle refuses the cycle); no application depends on another. The root `backend/` composes it
// into the one fast-jar, where its jar is `lib/main/com.veyq.veyq-memories.jar` — never deployed on its own.
//
// Its tests (`./gradlew :apps:memories:test`) are the Memories application's own and the core behaviour that needs an
// application at runtime — every share creation (core refuses a share on a kind no `ShareGuestWrites` supports), every
// `MEMORY`/`CHAPTER` placement, the seams' registered partitions. They boot core plus Memories: its own `%test` keys sit
// in `src/test/resources/application-test.properties` beside a comment-only `application.properties` anchor.
plugins {
    kotlin("jvm")
    kotlin("plugin.allopen")
    `java-library`
    // Only for this module's own `@QuarkusTest`s: a library `@QuarkusTest` fails without the plugin. Its application
    // tasks are disabled in gradle/module.gradle.kts — the one fast-jar is the root's.
    id("io.quarkus")
}

apply(from = "../../gradle/module.gradle.kts")
extra["veyq.openapi.owner"] = "memories"
apply(from = "../../gradle/openapi.gradle.kts")

dependencies {
    implementation(project(":core"))

    // The shared test helpers (`test-support/build.gradle.kts` says why a project, not `java-test-fixtures`).
    testImplementation(project(":test-support"))
    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation(kotlin("test"))
}
