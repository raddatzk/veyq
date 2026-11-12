# UC: Open a Memory & browse its gallery

- **ID:** uc.memory.gallery
- **Primary actor:** A member with an **unlocked vault**, inside one of their spaces, who **opens a Memory**
  from the shell.
- **Goal:** Open a Memory and look through its media — a thumbnail **grid**, an item **lightbox** (full view,
  prev/next), and **download** an original — with every byte decrypted **on device**; **mark** the good ones and
  narrow the grid to *everyone's favorites* or *only mine*.
- **Preconditions:** The user has unlocked the vault and is browsing the tree
  ([uc.memory.browse](uc.memory.browse.md)); the Memory exists and its **`K_node`** can be unwrapped; its media
  carry **derivatives** ([en.memory.derivatives](../enablers/en.memory.derivatives.md); a non-derivable item
  shows an icon tile).
- **Postconditions (success):** The user sees the Memory's media as a grid, opens any item in a lightbox and
  pages through, **plays a video** in place, can pin the Memory, can **mark individual items** and filter the
  grid by those marks, and can download an item's original. The server saw only **ciphertext fetches** — no
  pixel, no filename, no content key, and never which items are marked.

## Main flow

1. From the shell ([uc.memory.browse](uc.memory.browse.md)) the user clicks a Memory → it **opens**: the
   client unwraps the Memory's **`K_node`** (already held from the tree).
2. The client **lists the Memory's media** ([en.memory.media](../enablers/en.memory.media.md)) — in pages of a bounded
   size, every page before the grid is drawn, because the order below is only final once every item's sealed capture
   time is in hand ([ADR 0033](../../../decisions/0033-live-media-updates-revision-polling.md) decision 6); a share
   guest lists the node it has open, by the same paging rule, never the whole shared subtree — opens every item's
   listed seals once, off the page's main thread
   ([ADR 0105](../../../decisions/0105-a-gallery-renders-what-is-near-the-viewport-and-opens-its-listing-in-a-worker.md)
   decision 3), and renders a **grid** under a header (title, item count, pin toggle, and — in a group scope — the
   favorites filter), fetching the **thumbnail** ([en.memory.derivatives](../enablers/en.memory.derivatives.md)) of
   each tile near the viewport via presigned GET, nearest first, and decrypting it on device. The grid is
   ordered by **effective capture time** — newest first by default, a header toggle for oldest first, undated
   media last — read from each media's sealed capture tier, so the blind server never sees a timestamp yet the
   order is exact on device (cr-0066).
2a. **The keyboard** (ADR 0105 decision 5, owner 2026-10-19). The grid is one tab stop, on one tile: **Tab** enters on
   that tile's open button, goes through its own controls (the mark, the checkbox, the menu) and leaves the grid. The
   arrow keys move left and right through the grid order and up and down to the nearest tile of the adjacent row,
   **Home** and **End** to the first and the last item, **PageUp** and **PageDown** by one viewport; each move scrolls
   its tile into view and focuses it. **Enter** opens the item; in the trash and the contributors' view **Space**
   selects it, as a click does. The lightbox takes the focus while it is open, and closing it scrolls the item it
   showed last into view and focuses its tile. A screen reader is told the grid's row count and each mounted row's
   position (`role="grid"`, `aria-rowcount`, `aria-rowindex`), and each tile keeps its name, *Open item n*, n counting
   the whole list.
