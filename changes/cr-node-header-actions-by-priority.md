---
id: cr-node-header-actions-by-priority
title: The node header shows its actions by priority, as many as fit, the rest in one menu; the visual stays beside a two-line name
status: planned
adrs: ["0110", "0079", "0107", "0068"]
invariants:
  - no action is lost — every shown action is a button or a menu entry, at every width; a disabled action is a disabled entry
  - delete and report are never buttons
  - core imports no application; each kind's order is supplied by the page that draws the header (validate_web_core_imports_no_applications.py)
  - share view = member view (#401) — a guest gets this very header with fewer actions, never a guest-only one
  - tokens only, so the dark theme needs no rule of its own beyond the icon tile's
---

## Goal

Inbox #529, [ADR 0110](../docs/decisions/0110-a-node-header-shows-its-actions-by-priority-as-many-as-fit.md): core's
`NodeHeader` stops showing every action as a button at every width. The leading visual stays beside a name of at most
two lines; the actions get a row of their own; as many as fit are buttons in the kind's priority order, the rest sit in
one ⋯ menu; delete and report are always menu entries. Read the ADR before each phase — its decisions 1–7 are this
work order's rules.

## Context & constraints

### Measured substrate (worktree `cover-button-phone-width-da4c46` at `4842eaa4` = `main`, clean)

- `NodeHeader.vue` renders `mergeHeaderActions(coreActions, actions).filter(shown)` as `VeyqTooltip` + icon-only
  `VeyqButton size=small` with `data-action`, `aria-label`, `aria-pressed`; `NodeHeaderAction` has no priority and no
  menu field (`src/web/src/core/node/nodeHeader.ts`). Core actions come from builders that set `placement`
  (`favoriteHeaderActions` before; `cover`, `download`, `report` after; `copy` `{ after: 'move' }`).
- Today's orders (merged): Memory — favorite, upload, share, shareUpload, participants, banner, rename, move, copy,
  delete, cover, download, report; folder (`LevelView`) — favorite, share, rename, move, copy, delete, cover, download,
  report; file (`FileDetail`) — favorite, download, replace, share, rename, move, copy, delete, report; drop box
  (`DropBoxDetail`) — favorite, share, move, copy, delete, cover, download, report.
- The level rows' split (`levelRow.ts` `splitRowActions`, `MAX_FIRST_ACTIONS = 2`) and their menu mapping
  (`LevelRowItem.vue`: label, icon, disabled incl. `loading`, class `veyq-row-menu-item veyq-row-action-<id>` +
  `is-danger`, no separators) are the model for the entries. `VeyqMenu` (`shared/ui/menu.ts`) has `separator`, `class`,
  `disabled`; danger is a class.
- No shared width helper exists (`AppSidebar.vue` says so); `AppSidebar` packs group links greedily with a reserved
  overflow width, untested. `MediaGrid` keeps its geometry a pure function with its own test and stubs
  `ResizeObserver` in its component test — the pattern to copy.
- `@media (max-width: 540px)` stacks `.veyq-node-header.has-visual` and (since #525) stretches its leading box;
  `nodeHeaderStyle.test.ts` pins both. The Memory cover is `.memory-cover-preview` (180 px; `width: 100%;
  max-width: 260px` under `@container memory-detail-surface (max-width: 460px)`, `MemoryDetail.vue`).
- The favorite's label already names its state (`node.favorite` / `node.unfavorite`), so it reads right as a menu
  entry. `node.rowMore` ("More actions for “{name}”") is the rows' ⋯ label.
- e2e (Desktop Chrome, `tests/e2e/playwright.config.ts`) clicks header actions by role and name, never by
  `data-action`; the locator below found no e2e click on a header's delete or report (reports go through the
  selection bar).

## Decisions this CR takes

1. **The priority travels as an id list, not as a field on each action.** `NodeHeader` gets `order?: readonly string[]`:
   the shown actions whose ids are listed come first, in that order; every shown action not listed follows in today's
   merge order — so a core action added later appears, never vanishes. A rank on each action was rejected: core's
   builders cannot know a kind's order, and the kind's page is the one place that does.
2. **Menu-only is a property of the action.** `NodeHeaderAction` gains `menuOnly?: boolean`; `severity: 'danger'`
   implies it, the report builder sets it. Menu-only entries are the menu's last group, behind a separator, delete last.
3. **The fit is a pure function** beside the model (`fitHeaderActions` or similar in `core/node/nodeHeader.ts`): given
   the number of actions that may be buttons, the button width, the gap, the ⋯ width, the row's width and whether a
   menu-only action exists, it returns how many are buttons. Everything fits and nothing is menu-only → no ⋯ at all;
   otherwise the ⋯ is reserved first. Header buttons are icon-only and one size, so one measured button width stands
   for all — a test pins that `NodeHeader` renders no text label into a button, so the premise cannot drift unseen.
4. **Measuring.** The row's width is read in `onMounted` (before the first paint, so no reflow flash) and on every
   `ResizeObserver` callback, coalesced to one per frame, disconnected on unmount. **Unmeasured** — no
   `ResizeObserver`, or a width of 0 (jsdom; a hidden tab) — means every action that may be a button is one; so unit
   tests keep their buttons, and only menu-only actions move into the menu there.
5. **One mapping from an action to a menu entry**, shared by `LevelRowItem` and `NodeHeader` (label, icon, disabled,
   danger class, command), extracted to core next to the row model. The header's entries carry
   `veyq-node-header-menu-item veyq-node-header-action-<id>`; the ⋯ button `veyq-node-header-more` with
   `aria-haspopup="menu"` and a label of the rows' kind ("More actions for “{name}”").
6. **Layout.** The leading visual and the title share the first row at every width; no stacking. ≤540 px: the
   actions get a row of their own spanning the header's full width under them, then subtitle and meta. Wider: the
   actions stay where they are today, at the right end of the title row (owner 2026-10-23, ADR 0110 decision 3) — the
   title keeps its `flex-basis` minimum (12rem today) and the fit's available width is the title row's width minus
   that minimum and the gap, so a long name never squeezes below it and the buttons take the rest. The title is
   clamped to two lines (`line-clamp`) at every width and carries the full name as its `title`; the DOM keeps the
   whole name, so a screen reader reads all of it.
7. **The Memory's cover on a phone** is a 64 × 36 px tile (Memories' rule under its existing container query, replacing
   `width: 100%; max-width: 260px`); its hint over an empty cover shows the icon alone at that size, the words stay its
   accessible name and tooltip. #525's stretch rule and its test case go — there is no stacked header left.
