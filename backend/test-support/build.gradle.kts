// `:test-support` — the test helpers more than one module's tests use: the seeds (`TestFixtures`, `TenantRoots`,
// `TestIds`, `TestAdmission`, `ShareWriteProof`, `ReclaimRowAge`, `ScopeAccessWorld`), the upload plumbing (`TestBlob`,
// `FakePresignProfile`), the race harness (`Race.kt`), `givenAdminChannel` and the three CDI fakes of the blob store
// (`RecordingBlobStore`, `RefusingStoreReclaimStore`, `FakePresigningBlobStore` — beans, so this project carries a
// `META-INF/beans.xml` — without it the fakes are not beans at all, the production store answers instead, and the tests
// that cast an injected `BlobStore` to a fake fail with a `ClassCastException`, not an unsatisfied dependency: measured).
// Test code only: a `testImplementation` dependency of `:core`, `:apps:memories` and the root, on
// no runtime classpath, so nothing here reaches the fast-jar.
//
// **Why a project and not `java-test-fixtures` on `:core`**: a project dependency is a workspace module Quarkus loads
// like `:core` itself. A test-fixtures output was measured (Quarkus 3.36.0, cr-platform-gradle-modules step 6) to stay
// outside the `@QuarkusTest` application — its classes on the system class loader (`LinkageError` on a second copy of a
// core entity, `Arc.container()` null), its resources no config source — but that measurement ran while the module
// convention renamed every jar (`archivesName`), and the rename alone drops a fixtures jar out of the application model
// (measured 2026-10-05). Without it a plain fixture class is in the model; whether these helpers, which touch entities
// and Arc, would work from there is unmeasured (inbox #333).
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
