---
id: cr-search-calm-focus
title: A click into the search opens nothing until the member types; no flickering load state; focus colour wins over hover
status: done
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

Implemented 2026-11-09 in the worktree `search-design`, one phase (web). No backend, contract or e2e change.

### What was built, and the choices made (technical choices are mine)

- **One gate decides whether the list is open: something is asked** (text or a chip). `SearchField.vue` passes no
  `open-on-focus` any more, and a watch on `[open, asked]` closes the list whenever it is open with nothing asked. So
  whatever opened it - Reka's combobox opens on any input and on ↓/↑ - the list stays shut on a focus, on ↓ in an empty
  field and after Backspace to an empty field with no chip; with a chip left it stays open. The gate, not the missing
  prop, is the mechanism: bite B1a (prop back, gate kept) stays green.
- **`openOnFocus` left the layer** (`VeyqTagsInput.vue`): the search was its only user (`rg open-on-focus` before the
  change), so the prop was dead. The header says the combobox never opens on focus.
- **The loading line waits 300 ms (`LOADING_DELAY_MS`) counted from the moment the list is open AND a space is loading**,
  not from the load's start: a load that began on the focus and ends 50 ms after the first keystroke never shows, which
  the load's own start would not guarantee. No minimum display time once shown: a line kept after the load ended would
  say something false.
- **No panel while there is nothing to say.** The status slot is passed only when it has words (`statusSaid`), so the
  layer's `hide-when-empty` hides the list while it holds no entry - a typed query whose answer is still loading under
  300 ms shows nothing rather than an empty box.
- **The hint** ("Searches names, dates, ...") shows only with *nothing found* (uc A2), as before only after the loading
  ended.
- **Focus over hover** (`layer.css`): the hover rule reads `:not([data-disabled]):not([data-focused]):hover`. It was
  `(0,3,0)` against the focus rule's `(0,2,0)`, so the hover won. The rule is the layer's, so the participant and Pot
  pickers have it too.

### Tests rewritten (none deleted) - `SearchField.test.ts`

- "asks for the index on focus and says what it searches" → **asks for the index on focus and opens nothing; the first
  typed character opens the list**: after the focus `aria-expanded` is false, no panel, no status; ↓ opens nothing; `r`
  opens it with its result (control).
- "names the spaces it is still reading and those it could not search" → the not-searched note at once, the loading line
  only after 300 ms of the open list waiting (fake `setTimeout`: none at 1000 ms of focus alone, none at 200 ms after
  typing, there at 400 ms).
- "names what it searches: members and settings too" → the same words, now only after a query found nothing (nothing
  before typing).
- New: Backspace to empty closes; a chip keeps the list open through an emptied text, and removing it closes; at 200 ms
  of loading no status and no panel, at 400 ms the line; a load done at 250 ms never showed the line (polled every 50 ms
  to 1 s), and the empty state follows it (control).
- "says it found nothing, and what it searched" is unchanged and green: the empty state still names what is searched.

### Bites (`scratchpad/calm-bites.py`: a mutated copy beside the file plus a copy of its test, so the owner's live Vite
never reloads a mutant; the real file byte-equal afterwards, the copies removed)

- B1a `open-on-focus` back, gate kept: green (54 passed) - the gate is the mechanism.
- B1b `open-on-focus` back, gate gone: 2 red (the focus test, the Backspace test).
- B1c gate gone: the same 2 red.
- B2 `LOADING_DELAY_MS = 0`: 3 red (the three loading tests, the 200 ms one among them).
- B3 the loading line without the timer: the same 3 red.

### Browser pass (`veyq-search` stack, desktop, the owner's running backend 8180 and Vite 8189)

`search-pass/p18-calm-focus.mjs` (the p17 probe adapted, sampled per frame):

| action | ms after it | panel | rows | height | status |
|---|---|---|---|---|---|
| A: a click on a fresh page, nothing typed (3 s) | 0-3000 | none | 0 | 0 | "" |
| B: "r" typed 100 ms after the click, first index build | 0 | none | 0 | 0 | "" |
| | 49 | shown | 6 | 356 px | "" |
| | 76 | shown | 11 | 512 px | "" |
| C: Backspace to empty | 19 | none | 0 | 0 | "" |

The border: focused under the pointer `rgb(37, 99, 235)` = `--app-focus`; control, unfocused under the pointer
`rgb(102, 112, 133)` = `--app-muted`. No console error. Screenshot `search-pass/p18-desktop-focus-hover-typed.png`.

### Docs

- Coverage: new criterion `a-calm-focus` (`SearchField.test.ts`; the border by the browser pass, happy-dom resolves no
  hover).
- The Use page *Search all your spaces* / *Alle deine Bereiche durchsuchen*: the list opens with the first letter, an
  empty search names what is searched, a space still read "after a moment".

### Owner questions (each with the conservative default built)

1. **Results fill in during the first index build.** A first letter typed ~100 ms after the first click showed 6 rows,
   then 11 rows 27 ms later (B above): the results of the spaces still loading arrive. Built: shown as they come - the
   CR names the loading line, and holding results back would slow the first keystroke and hide results on every 20 s
   revalidation (it marks every space loading). Recommendation: keep.
2. **With chips in the field, a click into it opens nothing either**; a letter or ↓ opens the list. Recommendation: keep
   (one rule: focus never opens).
3. Delete this CR once read; its binding lessons are in `SearchField.vue`'s header, `VeyqTagsInput.vue`'s header, the
   `layer.css` comment and the coverage criterion.

### Process observation

- A bite that edits a file the owner's live Vite serves would hot-reload each mutant into the owner's page; a mutated
  copy beside the file, with a copy of its test, bites the same code and leaves the served module untouched.
