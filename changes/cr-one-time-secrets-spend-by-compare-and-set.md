---
id: cr-one-time-secrets-spend-by-compare-and-set
title: A one-time secret is spent by a checked compare-and-set, first in its transaction, and nothing writes its status back
status: planned
satisfies: [uc.admin.create-tenant, uc.spaces.add-member, uc.spaces.manage-member-invites, uc.account.recover-access, uc.account.onboard-device]
adrs: ["0054", "0059", "0020"]
invariants: [operator-blind E2EE everywhere]
---

## Open for the owner

**Answered by the owner, 2026-10-01: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists in `docs/inbox.md`.

1. **One CR or three?** The three sites (tenant invites, recovery token, pairing delegation) share no code, only
   the rule. **Recommendation: one CR, one phase per site.** Each phase is green on its own, so they can land in
   any order, but the rule lands once in the docs phase. Splitting would produce three CRs that each state the
   same rule and a fourth place to reconcile them.
2. **Does the tenant-invite phase also cover the rotation interplay (D3, D4)?** Inbox #199 asked only about
   two concurrent accepts. Measuring it turned up two more writers that race the same invite row: a tenant
   rotation's full-row flush writes a stale `status` back (measured: a revoked invite comes back `ACTIVE`),
   and a member accept that overlaps a rotation copies an admission the rotation has already replaced (read,
   not run). **Recommendation: yes, include both.** They touch the same row and the same accept transaction,
   and fixing only the CAS would leave the resurrection path open, so the consume would still not be final.
3. **Structural lever for the full-row flush: fix the one site, or put `@DynamicUpdate` on the entity?**
   `TenantInviteEntity` has no `@DynamicUpdate`, so any managed-entity write flushes every column, including a
   `status` it only read. **Recommendation: fix the site** (rotation re-tags with a column-scoped, guarded bulk
   update) **and put a KDoc on the entity** that names the trap. `@DynamicUpdate` would only narrow the window:
   a writer that changes `status` itself would still overwrite. The same trap exists on `SessionFamilyEntity`,
   and a sweep over every entity that is both bulk-updated on `status` and mutated as a managed entity is a
   separate question (proposed as its own inbox row, not built here).
4. **A guard?** A lint that flags `status = ?1 where id = ?2` without a status predicate. **Recommendation: no
   guard.** It would be a text match over Panache query strings, and the red-first race tests below are the
   thing that actually bites. The rule goes into the styleguide, where the next single-use flow is designed.

## Goal

The docs call three server-held secrets **single-use**: the tenant invite (owner and member), the account
recovery token, and the device-pairing channel's delegation. The code spends none of them atomically. Each
decides "still live?" with an unlocked read, then writes the spent state with an unconditional `UPDATE … where
id = ?` whose row count nobody reads. Two concurrent redeems therefore both pass the check.

What that yields differs per site, and measurement (below) shows it is worse than inbox #199 expected in two
places and milder in one:

- **Recovery:** both resets answer `Applied`, and the later commit's password wins. The person who saw
  "done" is locked out of the new password they just chose.
- **Pairing delegation:** both answer `200` with **different** refresh tokens. The second bind overwrites the
  channel's `delegatedFamilyId`, so the first family becomes a live 30-day refresh token that no sweep or
  supersede can find. `PairingStore.bindDelegation`'s own KDoc says this must be impossible.
- **Tenant invites:** the integrity holds, but only by accident. A unique constraint (the scope PK, or the
  user-email unique) kills the loser, which then answers **500** instead of the replay's `410`. What the
  constraint does not catch is the invite being written by **another writer**: a revoke that lost to a
  concurrent accept is overwritten to `CONSUMED`, and a tenant rotation's full-row flush writes a revoked or
  consumed invite back to `ACTIVE`.

The fix is one rule, applied at each site: **spend the secret with a compare-and-set on its live state, as the
first write of the transaction, and branch on the row count.** The loser then gets exactly the answer a replay
gets, before it has written anything that a constraint would have to undo. This is already the shape of
`PairingStore.markCollected`, `TasksStore.claim` and `TenantStore.suspend`, so no new mechanism is needed.

## Inventory

Substrate: worktree of `main` @ `64c1f65`, 2026-09-28. **Run** means a race test executed on the H2 test
datasource (`MODE=MariaDB`, READ COMMITTED). The probes were throwaway and are not in the tree; each DoD test
below recreates one. **Read** means established by reading the code only.

