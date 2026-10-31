# ADR 0023 — Member identity: per-tenant username + sealed real names, email is the global key

- Status: Accepted
- Built: yes
- Date: 2026-07-08
- Model: [en.spaces.member-identity](../features/spaces/enablers/en.spaces.member-identity.md)

## Context

A member was reached and displayed only by a display name on the account (free-form, non-unique) + `email`,
both plaintext on the global account ([ADR 0007](0007-membership-role-grant-relation.md)). Two forces now push
on that:

- **Group member-add needs a stable, findable handle** to pick an existing tenant member
  ([ADR 0014](0014-group-scopes-and-governance.md) "add a member = username search"). A non-unique display name
  can't be that handle.
- **Real names are more sensitive than a chosen handle.** The operator-blind stance stores only "low-sensitivity
  operational metadata" ([security model](../crosscutting/security-model.md)); putting members' real first/last
  names in server-visible plaintext widens the PII the operator sees more than we want.

The open questions: is a username a *global* handle or *per-tenant*? What must stay plaintext for the server to
do its job, and what can be sealed? Who sees what in the member directory?

Considered and rejected during refinement:

- **A single global unique username (`@kevin` across the whole instance).** Rejected. A veyq account is
  instance-global (one `veyq_user`, many tenants), so a global handle forces *all* tenants on a shared instance
  into one namespace — tenant A's "kevin" blocks tenant B's. Bad for a multi-tenant SaaS; unnecessary for a
  family server. Discord's model (global handle + per-server nickname) only works because a Discord account is a
  single global identity; ours is deliberately tenant-scoped.
- **Discord-style discriminator (`kevin#4823`).** Rejected — it solves *namespace contention at millions of
  users*, a problem veyq (small tenants, search **within** a tenant) does not have; and Discord itself retired it
  in 2023. It buys clunky-to-share handles for no benefit here.
- **Seal the username too (everything encrypted, no server uniqueness).** Rejected — server-enforced uniqueness
  **requires** the username in plaintext (the server must see it to check "is this handle free in this tenant?").
  Sealing it means no server-guaranteed uniqueness, which defeats the point of a handle.
- **Store real names plaintext for server-side name search/sort.** Rejected — search can be **local** (a tenant
  member already holds `K_tenant` and decrypts the directory on device), so the server never needs the names in
  the clear. Only the plaintext the mechanism *requires* stays plaintext.

## Decision

**Identity is per-tenant: `email` is the only instance-global key; `username` is per-tenant and unique within the
tenant (plaintext); first + last name are per-tenant and sealed. A member builds their identity when they enter
a tenant.**

- **`email` — global, plaintext, the account key.** Login is by email ([ADR 0003](0003-operator-blind-password-auth.md));
  it is the single instance-global unique identifier. It stays server-visible operational metadata, **owner-only**
  in the directory (see below).
- **`username` — per-(user, tenant), unique *within the tenant*, plaintext.** It is a per-**(user, tenant)**
  attribute — **one handle per person per tenant** — stored on a **tenant-profile row keyed `(user, tenant)`**,
  `UNIQUE(tenant_id, username)`; **not** on the account and **not** per-scope (a member holds several scope
  memberships in one tenant, so a per-membership column would collide with their own group rows). The same
  account can be `@kevin` in one tenant and `@kr` in another. It is a chosen **pseudonymous handle, decoupled
  from auth** (email + password + secret key are the
  credentials; the username touches no crypto path). Charset-constrained like a domain/slug
  (`[a-z0-9_.-]`-ish, no whitespace). **Chosen at every tenant join** (both the cold-birth register-and-accept and
  the existing-account accept), uniqueness checked within the target tenant; **changeable** later with a re-check in
  that tenant.
