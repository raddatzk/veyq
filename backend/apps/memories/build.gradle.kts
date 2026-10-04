// `:apps:memories` — the Memories application (ADR 0079): every `com.veyq.memories.*` class, the Memories specs'
// generated API (`openApiGenerateMemories`, `contracts/api/memories/`) and its migrations. It depends on `:core`, and
// never the reverse (Gradle refuses the cycle); no application depends on another. The root `backend/` composes it
// into the one fast-jar, where its jar is `lib/main/com.veyq.veyq-memories.jar` — never deployed on its own.
//
// No `io.quarkus` plugin: a module applies it only for `@QuarkusTest`s of its own (`gradle/module.gradle.kts`), and
// Memories' tests still run in the root until they move here (cr-platform-gradle-modules step 8a) — then this module
// also takes `testImplementation(project(":test-support"))` and its own `src/test/resources/application-test.properties`
// beside a comment-only `application.properties` anchor.
plugins {
    kotlin("jvm")
    kotlin("plugin.allopen")
    `java-library`
}

apply(from = "../../gradle/module.gradle.kts")
extra["veyq.openapi.owner"] = "memories"
apply(from = "../../gradle/openapi.gradle.kts")

dependencies {
    implementation(project(":core"))
}
