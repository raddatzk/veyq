# ADR 0152 — The apps import a download as the web does: the web's reader and walk in the core, each kind's import its application's, recognised by every Depot upload
- Status: Accepted
- Built: yes on the desktops — the cases, the core and the bridge 2026-11-19, the dialogs of macOS, Linux and Windows
  2026-11-20; passed on Linux and Windows as throwaways, the macOS click pass open (§ Consequences); the phones' entry
  off by the owner's decision (decision 1)
- Date: 2026-11-19
- Model: [native-apps](../crosscutting/native-apps.md) (the format: [ADR 0082](0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md), [uc.platform.export-import](../features/platform/use-cases/uc.platform.export-import.md))

## Context

Inbox #985. The apps download every kind as the web's archive ([ADR 0149](0149-the-apps-take-memories-rest-a-memory-re-keyed-downloaded-and-copied-its-marks-filter-and-contributions-and-tiles-the-webs-rules-in-the-core.md)
decision 1; the core's `ZipWriter`) but bring nothing back: the core has no ZIP reader, no manifest parser and no import
walk. The web has **no Import entry**: its Depot upload recognises a veyq download by its content — a ZIP picked or
dropped, or its unpacked folder — and offers to import it, with *Upload as a file* beside it (ADR 0082 decision 7). Its
reader is its own and store-only (`core/export/zip.ts`), its walk `importSubtree.ts`, each kind's import its
application's hook (`importContent`, `importsAsFolder`). Nothing held the two clients' readers to the same refusals: the
web crafts its refusal cases in `importHardening.test.ts`, and the only cross-client archive is one the core writes and
the web reads.

## Decision

### 1. No Import entry: every Depot upload recognises a download

The core's `DepotUploads` screens what it is handed before it queues (`screen`, `screenFolder`; a share's and a drop's
through `AddHere.Level.takeOrOffer` and `dropOrOffer`), as the web's `screenUpload`/`screenDirectory`: the
first picked, dropped or shared **file** that is a download (by its content: `PK\3\4`, a central directory that holds
`.veyq/manifest.json` or version 1's `manifest.json`) becomes an import offer with *Upload as a file*; a picked or dropped
**folder** holding `.veyq/manifest.json` at its root becomes an import offer without it; every other file or folder is the
ordinary upload. Only a member who may create a folder there, never a guest (ADR 0082 decision 7). The screen reads the
first four bytes, the end of the file and the central directory — never the whole file.

**The phones offer no import for now (owner 2026-11-19).** The import is switched on for the desktops (macOS, Linux,
Windows); on Android and iOS/iPadOS a veyq download is uploaded as the file it is. The core's screen is built and tested
on every target; each phone platform's entry stays off behind one flag (`ShellModel.depotUploadsImporting` where a
platform offers it, `depotUploads` where not; `DepotUploads.importOffered` underneath), so switching it on is that flag and the platform's dialog. An import runs while the app is open, as on the web, where closing the tab stops one;
an import that outlives the app would need a persistent job of its own — a second import path for one platform's
lifecycle, which is why the phones wait.

### 2. The web's reader and walk, ported into the core

