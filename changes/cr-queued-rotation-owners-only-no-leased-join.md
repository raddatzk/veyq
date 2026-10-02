---
id: cr-queued-rotation-owners-only-no-leased-join
title: A queued scope rotation reaches only an owner, and never folds a later event into a job already being worked
status: in-progress
satisfies: [en.spaces.scope-key-rotation, uc.spaces.remove-tenant-member, uc.account.rotate-keys, uc.spaces.complete-queued-rotation]
adrs: ["0024", "0021", "0060", "0025", "0014"]
invariants: [operator-blind E2EE, API-first]
---

## Open for the owner

**Re-read at implementation (2026-10-02) against `cr-grant-verifies-under-its-signing-key`, landed at `a063d59`
(V63).** A grant row now records its granter's identity key as of the write
(`grep -n "granterIdentityPublicKey = granterKey" backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopesStore.kt`),
so a grant the rotator signed before rotating keeps verifying. **The granter lock this CR's § Inventory for #225
measured is gone**, and with it every sentence that leaned on it: `failed` has no deterministic cause any more,
the re-queue (D9, D10) is a real remedy rather than a truthful report, Q7's caveat (a co-owner the rotator granted
fails their claim) no longer holds, and phase 0's *"a new exception names the granter lock as a known cause of
`failed`"* is **not written** — uc.account.rotate-keys E9 (written by that CR) already states what remains. The
inventory rows are kept as the record they were; nothing below is built on them.

