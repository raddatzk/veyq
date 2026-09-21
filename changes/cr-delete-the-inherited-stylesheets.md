---
id: cr-delete-the-inherited-stylesheets
title: Delete the pre-E2EE stylesheets that outlived the views they were written for
status: planned
satisfies: []
adrs: []
invariants:
  - Nothing a user can see changes. This CR removes code that nothing renders; any visible difference is a defect, not a trade-off.
---

## Goal

`web/src/styles/` defines 648 classes and **461 have no user anywhere in `web/src`**; `admin-web/src/styles/`
is 152 of 174. They are the design system of the pre-E2EE source (`legacy/frontend-noe2ee/src/styles/`), carried
across an import that did not carry the views — a directory that is not in this repo at all. `memory.css` holds
20 `.cm-*` rules for CodeMirror, which is not a dependency of `web/`.

This CR deletes them. Inbox rows **#136** (a duplicate `.veyq-shell`) and **#137** (`.veyq-light` +
`data-theme`, written by the theme module and read by nobody) are two hand-found grains of this pile and close
with it. **#140** is already decided and is *not* part of this CR's goal: no guard now, because a predicate over
this corpus is red on the day it is born. Its final phase re-asks that question against the corpus this work
leaves behind.

## Context & constraints

- [`docs/inbox.md`](../docs/inbox.md) rows **#146** (this work, with the measurement and its method), **#136**,
  **#137** and **#140** (the decision and why the exception list was never the blocker).
- [`docs/crosscutting/ui-web-styleguide.md`](../docs/crosscutting/ui-web-styleguide.md) — the conventions the
  surviving sheets must still satisfy. If a deletion makes one of its statements false, the statement is
  corrected in the same phase.
- [`changes/README.md`](README.md) § *A guard derives its anchor set from the code; it does not list it* and
  § *Run a new guard's predicate against the UNTOUCHED tree before you write it down* — both govern the final
  phase.

### This CR has no automatic gate, and pretending otherwise is the main risk

`npm run check` runs lint, typecheck, tests and build. **None of them renders a pixel**, and Vite does not
purge or validate CSS, so a green `check` after deleting a live rule is exactly as green as before. It is
necessary here and it proves nothing about the actual failure mode.

So the gate is a **live before/after pass per phase**, and it is part of the work rather than a review step:
with the app running, capture the named surfaces *before* the phase's deletion and again after, and compare.
A phase whose live pass has not run is not done, however green the check.

**Surfaces that must be in every pass** (extend, never shrink): sign-in, the gallery of a Memory with media,
a Memory's chapters and trash tabs, the sidebar with a pinned favourite, settings, a public share link, and —
for the `admin-web` phase — `/login`, the tenant list and a tenant's detail panel. **Both colour schemes**, and
at least one pass at phone width, because the dead sheets contain responsive blocks too.

### The predicate is a work-list, never an authority

Re-derive it; do **not** trust 461 or 425 from the row. The method that produced them, so it can be rebuilt
rather than guessed:

- **Defined side** — class selectors under `src/styles/` plus the entry `src/style.css`. Vendor names are
  *harvested* from `node_modules` via the dependencies in `package.json` (PrimeVue + primeicons give ~1520
  names under the `p-`/`pi-` prefixes); nothing is hardcoded.
- **User side** — literal `class="…"` in `.vue` templates **plus string literals and object keys in `<script>`
  blocks**. That second half is not optional: it is the only thing that keeps `router-link-active`,
  `veyq-dark`, `mobile-nav-item-active`, `is-on` and about eight others out of the delete list, and they are
  all live.
- Known blind spots, which is why a hit is a *candidate*: a class composed in a template literal
  (`` :class="`is-${status}`" ``) shows only its static prefix, and a compound selector whose first part is a
  component tag can be styling a child's root element from the parent.

**Treat every class that turns out to be alive as a finding about the predicate, not as an exception to wave
through** — tighten the predicate and re-run, so the correction protects the remaining phases instead of just
that one class.

## Phases

Staged by risk, deliberately: the earlier a phase, the less its deletion can be wrong.

- [ ] 1 web — **provably dead by an absent dependency.** The `.cm-*` CodeMirror block in `memory.css`, and any
      other vendor block whose package is not in `web/package.json`. ·
      Done when: `grep -ri codemirror web/package.json web/src` returns nothing, `cd web && npm run check`
      green, live pass clean.
- [ ] 2 web — **whole dead sections and whole dead files**, where every class in a block is a candidate.
      Deleting a section is more reviewable than picking classes out of a live block, so this phase takes all
      of them before any phase touches a mixed block. ·
      Done when: `npm run check` green, live pass clean.
- [ ] 3 web — **the remainder, one stylesheet per commit**, with the live pass between commits rather than at
      the end of the phase. ·
      Done when: `npm run check` green, live pass clean per sheet, and the re-run predicate is near zero.
- [ ] 4 admin-web — the same predicate and the same staging over `admin-web/src/styles/` + `admin-web/src/style.css`. ·
      Done when: `cd admin-web && npm run check` green, live pass clean.
- [ ] 5 docs + the guard question — close #136, #137 and #146 in `docs/inbox.md`, correct anything the
      styleguide now states falsely, and **re-ask #140**. ·
      **Entry condition for the guard half, phrased against what this phase would change:** the re-run predicate
      returns few enough hits that a guard is green on the tree this CR leaves behind. If it does not, #140's
      answer stands unchanged and this phase writes that down with the new number — **a guard is not owed by
      this CR**, and shipping one that starts red would repeat exactly the mistake #140 refused.

## Locator — run these, do not trust a count from this file

```bash
# the corpus
find web/src/styles admin-web/src/styles -name '*.css' | xargs wc -l
grep -c . web/src/style.css admin-web/src/style.css

# the classes that live only through script — these must survive
grep -rn "router-link-active\|veyq-dark\|is-on\|-active'" web/src --include=*.ts --include=*.vue | head -40

# the vendor prefixes, derived rather than assumed
grep -n '"primevue"\|"primeicons"\|"@primeuix' web/package.json admin-web/package.json

# what the styleguide claims about the sheets
grep -rn "styles/" docs/crosscutting/ui-web-styleguide.md
```

## Definition of done

`cd web && npm run check` and `cd admin-web && npm run check` green — **necessary and insufficient, see above**.
Every phase's live before/after pass has run over the named surfaces, in both colour schemes, and any
difference found was a deletion reverted rather than a difference accepted. `python3 scripts/validate_*.py`
green. Rows #136, #137 and #146 closed in `docs/inbox.md`; #140 either closed with the same answer and a fresh
number, or reopened as a guard that was **seen green on the untouched tree first**.

## Retrospective

<!-- filled at close -->
