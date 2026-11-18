# ADR 0144 — The apps curate a Memory's participants, a person's profile, a medium's capture time and the banner: the web's rules in the core, the platform's pickers and players
- Status: Accepted
- Built: yes — on every app (Android, iOS, macOS, Linux, Windows); passed on Android, Linux and Windows, each against
  the web (§ Consequences); on Apple built and compiled for macOS and iOS but never clicked on a device — that pass is
  the owner's (inbox #925, [native-apps](../crosscutting/native-apps.md) § Open)
- Date: 2026-11-17
- Model: [native-apps](../crosscutting/native-apps.md) (the rights: [Memories](../apps/memories.md))

## Context

The web curates three more things of a Memory that the apps only show or do not show at all: **who was there**
([uc.memory.participants](../features/memory/use-cases/uc.memory.participants.md),
[ADR 0083](0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md)), a medium's **corrected capture
time** (the lightbox's *Edit capture time*,
[uc.memory.concurrent-curation](../features/memory/use-cases/uc.memory.concurrent-curation.md)) and the Memory's
**banner** ([uc.memory.banner](../features/memory/use-cases/uc.memory.banner.md),
[ADR 0102](0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md)). From a participant's name the web
opens a **person's profile**: their name and picture and the Memories with them that the member may open, as tiles,
rows or on a map. The apps read a corrected time and mark it, but cannot set one; they carry the participants and the
banner through every write without showing either, and have no person screen at all.

