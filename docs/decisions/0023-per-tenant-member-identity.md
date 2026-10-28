# ADR 0023 — Member identity: per-tenant username + sealed real names, email is the global key

- Status: Accepted
- Date: 2026-07-08

## Context

A member is reached and displayed today only by `veyq_user.display_name` (free-form, non-unique) + `email`,
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
  the existing-account accept), uniqueness checked within the target tenant (`409 username_taken`); **changeable**
  later with a re-check in that tenant.
- **`firstName` / `lastName` — per-(user, tenant), sealed under a field key derived from `K_tenant`.** They live on the **same
  tenant-profile row** as the username. The real name is content, not operational metadata: it is **ciphertext**
  the server never decrypts. Every tenant member holds `K_tenant`, so each can decrypt any member's sealed name
  **locally** — which is where directory **search/sort over names** happens (client-side), so the server needs no
  plaintext name. The real name is **optional**, and is **offered at the tenant join itself** (alongside the
  required username): every invitee holds `K_tenant` at accept — the owner cold-birth, or a member's self-grant from
  its invite ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decision 3) — so the client seals it right there and submits it with the accept, or it is added or edited later in
  the profile (both entry points seal identically).
- **The join record** ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decision 3, built 2026-10-28). The profile row also holds `sealed_join_record`: the member's identity and
  Secret-Key proof public key with the owner-signed invite it joined through, signed by the invite key and the new
  identity, sealed under the tenant epoch the invite's grant carried (field purpose `member-join-record`, bound to the
  tenant and the member). It is written once, by the accept, never by a profile update, and served to every member on
  `ScopeMember.sealedJoinRecord` in the tenant scope; the tenant's first owner has none (the trust root). Nothing
  reads it to decide anything yet.
- **Directory visibility.** Every tenant member sees each member's `username` (plaintext) + `firstName`/`lastName`
  (locally decrypted). **Only a tenant `OWNER`** additionally sees `email` (owner correlates a pending member to
  their invite — [ADR 0007](0007-membership-role-grant-relation.md); the field is omitted for ordinary members).

## Consequences

- **`veyq_user.display_name` is removed.** Display comes from the per-tenant `username` + the sealed
  first/last; the account itself carries only `email` + keys. An account with **no** tenant membership (e.g. a
  Server Owner who is not a tenant member) has only its email as identity — fine, admin views already key off
  email.
- **A tenant-profile row carries the identity.** A per-`(user, tenant)` row holds `username` (plaintext,
  `UNIQUE(tenant_id, username)`) + the sealed name blob (one per person per tenant, independent of how many
  scopes they're in); the member directory (`listScopeMembers`) joins it in and returns username + sealed name to
  every member and `email` only to an owner caller.
- **The sealed name follows the grant.** A member can seal first/last only once they hold `K_tenant`, and every
  invitee holds it at accept (the owner cold-birth, or a member's self-grant from its invite), so the name is offered
  and sealed **at the join** — one step, no separate profile visit. A member who skips it shows only `@username` +
  (owner-only) email until they seal it in the profile, where it stays **editable** afterward.
- **Better operator-blindness than today.** Real names move from plaintext to ciphertext; the server's plaintext
  identity surface shrinks to `email` + a per-tenant pseudonymous `username`. No global handle namespace to
  govern; tenants stay isolated (no cross-tenant handle correlation) — good for the hosted SaaS.
- **Enables the group member-add search** ([ADR 0014](0014-group-scopes-and-governance.md)): resolve a per-tenant
  `username` (unique in the tenant) → the member's identity public key → wrap `K_SC`.
- **Refines [ADR 0007](0007-membership-role-grant-relation.md):** "reach an existing user by username" is now
  concretely a per-tenant handle on the membership; email-owner-only visibility is unchanged.
- **Built by cr-0083** (the identity enabler [en.spaces.member-identity](../features/spaces/enablers/en.spaces.member-identity.md)).
