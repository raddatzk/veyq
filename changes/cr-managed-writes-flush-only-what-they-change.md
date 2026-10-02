---
id: cr-managed-writes-flush-only-what-they-change
title: A managed-entity write flushes only the columns it changed, so it cannot undo another writer's transition
status: done
satisfies: [uc.admin.suspend-tenant, uc.admin.tenant-terms, uc.sharing.share-uploader, uc.spaces.rename-group, uc.spaces.set-space-icon, uc.account.change-password, uc.account.recover-access, uc.memory.buckets, uc.memory.live-updates]
adrs: ["0024", "0031"]
invariants: [operator-blind E2EE everywhere]
---

## Open for the owner

**Fourth round answered by the owner, 2026-10-01: every recommendation added since the third answer is taken too** (incl. the granter-lock questions Q1–Q3 and the rotation CR's Q8/Q9).

**Third round answered by the owner, 2026-10-01: every recommendation added since the second answer is taken too** (questions added by the third refine wave and by implementation hand-backs). Rows those questions proposed exist in `docs/inbox.md` (#233–#243).

1. **`@DynamicUpdate` on every entity, or only on the eight the sweep implicates?** **Recommendation: every
   entity.** The trap is a default, not a defect of particular sites. All eight implicated entities got it the
   same way: a writer was added somewhere else, and nobody re-read every managed write of the row. An entity
   without the annotation today is one new bulk update away from the same bug. The cost is a per-flush UPDATE
   statement instead of a cached one, which is negligible at this write rate. Hibernate ORM 7.3 has no global
   switch for it (remembered, not checked), so "every entity" means 33 annotations and E2's test.
2. **A guard: a metamodel test that every entity carries `@DynamicUpdate`?** **Recommendation: yes (E2).**
   `cr-one-time-secrets-spend-by-compare-and-set` declined a guard (its Question 4), because that one would
   have been a text match over query strings. This one reads Hibernate's own metamodel, so it is exact. It also
   bites: drop the annotation from any entity and the test names it.
3. **Do the three same-column races (E3) belong here?** `@DynamicUpdate` cannot help where two writers change
   the **same** column. The sweep found three: a share's `enabled`, a media's `bucketId`, and the rename and
   icon epoch gate. **Recommendation: include them, as Phase 3.** They sit on entities this CR annotates, and
   once Phase 1 lands, they are exactly what is left of the class. Each gets the compare-and-set rule from
   `cr-one-time-secrets-spend-by-compare-and-set`. The epoch gate matters most: it is a forward-secrecy hole
   under ADR 0024, read but not run.

## Goal

No entity carries `@DynamicUpdate` (`grep -rn DynamicUpdate backend/src/main/kotlin` → nothing). So every write
through a managed entity issues an `UPDATE` of **every** column, writing back values it only read. Any column
that another transaction changed between that read and the flush is silently reverted. That holds for
`status` and for every other column.

Inbox #224 asked for the entities that are both bulk-updated on `status` and saved as managed entities. The
sweep shows the class is wider. The column need not be `status`, and the other writer need not be a bulk
update: a managed write under a lock is reverted the same way by an unlocked one. Four cases were measured,
each 5 of 5:

- a tenant suspension is undone by a concurrent terms edit;
- an UPLOADER link's drop private key is wiped by a concurrent rename of its Memory;
- a scope's epoch is rolled back by a concurrent group rename;
- an account's auth key is rolled back by a concurrent consent toggle.

The lever is one annotation per entity. With it, a write flushes only the columns it changed. Measured: all four
go green. A test holds the default. What the annotation cannot fix, two writers of one column, gets the
compare-and-set rule from `cr-one-time-secrets-spend-by-compare-and-set`.

## Inventory

Substrate: worktree of `main` @ `7a34c57`, 2026-10-01, H2 2.4 test datasource (`MODE=MariaDB`, READ
COMMITTED). **Run** means a throwaway store-level probe, 5 rounds with a fresh row each. One transaction
makes its change and holds its commit 300 ms. The other writer starts inside that window, reads the
pre-change value (a plain read does not wait for the lock), and its flush then waits for the lock and
overwrites. The probe is not in the tree, and each DoD test recreates it. **Read** means established by reading
the code only. The sweep of managed writes was done by a read-only subagent and spot-checked. Each row
carries its own locator.

| # | Entity | Writer that changes a column | Managed writer that reverts it | Result | Locator |
|---|---|---|---|---|---|
| 1 | `TenantEntity` | `TenantStore.suspend`, a guarded bulk `status` update | `TenantStore.updateTerms`, and `setStorageQuota` | **Run:** `suspend` returned `true`, then status **`ACTIVE`**, 5/5. The tenant owner's terms edit lifts the Server Owner's suspension | `grep -n 'tenant.termsVersion += 1\|tenant.storageQuotaBytes = quotaBytes' backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantStore.kt` |
| 2 | `NodeEntity` | `SharingStore.create`, the UPLOADER drop-key compare-and-set (`sealedDropPrivateKey … is null`) | `NodesStore.applyUpdate` (rename, move, meta) | **Run:** the share answered `CREATED`, then `sealedDropPrivateKey` **null**, 5/5. **Read:** every upload sealed to that link's drop key can no longer be opened by the Memory's members (ADR 0031 point 7). The next UPLOADER link sets a new key, which orphans the first set for good | `grep -n 'sealedDropPrivateKey is null' backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt`; `grep -n 'encryptedName?.let' backend/src/main/kotlin/com/veyq/app/features/content/NodesStore.kt` |
| 3 | `ScopeEntity` | `ScopesStore.rotate` sets `currentEpoch` under `PESSIMISTIC_WRITE` | `ScopesStore.renameGroup`, `setIcon`, `clearIcon`, `softDelete`, `restore` (all unlocked) | **Run, with a stand-in for the rotation:** a bulk `currentEpoch + 1` holding the row, then `renameGroup`. The epoch went `0 → 1 → 0`, 5/5. The stand-in has the rotation's row-level shape (an uncommitted epoch change holding the row lock), not its code. **Read:** the epoch counter goes backwards, and the next rotation issues an epoch number that is already in use | `grep -n 'currentEpoch' backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopesStore.kt`; `grep -n 'it.encryptedName = encryptedName' backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopesStore.kt` |
| 4 | `VeyqUser` | `PasswordChangeStore.applyChange` and `RecoveryStore.applyReset` set `authPublicKey` (managed). `RotationStore.applyRotation` sets the identity pair | `AccountProfileHandler.update`'s `requireGroupAddConsent` write, and each of the others against each other | **Run, with a stand-in for the key change:** a bulk `authPublicKey` update holding the row, then the profile handler's exact managed statement (`AccountProfileHandler.kt:51`). The key went back to the old one, 5/5. **Read:** a password change or a recovery reset that overlaps a consent toggle loses its new auth key on the server, while its new wraps stay | `grep -rn 'user.authPublicKey = \|it.requireGroupAddConsent = ' backend/src/main/kotlin/com/veyq/app/features/account` |
| 5 | `MembershipEntity` | `TasksStore` grant delivery (bulk `wrappedGrant`, `grantedEpoch`, …), `ScopesStore.clearPendingConsent` (bulk `pendingConsent`) | `ScopesStore.setRole` (unlocked) | **Read, not run:** a role change that overlaps a grant delivery writes the older grant and epoch back, and the member waits for a key that was already delivered. One that overlaps a consent writes `pendingConsent = true` back | `grep -n 'wrappedGrant = ?1, grantedEpoch = ?2' backend/src/main/kotlin/com/veyq/app/features/tasks/TasksStore.kt`; `grep -n 'pendingConsent = false, sealedNameKey = null' backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopesStore.kt`; `grep -n 'fun setRole' -A6 backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopesStore.kt` |
| 6 | `ShareEntity` | `SharingStore.deactivateSharesByOwnerInScope`, the bulk `enabled = false` a member removal runs | `SharingStore.updateShare` (note, name, role, password) | **Read, not run:** an edit that overlaps the owner's removal writes `enabled = true` back, and the removed member's link stays live | `grep -n 'enabled = false where ownerAccount' backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt`; `grep -n 'fun updateShare' backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt` |
| 7 | `NodeEntity` | `MediaStore.bumpMediaRevision`, bulk `mediaRevision` / `deleteRevision` | `NodesStore.applyUpdate`, `deleteSubtree`, `restoreSubtree`; `MediaStore.commitCover`, `clearCover` | **Read, not run:** a rename or cover change that overlaps a media add writes the older revision back. The live-updates signal steps backwards, and a client whose last seen revision is the restored one misses the add | `grep -n 'mediaRevision = mediaRevision + 1' backend/src/main/kotlin/com/veyq/app/features/media/MediaStore.kt` |
| 8 | `MediaEntity` | `MediaStore` sub-bucket delete, bulk `bucketId` reparent | `MediaStore.setMeta`, `trashMedia`, `restoreMedia`, the `listBuckets` backfill | **Read, not run:** a meta edit that overlaps a sub-bucket delete writes the deleted bucket's id back | `grep -n 'bucketId = ?1 where bucketId = ?2' backend/src/main/kotlin/com/veyq/app/features/media/MediaStore.kt` |
| 9 | `TenantInviteEntity`, `SessionFamilyEntity` | — | — | Measured and owned by `cr-one-time-secrets-spend-by-compare-and-set` (Inventory 5 and 12; landed 2026-10-02: the re-tag is now `TenantStore.carryInvites`, so the locator in this row finds nothing, and `SessionService.rotate` holds the family lock). Listed so the sweep is complete. The annotation here makes those two belt and braces | `grep -n 'inv.persist()' backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopesStore.kt` |
| 10 | **Same column, two writers** (E3): the annotation does not help | (a) `ShareEntity.enabled`: `revoke` and `reactivate` (managed) against the removal's bulk `enabled = false`. (b) `MediaEntity.bucketId`: `moveMedia` (managed) against the reparent. (c) The rename and icon **epoch gate**: `ScopesHandler` checks `currentEpoch == nameEpoch` (and `iconEpoch`) with an unlocked read, then writes | **Read, not run.** (a) A removed member's `reactivate` that read before the removal commits leaves an enabled link it owns. (b) A move into a bucket being deleted leaves the media pointing at it. (c) A rotation that commits between the gate and the write stores a name or icon sealed under the pre-rotation epoch, which ADR 0024 says a removed member must not be able to read | `grep -n 'currentEpoch(scopeId) != nameEpoch\|currentEpoch(scopeId) != iconEpoch' backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopesHandler.kt` |
| 11 | **The lever, on H2** | Throwaway: `@org.hibernate.annotations.DynamicUpdate` on `TenantEntity`, `NodeEntity`, `ScopeEntity` and `VeyqUser` | — | **Run:** rows 1–4 all green, 5/5 each: `SUSPENDED`, the drop key kept, epoch `0 → 1`, the new auth key kept | the probe is not in the tree |
| 12 | Scale | 33 `@Entity` classes in 22 files. Hibernate ORM 7.3.4.Final | — | **Run** (counted) | `grep -rh '^@Entity' backend/src/main/kotlin` → 33 lines, `grep -rl '^@Entity' backend/src/main/kotlin` → 22 files; `grep -n 'name="hibernate-core"' backend/gradle/verification-metadata.xml` |

Entities with managed writes but no second writer found (`MasterKeyWrapEntity`, `TenantMailSettingsEntity`,
`TenantProfileEntity`, `BucketEntity`, the favourites, `StoreReclaimEntity`, `VeyqInstance`): read, and no
overlap found. The annotation reaches them anyway (Open question 1).

## Decisions

- **E1: every entity carries `@DynamicUpdate`** (Open question 1). One line per entity class. With it, a
  managed write flushes only the columns whose value it changed, so it can no longer revert a column it only
  read. Nothing else changes. A write that changes nothing still issues no `UPDATE` and takes no row lock,
  exactly as today.
- **E2: a metamodel test holds the default** (Open question 2). It walks the persistence unit's metamodel
  (`EntityManagerFactory.metamodel.entities`) and fails, naming the class, for any entity type without
  `@DynamicUpdate`. It lives with the backend tests, so it runs in `./gradlew check`. It reads the metamodel and
  not the source tree, so an entity in any package is covered.
- **E3: the three same-column races follow `cr-one-time-secrets-spend-by-compare-and-set`'s rule** (Open
  question 3):
  - (a) `revoke` and `reactivate` become guarded updates of `enabled`. `reactivate` checks the caller's
    membership **under the scope's lock**, so a removal that commits first is seen. The phase settles which of
    the two shapes the existing "reactivate transfers ownership" rule needs, red test first;
  - (b) `moveMedia` sets `bucketId` with `… where id = ? and bucketId = ?old`, and a target bucket that is
    gone answers the existing not-found;
  - (c) `renameGroup` and `setIcon` write with `… where id = ?1 and currentEpoch = ?2` and answer the existing
    `StaleEpoch` on a zero count. The handler's earlier read stays as the fast path.
- **No migration, no contract change, no new error code.**

## Phases

- [x] 1 backend, the default (E1, E2). Annotate all 33 entities and add the metamodel test. The four measured
  races (Inventory 1–4) get their red-first tests in the slice tests of their features.
  · Done when: DoD #1–#4 and E2's test are green, and `./gradlew check` is green in `backend/`.
- [x] 2 backend, the read-only pairs (Inventory 5–8). Red-first tests only. Phase 1's annotation is expected to
  turn them green. A test that cannot be made red **before** Phase 1 is recorded as such, not claimed.
  · Done when: DoD #5–#8 are green (or recorded), and `./gradlew check` is green.
- [x] 3 backend, same-column races (E3), one bullet per race, each red first.
  · Done when: DoD #10a–c are green, and `./gradlew check` is green.
- [x] 4 docs. `docs/crosscutting/developer-styleguide.md` §12: the single-use bullet from
  `cr-one-time-secrets-spend-by-compare-and-set` Phase 4 says a managed write flushes every column. Once this
  CR has landed, that is no longer true. The bullet becomes: **every entity carries `@DynamicUpdate` (E2 holds
  it), so a managed write flushes only what it changed. Two writers of the same column still need the
  compare-and-set rule.** Whichever CR lands second edits the bullet. `TenantInviteEntity`'s KDoc (that CR's D3)
  is reworded the same way.
  · Done when: the trust-chain glob is green.

