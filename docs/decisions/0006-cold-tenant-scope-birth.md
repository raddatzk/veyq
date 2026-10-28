# ADR 0006 — A tenant's root scope is born cold by the invited owner

- Status: Accepted
- Built: yes
- Date: 2026-06-26
- Model: [scopes-and-tenancy](../crosscutting/scopes-and-tenancy.md)
- Replaced in part: Consequences (the operator never selects a successor) by ADR 0101 (built)

## Context

A tenant is created by a **Server Owner**, but the Server Owner must stay **operator-blind**: they may
not hold any content key for the tenant. Tenant creation therefore cannot follow the documented
**invite-wrap** onboarding (see [Scopes & Tenancy](../crosscutting/scopes-and-tenancy.md)), because the
invite-wrap presupposes an **existing key-holder** who reseals the scope key to the invitee.
At the moment a tenant is created **no one holds its scope key** — it does not exist yet.

The creation flow (see [uc.admin.create-tenant](../features/admin/use-cases/uc.admin.create-tenant.md))
is two-party and asynchronous: the Server Owner enters `{ tenant name, domain, owner email }`, the
server creates a **`PENDING`** tenant and emails an invite; a separate person accepts and becomes the
**Tenant Owner**.

## Decision

The tenant's root `TENANT` scope is **born cold by the invited owner at acceptance** — not transferred
from anyone.

- Server Owner's creation act is **purely structural**: domain↔tenant mapping, a `PENDING` tenant, and
  a **single-use invite token** (a *bootstrap secret*, not a key). The server holds **no keys**.
- The invited owner establishes an identity (log in if the email is an existing global user, otherwise
  register cold), then **generates the `TENANT` scope key `K_SC` cold** and seals it as the **first grant to
  their own identity public key**. Uploading `{ scopeId, grant }` (with proof of the bootstrap secret) flips
  the tenant to **`ACTIVE`**.
- The **invite token gates acceptance only**; it transports no content key, so the server may hold it
  without breaking operator-blindness.

## Consequences

- **Server Owner is never a key-holder.** Operator-blindness holds by construction: there is no point in
  the flow at which the operator could obtain a tenant content key.
- **A second cold-birth path exists**, parallel to `setup-server`'s cold **account** birth — here it is a
  cold **scope** birth. Both are roots of a key tree with no upstream wrap; both are distinct from
  invite-wrap, which is for adding members to a scope that **already has** a key-holder.
- **`PENDING` tenants hold no content.** A tenant is a reserved domain mapping until its owner accepts;
  the scope key only comes into being at acceptance, so no content can exist before then.
- **Tenant name + domain are operational cleartext.** At creation no key exists under which the name
  could be encrypted, and host-based routing needs the domain server-visible anyway.
- **Rejected alternative — Server Owner pre-creates the scope key:** would require the operator to hold
  (even transiently) the tenant's content key, breaking operator-blindness. Rejected outright.
- **Rejected alternative — invite-wrap from the Server Owner:** impossible, as the Server Owner holds no
  scope key to reseal.
- **The operator boundary follows directly.** Because content ownership is the scope-key grant (never an
  operator-assignable role), a Server Owner can affect a tenant's **availability** (deactivate it, block
  its owner) but never its **confidentiality**.
- **Role vs. grant.** The owner *role* is server-side management and is **hollow without a grant** (no
  read, and no invite — the invite-wrap needs the scope key). Therefore: promoting an existing
  **grant-holding** member to owner is a full handoff with **no key transfer**; promoting a non-key-holder
  (or the operator) yields a powerless title. This closes the "operator smuggles itself in as owner" path
  by construction.
- **The operator never selects a successor.** Operator-blindness withholds both the key *and* the
  identity view (opaque member IDs only), so the operator cannot choose a new owner even setting crypto
  aside. Promotion is **tenant-internal**: an existing owner makes a member an owner. The one exception is a tenant
  left with no owner by an erasure: the Server Owner invites one of its members to own it, a role that moves no key
  ([ADR 0101](0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md) decision 9). This matches the
  universal E2EE pattern — Signal, Keybase, Proton, 1Password, Bitwarden, Apple ADP all keep
  recovery/succession one level *inside* (multiple admins / an org key / designated recovery contacts),
  never at the infrastructure operator.
- **Single-key-holder cliff (accepted): `solo owner gone == content gone`.** Owner removal is recoverable
  by **promoting another key-holder**; it is unrecoverable only when the owner is the **sole** key-holder
  (content cryptographically orphaned — no operator recovery, by invariant). Accepted as the owner's own
  risk; redundancy is **not forced** at activation — an owner may make a member a co-owner. Richer succession (a
  designated successor, a tenant recovery key) is deferred with the SaaS layer.
- **On-premise collapses the split.** In the common self-hosted case (one server, usually one tenant,
  self-administered) the Server Owner *is* the Tenant Owner — they invite their own email and accept. The
  operator/owner separation earns its keep only for multi-tenant/SaaS hosting of *other* people.
