---
id: cr-web-depot-on-the-core
title: Depot's rules move onto the Kotlin core for the web — `@veyq/core-js/depot`, the TypeScript twins deleted
status: in-progress
satisfies: ["uc.depot.view-file", "uc.depot.member-files", "uc.depot.share-folder-files"]
adrs: ["0155", "0156", "0079", "0136", "0154", "0046"]
invariants:
  - "operator-blind: the move changes where a rule runs (Kotlin over the web's own primitives), never what is read, sealed or sent"
  - "code a façade reaches carries no kotlinx.serialization, coroutines, Ktor or NotOnTheWeb refusal (ADR 0155 decision 4), held by the package's slice check"
  - "no screen model with a flow crosses (ADR 0155 decision 8): what keeps state over time or awaits I/O stays TypeScript"
  - "a Long never crosses the façade; a size is a JavaScript number"
  - "the platform module stays inside its running budget (80,094 B gz); a move that would pass it stops before the web switch (owner 2026-11-24)"
  - "no behaviour a member or a guest sees changes"
---

> Owner go 2026-11-24 ("weiter mit Depot"): ADR 0155 decision 7.3's next application. Refine and build in one run; the
> implementer decides technical questions and logs them below (owner 2026-11-21: autonomous work keeps a decision log).

## Goal

The web draws Depot's rules from `:apps:depot` (`@veyq/core-js/depot`, Depot's own module file
`kotlin_veyq_mobile_apps_depot.mjs`), as Expenses' move did (ADR 0155 § Expenses' move), and the TypeScript twins go.

## Refine — what of `src/web/src/features/depot/` crosses (measured at `96a40323e`)

Each file's exports read against `:apps:depot`'s common code (`grep -nE '^export' src/web/src/features/depot/*.ts`;
`grep -rnE 'fun |object |class ' src/mobile/apps/depot/src/commonMain`):

