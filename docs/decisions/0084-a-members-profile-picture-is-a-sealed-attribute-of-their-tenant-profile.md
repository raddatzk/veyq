# ADR 0084 — A member's profile picture is a sealed attribute of their per-tenant profile

- Status: Accepted
- Built: yes
- Date: 2026-10-13
- Model: [scopes-and-tenancy](../crosscutting/scopes-and-tenancy.md)

## Context

[ADR 0083](0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md) decision 6 and the participant chips
show a **letter** for a person; a picture was split out as inbox #414. The server is **operator-blind**, a member's
identity is **per tenant** ([ADR 0023](0023-per-tenant-member-identity.md): a plaintext `username`, the real name sealed
under a field key derived from `K_tenant`), and a share is the member view with rights removed. The question is where a picture lives and what
the operator learns from it. The nearest precedent is the space icon
([uc.spaces.set-space-icon](../features/spaces/use-cases/uc.spaces.set-space-icon.md)): one sealed, constant-length
field on a row rather than a blob.

## Decision

Decided by the owner, 2026-10-13.

**1. Storage: a sealed attribute of the profile row, not a blob.** `veyq_tenant_profile` gains `sealed_avatar TEXT`
(null = none) and `avatar_revision INT NOT NULL DEFAULT 0` (bumped on every set and clear). This is a **baseline edit**
(pre-release; a pin line in `scripts/applied_migrations.sha256`; dev databases are recreated). It is **not** a blob:
blobs are addressed by scope + node, counted against quota, chunked and need the object store, and a user-owned blob
would need a new owner kind and new erasers. It is also **not** part of `GET /api/scopes/{scopeId}/members`.

**2. Contract** (contracts first, then backend, then web):

- `PUT /api/account/profile/avatar` (`setMyAvatar`, body `{ sealedAvatar }`, `204`): overwrites, bumps `avatar_revision`.
- `DELETE /api/account/profile/avatar` (`clearMyAvatar`, `204`, idempotent): clears, bumps.
- `GET /api/tenants/{tenantId}/members/{userId}/avatar` (`getMemberAvatar`): gated on **tenant membership** like the
  directory, a non-member gets the `404` of [ADR 0042](0042-http-failure-projection.md). `200 { sealedAvatar }` with an
  `ETag` derived from `avatar_revision` and `Cache-Control: private`; `If-None-Match` matching gives `304`;
  `404 avatar_not_found` when the member has none.
- `ScopeMember` and `MemberProfile` carry only the small integer **`avatarRevision`** (`0` = none). The client fetches
  only when it is non-zero and caches by revision. Directory reads select the revision only, never the bytes.
  The stored counter only ever moves up (one guarded `UPDATE`, so overlapping writes cannot lose a bump or reuse a
  revision), but the wire shows `0` whenever there is no picture, so a cleared member reads `0`, not the counter a clear
  left behind; the `ETag` is the stored counter, quoted. The constant: a 16 KiB padded plaintext seals as a field envelope to 16429 bytes
  (1 type + 16 salt + 12 nonce + 16384 + 16 tag), **21908** base64 characters - the icon's pad, but no longer the icon's
  number (21884): the icon is a plain seal under its per-epoch `K_icon`, the picture a field - which is the `maxLength` of `SetAvatarRequest.sealedAvatar`, `MemberAvatar.sealedAvatar` and
  the accept bodies' `sealedAvatar` (`MemberAvatarSliceTest` pins the arithmetic and the wire bound).

