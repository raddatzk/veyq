# ADR 0149 — The apps take Memories' rest: a Memory re-keyed, downloaded and copied as the web's, its marks, filter and contributions, and every level drawn as tiles — the web's rules in the core
- Status: Accepted
- Built: yes — on every app (Android, iOS, macOS, Linux, Windows), 2026-11-19; passed on Android, Linux and Windows as
  throwaways and by the core's live tests, on macOS in part (§ Consequences, the passes; what is left inbox #994); the
  four owner questions answered 2026-11-19 (§ Consequences)
- Date: 2026-11-19
- Model: [native-apps](../crosscutting/native-apps.md) (the rights: [Memories](../apps/memories.md))

## Context

Block G of the V2 order is what the web does with a Memory that the apps still leave to the web. Measured 2026-11-19 on
the branch the refine read (each claim's command is in the refine's record of it, kept here as the claim itself):

- **A Memory stays due after a rotation.** The core's `AppKinds.rekeys` gives MEMORY and CHAPTER no content, so
  `NodeRekey` leaves such a node for a web device; an apps-only member's upload into it waits (`node_rekey_pending`).
  The web's re-key (`rekey.ts`) moves the banner's two wraps inside the node's meta, every medium's `media-meta` and
  `media-exif` (live and trashed) and each bucket name but a contributor's primary. The core's meta is opened and sealed
  with core's cover only, before the kind's hook runs, which is handed no meta.
- **A Memory's Download and Copy are closed.** `AppKinds.doors` answers `copies = false, downloads = false` for both kinds,
  and a folder's download leaves its Memories out (`unsupported_kind`). The web exports a Memory as the content of
  `contracts/export/memories/content.schema.json` (media, date, place, participants as text, cover and banner as files)
  and copies one by replaying that content with each medium's derived tiers read by frame (`addCopiedMedia`), never
  decoding an original. The core has the archive writer (`NodeExport`, `ZipWriter`) and the subtree copy (`SubtreeCopy`)
  that Depot and Expenses use; it has no ZIP reader and no import anywhere.
- **The gallery lacks the web's marks, filter, contributions and a medium's report.** The web marks a medium per member
  (a sealed set per scope and node, `en.memory.favorites`), narrows the grid by marks, a day, a year, a place, a kind and
  a name, lists who added what in a Contributions tab with curated sub-buckets (`en.memory.buckets`), names a guest from
  its bucket's sealed name, and reports a photo or a video from the lightbox. The core computes `MemoryRights.mark`,
  `filterFacts` and `buckets` and reads none of them.
- **The apps draw rows only.** The web offers every level, a person's Memories and a Memory's chapters as tiles (its
  default) or rows, one choice per account; the apps have no view mode, and a person's Memories are List | Map.

## Decision

