---
id: cr-queued-rotation-owners-only-no-leased-join
title: A queued scope rotation reaches only an owner, and never folds a later event into a job already being worked
status: planned
satisfies: [en.spaces.scope-key-rotation, uc.spaces.remove-tenant-member, uc.account.rotate-keys]
adrs: ["0024", "0021", "0060", "0025", "0014"]
invariants: [operator-blind E2EE, API-first]
---

## Open for the owner

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
- **D6: `enqueue` refuses `scope-rotation`**, answering `400 invalid_request` exactly as it answers
  `grant-delivery`, at the front door and in the store. The refused set is **every registry tag whose
  `originator` is server-side** (`server-structural`, `server-event`). The test derives that set from
  `contracts/tasks/tags/v1.json`; nobody lists it by hand.
- **Unchanged, stated:**
  - Coalescing into a `PENDING` job stays, so many removals before anyone is online still cost one rotation.
  - `complete` still does not verify that a rotation happened during the lease (proposed as a new inbox row).
  - A rotator who is the scope's only owner still leaves a job nobody may complete (E4). It now waits instead of
    dead-lettering.

## Phases

- [ ] 0 decision (docs only, after the owner answers):
      - **ADR 0021:** eligibility is holding the scope key, narrowed per capability tag, and `scope-rotation`
        narrows it to the scope's owners. Add this as a Decision amendment with a pointer here.
      - **[background-tasks](../docs/crosscutting/background-tasks.md):** the *"Eligibility = holding the scope
        key"* line gains the same narrowing.
      - **ADR 0024 § Guarantee:** the second bullet says *"completed by whichever owner is next online"*, and
        states that a job is joined only before it is claimed.
      - **ADR 0060:** leave it alone. Decision 4's *"`rotateScope` is owner-gated"* becomes true of the queue
        too, which the ADR already assumed.
      - **If Q4 is yes,** write `uc.spaces.complete-queued-rotation`: actor, flow and the five failure modes in
        Q4. Add it to `satisfies:`.
      - **Everything else goes to en.spaces.scope-key-rotation E1:** the join rule, the owners-only claim, and
        the stated consequence that an event mid-lease costs a second rotation.
      · Done when: all `scripts/validate_*.py` green.
- [ ] 1 contracts (descriptions only; the generator emits none, so no backend delta):
      - **`tasks.openapi.yaml`:** the `scope-rotation` paragraph (owners, join-only-pending), the `claim`
        description (per-tag eligibility), and the `enqueue` description (server-originated tags refused, both
        named).
      - **`contracts/tasks/tags/v1.json`:** the `scope-rotation` description. It names only the removal
        originator today; it gains the account-rotation originator, owners-only, and join-only-pending.
      - **`account.openapi.yaml` `queuedScopeIds`:** *"a different key-holder"* becomes *"another owner"*. Its
        *"coalesced job"* sentence stays true (a pending join is still a coalesce).
      · Done when: `CapabilityTagRegistryTest` and the contract guards are green (`./gradlew check` runs the
      former), and `npm run generate:api` then `npm run check` are green in `web/`. The generated client is
      gitignored (`git check-ignore -v web/src/api/generated/tasks`), so "no diff" cannot be the check;
      descriptions change no type.
- [ ] 2 backend:
      - D1, D3, D4, D5 and D6 in `TasksHandler`, `TasksStore`, `TasksResource` and `TenantHandler.cutSharedAccess`.
      - Rewrite the origination KDocs in `TasksStore` (*"skips when a live (PENDING/LEASED) rotation job already
        exists"*), `CapabilityTags.kt` and `TenantHandler`'s removal KDoc to the new rule. Put the reason for
        not joining a leased job at the line that decides it, as a comment: the worker has read its inputs.
      - Write B1–B4 and E6 first and record each red (DoD).
      · Done when: `./gradlew check` green.
- [ ] 3 web:
      - D2 in `scopeRotationWorker.ts`.
      - Update the header comment (*"the next remaining key-holder"*, *"coalesces multiple removals"*) and the
        comment at `listScopeMembers`, which now carries a load-bearing ordering: the directory must be read after
        the claim, because D3 relies on it.
      - Update `web/src/app/activeSpace.ts`'s comment that any key-holder completes a queued rotation.
      - Write W1 first.
      · Done when: `npm run check` green in `web/`. No e2e is needed: the live `scopeRotation.e2e.test.ts` claims
      as B, who owns G, and no phase here changes a request shape. If the implementer changes one anyway,
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
      - **E6 has no existing home.** The enqueue refusal of `grant-delivery` is covered nowhere today
        (`git grep -n "enqueueRejectsStructuralGrantDeliveryTag" docs/coverage` prints nothing on `faf9ef4`).
        With Q4 yes it goes to the new use-case. With Q4 no it goes to `coshared-group-rotation`, the criterion
        whose job it protects.
      · Done when: `python3 scripts/validate_coverage.py` green, all `scripts/validate_*.py` green.

## Definition of done

- **Baseline.** Every phase is done. The baseline was measured green at refine, on `faf9ef4` plus this refine's
  own docs (this CR and two inbox cells): `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 ||
  echo "RED $f"; done` printed nothing. The implementer runs it again before the first edit, and a red from
  that run is not this CR's.
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
- **Existing tests carry over per assertion, not by count.** Every hit of `git grep -n "SCOPE_ROTATION"
  backend/src/test web/src` keeps its assertions. Comments that say *"live"* where they now mean *"pending"* are
  corrected (`grep -n "live rotation job" backend/src/test`).
- **Invariants.** Operator-blind: every change is job rows, decline rows and a lock; no key, no body, no new
  field. API-first: phase 1 is description-only, and no DTO is written by hand.

**Not proven:**
- **Anything against an active operator.** A queued rotation re-grants the directory the server returns
  (ADR 0024 § Guarantee), and an operator can simply not offer the job. Both are unchanged.
- **That a completed job rotated.** `complete` takes the worker's word (proposed as a new row).
- **A rotator with a custom client driving `rotateScope` directly.** The exclusion binds the claim path only
  (proposed as a new row).

## Retrospective

(filled when done)