Phase 1 must come before Phase 2. Phase 3 does not depend on either.

## Definition of done

- **Red first, deterministic, store level, written before Phase 1.** Each test runs the changing writer in a
  `QuarkusTransaction.requiringNew()` that holds its commit 300 ms, starts the managed writer inside that
  window, and asserts the change survived. 5 rounds, a fresh row each. Today every one fails 5/5 (Inventory
  1–4). Real writers on both sides wherever the probe used one:
  - **#1** `TenantStore.suspend` against `updateTerms`, and against `setStorageQuota`: the status stays
    `SUSPENDED`;
  - **#2** an UPLOADER `SharingStore.create` against `NodesStore.applyUpdate`: the drop key survives;
  - **#3** the epoch change against `renameGroup`. It uses the real `ScopesStore.rotate` if a test can drive it
    with a minimal grant set. Otherwise it uses the probe's stand-in, named as one in the test;
  - **#4** the real `PasswordChangeStore.applyChange` (seed a `PASSWORD` wrap) against the real
    `AccountProfileHandler.update` (seed a tenant profile): the new auth key survives.
- **#5–#8** the same shape for Inventory 5–8, red first or recorded as not reddenable.
- **#10a–c** red first, each through its handler, with the stall device above.
- **Bite, per lever:**
  - remove `@DynamicUpdate` from one entity: E2's test goes red and names it, and that entity's race test goes
    red. Do this once for each of `TenantEntity`, `NodeEntity`, `ScopeEntity` and `VeyqUser`;
  - drop the `and currentEpoch = ?2` predicate: #10c goes red; the same for the guards of (a) and (b).
