// `web:*` — the member client (src/web/), through its own npm scripts.
@Suppress("UNCHECKED_CAST")
val ports = gradle.extra["veyq.ports"] as Map<String, String>
val webDir = rootDir.resolve("src/web")

tasks.register<Exec>("bootstrap") {
    group = "veyq"
    description = "Installs the npm workspace and (re)generates web's API client from the contracts (npm install in " +
        "src/web/; its prepare hook runs generate:api). Re-run after any contracts change."
    workingDir = webDir
    commandLine("npm", "install")
}

tasks.register<Exec>("run") {
    group = "veyq"
    description = "Starts web's Vite dev server; browse veyq.localhost:<port> (default 8081)."
    workingDir = webDir
    commandLine("npm", "run", "dev")
    ports["webPort"]?.let { environment("VITE_PORT", it) }
    ports["backendPort"]?.let { environment("VEYQ_API_TARGET", "http://127.0.0.1:$it") }
}

tasks.register<Exec>("build") {
    group = "veyq"
    description = "Builds web's production bundle into src/web/dist (npm run build)."
    workingDir = webDir
    commandLine("npm", "run", "build")
}
