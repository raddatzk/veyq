---
id: cr-app-memory-curation
title: Curating a Memory in the apps — its participants and a person's profile, a medium's corrected capture time, its banner; the core's ports of the web's rules, each platform's own pickers and players
status: planned
satisfies: []
adrs: ["0144", "0083", "0102", "0077", "0081", "0110", "0136", "0137", "0140", "0143", "0122", "0123", "0013"]
invariants:
  - "a person's profile is built on the device from trees the member can already list: the server answers no query about who was on which Memory, and the personal scope is never read for it (ADR 0083)"
  - "operator-blind E2EE: the participants stay user ids sealed in the node's wrappedMeta, a corrected capture time stays in the medium's sealed listed tier, a banner's bytes are sealed under fresh blob keys wrapped under K_node and its description of itself lives in the sealed wrappedMeta; the server learns nothing it does not learn from the web today (ADR 0083, ADR 0102 § What the server sees)"
  - "no hand-written DTOs: the banner's operations reach the core through the generator (contracts/api/memories/memory-banner.openapi.yaml added to the core's applicationSpecs), and the contracts are not changed"
  - "every rule is the core's (ADR 0136): the participants' normalising, cap and merge, the capture-time override and its conflict rule, the banner's shape, frame, equal-file match, its bounds and its write; a platform picks files, decodes and encodes pictures it is told to, plays bytes and draws"
  - "a sealed field this client does not know is carried verbatim through every write it makes (en.memory.node-meta; the cross-surface fixture)"
  - "nothing on disk the app did not choose (src/mobile/CLAUDE.md): a picked banner file is read where it lies or from the platform's own picker copy; no derived JPEG, poster or digest is left behind"
  - "the bridge carries a banner's picture as bytes beside the JSON (ADR 0137), never a key, a wrap, a blob ref or a token outside a named field (SecretFreeSchemaTest)"
  - "product words: Memory, Chapter, application; no word in an app but from src/mobile/words/words.json; each app follows its platform's guideline (ADR 0123 decision 9)"
---

## Goal

The web curates three more things of a Memory that the apps only show or do not show at all (inbox #890):
**who was there** ([uc.memory.participants](../docs/features/memory/use-cases/uc.memory.participants.md), ADR 0083), a
medium's **corrected capture time** (the lightbox's *Edit capture time*,
[uc.memory.concurrent-curation](../docs/features/memory/use-cases/uc.memory.concurrent-curation.md)), and the Memory's
**banner** ([uc.memory.banner](../docs/features/memory/use-cases/uc.memory.banner.md), ADR 0102). The apps read a
corrected time and mark it, but cannot set one; they carry the participants and the banner through every write without
showing either.

On Android, iOS, macOS, Linux and Windows the apps then: show a Memory's participants in its header (up to three
avatars, "+N", everyone in a list on a tap) and let a member who may write pick them from the scope's members, with
**I was there** ticked in the creation dialog; open a **person's profile** from a participant or a photo's uploader —
their name and picture and the Memories with them that the member may open, as rows or on the Memories map (inbox #941,
folded in by the owner 2026-11-17); let a member who may write correct a medium's capture time in its
details, and reset it; and show the banner above the Memory — an image, or a video's poster that plays on a tap, whole
in a 16:9 frame on a wide column and 1:1 on a narrow one with the picture's blurred surround — and set, change and
remove it from its own dialog, sharing an equal gallery file's stored object as the web does.

## Context & constraints

