rootProject.name = "veyq-backend"

// The root `src/backend/` is the composition: the Quarkus application whose `quarkusBuild` produces the one fast-jar.
// A module below it is a project dependency of the root, never deployed on its own (ADR 0079; `buildSrc`'s `veyq.module`).
include(":core")
// The Memories application, the first of them (ADR 0079): depends on `:core`, never the reverse, never on another application.
include(":apps:memories")
// The Depot application, the second (cr-files-application): the same direction — `:core` only, never another application.
include(":apps:depot")