| # | Site | Consume shape today | What a double redeem yields | How measured |
|---|---|---|---|---|
| 1 | Tenant OWNER accept, existing account: `TenantAcceptHandler.accept` → `TenantStore.activate` | Unlocked `findInviteByTokenHash` → `isLive` → inserts (scope, membership) → `update("status = ?1 where id = ?2", CONSUMED)`, count ignored | **Run, 5 rounds:** 4× `[200, 500]`, 1× `[200, 409 tenant_not_pending]`. `owners = 1` every round. The 500 is `PRIMARY KEY ON VEYQ_SCOPE(ID)` | `grep -n 'CONSUMED, inviteId\|CONSUMED, invite.id' backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantStore.kt`; the probe raced two `POST /api/tenants/accept` behind a `CyclicBarrier(2)` |
| 2 | Tenant accept for a new account: `registerAndAccept` | Same, with `createAccount` before the consume | **Run, 5 rounds:** 5× `[201, 500]`. The 500 is `UX_VEYQ_USER_EMAIL` | same grep; two `POST /api/tenants/accept/register` behind the barrier |
| 3 | Tenant MEMBER accept: `accept` / `registerAndAccept` → `joinAsMember`, and the already-a-member branch → `consumeInvite` | Same unconditional update. The join is backstopped by `ux_membership_user_scope` and `ux_tp_user_tenant`, the already-member branch by nothing (it succeeds twice, harmlessly) | **Read:** loser dies on the membership or profile unique → 500 | `grep -n 'ux_membership_user_scope' backend/src/main/resources/db/migration/V21__membership_grant_nullable.sql`; `grep -n 'ux_tp_user_tenant' backend/src/main/resources/db/migration/V23__member_identity.sql` |
| 4 | Revoke vs. consume, either order: `TenantStore.revokeInvite` and the three consume sites | Both unconditional. `revokeMemberInvite` reads `findPendingMemberInvite` unlocked, then writes | **Run** (deterministic, store level): `revokeInvite` then `consumeInvite` → status `CONSUMED`. An accept that read `ACTIVE` before a revoke committed joins anyway, and the owner was told "revoked". In the other order a revoke overwrites `CONSUMED` with `SUPERSEDED` while the membership stays | `grep -n 'SUPERSEDED, inviteId' backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantStore.kt` |
| 5 | Tenant rotation re-tags open invites: `ScopesStore` `invites.forEach { … inv.persist() }` | The invite rows are read as managed entities under the **scope** lock, which neither accept nor revoke takes. No `@DynamicUpdate` exists anywhere, so the flush writes every column, `status` included | **Run** (two transactions, latched): read invite → a revoke commits → re-tag and commit → status **`ACTIVE`**, tag `retagged`. The revoked secret is live again. The same applies to a consume that lands in the window | `grep -n 'inv.persist()' backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopesStore.kt`; `grep -rn 'DynamicUpdate' backend/src/main/kotlin` → nothing |
| 6 | MEMBER accept overlapping a tenant rotation | Accept copies `invite.admission()` from its unlocked read. The rotation reads memberships and invites under `ScopeEntity` `PESSIMISTIC_WRITE`. `mintMemberInvite` takes the same lock, but accept does not | **Read, not run:** if the rotation commits between accept's read and accept's insert, the new membership carries an epoch-`E` admission the rotation never re-tagged. Its grant-delivery then declines as `admission_unverified`, and the member waits for access that never comes | `grep -n 'PESSIMISTIC_WRITE' backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantStore.kt backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopesStore.kt` |
| 7 | Recovery complete: `RecoveryHandler.complete` → `RecoveryStore.applyReset` | Liveness is read in its own transaction (`findByTokenHash` is `@Transactional`). The reset is a separate `requiringNew`, ending in `update("status = ?1 where id = ?2", CONSUMED, tokenId)`, count ignored. `ix_account_recovery_token_hash` is **not** unique | **Run, 5 rounds, two different new passwords:** 5× `[Applied, Applied]`. The surviving floor and auth key were one racer's in 4 rounds and the other's in 1: whichever committed last | `grep -n 'CONSUMED, tokenId' backend/src/main/kotlin/com/veyq/app/features/account/recovery/RecoveryStore.kt` |
| 8 | Pairing delegate: `PairingHandler.delegate` → `sessions.issueDelegated` → `PairingStore.bindDelegation` | Unlocked `liveChannel` read, check `userId == null`, mint a family, then `update("userId = ?1, delegatedFamilyId = ?2 where id = ?3")`, count ignored | **Run, 5 rounds:** 5× `[200, 200]` with two different refresh tokens. The account's ACTIVE families rose by one orphan per round (3, 4, 5, 6, 7) | `grep -n 'userId = ?1, delegatedFamilyId = ?2 where id = ?3' backend/src/main/kotlin/com/veyq/app/features/account/pairing/PairingStore.kt` |
| 9 | **The lever works on the test datasource** | A guarded `update … where id = ? and status = ACTIVE` in two concurrent transactions, each holding its lock 300 ms | **Run, 5 rounds:** `[1, 0]` or `[0, 1]` every round. H2 in `MODE=MariaDB` blocks the second UPDATE on the row lock and re-evaluates its `WHERE` after the first commits, so every red-first test below can go green on H2 | the probe ran `TenantInviteEntity.update(…)` in two `requiringNew` blocks behind a barrier |
| 10 | Every loser answer already exists on the wire | `/api/tenants/accept` and `/accept/register` declare `410` (`invite_no_longer_valid`). The member-invite `DELETE` declares `404`. `/api/recovery/complete` declares `404`. `/api/pairing/delegate` declares `404` | No contracts phase and no web phase. The web surfaces the 410 through `mapError` (`TenantJoinView.vue`) | `grep -n '^  /api/tenants/accept\|^  /api/pairing/delegate\|^  /api/recovery/complete' contracts/api/*.yaml`, then read each path's responses |