**3. Sealing.** Under a field key derived from `K_tenant`
([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 9) with a **new AEAD
purpose `member-avatar`** bound to `[scopeId, userId]`: a new entry in the `packages/crypto` field purposes and a
`field_seal` conformance vector in `contracts/crypto/vectors/v1.json`
([ADR 0072](0072-a-seal-binds-its-object.md)). `member-name` is **not reused**, so a blob sealed for one purpose can
never be interpreted as the other. The plaintext is a **256 px WebP padded to a constant length** with the tenant icon's
pad/unpad (`@veyq/crypto`'s one pad, `src/packages/crypto/src/pad.ts`; the picture takes the icon's 16 KiB pad and 256 px edge, about 22 KB of
base64; the largest place it is drawn is 128 px, the profile page, so it stays sharp on a 2x screen). `sealed_avatar` is a
`TEXT` column and 21908 < 65532, so the raise needed no schema change. A picture sealed with the earlier 8 KiB pad is not
one of ours: the client's `openAvatar` refuses any plaintext of another length and shows the letter, so the operator cannot tell
a photo from a drawing. The contract `maxLength` equals the sealed constant's exact base64 length and is pinned by a test
and by `validate_maxlength_storage.py`, so it provably fits the `TEXT` column. `K_tenant` is the tenant epoch current at the
save; the row records no epoch, so a reader opens the picture under every epoch key it holds, and the member's own client
moves it forward after a rotation — the name's rule, [ADR 0023](0023-per-tenant-member-identity.md) § The epoch of a sealed
name.

**4. Who sees it: tenant members only.** Groups use the tenant key too, so the same read serves them. **Never a guest,
never a share view**: no share endpoint serves an avatar and a share view renders the letter. This is a statement about
other members, who agreed to be in the space, not to be pictured to a link holder (the reasoning of ADR 0083 decision 4,
here with no residual: a guest holds no `K_tenant`).

**5. Client.**

- Crop with `shared/ui/ImageCropper.vue` (aspect 1) in a dialog modelled on `features/spaces/SpaceIconDialog.vue`.
- Encode with `renderSpaceIcon`, parameterised by edge and byte budget.
- Seal and open in a new `features/account/profile/memberAvatar.ts`, mirroring `memberName.ts`.
- Cache per user as `{ revision, objectUrl }` in `core/people/directory.ts`: fetch only when the cached revision differs,
  revalidating with `If-None-Match`, revoke every object URL on sign-out (`resetPeopleDirectory`).
- `PersonAvatar.vue` shows it through its existing `image` prop and falls back to the letter (also on a blob that does
  not open, never an error).

**6. Editing: one component, two places.** In *Settings → Your profile* (`ProfilePanel`) **and** on one's **own** profile
page (`#people/<myId>`, `PersonProfile`). The same shared component/composable writes name and picture, and it renders
only when `userId === me`. On other people's profiles there is **no edit control**: rights only remove functions, and
there is no second UI.

**7. Join.** The tenant **OWNER** path holds `K_tenant` at accept (cold birth,
[ADR 0006](0006-cold-tenant-scope-birth.md)), so name and picture are sealed together there: an optional `sealedAvatar` in
the accept and register-and-accept request schemas (`tenants.openapi.yaml`), written by `TenantAcceptHandler` with
`avatarRevision` 1 (a MEMBER invite's body is not read for it: that invitee holds no key, so a picture there is dropped), and `TenantJoinView` gets the picker next to the name fields. A **member** joining holds **no
`K_tenant`** yet (the grant arrives later through the grant-delivery job), so it cannot seal. The picture, like the name
today, is offered **after the first unlock with the key**: a one-time "finish your profile" prompt, plus Settings. There is
**no local plaintext caching** of an unsealed image while waiting.

**8. Removal clears the picture, keeps the handle.** A removed member's profile row outlives the removal today (reused on
re-invite). The removal cascade (`TenantHandler.cutUnderLocks`) **clears `sealed_avatar` and bumps the revision** but keeps
the username, so old Memories fall back to the letter. A picture is **per tenant**, not account-global; "copy from another
tenant" is a possible later feature, not decided.

**9. Operator-blind residual.** The operator sees an opaque constant-size blob, a revision counter, and **the fact that a
member has or has no picture** (and how often it changed). It never sees the image, its dimensions or its content type.
[ADR 0044](0044-field-visibility-declared-at-the-field.md) field-visibility declarations and the
[security-model](../crosscutting/security-model.md) inventory name that residual.
`OperatorBlindProbeTest` gains entries for the three operations (`setMyAvatar` and `clearMyAvatar` host-resolved and
self-scoped, `getMemberAvatar` tenant-gated), and the accept and register-and-accept probe bodies carry the new field.

## Consequences

- **Each profile read costs one integer, each first sight of a picture one small request.** The directory stays light
  because it carries the revision, not the bytes; a person's picture is fetched once per revision and revalidated with a
  `304`.
- **The profile row becomes sealed-content-bearing in two columns.** `sealed_avatar` is a second opaque field next to the
  sealed name; the "operator sees" lists (security-model inventory, ADR 0044 declarations) are updated with it.
- **A removed member's pictures disappear from other members' clients on the next revision check**, while their Memories
  keep their name (ADR 0083 decision 3).
- **No picture before the first unlock for a joining member.** That is the price of not holding an unsealed image on the
  device, and the same price the real name pays.
- **Rejected:** a blob (quota, chunking, object store, a user owner kind and its erasers); the picture inside the member
  list response (every directory read would carry kilobytes per member); reusing `member-name` as the seal purpose (cross-
  interpretation); a variable-length pad (a photo would be told from a drawing by size); an account-global picture (a
  cross-tenant correlation handle, against [ADR 0023](0023-per-tenant-member-identity.md)); editing others' pictures; a
  picture for guests or in a share view.
