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
- [x] 5a.1 share roots by kind and the mandatory recovery copy — contracts: `CreateShareRequest.sealedShareKey` and
      `sealedShareKeyEpoch` required (`@NotNull`, `ReissueShareRequest` is the model; `ShareSummary` and the columns
      stay nullable, the baseline does not change); backend: a small fail-closed seam `ShareRoots` (per kind, the
      roles that may be a share root; core declares `FOLDER` with `READ` and `READ_WRITE`, Memories `MEMORY` with all
      three, `CHAPTER` none yet) replaces the coupling at `SharingHandler.kt` ~:245 (`ShareGuestWrites.supports` decided
      both shareability and who runs uploads); `WRITE_ONLY` on a `FOLDER` stays refused until Files' drop box;
      `SharingResource.create` calls `.validated()`; the `ShareGuestWrites` KDoc promise (:20-21) is reworded ·
      Done when: `CoreAloneSliceTest` shares a `FOLDER` with `READ` and a guest resolves it; bite tests: a chapter share
      refused, `WRITE_ONLY` on a folder refused, create without the recovery copy -> 400; guards
      `validate_contracts_common`, `validate_request_bounds` green.
      *Measured:* `ShareRoots.roles(kind): Set<String>?` (role names as `ShareEntity` holds them; null = not handled),
      `FolderShareRoots` (core) and `MemoryShareRoots` (`MEMORY` only; `CHAPTER` handled by nobody). A role change asks
      it too (`invalid_role_change`, the existing code of that operation). Tests: `CoreAloneSliceTest` (a folder
      shared READ/READ_WRITE, WRITE_ONLY and every other kind `illegal_scope`; a guest resolves `rootKind FOLDER`,
      the descendants' names and wraps, not the root's entry, a cover blob under the subtree and not one beside it),
      `ShareRootsPartitionTest` (none/two implementations), `ShareRootsSliceTest` (chapter refused, folder no drop
      box, role change on a seeded chapter share refused, a READ_WRITE folder guest's start and window
      `share_not_contributable`), `ShareKeyRecoveryEpochSliceTest` (create without the copy -> 400). The test bodies
      carry the copy through `ShareWriteProof.recovery()`; `ShareSharedScopeSliceTest` models a copy-less link as a
      legacy row (created, then the copy cleared), which `ReissuePolicy` still judges.
