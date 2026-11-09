---
id: cr-search-chips-for-everything
title: One list of results after the suggestions, ordered by match and place; every result kind has a chip word; a folder or Memory is a chip
status: planned
satisfies: [uc.platform.search]
adrs: ["0125", "0079"]
invariants:
  - a chip is a predicate over every document; no part of the list is hidden by a rule of its own (replaces the "a chip hides People and Settings" rule)
  - the list has exactly two areas, suggestions then results; where the member stands orders the results, it never splits them
  - core names no application's kind; the subtree chip is core's (the tree is core's)
  - nothing about a query leaves the device or is written to storage
  - no backend and no contract change
---

## Goal

The owner's rule (2026-11-09): "the same for nearly everything". Typing a word offers free-text results and, under
*Filter by*, a chip that narrows the search to what the word names. Kind words ("Erinnerung") and years ("2020") work
so today; three gaps remain:

1. **People and settings sections become kinds** with chip words (de *Person*, *Einstellung*; en *Person*,
   *Setting*).
   - **The predicate:** a chip is then a predicate over every document, and the rule "a chip hides the People and
     Settings groups" goes. A year chip excludes a person because a person has no year; *Art: Person* shows only
     people.
   - **The person chip:** *Person: Kevin* keeps meaning "content Kevin takes part in"; his profile does not satisfy it.
     The owner finds that right.
2. **A found folder or Memory is a chip:** *In: Reisen*.
   - **Offered:** when the typed token matches a folder's or a Memory's name (a Chapter's too). It is a suggestion like
     any other, beside the node itself as a result.
   - **The predicate:** a document lies in that node's subtree (a photo by its Memory). Its space is the node's space,
     so the chip also scopes to that space.
   - **A clash:** two nodes with the same name in different places are two suggestions, each with its path as the
     second line.
3. **One list instead of result groups** (owner, 2026-11-09: "keep it simple - suggestions and results as two
   separate areas, suggestions first; where a result lies should set the order, not a group").
   - **The two areas:** *Filter by* (the chip suggestions) and the results.
   - **What goes:** the result groups (*In "<open node>"*, *In this space*, *Other spaces*, *People*, *Settings*) go,
     and so does every group heading below *Filter by*.
   - **Ordering:** match quality first (an exact name, then a prefix, then a word start, then inside a word, then a
     place or settings word), then where the result lies (the open node's subtree, then the active space, then the
     other spaces, then people and settings), then the name's collation. A strong match elsewhere therefore still
     comes before a weak one here. Where the member stands breaks ties; it never hides a result.
   - **Each row:** it keeps saying where its result lies: space and path, as today; `~username` for a person; the rail
     heading for a section.
   - **Paging:** one page of 10 results, with "show more" adding 10. The count stays exact. Drawing fewer rows is the
     lever the volume measurement pointed at (`cr-search-polish` retrospective: the first keystroke's task is mostly
     the first list's render).
   - **The sticky highlight** (`cr-search-sticky-highlight`): its drop-out fallback "the first entry of its group" now
     reads "the first entry of its area" (suggestions or results). Update the use case's step 4 and its tests with it.
   - **"Show more" sets no chip.** That idea was tied to the groups, and with one list it is moot. A member narrows by
     the chips the field already suggests (space, *In:*, kind).

## Refined state this CR owes first (phase 0, docs only)

The polish CR (`cr-search-polish`) ran in the same files while this CR was written, so the state docs move here, in
phase 0, before any code.

- **ADR 0125:**
  - decision 4 (the chip list gains *In:* and the two kinds);
  - decision 5 rewritten: one result list after the suggestions, ordered by match, then place, then name, and why the
    groups went (the owner's simplicity rule, and the measured render cost of five groups);
  - decision 9 (people and settings are kinds in that one list; the hide-on-chip rule is replaced by the predicate
    rule).
- **The use case,** `uc.platform.search`:
  - step 4 (the chips);
  - step 6 (one list, its order, paging by 10; the hide rule removed);
  - a new alternate flow for a name clash between two *In:* suggestions.

## Context (the implementer re-reads; `cr-search-polish` landed just before)

- **The search:** `src/web/src/core/search/` (`query.ts` suggestions and predicates, `groups.ts`, `SearchField.vue`
  "show more", `peopleAndSettings.ts`), `model.ts` (folded fields at build), and the registry's `KindSearch.label`.
- **The subtree predicate** needs each document's ancestors. Nodes have `parentId` in the tree; a photo document
  carries its Memory's node id. Precompute an ancestor set, or the path's ids, at build time. The typing margin is thin
  (`cr-search-photos-of-every-space` and `cr-search-polish` retrospectives), so the predicate must be O(1) per
  document. Re-run `scripts/measure_volume.py search` once with this CR in and state the margin.
- **Memories' kind words:** a Chapter is a kind too, so *In: <Chapter>* works through the same tree.

## Phases

### Phase 0 — the refined state

The docs above. Trust-chain guards green.

### Phase 1 — build

- **Build:** the two kinds, the predicate rule, the *In:* chip, and the one ordered list.
- **Tests:**
  - *Art: Person* lists only people;
  - *Jahr: 2020* lists no person and no setting;
  - *In: Reisen* narrows to its subtree, a photo included, with a control: the same photo is listed
    without the chip;
  - two same-named folders give two suggestions with paths;
  - the order: an exact match in another space before an inside-word match here; at equal match the active space
    first; a person and a section sort after content at equal match;
  - no group heading below *Filter by*; paging by 10 with an exact count.
- **Bites:**
  - Keep the old hide rule: the *Art: Person* test is red.
  - Match the subtree by name instead of id: the clash test is red.
- **Volume:** one re-measurement, with the margin stated. Fewer rows drawn should widen it; say whether it did.
- **Browser pass on `veyq-search`:**
  - "Reisen" offers *In: Reisen*, which narrows;
  - "Person" offers the kind chip;
  - one list, with *Filter by* above it.

  Desktop, final screenshots only.
- **Docs:** coverage criteria and the Use page `spaces/search` in EN+DE.
- **Done when:** `pnpm run check` is green, the core-import guard is green, and the trust-chain guards are green.

## Retrospective

(filled by the implementer)