What the apps have to build on (read 2026-11-17, the CR's refine):

- **The web's rules, each with its tests.** The participants are `participants: {userIds}` in the node's sealed
  `wrappedMeta`, ids only, at most 100 (`memoryParticipants.ts`: normalized, merged on a `409` as (stored ∪ wanted) −
  (opened-with − wanted), so a peer's addition survives and the editor's removal wins), *I was there* ticked in the
  creation dialog and sealed at birth. The capture time is `capturedAtOverride` (epoch ms) in a medium's listed tier,
  effective = override ?? captured, one medium at a time, minute precision in the browser's wall clock; a `409` is
  re-applied unless the peer changed the override itself. The banner is `banner = {medium, blob, poster?, type?,
  sha256?, frame, focal}` in `wrappedMeta` and the slots `BANNER` / `BANNER_POSTER`, written with the meta in one
  request (`setMemoryBanner`, `removeMemoryBanner`); an equal gallery original shares its stored object
  ([ADR 0013](0013-blob-storage-presigned-direct-to-store.md) § 5). The profile's index (`participantIndex.ts`) reads the
  tenant's and group scopes' trees — never the personal one — and unseals each Memory's meta, at most 500 Memories per
  scope, held in memory.
- **The core is ready for a third field.** `NodeMetaWrite.write` re-runs only an edit on the blob a `409` returns, keeping
  every unknown field; `MemoryFacts` (date, place) and `PhotoPlace` and `TrackLabel` (the listed tier) are its callers
  ([ADR 0143](0143-the-apps-draw-a-memorys-places-and-maps-mapkit-on-apple-the-cores-raster-map-elsewhere-one-consent-per-device.md)
  decisions 5 and 7). `TenantSession.scopeMembers` names a scope's current members; `MemoryRights` already computes
  `editMeta`, `setBanner` and `participants`; `TenantSession.trees()` reads every open scope's tree through the kept,
  `ETag`-revalidated listing, offline too; `MemoriesMap.of` takes any set of Memories. The cover built a JPEG encoder and
  a video's first frame on every platform, and every platform plays a medium through the core's reader
  ([ADR 0137](0137-the-bridge-carries-shown-bytes-beside-the-json-ranges-through-a-reader-and-files-by-path.md)).
- **What is missing.** No banner client is generated; nothing in the core shares a stored object; the apps draw no tile
  view and keep no view-mode choice; the app's navigation (`Shell`) has no screen that is not an application's node; a
  photo's uploader is plain text; the kept reads hold no member list.

## Decision

1. **The participants: the core's port, shown in the header, picked from the scope's members.**
   `core/…/memories/Participants.kt` ports `memoryParticipants.ts`: normalizing, the cap of 100,
   `merge(stored, wanted, openedWith)`, and the edit of the `participants` sub-object (unchanged: no write; empty: the
   sub-object removed; its unknown fields kept). It writes through `NodeMetaWrite.write` with **the merge as its edit** —
   a `409` is never a conflict here (uc.memory.participants A2), unlike `MemoryFacts`' same-field rule.
   - **Shown** where `MemoryRights.participants` holds and the scope is not a personal one: the header's people line
     beside the facts line — one person as avatar and name, several as up to three overlapping avatars with "+N" in the
     third slot; a tap opens the list of everyone (avatar, display name; a former member greyed). The picture is
     `MemberAvatars`', else the letter. With nobody listed: an *Add participants* chip for who may edit, nothing for anyone
     else.
   - **Edited** where `participants ∧ editMeta`: the picker lists `TenantSession.scopeMembers(scopeId)` with a search
     field, ticks and unticks; only ids that list returned are accepted; an id already listed but no longer a member
     stays, greyed, and can be unticked; the 101st is refused with the web's words.
   - **A current member's name opens their profile** (decision 11); a former member's is greyed and opens nothing.
2. **_I was there_ in the creation dialog** (owner, 2026-11-17). The apps' New Memory form gains *I was there*, ticked,
   not remembered, offered only for a MEMORY in a tenant or group scope (not a personal one, not a chapter); ticked, the
   create seals `participants: {userIds: [own id]}` into the Memory's first `wrappedMeta` — no second write. The owner's
   web decision (2026-10-14) carried to the apps: a Memory made in an app and one made on the web mean the same. The date
   and the place of the same dialog stay inbox #940.
3. **A medium's capture time: the core's override, one listed-tier write for three callers.**
   `core/…/memories/CaptureTime.kt` sets and resets (removes) a medium's `capturedAtOverride` through a
   **`ListedTierWrite`** extracted from `TrackLabel` — the raw `media-meta` read-modify-write with every other field
   verbatim, a `409` re-applied on the server's tier unless the peer changed the edit's own field — which `TrackLabel`,
   `PhotoPlace` and `CaptureTime` share. A peer's different override is a conflict; the editor reopens on the peer's
   value with the web's words.
   - **The editor:** the details' capture-time row gains *Edit capture time* where `editMeta` holds and *Reset to
     original* where an override is set, drawn with the platform's own date and time pickers (Material 3 `DatePicker` +
     `TimePicker`, SwiftUI `DatePicker`, a `GtkCalendar` with two spin buttons, WinUI `CalendarDatePicker` +
     `TimePicker`), minute precision, in the device's time zone — the web's wall-clock rule with the device in the
     browser's place. **The platform converts** the picked local date and time to epoch ms with its own calendar and
     hands the core epoch ms; the core rounds to the minute and does no zone arithmetic: `kotlinx-datetime` stays a
     dependency of the desktops' bridged source set only, not of `commonMain`.
   - **Effect:** the gallery re-orders by the effective time at once; the Memory's `auto` date range follows at its next
     settle point (the Memory opening, ADR 0143 decision 5), as on the web — no new settle point.
