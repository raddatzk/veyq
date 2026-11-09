---
id: cr-search-visible-reasons
title: A result is never listed for a reason the member cannot see; one character matches word starts only
status: planned
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

(filled by the implementer)