8. **The orders.** Memory (owner, 2026-10-23): favorite, upload, share, shareUpload, participants, download, cover,
   banner, rename, move, copy. Defaults by the same reading (ADR 0110 decision 5), shown to the owner with this CR on
   2026-10-23 and not stopped:
   folder — favorite, share, download, cover, rename, move, copy; file — favorite, download, share, replace, rename,
   move, copy; drop box — favorite, share, download, cover, move, copy. Report and delete close every menu.
9. **A primary action** (Memory's upload, file's download) keeps its filled look as a button and is a plain entry in
   the menu.

## Out of scope

- The rows' fixed two-`first` split (ADR 0110 § Consequences keeps it).
- `AppSidebar`'s own greedy packer — a second width-fit; converging the two is a follow-up row if the owner wants it.
- The short description under the name (#528).

## Inventory — run each locator at implement time, read its output as a list

| What | Locator |
|---|---|
| Pages that draw the header (each supplies its `order`) | `git grep -n "<NodeHeader" -- src/web/src` |
| Core header-action builders (report gets `menuOnly`) | `git grep -nE "HeaderActions\(" -- src/web/src/core` |
| Unit tests that reach header actions | `git grep -nE "veyq-node-header-action\|node-header-actions\|veyq-node-header button" -- src/web/src` |
| Unit tests that click a header delete or report (now a menu entry; open the ⋯ with `pointerdown`, query `document`) | `git grep -nE "aria-label=\"?(Delete\|Report\|Löschen\|Melden)\|data-action=\"?(delete\|report)" -- src/web/src` — read each hit for whether it is a header's |
| Golden DOM snapshots of a header (re-bless with `-u`, read the diff: buttons drop delete/report, a ⋯ appears) | `git grep -l "veyq-node-header" -- 'src/web/src/**/__snapshots__/**'` |
| The rows' menu mapping to extract | `git grep -n "veyq-row-menu-item" -- src/web/src` |
| e2e clicks on a header action that may now be a menu entry | `git grep -nE "getByRole\('button', \{ name: '(Report\|Delete\|Melden\|Löschen)" -- tests/e2e`, and every spec whose viewport is narrowed (`git grep -nE "setViewportSize\|viewport:" -- tests/e2e`) |
| The rule being retired | `git grep -nE "every width\|EVERY width" -- src/web docs` |
| Docs that describe the header | `git grep -nE "NodeHeader\|node header\|action bar\|actions on the right" -- docs src/web/CLAUDE.md ':!docs/inbox.md'` |
| Site pages that send a reader to "a button next to the name" (EN + DE) | `git grep -nE "next to (its\|the) name\|neben (seinem\|ihrem\|dem) Namen\|buttons? (at the top\|next to)\|oben .*Schaltfl" -- site/src/content/docs` — read each hit: many are about other screens (a group, a space) |

## Phases

### Phase 1 — core and the four callers

The model (`order`, `menuOnly`), the fit function, the shared menu-entry mapping, `NodeHeader`'s render (buttons, ⋯,
menu with its separator), the measuring, the grid layout and the title clamp in `ui.css`, the report builder's
`menuOnly`, each caller's `order`, the Memory cover's phone size, #525's rule out. Tests:
- the fit function's table: everything fits (no ⋯), one too many (⋯ reserved, one fewer), zero room (⋯ alone), a
  menu-only action forces the ⋯ even when the rest fit;
