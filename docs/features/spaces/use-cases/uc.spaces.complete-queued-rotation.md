# UC: Complete a queued rotation of a space

- **ID:** uc.spaces.complete-queued-rotation
- **Primary actor:** An **owner** of the space (`OWNER` on the scope, holding its current grant) — in practice
  their client's scope-rotation worker, which runs on app entry and every 45 s while the vault is unlocked.
- **Secondary actor:** The **originator** of the job, which is the server on behalf of an event it witnessed but
  cannot complete: a tenant owner's removal that cut a co-shared group
  ([uc.spaces.remove-tenant-member](uc.spaces.remove-tenant-member.md) A4), or a member's account rotation after a
  compromise ([uc.account.rotate-keys](../../account/use-cases/uc.account.rotate-keys.md) step 7).
- **Goal:** Move a space to a new epoch that the event's subject can no longer read — the cut member, or the
  rotated account's old identity — when whoever caused the event could not rotate it there and then.
- **Preconditions:** A `SCOPE_ROTATION` job is queued on the scope. The owner's device holds the scope's current
  grant.
- **Postconditions (success):** The scope carries a new epoch, re-granted to the members the server listed after
  every event the job carried, minus any whose identity key reads `changed`. The job is `done`, which the server
  accepts only because this owner committed that epoch while holding the job's lease. The server saw job, decline
  and membership rows and the rotation's ciphertext — never a key.

## Main flow

1. **The event queues the job.** In the same transaction as the event, under the scope row's lock, the server
   joins the scope's **unclaimed** (`PENDING`) rotation job if there is one, and otherwise creates one. An account
   rotation additionally excludes the rotator from **every** live rotation job of the scope, claimed or not.
2. **An owner claims it.** The job is offered only to an owner of the scope who holds its grant and has not
   declined the job; a request that names several job kinds still gets a rotation job only on a scope the caller
   owns. The claim is a lease: one holder at a time, with an expiry.
3. **The worker reads the directory after the claim.** Every event the job carries committed before the claim, so
   the member list it reads already reflects them. This ordering is the client's, and it is what makes step 1's
   "join only an unclaimed job" sufficient.
4. **It commits the rotation** ([en.spaces.scope-key-rotation](../enablers/en.spaces.scope-key-rotation.md)
   main flow). The server refuses a caller who is not an owner, who is excluded by a live job, or whose own grant
   is behind or absent. On success it marks every rotation job of the scope leased to this caller with the epoch it
   committed.
5. **It completes the job.** The completion lands in one guarded statement: the job is still leased to this caller
   **and**, for a rotation, carries this caller's commit mark. A completion that lost its lease writes nothing.

## Alternate / exception flows

- **E1 — no owner comes online.** The job waits, unworked. Members who are not owners are never offered it, so it
  does not spend its attempts on workers that could never complete it.
- **E2 — no owner may complete it (`stalled`).** Every owner of the scope either holds no grant or has declined the
  job — always the case where the rotated account was the scope's only owner (uc.account.rotate-keys E4). The
  job waits for good. Every owner's row of that space shows **stalled**, with the remedy: promote a co-owner. A new
  owner holds no decline on the job, so their worker can claim it.
- **E3 — the worker's grant lags the scope.** The commit is `409 stale_epoch`; the worker **declines** the job,
  which releases it to other owners without spending an attempt.
- **E4 — the owner was demoted between claim and commit.** The commit is `403 not_scope_owner`; the worker
  declines, so other owners still complete it and no attempt is spent.
- **E5 — the excluded rotator already held the lease.** An owner who rotates their account while their own worker
  holds the job keeps that lease, but it is inert: their commit is `403 rotation_excluded`, their completion is
  refused without one, and the worker declines.
- **E6 — an event arrives while the job is claimed.** It gets a second job of its own: the first job's worker may
  already have read the directory the event changed, and for an account rotation a directory read before the new
  identity was published seals the new epoch to the old one. The cost is one more rotation.
