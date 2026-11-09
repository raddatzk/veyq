---
id: cr-search-calm-focus
title: A click into the search opens nothing until the member types; no flickering load state; focus colour wins over hover
status: planned
satisfies: [uc.platform.search]
adrs: ["0125"]
invariants:
  - the index still starts building on the first focus (ADR 0125 decision 3), so the first keystroke rarely waits
  - nothing about a query leaves the device or is written to storage
  - no backend and no contract change
---

## Goal

The owner recorded a click into the search field (2026-11-09) and found that "a lot happens". Measured with a headless
probe on the `veyq-search` stack (`scratchpad/search-pass/p17-focus-probe.mjs`):

| ms after the click | panel text | height |
|---|---|---|
| 108 | "Durchsucht Namen, Daten, Orte und Personen …" | 64 px |
| 125 | "2 Bereiche werden durchsucht …" | 45 px |
| 263 | "Ein Bereich wird durchsucht …" | 45 px |
| 297 | "Durchsucht Namen, Daten, Orte und Personen …" | 64 px |

The recording also shows the field's border turning grey (hover) while the field has focus.

[uc.platform.search](../docs/features/platform/use-cases/uc.platform.search.md) steps 1 and 2 now state the fix.

1. **Focusing opens nothing.** The list opens with the first typed character. The hint text, which describes what is
   searched, appears only where it answers something: the empty result state of a typed query (uc A2).
   - Escape and a click outside close the list as today.
   - Backspace to an empty field closes it. Keep the existing chip behaviour: Backspace in an empty field still
     removes the last chip, and the list follows what remains, open only while text or chips are present.
2. **No flickering load state.** "N Bereiche werden durchsucht …" shows only after the member typed AND the loading
   has lasted longer than about 300 ms. The panel never changes height for a state shorter than that.
3. **Focus wins over hover.** While the field has focus, its border keeps the focus colour, also under the pointer.
   This is the layer's `VeyqTagsInput` style, so check the participant and Pot pickers, which share it; they should get
   the same rule.

## Context

- **Where the code lives:** `src/web/src/core/search/SearchField.vue` (`openOnFocus`, the status slot, the loading
  state from `searchState`), `src/web/src/shared/ui/VeyqTagsInput.vue` (`openOnFocus`, `v-model:open`, its styles) and
  `src/web/src/shared/ui/layer.css` / `src/web/src/styles/ui.css` (find the hover and focus rules with `rg -n "veyq-tags-input" src/web/src/shared/ui/layer.css src/web/src/styles`).
- **Tests to rewrite:** the tests asserting that focus opens the list or shows the hint. Rewrite them, do not delete
  them silently, and say per test what it now asserts (`rg -n "hint|openOnFocus|focus" src/web/src/core/search/SearchField.test.ts src/web/src/shared/ui/VeyqTagsInput.test.ts`).
- **Lane L1:** `tests/e2e/tests/features/platform/search.spec.ts` may rely on the list opening on focus. Re-read it
  and adjust it if needed; run its check.

## Phases

Single phase (web).

- **Build:** the three items.
- **Tests:**
  - A focus with no text renders no list. A first character opens it.
  - **The loading state, with fake timers:** at 200 ms of loading after a keystroke nothing is shown; at 400 ms the
    state is shown; a load done at 250 ms never showed it.
  - The empty result state still names what is searched.
  - The focus border under hover, as a computed style if happy-dom resolves it. Otherwise prove it in the browser
    pass and say so.
- **Bites:**
  - Open on focus again, and the focus test goes red.
  - Drop the delay, and the 200 ms test goes red.
- **Browser pass:** re-run `p17-focus-probe.mjs` (adapt it) and show the list-state table after the fix: no state
  before the first character, and no flicker. Desktop, one final screenshot.
- **Docs:** the Use page `spaces/search` in EN+DE, if it describes the focus behaviour; coverage `chips-and-text` or a
  fitting criterion.
- **Done when:** `pnpm run check` is green in `src/web/`, `tests/e2e`'s check is green if a spec changed, and the
  trust-chain guards are green (`/usr/bin/python3`, guard_gaps skipped).

## Retrospective

(filled by the implementer)
