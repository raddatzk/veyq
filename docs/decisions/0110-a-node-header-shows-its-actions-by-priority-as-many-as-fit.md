# ADR 0110 — A node header shows its actions by priority, as many as fit, and the rest in one menu

- Status: Accepted
- Built: yes
- Date: 2026-10-23
- Model: [ui-web-styleguide](../crosscutting/ui-web-styleguide.md)

## Context

Every node page and every folder level opens with core's node header: a leading visual, the name, and the actions. Its
rule was that **every action is a button at every width** — "a width-hidden button is a lost action": the title row
wrapped, so the buttons took as many rows as they needed. A Memory carries 13 actions (favorite, upload, share, upload
request, participants, banner, rename, move, copy, delete, cover, download, report), a folder 9, a file 9, a drop box 8.
On a narrow header the leading visual stacked above the name, and a Memory's cover then grew to the header's width.

The owner judged the result on a 375 px phone: a large cover, then the name, then two rows of seven and six equal buttons
before the content starts. From mockups the owner chose a small cover beside a name of at most two lines, the actions in
a row of their own, the important ones as buttons and the rest in one "more" menu — and then: *prioritise the actions,
and show as many as fit*.

The level list already splits a row's actions: at most two marked `first` are buttons, every other shown action sits in
one menu. That split is fixed, not measured, because a list needs the same columns in every row.

A first build placed the actions by a viewport query and jumped between widths (rejected below). The owner then set the
order of what yields: *the title comes first and pushes actions into the menu, but only while favorite and upload stay
visible — after that the title is shortened*; *two lines only on a phone — on a desktop, lines two and three beside the
big cover are date, place and participants*; *and on a desktop the title and the actions are always one row*.

## Decision

