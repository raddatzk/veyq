// The root launcher (changes/cr-root-gradle-launcher.md): `./gradlew <surface>:<verb>` triggers a mechanism that
// already exists — an npm script, a `docker compose` line, the backend's own `./gradlew` — and nothing more. It is
// not an orchestrator: no surface's build lives here, and no task aggregates the surfaces' checks (each still proves
// itself with its own `npm run check` / `./gradlew check`).
//
// A Gradle task name cannot contain `:`, so `web:run` is task `run` of project `:web`. The five projects live under
// `launcher/`, never in the surfaces' own directories: `backend/` is a Gradle build of its own (it is RUN, never
// included — an included build's verification metadata is ignored in favour of this build's), a Gradle file inside
// an npm package lands in its Docker build context, and an IntelliJ module rooted at `web/` or `backend/` would
// compete with the one IntelliJ already has there.
rootProject.name = "veyq"

for (name in listOf("infra", "backend", "web", "admin", "site")) {
    include(":$name")
    project(":$name").projectDir = file("launcher/$name")
}

// Ports, for a second instance from a worktree. Each is optional, and an ABSENT one sets nothing: the tasks then set
// no env and every surface keeps its own default (web 8081, admin 5174, backend 8080 + 8090, the site's 4321,
// quarkusDev's debugger on 5005). Per checkout they live in an untracked `local.properties` at its root (IntelliJ's
// Gradle tool window runs a task without arguments, so the file is what makes a worktree's ports stick); `-P` on the
// command line wins over it. Not the tracked `gradle.properties` (a port there is a diff in every worktree), not
// `~/.gradle/gradle.properties` (one value for every checkout on the machine).
val portNames = listOf("webPort", "adminPort", "sitePort", "backendPort", "backendAdminPort", "debugPort")
val localFile = file("local.properties")
val local = java.util.Properties().apply { if (localFile.isFile) localFile.inputStream().use { load(it) } }
val ports: Map<String, String> = portNames.mapNotNull { name ->
    val key = "veyq.$name"
    val value = providers.gradleProperty(key).orNull ?: local.getProperty(key) ?: return@mapNotNull null
    require(value.trim().toIntOrNull() in 1..65535) { "$key=$value is not a port (1-65535)" }
    name to value.trim()
}.toMap()
gradle.extra["veyq.ports"] = ports
