---
id: cr-app-archive-import
title: Import of a download in the apps — the web's reader, manifest checks and walk ported into the core, each kind's import its application's, recognised by every Depot upload as on the web
status: planned
satisfies: ["uc.platform.export-import"]
adrs: ["0082", "0079", "0136", "0137", "0138", "0145", "0148", "0149"]
invariants:
  - "operator-blind E2EE: an import reads, checks, re-keys and seals on the device; the server sees what a web import makes it see — new nodes, uploads and sealed fields under the importer's keys — and nothing of the archive"
  - "each kind's import is its application's (ADR 0079): core walks the tree and creates folders, and asks AppKinds for every other kind — never knows an application's content"
  - "an archive is recognised by its content, never its name, and only for a member who may create there (ADR 0082 decision 7); a guest's download is an ordinary file"
  - "both clients answer to one set of cases (contracts/export/import-cases), the search vectors' form (ADR 0146)"
  - "no dependency: the archives are store-only and the reader is the web's, ported (ADR 0082)"
  - "the apps behave exactly like the web; only the look is the platform's (owner 2026-11-19)"
  - "no word in an app but from src/mobile/words/words.json"
  - "no pass signs in as the seeded principal or member: every pass is a throwaway's (ADR 0148 decision 12, `pnpm run throwaway:account` in src/web)"
---

## Goal

Inbox #985. The apps download every kind as the web's archive (ADR 0149 decision 1; the core's `ZipWriter`), but bring
nothing back: "Import stays the web's until #985" (ADR 0149). The web has **no Import entry**: its Depot upload
recognises a veyq download — a ZIP picked or dropped, or its unpacked folder — and offers to import it, with *Upload as
a file* beside it (ADR 0082 decision 7). After this CR every app does the same at every Depot upload entry it has —
*Upload files*, *Upload folder*, a drop onto a level or a folder, a share into a folder — for every kind the web imports:
folders, Depot's files and drop boxes, Expenses' Pots, Memories' Memories and chapters.

## Context & constraints

- **Read first:** ADR 0082 (decisions 5–7, 11), [uc.platform.export-import](../docs/features/platform/use-cases/uc.platform.export-import.md),
  `contracts/export/veyq-subtree.schema.json` and the three content schemas, the web's `src/web/src/core/export/`
  (`zip.ts` from line 296, `importSource.ts`, `manifest.ts`, `importSubtree.ts`, `hooks.ts`, `ImportSubtreeDialog.vue`)
  and `LevelView.vue`'s `screenUpload`/`screenDirectory` (the comment above them is the entry's rule), ADR 0149
  decision 1, `src/mobile/CLAUDE.md` § Nothing on disk, § Each platform's guidelines, § Prove your work.
