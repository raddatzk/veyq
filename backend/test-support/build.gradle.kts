// `:test-support` — the test helpers more than one module's tests use: the seeds (`TestFixtures`, `TenantRoots`,
// `TestIds`, `TestAdmission`, `ShareWriteProof`, `ReclaimRowAge`, `ScopeAccessWorld`), the upload plumbing (`TestBlob`,
// `FakePresignProfile`), the race harness (`Race.kt`), `givenAdminChannel` and the three CDI fakes of the blob store
// (`RecordingBlobStore`, `RefusingStoreReclaimStore`, `FakePresigningBlobStore` — beans, so this project carries a
// `META-INF/beans.xml`). Test code only: a `testImplementation` dependency of `:core`, `:apps:memories` and the root, on
// no runtime classpath, so nothing here reaches the fast-jar.
//
// **Why a project and not `java-test-fixtures` on `:core`** (measured, Quarkus 3.36.0, cr-platform-gradle-modules
// step 6): a test-fixtures output is not part of a `@QuarkusTest`'s application — its classes load through the system
// class loader, outside Quarkus (`LinkageError` on a second copy of a core entity, `Arc.container()` null), and its
// resources are no config source. A project dependency is a workspace module Quarkus loads like `:core` itself.
//
// `:core` is `compileOnly`: at test time the module under test (or the root) already brings it, and a runtime edge
// back to `:core` would put a second copy of core's classes on `:core`'s own test classpath.
plugins {
    kotlin("jvm")
    kotlin("plugin.allopen")
}

apply(from = "../gradle/module.gradle.kts")

dependencies {
    compileOnly(project(":core"))
    api("io.quarkus:quarkus-junit")
    api("io.rest-assured:rest-assured")
}
