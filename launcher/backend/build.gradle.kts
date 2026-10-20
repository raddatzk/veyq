// `backend:*` — the backend is RUN through its own wrapper, never included in this build: an included build's
// verification metadata is ignored in favour of the including build's, so `backend/gradle/verification-metadata.xml`
// would stop applying (settings.gradle.kts). `backend/` stays a build of its own, exactly as the Dockerfile and CI
// use it.
@Suppress("UNCHECKED_CAST")
val ports = gradle.extra["veyq.ports"] as Map<String, String>
val backendDir = rootDir.resolve("backend")

tasks.register<Exec>("run") {
    group = "veyq"
    description = "Starts the backend in dev mode (quarkusDev, %dev profile) against the dev infra (infra:up first). " +
        "A debugger attaches to quarkusDev's JDWP port: 5005, or veyq.debugPort."
    workingDir = backendDir
    commandLine(listOfNotNull("./gradlew", "quarkusDev", ports["debugPort"]?.let { "-Ddebug=$it" }))
    // Presigned URLs are signed for the origin the browser is on, so the endpoint follows the web port — never set
    // on its own, since the two disagreeing is an upload that fails in the browser with nothing pointing at why.
    ports["webPort"]?.let { environment("VEYQ_BUNDLED_STORE_PUBLIC_ENDPOINT", "http://veyq.localhost:$it") }
    ports["backendPort"]?.let { environment("QUARKUS_HTTP_PORT", it) }
    ports["backendAdminPort"]?.let { environment("VEYQ_ADMIN_CHANNEL_PORT", it) }
}

tasks.register<Exec>("seed") {
    group = "veyq"
    description = "Births the local dev principal from .run/.env against the running backend (npm run bootstrap:user " +
        "in web/; web/CLAUDE.md owns its inputs). Re-running needs a fresh instance: infra:wipe, backend:run, then this."
    workingDir = rootDir.resolve("web")
    commandLine("npm", "run", "bootstrap:user")
    ports["backendPort"]?.let { environment("VEYQ_BASE", "http://localhost:$it") }
    ports["backendAdminPort"]?.let { environment("VEYQ_ADMIN_BASE", "http://127.0.0.1:$it") }
}

tasks.register<Exec>("build") {
    group = "veyq"
    description = "Builds the backend's fast-jar into backend/build/quarkus-app (quarkusBuild in backend/; runs no tests — that is backend/'s ./gradlew check)."
    workingDir = backendDir
    commandLine("./gradlew", "quarkusBuild")
}

tasks.register<Exec>("image") {
    group = "veyq"
    description = "Builds the app image veyq:local from infra/docker/Dockerfile (backend with web's SPA baked in), " +
        "context the repo root as container.yml builds it."
    workingDir = rootDir
    commandLine("docker", "build", "-f", "infra/docker/Dockerfile", "-t", "veyq:local", ".")
}
