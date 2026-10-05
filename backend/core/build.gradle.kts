// `:core` — the platform (ADR 0079): every `com.veyq.core.*` class, core's generated API (`openApiGenerateCore`),
// core's migrations and the base `application.properties`. The root `backend/` composes it into the one fast-jar.
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
    // opt-in `./gradlew pitest` — mirrors web's `npm run mutation` staying out of `npm run check`. Every class it
    // mutates is core's, so the plugin, its scope file and its history live in this module.
    id("info.solidsoft.pitest")
}

veyqOpenApi { owner("core") }

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
    api("io.quarkus:quarkus-jdbc-h2") // dev/test store (the default)
    api("io.quarkus:quarkus-jdbc-mariadb") // production store
    api("io.quarkus:quarkus-flyway")
    api("org.flywaydb:flyway-mysql") // Flyway 12 keeps MariaDB/MySQL support out of flyway-core
    api("software.amazon.awssdk:s3:2.31.78") // S3 blob store: vendor-neutral S3 client + presigner (ADR 0013)
    api("io.quarkus:quarkus-mailer") // generic SMTP transport (transitive Vert.x MailClient), no vendor SDK (ADR 0048)
    // ADR 0064's Kubernetes ingress sink. Chosen over a bare fabric8 client for `@WithKubernetesTestServer`
    // (the root's tests) as much as for the client itself: this repository has no cluster and no CI that could get
    // one, so a fake control plane inside the test JVM is the only proof KubernetesIngressSink can ever offer.
    api("io.quarkus:quarkus-kubernetes-client")

    // The test helpers both this module's tests and the root's use (`TestFixtures`, `givenAdminChannel`): a project of
    // their own, so Quarkus loads them inside the application under test (`test-support/build.gradle.kts` says why
    // not `java-test-fixtures`).
    testImplementation(project(":test-support"))
    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation(kotlin("test"))
    testImplementation("io.quarkus:quarkus-test-kubernetes-client") // @WithKubernetesTestServer: a fabric8 mock API server in-JVM
    testImplementation("com.icegreen:greenmail:2.1.2") // hermetic in-JVM SMTP server: assert the real sent mail (ADR 0048/0049)

    // PIT's JUnit-5 runner (our tests are JUnit Platform). 1.2.3 requires PIT >= 1.19.4 — pinned together below.
    pitest("org.pitest:pitest-junit5-plugin:1.2.3")
}

// The mutate scope is read from pitest.scope.json — the SAME manifest a scripts/ meta-check consumes, so the mutator
// and its "something was mutated" guard never drift (ADR 0045 §4; mirrors web/stryker.config.mjs reading
// stryker.mutate.json). PIT class globs, curated to the pure decision tier (never the dumb stores or the slices).
@Suppress("UNCHECKED_CAST")
val pitScope = groovy.json.JsonSlurper().parse(file("pitest.scope.json")) as Map<String, Any>

pitest {
    pitestVersion.set("1.19.4") // paired with pitest-junit5-plugin 1.2.3 (its floor)
    junit5PluginVersion.set("1.2.3")
    targetClasses.set(pitScope["targetClasses"] as List<String>)
    targetTests.set(pitScope["targetTests"] as List<String>)
    // The gate: the build goes red below this mutation score. The pure tier measures 100% (zero survivors, zero
    // equivalents); the margin is NOT slack for a known gap — it is deliberate headroom for a FUTURE equivalent
    // mutant (one PIT cannot detect as equivalent, so it surfaces as SURVIVED), which at 100 would red the gate
    // with no fix available. Mirrors web's break=95 (ADR 0045 §4). At the current mutant count it tolerates
    // exactly one such survivor; a second collapses the score below 95.
    mutationThreshold.set(95)
    // Incremental analysis: PIT re-tests only mutants whose code OR covering tests changed since the cached
    // history, so a weakened assertion (a test edit, no source change) is still caught. The history file is the
    // CI cache (mutation-backend.yml), mirroring Stryker's incremental report (ADR 0045 §4). Same path in/out:
    // PIT reads the prior run, then writes the updated history for the next; a missing input on a cold run is fine.
    historyInputLocation.set(file("build/pitest/history.bin"))
    historyOutputLocation.set(file("build/pitest/history.bin"))
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false) // stable report path: core/build/reports/pitest/{index.html,mutations.xml}
    failWhenNoMutations.set(true)  // an empty scope must fail loudly — the meta-check's build-time sibling
    verbose.set(false)
}
