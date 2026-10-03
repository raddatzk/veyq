---
id: cr-platform-core-boundaries
title: Platform phase 1 — core packages stop importing the Memories application, inside the one Gradle module
status: planned
satisfies: [en.memory.tree, en.spaces.tenant-scope]
adrs: ["0079", "0072", "0013", "0012"]
invariants: [operator-blind E2EE everywhere, self-hostable by default, API-first]
---

## Open for the owner

1. **Should `ShareGuestWrites` (step 8) wait until the first non-Memories node kind exists?** Step 8 is the
   heaviest step and its second implementation does not exist yet, so the interface is shaped by one caller.
   **Recommendation: do it now.** The Memory-only check at `SharingHandler`'s create-share path is the exact
   coupling ADR 0079 names, and a core that still branches on `MEMORY` is not a core. The risk (an interface
   shaped wrong by a single implementation) is bounded by step 8 being behaviour-neutral and its interface being
   internal to the one module, so a later reshape is one commit, not a migration.

## Goal

[ADR 0079](../docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) decides that veyq is a
platform and Memories its first application, and that an application depends on core, never the reverse. No code
has moved yet (inbox #324). This CR is that restructuring's **phase 1: draw the boundary inside the existing
single Gradle module, with no behaviour change.**

After it, the **core** packages — the existing `shared/` plus four new ones, `core.node`, `core.blob`,
`core.sharing`, `core.quota` (all under `com.veyq.app.core`) — import nothing from the Memories application
(`features.media`, the future `features.memory`, and the favorites packages). Where core today does something
Memories-specific (erase media rows on a purge, collect media blob ids for a shared subtree, write media as a
guest), core instead calls a small interface that Memories implements. A guard keeps it that way, and its
bite-test proves it can go red.

The point of doing this before any new application: each interface is proven by the existing suite on the
one application that exists, with no new behaviour to confound a failure. A new node kind is the *next* CR, not
this one.

## Context & constraints

- **Hard invariants, unchanged.** Operator-blind E2EE ([ADR 0012](../docs/decisions/0012-content-key-hierarchy.md),
  [ADR 0072](../docs/decisions/0072-a-seal-binds-its-object.md)): nothing here touches what the server stores or
  sees. Blob storage stays direct-to-store ([ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md)),
  and every deletion of an object still goes through `reclaim.record`, never `blobStore.delete`.
- **No contract, crypto or migration change.** `contracts/` is not touched; no Flyway file is added. Package moves
  do not change JPQL (queries use simple entity names; there is no `persistence.xml`) or any table.
- **Boundaries here are Python guards, not ArchUnit** (none in the repo). A new boundary is a guard, and
  `scripts/` is the list ([trust-chain skill](../.claude/skills/trust-chain/SKILL.md) when in doubt).
- **Do not rely on CDI ordering.** Where several implementations must run in a fixed order, the interface carries
  an explicit `phase: Int` and the caller sorts by it, or calls the implementation by name. `@All`/`Instance`
  fan-in is not used anywhere in the backend today (command below), so this CR introduces the pattern and must
  not lean on bean-discovery order.
- **Same-package Kotlin usage is the trap in every move.** A test that never writes an `import` for an entity
  because it shares a package with it stops compiling when the entity moves. Each move step greps `src/test` too.
- **Out of scope.** The web side (a separate CR: kind→detail resolver, `childrenOf` buckets, `ShareView`
  dispatch, reversing the imports from `app/`, `components/app/` and `sw/` into `features/memory`, an eslint
  `no-restricted-imports`). The Gradle module split (a later CR, after these interfaces have proven themselves).
  Any new node kind. Any contract or crypto change.

## Inventory (measured at refine, `main` @ `9a4e7974`; each row is a command — re-run it, do not trust a tally)

All paths below are relative to `backend/src/main/kotlin/com/veyq/app/` (`M`) or `backend/src/test` (`T`), run
from `backend/`. **Per `changes/README.md`, the first implement action is to spot-check the row this CR leans on
hardest: row 4 (the quota split), because step 3's whole shape rests on `tenantUsage`/`usageByTenant` reading no
media table.**

| # | Fact | Locator (run it) |
|---|---|---|
| 1 | `core/` does not exist yet; the only boundary guard covers `shared/` and forbids only `features` imports | `ls $M` ; `sed -n 1,80p ../scripts/validate_shared_imports_no_features.py` |
| 2 | The only places core-to-be code imports the Memories application today (the sites this CR removes) | `grep -rn "^import com.veyq.app.features.media\|^import com.veyq.app.features.content.\(favorites\|itemfavorites\)" $M/features/spaces $M/features/content/NodeSubtreePurge.kt $M/features/sharing $M/features/admin` |
| 3 | `NodesStore.kt` holds `ParentSlot`, `NodeRow`, `TrashedNodeRow` and imports nothing from media, so `NodesStore`/`NodesHandler`/`ContentResource` may stay in `features.content` for now | `grep -n "class ParentSlot\|class NodeRow\|class TrashedNodeRow" -r $M/features/content` ; `grep -n "^import com.veyq.app.features" $M/features/content/NodesStore.kt` |
| 4 | `StorageQuota`'s `tenantUsage`/`usageByTenant`/`lockTenant` read only `veyq_blob`, `veyq_upload_session`, `veyq_scope`, `veyq_tenant`; only `linkUsage` (reads `veyq_media`), `linkFits` and `tenantFits` (take `UploadDeclare`, a media type) are media-specific | `grep -n "fun \|veyq_" $M/features/media/StorageQuota.kt` |
| 5 | Who calls which quota function: admin calls only `usageByTenant` and `tenantUsage`; `MediaHandler` calls `lockTenant`, `tenantFits`, `linkFits` | `grep -rnE "\.(tenantUsage\|usageByTenant\|lockTenant\|linkFits\|tenantFits\|linkUsage)\(" $M` |
| 6 | `NodeEntity` carries media columns (`coverBlobId`, `coverByteSize`, `mediaRevision`, `deleteRevision`, `sealedDropPrivateKey`); they **stay** — moving them would break `validate_blob_reference_arity`, `MediaStore.bumpMediaRevision`, `SharingStore.create`'s drop-key update and the `NodeRow` DTOs | `grep -n "coverBlobId\|coverByteSize\|mediaRevision\|deleteRevision\|sealedDropPrivateKey" $M/features/content/Entities.kt` |
| 7 | The `Entities.kt` files are multi-entity: media holds `UploadSession`, `Blob`, `Media`, `MediaDerivative`, `Bucket`; sharing holds `Share`, `ShareIdentity`, `ShareRetiredId` (`ScopePurge` uses all three — move the file wholesale) | `grep -n "^class .*Entity" $M/features/media/Entities.kt $M/features/sharing/Entities.kt $M/features/content/Entities.kt` |
| 8 | Test files that use a moving entity (including same-package users that never import it) | `for e in NodeEntity BlobEntity UploadSessionEntity ShareIdentityEntity ShareEntity ShareRetiredIdEntity; do echo $e main $(grep -rlw $e $M \| wc -l) test $(grep -rlw $e $T \| wc -l); done` ; `grep -rlw "NodeEntity" $T` for the file list |
| 9 | No `persistence.xml`; JPQL names entities by simple name, so a package move changes no query | `find . -name persistence.xml -not -path "./build/*"` (empty) ; `grep -rniE "(from|join) +(Node|Blob|UploadSession|Share)Entity" $M \| head` |
| 10 | `@All` / `Instance<>` fan-in is unused; the one `Instance` is `shared/tenant/TenantIngressSinkProducer.kt` | `grep -rn "@All\|Instance<" $M` |
| 11 | `ScopePurge.eraseScope` hard-codes the media/sharing/favorites entity deletes | `grep -n "Entity\.\(delete\|deleteById\)\|reclaim.record" $M/features/spaces/scope/ScopePurge.kt` |
| 12 | `NodeSubtreePurge` order today: step 0 lock media + buckets and collect guest ids FIRST; 1 `purgeMediaRows`; 2 delete buckets; 3 cover blobs (`NodeEntity.coverBlobId` + `BlobEntity` + `reclaim.record`); 4 `ItemFavorites`; 5 `sharing.tombstoneAllOn`; 6 `sharing.dropDeadIdentities` (must run after 1, 2 and 5); then the node delete | `sed -n 1,200p $M/features/content/NodeSubtreePurge.kt` |
| 13 | `SharingStore.dropDeadIdentities` decides liveness from `MediaEntity.count` and `BucketEntity.count` | `grep -n "MediaEntity.count\|BucketEntity.count" $M/features/sharing/SharingStore.kt` |
| 14 | `SharingStore.subtree` and `subtreeBlobIds` mix core (`collectSubtreeIds`, the live-node predicate, node rows, the cover arm) with media/derivative arms; `deleteContributedMedia` is media | `grep -n "fun subtree\b\|fun subtreeBlobIds\|fun deleteContributedMedia\|deletedAt is null" $M/features/sharing/SharingStore.kt` |
| 15 | The two `deletedAt is null` predicates in the subtree reads are different predicates and must stay separate | `grep -n "deletedAt is null" $M/features/sharing/SharingStore.kt` ; tests `ShareDeletedNodeSliceTest`, `ShareTrashedMediaSliceTest` |
| 16 | Memory-specific sites in the sharing handler: the create-share kind check and the guest write paths | `grep -n "NodeEntity.MEMORY\|fun startForLink\|fun windowForScope\|fun completeForNode\|fun deleteMedia" $M/features/sharing/SharingHandler.kt` |
| 17 | The upload DTOs the guest write path needs live in the media handler file | `grep -n "^data class \(UploadDeclare\|CompleteItem\|WindowRequest\|WindowItemResult\|CompleteItemResult\)\|UploadOpener" $M/features/media/MediaHandler.kt` |
| 18 | `completeOne` and `commitCover` deny `FOLDER` (a deny-list); `NodesHandler.parentAllows` ends `else -> false`, so the set of kinds is closed and an allow-list is equivalent today | `grep -n "NodeEntity.FOLDER\|illegal_target\|IllegalTarget" $M/features/media/MediaHandler.kt` ; `grep -n "fun parentAllows" -A6 $M/features/content/NodesHandler.kt` |
| 19 | Guards that name something this CR moves | `grep -ln "ScopePurge\|SharingStore\|subtreeBlobIds\|features/media\|features/sharing" ../scripts/*.py` |

Notes carried from the spike that the commands above do not show:
- `validate_scope_erasure_arity.py` has `PURGE` hard-wired to `features/spaces/scope/ScopePurge.kt` and an
  `ERASED_ENTITY` regex over that function's body.
- `validate_blob_reference_arity.py` has `SHARING_STORE` and `LIVE_TWIN_FN = "fun subtreeBlobIds"` and its
  live-twin check reads that body for the markers `ids.add(m.blobId)`, `MediaDerivativeEntity`, `coverBlobId`.
- `validate_store_reclaim_arity.py` is path-agnostic: every new eraser must use `reclaim.record`, never
  `blobStore.delete`.
- `validate_guard_gaps.py` forges files at anchors that do **not** move (`MediaTrashPurge.kt`, `ShareThrottle.kt`,
  `MembershipPolicy.kt`). Pitest/mutation configs name only `*Policy` classes, none of which move.

## Phases

One commit per step, each ending with `./gradlew check` and the trust-chain guards its Done-when names green
(`changes/README.md`: a wave runs the guards its done-when names; the coordinating session runs the whole glob
on the merged tree). **Confirm the commit path works before step 1** (one throwaway commit) — every step's green
is only as strong as its being committed.

**Ordering and parallelism.** Step 1 first. After it, steps 3 and 4 touch disjoint files and may run in
parallel. Steps 2a and 2b both touch `ScopePurge`, `MediaStore` and `SharingStore`: serialize them. Steps 5 and 6
both edit `ScopePurge`/`NodeSubtreePurge` and the guard files, and steps 7 and 8 both edit `SharingStore` and
`SharingHandler`: run **5 ‖ 7 and 6 ‖ 8 only after step 2**, and keep 7 before 8.

- [ ] 1 core-import guard — extend `scripts/validate_shared_imports_no_features.py` (or a sibling) so that
      `core/**` and `shared/**` may not import `features.media`, `features.memory` or the favorites packages
      (`features.content.favorites`, `features.content.itemfavorites`). A missing `core/` directory is **not** an
      error until step 2a creates it (the guard must be green on the untouched tree; run its predicate against
      the untouched tree first, `changes/README.md`). The guard's docstring keeps its three fields (asserts /
      the defect that bought it / its limit), the limit noting that `features.memory` does not exist yet and is
      forbidden prospectively. Register a forged-file case in `validate_guard_gaps.py` `CASES` (closed hole, last
      column `False`): the forgery plants a `core/node/` file that imports `features.media`.
      · **Bite-test (per bite, lever named):** the forged core file importing `features.media` turns the guard
      red; the same forgery against a file that imports only `shared.*` stays green.
      · Done when: `python3 scripts/validate_shared_imports_no_features.py` and
      `python3 scripts/validate_guard_gaps.py` green.
