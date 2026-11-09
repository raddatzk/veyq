---
id: cr-search-at-volume
title: How the search behaves at a household's volume — measured, and fixed where it blocks
status: planned
satisfies: [uc.platform.search]
adrs: ["0125", "0105"]
invariants:
  - every seeded node is sealed by the real client crypto, through the existing volume harness
  - nothing persists the index to fix a slow build (ADR 0125 decision 2); a fix stays in memory
  - measurements run on a quiet machine, alone (no parallel build or check)
  - no backend and no contract change
---

## Goal

The search has only been seen on a stack with a handful of nodes. ADR 0125's Consequences *estimate* the first build
from the volume pre-check's tree numbers (0.84 s for the root view at 5,000 nodes); nobody has measured the search
itself. Measure it, judge it by the rule below, and fix what fails, all in this CR. Write the numbers where the
decision lives: ADR 0125 § Consequences, which then cites a measurement instead of an estimate.

## The judging rule (decided at refine; the CR changes no product number beyond it)

At the volume pre-check's large tree stage (about 5,000 nodes) plus its member stage (about 445 members), spread
over at least three spaces (the tenant's, a group, and personal), on this Mac, measured in desktop Chrome and again at
4× CPU throttle:

1. **Typing never blocks.**
   - **Bound:** from a keystroke to the updated list, no main-thread task longer than **100 ms** at 4× throttle.
   - **Measurement:** a `PerformanceObserver` for `longtask` plus `event` timing over a scripted sequence ("K", "Ke",
     "Kev", a chip, Backspace).
2. **The first build does not freeze the page.**
   - **Bound:** while the trees of every space are opened after the first focus, no single main-thread task longer
     than **200 ms** at 4× throttle.
   - **Reported, with no bound:** the build's total time, until every space is indexed.
3. **Revalidation is cheap.** A second focus with nothing changed costs one `304` per space and no decryption
   (request count and the absence of decryption are asserted, not timed).
4. **Memory** of the index (heap delta after the build) is reported, with no bound.

Bounds 1 and 2 decide. When a bound fails, the CR fixes it inside the search and measures again. A fix may move the
build into a worker, chunk it with yields, or index lazily per space. A fix that changes what the member sees, or
needs a contract, is handed back as a question instead.

## Context (substrate: branch `worktree-search-design` at the tip this CR is committed on)

- **The harness:** `changes/cr-volume-measurement-cax.md` § Local pre-check is the procedure. It uses
  `scripts/measure_volume.py`, the seeders `src/web/scripts/seed-volume.mts` and `measure-volume.mts`, and the
  browser specs under `tests/e2e/measure/`.
  - It boots its **own** production-recipe instance (`… boot`) and seeds stages (`… stage tree <N>`,
    `… stage members <N>`). Re-seed times are in that CR.
  - Use that instance, not the `veyq-search` stack, and **never** the owner's `infra` stack.
  - The harness may need a stage that spreads the tree over several spaces. Add it to the harness, which
    `cr-volume-measurement-cax` deletes later, so say so there in one line.
- **The search:**
  - `src/web/src/core/search/` (`searchIndex.ts`: each space read by its ETag through `openListedScope`, keys zeroed in
    a `finally`; the active space from `treeStore`).
  - Memories' facets: `features/memories/search.ts`.
  - People and settings: `peopleAndSettings.ts`.
- **Machine:** run each measurement on a quiet machine. Check `uptime` before each run and record it beside the numbers.

## Phases

### Phase 1 — measure

- **Build:** a measurement spec beside the harness's browser specs.
  - It drives the real search field: first focus, then the typing sequence, then a second focus.
  - It reads the four quantities of the rule, desktop and at 4× throttle.
- **Seed:** the stages the rule names.
- **Report:** in the CR's retrospective, the numbers per quantity, per run, with `uptime` and the command.
- **Done when:** every quantity is measured twice, the two runs agree within reason, and the judgment per bound is
  written.

### Phase 2 — fix (only if a bound failed)

- **Build:** the smallest change inside `core/search/` that meets the bound.
- **Measure again:** the same spec, twice.
- **Unit tests:** pin the fix's behaviour (e.g. a build that yields; results unchanged).
- **Done when:** both bounds hold. `pnpm run check` is green in `src/web/`.

### Phase 3 — the docs

- ADR 0125 § Consequences replaces the estimate with the measured numbers and their command.
- The use case's Quality notes get one line if the build changed shape (a worker, chunks).
- **Done when:** the trust-chain guards are green (`/usr/bin/python3`, guard_gaps skipped).

## Retrospective

### How it was measured

- **Instance.** The harness's own (`measure_volume.py … boot`, project `veyq-vol-11090922`, images built from this branch
  at `13814429a`), never `infra` or `veyq-search`. Seeded with `seed tree --target 5000` (the group "Volume tree", the
  tree stage's shape), the new `seed spread --target 1000` (the tenant's space and the owner's personal space, 1,000
  nodes each, every Memory sealed with a date range and a place, names like "Kevelaer 2012 (000-0)") and
  `seed members --target 445`. So 7,000 nodes over three spaces plus 445 members. **My choice:** the largest space at
  the stage's full 5,000 and the two others on top, rather than 5,000 split three ways — a single space's build is what
  a task bound meets, so the larger space is the conservative reading.
