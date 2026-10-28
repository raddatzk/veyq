# veyq

Self-hostable, end-to-end-encrypted **platform** for sealed, shareable objects; its first **application**
is **Memories** — memory-sharing, the product center today ([ADR 0079](docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)).
The server is **operator-blind**: it stores and serves only ciphertext.

## Hard invariants — never violate
- **Operator-blind E2EE everywhere.** The server sees only ciphertext + opaque metadata (IDs,
  sizes, exact server-observed timestamps, scope/membership, blinded tokens) — and, while the Server Owner keeps the
  guest-write record on, a link guest's client address, source port and time per write (never content), kept
  N ≤ 30 days as evidence ([ADR 0093](docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 10).
  A user's unshared personal space is confidential even from the operator. No path may break this.
- **Product language: Memory / Chapter** — it belongs to the Memories application. Folders organize only.
  Never "Album" / "Explorer". Say "application" (de: *Anwendung*), never "plugin"; "app" means only an installable native client, never an
  application ([ADR 0086](docs/decisions/0086-one-word-per-meaning.md) holds the other word rules).
- **Self-hostable by default**; central infrastructure always optional. This is **also the security
  guarantee**, not only a sovereignty one: confidentiality is designed to hold against an *actively* malicious
  operator exactly where the user serves their own client ([ADR 0026](docs/decisions/0026-client-trust-tiers.md));
  how much of that is built is [security-model § What holds today](docs/crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve),
  and no doc claims more. So anything that erodes self-hostability is a security regression.
- **API-first:** contracts under `contracts/` are the source of truth; never hand-write DTOs.
- **All docs in English.**

## Repository shape
- `contracts/` — OpenAPI + crypto vectors, and the fixed texts the server renders (moderation templates, the
  operator section of the terms): the source of truth code is generated from or pinned to.
- `src/` — everything a release ships: the code surfaces, one directory each, and the operator tools
  ([ADR 0106](docs/decisions/0106-deployment-under-infra-tests-under-tests.md) stages 2 and 3), all below. Not to be
  confused with a surface's own `src/` (`src/web/src`, a Gradle module's `src/main`).
- `src/backend/` + `src/web/` — the two user-facing surfaces. A CR's phase order is
  **contracts → backend → web**. **Built** ([ADR 0079](docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)):
  each splits into **core** (the platform) and **applications** (Memories first; compiled in, no runtime
  loading; an application may use core, never the reverse, never another application). **The backend is split** —
  Gradle modules `:core` (`src/backend/core`, `me.raddatz.veyq.core`) and `:apps:memories` (`src/backend/apps/memories`,
  `me.raddatz.veyq.memories`), composed by the root `src/backend/` into one fast-jar; the direction is the build's and
  `validate_backend_module_graph.py`'s. **The web is split by import direction only** — `src/web/src/features/memories` is
  the application (each application's directory is `features/<owner>`, derived by the web guard), reached through a build-time registry.
  [`docs/apps/`](docs/apps/README.md) maps what belongs where and what is still open.
- `src/admin-web/` — the **Server-Owner administration** surface: its own package, its own container, reachable
  localhost-only by default ([ADR 0065](docs/decisions/0065-admin-ui-cli-separate-service.md)). A pure client
  of the existing backend Admin API — no DB, no KEK, no backend surface of its own. It holds tenant CRUD,
  release info and **first-run setup**; what looks admin-ish but is reachable by an ordinary member — accepting
  a tenant invite, accepting terms, and a tenant owner's own terms/mail settings — deliberately stays in `src/web/`.
- `infra/` — the two deployment targets, one directory each ([ADR 0106](docs/decisions/0106-deployment-under-infra-tests-under-tests.md)):
  `infra/docker/` — the app image's `Dockerfile` (built from the repo root), the dev and the production compose file
  (the production one a release asset, [ADR 0057](docs/decisions/0057-release-identity-and-deployment-recipe.md)) and
  what they read beside them; `infra/kubernetes/` — the Helm chart (`infra/kubernetes/charts/veyq/`,
  [ADR 0064](docs/decisions/0064-kubernetes-helm-deployment-target.md)) and the image only the chart runs (`tools/`).
  Every infra-level decision has an answer on both, and a change to one checks the other; the docs site's Run area
  (`site/src/content/docs/run/`) owns the operator commands for both, and `infra/README.md` the dev stack and cutting
  a release. A script learns these paths from `scripts/repo_layout.py`, never by spelling them.
- `tests/` — the harnesses no surface owns: `tests/e2e/` (lane L1's browser suite, the npm workspace `@veyq/e2e`, with
  its compose overlay in `tests/e2e/stack/`, [ADR 0090](docs/decisions/0090-end-to-end-tests-boot-the-release-candidate.md))
  and `tests/fuzz/` (the API fuzzer, [ADR 0091](docs/decisions/0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md)).
- `settings.gradle.kts` + `launcher/` — the **dev launcher**: `./gradlew <surface>:<verb>` (`infra:up`,
  `backend:run`, `web:bootstrap`, `admin:image`, …) triggers the commands the surfaces already have, from a terminal
  or IntelliJ's Gradle tool window; it replaced the `.run/*.xml` configurations. A launcher, never an
  orchestrator: it includes no surface's build (`src/backend/` is run through its own wrapper, never `includeBuild` —
  its verification metadata would be ignored) and aggregates no check. `infra/README.md` § The launcher holds the
  task list and the per-worktree ports.