`export/` gains, each a port of its web namesake held to the same cases (decision 4): **`ZipReader`** (store-only, every
refusal of `zip.ts`'s reader — multi-disk, a compressed or encrypted entry, a duplicate or non-UTF-8 name, an entry
outside the file or into the directory, a local header that disagrees, overlapping entries, a ZIP64 field out of range,
a CRC mismatch — entries read in 1 MiB frames through `UploadSource.read(from, length)`, the core's `Crc32`);
**`ImportSource`** (a ZIP or a walked folder, `FolderUpload.Found`, by NFC paths; a folder's files carry no CRC);
**`Manifest`** (`parse`, the caps, `LIMITS`, version 1 in a ZIP only); **`SubtreeImport`** (`open`, `plan`, the
`StorageFit` question, the replay top-down with a name freed as `Name (2)` up to 50 tries and on the server's own
`slug_taken`, each payload checked — path, size, CRC-32, SHA-256 — before its hook takes it, and the rollback of every
top-level node made). No dependency: no entry is compressed, so the core's `Inflate` is not used.

### 3. Each kind's import is its application's

`AppKinds.imports(kind)` → `NodeImport.Hook(application, schema, validate, import)`, beside `exports` and `copies`, each in its
application's package: Depot's `DepotImport` (a file through `DepotFiles.upload` reading the checked payload; a drop box
imported as a plain folder, as on the web); Expenses' `PotImport` (the Pot, its people and entries in batches under ids
minted anew, each receipt uploaded before the batch that lists it); Memories' `MemoryImport` (the node born with its date
and place, each medium prepared by the platform's `MediaUpload.Deriver` as an upload's is, its restored facts sealed at
birth, then the cover and the banner). A kind without a hook — a kept link, a kind this build does not know, content of
another application than its kind's — is left out with its subtree under `unknown_application`, content of a newer schema
under `newer_content`, as on the web.

### 4. One set of cases for both clients

`contracts/export/import-cases/cases.v1.json`, written by `generate.py` beside it — Python's standard library only,
independent of both clients, the form of the crypto vectors and [ADR 0146](0146-the-apps-search-with-the-cores-port-of-the-webs-search-both-answer-to-one-set-of-vectors.md)'s
search vectors: each case an archive (base64, a few KB) or a folder listing, with its verdict — the plan's totals and
skips, or the refusal's code. Every archive- or manifest-level refusal of the web's hardening tests is a case; the
`LIMITS` are pinned against the schema. The web's `importHardening.test.ts` reads the file beside its own crafted cases;
the core's tests read it. And the reverse of the existing fixture: an archive the **web** writes, committed at
`contracts/export/import-cases/web-download.zip` by a web test, is opened, planned and imported by the core's.

### 5. Inline, as the web: the dialog runs the import

An import runs in its dialog's model (`ImportDialog`), not in the upload queue: reading → the summary (nodes, size, the
target, what is left out per group, a quota warning) → running (nodes done of total) → done or failed; *Cancel* aborts
and rolls back; *Import the rest* where something is left out. The dialog is each platform's multi-step surface
([ADR 0148](0148-the-apps-account-the-webs-flows-ported-into-the-core-the-kit-written-by-the-core-into-the-file-the-person-chose.md)
decision 10). Payloads stream from the source a frame at a time; the manifest (≤ 128 MiB) is held while the plan runs, as
on the web.

### 6. The bridge carries paths

`Import.Screen(scopeId, parentId?, label, paths, deletedName)` and `Import.ScreenFolder(scopeId, parentId?, label, path,
deletedName)` → `ImportOffered(import, queued, notice)` — the other files queued — or the ordinary upload's `Queued`;
`Import.Run(import)`, `Import.AsFile(import)`, `Import.Cancel(import)`; `Watched.Import(import)` → `Update.Import(import,
ImportShown?)` (the step, the summary, what is left out, the progress, what it says, its buttons) — paths in, never bytes
([ADR 0137](0137-the-bridge-carries-shown-bytes-beside-the-json-ranges-through-a-reader-and-files-by-path.md)). The
desktops' drops and pickers send their paths through `Import.Screen` before `Upload.Files`/`Upload.Folder`.

### 7. What the server sees

Exactly a web import's requests: the created nodes, the uploads and the sealed fields under the importer's keys (ADR 0082
decision 6), and the storage question before them. Nothing about the archive, its manifest or its source.

### 8. Words

The web's `export.*` import keys ported to `src/mobile/words/words.json` (EN+DE, each `note` naming its web key); no new
copy.

## Consequences

- **The server learns nothing new**: no endpoint, field or vector changes; the backend is unchanged.
- **Two readers answer to one file.** A refusal one client adds without a case is a drift the other does not see; a case
  added to `generate.py` reaches both.
