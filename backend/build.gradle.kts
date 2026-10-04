// The root `backend/` — the composition (ADR 0079): the Quarkus application whose `quarkusBuild` produces the one
// fast-jar the Dockerfile copies. The modules it depends on (`:core`, settings.gradle.kts) land in that fast-jar's
// `quarkus-app/lib/main`, never deployed on their own. Until `:apps:memories` exists (cr-platform-gradle-modules
// step 7) the Memories application's code, its generated API and its migration still live here.
plugins {
    // Every plugin version of the backend is declared here, once; a module applies the plugin without one.
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.allopen") version "2.3.21"
    id("io.quarkus") version "3.36.0"
    id("info.solidsoft.pitest") version "1.19.0" apply false // applied by :core, whose classes it mutates
}

extra["veyq.openapi.owner"] = "memories"
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
    // Every Quarkus extension, the S3 client and the mailer reach the application through `:core`'s `api`.
    implementation(project(":core"))

    // The test helpers :core's tests use too (`test-support/build.gradle.kts`).
    testImplementation(project(":test-support"))
    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.quarkus:quarkus-test-kubernetes-client") // @WithKubernetesTestServer: a fabric8 mock API server in-JVM
    testImplementation("io.rest-assured:rest-assured")
    testImplementation(kotlin("test"))
    testImplementation("com.icegreen:greenmail:2.1.2") // hermetic in-JVM SMTP server: assert the real sent mail (ADR 0048/0049)
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
