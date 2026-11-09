---
id: cr-search-visible-reasons
title: A result is never listed for a reason the member cannot see; one character matches word starts only
status: done
satisfies: [uc.platform.search]
adrs: ["0125"]
invariants:
  - every listed result shows why it matched (its name, its place, or the settings word on its second line)
  - nothing about a query leaves the device or is written to storage
  - no backend and no contract change
---

## Goal

The owner's screenshots (2026-11-09) show "K" listing *Reiselink* and *Funktionen* (an inside-word "k") and "Ke" listing
*Passwort ändern* (through its hidden settings word "Kennwort"). The list jumped between the two queries, and one result
stood there for no visible reason. [uc.platform.search](../docs/features/platform/use-cases/uc.platform.search.md)
step 5 now states the two rules.

1. **Settings words are visible and need three characters.**
   - **Three characters:** a section's search word (`features/settings/sections.ts`, `words`) counts from the third
     typed character on.
   - **Visible:** where that word is the reason the section is listed (its label did not match), the row's second line
     shows the word with the matched part bold. The second line becomes "Persönlich · **Ken**nwort"; drawn the same way
     as the bold name, as text nodes and a `<strong>`, never markup.
2. **One character matches word starts only.**
   - With a single typed character, only a word start of a name or a place matches. Inside-word matches count from the
     second character.
   - **Why:** "K" then lists no *Reiselink*, and typing "Ke" only narrows the list.
   - **The matcher's memo and narrowing** (`cr-search-polish`) must stay correct across the boundary. A cached
     one-character result is a subset rule, so narrowing from "K" to "Ke" may NOT reuse "K"'s matches as the superset;
     "Ke" may match inside a word where "K" may not. Decide whether narrowing starts at two characters or how the
     superset is kept, and test it.

## Context

- **The code:** `src/web/src/core/search/query.ts` (rank classes, `wordPrefix`, the memo and narrowing),
  `model.ts` (`keywords`, folded fields), `SearchField.vue` (`secondLine`, the bold `nameParts`) and
  `peopleAndSettings.ts`.
- **Tests that assert the old behaviour:** a test listing an inside-word match for one character, or a settings word
  from two characters. Rewrite them and say per test what it now asserts:
  `rg -n "keywords|inside|Rank\." src/web/src/core/search/*.test.ts`.
- **The owner's running instance:** a backend on 8180 and Vite on 8189 are running from this worktree for the owner,
  and they see changes live.

## Phases

Single phase (web).

- **Build:** the two items.
- **Tests:**
  - "Ke" does not list *Passwort ändern*; "Ken" lists it with "Ken" bold in its second line.
  - A section whose label matches shows no keyword line.
  - "K" lists no inside-word match; "Ke" lists one where it exists (e.g. a name containing "ke").
  - The narrowing from "K" to "Ke" and back gives the same results as fresh queries (compare against a matcher with the
    memo off).
- **Bites:**
  - Drop the three-character rule, and the "Ke" test goes red.
  - Drop the keyword line, and the "Ken" test goes red.
  - Let one character match inside words, and the "K" test goes red.
  - Reuse "K"'s matches as the superset for "Ke", and the narrowing equivalence test goes red, if the design allows
    that bite.

  Run every bite on a COPY, never on a file the owner's Vite serves.
- **Browser pass:** on the owner's running instance, `http://veyq.localhost:8189/?devlogin` (do not restart it):
  - "K" and "Ke" as in the owner's screenshots;
  - "Ken" shows the keyword line.

  Desktop, final screenshots only.
- **Docs:** the Use page `spaces/search` in EN+DE, where it describes the settings words; coverage
  `settings-are-the-rails-sections` / `chips-and-text`.
- **Done when:** `pnpm run check` is green in `src/web/`, and the trust-chain guards are green (`/usr/bin/python3`,
  guard_gaps skipped).

## Retrospective

Implemented 2026-11-09 in the worktree `search-design`, one commit. Measured: `pnpm run check` green in `src/web/`
(544 test files passed, 33 skipped; 6,646 tests); every `scripts/validate_*.py` but `validate_guard_gaps.py` green with
`/usr/bin/python3` (69 run, 0 red; no validator changed).

### The choices (technical, mine)

- **Where the two lengths live.** `query.ts` holds `MIN_INSIDE = 2` and `MIN_KEYWORD = 3`, counted in characters of the
  FOLDED text (code points: an emoji is one). So "ß" typed alone folds to "ss" and counts as two - accepted, the edge
  is a letter nobody types alone. `nameRank` drops an inside-word match below `MIN_INSIDE`; a place, below it, matches
  from a word's start (`wordPrefix`) - the same rule as a name. A multi-word text ("rom k") is at least three
  characters, so the one-character rule never applies to a word of it: the rule is the whole text's, as the use case says
  ("a single typed character"), and it keeps the narrowing simple.