| Web | Kotlin twin | Verdict |
|---|---|---|
| `view.ts` — the decision what an opened file is (the bytes decide, the claim narrows; caps; a text's frames and cut) | `FileView.decide` | **moves** (pure; the reading — `streamBlob`, `TextDecoder` — stays) |
| `uploadFolder.ts` `planFolderUpload` — a directory's tree of safe names, slug-numbered per directory, the top among the level's siblings | `FolderUpload.plan` / `made`'s top numbering | **moves** |
| `svgRaster.ts` `planRaster`, the caps; `SvgView.vue`'s zoom steps | `SvgView.plan`, `MAX_PIXELS`, `MAX_EDGE`, `ZOOM_STEPS`, `zoomIn`, `zoomOut` | **moves** (the decode and paint stay: a canvas is a browser's) |
| `uploadFolder.ts` `createFolders`, `takeBackEmptyFolders` | `FolderUpload.made` (suspend) | stays — awaits I/O; a `suspend` path is coroutines (decision 8) |
| `rights.ts` `depotRights`, `canUploadFolder`; `source.ts` `sourceRights` | none whole: `FileRights.of` (the opened file only), `AppKinds.Header`/`doors`, `GuestView.createsFor` | stays — no twin to draw; building the matrix in the core first is its own CR (inbox #1300) |
| `uploader.ts` `uploaderOf`, `guestUploaderName` | `Uploaders.of` (suspend, a tenant), `guestFolder` | stays — the Kotlin twin opens no READ_WRITE guest's sealed name yet and reads through a session (inbox #1301) |
| `exportImport.ts` `validateFileContent`, `validateDropBoxContent` | `DepotImport.validateFile`/`validateDropBox` over kotlinx's `JsonObject` | stays — the twin is variant A; with the import's move (inbox #1302) |
| `uploadFile.ts` `deliveredSince`, `fileBlobMeta`; `register.ts` `fileNameStemEnd` | none | stays — no twin |
| `levelRows.ts`, `dropBox.ts`, `shareUpload.ts`, `uploadFile.ts` jobs, `download.ts`, `source.ts`, `useFileViewer.ts`, components | `DepotUploads`, `DropBoxPage`, `FileModel`, `DepotGuest`, `DepotFiles` — models with flows or I/O | stay — decision 8 |
| platform code 7.2 moved: the sniff, the archive name, the download name (`core/blob/sniff.ts`, `core/export/archiveName.ts`, `core/blob/meta.ts`) | `blob/Sniff`, `export/ArchiveName`, `blob/DownloadName` | already the platform's |

**Hot paths** (decision 7.3's time check): `planFolderUpload` over a directory of 10,000 files (100 directories, case
clashes, hostile names, slug clashes, 20 numbered siblings) and the view's decision over the first frames of 1,000
openings (100 each of a PNG, a JPEG, an SVG, an MP4, an MP3, a PDF, a binary, an ASCII, a UTF-8 and a UTF-16 text of
256 KiB). `planRaster` runs once per paint (debounced 150 ms): measured, not named hot.

**Vectors** (`contracts/depot/v1-vectors.json`): `view`, `folderPlan` (with the folding's tokens each case may ask),
`raster`, `zoom`; written by the core's `DepotVectorsTest`, answered by the package's `depot.test.ts` and the web's
`features/depot/vectors.test.ts`.

## Phases

1. **Core** — `FolderUpload`'s plan over any entry (`planned`, the namer internal), `FileView.decided`/`answer` (each sniff
   read once), the vectors. *Done when:* `:apps:depot:jvmTest`, `macosArm64Test` green, the vectors rewritten byte for byte.
2. **Package** — the façade (`apps/depot/src/jsMain/…/depot/web/`), `./depot` in `package.json`, the slice check's row, the
   package's vectors test. *Done when:* `pnpm run build && pnpm run check` green, the platform within its running budget.
3. **Web** — `view.ts`, `uploadFolder.ts`, `svgRaster.ts`, `SvgView.vue` as glue; the folder upload's module loaded on
   first use (`levelRows.ts`); the web's vectors test. *Done when:* `pnpm run check` green, the twins' tests passing
   through the glue.
4. **Gate and harvest** — sizes (decision 7.3 and decision 10's guest path), times, bites, three byte-identical builds;
   ADR 0155 § Depot's move, `docs/apps/depot.md`, inbox; this file deleted.

## Decision log

- **D1 — The set: three rules with a twin, nothing without one.** *Q:* what crosses? *Choice:* the view's decision, the
  folder plan, the SVG raster and zoom steps — the rules the apps already run. *Why:* decision 1 deletes a twin; the
  rights matrix, the uploader's naming and the content validators have no whole Kotlin twin (or one over variant A), so
  moving them means building the core first — separate work (inbox #1300–#1302). *Cost:* the move is small: 1,847 B gz of
  TypeScript sets measured, 374 B gz net of the glue (D7).
- **D2 — The folder upload's module loads on first use.** *Q:* `levelRows.ts` (eager) imported `uploadFolder.ts`; with the
  plan in Kotlin, a static import would pull Depot's module file into the eager set for every visitor. *Choice:*
  `levelRows.ts` imports `./uploadFolder` when a folder is picked or dropped (`await import`), so Depot's module is a
  lazy chunk shared with the file page. *Cost:* the two rows tests that drive a folder upload import `./uploadFolder`
  up front, so the first load is no wait of a test (an import line each).
- **D3 — Each sniff read once.** *Evidence:* the first façade asked `Sniff.textEncoding` again for a text's encoding after
  `decide` had asked `isText` — 2.00× the TypeScript over the corpus (1,136 against 567 ms for 10,000 openings), every
  256 KiB text validated twice. *Choice:* `FileView.decided` returns the decision with the stream and the encoding its own
  sniff found; `decide` is its first part, its signature unchanged for the apps. After: 1.00×.
- **D4 — A too-large decision carries the sniffed type.** *Q:* the web says which cap a file passed. *Choice:*
  `Decision(TOO_LARGE, mime)` — the type whose cap it passed; the apps read only the kind. No signature changes.
- **D5 — Where the core and the web answered apart, the web's answer won** (ADR 0154's rule): a path that names no file
  (`""`, `"/"`) counts toward neither the plan's files nor its bytes (the core counted every entry). Pinned by the
  `folderPlan` case "subfolders named before files; empty segments dropped, a path of none left out".
- **D6 — Plain objects field by field.** The façade fills each answer with `js("({})")` and field writes — no `Pair` per
  field, the plan makes one object per file.
- **D7 — The numbers** are in ADR 0155 § Depot's move (written at harvest).
