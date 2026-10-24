# ADR 0110 — A node header shows its actions by priority, as many as fit, and the rest in one menu

- Status: Accepted (owner design 2026-10-23, inbox #529), built by cr-node-header-actions-by-priority (2026-10-23);
  decisions 1–3 and 8 as they stand here (owner, 2026-10-23, inbox #531) built by cr-node-header-layout-by-fit
  (2026-10-24).
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

The first build placed the actions by a viewport query: a row of their own up to 540 px, the right end of the title row
above it beside a 12rem minimum name, with the name in two lines at every width — and the Memory's cover had its own
460 px container query. Measured 2026-10-23 (#531): a Memory showed 9 of its 11 buttons at 540 px, **1** at 541 px, 6 at
768 px and 10 at 1280 px; a folder and a file 7, 4, 7, 7 at 540, 541, 768 and 1280 px; and between the two queries a
180 px cover sat beside the name in a narrow header. The owner then decided (2026-10-23): *the title comes first and
pushes actions into the menu, but only while favorite and upload stay visible — after that the title is shortened*; *two
lines only on a phone — on a desktop, lines two and three beside the big cover are date, place and participants*; *and
on a desktop the title and the actions are always one row*.

## Decision

1. **Two layouts, and the leading visual stays beside the name in both; the header never stacks.**
   - **Compact** (a phone): a small leading visual — a Memory's or a folder's cover a 16:9 tile of 64 × 36 px, the icon
     tile its 42 px —, the name beside it in **at most two lines**, the actions in a row of their own, the header's full
     width, below them; then subtitle and meta.
   - **Wide** (a desktop): today's sizes (the Memory's 180 px cover, a folder's 5.5 rem cover, the 42 px icon tile), and
     the name and the actions **always one row** — the name **one line**, because the lines beside a big cover belong to
     the subtitle and the meta (a Memory's date, place and participants). The actions never get a row of their own here.
2. **The full name is the title's tooltip** in both layouts, and the DOM keeps all of it. A longer text belongs in a
   description under the name (#528), not in the name.
3. **On a wide header the name comes first.** It takes its natural width; the actions yield to the ⋯ menu from the back
   of the priority order, down to a **floor: the kind's first two shown actions** (a Memory favorite and upload, a folder
   favorite and share, a file favorite and download, a drop box favorite and share; a guest, who has no favorite, keeps
   the next two — a Memory's upload and download). Only below the floor is the name shortened, one line, with an
   ellipsis. On a compact header the name has its own two lines, and the actions' row holds as many as fit.
4. **As many actions as fit are buttons, in priority order; the rest are entries of one "more" menu (⋯) at the row's
   end.** The fit is measured from the row's width and measured again when that width changes (decision 8 picks the
   layout, this decision fills its row).
5. **The priority is the order of the node kind's list**, which the page that draws the header supplies, core's actions
   (favorite, cover, copy, download, report) included by their ids. A Memory's order is the owner's: favorite, upload,
   share, upload request, participants, download, cover, banner, rename, move, copy. A folder's, a file's and a drop
   box's follow the same reading — favorite first, then what the kind is for, then sharing, then editing the node itself,
   then moving and copying it (defaults shown to the owner on 2026-10-23 and not stopped; they are in the CR).
6. **Delete and report are never buttons.** They are always the last entries of the menu, behind a separator, delete
   last: a button that moves with the width is too easy to hit by mistake, and both open a consequential dialog.
7. **No action is lost.** An action that does not fit is an entry of the menu with its label and icon; a disabled
   action is a disabled entry. Every entry is reachable by keyboard (the layer's menu).
8. **The header's own width picks the layout, by fit — no viewport query.** Wide applies while the wide leading visual,
   the name's minimum (**12rem**, 192 px: about "Rom 2026" in a Memory's 2 rem name, about "Omas 80. Geburtstag" in the
   1.15 rem name of the other kinds), the floor's two buttons and the ⋯, with their gaps, fit in one row; otherwise
   compact. Cover size, name lines and where the actions sit all follow this one answer, so a big cover can no longer
   sit beside a name in a narrow header. Its inputs are the header's width (which its layout does not change) and fixed
   sizes — never the name's text or the layout in force — so it cannot flip back and forth. Measured 2026-10-24 (Chrome,
   viewport without the sidebar, every pixel from 300 to 1400 px and back): wide from a header of 542 px for a Memory
   (viewport 574 px), 404 px for a folder, a file and a drop box (436 px) and 450 px for a folder with a cover (482 px),
   one switch each way and the same in the dark theme: a 375 px phone is compact for every kind.

## Consequences

- The header and the level rows split their actions by two rules: the header by its measured width, a row by its fixed
  two `first` actions. They share the menu's entries (label, icon, disabled, danger) and nothing else.
- On a wide screen a Memory with a short name shows 10 of its 11 other actions (measured 2026-10-23 and again 2026-10-24
  at 1280 px with "Rom 2026"); with a long name it shows its floor, favorite and upload, and the name ends in an ellipsis
  on one line (a 92-character name, measured 2026-10-24: 2 buttons at 574, 768 and 1280 px, 587 of its 1504 px shown).
  Just above the switch a Memory with a short name shows 2 (574 px) and a folder with a cover 2 (482 px): the wide
  layout's row is as short as it gets there.
- A long name costs the wide header its buttons before it costs the name its text — the owner's priority. A guest's
  floor is whatever the guest's first two actions are; a guest with fewer than two has no floor to keep.
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
- **A viewport breakpoint for the layout** (the first build, 540 px): it ignores what the header holds — a Memory's 180
  px cover and an icon tile switch at the same width — and it jumped from 9 buttons to 1 at 541 px (#531). The header's
  own width, read against what it must fit, decides instead.
- **The actions first on a wide header, the name at a fixed minimum** (the first build): a long name squeezed to 12rem
  in two lines beside the buttons; the owner put the name first.
- **Two lines of name on a wide header:** beside a 180 px cover those lines are the date, place and participants.
- **A name minimum in characters (10ch):** it scales with the font, but at 119 px for the 1.15 rem names it switches a
  folder to the wide layout on a 375 px phone, with three buttons instead of six; 12rem keeps every phone compact.
