# ADR 0066 — Tenant suspension is an immediate, request-level lockout, not a routing toggle

- Status: Accepted
- Built: yes
- Date: 2026-09-07
- Model: [scopes-and-tenancy](../crosscutting/scopes-and-tenancy.md)

## Context

[ADR 0063](0063-database-driven-tenant-ingress.md) names **tenant create / domain change / deactivate** as the
three events its ingress projector reacts to — but "deactivate" named no backend operation. A tenant was
`PENDING` or `ACTIVE`, and the only lifecycle-ending call was revoking a `PENDING` one, a hard delete — there was no
way to take an **`ACTIVE`** tenant offline without destroying it. [uc.admin.create-tenant.md](../features/admin/use-cases/uc.admin.create-tenant.md)
already named the gap and drew the line this ADR now fills: *"Acting on an `ACTIVE` tenant (blocking its owner,
forcing an owner handoff) is a separate, future use-case"* and *"the operator controls availability, never
confidentiality."*

Forces:

- **Availability-only, by hard invariant.** A Server Owner may take a tenant offline; they may never gain
  content access by doing so. Whatever mechanism suspension uses must not touch the scope key, grants, or any
  encrypted row — only reachability.
- **A routing-only toggle is not a real lockout.** Removing a tenant's router entries (the literal reading of
  ADR 0063's "deactivate" trigger) leaves every **already-issued** access token working for its full remaining
  lifetime, and does nothing for a deployment where the app is also reachable by a path that bypasses the
  reverse proxy (an internal network, a direct container port a self-hosting operator exposed). The decision
  needed here, made explicit with the user, is that suspension **blocks immediately, with no exceptions for
  reads** — not "eventually, once tokens expire."
- **A session cannot be revoked per-tenant — only per-account, and accounts span tenants.** A session carries no
  tenant ([ADR 0004](0004-session-tokens.md)), and *"users are global to the server and may belong to multiple
  tenants"* ([scopes-and-tenancy](../crosscutting/scopes-and-tenancy.md)). Revoking a member's sessions to lock
  them out of a suspended tenant would therefore also sign them out of every **other**, unaffected tenant they
  belong to — the opposite of what suspension is
  supposed to scope to. This rules out a session-revoke as part of the mechanism; see Decision.
- **The `Host` says which domain a request arrived on, not which tenant it acts on.** The space switcher is
  host-independent: from whichever tenant's domain the app is open on, it reaches every tenant the caller
  belongs to **by id** (`my-tenants`, then scope-addressed calls). A gate keyed on `Host` therefore refuses a
  suspended tenant's own domain and nothing else — its scopes stay reachable through any other tenant's domain,
  and so do its public shares, its background jobs and its entry in `my-tenants`. The tenant a request acts on
  is known only once the operation has resolved the scope it addresses — which is why a `Host` filter that
  looks like a lockout is not one.
- **A global request-boundary filter is an established pattern here**, not a new shape: the rate-limit posture
  (ADR 0051) resolves its own axis (IP) straight off the raw request before a principal exists. A tenant-suspend
  gate is the same *idea*, keyed on Host instead of IP — but **not the same placement**, and the difference is
  load-bearing: a filter at authentication priority still runs after eager security
  ([ADR 0008](0008-centralized-http-auth.md)) has rejected an unauthenticated request, so it would never see a
  sign-in. The IP limit is unaffected only because it guards public routes, where eager security has nothing to
  reject. See Decision for the placement that does deliver it.

## Decision

**A new tenant status, `SUSPENDED`, refuses every operation that acts on that tenant — decided by the
one scope-access gate every such operation calls, so it holds whichever domain the request arrived on. A
`Host`-keyed filter additionally refuses every API request on the tenant's own domain before authentication,
sign-in included. Neither needs any session state.**

- **The tenant status adds `SUSPENDED`.** Reversible in both directions: a Server Owner can suspend an `ACTIVE`
  tenant and reactivate a `SUSPENDED` one back to `ACTIVE`, through Server-Owner-only operations guarded like every
  other tenant-admin operation. This is deliberately **not** a delete — suspension preserves the tenant, its scope,
  and all content untouched; only reachability changes.
