# ADR 0110 — A node header shows its actions by priority, as many as fit, and the rest in one menu

- Status: Accepted (owner design 2026-10-23, inbox #529), built by `changes/cr-node-header-actions-by-priority.md`; until
  it lands, what § Context measured holds.
- Date: 2026-10-23

## Context

Every node page and every folder level opens with core's `NodeHeader` (`src/web/src/core/node/components/NodeHeader.vue`,
inbox #406): a leading visual, the name, and the actions. Its rule today is that **every action is a button at every
width** — "a width-hidden button is a lost action" (`src/web/src/styles/ui.css` § node header, `src/web/CLAUDE.md` § One
header for every node): the title row wraps, so the buttons take as many rows as they need. A Memory carries 13 actions
(favorite, upload, share, upload request, participants, banner, rename, move, copy, delete, cover, download, report), a
folder 9, a file 9, a drop box 8. At ≤540 px a header with a leading visual stacks it above the name, and a Memory's cover
then grows to the header's width (up to 260 px, #525).

The owner judged the result on a 375 px phone (2026-10-23, from #525's browser pass): a large cover, then the name, then
two rows of seven and six equal buttons before the content starts. From mockups the owner chose a small cover beside a
name of at most two lines, the actions in a row of their own, the important ones as buttons and the rest in one "more"
menu — and then: *prioritise the actions, and show as many as fit*.

The level list already splits a row's actions (#407, `src/web/src/core/node/levelRow.ts`): at most two marked `first` are
buttons, every other shown action sits in one `VeyqMenu`. That split is fixed, not measured, because a list needs the
same columns in every row.

## Decision

1. **The leading visual stays beside the name at every width; the header never stacks.** On a phone a Memory's cover
   is a small 16:9 tile (64 × 36 px); on a wide screen the sizes of today stay (the Memory's 180 px cover, a folder's
   5.5 rem cover, the 42 px icon tile).
2. **The name shows at most two lines**, then an ellipsis, at every width; the full name is the title's tooltip. A
   longer text belongs in a description under the name (#528), not in the name.
3. **The actions have a row of their own** under the name. On a phone the row spans the header's full width below the
   cover and the name; on a wide screen it sits in the column beside the visual, under the name. *(The wide-screen
   placement is a default shown to the owner on 2026-10-23, not yet confirmed.)*
4. **As many actions as fit are buttons, in priority order; the rest are entries of one "more" menu (⋯) at the row's
   end.** The fit is measured from the row's width and measured again when that width changes, at every width — no
   breakpoint decides it.
5. **The priority is the order of the node kind's list**, which the page that draws the header supplies, core's actions
   (favorite, cover, copy, download, report) included by their ids. A Memory's order is the owner's: favorite, upload,
   share, upload request, participants, download, cover, banner, rename, move, copy. A folder's, a file's and a drop
   box's follow the same reading — favorite first, then what the kind is for, then sharing, then editing the node itself,
   then moving and copying it.
6. **Delete and report are never buttons.** They are always the last entries of the menu, behind a separator, delete
   last: a button that moves with the width is too easy to hit by mistake, and both open a consequential dialog.
7. **No action is lost.** An action that does not fit is an entry of the menu with its label and icon; a disabled
   action is a disabled entry. Every entry is reachable by keyboard (the layer's menu).

## Consequences

- The header and the level rows split their actions by two rules: the header by its measured width, a row by its fixed
  two `first` actions. They share the menu's entries (label, icon, disabled, danger) and nothing else.
- On a wide screen a Memory is expected to show all eleven of its other actions as buttons, the menu holding report
  and delete only (an expectation from the button size, measured by the CR's browser pass, not yet a measurement).
- The rule "every action is visible at every width" is retired in `ui.css`, `src/web/CLAUDE.md` and the styleguide; the
  published pages that say an action is "a button next to the name" learn that it may sit in the ⋯ menu.
- #525's rule (a stacked header's leading box spans the header) has no stacked header left to serve and goes.
- A unit test has no layout, so the measurement is held at its pure half (the count that fits a given width) and proven
  in a real browser at phone and desktop width.

## Alternatives rejected

- **Every action a button at every width** (the rule of #406): two or three rows of buttons on a phone before any
  content; the owner rejected it on sight.
- **A fixed split, as the rows do** (two buttons, the rest in the menu): a wide screen would hide nine actions behind a
  menu with room to spare; the owner chose "as many as fit".
- **Hiding actions by a media query:** a hidden action is a lost one — the reason #406's rule existed. The menu keeps
  every action reachable.
- **The full name at any length beside the cover** (mockup E4) and **one line with an ellipsis** (E3): the owner judged a
  long name beside a small cover to look bad, and one line hides too much; two lines are the middle (E2).
- **The cover above the name on a phone** (today's stacking): it pushes the content a screen down and is what the owner
  asked to change.
