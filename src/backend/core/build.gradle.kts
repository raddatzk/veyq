// `:core` — the platform (ADR 0079): every `me.raddatz.veyq.core.*` class, core's generated API (`openApiGenerateCore`),
// core's migrations and the base `application.properties`. The root `src/backend/` composes it into the one fast-jar.
plugins {
    id("veyq.module") // buildSrc: the Kotlin, all-open and java-library plugins and what every module repeats
    id("veyq.openapi")
    // Only for this module's own `@QuarkusTest`s: a library `@QuarkusTest` fails without the plugin. Its
    // application tasks are disabled by `veyq.module` — the one fast-jar is the root's.
    id("io.quarkus")
    // Mutation testing — the trust chain's assertion-strength gate on the pure policy tier (ADR 0045 §2). Deliberately
    // WITHOUT the archived `pitest/pitest-kotlin` mutators (read-only since 2023, successor is the commercial arcmutate
    // → both barred by CLAUDE.md's no-unmaintained-deps + self-hostability invariants). The spike measures whether a
    // pure policy over non-null types needs that Kotlin noise filter at all. NOT wired into `check`: a heavier, scoped,
    // opt-in `./gradlew pitest` — mirrors web's `pnpm run mutation` staying out of `pnpm run check`. Every class it
    // mutates is core's, so the plugin, its scope file and its history live in this module.
    id("info.solidsoft.pitest")
    // The test helpers this module's tests, every application's and the root's share live in `src/testFixtures`
    // (`TestFixtures`, `ScopeAccessWorld`, the race harness, the blob-store fakes): consumed as
    // `testImplementation(testFixtures(project(":core")))`, a workspace module in the `@QuarkusTest` application
    // model like `:core` itself, so its classes load in the application's class loader beside the entities, its CDI
    // fakes are beans (its own `META-INF/beans.xml`) and `Arc.container()` answers (measured 2026-10-05, Quarkus 3.36.0,
    // inbox #333). It holds only core's helpers: core's fixtures never depend on an application
    // (validate_backend_module_graph.py rule (b)). It works only while no jar is renamed (`buildSrc`'s `veyq.module`).
    `java-test-fixtures`
}

veyqOpenApi { generateOwnSpecs() } // `contracts/api/*.openapi.yaml`, by its path `:core`

// veyq's two fixed texts — the operator section of the terms (ADR 0035 decision 6) and the instance privacy notice
// (decision 8) — ship in the jar from their one source in `contracts/terms/`, read at startup by `core/terms/
// OperatorSections` and `PrivacyNotices` (`OperatorSectionText.RESOURCE`, `PrivacyNoticeText.RESOURCE`). A missing file
// fails the build here rather than a started server, since `from` alone copies nothing silently; the Dockerfile copies
// the files too.
val fixedTexts = listOf("operator-section.json", "privacy-notice.json").map { rootDir.resolve("../../contracts/terms/$it") }
tasks.processResources {
    inputs.files(fixedTexts)
    from(fixedTexts) { into("veyq/terms") }
}

// The settings of the retired streaming music (removed by ADR 0135), left over as an instance that ran it
// would leave them: every test JVM of this module carries every former `VEYQ_MUSIC_*` variable, so every test runs on such
// an instance and `FormerMusicSettingsSliceTest` asserts that nothing reads them (the policies, the hosts, the register).
// In the environment, where an operator sets them: the same keys in a properties file refuse the boot (SRCFG00050, a key
// under `veyq.` that maps to nothing), which a leftover variable must not and does not.
tasks.test {
    environment("VEYQ_MUSIC_RELEASED", "true")
    environment("VEYQ_MUSIC_DOMAIN", "music.veyq.test")
    environment("VEYQ_MUSIC_SPOTIFY_CLIENT_ID", "0123456789abcdef0123456789abcdef")
    environment(
        "VEYQ_MUSIC_APPLE_DEVELOPER_TOKEN",
        "eyJhbGciOiJFUzI1NiIsImtpZCI6IktFWTEyMyJ9.eyJpc3MiOiJURUFNMTIzIiwiaWF0IjoxNzAwMDAwMDAwLCJleHAiOjQxMDI0NDQ4MDB9.c2ln",
    )
}

