---
id: cr-node-key-hierarchy
title: Every node carries a key — folders become shareable, move and rename re-wrap (ADR 0080)
status: planned          # planned → in-progress → done (then harvest what binds, and delete the file)
satisfies: [uc.sharing.share-contributor, uc.sharing.share-public-viewer]
adrs: ["0012", "0024", "0031", "0036", "0072", "0075", "0077", "0079", "0080"]
invariants: [operator-blind E2EE everywhere, API-first]
---

## Goal

Implement [ADR 0080](../docs/decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md):
every node, `FOLDER` included, carries a random content key wrapped under its parent's key; a node's name and slug
live under the parent's key; move and rename are one O(1) re-wrap guarded by a `placement_version`; a share covers
exactly the subtree under its root and a `FOLDER` can be a share root; guests get core's tree operations behind
`ShareGuestGate`, with `READ_WRITE` interpreted per application and failing closed. Slices 1–5 are core (and the
Memories parts they touch). Slice 6 (the drop-box node) belongs to Files and is not started here. Part of the path
to a second application ([docs/apps/files.md](../docs/apps/files.md), inbox #349).

## Context & constraints

- The ADR holds the decisions and 21 bite tests; this file holds the order and the proofs. Owner decisions are in
  the ADR's *Decided with the owner*.
- Contracts first, then backend, then web. Not live yet: the core baseline may change with its pin line in the
  same commit (`scripts/applied_migrations.sha256:17`, `release: none`); the owner recreates the dev DB
  (`docker compose -f infra/docker-compose.yml down -v`).
- Measured by the 2026-10-06 spike (read-only, HEAD f8cea132):
  - **Chapter media already hang under the chapter's own key** (`memoryNodes.ts:29-33`, `media.ts:368-371`): a chapter
    share is cryptographically ready, gated only by `register.ts:22` and `MediaShareGuestWrites.kt:29`.
  - **Guards.** `validate_write_concurrency.py` goes red (one version field per operation, `:153-171`) and needs its
    rule changed first; `validate_migration_versions.py` is red until re-pinned; `validate_request_bounds.py`,
    `validate_field_visibility.py` (`placementVersion`, request `epoch`: `structure`) and
    `validate_contracts_common.py` (examples when `required` grows) need contract annotations;
    `validate_node_subtree_erasure.py` / `validate_scope_erasure_arity.py` go red only for a new table with a
    `node_id` or an FK (so keep new columns on `veyq_node`). No guard reads `contracts/crypto/vectors/v1.json`:
    regenerate the `node-name` and `content-key` vectors by hand, the conformance tests are the only check.
  - **Fixtures.** About 20 backend test files and 31 call sites create a `FOLDER` without a wrap (16 ×
    `seedNode(..., null)` at `TestFixtures.kt:155`, 15 HTTP create bodies); web `tree.ts:83-104`, `tree.test.ts:64-75`,
    `scopes.test.ts:505,537`. No e2e creates a folder. `ScopeAccessWorld` and `NodeEntity` already set the key.
- Stale comment to fix on the way: `NodesStore.kt:245` (a pre-trashed child is re-stamped, not kept).
- Mirror edits: ADR 0012's "folders carry no key" and `K_SC`-naming lines, ADR 0079's "folders organize only"
  wording ("a `FOLDER` can be a share root", edited in place, no addendum), `docs/apps/files.md`.
- Each phase's proof for gate and transaction changes includes `scripts/run_backend_races_on_mariadb.py`; e2e only
  from the main checkout (`scripts/run_web_e2e.py`).

## Phases

### Slice 1 — folder key and `NOT NULL`
- [ ] 1.1 contracts — `content.openapi.yaml`: `createNode` requires `wrappedContentKey` (with bounds annotation) ·
      Done when: `validate_request_bounds`, `validate_contracts_common` green.
- [ ] 1.2 backend — baseline `wrapped_content_key NOT NULL` + pin line in one commit; `NodesHandler.create` refuses a
      missing wrap; all backend fixtures supply a key · Done when: `./gradlew check` green; bite test 1.
- [ ] 1.3 web — `createFolder` mints a key; `nodeContentKey` (`tree.ts:165-180`) stops treating folders as
      transparent; web fixtures · Done when: `npm run generate:api && npm run check` green.

### Slice 2 — names and slugs under the parent's key
- [ ] 2.1 contracts — `ShareNode.encryptedName`; regenerate vectors `node-name`, `content-key`, `slug` ·
      Done when: conformance tests green; `validate_field_visibility`, `validate_opaque_names_disjoint` green.
- [ ] 2.2 backend — share resolve serves `encryptedName` for the subtree only, no parent id of the root ·
      Done when: bite tests 2, 9, 12 green.
- [ ] 2.3 web — `sealName`/`slugTokenFor` take the parent key (`K_SC@epoch` at the root); the share view renders
      chapter titles · Done when: `npm run check`; e2e `shareContributor`, `shareUploader`, `media`, `memoryCover`.

### Slice 3 — move and rename with `placement_version` (the hard slice)
- [ ] 3.0 guard first — `x-veyq-concurrency` becomes a list of declarations with a conflict code each; bite-test the
      guard with two version fields · Done when: `validate_write_concurrency` green and its bite test red when a
      declaration is dropped.
- [ ] 3.1 contracts — `updateNode`: `wrappedContentKey`, `placementVersion`, `epoch`; `NodeSummary.placementVersion`
      (`x-veyq-visibility: structure`); `stale_placement`, `stale_epoch` responses.
- [ ] 3.2 backend — baseline `placement_version` + pin; compare-and-set; cycle, scope and placement checks; the
      epoch check applies when the destination is the root (create, rename-at-root, move) · Done when: bite tests
      3, 4, 5, 11, 15, 16; MariaDB race run (move against rename).
- [ ] 3.3 web — rename and move re-wrap the key under the (new) parent's key; retry on `409 stale_placement` ·
      Done when: `npm run check`.

### Slice 4 — trash consistency
- [ ] 4.1 backend — pin by test: move into a trashed parent, restore under a trashed parent, purge leaves no child;
      fix the `NodesStore.kt:245` comment · Done when: bite tests 6, 7, 8; MariaDB race run.

### Slice 5 — shares by kind, tree operations for guests
- [ ] 5.1 contracts — `sharing.openapi.yaml`: `sealedShareKey`/`sealedShareKeyEpoch` required (inbox #347), guest
      tree-operation endpoints, role semantics text · Done when: contracts guards green.
- [ ] 5.2 backend — a `FOLDER` (and, once enabled, a chapter) is a share root; read shares no longer need
      `ShareGuestWrites.supports` (`SharingHandler.kt:245`); guest create/move/rename/delete in core behind
      `ShareGuestGate` (structure, `NodeKindRules`, subtree boundary); a per-kind `READ_WRITE` declaration seam,
      fail closed; baseline `sealed_share_key NOT NULL` + pin · Done when: bite tests 10, 13, 14, 17, 18, 19, 20;
      `CoreAloneSliceTest` grows (core alone shares a folder); MariaDB race run.
- [ ] 5.3 web — folder share dialog and a folder share body that embeds each kind's body; a member's root rename
      re-seals shares' names through the recovery copy · Done when: `npm run check`; e2e: a folder share, a chapter
      share (if enabled), a contributor moving inside the subtree.
- [ ] 5.4 docs — role matrix for Memories in `docs/apps/memories.md` (what a contributor may do; cover and date edits
      are, to verify, member-only today) · Done when: `validate_doc_links` green.

### Slice 6 — the drop-box node (not in this CR)
Files' work: ADR 0080 decision 11, inbox #349 and #350.

- [ ] N coverage — `docs/coverage/` for `uc.sharing.share-contributor` and `uc.sharing.share-public-viewer` ·
      Done when: `python3 scripts/validate_coverage.py` green.

## Definition of done

All slices' proofs green, the 21 bite tests of the ADR each failing against a deliberately wrong lever,
`./gradlew check`, `npm run check` (web, admin-web), the guard glob (`scripts/validate_*.py`), MariaDB races and the
e2e suites named above; `docs/coverage/` updated; ADR 0012/0079 and `files.md` edited in place. A decision that
should bind (the role matrix, the placement rule) ends up in the ADR or a guard, not only here.

## Retrospective
(Filled at close.)