1. **Two layouts, and the leading visual stays beside the name in both; the header never stacks.**
   - **Compact** (a phone): a small leading visual — a 16:9 tile of 64 × 36 px for every kind, a cover, its empty frame
     or the icon of a kind without one —, the name beside it in **at most two lines**, the actions in a row of their own, the header's full
     width, below them; then subtitle and meta.
   - **Wide** (a desktop): the full sizes - **the leading visual as tall as the rows core draws beside it** (owner
     2026-11-04): each kind of row - the name's, the subtitle, a row of date and place chips, a row of people, a line of
     text - has one fixed block size, and the visual's height is their sum and the gaps between them, computed from the
     data the header draws, never measured. **The leading visual is always 16:9 at that height** (owner 2026-11-04): a
     cover (a Memory's four rows - the name, the count, date and place, the people - make it about 244 px wide), the empty
     frame of a kind that takes one, and a kind without a cover (a file) its icon in that same frame - no square tile (a
     folder's name alone: a frame as tall as the name, about 75 px wide). Wrapped chips on a narrow window may make the text taller than the visual; that is accepted.
     The name and the actions are **always one row** — the name **one line**, because the lines beside a big cover belong
     to the subtitle and the meta (a Memory's date, place and participants). The actions never get a row of their own
     here. The content is centred against the visual.
   - **The name is one size, cover or not** (owner 2026-11-04): 2 rem wide, 1.5 rem compact.
2. **The full name is the title's tooltip** in both layouts, and the DOM keeps all of it. A longer text belongs in a
   description under the name, not in the name.
3. **On a wide header the name comes first.** It takes its natural width; the actions yield to the ⋯ menu from the back
   of the priority order, down to a **floor: the kind's first two shown actions** (a Memory favorite and upload, a folder
   favorite and share, a file favorite and download, a drop box favorite and share; a guest, who has no favorite, keeps
   the next two — a Memory's upload and download). Only below the floor is the name shortened, one line, with an
   ellipsis. On a compact header the name has its own two lines, and the actions' row holds as many as fit.
4. **As many actions as fit are buttons, in priority order; the rest are entries of one "more" menu (⋯) at the row's
   end.** The fit is measured from the row's width and measured again when that width changes (decision 8 picks the
   layout, this decision fills its row).
5. **The priority is the order of the node kind's list**, which the page that draws the header supplies, core's actions
   (favorite, copy, download, report) included by their ids. A Memory's order is the owner's: favorite, upload, share
   (one action for every link role since 2026-11-04, the upload request folded into it), download, banner, move, copy. A
   folder's, a file's, a drop box's and a Pot's follow the same reading — favorite first, then what the kind is for, then
   sharing, then moving and copying it; each kind's list is the order its descriptor's `header.order` names. What is
   edited at its place has no entry (decision 11).
6. **Delete and report are never buttons.** They are always the last entries of the menu, behind a separator, delete
   last: a button that moves with the width is too easy to hit by mistake, and both open a consequential dialog. **Move and
   copy are menu entries too** (owner 2026-11-04), in that last group before report.
7. **No action is lost.** An action that does not fit is an entry of the menu with its label and icon; a disabled
   action is a disabled entry. Every entry is reachable by keyboard (the layer's menu).
8. **The header's own width picks the layout, by fit — no viewport query.** Wide applies while the wide leading visual,
   the name's minimum (**12rem**, 192 px: about "Rom 2026" in the 2 rem name), the floor's two buttons and the ⋯, with
   their gaps, fit in one row; otherwise
   compact. Cover size, name lines and where the actions sit all follow this one answer, so a big cover can no longer
   sit beside a name in a narrow header. Its inputs are the header's width (which its layout does not change) and sizes
   no layout changes — the visual's wide width is computed from the rows the header draws (decision 1), never from the
   name's text or the layout in force — so it cannot flip back and forth, and a 375 px phone is compact for every kind.
9. **Core's actions come centrally, never from the page.** Favorite, copy, download and report are core's for every
   node (and the cover, whose way in a header is its visual, decision 11), each gated by its own core rights function and failing closed. The page names the node its header heads;
   the header takes core's actions for that node from the core view around it — core's node page, which both hosts
   mount around every kind's page, and the folder level — and merges them with the kind's own. No application builds,
   lists or forwards one, so none can leave one out: report — the notice mechanism the DSA asks of a host (Art. 16),
   offered on whatever a person sees ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
   § 1) — is on every node's header by construction, and a test walks every registered kind's page to prove it. A
   kind's order (decision 5) may still name a core action's id for its priority.
10. **Core draws the header; an application contributes data** (owner 2026-11-03: generalise into core whatever takes no
    function away from an application). The header row is the same for every application: core's node page draws it for
    every kind, the level for a folder. What never changes per node is the kind's descriptor - its icon, whether it takes
    a cover, its priority (decision 5). What depends on the page the page's detail contributes as data: a subtitle, its
    own actions, its **facts** - a date, a place, people, a line of text, each a feature any application may use (Memories
    a date, a place and who was there, Depot who uploaded a file) - and a **banner** core draws above the row (Memories').
    Core draws the facts in one look. **The cover is core's for every kind that takes one:** core shows it (decision 1's
    sizes) - without one the empty 16:9 frame with the kind's icon in its middle (owner 2026-11-04) - and it is the
    button that opens core's cover dialog, its hint "Set cover" on the empty frame (in words where the frame is three rows tall or more, else the image glyph on
    hover and focus, the words its tooltip), where whoever looks may set it; else
    a plain frame. A kind without a cover shows its icon in the same 16:9 frame, never a button. People link
    to a profile and show a member's picture on a member's page only - the header's default is a share page's, so no
    application can link a person for a guest. The body below the header is the application's own, to present itself.
    No application imports the header component (a guard), so none can draw a header of its own beside core's.
11. **What is editable at its place has no header entry** (owner 2026-11-04) - neither a button nor in the ⋯ menu. The
    **name** is renamed in place: the page's one `h1` holds a real button (named "Rename “…”", the heading itself still
    named by the name) where the viewer may rename - the rename right and the page's door, `renameTo` -, a click makes it
    a field pre-selected as core's name dialog selects (a file's name before its extension), Enter writes through the
    kind's own rename write, Esc cancels, and a blur writes a changed name as Enter does (not while the window lost focus,
    not a name just refused); the check and the words of a refusal (the colliding sibling by its kind's noun) are the
    name dialog's own code, shown under the field. A guest or a reader sees plain text. The **cover** is its visual
    (decision 10); a Memory's **participants** are their people fact, which opens the picker (an edit button beside the
    people, the chip "Add participants" while nobody is listed); a **date** and a **place** are their chips; a drawn
    **banner** opens its own dialog, so the menu offers "Add banner" only while none is drawn. A level's rows are
    unchanged: a row click opens the node, so its rename, cover and move stay entries of the row's menu.

