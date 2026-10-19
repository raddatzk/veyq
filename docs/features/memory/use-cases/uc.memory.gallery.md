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
   guest lists the node it has open, by the same paging rule, never the whole shared subtree — and fetches each
   item's **thumbnail** ([en.memory.derivatives](../enablers/en.memory.derivatives.md)) via presigned GET,
   decrypts on device, and renders a **grid** under a header (title, item count, pin toggle, and — in a group
   scope — the favorites filter). The grid is
   ordered by **effective capture time** — newest first by default, a header toggle for oldest first, undated
   media last — read from each media's sealed capture tier, so the blind server never sees a timestamp yet the
   order is exact on device (cr-0066).
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
   announced to a screen reader. A slow preview is waited for, never skipped. The next preview is fetched one ahead and
   dropped when it is not shown; while it runs, no EXIF read and no place-name backfill happen - they start when the
   slideshow pauses on an item. The member and the guest get both (share view = member view; a guest's loaders are its
   source's). Seen in a real browser on 2026-10-14 (a Memory of three 3000 x 2000 JPEGs, 2048 px previews): a
   double-click zoomed to 2x and fetched the original once (two chunks; the image became 3000 x 2000), ArrowRight panned
   while zoomed and did not page, Escape un-zoomed without closing, paging reset the zoom; the slideshow advanced every
   5 s, stopped on the last item announcing it, and fetched EXIF only on arriving there. Not yet seen in a browser: a
   video's poster in a slideshow, a pinch, ctrl+wheel, the 50 MB cap, a share guest's zoom.
3. **Lightbox:** clicking an item opens it full-view; the client fetches that item's **preview**, decrypts, and
   displays it; **prev/next** page through the grid order. (The preview — not the multi-MB original — keeps the
   lightbox fast.) A **video** (known from the media type sealed in its original's blob meta, which rides the
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
   fired), the provenance an editor wrote in (software, author, copyright, rating, title, description) and the
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
- **E5 — a filter that empties the grid** (e.g. *only mine* with nothing marked yet): the grid shows an empty
  state that names the active filter, so it never reads as a broken or empty Memory.
- **E6 — a medium blocked by the operator**
  ([uc.moderation.take-a-measure](../../moderation/use-cases/uc.moderation.take-a-measure.md)): its tile and the
  lightbox show *Blocked by the operator* with no reason and load no bytes (each answers `451 blocked_by_operator`), for
  members and guests alike; its uploader may still delete it, and it cannot be restored from the trash while blocked.
  A Memory blocked as a whole shows as that placeholder in its level and does not open.
- **E7 — a Memory or Chapter of thousands of photos** (inbox #488): the client asks for the thumbnails in grid order
  and never all at once — their presigns travel in batches of up to the contract's 256 blobs per request, at most two
  requests in flight, and at most sixteen store reads in flight across the page (`web/src/core/blob/readScheduler.ts`
  holds the limits and why). What the user opens — a preview, a video, a download, a cover — goes before the grid's
  thumbnails, and leaving the Memory drops the reads still waiting. Thumbnails that arrive within 100 ms of each other
  appear together, so the grid is not redrawn once per photo. A read without an answer, or answered `5xx`, `408` or
  `429`, is tried up to four times with a growing pause; a presigned URL that expired before it was used is presigned
  again, twice at most. A thumbnail that still cannot be read shows *Could not load* on its tile, never a placeholder
  that waits forever. Members and guests alike; the server sees the same blob ids and the same presigned reads, only
  batched and fewer at once.

## Quality notes

- **Operator-blind.** Every tier is fetched as **ciphertext** and decrypted on device; the server never sees a
  pixel, a filename, a media type or a content key. The lightbox shows the **preview**, not the original — the original
  leaves the store on explicit download, a video's playback, and a zoom past the preview (3a) - each fetched as
  ciphertext and opened on the device.
- **One order for every tile — including one that is still uploading.** The grid has a single ordering rule
  and no privileged front. A picked file takes its capture-time place the moment its tile appears — *before* it
  is sealed or uploaded — because that key is derivable on device from the same **EXIF date, else the file's
  date** rule the sealed tier will carry. Finishing an upload therefore changes a tile's **content** (the local
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
  [en.memory.tree](../enablers/en.memory.tree.md) (`K_node`)
- Decisions: [ADR 0013](../../../decisions/0013-blob-storage-presigned-direct-to-store.md) (direct-to-store
  media), [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (`K_node` → `K_blob`),
  [ADR 0068](../../../decisions/0068-favorites-two-confidentiality-levels.md) (the two favorite objects, the
  aggregate-only rule, and why the copy above says what it says)
- Related use-cases: [uc.memory.browse](uc.memory.browse.md) (done — the shell this opens from);
  [chapters](uc.memory.chapters.md) and the places map ([uc.memory.place](uc.memory.place.md)) — built; the journal — planned
- End-user view (docs site): [Is my data safe?](../../../../site/src/content/docs/privacy/is-my-data-safe.md)
- End-user view (docs site): [Mark the best photos](../../../../site/src/content/docs/memories/marked-photos.mdx)
- End-user view (docs site): [Look through the gallery](../../../../site/src/content/docs/memories/the-gallery.mdx)
