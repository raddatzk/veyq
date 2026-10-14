# UC: Open a Memory & browse its gallery

- **ID:** uc.memory.gallery
- **Primary actor:** A member with an **unlocked vault**, inside one of their spaces, who **opens a Memory**
  from the shell.
- **Goal:** Open a Memory and look through its media — a thumbnail **grid**, an item **lightbox** (full view,
  prev/next), and **download** an original — with every byte decrypted **on device**; **mark** the good ones and
  narrow the grid to *everyone's favorites* or *only mine*.
- **Preconditions:** The user has unlocked the vault and is browsing the tree
  ([uc.memory.browse](uc.memory.browse.md)); the Memory exists and its **`K_mem`** can be unwrapped; its media
  carry **derivatives** ([en.memory.derivatives](../enablers/en.memory.derivatives.md); a non-derivable item
  shows an icon tile).
- **Postconditions (success):** The user sees the Memory's media as a grid, opens any item in a lightbox and
  pages through, **plays a video** in place, can pin the Memory, can **mark individual items** and filter the
  grid by those marks, and can download an item's original. The server saw only **ciphertext fetches** — no
  pixel, no filename, no content key, and never which items are marked.

## Main flow

1. From the shell ([uc.memory.browse](uc.memory.browse.md)) the user clicks a Memory → it **opens**: the
   client unwraps the Memory's **`K_mem`** (already held from the tree).
2. The client **lists the Memory's media** ([en.memory.media](../enablers/en.memory.media.md)) and fetches each
   item's **thumbnail** ([en.memory.derivatives](../enablers/en.memory.derivatives.md)) via presigned GET,
   decrypts on device, and renders a **grid** under a header (title, item count, pin toggle, and — in a group
   scope — the favorites filter). The grid is
   ordered by **effective capture time** — newest first by default, a header toggle for oldest first, undated
   media last — read from each media's sealed capture tier, so the blind server never sees a timestamp yet the
   order is exact on device (cr-0066).
