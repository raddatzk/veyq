---
id: cr-app-memories-rest
title: Memories' rest in the apps — a Memory re-keyed, downloaded and copied; item marks, the gallery's filter, the contributions and a medium's report; every level and a person's Memories drawn as tiles
status: in-progress
satisfies: []
adrs: ["0077", "0080", "0082", "0109", "0114", "0122", "0123", "0136", "0137", "0140", "0144", "0145", "0146", "0149"]
invariants:
  - "operator-blind E2EE: the apps send the server exactly what the web sends for each operation (the same operationIds, the same sealed fields, the same contexts); no endpoint, field or schema is added, the backend and the contracts are unchanged"
  - "every rule is the core's (ADR 0136): the marks' open, count and write, the filter's chips and predicate, the contributions' buckets and a guest's name, a Memory's re-key, download and copy, the view mode and the tile frames; a platform draws the core's state and decides nothing"
  - "a re-key re-seals every plaintext byte for byte and keeps every K_blob, moving only its wrap (ADR 0109 point 2), and a sealed field this client does not know is carried verbatim through every re-seal (en.memory.node-meta)"
  - "a download is the web's archive: what the core writes for a Memory is what the web's importer reads (contracts/export/memories/content.schema.json), held by a fixture the core writes and the web's import test reads"
  - "nothing on disk the app did not choose (src/mobile/CLAUDE.md): a download is written by the core into the file the person chose; the marks and the buckets are kept only as the offline store's sealed listing answers"
  - "share view = member view (inbox #401): a guest sees what a member sees minus the functions its rights remove — no guest-only screen"
  - "product words: Memory, Chapter, application; no word in an app but from src/mobile/words/words.json, the web's where the web has one; each app follows its platform's guideline (ADR 0123 decision 9)"
  - "no pass of this CR signs in as the seeded principal or the seeded member: every write runs as a throwaway made and erased by the pass (ADR 0148 decision 12)"
---

## Goal

Block G of the V2 order ("Memories' rest", `docs/inbox.md` § V2): what the web does with a Memory that the apps still
leave to the web. This CR takes three of its four rows; the fourth is sequenced after it (D1).

- **#892 — re-key, download, copy.** A Memory or a chapter left due by a rotation waits for a web device today, and an
  apps-only member cannot upload into it meanwhile; its Download and Copy doors are closed, and a folder's download
  leaves its Memories out. The apps then re-key a Memory as the web does, and download and copy it as the web's archive.
- **#891 — marks, the filter, the contributions, a medium's report.** The apps then mark a medium for the space (the
  heart with its count), narrow the gallery by marks, days, places, kinds and a name, open the contributions (who added
  what, a guest named, sub-buckets curated) and report a photo or a video.
- **#950 — tiles.** Every level (Home, a folder, an application's list) and a person's Memories can be drawn as tiles as
  well as rows, one choice remembered per account on the device.

## Context & constraints

