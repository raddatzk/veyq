rootProject.name = "veyq-backend"

// The root `backend/` is the composition: the Quarkus application whose `quarkusBuild` produces the one fast-jar.
// A module below it is a project dependency of the root, never deployed on its own (ADR 0079; `gradle/module.gradle.kts`).
include(":core")
// Test helpers only: a `testImplementation` dependency of the modules and the root, never on a runtime classpath.
include(":test-support")
