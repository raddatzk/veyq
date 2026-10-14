# Application: Memories

The first application, and the product center today: photos and media, a journal, places, in **Memories**
nested into **Chapters**. Product language **Memory / Chapter** is unchanged; folders organize — they hold no content,
though each carries a key and can be shared ([CLAUDE.md](../../CLAUDE.md),
[ADR 0080](../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)). The backend half is a
Gradle module of its own; the web half is separated by import direction only (see the end).

## What it owns

- **Node kinds `MEMORY` and `CHAPTER`.** `FOLDER` is the platform's. On the wire a kind is an open token (the
  `NodeKind` of `contracts/api/_common.openapi.yaml`, `^[A-Z][A-Z0-9_]{0,15}$`, which core's specs alias), so the
  two values are Memories' own: `MemoriesNodeKinds` in the backend, `features/memory/kinds.ts` in the web, with their
  rules (`MemoryNodeKindRules`, `registerKind`).
- **Contracts:** `contracts/api/memories/` (`media.openapi.yaml`, `item-favorites.openapi.yaml`, `share-media.openapi.yaml`
  — a guest's media under a share link: the list a guest is served with the uploader keys that open it
  (`listShareMedia`), complete and delete-own; rooted at `/api/shares/{shareId}/media`), generated into
  `me.raddatz.veyq.memories.generated`; the blob transfer an upload runs on is core's `contracts/api/blob.openapi.yaml`,
  a guest's is core's `sharing.openapi.yaml`, and every guest operation passes core's `ShareGuestGate` first. Core's
  resolve serves the link and the shared nodes only.
- **Keys:** `K_mem` per Memory (the share grain) and `K_blob` per blob under it
  ([ADR 0012](../decisions/0012-content-key-hierarchy.md)). These derive as they always have, the documented
  exemption to the application-id context ([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) Decision 9).