1. **Two CRs, import a row of its own.** The marks, the filter, the contributions, the report, the re-key, the
   download, the copy and the tiles are the gallery's and the level's: the core ports web rules into models the four apps
   already draw, over operations already generated. A Memory as a share target (#893) is an entry point with a platform
   question of its own (iOS's share extension) and follows as its own change, landing on the gallery this one finishes.
   **Import** of an archive is missing for every kind in the apps (no ZIP reader, no import entry) and is its own row
   (#985), not built for Memories alone. Inside this decision the order is by soundness: the re-key first (it unblocks an
   apps-only member's uploads), then download and copy, then the gallery's additions, then the tiles.
2. **A Memory's re-key is the core's port, and the kind is handed the meta before it is sealed.**
   `core/…/memories/MemoryRekey.kt` ports `rekey.ts`: the banner's `BANNER` and `BANNER_POSTER` wraps in the node's meta;
   every medium's `media-meta` and `media-exif`, live and trashed, paged by `listMediaRekey` and staged by
   `stageMediaRekey` under the commit's re-key id at the contract's caps (256 media and 256 buckets per stage); each
   bucket name re-sealed under the node's new key but a READ_WRITE contributor's primary (its name is the server's copy
   of the identity's, re-sealed by core's part of the link root's commit). Every plaintext is re-sealed byte for byte and
   every `K_blob` kept — a medium's original and tiers are core's blobs, which the driver re-wraps (ADR 0109 point 3). A
   WRITE_ONLY guest's rows under its own `K_up` are not listed and stay as they are. `AppKinds.rekeys` gives MEMORY and
   CHAPTER that content, so neither is left for another device.
   - **The seam:** core's `RekeyContext` carries the node's meta, opened under the old key with core's cover already
     moved; the kind changes it, and only then does `NodeRekey` seal it under the new key — the web's hook shape
     (`hooks.ts`), so the banner's wraps move in the same re-sealed meta as the cover's. A field neither side knows is
     carried verbatim (the meta is never rebuilt from what was parsed). A kind with nothing in the meta (a Pot) leaves it.
   - **The item marks are the scope's, not the node's**: the scope rotation's participant (`ItemMarksRotation`) moves
     them; the node's re-key does not touch them.
3. **Download and copy: the web's content, the doors opened.** `core/…/memories/MemoryExport.kt` ports
   `exportMemoryContent` for both purposes, registered in `AppKinds.exports` and `AppKinds.copies`; a Memory's and a
   chapter's doors open both (`copies = true, downloads = true`).
   - **A download** writes the schema's content — each medium's original read by frame (its name, its type, its
     effective capture time as the payload's modified time and as `capturedAt`, `capturedAtCorrected`, its coordinates
     and place name, a track's name and activity, a music track's facts, its EXIF copy where it reads), the date, the
     place, the participants **as display names**, the cover and the banner as their own files — through `NodeExport`
     into the file the person chose, as a folder's download already does. A folder's download then carries its Memories
     and no longer names them left out. **A guest's download carries no names** (neither participants nor contributors),
     as the web's: a guest never reads the member directory.
   - **A copy** replays `addCopiedMedia`: per medium its original and each existing tier read by frame and uploaded under
     fresh `K_blob`s bound to the new medium, the blob meta each declared, the listed tier and the EXIF bundle sealed anew
     under the new node's key — never decoding an original (a video is never held whole on a phone). The new node is born
     with the date and the place sealed in its first meta; the cover is copied as its blob by frame; a banner (shared with
     a medium or not, ADR 0102) is copied as its own blob. Chapters are the copied Memory's children in the subtree's
     replay. What the copy leaves behind is said with a new word, `copy_loss_memory` (the web's `memory.copyLoss`:
     who was there, and who added each photo: the participants' ids mean nothing in a copy, and the copier is the
     uploader of every medium).
4. **Item marks: the core's model; on the tile a badge, the toggle in the lightbox.** `core/…/memories/ItemMarks.kt`
   reads `getItemFavorites` with the gallery and opens every set (the open and seal moved out of `ItemMarksRotation`,
   which then uses them), answering per medium its **count** and **markedByMe** (by the self tag, never from the count). A
   toggle is refused until the own set is read (ADR 0077), writes the own set through `putItemFavorites` with the
   `stale_epoch` re-seal, refuses the 12 285th mark with the web's words, and on a failure restores the heart and says
   so. In a personal scope there is no count. **Shown:** the tile carries a badge — the heart, filled when mine, with the
   count above zero — not a button: a phone's tile in the justified grid is one touch target (Material 3, HIG). The mark
   is toggled in the **lightbox's toolbar** on every platform and from a tile's menu where the platform has one (Apple's
   context menu). This is `uc.memory.gallery` step 7 ("and in the lightbox"), which the web's lightbox lacks (#986).
   **Offline** the marks' listing is a kept read; nothing is marked offline.
5. **The gallery's filter: the core's port over core's search chips.** `core/…/memories/GalleryFilter.kt` ports
   `galleryFilter.ts`: a day (the gallery's own chip), and year, place and kind through `search/Query.kt`'s
   `suggestChips`, `satisfies` and `chipKey` (reused, not copied), free text over the original's name, and the marks'
   mode all / everyone / mine (hidden in a personal scope); one predicate over the gallery's rows, so the grid and the
   lightbox page the same list. **Stored nowhere**; leaving the Memory clears it. Shown where `filterFacts` holds: a
   search field with the chips' suggestions in the Photos view's toolbar (Android's top-bar search, Apple's `.searchable`
   with tokens, Linux's `gtk::SearchBar`, Windows' `AutoSuggestBox`) and the marks' mode as the platform's filter menu.
   The search's vectors are not extended: the filter's rules are Memories', twinned from the web's tests, and `search/`
   names no application (ADR 0146).
6. **The contributions, a guest named, a medium's report.** `core/…/memories/Buckets.kt` ports `mediaBuckets.ts`: the
   listing, create, rename, delete and a medium's move, a bucket's name sealed under the node's key (`bucket-name`, or the
   ancestor key a guest's identity folder uses, ADR 0080 decision 6). The **Contributions** view joins the Memory's view
   switch — Photos | Music | Map | Contributions — where `buckets` holds: the uploader picker narrowing the grid and a
   member's own sub-buckets curated (**owner 2026-11-19:** the fourth segment, exactly as the web offers it — to a
   member only). **A guest is named** in the lightbox's details from its primary bucket's sealed name with
   "(guest)", as the web's; the buckets' listing is a kept read, so the names stay offline; nothing is curated offline.
   **A medium's report:** the lightbox offers *Report* for a photo or a video where the rights allow, through core's
   `ReportSheet` with `MediumReport.keys`, the path a track already takes.
7. **The download's cross-surface proof is a fixture.** A core test writes a fixed Memory (two media, a chapter, a date,
   a place, participants, a cover, an image banner) as a download into
   `src/web/src/features/memories/fixtures/app-memory-download.zip`, deterministically (fixed times, the writer's stored
   order), and fails when the committed file differs from what it writes (`VEYQ_WRITE_MEMORY_FIXTURE=1` rewrites it, as
   the QR fixture is rewritten). The web's `exportImport.test.ts` imports it through its own `importSubtree` and asserts
   the media, the facts and the banner. A core export the web cannot read is red on the web's side.
8. **Tiles: a view mode in core, one choice per account on the device, the platform's grid.**
   `core/…/navigation/ViewMode.kt`: `LIST | TILES`, and `MAP` where a screen offers it (a person's Memories), kept in
   the device store under `view-mode:<accountId>` as the map consent keeps its key — one value for every level, as the
   web keeps one. It applies to Home and a folder's level, an application's arranged list and a person's Memories
   (List | Tiles | Map). The levels offer no Map: the apps' Memories map is the bar's tab (ADR 0143), a deviation from
   the web's level map.
   - **The frame is core's:** `TileGrid.columns(width)` gives at least two columns on a phone (a 360-unit width two of
     about 160), more as the window widens (a 160-unit minimum, 12-unit gaps); a tile is its node's cover **square**,
     the name below and the row's second line under it — the platform's album grid. Every kind gets a tile; a folder's
     shows its cover or the folder symbol. **Owner 2026-11-19:** the first view is **Tiles**, the web's default (the
     apps behave exactly as the web); the two columns of square covers on a phone stay rather than the web's one column
     of 16:9 cards, since a tile's shape and columns are the look, and the look follows the platform's guidelines
     (owner 2026-11-09).
   - **The switch:** Android a segmented button row on the profile and a view toggle in the level's top bar; Apple a
     *View as* menu in the toolbar and the profile's segmented picker; Linux a grid/list toggle in the header bar and the
     profile's toggle group; Windows a *View* drop-down in the level's command bar and the profile's selector bar.
   - **A tile's actions are the row's** (the overflow, the context menu, a desktop's right click); the pick mode's dimming
     (ADR 0138) applies to a tile as to a row.
   - **Words (owner 2026-11-19, as recommended):** everything from the web's EN and DE but the switch's own, where the
     apps' short `node_view_list` "List" / "Liste" gains `node_view_tiles` "Tiles" / "Kacheln".
9. **Rights and the guest.** Every control follows `MemoryRights` as the core computes it: `mark` (a member, read-only
   included), `filterFacts`, `buckets`, the report's right, and copy and download from the doors and the session's role.
   A guest has none of the marks, the filter's marks mode or the contributions and sees the gallery as a member does
   otherwise (share view = member view); a READ guest's download carries no names. A link reached as its guest opens these models
   through the core's guest doors ([ADR 0150](0150-a-received-link-opens-in-the-app-as-its-guest-the-share-pages-rules-in-the-core-a-kept-link-a-bookmark-the-app-opened-by-a-veyq-link-that-carries-nothing.md) decisions 2 and 5).
10. **The bridge (Linux, Windows)** carries a gallery row's mark count and `mine`, the filter's state and the
    Contributions view's in the gallery's state, the lightbox's `canMark`, `marked` and `canReport`, and the view mode and
    tile frames in the shell's and the person's states; its calls are the marks' toggle, the filter's edits, the
    buckets', the lightbox's report and the view's set, and the header's existing download and copy for a Memory. No key,
    wrap, tag or blob ref crosses (`SecretFreeSchemaTest`); a dialog closes with its page and its tenant.
11. **No new permission, no new dependency.** The download's save dialogs and the copy's target picker are a folder's;
    the filter's controls, the badge, the grid and the switches are each platform's toolkit's.
12. **What the server sees: nothing new.** The same as from the web: a re-key's staged seals under the commit's id; a
    download's reads; a copy's uploads and blob rows; a member's own sealed mark set one revision later; a bucket's sealed
    name. No operation, field or code is added; the backend and the contracts are unchanged.

## Consequences

- **An apps-only member is never stuck behind a rotation in a Memory**: the app's background re-key and its pull before
  an upload finish a Memory and a chapter as they finish a folder or a Pot.
- **The meta handed to the kind** makes the re-key's seam the web's: a kind's own references in the node's meta move in
  the one meta core seals, never in a second write that would wait for the node it is re-keying.
- **A download is held to the web's importer by a committed fixture**, not by a schema validator in the core's tests: a
  validator would be a new test dependency, and an archive the schema accepts can still be one the importer refuses.
- **Import stays the web's** until #985; a person imports an app's download on the web.
- **The owner's answers** (2026-11-19; each was built as recommended, so nothing changed): the rule is that the apps
  **behave exactly as the web** and **look as their platform's guidelines** say (owner 2026-11-09). So the first view is
  Tiles, the web's default (`ViewMode.FIRST`, decision 8); the Contributions are the fourth view exactly as the web
  offers them (`Contributions.OFFERED`, decision 6); the switch's word is "Tiles" / "Kacheln" (`node_view_tiles`); and
  a phone's tiles stay two columns of square covers (`TileGrid.MIN_COLUMNS` = 2), not the web's one column of 16:9
  cards — a tile's shape and columns are the look, so the platform's album grid holds.
- **Where it was built otherwise than decided** (2026-11-19). *Decision 6's curation is the server's rule, not "a member's
  own":* the server lets any member curate any owner root — a sub-bucket under any primary, any sub-bucket renamed or
  deleted, a medium moved within one root, never a primary renamed and never a move across roots (ADR 0034 point 6,
  [en.memory.buckets](../features/memory/enablers/en.memory.buckets.md) § Ownership). The Android pass found the core
  taking another member's primary for the caller's (inbox #991); the core now names "You" by the caller's own id and
  offers only what the server takes (`Contributions.State`'s `createsSub`, `renames`, `moveTargets`). The web has the same
  display mistake (#1003, open). *Decision 3's copy* writes at the epoch the new node was born at, so a rotation another
  member makes mid-copy fails it and its node goes to the trash, where the web's copy pulls the node forward and re-sends
  (#988). *A bucket's lightbox* pages that bucket's media, not the gallery (#996). *Decision 5's
  places:* on Android the filter is the Photos view's own toolbar under the view switch (the shell's top bar is core's
  node header) and the marks' mode a filter chip's menu; on an iPhone it is `.searchable` with tokens, on an iPad and a
  Mac a field of the page's own above the grid, since the toolbar's one field is the shell's search (#995); a day chip
  is the viewer's calendar day, handed by each platform (#989). *Decision 8's grid:* Windows places the tiles on a
  `Canvas` at the core's `TileGrid` frames, as its gallery's grid, not an `ItemsRepeater` with a `UniformGridLayout`;
  Linux draws a tile's badge with icons of the app's own (Adwaita has no heart); Android's view switch scrolls where four
  segments do not fit. *Decision 10:* the bridge also carries `GalleryShown.marking` (where a toggle is taken, so a
  personal space's tiles mark), a bucket's media as gallery rows, and `Marks.Seen` (#997, #1001).
- **What the passes proved** (each as a throwaway owner and member, made and erased by the pass). *Android* (a throwaway
  API 36 emulator): one photo marked by two sessions — the count 2, each filled only for itself; the filter's day chip
  and the marks' mode; a sub-bucket made and media moved into it; a Memory downloaded into Downloads with its originals
  byte-equal; a copy into a folder, opened in the app (four media, no marks); the report sheet opened from the lightbox;
  tiles on Home, a folder, the Memories list and a profile, the choice kept across a restart and shared between the
  levels and the profile; the badge's and the toggle's labels read from the accessibility tree. *Linux* (Broadway): marks
  by two sessions; a photo reported and its receipt read; the local day chip; the mine mode; a sub-bucket made and a
  medium moved in and out; a Memory's download with its manifest; a copy, its loss line said; tiles from two to six
  columns as the window widened; the choice kept across a restart. *The core's live tests* as throwaways:
  `MemoryRekeyLiveTest` (a Memory left due, pulled forward by the member's upload, every medium and the banner opening
  after), `MemoryExportLiveTest` (a folder downloaded with its Memory, the Memory copied and read back to the byte),
  `MarksLiveTest` (two marks counted, each mine only for itself; a member's sub-bucket under the owner's primary taken,
  the owner's photo moved into the member's own sub-bucket refused, across roots — #991's server answers). The web imports the core's download through its own
  importer (`app-memory-download.zip`, decision 7).
- **What no pass has proved yet.** On Android and Linux: the re-key after a member's removal followed by an upload in the
  app (held by `MemoryRekeyLiveTest` in the core), a link guest's upload named in the app, a report seen in the
  moderation inbox, the copy opened on the web, a sub-bucket renamed and deleted, a screen reader's speech (TalkBack did
  not take the taps, #992; the Linux container has no accessibility bus, #998), German on Linux. On macOS: the marks
  counted by two sessions, *Copy to…* and *Report* after their fixes, a sub-bucket renamed and deleted, the columns across
  a resized window, the gallery's filter field (#1029) — inbox #994. **The owner's:** an iPhone and an iPad — the tiles at a phone's and a tablet's width, the
  badge's legibility, `.searchable`'s tokens, the lightbox's heart (no simulator boots here, #928).
- **What the Windows pass proved** (2026-11-19, the VM through UI Automation, a throwaway with a member on the fixed
  tenant `tw-win.localhost`; the marks' second session the core's `MarksLiveTest` as the member, then the member signed
  in on the same app): a tile's badge counted two marks, each session calling only its own mark its own; the lightbox's
  heart and a tile's right-click *Mark* (Shift+F10) flipped the caller's mark; the filter's `AutoSuggestBox` took a local
  day chip by keys and dropped it, free text narrowed the grid, the marks' menu narrowed it to *Only mine* and
  *Everyone's*; Contributions made, renamed, filled (a photo moved from its tile's menu) and deleted a sub-bucket, and the
  bucket's lightbox paged the bucket; *Report* on a photo was received; a Memory's *Download* (its zip: both photos and
  `.veyq/manifest.json`) and *Copy to…*; Home's, a folder's and a profile's tiles, the View's *List* kept across a
  restart and the tiles' columns following the window (4, 5, 7). A bucket picked in the picker ended the app (its items
  cleared inside the pick's own `SelectionChanged`); fixed in the pass.

- **What the macOS pass proved** (2026-11-19, the coordinator's main session with full-screen control, a throwaway with
  a member): a tile's context menu marked a photo (its badge ♥1) and the lightbox's heart took the mark back and set it
  again; the marks' menu narrowed the grid to *Only mine* (one item) and back to *All*; Contributions (the fourth
  segment): the bucket picker listed the owner's bucket with its count, the bucket's menu made a sub-bucket and a tile's
  *Move item* moved a photo into it, both counts following; Home drawn as tiles, the View menu's *List* chosen and kept
  across a restart; a Memory's *Download* through `NSSavePanel`: the zip held the photos and `.veyq/manifest.json`, each
  original byte-equal (`shasum`) to the file dropped in. Three defects it found were fixed after it, unclicked: *Copy
  to…*'s sheet drew only its title and Cancel (a Mac's sheet takes a `List`'s ideal size: `NodeMoveSheet` has a frame now);
  the report sheet from the lightbox took the lightbox's dark scheme on a light sheet and a Mac's columns `Form` (the
  lightbox's sheets take the window's scheme now, `ReportSheet` is grouped with a frame of its own); Home's list drew
  *Filter* with its segments at the window's far edge (its label hidden, as the other switches). The gallery's filter
  field took no click or keystroke; its cause is open (#1029).

## Alternatives considered

- **One change for all four rows** (#893 inside) — about twelve phases over two unrelated entry points, and the share
  target's iOS proof (the owner's) would hold the gallery's work open.
- **Memories' import inside this change** — the apps' first import, built for one kind.
- **A second meta write after the re-key** — a write sealing under a due node waits, and a second revision would race the
  commit.
- **A copy that re-derives the tiers** — it would hold a video whole on a phone.
- **The web's heart as a button on the tile** — a phone's tile is a single target; the desktops keep the apps' one shape.
- **A day chip in `search/`** — the web keeps it the gallery's, and the search's vectors would grow a rule no search uses.
- **One column of 16:9 cards on a phone** (the web's below 520 px) — a feed, not a grid; a phone's platform draws two
  columns. **A choice per level** — the web keeps one, and a person's sense of "how veyq shows things" is one.
- **Validating the download against the JSON schema in the core's tests** — see the Consequences.

Explained for users on the docs site (§ In the app on each): [Mark the best photos](../../site/src/content/docs/memories/marked-photos.mdx),
[See who added what](../../site/src/content/docs/memories/contributions.mdx),
[Look through the gallery](../../site/src/content/docs/memories/the-gallery.mdx) (the filter, a medium's report),
[People in your Memories](../../site/src/content/docs/memories/people-in-your-memories.md) and
[Views, tabs and favorites](../../site/src/content/docs/spaces/views-and-favorites.mdx) (the tiles),
[Download and import](../../site/src/content/docs/spaces/download-and-import.md),
[Copy things](../../site/src/content/docs/spaces/copy-things.mdx); what the app holds,
[Install the app](../../site/src/content/docs/getting-started/install-the-app.mdx).