- **Where what runs** (from `src/mobile/`): `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest`;
  `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :android:assembleDebug`; the Apple builds after
  `./gradlew :core:assembleVeyqCoreDebugXCFramework`; `python3 linux/run.py test|build`; `python3 windows/run.py build`
  over ssh to the VM; `pnpm run check` in `src/web/` for phase 2; live cases as a throwaway. **No iOS simulator boots on
  this Mac (#928)**; the macOS app runs here when the screen is unlocked (else named open).
- **Order:** after [cr-app-folder-share-target](cr-app-folder-share-target.md) (#987), whose share into a folder and
  drop onto a level on Android and Apple are two of this CR's entries; if this CR runs first, those two entries move to
  that CR. Block H's [cr-app-received-links](cr-app-received-links.md) edits `AppKinds.kt` in parallel: re-read it
  before editing.
- **Out of scope:** a guest's import (never, ADR 0082 decision 7); an import that goes on with the app closed (Q1);
  the web's own import, unchanged but for phase 2's test reading the shared cases.

## Measured at refine (2026-11-19)

Branch `claude/refine-open` at `6de08a8d0`. `C=src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile`,
`W=src/web/src`, `E=$W/core/export`.

| # | What | Where / command |
|---|---|---|
| R1 | **The web's entry is the upload:** `screenUpload` asks `isDownloadArchive(file)` (bytes `PK\3\4`, the central directory, `.veyq/manifest.json` or v1's `manifest.json`) for each picked or dropped file; `screenDirectory` asks `directoryHoldsManifest`; one import per pick or drop, the rest the ordinary upload; only `access.mode === 'member' && rights.createFolder`. Entries: Depot's *Upload files* (`levelRows.ts:258`), *Upload folder* (`webkitdirectory`, `:274`), a drop onto the level (`LevelView.vue:670-698`). | `sed -n 615,640p $W/core/node/LevelView.vue`; `git grep -n 'screenUpload\|screenDirectory\|webkitdirectory' -- $W/features/depot/levelRows.ts` |
| R2 | **The web's reader is its own, no library, store-only:** `zip.ts` (532 lines, the reader from line 296, ~235 lines) finds the central directory from the end and reads an entry in 1 MiB slices; refuses multi-disk, `method !== 0`, a duplicate name, a non-UTF-8 name, an entry outside the file or into the directory, a local header disagreeing, overlaps, ZIP64 out of range, a CRC mismatch. `importSource.ts` (135) puts a ZIP and a picked folder behind one `ImportSource` (NFC paths; a folder's files carry no CRC). The web has no inflate. | `wc -l $E/{zip,importSource,manifest,importSubtree,hooks,archiveName}.ts`; `grep -n 'throw new ZipError' $E/zip.ts`; `git grep -n -E 'DecompressionStream\|pako\|fflate\|jszip' -- $W src/web/package.json` → none |
| R3 | **The manifest:** `veyq-subtree`, version 2 written, 1 and 2 read (v1 in a ZIP only); `MANIFEST_MAX_BYTES` 128 MiB, `MAX_DEPTH` 100, `MAX_NODES` 200 000, `LIMITS` per field; refusals `not_an_export`, `newer_version`, `invalid_manifest`, `too_large`, `unsafe_path`, `damaged_archive`, `payload_missing`, `size_mismatch`, `checksum`, `nothing_to_import`. `manifest.ts:35` cites a `manifest.test.ts` that pins `LIMITS` to the schema — **no such file exists**. | `sed -n 1,60p $E/manifest.ts`; `git ls-files \| grep -E 'manifest\.test\.ts'` → none |
| R4 | **The walk:** `openExport` → `planImport` (dry run: each kind's `validateContent`, skips `unknown_application` / `newer_content`, totals) → `tenantStorageFits` → `replaySubtrees` top-down (a name freed `Name (2)` up to 50 tries and on `slug_taken`; `FOLDER` by core's `createContentNode`, every other kind by `registry.kind(kind).importContent`); each payload checked (path, size, CRC-32, SHA-256 one payload ahead) before its hook; a failure trashes every top-level node made (`ImportFailed(done, total, rolledBack, leftover)`). Not queued: inline in the dialog, progress counted in nodes. | `sed -n 1,140p $E/importSubtree.ts`; `git grep -n -E '\benqueue\(' -- $E $W/features/*/exportImport.ts \| wc -l` → 0 |
| R5 | **The web's hooks per kind:** Depot file `importFile` (`fileUploadJob`), drop box `importsAsFolder`, Expenses `importPot` (`createPotNode`, entries in batches, receipts), Memories `importMemoryContent` (each medium `prepareMedia` → `addMedia`, cover, banner); a bookmark has none (ADR 0120 decision 8). | `git grep -n -E 'importContent\|importsAsFolder\|validateContent\|contentSchema' -- 'src/web/src/features/*/register.ts'` |
| R6 | **Cross-client today:** one committed archive, `src/web/src/features/memories/fixtures/app-memory-download.zip` — written by the core (`MemoryDownloadFixtureTest`), imported by the web; no archive the web writes is read by the core; no shared refusal cases (the web crafts them in `importHardening.test.ts`, 44 cases). | `git ls-files \| grep -E '\.zip$'`; `git grep -n app-memory-download`; `grep -c "it(" $E/importHardening.test.ts` |
| R7 | **The core writes, never reads:** `export/Zip.kt` (`ZipWriter`, `Crc32`), `NodeExport.kt` (`FORMAT`, `MANIFEST_PATH`, `VERSION` 2), `SubtreeCopy.kt`, `ArchiveName.kt`, `Sha256.kt` (incremental `update`/`digest`); no reader, no manifest parser. | `ls $C/export/`; `git grep -n -i -E 'openZip\|ZipReader\|parseManifest' -- src/mobile` → none; `grep -n 'fun ' $C/export/Sha256.kt` |
| R8 | **The core's per-kind hooks:** `AppKinds.exports(…)` and `copies(…)` over Pot, file, drop box, Memory, chapter (the copy's `asFolder` for a drop box); no import hook. What an import can stand on: `DepotFiles.upload(…, read)` (any `(from, length)` reader), `MediaUpload.Deriver.derive(source, type, done)` (each platform's decode and tiers), `PotCopy`, `StorageFit.fits`, `UploadSource.read(from, length)`. | `grep -n 'fun exports\|fun copies' $C/AppKinds.kt`; `grep -n 'interface Deriver' -A3 $C/memories/MediaUpload.kt`; `grep -n 'fun fits' $C/session/StorageFit.kt` |
| R9 | **Every app already has the four entries' pickers and the desktops' drops:** *Upload files* (Android `OpenMultipleDocuments`, Apple `.fileImporter([.item])`, Linux `open_multiple_future`, Windows `FileOpenPicker`); *Upload folder* (`OpenDocumentTree`, `.fileImporter([.folder])`, `select_folder_future`, `FolderPicker`, walked into `FolderUpload.Found`); drops on Linux (`drop.rs`) and Windows (`Drops.cs`/`DropSort.cs`) into `Upload.Files`/`Upload.Folder`. | `git grep -n -E 'OpenMultipleDocuments\|OpenDocumentTree' -- src/mobile/android`; `git grep -n -E 'select_folder\|open_multiple' -- src/mobile/linux/src/uploads.rs`; `git grep -n -E 'FolderPicker\|FileOpenPicker' -- src/mobile/windows/Veyq/Uploads.cs` |
| R10 | **The words:** the web's `export.*` keys (importTitle, reading, invalid, `refused.*`, `skipReason.*`, importSummary, importSkipped, importSubmit, importRest, uploadAsFile, importing, imported, importFailed, importFailedLeft, importQuota, importNoRoom, importChecksum, …); the apps have none. | `grep -n -E '^\s+(import[A-Za-z]*\|uploadAsFile\|refused\|skipReason):' $W/i18n/index.ts`; `grep -c '"import_' src/mobile/words/words.json` |
| R11 | **The Use page says the apps do not import:** `spaces/download-and-import.md` § In the app ("Importing is not in the app yet"); `depot/upload-files-and-folders.mdx` names the web's offer. | `grep -n -i 'import' site/src/content/docs/spaces/download-and-import.md site/src/content/docs/depot/upload-files-and-folders.mdx` |

## Decisions

Written into **ADR 0152** in phase 1 (the next after [cr-app-account-rest](cr-app-account-rest.md)'s 0151; re-run
`git log --all --name-only --format= | grep -o 'docs/decisions/01[4-9][0-9]-' | sort -u` in phase 1 and take the next
free number). Its title: *The apps import a download as the web does: the web's reader and walk in the core, each kind's
import its application's, recognised by every Depot upload.* ADR 0149's "Import stays the web's until #985" and the use
case's "No import in the apps" are pointed at it.

### I1 — No Import entry: every Depot upload recognises a download

The core's `DepotUploads` screens what it is handed before it queues, as `screenUpload`/`screenDirectory` (R1): the
first picked, dropped or shared **file** that is a download (by content) becomes an import offer with *Upload as a file*;
a picked or dropped **folder** holding `.veyq/manifest.json` becomes an import offer (no *Upload as a file*); every
other file or folder is the ordinary upload. Only a member who may create a folder there (the level's create right),
never a guest. Entries: *Upload files*, *Upload folder*, a drop onto a level or a folder's row, a share into a folder
(cr-app-folder-share-target; on iOS the app screens a handed-over file when it queues it). The screen reads the end of
the file and the central directory only — never the whole file.

### I2 — The web's reader and walk, ported into the core

`export/` gains, each a port of its web namesake held to the same cases (I4): **`ZipReader`** (store-only, every
refusal of R2, entries read in 1 MiB frames through `UploadSource.read(from, length)`, the core's `Crc32`);
**`ImportSource`** (a ZIP or a walked folder — `FolderUpload.Found` — with NFC paths); **`Manifest`** (`parseManifest`,
the caps and `LIMITS`, v1 in a ZIP only); **`SubtreeImport`** (`openExport`, `plan`, `StorageFit`, the replay with
name freeing and `slug_taken`, the payload checks with the SHA-256 one payload ahead, the rollback of every top-level
node made). No dependency: no entry is compressed (R2), so the core's `Inflate` is not used.

### I3 — Each kind's import is its application's

`AppKinds.imports(kind)` → `NodeImport.Hook(contentSchema, validate, import)`, beside `exports` and `copies`, each in its
application's package: Depot's `DepotImport` (a file through `DepotFiles.upload` reading the checked payload; a drop box
imported as a folder, as the web); Expenses' `PotImport` (the Pot, its entries in batches, each receipt); Memories'
`MemoryImport` (each medium prepared by the platform's `MediaUpload.Deriver` — decoded, its tiers derived — as an
upload's are, then added; the cover and the banner). A bookmark has no hook and is left out with the web's
`unknown_application`-style group, as on the web. A kind whose application is switched off for the tenant is planned as
the web plans it (read at implement from `planImport`/`skipReason`).

### I4 — One set of cases for both clients

**`contracts/export/import-cases/cases.v1.json`**, written by `generate.py` beside it (Python's standard library only,
independent of both clients, as the crypto vectors' Python implementation is): each case an archive (base64; a
handful of KB) or a folder listing, with its expected verdict — the plan's totals and skips, or the refusal code.
Every archive- or manifest-level refusal of `importHardening.test.ts` is a case; the `LIMITS` are pinned against the
schema there (which repoints `manifest.ts:35`'s comment at a test that exists, R3). The web's `importHardening.test.ts`
reads the file beside its own crafted cases (a test change only); the core's test reads it. And the reverse of R6: an
archive the **web** writes (a folder holding a file, a drop box, a Pot with a receipt, a Memory with a photo and a
chapter) committed at `contracts/export/import-cases/web-download.zip` by a web test and imported by the core's.

### I5 — Inline, as the web: the dialog runs the import

An import runs in its dialog's model, not in the upload queue (R4): reading → the summary (nodes, size, the target, what
is left out per group, a quota warning) → running (nodes done of total) → done or failed; *Cancel* aborts and rolls back;
*Import the rest* where something is left out. The dialog is each platform's multi-step surface (ADR 0148 decision 10).
Payloads stream from the source a frame at a time; the manifest (≤ 128 MiB) is held while the plan runs, as on the web.

### I6 — The bridge carries paths

`Import.Screen(scopeId, parentId?, paths)` → `Answer.ImportOffered(id)` or the ordinary upload's answer; `Import.Run(id,
rest)`, `Import.AsFile(id)`, `Import.Cancel(id)`; `Watched.Import(id)` → `Update.Import(id, ImportShown?)` (the step, the
summary, the progress, the refusal) — paths in, never bytes (ADR 0137). The desktops' drops and pickers send their paths
through `Import.Screen` before `Upload.Files`/`Upload.Folder`.

### I7 — What the server sees

Exactly a web import's requests: the created nodes, the uploads and the sealed fields under the importer's keys (ADR 0082
decision 6), and the storage read before it. Nothing about the archive, its manifest or its source.

### I8 — Words

The web's `export.*` import keys ported to `words.json` (EN+DE, each `note` naming its web key); no new copy.

## Phases

- [ ] **1 ADR 0152 and the docs it binds** — ADR 0152 (I1–I8), its row in `docs/decisions/README.md`; ADR 0149's and
  ADR 0082's mentions of the apps' missing import pointed at it; `uc.platform.export-import` gains *In the apps*;
  `native-apps.md` § Open's line narrowed. · Done when: every guard but guard_gaps green.
- [ ] **2 contracts — the cases** (I4) — `generate.py`, `cases.v1.json`, `web-download.zip` written by a web test; the
  web's `importHardening.test.ts` reads the cases. · Done when: `pnpm run check` in `src/web/` green and
  `python3 contracts/export/import-cases/generate.py --check` reproduces the file byte for byte. **Bites:** (a) a case's
  expected code altered → the web's test red; (b) a `LIMITS` value changed in `manifest.ts` → the schema pin red
  (snapshot-and-restore).
- [ ] **3 core — reader, source, manifest** (I2) — `ZipReader`, `ImportSource`, `Manifest`. Tests: every case of the
  file; the core's own download read back (`ZipWriter` → `ZipReader`); `web-download.zip` opened and planned; a 4 GiB+
  ZIP64 entry's header read without reading the entry (a sparse source). **Bites:** (a) the CRC check skipped → the
  `crc_mismatch` case red; (b) `..` let through `isArchivePath` → the `unsafe_path` case red; (c) **no whole-file read**:
  a counting `UploadSource` asserts the screen and the open read only the tail and the directory — bitten by reading
  the source whole.
- [ ] **4 core — the walk and the hooks** (I3) — `SubtreeImport`, `AppKinds.imports`, `DepotImport`, `PotImport`,
  `MemoryImport`. Tests: `web-download.zip` imported into a fake tenant, every kind made with the importer's keys; name
  freeing; a payload's SHA-256 mismatch rolls back every top-level node; *Import the rest*; quota refused before any
  write. Live (throwaway): the web's archive imported and opened. **Bites:** (a) the rollback removed → the mismatch test
  red; (b) `AppKinds.imports` routing a MEMORY to Depot's hook → the per-kind test red.
- [ ] **5 core — the entries** (I1) — `DepotUploads` screens before queueing; the share into a folder screens.
  Tests: a pick of three files with one download offers one import and queues two; a guest's download queues as a file;
  a folder without a manifest uploads as a folder. **Bite:** the member check dropped → the guest test red.
- [ ] **6 bridge** (I6) — the calls and updates; the generated Rust and C# bindings. · Done when: `:bridge:jvmTest`
  green. **Bite:** an `Update.Import` carrying a payload's bytes or a sealed key → the schema test red.
- [ ] **7 Android** — the offer from *Upload files*, *Upload folder*, a level drop and a share; the dialog (M3 full-screen
  dialog). · Done when: `:android:assembleDebug`, the guidelines and words guards green; § Passes' Android row.
- [ ] **8 Apple** — the same (a sheet); iOS's handed-over share screened on queueing. · Done when: both Apple builds
  green; the macOS row run here or named open; iOS the owner's.
- [ ] **9 Linux** — the same over the bridge (`AdwDialog` with an `AdwNavigationView`); its drops screened. · Done when:
  `python3 linux/run.py test` and `build` green; § Passes' Linux row.
- [ ] **10 Windows** — the same (the settings frame's sub-page form or a `ContentDialog`, as ADR 0148's Windows
  flows); its drops screened. · Done when: `python3 windows/run.py build` green on the VM; § Passes' Windows row.
- [ ] **11 the passes** — § Passes, each a throwaway, erased after; results into ADR 0152's Consequences.
- [ ] **12 harvest** — the Use pages EN+DE: `spaces/download-and-import.md` § In the app, `depot/upload-files-and-folders.mdx`;
  `native-apps.md` § Built; #985's row deleted; this CR deleted. · Done when: `pnpm run check` in `site/` and every
  guard but guard_gaps green.

## Passes

| Pass | Who | What it proves |
|---|---|---|
| Android AVD, a throwaway | agent | on the web a folder holding a file, a drop box, a Pot with a receipt and a Memory with a photo, a video and a chapter downloaded; in the app the ZIP picked by *Upload files* and offered as an import, imported into the space's top; the same download unpacked and picked by *Upload folder*; each kind opened in the app and on the web (the Memory's tiers drawn, the receipt opened); *Upload as a file* uploads the ZIP as a file; a ZIP with an altered payload refused with the web's words and nothing left; a cancel mid-run leaves nothing |
| Linux (Flatpak build), a throwaway | agent | the ZIP dropped onto a folder's row and imported; a version-3 manifest refused as newer |
| Windows VM, a throwaway on `tw-win.localhost` | agent | the ZIP picked and imported; each kind opened |
| macOS, a throwaway | agent (screen unlocked) | the unpacked download dropped from Finder onto a level and imported |
| iPhone and iPad | **owner** | a ZIP from Files picked by *Upload files* and imported; a ZIP shared from Mail into a folder offered as an import when the app opens |

## Owner questions

1. **An import runs while the app is open (I5).** As on the web, where closing the tab stops an import, an app that is
   closed or put away long enough for the system to end it stops the import, and what was made by then stays (the
   failure's "left over" count says so; a cancel rolls back). *Recommendation:* yes — the web's behaviour; an import
   that outlives the app would need a persistent job of its own, a second import path for one platform's lifecycle.
   **Answered (owner 2026-11-19): not on the phone for now.** The import is switched on for the desktops (macOS, Linux,
   Windows) only; on Android and iOS/iPadOS an upload of a veyq download stays a plain file upload, no import is offered
   (the core's detection is still built and tested; each phone platform's entry stays off behind one flag). Phases 7 and
   8 build no import UI on Android and iOS/iPadOS; the Mac half of phase 8 does. The question above then holds for the
   desktops only. Revisit when the owner asks.

## Definition of done

Phases 1–12 checked; every bite seen red and green; the passes run as throwaways and erased, the owner's named open
where not run; the Use pages in EN and DE; every guard but guard_gaps green. `git diff --stat` over `src/backend/` is
empty; over `src/web/` only phase 2's tests (and `manifest.ts:35`'s comment); over `contracts/` only
`contracts/export/import-cases/`.

## Retrospective

_(filled at the end of the implementation)_