**Already sound, and the template to copy** (read; the first two are also covered by existing race tests):
- `PairingStore.markCollected`, a checked CAS (`PairingSliceTest.concurrentCollectsHandOverTheGrantOnce`).
- `LoginHandler.verify`, a row lock (`LoginSliceTest.concurrentVerifyConsumesTheChallengeOnce`).
- `TasksStore` claim, a checked CAS (`docs/crosscutting/background-tasks.md` § claim).
- `TenantStore.suspend`, `reactivate` and `changeDomain`, guarded updates with a KDoc that states the rule.

**Out of this CR**, measured by reading and proposed as separate inbox rows (see the refine report):
- the session refresh chain can fork under an orphan-and-current race, and a refresh can write a revoked family back to `ACTIVE` (the same full-row flush as #5, on `SessionFamilyEntity`);
- `TasksStore` completes a job with an unguarded `DONE`;
- the setup, share-create and upload-complete losers answer 500 from a PK instead of their named conflict.

## Decisions

- **D1: the consume is a checked CAS, and it is the first write.** Each site replaces its unconditional
  update with a store method that returns `Boolean` (`update(…) == 1`):
  - tenant invites: `… where id = ? and status = ACTIVE and expiresAt > now`;
  - recovery: `… where id = ? and status = PENDING and expiresAt > now`;
  - pairing: `userId = ? where id = ? and status = ANSWERED and userId is null`.

  The handler calls it **before** any insert, account birth, family mint or wrap rewrite. On `false` it returns
  the site's existing replay outcome (`NoLongerValid`, `TokenNotLive`, the pairing `null` → 404). The earlier
  read stays, because it still decides 404 against 410 and supplies the tenant id. It no longer authorizes
  anything. The KDoc says so, in the words `TenantStore.suspend` already uses.
- **D2: revoke is a CAS too.** `revokeInvite` becomes `… where id = ? and status = ACTIVE`, returning `Boolean`.
  `revokeMemberInvite` answers `InviteNotFound` (404, already the answer for a consumed invite) when it lost.
  `revokeUndelivered` (the mail-failure path) ignores the result, because that invite was never handed out.
- **D3: the rotation re-tags invites without writing `status`.** In `ScopesStore`, `inv.persist()` becomes a
  bulk update of `admissionId`, `admissionEpoch` and `admissionTag` with `and status = ACTIVE`. An invite that
  was spent or revoked mid-rotation stays spent. It had nothing left to carry, and the completeness check
  already ran on the list the rotator was shown. `TenantInviteEntity` gets a KDoc naming the full-row-flush
  trap (Open question 3).
- **D4: a MEMBER accept takes the tenant scope lock.** It takes `ScopeEntity.findById(tenantId,
  PESSIMISTIC_WRITE)`, the lock `mintMemberInvite` and the epoch commit already take, before its CAS. It then
  copies the admission from the invite **as re-read after the lock**. An OWNER accept has no scope yet and
  needs none. The CAS on the invite row serializes it.
- **D5: pairing claims before it mints.** `delegate` first CAS-claims the channel for the account, then calls
  `issueDelegated`, then records `delegatedFamilyId` (a plain update on a row this transaction now owns). A lost
  claim mints nothing, so correctness never rests on a rollback of an already-minted family.
- **No new error codes, no migration, no contract change** (Inventory 10). Every loser answers exactly what a
  replay of the same secret answers today.

## Phases

- [ ] 1 backend, tenant invites (D1, D2, D3, D4).
  - `TenantStore`: `consumeLiveInvite(id): Boolean`, called first by `TenantAcceptHandler.accept` and
    `registerAndAccept` on every branch (the already-a-member branch included). `activate` and `joinAsMember`
    stop consuming. `revokeInvite` returns `Boolean`.
  - `ScopesStore`: the guarded bulk re-tag replaces `inv.persist()`.
  - The MEMBER branches take the scope lock.
  - Tests go in `TenantAcceptSliceTest` and `MemberInviteManagementSliceTest`.

  · Done when: the DoD tests for #1–#5 are green and `./gradlew check` is green in `backend/`.
- [ ] 2 backend, recovery (D1). `RecoveryStore.applyReset` CAS-spends the token as its first statement and
  returns `false` before touching the floor. `RecoveryHandler.complete` already maps `false` to `TokenNotLive`.
  · Done when: the DoD test for #7 and `./gradlew check` are green.
- [ ] 3 backend, pairing (D1, D5). `PairingStore.claimDelegation(id, userId): Boolean`.
  `bindDelegation` narrows to setting the family id.
  · Done when: the DoD test for #8 and `./gradlew check` are green.
- [ ] 4 docs.
  - `docs/crosscutting/developer-styleguide.md` §12 gains one pattern bullet: **a single-use transition is a
    checked compare-and-set, first in its transaction**. The bullet also says that a managed-entity write
    flushes every column, so a row other writers transition by status is re-tagged by a bulk update. It
    points at `PairingStore.markCollected` and `TenantStore.consumeLiveInvite` as the two examples.
  - No use-case or ADR changes. Each already says "single-use", and the code now matches them.

  · Done when: the trust-chain glob is green.

## Definition of done

- **Red first, one race test per site, written before its phase.** Each test uses the existing
  `CyclicBarrier(2)` + `invokeAll` shape (`LoginSliceTest.concurrentVerifyConsumesTheChallengeOnce`), runs its
  race **5 times** with a fresh secret each round (one round passed by luck in the probes), and records its
  failing assertion before the fix:
  - **#1 and #2, tenant accept.** Two accepts of one invite give exactly one success and one `410
    invite_no_longer_valid`, and **no 500**. Today: `[200, 500]` / `[201, 500]`.
  - **#3, member accept.** The same property, for the MEMBER role and the existing-account path.
  - **#7, recovery.** Two completes with different new passwords give exactly one `Applied` and one
    `TokenNotLive`, and the surviving floor is the `Applied` one's. Today: `[Applied, Applied]`.
  - **#8, pairing.** Two delegates on one channel give exactly one `200` and one `404 invalid`, and the
    account's ACTIVE family count grows by exactly one. Today: `[200, 200]` and one orphan per round.
- **Red first, deterministic, store level.**
  - **#4.** `revokeInvite` then `consumeLiveInvite` returns `false` and leaves `SUPERSEDED`. `consumeLiveInvite`
    then `revokeInvite` returns `false` and leaves `CONSUMED`. Today the second write always wins.
  - **#5.** Two latched transactions. One reads an open invite through the rotation's re-tag path, a revoke
    commits, then the re-tag commits. The status stays `SUPERSEDED` and the tag is untouched. Today it comes
    back `ACTIVE`. The test drives the store method the rotation calls, not a copy of its body. If that
    method is private, the phase extracts the re-tag into a named store method first.
- **Bite, per lever.** Each lever is reverted alone, with the named test going red and its control staying green:
  - drop the `and status = …` predicate of one CAS: that site's race test goes red;
  - ignore the returned count: that site's race test goes red with a 500 (tenant) or a double success
    (recovery, pairing);
  - move the CAS after the inserts: the tenant tests go red with a 500;
  - restore `inv.persist()`: #5 goes red;
  - drop the scope lock in D4: nothing is expected to go red (#6 was read, not run), and the phase says so
    rather than claiming a bite it does not have;
  - swap D5's order back to mint-then-bind: the pairing test still goes green, because the CAS alone stops the
    double 200. The family-count assertion is what pins D5, and it goes red.
- **Controls.** Each race test ends with a fresh secret redeemed once, successfully, so a site that refuses
  everything cannot pass.
- `./gradlew check` is green in `backend/`, and the trust-chain glob is green. Inbox #199's status cell points
  here.

## Retrospective
