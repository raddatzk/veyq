# ADR 0031 — Uploader public share: a write-only drop-box with a client-local, session-only own-view

- Status: Accepted
- Date: 2026-07-07

## Context

[ADR 0030](0030-contributor-public-share.md) built the **READ_WRITE** public share (view + add photos) on a
sealed anonymous identity + a share-scoped upload path, and split out the **WRITE_ONLY** variant. This ADR decides
it: a **write-only drop-box** — a curator turns one Memory into a link where anyone can **add photos but cannot
see the Memory's existing content** (nor other contributors'). It is the classic "collect photos from guests
without them seeing each other / your library" flow (cf. Nextcloud *File Drop* / *File request*).

The whole upload machinery is reused from ADR 0030 (the sealed identity, `POST /identities` + `/blob-uploads` +
`/media`, the shared optimistic-upload queue). What is **not yet decided** is: how *"cannot see others"* is
enforced operator-blind, what the uploader **does** see, and how they **delete their own** — without the server
gaining a per-identity content-listing surface.

## Decision

Adopt a **WRITE_ONLY** role (renamed 2026-10-06 from `UPLOADER` —
[ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md)'s three access modes) as a write-only share, with a **session-scoped, client-local** view of one's own
uploads and a token-gated **delete-own**. **v1: role WRITE_ONLY, personal scope, direct-append.**

1. **`canView` gate — a write-only role has no *read* capability, at every read endpoint.** The gate matrix is
   `canView = { READ, READ_WRITE }`, `canUpload = { READ_WRITE, WRITE_ONLY }`, and it is enforced on **both**
   guest read paths, not just the resolve:
   - The anonymous resolve (`GET /api/shares/{shareId}`, [ADR 0029](0029-public-viewer-share.md)) returns, for a
     WRITE_ONLY share, only `role`, `sealedName` and the sealed drop public key (point 7) — **`media: []`,
     `nodes: []`, `identities: []`**. The client seals its uploads to the drop key and shows the title (from
     `sealedName`); it needs nothing from the subtree, so not even the root node is served. Since 2026-10-05 it also
     names the root node's kind (`rootKind`, inbox #329) — a structural fact the server holds anyway, so the client
     knows which application renders the drop.
   - The blob download (`GET /api/shares/{shareId}/blobs/{blobId}`) is **role-gated too** — a WRITE_ONLY share
     `404`s **before** the subtree check, so even a leaked in-subtree blob id yields nothing. Write-only is thus a
     **real server capability**, not merely id-secrecy — and point 7 makes it a cryptographic one.

   "Cannot see others" is therefore enforced by the server *not exposing*, on top of being structural (an uploader
   is never handed anyone else's ids; ids are unguessable — 256-bit `shareId`, 122-bit media/blob UUIDs).

2. **The own-view is client-local — no server read, no gallery filtering.** An uploader still sees the photos
   **they themselves add this session** (the product wants the familiar gallery look). This is done **client-side,
   not by a filtered server read**: on each upload the client keeps the returned `{ mediaId, blobId }` **plus the
   local preview** (the picked file's own bytes) in **session memory**, and renders them with the **same gallery
   mechanic** (the shared `MediaGrid` + optimistic tiles) from those local previews. The uploader **never
   downloads** anything, and the server **never** exposes a "list my contributions" endpoint — so *"see your own"*
   needs **no per-identity server-side filtering**. (The `mediaId`/`blobId` are unguessable **identifiers**, not
   capabilities — they do not authorize anything by themselves; the authority is the `contributorToken`, point 4.
   Their unguessability only matters in that an uploader never *learns* another guest's ids — see point 1.)

3. **Session-only, ephemeral by design.** Because the own-gallery lives only in the browser tab's memory (local
   previews + the in-memory id list), **a reload / new tab shows nothing** — the uploader's prior uploads are not
   re-listed or re-fetched. This is a deliberate privacy stance (the write-only guarantee is strongest when the
   guest holds no durable handle to server content) and matches Nextcloud's drop model. It is a **sharp UX edge**,
   so the uploader flow must **warn prominently and repeatedly** that the view is session-only (see point 5).

4. **Delete-your-own (token-gated, ownership-checked).** `DELETE /api/shares/{shareId}/media/{mediaId}` carries the
   contributor token (header); the server deletes the media + reclaims its blobs **iff** the media's
   `share_identity_id` equals the token's identity. This single-row ownership check is *not* gallery filtering —
   it is the correct authorization for a destructive op, and it keeps the endpoint safe even if a future role
   (a READ_WRITE, who *does* see others' ids) is allowed to delete. `canDeleteOwn = { WRITE_ONLY }` in v1
   (READ_WRITE delete is a later add over the same endpoint). Delete is reachable only **in-session** (after a
   reload the client no longer holds the id) — consistent with point 3.

5. **Prominent ephemerality warning (a hard requirement of this slice).** The write-only, session-only nature must
   be unmistakable, surfaced in **four** places: (a) on the mandatory **name pre-screen**, before the first upload;
   (b) a **persistent badge** in the uploader header (in place of the viewer's "read-only" badge) — *"visible only
   in this session"*; (c) a dedicated **empty-state after a reload** (not a blank gallery) — *"your earlier
   uploads aren't shown here, for privacy; they arrived safely — you can add more"*; (d) a note that **delete
   works only in this session**. The docs site's Use page for it ([Ask people to add photos](../../site/src/content/docs/sharing/request-uploads.mdx))
   states it too.

6. **Everything else inherits ADR 0030.** Mandatory display name → an anonymous share identity (sealed under
   the identity's own key, point 7); the share-scoped, token-gated upload path; direct-append to the shared root
   (the curator sees the contributions in *their* gallery); the byte budget, the per-link storage limit
   ([ADR 0073](0073-storage-quota-belongs-to-whoever-pays-for-the-store.md)) + per-share rate limit; the
   client-integrity trust-tier honesty (footer link, [ADR 0026](0026-client-trust-tiers.md)); personal-scope-only;
   the T&S decryptable-on-report corollary.

7. **The uploader never holds `K_node`: uploads are sealed to the Memory's drop key.** Points 1–6 make write-only a
   server capability. A capability the server enforces does not survive a dump, and an uploader link is the one
   link handed to people who are *meant* not to see — so a leaked link plus a database and store dump must read
   nothing beyond what that link-holder uploaded.
   - **The drop keypair belongs to the Memory.** An X-Wing (X25519 + ML-KEM-768) keypair, held as its 32-byte seed and minted by the owner's
     client when the Memory's first WRITE_ONLY share is created. Its private half is sealed under `K_node` on the
     Memory, so everyone who reads the Memory — members, viewer and contributor links — opens what uploaders
     sealed. Its public half is sealed under `K_share` on each WRITE_ONLY share, so the server can neither read
     nor substitute it. A WRITE_ONLY share carries no `symWrap(K_share, K_node)`.
   - **Each uploader identity seals under a key of its own.** The guest's client mints a random identity key,
     posts it `asymWrap`ped to the drop public key together with the identity, and seals everything of that
     identity under it — every blob key, media metadata and EXIF, the display name — at today's field sizes: one
     asymmetric wrap per identity, not per blob.
   - **A media's key path is fixed by where it came from.** A reader opens an uploaded media through its
     identity: drop private key (under `K_node`) → identity key → the media's keys. A member who later edits that
     media's metadata re-seals it under the same identity key, so no media ever changes path.
   - **What the guest holds:** `K_share` (the title, the note, the drop public key) and its own identity key. The
     sibling shares' `K_share` is out of reach as well, because `sealed_share_key` opens only under `K_SC`
     ([ADR 0036](0036-editable-share-note-recoverable-share-key.md) point 4) — which is also what keeps it out of a
     READ or READ_WRITE link's reach, since those do hold `K_node`.

## Consequences

- **The most operator-blind sharing surface yet.** The server serves the uploader **no gallery at all** — the
  resolve returns `media: []` / `nodes: []` and the blob endpoint is role-gated to a `404`. It holds the same
  opaque state as a contributor share (the sealed identity + the `share_identity_id` stamp) — needed only for the
  curator's own view + the delete ownership check — never a content listing (or a served byte) for the guest.
- **Ephemeral own-view is a real UX limitation.** An uploader who adds 20 photos, reloads, and expects to manage
  them will find an (intentional) empty state. The four-point warning (point 5) is the mitigation; it is why this
  is a *decision*, not just a UI detail. Persisting the id list client-side (`localStorage`) to allow post-reload
  **delete** — without re-showing thumbnails — is a **possible later add** (a "manage my uploads" affordance); it
  is deliberately **out of v1** to keep the write-only guarantee simple and the promise honest.
- **Attribution self-asserted; direct-append; scope-rotation ≠ share-revoke; personal-scope-only** — all inherited
  unchanged from [ADR 0030](0030-contributor-public-share.md) / [ADR 0029](0029-public-viewer-share.md).
- **New server surface (small):** the resolve gains a role branch (empty media for WRITE_ONLY); one new endpoint
  (`DELETE …/media/{id}`). No new table. Point 7 adds opaque fields (the sealed drop keypair halves, the wrapped
  identity key) and new seal purposes, each with its vector ([ADR 0072](0072-a-seal-binds-its-object.md)).

## Alternatives considered

- **Server-side identity-filtered gallery for the uploader** (the resolve returns *only this identity's* media,
  keyed on the token). Rejected: it gives the server a **per-identity content-listing** surface and makes the
  read path token-authenticated + filtered — more surface, less blind — to buy post-reload persistence the product
  doesn't need. The **client-local own-view** (point 2) delivers the same in-session own-gallery with **zero**
  server-side filtering.
- **A persistent client-local id list (`localStorage`) with targeted own-download after reload.** Rejected for v1:
  it would re-show thumbnails across reloads by **downloading the uploader's own blobs by id** — breaking the clean
  "an uploader never downloads" guarantee — for a convenience the drop-box use-case doesn't require. Kept as a
  clearly-scoped later option (persist ids for *delete*, still no download).
- **A pure write-only form with no own-view at all** (the strict Nextcloud *File Drop*). Rejected as the default:
  the product wants the familiar gallery look for *what you just added*; the session-local own-gallery (point 2)
  gives that at no server cost, and the warning (point 5) keeps the ephemerality honest. (This strict form is a
  trivial subset should we ever want it.)
- **Write-only as access control alone, documented as such** (the share password's posture,
  [ADR 0037](0037-password-protected-shares.md)). Honest, and free. Rejected because the one party this role
  exists to keep out — the guest — would then hold the Memory's read key, and a leaked backup is not an active
  attack: a link plus a dump would read the Memory on any tier.
- **A drop key per share instead of per Memory.** Every reader would then need the private half of every
  uploader share the Memory ever had, found through each media's identity and share. Per Memory, it travels with
  the node, where every reader already looks.
