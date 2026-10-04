rootProject.name = "veyq-backend"

// The root `backend/` is the composition: the Quarkus application whose `quarkusBuild` produces the one fast-jar.
// A module below it is a project dependency of the root, never deployed on its own (ADR 0079; `gradle/module.gradle.kts`).
include(":core")
