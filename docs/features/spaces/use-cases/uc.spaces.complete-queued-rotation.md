# UC: Complete a queued rotation of a space

- **ID:** uc.spaces.complete-queued-rotation
- **Primary actor:** An **owner** of the space (`OWNER` on the scope, holding its current grant) — in practice
  their client's scope-rotation worker, which runs on app entry and every 45 s while the vault is unlocked.
- **Secondary actor:** The **originator** of the job, which is the server on behalf of an event it witnessed but
  cannot complete: a tenant owner's removal that cut a co-shared group
  ([uc.spaces.remove-tenant-member](uc.spaces.remove-tenant-member.md) A4). An account rotation after a compromise
  queues nothing: the secured account commits its scopes' next epochs itself
  ([uc.account.rotate-keys](../../account/use-cases/uc.account.rotate-keys.md),
  [ADR 0060](../../../decisions/0060-key-rotation-after-compromise.md) Decision 4).
- **Goal:** Move a space to a new epoch that the cut member can no longer read, when whoever cut them could not
  rotate it there and then.
- **Preconditions:** A `SCOPE_ROTATION` job is queued on the scope. The owner's device holds the scope's current
  grant.
- **Postconditions (success):** The scope carries a new epoch, re-granted to the members the server listed after
  every event the job carried — each member the worker's client cannot anchor declared pending instead — and every
  live and trashed node of it
  re-keyed under that epoch ([uc.spaces.rekey-after-rotation](uc.spaces.rekey-after-rotation.md)). The job is `done`,
  which the server accepts only because this owner committed that epoch while holding the job's lease and no node
  of the scope lags it. The server saw job, decline
  and membership rows and the rotation's ciphertext — never a key.

## Main flow

1. **The event queues the job.** In the same transaction as the event, under the scope row's lock, the server
   joins the scope's **unclaimed** (`PENDING`) rotation job if there is one, and otherwise creates one.
2. **An owner claims it.** The job is offered only to an owner of the scope who holds its grant and has not
   declined the job; a request that names several job kinds still gets a rotation job only on a scope the caller
   owns. The claim is a lease: one holder at a time, with an expiry two minutes out. The worker renews it every 30 s
   while it works, so a worker that stops — its tab closed, reloaded or navigated away — frees the job for the
   scope's owners within two minutes (`veyq.tasks.lease.ttl`).
3. **The worker reads the directory after the claim.** Every event the job carries committed before the claim, so
   the member list it reads already reflects them. This ordering is the client's, and it is what makes step 1's
   "join only an unclaimed job" sufficient.
4. **It commits the rotation** ([en.spaces.scope-key-rotation](../enablers/en.spaces.scope-key-rotation.md)
   main flow): every member it can anchor granted, every other declared pending, which the server requires
   (`409 rotation_incomplete`). The server refuses a caller who is not an owner, or whose own grant is behind or
   absent. On success it marks every rotation job of the scope leased to this caller with the epoch it committed.
5. **It re-keys the scope's nodes** ([uc.spaces.rekey-after-rotation](uc.spaces.rekey-after-rotation.md)), top-down,
   before it completes.
6. **It completes the job.** The completion lands in one guarded statement: the job is still leased to this caller
   **and**, for a rotation, carries this caller's commit mark, **and** no node of the scope lags the current epoch
   (`409 rekey_incomplete` otherwise; the job stays leased). A completion that lost its lease writes nothing.

## Alternate / exception flows

- **E1 — no owner comes online.** The job waits, unworked. Members who are not owners are never offered it, so it
  does not spend its attempts on workers that could never complete it.
- **E2 — no owner may complete it (`stalled`).** The job is live and every owner of the scope either holds no grant
  or has declined the job. The job waits for good. Every owner's row of that space shows **stalled**, with the
  remedy: promote a co-owner. A new owner holds no decline on the job, so their worker can claim it.
