// `site:*` — the published documentation site (site/, ADR 0087): its own package, outside the pnpm workspace.
@Suppress("UNCHECKED_CAST")
val ports = gradle.extra["veyq.ports"] as Map<String, String>
val siteDir = rootDir.resolve("site")

tasks.register<Exec>("bootstrap") {
    group = "veyq"
    description = "Installs the site's own dependencies (pnpm install in site/; it is not part of the workspace)."
    workingDir = siteDir
    commandLine("pnpm", "install")
}

tasks.register<Exec>("run") {
    group = "veyq"
    description = "Starts the site's Astro dev server (default 4321)."
    workingDir = siteDir
    // `--ignore-lock` keeps the server in the FOREGROUND, tied to this task: Astro 7 detaches `astro dev` into a
    // background process on its own when it detects an AI agent in the environment (`am-i-vibing`; a Gradle daemon
    // started from an agent's shell carries that env), and the task then ends while the server keeps the port —
    // measured 2026-10-16. The flag is Astro's documented "one-off foreground server", which is what a launcher's
    // `run` is; `astro dev status`/`stop` do not track it, stopping the task does.
    commandLine(listOf("pnpm", "run", "dev", "--", "--ignore-lock") + (ports["sitePort"]?.let { listOf("--port", it) } ?: emptyList()))
}

tasks.register<Exec>("build") {
    group = "veyq"
    description = "Builds the site into site/dist (pnpm run build)."
    workingDir = siteDir
    commandLine("pnpm", "run", "build")
}
