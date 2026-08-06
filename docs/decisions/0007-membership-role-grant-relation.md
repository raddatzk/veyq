# ADR 0007 — Membership is the (user, scope, role, grant) relation

- Status: Accepted
- Date: 2026-06-26

## Context

Tenant activation (cr-0010, Slice B) creates the **first** membership and the **first** grant, so the
membership model must be settled first. Open questions: how do we model the **management role** vs.
**content access**? Is server-level membership n-m like tenants? How is a person **reached** when inviting
(email vs. username)? Who may see a tenant's **owner email**? See the
[Scopes & Tenancy](../crosscutting/scopes-and-tenancy.md) model and [ADR 0006](0006-cold-tenant-scope-birth.md).

## Decision

**Membership is a relation between a user and a scope, carrying both axes in one row:** a server-enforced
**management role** and a client-enforced **grant** (the scope's **current content-epoch key** sealed to the
member's identity public key — `asymWrap(memberIdentityPub, E_latest, "scope-grant")`,
[ADR 0024](0024-scope-key-rotation.md)). `K_SC` is **symmetric** (a random 32-byte epoch key); the grant is
asymmetric *transport* of it, and its plaintext **is** `E_latest` — no key sits between the grant and `K_SC`.

- **Role gates management; grant gates content.** A role without a grant is a hollow title; promoting an
  existing grant-holder is a pure role change (no key moves), and demoting one is too.
- **Ownership cardinality — many owners, never zero.** At **every** level (Server, Tenant, Group) a scope may
  have **multiple owners** and **must keep at least one**: removing or demoting the **last** owner is rejected (a
  "last-owner guard"). Orphaning is thus only possible when a sole owner *leaves* — handled by an automatic rule,
  not an admin power ([ADR 0014](0014-group-scopes-and-governance.md)).
- **The relation is a *shared* primitive, not a feature's.** `MembershipEntity` and the read-only membership /
  owner guards (`isScopeMember` · `isScopeOwner` · `ownerCount` · `isMember` · `memberRole` · `holdsGrant`) live
  in **`shared/membership/`** — a query-only leaf module that every feature (content · media · sharing · spaces ·
  admin) depends on, rather than each feature re-querying the table or importing the entity from another feature's
  slice. Only the last-owner **policy** — the decision to *reject* a remove/demote and raise `LastOwner` — stays in
  the feature handler that owns the action; the shared module never depends back on `ScopeEntity` / `TenantEntity`,
  so `shared` stays a leaf and no cycle forms. (The tell that this belonged in `shared`: the guard was reimplemented
  verbatim across 5 stores + 2 slice cycles — audit P3a.)
- **`tenantId` expresses belonging; every role check keys on `scopeId`.** A membership row carries both `tenantId`
  (which tenant it belongs to) and `scopeId` (which scope it grants a role on). Because every scope birth mints its
  creator an `OWNER` row, a role check filtered by `tenantId` **alone** answers true for any member who owns *any*
  scope in the tenant — it degrades to `isMember`. So **tenant-level authority keys on the tenant root scope**
  (`scopeId == tenantId`, cr-0026): `isTenantOwner(u, t) = isScopeOwner(u, t)`, mirroring `tenantOwnerCount =
  ownerCount(t)`. The `tenantId` column is for *belonging* (`isMember`), never *authorization*. (cr-0163 fixed
  three copies of a `tenantId`-only owner gate that were functionally `isMember` — a privilege escalation.)
- **Per scope kind:**
  - **Server role** is a single global column per user (`veyq_user.server_role`, `SERVER_OWNER` / `MEMBER`) — a user
    belongs to exactly one server. **Multiple `SERVER_OWNER`s are allowed** (subject to the guard above); a flow
    to mint a second is a later use-case. A server-scope **grant** is added only if the operator domain is later
    modelled as a server-scope (a separate, parked idea).
  - **Tenant and Group memberships are n-m** (user × tenant / user × group-within-tenant), each with a
    role + a grant to that scope's key. Group roles are **OWNER / MEMBER** only — no `ADMIN`
    ([ADR 0014](0014-group-scopes-and-governance.md)).
  - **Personal** scope is self-held — no membership row beyond the member's own grant.
- **The first tenant membership (the owner's) is born at activation** (cold `TENANT`-scope birth, cr-0010);
  members extend the same relation later via the invite-wrap.
- **Reaching a person:** **email** is used only for an **outsider** with no account (the tenant-owner
  invite); an **existing user** is reached by **username** — a **per-tenant, unique-within-tenant** handle on the
  membership ([ADR 0023](0023-per-tenant-member-identity.md)), fully in-app. This keeps person lookup
  non-enumerable (bounded to the tenant's own members).
- **Owner-email visibility is a role rule, not crypto:** it is server-visible operational metadata,
  returned only to roles that need it (a `SERVER_OWNER`, or the tenant's own `OWNER`) and omitted for ordinary
  members — who see each other's per-tenant `username` + sealed real name instead
  ([ADR 0023](0023-per-tenant-member-identity.md)).

## Consequences

- **cr-0010 introduces the membership table + the owner's grant.** Member/group invites (by username,
  invite-wrap) are a later use-case that extends the same relation.
- **The entity + guard are shared, not admin-hosted (P3a).** Relocating `MembershipEntity` + the guard queries
  from `features/admin/tenant/` to `shared/membership/` turns 8 cross-slice imports of the admin slice into a
  legal `features → shared` dependency, and collapses the 5 verbatim `isMember` copies + the twin owner-count
  guard (spaces + admin) onto one shared primitive; the last-owner *policy* stays per-handler. Realised by a
  follow-up change-request (P3a).
- **Consistent with [ADR 0006](0006-cold-tenant-scope-birth.md):** forced reassignment grants a role only;
  a key-preserving handoff still needs a grant from an existing key-holder.
- **Server role stays a single column** for now; promoting/minting additional `SERVER_OWNER`s is deferred.
- **Email exposure is minimised** — needed only to reach outsiders; in-system invites use a username.
- **Rejected — n-m server membership:** a user belongs to exactly one server, so the server role is 1-1.
- **Rejected — per-field email encryption-at-rest as part of this decision:** at-rest encryption of
  server-visible PII (under an operator-held key) is a **separate crosscutting** concern — it is *not*
  operator-blindness (the server must decrypt to send mail) and must cover **all** server-visible emails
  (incl. `veyq_user.email`), not just the tenant owner email.
