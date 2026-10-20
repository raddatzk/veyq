// `admin:*` — the Server-Owner administration surface (src/admin-web/, ADR 0065), through its own npm scripts.
@Suppress("UNCHECKED_CAST")
val ports = gradle.extra["veyq.ports"] as Map<String, String>
val adminDir = rootDir.resolve("src/admin-web")

tasks.register<Exec>("bootstrap") {
    group = "veyq"
    description = "(Re)generates admin-web's API client from the contracts (npm run generate:api). Its dependencies " +
        "come from the one workspace install, web:bootstrap. Re-run after any contracts change."
    workingDir = adminDir
    commandLine("npm", "run", "generate:api")
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
        "container.yml builds it."
    workingDir = rootDir
    commandLine("docker", "build", "-f", "src/admin-web/Dockerfile", "-t", "veyq-admin:local", ".")
}
