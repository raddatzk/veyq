---
id: cr-sibling-names-in-the-apps
status: in-progress
inbox: 1062
---

# The apps number equal sibling names wherever they name a folder (inbox #1062)

## Delta
The web numbers a folder whose name a sibling folder shares (`Anna`, `Anna (2)`: a drop box's guest folders, ADR 0080
decision 11) in the level, the heading and the breadcrumb, and on a guest's folder page (inbox #393), through the
platform's `tree/DisplayNames` (`@veyq/core-js/tree`, ADR 0155 § The node tree's slice). The apps number only on a drop
box's page. The core's plans hand the numbered name on beside the raw name, which stays what a rename prefills and a
move or collision check reads:

- `NodeRef.label` / `FolderRef.label` (null where the name is the label) and `displayName`; `Home.level` labels its
  folders, `Shell.at` and `asFolder` carry it onto Home's path, `ShellModel` relabels the path on each lists rebuild.
- `GuestLevel.Row.label` and `GuestLevel.title`; the bridge's `ReceivedRow.label` / `ReceivedPage.label`.
- Android, Apple, Linux and Windows draw the label on a folder's row and tile, Home's title, a guest's rows and title.

## Plan
1. core + composition + bridge: the labels, tests pinned to the web's cases.
2. web: the breadcrumb's and a guest page's numbering among sibling **folders** only (as the level's rows).
3. the four apps draw it.
4. harvest into ADR 0155 § The node tree's slice; inbox #1062 updated; this CR deleted.

Done when: `:core:jvmTest :composition:jvmTest :bridge:jvmTest :android:assembleDebug`, the web's `pnpm run check`, the
Linux and Windows builds where reachable, the trust chain green.

## Decision log
- *Only folders are numbered, everywhere.* The web's level numbers folders among folders, but its breadcrumb
  (`browse.ts displayNameOf`) and a guest's `nameOf` numbered among ALL siblings — a folder `Rom` beside a Memory `Rom`
  (legal: a name is unique among its application's siblings only, `registry.ts`) read `Rom (2)` in the breadcrumb and
  `Rom` in the list it was picked from, against `DisplayNames`' own promise. Equal names of one class arise only among
  folders (a drop box's guests'); an object's name is unique in its application. So the web is corrected to folders among
  folders, and the apps follow that one rule — no label on objects, bookmarks or files.
- *A label beside the name, nullable, a constructor property.* Not the name itself: rename's prefill, the move and the
  collision checks read `name` (measured 2026-11-22 by the coordinator). Nullable so a `copy(name = …)` never carries a
  stale label silently as a `= name` default would; a constructor property so kotlinx serializes it to the bridge's
  generated Rust and C# types.
- *The path relabelled on each rebuild*, not only on `enter`/`at`: a guest's later folder never renumbers an earlier one,
  but a restored older one does (creation order).
- *The web keeps drawing `displayNamesOf` from the core* — it already does (`treeRules.ts` → `core.displayNames`), so
  ADR 0155 decision 7.2's swap is done; nothing to follow up.