- [x] 5a.2 epoch gap and a principal-free node operation — a move out of the root with an epoch above the current is
      refused (`NodesHandler.kt` ~:191, any destination); the structure checks of create/placement are extracted as
      functions of `(scopeId, optional subtree boundary)` · Done when: existing tests green, a bite test for the gap,
      MariaDB race run. *Measured:* `NodesHandler.createInScope` / `updateInScope` / `deleteInScope` (internal, after
      the caller's gate) take an optional `SubtreeBoundary`, decided only under the scope lock
      (`NodesStore.withinSubtree`, in `applyUpdate`, `deleteSubtree` and create's locked block): node outside →
      `node_not_found` (delete: the 404 of a foreign node), the boundary root as the node or a parent/destination
      outside → `illegal_structure`. `createInScope` owns its transaction (`createOrConflict`), so 5a.3 cannot hold a
      share-row lock across it — its "scope lock → `admitted()`" order needs a hook inside create's locked block.
      The epoch check refuses an epoch above the current one at any destination; `theEpochIsCheckedOnlyAtTheRoot`
      pinned the gap (a made-up epoch 7 accepted between non-root parents) and now sends one below the current.
      Tests: `NodePlacementCoreSliceTest.anEpochAboveTheCurrentIsRefusedForAnyDestination`,
      `SubtreeBoundaryCoreSliceTest`, `SubtreeBoundaryRaceCoreSliceTest` (a member's move of the destination out of
      the boundary while a bounded move/create waits), each red against its lever.
- [x] 5a.3 guest tree operations in core — `POST /api/shares/{shareId}/nodes` and a placement `PATCH`
      (`sharing` tag, own guest schema, no `wrappedMeta`; headers `X-Share-Password`, `X-Contributor-Token`; create
      spends the upload budget), lock order scope lock -> `admitted()` -> boundary check (upward walk) -> write;
      boundary decided under the scope lock; the root never moved, renamed or deleted by a guest and it must be live;
      `WRITE_ONLY` refused; no guest delete; a fail-closed seam `GuestNodeRules` (per kind and operation, both sides of
      a move; core declares `FOLDER`, Memories nothing); route table 16 -> 18 with `@RateLimit` · Done when: bite tests
      13, 17, 18, 19, 20 of the ADR; MariaDB races: guest move against a member move out of the subtree, guest create
      against trash, guest operation against share delete.
      *Measured:* `createShareNode` (`CreateShareNodeRequest`: `nodeId` — ADR 0072 binds every seal to it — `parentId`,
      `kind`, `encryptedName`, `slugToken`, `epoch`, `wrappedContentKey`, `orderIndex`; 201 `ShareNodeCreated`) and
      `updateShareNode` (`UpdateShareNodeRequest`: every placement field required, no `epoch`; `x-veyq-concurrency`
      `placementVersion`/`stale_placement`); `X-Contributor-Token` is a `ContributorToken` parameter in both
      `sharing.openapi.yaml` and `share-media.openapi.yaml`, held identical by `validate_contracts_common.py`. Codes: the
      gate's as the share-media writes map them (`share_not_found` 404, `share_not_contributable` 403 — also a READ or
      WRITE_ONLY link, `ShareGuestGate.writeGate(onlyRole = READ_WRITE)` before the token and the budget, and a trashed
      root — `share_password_incorrect`, `rate_limited`); a node outside the subtree `node_not_found`; the root as the
      node, a parent or destination outside, a cycle, a kind `NodeKindRules` refuses `illegal_structure`; a container
      `GuestNodeRules` refuses `illegal_scope` (the code a refused kind-and-role pair answers on create). Backend:
      `ShareNodesHandler` (core/sharing), `GuestNodeRules` + `FolderGuestNodeRules`; `createInScope` joins its caller's
      transaction (the member's `create` opens it with `createOrConflict` + `createReplay`) and takes the scope lock
      before reading any node — with the reads before the lock, a node loaded first stayed in the transaction's
      persistence context and the boundary walk under the lock was handed the stale copy
      (`SubtreeBoundaryRaceCoreSliceTest`'s create landed outside, measured). A bounded placement may state no epoch
      (`PlacementRequest.completeWithoutEpoch`): the node keeps its stored one. Tests: `ShareNodesCoreSliceTest`,
      `ShareNodesSliceTest` (Memories: a Memory moved between subfolders; a chapter neither created, moved out, moved
      in nor renamed — `illegal_scope` — while the resolve serves it), `GuestNodeRulesPartitionTest`,
      `ShareNodeBudgetCoreSliceTest`, `ShareNodeRaceCoreSliceTest` (red on H2 and the pinned MariaDB against an
      unlocked read of the destination / the parent before the scope lock, the dropped `admitted()`, and the share row
      locked first — a deadlock against a re-activation), `ShareMediaRouteSliceTest` 18 rows, `ModuleCompositionTest`
      with the eleventh seam. **The create's epoch (owner, 2026-10-07):** every seal binds the node's epoch
      (`node-name`, `content-key`; the reader opens at the stored one), so a server-chosen current epoch left a name
      unopenable whenever it differed from the guest's. The guest now sends the epoch it sealed with
      (`CreateShareNodeRequest.epoch`, required, `@NotNull`), and `createInScope` checks a bounded create under the
      scope lock with the member's rule off the root (`NodesHandler.sealableOffRoot`, shared with the placement
      check): above the current one `409 stale_epoch`, nothing stored; at or below it stored as sent. Placements
      still state none. Tests: `ShareNodesCoreSliceTest.anEpochAboveTheCurrentIsStaleAndStoresNothing` (red with the
      check removed), `anEpochBelowTheCurrentIsStoredAsSentWithNoPayload` (red with the current epoch stored in its
      place), `aCreateWithoutAnEpochIsMalformed`. Owed to 5b: the web client of both operations, and which epoch it
      seals a create with — the resolve names each node's (`ShareNode.epoch`), never the scope's current one.
- [x] 5a.4 chapter share and role matrix — Memories declares `CHAPTER` as a share root and extends
      `MediaShareGuestWrites` (:29) and `UploadAdmission` to it (an upload lands in the chapter: `nodeId = rootNodeId`);
      the role matrix for Memories in `docs/apps/memories.md` from the real code · Done when: bite test 21; the matrix
      reviewed against the guest operations of `share-media.openapi.yaml`.
      *Measured:* one set, `MemoriesNodeKinds.SHARE_ROOTS` (`MEMORY`, `CHAPTER`), answers `MemoryShareRoots` (all three
      roles), `MediaShareGuestWrites.supports` and `MediaUploadAdmission.supports`; no core change was needed (the
      resolve, the view gate, the blob GET, the drop key and the member media/bucket reads are kind-blind). Tests:
      `ChapterShareSliceTest` — bite 21 and the chapter half of 12 (red with the subtree walked from the root's
      parent), an uploader link on a chapter (drop key on the chapter, not the Memory; the upload lands in the chapter,
      red with the complete attaching to the parent; a member's chapter media page and buckets serve what opens it; a
      viewer link serves the identity's wrapped key and no name), a contributor's start/window/complete/delete-own
      (red with `MediaShareGuestWrites` or `MediaUploadAdmission` back to `MEMORY` only), no guest tree operation
      inside a chapter (red with `FolderGuestNodeRules` claiming `CHAPTER`). `ShareRootsSliceTest` changed on purpose:
      `aChapterShareIsRefusedForEveryRole` became `aChapterIsAShareRootForEveryRole` (red with `MemoryShareRoots` back
      to `MEMORY` only), the role-change refusal moved to a seeded share on a kind nobody declares (red with the
      `ShareRoots` check dropped from `updateShare`), and `aChapterLinkChangesRoleAsAMemorysDoes`. Contract text only:
      `share-media.openapi.yaml` says "Memory or chapter". **Owed to 5b:** `shareable: true` for `CHAPTER`
      (`register.ts:22`, whose comment "the backend rejects a non-MEMORY share root" is now false) and the share
      dialog for a chapter; the matrix's three cells marked UNVERIFIED by a test.
- [ ] 5b web — planned 2026-10-06 from three inventories; owner decisions: an own core dialog and share management for
      folders (not the Memories dialog), the share-name re-seal on a root rename (needs 5b.0), no move UI is invented
      (inbox #358), `share_key_not_recoverable` stays (tombstoned and legacy shares keep the nullable summary).
  - [x] 5b.0 backend + contracts — a new core operation `PUT /api/shares/{shareId}/sealed-name` (the root's name re-sealed
        under `K_share`, stored opaque, bounds as the create field): any member with WRITE access on the share's scope
        may call it (NOT the owner gate of `updateShare`, ADR 0036 point 3, which also replaces its fields wholesale);
        it changes only `sealed_name`, takes the row lock like `liveForUpdate`, a deleted share answers 404; its own
        `x-veyq-concurrency` declaration (last writer wins: the value is always the root's current name) · Done when:
        bite tests (any member may, a stranger may not, the other fields untouched, resolve serves the new value),
        guards green.
        *Measured:* `setShareSealedName` (`SharingHandler.setSealedName`, `SharingStore.setSealedName`): the gate's WRITE
        answer on the share's scope (a reader — a group member removed from the tenant — and a stranger are the 404),
        a revoked share is renamed too (a re-activation shows the current name), a deleted one is the 404. It takes the
        share row's lock only, as `updateShare`/`revoke` do; `reactivate` takes the scope row, then the share row, so no
        order deadlocks. `ShareSealedNameCoreSliceTest` (`:core`, a `FOLDER` share): owner and co-member set it and
        resolve serves it (red with an owner gate), reader and stranger refused (each red without the gate), every other
        column untouched (red writing `sealed_note`), deleted/unknown 404 (red with tombstones found), 400 bounds (red
        without the annotation), a re-seal overlapping a revoke keeps both writes, and one overlapping a re-activation
        ends consistent (red — a deadlock on H2 and on MariaDB — with the scope row locked after the share row).
        `ShareMediaRouteSliceTest` (19 routes) and `OperatorBlindProbeTest` carry the route.
  - [x] 5b.1 chapter share — `CHAPTER` `shareable: true` (`register.ts:22`), `MemoryShareBody` also claimed for
        `CHAPTER_KIND`, stale comments (`register.ts:19-20,28-29`, `kinds.ts:9`, `MemoryHomeView.vue:81`,
        `MemoryDetail.vue:63`) · Done when: `npm run check`; e2e: a chapter uploader and a chapter contributor.
        *Measured:* the rest of the path was already kind-blind — the detail, both share dialogs (the node's id, its
        key from `nodeContentKey`, its name as the tree opened it), `createShare` (wrap, sealed name, drop key bound to
        the node), `openSharedMemory`/`walkSubtree` (the root is whatever node the server serves with no parent) and
        `MemoryShareBody`; no logic changed, no string added. Tests: `MemoryHomeView.test.ts` (a chapter is shareable
        and both dialogs open with its id, its own key and its name — red with `shareable: false`),
        `applications.test.ts` (both kinds shareable, one body for both — red either way), `ShareView.test.ts` (a
        viewer and an uploader link rooted at a `CHAPTER` reach the body over the real registry — red, `unavailable`,
        without the claim), `share.test.ts` (a chapter link's wrap, title, nested chapter and media; the uploader's drop
        key under the chapter's key bound to the chapter, not the Memory), `MemoryDetail.test.ts` (a chapter's share
        buttons follow `shareable`); `MemoryHomeView.registry.test.ts` mirrors the real registration and
        `ShareView.registry.test.ts`'s unclaimed kind is `EXPENSE` now. New e2e `chapterShare.e2e.test.ts` (a
        contributor and an uploader link on a chapter; the raw resolve and media read carry only the chapter) —
        type-checked, linted and discovered, **not yet run** (the orchestrator runs it from the main checkout). Open:
        the member dialog's strings say "this Memory" on a chapter too (`share.title`, `share.intro`).
  - [x] 5b.2 lift share management to core — mint (recovery copy mandatory), list, revoke, reactivate, reissue and
        recovery without drop keys move from `features/memory/share.ts` to `core/sharing/manageShares.ts`; `walkSubtree`
        to `core/sharing/shareTree.ts`; drop keys and the uploader stay in Memories · Done when: all tests unchanged
        green, `validate_web_core_imports_no_applications` green.
        *Measured:* `manageShares.ts` holds `mintShare`/`remintShare` (READ/READ_WRITE; a WRITE_ONLY role only with a
        `WriteOnlyMint` the application hands in, refused either way round), the recovery copy, edit, list, revoke,
        reactivate, delete and the read-back helpers; `shareTree.ts` holds `SHARE_KEY`, `openShareRoot` and the exported
        `walkSubtree` → `{keys, names, nodes}`; `shareLink.ts` moved too (5b.3's dialog needs it). Memories' `createShare`
        / `reissueShare` keep their signatures and compose the core mint with the drop key; `share.ts` and a
        `features/memory/shareLink.ts` shim re-export the moved names, so no importer and no `vi.mock` path changed
        (sabotaging the core `buildShareLink` leaves both dialog suites green and `share.test.ts` red). Zero existing
        test files touched; new `shareTree.test.ts` (6) and `manageShares.test.ts` (3). `npm run check` 1709 → 1718
        passed; the core-import guard reads 174 → 177 files. Bites: an application import in `manageShares.ts` (guard
        red), every node opened under the root key (`shareTree.test.ts` + `share.test.ts` red), the recovery copy
        dropped from `mintShare` (11 red). For 5b.4: `createContributorIdentity` is core-able for READ_WRITE (its name
        key is the root key; only the WRITE_ONLY branch needs `mintUploaderKey`), `addSharePhoto`/`deleteSharePhoto`
        and `resolveShare` are Memories' (media pipeline, the share-media client).
  - [x] 5b.3 share a folder (member side) — `FOLDER` `shareable` in core; the `levelView` contract gains `shareable` and
        a `share` event; a share button on the folder overview card; a core `FolderShareDialog` (READ, READ_WRITE)
        built from the existing components and `.veyq-*` classes; en and de strings · Done when: `npm run check`.
        *Measured:* `createRegistry` registers `FOLDER` `shareable: true`; `LevelViewProps.shareable` (descriptor AND
        capability, the shell's one `shareable` computed — false at the scope root) and `LevelViewEmits.share`;
        `MemoryLevelHost` passes both through, `MemoryBrowse`'s folder overview card shows a `pi-share-alt` icon button
        (`share.share` label and tooltip, as on a Memory's detail), kept during a pending deletion (listing links is a
        read). `MemoryHomeView` mounts core's `core/sharing/FolderShareDialog.vue` — never the application's
        `shareDialog` slot — with the folder's own key from `nodeContentKey` and its name. The dialog is built only on
        `manageShares.ts` + `shareLink.ts`: list (role, note, password, by another member, switched off folded, needs
        renewing), new link (label, READ / READ_WRITE — no WRITE_ONLY —, note, password, expiry), the link shown and
        copyable, copy / revoke / re-activate / renew (one click, carried settings, behind a confirm) / delete; shared
        `.veyq-form` / `.veyq-list-row` / `.veyq-badge` / `.veyq-empty` / `.veyq-dialog-action-row` classes. Strings: the
        Memory dialog's where the meaning is the same, `share.folder.*` (en + de, 7 keys) where a Memory sentence would
        be wrong. **Not built:** editing a folder link's label/note/password/expiry/role in place (Memories'
        `updateShare` form) — a member renews or deletes instead. Tests: `registry.test.ts` (FOLDER shareable, two cases
        flipped deliberately), `MemoryHomeView.test.ts` (+3: shareable folder level view, core dialog with the folder's
        key and name, none at the root, none without the capability), `MemoryBrowse.test.ts` (+3), new
        `FolderShareDialog.test.ts` (8) and `folderShare.wire.test.ts` (2: recovery copy at the write epoch, a `wrap`
        that opens under `K_share` to a nested folder's chain key — not `K_SC`, not the parent's —, the name under
        `K_share`). `npm run check` 1718 → 1734 passed; the core-import guard reads 177 → 178 files. Bites: FOLDER
        `shareable: false` (3 red, the level-view case among them), a WRITE_ONLY option in the dialog (1 red), the
        `mintShare` wrap sealed over `writeKey(epochs)` (2 red). New e2e `folderShare.e2e.test.ts` (a READ folder link
        through `mintShare`: `rootKind` FOLDER, the descendants' names and the Memory's key through `openShareRoot` +
        `walkSubtree`, the raw resolve serves the folder's subtree only and no entry for the root, the Memory's photo
        on the raw media read, a WRITE_ONLY folder link `422 illegal_scope`) — type-checked, linted and discovered,
        **not yet run**. Once it runs green, `docs/apps/memories.md`'s UNVERIFIED line loses the `READ` folder media
        cell. Still saying "one Memory" (5b.5's): `share.title`/`share.intro`, `docs/features/sharing/_index.md`,
        `uc.sharing.share-public-viewer.md`, `uc.sharing.share-contributor.md`, `uc.sharing.share-uploader.md`; and ADR
        0080 decision 13 still says "the web still offers no chapter link (`shareable: false`)", false since 5b.1.
  - [ ] 5a.5 backend + contracts — owner decision 2026-10-07: a `READ_WRITE` folder share gives a contributor's rights in every
        Memory or chapter below it (the role is inherited as the share allows; `WRITE_ONLY` on a folder stays refused).
        Today `ShareGuestWrites` dispatches by the share ROOT's kind and the server puts every guest upload on the root
        (`ShareMediaHandler.kt:119`), so a folder guest cannot upload. The guest media operations (start, window,
        complete, list, delete) gain an optional target `nodeId` (absent = the root, unchanged) that must lie inside the
        share subtree (the 5a.2 boundary, under the scope lock) and be a kind Memories accepts (`MEMORY`, `CHAPTER`);
        `ShareGuestWrites` and the upload admission dispatch by the TARGET kind. Update the role matrix in
        `docs/apps/memories.md` and ADR 0080 decision 6 · Done when: bite tests (a folder `READ_WRITE` guest uploads
        into a Memory below and a member opens it, deletes only its own, cannot reach a node outside the subtree or a
        kind Memories refuses; a `READ` guest and a folder `WRITE_ONLY` still refused), MariaDB races, guards green.
  - [ ] 5b.4a guest tree functions, no UI — export `contentKeyContext` (or a `wrapContentKey` helper) from `tree.ts`
        and drop the duplicated literal in `shareTree.ts:79`; `core/sharing/guestTree.ts` with a `GuestTreeContext`,
        `createShareNodeAsGuest` and `placeShareNodeAsGuest` (see the 5b.4 sketch above: parent key from the chain,
        parent's served epoch, version 0 / served+1, one retry on `stale_placement` after a re-resolve);
        `createGuestIdentity` for `READ_WRITE` and the localStorage session (`veyq:share-contributor:${shareId}`) in
        core, Memories calls it (the `WRITE_ONLY` branch stays); errors mapped by code through `decodeHttpError`
        (`slug_taken` with the colliding sibling's name from a client pre-check) with new `share.folder.*` keys ·
        Done when: wire and unit tests, `npm run check`; new e2e `folderShareGuest.e2e.test.ts` (a guest creates a
        folder and a nested one, moves a Memory between two subfolders, refusals: create inside a Memory and a chapter
        out of a Memory `illegal_scope`, the share root renamed `illegal_structure`, a `READ` link
        `share_not_contributable`, a stale version `stale_placement`).
  - [ ] 5b.4b public folder page — optional `ShareBodyProps.subtreeRoot {nodeId, key, name}`; `openSharedMemory` with a
        root (key from the chain, media filtered to the subtree, `chapterName` null for the root); `MemoryShareBody`
        embedded: name from the chain, no folder note, `canContribute` from the role AND the target (after 5a.5 an
        embedded Memory takes uploads and the guest's own deletes); core `FolderShareBody` (PrimeVue `Breadcrumb`,
        `.veyq-card-interactive` cards through a small core twin of the card grid, `?node=<id>` navigation keeping the
        `#k=` fragment, child bodies through `registry.shareBody(kind)`, an embedded `unavailable` becomes a local
        `.veyq-empty`), registered in `createRegistry` (measure the import cycle; else `ShareView` renders `FOLDER`);
        a small core name dialog for 'New folder' and for the guest's name (asked once, as Memories does); en+de keys ·
        Done when: component tests with the registry as a Proxy, `npm run check`.
  - [ ] 5b.5 clean-up — chapter wording in the member share dialog (en and de: 'Share this chapter', 'It shows only this chapter'; `share.title`/`share.intro` say Memory today); a member's root rename re-seals every share's `sealedName` through the recovery copy (each
        share on its own, a failure is reported, not rolled back); the trash dialog reloads after the tree reload on a
        restore (`SpaceTrashDialog.vue:65`, test `:103`) and shows a freed-slug descendant as a root (#357); docs under
        `docs/features/sharing/` and the two contract texts that say only a Memory can be shared · Done when:
        `npm run check`, `validate_doc_links`, e2e.

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
