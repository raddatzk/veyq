// `web:*` — the member client (src/web/), through its own package scripts.
@Suppress("UNCHECKED_CAST")
val ports = gradle.extra["veyq.ports"] as Map<String, String>
val webDir = rootDir.resolve("src/web")

tasks.register<Exec>("bootstrap") {
    group = "veyq"
    description = "Installs the pnpm workspace and (re)generates web's API client from the contracts (pnpm install, " +
        "then pnpm run generate:api in src/web/). Re-run after any contracts change."
    workingDir = webDir
    // generate:api is run, not left to the install's `prepare` hook: pnpm runs no lifecycle script when the install is
    // already up to date (measured 2026-11-03), so after a contracts change alone `pnpm install` would regenerate nothing.
    commandLine("sh", "-c", "pnpm install && pnpm run generate:api")
}

tasks.register<Exec>("run") {
    group = "veyq"
    description = "Starts web's Vite dev server; browse veyq.localhost:<port> (default 8081)."
    workingDir = webDir
    commandLine("pnpm", "run", "dev")
    ports["webPort"]?.let { environment("VITE_PORT", it) }
    ports["backendPort"]?.let { environment("VEYQ_API_TARGET", "http://127.0.0.1:$it") }
}

tasks.register<Exec>("build") {
    group = "veyq"
    description = "Builds web's production bundle into src/web/dist (pnpm run build)."
    workingDir = webDir
    commandLine("pnpm", "run", "build")
}