- **Narrowing across the boundary.** Both rules start at a length, so `textRank` is monotone only from
  `EVERY_RULE = max(MIN_INSIDE, MIN_KEYWORD) = 3` characters on. The matcher narrows only from a remembered text that long,
  or an empty one (it matches every document the chips leave): "K" → "Ke" and "Ke" → "Ken" scan every document, "Ken" →
  "Kenn" narrows. Backspace still answers from memory (an exact key). The cost is two scans that narrowed before; by
  cr-search-polish's bench a two-letter scan is ~0.5 ms against ~0.16 narrowed at 17,463 documents (remembered, not
  re-measured). A finer rule - narrow "Ke" → "Ken" but scan only the keyword documents - was rejected: one exception per
  rule is what the next rule breaks.
- **The reason line.** `reasonKeyword(doc, t)` answers the section's word (as its catalog spells it) only where neither
  the name nor an alias matched - the same order `textRank` decides by - so a label match draws no word. `SearchField`
  builds the second line as parts (`metaParts`): the heading, " · ", then `nameParts(word, t)`, drawn like the name as text
  interpolations and a `strong` (`.veyq-search__match`). Generic over any document with `keywords`, not a settings branch.
- **A consequence the owner should know:** a settings word of two characters can no longer be found alone - the
  storage section's "S3" (EN and DE). "QR-Code" still is, from "qr-". Left as the rule reads; if "S3" should find
  storage, the word list or an exact-word exception is the owner's call.

### The tests that asserted the old behaviour, rewritten (each says `was:` at its line)

- `SearchField.test.ts` "keeps the entry the member moved to…": "k" and "ke" no longer list the two sections found by
  "keys"; it now asserts only the people. "key" listing both, with "keys" after their heading, is a new test.
- `SearchField.test.ts` "finds the mail section by E-Mail…": the row now reads "<label> <heading> · e-mail".
- `SearchField.test.ts` "matches a section's words from a word's start only": its absence (`not.toContain` the bare row)
  could never be red against a row with the word appended, so it now asks for any row of the mail section, beside the
  `smtp` control.
- `query.test.ts` "narrows the last matches…" and "Backspace from kev to ke…": a two-character text is no superset any
  more, so the narrowing is asserted from "kev" ("ke" → "kev" now scans, "kev" → "kevi" narrows).
- The test helper `words()` reads a row's second line as one part, as it did the name, since the line now holds a
  `strong`.

### Bites (each on copies next to the sources - `query.bite.ts`, `SearchField.bite.vue` and their tests - removed after)

Control: the copies unchanged, 108/108 green. Then:
- the three-character rule dropped (`keywordAt` without its length check): 6 red, "Ke does not list Passwort ändern"
  and the unit "ke no, ken yes" among them;
- the keyword line dropped (`metaParts` with no word): 4 red, the "Ken" test among them;
- one character matching inside words (`MIN_INSIDE = 1`): 4 red, the "K" tests among them;
- "K" reused as the superset (`narrowsFrom` from one character): 6 red, the equivalence test with
  `ke: expected […6] to deeply equal […7]`;
- the length in UTF-16 units: 1 red, the emoji test.

### Browser pass

On the owner's instance (`http://veyq.localhost:8189/?devlogin`, German UI, desktop 1280×860; `search-pass/p20-pass.mjs`,
all checks PASS, no console error or warning):
- "K": Kenia, Kevins Geburtstag, kolosseum.jpg, Kartendienste, Kevin Raddatz, Konto absichern, Konto löschen - each by a
  word start; no Reiselink, no Funktionen, no word on a second line.
- "Ke": Kenia, Kevins Geburtstag, Kevin Raddatz - only a narrowing of "K"; no Passwort ändern.
- "Ken": Kenia, and Passwort ändern with "Persönlich · **Ken**nwort" (the strong one text node, weight 700).
- "Passw": Passwort ändern with "Persönlich" alone.

Screenshots: `p20-desktop-K.png`, `p20-desktop-Ke.png`, `p20-desktop-Ken.png` in the pass directory.

### Docs

The Use page `spaces/search` (EN+DE) says the one-character rule and that a settings word counts from the third letter
and is shown; coverage `chips-and-text` and `settings-are-the-rails-sections` say both and the narrowing's boundary;
`src/web/CLAUDE.md`'s search paragraph names `EVERY_RULE` as what a new length rule must raise; lane L1's settings test
(`tests/e2e/tests/features/platform/search.spec.ts`) asserts the drawn word "Personal · dark mode" - not run (heavy
tests at V1 end).
