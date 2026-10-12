# UC: A cover for any node — an image of the owner's choosing, core's feature

- **ID:** uc.platform.node-cover
- **Primary actor:** A member who may write the space (a member with write access on the scope, keys unlocked).
- **Goal:** Give a **folder**, a **drop box** or a **Memory** a **cover** — an image the member uploads directly — shown as the
  node's leading visual in the level list and tiles, in the node's header and in the sidebar's Favorites, with the kind's icon as
  the default. Core's feature (inbox #411): the action, the picker, the derive, the upload, the write and the display belong
  to the platform; a kind only **opts in** (`NodeKindRules.takesCover` on the server, `cover` on the web descriptor).
- **Preconditions:** The scope is unlocked (the epoch keys are loaded), not pending deletion, the member may write it, and the
  node's kind opted in (FOLDER — core's own, `FILES_DROPBOX`, `MEMORY`, `CHAPTER`; **not** `FILE`: a file's own thumbnail is a
  different idea, it needs the client to understand many formats). The node is not a guest's identity folder.
- **Postconditions (success):** The image is the node's own blob in core's `COVER` attachment slot — not a child, not a file in
  the folder — and the node's sealed `wrappedMeta` names it; both landed in ONE write. The node goes to the trash with its
  cover, counts against the quota like any attachment and is freed with the node's purge.

## Main flow

1. In the row's **⋯** menu (or the header's buttons) the member chooses **Set cover** (**Change cover** where one exists).
   The action is core's — one id `cover`, placed by `coverRowActions` / `coverActions` beside the favorite heart — and gated by
   `coverCapabilities` (fail closed).
2. Core's hidden `image/*` picker opens. The chosen file is checked (an image, not empty, up to **40 MB**; otherwise a clear
   message and nothing happens).
3. The client **derives** the cover size with the existing pipeline — one JPEG, long edge 2048, quality 0.85, exactly what
   Memories' covers always used — and seals it as the node's own blob: a fresh `K_blob` wrapped under the **node's content
   key**, bound to blob, node and role `COVER` ([ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md)), the blob meta
   `{type: image/jpeg}` under `K_blob`. The upload runs as a job of the core upload queue (progress and failure show in the
   tray; a quota refusal says so).
4. One request, `setNodeCover`, hands the open upload **and** the node's re-sealed `wrappedMeta` (the `cover` field, version-
   checked, [ADR 0077](../../../decisions/0077-a-write-carries-the-version-it-read.md)). The server verifies the upload,
   attaches it in the slot — freeing, in the same transaction, the blob the member replaces (`expectedBlobId`) — and
   compare-and-sets the meta. Either both land or neither does.
5. The member's tree is patched with the new meta; every surface that draws the node (the level's row or tile, the header, the
   sidebar's Favorites) re-resolves: the cover store is keyed on the sealed meta, so the new image appears and the old object URL
   is revoked at once.
6. **Remove cover** is `removeNodeCover`: the blob is freed and the meta re-sealed without the cover, again one write. A node
   whose cover could not be opened can still be removed.

## Alternate / exception flows

- **A1 — a Memory (its own source UI):** the Memory's page hands the cover action its own dialog (a photo of the Memory, an
  upload, a crop) instead of the plain picker; the dialog saves through the same core write. A cover chosen from the Memory's own
  photos is a second *source* in the same sealed field that core carries untouched and the Memory's `cover.load` reads.
- **E1 — a peer changed the cover meanwhile:** `409 cover_stale` — nothing was attached, the old cover is intact, the upload was
  spent and freed; the member is told and may try again. A meta that moved is `409 stale_version`: nothing written, the upload
  still open, the client re-applies its change on the current meta and sends the same upload again.
- **E2 — the upload or the write fails, or is cancelled:** the **old cover stays, exactly as it was** (the blob step and the
  meta are one transaction); an upload no write ever attached is an unfinished upload like any other, which the server's
  stale-session purge reclaims. Nothing is half-done.
