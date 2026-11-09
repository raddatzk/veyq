---
id: cr-search-sticky-highlight
title: The search list's highlight is Reka's first entry, and stays on an entry the member moved to while it is still listed
status: planned
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

## Retrospective

(filled by the implementer)
