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
- **Contracts:** `contracts/api/memories/` (`media.openapi.yaml`, `favorites.openapi.yaml`, `share-media.openapi.yaml`
  — a guest's media under a share link: the list a guest is served with the uploader keys that open it
  (`listShareMedia`), complete and delete-own; rooted at `/api/shares/{shareId}/media`), generated into
  `me.raddatz.veyq.memories.generated`; the blob transfer an upload runs on is core's `contracts/api/blob.openapi.yaml`,
  a guest's is core's `sharing.openapi.yaml`, and every guest operation passes core's `ShareGuestGate` first. Core's
  resolve serves the link and the shared nodes only.
- **Keys:** `K_mem` per Memory (the share grain) and `K_blob` per blob under it
  ([ADR 0012](../decisions/0012-content-key-hierarchy.md)). These derive as they always have, the documented
  exemption to the application-id context ([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) Decision 9).
- **Domain data:** media and its derivatives, uploader buckets, a Memory's sealed `wrappedMeta` (date, place,
  cover), item favorites. The media tables key on `node_id` (measured:
  `grep -n "node_id" backend/apps/memories/src/main/resources/db/migration/V*__memories_baseline.sql`).
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
| Create a chapter or a Memory | ✗ (`share_not_contributable`) | ✗ (`illegal_scope`: Memories declares no `GuestNodeRules`) | ✓ a `MEMORY` or `FOLDER` under a `FOLDER`; ✗ a chapter (`illegal_scope` inside a Memory, `illegal_structure` under a folder) | ✗ (`share_not_contributable`) |
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
- **Tree operations are core's** (`ShareNodesHandler`, `READ_WRITE` only): inside a `FOLDER` core allows them
  (`FolderGuestNodeRules`) and `NodeKindRules` decides where a kind may live; inside a `MEMORY` or a `CHAPTER`
  Memories declares nothing, so `GuestNodeRules` fails closed. The root is never a guest's to move or rename.
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
2026-10-07). **UNVERIFIED by a test** (read from the code only): a folder guest's create of a chapter directly under a folder answering `illegal_structure`
(`FolderGuestNodeRules` allows, `MemoryNodeKindRules` refuses); a `READ` ↔ `READ_WRITE` flip on a folder link.

## How far it is separated

Measured 2026-10-05 (`ls backend/core/src/main/kotlin/me/raddatz/veyq/core backend/apps/memories/src/main/kotlin/me/raddatz/veyq/memories`):
**the backend is.** Memories is the Gradle module `:apps:memories` (`backend/apps/memories`), depending on `:core`
and never the reverse; the root `backend/` composes both into the one fast-jar. Every class lives in its owner's
package — the platform in `me.raddatz.veyq.core` (the node tree in `core.content`/`core.node`, sharing in `core.sharing`),
Memories in `me.raddatz.veyq.memories` (`media`, `favorites`, `itemfavorites`, `node`) — and core reaches Memories only
through the interfaces Memories implements: it attaches its media through `UploadCompletion`, measures a link's
contributed blobs through `UploadAdmission`, places its `MEMORY` and `CHAPTER` through `NodeKindRules` (core places
only `FOLDER`), declares that a `MEMORY` and a `CHAPTER` may be the root of a share of every role through
`ShareRoots` and takes guest writes under both through `ShareGuestWrites` (one set, `MemoriesNodeKinds.SHARE_ROOTS`). Memories' tests run in `:apps:memories`, beside the
core tests that need an application at runtime; `:core`'s own run with no application at all. **The web is separated
by import direction only**: `features/memory` is the application (core imports it nowhere but the composition root
and asks a registry for what to render), though its directory name and three single-claim slots are still
Memories-shaped (the top-bar tray and the sidebar pins are ordered lists). What core still knows of Memories, and what is open on both sides,
[ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) Consequences lists.