- [ ] 2a move entities, part 1 — `BlobEntity` and `UploadSessionEntity` out of `features/media/Entities.kt` into
      `core.blob` (split the file; `Media`, `MediaDerivative`, `Bucket` stay); `features/sharing/Entities.kt`
      moves wholesale to `core.sharing`. Pure package change: fix imports in main and **all** test files,
      including same-package users (row 8; run `./gradlew compileTestKotlin` and read the red, do not guess).
      · Done when: `./gradlew check` green with the suite unchanged; `python3 scripts/validate_scope_erasure_arity.py`
      and `python3 scripts/validate_blob_reference_arity.py` green and **their own source unedited** in this
      commit (`git diff --stat HEAD~1 -- scripts/` shows only step-1-style anchors if any, and a reviewer can see
      why).
- [ ] 2b move entities, part 2 — `NodeEntity` into `core.node` (its media columns stay, row 6). Same method.
      · Done when: as 2a.
- [ ] 3 split `StorageQuota` — new `core.quota.StorageUsage` with `tenantUsage`, `usageByTenant`, `lockTenant`
      (core SQL only, row 4); `features/media` keeps the remainder (`linkUsage`, `linkFits`, `tenantFits`)
      delegating to `StorageUsage`. No interface: one implementation, and the dependency already points
      media → core. `TenantHandler` and `TenantStorageHandler` switch to `StorageUsage`; `MediaHandler` keeps
      calling the media remainder (row 5).
      · Done when: `./gradlew check` green; named proofs `TenantStorageQuotaSliceTest`, `ShareUploaderSliceTest`,
      `TenantSuspendSliceTest` green.
