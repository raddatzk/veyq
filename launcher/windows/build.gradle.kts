// `windows:*` — the veyq app on Windows (src/mobile/windows; ADR 0123's Consequences, the Windows app): every task is
// a verb of its `run.py`, which cross-compiles the core with src/mobile's own Gradle wrapper and runs the rest on a
// Windows host over ssh (VEYQ_WINDOWS_SSH; the test VM by default). No task is named `test` (infra/README.md § The
// launcher): the core's Windows tests are `coreTest`.
val runPy = rootDir.resolve("src/mobile/windows/run.py")

fun verb(name: String, what: String, vararg args: String) = tasks.register<Exec>(name) {
    group = "veyq"
    description = what
    workingDir = rootDir
    commandLine(listOf("python3", runPy.path) + args)
}

verb("build", "Generates the C#'s bridge, words and theme, gates the NuGet packages, builds the Windows app and runs its tests here and on the Windows host (run.py build).", "build")
verb("run", "Builds the Windows app and starts it in the Windows host's logged-on session, holding the tunnel to the dev backend (run.py run).", "run")
verb("coreTest", "Runs the core's Windows test binary on the Windows host: the suite over libsodium, normalization, the wall clock (run.py test).", "test")
verb("bridge", "Links veyq.dll, checks what it exports and runs the C smoke against it on the Windows host (run.py bridge).", "bridge")
