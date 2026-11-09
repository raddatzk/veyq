---
id: cr-search-suggestions-and-keyboard
title: Chip suggestions under their own heading, the first result highlighted, a kind word for bookmarks
status: done
satisfies: [uc.platform.search]
adrs: ["0125", "0079"]
invariants:
  - core imports no application (`validate_web_core_imports_no_applications.py`)
  - nothing about a query leaves the device or is written to storage (ADR 0125 decisions 1, 2)
  - no backend and no contract change
---

## Goal

Three small changes the owner asked for after `cr-search-people-and-settings` (owner, 2026-11-09:
"Ja, bau die drei Punkte noch"). [uc.platform.search](../docs/features/platform/use-cases/uc.platform.search.md)
step 4 already states the first two.

1. **The chip suggestions stand under a heading of their own,** *Filter by* (de: *Filtern nach*), above the result
   groups.
   - **Why:** a person suggestion ("Kevin Raddatz · Person") and a person result ("Kevin Raddatz ~kevin") stood
     directly under one another. They looked the same and did different things: one filters Memories, the other opens
     the profile.
   - **Shape:** the heading is the same kind of group heading the results use. The suggestions keep their chip icon
     and their facet word.
2. **The first result is highlighted whenever the list holds one, also right after a chip was chosen,** so Enter
   opens it.
   - **Today:** after a chip, nothing is highlighted, and Enter does nothing until ArrowDown.
   - **Unchanged:** a suggestion is still chosen by moving onto it.
   - **The decision:** when the list holds results AND suggestions, which one does Enter take? The first result,
     because the suggestions sit above it but are not highlighted by default. Typing a token and pressing Enter must
     not silently turn it into a chip: a chip is chosen by pointing at it. If Reka's combobox makes that ordering
     impossible without fighting it, hand the question back in the retrospective with the closest buildable variant.
