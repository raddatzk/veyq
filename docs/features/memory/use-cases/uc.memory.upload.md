# UC: Upload media into a Memory (web)

- **ID:** uc.memory.upload
- **Primary actor:** Account holder browsing a Memory on the **web** client.
- **Goal:** Add photos/videos to the open Memory — sealed on-device and uploaded direct-to-store — with a
  **global uploads tray** (a TopBar button + popup) that shows every in-flight upload's progress and lets the
  user cancel, while they keep navigating.
- **Preconditions:** Signed in + unlocked; a Memory is open (its `K_mem` is available); the media data layer +
  blob store exist (en.memory.media, cr-0028/0029) and derivatives are generated on-device (cr-0045).
- **Postconditions (success):** Each picked file is sealed under a fresh `K_blob` (wrapped to `K_mem`), its
  chunks `PUT` straight to the store, and one logical media (original + role-tagged derivatives) is attached to
  the Memory node. The gallery refreshes to show it. Operator-blind throughout — the server sees only ciphertext
  + opaque sizes/counts.

## Main flow

1. In the open Memory's gallery the user picks files (a hidden `<input type=file multiple accept=image/*,video/*>`
   behind an **Upload** affordance).
2. Each file is enqueued into the **global upload queue** as a batch against the current `{scopeId, nodeId}` +
   the Memory key. The TopBar **uploads** button shows a spinner + an active-file count badge, **and** the picked
   photos appear **immediately as tiles in that Memory's gallery grid** — an instant **local preview** (the user's
   own image, before it is sealed — a local object URL, never uploaded in the clear) with a per-tile progress /
   error(+retry) state (cr-0074). A tile appears **at its capture-time place**, not at the front: the sort key is
   derived on device before sealing, from the same rule the sealed tier will carry, so the tile is already where
   it will stay. [uc.memory.gallery](uc.memory.gallery.md) owns that ordering rule.
3. The queue uploads **one file at a time**: each file is sealed on-device (with its
   [derivatives](../enablers/en.memory.derivatives.md)) and sent **direct-to-store**, then attached to the
   Memory node — the seal → start → PUT → attach choreography is
   [en.memory.media](../enablers/en.memory.media.md)'s. Per-file + per-batch **progress** advance as it uploads.
   **Each tile names the stage its file is actually in** — *queued*, *preparing*, *uploading* — because they are
   three different waits and in two of them no byte has left the device yet. A tile that said *"uploading"*
   while the device was still decoding is what made a large batch read as hung.
4. Opening the uploads popup shows each batch's files with the same status (queued / preparing / uploading /
   done / failed / cancelled), a progress bar, and a **cancel** control while a file is queued/uploading.
5. As each file finishes, its optimistic tile **reconciles into the real decrypted thumbnail** (matched by the
   returned `mediaId`) with no empty-flash and no duplicate: the tile's **content** changes, its **position**
   does not. The user can **close** (acknowledge) the finished batch in the tray.

## Alternate / exception flows

- **E1 — transient failure** (aborted/failed request): the file is re-queued for one automatic retry; a
  hard error marks it **failed** and cancels the rest of that batch's queued files (the user retries explicitly).
- **E2 — cancel:** cancelling a queued file drops it; cancelling an in-flight file aborts its PUTs and stops
  before `complete` (no media is attached). The abandoned upload session is reclaimed by the store-side GC.
- **E3 — Safari's Photos picker.** Files chosen from the Photos gallery arrive with an **empty MIME type**, and
  Safari **populates `input.files` late / across repeated picks**. The picker therefore (a) accepts a
  file by **extension** when its `type` is empty, (b) **re-reads** the input over a short retry window and
  **merges** (never replaces) by a `name:size:lastModified:type` signature, and (c) infers a `mediaType` from
  the extension so derivation can still run (HEIC/MOV). **The window never delays the files already in hand** —
  each re-read enqueues only what is genuinely new, as an additional batch; gating the first enqueue on it made
  every browser pay for the one this workaround exists for. Underivable bytes upload **original-only** (the
  gallery shows an icon).
- **E4 — underivable media:** if on-device derivation fails (codec the browser can't decode), the original is
  uploaded without derivatives rather than failing the upload.

## Quality notes

- **Operator-blind, unchanged.** The client seals every chunk under a random `K_blob` (wrapped to `K_mem`) and
  PUTs raw sealed bytes; the server stores ciphertext + opaque sizes/counts (ADR 0013). No plaintext, key, or
  filename reaches the server in the clear.
- **The tray is global + non-blocking.** Uploads continue while the user browses other Memories; the queue is a
  module singleton, the popup lives in the app shell.
- **Sequential, not parallel.** One file at a time keeps the byte budget simple. Within a file the chunks
  stream — read, sealed and sent a few at a time — so a large video costs the device a few frames of memory,
  not the file twice, and its size is bounded by the server's ceiling rather than by how fast the uplink is
  ([en.memory.media](../enablers/en.memory.media.md)).

## What this means for you / Was das für dich bedeutet

**EN —** Drop photos or videos into a memory and they upload in the background — each picture tells you what it
is doing (waiting, getting ready, uploading), a button up top shows the whole batch, and you can cancel any of
them. Getting a photo ready takes a moment before anything is sent, which is why a big batch does not start
moving the instant you pick it. Everything is scrambled on your device before it leaves; the server only ever
stores the scrambled pieces.

**DE —** Leg Fotos oder Videos in eine Erinnerung — sie laden im Hintergrund hoch; jedes Bild sagt dir, was
gerade passiert (wartet, wird vorbereitet, lädt hoch), ein Button oben zeigt den ganzen Stapel, und du kannst
jeden Upload abbrechen. Ein Foto vorzubereiten dauert einen Moment, bevor überhaupt etwas gesendet wird —
deshalb setzt sich ein großer Stapel nicht sofort in Bewegung. Alles wird auf deinem Gerät verschlüsselt, bevor
es das Gerät verlässt; der Server speichert nur die verschlüsselten Teile.

## Related

- Feature: [Memory](../_index.md); enabler [en.memory.media](../enablers/en.memory.media.md) (the data layer).
- Use-cases: [browse](uc.memory.browse.md), [gallery](uc.memory.gallery.md) (where the upload affordance lives).
- Decisions: [ADR 0013](../../../decisions/0013-blob-storage-presigned-direct-to-store.md) (blob store + transport;
  windowed presigning; resuming **across a page reload** deferred).
