# Application: Files

> **Being built** by `changes/cr-files-application.md`. The smallest possible application: a file tree on the
> platform's node tree ([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)).
> Built so far: the Gradle module `:apps:files` with the node kind `FILE` (at the root or in a `FOLDER`, no children,
> no share root) and its web registration (`web/src/features/files`); a **member's file** end to end: its contract
> `contracts/api/files/files.openapi.yaml` and the upload completion, replace and listing below (F1a), and the web half
> (F1b, § The web below); a **folder share with files** (F2, § Guests below). No drop box (F3).

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
  contributor shares carry over from the platform; what a guest may do with a `FILE` is the role matrix in § Guests
  (ADR 0080 decision 6; no declaration allows nothing).
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

## Guests (a folder share, F2)

A `FILE` is **no share root** (Files declares no `ShareRoots`): a file is shared through a `FOLDER` link above it,
`READ` or `READ_WRITE`. The role matrix (ADR 0080 decision 6):

| A guest of a … link | may |
|---|---|
| `READ` | list the files of the shared subtree, download them |
| `READ_WRITE` | the above; create a `FILE` under a folder of the subtree (core's `createShareNode`; core's `FolderGuestNodeRules` lets any kind `NodeKindRules` admits into a folder); upload into a file below the root, first or replacing its blob (any file below the root, a member's too); rename or move it (core's `updateShareNode`) |
| any | **not** delete a file (nodes store no authorship), **not** upload into a `FOLDER` |

- **The contract** (tag `share-files` in `files.openapi.yaml`, no auth, behind core's `ShareGuestGate`):
  `listShareFiles` — `GET /api/shares/{shareId}/files`: `{items: [{nodeId, fileBlob|null}]}` for every live `FILE` of
  the live shared subtree (a `WRITE_ONLY` link: empty), names, keys and metas coming from core's `resolveShare`;
  `completeShareFile` — `POST /api/shares/{shareId}/files/{nodeId}/content`, `{contributorToken, uploadId,
  expectedBlobId?, wrappedMeta?}`: `completeFile`'s answers, plus `403 share_not_contributable` for a node that is no
  `FILE` (a folder) and `404 node_not_found` for one outside the live subtree. The upload itself is core's guest
  transport (`startShareBlobUploads` with `nodeId` = the file, `role: FILE_CONTENT`); the download core's
  `resolveShareBlob`.
- **The meta goes with the bytes.** A guest has no door to a node's `wrappedMeta` (core's guest create takes none,
  `updateNode` is a member's), so `completeShareFile` carries it and `FileContentWrite` writes it in the attach's
  transaction, under the scope lock, only when the swap happened (a conflict or refusal writes none); the node's
  `metaVersion` moves by one.
- **The write is decided again under the lock** (`ShareGuestGate.admittedInto`): a file trashed or moved out of the
  subtree, or a link revoked, between the guest's start and its completion refuses it (`upload_not_found`).
- **Trash:** a file in the trash — or under a trashed folder — is not listed, its blob is `404` at `resolveShareBlob`
  (core's attachment contributor authorizes live nodes only), an upload into it `404 node_not_found`.
- **Quota:** a guest's attachment names its identity, so the link's usage counts it (core's attachment term; Files'
  `UploadAdmission` reports 0) against the tenant's per-link limit; core's `AttachmentGuestIdentityUsage` keeps the
  guest's identity while a file names it (Files stores no row of its own naming a guest, so it ships no usage).
- **The web:** core's folder page of a public share (`core/sharing/FolderShareBody.vue`) renders a kind's
  `shareSection` for its children; Files' is `components/ShareFilesSection.vue` — the files with name, size and date
  opened under the chain's keys, download through the link, and for a contributor an upload button (one core-queue job
  per file, `shareUpload.ts`: guest create → guest transport → `completeShareFile` with the meta) and a replace per
  row. The guest names itself once per link through the page's own dialog (`requestGuest`). No rename on the guest
  page (no kind has one there yet).

## Operator-visible, beyond the platform's `kind`

File sizes (exact sealed sizes, unless padded), upload and change timestamps, the tree's shape, and when a file's blob
was replaced.

## Open questions

- Preview or thumbnail for images: a Files feature (later a derivative through another attachment slot), or out of
  scope?
- An operator-configurable per-file size cap (the quota only, at first).
- The metadata write is not atomic with the blob swap: a client that replaces the blob and then fails before its
  `updateNode` leaves the old MIME type/size beside the new bytes until the next write.
