---
id: cr-app-folder-share-target
title: A folder as a share target in the apps — Depot's answer, *Add here* on a level, and a drop onto a level on Android and Apple
status: planned
satisfies: []
adrs: ["0138", "0079", "0122", "0136", "0145", "0148"]
invariants:
  - "each kind's answer is its application's (ADR 0079, ADR 0138 decision 2): core asks AppKinds.share, never knows Depot's rules"
  - "the apps behave exactly like the web; only the look is the platform's (owner 2026-11-19; src/mobile/CLAUDE.md § Each platform's guidelines)"
  - "nothing is stored until the target's own save (ADR 0138 decision 3); no file of a share is held whole but a Pot's bounded receipts (decision 6)"
  - "nothing on disk the app did not choose (src/mobile/CLAUDE.md): a share's copies are the ones ADR 0138 names, removed once the queue lets them go"
  - "no word in an app but from src/mobile/words/words.json"
  - "no pass signs in as the seeded principal or member: every pass is a throwaway's (ADR 0148 decision 12, `pnpm run throwaway:account` in src/web)"
---

## Goal

Inbox #987. ADR 0138 decision 2 names a folder's answer as Depot's and leaves it open: today a file shared into veyq can
go into a Pot, a Memory or a chapter, never into a folder, and only images, videos and PDFs are offered to veyq at all.
After this CR a person shares **any file** from another app into a folder or a space's top — the place where the web's
*Upload files* puts it — and a file dropped onto an open folder on Android, an iPad or a Mac is uploaded there at once,
as the web's level and the desktops' already take a drop. The Linux and Windows apps have no share target (ADR 0138's
Consequences) and already take a drop onto a level and a folder's row (R7): nothing changes there.

## Context & constraints

- **Read first:** ADR 0138 (all), `src/mobile/CLAUDE.md` § Nothing on disk, § Each platform's guidelines, § Prove your
  work; the web's folder upload `src/web/src/features/depot/levelRows.ts` (`startUploads`) and `features/depot/rights.ts`.
