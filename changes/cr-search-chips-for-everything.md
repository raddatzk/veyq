---
id: cr-search-chips-for-everything
title: Every result kind has a chip word, a folder or Memory is a chip that narrows to its subtree, and "show more" sets its group's chip
status: planned
satisfies: [uc.platform.search]
adrs: ["0125", "0079"]
invariants:
  - a chip is a predicate over every document; no group is hidden by a rule of its own (replaces the "a chip hides People and Settings" rule)
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
3. **"Show more" sets its group's chip.**

   | Group | "show more" sets |
   |---|---|
   | the open node's | *In: <that node>* |
   | *In this space* | the space chip |
   | *People* | *Art: Person* |
   | *Settings* | *Art: Einstellung* |
   | *Other spaces* | nothing: the list grows by a page, as today |

   The space chips already exist ("Climbing", "Persönlich"). They are how a member narrows "other spaces" by hand
   (owner: *Other spaces* as a chip is not worth it).

## Refined state this CR owes first (phase 0, docs only)

The polish CR (`cr-search-polish`) ran in the same files while this CR was written, so the state docs move here, in
phase 0, before any code.

- **ADR 0125:**
  - decision 4 (the chip list gains *In:* and the two kinds);
  - decision 9 (people and settings are kinds; the hide-on-chip rule is replaced by the predicate rule, and why);
  - a sentence that "show more" sets the group's chip.
- **The use case,** `uc.platform.search`:
  - step 4 (the chips);
  - step 6 (the hide rule removed; "show more");
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

- **Build:** the two kinds, the predicate rule, the *In:* chip, "show more".
- **Tests:**
  - *Art: Person* lists only people;
  - *Jahr: 2020* lists no person and no setting;
  - *In: Reisen* narrows to its subtree, a photo included, with a control: the same photo without the chip is listed
    from another group;
  - two same-named folders give two suggestions with paths;
  - "show more" sets each group's chip, and *Other spaces* only grows.
- **Bites:**
  - Keep the old hide rule: the *Art: Person* test is red.
  - Match the subtree by name instead of id: the clash test is red.
- **Volume:** one re-measurement, with the margin stated.
- **Browser pass on `veyq-search`:**
  - "Reisen" offers *In: Reisen*, which narrows;
  - "Person" offers the kind chip;
  - "show more" under *In this space* sets the space chip.

  Desktop, final screenshots only.
- **Docs:** coverage criteria and the Use page `spaces/search` in EN+DE.
- **Done when:** `pnpm run check` is green, the core-import guard is green, and the trust-chain guards are green.

## Retrospective

(filled by the implementer)