3. **The bookmark kind (`SHARE_BOOKMARK`, core's own kind) gets its kind word,** *Bookmark* (de: *Lesezeichen*), so a
   kind chip reaches it. The word lives in core's catalog, because the kind is core's.

## Context (substrate: branch `worktree-search-design` at `779821eed`)

- **The field:** `src/web/src/core/search/SearchField.vue` on `src/web/src/shared/ui/VeyqTagsInput.vue` (Reka
  `Combobox` plus `TagsInput`). The previous CRs' retrospectives record how the result groups were added to
  `VeyqTagsInput` (`choose`, `status` slot, `v-model:open`, `openOnFocus`).
- **Kind words:** `KindDescriptor.search.label`. The bookmark kind is registered in `src/web/src/applications.ts` and
  `src/web/src/core/bookmark/`.
- **Text:** the EN+DE strings are in `src/web/src/i18n/index.ts`.
- **Tests that mount the field or the topbar:** find all of them before the first full check, so the check is not red
  three times on suites nobody ran (the last CR's lesson):
  `rg -ln "SearchField|AppTopbar|VeyqTagsInput" src/web/src --glob '*.test.ts'`.

## Phases

Single phase.

- **Build:** the three changes.
- **Tests:**
  - The suggestions render under the *Filter by* heading.
  - After a chip is chosen, the first result is highlighted, and Enter opens it.
  - **The bite pair:** typing a person's name and pressing Enter opens the first result and adds no chip, with a
    positive control — moving onto the suggestion and pressing Enter does add the chip.
  - A kind chip for a bookmark.
- **Coverage:**
  - Extend `uc.platform.search.yaml`'s `chips-and-text` with the heading and the highlight (or add a criterion; say
    which).
  - Extend `every-kind-has-a-word` with the bookmark.
- **Browser pass on the `veyq-search` stack:** see `cr-search-across-spaces` § Retrospective for how to start it.
  - "kevin" shows the *Filtern nach* heading above the suggestion and *Personen* below.
  - A year chip followed by Enter opens the first result.
  - A bookmark found by a kind chip (create one first if none exists).

  Desktop and phone width; final screenshots only.
- **Docs:** the Use page `spaces/search` in EN+DE where it describes chips and the keyboard.
- **Done when:** `pnpm run check` is green in `src/web/`, the core-import guard is green, and the trust-chain guards are
  green (`/usr/bin/python3`, guard_gaps skipped).

## Retrospective

Implemented 2026-11-09 in the worktree `search-design`, one phase. `pnpm run check` green in `src/web/` (measured: 539 test
files, 6498 tests); every `scripts/validate_*.py` but `validate_guard_gaps.py` green with `/usr/bin/python3` (69 guards,
the core-import guard among them; no validator changed).

**Technical choices made (owner away; recorded here, as asked).**
- **Reka could do it without a fork, with one lever it does not name.** `VeyqTagsInput` (only when it has `groups`) settles
  the highlight on every Reka `highlight` event and after every change of what the list holds: the first result, or
  nothing when there is none. Reka's `ComboboxRoot.highlightItem` puts it on the result; Reka has no call that DROPS a
  highlight, so a suggestion Reka highlighted by itself (its first-row highlight on typing and on opening) is cleared by a
  `pointerleave` dispatched on its list root - the event Reka drops a highlight on itself. Reka also drops it when the real
  pointer leaves the field; the field's `leave` handler then gives it back to the first result (seen in the browser pass).
- **"Moving onto" is tracked, not inferred:** ↑/↓/Home/End on the field (caught before Reka's handler) and a `pointermove`
  over the list (capture) mark the member's row; it stays highlighted while the list re-renders under it (the 20 s
  revalidation), until the text, the chips (compared by key, not array) or the list's open state change, or the row is
  gone. So from the first result, **↑ reaches the suggestion above it**; with suggestions and no result nothing is
  highlighted and ↓ enters the list as before. A field without `groups` (the participant and Pot pickers) is untouched:
  first suggestion highlighted, Enter adds it.
- **The heading** is a prop of the layer, `optionsLabel`: the suggestions are always one `ComboboxGroup`
  (`veyq-tags-input__suggestions`, the group's existing flex/gap rules), headed only when the caller names it.
- **The bookmark's word** reuses `node.bookmark.kind` (*Bookmark* / *Lesezeichen*), the word its level row already shows.
- **Coverage:** `chips-and-text` extended (heading, highlight, Enter never makes a chip; `VeyqTagsInput.test.ts` added);
  `every-kind-has-a-word` extended to core's folder and bookmark. The applications test dropped its exception.

**Bites (snapshot-and-restore, `scratchpad/p10_bites.py`):** B1 no settling - 5 tests red (the bite pair, the after-chip
highlight, the no-suggestion case, the person test, the layer test); B2 the member's move not kept - 12 red (every test
that moves with the keys, the bite pair's control included); B3 no heading - 5 red; B4 no bookmark word - 2 red.

**Browser pass** (`veyq-search` stack, German UI; seeded here: a view link of *Reisen* named *Für den Test*, kept as the
bookmark *Reiselink* at the tenant space's top level): desktop 18 checks, phone (390 px) 11 checks, all PASS
(`p10-pass.mjs`). Screenshots `p10-desktop-kevin`, `p10-desktop-year-chip`, `p10-desktop-bookmark-chip`,
`p10-phone-kevin`, `p10-phone-year-chip` in the session scratchpad's `search-pass/`.

**Owner questions (each with the conservative default built).**
1. **↑ from the first result reaches the suggestions** (they sit above). Recommendation: keep; the Use page says "move
   onto it with ↑ or ↓".
2. **With suggestions and no result, nothing is highlighted**, so Enter on `2024` alone (no name holds it) does nothing
   until ↓ - the CR's "typed text never silently becomes a chip", applied also where the list holds no result.
   Recommendation: keep.
3. Keep this CR for review or delete it: its binding lessons are in `VeyqTagsInput.vue`'s header and the coverage file.

**What went wrong.** The first layer test was red on the member's move being forgotten when the caller handed the same
chips as a new array: a watch on `keys` compared arrays by identity. Next time: a watch that means "the chips changed"
compares by content.

**Next time:** a pass script that navigates by hash reloads the page, or the field keeps the previous step's chips.
