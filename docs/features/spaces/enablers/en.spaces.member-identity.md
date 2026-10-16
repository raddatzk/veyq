# Enabler: Member identity (per-tenant username + sealed real names)

- **ID:** en.spaces.member-identity
- **Type:** **Enabler** — structural foundation, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
  It gives every membership a **findable handle + a human name**; the value reaches users through the directory,
  the group member-add search, editing your own identity in [uc.spaces.edit-profile](../use-cases/uc.spaces.edit-profile.md),
  and (later) the person/profile view it feeds.
- **Provides:** a member's **identity within a tenant** — a per-tenant **`username`** (unique in the tenant,
  plaintext, a pseudonymous handle decoupled from login) plus **`firstName` / `lastName` sealed under a field key derived from `K_tenant`**
  (operator-blind real name), and the **directory visibility rule** (every member sees username + names; only a
  tenant `OWNER` sees `email`). Instance-wide uniqueness stays with **`email`** alone
  ([ADR 0023](../../../decisions/0023-per-tenant-member-identity.md)).
- **Serves (use-cases/enablers):** the member directory of [uc.spaces.add-member](../use-cases/uc.spaces.add-member.md),
  editing your own handle and name in [uc.spaces.edit-profile](../use-cases/uc.spaces.edit-profile.md),
  the **group member-add username search** ([ADR 0014](../../../decisions/0014-group-scopes-and-governance.md), G2),
  and the person/profile view (built at `#people/<userId>`, ADR 0083, not the `~username` of
  [ADR 0015](../../../decisions/0015-operator-blind-readable-urls.md)).
- **Depends on:** [uc.admin.create-tenant](../../admin/use-cases/uc.admin.create-tenant.md) +
  [uc.spaces.add-member](../use-cases/uc.spaces.add-member.md) (the tenant-join flows where a username is chosen),
  `uc.account.unlock` (the identity that seals the name),
  [en.spaces.tenant-scope](en.spaces.tenant-scope.md) (the `K_tenant` the name's field key derives from).
- **Preconditions:** An **authenticated** caller joining or belonging to a tenant. Sealing the real name
  additionally needs an **unlocked, granted** member (holds `K_tenant`).
- **Postconditions (success):** The caller's per-`(user, tenant)` profile row carries a tenant-unique `username`
  (plaintext) and, once they hold `K_tenant`, a `firstName`/`lastName` blob **sealed under a field key derived from `K_tenant`** (`member-name`,
  [en.platform.field-key](../../platform/enablers/en.platform.field-key.md)). The
  directory returns username + (locally decrypted) names to every member and `email` only to an owner. The server
  never saw the real name.

## What's new vs. today

Today a member is `veyq_user.display_name` (free-form, non-unique) + `email`, both plaintext on the **global
account**. This enabler moves identity onto a **per-`(user, tenant)` profile row** (one per person per tenant,
independent of how many scopes they're in — so it is **not** a per-membership column, which would collide with a
member's own group rows) and splits it by sensitivity:

1. **`username` — per-(user, tenant), `UNIQUE(tenant_id, username)`, plaintext.** A chosen handle, charset-
   constrained (`[a-z0-9_.-]`-ish, no whitespace), **not a login credential** (email is). Chosen at **every**
   tenant join (register-and-accept *and* existing-account accept), uniqueness checked in the target tenant
   (`409 username_taken`); changeable later with a re-check.
2. **`firstName` / `lastName` — per-(user, tenant), sealed under a field key derived from `K_tenant`** (same profile
   row, a fresh salt per write). Real names are
   content, not operational metadata — ciphertext the server never decrypts. Every tenant member holds
   `K_tenant`, so each decrypts any member's name **locally**; directory **search/sort over names is
   client-side**.
3. **`veyq_user.display_name` is removed.** The account carries only `email` + keys; display is the per-tenant
   username + sealed names.

## Main flow — build your tenant identity on join

1. In the tenant-join flow the member picks a **username** (required, plaintext, on device) **and optionally a
   first/last name** → the server checks the username is free **in this tenant** (`UNIQUE(tenant_id, username)`);
   a clash is `409 username_taken`.
2. Once the member **holds `K_tenant`** (immediately for an owner's cold-birth or any accept that seals the grant
   on-device; after grant-delivery for a grant-less member — [ADR 0021](../../../decisions/0021-operator-blind-background-tasks.md)),
   the client **seals** `firstName` + `lastName` under `K_tenant` and stores the opaque blob on the profile row —
   **at the join itself** when the key is already present (submitted with the accept), otherwise later from the
   profile. Both entry points seal identically; the name stays editable in the profile.
3. Any tenant member reading the directory gets each member's `username` (plaintext) + the sealed name blob,
   **decrypts the name locally**, and can search/sort. A tenant **OWNER** additionally gets `email`.

## Alternate / exception flows

- **E1 — username taken in the tenant:** `409 username_taken`; the member picks another. Uniqueness is
  per-tenant, so the same handle may be free in a different tenant.
- **E2 — name before the grant:** a grant-less "awaiting access" member has no `K_tenant` yet, so cannot seal a
  name — the join's optional name field is **skipped** (username only), and the directory shows just `@username`
  (+ owner-only email) until their grant lands and they seal it from the profile.
- **E3 — username change:** re-checked for uniqueness in the tenant; re-slugs the member's `~username` path (old
  bookmarks may miss — [ADR 0015](../../../decisions/0015-operator-blind-readable-urls.md)).

## Quality notes

- **Operator-blind, tighter than before.** Real names become **ciphertext**; the server's plaintext identity
  surface shrinks to `email` + a per-tenant pseudonymous `username`. No global handle namespace; tenants stay
  isolated (no cross-tenant handle correlation).
- **Decoupled from auth.** The username touches no crypto/login path (email + password + secret key are the
  credentials, [ADR 0003](../../../decisions/0003-operator-blind-password-auth.md)), so it is freely chosen and
  changeable.
- **Email minimised.** Owner-only in the directory ([ADR 0007](../../../decisions/0007-membership-role-grant-relation.md));
  ordinary members correlate by handle + name, not email.

> **No end-user section.** As an enabler this has no standalone "what this means for you" story — the value
> reaches the user through the directory, group member-add and [uc.spaces.edit-profile](../use-cases/uc.spaces.edit-profile.md),
> which it serves.

## Related

- Feature: [Spaces (scopes)](../_index.md)
- Concepts: [Scopes & Tenancy](../../../crosscutting/scopes-and-tenancy.md),
  [Security Model](../../../crosscutting/security-model.md)
- Decisions: [ADR 0023](../../../decisions/0023-per-tenant-member-identity.md) (the identity model),
  [ADR 0007](../../../decisions/0007-membership-role-grant-relation.md) (membership = role + grant; email
  visibility), [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) (the group member-add search
  this feeds), [ADR 0015](../../../decisions/0015-operator-blind-readable-urls.md) (`~username` routing)
