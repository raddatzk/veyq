// `:apps:expenses` — the Expenses application (ADR 0079, ADR 0108; docs/apps/expenses.md): every `me.raddatz.veyq.expenses.*`
// class. It depends on `:core`, and never the reverse (Gradle refuses the cycle); no application depends on another. The
// root `src/backend/` composes it into the one fast-jar, where its jar is `lib/main/me.raddatz.veyq.expenses.jar` — never
// deployed on its own.
//
// The node kind `EXPENSES_POT` and its share roles, and a Pot's records: sealed revisions in its own table
// (`veyq_expenses_revision`), appended and never overwritten, read by members and link guests, erased by a member's
// erasure of a participant, re-wrapped by a re-key, held by a report (the Expenses spec's generated API,
// `openApiGenerateExpenses`, `contracts/api/expenses/`, package `me.raddatz.veyq.expenses.generated`). Every amount is
// sealed: the server stores ciphertext and the counts and times ADR 0108 § Consequences names.
//
// Its tests (`./gradlew :apps:expenses:test`) boot core plus Expenses — not Memories, not Depot: its own `%test` keys sit
// in `src/test/resources/application-test.properties` beside a comment-only `application.properties` anchor.
plugins {
    id("veyq.module") // buildSrc: the Kotlin, all-open and java-library plugins and what every module repeats
    id("veyq.openapi")
    // Only for this module's own `@QuarkusTest`s: a library `@QuarkusTest` fails without the plugin. Its application
    // tasks are disabled by `veyq.module` — the one fast-jar is the root's.
    id("io.quarkus")
}

veyqOpenApi { generateOwnSpecs() } // `contracts/api/expenses/`, by its path `:apps:expenses`

dependencies {
    implementation(project(":core"))

    // The shared test helpers: `:core`'s test fixtures (`core/build.gradle.kts` says why they work from there).
    testImplementation(testFixtures(project(":core")))
    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation(kotlin("test"))
}