- **Where what runs** (from `src/mobile/`): `./gradlew :core:jvmTest :core:macosArm64Test`;
  `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :android:assembleDebug`; the Apple builds after
  `./gradlew :core:assembleVeyqCoreDebugXCFramework`. **No iOS simulator boots on this Mac (#928)**: the iPhone and iPad
  passes are the owner's; the macOS app runs here when the screen is unlocked (else its pass is named open, not faked).
- **Parallel work:** block H's [cr-app-received-links](cr-app-received-links.md) is implementing on other branches and
  edits `AppKinds.kt` (its `guestDoor`) — re-read `AppKinds.kt` and `ShareInboxTest.kt` before editing them.
- **Out of scope:** an archive shared or dropped into a folder is uploaded as a file here; recognising it as a veyq
  download and offering the import is [cr-app-archive-import](cr-app-archive-import.md) (#985), whose entries include
  this CR's. A link shared as text (#1015). A share target on the desktops (none on GNOME; Windows' needs the packaged
  app's Share Target contract).

## Measured at refine (2026-11-19)

Branch `claude/refine-open` at `6de08a8d0`, clean but for the refine's CRs. `C=src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile`,
`A=src/mobile/android/src/main/kotlin/me/raddatz/veyq/mobile/android`.

| # | What | Where / command |
|---|---|---|
| R1 | **No kind answers for a folder.** `AppKinds.share` maps the Pot and a Memory/chapter only; `ShareInboxTest` asserts `assertNull(AppKinds.share("FOLDER"), "a folder is Depot's answer, not built (#987)")`. | `sed -n 365,375p $C/AppKinds.kt`; `grep -n 'share("FOLDER")' src/mobile/core/src/commonTest/kotlin/me/raddatz/veyq/mobile/navigation/ShareInboxTest.kt` |
| R2 | **The answer's shape:** `ShareAnswer(how: EDITOR / QUEUE, intake, may(Place))`, `Place(kind, applicationsOff, online, inIdentityFolder)`; the shell asks `takesShare(inbox, ref)`; Memories' answer queues through `inbox.queue(answer) { gallery.upload(it) }`. | `cat $C/navigation/ShareAnswer.kt`; `sed -n 296,304p $C/navigation/ShellModel.kt`; `cat $C/memories/MemoryShare.kt` |
| R3 | **What the web's folder takes:** any file (the picker has `multiple`, no `accept`), only the quota bounding it; uploading allowed at a folder and a space's top (`kind === null \|\| FOLDER`) where `!readonly && applicationWritable(access, FILE_KIND) && !inIdentityFolder`; a name held by a non-file refused, a same-name file asks to replace. The level takes a drop (`acceptsDrop: true`). | `sed -n 160,170p src/web/src/features/depot/levelRows.ts`; `sed -n 61,71p src/web/src/features/depot/rights.ts`; `sed -n 208,240p src/web/src/features/depot/levelRows.ts`; `sed -n 100,110p src/web/src/features/depot/register.ts` |
| R4 | **Depot's upload in the core** is the app's one queue: `DepotUploads.upload(scopeId, parentId, label, sources)` → `DepotFiles.UploadJob`; a same-name file settles `EXISTS` and is offered a replace (`DepotFiles.offerFor`); no type or size bound but the quota. | `grep -n 'fun upload\|fun uploadFolder' $C/depot/DepotUploads.kt`; `grep -n 'file_exists\|name_taken\|quota_exceeded' $C/depot/DepotFiles.kt` |
| R5 | **Android offers images, videos and PDFs only:** both `SEND` and `SEND_MULTIPLE` filters list `image/*`, `video/*`, `application/pdf`; the drop accepts the same (`ShareDrop.kt`); no drop onto a level, only onto the shell (pick mode) and a Memory's gallery. | `sed -n 68,86p src/mobile/android/src/main/AndroidManifest.xml`; `grep -n 'shouldStartDragAndDrop' $A/ShareDrop.kt`; `git grep -n 'sharedFilesDrop' -- src/mobile/android` |
| R6 | **iOS offers the same:** `NSExtensionActivationRule` asks every attachment to conform to `public.image`, `public.movie` or `com.adobe.pdf`; `IncomingFiles.accepted = [.image, .movie, .pdf]`; the handoff's `Target` names a node (`nodeId: String`, a Memory or chapter) and no kind. | `sed -n 12,16p src/mobile/apple/VeyqShare/Info.plist`; `grep -n 'accepted' src/mobile/apple/Veyq/IncomingFiles.swift`; `sed -n 10,25p src/mobile/apple/Veyq/ShareHandoff.swift` |
| R7 | **The desktops already drop onto a level and a folder's row** (`drop.rs` `onto_level`/`onto_folder` → `Upload.Files`/`Upload.Folder`; Windows `Drops.OntoFolder`). | `grep -n 'fn onto_level\|fn onto_folder' src/mobile/linux/src/drop.rs`; `git grep -n 'OntoFolder' -- src/mobile/windows/Veyq` |
| R8 | ***Add here* exists for a Memory only:** Android's banner shows it when `model.shareTarget` (a `GalleryModel`) is set; Apple's `addHereTarget`. | `grep -n 'shareTarget\|offersAddHere' $A/AppModel.kt`; `grep -n 'addHereTarget' src/mobile/apple/Veyq/AppModel.swift` |
| R9 | **Words:** `share_pick`'s note names "a Pot, a Memory or a chapter"; `share_add_here` is "A Memory's or a chapter's own save in the pick mode". | `grep -n -A3 '"share_pick"\|"share_add_here"' src/mobile/words/words.json` |
| R10 | **The Use page** `site/src/content/docs/getting-started/share-into-veyq.mdx` (and its DE twin) names Memories, Chapters and Pots only. | `sed -n 1,15p site/src/content/docs/getting-started/share-into-veyq.mdx` |

## Decisions

No new ADR: ADR 0138 already decides that a folder is Depot's answer (decision 2); phase 1 amends its decisions 2, 6, 8
and 9 in place (*Amended 2026-11-xx*) with F1–F5 and its Built line.

### F1 — Depot answers for a folder and a space's top

`depot/FolderShare.answer` (Depot's package), returned by `AppKinds.share(FOLDER)`: **QUEUE**, takes **any file** (no
type, no bound but the upload's own and the quota — the web's picker, R3), `may = online && !inIdentityFolder &&
"depot" !in applicationsOff` (the web's `rights.ts`, R3). A space's top asks as a FOLDER (the web treats `kind === null`
and `FOLDER` alike). FOLDER stays core's kind; what it *takes* is Depot's, as on the web, where Depot contributes the
level's upload actions. Rejected: a Depot branch in `ShareInbox` — core would know an application's rule.

### F2 — A folder is entered, and its level offers *Add here*

The pick mode keeps a folder's row as navigation (it is how the person walks to a place, ADR 0138 decision 1); the open
level — a folder or a space's top — shows the banner's **Add here** wherever F1's answer may be chosen, and queues the
waiting files into `DepotUploads.upload(scopeId, parentId, label, …)`, ending the share: the Memory's pattern
(decision 3). A same-name file is offered a replace exactly as the picker's upload is (R4). A level where Depot may not
put anything keeps the banner without *Add here*. Core decides which open thing offers *Add here* (one
`ShellModel.addHere` over the open gallery or level), so neither app branches on kinds.

### F3 — Every file type is offered to veyq

- **Android:** both share filters become `*/*` (R5); `ShareIn` reads any stream. A share that carries no file (a text, a
  link) answers `share_nothing` until #1015 gives a link a meaning. Each target still takes only what its answer takes:
  a PDF shared into a Memory is refused there as today.
- **iOS:** the activation rule asks every attachment to conform to `public.data` (any file); `IncomingFiles` reads any
  file type; a URL-only share (a web page) answers `share_nothing` (the exact rule — which of Safari's offered types
  conform — is phase 4's first measurement, against a Safari share and a Files share).
- **A photo is still taken as a JPEG where the sender offers one** (decision 7), a folder included: the share is read
  before the target is chosen, and one read for every target beats holding two copies until the choice. Recorded in
  ADR 0138 decision 7 as holding for a folder too.

### F4 — iOS's handoff names its target's kind

`ShareHandoff.Target` gains `kind` (`FOLDER` / `MEMORY` / `CHAPTER`) and a nullable `nodeId` (a space's top); the app
queues a folder's handoff into Depot's queue, a Memory's into Memories', each copy removed once the queue lets it go
(decision 6, unchanged). An older handoff without `kind` is a Memory's.

### F5 — A drop onto a level, on Android and Apple

A file dropped onto an open folder or a space's top where F1 may be chosen is uploaded there at once — the web's level
(`acceptsDrop`, R3) and the desktops' (R7). On macOS a dropped **folder** uploads as a folder (`DepotUploads.uploadFolder`,
as the desktops do). Elsewhere a drop still waits in the pick mode (decision 9). The drop's accepted types follow F3.

### F6 — Words

No new word: *Add here* / *Hier hinzufügen* serves the level. `share_pick`'s and `share_add_here`'s notes name the
folder.

## Phases

- [ ] **1 docs** — ADR 0138 amended (decisions 2, 7, 8, 9 and the extension's rule; Built line: a folder built, its
  passes open); `native-apps.md` § Open's "a folder as a share target (#987)" narrowed; `uc.depot.member-files` gains an
  *In the apps: shared into a folder* alternate flow. · Done when: every guard but guard_gaps green
  (`/usr/bin/python3 scripts/validate_*.py`).
- [ ] **2 core** (F1, F2, F4's reading of a kind) — `depot/FolderShare`, `AppKinds.share(FOLDER)`, `ShellModel.addHere`
  over a level. Tests in `ShareInboxTest`: a folder takes a `.docx`, a ZIP and a photo; a folder in an identity folder,
  offline, or with Depot off is dimmed; *Add here* on a level queues into Depot's queue once and ends the share; a
  space's top takes like a folder. · Done when: `:core:jvmTest :core:macosArm64Test` green. **Bites:** (a) R1's
  assertion flipped is the first red (guard → red → fix → green, the folder's test written before `FolderShare`);
  (b) `may` ignoring `inIdentityFolder` → the dimmed test red; (c) *Add here* queuing into `MediaUploads` instead of
  Depot's → the fake door that records which queue was handed the sources red.
- [ ] **3 Android** (F2, F3, F5) — `*/*` filters, `ShareIn` for any type, the level's banner *Add here*, a drop onto a
  level (`dragAndDropTarget` on the level, any type). · Done when: `:android:assembleDebug` and the guidelines and words
  guards green; § Passes' Android row.
- [ ] **4 Apple** (F2–F5) — the activation rule (its first measurement: which types a Safari page and a Files share
  offer), `IncomingFiles` for any type, the handoff's kind, *Add here* on a level, a drop onto a level and a folder dropped
  on macOS. · Done when: both Apple builds green; the macOS row run here or named open; iOS the owner's.
- [ ] **5 the passes** — § Passes, each a throwaway, erased after; results into ADR 0138's Consequences.
- [ ] **6 harvest** — the Use pages EN+DE: `getting-started/share-into-veyq.mdx` (a folder takes any file, a drop onto a
  folder), `depot/upload-files-and-folders.mdx` (*In the app*: sharing and dropping into a folder); `native-apps.md`
  § Built; #987's row deleted; this CR deleted. · Done when: `pnpm run check` in `site/` and every guard but guard_gaps
  green.

No contracts phase and no bridge phase: no endpoint, field or wire call changes (the desktops' drops exist, R7).

## Passes

| Pass | Who | What it proves |
|---|---|---|
| Android AVD, a throwaway | agent | from the Files app a `.docx`, a ZIP and a photo shared into a folder and into the space's top by *Add here*; the web lists them at their sizes; a second share of the same name offers the replace; a folder of the identity folder dimmed; a text shared from a browser says `share_nothing`; a file dragged from a freeform window onto a folder level uploaded at once; afterwards `run-as app.veyq find . -type f` shows no share copy left |
| macOS, a throwaway | agent (screen unlocked) | a file and a folder dropped from Finder onto a folder level, listed on the web |
| iPhone and iPad | **owner** | a PDF and a `.pages` file shared from Files into a folder, the extension closed at once, the app's next open uploading them; a Safari page shared says nothing can be taken; an iPad's drop onto a folder |

## Owner questions

1. **veyq in every share sheet (F3).** For a folder to take any file, Android's filter becomes `*/*` and iOS's rule any
   file, so veyq is offered for every share — a text or a web page too, where it says that nothing could be taken
   (until #1015 opens a shared link). *Recommendation:* yes — that is what a file store's share target is, and #1015
   will give a shared link its meaning.
   **Answered (owner 2026-11-19): yes, build it so.**

## Definition of done

Phases 1–6 checked; every bite seen red and green; the passes run as throwaways and erased, the owner's named open where
not run; the Use pages in EN and DE; every guard but guard_gaps green. `git diff --stat` over `src/backend/`,
`src/web/`, `contracts/`, `src/mobile/linux/`, `src/mobile/windows/` and `src/mobile/bridge/` is empty.

## Retrospective

_(filled at the end of the implementation)_