- **Read first:** ADR 0083 (all), ADR 0102 (all, its § What the server sees), ADR 0136 (the logic is the core's), ADR
  0137 (bytes beside the JSON), ADR 0140 decisions 7–9 (offline: read-only, kept originals, pins), ADR 0143 (the
  shape this CR follows: `MemoryFacts` over `NodeMetaWrite`, `PhotoPlace` over `TrackLabel`'s listed-tier write, the
  facts line), ADR 0013 § 5 (an equal file shares its stored object); [uc.memory.concurrent-curation](../docs/features/memory/use-cases/uc.memory.concurrent-curation.md);
  [Memories](../docs/apps/memories.md) (the rights matrix); `src/mobile/CLAUDE.md` whole (§ Nothing on disk, § Each
  platform's guidelines, § Traps).
- **The web is the reference implementation** (R1–R3, R17–R18); its tests are the list the core's tests mirror. Where
  this CR differs it says so and why (D4's surround while a video plays, D13's views, D11's profile frame).
- **Where what runs:** core and bridge tests on the JVM and macosArm64 (`./gradlew :core:jvmTest :bridge:jvmTest
  :core:macosArm64Test` from `src/mobile/`); Linux in its container (`/usr/bin/python3 src/mobile/linux/run.py test`),
  Windows on the VM (`/usr/bin/python3 src/mobile/windows/run.py test`); live tests with `VEYQ_LIVE` as
  `PlacesLiveTest` does.
- **Out of scope** (§ Inbox rows): the date and place in the creation dialog (#940 — only *I was there* comes in here, D2); a capture-time
  shift over several media (not built on the web either, [en.memory.buckets](../docs/features/memory/enablers/en.memory.buckets.md));
  the participants offline (#948); a guest's view of a received link (#435); one's own profile editing and the safety
numbers in the apps (#949, D11); a people search and a people overview in the apps (R17's other ways into a profile). Nothing in the backend, the web or the
  contracts changes.

## Measured at refine (2026-11-17)

Branch `claude/refine-890` cut from `claude/windows-cr-f6c73c` at `6db8f04c7`, clean. Commands from the repo root;
`W=src/web/src/features/memories`, `C=src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile`. A plain `grep -r`
over `src/mobile` walks the build directories; use `git grep`.

| # | What | Where / command |
|---|---|---|
| R1 | **The web's participants.** `participants: { userIds }` in the node's `wrappedMeta`, ids only; `MAX_PARTICIPANTS` 100 (`wrappedMeta` ≤ 8192 chars); `normalizeParticipants` (strings, non-empty, first wins, capped); `mergeParticipants(stored, wanted, openedWith)` = (stored ∪ wanted) − (openedWith − wanted), so a peer's addition survives and the editor's removal wins; written through `editParticipantsOf` (null when unchanged, an empty list deletes the sub-object, unknown sub-fields kept), never the date's same-field conflict. Shown when `memoryRights.participants` (a member, on a MEMORY, read-only members included), edited when the member may write; names from `listScopeMembers` minus `pendingConsent`, real name else username; an id not among the members is "Former member". *I was there* in `MemoryNodeDialog.vue`, ticked, not remembered, sealed at birth by `createMemory`. Tests: `memoryParticipants.test.ts` 13, `MemoryParticipants.test.ts` 14, `MemoryNodeDialog.test.ts` 16, `nodeMeta.test.ts` 22, `directory.test.ts` 14. | `grep -n 'MAX_PARTICIPANTS = \|export function' $W/memoryParticipants.ts`; `grep -n editParticipantsOf src/web/src/core/node/nodeMeta.ts`; `grep -n participants $W/rights.ts`; `grep -n 'wasThere' $W/components/MemoryNodeDialog.vue`; `grep -c 'it(\|test(' <file>` |
| R2 | **The web's capture-time correction** is one medium at a time: `capturedAtOverride` (epoch ms) beside `capturedAt` and `capturedAtSource` in the listed tier, effective = override ?? captured; the lightbox's `datetime-local` editor (minute precision, the browser's wall-clock), *Reset to original* deletes the override; a raw read-modify-write of the whole tier through `updateMediaMeta` at `metaRevision`, a `409 stale_revision` re-applied unless the stored override differs from the one the editor opened with (`MetaConflictError`: the editor reopens on the peer's value). Right: `editMeta`. A corrected time always counts for the `auto` date range; the web reconciles that range at its settle points, not on the edit. | `grep -n 'capturedAtOverride\|effectiveCapturedAt\|export async function setMediaMeta' $W/media.ts`; `grep -n 'persistCapturedAt\|canEditCapturedAt' $W/components/MediaLightbox.vue`; `grep -n 'operationId: updateMediaMeta' contracts/api/memories/media.openapi.yaml` |
| R3 | **The web's banner.** `banner = {medium, blob, poster?, type?, sha256?, frame, focal}` in `wrappedMeta` (`frame`/`focal` kept, never drawn); slots `BANNER`, `BANNER_POSTER`; `setMemoryBanner` / `removeMemoryBanner` (`contracts/api/memories/memory-banner.openapi.yaml`) write the slots and the meta in one request; an image derived to the cover's JPEG (2048, 0.85), at most 40 MB picked (`banner_too_large`), unreadable refused (`banner_unreadable`); a video as it is with its first frame as the poster; an equal gallery original (sealed size, then the `blob-meta` SHA-256) shares its object (`shared`/`posterShared`: an image banner the medium's PREVIEW, a video banner its ORIGINAL and PREVIEW); `409 banner_stale` / `stale_revision`; A2: trashing the medium whose original's digest is the banner's asks first, offering *Remove the banner too*. Right: `setBanner` (= write). Tests: `banner/*.test.ts` 57 cases over 8 files, `MemoryDetail.banner.test.ts` 5, `storedMatch.test.ts` 6. | `grep -n "operationId" contracts/api/memories/memory-banner.openapi.yaml`; `grep -n 'BANNER_ROLE\|BANNER_POSTER_ROLE' $W/banner/bannerModel.ts`; `grep -n 'BANNER_IMAGE_SPEC\|MAX_BANNER_IMAGE_BYTES' $W/banner/bannerWrite.ts`; `sed -n 1,15p $W/storedMatch.ts`; `grep -n isBannerPicture $W/components/MemoryDetail.vue` |
| R4 | **The core's node-meta write** is ready for a third field: `NodeMetaWrite.write(base, open, seal, edit, send)` re-runs only `edit` on the blob a 409 returns, at most 5 times, keeping unknown fields (`seal`); `MemoryFacts` (date, place) sends it through `updateNode` and turns a peer's change of the same field into `Outcome.Conflict`. The cover keeps its own loop (`NodeCover.write`). | `git grep -n 'fun write\|MAX_ATTEMPTS\|fun staleOf' -- $C/session/NodeMetaWrite.kt`; `git grep -n 'fun editField\|fun onServer' -- $C/memories/place/MemoryFacts.kt` |
| R5 | **The core's listed-tier write** lives in `TrackLabel` (`openRaw` in `media-meta`, `loop` returning `Conflict` only where the peer changed this edit's own field, `onServer` through `updateMediaMeta`); `PhotoPlace` (ADR 0143 D7) reuses it. | `git grep -n 'fun openRaw\|fun loop\|fun onServer' -- $C/memories/music/TrackLabel.kt`; `git grep -n 'TrackLabel' -- $C/memories/place/PhotoPlace.kt` |
| R6 | **The core reads a corrected time and writes none:** `Meta.capturedAtOverride`, `effectiveCapturedAt`, the gallery's order by it, `MediaDetails`' `CAPTURED` and `CORRECTED` rows, `MemoryDate.autoRange` trusting an override. Every hit is a read. | `git grep -n capturedAtOverride -- src/mobile/core/src/commonMain` (5 hits, all reads) |
| R7 | **The core knows the people and the rights, and draws neither control:** `TenantSession.scopeMembers(scopeId)` (current members, named by the tenant directory, `pendingConsent` dropped), `People`, `MemberAvatars`; `MemoryRights` has `editMeta` ("a medium's capture time" included), `setBanner`, `participants` (= `kind == MEMORY` for a member), whose header comment says no control is drawn yet. No Memories code names a participant. | `git grep -n 'suspend fun scopeMembers' -- $C/session/Account.kt`; `sed -n 45,62p $C/memories/MemoryRights.kt`; `git grep -n -i participant -- $C/memories src/mobile/bridge/src/commonMain` (only `MemoryRights` and Expenses' payers) |
| R8 | **What the cover already built, per platform:** `NodeCover` (`LONG_EDGE` 2048, `QUALITY` 0.85, `ApplicationBlobs.reserveAndPump` by role); a JPEG encoder on each platform (Android `Bitmap.compress`, Apple `CGImageDestination`, Linux gdk-pixbuf, Windows `BitmapEncoder`) and a video's first frame (Android `MediaMetadataRetriever`, Apple `AVAssetImageGenerator`, Linux a GStreamer appsink, Windows `MediaComposition.GetThumbnailAsync`); each platform's player for a medium read through the core's reader (ADR 0137, ADR 0142). | `git grep -n 'LONG_EDGE\|QUALITY =\|fun set(' -- $C/session/NodeCover.kt`; `git grep -n 'getScaledFrameAtTime\|AVAssetImageGenerator\|GetThumbnailAsync\|appsink' -- src/mobile` |
| R9 | **No banner client exists:** the core generates `expenses`, `depot/files` and `memories/media` only; nothing in the apps names a banner slot or operation (3 hits, all the offline banner's words). | `grep -n applicationSpecs src/mobile/core/build.gradle.kts`; `git grep -n 'BANNER\|setMemoryBanner' -- src/mobile \| wc -l` |
| R10 | **The apps' originals carry their digest; nothing shares a stored object yet:** `MediaUpload` seals `sha256` into the original's `blob-meta` (`BlobMeta.seal`), as the web does, so an equal-file match finds app-made media; no `SharedBlob` / `originalShared` code is in the core. | `git grep -n 'val sha256 = ' -- $C/memories/MediaUpload.kt`; `git grep -n 'SharedBlob\|originalShared' -- src/mobile/core/src/commonMain \| wc -l` → 0 |
| R11 | **Offline keeps no member list:** the kept reads are key material, tenants, scopes, nodes, node media, files and application blobs — `listScopeMembers` is none, so an account opened offline has an empty `people`; a pin keeps each node's cover (`setPinCovers`). | `sed -n 23,37p $C/offline/KeptRead.kt`; `git grep -n 'fun setPinCovers' -- $C/offline/AccountOffline.kt` |
| R12 | **Where each platform's header, facts line, picker and player are:** Android `MemoryPlaces.kt` (`MemoryFactsLine`), `MemoryScreen.kt` (`PickVisualMedia.ImageAndVideo`), `ReaderPlayer`; Apple `MapViews.swift` (`MemoryFactsLine`), `MemoryScreen.swift` (`.photosPicker`), `NodeHeader.swift`, `VideoPlayer`; Linux `memory_facts.rs`, `memory_page.rs` (`gtk::FileDialog`), `player.rs`; Windows `MemoryFacts.cs`, `MemoryPage.cs` (`FileOpenPicker`), `MediumPlayer.cs`. The bridge's `Header.*`, `Date.*`, `Place.*` calls and `HeaderShown`. | `git grep -n 'fun MemoryFactsLine\|func MemoryFactsLine\|struct MemoryFactsLine' -- src/mobile`; `git grep -n 'PickVisualMedia.ImageAndVideo\|photosPicker\|open_multiple_future\|new FileOpenPicker' -- src/mobile` |
| R13 | **Words:** the apps have `memory_field_captured` and `memory_corrected`, nothing for participants, editing a time or a banner; the web's EN+DE are `memory.participants.*`, `memory.banner.*` and the lightbox's `editCapturedAt`, `resetCapturedAt`, `capturedAtSaveFailed`, `capturedAtConflict`. | `grep -n '"memory_corrected"\|"memory_field_captured"' src/mobile/words/words.json`; `grep -n 'participants: {\|banner: {\|CapturedAt' $W/i18n.ts` |
| R14 | **No new permission and no blur API is needed:** every picker in use already reads images and videos (Android's photo picker asks nothing; Apple's `PhotosPicker` neither; the desktops' file dialogs, the Flatpak's through its portal); no platform draws a blur today; Android's `minSdk` is 31. | `grep -n 'uses-permission' src/mobile/android/src/main/AndroidManifest.xml`; `grep -n Capability src/mobile/windows/Veyq/Package.appxmanifest.in`; `grep -n 'finish-args' -A8 src/mobile/linux/flatpak/manifest.yml`; `git grep -n -i blur -- src/mobile/android/src src/mobile/apple/Veyq src/mobile/windows/Veyq src/mobile/linux/src` → none; `grep -n minSdk src/mobile/gradle/libs.versions.toml` |
| R15 | **Site pages that speak for this:** `getting-started/install-the-app.mdx` ("Not yet in it: a Memory's people and its banner …"), `memories/people-in-your-memories.md`, `memories/add-a-banner.mdx`, `memories/the-gallery.mdx` (*Edit capture time*) — each EN and DE. | `git grep -l -i 'participant\|banner\|capture time' -- site/src/content/docs/memories site/src/content/docs/getting-started` |
| R16 | **`kotlinx-datetime` is the native desktops' wall clock only:** a dependency of the `bridgedMain` source set, not of `commonMain`. | `grep -n 'kotlinx.datetime' src/mobile/core/build.gradle.kts` (line 280, inside `getByName("bridgedMain")`) |
| R17 | **The web's person profile.** Core's `PersonProfile.vue` at `#people/<token>` (members only; `SpaceView` mounts it, a share has no such route): the display name (real name, else username; "Former member" for an id no member holds), the avatar at its largest, `~username` under a real name, the own page's profile editing and safety number, another member's safety number, then every application's **section** (`app.personSection`; Memories' `PersonMemories.vue`). That section: "Memories with {name}", grid, rows or map (the per-user `veyq:view-mode` the folder view uses), newest start day first, undated last, then by name; a row is the level's (cover, date, place, the group's name); the map is `SpaceMap` with `locatedPin`s ("No places yet", the consent first); loading, error with retry, empty, partial. Ways in: the participants' popover, a current member's uploader name, Search's people, Settings → People. Tests: `PersonProfile.test.ts` 19, `PersonMemories.test.ts` 23. | `grep -n 'PEOPLE_SEGMENT\|function personHash' src/web/src/core/node/location.ts`; `grep -n 'personSection' src/web/src/core/applications/registry.ts $W/register.ts`; `grep -n 'withName\|empty:\|partial:' $W/i18n.ts`; `git grep -n "profileHash\|personHash" -- src/web/src ':!*.test.ts'` |
| R18 | **The web's person index** (`participantIndex.ts`): every Tenant and Group scope of `listMyScopes` (never the personal one), each tree read with its `ETag` (a 304 decrypts nothing, ADR 0081), each MEMORY's (not a chapter's) meta unsealed to its participants, date and place; `Map<userId, Set<"scope/node">>`; `MAX_MEMORIES_PER_SCOPE` 500, over it the scope is `capped` and the result `partial` (so is a failed scope; all failed rejects); held in memory, its keys zeroed by `resetParticipantIndex` on a scope switch, lock or sign-out. Tests: `participantIndex.test.ts` 7. | `grep -n 'MAX_MEMORIES_PER_SCOPE =\|MEMORY_KIND\|export function\|export async function' $W/participantIndex.ts` |
| R19 | **The apps have no person screen and no place for one:** the app's whole navigation is one serialisable `Shell` (application, open object, tab, space, path, filter, `back`, `top`); an `ObjectRef` is an application's node; nothing names a profile or person in `navigation/`. A Memory opens through `ShellModel.open(ref)` (recent use recorded, `back` chained). `PersonAvatar` exists on Android and Apple. | `sed -n 10,33p $C/navigation/Shell.kt`; `git grep -n -i 'profile\|person' -- $C/navigation` (prose only); `git ls-files src/mobile \| grep -i 'profile\|person\|people'` |
| R20 | **The core already reads what an index needs, offline too:** `TenantSession.trees()` reads every open scope's tree (the personal one included) through the kept, `ETag`-revalidated `listNodes` and memoises it until `forgetTree()`; `MemoryFacts.dates` and `MemoriesMap.of` already unseal each Memory's `wrappedMeta`; no cap anywhere. `MemoriesMap.of(tenant, memories: List<NodeRef>)` takes any set of Memories across scopes (ADR 0143 D6). | `git grep -n 'suspend fun trees\|fun forgetTree' -- $C/session/Account.kt`; `git grep -n 'fun of(' -- $C/memories/place/MemoriesMap.kt` |
| R21 | **The apps' lists are rows only:** no tiles and no view-mode setting anywhere in the core, Android or Apple; every application list is ordered by last use (`arrange`, ADR 0122 decision 5). | `git grep -n -i 'viewMode\|GRID_VIEW' -- src/mobile/core/src/commonMain src/mobile/apple/Veyq` → none; `git grep -n 'fun arrange' -- $C/navigation/Lists.kt` |
| R22 | **A photo's uploader is plain text in the apps:** `MediaDetails.Uploader(kind, name)` carries no user id; Android and Apple draw it as a detail row's text. No word names a profile ("Memories with" — none). | `sed -n 200,204p $C/memories/MediaDetails.kt`; `grep -n -i 'memories with\|Erinnerungen mit' src/mobile/words/words.json` → none |
| R23 | **The apps have no safety numbers and no own-profile editing:** no word or code names a safety number or sets one's real name or picture (`People` only reads the directory's). | `git grep -n -i 'safety.number\|safetyNumber' -- src/mobile/core/src/commonMain src/mobile/words/words.json` → none; `git grep -n -i 'setProfile\|profile_picture' -- src/mobile/core/src/commonMain src/mobile/words/words.json` → none |

## Decisions

The ADR phase (1) writes D1–D14 as **ADR 0144** ("The apps curate a Memory's participants, a person's profile, a
medium's capture time and the banner: the web's rules in the core, the platform's pickers and players"); each "Rejected" is its Alternatives.

### D1 — The participants: the core's port, shown in the header, picked from the scope's members

`core/…/memories/Participants.kt` ports `memoryParticipants.ts` (R1): `normalize`, the cap of 100, `merge(stored,
wanted, openedWith)`, and the edit of the `participants` sub-object (unchanged → no write; empty → the sub-object
removed; unknown sub-fields kept). It writes through `NodeMetaWrite.write` with the **merge as its edit** — a 409 is
never a conflict here (uc.memory.participants A2), unlike `MemoryFacts`' same-field rule.
- **Shown** where `MemoryRights.participants` holds: the header's people line (beside the facts line of ADR 0143) — one
  person as avatar and name, several as up to three overlapping avatars with "+N" in the third slot; a tap opens the
  list of everyone (avatar, display name; a former member greyed). The picture is `MemberAvatars`', else the letter.
  With nobody listed: an *Add participants* chip for who may edit, nothing for anyone else.
- **Edited** where `participants ∧ editMeta`: the picker lists `TenantSession.scopeMembers(scopeId)` (R7) with a search
  field, ticks and unticks; only ids that list returned are accepted; an id already listed but no longer a member stays,
  greyed, and can be unticked; the 101st is refused with the web's `full` word.
- **A current member's name opens their profile** (D11); a former member's is greyed and opens nothing (A1).
- Rejected: **`MemoryFacts.editField` for the participants** — its same-field conflict would refuse what the web merges.
  Rejected: **reading the members once per tenant** — a group Memory lists its group's members, which the tenant list
  is not (`listScopeMembers` is per scope, as on the web).

### D2 — *I was there* in the creation dialog (owner, 2026-11-17)

The apps' New Memory form gains **I was there**, ticked, not remembered, offered only for a MEMORY in a tenant or group
scope (not a personal one, not a chapter); ticked, `MemoryCreate` seals `participants: {userIds: [own id]}` into the
Memory's first `wrappedMeta` — no second write. This is the owner's web decision (2026-10-14) carried to the apps: a
Memory made in an app and one made on the web must mean the same. The date and place of the same dialog stay #940.
- Rejected: **leaving it to #940** — #940 holds a map and a date picker in the dialog; this is one checkbox, and without
  it every Memory made in an app silently lacks its maker where the web would list them.

### D3 — A medium's capture time: the core's override, one listed-tier write for three callers

`core/…/memories/CaptureTime.kt`: `set(item, overrideMs)` and `reset(item)` (the override removed), through a
**`ListedTierWrite`** extracted from `TrackLabel` (R5) — the raw `media-meta` read-modify-write with every other field
verbatim, a 409 re-applied on the server's tier unless the peer changed this edit's own field — which `TrackLabel`,
`PhotoPlace` and `CaptureTime` then share. Own field: `capturedAtOverride`; a peer's different override is
`Conflict`, and the editor reopens on the peer's value with the web's `capturedAtConflict` word (R2).
- **The editor:** the details' `CAPTURED` row (R6) gains *Edit capture time* where `editMeta` holds, and *Reset to
  original* where an override is set. The platform's own date and time pickers (Material 3 `DatePicker` + `TimePicker`,
  SwiftUI `DatePicker`, a `GtkCalendar` with two spin buttons, WinUI `CalendarDatePicker` + `TimePicker`), minute
  precision, in the device's time zone — the web's wall-clock rule with the device in the browser's place. **The
  platform converts** the picked local date and time to epoch ms with its own calendar (`java.time`, `Calendar`,
  `glib::DateTime`, `DateTimeOffset`) and hands the core epoch ms; the core rounds to the minute and does no zone
  arithmetic — `kotlinx-datetime` is a dependency of `bridgedMain` only (R16), not of `commonMain`, and is not added there
  for this.
- **Effect:** the gallery re-orders by the effective time at once (`MemoryMedia.order`); the Memory's `auto` date range
  follows at its next settle point (the Memory opening, ADR 0143 D5), as on the web — no new settle point.
- Rejected: **a shift over several media** — not built on the web (R2); a feature, not a catch-up. Rejected: **a third
  copy of the listed-tier loop** — three callers, one conflict rule.

### D4 — The banner is read and drawn as the web draws it

`core/…/memories/banner/Banner.kt` ports `bannerModel.ts` (R3): the shape, a reader that never throws (a bad focal →
centre, an unknown frame → fill, an unknown shape ignored and preserved), the refs of `BANNER` and `BANNER_POSTER`, and
**`BannerFrame.of(columnWidth, viewportHeight)`**: 16:9 above a 720-unit column (dp/pt/effective px), 1:1 at or below, never
taller than 60 % of the viewport. The picture is read from the node's own attachments as the cover's is (`NodeCover.open`'s
path), before and without the gallery list, and handed to the platform as bytes (ADR 0137).
- **Placement:** above the Memory's header, scrolling away with it; the view switch (Photos | Music | Map) and the tabs
  below it. *Owner question 2* (the phone's Map view).
- **Whole, with its surround:** the platform draws the picture contained in the frame and, behind it, the **same
  picture scaled down to at most 32 px on its long edge and stretched back with smooth filtering** — a blur by
  resampling, the web's own 32 px trick, on every platform the same and with no blur API (none is used today, R14).
- **A video** shows its poster with a play button; a tap plays the `BANNER` blob through the platform's player over the
  core's reader (R8, ADR 0137), with controls; nothing starts on its own. While it plays the surround **stays the
  poster's** — deviation from the web, whose surround follows the frames from a 32 px canvas; a per-frame readback from
  four players is not worth a moving blur.
- **Shown nothing** where the meta does not open, a slot does not read or a picture does not decode (E5): no frame, the
  header moves up.
- Rejected: **each platform's blur** (Compose `Modifier.blur`, SwiftUI `.blur`, GTK's blur node; WinUI has none
  without Win2D, a new dependency) — four looks for one rule, and a dependency on Windows.

### D5 — The banner is written through the generated client, sharing an equal file's object

`core/build.gradle.kts`' `applicationSpecs` gains `memories/memory-banner` (R9): `setMemoryBanner` and
`removeMemoryBanner` are generated, never written by hand. `core/…/memories/banner/BannerWrite.kt`:
- **Pick:** *Choose an image or a video* through the platform picker already used for uploads (R12), one file, images
  and videos. An image above 40 MB is refused before it is read (`banner_too_large`); a picture the platform cannot
  decode (`banner_unreadable`) or a video whose first frame it cannot decode (`banner_video_unreadable`) is refused in the
  dialog. The dialog shows the two previews (wide 16:9, phone 1:1) with D4's drawing; **Save banner** appears once a new
  file is picked.
- **The digest and the match:** the core reads the file's SHA-256 frame by frame once (the upload's path, R10) and
  ports `storedMatch.ts`: a live, unblocked gallery medium of the same sealed original size whose original's `blob-meta`
  digest is equal is shared — an image banner its PREVIEW, a video banner its ORIGINAL with its PREVIEW as the poster —
  as a `SharedBlob` row (the stored `K_blob` wrapped again bound to the node and the slot; the core's first shared row,
  ported from `core/blob/sharedBlob.ts`, conformance-checked against the web's wrap).
- **Otherwise uploaded:** an image the platform derives to the cover's JPEG (2048, 0.85, `NodeCover.encodedSize`); a
  video as it is, its `blob-meta` carrying the digest, with its first frame (R8) as the poster JPEG; each through
  `ApplicationBlobs.reserveAndPump` in role `BANNER` / `BANNER_POSTER`, poster first; progress and **Cancel**.
- **The write:** one `setMemoryBanner` with the uploads or shared rows and the `wrappedMeta` re-sealed through
  `NodeMetaWrite` (the `banner` field with `sha256` the file's digest, `frame: 'fit'`, a centred focal). `409
  stale_revision` re-applies the banner on the current meta and sends the same uploads again; `409 banner_stale` writes
  nothing and the dialog says a peer changed the banner (the web's `stale`); a failure or Cancel leaves the old banner as
  it was (the stale-session purge reclaims an unattached upload). **Remove banner** is `removeMemoryBanner` in one write.
- **A1, the other way:** a gallery upload whose original equals a video banner's file (size and the banner's sealed
  `sha256`) sends `originalShared` on the banner's object instead of the bytes; its posters are derived as always.
- **A2:** trashing a medium whose original's digest is the banner's asks first — the banner stays, *Remove the banner
  too* offered; declined, nothing is deleted. A banner without a digest asks nothing.
- Rejected: **uploading every banner fresh** — the server's object model (ADR 0013 § 5, ADR 0102 decision 3) and the
  web share an equal file; an app storing a video twice costs the owner its size again, and its residual (the server
  tells which medium the banner shows) holds by size anyway (ADR 0102 § What the server sees).

### D6 — Rights and the guest

The controls follow `MemoryRights` as the core already computes them (R7): `participants` shows, `participants ∧
editMeta` edits; `editMeta` corrects a time; `setBanner` sets and removes a banner. A guest has none of them and, with
the banner shown, sees it as a member does (share view = member view); the apps reach no link as a guest yet (#435), so
that path is the core's tests only.

### D7 — Offline

- **Participants are not shown offline**: the member list is no kept read (R11), so offline every id would read as a
  former member. Kept as a sealed read it would be a list of the tenant's people at rest on the device for one line —
  #948 decides it.
- **The banner shows offline where a pin kept it:** a pin keeps each node's `BANNER` and `BANNER_POSTER` blobs as it
  keeps covers (`setPinCovers`' record gains the banner's ids), so a pinned Memory opens with its banner and its video
  plays (ADR 0140 decision 8: an original is kept where the app reads one to show the item).
- **A corrected time shows offline** (it is in the kept listed tier). **Nothing is edited offline** (ADR 0140 decision 7).

### D8 — The bridge (Linux, Windows)

`HeaderShown` gains the people line (ids out, display names, avatar keys, `former`, `more`) and the banner's state
(`none` | `image` | `video`, the frame, `Answer.Bytes` keys for the picture and the poster); calls
`Participants.Open|Toggle|Search|Save|Close`, `CaptureTime.Open(item)|Set(epochMs)|Reset|Close` (the host converts its picker's local time, D3; `Open` hands the
effective time as epoch ms for the host to show in its zone),
`Banner.Pick(path)|Save|Cancel|Remove|Play`, the creation form's `wasThere`. No blob ref, wrap or digest crosses; the
picked path is read by the core and never echoed. `HeaderShown` and the new answers are under `SecretFreeSchemaTest`;
a dialog closes with its page and its tenant (`DispatchTest`'s pattern).

### D9 — No new permission, no new dependency

R14: every picker the dialogs use is already in use for uploads, the players for the gallery's videos, the encoders for
the cover; no blur API (D4). The packager-linter rule (changes/README.md) has nothing to lint: phase 3's first step
diffs the four manifests (AndroidManifest, `project.yml`/Info.plist, the Flatpak manifest, the appxmanifest) and finds
them unchanged — a changed one goes through its platform's checker before it is committed. D3 keeps the time zone
on the platform, so `commonMain` gains no `kotlinx-datetime` (R16).

### D10 — What the server sees: nothing new

The same as from the web: a re-sealed `wrappedMeta` one revision later, a re-sealed listed tier, the banner's attachment
rows (and, for a shared row, which medium it shares — ADR 0102's accepted residual). No operation, field or code is
added to the contracts or the backend. The person profile (D11–D14) is computed on the device from trees the member
already reads; the server learns no more than that the trees were read, which it learns today.

### D11 — A person's profile: a screen of the core, sections of the applications

The `Shell` gains **`person: String?`** (a user id) — a screen of its own, opened over whatever was on screen with `back`
chained as `open` does, never a top-level destination (R19). Core's frame draws the person: the display name
(`People`' rule), the picture at its largest (`MemberAvatars`, else the letter), `~username` under a real name, and then
each application's **section**: the application registry (`Applications.kt`, where `APPLICATION_SHAPES` live) gains a
`personSection` an application may claim; Memories claims D12–D13's, and core names no application (ADR 0079's
direction). The web's own-page profile editing (name, picture) and the safety numbers are not in this frame: the apps
have neither anywhere (R23), and a safety number is the identity check of ADR-level weight, not a curation feature —
#949 (*owner question 5*). One's own profile opens like anyone's, with its Memories.
- **Ways in:** a current member in the participants' list (D1) and a photo's uploader in its details where the uploader
  is a current member — `MediaDetails.Uploader` gains the user id for `MEMBER` (R22). A former member and a guest open
  nothing (uc.memory.participants A1, § Uploader attribution). The apps have no people search and no people overview to
  link from (R17's other two ways in; Out of scope).
- **Who reaches it:** a member only; the apps open no link as a guest (#435), and the core offers the frame no way in
  from a share's view.
- Rejected: **a profile as a Memories screen** — the web's frame is core's with applications' sections (ADR 0110), and a
  second application with a person section (Depot's uploads) would otherwise need its own page.

### D12 — The person index is the core's, over the trees it already reads

`core/…/memories/people/ParticipantIndex.kt` ports `participantIndex.ts` (R18) over `TenantSession.trees()` (R20) —
no second tree read, the same `ETag` revalidation, the same kept listings: the tenant's and group scopes only (the
personal scope is read by `trees()` for other reasons but never indexed), MEMORY nodes only, each Memory's meta
unsealed to its participants, date and place; `Map<userId, Set<NodeRef>>`; 500 Memories per scope, over it the scope
counts as capped and the section says the list is **partial** (so does a scope whose tree did not read). Built lazily on
the first profile, rebuilt when `trees()` is read anew (`forgetTree()`), dropped with its unsealed copies at
`closeTenant()`, a lock and a sign-out. A Memory the member cannot list is never read, so it never shows.
- Rejected: **the index on the server** — ADR 0083's whole point. Rejected: **a stored index on the device** — a
  who-was-with-whom map at rest for a screen rebuilt in one pass over kept trees.

### D13 — "Memories with {name}": rows and the Memories map

Memories' section lists the person's Memories as the level's rows — cover, name, date range, place, the group's name —
**newest start day first, undated last, then by name**, as the web's; a row opens its Memory (`ShellModel.open`, recent
use recorded). A switch offers **List | Map**: the map is ADR 0143's `MemoriesMap.of(tenant, refs)` fed the person's
located Memories (R20), with its consent gate, its clusters and its empty text ("No places yet"); a pin opens its
Memory. Loading, error with *Try again*, empty and partial take the web's words (R17). The choice is **not stored**: the
section opens on the list.
- **Not as the web:** no **tiles** — the apps draw no tiles anywhere (R21); and no stored view, because the web's choice
  is the folder view's (`veyq:view-mode`), which the apps do not have. *Owner question 4.*
- **The order is by date, not by last use:** ADR 0122 decision 5 orders an application's lists, which a member returns
  to; a person's Memories are a record of what they were part of, read in time — the web's order (*owner question 4*).
- Rejected: **the bar's Map tab filtered to a person** — the profile would then live in Memories' bar, and back would
  leave the person.

### D14 — The profile offline

The profile opens offline on what the device kept: `trees()` serves the kept listings (R11, R20), so the index and the
rows are built offline, the map says it needs a connection (ADR 0143 D9). But its ways in are hidden offline — the
participants' line is not shown (D7, no names kept) and an uploader's name cannot be resolved — so in practice it is
reached only when it was open as the device went offline; then it stays, naming the person from the session's
`people` read before. Nothing about it is written to disk.

## Phases

- [x] **1 the ADR and the docs it changes** — ADR 0144 (`ls docs/decisions | tail -3` for the number) holding D1–D14,
  their rejected alternatives, and the Consequences: D4's poster-only surround while a video plays, D7's offline
  participants (#948), D5's first shared row in the core, D13's views (no tiles, no stored choice). ADR 0083 and ADR 0102
  point at it; ADR 0140 decision 9 (a pin keeps the banner's blobs); ADR 0122 decision 5 names the person's list as
  ordered by date (D13); `docs/apps/memories.md` names the apps as planned for the four; `native-apps.md` § Open
  (Memories) already points here (refine); uc.memory.participants § Open a person names the apps' ways in (D11);
  `src/mobile/CLAUDE.md` names `memories/banner/`, `memories/people/`, `Participants`, `CaptureTime` and
  `ListedTierWrite`. · Done when: every `/usr/bin/python3 scripts/validate_*.py` but `validate_guard_gaps.py` exits 0.
  *Done 2026-11-17:* ADR 0144 written (decisions 1–14 = D1–D14, their rejections as its Alternatives, the four named
  Consequences); ADR 0083 and 0102 gain an *In the apps* consequence, ADR 0140's header and decision 9 name the banner's
  kept blobs, ADR 0122 decision 5 the person's list by date, ADR 0143's *Not decided here* points on; `docs/apps/memories.md`
  names the four as planned, uc.memory.participants § Open a person the apps' ways in, `src/mobile/CLAUDE.md` the planned
  files; the decisions index lists 0144. Measured: all 72 `scripts/validate_*.py` but guard_gaps exit 0
  (`for g in scripts/validate_*.py; do /usr/bin/python3 $g; done`, guard_gaps skipped).

- [x] **2 participants and capture time (core)** — D1, D2, D3: `Participants`, `MemoryCreate`'s `wasThere`,
  `ListedTierWrite` extracted from `TrackLabel` (its and `PhotoPlace`'s tests unchanged and green — the refactor's
  proof), `CaptureTime`; the header's people model; the details' editor model. Tests: R1's `memoryParticipants` and the
  merge cases ported; R2's lightbox cases (set, reset, peer's backfill rebased, peer's override conflicted); the
  **cross-surface fixture** of ADR 0143 extended — a `wrappedMeta` with participants, banner, date, place and cover as
  the web seals it, and a listed tier with `capturedAtOverride`, `placeName`, `trackName` and a field the core does not
  know — after a participants write and a time write every other field byte-equal. Live (`VEYQ_LIVE`): two sessions add
  different participants at once → both kept; one removes while the other adds → the removal and the addition both hold;
  correct a time and reset it; create a Memory with *I was there* → one revision, the maker listed. · Done when: `./gradlew
  :core:jvmTest :core:macosArm64Test` and the live tests green; **bites**, each one edit restored from a snapshot, each red
  at its own assertion: (a) the participants written through the date's same-field conflict → the concurrent-add case
  red; (b) the merge dropping `openedWith` → a removed id comes back, red; (c) an id outside `scopeMembers` accepted →
  red; (d) a time write re-sealed from the parsed tier → the unknown field gone, red; (e) a peer's different override
  overwritten on 409 → the conflict case red; (f) `wasThere` sealed into a chapter → red.
  *Done 2026-11-17 (core; the live run is the owner's, below):* built `memories/Participants.kt` (normalize, cap, merge,
  the sub-object's edit, the write through `NodeMetaWrite` with the merge as its edit — `MemoryFacts.onServer` made internal
  for it —, the header's `Line` and the `ParticipantsPicker` over `scopeMembers`), `MemoryCreate.offersWasThere`/`birthMeta`
  and `create(…, wasThere)` (sealed through `NodeCreate`'s `sealMeta`; `ShellModel.createMemory` gains an overload, not a
  default, since Swift sees no default; `TenantSession.scopeKind`), `memories/ListedTierWrite.kt` extracted from
  `TrackLabel` (whose `openRaw`/`seal`/`onServer` and `PhotoPlace.backfill` now delegate; `TrackLabelTest` and
  `PhotoPlaceTest` untouched and green), `memories/CaptureTime.kt` with `CaptureTimeEditor`, `MemoryFacts.Facts.participants`,
  and in `GalleryModel` the people line (`State.people`: a member's, on a Memory, not in a personal scope, online),
  `participantsPicker()` and `captureTimeEditor(mediaId)` (also on `Lightbox`), wired in `ShellModel`. Tests:
  `ParticipantsTest` (28), `CaptureTimeTest` (10), `MemoryCreateTest` +1, `CrossSurfaceFixtureTest` +3 (the fixture now
  carries a banner and a sub-object field; a photo tier with `capturedAtOverride`, `placeName`, `trackName` and an unknown
  field), and `jvmTest/…/CurationLiveTest` (skipped without `VEYQ_LIVE`). Bites, each one edit restored from a byte
  snapshot by a scripted run (`git status --porcelain` equal before and after), each red at its named case:
  (a) `on409APeersAddedParticipantSurvivesAndTheEditorsAdditionLands` — `expected:<Written> but was:<Conflict(…)>`;
  (b) `anIdTheEditorRemovedStaysRemovedThoughAPeersListStillHoldsIt` — `expected:<[A]> but was:<[A, B]>`, and the 409
  case `expected:<2> but was:<0>` sends; (c) `onlyAnIdTheMembersListReturnedCanBeTicked` — "an id outside the scope's
  members is refused"; (d) `aCaptureTimeWriteAndItsResetKeepEveryOtherFieldOfTheTierByteForByte` — the keys without
  `futurePhotoField`; (e) `aPeersDifferentCorrectionMeanwhileIsAConflictAndNothingIsWritten` — `expected:<Conflict(1500000000000)>
  but was:<Done(1700000040000)>`; (f) `iWasThereIsOfferedForAMemoryInATenantOrGroupScopeAndSealsTheMakerAtBirth` —
  "never into a chapter". Green: `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :core:jvmTest :bridge:jvmTest
  :core:macosArm64Test --no-daemon` (BUILD SUCCESSFUL). **Run live 2026-11-17** (the owner allowed the `.run/.env` read in the coordinating session): `VEYQ_LIVE=http://veyq.localhost:8080 VEYQ_ENV_FILE=<main>/.run/.env ./gradlew :core:jvmTest --tests '*CurationLiveTest*' --tests '*BannerLiveTest*' --tests '*PersonProfileLiveTest*'` from
  `src/mobile/` — `CurationLiveTest` 1 test, 0 skipped, 0 failures (`build/test-results/jvmTest`).

- [x] **3 the banner (core)** — D4, D5, D7's pin: the generated `MemoryBannerApi`, `Banner`, `BannerFrame`,
  `BannerWrite` with the digest, the match, `SharedBlob`, A1 in `MediaUpload`, A2 in the gallery's trash, the pin's
  banner blobs. First: the four manifests diffed unchanged (D9). Tests: R3's `bannerModel`, `bannerWrite`,
  `useBannerEditor`, `storedMatch` cases ported; the shared wrap against a vector the web's `sharedBlob` produces; the
  frame at 720 and 721 and at a short viewport (the 60 % cap). Live: set an image banner, replace it by a video equal to a
  gallery medium (no bytes uploaded — the plan's upload count 0), a gallery upload of that video shares the banner's
  object, trash the medium (asked, banner kept), remove the banner; a peer's banner set between open and save →
  `banner_stale`, the old banner intact; pin the Memory and read its banner offline. · Done when: core tests and the live
  tests green; **bites:** (a) `frame`/`focal` dropped from a re-seal → the fixture red; (b) an image banner sharing the
  ORIGINAL → the tier case red; (c) a 41 MB image read before refusing → the bound case red; (d) a `stale_revision`
  answered with fresh uploads → the upload count red; (e) A2's delete without asking → red; (f) the pin without the
  banner's ids → the offline read red.
  *Done 2026-11-17 (core; the live run is the owner's, below):* first, the four manifests diffed against the refine's base
  (`git diff 6db8f04c7 HEAD -- <AndroidManifest, project.yml, Info.plist, the Flatpak manifest, the appxmanifest>`): only
  the `app.veyq` id move, no permission or capability; this phase touches none. Built: `memories/memory-banner` in the
  generator's `applicationSpecs` (the generated `MemoryBannerApi`, `SetMemoryBannerRequest`, `RemoveMemoryBannerRequest`;
  the contracts unchanged); `blob/SharedBlob.kt` (the sealed size, the size-first match, the shared row — core's first);
  `memories/banner/Banner.kt` (the web's shape read and sealed, the slots, `Shown`, the picture and the video's reader from
  the node's own attachments, `BannerFrame.of` and the 32 px `surround`), `StoredMatch.kt`, `BannerWrite.kt` (the pick —
  an image above 40 MB refused before a byte is read, a picture or first frame that does not decode refused —, the parts,
  one start poster first, the write through `NodeMetaWrite` with the upload run once, a peer's banner `Stale`, `set` /
  `remove` over the tenant), `BannerEditor.kt` (the dialog's model: pick, previews' picture, progress, cancel, stale);
  `BlobMeta.Opened.sha256` and `MemoryMedia.Item.sha256` (the original's digest); `MemoryFacts.Facts.banner`/`bannerSlots`;
  A1 in `MediaUpload` (`sharedOriginal`: `originalShared` on the video banner's object); A2 in `GalleryModel`'s trash
  (`State.bannerPicture`, `trashBannerPicture(removeBanner)`, `cancelAsk`) with `State.banner`, `bannerPicture()`,
  `bannerReader()`, `bannerEditor(pictures)`, wired in `ShellModel`; the pin's banner through a new `PinKinds.named`
  (Memories names its banner, `Banner.pinned`; core's walk stays application-blind) recorded as `Pin.named` and read by
  `AccountOffline.reach`. Tests: `BannerTest` (12: R3's `bannerModel` cases, the frame at 720/721 and a short viewport,
  the surround, the 8192 worst case), `SharedBlobTest` (6, the vector below), `StoredMatchTest` (4, A1 included),
  `BannerWriteTest` (15: `bannerWrite` and `useBannerEditor` cases), `BannerEditorTest` (6: the dialog, A2),
  `CrossSurfaceFixtureTest` +1, `OfflinePinsTest` +1, and `jvmTest/…/BannerLiveTest` (skipped without `VEYQ_LIVE`). **The
  shared-wrap vector** is a row the web's own `shareBlob` produced over fixed inputs (a throwaway vitest file in this
  worktree, deleted; its blob id and nonces its own): the core opens the web's wrap and `blob-meta`, and its own row over
  the same inputs opens to the same key under the same binding. Bites, each one edit restored from a byte snapshot by a
  scripted run (`git status --porcelain` equal before and after), each red at its named case, green restored: (a)
  `frame`/`focal` dropped from `Banner.sealed` → `aBannerWriteSealsTheWebsShape…` expected `…"frame":"fit","focal":…` but
  was without; (b) an image sharing the ORIGINAL → `aPickedImageEqualToAGalleryOriginalShares…` expected `p-eq` but was
  `o-eq`; (c) the head read before the bound → `anImageAbove40Megabytes…` "a byte of it was read" expected 0 but was 1;
  (d) an upload per send → `aStaleRevisionReApplies…` "uploaded again on a stale revision" expected 1 but was 2; (e) A2's
  question skipped → `trashingTheBannersPictureAsksFirst…` the trash's notice where null was expected; (f) `Pin.named` left
  out of `reach` → `a_pin_keeps_its_memorys_banner…` kept only `photo-t`. Green: `ANDROID_HOME=$HOME/Library/Android/sdk
  ./gradlew :core:jvmTest :bridge:jvmTest :core:macosArm64Test :android:assembleDebug --no-daemon` (BUILD SUCCESSFUL);
  every `scripts/validate_*.py` but guard_gaps. **Departures:** a banner write's peer check compares the two slots' blob
  ids (`Banner.Slots`), not the raw `banner` value as the web's `editField` does — a peer's banner change always moves a
  slot; no client re-frames any more. A pin's banner goes through `PinKinds.named`/`Pin.named` (generic) rather than a
  banner field in core's pin record, so core names no application. The words of the dialog and of A2's question come with
  the platforms (phase 6); the core says them as `BannerEditor.Said`. **Run live 2026-11-17** with phase 2's command: `BannerLiveTest`
  1 test, 0 skipped, 0 failures.

- [x] **4 the person profile (core)** — D11–D14: `Shell.person` with its `back`, the registry's `personSection` and
  Memories' claim, `ParticipantIndex` over `trees()`, the section's model (rows, order, List | Map, the states),
  `MediaDetails.Uploader`'s user id, the participants' list opening a current member. Tests: R18's `participantIndex`
  cases and R17's `PersonMemories` order, empty, partial and map cases ported; `ShellTest`: a profile opened from a
  Memory's participants and from a photo's uploader, back returning to each, a profile → Memory → back → profile chain;
  the index's lifetime (rebuilt after `forgetTree()`, gone after `closeTenant()` and a lock). Live: a Memory in a group
  scope and one in the tenant scope, both with the same participant, and one in the member's personal scope with that
  participant written into its meta → the profile lists the first two only; offline (the session's server stopped
  mid-session, as the changes/README rule asks, and an account opened offline) the open profile keeps its rows and its
  map says it needs a connection. · Done when: core tests and the live tests green; **bites:** (a) the personal scope
  indexed → the live case lists three, red; (b) a chapter indexed → red; (c) the cap of 500 not marking partial → red;
  (d) the index kept after `closeTenant()` → red; (e) a former member's uploader name opening a profile → red; (f) the
  section ordered by last use → the order case red.
  *Done 2026-11-17 (core; the live run is the owner's, below):* built `Shell.person` with `openPerson` (a screen over what
  was on screen, `back` chained; no bar on it; `isHome`, `canGoBack`, `goBack`/`close` know it; the drawer and a space
  switch leave it), `ApplicationShape.personSection` (Memories claims it) and `personSections(off)`,
  `navigation/PersonProfile.kt` (core's frame: the directory's display name, `~username` under a real name, `former` only
  where the directory was read, the sections — names no application), `memories/people/ParticipantIndex.kt` (the web's
  walk as a pure `build` over `trees()`: TENANT and GROUP scopes only, MEMORY nodes only, 500 per scope, a capped scope, a
  tree that did not read (`TenantSession.unreadTrees`) and a closed grant of either kind (`TenantSession.closedKinds`)
  partial; each entry its node, space, date, place and map pin — no key held), held through a new generic
  `TenantSession.fromTree(key)` dropped by `forgetTree()` and a new `TenantSession.close()` (wired in the bridge's
  `closeTenant`), `memories/people/PersonMemories.kt` (the section's model: the web's order, the group on a row, loading,
  failed with *Try again*, empty, partial, List | Map once something is listed, not stored, the map's pins the person's
  located Memories, a row and a pin opening through `ShellModel.open`), `ShellModel.openPerson`/`profile`/`personMemories`,
  `MediaDetails.Uploader.userId` and `.profile` (a current member's only; the members' rule extracted as a pure
  `uploaders(keys, members, people)`), `Participants.Entry.opensProfile`. Tests: `ShellTest` +5 (from a participant, from
  an uploader, profile → Memory → back → profile, the drawer leaving it, the sections), `PersonProfileTest` (4),
  `ParticipantIndexTest` (8: R18's cases over a pure build and over `PlayedTenant` — now with TENANT and GROUP scopes and
  sealed participants — the lifetime, offline cut mid-session and opened offline, a group nothing of which was kept →
  partial), `PersonMemoriesTest` (8: R17's order, empty, partial, retry, views, map cases), and
  `jvmTest/…/PersonProfileLiveTest` (skipped without `VEYQ_LIVE`; skipped, said so, where the dev principal is in no group).
  Bites, each one edit restored from a byte snapshot by a scripted run (`git status --porcelain` equal before and after),
  each red at its named case: (a) the scope-kind filter dropped → `indexesTheMemories…neverThePersonalScope` "the personal
  scope's Memory is never indexed" expected `[a, b]` but was `[a, b, c]` (and the played-tenant cases likewise); (b) a
  CHAPTER let through → `aChapterIsNeverIndexed…` expected `[m]` but was `[ch, m]`; (c) the cap not marking partial →
  `stopsAtThePerScopeCap…` "a scope over the cap makes the list partial"; (d) `close()` dropping only the tree →
  `theIndexIsHeldWithTheTree…` "the index outlived the tenant's close"; (e) `Uploader.profile` = any user id →
  `aCurrentMembersUploaderNameOpensTheProfile…` "a former member's uploader name opens nothing" expected null but was
  `u-gone`; (f) the section ordered as `arrange` orders an unused list (by name) → `listsThePersonsMemoriesNewestStartDayFirst…`
  expected `[Harz, Amrum, Zingst, Ostsee, Alpen, beach]` but was `[Alpen, Amrum, beach, Harz, Ostsee, Zingst]`. The live
  case (a) is the same rule against the dev instance, not run here. Green: `ANDROID_HOME=$HOME/Library/Android/sdk
  ./gradlew :core:jvmTest :bridge:jvmTest :core:macosArm64Test :android:assembleDebug --no-daemon` (BUILD SUCCESSFUL);
  every `scripts/validate_*.py` but guard_gaps. **Departures:** the index is rebuilt per tree read, not revalidated per
  scope as the web's is — `trees()` already revalidates every scope by `ETag` and re-opens its kept listing, so a second
  per-scope cache would hold a second copy of what the session holds. A group's row names it by the apps' group word
  (`ShellWords.space`): the apps read no group names. "Gone after a lock" is `TenantSession.close()`; the phones call it
  where their tenant closes when phases 6–7 build the profile screens (no way in exists before them). A former uploader
  now carries its user id (opening nothing), so the profile rule is `Uploader.profile`'s alone. **Not run:**
  `PersonProfileLiveTest` with a group: run 2026-11-17 with phase 2's command it SKIPPED, said so — the dev principal is
  in no group ("the group's Memory cannot be made"); it runs once the dev principal joins a group.

- [x] **5 the bridge** — D8; `HeaderShown`'s people line and banner, the dialogs' calls, the creation form's
  `wasThere`; `Watched.Person` → `PersonShown` (name, avatar key, `~username`, the section's rows, state and `partial`),
  `Call.PersonOpen(userId)`, `Person.View(list|map)` with the map over `Call.MapOpenMemories`' path fed the person's
  Memories; schema under `SecretFreeSchemaTest`; dialogs and the profile closed with page and tenant. · Done when:
  `:bridge:jvmTest` green; **bites:** (a) a blob ref or the digest in `HeaderShown` → `SecretFreeSchemaTest` red; (b) a
  banner dialog left open after `closeTenant()` still answering `Banner.Save` → red; (c) `PersonShown` still answered
  after `closeTenant()` → red.
  *Done 2026-11-17:* built in `Wire.kt`/`Bridge.kt`: `GalleryShown` gains `people` (`PeopleShown`/`ParticipantShown`:
  ids, names, `former`, `opensProfile`, the avatars by id and `more`), `banner` (`BannerShown`: medium, the picture's cache
  key, a video's type), `editsBanner` and `bannerQuestion` (A2); the calls `Participants.Open|Load|Search|Toggle|Save|Close`
  (`Watched.Participants` → `ParticipantsShown`; search and ticks in the host's order), `CaptureTime.Open(lightbox,
  mediaId)|Set(epochMs)|Reset|Close` (`Watched.CaptureTime` → `CaptureTimeShown`), `Banner.Picture` (`Answer.Bytes`),
  `Banner.Play` (`Answer.Reader`, closed with its page), `Banner.Frame` (the frame and the 32 px surround by size, no
  tenant), `Banner.Open|Pick(path)|Preview|Save|Cancel|Remove|Close` (`Watched.Banner` → `BannerEditorShown`; a save the
  dialog's one job, `Banner.Cancel` → `BannerSaved(CANCELLED)`), `Gallery.TrashBannerPicture` (A2's answer),
  `Shell.OffersWasThere` and `Shell.CreateMemory`'s `wasThere`, `Shell.OpenPerson`, `Person.Open|View|Retry|Map|Close`
  (`Watched.Person` → `PersonShown`/`PersonMemoriesShown`/`PersonRowShown`; a row and a pin open through `Shell.Open`; the
  map closes with the page); `MediumDetails.profile` (the uploader's way in). A picked banner is decoded by
  `HostBannerPictures` over the host's existing `Media.Tiers` with the cover's spec (2048, 0.85, poster at 0 ms), asked
  suspending (`Host.askingWithPayload`); every dialog and page closes with its page (a picker, a banner dialog and the
  banner's reader with the gallery, a capture-time editor with its lightbox) and with the tenant. Tests:
  `CurationDispatchTest` (5), `MapDispatchTest` +1 (a person's map: located Memories only, a pin opening, closed with
  the page), `SecretFreeSchemaTest` +1 (`aMemorysCurationAndAPersonsPageCrossAsIdsNamesAndStatesAlone`: the new shapes'
  fields pinned, no field anywhere named like a digest), `WireTest` samples for every new variant, `DispatchTest`'s
  no-tenant list +3. Bites, each one edit restored from a byte snapshot by a scripted run (`git status --porcelain` equal
  before and after), each red at its named assertion: (a1) `sha256` on `BannerShown` →
  `aMemorysCurationAndAPersonsPageCrossAsIdsNamesAndStatesAlone` expected `[]` but was `[BannerShown.sha256]`; (a2)
  `wrappedBlobKey` on `BannerShown` → `nothingTheCoreSendsCarriesAKeyASealATokenASecretOrBytes` "named like a secret" and
  the pinned list; (b) `banners.removeAll()` dropped from `closeTenant` → `aBannerDialogAndItsSaveGoWithTheTenant`
  expected `Refused(NO_SUCH_BANNER)` but was `BannerSaved(DONE)`; (c) `persons.removeAll()` dropped →
  `aPersonsPageShowsTheirFrameAndTheirMemoriesAndGoesWithTheTenant` expected `Refused(NO_SUCH_PERSON)` but was `Done`.
  Green: `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :core:jvmTest :bridge:jvmTest :core:macosArm64Test
  :android:assembleDebug --no-daemon` (BUILD SUCCESSFUL); `linux/run.py build` and `windows/run.py build` (the generated
  bindings changed: `ShellCreateMemory.wasThere` is required in both, so `shell.rs`, `lightbox.rs` and `ShellView.cs` got
  the minimal compile fix — `was_there: false`, `..`, `wasThere = false`; the UIs are phase 8); every
  `scripts/validate_*.py` but guard_gaps. **Departures:** the people line and the banner ride `GalleryShown`, not
  `HeaderShown` as D8 said — where ADR 0143's facts line rides and the core put them (`GalleryModel.State`); the node's
  header is core's and names no application (ADR 0144 decision 8 now says so). `CaptureTime.Open` takes the lightbox
  and the medium (the core's editor is the lightbox's). No new host request: a banner's pictures reuse `Media.Tiers`, so
  no desktop decodes a banner a way of its own. Avatar keys are the user ids (`Avatar.Picture`, `ShellShown.pictures`).
  The profile's words (Memories with, former, its states) come with the platforms (phases 6–8); the wire says them as
  states and enums.

- [x] **6 Android** — the people line and its sheet, the picker, *I was there*, the details' time editor, the banner with
  its surround and player, its dialog and A2's question, the profile screen (a large `PersonAvatar`, the section's list
  and its List | Map segmented button); every word from `words.json` (EN+DE). · Done when: `./gradlew
  :android:assembleDebug` and `scripts/validate_mobile_words.py` green; clicked once on the emulator: add two
  participants and open the list, open one's profile and a Memory from it and come back, open a profile from a photo's
  uploader, switch to its map; create a Memory with *I was there*; correct a photo's time and reset it (the gallery
  re-orders); set an image banner and a video banner (play it), turn the device to see 1:1 and 16:9, trash the banner's
  medium (asked), remove the banner.
  *Built 2026-11-17 — the box stays open until the live click below is run:* the words first, in a commit of their own:
  the web's EN and DE unchanged (owner answer 3) as `words.json` keys named by the web key's segments in snake_case
  (`memory_participants_*`, `memory_people_*`, `memory_banner_*`, `media_edit_captured_at`, `media_reset_captured_at`,
  `media_captured_at_save_failed`, `media_captured_at_conflict`, `media_meta_unreadable`, `people_former_member_hint`,
  `people_unknown_member`, `people_open`, `node_view_list`) — all the CR's, for phases 7–8 too. `people.formerMember` is
  not added (`people_former_member` is Expenses' lowercase word): the profile says `memory_participants_former`;
  `memory.viewMap` is the apps' `memory_view_map` ("Map"). Android: `MemoryCuration.kt` — the people line under the facts
  line on every view (one person's avatar and name, else up to three overlapping avatars with "+N"; a tap lists everyone, a
  current member opening their profile, a former one greyed; *Add participants* where nobody is listed and the member
  edits), the picker as a bottom sheet (search, toggleable rows, a former member above, the cap's words), the banner above
  the hero on every view (`BannerFrame.of` over the column's dp and the screen's height, the picture contained over its
  `BannerFrame.surround` scaled with filtering and stretched back; a video's poster with a play button, `ReaderPlayer` over
  `Banner.reader` interrupting the music as a lightbox video does), the banner dialog as Material's full-screen dialog
  behind the header's `banner` action (the system photo picker `ImageAndVideo` read through `pickedMedium` as uploads are,
  the two previews, Save, progress with Cancel, Remove; the decoding in `MediaDerive.bannerImage`/`bannerPoster`), A2's
  question with *Remove the banner too*, and the details' capture-time editor (Material `DatePicker` + `TimePicker`,
  `java.time` in the device's zone, *Reset to original*; a medium with no time gets an *Edit capture time* button). The
  picker and the banner dialog are `AppModel`'s (a rotation keeps them, a save runs in its scope); the gallery, a tenant
  switch, a lock and a sign-out close them. `PersonScreen.kt`: the profile over the shell (`Shell.person`; a 112 dp
  `PersonAvatar`, the name, `~username`, a former member's hint), "Memories with {name}" with a `SingleChoiceSegmentedButtonRow`
  List | Map once something is listed, the rows (cover, name, date · place · group) newest first, loading, failed with *Try
  again*, empty and partial; the map is the Map tab's, extracted as `MemoriesPinMap`. Ways in: the participants' list and a
  current member's uploader name in the details. New Memory offers *I was there*, ticked, where `offersWasThere`.
  `TenantSession.close()` runs where Android's tenant closes: a switch (`show`), a lock or gone gate (`gateChanged`), a
  sign-out. The manifest, `build.gradle.kts` and the version catalog are unchanged (`git diff` empty): no permission, no
  dependency. Green: `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :core:jvmTest :bridge:jvmTest :android:assembleDebug
  :android:testDebugUnitTest --no-daemon` (BUILD SUCCESSFUL), `scripts/validate_mobile_words.py` and every
  `scripts/validate_*.py` but guard_gaps. On a throwaway emulator (`curation_p6`, API 36) the build installs and starts to
  its sign-in with no crash (`logcat -b crash` empty; the curation's state is declared above `AppModel`'s `init`, whose
  first gate closes it). **Not run: the live click** — `devlogin.py` reads the owner's `.run/.env`, which this session's
  classifier refused; the click list above is the owner's or a session allowed to sign the emulator in.
  *Done 2026-11-17:* the live click on a throwaway emulator (`curation_p6live`, API 36, Pixel 7, deleted after), signed in
  with `devlogin.py` over `VEYQ_ENV_FILE`, in Familie Raddatz (uiautomator dumps, logcat, a few screenshots): a New Memory
  with *I was there* ticked opened with the principal on its people line; four media added (GPS and names kept); two
  participants picked in the bottom sheet (the line then three avatars and "Participants"), the list opened, `other`'s
  profile ("Memories with other", List | Map), the Memory opened from it and back to the profile; a photo's "Added by"
  opened the uploader's profile (`~kevin`, newest start day first) and its Map segment (the map's consent card — map
  services left off); photo1's time corrected to Mar 1, 2026 (the gallery re-ordered it from last to second) and reset
  (back to Jul 15, 2021, last again); an image banner saved, then the gallery's clip as banner — saved at once with no
  upload progress — and played (`MediaPlayer` video/avc rendering); turned: portrait 1:1 with the blurred surround,
  landscape at a medium width (`wm density 468`, 820 dp, the rail) the wide frame capped at 60 % of the screen; the clip
  trashed from the lightbox asked A2's question (*Remove the banner too* unticked), the banner stayed and still played;
  Remove banner in the dialog (the menu back to *Add banner*). `logcat -b crash` empty. The Memory moved to the trash, the
  session signed out with *Sign out here*. Seen, not this CR's: a phone turned to landscape at its own density (914 dp)
  takes the expanded shell, where drawer, rail and the 360 dp list leave the open Memory a pane about 120 dp wide.

- [ ] **7 Apple (iOS, macOS)** — as phase 6 in SwiftUI (a popover on the Mac, a sheet on iOS, `PhotosPicker` with images
  and videos, `VideoPlayer`, the profile pushed on the navigation stack, its map MapKit's as ADR 0143's). · Done when: the
  Apple builds (iOS simulator SDK, macOS) green; clicked once on the Mac as phase 6 where the Mac's screen is free — else
  named as the owner's pass (the Mac's screen is the owner's, maps CR).
  *Built 2026-11-17 — the box stays open until the live click below is run:* `MemoryCuration.swift` — the people line under
  the facts line on every view (one person's avatar and name, else up to three overlapping avatars with "+N"; a tap lists
  everyone in a `.popover` — a popover on a Mac and an iPad, a sheet on an iPhone (`presentationCompactAdaptation`) —, a
  current member's row opening their profile, a former one greyed; *Add participants* where nobody is listed and the
  member edits), the picker as a sheet (the system's `.searchable`, checkmark rows, a former member above, the cap's words),
  the banner above the hero on every view (`BannerFrame.of` over the row's width and the page's height, the picture
  contained over its `BannerFrame.surround` drawn with `.interpolation(.high)`, no blur API; a video's poster with a play
  button, `VideoPlayer` over `Playback` on `GalleryModel.bannerReader`, the music interrupted meanwhile), the banner's sheet
  behind the header's `banner` action (`PhotosPicker` images and videos on iOS, the file importer on a Mac; both previews,
  Save, progress with Cancel, Remove; the decoding ImageIO's and `AVAssetImageGenerator`'s at `NodeCover`'s spec), A2's
  question as an alert over the lightbox (*Move to Trash*, *Remove the Banner Too*, Cancel — only Cancel cancels), and the
  details' capture-time sheet (SwiftUI's `DatePicker` with date and hour-and-minute, the device's calendar; *Reset to
  Original*; a medium with no time gets an *Edit Capture Time* button). `PersonScreen.swift`: the profile in the shell's
  place (`Shell.person`; a 112 pt `PersonAvatar`, the name, `~username`, a former member's hint), "Memories with {name}"
  with a segmented List | Map once something is listed, the rows (cover, name, date · place · group) newest first, loading,
  failed with *Try again*, empty and partial; the map is the Map tab's MapKit map, extracted as `MapViews.swift`'s
  `MemoriesPinMap`. Ways in: the participants' list and a current member's uploader name in the details (the lightbox
  closes, the profile opens over the Memory). New Memory is a sheet now (an alert holds no toggle), offering *I was there*,
  on, where `offersWasThere`. `appleMain`'s `AppleMemories` adapts what Swift cannot call: `watch(flow)` for any core state,
  `bannerPictures` (the platform's decoder as core's `BannerWrite.Pictures`, by callbacks), `bannerPick`/`bannerSave` off
  the main thread with a stoppable run (Cancel), `bannerPicture`/`pickedPicture` as `NSData`, `loadPerson` off the main
  thread. `TenantSession.close()` runs where Apple's tenant closes: a switch (`show`), any gate but Signed (a lock, gone), a
  sign-out from Manage or Settings. Apple's Title Case forms (`apple_en`) of the curation's buttons were added to
  `words.json`; no new key. `Info.plist`, `project.yml` and both entitlements are unchanged (`git diff` empty): no
  permission, no entitlement, no dependency. Green: the macOS build ad hoc (`xcodebuild -scheme Veyq -destination
  'platform=macOS' CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= CODE_SIGN_ENTITLEMENTS=`), the iOS compile
  (`-destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO`, the share extension included), `ANDROID_HOME=…
  ./gradlew :core:macosArm64Test --no-daemon`, `scripts/validate_mobile_words.py` and every `scripts/validate_*.py` but
  guard_gaps. **Departures:** the profile is drawn in the shell's place with core's back, not pushed on a SwiftUI
  `NavigationStack` (the app's navigation is core's `Shell`, ADR 0122: a push beside it would be a second navigation
  state); a profile opened from the lightbox's uploader returns to the Memory's gallery, not to the lightbox (iOS's
  full-screen cover is the page's view state); A2's checkbox is a second alert button (an alert holds no toggle). **Not
  run: the live click** — the Mac's screen is the owner's and no iOS simulator boots here: on the Mac (and an iPhone where
  one is free) add two participants and open the list, open one's profile and a Memory from it and come back, open a
  profile from a photo's uploader, switch its map; create a Memory with *I was there*; correct a photo's time and reset it
  (the gallery re-orders); set an image banner and a video banner (play it), resize the Mac's window across 720 pt for 1:1
  and 16:9, trash the banner's medium (asked), remove the banner.

- [x] **8 Linux and Windows** — as phase 6 over the bridge (a `gtk::Popover` / `Flyout` for the list, the file dialogs
  filtered to images and videos, `player.rs` / `MediumPlayer`, the profile a page of the shell with the core's raster
  map). · Done when: `run.py build`/`test` green on both; clicked once as phase 6, a window resized across 720 for the
  frame.
  *Done 2026-11-17:* Linux `banner.rs` (the frame by `Banner.Frame`, the picture contained over its surround — the
  picture drawn into the core's surround size, then stretched back by cairo's bilinear filter —, a video's poster with a
  play badge and the frame its button, played by `player.rs` over the poster; the dialog: GTK's file dialog for images
  and videos, the two previews drawn the same way, progress with Cancel, Remove banner), `curation.rs` (the people line
  with its `gtk::Popover`, *Edit participants* / *Add participants*, the picker `AdwDialog` with search and checks, the
  capture time's editor on the details' *Taken* row: a `GtkCalendar` and two spin buttons turned into epoch ms by
  `glib::DateTime`, *Reset to original*), `person_page.rs` (a page of the shell while `Shell.person` stands: the avatar
  at 128, name, `~username`, "Memories with {name}" with its states and an icon `AdwToggleGroup` List | Map, the map
  through `Person.Map` into `raster_map`), *I was there* in New Memory (`header::ask_name_with`), A2's `AdwAlertDialog`,
  the uploader's row opening the profile. Windows `Banner.cs`, `Participants.cs` (`Flyout`), `CaptureTimeEdit.cs`
  (`CalendarDatePicker` + `TimePicker`, `DateTimeOffset`), `PersonPage.cs` (`SelectorBar`), `CurationText.cs` with
  `CurationTextTest` (4); `windows/run.py` gains `VEYQ_WINDOWS_TASKS` (a pass's own scheduled tasks beside its own
  `VEYQ_WINDOWS_DIR`). No permission, capability or dependency added (the four manifests untouched). **Clicked live**
  against the dev instance (`--devlogin`), each pass's Memory trashed and its sessions signed out after:
  *Linux* (Broadway from headless system Chrome, its own home volume, removed): New Memory with *I was there* offered
  and ticked → the maker listed (`people line: 1 listed`); two participants picked through the search and saved
  (`Participants.Save … Saved`, `3 listed`), the popover listing all three; a participant's profile ("Memories with
  other", one row), its Map (the consent's offer and "No places yet"), a Memory opened from it and back to the profile and
  back; the uploader's row → the own profile (picture, `~kevin`, five Memories newest start day first); a photo's time set
  to 2021 (`CaptureTime.Set … Saved`, the gallery re-ordered 1 of 3 → 3 of 3, the details showing *Corrected*) and reset
  (back to 1 of 3); an image banner (progress 42 % → 85 % → 100 %, `banner framed 936 × 516 (16:9)`), the window resized
  to 1000 (`726 × 408`, 16:9) and 980 (`711 × 516`, 1:1 under the 60 % cap); a video banner equal to the gallery's clip
  (progress 0 → done: shared), played (`medium prepared: 10 s`, `medium at 5 s`); trashing the clip asked first, *Move to
  trash* kept the banner; *Remove banner* (`banner: none`). *Windows* (the VM, `F:\veyq-curation-p8` and the tasks
  `veyq-app-cp8`/`veyq-drive-cp8`, both removed with `F:\veyq-curation-p5`): the same walk — I was there, two
  participants and the flyout, a participant's profile → Memory → back, its map with "No places yet", the uploader's
  profile (newest first), a time set and reset with the gallery re-ordered, an image banner 16:9 and 1:1 under the cap
  at 760 px, a video banner equal to the clip saved at once and played, A2's question with the banner kept, Remove banner.
  Fixed from the passes: Linux's details re-opened on the old time (now on the lightbox's next state) and the banner
  dialog cut off *Remove banner*; Windows' video banner kept the image's frame, its profile showed "~~kevin", and New
  showed on the profile. Green: `/usr/bin/python3 src/mobile/linux/run.py build` and `test` (868 + the probes);
  `windows/run.py build` and `test` (869) with `VEYQ_WINDOWS_DIR`; every `scripts/validate_*.py` but guard_gaps.
  **Departures:** the people line and the banner ride `GalleryShown` (phase 5); a Linux banner's play is the frame
  itself with a badge (GNOME floats no button over a view, `validate_mobile_platform_guidelines`), and *Change banner*
  sits beside the people line, not over the picture; on Windows the player's own black bars cover the poster's surround
  at the sides while a video plays (D4 holds above and below only); the profile's switch is icons with the words
  `node_view_list` and `memory_view_map` as tooltips/labels; on the Linux container (UTC) a corrected time is said in UTC.

- [x] **9 the passes** — per platform against the dev instance: two sessions (an app and the web) editing the same
  Memory's participants at once, then its banner (the web's `banner_stale` word seen in the app), a time corrected in the
  app read as "Corrected" on the web and back; a banner set in the app shown whole on the web and the reverse; a person's
  profile in the app listing what the web's lists for the same member (count and order), its map's pins; offline: a
  pinned Memory's banner, no participants line, an open profile kept. Instrument: the dev backend's request log for the
  upload count of an equal-file banner — the app's own logs do not see the store (Android's `DevNetLog` does; Linux and
  Windows have none, #947), so the count is read from the server's side for every platform. · Done when: each pass's
  outputs are in ADR 0144's Consequences in this phase, with what could not be run named (Apple on a device is the
  owner's).
  *Done 2026-11-18:* Android (throwaway API 36 emulators, deleted), Linux (Broadway, its own homes, removed) and Windows
  (the VM, `F:\veyq-curation-p9` with the tasks `veyq-app-cp9`/`veyq-drive-cp9`, removed) each against the web in headless
  system Chrome on one Memory of their own: participants saved at once (a peer's addition kept), the banner's stale said,
  a banner each way shown whole, a capture time each way read as Corrected and reset, the profile's rows and located
  Memories equal to the web's, offline on Android and Linux (the banner drawn, no participants line, the open profile kept);
  the equal file's count from Garage's request log (`docker logs veyq-garage`, PUTs per save window: 1 for a new image, 0
  for the gallery's clip and photo). The commands and outputs are ADR 0144's Consequences. Fixed: the banner dialog's
  second Save after a stale (core, `BannerEditorTest` red first), Android's profile reopening on the Map with its first
  read, Android's banner pick sharing no equal photo (full media access now asked once). Filed: #952 (the web's picker
  drops a peer's addition it polled), #953 (an open Memory does not follow a peer's meta change). Not run: Apple (the
  owner's), Windows offline (the standing tunnel).

- [ ] **10 harvest** — the site (R15): `install-the-app.mdx` (EN+DE) no longer sends a Memory's people and banner to the
  browser; `people-in-your-memories.md`, `add-a-banner.mdx` and `the-gallery.mdx` (EN+DE) say what the apps do and do not
  (the profile as a list and a map, no tiles; participants not shown offline; the surround while a video plays); ADR 0144
  and the use cases' Status to built in the apps; `native-apps.md` § Built; the lessons to `changes/README.md` or the
  lines they bite; #890 deleted; this CR deleted. · Done when: `pnpm run check` in `site/` green and every guard but
  guard_gaps green.

## Owner questions

Answered by the owner on 2026-11-17:

1. **A participant's name in the apps** — *answered: build the profile now* ("Profil gleich mitbauen"). The refine had
   recommended the list without a profile until #941; the owner folded #941 into this CR instead: D1's names open
   D11's profile, D12–D14 and phase 4 are new, #941 is deleted from the inbox.
2. **The banner on a phone stands above every view, the Map view included** — *answered: yes, as recommended* (D4). On
   every view, scrolling away as on the web.
3. **Words** — *answered: the web's EN and DE unchanged, as recommended.* The participants (`memory.participants.*`),
   the banner (`memory.banner.*`), the capture time (`editCapturedAt` "Edit capture time" / "Aufnahmezeit bearbeiten",
   `resetCapturedAt`, `capturedAtSaveFailed`, `capturedAtConflict`) and, for the profile, `memory.people.*` ("Memories
   with {name}" / "Erinnerungen mit {name}", its loading, error, empty and partial texts), the profile frame's former
   and unknown member words and the view switch's labels come into `words.json` as they are.
- **D2, *I was there* in the apps' creation dialog** — *confirmed by the owner, in this CR.*

Open:

4. **The profile's Memories: a list and a map, no tiles, newest first.** The web offers tiles, rows and a map, and
   remembers the choice together with the folder view's. The apps draw no tiles anywhere and have no folder-view choice
   to share. *Recommendation:* the apps show **List | Map**, open on the list each time, and order the list as the web
   does — **newest first by the Memory's date, undated last** — not by last use like the apps' other lists, because a
   person's Memories are read as a timeline of what they were part of. Tiles would be a new look on every level of the
   apps and belong to their own change.
   **Answer (owner 2026-11-17):** List | Map as recommended; tiles go to the inbox (#950) — whether they look right on
   a phone is to be looked at there.
5. **The safety number and editing one's own profile stay out of the apps' profile for now.** The web's profile page
   also shows a member's **safety number** (to check it is really them) and, on one's own page, **Edit profile** (real
   name, picture). The apps have neither anywhere today. *Recommendation:* the apps' profile shows the person's name,
   picture and Memories now; the safety number and editing one's own profile come together in a change of their own
   (#949), since the safety number is a security check that deserves its own design and passes, not a part of curating
   Memories. Until then the web is where a member checks a safety number or changes their name and picture.
   **Answer (owner 2026-11-17):** later, as recommended — #949.

## Inbox rows

#890 (refined into this CR); #941 (a person's profile in the apps — folded into this CR by the owner 2026-11-17 and
deleted from the inbox); #940 (the date and place of the creation dialog, which keeps them — *I was there* comes in here,
D2); #948 (new: the participants offline — whether the member list becomes a kept, sealed read); #949 (new: one's own
profile editing and the safety numbers in the apps, D11, owner question 5); #947 (no request log on the desktops, phase
9's instrument); #435 (a guest's view, D6).

## Definition of done

Phases 1–10 checked with their Done-when met; the core, bridge, Linux and Windows checks green; the bites above each seen
red and green again; the passes recorded in ADR 0144; the Use pages in EN and DE updated with `pnpm run check` in
`site/` green; every `scripts/validate_*.py` but guard_gaps green. No web, backend or contract change: `git diff --stat`
over `src/web/`, `src/backend/` and `contracts/` is empty. `satisfies:` stays empty as in the music and maps CRs: the
coverage files have no platform for the apps; their proof is the core's and bridge's tests listed in `native-apps.md`.

## Retrospective

_(filled at the end of the implementation)_