4. **The banner is read and drawn as the web draws it.** `core/…/memories/banner/Banner.kt` ports `bannerModel.ts`: the
   shape, a reader that never throws (a bad focal: the centre; an unknown frame: fill; an unknown shape ignored and
   kept), the refs of `BANNER` and `BANNER_POSTER`, and **`BannerFrame.of(columnWidth, viewportHeight)`**: 16:9 above a
   720-unit column (dp, pt, effective px), 1:1 at or below, never taller than 60 % of the viewport. The picture is read
   from the node's own attachments as the cover's is, before and without the gallery list, and handed to the platform as
   bytes (ADR 0137).
   - **Placement:** above the Memory's header, scrolling away with it, above the view switch (Photos | Music | Map) and
     the tabs — on every view, the phone's Map view included (owner, 2026-11-17).
   - **Whole, with its surround:** the platform draws the picture contained in the frame and, behind it, **the same
     picture scaled down to at most 32 px on its long edge and stretched back with smooth filtering** — a blur by
     resampling, the web's own 32 px trick, the same on every platform and with no blur API.
   - **A video** shows its poster with a play button; a tap plays the `BANNER` blob through the platform's player over
     the core's reader, with controls; nothing starts on its own. While it plays, the surround stays the poster's.
   - **Shown nothing** where the meta does not open, a slot does not read or a picture does not decode: no frame, the
     header moves up.
