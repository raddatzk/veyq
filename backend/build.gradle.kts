plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.allopen") version "2.3.21"
    id("io.quarkus") version "3.36.0"
    // Mutation testing — the trust chain's assertion-strength gate on the pure policy tier (ADR 0045 §2). Deliberately
    // WITHOUT the archived `pitest/pitest-kotlin` mutators (read-only since 2023, successor is the commercial arcmutate
    // → both barred by CLAUDE.md's no-unmaintained-deps + self-hostability invariants). The spike measures whether a
    // pure policy over non-null types needs that Kotlin noise filter at all. NOT wired into `check`: a heavier, scoped,
    // opt-in `./gradlew pitest` — mirrors web's `npm run mutation` staying out of `npm run check`.
    id("info.solidsoft.pitest") version "1.19.0"
}

apply(from = "gradle/openapi.gradle.kts")

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.36.0"))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-hibernate-validator")
    implementation("io.quarkus:quarkus-security") // central auth: SecurityIdentity + @RolesAllowed (cr-0012)
    implementation("io.quarkus:quarkus-hibernate-orm-panache-kotlin")
    implementation("io.quarkus:quarkus-scheduler") // @Scheduled cleanup jobs (first use: tenant-invite purge)
    implementation("io.quarkus:quarkus-jdbc-h2") // dev/test store (the default)
    implementation("io.quarkus:quarkus-jdbc-mariadb") // production store
    implementation("io.quarkus:quarkus-flyway")
    implementation("org.flywaydb:flyway-mysql") // Flyway 12 keeps MariaDB/MySQL support out of flyway-core
    implementation("software.amazon.awssdk:s3:2.31.78") // S3 blob store: vendor-neutral S3 client + presigner (ADR 0013)
    implementation("io.quarkus:quarkus-mailer") // generic SMTP transport (transitive Vert.x MailClient), no vendor SDK (ADR 0048)
    // ADR 0064's Kubernetes ingress sink. Chosen over a bare fabric8 client for `@WithKubernetesTestServer`
    // below as much as for the client itself: this repository has no cluster and no CI that could get one, so a
    // fake control plane inside the test JVM is the only proof KubernetesIngressSink can ever offer.
    implementation("io.quarkus:quarkus-kubernetes-client")

    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.quarkus:quarkus-test-kubernetes-client") // @WithKubernetesTestServer: a fabric8 mock API server in-JVM
    testImplementation("io.rest-assured:rest-assured")
    testImplementation(kotlin("test"))
    testImplementation("com.icegreen:greenmail:2.1.2") // hermetic in-JVM SMTP server: assert the real sent mail (ADR 0048/0049)

    // PIT's JUnit-5 runner (our tests are JUnit Platform). 1.2.3 requires PIT >= 1.19.4 — pinned together below.
    pitest("org.pitest:pitest-junit5-plugin:1.2.3")
}

// Hibernate needs entity classes to be open + have a no-arg constructor.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.enterprise.context.ApplicationScoped")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    // The forked test JVM's default heap (~512m) OOMs partway through the full ~77-class Quarkus suite in one
    // run (measured: G1 heap pinned at ~505-520MB used, 0 survivor regions — genuine thrashing, not one test's
    // leak), taking a real assertion down with it as a knock-on flake (AuthRateLimitRefillTest's real-clock
    // polling window blows under the GC pressure). 2g clears it with headroom (measured green at ~2m45s).
    maxHeapSize = "2g"
}

kotlin {
    compilerOptions {
        javaParameters = true
    }
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
    timestampedReports.set(false) // stable report path: build/reports/pitest/{index.html,mutations.xml}
    failWhenNoMutations.set(true)  // an empty scope must fail loudly — the meta-check's build-time sibling
    verbose.set(false)
}
