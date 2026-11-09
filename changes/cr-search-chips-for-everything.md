---
id: cr-search-chips-for-everything
title: One list of results after the suggestions, ordered by match and place; every result kind has a chip word; a folder or Memory is a chip
status: done
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

Implemented 2026-11-09 in the worktree `search-design`: phase 0 `be0b4478c` (ADR 0125 decisions 4, 5, 6, 9 and the rejected
alternatives; the use case's steps 4-6, the accessibility note and A3), phase 1 the build with its docs. `pnpm run check`
green in `src/web/` after `pnpm run generate:api` (measured: 542 test files passed, 33 skipped; 6,612 tests); every
`scripts/validate_*.py` but `validate_guard_gaps.py` green with `/usr/bin/python3` (69 run, 0 red; no validator changed), the
core-import guard among them; `astro build` in `site/` green (209 pages).

### What was built, and the choices made (owner away; technical choices are mine)

- **One list.** `groups.ts` became `results.ts` (`git mv`, with its test): `searchResults` returns the first `limit`
  results and the exact `total`; the order is `inOrder` - rank, then `Place` (open subtree 0, active space 1, other spaces 2,
  documents of no space 3), then collation, then id. `firstRanked` (top-N) is kept unchanged, so the polish CR's cost
  bound holds for one list as it did per group. `byRank` (query.ts) went: its one other reader was its test.
- **The layer.** `VeyqTagsInput`'s `groups` (headed lists) became `results` (one list) and `resultsLabel` (the results'
  `aria-label`, no heading drawn); the sticky highlight's fallback reads two areas (`stickyArea`), so "the first entry of
  its group" is now "of its area" with no other change. Only the search used `groups` (`git grep`).
- **Where a document lies, by id.** `SearchDocument.pathIds` sits beside `path` (the ids of the same nodes): built once
  with the names by `documents.ts` `placeOf` (a node's: the nodes above it) and `placeWithin` (an object's: its node's
  above plus the node - one pair of arrays shared by every photo of a Memory). The *In:* predicate is
  `doc.scopeId === chip.scopeId && doc.pathIds.includes(chip.nodeId)`: an `includes` over the tree's depth (a handful of
  ids), constant per document at household depth - no set per document (17,000 sets would have cost heap for nothing).
  The open node's place in the order reads the same field, so `descendantsOf` (a tree walk per keystroke) went.
- **Which nodes are offered as *In:*.** A node something lies below (some document's `pathIds` names it), read from the
  documents once per document list (`holdersOf`, cached like the places): core names no kind (ADR 0079), and an empty
  folder, where the chip would find nothing, is not offered. A Depot drop box with files is offered too ("the same for
  nearly everything").
- **The node itself is not in its own *In:*.** "In" reads as inside; the node is listed beside the suggestion as a result.
  The open node itself likewise sits in its space, not first (as before).
- **Ranks.** A settings section's word now ranks with a place (`Rank.place`), after every name match (the CR's order:
  "then a place or settings word"); before, it ranked like a name. Matching is unchanged, so the matcher's monotonicity
  rule holds.
- **The kind words** *Person* / *Setting* (`shell.search.kindPerson`, `kindSetting`; de *Person*, *Einstellung*) are
  core's, added to the kind chips beside the registry's. The chip word for *In:* is `shell.search.chipIn` ("In" in both).
- **The *In:* suggestion's second line** is its space and path (`Familie · Work`), drawn with the result row's classes
  (`veyq-search__result-main`, `__result-meta`) - no new UI. It is shown for every *In:* suggestion, not only on a clash:
  one rule, and the member sees which "Reisen" before a second exists.
- **A row's second line** names the space whenever the result is not in the active space (was: in the *Other spaces*
  group) - the same words as before.
- Removed i18n keys `groupOpen`, `groupSpace`, `groupOther`, `groupPeople`, `groupSettings`; added `results`.

### Tests rewritten (none deleted silently)

