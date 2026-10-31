# ADR 0084 — A member's profile picture is a sealed attribute of their per-tenant profile

- Status: Accepted
- Built: yes
- Date: 2026-10-13
- Model: [en.spaces.member-identity](../features/spaces/enablers/en.spaces.member-identity.md)

## Context

[ADR 0083](0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md) decision 6 and the participant chips
show a **letter** for a person, and left a picture open. The server is **operator-blind**, a member's identity is
**per tenant** ([ADR 0023](0023-per-tenant-member-identity.md): a plaintext `username`, the real name sealed under a
field key derived from `K_tenant`), and a share is the member view with rights removed. The question is where a picture
lives and what the operator learns from it. The nearest precedent is the space icon
([uc.spaces.set-space-icon](../features/spaces/use-cases/uc.spaces.set-space-icon.md)): one sealed, constant-length
field on a row rather than a blob.

## Decision

**1. Storage: a sealed attribute of the profile row, not a blob.** The per-tenant profile row gains one opaque sealed
picture (none by default) and a revision counter, bumped on every set and clear. It is **not** a blob: blobs are
addressed by scope + node, counted against quota, chunked and need the object store, and a user-owned blob would need
a new owner kind and new erasers. It is also **not** part of the member list.

**2. Contract.** A member sets and clears their own picture in the tenant the request addresses; any member of the
tenant reads another member's, gated on **tenant membership** like the directory (a non-member gets the `404` of
[ADR 0042](0042-http-failure-projection.md)). The directory and the profile carry only the small integer revision
(`0` = none, whatever the stored counter reads), never the bytes; the client fetches a picture only when the revision
is non-zero and caches by revision, and the read answers a matching revision with `304`. The stored counter only ever
moves up, so overlapping writes cannot lose a bump or reuse a revision. The sealed picture has one exact length, and
the contract's bound is that length.

**3. Sealing.** Under a field key derived from `K_tenant`
([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 9) with a **new AEAD
purpose `member-avatar`** bound to `[scopeId, userId]`, pinned by a conformance vector
([ADR 0072](0072-a-seal-binds-its-object.md)). `member-name` is **not reused**, so a blob sealed for one purpose can
never be interpreted as the other. The plaintext is a **256 px WebP padded to a constant length** with the tenant
icon's pad (the largest place it is drawn is 128 px, the profile page, so it stays sharp on a 2x screen). A client
refuses any plaintext of another length and shows the letter, so the operator cannot tell a photo from a drawing. Which
tenant epoch it is sealed under, and how it moves forward after a rotation, is the name's rule
([ADR 0023](0023-per-tenant-member-identity.md) § The epoch of a sealed name).

**4. Who sees it: tenant members only.** Groups use the tenant key too, so the same read serves them. **Never a guest,
never a share view**: no share endpoint serves an avatar and a share view renders the letter. This is a statement about
other members, who agreed to be in the space, not to be pictured to a link holder (the reasoning of ADR 0083 decision 4,
here with no residual: a guest holds no `K_tenant`).

**5. Client.** The picture is cropped square and encoded with the tenant icon's encoder at this edge and budget, and
sealed and opened beside the sealed name. A client caches one picture per member and revision: it fetches only when the
revision differs from the one it holds, revalidates the one it holds, and revokes every object URL on sign-out. A person
is drawn with the picture where it opens and with the letter otherwise — never an error, also for a blob that does not
open.

**6. Editing: one component, two places.** In *Settings → Your profile* **and** on one's **own** profile page
(`#people/<myId>`). The same shared component writes name and picture, and it renders only when the profile is the
viewer's own. On other people's profiles there is **no edit control**: rights only remove functions, and there is no
second UI.

**7. Join.** Every joiner holds `K_tenant` at accept — the owner from the cold birth
([ADR 0006](0006-cold-tenant-scope-birth.md)), a member from the grant it gives itself from its invite
([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
decision 3) — so name and picture may be sealed together there: the accept and the register-and-accept carry an
optional sealed picture, and the join view offers the picker next to the name fields. A joiner who skipped either is
offered them once the device holds the key: a one-time "finish your profile" prompt, plus Settings. There is **no local
plaintext caching** of an unsealed image.

**8. Removal clears the picture, keeps the handle.** A removed member's profile row outlives the removal (reused on
re-invite). The removal cascade **clears the picture and bumps the revision** but keeps the username, so old Memories
fall back to the letter. A picture is **per tenant**, not account-global; "copy from another tenant" is a possible later
feature, not decided.

**9. Operator-blind residual.** The operator sees an opaque constant-size blob, a revision counter, and **the fact that a
member has or has no picture** (and how often it changed). It never sees the image, its dimensions or its content type.
[ADR 0044](0044-field-visibility-declared-at-the-field.md) field-visibility declarations and the
[security-model](../crosscutting/security-model.md) inventory name that residual, and the three operations are probed
like every other.

## Consequences

- **Each profile read costs one integer, each first sight of a picture one small request.** The directory stays light
  because it carries the revision, not the bytes; a person's picture is fetched once per revision and revalidated with a
  `304`.
- **The profile row becomes sealed-content-bearing in two fields.** The picture is a second opaque field next to the
  sealed name; the "operator sees" lists (security-model inventory, ADR 0044 declarations) are updated with it.
- **A removed member's pictures disappear from other members' clients on the next revision check**, while their Memories
  keep their name (ADR 0083 decision 3).
- **No picture from a device without the tenant key.** A joiner who skipped it at accept adds it once the device
  holds the key — the price of not holding an unsealed image on the device, and the same price the real name pays.
- **Rejected:** a blob (quota, chunking, object store, a user owner kind and its erasers); the picture inside the member
  list response (every directory read would carry kilobytes per member); reusing `member-name` as the seal purpose (cross-
  interpretation); a variable-length pad (a photo would be told from a drawing by size); an account-global picture (a
  cross-tenant correlation handle, against [ADR 0023](0023-per-tenant-member-identity.md)); editing others' pictures; a
  picture for guests or in a share view.