**Fourth round answered by the owner, 2026-10-01: every recommendation added since the third answer is taken too** (incl. the granter-lock questions Q1–Q3 and the rotation CR's Q8/Q9).

**Added 2026-10-01 by the #233–#235 refine: Q8 and Q9 are open.** They gate only D10 (#234) and D11 (#235).
Everything decided below, D1–D9 included, keeps its go. Inbox #233, the granter lock, is **not** folded here: it is
a different mechanism (which key a grant is verified under), it shares no decision with this CR, and it is now its
own planned CR, `cr-grant-verifies-under-its-signing-key` (landed, CR deleted). Where
this CR says "the granter lock" or "the proposed row", read that CR (planned).

8. **How do a scope's other owners learn that its compromise rotation needs them (#234)?** D9 tells only the
   rotator, and the rotator is the one person who may not act. **Recommendation: a nullable field on the owner's
   own scope row, `compromiseRotation` (`stalled` | `failed`), plus an owner re-queue from `failed` (D10).** The
   row is `ScopeSummary`, and `MyTenant` for a tenant root: the two reads that already carry the caller's grant.
   - **A field on the reads every client already makes**, not a new endpoint. The sidebar builds its groups from
     `listMyScopes` and its tenant row from `listMyTenants` on every entry, and already marks a scope *awaiting
     access* there (`grep -n -e "listMyGroups()" -e "awaitingAccess" web/src/components/app/AppSidebar.vue`),
     so the state shows wherever the scope does, with no extra call and no polling. The server knows the value
     from rows it holds, and no existing field implies it (changes/README.md § before adding a read field).
   - **Only `stalled` and `failed`.** `waiting` needs nothing from an owner, since their own worker already
     claims it on entry (`grep -n "immediate: true" web/src/features/tasks/scopeRotationPolling.ts`). `secured` needs
     nothing from anyone.
   - **Only on an owner's row.** Owners are the only ones who can act (D1, and the promote remedy of E4). A plain
     member learns nothing they did not know.
   - **The re-queue is D9's, seen from the scope.** It re-originates the scope's rotation excluding every rotator
     whose state is `failed`, through the same `originateScopeRotationExcluding`. The caller is not excluded,
     unless they are one of those rotators, so their own worker can claim it at once.
   - **Cheaper alternative: read-only** (the field without the re-queue). The owner then sees `failed` and can do
     nothing but tell the rotator, who can re-queue (D9). It works, one hop slower.
   - **What it needs from the sibling CR.** Under the granter lock an owner's re-queue dead-letters again for the
     same reason. Once `cr-grant-verifies-under-its-signing-key` lands, `failed` no longer has a deterministic
     cause, and the re-queue is a real remedy. D10 can land before it; it then reports truthfully and its action
     waits on the lock.
9. **Does D8's completion check become a compare-and-set for every tag (#235)?** `complete` reads the job, checks
   the holder, then writes `DONE` with no condition (§ Inventory for the #234/#235 fold). A worker whose lease
   expired and was reclaimed between that read and that write completes anyway, and erases the new holder's lease.
   **Recommendation: yes, here, for every tag (D11).** D8 already rewrites this branch for `scope-rotation`, and its
   check belongs inside the same guarded statement. The registry promises the rule for every tag (*"completed only
   by the worker holding its lease (ADR 0021 §6)"*), and a per-tag fix would leave `grant-delivery` making that
   promise falsely. The harm today is small, a reclaimer's work and result dropped, which is why it is folded and
   not a CR of its own. `cr-one-time-secrets-spend-by-compare-and-set` names it as out of its scope.

**Third round answered by the owner, 2026-10-01: every recommendation added since the second answer is taken too** (questions added by the third refine wave and by implementation hand-backs). Rows those questions proposed exist in `docs/inbox.md` (#233–#243).

**Added 2026-10-01 by folding in inbox #225 (D9), after both rounds of answers: Q6 and Q7 are open.** They gate
only D9. Everything decided below, D1–D8 included, keeps its go.

6. **What can the rotator do about a compromise rotation that dead-lettered?** Today nothing tells them, and
   nothing they can do moves it (§ Inventory for #225). D9 makes the state readable either way. The question is
   the action.
   **Recommendation: a "queue it again" request, offered only from the dead-lettered state.** It re-originates the
   scope's rotation with the rotator excluded, through the same `originateScopeRotationExcluding` the rotation
   itself calls.
   - **It is safe to hand to the rotator.** It creates work only for other owners, and it excludes the caller.
     That is the opposite of re-admitting themselves (ADR 0060 Decision 4).
   - **It re-arms the Q5 bound.** D7 clause 1 counts only a live job. A re-queued job is live again, so from the
     re-queue on the rotator is refused at the commit again. The lapse shrinks to the time between the dead-letter
     and the re-queue.
   - **Automatic re-origination on dead-letter (the alternative)** would close that gap too. It is an unbounded
     loop, though, on the one cause § Inventory for #225 shows to be deterministic (the granter lock). Every
     round would burn five owner attempts until someone fixes the lock.
   - **Inform only (the cheaper alternative)** leaves the user facing a state they are told about and cannot
     change. That is #225 with better copy.
7. **Does D9 tell "waiting" apart from "nobody can do it"?** A live job with no owner left who may claim it is
   just as silent as a dead-lettered one: the rotator is sole owner (uc.account.rotate-keys E4), or every other
   owner has declined the job. **Recommendation: yes, a `stalled` state, derived from rows that already exist.**
   It is the set of owners that hold a grant and hold no decline on the job, tested for emptiness. Without it,
   E4 reads `waiting` forever, which is the silence #225 names, one state earlier. Its action is the E4 remedy:
   promote a co-owner. A co-owner has no decline on the job, so the job is claimable again. The rotator is still
   an owner, so the rotator can do that promotion. There is a caveat: if the rotator signed the new co-owner's
   grant, the granter lock makes their claim fail. The state then moves on to `failed`, and it says so.

**Second round answered by the owner, 2026-10-01: every recommendation added since the first answer is taken too** (questions added by the 2026-09-28 second refine wave). Rows those questions proposed exist as #222–#232.

**Answered by the owner, 2026-09-28: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists (#197–#221, `docs/inbox.md`).

These are your calls. The CR is written for the recommendation in each. No phase starts until they are
answered, and phase 0 records the answers before any code changes.

1. **Who completes a queued rotation (inbox #180).** Either the job is offered to the scope's **owners only**,
   or `rotateScope` is opened to **any key-holder**. **Recommendation: owners only.** Opening the rotation is
   not a gate flip. A non-owner cannot build a complete rotation commit today, because the server requires every
   open admission to be re-tagged or dropped (`409 admissions_incomplete`), and two kinds of admission are
   hidden from non-owners: a pending consent proposal (cr-0094 shows it to owners only) and, on a tenant scope,
   an open member invite (tenant-owner-gated). Opening the rotation would therefore also reverse cr-0094's
   visibility decision and the invite gate, and it would remove one of ADR 0060 Decision 4's two stated reasons.
   Evidence in § Inventory. The price of owners-only: a job waits for an owner to come online instead of being
   attempted by whoever is online. Today it does not complete either, since a non-owner's attempt fails. The
   difference is that today the attempts dead-letter the job.
2. **The shape for #178 (2).** The row sketched *"a rotation commit closes, in its own transaction, the live
   jobs originated before it"*. **Recommendation: do not take that shape. Instead, an event joins only a
   `PENDING` job, and only the lease holder's completion closes a job** (Decisions D3–D5). The sketch is sound for
   a membership cut, because the server drops grants to a membership that is gone. It is **unsound for the
   second originator**: an account rotation's "cut" is an identity change. The server cannot see it in a
   commit's grants, and a commit whose client read the directory before the new identity was published seals
   the new epoch to the **old, compromised** identity. Closing the job on that commit would leave the old
   identity holding the current epoch with nothing queued. § Options (b) spells out the trace.
3. **Close the enqueue side door as well (D6).** Any key-holder can `POST /api/tasks/enqueue` a
   `scope-rotation` job today (only `grant-delivery` is refused), and that bypasses every origination rule this
   CR adds. **Recommendation: yes, in this CR.** It is one refusal, the tag registry already says the tag's
   originator is `server-event`, and the refused set can be derived from the registry instead of listed. If you
   say no, it becomes a proposed inbox row.
4. **Where the new acceptance criteria live.** The property *"a queued rotation is completed by an owner, from
   inputs read after the event"* has two triggers: a tenant-member removal and an account rotation. The coverage
   already shows the symptom that `changes/README.md` § coverage names: the same three `MemberRemoveSliceTest`
   refs are listed under two criteria of two use-cases (`coshared-group-rotation` and
   `queued-rotation-backstop`). **Recommendation: a use-case of its own, `uc.spaces.complete-queued-rotation`,
   written in phase 0.** It also gives the failure modes a home: no owner online, the rotator as sole owner
   (uc.account.rotate-keys E4), a lagging grant, a demotion mid-lease, and an event arriving mid-lease. None of
   them belongs to either trigger alone. The cheaper alternative: add the criteria to both existing use-cases and
   accept the duplicate refs.

**Added 2026-09-28 by folding in inbox #206 and #207 (D7, D8), after the owner's answers above: Q5 is open.** It
gates only D7's treatment of a dead-lettered job. D7's other clauses, D8, and everything Q1–Q4 decided keep their
go.

5. **Does `rotateScope` keep refusing the rotator after the job that excluded them dead-letters?** D7 refuses the
   rotator while a **live** (`PENDING`/`LEASED`) job of the scope carries their exclusion, and afterwards while
   their grant is behind. A job that ends `FAILED` is neither, so the refusal lapses there.
   **Recommendation: let it lapse, state it as a bound, and file the dead-letter itself as a new row.**
   - **Failing closed has no way out.** Counting a `FAILED` job's exclusion blocks the rotator on that scope for
     good. That includes after a later rotation and a human re-admission, because no state marks a `FAILED`
     job's exclusion as resolved, and inventing that state is a second design.
   - **The claim path already lapses at exactly this point.** The KDoc on `originateScopeRotationExcluding`
     states it: a fresh job after a dead-letter does not exclude the rotator. D7 lapsing at the same point keeps
     one bound, not two.
   - **The lapse is not the real harm.** In that state the compromise rotation never happened, so the old
     identity already holds the current epoch. That is the defect worth fixing, and it is proposed as a row.

## Goal

A removal is only forward-secure once a rotation **after** it has committed ([ADR 0024](../docs/decisions/0024-scope-key-rotation.md)).
When the remover cannot rotate, the server queues a `scope-rotation` job for somebody else to do it. That queue
has two defects, both measured by reading and neither yet bitten by a test.

- **#180: the wrong people are offered the job.** The claim offers it to every key-holder of the scope, but only
  an owner may commit a rotation. A non-owner's worker claims the job, is refused with `403 not_scope_owner`, and
  fails the job with `rotate-failed`, which spends an attempt. In a group or tenant whose online members are
  mostly not owners, the job dead-letters before an owner comes online. The scope then stays on an epoch the cut
  member holds.
- **#178 (2): a later event is folded into a job that has already been worked.** Origination skips a scope that
  already has a `PENDING` **or `LEASED`** job. A leased job's worker may already have read its inputs, or even
  committed its rotation. An event that arrives in that window is absorbed by a job whose rotation cannot
  reflect it, and the job's completion then closes the event with no rotation behind it.
  - For a membership cut, the window runs from the worker's commit to its completion. A commit after the cut
    excludes the cut member anyway, because the server writes grants only onto memberships that still exist.
  - For an account rotation, the window is wider. It opens when the worker **reads the directory**. A worker
    that read the old identity key seals the new epoch to it, whenever it commits.

After this CR:
- a `scope-rotation` job is offered only to an owner of its scope;
- an event joins a job only while nobody has claimed it;
- a job's completion only ever closes events its worker could have seen;
- a rotator is excluded from every live job on the scope, not just from the one it joined.

*Added by the #206/#207 fold.* Two more defects sit in the same code, both measured by reading and neither yet
bitten by a test.
- **#206: the rotator's exclusion binds only the claim.** `rotateScope` reads no exclusion and no grant. A
  rotator who is an **owner** can therefore commit the scope's next epoch directly, from a client of their own.
  ADR 0060 Decision 2 notes that the attacker it guards against runs one. For an owner, the claim path is the
  only place Decision 4 is enforced.

  Under an honest operator, the other members' clients stop a **silent** re-admission. Each of them verifies its
  own grant against the granter's served key and refuses one whose granter's key is `changed`
  (`grep -n "granter_key_changed" web/src/features/spaces/grantTrust.ts`). What the rotator gets instead is a
  **frozen scope**:
  - every other member's current grant is one their client refuses, so they are awaiting access;
  - the owner whose worker holds the queued job cannot open its own grant, so the worker fails the job until it
    dead-letters (`grep -n "cannot open scope to rotate" web/src/features/spaces/scopes.ts`,
    `grep -n "GrantRefused || e instanceof AwaitingCurrentKey" web/src/features/spaces/scopes.ts`);
  - the only way back is to accept the rotator's new key.

  The human review that Decision 4 routes re-admission through becomes the price of getting the group back. It
  is no longer a free decision. Measured by reading `verifyOwnGrant` and `openById`, not run.
- **#207: a completion takes the worker's word.** A `scope-rotation` job goes `DONE` whether or not anyone
  rotated. The job then closes its events with no epoch behind them. It also ends the window in which #206's fix
  can see the exclusion.

After the fold, also:
- a rotator cannot commit a rotation of the scope, whatever client they use, until the queued rotation has
  landed and a human there has re-admitted them;
- no owner can commit a rotation without holding the scope's current grant;
- a `scope-rotation` job is `DONE` only once its lease holder has committed an epoch while holding the lease.

*Added by the #225 fold.* **#225: a dead-lettered compromise rotation is silent.** Once the job carrying the
rotator's exclusion ends `FAILED`, three things hold at once:
- the old identity still holds the scope's current epoch, which is the harm the rotation was for;
- the rotator's exclusion has lapsed on both paths (the claim's own KDoc bound, and D7 clause 1 by Q5);
- the rotator still believes their scopes are "waiting for someone else". That status was synthesised once, on
  the client, from the rotation's response, and it is never read again.

The rotator is also outside the scope with nothing queued that would ever re-admit them: their grant is current,
so no grant-delivery is originated for them. The dead-letter is not an edge case either. § Inventory for #225
measures a **deterministic** cause by reading: in a scope where every owner's current grant was signed by the
rotator, no owner can open the scope to rotate it, so every attempt fails.

After the #225 fold, also:
- the rotator can read, at any time, whether each queued compromise rotation is `waiting`, `stalled`, `secured`
  or `failed`. The answer comes from the server's rows, not from a status the client synthesised once;
- `secured` is never shown while the old identity can still hold the current epoch;
- from `failed`, the rotator can queue the rotation again, still excluded from it.

*Added by the #234/#235 fold.* Two more gaps in the same rows and the same statement:
- **#234: the other owners are never told.** D9 serves the state to the rotator only. The scope's other owners are
  the ones who can complete or re-queue the rotation, and promote a co-owner where none is eligible, and nothing
  shows them that it is `stalled` or `failed`.
- **#235: `complete` writes `DONE` without a condition.** The holder check is a read on an unlocked row, and the
  write that follows names only the job id. Every other lease transition (claim, heartbeat, fail, decline, the
  stale-epoch release) is a compare-and-set on the lease already.

After the #234/#235 fold, also:
- an owner sees, on their own scope row, when the scope's compromise rotation is `stalled` or `failed`, and can
  re-queue a `failed` one;
- a completion lands only for the worker that holds the lease at the moment it lands, for every tag.

## Context & constraints

- **The promise:** [ADR 0024](../docs/decisions/0024-scope-key-rotation.md) Decision 4 and § Guarantee. The
  guarantee's second bullet already says a rotation *queued to another key-holder* holds against an **honest
  operator only**. This CR stays inside that bound. Both defects fail under an honest operator, and neither fix
  claims anything against an active one.
- **The policy:** [ADR 0025](../docs/decisions/0025-forward-secrecy-policy-per-scope.md). Groups rotate on
  removal. The tenant scope rotates only on an explicit re-key, and an account rotation is that re-key's first
  caller ([ADR 0060](../docs/decisions/0060-key-rotation-after-compromise.md) Decision 5).
- **Who may rotate:** `rotateScope` is owner-gated ([ADR 0014](../docs/decisions/0014-group-scopes-and-governance.md) §4).
  ADR 0060 Decision 4 cites that gate as one of two reasons the rotator cannot re-admit themselves, and
  [uc.account.rotate-keys](../docs/features/account/use-cases/uc.account.rotate-keys.md) step 7 and E4 are
  written on it. Owners-only claiming makes the queue agree with those documents.
- **The queue:** [ADR 0021](../docs/decisions/0021-operator-blind-background-tasks.md) and
  [background-tasks](../docs/crosscutting/background-tasks.md), which says *"Eligibility = holding the scope
  key"*. This CR narrows eligibility per capability tag, which that line must say.
- **The landed half:** `cr-removal-rotates-in-one-request` closed #178 (1). A group owner's removal is now one
  request that deletes the membership and commits the epoch in one transaction. That path originates no job and
  is untouched here.
- **The serialisation point that already exists:** `ScopesStore.rotate` and the grant-delivery `complete` both
  take the scope row under `PESSIMISTIC_WRITE`. This CR adds origination to what serialises on that row. It does
  not introduce a second lock.

### Inventory — measured on `main` @ `faf9ef4`, 2026-09-28

Every claim below is *read, not run*. The command after it re-derives it. Nothing here was bitten by a test.

**#180, the mismatch:**

| Claim | Locator |
|---|---|
| The claim's eligible scopes are the caller's granted ones that the gate lets them write. No role enters. | `grep -n "fun claim" -A3 backend/src/main/kotlin/com/veyq/app/features/tasks/TasksHandler.kt`; `grep -n "fun grantedScopeIds" -A2 backend/src/main/kotlin/com/veyq/app/features/tasks/TasksStore.kt` |
| The store applies one eligible set to every tag in the request. | `grep -n "scopeId in ?1 and capabilityTag in ?2" backend/src/main/kotlin/com/veyq/app/features/tasks/TasksStore.kt` |
| `rotateScope` refuses a non-owner. | `grep -n "RotateOutcome.NotOwner" backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopesHandler.kt` |
| The worker fails (spends an attempt on) every non-stale error, so a `403` goes through `failJob`. Only `stale_epoch` declines. | `grep -n -e "rotate-failed" -e "declineJob(job.jobId" web/src/features/tasks/scopeRotationWorker.ts` |
| Both originators feed that queue. | `git grep -n "originateScopeRotation" backend/src/main` → `TenantHandler.cutSharedAccess`, `RotationHandler.rotate` |

**#180, why "any key-holder" is not a gate flip:**

| Claim | Locator |
|---|---|
| A rotation must re-tag or drop **every** open admission, including those on pending proposals and tenant invites. | `grep -n "AdmissionsIncomplete" backend/src/main/kotlin/com/veyq/app/features/spaces/scope/RotationPolicy.kt`; `grep -n "val open = all" backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopesStore.kt` |
| A pending consent proposal carries an admission. | `grep -n -e "fun addSoftPendingMember" -e "admit(admission)" backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopesStore.kt` (the `admit` right after the function line is its own) |
| A non-owner's member list omits pending proposals. | `grep -n "filter { scopeOwner" backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopesHandler.kt` |
| A tenant rotation reads the invites, and only a tenant owner may list them. | `grep -n "listMemberInvites" web/src/features/spaces/scopes.ts`; `grep -n "MemberInvitesOutcome.NotOwner" backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantHandler.kt` |

So even with the owner gate removed, a non-owner's commit is `409 admissions_incomplete` in every group with a
pending proposal, and the tenant listing refuses them outright.

**Who is an owner on each originator's scope.**
- **Tenant-member removal.** The group keeps at least one owner: a solely owned group is handed to the oldest
  remaining member first. Locator: `grep -n "TRANSFER_THEN_REMOVE" -A4 backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantHandler.kt`.
- **Account rotation.** The rotator is declined from the job. So a scope where the rotator is the **only** owner
  has nobody who may complete it. That is uc.account.rotate-keys E4, already stated, with the remedy *promote a
  co-owner first*: `grep -n "E4" docs/features/account/use-cases/uc.account.rotate-keys.md`. Owners-only
  claiming changes E4 from a job that dead-letters into a job that waits. It does not change what E4 says.

**#178 (2), the join:**

| Claim | Locator |
|---|---|
| Both originations treat a `LEASED` job as joinable. | `grep -n "listOf(JobEntity.PENDING, JobEntity.LEASED)" backend/src/main/kotlin/com/veyq/app/features/tasks/TasksStore.kt` (two origination hits, plus one in `reconcileGrantDelivery`, which is not this CR's) |
| A `scope-rotation` completion marks the job `DONE`, and nothing else. | `grep -n "JobEntity.DONE, jobId" backend/src/main/kotlin/com/veyq/app/features/tasks/TasksStore.kt` (the `grant-delivery` branch above it does not apply) |
| The worker reads the directory **after** its claim. This ordering is what makes "join only `PENDING`" sufficient. | `grep -n "const remaining = await listScopeMembers" web/src/features/tasks/scopeRotationWorker.ts` |
| The server drops a grant to a membership that is gone, so a commit after a cut excludes the cut member. | `grep -n "rows.firstOrNull { it.userId == g.userId }" backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopesStore.kt` |
| There is no such structural drop for an identity change: the grant is opaque, and the server writes it onto the membership as it is. | same line; the identity key a grant was sealed to is not visible to the server |
| The rotating client skips a member whose directory key differs from its pin. A worker that read the directory **before** the new identity was published therefore sees `same` and grants the old key. | `grep -n "checkAndPin(m.userId, m.identityPublicKey)" web/src/features/spaces/scopes.ts` |
| Origination and the cut share a transaction on both paths. | `grep -n -B1 "fun removeMember(callerId: String, tenantId" backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantHandler.kt`; `grep -n "QuarkusTransaction.requiringNew().call<RotationOutcome>" backend/src/main/kotlin/com/veyq/app/features/account/rotation/RotationHandler.kt` |
| The cut deletes the membership **before** it originates, and neither step locks the scope row. | `grep -n "private fun cutSharedAccess" -A5 backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantHandler.kt` |
| No unique index limits live jobs per scope, so a second live job needs no migration. | `grep -n "INDEX" backend/src/main/resources/db/migration/V20__background_jobs.sql` |

**The side door (D6):**
- The front door refuses only `grant-delivery`, and so does the store. Locator:
  `grep -n "CapabilityTags.GRANT_DELIVERY" backend/src/main/kotlin/com/veyq/app/features/tasks/TasksResource.kt backend/src/main/kotlin/com/veyq/app/features/tasks/TasksStore.kt`.
- The registry names each tag's originator. Locator: `grep -n '"originator"' contracts/tasks/tags/v1.json`.

**Every test that touches a `scope-rotation` job** (the blast radius of D1 and D3). Run
`git grep -n "SCOPE_ROTATION" backend/src/test web/src`. Read on `faf9ef4`:
- **Every backend claimer that is expected to succeed seeds an `OWNER`.** Locator:
  `grep -n "role = MembershipEntity" backend/src/test/kotlin/com/veyq/app/features/account/rotation/RotationSliceTest.kt backend/src/test/kotlin/com/veyq/app/features/tasks/TasksSliceTest.kt`.
  In `MemberRemoveSliceTest`, `seedGroup(tenantId, keyholder)` makes the key-holder the owner, and the live e2e's
  B owns G. So D1 disarms no existing negative.
  - Spot-check the one this leans on hardest: `TasksSliceTest.clearingDeclinesNeverFreesARotationExclusion`
    ends in a `204` for the rotator. If the rotator were a plain member there, D1 would make that `204` true for
    the wrong reason.
- **`ScopesSliceTest.purgeErasesAGroupThatEverEnqueuedAJob` calls `tasks.claim(...)` at store level.** If D1
  changes the store's signature, this call site moves with it. Locator:
  `git grep -n -E "(store|tasks)\.claim\(" backend/src`.
- **Two tests assert the PENDING join, and both stay:**
  `MemberRemoveSliceTest.secondRemovalCoalescesIntoTheOneLiveRotationJob` and
  `RotationSliceTest.aScopeThatAlreadyHadALiveRotationStillExcludesTheRotator`. Both seed or produce a `PENDING`
  job. They become the controls for B2 and B3.
- **`RotationSliceTest.rotationJobIn` takes `firstResult()`.** With two live jobs on one scope that answer is
  arbitrary, so the new tests select by status.

**Prose that says "any key-holder" or "one live job coalesces"** (the docs phase). The first angle is keyed on
the wording, the second on the subject:
- `git grep -n -i -E 'coalesc|(next|remaining|any|different)( online)?( remaining)? key-holder|Eligibility = holding' -- docs contracts backend/src/main web/src ':!docs/inbox.md' | grep -i -E 'rotat|coalesc|eligib'`
- `git grep -n -i -E 'SCOPE_ROTATION|scope-rotation|queued rotation|rotation (is )?queued' -- docs contracts backend/src/main web/src ':!docs/inbox.md'`

The second angle adds files the first misses, including ADR 0025, ADR 0060, uc.account.rotate-keys,
`scopes.openapi.yaml` and `rotateKeys.ts`. Read each hit; most only say "key-holder" generically and stay. The
media `COALESCE` hits of the first angle are noise.

### Inventory for the fold (#206, #207): measured on `main` @ `64c1f65`, 2026-09-28

*Added by the #206/#207 fold.* This is also read, not run. `B=backend/src/main/kotlin/com/veyq/app`.

**#206: the exclusion is invisible to `rotateScope`:**

| Claim | Locator |
|---|---|
| `rotateScope` checks the access gate, the owner role and that the scope exists. Nothing else is checked before the store commits. | `grep -n "fun rotateScope" -A8 $B/features/spaces/scope/ScopesHandler.kt` |
| Neither the handler, the policy nor the gate reads a decline or the caller's grant. So the gate admits a grant-less owner. | `grep -n -e JobDeclineEntity -e wrappedGrant -e grantedEpoch $B/features/spaces/scope/ScopesHandler.kt $B/features/spaces/scope/RotationPolicy.kt $B/shared/membership/ScopeAccess.kt` prints nothing |
| The exclusion is a decline row with reason `rotation_originator`. Only the claim's candidate query reads decline rows. | `grep -rn -e rotation_originator -e ROTATION_ORIGINATOR $B` (only `TasksStore`: the write and the constant); `grep -n "JobDeclineEntity d where d.workerId" $B/features/tasks/TasksStore.kt` |
| After rotating, the rotator is still an owner with a live session, and none of their memberships is touched. So their grant is still at the current epoch, sealed to the **old** identity. | `grep -n "revokeOtherFamilies" $B/features/account/rotation/RotationHandler.kt` (the caller's own family is spared); `grep -c Membership $B/features/account/rotation/RotationStore.kt` → `0` |
| The server cannot tell a real commit from one built without the current key: the chain link and grants are opaque, and dropping every admission satisfies the admissions rule. So the behind clause of D7 cannot be left to the key the caller lacks. | `grep -n "Client-minted; the server stores only" $B/features/spaces/scope/ScopesStore.kt`; `grep -n "AdmissionsIncomplete" $B/features/spaces/scope/RotationPolicy.kt` |
| The docs claim what the code lacks. Step 7 and the coverage criterion say the rotator never drives their own re-grant, but both tests behind the criterion go through the claim. | `grep -n "never by the rotator" docs/features/account/use-cases/uc.account.rotate-keys.md`; `grep -n "may never drive their own re-grant" docs/coverage/features/account/use-cases/uc.account.rotate-keys.yaml`; `grep -n "claim(rotator" backend/src/test/kotlin/com/veyq/app/features/account/rotation/RotationSliceTest.kt` |
| An honest client never commits while behind: it states the epoch after the one its own grant opens, so a lagging grant is already `stale_epoch`. The contract describes exactly that. | `grep -n "opened.epochs.current + 1" web/src/features/spaces/scopes.ts`; `grep -n "caller's grant lags the scope" contracts/api/scopes.openapi.yaml` |
| The spaces store already reads the job tables, so D7 and D8 add no new cross-feature direction. | `grep -n "import com.veyq.app.features.tasks" $B/features/spaces/scope/*.kt` → `ScopePurge` |
| The rotation decision is pure and mutation-gated, which is where D7's clauses go. | `grep -n "RotationPolicy" backend/pitest.scope.json .github/workflows/mutation-backend.yml` |

**D7's blast radius on existing tests.** This was measured by a delegated read, and the read returned its ledger.
Locator for every commit call site: `grep -rn "/epochs" backend/src/test/kotlin`. Keep only the POSTs, including
those made through the `ScopesSliceTest` helpers `removeRotating` and `rotateCarrying`.
- **No existing test expects success from a caller who is behind or grant-less.** Every owner that commits
  successfully holds the current grant.
- **Placement matters for two tests.** `ScopesSliceTest.aRemovalRotationKeepsTheOwnerGateAndTheLastOwnerGuard`
  and `rotateNonOwnerIs403` post as a **grant-less non-owner** and assert `403 not_scope_owner`. They keep that
  answer only if D7 runs **after** the owner check.
- **The response code matters for one test.** In `ScopesSliceTest.twoConcurrentRotationsStatingTheSameEpochCommitOnce`,
  the winner commits with `"grants":[]`, which leaves the owner behind. The loser must then answer exactly
  `409 stale_epoch`. That test is why D7's behind clause answers `stale_epoch` and not a new code.
- **`OperatorBlindProbeTest` is unaffected.** Its control owner holds the current grant, and its refused personas
  stop at the gate, which runs first.

**#207: completion is unconditioned:**

| Claim | Locator |
|---|---|
| A `scope-rotation` completion writes `DONE` and nothing else, and the worker sends an empty body. | `grep -n "JobEntity.DONE, jobId" $B/features/tasks/TasksStore.kt`; `grep -n "completeRequest: {}" web/src/features/tasks/scopeRotationWorker.ts` |
| No row records who committed an epoch, or at which epoch a lease began. | `grep -n 'Column(name' $B/features/spaces/scope/Entities.kt $B/features/tasks/Entities.kt` (`veyq_scope_epoch`: id, scope_id, epoch, wrapped_prev, created_at; `veyq_job`: no epoch column) |
| `complete` accepts a holder whose lease has expired but has not been reclaimed. D8's mark therefore has to use the same notion of "holder". | `grep -n -F "job.leaseHolder != userId) return CompleteResult.NotHolder" $B/features/tasks/TasksStore.kt` |
| The worker declines only on `stale_epoch`. A refused completion therefore goes through `failJob` and spends an attempt, which is the right outcome for a worker that did not rotate. | `grep -n "isStaleEpoch(e)" web/src/features/tasks/scopeRotationWorker.ts` |
| The newest migration is `V58`, so D8's column is the next one. The number is re-derived at implementation. | `find backend/src/main/resources/db/migration -name "V5[89]__*"` (prints only `V58__…`) |
| A plain INT column with no foreign key does not enter the scope-erasure closure. | `validate_scope_erasure_arity.py` walks `REFERENCES` edges only (its docstring) |

### Inventory for the #225 fold: measured on `main` @ `7a34c57`, 2026-10-01

*Added by the #225 fold.* This is read, not run: there is no `node_modules` in a refine worktree, and nothing here
needed a JVM. `B=backend/src/main/kotlin/com/veyq/app`.

**The rotator's view is synthesised once and never read again:**

| Claim | Locator |
|---|---|
| The client turns every `queuedScopeIds` entry into `waiting`, on the spot, from the rotation's own response. | `grep -n "status: 'waiting'" web/src/features/account/rotation/rotateKeys.ts` |
| The panel renders that list only as the result of the call that just ran. Nothing re-reads it on a later visit. | `grep -n 'v-for="s in result.scopes"' web/src/features/account/rotation/KeyRotationPanel.vue` |
| No operation lets a user read a job's state back. Tasks has claim, enqueue, heartbeat, complete, fail, decline and clear-declines. Account has no rotation read. | `grep -n "operationId" contracts/api/tasks.openapi.yaml contracts/api/account.openapi.yaml` |
| The `waiting` copy promises re-admission. It does not say that until the rotation lands, the old key still reads the space. | `grep -n -A3 "statusHint: {" web/src/i18n/index.ts` (first hit is English) |
| uc.account.rotate-keys E3 says the job *"stays queued"*. After a dead-letter it does not. | `grep -n "stays queued" docs/features/account/use-cases/uc.account.rotate-keys.md` |

**How a job dead-letters, and what is left afterwards:**

| Claim | Locator |
|---|---|
| A failure at `attempts >= maxAttempts` writes `FAILED`. `maxAttempts` is 5, and every claim, including a lazy reclaim, counts one. | `grep -n "if (job.attempts >= job.maxAttempts) JobEntity.FAILED" $B/features/tasks/TasksStore.kt`; `grep -n "^veyq.tasks.max-attempts" backend/src/main/resources/application.properties` |
| An exhausted job whose lease expired is never claimed again, but it stays `LEASED` until a claim on its scope and tag sweeps it to `FAILED`. Until then D7 clause 1 counts it as live, which errs closed. A status read must count it as dead. | `grep -n "attempts < maxAttempts and" $B/features/tasks/TasksStore.kt`; `grep -n "private fun sweepDeadLetters" -A5 $B/features/tasks/TasksStore.kt` |
| The claim-path exclusion's bound is already written down. | `grep -n "If the job dead-letters" $B/features/tasks/TasksStore.kt` |
| The only record of "this account's compromise rotation on this scope" is the decline row with reason `rotation_originator`, together with that job's status. | `grep -n "reason = ROTATION_ORIGINATOR" $B/features/tasks/TasksStore.kt` |
| After a dead-letter, nothing re-admits the rotator. Grant-delivery is originated only for a member who is grant-less or behind, and the rotator's grant is current, sealed to the old identity. | `grep -n -F "it.grantedEpoch < (epochByScope" $B/features/tasks/TasksStore.kt` |
| The rotator can still act. Their own session family is spared, and their memberships and roles are untouched. | `grep -n "revokeOtherFamilies" $B/features/account/rotation/RotationHandler.kt`; `grep -c Membership $B/features/account/rotation/RotationStore.kt` → `0` |

**A deterministic dead-letter cause: the granter lock.** It is read, not run, and every link has a locator:
1. A rotation writes every grant it carries with the **committer** as granter:
   `grep -n "it.granterUserId = granterId" $B/features/spaces/scope/ScopesStore.kt`.
2. A grant row is served with its granter's **current** identity key:
   `grep -n "CURRENT identity public key" $B/features/spaces/scope/ScopesStore.kt`.
3. An account rotation replaces that key: `grep -n "user.identityPublicKey = identityPublicKey" $B/features/account/rotation/RotationStore.kt`.
4. The recipient verifies the signature under the served key, so a grant the rotator signed with the old key
   is `bad_signature`: `grep -n "granterSign, scope, to, wrappedGrant" web/src/features/spaces/grantTrust.ts`.
5. A refused grant opens as `null` (`grep -n "orAwaitingAccess(async () => toOpenedScope(scope, priv, await me(priv), staleGrant)" web/src/features/spaces/scopes.ts`).
   The rotation therefore throws `cannot open scope to rotate`
   (`grep -n "cannot open scope to rotate" web/src/features/spaces/scopes.ts`).
6. The worker fails the job and spends an attempt:
   `grep -n "else await failJob(job.jobId, 'rotate-failed')" web/src/features/tasks/scopeRotationWorker.ts`.

So in a scope where the rotator committed the last rotation, every surviving owner's grant is one the rotator
signed. The same holds where the rotator admitted every other owner and nothing has rotated since. There, every
owner who claims fails, and the job dead-letters after five claims.

ADR 0028 calls the unverifiable grants deliberate *"until the rotation's re-grant reaches the recipient"*
(`grep -n "the rotation's re-grant reaches the recipient" docs/decisions/0028-identity-authenticity-tofu-verification.md`; the
sentence starting one line above). In exactly this topology no re-grant can be produced. The same mechanism also
freezes every non-owner member the rotator signed for. That is a defect in its own right, and it is out of this
CR: it is proposed as a row (§ Proposed inbox rows (#225 fold)). It belongs here only as the reason the dead-letter is
reachable by honest clients alone.

*Updated by the #233–#235 refine:* the row is inbox #233, and the chain is now **run**, not only read:
`cr-grant-verifies-under-its-signing-key` (landed, CR deleted) § Inventory 5
(the server serves the rotator's new key beside the signature made with the old one) and 6 (an owner holding that
grant cannot open the scope, `commitRotation` throws, and accepting the new key does not help).

**The ordering D9's "newest" rule rests on.** Under D3 a new rotation job is created only when no `PENDING` one
exists, so at any moment at most one job of a scope is `PENDING`, and it is the newest live one. An account
rotation's exclusion therefore always lands on the scope's **newest** job, whether it joins that job or creates
it. D5 also writes the exclusion on older `LEASED` jobs, but none of those carries the D3 guarantee. A re-queue
(Q6) creates or joins a newer job still. Measured by reading D3/D5 above, not run.

### Inventory for the #234/#235 fold: measured on `main` @ `857d711`, 2026-10-01

*Added by the #234/#235 fold.* Read, not run, except where a row says otherwise. `B=backend/src/main/kotlin/com/veyq/app`.

**#234: nothing shows an owner the state.**

| Claim | Locator |
|---|---|
| D9's read serves the caller's own rows only, and its "which scopes" is keyed on the caller's own `rotation_originator` decline. | § Decisions D9, *The read* and *Which scopes* (planned, not built) |
| Neither scope read carries anything about a rotation. | `python3 -c "import yaml;d=yaml.safe_load(open('contracts/api/scopes.openapi.yaml'))['components']['schemas'];print(sorted(d['ScopeSummary']['properties']), sorted(d['MyTenant']['properties']))"` lists no rotation field |
| The sidebar builds its groups from `listMyScopes` and its tenant row from `listMyTenants`, on entry, and marks a scope *awaiting access* there. | `grep -n -e "listMyGroups()" -e "listMyTenants" -e "awaitingAccess" web/src/components/app/AppSidebar.vue` |
| The owner surfaces where a co-owner is promoted, which is E4's remedy for `stalled`. | `grep -rln "awaitingAccess" web/src --include='*.vue'` → `GroupMembersPanel.vue`, `TenantMembersPanel.vue` (and the sidebar) |
| An owner online drains `waiting` jobs by itself: the worker runs on entry and every 45 s. | `grep -n -e "POLL_INTERVAL_MS = " -e "immediate: true" web/src/features/tasks/scopeRotationPolling.ts` |

**#235: the completion is a check, then an unconditioned write.**

| Claim | Locator |
|---|---|
| `complete` reads the job without a lock, checks status and holder on that read, and ends with a write that names only the id. | `grep -n -e "val job = JobEntity.findById(jobId) ?: return CompleteResult.NotFound" -e "job.leaseHolder != userId) return CompleteResult.NotHolder" -e "JobEntity.DONE, jobId" $B/features/tasks/TasksStore.kt` → 187, 189, 224 |
| Every other lease transition is a compare-and-set on the lease: the claim (on `PENDING`, and on the exact expiry it read for a reclaim), heartbeat, fail, decline, and the stale-epoch release inside `complete` itself. | `grep -n -e "and leaseHolder = ?" -e "where id = ?4 and status = ?5" $B/features/tasks/TasksStore.kt` |
| For `grant-delivery` the membership write comes **before** the `DONE` write, in the same transaction. | the line numbers of `grep -n -e "MembershipEntity.update(" -e "JobEntity.DONE, jobId" $B/features/tasks/TasksStore.kt` (216 < 224) |
| The window: a holder whose lease expired completes while another worker reclaims. The holder's read sees itself, the reclaim commits, and the holder's write then erases the new lease. The reclaimer's heartbeat answers `NotHolder`, and its own completion is answered `Done` with its result dropped. | the reclaim branch (`grep -n "Reclaim: guard on the exact expiry" $B/features/tasks/TasksStore.kt`); the idempotent early return (`grep -n "if (job.status == JobEntity.DONE) return CompleteResult.Done" $B/features/tasks/TasksStore.kt`); a lease is 5 minutes (`grep -n "^veyq.tasks.lease.ttl" backend/src/main/resources/application.properties`) |
| The registry promises the rule for every tag. | `grep -c "completed only by the worker holding its lease" contracts/tasks/tags/v1.json` → 1 (on `grant-delivery`; the registry has two tags, `python3 -c "import json;print(list(json.load(open('contracts/tasks/tags/v1.json'))['tags']))"`) |
| **Run elsewhere:** H2 in `MODE=MariaDB` blocks a second guarded `UPDATE` on a row lock and re-evaluates its `WHERE` after the first commits, so a held-transaction test can make the race deterministic. | `cr-one-time-secrets-spend-by-compare-and-set` § Inventory 9 (5 rounds, run 2026-10-01); the test datasource: `grep -n "jdbc.url" backend/src/main/resources/application-test.properties` |
| The harm today is small: under `grant-delivery` the stale holder's grant is a valid one, and a reclaimer's work is duplicate work. What breaks is ADR 0021 §6's rule, which D8 and D9 now lean on for `scope-rotation`. | the two rows above; D8 *"The mark"* defines "leased to" with no expiry test |

## Options

**For #180:**
- **(a) Owners only. Recommended.** `scope-rotation` eligibility is narrowed to the caller's owned, granted,
  writable scopes. Nothing about rotation authority changes.
- **(b) Any key-holder rotates.** Drop the owner gate on `rotateScope` for a rotation without `removes`. This
  needs non-owners to see pending proposals and tenant invites (§ Inventory), and it removes a reason ADR 0060
  Decision 4 relies on. It also lets any member drop open admissions (`droppedAdmissions`), which is a
  membership-management effect ADR 0014 §4 reserves for owners.
- **(c) A rotation authorised by the lease.** A non-owner may commit only while holding a live `scope-rotation`
  lease on that scope. This narrows (b)'s authority, but it hits the same visibility wall, so it costs everything
  (b) costs.

**For #178 (2):**
- **(a) Join only `PENDING`, and only the lease holder's completion closes a job. Recommended.** A worker claims
  after the event has committed, so it reads the directory after the event. The cost: an event arriving mid-lease
  gets a second rotation, one `O(N)` round more, in a window of seconds.
- **(b) A commit closes the live jobs originated before it** (the row's sketch). This fails on this trace:
  1. Owner O's panel reads the directory, with R's old key pinned `same`.
  2. R rotates their account. The job J is originated, with R excluded.
  3. O's synchronous removal-rotation commits `E_{n+1}`, sealed to R's **old** identity.
  4. The commit closes J.

  The compromised key now holds the current epoch, and nothing is queued. Making (b) safe needs an
  originator-kind column, closing only removal-originated jobs. All that buys is the saving of one redundant
  rotation when a synchronous removal overlaps a queued one.

**For #206** (*added by the fold*):
- **(a) Refuse the rotator while a live job carries their exclusion, and refuse any caller whose own grant is
  behind. Recommended (D7).** Each clause covers a window the other misses:
  - **Before the covering rotation lands:** the rotator's grant is at the current epoch, but it is sealed to the
    old identity. Only the decline row tells the server that.
  - **After it lands:** the lease holder's client skipped the rotator's `changed` key
    (`grep -n "checkAndPin(m.userId, m.identityPublicKey)" web/src/features/spaces/scopes.ts`). The rotator is
    therefore behind, their live declines are all on `DONE` jobs, and only the grant check still holds them.
  - **Once a human re-admits them:** the rotator is current again, and nothing refuses them. That is the intended
    end of the exclusion.
- **(b) Refuse only while a live job carries the exclusion.** This reopens the moment the covering job is `DONE`.
  The rotator, now behind and signing with a key everyone else sees as `changed`, commits `E_{n+2}`. That
  freezes the scope exactly as in § Goal, one step later.
- **(c) A per-membership "awaiting re-admission" flag, written at rotation and cleared on re-admission.** It would
  need its own definition of re-admission, and the server cannot see it: after the Options (b) trace above, a
  grant written after the rotation can still be sealed to the old identity. (a) derives the same answer from rows
  that already exist.

**For #207** (*added by the fold*):
- **(a) The lease holder must have committed an epoch on the scope while holding the job's lease. Recommended
  (D8).** "Some epoch advanced during the lease" is **not** enough, for the reason in Options (b): a synchronous
  commit by another owner during the lease may have read the directory before the event.
- **(b) Record the epoch at claim, and record who committed each epoch.** This proves the same thing with two
  columns on two tables, and it attributes every epoch of every scope to a user when nothing else needs that.
  (a) needs one column on the job, written by the commit.
- **(c) Leave it unconditioned.** D7's live clause would then end on the worker's word. A buggy or custom
  completion without a rotation would close the covering job, and would reopen #206 for a rotator whose grant is
  still current.

**For #225** (*added by the #225 fold*):
- **Fold, not a sibling CR.** The status D9 reads is defined by D3's ordering, D5's exclusion and D8's `DONE`.
  The re-queue re-arms D7 clause 1 and is the remedy for Q5's bound. A sibling would be specified against rows
  whose meaning this CR is still changing, and it would land after it anyway.
- **(a) A server-derived per-scope state the rotator can read at any time, plus a re-queue from `failed`.
  Recommended (D9, Q6, Q7).**
- **(b) Inform only:** the same read, with no action. That is the cheaper half of Q6, and it is why Q6 exists.
- **(c) Automatic re-origination when an excluding job dead-letters.** It keeps D7 clause 1 continuous, but it is
  an unbounded loop on the granter lock (§ Inventory for #225).
- **(d) Decline instead of fail when the worker's own grant is refused.** It mirrors the `stale_epoch` decline
  ("a worker that can never perform it"), and it would turn the granter lock from `failed` into `stalled`.
  **Not taken.** A decline is permanent per job (`grep -n "must never be re-offered it" backend/src/main/kotlin/com/veyq/app/features/tasks/TasksStore.kt`).
  So once the granter lock is fixed, an owner whose grant opens again would still be locked out of that job, and
  the job would stay stalled although someone could now do it. Under (a), a `failed` job is re-queued as a fresh
  job with no declines, which is exactly what a fixed lock needs. The lock itself is the proposed row.
- **(e) Surface the state to the scope's other owners as well.** They are the ones who can act. It is not taken
  here: under the granter lock they cannot act either, and their surface is part of the proposed row's design.
  *#234 fold:* now taken as D10 (§ Options for #234 and #235).

**For #234 and #235** (*added by the #234/#235 fold*):
- **Fold both, not sibling CRs.** #234's state is D9's derivation read from the scope's side, and its action is
  D9's re-queue; specified apart, it would be written against a function this CR has not built. #235's guarded
  write is the statement D8 rewrites for `scope-rotation`. Inbox #233 is the opposite case and went to its own CR
  (§ Open for the owner).
- **#234 (a) a field on the owner's scope row, plus an owner re-queue. Recommended (D10, Q8).**
- **#234 (b) the field only.** The owner can tell the rotator, who re-queues (D9).
- **#234 (c) a per-scope endpoint, read when the owner opens the space's member panel.** No contract change to
  the list reads, but the owner learns only by visiting that panel, which is the silence #234 names.
- **#234 (d) push or app-entry notice.** Out, as for D9: no push channel exists (ADR 0021 defers SSE).
- **#235 (a) one guarded statement for every tag. Recommended (D11, Q9).**
- **#235 (b) guard `scope-rotation` only, inside D8.** Leaves `grant-delivery` breaking the rule its registry
  entry states.
- **#235 (c) lock the job row (`PESSIMISTIC_WRITE`) at the read.** Also sound, but every other transition here
  is a compare-and-set, and a lock taken before the scope row would invert D4's lock order on the
  `grant-delivery` path.

## Decisions (for the recommendations)

- **D1: a `scope-rotation` job is offered only to an owner of its scope.**
  - Eligibility becomes **per capability tag**. `grant-delivery` keeps *granted ∩ writable*. `scope-rotation`
    is *granted ∩ writable ∩ owned*.
  - It is computed where the gate is already composed (`TasksHandler.claim`). The store then takes eligibility
    per tag, including in `sweepDeadLetters`. A request that names both tags must never receive a rotation job on
    a scope the caller does not own.
  - How the store's signature carries this (a tag→scopes map, or one query per tag) is the implementer's choice.
    The ordering by `createdAt` within a tag must survive it.
  - Heartbeat, complete, fail and decline are unchanged: they already require the lease.
- **D2: the worker declines, not fails, a rotation refused as `not_scope_owner`.** After D1 this is only the
  race of an owner demoted between claim and commit. The decline is per key-holder and permanent for that job,
  which is the right reading of a demotion: other owners still complete it, and no attempt is spent.
  Defence in depth: it holds even if a future change widens the claim.
  - *Added by the fold:* the worker also declines a commit refused as `rotation_excluded` (D7). That can reach
    a worker only through the one lease D5 leaves alone: the one the rotator already held when they rotated.
- **D3: an origination joins a job only while the job is `PENDING`, and reads it under a row lock.**
  - The lock is `PESSIMISTIC_WRITE` on the `PENDING` job. A concurrent claim's compare-and-set on that row
    then waits for the origination to commit, so the claim, and the directory read after it, come after the
    event.
  - If the job was leased first, the locked read no longer matches `PENDING`, and the origination creates a
    fresh `PENDING` job.
  - A `LEASED` job is never joined, whether its lease is live or expired.
  - **Only the lease holder's completion closes a job; a rotation commit closes none.** § Options (b) is the
    reason.
- **D4: all origination for one scope serialises on the scope row, and the cut takes that lock before it
  deletes.** The lock order is **scope row → membership rows → job rows**, the order `ScopesStore.rotate`
  already follows. `TenantHandler.cutSharedAccess` therefore locks the scope before `removeMembership`.
  Otherwise a cut holding the member's row, while a rotation holds the scope row, deadlocks.

  This lock is what makes D5 exact. A removal and an account rotation of the same scope can no longer both see
  "no `PENDING` job" and create one each, the rotator's exclusion landing on one while the rotator claims the
  other. Scopes locked by one request are taken in ascending `scopeId` order (`RotationHandler` already sorts its
  scopes).
- **D5: an account rotation writes the rotator's exclusion on every live job of the scope**, `PENDING` and
  `LEASED`, not only on the job it joins or creates. Otherwise a `LEASED` job whose lease expires can be
  reclaimed by the rotator, who is an owner, and ADR 0060 Decision 4 falls to lazy reclaim. The exclusion does
  not revoke a lease the rotator already holds. The fresh `PENDING` job (D3) is what guarantees a rotation after
  the identity change.
  - *Added by the fold:* that lease is now inert. D7 refuses the rotator's commit, and D8 refuses a completion
    without one. The worker declines (D2), and the job returns to `PENDING` with no attempt spent.
- **D6: `enqueue` refuses `scope-rotation`**, answering `400 invalid_request` exactly as it answers
  `grant-delivery`, at the front door and in the store. The refused set is **every registry tag whose
  `originator` is server-side** (`server-structural`, `server-event`). The test derives that set from
  `contracts/tasks/tags/v1.json`; nobody lists it by hand.
- **D7 (#206, added by the fold): `rotateScope` refuses a caller that the rotation exclusion names, and any
  caller whose own grant is behind.**
  - **Where it is checked.** Inside `ScopesStore.rotate`, after the scope row is locked. The decision goes into
    the pure `RotationPolicy`, which is in the mutation scope; the store supplies two facts read under the lock.
    Because D4 makes every origination take the same lock, an account rotation and the rotator's own commit on
    one scope serialise. Whichever commits second sees the other.
  - **It runs after the owner check.** A non-owner keeps its `403 not_scope_owner` (§ Inventory for the fold,
    blast radius).
  - **Clause 1, excluded: `403 rotation_excluded`, nothing stored.** The caller holds a decline with reason
    `rotation_originator` on a `scope-rotation` job of this scope whose status is `PENDING` or `LEASED`.
    - Only that reason counts. A worker's own `identity_changed`, `stale_epoch` or `not_scope_owner` decline is
      about one job. It is not about the caller's authority, and it must never stop them rotating.
    - The reason constant stops being private to `TasksStore`.
  - **Clause 2, behind: `409 stale_epoch`, nothing stored.** The caller's membership has no grant, or its
    `grantedEpoch` is below the scope's current epoch.
    - This is the answer the contract already gives for *"the caller's grant lags the scope"*. The server
      simply stops taking the client's word for it.
    - `twoConcurrentRotationsStatingTheSameEpochCommitOnce` needs exactly this code (§ Inventory for the fold).
  - **Why both clauses.** Before the covering rotation lands, only clause 1 can see the rotator: their grant is
    current, but it is sealed to the old identity. Afterwards, only clause 2 can: they were skipped as `changed`
    and are now behind. Once a human re-admits them, neither clause applies. That end is intended, and B6 proves
    it. § Options for #206.
  - **Bound (Q5): a `FAILED` job does not count.** If the job carrying the exclusion dead-letters, clause 1
    lapses. This is the same point where the claim-path exclusion already lapses.
  - **The honest client pays nothing.**
    - The rotator's client never commits a shared-scope rotation (uc.account.rotate-keys step 7).
    - A behind client already states a superseded epoch (§ Inventory for the fold).
    - An honest rotator's client cannot build such a commit in the first place. Its own grant is bound to the
      **old** identity, and it verifies that grant against the identity it now holds, so the grant is refused
      before anything is sent (`grep -n "identityPublicKeyB64: self.pubB64" web/src/features/spaces/grantTrust.ts`).
      Only a client that kept the old key gets as far as the server, and D7 refuses that one. Decision 6 already
      says the rotator is outside the scope until re-admitted.
- **D8 (#207, added by the fold): a `scope-rotation` job completes only after its lease holder committed an epoch
  on the job's scope while holding its lease.**
  - **The mark.** `veyq_job` gains a nullable `rotated_epoch`. `ScopesStore.rotate` sets it to the new epoch, in
    its own transaction, on every `scope-rotation` job of the scope that is `LEASED` to the committer. The lock
    order is scope → membership → job, as D4 requires.
    - "Leased to" means the same thing `complete` means by it: status `LEASED` and `leaseHolder` equal to the
      committer, with no expiry test (§ Inventory for the fold).
  - **The check.** `complete`'s `scope-rotation` branch refuses a job whose `rotated_epoch` is null:
    `409 rotation_not_committed`, nothing written, the lease kept. The worker's catch then fails the job, which
    spends an attempt. That is right for a worker that did not rotate.
  - **The mark is not cleared on reclaim.** Every holder's lease starts after the first claim, and D3 puts every
    joined event before that claim. So any holder's commit during any lease of the job is after all of its
    events. Clearing the mark would only cost the next holder a redundant rotation.
  - **What the server proves, and what it does not.** It proves that an epoch was committed by the lease holder
    during the lease. It does **not** prove that the commit's directory read came after the claim. That is a
    property of the client (§ Inventory, "The worker reads the directory after its claim"), and it stays under
    the honest-client bound.
  - **This is what makes D7's clause 1 exact.** The covering job reaches `DONE` only on a real commit made after
    the event.
  - **Operator-blind.** An integer on a job row, derived from a commit the server already attributes (the
    membership's `granterUserId`). No key and no body.
- **D9 (#225, added by the #225 fold): the rotator reads each queued compromise rotation's state from the server,
  and can re-queue one that dead-lettered.** Q6 and Q7 gate it.
  - **Which scopes.** Every scope where the caller holds a decline with reason `rotation_originator` (D7's
    constant) and is still a member. A purged scope drops out because its rows are gone. A scope the caller
    left drops out because they are no longer a member.
  - **The covering job** is the newest of those jobs by `createdAt` (§ Inventory for #225, the ordering).
  - **The state, first match wins:**
    1. **`secured`** if the caller's own grant on the scope is behind or absent (`grantedEpoch < currentEpoch`,
       the fact D7 clause 2 already reads). The old identity then holds no grant to the current epoch, whatever
       the jobs say. This also covers a synchronous owner rotation that landed with no job behind it.
    2. **`secured`** if the covering job is `DONE`. Under D8 that is a holder's commit made during a lease, and
       under D3 the lease started after the identity change.
    3. **`failed`** if the covering job is `FAILED`, or is `LEASED` with an expired lease and
       `attempts >= maxAttempts` (dead in fact, not yet swept).
    4. **`stalled`** (Q7) if the covering job is live and no owner of the scope both holds a grant and holds no
       decline on it.
    5. **`waiting`** otherwise.
  - **Its one safety property.** `secured` is never shown while the old identity can hold the current epoch.
    Clause 1 holds by what a grant epoch is. Clause 2 holds by D8 and D3, under the honest-client bound D8
    already states.
  - **The error direction is the safe one.** A rotator who was re-admitted after another owner's rotation landed
    can read `failed`. Their re-queue then costs one redundant rotation, nothing else.
  - **The read:** `GET /api/account/rotation/scopes` returns `[{scopeId, state}]`, the caller's own rows only.
    It is empty for an account that never rotated.
  - **The action (Q6):** `POST /api/account/rotation/scopes/{scopeId}/requeue`.
    - Allowed only when the state is `failed`. Any other state answers `409 rotation_not_failed`, and nothing is
      written. A scope the caller holds no exclusion on answers `404`.
    - It calls `originateScopeRotationExcluding(scopeId, caller)` under D4's scope-row lock. D3 and D5 then apply
      unchanged: it joins a `PENDING` job if one exists (a removal may have queued one since), creates one
      otherwise, and excludes the caller on every live job.
    - It answers the new state, which is `waiting` or `stalled`.
    - It is idempotent in effect: from `waiting`, a second call is the `409`.
  - **Where it shows.** Settings → Security (`KeyRotationPanel`) reads the endpoint on mount, and shows the
    per-scope state with the scope's name where the client can decrypt it.
    - This replaces the list synthesised from the rotation response. Right after a rotation the panel re-reads it,
      so `waiting` is the server's word from the first render on.
    - The self-served scopes keep their client outcome (`secured`/`failed`, step 6), because no job is behind
      them.
    - The re-queue button appears only on `failed`, and the promote-a-co-owner hint only on `stalled`.
    - Copy for `waiting` and `failed` says, in plain words, that until the rotation lands the old key still reads
      what is new in that space. Forward secrecy, not repair (ADR 0060 Decision 3).
  - **Not in this CR:** a push or app-entry notice. ~~And the other owners' side.~~ *#234 fold:* that is D10.
    Both were listed in § Options for #225 and in the proposed rows.
  - **Operator-blind.** The read serves scope ids, which the server routes on already, and one enum derived from
    job, decline and membership rows the server already holds. It carries no key and no body. The re-queue writes
    the same job and decline rows a rotation already writes.
  - **Rate-limit posture.** Both endpoints act only on the caller's own rows. The re-queue is bounded by its own
    state gate: at most one live covering job, and each needs five failed claims before it is re-queueable. Each
    endpoint declares `@RateLimitExempt` with that reason, as `rotateAccountKeys` does
    (`grep -n "@RateLimitExempt" -A6 backend/src/main/kotlin/com/veyq/app/features/account/rotation/AccountRotationResource.kt`).
    `RateLimitPostureSweepTest` holds them to declaring one.
- **D10 (#234, added by the #234/#235 fold): an owner's scope row says when the scope's compromise rotation is
  `stalled` or `failed`, and an owner can re-queue a `failed` one.** Q8 gates it.
  - **The value.** For every account that holds a `rotation_originator` decline on a `scope-rotation` job of the
    scope and is still a member, D9's derivation gives that rotator's state, with D9 rule 1's *"the caller's own
    grant"* read as *"that rotator's grant"*. The row carries `failed` if any is `failed`, else `stalled` if any is
    `stalled`, else nothing. One pure function serves D9 and D10; D10 adds no second derivation.
  - **Where.** A nullable `compromiseRotation` (`stalled` | `failed`) on `ScopeSummary` and on `MyTenant` (for a
    tenant root), set only where the caller's membership is `OWNER`. Every other row carries null.
  - **Cost.** Each list read runs one query for `rotation_originator` declines over the caller's owned scope ids,
    and derives only for the hits.
  - **The action.** `POST /api/scopes/{scopeId}/rotation/requeue`, behind the scope-access gate.
    - A non-owner answers `403 not_scope_owner`. A state other than `failed` answers `409 rotation_not_failed`,
      and nothing is written.
    - Under D4's scope-row lock it calls `originateScopeRotationExcluding(scopeId, R)` for every rotator `R` whose
      state is `failed`. D3 and D5 apply unchanged: the first call creates or joins the `PENDING` job, and every
      call writes its rotator's decline on every live job.
    - The caller is not excluded (unless they are one of those rotators), so their own worker can claim it.
    - It answers the row's new value (null or `stalled`).
    - Rate-limit posture: `@RateLimitExempt`, for D9's reason. The state gate bounds it to one live covering job,
      and a job needs five failed claims before it is re-queueable.
  - **The surface.** The sidebar marks the scope (beside *awaiting access*), and the scope's member panel
    (`GroupMembersPanel`, `TenantMembersPanel`) shows a notice: on `failed` with the re-queue, on `stalled` with
    the promote-a-co-owner hint (E4). The copy says in plain words that until the re-key lands, the old key still
    reads what is new in that space. EN and DE.
  - **Operator-blind.** An enum per row the caller owns, derived from rows the server holds; the re-queue writes
    the rows a rotation already writes. Rotator ids are not served: the notice does not name who rotated.
- **D11 (#235, added by the #234/#235 fold): a completion lands in one guarded statement, for every tag.** Q9
  gates it.
  - **The write.** `status = DONE, leaseHolder = null, leaseExpiresAt = null` (and `sealedResult` when one is
    posted) `where id = ? and status = LEASED and leaseHolder = ?`, and for `scope-rotation` also
    `and rotatedEpoch is not null`. D8's check thereby moves into the statement.
  - **The answer on zero rows**, from a fresh read: `DONE` → `Done` (the idempotent no-op stays);
    `scope-rotation`, leased to the caller, no mark → `RotationNotCommitted` (D8); anything else → `NotHolder`.
  - **A lost completion writes nothing.** On `grant-delivery` the membership write must not survive a lost
    compare-and-set: either it runs after the guarded `DONE` succeeds, or the transaction is rolled back on zero
    rows. Implementer's choice; B11 bites both. Either order keeps D4's lock order: the scope row is taken first
    (the stale-epoch read already locks it), and every writer of both a job row and a membership row of one scope
    holds that scope row first, so the two cannot deadlock against each other.
  - **Unchanged:** "holder" still means `LEASED` and `leaseHolder` equal to the caller with no expiry test, as D8
    defines it. A holder whose lease expired but was not reclaimed still completes.
- **Unchanged, stated:**
  - Coalescing into a `PENDING` job stays, so many removals before anyone is online still cost one rotation.
  - ~~`complete` still does not verify that a rotation happened during the lease (proposed as a new inbox row).~~
    That row is #207, and D8 now closes it.
  - A rotator who is the scope's only owner still leaves a job nobody may complete (E4). It now waits instead of
    dead-lettering, and *after the #225 fold* it reads `stalled` (D9, Q7).

## Phases

- [x] 0 decision (docs only, after the owner answers):
      - **ADR 0021:** eligibility is holding the scope key, narrowed per capability tag, and `scope-rotation`
        narrows it to the scope's owners. Add this as a Decision amendment with a pointer here.
      - **[background-tasks](../docs/crosscutting/background-tasks.md):** the *"Eligibility = holding the scope
        key"* line gains the same narrowing.
      - **ADR 0024 § Guarantee:** the second bullet says *"completed by whichever owner is next online"*, and
        states that a job is joined only before it is claimed.
      - **ADR 0060:** ~~leave it alone.~~ *Changed by the fold:* Decision 4 gains a sentence saying the
        exclusion is enforced where the commit lands (`rotateScope`, D7) as well as at the claim. For an owner
        rotator, the owner gate is not a reason at all, and the claim alone left the other reason unenforced.
        Decision 4's *"`rotateScope` is owner-gated"* still becomes true of the queue too, which the ADR already
        assumed.
      - *Added by the fold:* **uc.account.rotate-keys** step 7 and E4 say that `rotateScope` itself refuses the
        rotator until they are re-admitted (D7), and they state the dead-letter bound as Q5 decides it.
        **en.spaces.scope-key-rotation** states that the committer must
        hold the current grant (D7 clause 2), and that a queued job is `DONE` only on its holder's commit (D8).
      - **If Q4 is yes,** write `uc.spaces.complete-queued-rotation`: actor, flow and the five failure modes in
        Q4. Add it to `satisfies:`.
      - **Everything else goes to en.spaces.scope-key-rotation E1:** the join rule, the owners-only claim, and
        the stated consequence that an event mid-lease costs a second rotation.
      - *Added by the #225 fold:* in **uc.account.rotate-keys**:
        - Step 9 reports the four D9 states from the server, re-readable at any time, not only once.
        - E3 stops saying the job *"stays queued"*. It says the job waits while owners are online and
          dead-letters after repeated failures, and that a dead-letter reads `failed` and is re-queueable (Q6).
        - E4 gains its visible form, `stalled`, together with its remedy.
        - A new exception names the granter lock as a known cause of `failed`, with a pointer to the proposed row.
        - Q5's bound becomes *"lapses from the dead-letter until the rotator re-queues"*.
      - *Added by the #225 fold:* **ADR 0060 Decision 6** (*"the honest surface is a per-scope status"*) gains
        one sentence: the status is the server's, it is re-readable, and it includes `failed`.
      - *Added by the #234/#235 fold:*
        - **uc.spaces.complete-queued-rotation** (Q4) names the owners' view (D10) among its failure modes: an
          owner sees `stalled` and `failed` on their own scope row, re-queues `failed`, and promotes a co-owner on
          `stalled`. uc.account.rotate-keys E3 and E4 point there.
        - **[background-tasks](../docs/crosscutting/background-tasks.md)**: the completion is a compare-and-set
          on the lease like every other transition (D11), next to the sentence
          `grep -n "Only the lease holder heartbeats, completes" docs/crosscutting/background-tasks.md` finds.
      · Done when: all `scripts/validate_*.py` green.
- [x] 1 contracts (descriptions only for D1–D8; the generator emits none for them, so they cause no backend
      delta. The #206/#207 fold's error codes are prose on the generic `ErrorResponse`, so this still holds for
      them. **The #225 fold is the exception:** D9 adds two operations, so the generated `AccountRotationApi` gains
      two methods):
      - **`tasks.openapi.yaml`:** the `scope-rotation` paragraph (owners, join-only-pending), the `claim`
        description (per-tag eligibility), and the `enqueue` description (server-originated tags refused, both
        named).
      - **`contracts/tasks/tags/v1.json`:** the `scope-rotation` description. It names only the removal
        originator today; it gains the account-rotation originator, owners-only, and join-only-pending.
      - **`account.openapi.yaml` `queuedScopeIds`:** *"a different key-holder"* becomes *"another owner"*. Its
        *"coalesced job"* sentence stays true (a pending join is still a coalesce).
      - *Added by the fold:* **`scopes.openapi.yaml` `rotateScope`:**
        - the `403` gains `rotation_excluded`;
        - the `409 stale_epoch` text says the server itself refuses a caller whose own grant is behind or absent;
        - the description names both clauses of D7.
      - *Added by the fold:* **`tasks.openapi.yaml` `completeTask`:** the `409` gains `rotation_not_committed`
        for a `scope-rotation` job whose holder committed no epoch during its lease (D8).
      - *Added by the #225 fold:* **`account.openapi.yaml`, tag `account-rotation`:**
        - `listRotationScopeStates` (`GET /api/account/rotation/scopes`) and `requeueRotationScope`
          (`POST /api/account/rotation/scopes/{scopeId}/requeue`), as D9 specifies;
        - a `RotationScopeState` schema of `{scopeId, state}`, with `state` an enum of the four values (three if
          Q7 is no). Every field carries an `x-veyq-visibility` marker: `id` for `scopeId`, `enum` for `state`
          (`validate_field_visibility.py`);
        - the requeue's `409 rotation_not_failed` and `404` documented on `ErrorResponse`;
        - `RotateAccountKeysResult.queuedScopeIds` points at the new read for the state over time.
      - *Added by the #234/#235 fold:*
        - **`scopes.openapi.yaml`:** `compromiseRotation` on `ScopeSummary` and `MyTenant`, a nullable enum of
          `stalled` and `failed` with an `x-veyq-visibility: enum` marker, described as owner-only; the operation
          `requeueScopeRotation` (`POST /api/scopes/{scopeId}/rotation/requeue`) with its `403 not_scope_owner`
          and `409 rotation_not_failed`. This adds an operation to an **existing** tag, so the backend does not
          compile until phase 2 (changes/README.md § phases only above real size), and the response field reddens
          only code that reads it.
        - **`tasks.openapi.yaml` `completeTask`:** the description says the completion lands only for the worker
          that holds the lease when it lands (D11). Description only.
      · Done when: `CapabilityTagRegistryTest` and the contract guards are green (`./gradlew check` runs the
      former), and `npm run generate:api` then `npm run check` are green in `web/`. The generated client is
      gitignored (`git check-ignore -v web/src/api/generated/tasks`), so "no diff" cannot be the check;
      descriptions change no type.
- [x] 2 backend:
      - D1, D3, D4, D5 and D6 in `TasksHandler`, `TasksStore`, `TasksResource` and `TenantHandler.cutSharedAccess`.
      - Rewrite the origination KDocs in `TasksStore` (*"skips when a live (PENDING/LEASED) rotation job already
        exists"*), `CapabilityTags.kt` and `TenantHandler`'s removal KDoc to the new rule. Put the reason for
        not joining a leased job at the line that decides it, as a comment: the worker has read its inputs.
      - Write B1–B4 and E6 first and record each red (DoD).
      - *Added by the fold:*
        - D7 in `RotationPolicy` (a new `RotationDecision` variant and a new `RotateOutcome.RotationExcluded`),
          with the two facts read in `ScopesStore.rotate` under the lock.
        - D8 as migration `V59` (re-derive the number), plus `JobEntity.rotatedEpoch`, the mark in
          `ScopesStore.rotate`, and the check in `TasksStore.complete`.
        - `RotationPolicyTest` gains a unit case for each new clause, so the mutation gate
          (`mutation-backend.yml`, path-triggered on `RotationPolicy.kt`) has something to kill.
        - Update the `originateScopeRotationExcluding` KDoc: its "bound" paragraph now names the second place the
          exclusion binds.
        - Write B5–B7 first and record each red.
      - *Added by the #225 fold:*
        - D9's state derivation as a **pure function** over the rows it reads (the covering job's status, lease
          expiry and attempts; the caller's grant epoch against the scope's; the set of granted, undeclined
          owners), next to `RotationHandler`. The handler supplies the facts.
        - The two endpoints on `AccountRotationResource`, each with its `@RateLimitExempt` reason.
        - The requeue under D4's scope-row lock, through `originateScopeRotationExcluding`.
        - Update that function's KDoc "bound" paragraph a second time: the lapse now ends at a re-queue.
        - Write B8 and B9 first and record each red.
      - *Added by the #234/#235 fold:*
        - D10: the owner value in `listMine` and `myTenants` through D9's pure function, and the re-queue on
          `ScopesResource` with its `@RateLimitExempt` reason, under D4's lock.
        - D11 in `TasksStore.complete`, and its KDoc: the decision is the guarded write, the earlier read only
          chooses the answer.
        - Write B10 and B11 first and record each red.
      · Done when: `./gradlew check` green.
- [ ] 3 web:
      - D2 in `scopeRotationWorker.ts`.
      - Update the header comment (*"the next remaining key-holder"*, *"coalesces multiple removals"*) and the
        comment at `listScopeMembers`, which now carries a load-bearing ordering: the directory must be read after
        the claim, because D3 relies on it.
      - Update `web/src/app/activeSpace.ts`'s comment that any key-holder completes a queued rotation.
      - Write W1 first.
      - *Added by the fold:* D2's `rotation_excluded` leg, with W2 written first.
      - *Added by the #225 fold:*
        - `KeyRotationPanel` reads D9's states on mount and after a rotation, as D9 specifies.
        - `rotateKeys` stops synthesising `waiting`: it returns the self-served outcomes only, and the panel merges
          in the server's list.
        - The requeue action, and the EN and DE copy for the four states and their hints
          (`grep -n "statusHint: {" web/src/i18n/index.ts` → two blocks). `rotation.status.*` and
          `rotation.statusHint.*` are read dynamically, so the i18n test's dead-key blind spot applies
          (`grep -n "rotation.statusHint" web/src/i18n/i18n.test.ts`). Every new key is checked by hand in both
          blocks.
        - Write W3 first.
      - *Added by the #234/#235 fold:* D10's sidebar mark and the member-panel notice with its re-queue and
        promote hint, EN and DE. Write W4 first.
      · Done when: `npm run check` green in `web/`. No e2e is needed: the live `scopeRotation.e2e.test.ts` claims
      as B, who owns G, and no phase here changes an existing request shape. D9's endpoints are new, and B8/B9 bite
      them at the HTTP layer. If the implementer changes one anyway,
      `python3 scripts/run_web_e2e.py` runs from the **main checkout**.
- [ ] 4 docs + coverage:
      - Re-run both prose locators in § Inventory; phases 1–3 wrote new prose.
      - Fix every hit that says *any / next / remaining key-holder completes* a queued rotation, or *one live job
        per scope*: en.spaces.scope-key-rotation E1, uc.spaces.remove-tenant-member (flow and A4),
        uc.spaces.manage-group-members A2, spaces `_index.md`, uc.account.rotate-keys step 7.
      - **Coverage, if Q4 is yes:** the new use-case's criteria carry B1–B4, E6 and W1. The completion half
        leaves `coshared-group-rotation` (remove-tenant-member) and `queued-rotation-backstop`
        (manage-group-members). Each keeps its origination half and points at the new use-case.
      - **Coverage, if Q4 is no:** add the owners-only and join-only-pending criteria to both existing
        criteria, and add the D5 criterion to uc.account.rotate-keys `shared-scopes-queued-not-self-served`.
      - *Added by the fold:*
        - **B5 and B6 go to uc.account.rotate-keys `shared-scopes-queued-not-self-served`**, whichever way Q4
          went. Its text already promises *"the rotator may never drive their own re-grant"*, and today only the
          claim tests back it.
        - **B7 goes with the completion half:** to the new use-case if Q4 is yes, to `coshared-group-rotation` if
          no.
        - W2 goes beside W1.
      - *Added by the #225 fold:*
        - A new criterion in uc.account.rotate-keys, `queued-rotation-state-readable`, carries B8, B9 and W3.
        - `outside-until-readmitted`'s *"the result surface reports this per scope"* now names the re-readable
          server state.
      - *Added by the #234/#235 fold:* B10 and W4 go to the new use-case's failure-mode criterion (Q4); B11 goes
        beside B7, the completion half.
      - **E6 has no existing home.** The enqueue refusal of `grant-delivery` is covered nowhere today
        (`git grep -n "enqueueRejectsStructuralGrantDeliveryTag" docs/coverage` prints nothing on `faf9ef4`).
        With Q4 yes it goes to the new use-case. With Q4 no it goes to `coshared-group-rotation`, the criterion
        whose job it protects.
      · Done when: `python3 scripts/validate_coverage.py` green, all `scripts/validate_*.py` green.

## Definition of done

- **Baseline.** Every phase is done. The baseline was measured green at refine, on `faf9ef4` plus this refine's
  own docs (this CR and two inbox cells): `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 ||
  echo "RED $f"; done` printed nothing. The implementer runs it again before the first edit, and a red from
  that run is not this CR's. *Fold:* the same command printed nothing again, on `64c1f65` before the fold and on
  the fold's own docs (this CR and two inbox cells). *#225 fold:* the same command printed nothing on `7a34c57`
  plus the #225 fold's own docs (this CR and one inbox cell). *#234/#235 fold:* the same command printed nothing
  on `857d711` plus that refine's own docs (this CR, `cr-grant-verifies-under-its-signing-key`, three inbox cells).
- **B1: a non-owner is never offered a rotation job (#180).** A backend slice test, a **live defect** (red on
  the untouched tree).
  - **Setup:** one group with owner O and member M, both granted. A `scope-rotation` job is originated through
    the real tenant-removal path, which cuts a third member.
  - **Action:** M claims `[SCOPE_ROTATION]`.
  - **Assert:** `204`. **Red today:** `200`, with the job.
  - **Positive control, same job:** O's claim answers `200`, with that `jobId`.
  - **Mixed-tag leg:** add a pending `grant-delivery` job on the same group. M claims `[GRANT_DELIVERY,
    SCOPE_ROTATION]` twice. The first claim gets the delivery job, the second answers `204`.
  - **Lever on the fixed tree** (snapshot and restore, changes/README.md § bite-proof): give `scope-rotation`
    the `grant-delivery` eligibility set. B1 goes red on the `204`, not on setup.
- **B2: a removal during a lease gets its own job (#178 (2), removal originator).** A **live defect**.
  - **Setup:** job J1, leased to owner W through the real claim.
  - **Action:** a tenant removal cuts Y's co-shared membership in that group.
  - **Assert:** a second live job J2 exists, `PENDING`. **Red today:** the live count stays 1, because
    `originateScopeRotation` returns `false`.
  - Then W completes J1, and J2 is still live. An owner's claim returns J2's id: that is the positive control
    that J2 is real work, not a row.
  - **Control on the same predicate:** `secondRemovalCoalescesIntoTheOneLiveRotationJob` stays green (a
    `PENDING` job is still joined).
- **B3: an account rotation during a lease gets its own job and excludes the rotator from both (#178 (2),
  identity originator; D5).** A **live defect**.
  - **Setup:** job J1, leased to owner O2, on a scope with the rotator R (an owner) and O2.
  - **Action:** R rotates.
  - **Assert:** a fresh `PENDING` J2 exists. **Red today:** no J2.
  - **Assert:** R is declined on J2 and on J1. Expire J1's lease, then R's claim answers `204`.
  - **Positive control:** O2 reclaims J1 (`200`, J1's id) and claims J2.
  - **Lever for the D5 half:** write the exclusion on J2 only. B3 goes red on R's `204`.
  - **Control that stays:** `aScopeThatAlreadyHadALiveRotationStillExcludesTheRotator`.
- **B4: a claim racing a join lands after the event (D3's lock).** An **injection**: the fixed tree is green by
  construction, so the lever is what proves it.
  - **Setup:** a `PENDING` job. Thread A runs an origination inside a transaction held open on a latch, after
    its locked read.
  - **Action:** thread B claims.
  - **Assert:** B's claim has not returned while A is held. After A commits, B holds the job A joined, and no
    second job exists.
  - **Lever:** drop the lock from the join's read. B's claim returns while A is held, and A then joins a job
    that is already `LEASED`.
  - Pattern: `ScopesSliceTest.twoConcurrentRotationsStatingTheSameEpochCommitOnce`
    (`grep -n "CyclicBarrier" backend/src/test/kotlin/com/veyq/app/features/spaces/scope/ScopesSliceTest.kt`).
  - **If H2 in `MODE=MariaDB` will not block the claim's update on the row lock, B4 cannot be made red.** That
    says the design is not what this CR describes, and the implementer stops and reports it rather than
    shipping D3 on reasoning alone (changes/README.md § a bite test you cannot make red).
- **E6: server-originated tags are refused at enqueue (D6).** A **live defect** for `scope-rotation`.
  - **Test:** read `contracts/tasks/tags/v1.json`. For every tag whose `originator` starts with `server-`,
    assert `400 invalid_request` from `/api/tasks/enqueue`, and assert no row was written.
  - **Red today:** `scope-rotation` answers `201`.
  - **Positive control:** a content tag enqueues (`201`).
  - It is also the permanent guard: a third server tag added to the registry is refused without anyone
    remembering to list it.
- **W1: a `not_scope_owner` refusal declines, not fails (D2).** A web unit test in `scopeRotationWorker.test.ts`,
  a **live defect**.
  - **Setup:** `commitRotation` rejects with `httpError(403, 'not_scope_owner')`.
  - **Assert:** `declineTask` is called with reason `not_scope_owner`, and `failTask` is not.
  - **Red today:** `failTask` with `rotate-failed`.
  - **Control, already live:** the case *"fails the job (not left leased) when the rotation throws mid-flight"*
    (`grep -n "throws mid-flight" web/src/features/tasks/scopeRotationWorker.test.ts`): any other error still
    fails.
- *B5–B7 and W2 were added by the #206/#207 fold.*
- **B5: the rotator cannot commit while their exclusion is live (#206, D7 clause 1).** A backend slice test, a
  **live defect**.
  - **Setup:** a group with owners R and O2, both granted at the current epoch. R rotates their account through
    the real endpoint (the `RotationSliceTest` helper), which queues job J with R's exclusion.
  - **Action:** R posts a well-formed commit at the current epoch + 1 to `/api/scopes/{id}/epochs`, granting R and
    O2, with no admissions.
  - **Assert:** `403 rotation_excluded`, and the scope's epoch and chain are unchanged. **Red today:** `201`.
  - **Positive control, same scope and moment:** O2's identical commit answers `201`.
  - **Leased leg:** O2 claims every live job of the scope before R posts. R is still refused.
  - **Reason-filter control, on a fresh scope:** an owner O3 claims the job and declines it with
    `identity_changed` through the real endpoints. O3's commit then answers `201`.
  - **Levers:**
    - Drop clause 1: B5 goes red on the `403`.
    - Let any decline reason count: the O3 control goes red.
    - Count only `PENDING` jobs: the leased leg goes red.
- **B6: after the covering rotation, the rotator is behind, and re-admission ends the exclusion (#206, D7 clause
  2).** A **live defect**.
  - **Setup:** as in B5. O2 claims every live job and commits the current epoch + 1, granting O2 only, as a
    client that sees R's key as `changed` does. O2 then completes each job, which satisfies D8. Every job
    carrying R's exclusion is now `DONE`.
  - **Action:** R commits at the current epoch + 1, granting R and O2.
  - **Assert:** `409 stale_epoch`, nothing stored. **Red today:** `201`.
  - **Grant-less leg:** a member added without a grant and promoted to `OWNER` through `setRole` commits.
    Assert `409 stale_epoch`. **Red today:** `201`.
  - **Re-admission control (the exclusion ends):** a grant-delivery completion writes R a grant at the current
    epoch, through the real reconcile-on-claim and O2's `complete` with that epoch. R's commit then answers
    `201`.
  - **Lever:** drop clause 2. R's commit and the grant-less leg go red.
- **B7: a `scope-rotation` completion needs its holder's commit during the lease (#207, D8).** A **live defect**.
  - **Setup:** job J, originated through the real tenant-removal path and claimed by owner W.
  - **Assert, no commit:** W completes without committing. The answer is `409 rotation_not_committed`, and J is
    still `LEASED` to W. **Red today:** `204`, and J is `DONE`.
  - **Assert, someone else's commit:** another owner O commits during W's lease, then W completes. The answer is
    `409`.
  - **Assert, a commit before the lease:** W commits while a second job J' is still `PENDING`, then claims J' and
    completes it. The answer is `409`.
  - **Positive control:** W commits during the lease, then completes: `204`, and J is `DONE`. A repeated
    completion also answers `204` (idempotent).
  - **Reclaim leg (pins D8's "not cleared"):** W commits, and W's lease expires. O reclaims and completes without
    committing: `204`.
  - **Levers:**
    - Drop the check: the no-commit leg goes red.
    - Mark every `scope-rotation` job of the scope on any commit, whoever holds it and whatever its status: the
      other-committer and before-the-lease legs go red.
    - Clear the mark at reclaim: the reclaim leg goes red.
- **W2: a `rotation_excluded` refusal declines, not fails (D2 as amended).** Shaped as W1, in
  `scopeRotationWorker.test.ts`.
  - **Setup:** `commitRotation` rejects with `httpError(403, 'rotation_excluded')`.
  - **Assert:** `declineTask` is called with that reason, and `failTask` is not.
  - **Red today:** `failTask`.
- *B8, B9 and W3 were added by the #225 fold.* B8 and B9 are slice tests in `RotationSliceTest`, using its
  `rotate` helper and the real task endpoints. The dead-letter is reached through the real path: an owner claims
  and fails the job `maxAttempts` times. A seeded `FAILED` row would test the reader, not the path.
- **B8: the rotator reads each queued rotation's state from the server (D9).** A **missing behaviour**: red today
  as `404`/`405` on the read, before any assertion. The legs below are what bite the derivation once it exists.
  - **Setup:** a group G with owners R and O2, both granted at the current epoch. R rotates.
  - **`waiting`:** R's read lists G as `waiting`. Positive control: an account that never rotated reads `[]`.
  - **`failed`:** O2 claims and fails the covering job until it is `FAILED`. G reads `failed`.
    - **Unswept leg:** O2 claims the last time and lets the lease expire without failing, and no claim runs after
      that. G still reads `failed`, although the row is `LEASED`.
  - **`secured` by `DONE`:** on a fresh scope, O2 claims, commits granting O2 only, and completes. G reads
    `secured`.
  - **`secured` by behind, with no job done:** on a fresh scope, the covering job dead-letters, and **then** O2
    commits a synchronous rotation granting O2 only. G reads `secured` although the covering job is `FAILED`.
  - **`stalled` (Q7):** a group with R as sole owner and a granted member M. R rotates, and G reads `stalled`. O2
    is promoted to owner with a grant, and G reads `waiting`: that is the control that `stalled` is the
    empty-eligible-set test and not "R is an owner".
  - **Ordering leg:** a job J1 is `LEASED` to O2 when R rotates, so R's exclusion is on J1 (D5) and on the fresh J2
    (D3). O2 commits during J1's lease and completes J1, so J1 is `DONE`. Then J2 dead-letters. G reads `failed`.
  - **Levers:**
    - Derive from the **oldest** excluding job instead of the newest: the ordering leg goes red, reading
      `secured`.
    - Drop rule 1 (behind ⇒ `secured`): the secured-by-behind leg goes red.
    - Read status only, ignoring lease expiry and attempts: the unswept leg goes red.
    - Count R among the eligible owners: the `stalled` leg goes red.
- **B9: from `failed`, the rotator re-queues, and the exclusion is back in force (D9, Q6).** A **missing
  behaviour**: red today as `404`/`405`.
  - **Setup:** as B8's `failed` leg.
  - **Action:** R posts the requeue for G.
  - **Assert:** `200` with `waiting`. A fresh `PENDING` job exists carrying R's `rotation_originator` decline. R's
    well-formed commit on G (B5's shape) now answers `403 rotation_excluded` (D7 clause 1).
  - **Control on the same predicate, on a twin scope set up identically and not re-queued:** R's identical commit
    answers `201`. That is the Q5 lapse, made visible, and it is what the requeue closes. It runs on the twin
    because a commit would change G's state.
  - **Refusals, nothing written:** a second requeue answers `409 rotation_not_failed`, and the job count is
    unchanged. A requeue on a scope R holds no exclusion on answers `404`.
  - **Positive control:** O2 claims the re-queued job (`200`, its id).
  - **Join leg:** after the dead-letter, a tenant removal queues a `PENDING` job J3 on G without R's exclusion.
    R's requeue joins J3 (no new job is created) and writes R's decline on it.
  - **Levers:**
    - Drop the state gate: the second requeue answers `200`, and the refusal leg goes red.
    - Originate without the exclusion: the `403` goes red.
- **W3: the panel shows the server's states and offers the requeue only on `failed` (D9).** A web unit test beside
  `rotateKeys.test.ts`.
  - **Setup:** the read resolves one scope in each state.
  - **Assert:** each row carries its state's badge and hint. The requeue control renders only on the `failed`
    row, and clicking it calls `requeueRotationScope` with that `scopeId` and re-renders the answered state.
  - **Assert:** `rotateKeys` no longer returns a synthesised `waiting` for a queued scope.
  - **Red today:** no read call is made, and `waiting` is synthesised
    (`grep -n "status: 'waiting'" web/src/features/account/rotation/rotateKeys.ts`).
  - **Lever:** render the requeue control on every row; the `failed`-only assertion goes red.
- *B10, B11 and W4 were added by the #234/#235 fold.*
- **B10: an owner sees a stuck compromise rotation on their own row, and can re-queue a failed one (D10).** Slice
  tests, a **missing behaviour**: red today as the absent field and a `404`/`405` on the re-queue.
  - **Setup:** a group G with owners R, O2 and O3 and a plain member M, all granted. R rotates through the real
    endpoint. The dead-letter is reached through the real path: O2 claims and fails the covering job
    `maxAttempts` times (B8's rule: a seeded `FAILED` row tests the reader, not the path).
  - **`waiting`:** before any failure, O2's row for G carries null.
  - **`failed`:** afterwards O2's and O3's rows carry `failed`. M's row carries null: the owner-only control.
  - **`stalled`:** on a fresh group where R is the only granted owner and O4 an owner without a grant, after R
    rotates O4's row carries `stalled`.
  - **Tenant root:** the same `failed` value on `MyTenant` for a tenant scope set up the same way.
  - **Re-queue:** O3 posts it for G: `200`, value null. A fresh `PENDING` job carries R's `rotation_originator`
    decline and none for O3; O3's claim answers `200` with that job; R's well-formed commit on G answers
    `403 rotation_excluded` (D7 clause 1 re-armed).
  - **Refusals, nothing written:** a second re-queue answers `409 rotation_not_failed`, and M's answers
    `403 not_scope_owner`; the job count is unchanged after each.
  - **Levers:**
    - Serve the value on every row, whatever the role: the M control goes red.
    - Exclude the caller instead of the failed rotators: O3's claim answers `204` and R's commit `201`.
    - Drop the state gate: the second re-queue answers `200`.
- **B11: a completion that lost its lease writes nothing (D11).** A backend slice test, a **live defect**.
  - **Setup:** a `grant-delivery` job leased to worker A through the real claim, then A's lease moved into the
    past.
  - **Action:** worker B claims it (the lazy reclaim) inside a transaction the test holds open for 300 ms after the
    claim, while A posts its completion from a second thread inside that window.
  - **Assert:** after both commit, A's completion answered `409`, the job is `LEASED` to B, and the target
    membership is still grant-less. **Red today:** `204`, the job `DONE` with no holder, and A's grant written.
  - **Positive control, same harness:** with no reclaim, A's completion answers `204`.
  - **Scope-rotation leg:** the same race on a `scope-rotation` job whose mark A set during its own lease (D8):
    A's completion answers `409`, and the job stays `LEASED` to B.
  - **Lever:** drop `and leaseHolder = ?` from the guarded write. The main leg goes red.
  - **If H2 does not block A's write on B's open lock,** B11 cannot be made red; stop and report it
    (changes/README.md § a bite test you cannot make red). `cr-one-time-secrets-spend-by-compare-and-set` § Inventory
    9 measured that it does.
- **W4: an owner's stuck rotation is shown, with the action that fits it (D10).** A web unit test for the member
  panel, a **missing behaviour**.
  - **Setup:** the scope row carries `failed`, then `stalled`, then null.
  - **Assert:** on `failed` the notice and the re-queue control render, and clicking it calls `requeueScopeRotation`
    with the scope id and re-renders the answered value. On `stalled` the notice renders with the promote hint and
    no re-queue control. On null nothing renders. The sidebar marks the scope on both non-null values.
  - **Lever:** render the re-queue control on `stalled` too; the `stalled` assertion goes red.
- **Existing tests carry over per assertion, not by count.** Every hit of `git grep -n "SCOPE_ROTATION"
  backend/src/test web/src` keeps its assertions. Comments that say *"live"* where they now mean *"pending"* are
  corrected (`grep -n "live rotation job" backend/src/test`).
- **Existing tests under D7 (fold).** Every existing `POST /epochs` test keeps its answer, including the two
  `not_scope_owner` tests and the concurrent `stale_epoch` test (§ Inventory for the fold, blast radius).
  `OperatorBlindProbeTest` stays green.
- **Invariants.**
  - **Operator-blind.** Every change is job rows, decline rows and a lock. The fold adds one integer column on a
    job row (D8). No key, no body, and no new API field. *#225 fold:* D9 adds two operations. They serve the
    caller's own scope ids and an enum derived from rows the server already holds, and their re-queue writes the
    rows a rotation already writes (D9, Operator-blind). *#234 fold:* D10 adds one owner-only enum on two scope
    reads and one operation that writes the same rows (D10, Operator-blind). D11 changes a `WHERE` clause.
  - **API-first.** Phase 1 is description-only for D1–D8, and no DTO is written by hand. D9's two operations are
    contract-first, and their DTOs are generated. So are D10's field and operation.

**Not proven:**
- *Added by the #225 fold:* **that a `failed` state can be cleared at all under the granter lock.** A re-queue
  under that lock dead-letters again, for the reason § Inventory for #225 measures. D9 makes the state honest,
  not reachable. *Updated by the #233–#235 refine:* the lock is `cr-grant-verifies-under-its-signing-key`
  (planned), and until it lands D10's owner re-queue dead-letters the same way.
- ~~*Added by the #225 fold:* **that the other owners learn of it.**~~ *#234 fold:* D10 and B10/W4 now cover it.
  What stays unproven: **that an owner looks.** The value is shown where the scope is; nothing pushes it.
- **Anything against an active operator.** A queued rotation re-grants the directory the server returns
  (ADR 0024 § Guarantee), and an operator can simply not offer the job. Both are unchanged.
- ~~**That a completed job rotated.**~~ *Fold:* D8 and B7 now prove that the holder committed an epoch during
  the lease. What stays unproven is **that the commit's directory read came after the claim**. The server
  cannot see that, and it is the client's ordering (Phase 3 comments it).
- ~~**A rotator with a custom client driving `rotateScope` directly.**~~ *Fold:* D7 and B5/B6 now cover it.
  What stays unproven:
  - **a rotator whose excluding job dead-lettered** (the Q5 bound), but *after the #225 fold* only until they
    re-queue (D9, B9). The window is visible to them as `failed`, and it is no longer silent;
  - **a rotator who was never excluded on this scope**, for example on a job originated after a dead-letter,
    which the `originateScopeRotationExcluding` KDoc already names.

## Proposed inbox rows (#225 fold)

The refine does not number rows. Once these have numbers, phase 0 replaces every "the proposed row" in this CR
with the number. *Updated by the #233–#235 refine:* both are filed. The first is inbox #233, refined into
`cr-grant-verifies-under-its-signing-key` (landed, CR deleted), which takes the snapshot
shape below and rejects the re-sign shape. The second is inbox #234, folded here as D10.
- **The granter lock: an account rotation leaves every grant the rotator signed unverifiable, and when that
  covers every owner the queued rotation can never land** (security, rotation, crypto). § Inventory for #225 has
  the six-link chain, read but not run. It also freezes every non-owner member the rotator signed for, and no
  path re-grants them. ADR 0028's *"until the rotation's re-grant reaches the recipient"* assumes an owner who can
  open the scope. Two candidate shapes, for its refine:
  - verify a grant under the granter key **as of the grant**, a snapshot column written with the grant, instead
    of the current one;
  - have the rotator's client re-sign its outstanding grants under the new identity. This launders whatever the
    server says the rotator granted, so it is probably the wrong shape.
  Its first test is a red-first web case: an owner whose current grant the rotator signed cannot open the scope
  after the rotator's identity changes.
- **The other owners are never told that a compromise rotation of their scope dead-lettered or stalled**
  (rotation, ux). They are the only ones who can act. § Options for #225 (e) left them out on purpose, because
  under the granter lock they cannot act either. Refine it after the lock row.

## Retrospective

(filled when done)