- **E7 — a completion without a commit.** A worker that completes without having committed an epoch during its
  lease is refused `409 rotation_not_committed`; the job stays leased, and the worker's failure report spends an
  attempt. A commit by someone else, or one made before the lease, does not count.
- **E8 — repeated failures dead-letter the job (`failed`).** After its last attempt the job ends `failed`, and the
  event it carried has no rotation behind it. Every owner's row of that space shows **failed**, with a control to
  queue it again: a fresh job (or the scope's unclaimed one), excluding every rotated account whose rotation had
  failed — but not the owner who asks, so their own worker can claim it at once. The rotated account itself sees
  the same state in its security settings and can re-queue it too, still excluded
  ([uc.account.rotate-keys](../../account/use-cases/uc.account.rotate-keys.md) E3).
- **E9 — a lease expires and is reclaimed mid-completion.** The reclaiming owner keeps the job; the stale holder's
  completion writes nothing and is answered `409 not_lease_holder`.

## Quality notes

- **Honest-operator bound.** The worker re-grants the member list the server returns, and an operator can simply
  not offer the job ([ADR 0024](../../../decisions/0024-scope-key-rotation.md) § Guarantee). What the server
  proves is that the holder committed an epoch during its lease; that the holder's directory read came after the
  claim is the client's ordering (step 3).
- **Owners only, on purpose.** A non-owner cannot build a complete rotation commit: it must carry every open
  admission, and a pending consent proposal and a tenant's open member invites are hidden from non-owners
  ([ADR 0021](../../../decisions/0021-operator-blind-background-tasks.md) §4).
- **The states are derived, not stored.** `waiting`, `stalled`, `secured` and `failed` are computed from job,
  decline and membership rows the server already holds; the owner's row carries only `stalled` and `failed`,
  the two that need an owner, and never names who rotated.
- **Nothing pushes.** The state is shown where the space is (its row and its member panel); nothing notifies an
  owner who does not look.

## What this means for you / Was das für dich bedeutet

**EN —** Sometimes a shared space needs a new lock and the person who caused it can't fit one: an admin removed
someone from a group they aren't in, or a member re-secured their account after a break-in. Then one of the
space's owners does it — quietly, in the background, the next time they open the app. Until that happens, whoever
was removed (or the old, compromised key) can still read what's new in that space. If it can't happen — every
owner is out, or it kept failing — owners see it on the space and can fix it: name another owner, or start it
again.

**DE —** Manchmal braucht ein geteilter Raum ein neues Schloss, und wer es ausgelöst hat, kann es nicht selbst
einbauen: Eine Admin-Person hat jemanden aus einer Gruppe entfernt, in der sie selbst nicht ist, oder ein Mitglied
hat sein Konto nach einem Einbruch neu abgesichert. Dann übernimmt das eine der Personen, denen der Raum gehört —
still im Hintergrund, beim nächsten Öffnen der App. Bis dahin kann die entfernte Person (oder der alte, kompromittierte
Schlüssel) Neues in diesem Raum noch lesen. Klappt es nicht — niemand kann es übernehmen, oder es ist wiederholt
gescheitert —, sehen die besitzenden Personen das am Raum und können es beheben: eine weitere besitzende Person
ernennen oder es neu anstoßen.

## Related

- Feature: [Spaces (scopes)](../_index.md)
- Enabler: [en.spaces.scope-key-rotation](../enablers/en.spaces.scope-key-rotation.md) (the rotation itself)
- Triggers: [uc.spaces.remove-tenant-member](uc.spaces.remove-tenant-member.md) (A4),
  [uc.account.rotate-keys](../../account/use-cases/uc.account.rotate-keys.md) (step 7)
- Decisions: [ADR 0021](../../../decisions/0021-operator-blind-background-tasks.md) (the queue; per-tag
  eligibility), [ADR 0024](../../../decisions/0024-scope-key-rotation.md) (§ Guarantee),
  [ADR 0060](../../../decisions/0060-key-rotation-after-compromise.md) (Decisions 4 and 6),
  [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) (§4 owners rotate)
- Concept: [background tasks](../../../crosscutting/background-tasks.md)
