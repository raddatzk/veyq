# ADR 0031 — Uploader public share: a write-only drop-box with a client-local, session-only own-view

- Status: Accepted
- Built: yes
- Date: 2026-07-07
- Model: [en.sharing.public-link](../features/sharing/enablers/en.sharing.public-link.md)

## Context

[ADR 0030](0030-contributor-public-share.md) built the `READ_WRITE` public share (view + add) and split out the
write-only variant: a link where anyone can **add photos but cannot see the content** — nor other contributors' (cf.
Nextcloud *File Drop*). The upload machinery is ADR 0030's. What is open is how *"cannot see others"* is enforced
operator-blind, what the uploader **does** see, and how they **delete their own** — without the server gaining a
per-identity content-listing surface.

## Decision

Adopt a **`WRITE_ONLY`** role ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md)'s three access
modes) with a **session-scoped, client-local** view of one's own uploads and a token-gated **delete-own**. The key
path and the gates as built are the model, [en.sharing.public-link](../features/sharing/enablers/en.sharing.public-link.md).

1. **`canView` gate — a write-only role has no read, at every read endpoint.** `canView = { READ, READ_WRITE }`,
   `canUpload = { READ_WRITE, WRITE_ONLY }`. The resolve of a `WRITE_ONLY` link serves no node, no media and no
   identity — only what the client needs to send; the blob read refuses before the subtree check, so even a leaked
   in-subtree blob id yields nothing.
   Write-only is a **real server capability**, not id-secrecy — and point 7 makes it a cryptographic one.

2. **The own-view is client-local — no server read, no gallery filtering.** An uploader sees the photos **they add
   this session**: the client keeps each returned id plus the picked file's own bytes in session memory and renders
   them with the member gallery's mechanics. The uploader **never downloads** anything, and there is no "list my
   contributions" endpoint.

3. **Session-only, ephemeral by design.** A reload or a new tab shows nothing of earlier uploads. The device keeps
   the guest's identity per link — its name, its token and its own key — until "Not you?", so a returning guest is
   not asked its name again; it never keeps a handle to content, because the write-only guarantee is strongest when
   the guest holds none. Only the own-view is bound to the tab. It is a sharp UX edge, hence point 5.

4. **Delete-your-own (token-gated, ownership-checked).** A delete carries the contributor token; the server deletes
   the medium and reclaims its blobs **iff** the medium's identity is the token's. That single-row ownership check is
   the right authorization for a destructive operation, not gallery filtering, so it stays safe for a role that sees
   others' ids: a `READ_WRITE` guest deletes its own the same way. For an uploader it is reachable only in-session.

5. **Prominent ephemerality warning (a hard requirement).** Four places: the name pre-screen before the first upload;
   a persistent "visible only in this session" badge; a dedicated empty state after a reload ("your earlier uploads
   aren't shown here, for privacy; they arrived safely"); and a note that delete works only in this session. The docs
   site's Use page ([Ask people to add photos](../../site/src/content/docs/sharing/request-uploads.mdx)) states it too.

6. **Everything else inherits ADR 0030:** the mandatory name and anonymous identity (the name sealed under the
   identity's own key, point 7), the token-gated upload path, direct-append, the byte budget, the per-link storage
   limit ([ADR 0073](0073-storage-quota-belongs-to-whoever-pays-for-the-store.md)), the per-share rate limit and the
   trust-tier footer link. What a report hands the moderators is
   [ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 1's: an uploader
   sees only its own uploads, so it reports the link itself.

7. **The uploader never holds the node's key: uploads are sealed to the node's drop key.** Points 1–6 make
   write-only a server capability, and a server capability does not survive a dump; an uploader link is the one link
   handed to people who are *meant* not to see, so a leaked link plus a database and store dump must read no content
   beyond what that link-holder uploaded.
   - **The drop key pair belongs to the node** (a Memory, a chapter, a Depot drop box): an X-Wing pair
     ([ADR 0017](0017-crypto-primitives-and-post-quantum-strategy.md)), minted with the node's first `WRITE_ONLY`
     link. Its private half is sealed under the node's key, so everyone who reads the node — members, viewer and
     contributor links — opens what uploaders sealed. Its public half is sealed under each `WRITE_ONLY` link's `K_share`, so the server can neither
     read nor substitute it. A `WRITE_ONLY` link carries no wrap of the node's key.
   - **Each uploader identity seals under a key of its own**, wrapped to the drop public key: one asymmetric wrap per
     identity, not per blob.
   - **A medium's key path is fixed by where it came from:** drop private key → identity key → the medium's keys. A
     member who edits that medium's metadata re-seals it under the same identity key, so no medium changes path.
   - **What the guest holds:** `K_share` and its own identity key. The sibling links' `K_share` is out of reach,
     because their recovery copies open only under `K_SC` ([ADR 0036](0036-editable-share-note-recoverable-share-key.md)
     point 4).

## Consequences

- **The most operator-blind sharing surface.** The server serves the uploader no gallery and no byte; it holds the
  same opaque state as a contributor link, needed for the members' view and the delete check, and, as for every
  writing guest, the client's address, source port and time of each write for N days
  ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 10).
- **The ephemeral own-view is a real UX limitation.** An uploader who adds 20 photos and reloads finds an intentional
  empty state; point 5 is the mitigation, which is why this is a decision and not a UI detail. Persisting the id list
  on the device to allow a post-reload delete, without re-showing thumbnails, stays possible.
- **Attribution self-asserted; direct-append; a rotation does not revoke the link** — as for
  [ADR 0030](0030-contributor-public-share.md) and [ADR 0029](0029-public-viewer-share.md).

## Alternatives considered

- **A server-side, identity-filtered gallery for the uploader.** Rejected: it gives the server a per-identity
  content-listing surface and a token-authenticated, filtered read path — more surface, less blind — for a post-reload
  persistence the product doesn't need.
- **A persistent client-local id list with own-download after reload.** Rejected: it would download the uploader's own
  blobs by id, breaking "an uploader never downloads", for a convenience the drop-box does not require.
- **A pure write-only form with no own-view** (the strict *File Drop*). Rejected as the default: the product wants the
  familiar gallery look for what you just added, which the session-local view gives at no server cost.
- **Write-only as access control alone, documented as such** (the share password's posture,
  [ADR 0037](0037-password-protected-shares.md)). Rejected: the one party this role exists to keep out — the guest —
  would hold the read key, and a leaked backup is not an active attack: a link plus a dump would read the content on
  any tier.
- **A drop key per link instead of per node.** Every reader would then need the private half of every uploader link
  the node ever had, found through each medium's identity and link. Per node, it travels with the node, where every
  reader already looks.
