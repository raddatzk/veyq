# ADR 0066 — Tenant suspension is an immediate, request-level lockout, not a routing toggle

- Status: Accepted
- Date: 2026-09-07

## Context

[ADR 0063](0063-database-driven-tenant-ingress.md) names **tenant create / domain change / deactivate** as the
three events its ingress projector reacts to — but "deactivate" names no existing backend operation.
`TenantStatus` is `[PENDING, ACTIVE]` only (`contracts/api/tenants.openapi.yaml`), and the only
lifecycle-ending call today is `revokeTenant`, guarded to `PENDING` and implemented as a hard delete
(`TenantStore.deleteTenant`, `TenantStore.kt:218-222`) — there is no way to take an **`ACTIVE`** tenant
offline without destroying it. [uc.admin.create-tenant.md](../features/admin/use-cases/uc.admin.create-tenant.md)
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
- **A session cannot be revoked per-tenant — only per-account, and accounts span tenants.**
  `SessionService.revokeAllForUser` (`SessionService.kt:152`) is user-scoped, and
  [scopes-and-tenancy.md](../crosscutting/scopes-and-tenancy.md) states plainly that *"users are global to the
  server and may belong to multiple tenants"* — confirmed structurally: `SessionFamilyEntity` carries no
  `tenantId` at all. Revoking a member's sessions to lock them out of a suspended tenant would therefore also
  sign them out of every **other**, unaffected tenant they belong to — the opposite of what suspension is
  supposed to scope to. This rules out a session-revoke as part of the mechanism; see Decision.
- **The per-request Host→tenant resolution this needs already exists.** `ScopesResource.activeHost()` +
  `ScopesStore.findTenantIdByDomain` (`ScopesResource.kt:223-226`, `ScopesStore.kt:87-89`) is the same lookup a
  request-level gate needs — reuse, not new machinery.
- **A global request-boundary filter is an established pattern here**, not a new shape: the rate-limit posture
  (ADR 0051) is enforced by a `@Provider` `ContainerRequestFilter` running at `Priorities.AUTHENTICATION`
  (`RateLimitFilters.kt`), resolving its own axis (IP) straight off the raw request before a principal exists.
  A tenant-suspend gate is the same *idea*, keyed on Host instead of IP — but **not the same shape**, and the
  difference is load-bearing. `Priorities.AUTHENTICATION` does not actually place a filter ahead of
  authentication: Quarkus's eager security (ADR 0008) rejects an unauthenticated request from a handler that
  runs before every post-match filter, so a gate at that priority never sees the request and the caller gets
  `401`. `IpRateLimitFilter` is unaffected only because `key = IP` is confined to `@PermitAll` routes, where
  eager security has nothing to reject. See Decision for the shape that does deliver it.

## Decision

**A new `TenantStatus` value, `SUSPENDED`, gates every request to that tenant at the boundary — before
authentication is even evaluated — resolved from `Host`, so it needs no session state at all.**

- **`TenantStatus` becomes `[PENDING, ACTIVE, SUSPENDED]`.** Reversible in both directions: a Server Owner can
  suspend an `ACTIVE` tenant and reactivate a `SUSPENDED` one back to `ACTIVE`. This is deliberately **not**
  `revokeTenant`'s hard-delete path — suspension preserves the tenant, its scope, and all content untouched;
  only reachability changes. New Server-Owner-only contract operations on
  `contracts/api/tenants.openapi.yaml` (`POST /api/tenants/{tenantId}/suspend`,
  `POST /api/tenants/{tenantId}/reactivate`, or one status-transition operation — the CR decides the exact
  shape), guarded the same way every other tenant-admin operation already is.
- **A request-level gate, not a routing-level one.** A new **pre-matching** request filter
  (`TenantSuspensionFilter`) resolves the tenant from the `Host` the same way `ScopesResource.activeHost()`
  already does and rejects **every** request against a `SUSPENDED` tenant with `403 tenant_suspended` —
  unauthenticated or not, read or write, before the request reaches a handler or an authentication check. This
  is what makes the lockout immediate rather than eventual: a still-valid access token cannot be used at all,
  because the gate never gets far enough to look at it.
- **No session revoke.** The request gate alone already delivers "blocks immediately, no exceptions" — it
  resolves the tenant from `Host`, independent of which account presents a token, so it refuses every request
  the moment the status flips, full stop. A session-revoke was considered as extra hardening and **rejected**:
  since sessions are account-scoped, not tenant-scoped (Forces above), it would collaterally sign a
  multi-tenant member out of every *other*, unaffected tenant too — a real defect, not a redundant safety
  margin. A member's refresh token staying technically valid against a suspended tenant is harmless: the gate
  refuses it exactly like every other request, and it dies as normal on `revokeOtherFamilies` or password
  change like any other session — suspension does not need to accelerate that.
- **The ingress projector's "deactivate" trigger** ([ADR 0063](0063-database-driven-tenant-ingress.md)) fires
  on `ACTIVE → SUSPENDED`: the tenant's router entries are removed, same as any other domain no longer served.
  This is a third, belt-and-suspenders layer (unreachable at the network edge too) — never the mechanism the
  security guarantee rests on, since an operator's own topology may not route everything through it.
- **Suspension never touches keys.** No wrap, grant, or scope-key operation is part of this ADR — a suspended
  tenant's content is exactly as encrypted and exactly as inaccessible-to-the-operator as an active one; only
  *nobody* (including its own members) can reach it while suspended.

## Consequences

- **The gate is a new boundary surface every tenant-scoped route passes through**, so it must be a filter, not
  a per-handler check — a new route added later inherits it automatically only if it is wired at that layer.
- **`TenantStatus` becomes a three-way enum** everywhere it is matched exhaustively (Kotlin `when`, the
  generated TS union) — the CR locates every such site rather than assuming only the two known ones.
- **"Before authentication" costs a thread hop, and any future boundary gate pays it too.** Pre-matching is the
  only placement ahead of eager security, and a pre-matching filter runs before RESTEasy Reactive has decided
  blocking-vs-not — so it runs on the IO thread, where a database read throws. `@Blocking` is not available
  there (the build rejects it outside "entrypoint" methods), so the filter dispatches explicitly to a worker
  pool. Cheap here, because every resource method in this service is blocking anyway; the constraint is
  recorded because the next gate that needs to read anything will hit it identically.
- **Rejected — routing-removal only** (the literal reading of ADR 0063's "deactivate"): insufficient on its
  own per the Forces above; kept as a secondary effect, never the guarantee.
- **Rejected — passive/TTL-based lockout** (suspend the tenant and let existing tokens expire naturally):
  fails the "blocks immediately, no exceptions" requirement decided with the user.

## Related

- [ADR 0063](0063-database-driven-tenant-ingress.md) (the ingress projector this ADR's `SUSPENDED` transition
  drives).
- [uc.admin.create-tenant.md](../features/admin/use-cases/uc.admin.create-tenant.md) (named this as future
  work: "the operator controls availability, never confidentiality").
- [uc.admin.suspend-tenant.md](../features/admin/use-cases/uc.admin.suspend-tenant.md) (the use-case this ADR
  backs).
- [ADR 0004](0004-session-tokens.md) (the account-global session-family model that ruled out a session-revoke
  here — sessions carry no `tenantId`).
