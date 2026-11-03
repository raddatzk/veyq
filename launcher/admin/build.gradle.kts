// `admin:*` — the Server-Owner administration surface (src/admin-web/, ADR 0065), through its own package scripts.
@Suppress("UNCHECKED_CAST")
val ports = gradle.extra["veyq.ports"] as Map<String, String>
val adminDir = rootDir.resolve("src/admin-web")

tasks.register<Exec>("bootstrap") {
    group = "veyq"
    description = "Installs the pnpm workspace and (re)generates admin-web's API client from the contracts (pnpm " +
        "install, then pnpm run generate:api in src/admin-web/). Re-run after any contracts change."
    workingDir = adminDir
    // The same shape as web:bootstrap, on purpose: `pnpm install` in a member installs the WHOLE workspace (one lockfile,
    // one store), and the member's own generate:api follows, so each frontend's bootstrap stands on its own. Run, not
    // left to `prepare`: pnpm runs no lifecycle script when the install is already up to date (measured 2026-11-03).
    commandLine("sh", "-c", "pnpm install && pnpm run generate:api")
}

tasks.register<Exec>("run") {
    group = "veyq"
    description = "Starts admin-web's Vite dev server (default 5174), proxying to the backend's admin listener. " +
        "First run on a fresh instance happens here."
    workingDir = adminDir
    commandLine("pnpm", "run", "dev")
    ports["adminPort"]?.let { environment("VITE_ADMIN_PORT", it) }
    // The admin listener, never the public port, which 404s every Server-Owner operation.
    ports["backendAdminPort"]?.let { environment("VEYQ_API_TARGET", "http://127.0.0.1:$it") }
}

tasks.register<Exec>("build") {
    group = "veyq"
    description = "Builds admin-web's production bundle (pnpm run build)."
    workingDir = adminDir
    commandLine("pnpm", "run", "build")
}

tasks.register<Exec>("image") {
    group = "veyq"
    description = "Builds the admin image veyq-admin:local from src/admin-web/Dockerfile, context the repo root as " +
        "ci.yml builds it."
    workingDir = rootDir
    commandLine("docker", "build", "-f", "src/admin-web/Dockerfile", "-t", "veyq-admin:local", ".")
}
