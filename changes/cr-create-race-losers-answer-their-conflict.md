---
id: cr-create-race-losers-answer-their-conflict
title: The loser of a concurrent create answers the conflict a replay gets, never a 500
status: planned
satisfies: [uc.admin.setup-server, uc.sharing.share-public-viewer, uc.sharing.share-contributor, uc.memory.upload, uc.memory.buckets, uc.memory.chapters]
adrs: ["0072", "0042"]
invariants: [operator-blind E2EE everywhere]
---

## Open for the owner

**Third round answered by the owner, 2026-10-01: every recommendation added since the second answer is taken too** (questions added by the third refine wave and by implementation hand-backs). Rows those questions proposed exist in `docs/inbox.md` (#233–#243).

1. **Scope: the three sites #223 named, or every create that has the same shape?** Inventory 4–11 lists eight
   more creates that check "taken?" with an unlocked read and then insert. Each has the same loser (a 500 from
   a unique index), and each has a named answer already on the wire. **Recommendation: all eleven, one phase
   per feature.** The mechanism is one shared function, so leaving eight sites unwrapped would leave the same
   500 one call away. Each site still gets its own race test, since a site the test does not cover is a site
   nothing proves.
2. **Mechanism for an insert: re-ask after a failed commit, classify the exception, or lock a parent?**
   - **(a) Re-ask (D1).** On a failed commit, the handler re-runs the site's own "taken?" read. If it now
     answers "taken", the loser gets the named conflict. Otherwise the failure is re-thrown.
   - **(b) Classify.** Map a unique-violation exception to the conflict. This needs dialect-aware matching
     (H2 `23505`, MariaDB error 1062 under SQLSTATE `23000`, or Hibernate's constraint kind). It would also
     answer "taken" for a violation of some other unique index, which hides a real bug as a 409.
   - **(c) Lock a parent** (scope, node, instance row) before the check. That puts a lock on the upload hot
     path, and it still misses two creates of one client-minted id under different parents.

   **Recommendation: (a).** It needs no dialect knowledge. It answers "taken" only when the replay question
   says so, so a different violation stays a 500, which is what a bug should look like. It reuses the check
   each site already has.
3. **Upload-complete: spend the upload sessions first (CAS), or use D1 as well?** Upload-complete is not a pure
   create. It spends single-use upload sessions, which is exactly
   [`cr-one-time-secrets-spend-by-compare-and-set`](cr-one-time-secrets-spend-by-compare-and-set.md)'s rule.
   **Recommendation: the CAS (D2).** `attach` deletes its sessions first and branches on the count, so a loser
   writes nothing and throws nothing. That also takes the 500 out of a multi-item batch, where today the loser
   hides the items that had already committed.

## Goal

Every create of a row that must exist at most once already has its integrity: a unique index or a primary key
lets exactly one writer through. The loser, though, dies at commit with a constraint violation, and that
reaches the client as **500**. The contract already names the answer a replay of the same request gets: `409
setup_already_complete`, `409 id_taken`, the per-item `upload_not_found` / `id_taken`. A client that
double-submits, or retries after a dropped response while the first request is still running, should get that
answer, not a server error it cannot tell apart from a crash.

This is the **insert half** of `cr-one-time-secrets-spend-by-compare-and-set`'s rule. There, a live row is
spent with a compare-and-set and the loser branches on the row count. Here, there is no row yet. The unique
index is the compare-and-set, and the loser branches on whether the replay question now says "taken".

## Inventory

Substrate: worktree of `main` @ `7a34c57`, 2026-10-01, H2 test datasource (`MODE=MariaDB`, READ COMMITTED).
**Run** means a throwaway race probe: two identical requests behind a `CyclicBarrier(2)`, 5 rounds, a fresh
target each round. The probe is not in the tree, and each DoD test recreates it. **Read** means established by
reading the code only.

| # | Site | Shape today | What the loser gets | How measured |
|---|---|---|---|---|
| 1 | First-run setup: `SetupHandler.createOwner` → `SetupStore.persistOwner` | Unlocked `isSetupComplete()` (`ServerSetup.count() > 0`), then account birth and the `ServerSetup` singleton insert | **Run:** 3× `[201, 500]`, 2× `[201, 409 setup_already_complete]`. One setup row every round. The 500 is `PRIMARY KEY ON VEYQ_SERVER_SETUP(ID)` | `grep -n 'isSetupComplete()' backend/src/main/kotlin/com/veyq/app/features/admin/setup/SetupHandler.kt` |
| 2 | Share create: `SharingStore.create` | `findById(id) != null → ID_TAKEN`, then insert under the client-minted id (ADR 0072) | **Run**, same body twice: 3× `[201, 500]`, 2× `[201, 409 id_taken]`. The 500 is the `VEYQ_SHARE(ID)` primary key. The UPLOADER drop-key write in the same method is already a guarded update | `grep -n 'ShareEntity.findById(id) != null' backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt` |
| 3 | Upload complete: `MediaHandler.completeOne` → `MediaStore.attach` | Unlocked `findSession` and `mediaIdTaken`, then `attach` inserts the blob rows and the media, and deletes the sessions **last** | **Run**, same body twice: 3× `[200, 500]`, 2× `[200, 200 upload_not_found]`. The 500 is the `VEYQ_BLOB(ID)` primary key. **Read:** each `attach` is its own transaction and the handler has none, so in a multi-item batch the items before the loser stay committed while the whole response is a 500. A retry then reports those items `upload_not_found` | `grep -n 'UploadSessionEntity.deleteById' backend/src/main/kotlin/com/veyq/app/features/media/MediaStore.kt` |
| 4 | Share identity create (guest): `SharingStore.createIdentity` | `findById(id) != null → null` (→ `id_taken`), then insert | **Read:** primary-key loser → 500 | `grep -n 'ShareIdentityEntity.findById(id) != null' backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt` |
| 5 | Personal scope create: `ScopesStore.createPersonal` | `findById(scopeId) != null → false` (→ `id_taken`), then insert | **Read:** primary-key loser → 500 | `grep -n 'the client-minted id is taken' backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopesStore.kt` (two hits: 5 and 6) |
| 6 | Group create: `ScopesStore.createGroup` | Same as 5 | **Read:** same | same grep |
| 7 | Node create: `NodesHandler` create → `NodesStore.create` | `slugTaken` (→ `slug_taken`), then the store's id check (→ `id_taken`), then insert | **Read:** a loser on the id gets 500 from the primary key. Two creates of different ids with one slug under one parent: the loser gets 500 from `ux_node_parent_slug`, instead of `slug_taken`. So this site re-asks both questions, the slug first | `grep -n 'slugTaken(slot, slugToken)\|CreateNodeOutcome.IdTaken' backend/src/main/kotlin/com/veyq/app/features/content/NodesHandler.kt`; `grep -n 'ux_node_parent_slug' backend/src/main/resources/db/migration/V10__nodes.sql` |
| 8 | Sub-bucket create: `MediaStore.createSubBucket` | `findById(bucketId) != null → null` (→ `id_taken`), then insert | **Read:** primary-key loser → 500 | `grep -n 'taken anywhere' backend/src/main/kotlin/com/veyq/app/features/media/MediaStore.kt` |
| 9 | Blob-upload start: the per-item `blobIdTaken` check | Unlocked check over blobs and open sessions, then the session insert. `ux_upload_session_blob_id` is unique | **Read:** two starts naming one blob id → the loser dies on `ux_upload_session_blob_id` → 500 for the batch | `grep -n 'store.blobIdTaken' backend/src/main/kotlin/com/veyq/app/features/media/MediaHandler.kt`; `grep -n 'ux_upload_session_blob_id' backend/src/main/resources/db/migration/V53__upload_session_binding.sql` |
| 10 | Username: tenant accept and the profile edit | `usernameTaken` read, then the profile insert or update. `ux_tp_tenant_username` is unique | **Read:** two people claiming one handle at once → the loser gets 500 instead of `username_taken` | `grep -n 'usernameTaken' backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantAcceptHandler.kt backend/src/main/kotlin/com/veyq/app/features/account/profile/AccountProfileHandler.kt`; `grep -n 'ux_tp_tenant_username' backend/src/main/resources/db/migration/V23__member_identity.sql` |
| 11 | Tenant accept for a new account, the user-id and email check | `VeyqUser.findById(userId) != null → IdTaken` | Covered for one invite: `cr-one-time-secrets-spend-by-compare-and-set` Inventory 2 spends the invite first, so the second accept of it loses at the CAS before it reaches the insert. Two **different** invites redeemed under one client-minted user id is one client racing itself, and stays out. **Not a site here.** It is listed so nobody wraps it twice | `grep -n 'RegisterAcceptOutcome.IdTaken' backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantAcceptHandler.kt` |
| 12 | Every loser answer already exists on the wire | `setup_already_complete` (409), `id_taken` (409, `Failures.idTaken`), `username_taken` (409, `Failures.usernameTaken`), and the per-item `upload_not_found` / `id_taken` of the two batch endpoints | No contracts phase, no web phase | `grep -n 'idTaken' backend/src/main/kotlin/com/veyq/app/shared/http/Failures.kt` |

## Decisions

- **D1: an insert's loser re-asks the replay question after its failed commit.** A small helper in a
  new `shared/persistence/` package (the name is settled in the phase) runs the create's transaction. If the commit fails with a
  `PersistenceException` anywhere in the cause chain, it runs the site's existing "taken?" read in a fresh
  read. If that answers "taken", the helper returns the site's named conflict. If not, it re-throws, and the
  500 stays the answer for a failure that is not this race.
  - The loser only fails **after** the winner has committed. H2 and InnoDB both make a duplicate-key insert
    wait for the uncommitted row and fail only once it commits. So the re-ask always sees the winner. If the
    winner rolls back instead, the loser's insert succeeds and there is nothing to re-ask.
  - The helper owns the transaction, so it sits where the transaction starts today. That is the handler or the
    store's `@Transactional` boundary, never inside an open transaction: a failed flush marks a JTA transaction
    rollback-only, so nothing can recover inside it.
  - The pre-check stays as the fast path, and as what decides between the conflict and other answers. It no
    longer has to be right under concurrency.
- **D2: upload-complete spends its sessions first** (Open question 3). `MediaStore.attach` deletes the original
  and derivative sessions as its first statements (`UploadSessionEntity.delete("id = ?1", …)`), and continues
  only if every delete hit one row. Otherwise it rolls back and the item answers `upload_not_found`, which is
  what a replay answers today. The blob and media inserts follow, as now. This is
  `cr-one-time-secrets-spend-by-compare-and-set`'s D1 applied to an upload session.
- **D3: a failed item never fails the batch.** `completeForNode` already returns one result per item. With D2,
  the race loser is an item result, not an exception, so the batch keeps its earlier items' answers. The
  blob-upload start (Inventory 9) uses D1 per item for the same reason.
- **No new error codes, no migration, no contract change** (Inventory 12).

## Phases

- [ ] 1 backend, the helper and first-run setup (D1). `SetupHandler.createOwner` runs `persistOwner` through
  the helper, with `isSetupComplete()` as its re-ask. Tests: `SetupServerSliceTest` (its existing ordering
  means the race test resets the setup row itself and puts it back).
  · Done when: DoD #1 and the helper's own tests are green, and `./gradlew check` is green in `backend/`.
- [ ] 2 backend, media (D2, D3, D1 for Inventory 8–9). `attach` spends its sessions first. Sub-bucket create
  and blob-upload start go through the helper.
  · Done when: DoD #3, #8 and #9 are green, and `./gradlew check` is green.
- [ ] 3 backend, sharing (D1). Share create and share-identity create.
  · Done when: DoD #2 and #4 are green, and `./gradlew check` is green.
- [ ] 4 backend, spaces and content (D1). Personal and group scope create, node create (id and slug), and
  usernames.
  · Done when: DoD #5, #6, #7 and #10 are green, and `./gradlew check` is green.
- [ ] 5 docs. The styleguide bullet that `cr-one-time-secrets-spend-by-compare-and-set` Phase 4 adds gains its
  insert half: *a create's loser re-asks the replay question after its failed commit, through the shared
  helper*. If that bullet is not in yet, this phase writes the whole bullet and the other CR's Phase 4 extends
  it.
  · Done when: the trust-chain glob is green.

Phases 2–4 depend on Phase 1's helper, and not on each other.

## Definition of done

- **Red first, one race test per site, written before its phase.** Each uses
  `LoginSliceTest.concurrentVerifyConsumesTheChallengeOnce`'s `CyclicBarrier(2)` + `invokeAll` shape and runs
  **5 rounds** with a fresh target. It asserts exactly one success and one named conflict, and **no 500**, and
  it records its failing assertion before the fix. The probe made the 500 appear in 3 of 5 rounds at each of
  sites 1–3, so 5 rounds red is likely but not certain. If a site's test is green before its fix, the test runs
  10 rounds and the phase says so.
  - **#1** today `[201, 500]`. **#2** today `[201, 500]`. **#3** today `[200, 500]`. For #3 there is also a
    two-item batch whose second item loses the race: the first item's result must come back, not a 500.
  - **#4–#10**: the same property, red first, each against its own named answer.
- **Bite, per lever:**
  - make the helper re-throw unconditionally: #1, #2 and #4–#10 go red with a 500;
  - make the re-ask always answer "taken": the helper's own test goes red. That test forces a commit failure
    from a **different** unique index (for setup, an e-mail collision on a second, non-racing call) and
    asserts it stays a 500;
  - move D2's session delete back after the inserts: #3 goes red with a 500.
- **Controls.** Each race test ends with one fresh create that succeeds, so a site that refuses everything
  cannot pass.
- `./gradlew check` is green in `backend/`, and the trust-chain glob is green. Inbox #223's status cell points
  here.

## Retrospective