## Consequences

- The header and the level rows split their actions by two rules: the header by its measured width, a row by its fixed
  two `first` actions. They share the menu's entries (label, icon, disabled, danger) and nothing else.
- The leading visual follows the rows (decision 1): a Memory's cover stays near its former 244 px, a folder's name alone
  gets a visual as tall as the name, and every kind that takes a cover shows the empty frame without one - the look
  Memories drew before decision 10, generalised. What a detail contributes reaches the header one render after the detail
  mounts (the same frame in a browser; a unit test waits a tick).
- On a folder level the header's action buttons and the toolbar's controls share one height, so in one row they line up.
- **Rejected — the application declaring its visual's row count:** core already knows which rows it draws; a declared
  number drifts from what is drawn.
- **Rejected — measuring the body's height for the visual:** the layout answer reads the visual's wide width, and a
  measured one moves with fonts and wraps; computed from the data it cannot flip.
- **Rejected — an action beside what is edited in place** (rename, cover, participants as header buttons): two ways to the
  same edit, and the button row full of what the page already offers where the thing is.
- **Rejected — slots in the header for an application's own visual and meta row** (the build before decision 10): each
  application drew what it liked there - Memories its own cover button, chips and avatar row, Depot its own uploader
  line - so the same fact looked different per application, and keeping a person unlinked on a share page was each
  application's to remember.
- On a wide screen a Memory with a short name shows nearly all its actions as buttons; with a long name it shows its
  floor, favorite and upload, and the name ends in an ellipsis on one line. Just above the switch to compact the wide
  layout's row is as short as it gets: the floor.
- A long name costs the wide header its buttons before it costs the name its text — the owner's priority. A guest's
  floor is whatever the guest's first two actions are; a guest with fewer than two has no floor to keep.
- A unit test has no layout, so the measurement is held at its pure half (the count that fits a given width) and proven
  in a real browser at phone and desktop width.
- **Rejected — every action a button at every width:** two or three rows of buttons on a phone before any content; the
  owner rejected it on sight.
- **Rejected — a fixed split, as the rows do** (two buttons, the rest in the menu): a wide screen would hide nine actions
  behind a menu with room to spare; the owner chose "as many as fit".
- **Rejected — hiding actions by a media query:** a hidden action is a lost one — the reason the every-button rule
  existed. The menu keeps every action reachable.
- **Rejected — the full name at any length beside the cover, or one line with an ellipsis:** the owner judged a long name
  beside a small cover to look bad, and one line hides too much; two lines are the middle.
- **Rejected — the cover above the name on a phone** (the former stacking): it pushes the content a screen down and is
  what the owner asked to change.
- **Rejected — a viewport breakpoint for the layout** (the first build, 540 px): it ignores what the header holds — a
  Memory's cover and a file's icon switch at the same width — and it jumped from 9 buttons to 1 one pixel past the
  breakpoint. The header's own width, read against what it must fit, decides instead.
- **Rejected — the actions first on a wide header, the name at a fixed minimum** (the first build): a long name squeezed
  to 12rem in two lines beside the buttons; the owner put the name first.
- **Rejected — two lines of name on a wide header:** beside a big cover those lines are the date, place and
  participants.
- **Rejected — a name minimum in characters (10ch):** it scales with the font, but at 119 px for the 1.15 rem names it
  switches a folder to the wide layout on a 375 px phone, with three buttons instead of six; 12rem keeps every phone
  compact.
