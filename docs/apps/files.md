# Application: Files

> **Built.** The platform's second application: a file tree on the platform's node tree
> ([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)) — the Gradle module
> `:apps:files` (`me.raddatz.veyq.files`, depending on `:core` only), its contract `contracts/api/files/`, and
> `web/src/features/files`. What a person does with it, and the tests that hold it, are its use-cases:
> [uc.files.member-files](../features/files/use-cases/uc.files.member-files.md),
> [uc.files.share-folder-files](../features/files/use-cases/uc.files.share-folder-files.md),
> [uc.files.drop-box](../features/files/use-cases/uc.files.drop-box.md). This page is how it works.

## What it is

- A **file** is a node kind that holds **one blob**, with its name and metadata sealed. Directories are the platform's
  `FOLDER`: every node carries a key and a `FOLDER` can be a share root with `READ` or `READ_WRITE`
  ([ADR 0080](../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md), built), so Files needs
  no directory kind of its own, and a directory can be shared whole. A file node carries its own key like every node,
  but Files declares it **no share root**: a file is shared through its folder (a single-file link would need a share
  body of its own). Files live in the scope's one folder tree, beside Memories.
- **Web only.** No sync client, no versioning: replacing a file replaces its blob.
- A **drop box** (write-only) is a node of its own with one guest folder per identity (ADR 0080 decision 11,
  § The drop box); a `WRITE_ONLY` link on a plain `FOLDER` stays refused. Viewer and
  contributor shares carry over from the platform; what a guest may do with a `FILE` is the role matrix in § Guests
  (ADR 0080 decision 6; no declaration allows nothing).
- Rides on the platform's blobs, quota and trash; names sit under the parent's key, `K_SC` only at the scope root.

## How a file is stored

- **The node** is core's: a `FILE` node with its sealed name and slug (`fileNameSlug`, case-folded) and its random key
  wrapped under its parent's. Its NAME is the file's name — a rename changes it, a replace keeps it. Files writes
  nothing into the node's generic `wrappedMeta` (inbox #397; it used to hold the file's type, size and date). Files
  adds no column and no table.
- **The blob** is a core attachment: the slot `FILE_CONTENT` of `veyq_node_attachment` (one blob per slot), its
  role the same token. Its `K_blob` is wrapped under the file node's key and sealed bound to the blob id, the node id
  (`ownerId`) and the role ([ADR 0072](../decisions/0072-a-seal-binds-its-object.md), ADR 0080 Decision 13), so no
  new seal purpose exists for Files.
- **The metadata is the blob's** (inbox #397): the picked file's type, its name and its modified time
  (`{type, name, modifiedAt}`, `uploadFile.ts` `fileBlobMeta`) are sealed under the blob's own `K_blob` with core's
  `blob-meta` seal at the upload's declare (`planBlob`'s `meta`, `wrappedBlobMeta` on the start — member and guest
  alike), stored write-once beside the blob and served back on every `FileBlobRef`. So the metadata changes exactly
  when the bytes do, in the one attach transaction: a replace is a new blob with its own metadata, and no state shows
  new bytes beside old metadata. The **size** is not in it: the plaintext size is the blob's sealed size less 28 bytes
  per chunk (`plaintextTotal`), exact. The sealed name is the name the file was *uploaded* under; what a member sees and
  downloads under is the node name (`core/blob/meta.ts` `downloadName`: the node name, else the sealed name, plus the
  sealed type's extension when the name has none). A blob uploaded before the field carries no metadata and lists
  with its size alone.
- **Trash and purge** are core's: deleting the node trashes it with its attachment (the quota still counts it, ADR
  0073 §2); the purge frees the attachment row and the blob through core's eraser.