- `groups.test.ts` → `results.test.ts`: the group order became "at an equal match, open subtree → space → other";
  "a contribution counts only for the open node" kept over the list; "ranks within a group, never across" became its
  reverse, the owner's rule ("an exact match in another space before an inside-word match here"); "members and settings
  after every group" became "after content at an equal match, before it at a better one"; "neither group while a chip is
  set" became the predicate tests (*Kind: Person* only people; a year chip no person and no setting, with the chip-less
  control; a person chip not the person's profile); "what lies below a node, survives a cycle" (`descendantsOf`, gone)
  became `placeOf`'s ids and its cycle; the top-N tests now run over rank + place (half the documents in another space).
  New: "at an equal match, the active space first; at an equal place, the name".
- `query.test.ts`: "orders by rank, then name" now reads `searchResults` in one place (was `byRank`); new: a settings
  word ranks with a place; the *In:* chip (offered with its path, not for an empty node; narrows to the subtree, a photo
  included, the node itself not, with the chip-less control; two nodes of one name told apart by id, in one space and
  across spaces; a document of no space is in no node).
- `VeyqTagsInput.test.ts`: the results' tests now pass `results`; the headings test asserts one heading and the results'
  `aria-label`; the drop-out tests read "area" (same assertions).
- `SearchField.test.ts`: every `headings()` that listed a group heading now asserts none below *Filter by*; "the results
  in their groups" → one list with each row's place; "the open node's group by its name" → the open subtree first at an
  equal match (the name alone would put the other first); "a group's first results and more" → 10, then 10 more, the
  count exact (25); "People after every space's group" → a person after content at an equal match; "hides both groups
  once a chip is set" → a person chip lists what the person takes part in, not their profile, plus new *Kind: Person*,
  *Kind: Setting* (German) and a year chip with a person-shaped control; "each group named by its heading" → the
  suggestions by their heading, the results by `aria-label`; the bold test finds its two names by text (the member
  `kevin` is now a row between them). New: *In: Trips* with the photo and its control; two same-named folders as three
  suggestions with paths, the second narrowing to its own subtree.

### Bites (`scratchpad/p15/bites.py`, snapshot and restore by bytes, `git status` equal before and after)

- B1 the old hide rule kept (a chip drops people and settings): *Kind: Person* red in `results.test.ts` and
  `SearchField.test.ts`, and *Kind: Setting* red.
- B2 the subtree matched by name (`doc.path.includes(chip.label)`): the clash test red in both files, and the subtree test.
- B3 the place ordered before the match (the groups' order): four ordering tests red, the owner's exact-elsewhere test
  among them.
- B4 the drop-out falling back to the list's first, not its area's: the two drop-out tests red.

### The volume re-measurement

- **Instance.** The harness's own (`measure_volume.py … boot`, project `veyq-vol-11091436`), booted from images built from
  this worktree with the CR in (`veyq-vol-search:p15`, the web bundle rebuilt in the image), seeded `birth`, `tree --target
  5000`, `spread --target 1000`, `members --target 445`, `photos --target 10000` (3.5 min); torn down afterwards
  (`teardown`), both images removed.
- **The command:** `/usr/bin/python3 scripts/measure_volume.py --instance I search --runs 2 --throttles 1,4 --max-load 1000
  --active tenant|group` (`scratchpad/p15/vol-measure.sh`), `uptime` at both ends of each landing. My own heavy steps
  (`pnpm run check`, the image build, the seed) had ended before A; the load is the owner's.

| Invocation, load (1-min, start→end) | landing | build wall ×4 | longest build task ×1 / ×4 | longest typing task ×4 (margin to 100 ms) |
|---|---|---|---|---|
| A 15:07, 13.0→7.9 | tenant | 5.2, 5.2 s | 79, 79 / 96, 96 ms | none ≥ 50 ms (≥ 50), none (≥ 50) |
| A 15:08, 7.9→5.6 | group | 4.1, 3.9 s | 57, 74 / 108, 105 ms | none (≥ 50), 66 (34) |
| B 15:10, 5.7→5.6 | tenant | 5.2, 5.0 s | 63, 67 / 92, 95 ms | 62 (38), none (≥ 50) |
| B 15:11, 5.6→8.1 | group | 4.3, 3.8 s | 63, 57 / 104, 107 ms | 50 (50), 50 (50) |

- **Judgment.** Both bounds held in every reading of both invocations. **Fewer rows drawn widened the typing margin**, as
  the polish CR's profile predicted: the first keystroke "K" draws 11 rows now (10 results and "show more"; one letter
  offers no chip) where five groups drew up to 29 at volume, and its longest task at ×4 is none-to-66 ms (its event
  duration 64-88 ms), against 76-100 ms in cr-search-polish's readings that held (margin 0-24 ms there, 34-50 ms or more
  here). The build is unchanged within noise (92-108 ms at ×4; 93-120 before). Every second focus read each other space's
  tree and every media listing as one `304` and decoded 0 strings (`secondDecodes`).
