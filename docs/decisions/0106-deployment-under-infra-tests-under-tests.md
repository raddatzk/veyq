# ADR 0106 — Deployment lives under `infra/`, the test harnesses under `tests/`, the code under `src/`

- Status: Accepted
- Built: yes
- Date: 2026-10-20
- Model: [release-and-deployment](../crosscutting/release-and-deployment.md)

## Context

The repository root had grown to seventeen peers that are not peers: two deployment targets in three places, the app
image's `Dockerfile` loose at the root, three test harnesses in two places, and the code surfaces beside all of them. A
move touches every reader of a path — Dockerfiles, Compose mounts, workflows, the npm workspace, guards, coverage, ADRs,
the published site — and conflicts with every branch that touches the moved files. The layout itself is the root
`CLAUDE.md` § Repository shape and `scripts/repo_layout.py`; this ADR keeps why it is so.

## Decision

1. **Stage 1 — deployment under `infra/`, split by target; the harnesses under `tests/`.** `infra/docker/` holds what
   Docker builds and Compose runs, `infra/kubernetes/` the chart and the image only the chart runs; `tests/e2e/` the
   browser suite with lane L1's overlay in `tests/e2e/stack/`, `tests/fuzz/` the API fuzzer. `contracts/`, `scripts/`,
   `site/`, `docs/`, `changes/` and the launcher's root files stay at the root: every surface is generated from or pinned
   to `contracts/`, and a Gradle root build sits where `./gradlew` runs.
2. **The dev Compose project keeps its name.** `docker-compose.yml` declares `name: infra`: Compose otherwise names a
   project after its directory, and `docker` would start on empty volumes and share a generic name with any other
   repository's `infra/docker`. The production file declares `name: veyq`.
3. **A reader that compares two trees finds a path in either layout.** The chart upgrade check renders a predecessor's
   chart from the new path or the old one, so the first release after a move still checks an upgrade.
4. **Records stay as they were measured.** A `file:line` result in a measurement describes the tree it was taken on and
   is not rewritten; instructions, links and repository-shape descriptions are.
5. **Stage 2 — the code surfaces under `src/`**: `src/backend/`, `src/web/`, `src/admin-web/`, `src/packages/crypto/`.
   The directory is `src/`, never `application/`: an *application* is Memories ([ADR 0086](0086-one-word-per-meaning.md)),
   and `application/backend/` would hold core and every application at once. `web/` keeps its name: `frontend/` would
   name one of two frontends by what both are.
6. **An image mirrors the checkout.** A build stage lays the sources out as the checkout does, so a relative path
   between surfaces holds in both.
7. **Decision 3's rule holds for the code too**: the upgrade proof builds a predecessor from before stage 2.
8. **Stage 3 — what a release ships lives under `src/`, what the repository runs on itself under `scripts/`.** The two
   operator tools a release carries, `veyqctl` and `instance_backup.py`, are in `src/tools/` with their selftests. They
   ship alone — Python 3.9 and the standard library, no import from `scripts/` — and prove themselves by their selftest,
   not by a coverage ref ([ADR 0040](0040-coverage-gaps-and-http-not-a-surface.md)).

## Consequences

- **A script learns a path from `scripts/repo_layout.py`, never by spelling it** — except what ships alone (decision
  8), which spells its own and has its selftest compare them.
- **An installed instance does not see the tree's layout.** Release assets are flat names (`releases/<r>/…`) and the
  chart's registry path `ghcr.io/<owner>/charts/veyq` is a name, so moving a file in the tree changes neither.
- **Rejected — moving the code surfaces in stage 1's step**: the larger cost for the smaller gain, and a conflict with
  every branch that touches code.
- **Rejected — `tests/stack/` for L1's overlay**: the coverage surface `stack` already means the browser suite itself.
