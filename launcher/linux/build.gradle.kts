// `linux:*` — the veyq app on Linux (src/mobile/linux, CR core-as-a-native-library D8): every task is a verb of its
// `run.py`, which cross-compiles the core with src/mobile's own Gradle wrapper and runs cargo and the app in its Fedora
// 44 container (Docker). No task is named `test` (infra/README.md § The launcher): the core's Linux tests are `coreTest`.
val runPy = rootDir.resolve("src/mobile/linux/run.py")

fun verb(name: String, what: String, vararg args: String) = tasks.register<Exec>(name) {
    group = "veyq"
    description = what
    workingDir = rootDir
    commandLine(listOf("python3", runPy.path) + args)
}

verb("build", "Generates the bridge's Rust, the words and the theme, then cargo clippy, test and build the Linux app in its container (run.py build).", "build")
verb("run", "Runs the Linux app under gtk4-broadwayd, drawn into a browser at http://127.0.0.1:8095 (run.py run).", "run")
verb("coreTest", "Runs the core's Linux test binary in the container: the suite over libsodium, normalization, the wall clock (run.py test).", "test")
verb("bridge", "Links libveyq.so, checks what it exports and runs the C smoke against it in the container (run.py bridge).", "bridge")