- **The index's heap:** +9.1-9.6 MB (+8.4-8.9 MB before): the `pathIds` arrays beside `path`.
- The readings: `.run/measurements/20261109-150720-search-tenant`, `-150837-search-group`, `-151019-search-tenant`,
  `-151134-search-group`.

### Browser pass (`veyq-search` stack, desktop, German UI)

The worktree's backend (8180) and Vite (8189), started by `scratchpad/p15/start-backend.sh` and `start-vite.sh`, stopped by
PID afterwards; `search-pass/p15-pass.mjs`, headless system Chrome, 18 checks, all pass, no console error:
- control: "strand" lists *strand.jpg* with no chip;
- "Reisen" offers *Reisen · Familie Raddatz · In* under *Filtern nach*, the one heading; the folder itself is a result
  beside it; the list has two groups, the second named *Treffer* (Chrome's computed accessible name);
- Enter on it makes the chip; the list is Roma 2024, its three photos, Kenia, Kevins Geburtstag and Reiseplan.txt - not
  Gartenfest, not Reisen, no person; "garten" under the chip lists nothing of Gartenfest;
- "Person" offers *Person · Art* (beside *Persönlich · Bereich*); the chip lists only Kevin Raddatz (`~kevin`);
- "k": one results group, no heading, 10 rows and "1 weitere zeigen".

Screenshots: `search-pass/p15-desktop-reisen-in.png`, `p15-desktop-in-chip.png`, `p15-desktop-person-kind.png`. The pass
found no defect.

### Docs

- Coverage (`uc.platform.search.yaml`): `grouped-by-context` became `one-list-ordered-by-match-and-place`; new
  `a-chip-is-a-predicate-over-every-document` and `in-a-node`; `chips-and-text`, `photos-of-every-space`,
  `people-are-results`, `settings-are-the-rails-sections`, `a-screen-reader-drives-it` and `every-kind-has-a-word`
  rewritten to the one list; refs moved from `groups.test.ts` to `results.test.ts`.
- The Use page *Search all your spaces* / *Alle deine Bereiche durchsuchen*: the *In* and the two kind words, "a filter
  narrows everything", *How the results are ordered* (replacing *grouped*), paging by 10, the screen reader's names.
- `src/web/CLAUDE.md`'s search paragraph: one list, the predicate rule (never add a hide rule), `pathIds`.

### Owner questions (each with the conservative default built)

1. **The *In:* second line is always shown** (space and path), not only on a clash. Recommendation: keep - one rule.
2. **"Person" also offers *Persönlich · Bereich*** (the personal space's word starts with "person"). Recommendation:
   keep; it is the space grammar working.
3. **A settings word now ranks after a name inside a word** ("Kennwort" lists a name holding "kennwort" mid-word first).
   Recommendation: keep - it is the CR's order.
4. Delete this CR once read; its binding lessons are in `src/web/CLAUDE.md`, the headers of `results.ts`, `query.ts`,
   `SearchField.vue` and `VeyqTagsInput.vue`, and the coverage criteria.

### Process observations

- The worktree isolation refused several heredoc-and-run edits that earlier calls of the same shape had passed; writing
  each edit as a script file first (as the brief said) was the reliable form.
- A test helper that counts rows across both areas hid a correct change: once *Rome 2024* held a chapter it was offered
  as *In:*, and the old `rows()` assertion read that as a wrong result. A `resultRows()` helper (the results area only)
  separates "what is offered" from "what is found".
