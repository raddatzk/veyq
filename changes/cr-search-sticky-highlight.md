---
id: cr-search-sticky-highlight
title: The search list's highlight is Reka's first entry, and stays on an entry the member moved to while it is still listed
status: done
satisfies: [uc.platform.search]
adrs: ["0125"]
invariants:
  - only Reka's public API moves the highlight (`highlightItem`); no synthetic pointer event, no write into Reka's internals
  - fields without result groups (the participant and the Pot member pickers) behave as before
  - no backend and no contract change
---

## Goal

The owner's rule (2026-11-09) replaces `cr-search-suggestions-and-keyboard`'s "the first result is highlighted, a
suggestion never on its own". [uc.platform.search](../docs/features/platform/use-cases/uc.platform.search.md) step 4
states it:

1. **Default:** the first entry of the list is highlighted, whether it is a suggestion or a result. That is Reka's own
   behaviour, and **Enter** takes the highlighted entry. Typing "kevin" therefore highlights the person suggestion
   first, and Enter makes the chip.
2. **Sticky:** once the member moves the highlight (arrow keys, Home/End, pointer), it stays on that entry while they
   type on, as long as the entry is still listed. When the entry drops out, the first entry is highlighted again, and
   the remembered entry is forgotten.
   - **The owner's example:** with "Ken" and "Kevin", typing "K" highlights Ken; ↓ moves to Kevin; typing "e" keeps
     Kevin.

## Context (substrate: branch `worktree-search-design` at `0452d737f`)

- **What Reka does today** (measured 2026-11-09 with a throwaway vitest probe against reka-ui 2.11.0, happy-dom; the
  probe is deleted): with `ignore-filter`, "K" highlights Ken, ↓ moves to Kevin, and "Ke" puts the highlight **back
  on Ken**. Reka does not keep a moved highlight, so we restore it with `highlightItem(key)` after the list re-renders.
