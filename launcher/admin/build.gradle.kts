// `admin:*` — the Server-Owner administration surface (src/admin-web/, ADR 0065), through its own npm scripts.
@Suppress("UNCHECKED_CAST")
val ports = gradle.extra["veyq.ports"] as Map<String, String>
val adminDir = rootDir.resolve("src/admin-web")

tasks.register<Exec>("bootstrap") {
    group = "veyq"
    description = "Installs the npm workspace and (re)generates admin-web's API client from the contracts (npm install " +
        "in src/admin-web/; its prepare hook runs generate:api). Re-run after any contracts change."
    workingDir = adminDir
    // The same command as web:bootstrap, on purpose: `npm install` in a workspace member installs the WHOLE workspace
    // (one lockfile, one root node_modules) but runs `prepare` for that member only (measured 2026-10-20), so each
    // frontend's bootstrap stands on its own on a fresh checkout and neither borrows the other's install.
    commandLine("npm", "install")
}

tasks.register<Exec>("run") {
    group = "veyq"
    description = "Starts admin-web's Vite dev server (default 5174), proxying to the backend's admin listener. " +
        "First run on a fresh instance happens here."
    workingDir = adminDir
    commandLine("npm", "run", "dev")
    ports["adminPort"]?.let { environment("VITE_ADMIN_PORT", it) }
    // The admin listener, never the public port, which 404s every Server-Owner operation.
    ports["backendAdminPort"]?.let { environment("VEYQ_API_TARGET", "http://127.0.0.1:$it") }
}

tasks.register<Exec>("build") {
    group = "veyq"
    description = "Builds admin-web's production bundle (npm run build)."
    workingDir = adminDir
    commandLine("npm", "run", "build")
}

tasks.register<Exec>("image") {
    group = "veyq"
    description = "Builds the admin image veyq-admin:local from src/admin-web/Dockerfile, context the repo root as " +
        "ci.yml builds it."
    workingDir = rootDir
    commandLine("docker", "build", "-f", "src/admin-web/Dockerfile", "-t", "veyq-admin:local", ".")
}
