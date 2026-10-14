---
id: cr-copy-node
title: Copy a node and its subtree within a space - the export/import walk without the archive
status: planned
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

- [ ] 1 web: Decisions 1-5 for one node ("Copy to…" in the row menu and the header).
  - Tests: the browser in copy mode allows the current place and blocks the node and its inside; a folder with a file
    and a Memory with a chapter and photos copies to the same tree under fresh keys (the wire: new ids, new wraps, no
    request names the original's ids except reads); `Name (2)`; the losses listed per kind; a quota "no" writes
    nothing; a stop part-way trashes what was created; no file is held whole (the source's reads are chunk-sized); no
    "Copy to…" for any guest. Bitten.
  - Done when `npm run check` is green and a real browser on the dev instance copied a folder holding a Memory, a file
    and a drop box, and the copy opens.
- [ ] 2 web: "Copy to…" in the selection bar of cr-select-nodes (several nodes, one dialog, one summary).
  - Done when `npm run check` is green and a selection was copied in a real browser.
- [ ] 3 docs: the use case's status and what was seen; its coverage from `planned` to refs; `docs/apps/*.md` matrices;
  both wikis.
  - Done when every `scripts/validate_*.py` is green.

## Definition of done

- `npm run check` is green in `web/`.
- Every `scripts/validate_*.py` is green, run on a detached worktree.
- The browser runs are recorded in the use case.
- Coverage is updated and no longer `planned`.

## Retrospective

_(filled at implementation)_
