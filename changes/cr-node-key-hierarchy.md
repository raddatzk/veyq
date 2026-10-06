---
id: cr-node-key-hierarchy
title: Every node carries a key — folders become shareable, move and rename re-wrap (ADR 0080)
status: in-progress      # planned → in-progress → done (then harvest what binds, and delete the file)
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
- [x] 1.1 contracts — `content.openapi.yaml`: `createNode` requires `wrappedContentKey` (with bounds annotation) ·
      Done when: `validate_request_bounds`, `validate_contracts_common` green.
- [x] 1.2 backend — baseline `wrapped_content_key NOT NULL` + pin line in one commit; `NodesHandler.create` refuses a
      missing wrap; all backend fixtures supply a key · Done when: `./gradlew check` green; bite test 1.
- [x] 1.3 web — `createFolder` mints a key; `nodeContentKey` (`tree.ts:165-180`) stops treating folders as
      transparent; web fixtures · Done when: `npm run generate:api && npm run check` green.

### Slice 2 — names and slugs under the parent's key
- [x] 2.1 contracts — `ShareNode.encryptedName`; regenerate vectors `node-name`, `content-key`, `slug` ·
      Done when: conformance tests green; `validate_field_visibility`, `validate_opaque_names_disjoint` green.
      *Measured:* the `node-name`, `content-key` and `slug_token` vectors stay byte-identical (only the key's
      identity moved: no purpose, field or derivation changed); a `directory_entry` case was added instead, consumed
      by `web/src/core/node/tree.test.ts`. `NodeSummary.wrappedContentKey` is required on the read schema too.
- [x] 2.2 backend — share resolve serves `encryptedName` for the subtree only, no parent id of the root ·
      Done when: bite tests 2, 9, 12 green. *Measured:* `ShareDirectoryEntrySliceTest` — bite 9, and bite 12's
      "nothing above the root" on a Memory share under a folder (the root's name, wrap and parent id are not
      served). Bite 2 (a folder share) and the chapter-share half of 12 need a folder or chapter share root: slice 5.
- [x] 2.3 web — `sealName`/`slugTokenFor` take the parent key (`K_SC@epoch` at the root); the share view renders
      chapter titles · Done when: `npm run check`; e2e `shareContributor`, `shareUploader`, `media`, `memoryCover`.
      *Measured:* `npm run check` green. *Open:* the four e2e suites run only from the main checkout
      (`scripts/run_web_e2e.py`) — not run in the slice's worktree. The share view labels a chapter's media by its
      title (tile tooltip + image alt); it has no chapter grouping, which would be new UI.

### Slice 3 — move and rename with `placement_version` (the hard slice)
- [x] 3.0 guard first — `x-veyq-concurrency` becomes a list of declarations with a conflict code each; bite-test the
      guard with two version fields · Done when: `validate_write_concurrency` green and its bite test red when a
      declaration is dropped. *Measured:* `mode: version` + `fields: [{field, requiredWith (one or a list),
      conflictCode}]`, one form for all five version-checked operations; bite cases 59–61 in
      `validate_guard_gaps.py` (a declaration dropped, a second version-shaped field undeclared, a conflict code the
      `409` does not name) each red on the guard.
- [x] 3.1 contracts — `updateNode`: `wrappedContentKey`, `placementVersion`, `epoch`; `NodeSummary.placementVersion`
      (`x-veyq-visibility: structure`); `stale_placement`, `stale_epoch` responses. *Measured:* `TrashedNodeSummary`
      and `ShareNode` serve `placementVersion` too (omitted at a share's root, like its name). The `node-name` and
      `content-key` seals bind it (fourth field, u64be): their vectors and `directory_entry`'s child regenerated by an
      independent Python implementation that first reproduced the old blobs byte for byte. A placement's `parentId`
      null/absent is the scope root, so a rename sends the current parent.
- [x] 3.2 backend — baseline `placement_version` + pin; compare-and-set; cycle, scope and placement checks; the
      epoch check applies when the destination is the root (create, rename-at-root, move) · Done when: bite tests
      3, 4, 5, 11, 15, 16; MariaDB race run (move against rename). *Measured:* `NodePlacementCoreSliceTest` (3, 5,
      11, 15, 16, a trashed or foreign destination), `NodePlacementSliceTest` (the kind rule at the destination),
      `NodePlacementRaceCoreSliceTest` (rename vs move, a cycle from two moves, two moves of one node) green on H2
      and on the pinned MariaDB, each red against its lever. Bite 4 is the client's (a wrap opened at another
      version or row): `tree.test.ts`, `v1.conformance.test.ts`. A restore does not move the version.
- [x] 3.3 web — rename and move re-wrap the key under the (new) parent's key; retry on `409 stale_placement` ·
      Done when: `npm run check`. *Measured:* `placeNode` (`renameNode`/`moveNode` delegate), one re-read and rebuild
      on `stale_placement`, then `PlacementConflictError`; `tree.wire.test.ts` records the PATCH at `fetch`. No move
      UI. A restore stays its own endpoint (`restoreNode`, the version unmoved).