- **Read first:** ADR 0082 (all; decision 11 the copy), ADR 0109 and 0114 (the re-key), ADR 0077 (a write carries the
  version it read), [en.memory.favorites](../docs/features/memory/enablers/en.memory.favorites.md),
  [en.memory.buckets](../docs/features/memory/enablers/en.memory.buckets.md),
  [uc.memory.buckets](../docs/features/memory/use-cases/uc.memory.buckets.md),
  [uc.memory.gallery](../docs/features/memory/use-cases/uc.memory.gallery.md) steps 7–8 and E4/E8,
  [uc.platform.export-import](../docs/features/platform/use-cases/uc.platform.export-import.md),
  [uc.platform.copy-node](../docs/features/platform/use-cases/uc.platform.copy-node.md), ADR 0136, ADR 0137, ADR 0140
  decisions 5 and 7, ADR 0144 (the shape this CR follows), ADR 0146 (the search chips the filter reuses), ADR 0148 decision
  12 (throwaways); `src/mobile/CLAUDE.md` whole (§ Nothing on disk, § Each platform's guidelines, § Traps).
- **The web is the reference implementation** (R1–R9); its tests are the list the core's tests mirror, each web test a
  Kotlin twin as in ADR 0144. Where this CR differs it says so and why (D4's mark in the lightbox and the badge, D8's
  tiles on a phone and the level's Map).
- **Where what runs** (from `src/mobile/`): `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :core:jvmTest
  :core:macosArm64Test :bridge:jvmTest :android:assembleDebug`; the Apple builds (`xcodebuild -project
  apple/Veyq.xcodeproj -target Veyq -sdk macosx build CODE_SIGNING_ALLOWED=NO`, and `-sdk iphonesimulator`) after
  `./gradlew :core:assembleVeyqCoreDebugXCFramework`; `/usr/bin/python3 linux/run.py test|bridge|build|run`,
  `/usr/bin/python3 windows/run.py test|bridge|build|run`; live tests with `VEYQ_LIVE=http://veyq.localhost:8080` and
  `VEYQ_ENV_FILE=<a throwaway's env file>`; the web's `pnpm run check` in `src/web/` for the fixture test (D7).
- **Machine hints (hold for the planned shape; re-derive them):** no iOS simulator boots under this Mac's Xcode (#928) —
  the iPhone's passes are the owner's, the macOS build runs here; Linux passes in the Fedora container through Broadway,
  Windows on the ARM64 VM through UI Automation; Gradle needs `--rerun` per task when only the environment changed.
- **Out of scope** (each its own row or CR): a Memory as a share target and a drop onto a phone's gallery (#893, its own
  CR, D1); **import** of an archive in the apps — for every kind, not Memories alone (#985, new, D1); the web lightbox's
  heart (#986, new, D4); a guest's view of a received link (#435, block H); the live stream (#6).
  Nothing in the backend or the contracts changes; in `src/web/` only the fixture's test (D7).

## Measured at refine (2026-11-19)

Branch `claude/g-refine` at `42ac591de`, clean before this CR. `C=src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile`,
`W=src/web/src/features/memories`, commands from the repo root. A plain `grep -r` over `src/mobile` walks the build
directories; use `git grep`.

| # | What | Where / command |
|---|---|---|
| R1 | **The web's marks.** A member's own set of media ids per (scope, node), sealed under a fresh value key wrapped under the current K_SC (`item-favorites`, bound to scope and node), its plaintext `tag ‖ ids` with the tag an HMAC under `itemMarkSelfKey`; read as `[{self, ciphertext}]` with no member id, written own set only; a toggle waits for the own set (ADR 0077); at most 262 144 chars (12 284 marks); `stale_epoch` re-sealed. Shown on the **tile** (heart, count, filled when mine; no count in a personal scope), **not in the lightbox** — which `uc.memory.gallery` step 7 asks for. Right `mark` for a member, read-only included; a guest none. Tests: `itemFavorites.test.ts` 25, `itemFavoritesStore.test.ts` 7, `MediaTile.test.ts` 5. | `git grep -n operationId -- contracts/api/memories/item-favorites.openapi.yaml`; `grep -n 'ITEM_MARKS_CIPHERTEXT_MAX\|export async function' $W/itemFavorites.ts`; `sed -n 31,37p $W/components/MediaTile.vue`; `git grep -n -i 'favorit\|mark' -- $W/components/MediaLightbox.vue` → none; `sed -n 108,110p docs/features/memory/use-cases/uc.memory.gallery.md` |
| R2 | **The web's filter** (step 8): `markFilter` all / everyone / mine (three toggles, hidden in a personal scope, reset when another Memory opens) AND `galleryFilter.ts`' chips — a day (`DayChip`, the gallery's own) and year, place, kind from core search's `SearchChip` (`suggestChips`, `satisfies`, `chipKey`) — and free text over the original's name; one predicate for the grid and the lightbox; **stored nowhere**. Tests: `galleryFilter.test.ts` 19, `MemoryDetail.filter.test.ts` 7, `MemoryDetail.search.test.ts` 8. | `sed -n 18,35p $W/galleryFilter.ts`; `grep -n 'galleryPredicate\|markFilter' $W/galleryFilter.ts $W/components/MemoryDetail.vue \| head`; `grep -n 'nothing is written anywhere' $W/components/MemoryDetail.vue` |
| R3 | **The web's contributions** ("Beiträge", `MemoryBuckets.vue`, cr-0122): an uploader picker that narrows the grid and sub-buckets created, renamed, deleted and filled; a bucket's name sealed under K_node (`bucket-name`, scope + node + bucket); a guest named in the lightbox from its primary bucket's sealed name (`useUploaderNames.ts`, "(guest)"), a member from the people directory, else "Former member". The gallery filter has **no uploader facet**. Tests: `media.buckets.test.ts` 12, `MemoryBuckets.test.ts` 20, `useUploaderNames.test.ts` 7. | `grep -n 'export async function\|export function' $W/mediaBuckets.ts`; `grep -n 'MemoryBuckets\|Beiträge' $W/components/MemoryDetail.vue`; `git grep -n 'operationId: \(listNodeBuckets\|createBucket\|renameBucket\|deleteBucket\|moveBucketMedia\)' -- contracts/api/memories/media.openapi.yaml` |
| R4 | **The web's report of a medium:** the lightbox offers *Report* (`canReport`) and opens core's report dialog with the medium's keys (`mediumReportKeys` in `media.ts`, its test `mediumReportKeys.test.ts` 2). | `sed -n 97,105p $W/components/MediaLightbox.vue`; `git grep -n 'mediumReportKeys' -- $W/media.ts` |
| R5 | **The web's Memory download, import and copy** (ADR 0082): `exportMemoryContent` / `importMemoryContent` / `copyLosses: ['memory.copyLoss']` registered; content `memories.memory` / `memories.chapter` = media, date, place, participants as text, cover, banner (its own files); a copy carries per medium the derived tiers read by frame, their blob meta and the listed tier verbatim (`addCopiedMedia`, never decoding), the cover by frame (`uploadCoverBlob`); a guest's download carries no names. Schema: `contracts/export/memories/content.schema.json`. Size: `exportImport.ts` 617 lines, its test 33 cases. | `git grep -n 'exportContent\|importContent\|copyLosses\|rekeyContent' -- $W/register.ts`; `sed -n 1,40p $W/exportImport.ts`; `wc -l $W/exportImport.ts`; `ls contracts/export/memories/` |
| R6 | **The web's Memory re-key** (`rekey.ts`, 81 lines): the banner's blob and poster wraps inside the node meta; per medium, live and trashed, `media-meta` and `media-exif` re-sealed (`listMediaRekey` / `stageMediaRekey`); each bucket name but a contributor's primary. Originals and derivatives are core's blobs, re-wrapped by the driver. The hook is handed the opened meta to change in place. | `sed -n 1,12p $W/rekey.ts`; `sed -n 15,25p src/web/src/core/node/rekey/hooks.ts` |
| R7 | **The core leaves a Memory due and its doors closed:** `MEMORY, CHAPTER -> Doors(coverOffered = true, copies = false, downloads = false)`; `rekeys` gives a Memory no content, so `NodeRekey` answers `Step.LEFT` ("stay due for a web device"); the meta is opened and re-sealed with `rewrapCover` only, before `content.rekey(RekeyContext(…))` runs, which carries no meta. An upload or a banner set into a due node asks `NodeRekey.pull`, false where another device's path holds it → `node_rekey_pending`. Expenses' `PotRekey` (38 lines), `PotExport`, `PotCopy` are the templates. | `grep -n 'MEMORY, CHAPTER -> Doors\|web device' $C/AppKinds.kt`; `grep -n 'return Step.LEFT' $C/session/NodeRekey.kt`; `sed -n 329,331p $C/session/NodeRekey.kt`; `git grep -n 'NodeRekey.pull' -- $C/memories`; `wc -l $C/expenses/PotRekey.kt` |
| R8 | **The core's export and copy exist for Depot and Expenses:** `export/NodeExport.kt` (walks, skips an unexported kind as `UNSUPPORTED_KIND` — the word `download_left_out`), `export/SubtreeCopy.kt`, `export/Zip.kt` (`ZipWriter` only, **no reader**), `FileSink`/`FileSource`; no import anywhere in the apps. The words have no `copy_loss_memory`. | `wc -l $C/export/*.kt`; `grep -n '^class\|^object' $C/export/Zip.kt`; `git grep -n -i -E 'import(Subtree\|Archive)\|ZipReader\|unzip' -- src/mobile` → a comment only; `grep -n '"download_left_out"\|"copy_loss_' src/mobile/words/words.json` |
| R9 | **The core's marks, buckets and rights:** `MemoryRights.mark`, `filterFacts`, `buckets` computed, read by no model; `crypto/ItemMarks.kt` and `memories/ItemMarksRotation.kt` (the retag and the scope re-key participant, its private `openMarks`/`seal`); the generated `ItemFavoritesApi` (`applicationSpecs` holds `memories/item-favorites`) has no per-node reader; `listNodeBuckets` is generated, called by nothing; `MediaDetails.uploaders` names a guest only "a guest" ("the buckets', not read here yet"); `MediumReport` serves tracks only. | `grep -n 'val mark\|val filterFacts\|val buckets' $C/memories/MemoryRights.kt`; `git grep -n 'getItemFavorites\|listNodeBuckets' -- $C` → none; `grep -n applicationSpecs src/mobile/core/build.gradle.kts`; `grep -n "not read here yet" $C/memories/MediaDetails.kt`; `git grep -n 'MediumReport' -- $C/navigation` |
| R10 | **The core's search chips** (`search/Query.kt`): Year, Month, Place, Person, Kind, In; `suggestChips`, `satisfies`, `chipKey`; no day chip (the web's is the gallery's own). | `sed -n 5,19p $C/search/Query.kt`; `grep -n 'fun suggestChips\|fun satisfies' $C/search/Query.kt` |
| R11 | **The web's tiles:** core's `ViewModeToggle.vue` + `levelViews.ts` (grid, list; Memories adds map, `memoryViews.ts`), offered on a level (the union of what is on screen, shown from two options), a person's Memories (`PersonMemories.vue`) and a Memory's Chapters tab; one value per account in `localStorage` (`veyq:view-mode`, default grid). A card: a 16:9 cover flush on top, the name and a meta line (a Memory's date and place); a folder a compact tile. **On a phone the web draws one column:** `.veyq-level-list` is `minmax(min(100%, 210px), 1fr)` and one column below 520 px — two columns would need 436 px of content. Tests: `ViewModeToggle.test.ts` 4, `useViewMode.test.ts` 5, `PersonMemories.test.ts` 22. | `grep -n 'FOLDER_VIEWS\|GRID_VIEW' src/web/src/core/node/levelViews.ts`; `grep -n "veyq:view-mode" -r src/web/src/shared/state/`; `sed -n 1077,1087p src/web/src/styles/ui.css`; `sed -n 1512,1517p src/web/src/styles/ui.css` |
| R12 | **The apps draw rows only:** no view mode in the core; `PersonMemories.View { LIST, MAP }`, "not stored … No tiles (inbox #950)"; each platform's level a list (`ShellScreen.kt` `ObjectRow`, `ShellView.swift` `ObjectRow`, `home.rs` `adw::ActionRow`, `HomeView.cs` `ListViewItem`) with a 34-unit cover; the profile's switch a `SingleChoiceSegmentedButtonRow`, a segmented `Picker`, an `adw::ToggleGroup`, a `SelectorBar`. A device setting is kept in core's `DeviceStore` (`MapConsent`'s `maps-consent`). | `git grep -n -i 'viewMode' -- $C` → none; `sed -n 19,35p $C/memories/people/PersonMemories.kt`; `git grep -n 'KEY = "maps-consent"' -- $C/map` |
| R13 | **The bridge:** `GalleryShown`, `GalleryRow`, `LightboxShown` carry no mark, filter, bucket or report; `ShellShown` carries `groups` and `list` with covers per `Call.ShellRowCover`; `PersonShown.memories` the view enum. | `grep -n 'class GalleryShown\|class GalleryRow\|class LightboxShown\|class ShellShown\|class PersonShown' src/mobile/bridge/src/commonMain/kotlin/me/raddatz/veyq/mobile/bridge/Wire.kt` |
| R14 | **Each platform's gallery and its controls:** Android `MemoryScreen.kt` (`MemoryViewSwitch`, the lightbox's `TopAppBar` actions; a tile has no long press), Apple `MemoryScreen.swift` (the lightbox's `ToolbarItemGroup`, a tile's `.contextMenu`), Linux `memory_page.rs` + `lightbox.rs` (`adw::ToggleGroup`), Windows `MemoryPage.cs` + `LightboxView.cs` (`SelectorBar`); none draws a mark, a filter, a medium's report; all four the uploader in the details. | `git grep -n 'MemoryViewSwitch\|contextMenu\|ToggleGroup\|SelectorBar' -- src/mobile/android src/mobile/apple/Veyq src/mobile/linux/src src/mobile/windows/Veyq \| grep -i memory`; `git grep -n 'combinedClickable\|onLongClick' -- 'src/mobile/android/*MemoryScreen.kt'` → none |
| R15 | **Offline:** the kept reads are key material, tenants, scopes, nodes, node media, files, application blobs and members — neither the marks nor the buckets. | `sed -n 23,42p $C/offline/KeptRead.kt` |
| R16 | **The site says nothing of the gap:** `install-the-app.mdx`'s "Not yet in it" names none of marks, the filter, the contributions, a download or a copy of a Memory; `people-in-your-memories.md` says the app shows "no tiles"; `views-and-favorites.mdx`, `marked-photos.mdx`, `the-gallery.mdx`, `contributions.mdx`, `download-and-import.md`, `copy-things.mdx` tell the web's (each EN and DE). | `sed -n 18,24p site/src/content/docs/getting-started/install-the-app.mdx`; `git grep -n -i 'no tiles' -- site/src/content/docs`; `ls site/src/content/docs/memories site/src/content/docs/spaces` |

## Decisions

The ADR phase (1) writes D1–D12 as **ADR 0149** ("The apps take Memories' rest: a Memory re-keyed, downloaded and copied
as the web's, its marks, filter and contributions, and every level drawn as tiles — the web's rules in the core"; 0147 is
taken on another branch, 0148 is the account); each "Rejected" is its Alternatives.

### D1 — Block G is two CRs: this one, then #893's; import is a row of its own

#891, #892 and #950 are the gallery's and the level's: the core ports web rules into models the four apps already draw,
over operations already generated. **#893 is not:** it is an entry point (the system's share sheet, a drop), its core
part generalises a gate that today names the Pot alone (`AppKinds.takesShared`), it reaches Depot's folder as well
(ADR 0138 decision 2), and its hard half is iOS's share extension uploading from disk — a platform question whose proof
is the owner's (no simulator boots, #928). It is refined as **[cr-app-share-into-a-memory](cr-app-share-into-a-memory.md)**
and implemented after this CR, so its Memory target lands on the gallery this CR finishes. **Import** of an archive is
missing for every kind in the apps (R8: no ZIP reader, no import entry on any platform) — a core-wide feature, filed as
**#985**, not smuggled in as "Memories' import". Inside this CR the order is by soundness: the re-key first (it unblocks
an apps-only member's uploads, R7), then download and copy, then the gallery's additions, then the tiles.
- Rejected: **one CR for all four rows** — about twelve phases over two unrelated entry points, and the share target's
  iOS proof would hold the gallery's work open. Rejected: **#892's import inside** — it would be the apps' first import,
  built for one kind.

### D2 — A Memory's re-key: the core's port, the meta handed to the kind before it is sealed

`core/…/memories/MemoryRekey.kt` ports `rekey.ts` (R6): the banner's `BANNER` and `BANNER_POSTER` wraps in the node's
meta; every medium's `media-meta` and `media-exif`, live and trashed, paged by `listMediaRekey` and staged by
`stageMediaRekey` under the commit's re-key id at the contract's caps; each bucket name under the node's key but a
contributor's primary. `AppKinds.rekeys` gives MEMORY and CHAPTER that content; a Memory is no longer `LEFT`.
- **The seam (R7):** `RekeyContext` gains the opened node meta, which the kind changes in place **before** `NodeRekey`
  seals it — the web's hook shape (`hooks.ts`) — so the banner's wraps move in the same re-sealed meta as the cover's.
  `PotRekey` ignores it; its tests stay green unchanged (the refactor's proof).
- **The item marks** are the scope's, not the node's: `ItemMarksRotation` already moves them at a scope rotation; this CR
  does not touch it.
- Rejected: **a second meta write after the re-key** — a write sealing under a due node waits, and a second revision
  would race the commit.

### D3 — Download and copy: the web's content, the doors opened

`core/…/memories/MemoryExport.kt` ports `exportMemoryContent` (R5) for `purpose` download and copy, registered in
`AppKinds.exports()` and `copies()`; the doors become `Doors(coverOffered = true, copies = true, downloads = true)`.
- **Download:** the content of `contracts/export/memories/content.schema.json` — media (the originals read by frame
  through the core's reader), date, place, participants **as text** (names from the people the session reads; a guest's
  download none), cover and banner as their own files — through `NodeExport.write` into the file the person chose, as a
  folder's download already is. A folder's download then carries its Memories; `download_left_out` no longer names them.
- **Copy:** `MemoryCopy` replays `addCopiedMedia`: each medium's original and existing tiers by frame under fresh
  `K_blob`s, their blob meta, the listed tier verbatim, never decoding; the cover by frame; the copy's losses said with a
  new `copy_loss_memory` word (the web's `memory.copyLoss`). A shared banner (ADR 0102) is copied as its own blob, as the
  web does.
- Rejected: **a Memory's copy that re-derives tiers** — it would hold a video whole on a phone, the trap
  `docs/ai/README.md` names from cr-copy-node.

### D4 — Item marks: the core's model, a badge on the tile, the heart in the lightbox

`core/…/memories/ItemMarks.kt` (the model; the crypto stays `crypto/ItemMarks.kt`): reads `getItemFavorites` with the
gallery, opens every set (`openMarks`/`seal` moved out of `ItemMarksRotation`, which then uses them — the second user
moves it), and answers per medium its **count** and **markedByMe**; a toggle is refused until the own set is read (ADR
0077), writes the own set through `putItemFavorites` with the `stale_epoch` re-seal, refuses the 12 285th with the web's
`tooMany`, and on a failure puts the heart back and says `saveFailed` (E8). In a personal scope there is no count (E4).
- **Shown:** the tile carries a **badge** — the heart, filled when mine, with the count when above zero — not a button:
  the justified grid's tiles on a phone are too small for a second touch target over the tap that opens the medium.
  **The mark is toggled in the lightbox** (its toolbar, every platform) and, where the platform has a tile menu, from
  it (Apple's `.contextMenu`, R14). This is `uc.memory.gallery` step 7 ("and in the lightbox"); the web lacks the
  lightbox's heart, which becomes **#986** (the web catches up with its own use case).
- **Offline:** the marks' listing joins the kept reads (`KeptRead`, ADR 0140 decision 5 as the members' did), so a
  kept Memory shows its badges; nothing is marked offline (decision 7).
- Rejected: **the web's button on the tile** — a phone's tile is a single target (Material 3, HIG); the desktops keep
  the apps' one shape.

### D5 — The gallery's filter: the core's port over core's search chips

`core/…/memories/GalleryFilter.kt` ports `galleryFilter.ts` (R2): the day chip (the gallery's own), year, place and kind
through `search/Query.kt`'s `suggestChips`, `satisfies` and `chipKey` (R10 — reused, not copied), the free text over the
original's name, and the marks' mode all / everyone / mine (hidden in a personal scope); one predicate over
`GalleryModel`'s rows, so the grid and the lightbox page the same filtered list. **Stored nowhere**; leaving the Memory
clears it. Shown where `filterFacts` holds and the Memory's key is open: a search field with the chips' suggestions in the
Photos view's toolbar (Android's top bar search, Apple's `.searchable` with tokens, Linux's `gtk::SearchBar`, Windows'
`AutoSuggestBox`), the marks' mode beside it as the platform's filter menu. The search's vectors are not extended: the
filter's rules are Memories', twinned from the web's tests (ADR 0146's guard keeps `search/` naming no application).
- Rejected: **a day chip in `search/`** — the web keeps it the gallery's; the search's vectors would grow a rule no search
  uses.

### D6 — The contributions: the web's tab, a guest named, and a medium's report

- `core/…/memories/Buckets.kt` ports `mediaBuckets.ts` (R3): `listNodeBuckets`, create, rename, delete, move a medium,
  the `bucket-name` seal under K_node (or the ancestor key a guest's identity folder uses, ADR 0080 decision 6). The
  **Contributions** view (`memory.buckets.*`'s words) joins the Memory's view switch as the web's tab does — Photos |
  Music | Map | Contributions — where `buckets` holds: the uploader picker narrowing the grid, a member's own sub-buckets
  curated. *Owner question 3* (the fourth segment on a phone).
- **A guest is named** in the lightbox's details from its primary bucket's sealed name, "(guest)" as the web's
  `guestDisplayName` (`MediaDetails.uploaders` reads the buckets; its "not read here yet" goes). The buckets' listing
  joins the kept reads, so offline names stay; nothing is curated offline.
- **A medium's report:** the lightbox offers *Report* for a photo or a video where the rights allow, through core's
  `ReportSheet` with `MediumReport.keys` — the track's path, now for every medium (src/mobile/CLAUDE.md's "not yet: inbox
  #891" goes).

### D7 — The download's cross-surface proof

A core test writes a fixed Memory (two media, a chapter, date, place, participants, cover, an image banner) as a
download into `src/web/src/features/memories/fixtures/app-memory-download.zip` (deterministic: fixed times, `ZipWriter`'s
stored order); `exportImport.test.ts` gains a case importing it through the web's own `importSubtree`, asserting the
media, the facts and the banner. A changed core export that the web cannot read is red on the web's side; the core's
test fails when the committed fixture differs from what it writes (regenerated with `-Pveyq.writeFixture`).
- Rejected: **validating against the JSON schema in the core's tests** — a schema validator is a new test dependency,
  and a schema-valid archive can still be one the importer refuses.

### D8 — Tiles: a view mode in core, one choice per account on the device, the platform's grid

`core/…/navigation/ViewMode.kt`: `LIST | TILES`, plus `MAP` where a screen offers it (a person's Memories); kept in
`DeviceStore` under `view-mode:<accountId>` as `MapConsent` keeps its key (R12), one value for every level, as the web
keeps one (R11). It applies to the space's Home and a folder's level (`Home.Group`), an application's arranged list, and
a person's Memories (List | Tiles | Map). The levels do not offer Map: the apps' Memories map is the bar's tab
(ADR 0143) — a deviation from the web's level map, recorded.
- **The frame is core's:** `TileGrid.columns(width)` → at least two columns on a phone (a 360-unit width gives two of
  about 160), more as the window widens (a 160-unit minimum, 12-unit gaps); a tile is its node's cover **square**, the name
  below, the row's second line (a Memory's date and place, a file's size) under it — the platform's album grid
  (Photos, Google Photos), *owner question 1*. Every kind gets a tile; a folder's shows its cover or the folder symbol
  (the web's compact folder tile is not carried: a mixed grid of two tile sizes is no platform's).
- **The switch:** Android a `SingleChoiceSegmentedButtonRow` on the profile (as today) and a view toggle icon in the
  level's top bar; Apple a *View as* menu in the toolbar (Files' Icons | List) and the profile's segmented `Picker`;
  Linux a `view-grid-symbolic`/`view-list-symbolic` toggle in the header bar (Nautilus) and the profile's
  `adw::ToggleGroup`; Windows a *View* `DropDownButton` in the level's `CommandBar` (Explorer) and the profile's
  `SelectorBar`.
- **A tile's actions** are the row's: Android's overflow, Apple's context menu and swipe-free grid, the desktops' right
  click; the pick mode's dimming (ADR 0138) applies to a tile as to a row.
- Rejected: **one column of 16:9 cards on a phone** (the web's, R11) — a feed, not a grid; a phone's platform draws two
  columns. Rejected: **a choice per level** — the web keeps one, and a person's model of "how veyq shows things" is one.

### D9 — Rights and the guest

Every control follows `MemoryRights` as the core computes it (R9): `mark` (a member, read-only included), `filterFacts`,
`buckets`, the report's right, `copies`/`downloads` from the doors and the session's role. A guest has none of the
marks, the filter's marks mode or the contributions, sees the gallery as a member does otherwise, and a READ guest's
download carries no names (R5). The apps reach no link as a guest yet (#435): the guest's paths are the core's tests.

### D10 — The bridge (Linux, Windows)

`GalleryRow` gains `marks` (count) and `mine`; `GalleryShown` the filter's state (chips, text, mode, suggestions, a
`filtered` count) and the Contributions view's state; `LightboxShown` `canMark`, `marked`, `canReport`; `ShellShown` and
`PersonShown` the view mode and the tile frames. Calls: `Marks.Toggle(mediaId)`, `Filter.Text|Chip|Remove|Mode|Clear`,
`Buckets.Open|Pick|Create|Rename|Delete|Move|Close`, `Lightbox.Report`, `View.Set(mode)`, and the header's existing
`Header.Download` / copy for a Memory. No key, wrap, tag or blob ref crosses (`SecretFreeSchemaTest`); a dialog closes
with its page and its tenant (`DispatchTest`'s pattern).

### D11 — No new permission, no new dependency

The download's save dialogs and the copy's target picker are the ones a folder uses (R8); the filter's controls, the
badge, the grid and the switches are each platform's toolkit's. Phase 3's first step diffs the four manifests
(AndroidManifest, `project.yml`/Info.plist, the Flatpak manifest, the appxmanifest) and finds them unchanged.

### D12 — What the server sees: nothing new

The same as from the web: a re-key's staged seals under the commit's id; a download's reads; a copy's uploads and blob
rows (a copy's tiers uploaded as the web's copy uploads them); a member's own sealed mark set one revision later; a
bucket's sealed name. No operation, field or code is added.

## Inventory (locators — run, read as lists)

| What | Command |
|---|---|
| The web's rules to port | `ls src/web/src/features/memories/{rekey,exportImport,itemFavorites,itemFavoritesStore,galleryFilter,mediaBuckets,useUploaderNames,media}.ts` (`mediumReportKeys` is in `media.ts`) |
| The web's tests to twin | `ls src/web/src/features/memories/{rekey,exportImport,itemFavorites,itemFavoritesStore,galleryFilter,media.buckets,useUploaderNames,mediumReportKeys}.test.ts src/web/src/features/memories/components/{MemoryBuckets,MemoryDetail.filter,MediaTile}.test.ts src/web/src/core/node/ViewModeToggle.test.ts src/web/src/shared/state/useViewMode.test.ts` |
| **Core building blocks** (reused, not copied) | the re-key loop `session/NodeRekey.kt` + `PotRekey`'s shape; `export/NodeExport.kt`, `export/SubtreeCopy.kt`, `export/Zip.kt`, `blob/FileSink`/`FileSource`; `blob/ApplicationBlobs.kt`; `memories/ListedTierWrite.kt`; `memories/ItemMarksRotation.kt`'s open/seal (moved to `ItemMarks`); `search/Query.kt`'s chips; `navigation/ReportSheet` + `memories/MediumReport.kt`; `session/TrustMarks.kt`'s `DeviceStore`; Depot's `depot/Uploaders.kt` read for the guest-name shape (a different source: searched, kept separate — `git grep -n 'guestFolder' -- src/mobile/core/src/commonMain`). A view mode or a tile grid elsewhere: `git grep -n -i 'viewMode\|TileGrid' -- src/mobile/core/src` → none |
| The doors, hooks and gates to open | `git grep -n 'MEMORY, CHAPTER ->\|fun exports\|fun copies\|val rekeys' -- src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/AppKinds.kt` |
| Tests that assert a Memory is left out or due (they flip) | `git grep -n -i 'UNSUPPORTED_KIND\|Step.LEFT\|left_out\|web device' -- src/mobile/core/src/*Test src/mobile/bridge/src` |
| Each platform's level, profile, gallery and lightbox | `git ls-files src/mobile | grep -E '(ShellScreen|ShellView|home\.rs|HomeView|PersonScreen|PersonPage|person_page|MemoryScreen|MemoryPage|memory_page|lightbox\.rs|LightboxView)\.'` |
| The words to carry | `grep -n 'marks: {\|filter: {\|buckets: {\|copyLoss\|viewGrid\|viewList' src/web/src/features/memories/i18n.ts src/web/src/i18n/index.ts` |
| Site pages (phase 10) | `git grep -l -i 'tiles\|Kacheln\|marked\|markier\|contribution\|Beitr\|download\|copy' -- site/src/content/docs/memories site/src/content/docs/de/memories site/src/content/docs/spaces site/src/content/docs/de/spaces site/src/content/docs/getting-started site/src/content/docs/de/getting-started` |
| Docs naming these as the web's only | `git grep -n '#891\|#892\|#950\|web device\|No tiles\|no tiles' -- docs src/mobile/CLAUDE.md site` |

## Gates at refine

Every `/usr/bin/python3 scripts/validate_*.py` but `validate_guard_gaps.py`: exit 0 with this CR, its sibling and the
inbox edit in place (2026-11-19). Not run at refine: the mobile builds and tests, `pnpm run check` — phases 2 and 3 record
theirs before their first edit.

## Phases

Each phase ends with every platform building.

- [x] **1 the ADR and the docs it changes** — ADR 0149 (`ls docs/decisions | tail -3`; 0147 is another branch's) holding
  D1–D12 and their Alternatives; ADR 0082, 0109 and 0123 (its "not in the app yet" of the media re-key) name the apps as
  planned; ADR 0140 decision 5 (the marks and buckets kept); `uc.memory.gallery` step 7 unchanged (the apps follow it,
  #986 the web); `docs/apps/memories.md` and `native-apps.md` § Open point #891/#892/#950 here; `src/mobile/CLAUDE.md`
  names the new models. · Done when: every guard but guard_gaps exits 0.

- [x] **2 contracts** — none changes: `git diff --stat contracts/` empty at the phase's end; the generated clients the
  core needs (`memories/item-favorites`, `memories/media`) are already in `applicationSpecs` (R9). Recorded, no commit.

- [x] **3 core: the re-key (first)** — D2: `RekeyContext`'s meta, `MemoryRekey`, `AppKinds.rekeys`. Tests: the web's
  `rekey.test.ts` twinned; the **cross-surface fixture** of ADR 0143/0144 re-keyed — every plaintext byte equal after,
  every `K_blob` equal, a field the core does not know carried; `PotRekey`'s tests unchanged. Live (`VEYQ_LIVE`, a
  throwaway with a member): the owner removes the member on the web → the Memory due → the app's re-key completes it →
  an upload from the app lands and the web opens every medium, the banner and a bucket's name. · Done when: `:core:jvmTest
  :core:macosArm64Test` and the live test green; **bites** (snapshot-and-restore, each red at its own assertion): (a) the
  banner's wraps not moved → the fixture's banner read red; (b) a trashed medium skipped → the trashed case red; (c) a
  primary bucket re-sealed → the primary case red; (d) the meta sealed before the kind runs → the banner case red.

- [x] **4 core: download and copy** — D3, D7. Tests: `exportImport.test.ts`'s download and copy cases twinned; a folder's
  download with a Memory no longer leaving it out; a copy's tier count and upload count (no decode: the reader is
  asked by frame only); a guest's download without names; the fixture written and the web's new import case green
  (`pnpm run check` in `src/web/`). Live: download a Memory with a video and a banner, import it on the web (the
  throwaway's own browser session through `?devlogin` is the principal's — so the import runs as the web's test over the
  fixture, named here so no one runs it on the principal); copy a Memory into a folder, open the copy on the web. ·
  **Bites:** (a) the banner left out of the content → the web's fixture case red; (b) a copy that decodes the original →
  the frame-only reader's count red; (c) participants exported for a guest → red.

- [ ] **5 core: marks, filter, contributions, report** — D4, D5, D6, D9: `ItemMarks` (with `ItemMarksRotation` on the
  moved open/seal, its tests unchanged), `GalleryFilter`, `Buckets`, the guest's name in `MediaDetails`, the lightbox's
  report, the kept reads. Tests: R1–R3's web tests twinned; two sessions marking one medium → the count 2, each `mine`
  only for itself; a toggle before the own set is read refused; a personal scope without a count and without the mode;
  the filter's day ∧ place ∧ text ∧ mine; offline: a kept Memory's badges and a guest's name, no toggle. Live: a member and
  the owner (two throwaways) mark the same photo; a WRITE_ONLY guest's upload named in the app. · **Bites:** (a) a toggle
  written before the own set is read → the ADR 0077 case red; (b) `markedByMe` from the count instead of the self tag →
  red; (c) the filter's mode applied in a personal scope → red; (d) a guest named "a guest" again → red.

- [ ] **6 core: the view mode and tiles** — D8: `ViewMode`, its store, `TileGrid`, `PersonMemories.View` gaining TILES.
  Tests: the store per account; the columns at 359, 360 and 1200 units; the profile's three views. · **Bite:** the store
  keyed without the account → the two-account case red.

- [ ] **7 the bridge** — D10, under `SecretFreeSchemaTest`. · Done when: `:bridge:jvmTest` green; **bites:** (a) a
  mark's self tag in `GalleryRow` → `SecretFreeSchemaTest` red; (b) a Contributions dialog answering after
  `closeTenant()` → red.

- [ ] **8 Android** — the badge, the lightbox's heart and Report, the filter in the top bar's search with its chips, the
  marks' mode, Contributions in the view switch, Download and Copy on a Memory's header, the tiles and the level's toggle;
  words from `words.json` (EN+DE). · Done when: `:android:assembleDebug` and `scripts/validate_mobile_words.py` and
  `scripts/validate_mobile_platform_guidelines.py` green; clicked on the emulator as a throwaway (§ Passes).

- [ ] **9 Apple (iOS, macOS)** — as phase 8 in SwiftUI (`.searchable` with tokens, the tile's context menu marking, *View
  as* in the toolbar, `NSSavePanel`/`fileMover` for the download). · Done when: both Apple builds green; clicked on the
  Mac where its screen is free, else the owner's (#925).

- [ ] **10 Linux and Windows** — as phase 8 over the bridge (`gtk::SearchBar` / `AutoSuggestBox`, the header bar's
  toggle / the `CommandBar`'s *View*, a `gtk::GridView`/`FlowBox` / `ItemsRepeater` with a `UniformGridLayout` for the
  tiles). · Done when: `run.py build`/`test` green on both; clicked as phase 8, a window resized across the columns.

- [ ] **11 the passes** — § Passes; their outputs into ADR 0149's Consequences in this phase, what could not run named.

- [ ] **12 harvest** — the site (R16, EN+DE): `install-the-app.mdx`, `marked-photos.mdx`, `the-gallery.mdx`,
  `contributions.mdx`, `people-in-your-memories.md` (tiles), `views-and-favorites.mdx`, `download-and-import.md`
  (import stays the web's, #985), `copy-things.mdx`; ADR 0149 built; the use cases' Status; `native-apps.md` § Built and §
  Open; the **reuse sweep** (`core-reuse-sweep` over the added core code, changes/README.md § Core first), each finding
  fixed or answered; the lessons to `changes/README.md` or the lines they bite; #891, #892, #950 deleted; this CR deleted.
  · Done when: `pnpm run check` in `site/` green and every guard but guard_gaps green.

## Passes

Every pass signs in as a throwaway (`pnpm run throwaway:account --with-member --out <scratch file>` in `src/web/`, ADR
0148 decision 12), made before and erased after (`--erase`); a guest is a link the throwaway owner opens on the web.

| Pass | Who | What it proves |
|---|---|---|
| Android emulator (`veyq_pixel` or a throwaway AVD) | agent | the re-key after a member's removal, then an upload; a Memory downloaded into Downloads and copied into a folder (the copy opened on the web); marks by two sessions (the count), the filter, a guest named, a sub-bucket made, a photo reported (seen in the web's moderation inbox as the throwaway owner's tenant); tiles on Home, a folder and a profile, the choice kept across a restart; TalkBack's labels on the badge and the toggle |
| macOS build | agent | the same, where the Mac's screen is free |
| Linux Broadway | agent | the same over the bridge, a window resized across the columns |
| Windows VM | agent | the same over the bridge |
| App ↔ web | agent | a mark set in an app counted on the web and back; a sub-bucket made in an app filled on the web; the app's download imported by the web's test over the fixture (D7) |
| iPhone and iPad on a device | **owner** | the tiles at a phone's and an iPad's width, the badge's legibility, `.searchable`'s tokens, the lightbox's heart (no simulator boots, #928) |

## Owner questions

1. **Tiles on a phone: two columns of square covers, not the web's one column of wide cards.** The web's tiles fall to
   one column of 16:9 cards below 520 px (R11) — on a phone that is a feed of large pictures. The phones' own album grids
   (Photos, Google Photos) are two columns of square covers with the name under each. *Recommendation:* the platform's
   — two columns on a phone, more as the window widens, every kind a square tile (a folder its cover or the folder
   symbol); the web stays as it is.
2. **What the apps show first, and is the choice shared?** *Recommendation:* as the web — one choice per account on
   this device, remembered, for every level and a person's Memories; the first view **Tiles**, as the web's default.
   (The alternative, keeping List first, is what the apps show today.)
3. **The Contributions as a fourth view: Photos | Music | Map | Contributions.** The web has it as a tab beside the
   others; on a phone four segments fit ("Fotos | Musik | Karte | Beiträge"). *Recommendation:* yes, the fourth
   segment, shown only to a member.
4. **Words.** Everything comes from the web's EN and DE (`media.marks.*`, `media.filter.*`, `memory.buckets.*`,
   `memory.copyLoss`) except the switch's: the apps' short words (`node_view_list` "List" / "Liste", owner 2026-11-17)
   gain `node_view_tiles` "Tiles" / "Kacheln" (the web's "Grid view" / "Rasteransicht" in its long form).
   *Recommendation:* "Tiles" / "Kacheln" — #950's word, and the German site already calls a folder's grid items
   *Kacheln* (`sed -n 34,35p site/src/content/docs/de/spaces/views-and-favorites.mdx`).

## Inbox rows

#891, #892, #950 (this CR, refining); #893 (refining into [cr-app-share-into-a-memory](cr-app-share-into-a-memory.md),
after this CR); #985 (new: import in the apps, every kind); #986 (new: the web lightbox's heart, `uc.memory.gallery` step
7); #966 (the task queue's jobs, which this CR's re-key does not claim); #925, #928 (Apple's passes); #435 (a guest's view).

## Definition of done

Phases 1–12 checked with their Done-when met; the core, bridge, Linux and Windows checks green; the bites above each seen
red and green again; the fixture read by the web's import test; every live test and pass run as a throwaway, each erased;
the owner's passes named open where not yet run; the Use pages in EN and DE updated with `pnpm run check` in `site/`
green; every `scripts/validate_*.py` but guard_gaps green. `git diff --stat` over `src/backend/` and `contracts/` is empty;
over `src/web/` it names the fixture and `exportImport.test.ts` only.

## Retrospective

_(filled at the end of the implementation)_
