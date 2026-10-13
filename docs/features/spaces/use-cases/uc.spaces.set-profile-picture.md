# UC: Set your profile picture in a space

- **ID:** uc.spaces.set-profile-picture
- **Status:** built 2026-10-13 ([ADR 0084](../../../decisions/0084-a-members-profile-picture-is-a-sealed-attribute-of-their-tenant-profile.md)): contract, `member-avatar` seal purpose, backend and web. Verified by unit and component tests (and the backend slice tests); **not verified in a real browser** - the crop, the WebP encode and the picture's drawing in the header, the popover and the profile page were never seen by eye.
- **Primary actor:** A member of a tenant space, giving themselves a face there.
- **Secondary actor:** Every other member of the tenant, who only sees it.
- **Goal:** Show a chosen picture instead of the letter wherever the member appears (profile page, participant
  chips, uploader name), sealed on the device so the operator never sees it.
- **Preconditions:** Signed in and unlocked, a tenant space active, and the member's tenant grant (`K_tenant`).
  Without the grant the picture cannot be sealed yet (E2).
- **Postconditions (success):** The member's per-tenant profile row carries `sealed_avatar` (one constant-length
  ciphertext) and a bumped `avatar_version`. Every member of the tenant sees the picture on their next version check;
  no other tenant is touched; a guest sees nothing.

## Main flow

1. The member opens the picture control in *Settings → Your profile* or on **their own** profile page (`#people/<myId>`);
   the same component serves both, and it is absent on anyone else's profile.
2. They pick an image file and frame it 1:1 in the cropper.
3. The client re-encodes it to a 256 px WebP, pads the bytes to the constant length and seals them under `K_tenant`
   with the `member-avatar` purpose at (tenant scope, own user id).
4. `PUT /api/account/profile/avatar { sealedAvatar }` — the server stores the blob verbatim, bumps `avatar_version`
   and answers `204`. In *Your profile* the picture rides with the profile's Save (the profile first, then the picture); on
   the own profile page "Edit profile" opens the same fields with their own Save.
5. Members read it: the directory and profile carry only `avatarVersion`; when it is non-zero and differs from the
   cached one, the client calls `GET /api/tenants/{tenantId}/members/{userId}/avatar` (with `If-None-Match` for a
   known version), opens the blob on the device and renders it through `PersonAvatar`.

## Alternate / exception flows

- **A1 — remove the picture:** `DELETE /api/account/profile/avatar` (idempotent `204`) clears the blob and bumps the
  version; everyone falls back to the letter.
- **A2 — at join, as the tenant owner:** the owner holds `K_tenant` at accept, so the picker beside the name fields
  seals name and picture together and sends `sealedAvatar` with the accept request (stored with `avatarVersion` 1).
- **A3 — at join, as a member:** no `K_tenant` yet, so no picture is offered; after the first unlock with the key a
  one-time "finish your profile" notice (and Settings) offers it - shown when neither a name nor a picture is set, until
  "Not now" or its link dismisses it for that user on that browser (a flag, never an image). Nothing unsealed is cached
  meanwhile.
- **E1 — the browser cannot decode the file:** rejected in the picker with a message; nothing is stored.
- **E2 — no grant yet:** the control is disabled with a hint, as the name fields are
  ([uc.spaces.edit-profile](uc.spaces.edit-profile.md) E2).
- **E3 — the blob does not open** (wrong key, corrupt, another member's blob on this row, a pad of another length such as the earlier 8 KiB one): the letter shows; never a
  throw and never a broken image.
- **E4 — `404 avatar_not_found`** on read (cleared since the version was seen): the letter shows and the cached entry
  is dropped. A non-member asking gets the `404` of ADR 0042.
- **A4 — the member is removed from the tenant:** the removal clears `sealed_avatar` and bumps the version but keeps
  the username ([uc.spaces.remove-tenant-member](uc.spaces.remove-tenant-member.md)); old Memories show the letter.

## Quality notes

- **Constant length.** Photo and drawing seal to the same size, so the operator cannot tell them apart; the contract
  `maxLength` is the exact base64 length of the sealed constant.
- **Visible residual.** The operator learns that a member has a picture and how often it changed, nothing else.
- **Per tenant.** A picture set here changes nothing in another tenant; copying one across is a possible later feature.
- **Members only.** No share endpoint serves it and a share view shows the letter.

## What this means for you / Was das für dich bedeutet

**EN —** You can give yourself a picture in each space you belong to, instead of the plain letter. The picture is
locked with the space's key on your device first: the people in the space see it, the server only stores a scrambled
block of always the same size. Remove it any time and the letter returns. If you join a space as a member, you can add
your picture right after your first unlock.

**DE —** In jedem Bereich, in dem du bist, kannst du dir ein Bild geben statt des einfachen Buchstabens. Das Bild wird
auf deinem Gerät mit dem Schlüssel des Bereichs verschlossen: Die Leute im Bereich sehen es, der Server speichert nur
einen verwürfelten Block, immer gleich groß. Du kannst es jederzeit entfernen, dann kehrt der Buchstabe zurück. Trittst
du einem Bereich als Mitglied bei, kannst du dein Bild gleich nach dem ersten Entsperren hinzufügen.

## Related

- Feature: [Spaces (scopes)](../_index.md)
- End-user view (wiki): [People in your Memories](../../../../wiki/en/people-in-your-memories.md)
- Enabler: [en.spaces.member-identity](../enablers/en.spaces.member-identity.md) (the per-tenant profile row)
- Decisions: [ADR 0084](../../../decisions/0084-a-members-profile-picture-is-a-sealed-attribute-of-their-tenant-profile.md),
  [ADR 0023](../../../decisions/0023-per-tenant-member-identity.md),
  [ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md),
  [ADR 0083](../../../decisions/0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md)
- Related use-cases: [edit-profile](uc.spaces.edit-profile.md) (name and handle, same panel),
  [set-space-icon](uc.spaces.set-space-icon.md) (the sealed constant-length precedent),
  [add-member](uc.spaces.add-member.md) (join),
  [memory participants](../../memory/use-cases/uc.memory.participants.md) (where the picture shows)
