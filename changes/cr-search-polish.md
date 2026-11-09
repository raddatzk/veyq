---
id: cr-search-polish
title: Search polish — the matched part in bold, a month chip, words for settings sections, a screen reader's view
status: planned
satisfies: [uc.platform.search]
adrs: ["0125", "0117"]
invariants:
  - markup only through text nodes and elements; no string at an HTML sink (`v-safe-html` rule, ADR 0117 Trusted Types)
  - the settings words live with the section in `features/settings/sections.ts`, the one list the rail reads; never a second list
  - nothing about a query leaves the device or is written to storage
  - no backend and no contract change
---

## Goal

Four small items the owner chose (2026-11-09, items 4–7 of the search follow-ups), as
[uc.platform.search](../docs/features/platform/use-cases/uc.platform.search.md) steps 4–6 and its Quality notes now
state them:

1. **The matched part of a name in bold** ("**Kev**ins Geburtstag").
2. **A month chip:** a month name with a year ("Mai 2024", "May 2024", in the UI's language and in English). A Memory
   matches when its range overlaps the month; a photo when it was captured in it. A month name alone offers nothing:
   the year is what makes it a filter rather than a word of a name.
3. **Words for settings sections.** "E-Mail" finds the mail section, "Kennwort" the password. A few words per section,
   in the section's entry of `features/settings/sections.ts`, EN+DE through the catalogs.
4. **Accessibility.**
   - The result count is announced politely as it changes.
   - Each group is named by its heading.
   - Adding or removing a chip is announced.
   - The field stays a combobox a screen reader drives.

## Context (substrate: branch `worktree-search-design`; the implementer re-reads the search's files, since
`cr-search-photos-of-every-space` and `cr-gallery-filter-by-facts` may have landed before this CR runs)

- **The search:** `src/web/src/core/search/` (`query.ts` chips and suggestions, `groups.ts`, `SearchField.vue`), with
  `shared/text/fold.ts` and `shared/ui/VeyqTagsInput.vue`.
- **Bold under folding:** folding (NFD, mark-stripping, ß→ss) changes string lengths, so a match found in the folded
  text must be mapped back to the original's characters before it is drawn. Test it on "Straße" (query "strasse") and
  "Café" (query "cafe").
- **Rendering:** text segments and a `<strong>`, built as Vue nodes. Never `v-html`, never a string sink.
- **Years:** `features/memories/search.ts` gives Memories' years as a span. A month needs the date range itself, or a
  month span. Settle the facet shape, and keep core free of Memories' names.
- **Settings:** `features/settings/sections.ts` is the list; `peopleAndSettings.ts` builds the documents.
- **A live region:** check whether the layer has one already, with
  `rg -n "aria-live|role=\"status\"" src/web/src/shared/ui src/web/src/components`, and reuse it.

- **Found by the volume measurement** (`cr-search-at-volume` retrospective): every focus of the field reloads the
  settings sections - per focus a grant open per group and two account documents. Load them once per session (and
  again only where the rail itself would reload: a membership or role change it already watches), as a fifth item of
  this CR; a test counts the reads over two focuses.

- **The typing margin is nearly gone** (`cr-search-photos-of-every-space` retrospective): with 10,000 photos indexed,
  the longest keystroke task at 4x throttle measured 57-99 ms against the 100 ms bound of `cr-search-at-volume`. This
  CR adds work per keystroke (the month predicate) and per drawn row (the bold mapping), so it first wins room back, as
  a sixth item: matching becomes incremental where the query only grew (narrow the previous matches instead of
  scanning every document), and the folded name and place of a document are computed once at build, not per
  keystroke. And a group is ranked only as far as it is drawn: the field shows 8 per group (`SearchField.vue`
  `GROUP_PAGE`), while `groups.ts` sorts EVERY match of every group on each keystroke - with a one-letter query that
  is thousands of documents. Select the top N of a group (N = what is shown, grown by "show more") instead of a full
  sort; the count for "show more" stays exact. Measure which of the three moves wins most and say so. Then the volume search measurement runs again WITH this CR's other items in (`scripts/measure_volume.py
  search`, photos stage included, own instance, at any load with `uptime` recorded): the typing bound must hold in
  every reading of two runs, and the retrospective states the remaining margin.

## Phases

Single phase (web only).

- **Build:** the four items.
- **Tests:**
  - **Bold:** the mapping for the two folding cases and for a plain prefix; no markup string reaches the DOM (the
    rendered node is a `strong` element with a text child).
  - **The month chip:**
    - "mai 2024" and "May 2024" suggest it;
    - "Mai" alone does not;
    - overlap at month edges for a Memory and a photo;
    - a Memory without a date matches no month.
  - **Settings words:** "E-Mail" lists the mail section for an owner and nothing for a non-owner, the gate's control.
  - **Accessibility:**
    - the live region's text after a query;
    - after a chip is added;
    - after a chip is removed;
    - each group has an accessible name.
- **Bites:**
  - Draw the folded indices unmapped, and the "Straße" test goes red.
  - Drop the year requirement, and the "Mai alone" test goes red.
- **Browser pass on the `veyq-search` stack:**
  - "kev" shows the bold part;
  - "Mai 2024" offers the month chip and narrows to *Roma 2024*;
  - "E-Mail" lists the mail section.
  - Desktop, final screenshots only.
- **Docs:**
  - coverage criteria (extend `chips-and-text`, `settings-are-the-rails-sections`, and add one for accessibility, or
    say why not);
  - the Use page `spaces/search` in EN+DE.
- **Done when:** `pnpm run check` is green in `src/web/`, the core-import guard is green, and the trust-chain guards are
  green (`/usr/bin/python3`, guard_gaps skipped).

## Retrospective

(filled by the implementer)