- **`firstName` / `lastName` — per-(user, tenant), sealed under a field key derived from `K_tenant`.** They live on the **same
  tenant-profile row** as the username. The real name is content, not operational metadata: it is **ciphertext**
  the server never decrypts. Every tenant member holds `K_tenant`, so each can decrypt any member's sealed name
  **locally** — which is where directory **search/sort over names** happens (client-side), so the server needs no
  plaintext name. The real name is **optional**, and is **offered at the tenant join itself** (alongside the
  required username): every invitee holds `K_tenant` at accept — the owner cold-birth, or a member's self-grant from
  its invite ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decision 3) — so the client seals it right there and submits it with the accept, or it is added or edited later in
  the profile (both entry points seal identically).
- **The epoch of a sealed name.** The name, and the picture of [ADR 0084](0084-a-members-profile-picture-is-a-sealed-attribute-of-their-tenant-profile.md),
  are sealed under the tenant epoch ([ADR 0024](0024-scope-key-rotation.md)) current when their member saved them, and
  the profile row records no epoch. **Every reader opens them under every tenant epoch key it holds, newest first** — the
  join record's rule. So a rotation, which securing an
  account causes in every tenant it is in ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decision 8), leaves every name and picture readable to every member: a grant at the current epoch peels the chain down
  to `E_0`, a newcomer's included. Nothing opens under a key the reader does not hold, so no one reads a name their
  membership did not already give them. **The member's own client moves its name and picture forward** to the current
  epoch when it enters the tenant: opened and sealed again, written only while the stored value is still the one it
  opened, so a save from another device in between wins
  ([en.spaces.member-identity § The epoch of a sealed field](../features/spaces/enablers/en.spaces.member-identity.md#the-epoch-of-a-sealed-field)).
  - *Why on entry, not on the next save:* a name is set once and rarely saved again, and every failed trial open is
    paid again on every directory read, for every member — without the move, each rotation would add one for good.
  - *Why no epoch beside the blob:* the server could hold one (an epoch number is opaque metadata it already holds per
    scope), but it would check nothing, and it would cost a field on the profile's writes, the accepts and both reads, and a
    migration whose existing rows would need the trial open anyway. With the move, the trial is one open in the steady
    state.
- **The join record and the verifications** live beside the profile
  ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decisions 3 and 12; [keys, grants and trust § The anchor, per tenant](../crosscutting/keys-grants-and-trust.md#the-anchor-per-tenant)).
- **Directory visibility.** Every tenant member sees each member's `username` (plaintext) + `firstName`/`lastName`
  (locally decrypted). **Only a tenant `OWNER`** additionally sees `email` (owner correlates a pending member to
  their invite — [ADR 0007](0007-membership-role-grant-relation.md); the field is omitted for ordinary members).

## Consequences

- **The account carries no display name.** Display comes from the per-tenant `username` + the sealed
  first/last; the account itself carries only `email` + keys. An account with **no** tenant membership (e.g. a
  Server Owner who is not a tenant member) has only its email as identity — fine, admin views already key off
  email.
- **A tenant-profile row carries the identity.** A per-`(user, tenant)` row holds `username` (plaintext,
  `UNIQUE(tenant_id, username)`) + the sealed name blob (one per person per tenant, independent of how many
  scopes they're in); the member directory joins it in and returns username + sealed name to every member and
  `email` only to an owner caller.
- **The sealed name follows the grant.** A member can seal first/last only once they hold `K_tenant`, and every
  invitee holds it at accept (the owner cold-birth, or a member's self-grant from its invite), so the name is offered
  and sealed **at the join** — one step, no separate profile visit. A member who skips it shows only `@username` +
  (owner-only) email until they seal it in the profile, where it stays **editable** afterward.
- **Better operator-blindness than today.** Real names move from plaintext to ciphertext; the server's plaintext
  identity surface shrinks to `email` + a per-tenant pseudonymous `username`. No global handle namespace to
  govern; tenants stay isolated (no cross-tenant handle correlation) — good for the hosted SaaS.
- **Enables the group member-add search** ([ADR 0014](0014-group-scopes-and-governance.md)): resolve a per-tenant
  `username` (unique in the tenant) → the member's identity public key → wrap `K_SC`.
- **Refines [ADR 0007](0007-membership-role-grant-relation.md):** "reach an existing user by username" is concretely a
  per-tenant handle on the member's tenant profile; email-owner-only visibility is unchanged.
