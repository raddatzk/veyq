---
id: cr-a-gate-holds-until-its-write-commits
title: A check that decides a write still holds when the write commits — on MariaDB too
status: in-progress
satisfies: []
adrs: ["0004", "0020", "0075"]
invariants: [operator-blind E2EE everywhere]
---

## Open for the owner

**Answered by the owner's standing answer (2026-10-03): every recommendation below is taken.** Read each
recommendation as the decision.

1. **#288/#289: fix the one site, or the isolation level?** Production MariaDB runs REPEATABLE READ (measured on
   the pinned `mariadb:11.4`: `@@tx_isolation = REPEATABLE-READ`). The test H2 runs READ COMMITTED. Under RR the
   first plain read of a transaction fixes its snapshot, so every plain read after a later `FOR UPDATE` is stale.
   The inventory found **seven** such sites, not one (C-6, C-8, C-10, C-13, C-15, C-17, C-18 below). Three of them
   are user-visible defects: the concurrent-refresh grace of ADR 0004, the tenant quota gate, and the
   re-activation of #288.
   - (a) Fix each site to "the lock is the first read" (styleguide §12 today). Seven code changes, and the rule
     must be remembered by every future lock, with no test on H2 able to tell.
   - (b) **Run production at READ COMMITTED**, the isolation every slice test already proves (one line,
     `quarkus.datasource.jdbc.transaction-isolation-level`, set by the app per connection, so an operator's own
     MariaDB gets it too).

   **Recommendation: (b).** It makes the suite's race evidence production's evidence, and it closes the class
   instead of its known members. Reads outside a transaction (autocommit) already behaved this way in production,
   so only the inside of a `@Transactional` changes, toward what the tests ran. The costs: a non-repeatable read
   inside one transaction (which is what every test already saw), and no gap locks (nothing here relies on them:
   every range lock in the code is taken under a parent row lock). A statement-format binlog refuses RC writes;
   MariaDB's default is `MIXED` and the bundled one runs no binlog. Recorded as ADR 0078; §12's "the lock is the
   first read" is replaced by it.
2. **The persistence-context half survives (b).** `findById(id, PESSIMISTIC_WRITE)` on an entity this transaction
   already loaded takes the lock and does **not** reload the state, at any isolation. `access.check` loads the
   `ScopeEntity` in every transaction that gates, so `TenantStore.mintMemberInvite` compares a pre-lock
   `currentEpoch` (C-7). **Recommendation:** `ScopeEntity.lockRow` refreshes the managed copy under the lock, and
   every scope-row lock goes through it.
