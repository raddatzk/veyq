# veyq

Self-hostable, end-to-end-encrypted **memory-sharing** app. The server is **operator-blind**:
it stores and serves only ciphertext. The product center is the **Memory** (nested into **Chapters**).

## Hard invariants — never violate
- **Operator-blind E2EE everywhere.** The server sees only ciphertext + opaque metadata (IDs,
  sizes, coarse timestamps, scope/membership, blinded tokens). A user's unshared personal space
  is confidential even from the operator. No path may break this.
- **Product language: Memory / Chapter.** Folders organize only. Never "Album" / "Explorer".
- **Self-hostable by default**; central infrastructure always optional. This is **also the security
  guarantee**, not only a sovereignty one: confidentiality holds against an *actively* malicious operator
  exactly where the user serves their own client ([ADR 0026](docs/decisions/0026-client-trust-tiers.md)). So
  anything that erodes self-hostability is a security regression.
- **API-first:** contracts under `contracts/` are the source of truth; never hand-write DTOs.
- **All docs in English.**

## Repository shape
- `contracts/` — OpenAPI + crypto vectors: the source of truth code is generated from.
- `backend/` + `web/` — the two surfaces, and the whole set. A CR's phase order is
  **contracts → backend → web**.
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