- [ ] 4 allow-list instead of deny-list — `MediaHandler.completeOne` (returns `"illegal_target"`) and
      `commitCover` (`IllegalTarget`): replace `kind == FOLDER` by "kind is not `MEMORY` or `CHAPTER`".
      Behaviour-neutral because `NodesHandler.parentAllows` ends `else -> false`, so kinds are closed (row 18) —
      **state in the commit that this stops being true the day a kind is added, which is the point.**
      · Done when: `./gradlew check` green; named proofs `MediaSliceTest.mediaOnFolderIsIllegalTarget` and
      `NodeCoverSliceTest.coverOnFolderIsIllegalTarget` green.
- [ ] 5 `ScopeEraser` — core interface `interface ScopeEraser { val phase: Int; fun eraseScope(scopeId: String) }`
      replacing `ScopePurge`'s hard-coded media/sharing/favorites entity deletes (row 11). Constraints that stay
      true: `reclaim.record` for `Blob`/`UploadSession` runs **while the rows still name their keys** (stays in
      core); the delete order derivative → media → blob is FK-ordered, so the media eraser runs before core's
      blob delete; shares need their ids retired before they are deleted; `ScopeEntity` is deleted last. Order is
      the explicit `phase` sorted by the caller, never bean order. `validate_scope_erasure_arity.py` is
      updated to scan **every file implementing `ScopeEraser`** (it is hard-wired to `ScopePurge.eraseScope` today)
      and its `ERASED_ENTITY` check keeps asserting that every table with a scope reference is erased by one of them.
      · **Bite-test:** drop one entity delete from the media eraser (lever: delete the `MediaEntity.delete` line
      in the implementation) → `validate_scope_erasure_arity.py` red; add an eraser that calls
      `blobStore.delete` → `validate_store_reclaim_arity.py` red.
      · Add an **ordering test** that fails if the media eraser is given a phase after core's blob delete (the
      FK order is a runtime failure, so the test must hit a real scope with media in it).
      · Done when: `./gradlew check`, `validate_scope_erasure_arity.py`, `validate_store_reclaim_arity.py`,
      `validate_guard_gaps.py` green.
