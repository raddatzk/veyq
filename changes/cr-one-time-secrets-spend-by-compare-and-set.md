---
id: cr-one-time-secrets-spend-by-compare-and-set
title: A one-time secret is spent by a checked compare-and-set, first in its transaction, and nothing writes its status back
status: planned
satisfies: [uc.admin.create-tenant, uc.spaces.add-member, uc.spaces.manage-member-invites, uc.account.recover-access, uc.account.onboard-device, uc.account.login, uc.account.sessions]
adrs: ["0054", "0059", "0020", "0004"]
invariants: [operator-blind E2EE everywhere]
---

## Open for the owner

**Fourth round answered by the owner, 2026-10-01: every recommendation added since the third answer is taken too** (incl. the granter-lock questions Q1–Q3 and the rotation CR's Q8/Q9).

**Third round answered by the owner, 2026-10-01: every recommendation added since the second answer is taken too** (questions added by the third refine wave and by implementation hand-backs). Rows those questions proposed exist in `docs/inbox.md` (#233–#243).

**Open, added 2026-10-01 by the third refine wave (inbox #222, #223, #224).** Phases 1–4 keep their go. Only
Phase 5 waits on questions 5 and 6.

5. **Fold #222 (the refresh chain) into this CR as Phase 5?** The refresh token is the fourth single-use secret:
   ADR 0004 spends each one exactly once, by rotation. Measured (Inventory 11–14), the chain forks when an
   orphaned and the current token are presented together, and a refresh racing a revoke writes the family back
   to `ACTIVE`. It is the same rule (the decision is made under the serializing write, first in the
   transaction), and the resurrection is the same full-row flush as Inventory 5, on the entity Question 3
   already named. **Recommendation: fold, as its own phase.** It shares no code with Phases 1–3 and can land in
   any order. Phase 4's styleguide bullet then has a lock-first example next to the CAS examples, and ADR 0004
   gets one paragraph.
6. **Phase 5's lever: lock the family row first, or compare-and-set the token rows?** A CAS on the `CURRENT` row
   (`status = SUPERSEDED where id = ? and status = CURRENT`, branching on the count) stops the fork. It leaves
   the family's `status` written by an unlocked managed flush, though, so the revoke race needs a second lever (a
   column-scoped `lastUsedAt` update). **Recommendation: one lever, the family row lock (D6).** Every rotation
   and every revoke of a family already write that row, so locking it first serializes the whole chain on one
   row. ADR 0004's distance is then decided against committed state by construction. Measured on H2: the lock
   alone closes both (Inventory 14).
7. **Where #223 and #224 go: two sibling CRs, not this one.**
   - **#223 → [`cr-create-race-losers-answer-their-conflict`](cr-create-race-losers-answer-their-conflict.md).**
     Its sites create a row that does not exist yet, so there is no live row to compare-and-set. Their arbiter
     is the unique index, and the fix is the insert half of this CR's rule: the loser re-asks the replay question
     after its failed commit. Its inventory is also eleven sites wide, not three.
   - **#224 → [`cr-managed-writes-flush-only-what-they-change`](cr-managed-writes-flush-only-what-they-change.md).**
     The sweep found the full-row flush on eight entities, four of them measured. The lever is
     `@DynamicUpdate` on every entity, which is a different kind of change from a per-site CAS.

   **Recommendation: yes, siblings.** Folding them would turn a four-site CR into one that touches every entity
   and every client-minted-id create. Both siblings cite this CR's rule rather than restating it.

   **This corrects Question 3's reasoning, but not its decision.** Question 3 said `@DynamicUpdate` "would only
   narrow the window". Measured on 2026-10-01, it does more: it closes the write-back of every column a writer
   did not change (four entities, 5/5 each, `cr-managed-writes-flush-only-what-they-change` Inventory). What it
   does not close is a race between two writers of the **same** column, and D1/D2 are about exactly that. D3
   stays as decided. With the sibling landed it becomes belt and braces, and its `and status = ACTIVE` still
   keeps a spent invite from being re-tagged.

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
recovery token, and the device-pairing channel's delegation. A fourth one, the rotating refresh token (ADR 0004),
was added on 2026-10-01 (Open question 5). The code spends none of the first three atomically. Each
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
- **Refresh tokens** (Phase 5): the rotation serializes only presentations of the **same** token. An orphaned
  token and the current one, presented together, both answer `200` and leave the family with two live chains.
  ADR 0004's distance-zero detector then never fires for either holder. A refresh racing a revoke writes the
  family back to `ACTIVE` after the revoke answered `204`.

The fix is one rule, applied at each site: **spend the secret with a compare-and-set on its live state, as the
first write of the transaction, and branch on the row count.** The loser then gets exactly the answer a replay
gets, before it has written anything that a constraint would have to undo. This is already the shape of
`PairingStore.markCollected`, `TasksStore.claim` and `TenantStore.suspend`, so no new mechanism is needed.
Where the decision reads more than one row, as the refresh distance reads the presented and the `CURRENT`
token, the serializing write is a row lock on the parent, taken first. That is `LoginHandler.verify`'s shape,
and D4's and D6's.

## Inventory

Substrate: worktree of `main` @ `64c1f65`, 2026-09-28 (rows 1–10), and `main` @ `7a34c57`, 2026-10-01 (rows
11–14). **Run** means a race test executed on the H2 test
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
| 11 | Refresh with an orphaned and the current token at once: `SessionService.rotate` | `rotate` locks only the presented token's row. It reads the family and the `CURRENT` row unlocked. The orphan (distance 1, the grace path) and the current token lock **different** rows, so nothing orders the two | **Run** (2026-10-01): with a barrier only, 10 rounds, the race forked 3 times: `[200, 200]`, **two `CURRENT` rows**, family `ACTIVE`, and both new tokens rotated again (`[200, 200]`). The other 7 rounds gave one `200`, one `401` and family `REVOKED`, which is the serial outcome. With a test transaction holding the family row 300 ms while both rotations start, 5 rounds forked 3 times. Either serial order revokes (distance 0 or 2), so the fork exists only through the race. It defeats ADR 0004's detector: neither holder ever lands at distance zero, so a stolen orphan presented alongside the victim's refresh is never caught | `grep -n 'PESSIMISTIC_WRITE' backend/src/main/kotlin/com/veyq/app/features/account/login/SessionService.kt` → one hit, the token row |
| 12 | Refresh racing a revoke: `rotate` vs `revokeFamilyForUser`, `revokeOtherFamilies`, `revokeAllForUser` | `rotate` sets `family.lastUsedAt` on a family it read unlocked. The managed flush writes every column, `status` included. This is the Inventory 5 trap on `SessionFamilyEntity` | **Run** (2026-10-01), deterministic, 5 rounds: the real `revokeFamilyForUser` ran in a transaction whose commit the test delayed 300 ms, and a refresh started inside that window. Every round answered `200` with family **`ACTIVE`**, and the new refresh token rotated again (`200`). With a barrier only, 30 rounds, 6 families came back `ACTIVE` after a `204` revoke. **Read:** recovery's `revokeAllForUser` is the same statement. A reset that must end every session can leave the one that was refreshing alive | `grep -n 'family.lastUsedAt = now' backend/src/main/kotlin/com/veyq/app/features/account/login/SessionService.kt` |
| 13 | Reuse-detection revoke racing a rotation of the same family | The revoking `rotate` sets `family.status = REVOKED` through a managed flush. A concurrent rotation of the current token flushes the family too | **Read, not run:** the later commit wins, so a rotation that commits after the clone verdict writes `ACTIVE` back | `grep -n 'family.status = SessionFamilyEntity.REVOKED' backend/src/main/kotlin/com/veyq/app/features/account/login/SessionService.kt` |
| 14 | **The family lock is the lever, on H2** | Throwaway: read the family id with a scalar query, take `SessionFamilyEntity.findById(id, PESSIMISTIC_WRITE)`, then lock the token row as today | **Run** (2026-10-01): #11 forked 0 of 10 rounds (barrier) and 0 of 5 (held lock). In every round one rotation won and the other read its commit and revoked. #12 resurrected 0 of 5 (held revoke) and 0 of 30 (barrier). In the held rounds the refresh waited for the revoke and answered `401`. In the barrier rounds every family ended `REVOKED`, whichever request ran first. `LoginSliceTest` (24 tests, `concurrentRefreshOfOneTokenKeepsTheFamily` included) and `SessionManagerSliceTest` (13) stayed green with the lever in | the probe is not in the tree. The DoD tests for #11 and #12 recreate it |

**Already sound, and the template to copy** (read; the first two are also covered by existing race tests):
- `PairingStore.markCollected`, a checked CAS (`PairingSliceTest.concurrentCollectsHandOverTheGrantOnce`).
- `LoginHandler.verify`, a row lock (`LoginSliceTest.concurrentVerifyConsumesTheChallengeOnce`).
- `TasksStore` claim, a checked CAS (`docs/crosscutting/background-tasks.md` § claim).
- `TenantStore.suspend`, `reactivate` and `changeDomain`, guarded updates with a KDoc that states the rule.

**Out of this CR:**
- `TasksStore` completes a job with an unguarded `DONE` (read, 2026-09-28). It has no inbox row yet; the
  2026-10-01 refine report proposes one.
- The setup, share-create and upload-complete losers answer 500 (inbox #223) →
  [`cr-create-race-losers-answer-their-conflict`](cr-create-race-losers-answer-their-conflict.md) (Open
  question 7).
- Every other entity with the full-row flush of Inventory 5 (inbox #224) →
  [`cr-managed-writes-flush-only-what-they-change`](cr-managed-writes-flush-only-what-they-change.md) (Open
  question 7).

The refresh chain (inbox #222) was on this list until 2026-10-01. It is now Inventory 11–14 and Phase 5.

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
- **D6: a rotation takes its family's row lock first** (Open question 6). `SessionService.rotate` resolves the
  family id with a scalar query (`select r.familyId from RefreshTokenEntity r where r.tokenHash = ?1`), takes
  `SessionFamilyEntity.findById(familyId, PESSIMISTIC_WRITE)`, and only then locks and reads the token rows.
  Every rotation and every revoke of one family write that row, so they all serialize on it. The distance is
  then computed against committed state, and a revoke either commits before the rotation reads the family (the
  rotation answers `401`) or waits for the rotation and lands after it (the family ends `REVOKED`). The lock
  order is family first, token second, on every path. Nothing locks a token row and then its family.
  - **Trap: the family id must not come from loading the token entity before the lock.** A later locked query
    for the same row would hand back the instance already in the persistence context, with the status it had
    before the lock. This is Hibernate's identity map, remembered and not measured. The probe used the scalar
    form, which is the measured one.
  - **`withDeadlockRetry` stays.** Its measured deadlock is two refreshes of one token meeting on
    `ux_refresh_token_hash`. With the family lock first they queue on the family's primary key before either
    touches that index, so the cycle should no longer form. That is reasoned about InnoDB and not measured,
    since there is no MariaDB here. The KDoc gets one sentence that says exactly that, and nothing is removed on
    a reasoned claim.
  - **Coordinate with `cr-session-purge-cost-on-mariadb` (landed 2026-10-01, CR deleted).** It measured the
    purge's lock footprint against a concurrent login or refresh on a throwaway MariaDB and split `SessionPurge.run()`
    into short per-step transactions with primary-key family batches (the why: the comment in `SessionPurge.run()`
    and the V59 header; the harness and numbers: that CR's retrospective, `git log -- changes/cr-session-purge-cost-on-mariadb.md`). This lock is new on the
    refresh side and this CR lands second, so it re-runs that measurement with both in place.
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
    points at `PairingStore.markCollected` and `TenantStore.consumeLiveInvite` as the two examples. When a
    decision reads more than one row, the serializing write is a lock on their parent, taken first.
    `SessionService.rotate` (D6) is that example, once Phase 5 has landed. If Phase 4 lands first, Phase 5
    adds the example itself.
  - ADR 0004 § the one-generation grace gains one paragraph: the distance is decided under the family's row
    lock. Without that lock, two presentations both measure against a stale `CURRENT` row and the chain forks,
    as measured on 2026-10-01 (Inventory 11). The paragraph lands with Phase 5, not before it.
  - No use-case changes. Each already says "single-use", and the code now matches them.

  · Done when: the trust-chain glob is green.
- [ ] 5 backend, sessions (D6). Inbox #222. Independent of Phases 1–3.
  - `SessionService.rotate` takes the family lock first, as D6 says. The grace path's
    `current.status = SUPERSEDED` and the reuse verdict's `family.status = REVOKED` stay managed writes. Under
    the lock no other writer can interleave with them, so their full-row flush is safe.
  - The ADR 0004 paragraph from Phase 4 lands here, if Phase 4 is already in.
  - `SessionRotationRetry.kt`'s KDoc gets D6's sentence about the deadlock it was written for.
  - Tests go in `LoginSliceTest` (the fork, beside the existing grace block) and `SessionManagerSliceTest` (the
    revoke race, which already has the second session that revokes).

  · Done when: the DoD tests for #11 and #12 are green, the existing grace tests in `LoginSliceTest` are green
  unchanged, `./gradlew check` is green in `backend/`, and the trust-chain glob is green.

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
  - **#11, refresh fork.** Set up r1 → r2 (r1 is then one generation behind). A test transaction holds the
    family row with `PESSIMISTIC_WRITE` for 300 ms. Both refreshes, r1 and r2, start inside that window. After
    it closes, the round asserts at most one `200`, at most one `CURRENT` row, and no two new tokens that both
    rotate again. This runs **10 rounds**, not 5, because the held lock made the fork 3 of 5 in the probe, not
    5 of 5. Today some round forks. The barrier-only form measured 3 of 10, which is too weak to be the
    red-first signal on its own.
- **Red first, deterministic, through the real paths.**
  - **#12, refresh vs. revoke.** The real `SessionService.revokeFamilyForUser` runs in a
    `QuarkusTransaction.requiringNew()` whose commit the test delays 300 ms, and the refresh starts inside that
    window. Assert family `REVOKED` and that a token from the refresh, if it got one, answers `401`. 5 rounds.
    Today, 5 of 5 rounds give family `ACTIVE` and a token that rotates. No test seam enters production code:
    the delay lives in the test's own transaction.
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
    double 200. The family-count assertion is what pins D5, and it goes red;
  - drop D6's family lock: #11 and #12 both go red (measured with the probe: 3 of 5 and 5 of 5);
  - take D6's family lock **after** the token-row lock: the orphan and the current token then each hold one
    token row while waiting for the family. The phase records what H2 does with that (a deadlock, a lock
    timeout, or a pass) instead of predicting it;
  - load the token entity before the lock instead of the scalar query: the phase records whether #11 goes
    red. This is D6's remembered trap, and the bite is what turns it into a measured one.
- **The full-row-flush bites depend on what has landed.** If
  [`cr-managed-writes-flush-only-what-they-change`](cr-managed-writes-flush-only-what-they-change.md) is in
  first, `@DynamicUpdate` already stops the write-back, so restoring `inv.persist()` alone does not redden #5.
  The bite then also removes `@DynamicUpdate` from `TenantInviteEntity` for its run, and #12's lock bite is run
  the same way on `SessionFamilyEntity`. The phase records which order it found.
- **Controls.** Each race test ends with a fresh secret redeemed once, successfully, so a site that refuses
  everything cannot pass.
- `./gradlew check` is green in `backend/`, and the trust-chain glob is green. Inbox #199's and #222's status
  cells point here.

## Retrospective
