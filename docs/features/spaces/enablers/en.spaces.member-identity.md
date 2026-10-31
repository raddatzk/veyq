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
  the **group member-add username search** ([ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §5),
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

## The identity model

The global account carries only `email` + keys. A member's identity lives on a **per-`(user, tenant)` profile row**
(one per person per tenant, independent of how many scopes they're in — so it is **not** a per-membership column,
which would collide with a member's own group rows), split by sensitivity:

1. **`username` — per-(user, tenant), `UNIQUE(tenant_id, username)`, plaintext.** A chosen handle, charset-
   constrained (`[a-z0-9_.-]`-ish, no whitespace), **not a login credential** (email is). Chosen at **every**
   tenant join (register-and-accept *and* existing-account accept), uniqueness checked in the target tenant
   (`409 username_taken`); changeable later with a re-check.
2. **`firstName` / `lastName` — per-(user, tenant), sealed under a field key derived from `K_tenant`** (same profile
   row, a fresh salt per write). Real names are
   content, not operational metadata — ciphertext the server never decrypts. Every tenant member holds
   `K_tenant`, so each decrypts any member's name **locally**; directory **search/sort over names is
   client-side**.
3. **No display name on the account.** Display is the per-tenant username + sealed names, and the profile picture
   ([§ Profile picture](#profile-picture)).

## The display name

One rule names a member everywhere the interface names a person — a Memory's participants, an uploader, a file's
uploader, a Memory's Contributions tab ([uc.memory.buckets](../../memory/use-cases/uc.memory.buckets.md)), the profile
page: the **real name** when it opens on this device, else the **username**. One who left the
scope is a "former member". (Cited as [ADR 0083](../../../decisions/0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md)
decision 9; the code is `core/people/directory.ts`.)

## Main flow — build your tenant identity on join

1. In the tenant-join flow the member picks a **username** (required, plaintext, on device) **and optionally a
   first/last name** → the server checks the username is free **in this tenant** (`UNIQUE(tenant_id, username)`);
   a clash is `409 username_taken`.
2. Every joiner **holds `K_tenant`** at accept — an owner from the cold birth, a member from the invite's grant
   ([uc.spaces.add-member](../use-cases/uc.spaces.add-member.md)) — so the client **seals** `firstName` +
   `lastName` under `K_tenant` and stores the opaque blob on the profile row **at the join itself** (submitted with
   the accept), or later from the profile. Both entry points seal identically; the name stays editable in the
   profile.
   - A member's join also writes the profile's **join record** — identity and Secret-Key proof public keys and the
     owner-signed invite, signed by the invite key and the new identity, sealed under the tenant epoch key the
     invite's grant carried (field `member-join-record`, bound to tenant and member). It is written only at accept,
     never by a profile update, served to co-members on the tenant listing (`ScopeMember.sealedJoinRecord`), and
     null for the tenant's first owner, the trust root. Every member's client reads it as the anchor of that member's
     key in the tenant. Beside it the profile keeps who rooted the join (`join_signer_user_id`, the invite's signer,
     written at accept) and that owner's **endorsement** of the record (`ScopeMember.joinEndorsement`, the
     `join-endorsement` statement over the sealed record as stored), which their securing writes with the new key: a
     record signed by a key the owner has since retired counts only with it. Both go with the profile (removal, erasure,
     purge)
     ([ADR 0111](../../../decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) decisions 3, 12, 15, 21).
3. Any tenant member reading the directory gets each member's `username` (plaintext) + the sealed name blob,
   **decrypts the name locally**, and can search/sort. A tenant **OWNER** additionally gets `email`. A name saved under an
   earlier tenant epoch still opens after a rotation, and its member's own client moves it forward
   ([§ The epoch of a sealed field](#the-epoch-of-a-sealed-field)).

## The epoch of a sealed field

The name and the picture are sealed under the tenant epoch current when their member saved them; the profile row
records no epoch ([ADR 0023](../../../decisions/0023-per-tenant-member-identity.md) § The epoch of a sealed name holds
why).
- **Reading:** every reader opens them under every tenant epoch key it holds, newest first — the join record's rule,
  `openUnderHeldEpochs` (`src/web/src/features/spaces/epochs.ts`). A grant at the current epoch peels the chain down to
  `E_0`, so a newcomer reads every name; nothing opens under a key the reader does not hold.
- **Carrying forward:** the member's own client moves its name and picture to the current epoch when it enters the
  tenant (`src/web/src/features/account/profile/carryProfileForward.ts`): opened and sealed again, written only while the
  stored blob (the name) or revision (the picture) is still the one it opened, so a save from another device in between
  wins. In the steady state a read is then one open per member.

## Profile picture

A sealed attribute of the profile row ([ADR 0084](../../../decisions/0084-a-members-profile-picture-is-a-sealed-attribute-of-their-tenant-profile.md)),
read by tenant members only — never by a guest, never on a share page.
- **Storage:** `veyq_tenant_profile.sealed_avatar` (`TEXT`, null = none) and `avatar_revision` (`INT`, default 0),
  bumped by one guarded `UPDATE` on every set and clear, so the stored counter only moves up. The wire shows `0`
  whenever there is no picture.
- **Operations:** `setMyAvatar` (`PUT /api/account/profile/avatar`, `204`, overwrites and bumps) and `clearMyAvatar`
  (`DELETE`, `204`, idempotent) act in the Host's tenant; `getMemberAvatar`
  (`GET /api/tenants/{tenantId}/members/{userId}/avatar`) is gated on tenant membership and answers
  `200 { sealedAvatar }` with an `ETag` of the stored revision, quoted, and `Cache-Control: private`, `304` on a
  matching `If-None-Match`, `404 avatar_not_found` when there is none. `ScopeMember` and `MemberProfile` carry only
  `avatarRevision`.
- **The constant:** a 256 px WebP padded to 16 KiB (the tenant icon's pad, `src/packages/crypto/src/pad.ts`) seals as
  a field envelope (purpose `member-avatar`, bound to `[scopeId, userId]`) to 16429 bytes (1 type + 16 salt + 12 nonce
  + 16384 + 16 tag), **21908** base64 characters — the `maxLength` of every `sealedAvatar` in the contracts, which fits
  the `TEXT` column (`scripts/validate_maxlength_storage.py`); `MemberAvatarSliceTest` pins the arithmetic. A client's
  open refuses a plaintext of any other length and draws the letter.
- **Join:** the accept and the register-and-accept take an optional `sealedAvatar` beside the name, from either role;
  a profile born with one starts at revision 1. A joiner who skipped it gets the one-time "finish your profile" prompt
  once the device holds the key.
- **Removal:** the tenant member-removal cascade clears the picture and bumps the revision; the username stays.
- **Client:** `features/account/profile/memberAvatar.ts` seals and opens; `ProfilePictureDialog.vue` crops with
  `shared/ui/ImageCropper.vue` and encodes with `renderSpaceIcon`; `core/people/avatarCache.ts` keeps one
  `{ revision, objectUrl }` per user, fetches only a non-zero revision it does not hold and revokes every URL on
  `resetPeopleDirectory`; `core/people/PersonAvatar.vue` draws it. Name and picture are edited by one model and
  component (`useNameAndPicture` / `NamePictureFields`) in Settings → Your profile and on one's own profile page only.
- **Residual:** the operator sees a constant-size blob, the revision, and whether a member has a picture; the three
  operations are in `OperatorBlindProbeTest`.

## Alternate / exception flows

- **E1 — username taken in the tenant:** `409 username_taken`; the member picks another. Uniqueness is
  per-tenant, so the same handle may be free in a different tenant.
- **E2 — name skipped, or no key on this device:** the name is optional at join; a member who skipped it, or whose
  tenant key does not open on this device (*waiting for the current key*), shows as just `@username` (+ owner-only
  email) until they seal a name from the profile with the key.
- **E3 — username change:** re-checked for uniqueness in the tenant. The profile view is addressed by user id
  (`#people/<userId>`), so no link to it breaks.

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
  [ADR 0084](../../../decisions/0084-a-members-profile-picture-is-a-sealed-attribute-of-their-tenant-profile.md) (the
  profile picture),
  [ADR 0007](../../../decisions/0007-membership-role-grant-relation.md) (membership = role + grant; email
  visibility), [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) (the group member-add search
  this feeds), [ADR 0015](../../../decisions/0015-operator-blind-readable-urls.md) (fragment routing; the profile view
  is `#people/<userId>`, ADR 0083)
