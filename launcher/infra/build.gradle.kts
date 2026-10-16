// `infra:*` — the bundled dev infra (MariaDB + Garage S3, plus mailpit from the mail profile), infra/README.md.
//
// The dev infra is ONE per machine: infra/docker-compose.yml names its containers (`veyq-mariadb`, `veyq-garage`, …).
// So every task here runs the MAIN checkout's compose file, also from a worktree. Compose resolves a file's relative
// mounts (`./garage.toml`, `./traefik-dev`) against that file's directory and names the project after it (`infra`),
// so a worktree's copy is the same project with other mount sources — and `up` recreates the running containers onto
// the worktree's files (it did, 2026-09-24). Two worktrees, one infra: their backends share its database and bucket
// (inbox #455).
val mainCheckout: File = providers.exec {
    workingDir = rootDir
    commandLine("git", "rev-parse", "--path-format=absolute", "--git-common-dir")
}.standardOutput.asText.get().trim().let { File(it).parentFile }
val composeFile = mainCheckout.resolve("infra/docker-compose.yml")
val compose = "docker compose -f '$composeFile' --profile s3 --profile mail"

tasks.register<Exec>("up") {
    group = "veyq"
    description = "Starts the dev infra ($composeFile) and waits for MariaDB to be healthy."
    workingDir = mainCheckout
    // Two steps on purpose: the first `up` starts everything and exits 0; the second waits only on the mariadb
    // healthcheck so Flyway never races a cold DB. The store comes up running but not usable, and nothing here waits
    // for it: the backend provisions it itself on its first start (ADR 0067). Waiting on the WHOLE project is a habit
    // worth not forming — any container that exits, even cleanly, makes `--wait` exit 1.
    commandLine("sh", "-c", "$compose up -d && $compose up -d --wait mariadb")
}

tasks.register<Exec>("down") {
    group = "veyq"
    description = "Stops the dev infra and removes its containers; the data volumes stay."
    workingDir = mainCheckout
    commandLine("sh", "-c", "$compose down")
}

tasks.register<Exec>("wipe") {
    group = "veyq"
    description = "DESTRUCTIVE: stops the dev infra AND deletes its volumes — the dev database and every stored blob. " +
        "Afterwards .run/.env's key material is stale: backend:run, then backend:seed (web/CLAUDE.md, cold start)."
    workingDir = mainCheckout
    commandLine("sh", "-c", "$compose down -v")
}
