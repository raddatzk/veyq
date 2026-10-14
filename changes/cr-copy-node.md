---
id: cr-copy-node
title: Copy a node and its subtree within a space - the export/import walk without the archive
status: in-progress
satisfies: [uc.platform.copy-node]
adrs: ["0082", "0080", "0079", "0062"]
invariants: [operator-blind E2EE everywhere]
---

## Goal

**Copy to…** re-creates a node and everything under it at another place in the same space. The client walks the subtree,
decrypts each file by chunk and uploads it again under fresh keys through each kind's existing export/import hooks; the
server sees only ordinary creates and uploads. Members only. Owner decisions 2026-10-14 (inbox #423).

## Context & constraints

- [uc.platform.copy-node](../docs/features/platform/use-cases/uc.platform.copy-node.md) (flow, losses, quality notes) and
  [ADR 0082](../docs/decisions/0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md)
  Addendum 2 (copy = the walk without the archive; server copy and dedup rejected).
- **Depends on cr-select-nodes** (built and closed 2026-10-14: the level's Select mode, `core/node/selection.ts`, `LevelRow.selection`) for the selection bar's "Copy to…" (phase 2 here); build that CR
  first. Phase 1 here does not depend on it.
- **No contract or backend change.** Nodes are created and files uploaded through the endpoints the import uses;
  `checkTenantStorageFit` is the quota question. One reference per blob ([ADR 0062](../docs/decisions/0062-store-object-reclaim-is-a-durable-work-list.md),
  `scripts/validate_blob_reference_arity.py`) stays true: nothing is shared.
- **The seam.** The walk (`prepareExport`, `web/src/core/export/exportSubtree.ts`) yields payloads as forward-only
  decrypting streams; the import (`importSubtree`, `importSubtree.ts`) takes an `ImportSource` whose
  `ImportNodeContext.payload(ref)` returns a `File`, which the upload pump `slice`s. A copy needs a source that answers
  `payload` from the original without holding a file whole.
- **The same space only:** `treeStore` is one space's, and the server refuses a parent in another scope.
- **Invariant:** operator-blind. The copy's writes carry new ids, keys and ciphertext only; nothing names the original.

## Inventory (commands)

- Walk, import, hooks: `git ls-files 'web/src/core/export/*' 'web/src/features/*/exportImport*'`,
  `grep -rn -E "exportContent|importContent|importsAsFolder|downloadsAsFile" web/src/features web/src/core/applications | grep -v '\.test\.ts'`.
- Browser and actions: `git ls-files 'web/src/core/node/move*' 'web/src/core/node/Move*'`, `grep -n "move" web/src/core/node/nodeHeader.ts`,
  `grep -rn "host.move" web/src/features/*/levelRows.ts`.
- The chunked read of a file: `grep -rn "fileChunkProvider\|presignBlobDownloads\|chunkUrls" web/src/core/blob web/src/features/files -l`.
- Tests: `web/src/core/export/{subtree,importHardening,ImportSubtreeDialog}*.test.ts`, `web/src/features/{files,memory}/exportImport.test.ts`,
  `web/src/core/node/{move,move.wire,MoveNodeDialog}.test.ts`.
- Docs: the use case and its coverage `docs/coverage/features/platform/use-cases/uc.platform.copy-node.yaml` (status
  planned), `docs/apps/files.md` and `docs/apps/memories.md` (the matrices: a copy row), both wikis' files pages.

## Decisions

1. **One browser, two modes.** Core's folder browser takes a mode: "Move here" or "Copy here". In copy mode the node's
   current place is allowed (the move's "where it is now" block does not apply); the node itself and anything inside
   it stay blocked, and the places are those that take every copied kind. "Copy to…" is a node action beside "Move to…"
   wherever a member is offered the move, and never for a guest (whatever its role).
2. **The walk feeds the import directly.** A copy is `prepareExport` over the subtree handed to `importSubtree` through
   an in-memory manifest and a payload source; no ZIP is written and no archive checks run on it (the input is the
   member's own decrypted tree, not untrusted bytes), but each payload's size is checked against what the walk
   declared.
3. **No memory wall.** A payload source answers a file by reading the original **by chunk** (the download's chunk
   provider) and decrypting on demand - a seekable `Blob`-like source the upload pump can `slice` - never by holding a
   file whole. If the upload pump's `slice` cannot be satisfied this way without buffering a whole file, hand that
   back with a recommendation instead of buffering.
4. **The dialog** is the import's, worded for a copy: what will be created, the bytes, the losses (from the use case's
   list, only those that apply to the copied kinds), the quota question, "N of M", rollback to the trash on a stop.
   `Name (2)` for a taken name, as the import.
5. **Rights:** a member with the right to create at the destination; the original only needs READ.

## Phases

- [x] 1 web: Decisions 1-5 for one node ("Copy to…" in the row menu and the header).
  - Tests: the browser in copy mode allows the current place and blocks the node and its inside; a folder with a file
    and a Memory with a chapter and photos copies to the same tree under fresh keys (the wire: new ids, new wraps, no
    request names the original's ids except reads); `Name (2)`; the losses listed per kind; a quota "no" writes
    nothing; a stop part-way trashes what was created; no file is held whole (the source's reads are chunk-sized); no
    "Copy to…" for any guest. Bitten.
  - Done when `npm run check` is green and a real browser on the dev instance copied a folder holding a Memory, a file
    and a drop box, and the copy opens.
  - **Built for folders, files and drop boxes (ecc26e58); the Memory half is handed back.** Decision 3's premise held for
    the upload pump (it reads only frame-sized `slice`s) but not for Memories' import: `addMedia` → `deriveTiers` decodes
    each original whole (`createImageBitmap`, a `<video>` on an object URL), and so does the cover upload. Until that is
    decided, Memories' kinds declare no `importStreams` and a copy leaves a Memory or chapter out and names it (E3). The
    Memory-with-a-chapter test and the browser's Memory copy wait on the answer.
  - **Owner answer 2026-10-14 (option b):** for a copy only, Memories' `exportContent` also hands over each medium's
    existing THUMBNAIL/PREVIEW tiers as payloads read by frame and its sealed per-medium facts (capture time + correction,
    coordinates, place name) as plaintext content; `importContent` in copy mode uploads those tiers under fresh `K_blob`s
    sealed under the new Memory key and medium id instead of calling `deriveTiers`, and seals the facts anew. The
    archive path is unchanged (no tiers in a ZIP). The cover: a photo-of-the-Memory cover follows its medium; an uploaded
    cover image is copied as its blob by frame (no re-encode). Memories' kinds then declare `importStreams`. ADR 0082
    Addendum 2 and the use case already say so. Done when the Memory-with-a-chapter copy test (fresh keys on the wire, no
    whole-file read, the facts and the cover kept) is green and bitten, `npm run check` is green, and a real browser
    copied a folder holding a Memory.
  - **Built (a27103eb):** both hooks learn the walk's `purpose` (`download` | `copy`); Memories' copy hands over each
    medium's tiers by frame, its blobs' declared meta and the listed tier verbatim, and `addCopiedMedia` uploads them
    without `deriveTiers` or an EXIF read; an uploaded cover goes through `uploadCoverBlob` (no re-encode). Proven by
    `features/memory/copy.wire.test.ts` (13 bites across it and `exportImport.test.ts`) and the browser run in the use case.
- [x] 2 web: "Copy to…" in the selection bar of cr-select-nodes (several nodes, one dialog, one summary).
  - Done when `npm run check` is green and a selection was copied in a real browser.
- [x] 3 docs: the use case's status and what was seen; its coverage from `planned` to refs; `docs/apps/*.md` matrices;
  both wikis.
  - Done when every `scripts/validate_*.py` is green.

## Definition of done

- `npm run check` is green in `web/`.
- Every `scripts/validate_*.py` is green, run on a detached worktree.
- The browser runs are recorded in the use case.
- Coverage is updated and no longer `planned`.

## Retrospective

- **Planning: Decision 3 named the wrong consumer.** It asked whether the *upload pump's* `slice` could be served by
  chunk - it can - but the payload's other consumer is the kind's import hook, and Memories' hook decodes the whole
  original to derive its tiers. The escape hatch was there and worked (handed back, not buffered), but phase 1's
  done-when ("a folder holding a Memory") could not be met. Root cause: the refinement traced the bytes to the pump and
  stopped; `deriveTiers` is one call further. **Next time:** when a CR changes what a hook is handed, list every reader
  of that value down to the native API (`createImageBitmap`, `URL.createObjectURL`, `fetch`), not just the first one.
- **Execution: a shared provider met concurrency it never had.** The download's chunk provider presigned once per
  reader; the stream reader warmed it with one chunk first, the pump's four workers did not, so the first copy presigned
  the same blob twice. Caught only because the wire test pinned the read list; fixed in the provider (one presign in
  flight). **Next time:** a new caller of a stateful helper gets a wire test that counts its requests.
- **Execution: a bite found a test that could not tell two reasons apart.** "Not copyable" had two levers
  (`importStreams`, payload `frames`) and the Memory-like test kind lacked both, so removing either gate stayed green.
  Each lever now has its own kind. Process held otherwise: staged by path, unsigned commits as allowed, the dev instance
  restored (the copies are in the space's trash).
- **Handed back, then answered the same day (option b):** how Memories copy.
- **Memory half - planning: the owner's answer named the facts but not every sealed tier.** "Capture time + correction,
  coordinates, place name" left the EXIF bundle and the geometry (`w`/`h`, which the grid's layout needs) unnamed. Both are
  sealed per-medium facts an import of the same file would restore, so the copy re-seals them; reading them as "not named,
  so dropped" would have made a copy carry less than an import. **Next time:** when an answer lists fields of a sealed
  record, ask (or state in the CR) whether the list is exhaustive or illustrative - a record copied verbatim is the safer
  default and also keeps fields a newer client wrote (ADR 0077 point 4's lesson).
- **Memory half - execution: the archive's content shape had to stay untrusted.** The copy's extras ride the same
  `content` an archive carries, so a forged archive could carry them too. The import reads them only under
  `purpose: 'copy'`, which core sets from `prepareCopy` alone, and a test pins that an import ignores a `copy` it finds.
  **Next time:** a mode flag that widens what a hook trusts comes from the caller, never from the data.
- **Memory half - execution: bites first went red for the wrong reason.** Several of the new unit tests went red on a
  missing mock (`TypeError`), not on their assertion - the previous retro's "a test that could not tell two reasons apart"
  in a new form. Defaulting every new door's mock in `beforeEach` made each bite fail on its own assertion. Process held:
  staged by path, unsigned commits, `addMedia`'s characterisation snapshots unchanged, the dev copies in the trash (the
  original "Lissabon" keeps the correction, cover and empty chapter the run gave it); the `npm run check` run under a load
  of ~22 lost four suites to setup-hook timeouts that passed on a re-run, and its build and verify steps ran green after.
