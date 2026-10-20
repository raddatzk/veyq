# ADR 0106 — Deployment lives under `infra/`, the test harnesses under `tests/`

- Status: Accepted 2026-10-20 (owner: the layout of stage 1, and stage 2's direction with its naming constraint);
  stage 1 to be built by `cr-repo-layout-infra-and-tests`. Stage 2 is not decided to happen, only what it may not be
  called.
- Date: 2026-10-20

## Context

The repository root had grown to seventeen directories, most of them peers that are not peers: two deployment
targets in three places (`infra/` holds the Compose files, `charts/veyq/` the Helm chart, `tools/` the image only the
chart runs), the app image's `Dockerfile` loose at the root, and three test harnesses spread over two of them (`e2e/`,
lane L1's overlay in `infra/e2e/`, the API fuzzer in `infra/fuzz/`). The owner asked for a grouped layout
(2026-10-20): the code surfaces under one directory, deployment under `infra/` split by target, the harnesses under
`tests/`.

What a move costs was measured before deciding (`git grep` over tracked files, 2026-10-20): the deployment and test
paths have about 300 readers, the code surfaces about 800 (`web/` alone ~300, `contracts/` 217), in Dockerfiles,
Compose mounts, workflows, the npm workspace and its lockfile, the guards, coverage, ADRs and the published site. And
every open worktree branch conflicts with a move of the files it touches.

## Decision

1. **Stage 1 — deployment and tests are grouped; the code surfaces stay where they are.**
   - `infra/docker/` — what Docker builds and Compose runs: the app image's `Dockerfile` (built from the repository
     root, as before), `docker-compose.yml` (dev), `docker-compose.prod.yml`, `garage.toml`, `traefik-clientaddr.*`,
     `traefik-dev/`, and `.env.example` beside the file that reads `.env`.
   - `infra/kubernetes/charts/veyq/` — the Helm chart; `infra/kubernetes/tools/Dockerfile` — the `veyq-tools` image,
     which only the chart runs ([ADR 0098](0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md)).
   - `infra/README.md` stays one level up: it is about both targets and the dev stack.
   - `tests/e2e/` — the browser suite (the npm workspace `@veyq/e2e`); `tests/e2e/stack/` — lane L1's Compose overlay
     and its NSS helper, which exist only for that suite; `tests/fuzz/` — the API fuzzer.
   - Unchanged: `contracts/` (the source of truth every surface and harness is generated from or pinned to stays at the
     root), `scripts/`, `site/`, `docs/`, `changes/`, and the launcher's root files (`settings.gradle.kts`, `gradlew`,
     `gradle/` — a Gradle root build must sit where `./gradlew` is run).
2. **The dev Compose project keeps its name.** Compose names a project after its file's directory unless the file
   declares one; moved into `infra/docker/` the dev stack would become project `docker`, start on empty volumes and share
   a generic name with any other repository's `infra/docker`. So `docker-compose.yml` declares `name: infra`, and what
   derived the name from the directory (`run_web_e2e.py`'s `DOCKER_NETWORK`) reads the declared name. The production
   file already declares `name: veyq`; the two stay apart on one host as before.
3. **A reader that compares two trees finds a path in either layout.** `chart_upgrade_check.py` renders the chart of the
   predecessor release, which carries it at `charts/veyq`; it looks at the new path first and the old one second, so
   the first release after the move still checks an upgrade instead of reporting "no predecessor chart".
4. **Records stay as they were measured.** A grep command and its `file:line` results in an ADR's measurement ledger
   describe the tree they were taken on; they are not rewritten. Instructions, links and the repository-shape
   descriptions are.
5. **Stage 2 — the code surfaces under one directory — is a direction, not a commitment.** If it is built, the
   directory is `src/` (or another neutral word), **never `application/`**: an *application* is Memories or Depot
   ([ADR 0086](0086-one-word-per-meaning.md)), and `application/backend/` would hold core and every application
   at once. `web/` keeps its name in either stage: `admin-web/` is just as much a frontend, so `frontend/` would name
   one of two by what both are. Stage 2 is decided on its own, when no branch is in flight, against its cost above.

## Consequences

- The root lists `admin-web/ backend/ changes/ contracts/ docs/ infra/ launcher/ packages/ scripts/ site/ tests/ web/`
  and the launcher's files.
- A bare `docker build .` no longer finds the app image's Dockerfile; every build names it
  (`docker build -f infra/docker/Dockerfile .`), as the workflows and the launcher already did.
- An operator's local `.env` moves from `infra/.env` to `infra/docker/.env`; release assets are flat
  (`releases/<r>/docker-compose.prod.yml`), so an installed instance is unaffected.
- The OCI registry path `ghcr.io/<owner>/charts/veyq` is a name, not a repository path, and does not change.
- Rejected: moving the code surfaces in the same step (the larger cost for the smaller gain, and it would conflict with
  every branch that touches code); `tests/stack/` for L1's overlay (the coverage surface `stack` already means the
  browser suite itself).