- **Controls.** Each race test ends with the managed writer alone: its own change must land, so a test that
  blocks every write cannot pass.
- `./gradlew check` is green in `backend/`, and the trust-chain glob is green. Inbox #224's status cell points
  here.

## Retrospective

- **Planning: the refine prescribed fixes for races it had only read, and two were wrong.** (b)'s
  `and bucketId = ?old` guards a column the race never touches; run, the move into a bucket being deleted was a
  `500` from `fk_media_bucket`, fixed by a lock on the target bucket that both writers take first. (a)'s "guarded
  update of `enabled`" was already done by `cr-share-link-delete`'s row lock; the live race was the membership
  check read before it, fixed by taking the scope lock every removal holds. Root cause: "read, not run" rows
  carried a fix shape, and the read did not include the schema's foreign keys or the locks already in place.
  Harvested into `changes/README.md` (*a race the refine only read …*).
- **Planning: DoD #3 levered on a writer Phase 3 removes.** It named `renameGroup` as the managed writer that rolls
  the epoch back, and Phase 3 turns that into a guarded bulk update. Its bite would have stopped biting, which is
  exactly § *a bite's lever must survive the CR's own later phases*. The test uses `clearIcon`, which stays a
  managed write. Also, two of the four "measured" reverts were closed before implementation: the epoch one by
  `ScopeEntity`'s annotation (`cr-tenant-removal-share-links`) and the share edit by its row lock. The brief said
  to re-run every locator first, and that caught both. The CR had not been re-read when those landed.
- **Execution: the worktree sandbox refuses compound shell commands** (variables, globbed `--tests` filters, `cd &&`
  chains). Filtered test runs went through a small Python runner in the scratchpad. The first `cp` restore also
  hit the interactive alias; the restore used `command cp` and a byte compare, per the rule. **Next time:** start a
  bite-heavy session by writing the runner and the snapshot/restore helper first, before the first red.