dependencies {
    // `api`: the root and every application build on these extensions through core.
    api("io.quarkus:quarkus-arc")
    api("io.quarkus:quarkus-kotlin")
    api("io.quarkus:quarkus-rest")
    api("io.quarkus:quarkus-rest-jackson")
    api("io.quarkus:quarkus-hibernate-validator")
    api("io.quarkus:quarkus-security") // central auth: SecurityIdentity + @RolesAllowed (cr-0012)
    api("io.quarkus:quarkus-hibernate-orm-panache-kotlin")
    api("io.quarkus:quarkus-scheduler") // @Scheduled cleanup jobs (first use: tenant-invite purge)
    api("io.quarkus:quarkus-jdbc-mariadb") // the store of every profile but `%test` (prod, dev, e2e)
    api("io.quarkus:quarkus-flyway")
    api("org.flywaydb:flyway-mysql") // Flyway 12 keeps MariaDB/MySQL support out of flyway-core
    api("software.amazon.awssdk:s3:2.57.11") // S3 blob store: vendor-neutral S3 client + presigner (ADR 0013)
    api("io.quarkus:quarkus-mailer") // generic SMTP transport (transitive Vert.x MailClient), no vendor SDK (ADR 0048)
    // ADR 0064's Kubernetes (Gateway API) sink. Chosen over a bare fabric8 client for `@WithKubernetesTestServer`
    // (the root's tests) as much as for the client itself: this repository has no cluster and no CI that could get
    // one, so a fake control plane inside the test JVM is the only proof KubernetesGatewaySink can ever offer.
    api("io.quarkus:quarkus-kubernetes-client")

    // What the fixtures build on reaches their consumers too (`FakePresignProfile` is a `QuarkusTestProfile`,
    // `givenAdminChannel` a RestAssured spec). `:core`'s main classes need no line: the plugin gives the fixtures them.
    testFixturesApi("io.quarkus:quarkus-junit")
    testFixturesApi("io.rest-assured:rest-assured")
    // The `%test` store (each project's application-test.properties: in-memory H2, `MODE=MariaDB`) and nothing else's,
    // so it rides the fixtures every project's tests consume rather than `api`, which put the driver in the shipped
    // fast-jar. The db-kind is build-time, so no profile of the prod-built jar could have used it.
    testFixturesApi("io.quarkus:quarkus-jdbc-h2")
    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation(kotlin("test"))
    testImplementation("io.quarkus:quarkus-test-kubernetes-client") // @WithKubernetesTestServer: a fabric8 mock API server in-JVM
    testImplementation("com.icegreen:greenmail:2.1.15") // hermetic in-JVM SMTP server: assert the real sent mail (ADR 0048/0049)

    // PIT's JUnit-5 runner (our tests are JUnit Platform). 1.2.3 requires PIT >= 1.19.4 — pinned together below.
    pitest("org.pitest:pitest-junit5-plugin:1.2.3")
    // PIT 1.19.6 -> commons-text 1.12.0 asks commons-lang3 3.14.0 (GHSA-j288-q9x7-2f5v, uncontrolled recursion, fixed
    // in 3.18.0). A floor rather than a PIT bump: the first PIT whose commons-text asks a fixed one is a jump of eleven
    // minors (1.30.x -> commons-text 1.14.0 -> 3.18.0, measured 2026-11-05), and the gate's own semantics are measured
    // against 1.19.6 (scripts/mutation_meta_check_backend.py: which statuses count as detected) — a security floor
    // does not re-measure the gate. 3.20.0 is the version the rest of the build already resolves. **Drop it with the
    // PIT bump that brings commons-lang3 3.18.0 or later.**
    constraints {
        pitest("org.apache.commons:commons-lang3:3.21.0") {
            because("GHSA-j288-q9x7-2f5v: 3.14.0 via PIT's commons-text 1.12.0, fixed in 3.18.0")
        }
    }
}

// The mutate scope is read from pitest.scope.json — the SAME manifest a scripts/ meta-check consumes, so the mutator
// and its "something was mutated" guard never drift (ADR 0045 §4; mirrors src/web/stryker.config.mjs reading
// stryker.mutate.json). PIT class globs, curated to the pure decision tier (never the dumb stores or the slices).
// Each entry checked as it is read (a JSON list of strings), so no unchecked cast stands in for the manifest's shape.
val pitScope = groovy.json.JsonSlurper().parse(file("pitest.scope.json")) as Map<*, *>
fun pitScopeList(key: String): List<String> = (pitScope[key] as List<*>).map { it as String }

pitest {
    // 1.19.6, not 1.19.4: PIT reads the classes it mutates with ASM, and 1.19.4's ASM 9.7.1 knows class files up to
    // Java 24 (`Opcodes.V24`) — the backend compiles for 25 (ADR 0119), and 1.19.6 is the first PIT on ASM 9.8 (`V25`;
    // pitest-parent's `asm.version`). Still above pitest-junit5-plugin 1.2.3's floor of 1.19.4.
    pitestVersion.set("1.19.6")
    junit5PluginVersion.set("1.2.3")
    targetClasses.set(pitScopeList("targetClasses"))
    targetTests.set(pitScopeList("targetTests"))
    // The gate: the build goes red below this mutation score. The pure tier measures 100% (zero survivors, zero
    // equivalents); the margin is NOT slack for a known gap — it is deliberate headroom for a FUTURE equivalent
    // mutant (one PIT cannot detect as equivalent, so it surfaces as SURVIVED), which at 100 would red the gate
    // with no fix available. Mirrors web's break=95 (ADR 0045 §4). At the current mutant count it tolerates
    // exactly one such survivor; a second collapses the score below 95.
    mutationThreshold.set(95)
    // Kotlin hands PIT equivalent mutants a test cannot kill, so a pure class here is written around them
    // (ClientAddress, ADR 0094, met all three): an inline stdlib call (`any`, `none`, `mapIndexed`) inlines its
    // `is Collection` and index-overflow branches into the class; a Java platform-typed value passed to a non-null
    // parameter (`InetAddress.address`) gets a null-check intrinsic whose removal changes nothing; and `indexOf` < 0
    // has a boundary twin where index 0 cannot occur. Use a plain loop, a non-inline call (`drop`, `substringBefore`,
    // `max`) or move the Java call into a holder class outside the scope. `avoidCallsTo("kotlin.jvm.internal")` is
    // NOT the fix: PIT then skips every LINE that calls the package, `==` on objects included, and the scope lost a
    // third of its mutants (MembershipPolicy all of them) when it was tried.
    // Incremental analysis: PIT re-tests only mutants whose code OR covering tests changed since the cached
    // history, so a weakened assertion (a test edit, no source change) is still caught. The history file is the
    // CI cache (mutation.yml's `backend` job), mirroring Stryker's incremental report (ADR 0045 §4). Same path in/out:
    // PIT reads the prior run, then writes the updated history for the next; a missing input on a cold run is fine.
    historyInputLocation.set(file("build/pitest/history.bin"))
    historyOutputLocation.set(file("build/pitest/history.bin"))
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false) // stable report path: core/build/reports/pitest/{index.html,mutations.xml}
    failWhenNoMutations.set(true)  // an empty scope must fail loudly — the meta-check's build-time sibling
    verbose.set(false)
}
