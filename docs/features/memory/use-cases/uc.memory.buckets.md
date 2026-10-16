# UC: Buckets — group a Memory's media by who contributed it

- **ID:** uc.memory.buckets
- **Primary actor:** A member with an **unlocked vault** viewing a Memory who wants to see its media grouped by
  **who uploaded it** — their own contributions, a fellow member's, or a public contributor's.
- **Goal:** Organize a Memory's media in the **"Beiträge" tab** — group by uploader (the attribution axis, without
  disturbing the owner's Chapter curation) **and** file your own uploads into self-named **sub-buckets** (create /
  rename / delete folders, move media in — e.g. "Oma (WhatsApp)"). Ported from the pre-E2EE MemoryAssetBuckets
  organizer; always available (even an empty Memory), your primary bucket **virtual until** the first sub-bucket/upload.
- **Preconditions:** The vault is unlocked and the Memory is open (its **`K_node`** is held); the bucket data layer
  ([en.memory.buckets](../enablers/en.memory.buckets.md)) is in place, so each medium carries an **`owner_key`**
  (a member `user:{userId}` **or** a contributor `share:{shareIdentityId}`) and a `bucket_id` (`null` = the
  owner's primary).
- **Postconditions (success):** A **"Beiträge"** view lists the Memory's uploaders (their **primary buckets**);
  selecting one filters the gallery grid to that uploader's media. **No data is mutated** in this slice — it is a
  read-side grouping over the bucket data layer.

## What buckets are here

A **bucket** = the media **one uploader** contributed to *this* Memory. It is the **attribution axis** ("who
contributed this?"), **orthogonal to Chapters** (the owner's curation axis) — an organization view over the same
media, **not** a node, permalink, or Memory / Chapter / Folder. The internal noun is **"bucket"** (a sanctioned
third noun, distinct from the Memory/Chapter product language); the **user-facing** surface is the **"Beiträge"
tab**.

The data model is a **real bucket table** keyed by an opaque **`owner_key`**
([en.memory.buckets](../enablers/en.memory.buckets.md), [ADR 0034](../../../decisions/0034-media-uploader-buckets.md)),
carrying a **lazy primary** bucket per uploader plus optional **one-level sub-buckets**. **This slice surfaces the
primary grouping**: each uploader's **primary bucket** (everything they contributed). The **sub-bucket management
UI** (create/rename/move/delete folders) followed on the same table + endpoints (cr-0122).

Uploaders span **members** (`owner_key = user:{userId}`, display name = the per-tenant **username**) and anonymous
**public contributors** (`owner_key = share:{shareIdentityId}`, display name = the **sealed** name from
[ADR 0030](../../../decisions/0030-contributor-public-share.md)), shown marked as a guest's — "Ben (guest)" (inbox #433):
a contributor names itself freely and could pick a member's name; a sub-bucket's name is a member's and stays as it is.
One's own bucket reads **"Deine Beiträge"**.

## Main flow

1. Open a Memory and switch to the **Beiträge** tab.
2. The client lists the Memory's buckets (or groups its already-loaded media by `owner_key` / `bucket_id`) and
   **unseals** the bucket names with `K_node` (each under a field key derived from it).
3. It renders an **uploader picker** — **"Deine Beiträge"** first, then each fellow member / contributor with a
   **count**.
4. Selecting an uploader filters the existing **`MediaGrid`** to that uploader's **primary bucket**; opening an
   item uses the **same lightbox**. The owner's delete/trash and Chapter curation are untouched.
5. Nothing is written — this slice is a **read-side grouping**.

## Alternate / exception flows

- **Only your own contributions** → the picker shows just **"Deine Beiträge"**; a Memory nobody else touched can
  keep the tab quiet.
- **A sealed name won't unseal** (missing/rotated `K_node`) → fall back to a neutral placeholder ("Beitrag"),
  **never block the grid**.
- **Group / tenant scope** → **member buckets only** (name = username). Anonymous contributor shares are
  **personal-scope-only** ([ADR 0029](../../../decisions/0029-public-viewer-share.md) / ADR 0030), so they do not
  appear in group/tenant memories.
- **A medium with `bucket_id = null`** (no write produces one; the column is merely nullable) → it
  resolves to the **owner's primary bucket** (lazy), so it always groups under its uploader.

## Quality notes

- **Operator-blind.** The grouping reads the **opaque `owner_key`** + the bucket structure the operator already
  relays, and **unseals** the names **client-side** with `K_node`. The operator learns **no cleartext name and no
  content**; a personal Memory with no share exposes only the account owner's `owner_key`.
- **A guest's name is for the members.** The member bucket list is the one read that serves a guest's sealed name.
  No guest read of a share link does (the resolve, the media read), and none serves a contributor's identity or
  stamps a contributor's media with it
  ([ADR 0030](../../../decisions/0030-contributor-public-share.md) point 8, which also states the accepted
  residual: a link plus a database dump opens the names, as it opens the photos).
- **A real data model.** The bucket table ([en.memory.buckets](../enablers/en.memory.buckets.md)) carries
  sub-buckets from day one, and the organizer UI (create/rename/delete a sub-bucket, move media in) is built on the
  shipped endpoints.
- **Reuse, not a new surface.** The organizer reuses the gallery **`MediaGrid` + lightbox**; the picker (a
  hierarchy Select), the dialogs, and the tile checkbox/menu are existing components — never invent UI.
- **Membership is server-enforced (cr-0123).** Create / rename / delete a sub-bucket + move media are gated to
  **scope membership** — **any member** may organize **any owner-root's** sub-buckets (yours *or* a
  contributor's), but **never a primary** and **never across owners** (a move stays within one owner-root);
  **delete media** + **edit capture time** stay any key-holder.

## User impact

In a shared Memory you see at a glance who added which photos and videos, without anything being moved and without the
server learning anyone's name.

## Related

- End-user view (docs site): [See who added what](../../../../site/src/content/docs/memories/contributions.mdx)
- **Feature:** [memory](../_index.md)
- **Enablers:** [en.memory.buckets](../enablers/en.memory.buckets.md) (the bucket data layer this reads),
  [en.memory.media](../enablers/en.memory.media.md) (the media rows a bucket groups).
- **Use-cases:** [uc.memory.upload](uc.memory.upload.md) (stamps the uploader's `owner_key` on attach),
  [uc.memory.gallery](uc.memory.gallery.md) (the grid + lightbox this reuses),
  [uc.sharing.share-contributor](../../sharing/use-cases/uc.sharing.share-contributor.md) (the contributor
  identity behind a `share:` owner_key + its sealed name).
- **Cross-cutting:** [security-model](../../../crosscutting/security-model.md) (owner_key + bucket structure =
  operator-visible coarse metadata; names sealed), [cc.inclusive-language](../../../crosscutting/cc.inclusive-language.md)
  (the DE „Beiträge" voice).
- **Decisions:** [ADR 0034](../../../decisions/0034-media-uploader-buckets.md) (the bucket model),
  [ADR 0030](../../../decisions/0030-contributor-public-share.md) (the contributor identity it builds on).
