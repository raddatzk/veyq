# Application: Files (concept)

> **Concept only.** Nothing here is built, refined or planned. The smallest possible application: a file tree on
> the platform's node tree ([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)).

## Idea

- A **file** is a node kind that holds **one blob**, with its name and metadata sealed. Directories are the platform's
  `FOLDER`: every node carries a key and a `FOLDER` can be a share root with `READ` or `READ_WRITE`
  ([ADR 0080](../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md), built), so Files needs
  no directory kind of its own, and a directory can be shared whole. A file node carries its own key like every node,
  so a file can be a share root too, if Files declares it one (`ShareRoots`). One file tree per scope, created on
  demand.
- **Web only.** No sync client, no versioning: replacing a file replaces its blob.
- A **drop box** (write-only) is a node of its own with one guest folder per identity (ADR 0080 decision 11),
  built with Files (inbox #349); a `WRITE_ONLY` link on a plain `FOLDER` is refused until then. Viewer and
  contributor shares carry over from the platform, and what a `READ_WRITE` guest may do with Files' kinds is the role
  matrix this page will hold (ADR 0080 decision 6; no declaration allows nothing).
- Rides on the platform's blobs, quota and trash; names sit under the parent's key, `K_SC` only at the scope root.

## Depends on a decision that is open

`veyq_blob` is scope-keyed, but every blob is referenced today through Memories' tables — `veyq_media.blob_id`,
`veyq_media_derivative.blob_id`, or the cover slot `veyq_node.cover_blob_id` (a node column, no media row) — and its
`K_blob` is wrapped under a Memory's `K_mem` (measured: `grep -rn 'blob_id' backend/core/src/main/resources/db/migration backend/apps/memories/src/main/resources/db/migration`).
Files needs a blob that **a file node references** without going through media, wrapped under a key that is not
`K_mem`: a **blob-role decision** (who may reference a blob, what reclaims it, which key wraps it). Two prerequisites
follow from it and are not built yet: a generic `veyq_node_attachment(node_id, slot, blob_id)` table with real foreign
keys, and a key rule for non-Memories blobs (decided since: no new seal purpose, see below). Until it is made, Files cannot start without
depending on Memories, which the rules forbid.

What the decision does **not** have to change is the transfer: the upload itself is core's and neutral. Starting,
windowing, verifying and downloading a blob, the upload sessions and the quota are `me.raddatz.veyq.core.blob` and
`core.quota`, served by core's `contracts/api/blob.openapi.yaml`; a blob's `K_blob` wrap, `owner_id` and `blob_role`
are opaque strings that code stores and compares and never opens. What a verified upload *becomes* is the
application's, through core's `UploadCompletion` seam (Memories': a media row), and a node kind no application
accepts takes no upload (`illegal_target`). So the decision lands in places none of which is the upload path: the
reference table, and the role and key of the `blob-key` seal. The role is decided (cr-files-application P3-S1/S2):
`BlobRole` is an open token in the contract and the crypto package, Files' is `FILE_CONTENT`, and the seal needs no
new purpose — the file node's random key, wrapped under its parent's, wraps `K_blob`, bound to the node id and the
role ([ADR 0079 §9](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)).

## Operator-visible, beyond the platform's `kind`

File sizes (exact, unless padded), upload and change timestamps, the tree's shape.

## Open questions

- Blob role: the shape of core's generic attachments (inbox #327) — not each application owning its own blob
  table, which [ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) Consequences
  (*What the platform is*) rules out.
- The slug of a file name: containers keep today's slug, files want a stricter case-insensitive uniqueness (inbox
  #350, ADR 0080 *Open questions*).
- Preview or thumbnail for images: a Files feature, or out of scope?
- Is the upper size limit the quota only, or is there a per-file cap?