- **Domain data:** media and its derivatives, uploader buckets, a Memory's sealed `wrappedMeta` (date, place,
  cover), item favorites (the marks on media; the member's *pin* of a node is core's, `core/favorites`, ADR 0068 §10), the live gallery list on core's item-list primitive (its counters are core's `veyq_node_items_revision`, bumped by every media writer through `NodeItemsRevisions.bump`; ADR 0033, inbox #410). The media tables key on
  `node_id` (measured: `grep -n "node_id" backend/apps/memories/src/main/resources/db/migration/V*__memories_baseline.sql`).
  A cover is core's feature (inbox #341, #411): the node's `COVER` attachment in `veyq_node_attachment`, written by core's
  `setNodeCover` / `removeNodeCover` together with the sealed meta that names it; Memories opts its two kinds in
  (`MemoryNodeKindRules.takesCover`), keeps only its second source (one of its photos, offered to core's one cover dialog through the kind's `cover.extraSource`, inbox #411 follow-up); core's `veyq_node` carries no Memories column. Memories' configuration is its own
  mapping, `veyq.media.*` (`MemoriesMediaConfig`, inbox #342).
- **Screens and behaviour:** gallery and lightbox, upload with client-generated derivatives, covers, date and
  place curation, the map, live updates. Their state is the use-cases and enablers under
  [`docs/features/memory/`](../features/memory/_index.md); this page points at them and does not restate them.

## What it uses from the platform

| Platform primitive | Where it is described |
|---|---|
| The node tree (create, rename, move, slug uniqueness) | [en.memory.tree](../features/memory/enablers/en.memory.tree.md), today filed under the memory feature but a platform concern |
| Node trash | [ADR 0061](../decisions/0061-node-deletion-reversible-node-trash.md) |
| Scopes, memberships, grants, key epochs | [scopes-and-tenancy](../crosscutting/scopes-and-tenancy.md) |
| Public shares (viewer, contributor, uploader) | [`docs/features/sharing/`](../features/sharing/_index.md) |
| Blobs, direct-to-store upload, storage quota | [ADR 0013](../decisions/0013-blob-storage-presigned-direct-to-store.md), [ADR 0073](../decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md) |
| Seals bound to their object | [ADR 0072](../decisions/0072-a-seal-binds-its-object.md) |
| A blob's sealed meta (`blob-meta`): a media original's type, file name and modified time; a derivative's type | [ADR 0072](../decisions/0072-a-seal-binds-its-object.md) Decision 2, [en.memory.media](../features/memory/enablers/en.memory.media.md) (inbox #397) |
| Concurrent writes to a sealed blob | [ADR 0077](../decisions/0077-a-write-carries-the-version-it-read.md) |

## What a guest may do — the role matrix

ADR 0080 decision 6 has each application write down what a link's role lets a guest do with its kinds; a kind with
no declaration allows nothing. This is Memories' answer, measured from the code after cr-node-key-hierarchy 5a.5
(2026-10-07), for a link rooted at a `MEMORY`, at a `CHAPTER`, and — for the Memories content it reaches — at core's
`FOLDER`. The roles: `READ` (viewer, [ADR 0029](../decisions/0029-public-viewer-share.md)), `READ_WRITE`
(contributor, [ADR 0030](../decisions/0030-contributor-public-share.md)), `WRITE_ONLY` (uploader,
[ADR 0031](../decisions/0031-uploader-public-share.md)). ✓ allowed, ✗ refused (the answer in brackets).

| Operation | `READ` | `READ_WRITE` on `MEMORY` / `CHAPTER` | `READ_WRITE` on `FOLDER` | `WRITE_ONLY` on `MEMORY` / `CHAPTER` |
|---|---|---|---|---|
| Read: the subtree's nodes, its media, their blobs | ✓ | ✓ | ✓ (the Memories below it with their media) | ✗ (no node, empty media lists, blob `404`) |
| Upload media (start, window, complete) | ✗ (`share_not_contributable`) | ✓ into the root, or into a chapter below a `MEMORY` root (`nodeId`) | ✓ into any `MEMORY` or `CHAPTER` below the folder (`nodeId`); ✗ into a folder, the root itself included (`share_not_contributable`) | ✓, into the root node only (another `nodeId`: `share_not_contributable`) |
| Delete the own media | ✗ (`share_not_contributable`) | ✓ | ✓ (in any Memory or chapter of the subtree) | ✓ |
| Delete others' media | ✗ | ✗ (`404 share_media_not_found`) | ✗ | ✗ (`404 share_media_not_found`) |
| Edit a medium's meta | ✗ (no guest operation) | ✗ (no guest operation) | ✗ | ✗ |
| Rename a node | ✗ (`share_not_contributable`) | ✗: the root never (`illegal_structure`), a chapter inside a Memory `illegal_scope` | ✓ a child of a `FOLDER`; ✗ the root, a chapter (`illegal_scope`) | ✗ (`share_not_contributable`) |
| Cover, date, place of a node (`wrappedMeta`, cover blob) | ✗ (no guest operation) | ✗ (no guest operation) | ✗ (no guest operation) | ✗ |
| Create a chapter or a Memory | ✗ (`share_not_contributable`) | ✗ (`illegal_scope`: Memories declares no `GuestNodeRules`) | ✗ a `MEMORY` or a chapter (`illegal_scope`; owner decision 2026-10-14: a guest creates no application's node; core lets a guest create a `FOLDER` and a kind its application declares guest-creatable, `GuestCreatableKinds`, and Memories declares none) | ✗ (`share_not_contributable`) |
| Move a node | ✗ (`share_not_contributable`) | ✗ (`illegal_scope`; the root `illegal_structure`) | ✓ a `FOLDER` or `MEMORY` between folders of the subtree; ✗ a chapter out of or into a Memory (`illegal_scope`), anything out of the subtree | ✗ (`share_not_contributable`) |
| Delete a node | ✗ (no guest operation) | ✗ (no guest operation) | ✗ (no guest operation, ADR 0080 decision 6) | ✗ |

What the cells rest on:

- **A `CHAPTER` link is a smaller `MEMORY` link.** The chapter has its own `K_mem`, its media's `K_blob` hang under
  it, and the resolve, the media list and the blob GET all read the subtree under the share's root
  (`SharingStore.subtree`, `ShareGuestGate.viewGate`, `SharingStore.authorizedBlob`): a chapter guest is served the
  chapter (without its own entry, which is sealed under the Memory's key) and nothing of the Memory or a sibling
  chapter (`ChapterShareSliceTest`, bite test 21). `NodeKindRules` puts a chapter only directly inside a Memory, so a
  chapter link has no node below its root.
- **A role applies to every node below the root as Memories allows it** (ADR 0080 decision 6, owner 2026-10-07;
  cr-node-key-hierarchy 5a.5). A guest's start, every window and the completion name an optional target `nodeId`;
  absent, it is the root, as before. Core checks a named target (`ShareGuestGate.target`): live, inside the share's
  subtree (else `404 node_not_found`, the same for a node outside as for none), the root live (else
  `share_not_contributable`); and core dispatches the writer (`ShareGuestWrites`), the per-link measure
  (`UploadAdmission`) and the completion by the TARGET's kind — Memories takes `MEMORY` and `CHAPTER`, so a folder,
  the root of a folder link included, is `share_not_contributable`. The completion decides the target again in each
  item's write, under the scope lock and then the share row's (`ShareGuestGate.admittedInto`): a target trashed or
  moved out of the subtree since, or a link revoked since, refuses the item (`upload_not_found`,
  `GuestCompleteTargetRaceSliceTest`). A `MEMORY` link's contributor so uploads into the Memory or one of its
  chapters, a `FOLDER` link's into any Memory or chapter below the folder, a `CHAPTER` link's into the chapter. The
  per-link limit counts the link's media by identity, wherever in the subtree they lie; a guest read serves a
  contributor's media below the root without its identity, as on the root (ADR 0030 point 8). A guest's delete of
  its own reaches its media anywhere in the subtree, and none a member has since moved out of it.
- **A `WRITE_ONLY` link writes into its root only** (another `nodeId`: `share_not_contributable`): its guest holds no
  key below the root ([ADR 0031](../decisions/0031-uploader-public-share.md) point 7).
  A `WRITE_ONLY` link seals to the root node's own drop key, set on that node by its first uploader link
  (`SharingStore.create`), and every reader of the node — a member's media page and buckets, a viewer link — is served
  what opens it. A guest's name reaches members only; a guest read serves a `WRITE_ONLY` identity's wrapped key and
  never a `READ_WRITE` identity ([ADR 0030](../decisions/0030-contributor-public-share.md) point 8).
- **A `READ_WRITE` guest's name is sealed under the link ROOT's key**, whichever node it writes in (core's
  `createGuestIdentity`, one identity per link). A folder link's guest who uploads into a Memory below, or a Memory
  link's into a chapter, therefore names itself under a key ABOVE the node its bucket is on: a member opens it with
  the node's key or an ancestor's, through the chain (`openBucketName`'s `ancestorKeys`, `contentKeyChain`; web,
  cr-node-key-hierarchy 5b.4b).
- **Tree operations are core's** (`ShareNodesHandler`, `READ_WRITE` only): inside a `FOLDER` core decides
  (`FolderGuestNodeRules`) — a move or a rename of any kind, a create of a `FOLDER` or of a kind its application declares
  guest-creatable (`GuestCreatableKinds`; Memories declares none, so no `MEMORY` or `CHAPTER` is `illegal_scope`) — and
  `NodeKindRules` decides where a kind may live; inside a `MEMORY` or a `CHAPTER` Memories declares nothing, so
  `GuestNodeRules` fails closed. The root is never a guest's to move or rename.
- **No guest route exists** for a medium's meta (`updateMediaMeta`), a node's `wrappedMeta` or cover, or a node
  delete: `share-media.openapi.yaml` holds list, complete and delete-own; `sharing.openapi.yaml`'s guest node schemas
  carry no `wrappedMeta`.
- A `WRITE_ONLY` link on a `FOLDER` cannot be created (`ShareRoots`: a drop box is Files' own node, ADR 0080
  decision 11), and a role change never reaches or leaves `WRITE_ONLY` (`invalid_role_change`); `READ` ↔
  `READ_WRITE` flips in place on all three kinds.

**Verified by a test vs. by reading the code.** Every `MEMORY` and `CHAPTER` cell has a slice test
(`ChapterShareSliceTest`, `ShareRootsSliceTest`, `ShareNodesSliceTest`, the share media suites, `SubtreeUploadSliceTest`
for an upload into a chapter below a Memory link and the `WRITE_ONLY` root-only rule). The `FOLDER` column's
upload, delete-own, rename, create and move cells are tested (`SubtreeUploadSliceTest`, `ShareRootsSliceTest`,
`ShareNodesSliceTest`, `ShareNodesCoreSliceTest`). A `READ` folder guest is served the media of a Memory below the folder (e2e `folderShare.e2e.test.ts`,
2026-10-07). A folder guest's create of a `MEMORY` or a chapter under a folder answering `illegal_scope` is
`ShareNodesSliceTest.aGuestCreatesNoMemoryInAFolder`. **UNVERIFIED by a test** (read from the code only): a `READ` ↔
`READ_WRITE` flip on a folder link.

### What the web offers — the capability matrix

One view per kind serves a member and a guest ([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)
Consequences, inbox #401); what it shows is `memoryCapabilities(access, target)` (`web/src/features/memory/capabilities.ts`,
pinned row by row in `capabilities.test.ts`): pure, every right false unless a row grants it, an unknown role or kind
grants nothing. On an opened `MEMORY` or `CHAPTER` (the detail):

| Right | member | member, scope pending deletion | `READ` | `READ_WRITE` | `WRITE_ONLY` |
|---|---|---|---|---|---|
| navigate (Chapters tab), download | ✓ | ✓ | ✓ | ✓ | ✗ |
| upload into the node on screen | ✓ | ✗ | ✗ | ✓ | ✓ |
| delete media | any (trash) | ✗ | ✗ | own (purge) | own (purge) |
| create a chapter | ✓ on a `MEMORY` only | ✗ | ✗ | ✗ | ✗ |
| date, place, cover (the node's sealed meta; the automatic reconcile too) | ✓ | ✗ | ✗ | ✗ | ✗ |
| rename, delete the node | ✓ | ✗ | ✗ | ✗ | ✗ |
| participants (opaque user ids in the node's sealed meta, [ADR 0083](../decisions/0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md); a Memory in a tenant or group scope) | ✓ shown and edited; the profile at `#/people/<id>` (its Memories as tiles, rows or the level's map) | ✓ shown, not edited | ✗ not shown | ✗ not shown | ✗ not shown |
| move the node (core's move dialog, inbox #358) | ✓ a Memory to the root or a folder, a chapter into another Memory | ✗ | ✗ | ✓ a Memory a folder of the subtree holds, between the subtree's folders; ✗ a chapter, the link's root | ✗ |
| item marks, media trash, contributions organizer | ✓ | ✓ | ✗ | ✗ | ✗ |
| share (and the uploader link) | the instance's public (and uploader) links | the same | ✗ | ✗ | ✗ |

At a folder level (Memories' rows in core's one list): a member creates a Memory (not during a pending deletion, never in a guest's
identity folder); a share guest creates none — the "New" menu offers a guest no Memory (owner decision 2026-10-14, ADR 0080 decision 6); every role that reads opens
one. A share right also needs the kind's descriptor to bring a share dialog (`targetOf`, core's), and a guest's move
the node's parent to be a folder the link serves (`targetOf`'s `parentKind`: unknown at the link's root). A level's selection
(core's Select mode, inbox #421) reads the same matrix per Memory (`LevelRow.selection`): it moves with the selection where
`move` says yes and is deleted with it where `deleteNode` does (core's delete, as the Memory's own page asks the host for),
so a guest's selection moves a Memory a folder holds and deletes none. These are the matrix
half; a right shows only where the session's data source also implements it (ADR 0079), so the guest's columns are the
most a guest page can ever offer. Memories' source is `web/src/features/memory/source.ts` (inbox #401 S4): the
member's wraps the scope's calls; the guest's reads the link's media listing (each node's own media from the subtree's),
opens every tier through the share presign, and — for `READ_WRITE` only — uploads into the node on screen, naming it
(the root names none) and sealing under that node's key from the chain, and purges what this device sent
(`ownShareMedia.ts`). It has no door for the media trash, the node's date, place and cover, a medium's meta, the item
marks, the organizer, the live poll or the heavy EXIF — so a guest's view re-reads its own listing after its own
upload or delete, and sees another guest's upload on its next open; a `WRITE_ONLY` link uploads and deletes through its
drop page, never this source.

**A guest sees the member's views (inbox #401).** At a folder level `memoryRows` (`web/src/features/memory/levelRows.ts`), handed a guest's access and tree,
gives core's list the Memories of a shared folder as rows and tiles (a cover tile in the grid), each date and place unsealed under the node's key the link's
chain opened, each cover through the link's own reads (core's cover store, `core/node/cover/coverStore.ts`: an uploaded cover blob through
`resolveShareBlob`, which serves a node's cover blob below the share; a chosen photo's thumbnail by Memories' `cover.load`,
`coverLoad.ts`, from `listShareMedia`, read once and only when a cover needs it), the map over the opened subtree. A Memory or a chapter — below a shared
folder, or the root of a Memory or chapter link — is the member's `MemoryDetail` (core's `ShareTreePage` mounts the
kind's `detail`): the gallery, the lightbox with the download, the Chapters tab to navigate into (through the tree's
link, `#k=` kept); for a contributor besides, the upload into the node on screen and the delete of its own photos on
the tile and in the lightbox. It shows the date and place the members sealed but never reconciles them (the member's
detail settles them automatically on a gallery change; that writes the node's meta, which no guest may). Nothing of the
member's world is read — no `treeStore`, `activeSpace`, the member's cover store, the pin store or item-mark store
(`ShareView.memory.test.ts` records every export of those and every authenticated client). The one page a guest gets
that a member has not is a `WRITE_ONLY` link's drop page (`MemoryShareBody`: name, warning, add photos, the session's
own tiles) — its guest holds no key to render anything the member's way. A link's guest names itself through core's
one naming component (`core/sharing/GuestNaming.vue`): a contributor in the shell's dialog at its first write, an
uploader on the drop page; "Not you?" — the shell's line for a contributor, the drop page's own for an uploader — asks,
then forgets the device's session.

## How far it is separated

Measured 2026-10-05 (`ls backend/core/src/main/kotlin/me/raddatz/veyq/core backend/apps/memories/src/main/kotlin/me/raddatz/veyq/memories`):
**the backend is.** Memories is the Gradle module `:apps:memories` (`backend/apps/memories`), depending on `:core`
and never the reverse; the root `backend/` composes both into the one fast-jar. Every class lives in its owner's
package — the platform in `me.raddatz.veyq.core` (the node tree in `core.content`/`core.node`, sharing in `core.sharing`),
Memories in `me.raddatz.veyq.memories` (`media`, `itemfavorites`, `node`) — and core reaches Memories only
through the interfaces Memories implements: it attaches its media through `UploadCompletion`, measures a link's
contributed blobs through `UploadAdmission`, places its `MEMORY` and `CHAPTER` through `NodeKindRules` (core places
only `FOLDER`), declares that a `MEMORY` and a `CHAPTER` may be the root of a share of every role through
`ShareRoots` and takes guest writes under both through `ShareGuestWrites` (one set, `MemoriesNodeKinds.SHARE_ROOTS`). Memories' tests run in `:apps:memories`, beside the
core tests that need an application at runtime; `:core`'s own run with no application at all. **The web is separated
by import direction only**: `features/memory` is the application (core imports it nowhere but the composition root
and asks a registry for what to render; the folder level view is core's, Memories contributes ROW DATA, not a section
(inbox #407): its row factory `memoryRows` answers, for each MEMORY and CHAPTER at the level, a row — the cover as the
leading visual (large on a tile; core resolves the cover, `host.covers`, inbox #411), the name, the meta line "Memory" plus the details date range and place (`details`, `memoryRowDetails.ts`: each left out when the Memory has none, the same for a share guest, [apps/README](README.md#how-an-application-shows-facts-in-a-levels-rows-and-tiles)), a primary click that opens it, rename and move in
the row's menu (the favorite heart on the row is core's own action, the same for every kind, inbox #409, and so is the ONE "Cover" action - "Set cover" / "Change cover" - that opens core's cover dialog, where the image is chosen, cropped and removed, inbox #411) —
and the map as its own presentation; core draws the list, orders it (folders first, then everything by name, mixed with
files and drop boxes) and offers the presentations grid, list and map in the level's toolbar, inbox #370; one list of
the three, `memoryViews.ts`), and its create and share dialogs ride on its kinds
(a rename is core's, as a folder's create is), though its directory name is still Memories-shaped (the top-bar tray and
the sidebar pins are ordered lists, today unused by Memories: the sidebar's Favorites section is core's; the upload status in the top bar is core's, not Memories' tray item, and lists Memories'
batches with every other application's). What core still knows of Memories, and what is open on both sides,
[ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) Consequences lists.
