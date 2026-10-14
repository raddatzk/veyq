---
id: cr-select-nodes
title: Select several nodes of a level and move or delete them together
status: planned
satisfies: [uc.memory.browse, uc.memory.move-node, uc.memory.delete-node]
adrs: ["0079", "0080"]
invariants: [operator-blind E2EE everywhere]
---

## Goal

A level gets a **Select** mode: checkboxes on its rows, All / None, a count, and **Move to…** / **Delete** for the
selection. Several nodes move through the existing folder browser and are deleted after one question; each is its own
ordinary write, the tree is re-read once, one summary names every refusal, and one Undo takes back exactly what
happened. Owner decisions 2026-10-14 (inbox #421). Drag and drop is **not** part of this CR.

## Context & constraints

- [uc.memory.browse](../docs/features/memory/use-cases/uc.memory.browse.md) step 6 (the selection),
  [uc.memory.move-node](../docs/features/memory/use-cases/uc.memory.move-node.md) § Several at once and its quality note
  "N writes, not a batch", [uc.memory.delete-node](../docs/features/memory/use-cases/uc.memory.delete-node.md) step 1.
  Each says "decided, not yet built"; the docs phase removes that.
- **No contract or backend change.** A move is one `updateNode` / `updateShareNode`, a delete one `deleteNode`; node
  writes are `@RateLimitExempt`. The server stays the authority for every node.
- **The folder browser exists** (`core/node/MoveBrowser.vue`, inbox #358): `moveHereBlock`, `movePlaces`, `moveStart` in
  `core/node/move.ts` already take `nodes: readonly MovedNode[]`; only the write (`nodeMover`, `MoveNodeDialog.vue`'s
  header comment) moves one node and re-reads the tree after it.
- **Capabilities decide per node** (ADR 0079: core never names an application's kind): `folderCapabilities` in
  `core/access.ts`, `filesCapabilities`, `memoryCapabilities`. A `READ_WRITE` guest moves (a node a folder of its subtree
  holds) and never deletes; `READ` / `WRITE_ONLY` do neither.
- Rule `web/CLAUDE.md` § Core and application: a share view is the member view with a source and capabilities - the
  guest's level gets the same Select, never a guest-only UI.
- **Invariant:** operator-blind, unchanged - the same writes as one node's move or delete, N times.

## Inventory (commands)

- Level, rows, dialogs: `ls web/src/core/node/{LevelView.vue,MoveBrowser.vue,MoveNodeDialog.vue,move.ts,trashUndo.ts,tree.ts,levelActions.ts} web/src/core/node/components/{LevelRowItem,DeleteNodeDialog}.vue`.
- Per-kind row actions and delete doors: `grep -rn "host.move\|deleteFile\|actions: \[\]" web/src/features/*/levelRows.ts web/src/core/node/LevelView.vue`.
- Capabilities: `grep -n "Capabilities(" web/src/core/access.ts web/src/features/{files,memory}/capabilities.ts`.
- Hosts that mount the dialogs: `grep -n "MoveNodeDialog\|DeleteNodeDialog\|useTrashUndo" web/src/views/MemoryHomeView.vue web/src/core/sharing/ShareTreePage.vue web/src/features/files/components/DropBoxDetail.vue`.
- The existing media selection (reference only, not changed): `grep -n "selected\|deleteSelected" web/src/features/memory/components/MemoryBuckets.vue`.
- Tests: `ls web/src/core/node/{move,move.wire,move.e2e,MoveNodeDialog,LevelView,LevelView.tabs,trashUndo}.test.ts web/src/core/node/components/{DeleteNodeDialog,LevelRowItem}.test.ts web/src/core/sharing/ShareTreePage.move.test.ts web/src/core/access.test.ts web/src/features/files/{capabilities,levelRows,levelRows.guest}.test.ts`, `find web/src -name "MemoryHomeView*.test.ts"`. There is no `MoveBrowser.test.ts`.
- Docs: the three use cases above, their coverage under `docs/coverage/features/memory/use-cases/`, `docs/apps/memories.md`
  and `docs/apps/files.md` (the matrices), both wikis' files/navigation pages (`grep -rln "Move to\|Verschieben nach" wiki`).

## Decisions

1. **The selection is core's.** A small selection model (a set of node ids with toggle, all, none and clear; name it
   by what it is, e.g. `useSelection`) lives in core, held by the level, cleared when the level changes or the mode is
   left, and **kept across the level's tabs**: "All" selects what the tab on screen shows, the count says how many are
   selected and how many of them the tab hides. Memories' media selection is not migrated in this CR (inbox #427).
2. **The UI.** "Select" is a toolbar button beside "New" and the trash, hidden in the map presentation. In the mode each
   row has a real, labelled `<input type="checkbox">` (name of the node), the row's primary click still opens it, and a
   bar carries All, None, the count (an `aria-live` region), **Move to…** and **Delete**. An action is enabled when at
   least one selected node allows it; its label or hint says "n of m" when the capability allows only part. A guest is
   offered no Delete (its capability is never true).
3. **Move.** The browser opens with the movable part of the selection. Its pre-check (`moveHereBlock` over the list, the
   colliding-sibling check per node) names and leaves out what cannot go; the rest is written **one node after the
   other** through the session's door (a member's `moveNode`, a guest's `placeShareNodeAsGuest` after naming) with
   **one** tree re-read at the end (a "move many, reload once" path in `nodeMover`, not N reloads). A refusal from the
   server does not stop the rest. Then one summary (moved count; each refused node by name with its reason, through
   `moveRefusalOf`) and one toast with Undo that moves back only what moved; a drop-box guest's folder moved out keeps
   its question and has no undo, as for one node.
4. **Delete.** One question for the deletable part, naming how many and of which kinds (worded through the kinds'
   `nounWithArticle`), replacing the per-node questions. Each node is deleted through the door its single delete uses
   today (core's `deleteNode` for folders, Memories and drop boxes; Files' source for a file), one after the other, the
   tree re-read once. One summary names failures; one toast with Undo restores exactly what went (core's
   `restoreNode`, as `trashUndo.ts` does for one).
5. **One node stays as it is.** The existing single-node actions (row menu, header) and their dialogs, questions and
   toasts are unchanged; the selection is an additional path, and a selection of one behaves like it, wording aside.

## Phases

- [ ] 1 web, core: the selection model and the Select mode (Decisions 1, 2) on a member's and a guest's level.
  - Tests: All / None / toggle; kept across a tab switch, cleared on navigation and on leaving the mode; the count and
    its hidden part; checkboxes labelled; no Select in map mode; a guest's bar has Move and no Delete; an action
    disabled when no selected node allows it. Bitten.
  - Done when `npm run check` is green.
- [ ] 2 web: move a selection (Decision 3).
  - Tests: the browser blocks a place any selected node cannot take; the pre-check leaves out and names a node; N
    writes, one tree re-read (count the `loadTree` calls); a server refusal mid-way leaves the earlier moves and still
    sends the rest; the summary; Undo moves back only what moved; a guest's selection moves through the guest door; the
    wire test shows each write is the single-node write (`move.wire.test.ts` pattern). Bitten.
  - Done when `npm run check` is green and a real browser on the dev instance moved a mixed selection (folder, Memory,
    file) as a member and a selection as a `READ_WRITE` guest.
- [ ] 3 web: delete a selection (Decision 4).
  - Tests: one question naming count and kinds; each node through its kind's door; a failure is named and the rest
    deleted; Undo restores exactly what went; no Delete for a guest. Bitten.
  - Done when `npm run check` is green and a real browser deleted and restored a mixed selection.
- [ ] 4 docs: the three use cases lose "decided, not yet built" and say what was seen; coverage for the new criteria;
  `docs/apps/*.md` where the matrices name move/delete; both wikis (select, move and delete several).
  - Done when every `scripts/validate_*.py` is green.

## Definition of done

- `npm run check` is green in `web/`.
- Every `scripts/validate_*.py` is green, run on a detached worktree.
- The browser runs of phases 2 and 3 are recorded in the use cases.
- Coverage is updated.

## Retrospective

_(filled at implementation)_
