# ADR 0106 — Deployment lives under `infra/`, the test harnesses under `tests/`, the code under `src/`

- Status: Accepted 2026-10-20 (owner: the layout of stage 1, and stage 2's direction with its naming constraint);
  stage 1 built 2026-10-20 (cr-repo-layout-infra-and-tests): the script-side paths live once in
  `scripts/repo_layout.py`, except in what ships alone (`instance_backup.py`, which spells its own and is compared by
  its selftest). Stage 2 decided by the owner the same day (decision 5) and built the same day (cr-repo-layout-src): the code
  surfaces' paths live in `scripts/repo_layout.py` too, a predecessor's in either layout.
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
5. **Stage 2 — the code surfaces under `src/`.** Decided by the owner on 2026-10-20, with no branch in flight:
   `src/backend/`, `src/web/`, `src/admin-web/`, `src/packages/crypto/`. The directory is `src/`, **never
   `application/`**: an *application* is Memories or Depot ([ADR 0086](0086-one-word-per-meaning.md)), and
   `application/backend/` would hold core and every application at once. `web/` keeps its name: `admin-web/` is just
   as much a frontend, so `frontend/` would name one of two by what both are. `contracts/` stays at the root (decision
   1), and so does the launcher (`launcher/` beside `settings.gradle.kts`: dev tooling, not a surface).
6. **An image mirrors the checkout.** A surface's build reaches its neighbours by relative path (the backend's
   `../contracts`, the web's `../contracts/api`), so a build stage lays the sources out under `/repo` exactly as the
   checkout does (`/repo/src/backend`, `/repo/contracts`, `/repo/VERSION`), and a relative path that holds in the
   checkout holds in the image.
7. **A reader that compares two trees finds a surface in either layout** (decision 3's rule, for the code): the
   upgrade proof builds a predecessor from before stage 2, where the surfaces are at the root.

## Consequences

- After stage 2 the root lists `changes/ contracts/ docs/ infra/ launcher/ scripts/ site/ src/ tests/` and the
  launcher's files.
- IntelliJ links the backend's own Gradle build per checkout; after stage 2 it is re-linked at `src/backend/`. Ignored
  build output (`build/`, `node_modules/`, generated clients) and a surface's local `.env` stay at the old paths in an
  existing checkout until moved or deleted by hand.
- A bare `docker build .` no longer finds the app image's Dockerfile; every build names it
  (`docker build -f infra/docker/Dockerfile .`), as the workflows and the launcher already did.
- An operator's local `.env` moves from `infra/.env` to `infra/docker/.env`; release assets are flat
  (`releases/<r>/docker-compose.prod.yml`), so an installed instance is unaffected.
- The OCI registry path `ghcr.io/<owner>/charts/veyq` is a name, not a repository path, and does not change.
- Rejected: moving the code surfaces in stage 1's step (the larger cost for the smaller gain, and it would conflict with
  every branch that touches code); `tests/stack/` for L1's overlay (the coverage surface `stack` already means the
  browser suite itself).
