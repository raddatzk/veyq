---
id: cr-upload-tray-one-list-per-destination
title: The upload tray draws one list per destination, and a later upload joins the open list
status: planned
satisfies: [uc.memory.upload]
adrs: ["0079"]
invariants: [operator-blind E2EE everywhere]
---

## Goal

Two uploads into the same Memory or folder show today as two lists with the same title, each with its own progress
bar, because each pick is its own queue batch. After this CR, core's upload tray (`core/blob/UploadStatusMenu.vue`)
groups every **open** batch with the same destination into **one list**:

- one title;
- one progress bar over all its files, which recomputes and may go down when a later upload joins;
- one "Close", which closes every batch in it.

A closed list is not reopened, and different destinations stay separate lists. Owner decision 2026-10-14.

## Context & constraints

- [uc.memory.upload](../docs/features/memory/use-cases/uc.memory.upload.md), main flow steps 4 and 5. The tray is
  core's and every application's uploads pass through it, Files' included ([docs/apps/files.md](../docs/apps/files.md)
  § The upload), so the grouping must not depend on Memories.
- **The queue stays as it is.** `core/blob/queue.ts` keeps its batches, their `onComplete` / `onItemDone` hooks, their
  `extra`, and the rule that a hard failure cancels the rest of *that batch's* queued files. The grouping is purely in
  the tray. Merging the batches themselves would merge hooks that belong to different enqueues.
- **The destination is the batch's `key`.** It is "the caller's grouping key (which view an in-flight item belongs
  to)" (queue.ts). A batch without a `key` is a list of its own.
- **Invariant:** operator-blind. Nothing here reaches the server.

## Inventory (commands)

- The tray and its tests: `web/src/core/blob/UploadStatusMenu.vue`, `UploadStatusMenu.test.ts`.
- Who enqueues with which `key` and `label`: `grep -rn "enqueue(\|key: batchKey\|label:" web/src --include=*.ts | grep -v test`.
  Check that every application's key names its destination. If one does not (a missing or per-call key), that is a
  gap to report, not to paper over.
- The tray's batch-level controls: close (`acknowledgeBatch`), cancel all of a batch (`cancelBatch`), and the
  per-file cancel and retry.

## Decisions

1. **A list** is the set of unacknowledged batches with the same `key`, in `seq` order. Its title is the label of its
   first batch. Its files are the batches' files in order.
2. **Its progress** uses the same formula the tray uses for a batch today, applied over all the list's files. A file
   that is done counts as complete. A cancelled file leaves the denominator, if that is today's per-batch rule.
   Whatever the rule is now, it stays the rule, only over the larger set.
3. **Close** appears when every batch of the list is finished and acknowledges all of them. A list-level cancel, if
   the tray has one, cancels every batch in the list.
4. **The header count** ("37 aktiv") is unchanged: it counts files, not lists.

## Phases

- [ ] 1 web: Decisions 1–4 in `UploadStatusMenu.vue`.
  - The tests must cover:
    - two batches with one key render one list with one bar whose value equals the formula over both;
    - a third batch enqueued later joins, and the bar goes down;
    - a closed list stays closed, and a new batch with its key is a new list;
    - two keys make two lists;
    - a batch without a key is its own list;
    - Close acknowledges every batch of the list.
  - The "joins" and "stays closed" assertions are bitten.
  - Done when `npm run check` is green in `web/` and two uploads into one Memory have been seen as one list in a real
    browser (the web e2e suite, or the Browser pane on a stack of its own).
- [ ] 2 docs: `uc.memory.upload`'s coverage YAML and the wiki's upload text (EN and DE), where it describes the tray.
  - Done when the trust-chain guards are green.

## Definition of done

- `npm run check` is green in `web/`.
- Every `scripts/validate_*.py` is green, run on a detached worktree.
- The click-through is recorded.
- Coverage is updated.

## Retrospective

_(filled at implementation)_