- **The enforcement is per operation: the one scope-access gate.** Every scope-, tenant-, share- and job-addressed
  operation calls it inline ([ADR 0007](0007-membership-role-grant-relation.md)), and the gate reads the addressed scope's tenant
  status on every call ([uc.spaces.scope-access](../features/spaces/use-cases/uc.spaces.scope-access.md) owns the
  whole rule). Membership is answered **first** ([ADR 0042](0042-http-failure-projection.md)), so a non-member
  gets the same 404 as for an invented id and never learns that a tenant is suspended; a member is refused
  as suspended — reads included. Because the gate reads the status on every call, the lockout is as
  immediate as the flip: a still-valid access token opens nothing, since each operation asks again. What
  follows from it: the tenant's public shares answer as revoked links, no background job in it can be claimed
  or completed, and `my-tenants` still lists it — with its status and without its grant or icon, so the
  switcher can show it offline.
- **The `Host` filter stays, for what a `Host` can decide.** A **pre-matching** request filter resolves the tenant
  from the `Host` by the one rule every resource's tenant comes from, and refuses every **API** request on a
  `SUSPENDED` tenant's own domain — unauthenticated or not, before the request reaches a handler or an authentication
  check. That covers what no scope is addressed by — signing in on that domain. What it refuses is the API and
  only the API: the app shell ([ADR 0069](0069-backend-serves-the-web-app-shell.md)) loads on that domain at
  every URL, so the client can say the tenant is suspended, and nothing it does works without the API. It is
  **not** the enforcement — a request on any other domain never meets it (Forces above).
- **No session revoke.** The gate already delivers "blocks immediately, no exceptions": it decides by the
  tenant an operation acts on, independent of which account presents a token, so it refuses every operation
  the moment the status flips, full stop. A session-revoke was considered as extra hardening and **rejected**:
  since sessions are account-scoped, not tenant-scoped (Forces above), it would collaterally sign a
  multi-tenant member out of every *other*, unaffected tenant too — a real defect, not a redundant safety
  margin. A member's refresh token staying technically valid against a suspended tenant is harmless: the gate
  refuses every operation it is presented to, and it ends as normal (signing out other devices, a password
  change) like any other session — suspension does not need to accelerate that.
- **The ingress projector's "deactivate" trigger** ([ADR 0063](0063-database-driven-tenant-ingress.md)) fires
  on `ACTIVE → SUSPENDED`: the tenant's router entries are removed, same as any other domain no longer served.
  This is a third, belt-and-suspenders layer (unreachable at the network edge too) — never the mechanism the
  security guarantee rests on, since an operator's own topology may not route everything through it.
- **Suspension never touches keys.** No wrap, grant, or scope-key operation is part of this ADR — a suspended
  tenant's content is exactly as encrypted and exactly as inaccessible-to-the-operator as an active one; only
  *nobody* (including its own members) can reach it while suspended.

## Consequences

- **A new route is covered only if it calls the gate — so completeness is a test, not a layer.** A filter
  would have inherited every new route automatically, and it would have been the wrong layer (Forces above).
  The per-operation gate inherits nothing, so the operator-blind probe derives the operation set from the
  contract and fails on any operation that is neither probed against a suspended tenant's member nor named on
  its reasoned exclusion list.
- **"Before authentication" costs a thread hop, and any future boundary gate pays it too.** Pre-matching is the
  only placement ahead of eager security, and there a database read must be dispatched off the IO thread
  explicitly; the filter's own comment holds how. Cheap here, because every resource method in this service is
  blocking anyway.
- **Rejected — a `Host`-keyed filter as the mechanism:** it refuses the suspended tenant's own domain and
  nothing else, while the host-independent switcher reaches the tenant's scopes, shares and jobs from any other
  domain (Forces above); kept for sign-in and every other API call on that domain, never the guarantee.
- **Rejected — routing-removal only** (the literal reading of ADR 0063's "deactivate"): insufficient on its
  own per the Forces above; kept as a secondary effect, never the guarantee.
- **Rejected — passive/TTL-based lockout** (suspend the tenant and let existing tokens expire naturally):
  fails the "blocks immediately, no exceptions" requirement decided with the user.

## Related

- [ADR 0063](0063-database-driven-tenant-ingress.md) (the ingress projector this ADR's `SUSPENDED` transition
  drives).
- [uc.admin.create-tenant.md](../features/admin/use-cases/uc.admin.create-tenant.md) (named this as future
  work: "the operator controls availability, never confidentiality").
- [uc.spaces.scope-access.md](../features/spaces/use-cases/uc.spaces.scope-access.md) (the one gate that
  enforces it, and what every other caller is told).
- [uc.admin.suspend-tenant.md](../features/admin/use-cases/uc.admin.suspend-tenant.md) (the use-case this ADR
  backs).
- [ADR 0004](0004-session-tokens.md) (the account-global session-family model that ruled out a session-revoke
  here — sessions carry no `tenantId`).
- Run area of the docs site: [Moderation](../../site/src/content/docs/run/moderation.md)
