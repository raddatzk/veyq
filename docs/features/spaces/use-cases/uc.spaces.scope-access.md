# UC: Act on a space — who is let in, and what everyone else is told

- **ID:** uc.spaces.scope-access
- **Primary actor:** **anyone who names a space**: a member, a proposed member who has not accepted yet
  (`pendingConsent`), a former member of the tenant, a member of a suspended tenant, a share guest holding a
  link, a background-task worker, or a stranger with an id they guessed.
- **Goal:** Every operation that is addressed by a space (a scope, a tenant's root scope, a share, a background
  job — or a node, medium or bucket that resolves to one) gives the **same answer for the same state**, decided
  in **one place**. What is let through, and what the caller is told, never depends on which feature's handler
  happened to be written last.
- **Preconditions:** none. This use-case owns a **property** with many triggers — every scope-addressed
  operation, the tenant's suspension, a group's deletion, a member's removal, an unaccepted proposal. The
  operations stay with their own use-cases and point here, the way
  [tenant-reachability](../../admin/use-cases/uc.admin.tenant-reachability.md) owns *"the domain routes"* for
  the five operations that change it.
- **Postconditions (success):** the operation ran exactly when the caller's state allows it; otherwise it was
  refused with the answer below and changed nothing.

## Main flow — one gate, one order

1. The operation resolves the space it acts on — directly from the path or body, or by the node, medium,
   bucket, share or job it names.
2. It asks the **one** gate, `ScopeAccess.check(caller, scope, READ | WRITE)` in `core/membership`, **inline,
   before anything else it does** — before existence, kind, role or capability checks. The one thing that may
   come first is an **instance-wide** policy check that names no space (a capability the operator switched off,
   answered `422` to anyone): it reveals nothing about any scope, and answering it first is what lets it read as
   instance policy rather than as a scope error.
3. The gate answers in a fixed order, and the order is the security property
   ([ADR 0042](../../../decisions/0042-http-failure-projection.md)):
   1. **Not a member** — the caller holds no *accepted* membership on this scope → the operation's own
      **404**, identical to the answer for an id nobody ever issued. A proposed member who has not accepted is
      not a member here. Nothing the gate knows beyond this point — deletion, suspension — is ever revealed to
      this caller.
   2. **Tenant suspended** → **`403 tenant_suspended`**, for reads and writes alike, whatever `Host` the request
      arrived on ([ADR 0066](../../../decisions/0066-tenant-suspension.md)).
   3. For a **write** only: the caller **no longer belongs to the tenant** (no accepted membership on its root
      scope) → the same 404 as step 1; the scope is **being deleted** → **`409 scope_deleting`**.
   4. Otherwise **ok** — and only now does the operation run its own checks (owner, key-holder, capability,
      state) and its work.