3a. **Zoom and slideshow in the lightbox** (decided 2026-10-14 by the coordinator on the owner's behalf, inbox #90 and
   #91; built the same day). **Zoom** (a photo; a video does not zoom): double-click / double-tap toggles 1x / 2x at the
   pointer, a pinch with two pointers and ctrl+wheel zoom continuously (1x - 8x), a drag pans within the zoomed photo's
   edges, and `+` / `-` / `0` are the keyboard path. Once the zoomed photo shows more device pixels than the preview
   has, the **original** is fetched and decrypted once for that item - only for a sealed media type every browser decodes
   (JPEG, PNG, GIF, WebP, AVIF, BMP; HEIC is left out, only Safari decodes it) and under 50 MB of ciphertext - and
   replaces the preview in place; anything else, or a failure, stays on the preview without a message. Navigating
   resets the zoom and drops the original; the arrow keys do not navigate while zoomed, and Escape un-zooms before it
   closes. **Slideshow:** a play / pause control in the lightbox's toolbar advances every 5 s through the grid order, a
   video showing its poster for one interval (no autoplay), and stops on the last item; any key, pointer or wheel in the
   lightbox (so any zoom or details interaction) pauses it, a hidden tab and a dialog over the lightbox too; the state is
   announced to a screen reader. A slow preview is waited for, never skipped. The next preview is fetched one ahead and
   dropped when it is not shown; while it runs, no EXIF read and no place-name backfill happen - they start when the
   slideshow pauses on an item. The member and the guest get both (share view = member view; a guest's loaders are its
   source's). Proven by unit tests; the first real-browser pass (zoom by wheel and double-click, a slideshow over a
   photo and a video) is the merge's.
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
- **E2 — vault locked / Memory not open:** without `K_mem` the media can't be read → the gallery is unavailable
  until unlock + the Memory is opened.
- **E3 — empty Memory:** the grid shows an empty state.
- **E4 — personal scope:** *"everyone's favorites"* is degenerate when the user is the only member, so the
  filter is **hidden** — not rendered disabled. The per-tile count is likewise pointless and is not shown; the
  item heart still marks, it just has no audience to report.
- **E5 — a filter that empties the grid** (e.g. *only mine* with nothing marked yet): the grid shows an empty
  state that names the active filter, so it never reads as a broken or empty Memory.

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

## What this means for you / Was das für dich bedeutet

**EN —** Open a Memory and you see its photos as a grid; tap one to view it large and swipe through the rest.
You can **mark the good ones with a heart**, and then show only the marked ones — either **everyone's** or
**just yours**. Worth knowing: in a shared space a mark on a photo is **not private** — the others in that
space see that the photo was marked, and the heart shows **how many** people marked it. veyq does not show
*who* did, but it cannot hide it either, so treat a mark as something you do in front of the group. The heart
on the **Memory itself** is the other way round: that one is **only yours**, and nobody else can see it.
A **video** shows a small ▶ badge on its tile — open it and press play to watch it right there. Everything is
decrypted on **your own device** — the server only ever stores scrambled files and never sees your pictures or
videos. Pictures are ordered by **when they were taken**, newest first unless you flip the order — and one that
is still uploading already sits in its right place, so nothing jumps around once it finishes. Open a picture
and a panel beside it tells you what the picture knows about itself: the file and its size, the camera and
lens, how it was exposed, who made it — and, if it was taken with location on, **where**, as a place name and a
small map rather than a pair of numbers. Nothing you did not give it appears there, and none of it is ever
readable by the server. To keep it quick, the grid and the large view use **compact copies your device made**; the
**full-quality original is only fetched when you download it, or zoom into a photo further than the compact copy
can show** — and a video is **streamed piece by piece as you watch**, so only the parts you actually play are fetched, not the whole file. Double-click or pinch a
photo to **zoom** in, and press play for a **slideshow** that moves on every five seconds and stops the moment you
touch anything. Journal, chapters, maps and playlists come in later steps.

**DE —** Öffne eine Erinnerung und du siehst ihre Fotos als Raster; tippe eines an, um es groß zu sehen und
durch die anderen zu blättern. Du kannst **die guten mit einem Herz markieren** und dann nur die markierten
anzeigen — entweder **die von allen** oder **nur deine**. Wichtig zu wissen: In einem geteilten Bereich ist
eine Markierung an einem Foto **nicht privat** — die anderen in diesem Bereich sehen, dass das Foto markiert
wurde, und das Herz zeigt, **wie viele** Leute es markiert haben. veyq zeigt nicht, *wer* es war, kann es aber
auch nicht verbergen; betrachte eine Markierung also als etwas, das du vor der Gruppe tust. Das Herz an der
**Erinnerung selbst** ist umgekehrt: das gehört **nur dir** und ist für niemanden sonst sichtbar. Ein **Video** zeigt ein kleines ▶-Abzeichen auf seiner Kachel — öffne es und
drücke Play, um es direkt dort anzusehen. Alles wird auf **deinem eigenen Gerät** entschlüsselt — der Server
speichert nur verschlüsselte Dateien und sieht deine Bilder und Videos nie. Sortiert wird nach **Aufnahmezeit**,
neueste zuerst, solange du die Richtung nicht umdrehst — und was noch hochlädt, steht schon an seiner richtigen
Stelle, sodass nach dem Fertigwerden nichts mehr springt. Öffnest du ein Bild, erzählt dir eine Leiste daneben,
was das Bild über sich selbst weiß: die Datei und ihre Größe, Kamera und Objektiv, wie belichtet wurde, wer es
gemacht hat — und, wenn es mit eingeschaltetem Ort aufgenommen wurde, **wo**, als Ortsname und kleine Karte
statt als Zahlenpaar. Es steht dort nichts, was du nicht selbst mitgegeben hast, und nichts davon kann der
Server lesen. Damit es flott bleibt, nutzen das
Raster und die Großansicht **kompakte Kopien, die dein Gerät erstellt hat**; das **Original in voller Qualität
wird nur geladen, wenn du es herunterlädst oder ein Foto weiter vergrößerst, als die kompakte Kopie zeigen kann** — und ein Video wird beim Ansehen **Stück für Stück
gestreamt**, sodass nur die tatsächlich abgespielten Teile geladen werden, nicht die ganze Datei. Mit Doppelklick oder zwei
Fingern **vergrößerst** du ein Foto, und Play startet eine **Diashow**, die alle fünf Sekunden weiterblättert und
anhält, sobald du etwas berührst. Journal, Kapitel, Karten und Playlists kommen in späteren Schritten.

## Related

- Feature: [Memory](../_index.md)
- Enablers: [en.memory.derivatives](../enablers/en.memory.derivatives.md) (the three tiers),
  [en.memory.media](../enablers/en.memory.media.md) (the blob transport),
  [en.memory.favorites](../enablers/en.memory.favorites.md) (the Memory pin **and** the item mark),
  [en.memory.tree](../enablers/en.memory.tree.md) (`K_mem`)
- Decisions: [ADR 0013](../../../decisions/0013-blob-storage-presigned-direct-to-store.md) (direct-to-store
  media), [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (`K_mem` → `K_blob`),
  [ADR 0068](../../../decisions/0068-favorites-two-confidentiality-levels.md) (the two favorite objects, the
  aggregate-only rule, and why the copy above says what it says)
- Related use-cases: [uc.memory.browse](uc.memory.browse.md) (done — the shell this opens from);
  the journal / chapters / map — planned
- End-user view (wiki): [Is my data safe?](../../../../wiki/en/is-my-data-safe.md)