5. **The banner is written through the generated client, sharing an equal file's object.** The core's generated specs
   gain `memories/memory-banner`: `setMemoryBanner` and `removeMemoryBanner` are generated, never written by hand.
   `core/…/memories/banner/BannerWrite.kt`:
   - **Pick:** *Choose an image or a video* through the platform picker already used for uploads, one file, images and
     videos. An image above 40 MB is refused before it is read; a picture the platform cannot decode, or a video whose
     first frame it cannot decode, is refused in the dialog. The dialog shows the two previews (wide 16:9, phone 1:1) with
     decision 4's drawing; **Save banner** appears once a new file is picked.
   - **The digest and the match:** the core reads the file's SHA-256 frame by frame once (the upload's path) and ports
     `storedMatch.ts`: a live, unblocked gallery medium of the same sealed original size whose original's `blob-meta`
     digest is equal is shared — an image banner its PREVIEW, a video banner its ORIGINAL with its PREVIEW as the poster —
     as a **shared row** (the stored `K_blob` wrapped again, bound to the node and the slot; the core's first, ported from
     the web's `core/blob/sharedBlob.ts` and checked against a vector the web's wrap produces).
   - **Otherwise uploaded:** an image the platform derives to the cover's JPEG (2048 px, quality 0.85); a video as it
     is, its `blob-meta` carrying the digest, with its first frame as the poster JPEG; each through the application blobs'
     reserve-and-pump in role `BANNER` / `BANNER_POSTER`, poster first; with progress and **Cancel**.
   - **The write:** one `setMemoryBanner` with the uploads or shared rows and the `wrappedMeta` re-sealed through
     `NodeMetaWrite` (the `banner` field with `sha256` the file's digest, `frame: 'fit'`, a centred focal). `409
     stale_revision` re-applies the banner on the current meta and sends the same uploads again; `409 banner_stale` writes
     nothing and the dialog says a peer changed the banner; a failure or Cancel leaves the old banner as it was (the
     stale-session purge reclaims an unattached upload). **Remove banner** is one `removeMemoryBanner`.
   - **The other way:** a gallery upload whose original equals a video banner's file (its size and the banner's sealed
     `sha256`) sends `originalShared` on the banner's object instead of the bytes; its posters are derived as always.
   - **Trashing the banner's medium** (a medium whose original's digest is the banner's) asks first — the banner stays,
     *Remove the banner too* offered; declined, nothing is deleted. A banner without a digest asks nothing.
6. **Rights and the guest.** The controls follow `MemoryRights` as the core computes them: `participants` shows,
   `participants ∧ editMeta` edits; `editMeta` corrects a time; `setBanner` sets and removes a banner. A guest has none
   of them and sees the banner as a member does (share view = member view); the apps reach no link as a guest yet
   (inbox #435), so that path is the core's tests only.
7. **Offline.**
   - **Participants are not shown offline:** the member list is no kept read, so offline every id would read as a former
     member. Kept as a sealed read it would be a list of the tenant's people at rest on the device for one line — inbox
     #948 decides it.
   - **The banner shows offline where a pin kept it:** a pin keeps each node's `BANNER` and `BANNER_POSTER` blobs as it
     keeps covers (the pin's sealed record gains the banner's ids), so a pinned Memory opens with its banner and its video
     plays ([ADR 0140](0140-the-apps-keep-content-as-ciphertext-sealed-to-the-device-by-choice-or-within-a-limit-an-unlock-opens-it-offline.md)
     decision 8: an original is kept where the app reads one to show the item).
   - **A corrected time shows offline** (it is in the kept listed tier). **Nothing is edited offline** (ADR 0140
     decision 7).
8. **The bridge (Linux, Windows).** The page's state (`GalleryShown`, where ADR 0143's facts line rides — the node's
   header, `HeaderShown`, is core's and names no application) gains the people line (ids out, display names, avatar keys,
   `former`, `more`) and the banner's state (none | `image` | `video`, the picture's cache key; the frame and its surround
   asked by size, `Banner.Frame`; the picture and the poster as `Answer.Bytes`); the calls
   `Participants.Open|Load|Toggle|Search|Save|Close`, `CaptureTime.Open(lightbox, mediaId)|Set(epochMs)|Reset|Close` (the
   host converts its picker's local time; the editor's state hands the effective time as epoch ms for the host to show in
   its zone), `Banner.Open|Pick(path)|Preview|Save|Cancel|Remove|Close` and `Banner.Picture|Play`, A2's answer, and the
   creation form's `wasThere`; a person's page `Shell.OpenPerson`, `Person.Open|View|Retry|Map|Close`. A picked banner is
   decoded by the host's upload-tiers request with the cover's spec, so no desktop decodes a banner a way of its own. No
   blob ref, wrap or digest crosses; a picked path is read by the core and never echoed. The new states are under
   `SecretFreeSchemaTest`; a dialog closes with its page and its tenant, a person's page with its tenant.
9. **No new permission, no new dependency.** Every picker the dialogs use is already in use for uploads, the players for
   the gallery's videos, the encoders for the cover; no blur API (decision 4). The four platform manifests are diffed
   unchanged before the banner is built; a changed one goes through its platform's checker before it is committed.
10. **What the server sees: nothing new.** The same as from the web: a re-sealed `wrappedMeta` one revision later, a
    re-sealed listed tier, the banner's attachment rows (and, for a shared row, which medium it shares — ADR 0102's
    accepted residual). No operation, field or code is added to the contracts or the backend. The person profile
    (decisions 11–14) is computed on the device from trees the member already reads; the server learns no more than that
    the trees were read, which it learns today.
11. **A person's profile: a screen of the core, sections of the applications.** The `Shell` gains **`person`** (a user
    id) — a screen of its own, opened over whatever was on screen with `back` chained as an object's open is, never a
    top-level destination. Core's frame draws the person: the display name (real name, else username), the picture at
    its largest (`MemberAvatars`, else the letter), `~username` under a real name, and then each application's
    **section**: the application registry gains a `personSection` an application may claim; Memories claims decisions
    12–13's, and core names no application ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md)'s
    direction). The web's own-page profile editing (name, picture) and the safety numbers are not in this frame: the apps
    have neither anywhere, and a safety number is an identity check of its own weight, not a curation feature — inbox
    #949 (owner, 2026-11-17). One's own profile opens like anyone's, with its Memories.
    - **Ways in:** a current member in the participants' list (decision 1) and a photo's uploader in its details where
      the uploader is a current member — the details' uploader gains the user id for a member. A former member and a
      guest open nothing (uc.memory.participants A1, § Uploader attribution). The apps have no people search and no
      people overview to link from.
    - **Who reaches it:** a member only; the apps open no link as a guest (inbox #435), and the core offers the frame no
      way in from a share's view.
12. **The person index is the core's, over the trees it already reads.** `core/…/memories/people/ParticipantIndex.kt`
    ports `participantIndex.ts` over `TenantSession.trees()` — no second tree read, the same `ETag` revalidation, the same
    kept listings: the tenant's and group scopes only (the personal scope is read by `trees()` for other reasons but
    never indexed), MEMORY nodes only, each Memory's meta unsealed to its participants, date and place;
    `Map<userId, Set<NodeRef>>`; 500 Memories per scope, over it the scope counts as capped and the section says the list
    is **partial** (so does a scope whose tree did not read). Built lazily on the first profile, rebuilt when `trees()` is
    read anew, dropped with its unsealed copies when the tenant closes, at a lock and at a sign-out. A Memory the member
    cannot list is never read, so it never shows.
13. **"Memories with {name}": rows and the Memories map.** Memories' section lists the person's Memories as the level's
    rows — cover, name, date range, place, the group's name — **newest start day first, undated last, then by name**, as
    the web's; a row opens its Memory (its use recorded). A switch offers **List | Map**: the map is ADR 0143's
    Memories map fed the person's located Memories, with its consent gate, its clusters and its empty text; a pin opens
    its Memory. Loading, error with *Try again*, empty and partial take the web's words. The choice is **not stored**: the
    section opens on the list (owner, 2026-11-17).
    - **Not as the web:** no **tiles** — the apps draw no tiles anywhere (inbox #950); and no stored view, because the
      web's choice is the folder view's, which the apps do not have.
    - **The order is by date, not by last use:** [ADR 0122](0122-one-app-holds-every-application-the-drawer-chooses-the-bar-belongs-to-it.md)
      decision 5 orders an application's lists, which a member returns to; a person's Memories are a record of what they
      were part of, read in time — the web's order.
14. **The profile offline.** The profile opens offline on what the device kept: `trees()` serves the kept listings, so the
    index and the rows are built offline, and the map says it needs a connection (ADR 0143 decision 9). But its ways in
    are hidden offline — the participants' line is not shown (decision 7) and an uploader's name cannot be resolved — so
    in practice it is reached only when it was open as the device went offline; then it stays, naming the person from the
    session's directory read before. Nothing about it is written to disk.

## Consequences

- **The operator's view is unchanged** (decision 10): the participants stay user ids sealed in the node's `wrappedMeta`,
  a corrected time stays in the medium's sealed listed tier, a banner's bytes are sealed under fresh blob keys wrapped
  under `K_node` and its description lives in the sealed `wrappedMeta`; nothing in the backend, the web or the contracts
  changes.
- **While a banner's video plays, its surround stays the poster's** — a deviation from the web, whose surround follows
  the frames from a 32 px canvas. A per-frame readback from four players is not worth a moving blur.
- **Participants are not shown offline** (decision 7) until inbox #948 decides whether the member list becomes a kept,
  sealed read; a corrected time and a pinned banner are.
- **The core gains its first shared row** (decision 5): a stored `K_blob` wrapped again under another node's key. It is
  the web's wrap, held to a vector the web produces, so an app-made banner that shares a medium and a web-made one are
  the same rows.
- **The profile's views differ from the web's** (decision 13): a list and a map, no tiles, no stored choice, opening on
  the list each time. Tiles are their own change on every level of the apps (inbox #950).
- **A person's list is the one list of the apps ordered by date** (decision 13), not by last use; ADR 0122 decision 5
  says so.
- **Three callers share one listed-tier loop** (decision 3): a rename, a place backfill and a capture-time correction
  conflict only on their own field, by one rule.
- **The passes, run 2026-11-17/18** against the dev instance (`veyq.localhost:8080`, the store through Vite's
  `veyq.localhost:8081`), each app and the web editing the same Memory at once. The web: headless system Chrome through the
  workspace's Playwright, signed in by `?devlogin` (German words), one session kept open for the whole run. The fixture: per
  platform a Memory made on the web with *I was there* ticked (`P9 Kuration Android`, `… Linux`, `… Windows`), holding two
  geotagged photos (`photo2.jpg`, `photo3.jpg`) and a clip; banner files `wide.jpg` (1280 × 480) and `square.jpg` (800 ×
  800). **The instrument for an equal file is the store's own request log** — Garage's, read on the server's side for every
  platform: `docker logs --since <t0> --until <t1> veyq-garage`, its `PUT /blobs/…` lines counted over each save's window
  (the method and the path only; a presigned query is never printed). All three Memories were moved to the trash after,
  and every session a pass opened was signed out (the apps from their Settings, the web from its account menu).
  - **Participants, both at once (all three platforms):** the app's picker opened on {the maker}; the web added *other*
    and saved; the app, its picker still open, ticked *link-a69657* and saved → the web, reloaded, lists all three
    (Android: the people line's dump; Linux `Participants.Save answered Ok(FactsSaved { outcome: Saved })`, `people line:
    3 listed`; Windows `Participants.Save: Saved`, `people line: 3`) — a peer's addition survives the app's merge
    (decision 1). **The reverse lost one** (Android, found here, the web's): the web's picker open, the app added
    *link-de7ab7* and saved; the web then removed *other* and saved → *link-de7ab7* was gone. The web takes `openedWith`
    from the page's node at the save (`useMemoryParticipants.onPickerSave`: `nodeMetaState(ctx.node())`), which its poll
    had moved past the peer's write, so the peer's addition counted as the editor's removal. Fixed since: the web's picker
    keeps the list it opened with (`useMemoryParticipants.openPicker`), as the apps' does.
  - **The banner, both at once:** the app's dialog holding a picked `wide.jpg`, the web saved `square.jpg` (1 store PUT),
    the app's **Save banner** said "Someone else changed the banner meanwhile. Please try again." on every platform
    (Android's dump, Linux `Banner.Save answered Ok(BannerSaved { outcome: Stale })`, Windows `Banner.Save: Stale` with
    `#banner-problem`), and the page behind showed the web's banner whole (Linux `banner framed 936 × 516 (16:9)`, Windows
    `banner framed 16:9: 1375×559 … surround 32×32`, Android 1:1 on the phone). **Found and fixed:** a second **Save**
    in the same dialog answered Stale again, forever — the dialog stood on the banner it had opened on, where the web's
    save takes the page's node, which the conflict updated. `GalleryModel.bannerEditor` now follows the peer's banner
    after a Stale (`BannerEditorTest.afterAPeersChangeIsSaidSaveAgainWritesOverThePeersBanner`, red before: `expected
    [set:bb…, set:peer…] but was [set:bb…, set:bb…]`); live on Linux and Windows after the fix: Stale, then Save → Done.
  - **A banner set in the app, shown whole on the web:** the web's `.banner-frame-media` after a reload — `wide.jpg` from
    each app `1280 × 480` drawn `object-fit: contain` in a 956 × 538 frame (16:9, the blurred surround above and below);
    the reverse, the web's `square.jpg`, drawn whole by each app as above.
  - **The equal file's upload count** (store PUTs in the save's window): `wide.jpg` (no gallery twin) **1** on Android,
    Linux and Windows; the gallery's `clip.mp4` as the banner **0** on all three (its ORIGINAL and PREVIEW shared; the web
    then draws its 640 × 360 poster); the gallery's `photo2.jpg` **0** on Linux and Windows. On Android **1** at first: the
    system photo picker hands over its own copy with the location zeroed, whose digest is not the gallery original's, and
    the banner dialog had never asked for full media access (only the upload does). **Fixed:** the dialog asks once before
    its first pick, as the upload does; on a fresh install, Allow all, then `photo2.jpg` → **0** PUTs (and 0 with the
    permissions granted by `pm grant` before the fix, the same cause).
  - **A capture time, both ways:** corrected in each app (photo3 to 20 June 2025) → the web's lightbox details read
    "Aufgenommen 20.06.2025, 11:00 · Status Korrigiert"; corrected on the web (photo2 to 24.12.2024 18:30) → Android
    "Taken Dec 24, 2024 6:30 PM · Status Corrected", Linux "12/24/2024 17:30 · Corrected" (the container's UTC), Windows
    "Taken: 24.12.2024 18:30 · Status: Corrected"; reset in each app → the web reads 02.06.2025, 10:00 with no status row.
  - **A person's profile against the web's:** Kevin's — Android 5 rows (before the Linux and Windows Memories existed),
    Linux 7 (`person's memories: … 7 rows, 1 located`) — in the web's order on `#people/kevin` row for row (Musik-Test,
    P9 Kuration Android, P9 Kuration Linux, P9 Kuration Windows, Ostsee 2026, Leftovers 926, Rom 2025: newest start day
    first, undated last by name); *other*'s on Linux and *link-a69657*'s on Windows the web's 1 and 3 rows in the same
    order. The map: with a place set on the Android Memory on the web (München), the web's profile map 1 marker and each
    app 1 located Memory (Android's map one pin at München; Linux and Windows `1 located`); *other*'s 0 and "No places yet"
    on both.
  - **Offline:** Android (airplane mode) and Linux (the container's forwards to the server killed): the Memory made
    available offline, a profile open, the server cut — the profile stayed with its rows; the pinned Memory opened from
    it with "Offline — content as of …", **its banner drawn** (Android's photo at 1:1; Linux's video poster at 16:9,
    played from the kept original, `medium at 5 s of 8 s`) and **no participants line** (decision 7); an unpinned Memory
    "Not available offline", no line either.
  - **Found and fixed besides:** Android held a person's profile section across opens, so the same person's profile
    reopened on the Map with its first read's rows (decision 13: the choice is not stored); `AppModel.openPerson` now drops
    it, as Apple's and the bridge's never held one — reopened, it starts on the list (Android has no unit-test harness
    for `AppModel`; the live repro before and after is the proof).
  - **Seen, fixed since:** an app's open Memory did not follow a peer's change of its meta — participants, date, place,
    banner — until its tree was read anew, and a phone had no refresh. Now the gallery's live poll also asks whether the
    scope's tree moved and reads the lists anew where it did (`GalleryModel.Door.treeMoved`), and the phones have a pull to
    refresh; the profile's rows follow the same read.
  - **Not run.** **Apple** (iOS, macOS): built, and compiled for macOS (ad hoc signed) and iOS, but never clicked — the
    Mac's screen is the owner's and no iOS simulator boots here. The owner's pass is inbox #925: the click list the
    Android pass ran, a window resized across 720 pt, and on an iPhone whether `PhotosPicker`'s copy of a gallery photo
    still shares (0 store PUTs) or, as Android's photo picker did, hands a copy whose digest differs. **Windows offline**:
    the VM reaches the backend through a standing ssh tunnel a pass may not stop; offline is the core's code Linux ran.
    Android's participants list re-read after the reverse write was done by a tenant switch, the phone having had no
    refresh then. **`PersonProfileLiveTest`'s group case** (a group's and the tenant's Memory listed, the personal scope's not)
    skipped at first, the dev principal being in no group; with a group made on the web it ran green on 2026-11-18
    (`VEYQ_LIVE=http://veyq.localhost:8080 ./gradlew :core:jvmTest --tests '*PersonProfileLiveTest*'` in `src/mobile/`:
    1 test, 0 skipped, 0 failures).
- **As built, where the apps depart from the decisions above or from the web** (each named by its phase's build):
  - **The core.** A banner write's check for a peer's banner compares the two slots' blob ids, not the raw `banner`
    value as the web's field edit does — a peer's banner change always moves a slot, and no client re-frames any more. A
    pin keeps the banner's blobs through a generic `PinKinds.named` / `Pin.named` that Memories fills, not a banner field
    in core's pin record, so core names no application. The person index is rebuilt per tree read, not revalidated per
    scope as the web's is: `trees()` already revalidates every scope by `ETag`, and a second cache would hold a second
    copy of what the session holds. A row names its group by the apps' group word — the apps read no group names. A
    former member's uploader carries their user id and opens nothing; only `Uploader.profile` decides.
  - **Apple.** The profile is drawn in the shell's place with core's back, not pushed on a SwiftUI `NavigationStack`
    (the app's navigation is core's `Shell`, ADR 0122; a push beside it would be a second navigation state). A profile
    opened from the lightbox's uploader returns to the Memory's gallery, not to the lightbox. Trashing the banner's
    medium asks in an alert, *Remove the Banner Too* a second button (an alert holds no toggle). The banner is picked
    with `PhotosPicker` on iOS and the file importer on a Mac. New Memory is a sheet, an alert holding no toggle for *I
    was there*.
  - **Linux.** A video banner plays from the frame itself with a play badge, and *Change banner* stands beside the
    people line, not over the picture: GNOME floats no button over a view (`validate_mobile_platform_guidelines.py`).
  - **Windows.** While a banner's video plays, the player's own black bars cover the poster's surround at the sides; the
    surround holds above and below only.
  - **Linux and Windows.** The profile's List | Map switch is icons, `node_view_list` and `memory_view_map` their
    tooltips and labels.
- **Not decided here:** the date and place of the creation dialog (inbox #940); the participants offline (#948); one's
  own profile editing and the safety numbers in the apps (#949); tiles (#950); a guest's view of a received link (#435);
  a capture-time shift over several media (not built on the web either,
  [en.memory.buckets](../features/memory/enablers/en.memory.buckets.md)); a people search and a people overview in the
  apps.

## Alternatives considered

- **`MemoryFacts`' same-field edit for the participants** — its conflict would refuse what the web merges: a peer's
  addition would make the editor redo its pick.
- **Reading the members once per tenant** — a group Memory lists its group's members, which the tenant list is not;
  `listScopeMembers` is per scope, as on the web.
- **Leaving *I was there* to #940** — #940 holds a map and a date picker in the dialog; this is one checkbox, and without
  it every Memory made in an app silently lacks its maker where the web would list them.
- **A capture-time shift over several media** — not built on the web; a feature, not a catch-up.
- **A third copy of the listed-tier loop** — three callers, one conflict rule.
- **Each platform's blur** (Compose `Modifier.blur`, SwiftUI `.blur`, GTK's blur node; WinUI has none without Win2D, a
  new dependency) — four looks for one rule, and a dependency on Windows.
- **Uploading every banner fresh** — the server's object model (ADR 0013 § 5, ADR 0102 decision 3) and the web share an
  equal file; an app storing a video twice costs the owner its size again, and the residual it would avoid (the server
  tells which medium the banner shows) holds by size anyway (ADR 0102 § What the server sees).
- **A profile as a Memories screen** — the web's frame is core's with applications' sections
  ([ADR 0110](0110-a-node-header-shows-its-actions-by-priority-as-many-as-fit.md)), and a second application with a
  person section (Depot's uploads) would otherwise need its own page.
- **The index on the server** — ADR 0083's whole point.
- **A stored index on the device** — a who-was-with-whom map at rest for a screen rebuilt in one pass over kept trees.
- **The bar's Map tab filtered to a person** — the profile would then live in Memories' bar, and back would leave the
  person.
- **Tiles and a stored view in the profile now** — a new look on every level of the apps, and a choice the apps have no
  folder view to share.

Explained for users on the docs site (§ In the app on each): [People in your Memories](../../site/src/content/docs/memories/people-in-your-memories.md),
[Add a banner](../../site/src/content/docs/memories/add-a-banner.mdx) and
[Look through the gallery](../../site/src/content/docs/memories/the-gallery.mdx) (the capture time); what a pin keeps,
[Use content offline](../../site/src/content/docs/getting-started/use-content-offline.mdx).