- `NodeHeader`: `order` puts listed ids first and keeps unlisted ones (a core action not in the list still renders);
  delete and report are menu entries, last, behind a separator, delete last, never `button[data-action]`; unmeasured
  renders every other action as a button; a button carries no text label; a disabled action is a disabled entry;
- each caller's order, asserted on its rendered buttons (unmeasured) — one test per kind;
- `nodeHeaderStyle.test.ts` rewritten to pin the new rules: no `flex-direction: column` for `.has-visual`, the title's
  two-line clamp; and bite each new rule once (remove it, see red, restore by `cat backup > file`).

**Done when:** `npm run check` in `src/web/` green; `validate_web_core_imports_no_applications.py` green; a browser pass
in a **visible** pane (a hidden one never fires `ResizeObserver`) at 375 px and at 1280 px, light and dark, of a Memory
with a cover and one without, one with a name long enough to clamp, a folder, a file and a drop box, and one guest
share page: the cover stays beside the name, the name ends in "…" at two lines with the full name as tooltip, the
buttons follow the order and stop where the row ends, the ⋯ holds the rest with report and delete last, and resizing
the window moves actions between row and menu without a jump. Screenshots at 375 px and 1280 px are the proof.

### Phase 2 — the texts and the specs

The rule retired where it is stated (`ui.css`'s header comment, `src/web/CLAUDE.md` § One header for every node, the
styleguide's node-header bullet), the docs that describe the header (locator above; `docs/apps/depot.md`,
`uc.memory.participants`, `uc.moderation.report`, ADR 0102 and 0068 name the header's buttons), the site's Use pages
that point at a header button (EN and DE together: "… or in the ⋯ menu"), and the e2e specs the locator found. ADR
0110's status loses its "until it lands" clause.

**Done when:** every `scripts/validate_*.py` except `validate_guard_gaps.py` green; `npm run build` in `site/` green; the
e2e specs are updated but **run in the heavy stage at the end of V1** (owner 2026-10-19), which the inbox row names as
the proof still owed.
