# Application: Memories

The first application, and the product center today: photos and media, a journal ([concept](journal.md)), places, in **Memories**
nested into **Chapters**. Product language **Memory / Chapter** is unchanged; folders organize — they hold no content,
though each carries a key and can be shared ([CLAUDE.md](../../CLAUDE.md),
[ADR 0080](../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)). The backend half is a
Gradle module of its own; the web half is separated by import direction only (see the end).

## What it owns

- **Node kinds `MEMORY` and `CHAPTER`.** `FOLDER` is the platform's. On the wire a kind is an open token (the
  `NodeKind` of `contracts/api/_common.openapi.yaml`, `^[A-Z][A-Z0-9_]{0,15}$`, which core's specs alias), so the
  two values are Memories' own: `MemoriesNodeKinds` in the backend, `features/memories/kinds.ts` in the web, with their
  rules (`MemoryNodeKindRules`, `app.kind`).
- **Contracts:** `contracts/api/memories/` (`media.openapi.yaml`, `item-favorites.openapi.yaml`, `share-media.openapi.yaml`
  — a guest's media under a share link: the list a guest is served with the uploader keys that open it
  (`listShareMedia`), complete and delete-own; rooted at `/api/shares/{shareId}/media`), generated into
  `me.raddatz.veyq.memories.generated`; the blob transfer an upload runs on is core's `contracts/api/blob.openapi.yaml`,
  a guest's is core's `sharing.openapi.yaml`, and every guest operation passes core's `ShareGuestGate` first. Core's
  resolve serves the link and the shared nodes only.
- **Keys:** `K_node` per Memory (the share grain) and `K_blob` per blob under it
  ([ADR 0012](../decisions/0012-content-key-hierarchy.md)). These derive as they always have, the documented
  exemption to the application-id context ([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) Decision 9).