- **The field:** `src/web/src/shared/ui/VeyqTagsInput.vue`. Today it calls `highlightItem(first result)` (the "first
  result" rule), clears the highlight with a synthetic `pointerleave` on the listbox root when only suggestions
  exist, and carries a `moved` flag (`@pointermove.capture`). All three are the previous rule's and go or change. Its
  header comment describes the old rule; rewrite it.
- **Readers of the old rule:**
  - `rg -n "pointerleave|highlightItem|moved" src/web/src/shared/ui src/web/src/core/search`
  - the tests that mount the field: `rg -ln "SearchField|AppTopbar|VeyqTagsInput" src/web/src --glob '*.test.ts'`

  Several tests assert the old rule (the bite pair "Enter opens the first result and adds no chip"). Rewrite them to
  the new rule; do not delete them silently. Say per test what it now asserts.
- **The Use page:** `site/src/content/docs/spaces/search.mdx` and `de/spaces/search.mdx` describe the keyboard. Their
  sentence follows the new rule.
- **Coverage:** `uc.platform.search.yaml`'s `chips-and-text` names the old highlight. Its text and refs follow.

## Design

- **What the field remembers:** the key of the entry the member moved to (`stickyKey`).
  - It is set on keyboard navigation (ArrowUp/ArrowDown/Home/End, read in the existing `keydown` capture) and on a
    pointer move onto an item (Reka's highlight change after a real `pointermove`).
  - It is cleared when its entry is no longer among the rendered keys, when a chip is added or a result chosen, and
    when the list closes.
- **After every change of the rendered entries:** if `stickyKey` is still listed, call `highlightItem(stickyKey)` once
  Reka has settled (after its own first-item highlight, `nextTick`). Otherwise leave Reka's default alone.
- **A field without result groups** never sets `stickyKey`, so it keeps Reka's behaviour unchanged. Whether the sticky
  rule should hold there too is an owner question. Default: no, keep them unchanged.

## Phases

Single phase.

- **Build:** the design above; remove the "first result" logic and the synthetic `pointerleave`.
- **Tests:**
  - "K" highlights the first entry; ↓ then "e" keeps Kevin. Control: with sticky disabled (bite), "e" puts the highlight
    on Ken. Reka's own behaviour, measured above, is the control's evidence.
  - Kevin drops out (typing "Ken" after moving to Kevin), and the first entry is highlighted.
  - A pointer move onto an entry is remembered like ↓.
  - Enter takes the highlighted entry: a suggestion makes a chip, a result opens it.
  - After a chip is added, nothing is remembered.
  - The other pickers are unchanged (their existing tests stay green).
- **Bites:**
  - Remove the restore call: the sticky test is red.
  - Never clear `stickyKey` on drop-out: the drop-out test is red.

  Snapshot and restore; never `git checkout`.
- **Browser pass on the `veyq-search` stack** (start it as `cr-search-across-spaces` § Retrospective says):
  - the owner's example with two names that share a prefix (create a second member or two Memories, e.g. "Kenia" and
    "Kevins Geburtstag", if the stack lacks them);
  - Enter on a first suggestion makes the chip.

  Desktop only, final screenshots only.
- **Docs:** the Use page sentence (EN+DE), the coverage criterion, and this CR's retrospective. `cr-search-suggestions-and-keyboard`'s
  retrospective gets one line saying that its highlight rule was replaced by this CR.
- **Done when:** `pnpm run check` is green in `src/web/`, the core-import guard is green, and the trust-chain guards are
  green (`/usr/bin/python3`, guard_gaps skipped).

## Amendment (owner, 2026-11-09, while phase 1 ran) — replaces the drop-out rule above

1. **Drop-out:** when the remembered entry is no longer listed, highlight the **first entry of its group**, the group
   it was listed in (a suggestion's group is the suggestions). When that group is gone or empty, highlight the first
   entry of the list.
2. **Restore:** the remembered entry is NOT forgotten when it drops out.
   - Whenever it is listed again, it is highlighted again, whatever input brought it back (Backspace is the common
     case). The owner's analogy is Ctrl+Z.
   - The group fallback of point 1 is no move by the member, so it never replaces the memory.
   - **What does forget it:** a new move (it replaces the memory), choosing an entry (chip or result), and the list
     closing.
3. **Tests added to the phase:**
   - "Kev" then ↓ to Kevin, type "x" (Kevin drops), then the first entry of Kevin's group is highlighted; Backspace
     brings Kevin back highlighted.
   - The group fallback: the group is gone, and the first entry of the list is highlighted.
   - A move after a drop-out replaces the memory.
   - **Bites:**
     - Forget the key on drop-out: the restore test is red.
     - Fall back to the list's first entry instead of the group's: the group test is red.
4. **The Use page sentence** follows the use case's step 4, which already states this.

## Retrospective

Implemented 2026-11-09 in the worktree `search-design`, one phase. **The drop-out rule changed mid-phase:** the owner's
amendment above arrived after the first rule (forget on drop-out, the list's first entry) was built, tested and bitten;
the field, its tests, the bites and the browser pass were redone to the amendment, and only the amended state is
committed. `pnpm run check` green in `src/web/` (measured: `pnpm run check`, 539 test files, 6507 tests); every
`scripts/validate_*.py` but `validate_guard_gaps.py` green with `/usr/bin/python3` (no validator changed), the core-import
guard among them.

**Technical choices made (owner away; recorded here, as asked).**
- **Who moved is known, not inferred.** A navigation key sets a flag before Reka's handler (`keydown` capture) and clears
  it after (`keydown` bubble); Reka moves the highlight synchronously inside that handler (`ListboxRoot.onKeydownNavigation`
  → `changeHighlight` → `highlight` event, read in reka-ui 2.11.0's `dist/`), so the `highlight` event in between is the
  member's. A pointer move reads the entry under the pointer from `data-option-key` (capture on the list, before the
  item's own handler) - a flag consumed by the next `highlight` would have mistaken Reka's later first-entry highlight for
  a move when the pointer rested on an already highlighted row.
- **One answer for "where the highlight belongs"** (`wanted()`): the remembered entry while listed, else the first entry
  of the group it was in when the member moved to it (the suggestions count as a group), else the list's first. It is
  applied on every Reka `highlight` that is not the member's, after each change of the listed entries (`nextTick`, after
  Reka's own first-entry highlight) and when the pointer leaves the list - only through `highlightItem`. The old
  synthetic `pointerleave` and the "first result" logic are gone.
- **Where Reka leaves nothing highlighted** (its highlighted row went with a chip; the text cleared by the field, not an
  input, so Reka does not re-highlight; the pointer left the list) and nothing is remembered, the field highlights the
  first entry - the rule's default, which Reka alone would not give there.
- **What forgets:** a new move (replaces), choosing a suggestion or a result, the list closing - nothing else. A chip
  removed by Backspace keeps the memory (the amendment names no chip removal); the first build forgot on every chip change.
- **Without `groups`** nothing is remembered or restored; a new test pins Reka's own behaviour there (↓, then a changed
  text puts the highlight back on the first suggestion), which is also the control for the sticky tests.

**Tests rewritten** (no test deleted): `VeyqTagsInput.test.ts` - the one "first result" test became eight (`with result
groups`): the first entry is highlighted, a suggestion; ↓ then typing on keeps Kevin; a dropped entry falls back to its
group's first (Kenia, not the list's Ken) and is highlighted again once listed; the group gone → the list's first; a move
after a drop-out replaces the memory; the pointer is remembered like ↓; Enter takes the highlight (a suggestion → chip, a
result → chosen); nothing is remembered after a chip. `SearchField.test.ts` - `moveOntoSuggestion` became
`expectSuggestionHighlighted` (the year, person and kind chips are now taken by Enter on the first entry); the bite pair
now asserts "kevin"/"anna" + Enter makes the person chip, control ↓ + Enter opens the profile; "no suggestion by itself"
became "the first suggestion is highlighted and Enter makes it a chip"; the person test moves ↓ to the person result
before Enter; new: the owner's example with two members (K → ↓ Kevin → "Ke", which adds chip suggestions above, keeps it).

**Bites** (snapshot-and-restore, `scratchpad/p11_bites.py`): B1 no restore - 5 red (the sticky ↓, pointer, drop-out,
move-replaces and the field's two-members test); B2 the key forgotten on drop-out - the drop-out/restore test red;
B3 the fallback the list's first, not the group's - the drop-out test and the move-replaces test red. The superseded
first-rule bites (no restore; never cleared on drop-out) were red as designed before the amendment.

**Browser pass** (`veyq-search` stack, desktop, German UI; seeded here: the Memories *Kenia* and *Kevins Geburtstag* in
*Reisen*): 17 checks PASS (`search-pass/p11-pass.mjs`) - "K" highlights the first entry, ↓ to *Kevins Geburtstag*, "e"
keeps it (also with the pointer moved away); ↑ to *Kenia*, "v" drops it and the first entry of its group (*Kevins
Geburtstag*) is highlighted, not the list's first (the person suggestion); Backspace brings *Kenia* back highlighted;
a pointer move is kept and Enter opens it; "kevin" highlights the person suggestion first and Enter makes the chip, after
which the first entry is highlighted. Screenshots `p11-desktop-sticky`, `p11-desktop-dropout`, `p11-desktop-kevin-chip`
in the session scratchpad's `search-pass/`.

**Owner questions (each with the conservative default built).**
1. Should the sticky rule hold in the people pickers (participants, Pot members) too? Built: no, unchanged.
   Recommendation: no - a picker's list is short and its first entry is what Enter should add.
2. A chip removed with Backspace keeps the remembered entry. Recommendation: keep (it is the Ctrl+Z reading of the
   amendment: the member did not choose anything new).
3. Delete this CR once read; its binding lessons are in `VeyqTagsInput.vue`'s header and the coverage file.

**Next time:** a rule the owner is still shaping is cheaper to build after one probe of its edge cases (drop-out, return)
than after the full test-and-bite round; ask the drop-out question while the CR is planned.
