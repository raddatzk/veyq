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

- ★ [en.spaces.tenant-scope](enablers/en.spaces.tenant-scope.md) — **built (web + backend)** (the shared root TENANT scope
  **opened + entered** on the device — no birth, it already exists from activation; the token-less default home;
  [ADR 0019](../../decisions/0019-token-less-tenant-home.md))
- ★ [en.spaces.personal-scope](enablers/en.spaces.personal-scope.md) — **built** (the per-tenant PERSONAL
  scope born + opened, client-driven & operator-blind; the keyed container the content flows fill)
- ★ [en.spaces.group-scope](enablers/en.spaces.group-scope.md) — **built (web + backend, cr-0032)** (a shared, **named** GROUP scope
  born by its owner + opened — confidential even from the tenant owner;
  [ADR 0014](../../decisions/0014-group-scopes-and-governance.md))
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
- ★ [Add a member to the shared tenant space](use-cases/uc.spaces.add-member.md) — **built (web)** (the invite is
  a placeholder member — an owner-signed invite key holding a grant of `K_SC`, its link mailed and sealed under a
  code the owner passes on; the invitee checks it, grants itself and writes a join record;
  [ADR 0111](../../decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) decisions 1–3, a mail-only invite,
  [ADR 0055](../../decisions/0055-mail-is-a-precondition-not-a-capability.md))
- [Edit your profile in a space](use-cases/uc.spaces.edit-profile.md) — **built (web)** (change your per-tenant
  username and your real name, sealed under a field key derived from `K_tenant` on the device; empty both name fields to remove the name;
  [en.spaces.member-identity](enablers/en.spaces.member-identity.md), [ADR 0023](../../decisions/0023-per-tenant-member-identity.md))
- [Set your profile picture in a space](use-cases/uc.spaces.set-profile-picture.md) — **built (web, backend, contract)**
  (a sealed constant-length picture on the per-tenant profile row, tenant members only, set in Settings or on your
  own profile page; [ADR 0084](../../decisions/0084-a-members-profile-picture-is-a-sealed-attribute-of-their-tenant-profile.md))
- [Manage pending member invites](use-cases/uc.spaces.manage-member-invites.md) — **built** (the owner lists /
  re-issues / revokes the member invites add-member mints; owner-gated; a re-issue is a new invite under a new
  code, and an ended invite holds no key and no grant)
- [Remove a member from a tenant](use-cases/uc.spaces.remove-tenant-member.md) — **web done (cr-0091)** (TenantOwner
  cuts a member's access to shared + group content immediately, applies the **group orphan rule** (hand over or
  wind down the groups they leave), and gives them a grace window to read their own memories before erase;
  co-shared groups rotate `K_SC` via a remaining owner; [ADR 0014](../../decisions/0014-group-scopes-and-governance.md) §4)
