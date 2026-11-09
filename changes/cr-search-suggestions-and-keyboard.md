---
id: cr-search-suggestions-and-keyboard
title: Chip suggestions under their own heading, the first result highlighted, a kind word for bookmarks
status: planned
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

(filled by the implementer)
