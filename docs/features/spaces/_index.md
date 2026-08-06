# Feature: Spaces (scopes)

Where content lives and who may access it: the **scopes** a user holds — the shared **tenant** root, optional
**groups**, and a per-tenant **personal** space — and how each is created, listed and **opened** on the
device. **Operator-blind throughout** — the server stores only the opaque scope row (id / kind / tenant) and
the **ciphertext grant** (the scope key sealed to a member's identity); it never holds a scope key.

- **Cross-cutting:** [Scopes & Tenancy](../../crosscutting/scopes-and-tenancy.md) (scope kinds, grants =
  membership, host-based tenant resolution), [Account & Key Model](../account/enablers/en.account.key-model.md)
  (identity key opens grants), [Security Model](../../crosscutting/security-model.md) (operator-blind).
- **Decisions:** [ADR 0002](../../decisions/0002-personal-scope-per-tenant.md) (personal scope is
  tenant-bound, one per member per tenant), [ADR 0007](../../decisions/0007-membership-role-grant-relation.md)
  (membership = role + grant), [ADR 0006](../../decisions/0006-cold-tenant-scope-birth.md) (cold scope
  birth), [ADR 0017](../../decisions/0017-crypto-primitives-and-post-quantum-strategy.md) (the hybrid-KEM grant),
  [ADR 0014](../../decisions/0014-group-scopes-and-governance.md) (group scopes + governance — private even
  from the tenant owner), [ADR 0015](../../decisions/0015-operator-blind-readable-urls.md) (readable URLs in
  the `#fragment`), [ADR 0019](../../decisions/0019-token-less-tenant-home.md) (token-less tenant home),
  [ADR 0020](../../decisions/0020-grant-distribution-invite-wrap.md) (grant distribution — wrap a scope key to
  another member), [ADR 0023](../../decisions/0023-per-tenant-member-identity.md) (per-tenant member identity —
  username + sealed real names).
- **Note:** a **scope** is the unit content is keyed on (opaque `scopeId` + `kind`); a **grant** is how a
  member holds its key. The tenant's root scope is born at tenant activation; this feature adds the
  **personal** scope and the general scope-identity model.

## Enablers

- ★ [en.spaces.tenant-scope](enablers/en.spaces.tenant-scope.md) — **refined** (the shared root TENANT scope
  **opened + entered** on the device — no birth, it already exists from activation; the token-less default home;
  [ADR 0019](../../decisions/0019-token-less-tenant-home.md))
- ★ [en.spaces.personal-scope](enablers/en.spaces.personal-scope.md) — **built** (the per-tenant PERSONAL
  scope born + opened, client-driven & operator-blind; the keyed container the content flows fill)
- ★ [en.spaces.group-scope](enablers/en.spaces.group-scope.md) — **refined** (a shared, **named** GROUP scope
  born by its owner + opened — confidential even from the tenant owner;
  [ADR 0014](../../decisions/0014-group-scopes-and-governance.md), building in cr-0032)
- [en.spaces.member-identity](enablers/en.spaces.member-identity.md) — **built** (per-tenant `username`
  unique-in-tenant + `firstName`/`lastName` **sealed** under `K_tenant`; email the only global key, owner-only in
  the directory; [ADR 0023](../../decisions/0023-per-tenant-member-identity.md))
- [en.spaces.scope-key-rotation](enablers/en.spaces.scope-key-rotation.md) — **built** (rotate a scope's content
  key **forward into a new epoch**, granting it only to the remaining members, so a removed member reads
  pre-removal content but never post-removal — no re-encryption; the substrate member-removal builds on;
  [ADR 0024](../../decisions/0024-scope-key-rotation.md))

## Use-cases

- ★ [List & switch spaces](use-cases/uc.spaces.switch-spaces.md) — **built (web)** (tenant switcher +
  per-space `#fragment` switching; enter-tenant + Personal↔Tenant)
- ★ [Add a member to the shared tenant space](use-cases/uc.spaces.add-member.md) — **built (web)** (invite a
  person, then wrap `K_SC` to their identity — the grant-distribution primitive;
  [ADR 0020](../../decisions/0020-grant-distribution-invite-wrap.md); delivery + onboarding + a mail-only invite,
  [ADR 0055](../../decisions/0055-mail-is-a-precondition-not-a-capability.md))
- [Manage pending member invites](use-cases/uc.spaces.manage-member-invites.md) — **built** (the owner lists /
  re-issues / revokes the member invites add-member mints; owner-gated, additive-safe)
- [Remove a member from a tenant](use-cases/uc.spaces.remove-tenant-member.md) — **web done (cr-0091)** (TenantOwner
  cuts a member's access to shared + group content immediately, applies the **group orphan rule** (hand over or
  wind down the groups they leave), and gives them a grace window to read their own memories before erase;
  co-shared groups rotate `K_SC` via any remaining key-holder; [ADR 0014](../../decisions/0014-group-scopes-and-governance.md) §4)