- [Complete a queued rotation of a space](use-cases/uc.spaces.complete-queued-rotation.md) — **web done**
  (an owner's worker completes a rotation a tenant removal or an account rotation had to queue; owners only, an
  event joins only an unclaimed job, `done` only on the holder's own commit; owners see `stalled` / `failed` on the
  space and re-queue a failed one; `done` only once the scope's re-key is)
- [Re-key a space's nodes after a rotation](use-cases/uc.spaces.rekey-after-rotation.md) — **built**
  (every rotation replaces every node key below `K_SC` and re-seals what sits under it, nothing re-encrypted in the
  store; writes wait per node; links follow the new key and stay marked for re-issue;
  [ADR 0109](../../decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md), inbox #518)
- Create / share with a group — **built (G1–G3)**, each step its own use-case below ([ADR 0014](../../decisions/0014-group-scopes-and-governance.md)):
  **G1** create + open + browse (`en.spaces.group-scope`, cr-0032); **G2** member-add — per-tenant username
  search ([en.spaces.member-identity](enablers/en.spaces.member-identity.md)) + the
  [ADR 0020](../../decisions/0020-grant-distribution-invite-wrap.md) grant-wrap, direct-add default with an
  optional consent toggle (derived name-key for verifiable informed consent, ADR 0027); **G3** member management (promote/
  demote, multiple owners + last-owner guard), grace-delete + orphan rule, `K_SC` rotation
- [Add a member to a group](use-cases/uc.spaces.add-group-member.md) — **web done (cr-0086)** (GroupOwner adds an
  existing tenant member by **username**; the owner's client wraps `K_SC` to them with the add, so the membership
  holds its grant from the start — direct-add is the default; [ADR 0014](../../decisions/0014-group-scopes-and-governance.md) §4/§5,
  [ADR 0111](../../decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) decision 4)
- [Consent before being added to a group](use-cases/uc.spaces.consent-group-add.md) — **web done (cr-0094, cr-0096)** (the
  optional account-global "ask before adding me" opt-in: an add becomes a proposal — a soft membership holding the
  grant + a wrapped **derived name-key** (`group-name-key` context) so the invitee reads the **canonical** name and
  the owner can't show a divergent name; the group is listed to them only on accept, and a decline or rescind
  queues the group's rotation; [ADR 0014](../../decisions/0014-group-scopes-and-governance.md) §5,
  [ADR 0027](../../decisions/0027-group-invite-name-shared-key.md))
- [Rename a group](use-cases/uc.spaces.rename-group.md) — **web done (cr-0097)** (owner re-seals the name under
  `K_name` from the **current** epoch and bumps `nameEpoch`, so a rename is **forward-secret** — a removed member
  can't read the new name; name-only, no `K_SC` rotation; server rejects a stale `nameEpoch`;
  [ADR 0027](../../decisions/0027-group-invite-name-shared-key.md))
- [Space icon](use-cases/uc.spaces.set-space-icon.md) — **built (web + backend; TENANT cr-space-icon, GROUP cr-group-space-icon)** (the owner gives a space a **face**: one
  square image, framed + re-encoded to a fixed box + **padded to a constant length** on device, then sealed under
  `K_icon` derived from the **current** epoch → `sealedIcon` + `iconEpoch` on the scope row — a **field**, not a
  blob. The tenant's *name* stays cleartext because at creation **no key exists**
  ([ADR 0006](../../decisions/0006-cold-tenant-scope-birth.md)); an icon is chosen *after* activation, so
  [ADR 0023](../../decisions/0023-per-tenant-member-identity.md)'s *only-the-plaintext-the-mechanism-requires*
  rule seals it — making it the **first sealed content on a TENANT row**. Rendered cross-tenant off the
  account-scoped `my-tenants` (which widens to carry each tenant's own grant,
  [en.spaces.tenant-scope](enablers/en.spaces.tenant-scope.md)); initial stays the fallback. A group carries the
  identical field one level down; a personal space never has an icon)
- [Manage a group's members](use-cases/uc.spaces.manage-group-members.md) — **web done (cr-0088, cr-0089)** (the
  GroupOwner runs the roster: **promote/demote** co-owners (multiple owners + a last-owner guard) and **remove** a
  member — removal rotates the content key forward ([en.spaces.scope-key-rotation](enablers/en.spaces.scope-key-rotation.md))
  so they lose access to new content; [ADR 0014](../../decisions/0014-group-scopes-and-governance.md) §4)
- [Delete a group](use-cases/uc.spaces.delete-group.md) — **web done (cr-0090)** (owner soft-deletes into a
  **grace period** (clearly marked, still readable, any owner can restore); a scheduled operator-blind job then
  hard-purges the ciphertext + files; [ADR 0014](../../decisions/0014-group-scopes-and-governance.md) §4/§6)
- [Act on a space — who is let in, and what everyone else is told](use-cases/uc.spaces.scope-access.md) —
  **built (backend gate + the web's suspension notice; cr-scope-access-gate)** (the one `ScopeAccess` gate every scope-, tenant-, share- and job-addressed operation calls first:
  an unaccepted proposal is not a member, a deleting group refuses every write, a suspended tenant refuses its
  members everything on any domain, and a non-member — share guests included — learns neither;
  [ADR 0042](../../decisions/0042-http-failure-projection.md), [ADR 0066](../../decisions/0066-tenant-suspension.md))
- [Trust-on-first-use, notice, and review a changed identity key](use-cases/uc.spaces.verify-identity.md) —
  **built (cr-0098/cr-0099)** (a client **pins** each contact's identity key on first wrap — operator-blind,
  cross-device; a **changed** key is never silently trusted: the grant-delivery worker **declines**, the UI
  surfaces a notice + accept → re-pin, rotation excludes the member;
  [ADR 0028](../../decisions/0028-identity-authenticity-tofu-verification.md),
  [en.account.identity-pinning](../account/enablers/en.account.identity-pinning.md))
- [Actively verify a member's identity (safety number)](use-cases/uc.spaces.enforce-verified-identities.md) —
  **built (web, cr-identity-verification)** (a member's own profile shows their safety number and its QR; on another
  member's profile you scan their QR or compare the digits, and a match marks them *verified* — pinning an unpinned
  member with the compared key; "Verified" shows on the profile and in the member lists, and a verified contact's
  key change is said louder; [ADR 0028](../../decisions/0028-identity-authenticity-tofu-verification.md) Decision 3)
- [Require verified identities before sharing (strict mode)](use-cases/uc.spaces.require-verified-identities.md) —
  **planned** (split out of the use-case above when its ceremony was built — the worker skips unverified members and
  a changed key is a hard block; needs a scope-wide redesign before it is buildable at all, a per-account toggle
  can't protect shared-scope content)
- Person / profile view — **built (web)** (`#people/<userId>`, ADR 0083 — not the `~username` of
  [ADR 0015](../../decisions/0015-operator-blind-readable-urls.md)): open a member and see the Memories **you can
  see** that tag them; client-side (never a server query), over the per-Memory **participants** tag
  ([uc.memory.participants](../memory/use-cases/uc.memory.participants.md))
- Public share of a subtree — **built** → [Sharing](../sharing/_index.md)
  ([uc.sharing.share-public-viewer](../sharing/use-cases/uc.sharing.share-public-viewer.md), [ADR 0029](../../decisions/0029-public-viewer-share.md)):
  a per-share key, anonymous link, least-privilege (only the shared node's own key: a Memory, a chapter or a folder,
  [ADR 0080](../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)); a node of any scope the
  curator belongs to (ADR 0029 §6, [uc.sharing.share-shared-scope](../sharing/use-cases/uc.sharing.share-shared-scope.md))