- **The quota:** a member's upload counts against the tenant quota through core's start, unchanged. That start takes
  only a role some application declares to core's `MemberBlobRoles` (Files: `FILE_CONTENT`, `FilesMemberBlobRoles`) and
  refuses any other whole, `400 invalid_request`, before it reserves anything (inbox #366); a `FILE_CONTENT` start
  names no node, so one for an object that is no `FILE` still opens and ages out unattached. A link's usage
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
  for every live `FILE` directly under the parent, ordered by node id, each `fileBlob` with its `wrappedBlobMeta`. Names
  and keys come from core's node listing; the client joins by `nodeId`. `404 node_not_found` for a parent that is no live node of the scope.
- **A download** is core's `presignBlobDownloads` with the blob id (scope READ). **Delete** is core's node delete
  (trash). **Replace** is another `completeFile` with the blob the client read.

Backend: `me.raddatz.veyq.files.content` — `FilesResource`/`FilesHandler` (the scope-access gate first), core's
dispatcher to `FilesUploadCompletion` (`UploadCompletion` for `FILE`, maps the item's `expectedBlobId` payload onto
`AttachmentStore.attach` — the whole write, nothing beside it), `FilesUploadAdmission`, `FilesStore` (the listing's
reads).

## The web (`web/src/features/files`)

- **Files' section** of core's folder level view (`components/FilesSection.vue`, `section` order 20, after Memories'):
  in every folder and at the scope root, its FILE children as rows (icon, the node name, the plaintext size the blob's
  sealed size gives and the modified time from the blob's sealed meta — `download.ts` `fileFacts`; a blob without
  meta, or one whose meta does not open under this node's key, shows its size alone; a file listed without a blob says
  "not uploaded yet" — none of it reads the node's own payload), with download, replace, rename (core's
  `FolderNameDialog`, through the section's `rename` event) and delete (into core's trash, restored from core's trash
  dialog — or at once from the "moved to the trash" toast's Undo, core's `trashUndo.ts`, inbox #385 (c)). An upload button and a drop onto the section; an empty folder says so. Hidden in map mode, like the
  folders — the mode the level hands in (`LevelSectionProps.viewMode`), which is the map only where Memories' section is
  on screen to draw it (inbox #404; a guest's identity folder without Memories shows its files in the grid). Read-only during a pending deletion: download only. The same section is a share guest's (§ Guests).
- **The upload** is one job of core's upload queue per file (`uploadFile.ts`): create the `FILE` node (or, for a name a
  FILE sibling already holds by `fileNameSlug`, replace that file after the member confirmed — the row's replace asks
  too, naming the file and the picked one, and both questions say the old content cannot be restored, there being no
  version history (owner decision 2026-10-08, inbox #383); "replace anyway" after a peer's replace is itself the
  answer and asks nothing), plan and pump the blob
  under the node's own key with the file's metadata sealed under its `K_blob`, then `completeFile` with
  `expectedBlobId` — nothing is written after it. The status menu shows progress; the section says why a file of its
  folder was not uploaded (a taken name, `stale_file` with "replace anyway", no space). **A name another kind holds is
  not sent** (inbox #400): before anything is asked, every picked name is folded by the file's function and each
  sibling's by its own kind's (core's `collidingSibling`, the dialogs' pre-check), and one that meets a folder, Memory
  or drop box is skipped with a notice naming that sibling by its kind (core's `nameTakenMessage`) — for a guest too, who
  is then not even asked to name itself; a server `409 slug_taken` for such a sibling (the race backstop) is named the
  same way. **A given-up job cleans up**
  (inbox #384): failed after its
  retry or cancelled (core's queue calls the job's `abandon`), a job moves a node it created to the trash once
  `listFiles` shows it still holds no content — best effort; a node it cannot trash stays and its retry fills it. A file
  that holds no content anyway (an upload cut off, e.g. a closed tab; a guest's, which a guest cannot delete) offers an
  upload into it instead of a replace, and a picked file of its name fills it without the replace question; in core's
  trash such a file (core's `attached` false on the trash row, Files' `emptyInTrash`) says it holds no content — its
  restore stays offered (inbox #391). A failure the same file has since been uploaded over is no longer shown. The row's
  replace keeps the file's name (its tooltip says so); a rename selects the name without its extension (`register.ts`
  `renameSelectionEnd`).
- **The download** (`download.ts`) saves under core's `downloadName` (the node name, else the blob's sealed name, plus
  the sealed type's extension when the name has none — the save target itself stays `application/octet-stream`, so a
  peer's sealed type never decides how a browser renders the bytes), presigns the blob's chunks, opens them under the
  node key at most four at a time and streams them in order (`core/blob/stream.ts`) into the save target `shared/saveStream.ts` opens: a file the member
  picks (File System Access, where the browser has it), else the Service Worker's download route
  ([ADR 0047 § Addendum](../decisions/0047-inline-video-playback-range-streaming.md#addendum-the-download-route)),
  else an in-memory `Blob` up to 512 MiB — above that, with no streaming target, the download is refused with a
  message rather than built as a multi-GB Blob. Proven in unit tests only; no real browser has streamed a large file yet
  (inbox #371).

## Guests (a folder share)

A `FILE` is **no share root** (Files declares no `ShareRoots`): a file is shared through a `FOLDER` link above it,
`READ` or `READ_WRITE`. The role matrix (ADR 0080 decision 6):

| A guest of a … link | may |
|---|---|
| `READ` | list the files of the shared subtree, download them |
| `READ_WRITE` | the above; create a `FILE` under a folder of the subtree (core's `createShareNode`; core's `FolderGuestNodeRules` lets any kind `NodeKindRules` admits into a folder); upload into a file below the root, first or replacing its blob (any file below the root, a member's too); rename or move it (core's `updateShareNode`) |
| any | **not** delete a file (nodes store no authorship), **not** upload into a `FOLDER` |

**What the web offers.** One view per kind serves a member and a guest (ADR 0079 Consequences, inbox #401): what it
shows is `filesCapabilities(access, target)` (`web/src/features/files/capabilities.ts`, pinned row by row in
`capabilities.test.ts`) — pure, every right false unless a row grants it, an unknown role or kind grants nothing:

| Right | member | member, pending deletion | member, guest's identity folder | `READ` | `READ_WRITE` | `WRITE_ONLY` |
|---|---|---|---|---|---|---|
| download (a folder's files; a drop box's guests' folders) | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ |
| upload, replace | ✓ | ✗ | ✗ | ✗ | ✓ (not in an identity folder, not in a drop box below the link) | ✓ on its own drop box only |
| rename a file | ✓ | ✗ | ✗ | ✗ | ✗ (the server allows it; offered nowhere yet, inbox #358) | ✗ |
| delete a file, a guest's folder | ✓ | ✗ | ✓ (inbox #391) | ✗ | ✗ | ✗ |
| create a drop box | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ |
| a drop box's links, delete it | links with the instance's public links; delete ✓ | links only | — | ✗ | ✗ | ✗ |

These are the matrix half; a right shows only where the session's data source also implements it
(`web/src/features/files/source.ts`, `sourceCapabilities`: the guest's source has no delete and no rename, and no upload
unless the link is `READ_WRITE`), so the guest's columns are the most a guest page can ever offer.

- **The contract** (tag `share-files` in `files.openapi.yaml`, no auth, behind core's `ShareGuestGate`):
  `listShareFiles` — `GET /api/shares/{shareId}/files`: `{items: [{nodeId, fileBlob|null}]}` for every live `FILE` of
  the live shared subtree (a `WRITE_ONLY` link: empty), each blob with its `wrappedBlobMeta`, names and keys coming
  from core's `resolveShare`; `completeShareFile` — `POST /api/shares/{shareId}/files/{nodeId}/content`,
  `{contributorToken, uploadId, expectedBlobId?}`: `completeFile`'s answers, plus `403 share_not_contributable` for a node that is no
  `FILE` (a folder) and `404 node_not_found` for one outside the live subtree. The upload itself is core's guest
  transport (`startShareBlobUploads` with `nodeId` = the file, `role: FILE_CONTENT`; any other role `400
  invalid_request`, refused before the link's upload budget is spent, inbox #367); the download core's
  `resolveShareBlob`.
- **The meta goes with the bytes.** A guest's file metadata is its blob's, as a member's (§ How a file is stored):
  sealed under `K_blob` and declared at `startShareBlobUploads`, so it lands only when the swap happens, and the guest
  writes no node payload at all (it has no door to one: core's guest create takes none, `updateNode` is a member's).
  `completeShareFile` used to carry a node meta that a Files-side write put in the attach's transaction; both are gone.
- **The write is decided again under the lock** (`ShareGuestGate.admittedInto`): a file trashed or moved out of the
  subtree, or a link revoked, between the guest's start and its completion refuses it (`upload_not_found`).
- **Trash:** a file in the trash — or under a trashed folder — is not listed, its blob is `404` at `resolveShareBlob`
  (core's attachment contributor authorizes live nodes only), an upload into it `404 node_not_found`.
- **Quota:** a guest's attachment names its identity, so the link's usage counts it (core's attachment term; Files'
  `UploadAdmission` reports 0) against the tenant's per-link limit; core's `AttachmentGuestIdentityUsage` keeps the
  guest's identity while a file names it (Files stores no row of its own naming a guest, so it ships no usage).
- **The web:** one view per kind for a member and a guest (inbox #401 S3). Core's page of a public share
  (`core/sharing/ShareTreePage.vue`) is core's member `LevelView` handed the guest's access and tree, so the files are
  the member's `components/FilesSection.vue` and the drop boxes `DropBoxSection`'s rows; a drop box below the link opens
  the member's `DropBoxDetail.vue` (§ The drop box). Every read and write of those views goes through Files' data source
  (`source.ts`): the guest's lists through `listShareFiles`, downloads through the link's presign (`shareChunkUrls`)
  and, for a contributor, uploads one core-queue job per file (`shareUpload.ts`: guest create → guest transport with
  the blob meta → `completeShareFile`) and re-resolves the link afterwards (`access.reload`). So a guest sees the
  member's rows — name, size and date opened under the chain's keys, the download — and a contributor the upload and a
  row's replace (an upload into it, for a file that holds no content), above the list the sentence that it can replace
  any file, the owner's too, and that no version is kept. An upload of a name a file there already holds stays refused
  (`409 slug_taken`, no replace by upload for a guest, owner decision 2026-10-08, inbox #392): the guest's source does
  not turn it into a replace as the member's does, and the notice says a file of that name exists and to use its row's
  replace. **Every replace asks first** (owner decision 2026-10-08, inbox #383) — the contributor's row replace, after
  the file is picked and before the guest is named ("it changes for everyone who sees this folder, the owner too; the
  old content cannot be restored"), as the member's does (§ The web); a file that holds no content asks nothing; after a
  peer's replace (`stale_file`) the page offers "replace anyway" on the current blob, as a member's does. The guest names
  itself once per link through the page's one naming flow (`requestGuest`, the shell's dialog, core's `GuestNaming`).
  No rename and no delete on the guest page (inbox #358; nodes store no authorship). Inside a guest's identity folder
  (§ The drop box) no guest is offered a write (the capabilities). A guest-mode mount of each view proves it calls no
  authenticated client and reads nothing of the member's world (`FilesSection.guest.test.ts`,
  `DropBoxDetail.guest.test.ts`).

## The drop box

A **`FILES_DROPBOX`** is a Files node at the root or in a `FOLDER` (no placement rule admits anything under it), shared
**`WRITE_ONLY` only** (`FilesShareRoots`). Its guests hold no key of it; what they send lands in **identity folders**,
one per guest identity, which are core `FOLDER`s carrying `veyq_node.share_identity_id` (ADR 0080 decision 11).

- **The keys.** The drop box's drop key pair is core's (`veyq_node.sealed_drop_private_key` under the drop box's key,
  the public half per link under `K_share`, set with the first link exactly as for a Memory's uploader link,
  `core/sharing/dropKey.ts`). A guest names itself with its own identity key `K_up`, wrapped to the drop public key
  (`veyq_share_identity.wrapped_identity_key`, seal `upload-identity-key`) and its name sealed under `K_up`. Its folder's
  entry — name (the guest's display name), slug and the folder's fresh key `K_F` — is sealed under **`K_up`** (seals
  `node-name`, `content-key`, epoch 0); everything below the folder is an ordinary subtree under `K_F`. A member (or a
  guest of a link above the drop box) opens drop box key → drop private key → `K_up` → `K_F` (core's tree,
  `childEntryKey`); no new seal purpose, no vector change. A guest-sealed entry that does not open leaves that folder
  out of the member's tree instead of failing it.
- **The guest** (`components/DropBoxGuestForm.vue`, what Files' share body `DropBoxShareBody.vue` renders on the link
  rooted at the drop box; `dropBox.ts`): names itself once — the name stays in the page until the **first file**, which
  creates the identity (`nameDropGuest`, once even for a second pick in flight) and then the folder, so a visitor who
  names itself and leaves leaves nothing on the server (inbox #385 (a)); a reload before that asks for the name again
  (the session then keeps identity, token, `K_up` and the name on the device; through core's one guest-naming component, `core/sharing/GuestNaming.vue`,
  the page says "You are here as …" with a "Not you?" that asks — only once a session exists — then forgets the
  session there and asks for a name again — a new identity, the old one's folder untouched; after a gone link the same
  action reads the link again first — a revoked or deleted one turns the page to "not available", inbox #393), then, with its **first file**, **`ensureShareDropFolder`** — `POST /api/shares/{shareId}/files/drop-folder`,
  Files' operation over core's `IdentityFolders`: the first call creates the folder from the guest's ciphertext, every
  later call (a reload, a second tab, a concurrent first upload) answers the same folder with its stored wrap. Then per
  file core's guest create **inside its own folder** (`createShareNode`: a `WRITE_ONLY` link creates only there, and
  **only `FILE`s** — a sub-folder or any other kind is `422 illegal_scope`; Files declares it through core's
  `WriteOnlyGuestKinds`, keyed on the link's root kind, owner decision 2026-10-08), core's
  guest transport and `completeShareFile` (core's `ShareGuestGate.target`: a `WRITE_ONLY` link names nothing else below
  its root). It sees nothing of the drop box or of others (no node served, `listShareFiles` empty) — only its files of
  this session: being sent, sent (with the size), or not sent with the reason and, where a retry can help, a retry (the
  page has no upload menu, so this list is the only place a guest learns what failed, inbox #381). A gone link — any
  `404`, or `share_not_contributable`/`share_password_incorrect` at any door — turns the page into "this link no longer
  takes files from you" without a picker, and `dropGuestJob` stops the rest of the batch without a request; a store's own
  refusal (`chunk_put_failed:403`) is a failed file, not a gone link.
- **The member** (`components/DropBoxSection.vue`, `DropBoxDetail.vue`, `DropBoxCreateDialog.vue`,
  `DropBoxShareDialog.vue`): creates a drop box from a folder level, mints, copies, switches off (after a question) and
  on again (core's `reactivateShare`; a link a departed member may hold says to make a new one) and deletes its links,
  opens it to its guests' folders — under core's header card (`NodeOverviewCard`, the one a folder opens with: the
  drop box's name, its share and delete; inbox #385 (b)) — (a repeated guest name numbered at display time, never stored: `Anna`, `Anna (2)` in
  creation order — the server-observed `createdAt` of core's listing, then the node id for one millisecond, core's
  `compareByCreation`, by core's `displayNamesOf`, which the folder's heading and breadcrumb use too; the same two folders' URL segments are `anna`, `anna-2` by the same order,
  core's `segmentsOf` in `core/node/location.ts`, which a future folder export uses for its directory names too), opens a folder
  (core's level view, read-only there: download and a delete per file — a content-less one too — through the level's
  `deletable`, no share, and no section that has nothing to show — the host's `hideEmptySections`) and deletes a folder. **Members place nothing** in a drop box or
  a guest's folder (core: no rule places into the drop box; a create, move or rename into an identity folder is
  `illegal_structure`, `NodesHandler`), and only delete or move out — a move out re-wraps and re-slugs under the
  target and ends the folder's identity link. The server allows the move out; the web offers no move yet (inbox #358).
  **Only a member moves out** (owner decision 2026-10-08, inbox #377 (a)): a guest's `updateShareNode` of an identity
  folder or of anything inside one is `422 illegal_structure` (`ShareNodesHandler`, known by core's
  `share_identity_id`, before any application's `GuestNodeRules`), held by
  `DropBoxSliceTest.aGuestOfALinkAboveMovesNothingOutOfADropBox`.
- **In a folder share above** (`READ`/`READ_WRITE`) the drop box **is shown, read-only** (owner decision 2026-10-08,
  inbox #380: the guest was given that tree): the backend serves the drop box's subtree with its drop key to a guest of
  a link above (ADR 0080 decision 11), so core's walk opens the identity folders' names (`childEntryKey`) and their
  files. Core's folder page renders the drop box's `detail` — the member's `DropBoxDetail.vue`, handed the guest's
  access and tree (inbox #401 S3) — never the `WRITE_ONLY` guest's form (Files' claimed share body, which the shell
  mounts for the drop box's own `WRITE_ONLY` link only): the guests' folders with their file counts, a repeated name numbered in node-id order (the
  guest's tree serves no `createdAt`, so `compareByCreation` falls to its id tie-break and the numbers can differ from the
  member's creation order), in the guest's words (`files.dropBox.sharedHint`: only the owner deletes or moves); a folder
  opens on core's folder page, which inside an identity folder offers no guest a write (no 'New folder', Files' section
  only downloads), so it lists the files with their download only. No send form, no upload, no replace, no share, no
  delete, no move — the guest's source carries no delete and the matrix grants a guest neither share nor delete; the
  server refuses each anyway (a create inside an identity folder `illegal_structure` for anyone but its identity, a
  guest move out above). Pinned by `DropBoxDetail.guest.test.ts` (the real registry, router and PrimeVue, DOM clicks,
  guarded clients; red with the write controls let through or the guest's form embedded).
- **Revoking or deleting a link keeps the drop box and everything in it**: an identity a folder names is kept
  (`NodeGuestIdentityUsage`, `fk_node_share_identity`), so the member still opens it; the purge of a trashed drop box
  erases its folders, then the identities nothing names any more.
- **A trashed identity folder** takes nothing: the guest's next `ensureShareDropFolder` and creates there answer
  `403 share_not_contributable` for good — the unique index spans trashed rows, so that identity never gets a second
  folder; the guest starts over under a new identity ("Not you?" / "Start over") — and a member restores the folder from
  the trash. Owner-accepted as built (2026-10-08, inbox #377 (b)).
- **The folder call spends upload budget:** every `ensureShareDropFolder` spends a token of the link's upload rate
  limit (300/min by default), also when it only answers the existing folder — deliberately, so the limit has no gap a
  caller could loop through. Owner-accepted as built (2026-10-08, inbox #377 (d)).

## Operator-visible, beyond the platform's `kind`

File sizes (exact sealed sizes, unless padded), upload and change timestamps, the tree's shape, and when a file's blob
was replaced; of a blob's sealed metadata only its length, padded to one of four 256-byte buckets (a name's rough length
class, not the name). For a drop box: how many guests sent something (one folder per identity), which identity holds which
folder and its files, and when.

## Open questions

- Preview or thumbnail for images: a Files feature (later a derivative through another attachment slot), or out of
  scope?
- An operator-configurable per-file size cap (the quota only, at first).
- The other reversible choices made while building Files wait for the owner's review: inbox #378. (Where the drop box
  differed from ADR 0080 decision 11, inbox #377, the owner ruled 2026-10-08 — § The drop box.)
