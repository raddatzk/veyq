# veyq

Self-hostable, end-to-end-encrypted **platform** for sealed, shareable objects; its first **application**
is **Memories** — memory-sharing, the product center today ([ADR 0079](docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)).
The server is **operator-blind**: it stores and serves only ciphertext.

## Hard invariants — never violate
- **Operator-blind E2EE everywhere.** The server sees only ciphertext + opaque metadata (IDs,
  sizes, exact server-observed timestamps, scope/membership, blinded tokens). A user's unshared personal space
  is confidential even from the operator. No path may break this.
- **Product language: Memory / Chapter** — it belongs to the Memories application. Folders organize only.
  Never "Album" / "Explorer". Say "application" (de: *Anwendung*), never "plugin" or "app".
- **Self-hostable by default**; central infrastructure always optional. This is **also the security
  guarantee**, not only a sovereignty one: confidentiality is designed to hold against an *actively* malicious
  operator exactly where the user serves their own client ([ADR 0026](docs/decisions/0026-client-trust-tiers.md));
  how much of that is built is [security-model § What holds today](docs/crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve),
  and no doc claims more. So anything that erodes self-hostability is a security regression.
- **API-first:** contracts under `contracts/` are the source of truth; never hand-write DTOs.
- **All docs in English.**

## Repository shape
- `contracts/` — OpenAPI + crypto vectors: the source of truth code is generated from.
- `backend/` + `web/` — the two user-facing surfaces. A CR's phase order is
  **contracts → backend → web**. **Target** ([ADR 0079](docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)):
  each splits into **core** (the platform) and **applications** (Memories first; compiled in, no runtime
  loading; an application may use core, never the reverse, never another application). **Partly moved** —
  the backend names every package for its owner, `com.veyq.core` (guarded: it imports no Memories code) and
  `com.veyq.memories`; core is its own Gradle module (`backend/core`), Memories still lives in the root project; all of `web/` is still organised per feature; ADR 0079's
  Consequences say what is not done, and
  [`docs/apps/`](docs/apps/README.md) maps what belongs where.
- `admin-web/` — the **Server-Owner administration** surface: its own package, its own container, reachable
  localhost-only by default ([ADR 0065](docs/decisions/0065-admin-ui-cli-separate-service.md)). A pure client
  of the existing backend Admin API — no DB, no KEK, no backend surface of its own. It holds tenant CRUD,
  release info and **first-run setup**; what looks admin-ish but is reachable by an ordinary member — accepting
  a tenant invite, accepting terms, and a tenant owner's own terms/mail settings — deliberately stays in `web/`.
- `infra/` + `charts/veyq/` — the two deployment targets: the production compose file (travels inside the
  signed image, [ADR 0057](docs/decisions/0057-release-identity-and-deployment-recipe.md)) and the Helm chart
  ([ADR 0064](docs/decisions/0064-kubernetes-helm-deployment-target.md)). Every infra-level decision has an
  answer on both, and a change to one checks the other; `infra/README.md` owns the commands for both.
- `packages/crypto/` — the one internal npm-workspace package (`@veyq/crypto`): the conformance-pinned crypto
  suite + the cold account birth + the Emergency Kit codec that carries its Secret Key + the wire base64,
  shared by `web/` and `admin-web/`
  ([ADR 0065](docs/decisions/0065-admin-ui-cli-separate-service.md)). The workspace exists **for that one
  package** — duplicating key-material logic would be a security defect, not a maintenance nuisance. Nothing
  else is shared: no UI, no router, no state. `npm ci` runs from the repo root; each package still proves
  itself with its own `npm run check`.
- `docs/` — internal engineering state (English) + working flow (`ai/`, `inbox.md`).
- `wiki/` — **published** end-user GitHub Wiki: plain language, **EN+DE**, focused on what you can do
  and "your data is safe, here's why".
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
Each surface's own `CLAUDE.md` names its check: [`backend/CLAUDE.md`](backend/CLAUDE.md) (`./gradlew check`),
[`web/CLAUDE.md`](web/CLAUDE.md) (`npm run check`). The doc trust chain is the `trust-chain` CI job
(`scripts/validate_*.py`); run it before claiming a doc change is sound.
