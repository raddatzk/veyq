# ADR 0007 — Membership is the (user, scope, role, grant) relation

- Status: Accepted
- Built: yes
- Date: 2026-06-26
- Model: [scopes-and-tenancy](../crosscutting/scopes-and-tenancy.md)

## Context

Tenant activation creates the **first** membership and the **first** grant, so the membership model must be
settled first. Open questions: how do we model the **management role** vs.
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
  "last-owner guard"). An owner is an **accepted** member: an unaccepted consent proposal is never made one, and no
  owner count includes it — counted, it let the last accepted owner step down, and a decline then left the group with
  none (inbox #597). A scope is orphaned only when its sole owner is taken out — removed from the tenant or
  erased — and that is handled by a rule, not an operator power: a group passes to its oldest other accepted member,
  its open proposals expiring, or is tombstoned when no accepted member is left
  ([ADR 0014](0014-group-scopes-and-governance.md) §6), and an ownerless tenant waits for the Server Owner to invite one
  of its members to own it, a role that moves no key
  ([ADR 0101](0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md) decision 9).
- **The relation is a *shared* primitive, not a feature's.** `MembershipEntity`, the read-only membership /
  owner guards (`isScopeOwner` · `ownerCount` · `memberRole` · `holdsGrant`) and the one access gate every
  scope-addressed operation calls (`ScopeAccess`, [uc.spaces.scope-access](../features/spaces/use-cases/uc.spaces.scope-access.md))
  live in **`core/membership/`** (a kernel package) — a query-only module that every feature (content · media · sharing · spaces ·
  admin · tasks) depends on, rather than each feature re-querying the table or importing the entity from another
  feature's slice. Only the last-owner **policy** — the decision to *reject* a remove/demote and raise `LastOwner` —
  stays in the feature handler that owns the action. The gate reads a scope's `deletedAt` and a tenant's `status`,
  so the scope and tenant rows are query-only entities of that kernel too; it never depends back on a feature, and
  no cycle forms. A guard every feature re-implements drifts copy by copy, which is why it has one home.
- **`tenantId` groups; belonging and every role check key on `scopeId`.** A membership row carries both `tenantId`
  (which tenant it belongs to) and `scopeId` (which scope it grants a role on). Because every scope birth mints its
  creator an `OWNER` row, a role check filtered by `tenantId` **alone** answers true for any member who owns *any*
  scope in the tenant — it degrades to `isMember`. So **tenant-level authority keys on the tenant root scope**
  (`scopeId == tenantId`, cr-0026): `isTenantOwner(u, t) = isScopeOwner(u, t)`. **Belonging keys on the root scope too:** a caller belongs to a tenant when they hold an
  *accepted* membership on its root scope, and tenant-addressed operations ask the same gate on that scope. The
  `tenantId` column only **groups** a user's rows (the space list, the removal cascade) and never authorizes: a
  member removed from the tenant keeps rows on their own spaces for the grace window, and a proposed member holds
  an unaccepted row, so a `tenantId`-only belonging check counted both — and a `tenantId`-only *owner* gate is
  functionally a belonging check, a privilege escalation.
- **Per scope kind:**
  - **Server role** is a single global column per user (`veyq_user.server_role`, `SERVER_OWNER` / `MEMBER`) — a user
    belongs to exactly one server. **Multiple `SERVER_OWNER`s are allowed** (subject to the guard above); a flow
    to mint a second is a later use-case. A server-scope **grant** is added only if the operator domain is later
    modelled as a server-scope (a separate, parked idea).
  - **Tenant and Group memberships are n-m** (user × tenant / user × group-within-tenant), each with a
    role + a grant to that scope's key. Group roles are **OWNER / MEMBER** only — no `ADMIN`
    ([ADR 0014](0014-group-scopes-and-governance.md)).
  - **Personal** scope holds one membership, the member's own: role `OWNER` with its self-grant, and nobody else is
    ever added.
- **The first tenant membership (the owner's) is born at activation** (cold `TENANT`-scope birth,
  [ADR 0006](0006-cold-tenant-scope-birth.md)); members join the same relation later through an invite.
  How a grant is written and checked is [keys, grants and trust](../crosscutting/keys-grants-and-trust.md#grants-who-wraps-a-scope-key-to-whom).
- **Reaching a person:** **email** is used only for an **outsider** with no account (the tenant-owner
  invite); an **existing user** is reached by **username** — a **per-tenant, unique-within-tenant** handle on the
  member's tenant profile ([ADR 0023](0023-per-tenant-member-identity.md)), fully in-app. This keeps person lookup
  non-enumerable (bounded to the tenant's own members).
- **Owner-email visibility is a role rule, not crypto:** it is server-visible operational metadata,
  returned only to roles that need it (a `SERVER_OWNER`, or the tenant's own `OWNER`) and omitted for ordinary
  members — who see each other's per-tenant `username` + sealed real name instead
  ([ADR 0023](0023-per-tenant-member-identity.md)).

## Consequences

- **Consistent with [ADR 0006](0006-cold-tenant-scope-birth.md):** forced reassignment grants a role only;
  a key-preserving handoff still needs a grant from an existing key-holder.
- **Server role stays a single column** for now; promoting/minting additional `SERVER_OWNER`s is deferred.
- **Email exposure is minimised** — needed only to reach outsiders; in-system invites use a username.
- **Rejected — n-m server membership:** a user belongs to exactly one server, so the server role is 1-1.
- **Rejected — per-field email encryption-at-rest as part of this decision:** at-rest encryption of
  server-visible PII (under an operator-held key) is a **separate crosscutting** concern — it is *not*
  operator-blindness (the server must decrypt to send mail) and must cover **all** server-visible emails
  (incl. `veyq_user.email`), not just the tenant owner email.
