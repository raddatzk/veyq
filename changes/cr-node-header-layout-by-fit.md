---
id: cr-node-header-layout-by-fit
title: The node header picks its layout by its own width; on a wide header the name comes first, down to a two-action floor
status: planned
adrs: ["0110", "0107", "0079"]
invariants:
  - the layout answer depends only on the header's width and fixed sizes — never on the name's text or the layout in force (it cannot flip)
  - no action is lost — every shown action is a button or a menu entry in both layouts; delete and report are never buttons
  - core imports no application; a kind's wide leading size reaches core as data, not as an import (validate_web_core_imports_no_applications.py)
  - share view = member view (#401) — a guest gets this very header, its floor its own first two actions
  - tokens only, so the dark theme needs no rule of its own beyond the icon tile's
---

## Goal

Inbox #531, [ADR 0110](../docs/decisions/0110-a-node-header-shows-its-actions-by-priority-as-many-as-fit.md) decisions
1–3 and 8: core's `NodeHeader` stops choosing its layout by a 540 px viewport query (and the Memory's cover by its own
460 px container query). One answer, from the header's own width, picks **compact** (small cover, the name in two lines,
the actions in a row of their own) or **wide** (today's cover sizes, the name in ONE line and the actions in the same
row). On a wide header the name takes its natural width and the actions yield to the ⋯ menu down to a floor of the
kind's first two shown actions; only then is the name shortened with an ellipsis. Read the ADR before each phase.

## Context & constraints

- Built on `cr-node-header-actions-by-priority` (merged `37a52fc7`, `a4c28b1c`); the owner's decisions of 2026-10-23 are
  quoted in ADR 0110 § Context, and their two open choices were answered the same day: the floor is **each kind's first
  two shown actions**, the name's wide minimum stays **12rem**.

### Measured substrate (worktree `node-header-actions-by-priority-b6bad7` at `b934ffab`, header code = `37a52fc7`)

- `NodeHeader.vue` `measure()` reads the room for the buttons: the actions' own `clientWidth` when the title row has
  `display: contents` (the ≤540 px grid), else the title row's width less the title's computed `flex-basis` (12rem) and
  the row's `column-gap`; one `offsetWidth` per button (41 px), the actions' gap (7.2 px), the ⋯ (41 px). A
  `ResizeObserver` on the header re-measures once per frame. `fitHeaderActions` (`core/node/nodeHeader.ts`) is the pure
  count. Layout widths, not `getBoundingClientRect`: suites stub the latter globally (a comment at `measure()` says why).