- **E3 — an image the browser cannot decode:** refused (`cover_unreadable`), never sealed as a cover nothing could draw.
- **E4 — a cover that fails to load or has no reader:** the node shows its kind's **icon**, silently.
- **E5 — a share guest:** sees the covers of the nodes in its shared subtree (the node's `wrappedMeta` it already gets, the
  attachment through the share's gated blob route) and gets **no action**: no guest role sets a cover in this step, the server has
  no guest operation for it, and the capability says so first. **Share view = member view**, with the function removed.
- **E6 — a pending deletion, locked keys, a guest's identity folder, a kind that did not opt in:** no action is offered; the
  server refuses the rest (`scope_deleting`, `illegal_target`).

## Quality notes

- **Operator-blind.** The server holds ciphertext chunks, the opaque attachment row (node, slot, blob id), sizes and the sealed
  meta — never the image, the crop or which source a cover has. The cover meta is the same `node-meta` seal a Memory's date and
  place use; the blob wrap and blob meta are the seals Memories' covers always used, so **every Memory cover already stored
  stays readable byte for byte** (`coverModel.test.ts` pins the exact shape and key order).
- **No half state.** The slot and the meta that names it are written in one transaction (`NodeCoverStore`): a refused meta write
  rolls the attach back with it, a refused slot compare writes nothing. There is no window in which the meta names a blob that
  is gone, and no second call to forget.
- **Removal is real, with honest limits.** A replaced or removed cover's blob row goes in the same transaction as the swap and a
  `veyq_store_reclaim` record per storage key; the sweep, the only deleter ([ADR 0062](../../../decisions/0062-store-object-reclaim-is-a-durable-work-list.md)),
  deletes the objects up to one not-before-plus-interval later, and nothing readable references them in between. The client
  revokes the old object URL at once; covers never touch Cache Storage, IndexedDB or the service worker. **What the owner
  cannot erase:** copies already loaded into other viewers' browsers, and presigned URLs until they expire.
- **Core names no application kind.** The opt-in is a seam on each kind's own rules; the tests assert the matrix across core and
  every application.

## What this means for you / Was das für dich bedeutet

**EN —** Give a folder, a drop box or a memory a **face**: choose **Set cover** in its menu and pick any picture from your
device. It replaces the icon in the list, on the page and in your favorites. The picture is locked on your device with the
entry's own key; the server only stores scrambled data. Changing or removing the cover really deletes the old picture from the
server's storage — only copies other people's browsers already hold cannot be taken back. People you share a link with can see
the covers but cannot set them.

**DE —** Gib einem Ordner, einem Briefkasten oder einer Erinnerung ein **Gesicht**: Wähle im Menü **Cover festlegen** und such ein
beliebiges Bild von deinem Gerät aus. Es ersetzt das Symbol in der Liste, auf der Seite und in deinen Favoriten. Das Bild wird
auf deinem Gerät mit dem eigenen Schlüssel des Eintrags verschlüsselt; der Server speichert nur verschlüsselte Daten. Wenn du das
Cover änderst oder entfernst, wird das alte Bild wirklich aus dem Speicher des Servers gelöscht — nur Kopien, die andere Browser
schon geladen haben, lassen sich nicht zurückholen. Wer nur einen Link bekommt, sieht die Cover, kann sie aber nicht festlegen.

## Related

- Feature: [Platform](../_index.md)
- End-user view (wiki): [Files — and finding your way around](../../../../wiki/en/files.md)
- Use-cases: [uc.memory.cover](../../memory/use-cases/uc.memory.cover.md) (a Memory's own source UI and its crop),
  [uc.files.drop-box](../../files/use-cases/uc.files.drop-box.md) (a drop box carries one),
  [uc.memory.delete-node](../../memory/use-cases/uc.memory.delete-node.md) (the trash keeps a cover for a restore, the purge frees it)
- Enabler: [en.memory.node-meta](../../memory/enablers/en.memory.node-meta.md) (the sealed `wrappedMeta` envelope the cover field lives in)
- Decisions: [ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) (core's attachments),
  [ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md), [ADR 0077](../../../decisions/0077-a-write-carries-the-version-it-read.md),
  [ADR 0062](../../../decisions/0062-store-object-reclaim-is-a-durable-work-list.md)