- `src/packages/crypto/` — the one internal npm-workspace package (`@veyq/crypto`): the conformance-pinned crypto
  suite + the cold account birth + the Emergency Kit codec that carries its Secret Key + the wire base64,
  shared by `src/web/` and `src/admin-web/` (and read by `tests/e2e/`, with the wire assertion's canary collision rule, ADR 0090)
  ([ADR 0065](docs/decisions/0065-admin-ui-cli-separate-service.md)). The workspace exists **for that one
  package** — duplicating key-material logic would be a security defect, not a maintenance nuisance. Nothing
  else is shared: no UI, no router, no state. `npm ci` runs from the repo root; each package still proves
  itself with its own `npm run check`.
- `src/tools/` — the two operator tools a release ships as assets: `veyqctl` (installs, upgrades and backs up a
  Compose instance from its releases, [ADR 0096](docs/decisions/0096-veyqctl-manages-an-instance-from-immutable-releases.md))
  and `instance_backup.py` (the backup tool it runs, and the `veyq-tools` image's entrypoint). Each ships alone.
- `docs/` — internal engineering state (English) + working flow (`ai/`, `inbox.md`).
- `scripts/` — what the repository runs on itself, in Python: the guards (`validate_*.py`, the trust chain below),
  the e2e orchestrators and the release producer.
- `site/` — the **published** documentation site (Astro Starlight, GitHub Pages; its own package outside the npm
  workspace, [ADR 0087](docs/decisions/0087-published-docs-are-a-starlight-site-docs-is-for-development.md)):
  **Use** (plain language, **EN+DE**, for users and space owners), **Run** (EN, for Server Owners) and
  **Security** (EN, for researchers). Each page names the `docs/` it speaks for in `sources:`. Its root is the
  **project website** (`site/src/pages/`, content in `site/src/components/website/`): what veyq is, its applications,
  why, and the way into the three areas — every claim one a docs page makes, nothing loaded from a third party.
- `changes/` — the **live** work order + any open review backlog; a CR is deleted once done.

## How we work
- Working rules + workflow: **`docs/ai/README.md`** — it owns the pipeline; never restate it here.
- Be a **sparring partner**: question proposals (mine and the user's), surface tradeoffs and
  simpler alternatives — don't just agree. The hard invariants above are the only thing not up
  for debate.
- **Structured over fast — there is no pressure to ship something demoable quickly.** Build the
  foundation/enabler properly and slice for *soundness*, not for the earliest visible result. Prefer doing
  it right (e.g. the derivatives pipeline before the gallery) over a throwaway stopgap. Correctness and a
  clean structure come first; speed-to-demo is explicitly **not** a goal.
- **Legal duties win over what we wrote or built.** Where a legal obligation (DSA, GDPR, StGB, TCO, …) and an ADR,
  a CR or the code disagree, the obligation decides and the ADR or code is corrected — however settled it looked
  (owner 2026-10-18). A question the law leaves open stays a named legal blocker in the ADR, never a guess either way.
- Scripts and utilities in **Python** (bash only for thin CI/hook glue).
- **No deprecated / unmaintained dependencies.** Never add — and don't keep — a dependency that is EOL,
  archived, or abandoned by its maintainers, or whose only living successor is commercial/closed (that breaks
  *self-hostable by default*). Prefer the **actively-maintained, vendor-neutral standard** over a single
  vendor's client. When a dep we rely on becomes deprecated, replacing it is **in-scope now**, not someday.
  The npm half is a gate: CI installs through `scripts/npm_ci_no_deprecated.py`, red on `npm warn deprecated`.
  (E.g. MinIO's OSS server was archived in 2026 and its successor AIStor is commercial — so the bundled store
  stays a generic **S3-compatible OSS** one, and the backend signs via the vendor-neutral **AWS S3 SDK**,
  never a MinIO-locked client.)
- Durable engineering state lives in `docs/`. A change-request is scaffolding: **deleted when done**, after
  what it taught is moved to where it binds — a code trap to a comment at that line, a working lesson to a
  rule. Never to git: a reason only a `git log` holds is one the next reader will not find.

## Prove your work
Each surface's own `CLAUDE.md` names its check: [`src/backend/CLAUDE.md`](src/backend/CLAUDE.md) (`./gradlew check`),
[`src/web/CLAUDE.md`](src/web/CLAUDE.md) (`npm run check`), [`src/tools/CLAUDE.md`](src/tools/CLAUDE.md) (the two
selftests). The doc trust chain is the `trust-chain` CI job
(`scripts/validate_*.py`); run it before claiming a doc change is sound.
