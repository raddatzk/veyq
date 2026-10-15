---
id: cr-concurrency-counters-are-revisions
title: A node's concurrency counters are revisions — metaRevision, placementRevision
status: in-progress
satisfies: []
adrs: ["0086", "0077", "0080"]
invariants:
  - "API-first: the contract changes first, the backend and the web client follow from the generated code"
  - "Operator-blind: no sealed byte changes — a seal's binding fields are encoded by position, never by name"
---

## Goal

Inbox #437, second half. ADR 0086: "version" means compatibility only, a concurrency counter is a **revision**.
ADR 0077's node counters still say version on the wire, in the database and in the guard that holds them. Rename
them, contract → backend → web. Pre-V1: no shim, no major.

## Scope decision: placementVersion comes along

ADR 0086 names only `metaVersion`. `placementVersion` (ADR 0080 Decision 3) is the same kind of counter on the same
row, stated in the same `updateNode` request and declared in the same `x-veyq-concurrency` list. Renaming one leaves
`updateNode` carrying `metaRevision` beside `placementVersion`, which breaks the rule ADR 0086 makes binding. Both
move here, and ADR 0086's Consequences say so.

**Left for a follow-up row** (other surfaces, other ADRs): the conflict code `stale_version`, which identity pins and
device registration share; their own `version` counters (bound into the `identity-pins` / `device-register` seals);
`avatarVersion` (ADR 0084); the terms' `termsVersion` / `acceptedVersion`.

## Inventory (measured on `9f2661a1`)

`git grep -c -I -E 'metaVersion|meta_version|placementVersion|placement_version'` → **777 hits in 193 files**:
contracts (`_common`, `content`, `sharing`, `memories/media`, depot), `contracts/crypto/vectors/v1.json` (context
keys only), `packages/crypto/src/seal.ts` (the `node-name` / `content-key` binding fields), the two baselines,
entities, stores, handlers, resources, slice tests, the web client, the guards and the docs.

- **The x-veyq-concurrency mode** `version` becomes `revision`: it is the guard's name for this mechanism.
- **The guard** `scripts/validate_write_concurrency.py` keys the reverse angle on a version-shaped property name.
  It must also see `revision` / `…Revision`, or it goes blind to the renamed fields. Its bite cases in
  `validate_guard_gaps.py` (59–61) anchor on `metaVersion` / `placementVersion` and move with it.
- **The database:** `veyq_node.meta_version`, `veyq_node.placement_version`, `veyq_media.meta_version` become
  `…_revision` in the two baselines, with their pin lines in the same commit (backend/CLAUDE.md § Migrations, no
  release yet). Every dev database is recreated afterwards (`down -v`).
- **Crypto:** the seal's binding fields are encoded by position, so renaming the field changes no byte; the
  conformance vectors prove it, unchanged in every byte value.

## Phase 1 — contract + crypto field names

Done when `npm run generate:api` ran, the contract guards are green, and `packages/crypto` checks green with the
renamed vector keys.

## Phase 2 — backend

Done when `cd backend && ./gradlew --no-watch-fs check` is green.

## Phase 3 — web + docs

Done when `npm run check` is green in `web/` and `admin-web/`, the full guard glob is green, and the inventory grep
returns only applied history (none: the baselines change too) and done inbox rows.
