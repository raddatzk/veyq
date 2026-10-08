# Application: Files

> **Being built** by `changes/cr-files-application.md`. The smallest possible application: a file tree on the
> platform's node tree ([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)).
> Built so far: the Gradle module `:apps:files` with the node kind `FILE` (at the root or in a `FOLDER`, no children,
> no share root) and its web registration (`web/src/features/files`); a **member's file** end to end: its contract
> `contracts/api/files/files.openapi.yaml` and the upload completion, replace and listing below (F1a), and the web half
> (F1b, § The web below). No guests (F2), no drop box (F3).

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

## How a file is stored

- **The node** is core's: a `FILE` node with its sealed name and slug (`fileNameSlug`, case-folded), its random key
  wrapped under its parent's, and its sealed metadata — MIME type, plaintext size, modified time — in the node's
  generic `wrappedMeta` under the file node's key, written with core's `updateNode` and its `metaVersion`
  ([ADR 0077](../decisions/0077-a-write-carries-the-version-it-read.md)). Files adds no column and no table.
- **The blob** is a core attachment: the slot `FILE_CONTENT` of `veyq_node_attachment` (one blob per slot), its
  role the same token. Its `K_blob` is wrapped under the file node's key and sealed bound to the blob id, the node id
  (`ownerId`) and the role ([ADR 0072](../decisions/0072-a-seal-binds-its-object.md), ADR 0080 Decision 13), so no
  new seal purpose exists for Files.
- **Trash and purge** are core's: deleting the node trashes it with its attachment (the quota still counts it, ADR
  0073 §2); the purge frees the attachment row and the blob through core's eraser.
- **The quota:** a member's upload counts against the tenant quota through core's start, unchanged. A link's usage
  for a `FILE` is core's attachment term; Files' `UploadAdmission` reports 0 for it (core never admits a link by its
  own terms alone).

## The contract (member, `contracts/api/files/files.openapi.yaml`, tag `files`)

- `completeFile` — `POST /api/scopes/{scopeId}/files/{nodeId}/content`, body `{uploadId, expectedBlobId?}`. The upload
  was started with core's `startBlobUploads` (`role: FILE_CONTENT`, `ownerId` = the node). Without `expectedBlobId`
  it fills an empty slot; with it, it replaces exactly that blob (a compare-and-set; the replaced blob is reclaimed in
  the same transaction). `200` the attached `FileBlobRef`; `409 stale_file` with the slot's `currentBlobId` when it
  holds anything else (the upload is spent and reclaimed); `404 node_not_found` (no live node — trashed, another
  scope's), `404 upload_not_found`, `422 illegal_target` (not a `FILE`), `422 upload_mismatch` (started for another
  node or role), `400 budget_exceeded`.
- `listFiles` — `GET /api/scopes/{scopeId}/files?parentId=` (absent: the scope root): `{items: [{nodeId, fileBlob|null}]}`
  for every live `FILE` directly under the parent, ordered by node id. Names, keys and metadata come from core's node
  listing; the client joins by `nodeId`. `404 node_not_found` for a parent that is no live node of the scope.
- **A download** is core's `presignBlobDownloads` with the blob id (scope READ). **Delete** is core's node delete
  (trash). **Replace** is another `completeFile` with the blob the client read.

Backend: `me.raddatz.veyq.files.content` — `FilesResource`/`FilesHandler` (the scope-access gate first), core's
dispatcher to `FilesUploadCompletion` (`UploadCompletion` for `FILE`, maps the item's `expectedBlobId` payload onto
`AttachmentStore.attach`), `FilesUploadAdmission`, `FilesStore` (the listing's reads).

## The web (`web/src/features/files`)

- **Files' section** of core's folder level view (`components/FilesSection.vue`, `section` order 20, after Memories'):
  in every folder and at the scope root, its FILE children as rows (icon, name, size and modified time from the
  sealed meta, else the plaintext size the blob's sealed size gives), with download, replace, rename (core's
  `FolderNameDialog`, through the section's `rename` event) and delete (into core's trash, restored from core's trash
  dialog). An upload button and a drop onto the section; an empty folder says so. Hidden in map mode, like the
  folders. Read-only during a pending deletion: download only.
- **The upload** is one job of core's upload queue per file (`uploadFile.ts`): create the `FILE` node (or, for a name a
  FILE sibling already holds by `fileNameSlug`, replace that file after the member confirmed), plan and pump the blob
  under the node's own key, `completeFile` with `expectedBlobId`, then write the meta (`fileMeta.ts`). The status menu
  shows progress; the section says why a file of its folder was not uploaded (a taken name, `stale_file` with
  "replace anyway", no space) or that its meta is missing.
- **The download** (`download.ts`) presigns the blob's chunks, opens them under the node key at most four at a time and
  saves one `Blob` under the file's name: a file needs about its own size in tab memory (unverified beyond a few MB; a
  streaming save is not built).

## Operator-visible, beyond the platform's `kind`

File sizes (exact sealed sizes, unless padded), upload and change timestamps, the tree's shape, and when a file's blob
was replaced.

## Open questions

- Preview or thumbnail for images: a Files feature (later a derivative through another attachment slot), or out of
  scope?
- An operator-configurable per-file size cap (the quota only, at first).
- The metadata write is not atomic with the blob swap: a client that replaces the blob and then fails before its
  `updateNode` leaves the old MIME type/size beside the new bytes until the next write.