- **E3 — the worker's grant lags the scope.** The commit is `409 stale_epoch`; the worker **declines** the job,
  which releases it to other owners without spending an attempt.
- **E4 — the owner was demoted between claim and commit.** The commit is `403 not_scope_owner`; the worker
  declines, so other owners still complete it and no attempt is spent.
- **E6 — an event arrives while the job is claimed.** It gets a second job of its own: the first job's worker may
  already have read the directory the event changed. The cost is one more rotation.
- **E7 — a completion without a commit.** A worker that completes without having committed an epoch during its
  lease is refused `409 rotation_not_committed`; the job stays leased, and the worker's failure report spends an
  attempt. A commit by someone else, or one made before the lease, does not count.
- **E8 — repeated failures dead-letter the job (`failed`).** After its last attempt the job ends `failed`, and the
  event it carried has no rotation behind it. Every owner's row of that space shows **failed**, with a control to
  queue it again: a fresh job (or the scope's unclaimed one), excluding nobody, so the asking owner's own worker can
  claim it at once.
- **E9 — a lease expires and is reclaimed mid-completion.** The reclaiming owner keeps the job; the stale holder's
  completion writes nothing and is answered `409 not_lease_holder`.
- **E10 — the re-key stops halfway.** The scope reads `rekeying` on every member's row, derived from the node rows,
  and a guest's writes into a node not yet re-keyed wait (a member's re-key their path first,
  [uc.spaces.rekey-after-rotation](uc.spaces.rekey-after-rotation.md)). The next run of any member's worker resumes
  the re-key before it claims anything; a job whose epoch an earlier lease committed completes once the re-key is done, without a second
  rotation.

## Quality notes

- **Honest-operator bound.** The worker re-grants the member list the server returns, and an operator can simply
  not offer the job ([ADR 0024](../../../decisions/0024-scope-key-rotation.md) § Guarantee). What the server
  proves is that the holder committed an epoch during its lease; that the holder's directory read came after the
  claim is the client's ordering (step 3).
- **Owners only, on purpose.** A non-owner cannot build a complete rotation commit: it must re-grant a pending
  consent proposal, which is hidden from non-owners
  ([ADR 0021](../../../decisions/0021-operator-blind-background-tasks.md) §4). A tenant's open member invites are
  no longer the reason: every member reads them (`listOpenInvites`).
- **The states are derived, not stored.** From the scope's newest rotation job and the decline, membership and node
  rows the server already holds: a `done` job needs nobody, a dead one is `failed`, a live one no owner may claim is
  `stalled`. The owner's row carries `stalled` and `failed`, the two that need an owner, and every member's row
  `rekeying` as its own flag, which a worker resumes without anyone acting.
- **Nothing pushes.** The state is shown where the space is (its row and its member panel); nothing notifies an
  owner who does not look.

## User impact

When a space needs a new lock that the person who removed someone cannot fit, another owner's device fits it quietly,
so the removed member stops reading what comes next.

## Related

- Feature: [Spaces (scopes)](../_index.md)
- Enabler: [en.spaces.scope-key-rotation](../enablers/en.spaces.scope-key-rotation.md) (the rotation itself)
- Use-case: [uc.spaces.rekey-after-rotation](uc.spaces.rekey-after-rotation.md) (the re-key step 5 runs)
- Trigger: [uc.spaces.remove-tenant-member](uc.spaces.remove-tenant-member.md) (A4)
- Decisions: [ADR 0021](../../../decisions/0021-operator-blind-background-tasks.md) (the queue; per-tag
  eligibility), [ADR 0024](../../../decisions/0024-scope-key-rotation.md) (§ Guarantee),
  [ADR 0109](../../../decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md) (point 7),
  [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) (§4 owners rotate)
- Concept: [background tasks](../../../crosscutting/background-tasks.md)
- End-user view (docs site): [Invite and manage members](../../../../site/src/content/docs/space-owners/who-gets-into-your-space.mdx)