- [ ] 6 `NodeSubtreeEraser` + `GuestIdentityUsage` (both `core.node`) — `NodeSubtreePurge` keeps its class name
      and its `eraseExpiredIn` signature (tests inject it). The media eraser takes steps 0–2 of row 12 (lock
      media + buckets, collect guest ids; `purgeMediaRows`; delete buckets) and **returns the guest-id set**
      before core's step 5 (`tombstoneAllOn`); core keeps step 3 (cover blobs: `NodeEntity.coverBlobId` +
      `BlobEntity` + `reclaim.record`), `sharing.tombstoneAllOn`, `dropDeadIdentities` and the node delete.
      Favorites (step 4) moves to its own eraser implementation. `dropDeadIdentities` must still run after
      steps 1, 2 and 5; its `MediaEntity.count`/`BucketEntity.count` (row 13) become `GuestIdentityUsage`
      queries that media implements.
      · Done when: `./gradlew check` green; named proofs `NodeTrashSliceTest`, `NodeTreeRaceSliceTest`,
      `ShareDeletedNodeSliceTest`, `NodeCoverSliceTest`, plus a new **order test** that fails if
      `dropDeadIdentities` runs before the media eraser's guest set is returned.
- [ ] 7 `SharedSubtreeContributor` (`core.sharing`) — `SharingStore.subtree` and `subtreeBlobIds` keep
      `collectSubtreeIds`, the live-node predicate, node rows and the cover arm; the media and derivative arms move
      to the media implementation (`authorizedBlobIds`). **Keep the two `deletedAt is null` predicates
      separate** (row 15). Update `validate_blob_reference_arity.py`'s live-twin check (`LIVE_TWIN_FN`, the markers
      `ids.add(m.blobId)`, `MediaDerivativeEntity`, `coverBlobId`) to scan the core function **plus every
      contributor's `authorizedBlobIds` body**, so the check follows the arms to where they now live.
      · **Bite-test:** remove the derivative arm from the media contributor → `validate_blob_reference_arity.py`
      red (it must be red *because of the follow*, not because the file moved: run it once with the arm present
      to see green first).
      · Done when: `./gradlew check`, `validate_blob_reference_arity.py`, `validate_guard_gaps.py` green; named
      proofs `ShareDeletedNodeSliceTest`, `ShareTrashedMediaSliceTest` green.