- Create / share with a group — **in progress** ([ADR 0014](../../decisions/0014-group-scopes-and-governance.md)):
  **G1** create + open + browse (`en.spaces.group-scope`, cr-0032); **G2** member-add — per-tenant username
  search ([en.spaces.member-identity](enablers/en.spaces.member-identity.md)) + the
  [ADR 0020](../../decisions/0020-grant-distribution-invite-wrap.md) grant-wrap, direct-add default with an
  optional consent-first toggle (derived name-key for verifiable informed consent, ADR 0027); **G3** member management (promote/
  demote, multiple owners + last-owner guard), grace-delete + orphan rule, `K_SC` rotation
- [Add a member to a group](use-cases/uc.spaces.add-group-member.md) — **web done (cr-0086)** (GroupOwner adds an
  existing tenant member by **username**; a **grant-less** membership is recorded and `K_SC` is delivered later by
  grant-delivery — direct-add is the default; [ADR 0014](../../decisions/0014-group-scopes-and-governance.md) §4/§5,
  [ADR 0020](../../decisions/0020-grant-distribution-invite-wrap.md))
- [Consent before being added to a group](use-cases/uc.spaces.consent-group-add.md) — **web done (cr-0094, cr-0096)** (the
  optional account-global "ask before adding me" opt-in: an add becomes a proposal — a soft membership
  grant-delivery **skips** + a wrapped **derived name-key** (`group-name-key` context) so the invitee reads the
  **canonical** name — so `K_SC` is gated **before** the wrap and the owner can't show a divergent name; accept
  delivers, decline drops silently; [ADR 0014](../../decisions/0014-group-scopes-and-governance.md) §5,
  [ADR 0027](../../decisions/0027-group-invite-name-shared-key.md))
- [Rename a group](use-cases/uc.spaces.rename-group.md) — **web done (cr-0097)** (owner re-seals the name under
  `K_name` from the **current** epoch and bumps `nameEpoch`, so a rename is **forward-secret** — a removed member
  can't read the new name; name-only, no `K_SC` rotation; server rejects a stale `nameEpoch`;
  [ADR 0027](../../decisions/0027-group-invite-name-shared-key.md))
- [Space icon](use-cases/uc.spaces.set-space-icon.md) — **refined** (the owner gives a space a **face**: one
  square image, framed + re-encoded to a fixed box + **padded to a constant length** on device, then sealed under
  `K_icon` derived from the **current** epoch → `sealedIcon` + `iconEpoch` on the scope row — a **field**, not a
  blob. The tenant's *name* stays cleartext because at creation **no key exists**
  ([ADR 0006](../../decisions/0006-cold-tenant-scope-birth.md)); an icon is chosen *after* activation, so
  [ADR 0023](../../decisions/0023-per-tenant-member-identity.md)'s *only-the-plaintext-the-mechanism-requires*
  rule seals it — making it the **first sealed content on a TENANT row**. Rendered cross-tenant off the
  account-scoped `my-tenants` (which widens to carry each tenant's own grant,
  [en.spaces.tenant-scope](enablers/en.spaces.tenant-scope.md)); initial stays the fallback. **v1 = TENANT**;
  group is the identical field one level down)
- [Manage a group's members](use-cases/uc.spaces.manage-group-members.md) — **web done (cr-0088, cr-0089)** (the
  GroupOwner runs the roster: **promote/demote** co-owners (multiple owners + a last-owner guard) and **remove** a
  member — removal rotates the content key forward ([en.spaces.scope-key-rotation](enablers/en.spaces.scope-key-rotation.md))
  so they lose access to new content; [ADR 0014](../../decisions/0014-group-scopes-and-governance.md) §4)
- [Delete a group](use-cases/uc.spaces.delete-group.md) — **web done (cr-0090)** (owner soft-deletes into a
  **grace period** (clearly marked, still readable, any owner can restore); a scheduled operator-blind job then
  hard-purges the ciphertext + files; [ADR 0014](../../decisions/0014-group-scopes-and-governance.md) §4/§6)
- [Verify a member's identity](use-cases/uc.spaces.verify-identity.md) — **web: TOFU foundation built (cr-0098)**
  (a client **pins** each contact's identity key on first wrap — operator-blind, cross-device; a **changed** key
  is never silently trusted: the grant-delivery worker **declines**, the UI surfaces a notice + accept → re-pin,
  rotation excludes the member; [ADR 0028](../../decisions/0028-identity-authenticity-tofu-verification.md),
  [en.account.identity-pinning](../account/enablers/en.account.identity-pinning.md). The out-of-band **QR
  safety-number** ceremony, the *verified* badge, and strict-mode are the follow-up CR)
- Person / profile view — planned (`~username`, [ADR 0015](../../decisions/0015-operator-blind-readable-urls.md)):
  open a member and see the Memories **you can see** that tag them; client-side (never a server query), depends
  on a future per-Memory **participants** tag (sealed `userId`s)
- Public share of a subtree — **refined** → [Sharing](../sharing/_index.md)
  ([uc.sharing.share-public-viewer](../sharing/use-cases/uc.sharing.share-public-viewer.md), [ADR 0029](../../decisions/0029-public-viewer-share.md)):
  a per-share key, anonymous read-only link, least-privilege (only that one Memory's `K_mem`); v1 = personal-scope
