---
id: cr-the-remaining-counters-are-revisions
title: The remaining change counters are revisions — stale_revision, the account documents, the avatar, the terms
status: in-progress
satisfies: []
adrs: ["0086", "0077", "0072", "0084", "0035", "0028"]
invariants:
  - "API-first: the contract changes first, the backend and the web client follow from the generated code"
  - "Operator-blind: no sealed byte changes — a seal's binding fields are encoded by position, never by name"
---

## Goal

Inbox #452, the second round after #437. ADR 0086: "version" means compatibility only; a concurrency or change
counter is a **revision**. Rename what still says version, contract → backend → packages/crypto → web. Pre-V1: no
shim, no major.

## What is a revision here (ADR 0086's table, one by one)

| Old | Is | New |
|---|---|---|
| conflict code `stale_version` (every revision-checked write, the pins, the register, a rotation) | the conflict of a revision | `stale_revision` |
| the pins' and the register's `version` (request, response, the `identity-pins` / `device-register` seals' binding field, columns `veyq_identity_pins.version`, `veyq_device_register.version`) | compare-and-set counter | `revision` |
| `avatarVersion` (ADR 0084), column `avatar_version` | change counter, the avatar read's ETag | `avatarRevision`, `avatar_revision` |
| the terms' `termsVersion` / `acceptedVersion`, the accept request's `version`, `409 terms_version_changed`, columns `terms_version` / `accepted_version` | a change counter: bumped on every real change of the text, compared on the accept with a 409 | `termsRevision` / `acceptedRevision`, `revision`, `terms_revision_changed`, `terms_revision` / `accepted_revision` |

The terms were #452's open question ("revision or edition?"). A revision: the number has no compatibility meaning,
it moves on every change of the text and guards a write exactly as `metaRevision` does. "Edition" would be a word
the table does not have for a meaning it already names.

**Stays version (compatibility):** `/api/setup`'s `version: { major, build }` (ADR 0032), the release version
(ADR 0057), an export's `appVersion`, every `info.version` of a spec, Flyway's schema version, and the word
"versioned" for a payload format a client owns.

## Crypto: a rename, not a format change

`encodeSealContext` writes `lp(purpose) ‖ field values in the schema's order` — no field name is in the bound
bytes. The vectors `seal.sym.identity-pins` / `seal.sym.device-register` pin `context_bytes`
`…6964656e746974792d70696e73 ‖ u64(7)`; only the context's key renames (`version` → `revision`), no byte value
moves. The vector's sample plaintext (`{"version":1,"pins":{}}`) is opaque test bytes, not the client's format (the
client seals `{ pins }`); it stays, because changing it would move `blob`.

## Guards

- `validate_write_concurrency.py`: the default conflict code becomes `stale_revision`. The reverse angle keeps
  reading both words, so a counter still named version cannot slip past undeclared; and a **declared revision
  field may no longer be named version-shaped** (ADR 0086) — new, bitten by a `validate_guard_gaps.py` case.
- `validate_guard_gaps.py` case(s) that anchor on `stale_version` move with it.

## Phase 1 — contract + crypto field names

Done when `npm run generate:api` ran in `web/`, the contract guards are green, and `packages/crypto` checks green
with the renamed vector keys and `context_bytes` byte-identical.

## Phase 2 — backend

Done when `cd backend && ./gradlew --no-watch-fs check` is green; the columns renamed in the core baseline with its
pin line in the same commit.

## Phase 3 — web + docs

Done when `npm run check` is green in `web/` and `admin-web/`, the full guard glob is green, and the inventory grep
returns only done inbox rows and ADR 0086's own "was" text.