### Slice 4 — trash consistency
- [x] 4.1 backend — pin by test: move into a trashed parent, restore under a trashed parent, purge leaves no child;
      fix the `NodesStore.kt:245` comment · Done when: bite tests 6, 7, 8; MariaDB race run. *Measured:* no hole —
      the code already held Decision 8; slice 4 is tests and the corrected `restoreSubtree` comment.
      `NodeTrashConsistencyCoreSliceTest`: 6 (create half; the move half is slice 3's `aMoveIntoATrashedParentIs422`),
      7 (a child or a grandchild trashed earlier by a narrower delete, the folder later, the scheduled purge after the
      retention: no subtree row and no orphan left), 8 (`404 node_not_found` both when the child went down with the
      parent and when it was trashed first) and a parent's restore bringing the whole subtree back on its one marker.
      `NodeTreeRaceCoreSliceTest` adds the other overlap order — a child's restore during its parent's trash, a
      create or a move committing while the trash waits — beside `NodeTreeRaceSliceTest`'s create and move races;
      each red against its lever (the trashed-parent filter, the restore's parent check, the purge erasing only trash
      roots, the scope lock in `deleteSubtree` / `restoreSubtree`, the re-check under the lock in create and
      `applyUpdate`), green on H2 and the pinned MariaDB. Web: `tree.wire.test.ts` pins a restore's slug token and
      re-sealed name under the LIVE parent's key (a parent moved meanwhile), at the node's own placement version. No
      DB-level guard: `parent_id` holds the scope id at the root, so it cannot carry a foreign key, and both erasure
      guards read `node_id` columns and scope FKs, not the tree's self-reference.

### Slice 5 — shares by kind, tree operations for guests (backend 5a, web 5b)
Planned 2026-10-06 from three inventories; each sub-slice is its own worktree run with guard before/after.
- [ ] 5a.1 share roots by kind and the mandatory recovery copy — contracts: `CreateShareRequest.sealedShareKey` and
      `sealedShareKeyEpoch` required (`@NotNull`, `ReissueShareRequest` is the model; `ShareSummary` and the columns
      stay nullable, the baseline does not change); backend: a small fail-closed seam `ShareRoots` (per kind, the
      roles that may be a share root; core declares `FOLDER` with `READ` and `READ_WRITE`, Memories `MEMORY` with all
      three, `CHAPTER` none yet) replaces the coupling at `SharingHandler.kt` ~:245 (`ShareGuestWrites.supports` decided
      both shareability and who runs uploads); `WRITE_ONLY` on a `FOLDER` stays refused until Files' drop box;
      `SharingResource.create` calls `.validated()`; the `ShareGuestWrites` KDoc promise (:20-21) is reworded ·
      Done when: `CoreAloneSliceTest` shares a `FOLDER` with `READ` and a guest resolves it; bite tests: a chapter share
      refused, `WRITE_ONLY` on a folder refused, create without the recovery copy -> 400; guards
      `validate_contracts_common`, `validate_request_bounds` green.
- [ ] 5a.2 epoch gap and a principal-free node operation — a move out of the root with an epoch above the current is
      refused (`NodesHandler.kt` ~:191, any destination); the structure checks of create/placement are extracted as
      functions of `(scopeId, optional subtree boundary)` · Done when: existing tests green, a bite test for the gap,
      MariaDB race run.
- [ ] 5a.3 guest tree operations in core — `POST /api/shares/{shareId}/nodes` and a placement `PATCH`
      (`sharing` tag, own guest schema, no `wrappedMeta`; headers `X-Share-Password`, `X-Contributor-Token`; create
      spends the upload budget), lock order scope lock -> `admitted()` -> boundary check (upward walk) -> write;
      boundary decided under the scope lock; the root never moved, renamed or deleted by a guest and it must be live;
      `WRITE_ONLY` refused; no guest delete; a fail-closed seam `GuestNodeRules` (per kind and operation, both sides of
      a move; core declares `FOLDER`, Memories nothing); route table 16 -> 18 with `@RateLimit` · Done when: bite tests
      13, 17, 18, 19, 20 of the ADR; MariaDB races: guest move against a member move out of the subtree, guest create
      against trash, guest operation against share delete.
- [ ] 5a.4 chapter share and role matrix — Memories declares `CHAPTER` as a share root and extends
      `MediaShareGuestWrites` (:29) and `UploadAdmission` to it (an upload lands in the chapter: `nodeId = rootNodeId`);
      the role matrix for Memories in `docs/apps/memories.md` from the real code · Done when: bite test 21; the matrix
      reviewed against the guest operations of `share-media.openapi.yaml`.
- [ ] 5b web — folder share dialog and body, chapter share (`shareable` in the registry), the member's root rename
      re-seals share names through the recovery copy, drop the `share_key_not_recoverable` branch (`share.ts`), Restore
      of a freed-slug descendant in the trash list (slice 4 note) · Done when: `npm run check`; e2e: a folder share,
      a chapter share, a contributor moving inside the subtree.

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