3a. **Zoom and slideshow in the lightbox** (decided 2026-10-14 by the coordinator on the owner's behalf, inbox #90 and
   #91; built the same day). **Zoom** (a photo; a video does not zoom): double-click / double-tap toggles 1x / 2x at the
   pointer, a pinch with two pointers and ctrl+wheel zoom continuously (1x - 8x), a drag or the arrow keys pan within the
   zoomed photo's edges, and `+` / `-` / `0` zoom from the keyboard. Once the zoomed photo shows more device pixels than
   the preview has, the **original** is fetched and decrypted once for that item - only where the preview was scaled down
   (its longer edge at the 2048 px tier; a smaller preview already has every pixel the original has), for a sealed
   media type every browser decodes
   (JPEG, PNG, GIF, WebP, AVIF, BMP; HEIC is left out, only Safari decodes it) and under 50 MB of ciphertext - and
   replaces the preview in place; anything else, or a failure, stays on the preview without a message. Navigating
   resets the zoom and drops the original; while zoomed the arrow keys pan instead of navigating, and Escape un-zooms
   before it closes. **Slideshow:** a play / pause control in the lightbox's toolbar advances every 5 s through the grid order, a
   video showing its poster for one interval (no autoplay), and stops on the last item; any key, pointer or wheel in the
   lightbox (so any zoom or details interaction) pauses it, a hidden tab and a dialog over the lightbox too; the state is
   announced to a screen reader. A slow preview is waited for, never skipped. The next preview is read ahead like any
   neighbour (item 3) and stays held while the lightbox is open; while it runs, no EXIF read and no place-name backfill happen - they start when the
   slideshow pauses on an item. The member and the guest get both (share view = member view; a guest's loaders are its
   source's). Seen in a real browser on 2026-10-14 (a Memory of three 3000 x 2000 JPEGs, 2048 px previews): a
   double-click zoomed to 2x and fetched the original once (two chunks; the image became 3000 x 2000), ArrowRight panned
   while zoomed and did not page, Escape un-zoomed without closing, paging reset the zoom; the slideshow advanced every
   5 s, stopped on the last item announcing it, and fetched EXIF only on arriving there. Not yet seen in a browser: a
   video's poster in a slideshow, a pinch, ctrl+wheel, the 50 MB cap, a share guest's zoom.
3. **Lightbox:** clicking an item opens it full-view; the client fetches that item's **preview**, decrypts, and
   displays it; **prev/next** page through the grid order. (The preview — not the multi-MB original — keeps the
   lightbox fast.) The decrypted previews stay **in memory while the lightbox is open** (owner 2026-11-12,
   `previewCache.ts`): a step back shows the item at once, and once an item's preview is shown its two neighbours are
   read ahead, after it, so a step forward usually does too. At most 24 MiB, the least recently shown dropped first; a
   block or a removal drops the item's bytes, a new key or closing drops all. Never on the device's storage: a cache that
   outlives the session would, on our reading of § 25 TDDDG, need the member's own choice first. Seen in headless Chrome
   on 2026-11-12 (the dev instance's Memory of 20 photos): open, two steps forward, two back - the old lightbox showed
   the loading state on all four steps and read the store twice going back; this one on none of them, with no store
   read going back. A **video** (known from the media type sealed in its original's blob meta, which rides the
   listing — inbox #397, [ADR 0047](../../../decisions/0047-inline-video-playback-range-streaming.md))
   shows its poster with a **play** control; playing it **streams** the **original** on device — a key-free Service
   Worker answers the `<video>` demuxer's byte-range requests, pulling + decrypting only the covering chunks from
   the page as the demuxer needs them (no whole-blob wait). Where no Service Worker is available it falls back to
   fetching + decrypting the whole original into a typed blob URL. A **play badge** marks a video's grid tile
   (#104/#105).
4. **The detail panel — what the photo itself knows.** Opening an item also opens a panel beside it, filled
   from **three** sealed sources: two already in hand from the listing — the **listed tier** (capture time, GPS and
   a resolved place name) and the original's **blob meta** (its file name and format; the size is the original's
   own) — and the **panel bundle**, fetched lazily for this one item. Between them the panel
   shows the file (its original name, its plaintext size, its format — for every item, EXIF or not), the camera
   and lens, the exposure
   (focal length and its 35 mm equivalent, aperture, shutter, ISO, exposure compensation, whether the flash
   fired), the provenance an editor wrote in (software, author, copyright, rating, title, description; author and
   copyright from EXIF only, never from XMP `dc:creator`/`dc:rights` or IPTC, owner 2026-10-30) and the
   geo block (altitude, the direction the camera faced, the fix's own accuracy). **Every row is present only
   when the photo carries it** — a phone snapshot shows a handful, a raw file from a camera shows twenty, and
   neither is a special case. Where the user has consented to maps ([ADR 0018](../../../decisions/0018-map-tiles-provider-neutrality.md))
   the coordinate is joined by a **resolved place name** above it and a small map below, with a needle for the
   view direction; without consent the rows stand alone and **no tile and no coordinate leave the device**.
   The place name is resolved **once per place** and sealed — see
   [uc.memory.place](uc.memory.place.md) and [ADR 0016](../../../decisions/0016-geocoding-provider-neutrality.md)
   § *Request discipline*.
5. **Download:** an explicit download fetches the **original** (the only pristine, full-resolution copy) and
   saves it, streamed chunk by chunk — into a file the user picks, the browser's own download (the Service Worker's
   download route), or, only up to 512 MiB, an in-memory copy; above that with neither, it is refused with a message
   (inbox #371). The file is saved under **the item's own file name** — the name the uploader's device gave it, sealed
   into the original's blob meta at upload and known from the listing, so the download is offered at once and nothing
   is read before the save target opens (inbox #397) — so the same photo always downloads under the same name, however
   many items are added after it. A name without an extension gains the one its sealed media type names. Only an item
   whose original carries no name (or no meta that opens) is named from the Memory's name and its position, which
   moves when the gallery does, with an extension from the sealed media type, else `.bin`.
6. **Pin the Memory:** the heart in the header pins / unpins it - the same heart every node has, core's
   ([en.memory.favorites](../enablers/en.memory.favorites.md)). This pin is **private to the user**.
7. **Mark an item, and narrow by marks.** A heart on each tile (and in the lightbox) marks that item. Unlike the
   header pin, an item mark is **shared with the scope**: the tile's heart carries a **count** — how many
   members marked it — and that count is the whole of what the UI shows. **Who** marked an item is never
   displayed: no faces, no names, no per-member attribution
   ([ADR 0068](../../../decisions/0068-favorites-two-confidentiality-levels.md) §6).
   A header filter narrows the grid to **everyone's favorites** or **only mine**; the grid's ordering rule is
   unchanged, the filter only removes tiles. A marked item that has since been trashed or purged simply drops.
8. **Narrow by facts.**
   - **The field:** a filter field in the gallery's header takes the search's chip grammar
     ([uc.platform.search](../../platform/use-cases/uc.platform.search.md) step 4, one component).
   - **Chips:** a capture **day** (the days the Memory's items were captured on), a capture **year** when the Memory
     spans more than one, a **place** its items carry, and a **kind** (photo, video, offered when both occur). The day and
     the year are the viewer's, in the time zone the gallery shows dates in, from the corrected capture time where one is
     set; a day's chip names its year where the Memory spans more than one. An item without a capture time matches no day
     and no year, and a medium blocked by the operator matches no chip and no text.
   - **Free text:** whatever is not turned into a chip matches the original's name.
   - **Combining:** the chips combine with each other and with the favorites filter of step 7.
   - **Display:** as with step 7, the ordering rule is unchanged and the filter only removes tiles. The lightbox pages
     through the narrowed set.
   - **Source:** the facts are the ones the opened listing already holds (ADR 0105). Nothing is fetched or sent for
     the filter, and it is not stored; leaving the Memory clears it.
   - **Who:** a member's view only. A share guest has no filter field, as they have no search (owner, 2026-11-08:
     guests get no search; ADR 0125 decision 7).

## Alternate / exception flows

- **E1 — an item has no derivable frame** (a type that is neither image nor video): the grid shows an **icon +
  name**. (There is **no backfill** — on a fresh instance every image/video media carries its derivatives from
  upload, [en.memory.derivatives](../enablers/en.memory.derivatives.md).)
- **E2 — vault locked / Memory not open:** without `K_node` the media can't be read → the gallery is unavailable
  until unlock + the Memory is opened.
- **E3 — empty Memory:** the grid shows an empty state.
- **E4 — personal scope:** *"everyone's favorites"* is degenerate when the user is the only member, so the
  filter is **hidden** — not rendered disabled. The per-tile count is likewise pointless and is not shown; the
  item heart still marks, it just has no audience to report.
- **E5 — a filter that empties the grid** (e.g. *only mine* with nothing marked yet, or a day chip with a place chip
  no item of that day carries): the grid shows an empty state that names every active filter, so it never reads as a
  broken or empty Memory.
- **E6 — a medium blocked by the operator**
  ([uc.moderation.take-a-measure](../../moderation/use-cases/uc.moderation.take-a-measure.md)): its tile and the
  lightbox show *Blocked by the operator* with no reason and load no bytes (each answers `451 blocked_by_operator`), for
  members and guests alike; its uploader may still delete it, and it cannot be restored from the trash while blocked.
  A Memory blocked as a whole shows as that placeholder in its level and does not open.
- **E7 — a Memory or Chapter of thousands of photos** (inbox #488, #489): the client asks only for the thumbnails of
  the tiles near the viewport, nearest first, drops the ones that scrolled away while they still wait, and never asks
  for all at once — their presigns travel in batches of up to the contract's 256 blobs per request, at most two
  requests in flight, and at most sixteen store reads in flight across the page (`src/web/src/core/blob/readScheduler.ts`
  holds the limits and why). What the user opens — a preview, a video, a download, a cover — goes before the grid's
  thumbnails, and leaving the Memory drops the reads still waiting. Thumbnails that arrive within 100 ms of each other
  appear together, so the grid is not redrawn once per photo. A read without an answer, or answered `5xx`, `408` or
  `429`, is tried up to four times with a growing pause; a presigned URL that expired before it was used is presigned
  again, twice at most. A thumbnail that still cannot be read shows *Could not load* on its tile, never a placeholder
  that waits forever. Members and guests alike; the server sees the same blob ids and the same presigned reads, only
  batched and fewer at once.
- **E8 — a mark or a pin that does not land** (inbox #500): the heart goes back to where it was and a message says
  so. One member's marks in one Memory hold 12,284 photos ([ADR 0068](../../../decisions/0068-favorites-two-confidentiality-levels.md)
  §4); the next one is refused before anything is sent, and its message asks to remove a mark first.

## Quality notes

- **Operator-blind.** Every tier is fetched as **ciphertext** and decrypted on device; the server never sees a
  pixel, a filename, a media type or a content key. The lightbox shows the **preview**, not the original — the original
  leaves the store on explicit download, a video's playback, and a zoom past the preview (3a) - each fetched as
  ciphertext and opened on the device.
- **Bounded rendering** ([ADR 0105](../../../decisions/0105-a-gallery-renders-what-is-near-the-viewport-and-opens-its-listing-in-a-worker.md)
  decision 1). Every view over a list of media — this gallery, the trash, the contributors' view, a guest's share —
  keeps the justified layout of the whole list but mounts only the rows near its viewport, so its DOM does not grow
  with the Memory, and keeps a bounded number of decrypted thumbnails, revoking the rest. A new view that renders every
  item, or opens a listed seal on the page at every render, is a defect against that record. The targets it is held to
  are ADR 0105's (§ Targets), judged by `scripts/measure_volume.py judge` over a stage's readings while the volume
  harness exists, and the volume harness met every one at the V1 heavy stage (2026-10-24, a local
  production-recipe instance, Chromium, three runs per stage, no failed request in any): *viewport complete* after
  403–414 ms at 1,000 photos, 712–734 ms at 3,000 and 1,826–1,845 ms at 10,000 on the desktop, and 510–550 ms and
  876–910 ms at 1,000 and 3,000 under the 4× throttle, with no long task after it over 120 ms; *end complete* 117–122 ms;
  at most 1,098 DOM nodes at any size. Seen in a browser: the windowed grid (that reading) and its keyboard model (lane
  L1's `gallery-keyboard.spec.ts`). Not yet: a screen reader's reading of it.
- **An upload's progress redraws its own tile, never the layout** (inbox #640). The justified layout's input is only
  what places a tile — its id, aspect ratio and capture time, and the preview it shows — and it changes only when a
  tile appears, gets its probe's answer, settles or moves. A tile's stage and progress are read from the queue's own
  item by that tile alone (`OptimisticTileVM.upload`), so a chunk, which writes the progress, re-renders one tile; when
  every chunk re-ran the layout over the whole gallery, that was the largest part of the main thread during a
  50-photo upload at the 4× throttle.
- **One order for every tile — including one that is still uploading.** The grid has a single ordering rule
  and no privileged front. A picked file takes its capture-time place the moment its tile appears — *before* it
  is sealed or uploaded — because that key is derivable on device from the same **EXIF date (a video's container
  time), else the file's date** rule the sealed tier will carry. Finishing an upload therefore changes a tile's **content** (the local
  preview becomes the real decrypted thumbnail), never its **place**. This is stated as an obligation because
  the obvious implementation breaks it: holding in-flight tiles in a block of their own is one line shorter and
  makes every completed upload jump *behind* the files still going — the more a user picks at once, the worse
  it reads.
- **Reuse the existing UI/UX — never invent** ([ui-web-styleguide](../../../crosscutting/ui-web-styleguide.md)
  § Reuse first). The grid + lightbox are existing components; only the **data layer** is client-side crypto
  over the contracts client. The item mark reuses the **same heart** as the Memory pin: a Memory in the shell
  and a photo in a grid are different elements in different places, so *what* is being marked was never the
  ambiguous part — a second icon would be invented UI solving a problem nobody has.
- **The aggregate is a presentation choice, not a confidentiality guarantee, and the copy here must not blur
  that.** Item marks seal under `K_SC`, which every member of a group holds, and the operator-blind server
  cannot aggregate for us — so the client receives the **per-member** blobs and *could* name who marked what.
  Showing only a count is a decision about the UI, not a property of the data. Therefore **no string on this
  screen may suggest that nobody can see what you marked**: *"only mine"* names a **filter**, never a promise.
  Nor is the count operator-proof: it is the operator's report of how many stored sets hold the id, which it can
  lower by leaving entries out and raise by serving stored sets again as extra entries (ADR 0068 §9).
  The reasoning and the rejected alternatives are in
  [ADR 0068](../../../decisions/0068-favorites-two-confidentiality-levels.md).
- **Two carriers tell the user the mark is shared, because neither covers the other's case.** The per-tile
  **count** says it continuously — but it fails at the exact moment it matters most, the **first** mark, where
  "1" is the user's own and proves nothing about who else sees it. That case is carried by the **filter**,
  which is present in the header precisely when marks are shared and hidden when they are not (E4). Dropping
  either one puts the user back to guessing from the icon.

## User impact

You look through a Memory's photos and videos quickly and comfortably — grid, large view, details, marks, zoom and
slideshow — with everything decrypted only on your own device.

## Related

- Feature: [Memory](../_index.md)
- Enablers: [en.memory.derivatives](../enablers/en.memory.derivatives.md) (the three tiers),
  [en.memory.media](../enablers/en.memory.media.md) (the blob transport),
  [en.memory.favorites](../enablers/en.memory.favorites.md) (the Memory pin **and** the item mark),
  [en.platform.node-tree](../../platform/enablers/en.platform.node-tree.md) (`K_node`)
- Decisions: [ADR 0013](../../../decisions/0013-blob-storage-presigned-direct-to-store.md) (direct-to-store
  media), [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (`K_node` → `K_blob`),
  [ADR 0068](../../../decisions/0068-favorites-two-confidentiality-levels.md) (the two favorite objects, the
  aggregate-only rule, and why the copy above says what it says)
- Related use-cases: [uc.memory.browse](uc.memory.browse.md) (done — the shell this opens from);
  [chapters](uc.memory.chapters.md) and the places map ([uc.memory.place](uc.memory.place.md)) — built; the journal — planned
- End-user view (docs site): [Is my data safe?](../../../../site/src/content/docs/privacy/is-my-data-safe.md)
- End-user view (docs site): [Mark the best photos](../../../../site/src/content/docs/memories/marked-photos.mdx)
- End-user view (docs site): [Look through the gallery](../../../../site/src/content/docs/memories/the-gallery.mdx)