- [ ] 8 `ShareGuestWrites` (`core.sharing`, dispatch by `NodeEntity.kind` via `supports(kind)`) — the sites are
      `SharingHandler.startForLink`, `windowForScope`, `completeForNode`, `deleteMedia` (row 16);
      `SharingStore.deleteContributedMedia` moves to the media implementation. **Prerequisite inside this step:**
      move `UploadDeclare`, `WindowRequest`, `CompleteItem`, `UploadOpener`, `WindowItemResult`,
      `CompleteItemResult` from `features/media/MediaHandler.kt` into a core package (`core.upload` or
      `core.sharing`; row 17) — otherwise core's interface mentions media types. The create-share check
      `node.kind != MEMORY` becomes "no registered `ShareGuestWrites` supports this kind". This is the heaviest
      step; split it into the DTO move and the dispatch if a single commit would not stay reviewable.
      · Done when: `./gradlew check` green; named proofs `ShareUploaderSliceTest`, `GuestWriteRaceSliceTest`,
      `ShareUploadWindowSliceTest`, `ShareWriteProofSliceTest` green.
- [ ] 9 verify — core imports nothing from the Memories application, across the whole tree.
      · Done when: `grep -rn "features.media\|features.content.\(favorites\|itemfavorites\)" $M/core $M/shared`
      is empty (run from `backend/`), and the step-1 guard is green on the final tree.

## Definition of done

- **The enforcement exists and is bitten.** The core-import guard (step 1) is green on the final tree, and its
  forged-file case in `validate_guard_gaps.py` is red when the forgery is planted (bite recorded in the commit
  message). The grep in step 9 is empty. Each interface step (5–8) carries its own bite and its ordering test.
- **Behaviour is unchanged.** `./gradlew check` is green with **no test deleted or weakened** — account the
  suite per assertion where a test moved, not by count (`changes/README.md`). **No contract diff:**
  `git diff main -- contracts/` is empty, and `git diff main --stat -- backend/src/main/resources/db/` is empty
  (no migration). No crypto-vector diff: `git diff main -- contracts/crypto` is empty.
- **The trust chain.** The guards this CR names — `validate_shared_imports_no_features.py`,
  `validate_scope_erasure_arity.py`, `validate_blob_reference_arity.py`, `validate_store_reclaim_arity.py`,
  `validate_guard_gaps.py`, `validate_changes.py` — are green. The coordinating session runs
  `for f in scripts/validate_*.py; do python3 "$f"; done` on the merged tree.
- **Coverage.** No acceptance criterion changes (`satisfies:` names the two enablers whose code moves), so
  `docs/coverage/` needs no new rows; `python3 scripts/validate_coverage.py` is green, and any coverage entry
  that cites a moved test or class by path is repointed.
- **Docs.** `docs/apps/README.md`'s Memories row ("Its code is not yet separated from core") and ADR 0079's
  Consequences are updated to say what is now separated and what is not (the web side and the module split).
  The `docs/crosscutting/developer-styleguide.md` § on `shared/` names the extended guard. Inbox #324's status
  text names this CR, and deletes it only when the later CRs of the series have also landed.
- **Retrospective** written, and the CR deleted after what it taught moved to where it binds
  (`changes/README.md`).

## Retrospective

_(filled in at close)_
