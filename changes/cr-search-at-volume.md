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

(filled by the implementer)