3. **#282: which gate-outside-the-write races to fix here?** The sweep found real (non-serializable) races in four
   unrelated clusters. **Recommendation:** fix the two whose lock already exists — **a removal of a member**
   (scope row lock) and **a purge against a restore** (the purged row) — here. Split two into rows of their own,
   because each needs a lock nobody takes yet and a design decision with it: the **node tree against trash and
   purge** (the scope row as the node tree's serializer; `NodeSubtreePurge` per scope), and the **account
   credential lifecycle** (login, add-wrap and pairing against reset, password change and key rotation: the user
   row first). Also split: **a guest upload against the deletion of its link** (the share row as the guests'
   serializer).
4. **Run the MariaDB proof in CI?** Out of these rows' reach (the e2e/CI batch owns the workflows).
   **Recommendation:** a row. Until then the MariaDB run is a hand-run proof with a committed runner and makes no
   coverage claim (ADR 0040).

## Goal

A permission or state check that decides a write must still be true when that write commits. Two ways it was
not: the check ran in a different transaction than the write, while a concurrent cleanup (a member's removal, a
purge) enumerated the rows it had to clean before the write's row existed; or the check ran under the right lock
but read a snapshot taken before it (MariaDB REPEATABLE READ), or an entity copy loaded before it. After this CR
every such site in the two fixed clusters is serializable — its outcome is one some serial order of the two
writes produces — production runs the isolation the slice tests prove, and a committed runner proves the race
suite on the pinned MariaDB.

## Context & constraints

- [developer-styleguide §12](../docs/crosscutting/developer-styleguide.md#12-architectural-patterns) — the
  compare-and-set, parent-lock and "a check that decides a write is read where the write happens" rules.
- ADR 0004 (the refresh grace), ADR 0020 move 6 (admissions under the tenant scope's lock), ADR 0075 (a removal
  turns the removed member's links off).
- `changes/README.md` § *A race the refine only read gets its symptom and its fix from the red*: every fix below
  is a hypothesis until its red.

## Inventory (measured at refine, `main` @ `1635efd`)

Locators (run them, don't trust the rows):
- Lock sites: `grep -rnE 'PESSIMISTIC_WRITE|lockRow\(|lockScope\(|lockTenant\(|lockTenantScope\(' backend/src/main/kotlin`
- Gates outside the write: `grep -rLn '@Transactional' backend/src/main/kotlin --include='*Handler.kt'` (handlers
  with no transaction of their own), then each one's `access.check`/`activeShare`/`find…` before a store write.
- Cleanup writers (the second angle, keyed on the *other* side of the race): bulk deletes and updates by a set,
  `grep -rnE '\.(delete|update)\("[^"]*(userId|scopeId|ownerAccount|nodeId|deletedAt|lastWindowAt)' backend/src/main/kotlin`,
  plus `grep -rln '@Scheduled' backend/src/main/kotlin`.

A pair is **real** when the gated write reads what the cleanup writes and the cleanup misses the row the gated
write adds — a read-write cycle no serial order produces. **Safe by serial order** otherwise.

### Snapshot after the lock (MariaDB RR) — closed by decision 1

| Site | Read before the lock | Stale decision after it |
|---|---|---|
| C-13 `SharingHandler.reactivate` (#288) | `liveScopeOf` | `access.check` membership, `ownerIsMember` |
| C-17 `SessionService.rotate` | scalar `familyId` | `currentTokenOf` → a concurrent refresh revokes the family |
| C-18 `MediaHandler.reserveAndOpen` → `StorageQuota.lockTenant` | `ScopeEntity.findById` | tenant/link usage sums → over-admits |
| C-10 `TasksStore.originateScopeRotationExcluding` (rotation caller) | the whole rotation | the open-job list → a cut's job not excluded |
| C-6 `TenantAcceptHandler` MEMBER accept | invite, tenant, user | `belongsToTenant`, username/email taken (unique-index backstop) |
| C-8 `TasksStore.complete` | `JobEntity.findById` | `lostCompletion`'s refresh → answer code only |
| C-15 `NodeSubtreePurge` → `tombstoneAllOn` | the expired nodes, media | identity drop counts |

### Entity loaded before its lock — decision 2

| Site | Loaded before the lock | Stale decision |
|---|---|---|
| C-7 `TenantStore.mintMemberInvite` | `access.check` loaded the tenant `ScopeEntity` | `currentEpoch` from that copy → a stale-epoch invite |

### Gate outside the write (#282)

| Gated write | Cleanup it races | Class | Fix |
|---|---|---|---|
| `SharingHandler.create` | member removal (`ScopesStore.rotate`, `TenantHandler.removeMember`) turns the member's links off | **real** | scope lock first in the create's transaction, membership + epoch re-checked under it |
| `SharingHandler.reissue` | same | **real** | same, in `SharingStore.reissue`'s transaction |
| `ItemFavoritesHandler.put` | removal deletes the member's item favorites | **real** | scope lock first, re-check |
| `ScopesHandler.createGroup` / `createPersonal` | tenant removal plans its cascade from the member's memberships | **real** | tenant-root lock first, re-check |
| `ScopesHandler.addGroupMember` | tenant removal of the target (or of the owner) | **real** | tenant root and group locked in id order, every gate re-checked |
| `TenantHandler.reissueMemberInvite` | tenant rotation carries the open invites | **real** | tenant-scope lock before the invite is read |
| `MediaStore.purgeTrashedBefore` vs `restoreMedia` | — | **real** (a restored photo erased) | purge selects with a lock; restore is a guarded update |
| `MediaStore.purgeMedia` vs `restoreMedia` | — | **real** | the purge's read is the lock |
| `MediaStore.purgeSessionsIdleSince` vs `touchWindow` | — | **real** (a live upload's session dropped) | purge selects with a lock |
| `ScopePurge.eraseScope` vs `ScopesHandler.restoreGroup` | — | **real** (a restored group erased) | the erase locks the scope and re-checks; restore is a guarded update |
| `MediaStore.attach` into a bucket vs `deleteBucketReparent` | — | unreachable: no caller passes `targetBucketId` (`grep -rn 'targetBucketId' backend/src/main/kotlin` — only `moveMedia`'s own) | row: delete the parameter or wire it |
| `NodesHandler.create`/`update` (move) vs `deleteSubtree`; `restoreSubtree` vs `NodeSubtreePurge` | — | **real** | **split** → row (decision 3) |
| `LoginHandler.verify`, `KeyMaterialHandler.addWrap`, `PairingHandler.delegate` vs reset / password change / rotation | — | **real** | **split** → row |
| guest `completeUploads`/`createIdentity` vs share delete's identity drop | — | **real** | **split** → row |
| `revokeScopeShares` vs a share create; `NodesHandler.create`, media upload, rename, `TasksHandler.enqueue` vs a removal; any write vs suspend / soft-delete | — | safe by serial order (the cleanup does not read what the write adds, or enumerates nothing) | — |
| writes into a scope vs `ScopePurge` past the grace | — | unreachable (the gate refuses `Deleting`; the grace is days) | — |

## Phases

- [ ] 1 isolation — **red first on MariaDB**: `scripts/run_backend_races_on_mariadb.py` (throwaway container of
  the compose-pinned image, a timeout, never the owner's `veyq-mariadb`) runs the race classes on the untouched
  tree; expected red: `aReactivationByAMemberBeingRemovedLeavesTheLinkOff` (outcome `Done` where `NotFound` is
  asserted) and `concurrentRefreshOfOneTokenKeepsTheFamily` (family revoked). Then the property, a test that pins
  it, ADR 0078, §12 rewritten, the RR comments corrected. · Done when: the MariaDB run is green; **bite**: the
  property removed → the same reds (lever: the one property line, snapshot-and-restore).
- [ ] 2 the lock refreshes — red first on H2: an invite minted while a tenant rotation holds the scope row,
  admission at the old epoch → `StaleEpoch` asserted, `Done` today (live defect, lever: the overlap). Then
  `lockRow` refreshes, every scope-row lock uses it, `reissueMemberInvite` locks first. · Done when: green, and
  re-bitten by reverting `lockRow` to the query.
- [ ] 3 removal — red first on H2, one `whileUncommitted` race per real row of the removal cluster (the removal
  held uncommitted, the gated write overlapping), each asserting the replay's answer and that nothing of the
  removed member is live; each with a control. · Done when: green; each re-bitten by removing its re-check.
- [ ] 4 purge vs restore — red first on H2, same shape (restore / touch held uncommitted, purge overlapping, and
  the reverse where the reverse is the risk). · Done when: green; bites by reverting each lock.
- [ ] 5 close — `./gradlew check`, guard glob, MariaDB run, retro, harvest, rows.

## Definition of done

`./gradlew check` green; `for f in scripts/validate_*.py; do python3 "$f"; done` green; the MariaDB race run green
on the final tree and red with the isolation line removed; every red recorded with the assertion that reddened.
No coverage claim for the MariaDB run (no CI job runs it — row). The permanent guard is the isolation test (a
removed line is red in `./gradlew check`), not a grep.

## Retrospective