- **The phones upload a veyq download as a file** until the owner switches their entry on; a person imports it on a
  desktop app or on the web. Android's and iOS/iPadOS's dialogs are not built (owner 2026-11-19), so their passes —
  a ZIP picked by *Upload files*, the unpacked download by *Upload folder*, a ZIP shared from Mail into a folder — wait
  with them.
- **One known difference from the web: *Cancel* during a run.** The apps' *Cancel* (and Escape, and closing the dialog)
  stops a running import and trashes what it made (`ImportDialog.cancel`); the web disables *Cancel* and the dialog's
  close while a run goes (`ImportSubtreeDialog.vue`), so there a run ends only by finishing or failing. Left as built
  (2026-11-20); which one both follow is the owner's.
- **The passes (throwaways, erased after).** *Linux* (Broadway, 2026-11-20): the web's `web-download.zip` picked by
  *Upload files* into a folder → offered (7 items, 3.5 kB) → imported, each kind read back (`notes.txt` 44 characters,
  the Pot's *Dinner* €42.00, the Memory's medium and chapter, the drop box as a folder); *Upload as a file* left the ZIP
  as a file; *Cancel* at 4 of 7 left nothing. *Windows* (`tw-win.localhost`, UI Automation, 2026-11-19): the same ZIP
  offered with the web's summary, *Cancel* wrote nothing; *Upload as a file* uploaded it (9778 B); *Cancel* while
  running left only the ZIP, *Trip* in the trash; *Import* → `Done, 7 of 7`, each kind read back (the receipt's JPEG
  opened, the Memory's chapter and its photo's tile); the unpacked download by *Upload folder…* → offered without
  *Upload as a file* → imported as *Trip (2)*. A version-3 manifest's refusal is the core's (`ImportCasesTest`, and
  `ImportScreenTest` for the dialog's words), not driven in an app.
- **Open:** a drop from the system's file manager — Linux's from Files (Broadway has none; a GNOME session's), Windows'
  from Explorer (the driven press landed on the app's window, which the host kept above Explorer; the drop's rule is
  `DropSortTest`'s) — and **the macOS click pass**, the coordinator's from the main session (a background agent has no
  full-screen control). On macOS, as a throwaway (`pnpm run throwaway:account -- --out <f> --with-member` in `src/web/`,
  the app signed in by `DevLogin.swift`'s `VEYQ_DEV_*`, `--erase <f>` after): in a folder, *New → Upload files…*, pick
  `contracts/export/import-cases/web-download.zip` → the sheet offers 7 items with *Upload as a file* → *Import* →
  *Imported “Trip”* once the sheet is gone; open `notes.txt`, the Pot's *Dinner* and its receipt, the Memory's photo and
  chapter, the drop box as a folder. The same ZIP again → *Upload as a file* → the ZIP lies as a file. Again → *Import*,
  then *Cancel* while it runs → nothing left but the ZIP, *Trip* in the trash. Unpack the ZIP in Finder and drag the
  folder from Finder onto the level → offered without *Upload as a file* → *Import* → *Trip (2)*. Drop the ZIP from
  Finder onto the level → offered as from the picker.
- **Out of scope:** a guest's import (never, ADR 0082 decision 7); an import that goes on with the app closed.

Explained for users on the docs site (§ In the app on each): [Download and import](../../site/src/content/docs/spaces/download-and-import.md),
[Upload files and folders](../../site/src/content/docs/depot/upload-files-and-folders.mdx).

## Alternatives considered

- **An Import entry of its own** — the web has none; a second door to the same flow, and a person who picks a download
  through *Upload files* would still need the offer.
- **The import queued in the upload queue** — the web runs it inline; a queued import would need its own rollback across
  restarts and its own progress in nodes, a second import path.
- **A ZIP library** — the archives are store-only and the web's reader is ~235 lines; a dependency for it would read more
  than the format allows and refuse less.
- **The web's crafted cases ported by hand** — two copies that drift; one generated file is the search vectors' form.