4. Operations addressed by a **tenant** use the same gate on the tenant's **root scope** (`scopeId ==
   tenantId`), so *belonging to a tenant* is one fact with one definition: an accepted membership on its root.

## Alternate / exception flows

- **A1 — restore is the one write a deleting group allows.** [delete-group](uc.spaces.delete-group.md)'s
  restore is the only way out of the grace window, so it skips the deleting check — and only that: it still
  needs an owner who still belongs to the tenant, on a GROUP.
- **A2 — a former tenant member reads their own spaces, and does nothing else.**
  [remove-tenant-member](uc.spaces.remove-tenant-member.md) keeps their membership on their own personal space
  and solo groups for the grace window. Reads pass step 1; every write — restore included — stops at step 3,
  and every tenant-addressed operation answers 404.
- **A3 — a proposed member sees only their proposal.** Until they accept
  ([consent-group-add](uc.spaces.consent-group-add.md)) they read and answer their own invitation and nothing
  of the group: every group route answers them exactly as it answers a stranger.
- **A4 — a share serves only while its space is live.** A guest holding a link is not a member, so they learn
  neither deletion nor suspension: while the space is being deleted or its tenant is suspended, every guest
  route answers the **same 404 as a revoked link**, and the link works again unchanged after a restore or a
  reactivation — nothing is written onto the share. An invite link to a suspended tenant answers like an
  invite that is no longer valid, for the same reason.
- **A5 — a background job is worked on only by the member holding its lease, in a live space.** Claiming,
  heartbeating, completing, failing and declining a job are writes on its space, so none of them happen in a
  suspended tenant or a deleting group — the jobs wait for reactivation or restore, or are erased with the
  purge. Completing or failing a job additionally needs the **current lease**: a job leased to someone else
  answers `409 not_lease_holder`, as heartbeat and decline already do; completing a job that is already done
  stays a no-op success ([background-tasks](../../../crosscutting/background-tasks.md)).
- **A6 — a member with no key in a deleting group stays without one.** Grant delivery is a job (A5), so a
  member added just before the deletion — or one re-keying after an account key rotation
  ([ADR 0060](../../../decisions/0060-key-rotation-after-compromise.md)) — stays *awaiting access* until
  a restore. The grace window's promise is that nothing changes, and a delivered grant is a change.
- **A7 — the space switcher still lists a suspended tenant.** `my-tenants` carries each tenant's status; a
  suspended one comes with **no grant and no icon** and is shown offline and not enterable
  ([switch-spaces](uc.spaces.switch-spaces.md)). A `403 tenant_suspended` arriving mid-session for a tenant
  other than the one whose domain the app is open on leaves that space for the domain's own tenant; on the
  suspended tenant's **own** domain there is nowhere to go, so the app shows a full-page notice that the space
  is temporarily offline — the same card shape as the not-set-up notice, naming no address.
- **A8 — `Host` and gate agree on their own domain.** On the suspended tenant's own domain the `Host` filter
  refuses every API request first, sign-in included; the gate is what refuses the same tenant from everywhere
  else.

## Quality notes

- **Why inline and not a request filter.** A filter keyed on `Host` cannot see which tenant a request is
  *about*: the space switcher reaches every tenant's spaces by id from whichever domain the app is open on, so
  a suspended tenant's content was reachable through any other tenant's domain. The tenant a request acts on is
  only known once the operation has resolved its space — which is exactly where the gate is called. The `Host`
  filter stays, for what it can do: refusing sign-in and every other API call on the suspended tenant's own
  domain ([ADR 0066](../../../decisions/0066-tenant-suspension.md)).
- **One gate, so the order cannot drift.** Before this use-case, every handler decided on its own: pending
  proposals counted as members, "read-only while deleting" covered node writes only, and three different codes
  meant *being deleted*. There is now one code (`scope_deleting`) and one place to get the order wrong.
- **Completeness is derived, not listed.** `OperatorBlindProbeTest` takes the operation set from the generated
  API interfaces — the contract — and requires every operation to be either probed against each state above or
  named on a reasoned exclusion list: public or token-gated, Server-Owner, account-wide, the Host-resolved
  operations that touch only the caller's own rows on the Host's own tenant (where the `Host` filter is a correct
  suspension gate), answering one's own proposal (A3 — the gate by definition does not count it), and the
  listings whose answer is a filter rather than a refusal (claim, my-tenants), which carry tests of their own. A
  new operation that nobody classified is red on the day it is added, instead of an unguarded route nobody
  noticed.
- **Operator-blind by construction.** The gate reads membership rows, a scope's `deletedAt` and a tenant's
  `status` — operational metadata the server already holds ([ADR 0007](../../../decisions/0007-membership-role-grant-relation.md)).
  No key, no name, no content.

## User impact

Only the people in a space can reach it, and to everyone else it looks as if it did not exist — not even whether it is
being deleted or taken offline.

## Related

- Feature: [Spaces (scopes)](../_index.md)
- Decisions: [ADR 0042](../../../decisions/0042-http-failure-projection.md) (the guard order),
  [ADR 0066](../../../decisions/0066-tenant-suspension.md) (suspension), [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md)
  §5/§6 (proposals, the deletion grace), [ADR 0021](../../../decisions/0021-operator-blind-background-tasks.md)
  (the lease)
- Crosscutting: [cc.security.authorization](../../../crosscutting/cc.security.authorization.md) (the map of every
  authorization dimension)
- The operations whose state this decides: [delete-group](uc.spaces.delete-group.md),
  [consent-group-add](uc.spaces.consent-group-add.md), [remove-tenant-member](uc.spaces.remove-tenant-member.md),
  [switch-spaces](uc.spaces.switch-spaces.md), [suspend-tenant](../../admin/use-cases/uc.admin.suspend-tenant.md),
  [share-shared-scope](../../sharing/use-cases/uc.sharing.share-shared-scope.md)
- End-user view (docs site): [Your spaces](../../../../site/src/content/docs/spaces/index.mdx)
- End-user view (docs site): [Create a group](../../../../site/src/content/docs/spaces/create-a-group.mdx)