- **Domain data:** media (Memories' record: its sealed meta and EXIF, its bucket, its place, its trash) and their
  bytes — an original and its derivatives, blobs of core's blob service held by the medium, its owner's content
  ([ADR 0113](../decisions/0113-memories-media-are-blobs-of-cores-blob-service.md), [Applications § Core's blob service](README.md#cores-blob-service)) —, uploader buckets, a Memory's sealed `wrappedMeta` (date, place,
  cover, banner), item favorites (the marks on media, [en.memory.favorites](../features/memory/enablers/en.memory.favorites.md); the member's *pin* of a node is core's, `core/favorites`, ADR 0068 §10), the live gallery list on core's item-list primitive (its counters are core's `veyq_node_items_revision`, bumped by every media writer through `NodeItemsRevisions.bump`; ADR 0033, inbox #410). The media tables key on
  `node_id` (measured: `grep -n "node_id" src/backend/apps/memories/src/main/resources/db/migration/V*__memories_baseline.sql`).
  A cover is core's feature (inbox #341, #411): the node's `COVER` attachment in `veyq_node_attachment`, written by core's
  `setNodeCover` / `removeNodeCover` together with the sealed meta that names it; Memories opts its two kinds in
  (`MemoryNodeKindRules.takesCover`) and adds one seam, `cover.findStored` (`storedMatch.ts`: an uploaded image equal to a gallery original shares that medium's PREVIEW, ADR 0013 § 5); a cover is set only by an upload in core's dialog; core's `veyq_node` carries no Memories column. Memories has no configuration key of its own: a medium waits out core's
  node trash retention, the window its blobs are kept for (ADR 0113 decision 5).
  A **banner** is Memories' own ([uc.memory.banner](../features/memory/use-cases/uc.memory.banner.md),
  [ADR 0102](../decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md)): the node's
  `BANNER` and `BANNER_POSTER` attachments in core's `veyq_node_attachment` (Memories' member-only roles,
  `MemoryBlobRoles`), written by Memories' `setMemoryBanner` / `removeMemoryBanner` together with the sealed meta that
  names them (`MemoryBannerStore`, built from core's `AttachmentStore` and `NodesStore`); it adds no table, so every path
  core runs for an attachment carries it.
  A **GPX track** is a medium ([uc.memory.tracks](../features/memory/use-cases/uc.memory.tracks.md),
  [ADR 0130](../decisions/0130-a-gpx-track-is-a-medium-of-its-memory-drawn-on-its-map.md)): its original is the GPX file,
  its `blob-meta` typed `application/gpx+xml`; the server stores it as any medium and cannot tell it from one. The client
  knows it by that meta (`track/gpx.ts`), keeps it out of every view of photos (`useMediaGalleryLive`'s `items` and
  `tracks`, `search.ts`'s documents) and reads and draws it on the Memory's Map tab only; it adds no contract, table or role.
- **Screens and behaviour:** gallery and lightbox, upload with client-generated derivatives, covers, banners, date and
  place curation, the map, a Memory's GPX tracks on its Map tab, live updates. Their state is the use-cases and enablers under
  [`docs/features/memory/`](../features/memory/_index.md); this page points at them and does not restate them.

## Places and the map

A place ([uc.memory.place](../features/memory/use-cases/uc.memory.place.md)) and the maps reach two outside services,
both third parties a member's browser calls **directly** — never through the veyq backend, never an operator-run
instance, so the veyq operator sees neither the viewport nor a coordinate
([ADR 0016](../decisions/0016-geocoding-provider-neutrality.md), [ADR 0018](../decisions/0018-map-tiles-provider-neutrality.md);
[security-model](../crosscutting/security-model.md) lists the boundary):

- **Map tiles** (`memories.map`): raster tiles from a tile-URL template, with its attribution, drawn by Leaflet — the
  place editor's map, a level's map, a person's profile map and a Memory's Map tab, which draws its GPX tracks beside its
  photos ([uc.memory.tracks](../features/memory/use-cases/uc.memory.tracks.md)). A tile request carries the viewport `{z,x,y}` and the
  client address, no veyq identity and no Memory context.
- **The reverse geocoder** (`memories.places`): a bare coordinate in, the address hierarchy out (road … country) in the
  reader's locale; the member picks the level or types a name. There is no forward search.

**The addresses are the Server Owner's.** A fresh instance comes configured with the reference providers —
OpenStreetMap's standard tile server and public Nominatim — and the Server Owner may change or remove either
([uc.admin.configure-external-services](../features/admin/use-cases/uc.admin.configure-external-services.md)). An entry
is active only while its address is configured and its switches are on, and the served CSP names its origin only then
([en.platform.capability-register § What "off" does](../features/platform/enablers/en.platform.capability-register.md#what-off-does)).

**The consent, as built** ([ADR 0016](../decisions/0016-geocoding-provider-neutrality.md)). One consent covers both,
because tiles gate geocoding. It is off by default and kept per browser, not per account, in every space; the server
holds no consent state, and no request leaves before it. It is given in context — the place editor or a map offers it where the map would stand, before
the first tile loads (the place editor is the name field meanwhile, uc.memory.place A6) — or under Settings → *Map services*, which names the tile host and the geocoder host this
server configured (or that none is) and what each receives: the part of the map in view or a place's coordinate, and the
device's IP address. The consent names those hosts and holds only for them: a new tile host, or a new or changed
geocoder host, shows the prompt again, naming the new host, and nothing reaches it until the member agrees (a geocoder
the Server Owner removed asks nothing; a consent stored before hosts were recorded is asked once more, and the settings
show which hosts an earlier consent named). It is withdrawn there, with the same switch, or by *Turn map services off* on any
map (a level's, a person's, the place editor's, the photo panel's and a Memory's Map tab), beside the attribution — the one control a share guest, who has no
settings, withdraws with. A withdrawal acts at once: the flag is removed, every open map unmounts with its tile layer
(another tab of the browser follows), no geocoding request leaves, and the consent prompt stands where the map was.
Nothing stored is touched: resolved names stay in the Memories. A member without the consent sees resolved names as
text. A space owner's enabling notice is decided and not built (ADR 0016).

**Request discipline** ([ADR 0016 § Request discipline](../decisions/0016-geocoding-provider-neutrality.md#request-discipline--the-providers-policy-is-part-of-the-decision-not-an-optimisation)):
a resolved name is sealed into the node's meta (a photo's into its tier) and read from there. The client asks the
provider once per place, keyed on the coordinate rounded to three decimals (about 110 m), queues requests at least one
second apart, and resolves a failure to nothing, with no retry. The app shell's `Referrer-Policy` keeps the instance's
origin in `Referer`, which is what identifies it to the provider.

**Without the map** (`memories.map` off or not configured) there is nothing to consent to: the place editor opens with
no map, a place is a typed name positioned by the photos' GPS or an earlier pin, and no geocoder is asked, since tiles
still gate geocoding. With `memories.map` and `memories.places` both off a place still works, named by hand.

## What it uses from the platform

| Platform primitive | Where it is described |
|---|---|
| The node tree (create, rename, move, slug uniqueness) | [en.platform.node-tree](../features/platform/enablers/en.platform.node-tree.md) (core's) |
| Node trash | [ADR 0061](../decisions/0061-node-deletion-reversible-node-trash.md) |
| Scopes, memberships, grants, key epochs | [scopes-and-tenancy](../crosscutting/scopes-and-tenancy.md) |
| Public shares (viewer, contributor, uploader) | [`docs/features/sharing/`](../features/sharing/_index.md) |
| Blobs, direct-to-store upload, storage quota | [ADR 0013](../decisions/0013-blob-storage-presigned-direct-to-store.md), [ADR 0073](../decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md) |
| Seals bound to their object | [ADR 0072](../decisions/0072-a-seal-binds-its-object.md) |
| A blob's sealed meta (`blob-meta`): a media original's type, file name and modified time; a derivative's type | [ADR 0072](../decisions/0072-a-seal-binds-its-object.md) Decision 2, [en.memory.media](../features/memory/enablers/en.memory.media.md) (inbox #397) |
| Concurrent writes to a sealed blob | [ADR 0077](../decisions/0077-a-write-carries-the-version-it-read.md) |

## What a guest may do — the role matrix

ADR 0080 decision 6 has each application write down what a link's role lets a guest do with its kinds; a kind with
no declaration allows nothing. This is Memories' answer, measured from the code, for a link rooted at a `MEMORY`, at a
`CHAPTER`, and — for the Memories content it reaches — at core's
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
| Cover, banner, date, place of a node (`wrappedMeta`, cover and banner blobs) | ✗ (no guest operation; an upload start in `COVER`, `BANNER` or `BANNER_POSTER` is `invalid_request`) | ✗ (no guest operation) | ✗ (no guest operation) | ✗ |
| Create a chapter or a Memory | ✗ (`share_not_contributable`) | ✗ (`illegal_scope`: Memories declares no `GuestNodeRules`) | ✗ a `MEMORY` or a chapter (`illegal_scope`; ADR 0080 decision 6: a guest creates no application's node; core lets a guest create a `FOLDER` and a kind its application declares guest-creatable, `GuestCreatableKinds`, and Memories declares none) | ✗ (`share_not_contributable`) |
| Move a node | ✗ (`share_not_contributable`) | ✗ (`illegal_scope`; the root `illegal_structure`) | ✓ a `FOLDER` or `MEMORY` between folders of the subtree; ✗ a chapter out of or into a Memory (`illegal_scope`), anything out of the subtree | ✗ (`share_not_contributable`) |
| Delete a node | ✗ (no guest operation) | ✗ (no guest operation) | ✗ (no guest operation, ADR 0080 decision 6) | ✗ |

What the cells rest on:

- **A `CHAPTER` link is a smaller `MEMORY` link.** The chapter has its own `K_node`, its media's `K_blob` hang under
  it, and the resolve, the media list and the blob GET all read the subtree under the share's root
  (`SharingStore.subtree`, `ShareGuestGate.viewGate`, `SharingStore.authorizedBlob`): a chapter guest is served the
  chapter (without its own entry, which lives under the Memory's key) and nothing of the Memory or a sibling
  chapter (`ChapterShareSliceTest`, uc.sharing.share-public-viewer:chapter-share-root). A chapter is a share root for
  every role through Memories' one share-root set (`MemoriesNodeKinds.SHARE_ROOTS`, which `MemoryShareRoots`,
  `MediaShareGuestWrites` and `MediaUploadAdmission` all answer from), and the web offers its link through the
  Memory's own dialogs (`shareable: true`, `register.ts`; ADR 0080 decision 13). `NodeKindRules` puts a chapter only
  directly inside a Memory, so a chapter link has no node below its root.
- **A role applies to every node below the root as Memories allows it** (ADR 0080 decision 6;
  uc.sharing.share-contributor:rights-inherit-below-a-folder). A guest's start, every window and the completion name an optional target `nodeId`;
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
  what opens it. A guest's name reaches members only, and is shown to them marked as a guest's — "Ben (guest)" in the
  lightbox's uploader and the contributor's bucket (core's `guestDisplayName`, inbox #433); a guest read serves a `WRITE_ONLY` identity's wrapped key and
  never a `READ_WRITE` identity ([ADR 0030](../decisions/0030-contributor-public-share.md) point 8).
- **A `READ_WRITE` guest's name is sealed under a field key derived from the link ROOT's key**, whichever node it writes in (core's
  `createGuestIdentity`, one identity per link). A folder link's guest who uploads into a Memory below, or a Memory
  link's into a chapter, therefore names itself under a key ABOVE the node its bucket is on: a member opens it with
  the node's key or an ancestor's, through the chain (`openBucketName`'s `ancestorKeys`, `contentKeyChain`; web,
  uc.memory.buckets:guest-named-above-the-node).
- **Tree operations are core's** (`ShareNodesHandler`, `READ_WRITE` only): inside a `FOLDER` core decides
  (`FolderGuestNodeRules`) — a move or a rename of any kind, a create of a `FOLDER` or of a kind its application declares
  guest-creatable (`GuestCreatableKinds`; Memories declares none, so no `MEMORY` or `CHAPTER` is `illegal_scope`) — and
  `NodeKindRules` decides where a kind may live; inside a `MEMORY` or a `CHAPTER` Memories declares nothing, so
  `GuestNodeRules` fails closed. The root is never a guest's to move or rename.
- **No guest route exists** for a medium's meta (`updateMediaMeta`), a node's `wrappedMeta` or cover, or a node
  delete: `share-media.openapi.yaml` holds list, complete and delete-own; `sharing.openapi.yaml`'s guest node schemas
  carry no `wrappedMeta`.
- A `WRITE_ONLY` link on a `FOLDER` cannot be created (`ShareRoots`: a drop box is Depot's own node, ADR 0080
  decision 11), and a role change never reaches or leaves `WRITE_ONLY` (`invalid_role_change`); `READ` ↔
  `READ_WRITE` flips in place on all three kinds.

**Verified by a test vs. by reading the code.** Every `MEMORY` and `CHAPTER` cell has a slice test
(`ChapterShareSliceTest`, `ShareRootsSliceTest`, `ShareNodesSliceTest`, the share media suites, `SubtreeUploadSliceTest`
for an upload into a chapter below a Memory link and the `WRITE_ONLY` root-only rule). The `FOLDER` column's
upload, delete-own, rename, create and move cells are tested (`SubtreeUploadSliceTest`, `ShareRootsSliceTest`,
`ShareNodesSliceTest`, `ShareNodesCoreSliceTest`). A `READ` folder guest is served the media of a Memory below the folder (e2e `folderShare.e2e.test.ts`,
uc.sharing.share-public-viewer:folder-share-root). A folder guest's create of a `MEMORY` or a chapter under a folder answering `illegal_scope` is
`ShareNodesSliceTest.aGuestCreatesNoMemoryInAFolder`. **UNVERIFIED by a test** (read from the code only): a `READ` ↔
`READ_WRITE` flip on a folder link.

### What the web offers — the rights matrix

One view per kind serves a member and a guest ([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)
Consequences, inbox #401); what it shows is `memoryRights(access, target)` (`src/web/src/features/memories/rights.ts`,
pinned row by row in `rights.test.ts`): pure, every right false unless a row grants it, an unknown role or kind
grants nothing. Its share, rename, move and delete of the node are core's `nodeRights`, which Memories only narrows (a
guest moves a Memory, never a chapter); the cover is core's action with core's right (`coverRights`). On an opened
`MEMORY` or `CHAPTER` (the detail):

| Right | member | member, scope pending deletion | `READ` | `READ_WRITE` | `WRITE_ONLY` |
|---|---|---|---|---|---|
| navigate (Chapters tab), download | ✓ | ✓ | ✓ | ✓ | ✗ |
| upload into the node on screen | ✓ | ✗ | ✗ | ✓ | ✓ |
| delete media | any (trash) | ✗ | ✗ | own (purge) | own (purge) |
| create a chapter | ✓ on a `MEMORY` only | ✗ | ✗ | ✗ | ✗ |
| date, place, cover, banner (the node's sealed meta; the automatic reconcile too) | ✓ | ✗ | ✗ | ✗ | ✗ |
| see the banner (read from the node's own attachment) | ✓ | ✓ | ✓ | ✓ | ✗ |
| the Map tab and its GPX tracks ([uc.memory.tracks](../features/memory/use-cases/uc.memory.tracks.md)): see and download them; add one (the upload right), naming it and giving it an activity as it is added, and remove one (the delete-media right) | ✓ see, add, remove (trash) | ✓ see | ✓ see | ✓ see, add, remove its own (purge) | ✗ |
| background music: set the playlists (the node's sealed meta, [uc.memory.music](../features/memory/use-cases/uc.memory.music.md)) / the header's play buttons and the music bar below the shell's footer, playing after one's own opt-in | ✓ / ✓ | ✗ / ✓ | ✗ / ✓ | ✗ / ✓ | ✗ / ✗ |
| rename, delete the node | ✓ | ✗ | ✗ | ✗ | ✗ |
| narrow the gallery by facts (day, year, place, kind, the original's name; uc.memory.gallery step 8) | ✓ | ✓ | ✗ | ✗ | ✗ |
| participants (opaque user ids in the node's sealed meta, [ADR 0083](../decisions/0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md); a Memory in a tenant or group scope) | ✓ shown and edited; the profile at `#/people/<id>` (its Memories as tiles, rows or the level's map) | ✓ shown, not edited | ✗ not shown | ✗ not shown | ✗ not shown |
| move the node (core's move dialog, inbox #358) | ✓ a Memory to the root or a folder, a chapter into another Memory | ✗ | ✗ | ✓ a Memory a folder of the subtree holds, between the subtree's folders; ✗ a chapter, the link's root | ✗ |
| copy the node (core's "Copy to…", [uc.platform.copy-node](../features/platform/use-cases/uc.platform.copy-node.md)) | ✓ a Memory with its chapters to the root or a folder, a chapter into a Memory: each medium's original and its existing tiers read by frame and uploaded as they are under fresh keys, its sealed facts (capture time and its correction, coordinates, place name, EXIF) sealed anew - nothing decoded (`exportImport.ts`, `addCopiedMedia`, [ADR 0082](../decisions/0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md) decision 11); an uploaded cover as its blob, a photo cover by its medium; participants and contributors do not come along | ✗ | ✗ | ✗ | ✗ |
| item marks, media trash, contributions organizer | ✓ | ✓ | ✗ | ✗ | ✗ |
| share (one action and one dialog for every link role: it lists them all and offers each role a new link may take where its leaf is on, owner 2026-11-04) | the instance's `core.public-shares` for the viewer role, `.contribute` for the READ_WRITE role, `.drop-box` for the uploader link - one leaf each; the action shows where any is on | the same: the dialog lists and copies the links and revokes ✓ the caller's own live one, mints and changes nothing else (the server refuses every other share write then) | ✗ | ✗ | ✗ |

At a folder level (Memories' rows among core's contents, after the folders): a member creates a Memory (not during a pending deletion, never in a guest's
identity folder); a share guest creates none — the "New" menu offers a guest no Memory (ADR 0080 decision 6); every role that reads opens
one. A share right also needs the kind's descriptor to bring a share dialog (`targetOf`, core's), and a guest's move
the node's parent to be a folder the link serves (`targetOf`'s `parentKind`: unknown at the link's root). A level's selection
(core's Select mode, inbox #421) reads the same matrix per Memory (`LevelRow.selection`): it moves with the selection where
`move` says yes and is deleted with it where `delete` does (core's delete, as the Memory's own page asks the host for),
so a guest's selection moves a Memory a folder holds and deletes none. These are the matrix
half; a right shows only where the session's data source also implements it (ADR 0079), so the guest's columns are the
most a guest page can ever offer. Memories' source is `src/web/src/features/memories/source.ts` (inbox #401 S4): the
member's wraps the scope's calls; the guest's reads the link's media listing (each node's own media from the subtree's),
opens every tier through the share presign, and — for `READ_WRITE` only — uploads into the node on screen, naming it
(the root names none) and sealing under that node's key from the chain, and purges what this device sent
(`ownShareMedia.ts`). It has no door for the media trash, the node's date, place and cover, a medium's meta, the item
marks, the organizer or the heavy EXIF; a `WRITE_ONLY` link uploads and deletes through its drop page, never this source.
It polls as the member's does (inbox #497, the same `useMediaGalleryLive` loop): its tag is the `ETag` of
`listShareMedia`'s first page, a digest of everything the read serves ([ADR 0081](../decisions/0081-a-scopes-tree-revision-is-the-level-views-change-signal.md)
decision 4, never the node's item revision), and a change answers the whole listing with every id in it — a guest is
told no order of the Memory's changes to continue a keyset from — so another uploader's photo appears, and one gone
from the link drops, without a reload.

**A guest sees the member's views (inbox #401).** At a folder level `memoryRows` (`src/web/src/features/memories/levelRows.ts`), handed a guest's access and tree,
gives core's list the Memories of a shared folder as rows and tiles (a cover tile in the grid), each date and place unsealed with the node's key the link's
chain opened, each cover through the link's own reads (core's cover store, `core/node/cover/coverStore.ts`: an uploaded cover blob through
`resolveShareBlob`, which serves a node's cover blob below the share), the map over the opened subtree. A Memory or a chapter — below a shared
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

Held by `scripts/validate_backend_module_graph.py`: **the backend is.** Memories is the Gradle module `:apps:memories` (`src/backend/apps/memories`), depending on `:core`
and never the reverse; the root `src/backend/` composes both into the one fast-jar. Every class lives in its owner's
package — the platform in `me.raddatz.veyq.core` (the node tree in `core.content`/`core.node`, sharing in `core.sharing`),
Memories in `me.raddatz.veyq.memories` (`media`, `itemfavorites`, `node`) — and core reaches Memories only
through the interfaces Memories implements: it attaches its media through `UploadCompletion`, measures a link's
contributed blobs through `UploadAdmission`, places its `MEMORY` and `CHAPTER` through `NodeKindRules` (core places
only `FOLDER`), declares that a `MEMORY` and a `CHAPTER` may be the root of a share of every role through
`ShareRoots` and takes guest writes under both through `ShareGuestWrites` (one set, `MemoriesNodeKinds.SHARE_ROOTS`). Memories' tests run in `:apps:memories`, beside the
core tests that need an application at runtime; `:core`'s own run with no application at all. **The web is separated
by import direction**: `features/memories`, named for its owner as `:apps:memories` is, is the application (core imports it nowhere but the composition root
and asks a registry for what to render; the folder level view is core's, Memories contributes ROW DATA, not a section
(inbox #407): its row factory `memoryRows` answers, for each MEMORY and CHAPTER at the level, a row — the cover as the
leading visual (large on a tile; core resolves the cover, `host.covers`, inbox #411), the name, the meta line "Memory" plus the details date range and place (`details`, `memoryRowDetails.ts`: each left out when the Memory has none, the same for a share guest, [apps/README](README.md#how-an-application-shows-facts-in-a-levels-rows-and-tiles)), a primary click that opens it, and the answer for core's
default rename, move and delete in the row's menu (`standard`, `core/node/standardRowActions.ts`: Memories builds none of
the three; the favorite heart on the row is core's own action, the same for every kind, inbox #409, and so is the ONE "Cover" action - "Set cover" / "Change cover" - that opens core's cover dialog, where the image is chosen, cropped and removed, inbox #411) —
and the map as its own presentation; core draws the list, orders it (folders first, then everything by name, mixed with
files and drop boxes) and offers the presentations grid, list and map in the level's tools row, inbox #370; one list of
the three, `memoryViews.ts`), and its create and share dialogs ride on its kinds
(a rename is core's, as a folder's create is); Memories adds nothing to the shell (the sidebar's Favorites section is core's,
and so is the upload status in the top bar, which lists Memories' batches with every other application's). How core and an application meet, on both sides, is
[Applications](README.md#how-core-and-an-application-meet).

- End-user view (docs site): [Memories](../../site/src/content/docs/memories/index.mdx)
