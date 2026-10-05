# Application: Files (concept)

> **Concept only.** Nothing here is built, refined or planned. The smallest possible application: a file tree on
> the platform's node tree ([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)).

## Idea

- A **file** is a node kind that holds **one blob**, with its name and metadata sealed. Folders are the
  platform's existing ones. One file tree per scope, created on demand.
- **Web only.** No sync client, no versioning: replacing a file replaces its blob.
- An **uploader share is a drop box** (write-only, [ADR 0031](../decisions/0031-uploader-public-share.md));
  viewer and contributor shares carry over from the platform.
- Rides on the platform's blobs, quota and trash, and on `K_SC` for names.

## Depends on a decision that is open

`veyq_blob` is scope-keyed, but every blob is referenced today through Memories' tables — `veyq_media.blob_id`,
`veyq_media_derivative.blob_id`, or the cover slot `veyq_node.cover_blob_id` (a node column, no media row) — and its
`K_blob` is wrapped under a Memory's `K_mem` (measured: `grep -rn 'blob_id' backend/core/src/main/resources/db/migration backend/apps/memories/src/main/resources/db/migration`).
Files needs a blob that **a file node references** without going through media, wrapped under a key that is not
`K_mem`: a **blob-role decision** (who may reference a blob, what reclaims it, which key wraps it). Two prerequisites
follow from it and are not built yet: a generic `veyq_node_attachment(node_id, slot, blob_id)` table with real foreign
keys, and a seal purpose plus key rule for non-Memories blobs. Until it is made, Files cannot start without
depending on Memories, which the rules forbid.

What the decision does **not** have to change is the transfer: the upload itself is core's and neutral. Starting,
windowing, verifying and downloading a blob, the upload sessions and the quota are `me.raddatz.veyq.core.blob` and
`core.quota`, served by core's `contracts/api/blob.openapi.yaml`; a blob's `K_blob` wrap, `owner_id` and `blob_role`
are opaque strings that code stores and compares and never opens. What a verified upload *becomes* is the
application's, through core's `UploadCompletion` seam (Memories': a media row), and a node kind no application
accepts takes no upload (`illegal_target`). So the decision lands in three places, none of them the upload path:
the reference table, the closed `BlobRole` enum (contract, crypto package, its web re-export), and the `blob-key`
seal's key.

## Operator-visible, beyond the platform's `kind`

File sizes (exact, unless padded), upload and change timestamps, the tree's shape.

## Open questions

- Blob role: the shape of core's generic attachments (inbox #327) — not each application owning its own blob
  table, which [ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) Consequences
  (*What the platform is*) rules out.
- Which key seals a file: `K_SC` directly, or a per-file key so one file can be shared alone?
- Preview or thumbnail for images: a Files feature, or out of scope?
- Is the upper size limit the quota only, or is there a per-file cap?