- `styles/ui.css` § node header: flex row (leading, body), title `flex: 1 1 12rem`, `-webkit-line-clamp: 2` at every
  width; `@media (max-width: 540px)` turns the header into a grid (`.has-leading` two columns, body and title row
  `display: contents`, actions and the body's other children `grid-column: 1 / -1`), title 1.5rem for `.has-visual`.
  `nodeHeaderStyle.test.ts` pins these rules.
- Leading sizes: the icon tile 42 px (`ui.css`), a folder's and a drop box's cover `.veyq-node-header-cover` 5.5rem
  (`LevelView.vue`, `DropBoxDetail.vue`), the Memory's `.memory-cover-preview` 180 px, 64 px under
  `@container memory-detail-surface (max-width: 460px)` (`MemoryDetail.vue`, its hint shows the icon alone there).
- Gaps: header (leading ↔ body) 1rem, title row 1rem, actions 0.45rem.
- Name widths (Inter, this Mac, 2026-10-23): a Memory's 2rem bold name — "Rom 2026" 161 px, "Sommerurlaub 2025" 321 px,
  12rem = 192 px; the other kinds' 1.15rem name — "Reisen" 59 px, "Notiz.txt" 78 px, "Einreichungen" 129 px, "Omas 80.
  Geburtstag" 190 px.

### Before (measured 2026-10-23 at `37a52fc7`, visible pane) and after (computed from the sizes above)

Buttons shown (Memory 11 that may be buttons, folder and file 7, drop box 6; report and delete always in the ⋯). m =
compact, w = wide. Header width = viewport − 32 px without the sidebar; at 1280 px the sidebar leaves 936 px.

| Viewport (header) | 375 (343) | 540 (508) | 541 (509) | 768 (736) | 1280 (936) |
|---|---|---|---|---|---|
| Memory before | 6 | 9 | **1** | 6 | 10 |
| Memory after, "Rom 2026" | 6 m | 9 m | 9 m | 6 w | 10 w |
| Memory after, a long name | 6 m | 9 m | 9 m | 2 w, name 386 px | 2 w, name 586 px |
| Folder / file before | 6 | 7 | 4 | 7 | 7 |
| Folder / file after | 6 m | 6 w | 6 w | 7 w | 7 w |
| Drop box before | 6 | 6 | 4 | 6 | 6 |
| Drop box after | 6 m | 5 w | 5 w | 6 w | 6 w |

The switch (decision 8): wide while `leadingWide + 16 + 192 + 16 + (2 × 41 + 2 × 7.2 + 41)` fits — a header of 541 px
for a Memory (viewport ≈ 574 px without the sidebar), 403 px for an icon tile (≈ 435 px), 449 px for a folder or drop
box with a cover (≈ 481 px).

## Decisions this CR takes

1. **One pure function decides the layout**, beside `fitHeaderActions` in `core/node/nodeHeader.ts` (e.g.
   `headerLayout`): given the header's width, the wide leading width, the name's minimum, the button width, the gaps and
   the ⋯ width, it answers `wide` or `compact`. Unmeasured (width 0, no `ResizeObserver`) it answers `wide`, so unit
   tests keep the markup they have. It takes no name width and no current layout, by signature.
2. **A kind's wide leading width is one CSS number**, a custom property `--veyq-node-header-leading-wide` that the
   leading visual's own wide rule uses for its width and that core reads from the leading box: core sets it for the
   icon tile (42 px) and `.veyq-node-header-cover` (5.5rem), Memories for `.memory-cover-preview` (180 px). It and
   `--veyq-node-header-name-min` (12rem) are registered with `@property … syntax: '<length>'`, so `getComputedStyle`
   answers in px whatever unit the sheet uses. No prop, no import: core imports no application.
3. **The answer is a class and a slot prop.** `NodeHeader` sets `is-compact` on the header and hands the `leading` slot
   `{ compact }`; every compact rule is keyed to `.veyq-node-header.is-compact` (the 540 px `@media` block goes), the
   Memory's cover sizes itself from `compact` (its 460 px container query goes), core's cover is 64 × 36 px when compact.
   The subtitle, meta and the `.has-visual` title size follow the same class.
4. **The wide row fills by name first.** Wide, the title is one line (`white-space: nowrap; text-overflow: ellipsis`),
   its natural width read from its `scrollWidth` (the text's width, which the button count does not change); the
   buttons get the rest, but never fewer than the floor: `fitHeaderActions` gains `floor` (the count of the kind's first
   two shown actions that may be buttons, at most two) and the room is `titleRow − nameNatural − gap`, clamped so the
   floor always fits; the title's width is what remains. Compact keeps today's fit (the actions' own row) and the
   two-line clamp.
5. **Measuring.** One `ResizeObserver` on the header, once per frame, as today; the layout answer comes first, then the
   fit of that layout's row in the same frame. The header's width is the only input that changes the layout.
6. **Docs.** ADR 0110 is amended (done with this CR's refine); `src/web/CLAUDE.md` § One header for every node, the
   styleguide's node-header bullet and `ui.css`'s header comment say compact/wide by the header's width and the name-first
   wide row; the Use pages say nothing about lines or widths today (the locator below checks), so none moves unless it
   finds one.

## Out of scope

- The level rows' fixed split, `AppSidebar`'s packer, the description under the name (#528).
- The order lists (unchanged).

## Inventory — run each locator at implement time, read its output as a list

| What | Locator |
|---|---|
| The viewport and container queries being retired | `git grep -nE "max-width: 540px\|memory-detail-surface \(max-width: 460px\)" -- src/web/src` |
| Leading visuals that need `--veyq-node-header-leading-wide` | `git grep -nE "veyq-node-header-cover\|memory-cover-preview\|veyq-node-header-icon" -- 'src/web/src/**/*.vue' 'src/web/src/**/*.css'` |
| The style pins to rewrite | `src/web/src/core/node/nodeHeaderStyle.test.ts` |
| Tests that reach the header (helpers stay; layout is unmeasured = wide) | `git grep -ln "test/nodeHeader" -- src/web/src` |
| Docs that state the 540 px rule or the two lines | `git grep -nE "540 ?px\|two lines\|row of their own" -- src/web/CLAUDE.md docs/crosscutting src/web/src/styles ':!docs/inbox.md'` |
| Use pages about the name's lines or the header's width (EN + DE) | `git grep -nE "two lines\|zwei Zeilen\|line(s)? of the name\|gekürzt" -- site/src/content/docs` — read each hit |

## Phases

### Phase 1 — the layout answer, the name-first wide row, the covers

The pure `headerLayout`, `fitHeaderActions`' floor, the registered custom properties and their uses, `is-compact` and the
slot prop, the wide title's one line, the Memory's and core's compact cover, the 540 px and 460 px queries out. Tests:
- `headerLayout`'s table: a Memory, an icon tile and a folder with a cover one pixel below and at their switch width;
  unmeasured is wide; and **the bite**: for every width from 300 to 1400 px the answer flips at most once (a decision that
  reads the leading's current width — 64 px when compact — would flip; inject exactly that and see red), and a component
  test where a short and a very long name at the same measured width get the same layout;
- `fitHeaderActions` with a floor: a long name keeps two buttons, a short one gets all that fit, a guest with one action
  keeps one, a floor never exceeds the actions that may be buttons;
- `NodeHeader`: `is-compact` and the slot prop follow the answer (stub the layout widths as the measured test does);
- `nodeHeaderStyle.test.ts` pins: no `@media (max-width: 540px)` for the header, the compact grid under `.is-compact`, the
  wide title's `nowrap` + `ellipsis`, the compact two-line clamp, the two `@property` registrations; bite each once
  (remove it, see red, restore by `cat backup > file`).

**Done when:** `npm run check` in `src/web/` green; `validate_web_core_imports_no_applications.py` and
`validate_stylesheet_anchors.py` green; a browser pass in a **visible** pane (probe `tabs_context` and an rAF race first)
of a Memory with a cover, a Memory with a **very long name**, a folder with and without a cover, a file, a drop box and one
guest share page, at 375, 540, 541, 768 and 1280 px and one pixel each side of the Memory's (≈ 573/574 px) and the icon
kinds' (≈ 435/436 px) switch, light and dark: the table above's "after" rows hold (each count measured, the switch width
measured), a wide name is one line ending in "…" only once the floor is reached, a compact name two lines, and dragging
the width moves the header between layouts once, with no flicker. Screenshots of the long name at 375, 768 and 1280 px,
light and dark, are the proof.

### Phase 2 — the texts

The locators' doc hits (`src/web/CLAUDE.md`, the styleguide, `ui.css`'s comment), ADR 0110's status loses its "until it
lands" clause, #531 is deleted.

**Done when:** every `scripts/validate_*.py` except `validate_guard_gaps.py` green; `npm run check` in `site/` green if a
page changed; e2e specs moved where needed and run in the heavy stage at the end of V1. Desktop Chrome (1280 px) is wide for every kind,
so a header button a spec clicks stays a button only while the node's name leaves room for it: **Share** is a Memory's
third action and a folder's second, so on a Memory with a long name it moves into the ⋯ — read each spec that clicks a
header button (`git grep -nE "getByRole\('button', \{ name: '(Share|Rename|Move to…|Add banner|Change banner)'" --
tests/e2e`) against the width of the name it gave its node, and open the ⋯ where the name is long.