- **The leg.** `tests/e2e/measure/search.ts` (`search.config.ts`), driven by
  `/usr/bin/python3 scripts/measure_volume.py --instance I search --runs 2 --throttles 1,4 --max-load 1000 --active
  tenant|group`. Per run a fresh device signs in; `--active group` then enters the 5,000-node group by its fragment.
  First focus (the build): long tasks from the click until the "Searching N spaces…" line is gone and every other
  space's tree has answered, the build's wall time, the JS heap after a forced GC before and after. Typing: "K", "e",
  "v", Enter (takes the highlighted place chip "Kevelaer"), Backspace (removes it); per step the long tasks from the
  keystroke until the list stopped changing for 300 ms, and the Event Timing entries. Second focus: every API response
  with its status, and the strings the page decoded. The driver's verdict is `search-verdict.json` beside the readings
  in `.run/measurements/<time>-search-<active>/`.
- **Quantity 3's detector, bitten.** `TextDecoder.prototype.decode` is counted from an init script: a tree's names are
  opened as `decoder.decode(v1.openField(…))` (`core/node/tree.ts`), so a tree that is opened decodes once per node.
  The first focus is the bite: it must count at least one decode per node the search opens itself — it counted 6,389
  for the 6,000 nodes of group + personal (tenant on screen) and 2,388 for the 2,000 of tenant + personal (group on
  screen), in every run.
- **Two landings.** The CR names no space on screen. The tenant's (where sign-in lands) makes the search open the
  5,000-node space itself; the group's makes the index build the 5,000-node space from the tree on screen
  (`followActive`), a different code path. Both were measured, and both decide.
- **The load rule (coordinator, 2026-11-09).** The owner works on this machine, so it is seldom quiet: the 1-minute
  load stayed 6–25 the whole session, never ≤ 3 (the harness's old `--max-load 3` waited 25 minutes in vain). So every
  run was taken at the load there was, with `uptime` recorded at both ends of each invocation (`quiet-start.json`,
  `quiet-end.json`), and judged like this: **a bound that holds under load passes** — load only makes the numbers worse,
  so a pass under load is conservative; **a bound that fails under load gets one re-run**, ideally at a lower load, and
  fails if it fails again; **after a fix, both runs of the re-measurement must hold**, each with its load reported.

### Phase 1 — the unchanged search (image `veyq-vol-search:local`)

| Invocation, load (1-min, start→end) | landing | build wall ×1 / ×4 | longest build task ×1 / ×4 | longest typing task ×4 | heap after build |
|---|---|---|---|---|---|
| A 10:21, 8.6→8.0 | tenant | 859, 680 / 2,675, 2,651 ms | 488, 385 / **1,652, 1,636** ms | 65, 90 ms | +4.1 MB |
| A 10:22, 8.0→10.1 | group | 548, 416 / 1,919, 1,892 ms | 209, 158 / **828, 829** ms | **260**, 69 ms | +3.7 MB |
| B 10:24, 10.0→10.4 (re-run) | tenant | 645, 852 / 3,089, 4,050 ms | 372, 497 / **2,020, 2,631** ms | 88, 95 ms | +4.1 MB |
| B 10:25, 10.4→14.2 (re-run) | group | 507, 508 / 2,796, 3,576 ms | 204, 202 / **1,007, 1,640** ms | 81, **150** ms | +3.7, +0.9 MB |

Typing at ×1 had no long task at all (no task over 50 ms) in any run; at ×4 the longest typing task was always the
first keystroke ("K"), whose Event Timing duration was 88–320 ms.

- **Bound 2 failed**, in both landings and both invocations, 4–13× over its 200 ms (and over 200 ms even unthrottled in
  the tenant landing). The long-animation-frame attribution named one task per space: the space the search opened
  (`openTree` + `nodeDocuments` in one go: 1.6–2.6 s for the 5,000-node group), and the active space's documents built
  synchronously in the watcher (`followActive`: 0.8–1.6 s for the 5,000-node group on screen, 0.2–0.3 s for the
  tenant's 1,000).
- **Bound 1 failed in the group landing** (260 ms, then 150 ms in the re-run) and held in the tenant landing (65–95 ms).
  A CPU profile of the typing at ×4 (`search --profile`, a diagnosis run, no reading) put about a third of the "K"
  task in `searchGroups` — `matchAll` over ~7,500 documents, the reactive chip array read once per document, the sort
  of ~840 matches with the collator — and the rest in Vue's render of the list.
- **Quantity 3 holds for the index, with one finding beside it.** In every run of every invocation, phase 2's
  included, the second focus read each space the search opens itself exactly once, as `304`, and the index decoded
  nothing. The focus as a whole decoded 2 strings every time: the group's name ("Volume tree") and the device register
  document — the settings sections' `load()` (`features/settings/sections.ts`: `listMyGroups` opens every group's grant
  and name, `refusedDocuments` opens the two account documents), which `SearchField.onFocus` calls on every focus. That
  is a cost per group per focus outside `core/search/`; recorded here, not fixed (no bound covers it).
- **Quantity 4**, the index's heap: +4.1 MB with the group opened by the search, +3.7 MB with it on screen.
