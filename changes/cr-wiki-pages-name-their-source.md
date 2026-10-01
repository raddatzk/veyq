---
id: cr-wiki-pages-name-their-source
title: Every published wiki page names the docs it speaks for, and those docs name it back
status: in-progress
satisfies: []
adrs: []
invariants: ["self-hostable by default"]
---

## Open for the owner

**Second round answered by the owner, 2026-10-01: every recommendation added since the first answer is taken too** (questions added by the 2026-09-28 second refine wave). Rows those questions proposed exist as #222–#232.

**Answered by the owner, 2026-09-28: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists (#197–#221, `docs/inbox.md`).

1. **The wiki is not what the docs say it is. Which side moves?** `docs/features/README.md` says the wiki is
   *"DERIVED from these sections"*, the use-cases' bilingual *What this means for you*, by *"a Python generator
   … later"*. `validate_feature_format.py` check E justifies itself with that sentence: *"a missing half is a
   missing wiki page"*. The measured reality:
   - No generator exists.
   - All 11 pages are hand-written topic pages, the kind that README calls *"standalone safety pages"*.
   - The account-keys page speaks for an **enabler**, which `docs/README.md`'s *"Wiki source?"* column says
     cannot be one.

   **Recommendation: correct the docs to the tree, and do not build the generator.** Under `docs/ai/README.md`
   rule 4, the generator sentence is a claim nothing backs. The wiki then reads as *"hand-written topic pages;
   each names the docs it speaks for"*. The use-cases' bilingual sections stay, still checked by E, as the
   plain-language source material a page draws from, and E's docstring loses its *"missing wiki page"*
   premise. This is a refinement decision, so it lands as Phase 1, before any guard.
2. **Must the doc name the page back?** **Recommendation: yes.** The row wants *"a use-case or enabler change
   [to land] with a visible list of published pages"*, and the one place a change is made is the doc, not the
   wiki. A header only in the wiki page is invisible to whoever edits `en.account.key-model`. Two docs already
   carry such a link: `en.account.key-model.md:27` (and `:213`) and `crosscutting/security-model.md:8`.
   `validate_doc_links.py` already resolves them. The new guard makes the pair symmetric, in the same shape as
   `validate_feature_wiring.py`'s `Serves` ↔ back-reference check.
3. **The form of the page's header.**
   - **Recommendation: an HTML comment on line 1**, `<!-- speaks-for: docs/…md, …; pair: de/….md -->`.
   - A relative link to `docs/` is **dead on the published wiki**: `wiki-sync.yml` publishes `wiki/` alone
     (*"docs/ is repo-only and not published"*).
   - A visible absolute GitHub link works there (`running-your-instance.md:78` uses one). It would put engineering
     docs in front of the plain-language audience on every page, though.
   - That GitHub's renderer hides an HTML comment is **remembered, not measured**. There is no remote to
     publish to, so check it once on the first real sync.

## Goal

Inbox #120. After this CR every page under `wiki/en/` and `wiki/de/` declares which docs it speaks for and
which page is its other-language pair, and a guard checks four things:
- every declared doc resolves;
- the EN/DE pair points at each other and declares the **same** docs;
- a page with no pair says so, allowed for an English operator page only;
- every declared doc under `docs/` links back to the EN page.

The guard does **not**, and cannot, decide whether the page and the doc *claim the same thing*: it would not
have caught the sentence that found this row. What it buys is an address. A change to a doc now shows the
published pages that speak for it.

## Context & constraints

- **Measured on** `faf9ef4`, in worktree `worktree-agent-a988acad05eff2335`, 2026-09-28. Re-run the locators.
- **The pages** (`find wiki -name '*.md' | sort`): 6 EN, 5 DE and `Home.md`.
  - EN and DE file names are translated (`account-keys-and-recovery.md` ↔ `konto-schluessel-und-wiederherstellung.md`),
    so **nothing can pair them mechanically**. Only `Home.md`'s two parallel lists pair them, which is why the
    pair is declared.
  - `running-your-instance.md` has no DE page, **on purpose** (`Home.md`: *"Technical, English only"*).
- **No page names a source today:** `grep -rnE "docs/|\b(uc|en|cc)\.[a-z]|<!--" wiki` finds one absolute
  GitHub link in the operator page and nothing else.
- **Candidate sources**, for Phase 2 to confirm by reading each page against the docs:

  | EN page | Likely speaks for |
  |---|---|
  | `is-my-data-safe` | `docs/crosscutting/security-model.md` |
  | `account-keys-and-recovery` | `en.account.key-model`, `uc.account.recover-access` |
  | `adding-another-device` | `uc.account.onboard-device` |
  | `who-gets-into-your-space` | `uc.spaces.add-member`, `uc.spaces.verify-identity` |
  | `quantum-computers-and-your-photos` | `security-model.md` (its PQ section) |
  | `running-your-instance` | `infra/README.md`. A declared source may live outside `docs/`; the backlink rule applies only to `docs/`. |

- **A second finding: nothing checks the wiki's own links.** `validate_doc_links.py`'s `DOC_ROOTS` is `docs/`
  and `changes/`. The wiki's 36 relative links all resolve today (measured with that guard's own predicate
  pointed at `wiki/`), but no run would notice one breaking.
- **Relates to** #87 (the doc-vs-code drift review) and #108 / ADR 0046 (claims marked measured vs. remembered).
  Sibling: `cr-doc-citations-resolve-from-code` (#122, **planned**) checks ids cited *from code*. This one is
  about pages cited *from the wiki*. They share nothing but "resolve a pointer", so they are separate CRs.

## Decisions

1. **Phase 1 is the docs correction of Q1.** It touches `docs/features/README.md` (the use-case template's
   *What this means for you* note and the *"single source behind `wiki/`"* paragraph), `docs/README.md`'s
   *Wiki source?* column, and `validate_feature_format.py`'s E docstring. Locator:
   `grep -rn -i "derived\|generator\|single source" docs/README.md docs/features/README.md scripts/validate_feature_format.py | grep -i wiki`.
2. **The header** (Q3), on line 1 of every `wiki/{en,de}/*.md`:
   - `speaks-for:` takes repo-relative paths, comma-separated;
   - `pair:` takes the other-language page relative to `wiki/`, or `none — <reason>`.

   `Home.md` is the index and carries none.
3. **The guard `scripts/validate_wiki_sources.py`**, enforcing, per page:
   - (a) the header exists;
   - (b) every `speaks-for` path exists;
   - (c) `pair` resolves, the pair's `pair` points back, and both declare the same `speaks-for` set;
   - (d) `pair: none` only under `wiki/en/`;
   - (e) every `speaks-for` target under `docs/` contains a markdown link to the EN page (Q2).

   The docstring states the limit in the Goal's words.
4. **`wiki/` joins `validate_doc_links.py`'s `DOC_ROOTS`.** It is green on the day it lands, measured above.

## Phases

- [x] 1 docs: Decision 1. · Done when: the trust-chain glob is green, and the locator returns no sentence that
      calls the wiki derived or generated.
      **Measured:** the Decision-1 locator returns nothing. A second angle
      (`grep -rn -i wiki docs/ CLAUDE.md .claude/skills scripts/*.py .github | grep -v docs/inbox.md | grep -i
      "deriv\|generat\|single source\|assembl"`) found three more the locator could not see, all corrected:
      `docs/ai/README.md` § Two documentation audiences (*"Single source, derive the wiki"*) and § Start light
      (*"the wiki generator"*), and a comment in `uc.account.rotate-keys.yaml`. It now returns only
      `wiki-sync.yml:4`, which says the *repo* is the wiki's source of truth, which is true. Glob without
      `validate_guard_gaps.py`: `ran=42 failures=0`.
- [x] 2 guard, red: Decisions 3 and 4, with no headers written yet. · Done when: on the untouched wiki the
      guard fails for **all 11 pages**, each with *no header*, and every other guard is green. Paste the
      output.
      **Measured** (`python3 scripts/validate_wiki_sources.py`, exit 1), each line ending
      `no header — line 1 must be <!-- speaks-for: …; pair: … -->`:
      ```
      wiki/de/ein-weiteres-geraet-hinzufuegen.md · wiki/de/konto-schluessel-und-wiederherstellung.md
      wiki/de/quantencomputer-und-deine-fotos.md · wiki/de/sind-meine-daten-sicher.md
      wiki/de/wer-in-deinen-bereich-kommt.md · wiki/en/account-keys-and-recovery.md
      wiki/en/adding-another-device.md · wiki/en/is-my-data-safe.md
      wiki/en/quantum-computers-and-your-photos.md · wiki/en/running-your-instance.md
      wiki/en/who-gets-into-your-space.md
      ```
      `validate_doc_links.py` with `wiki/` in `DOC_ROOTS`: `all relative links in 202 doc(s) resolve`. The
      guard-gap pins land in Phase 3: `validate_guard_gaps.py` refuses a case whose guard is red on the
      un-forged copy, so they can only be written once the headers exist.
- [ ] 3 anchors: write the 11 headers and the missing backlinks. Read each page against its declared docs
      while doing it. A claim that disagrees is recorded as a new inbox row, not fixed silently.
      · Done when: every `validate_*.py` is green.

## Definition of done

- Before the first edit, run the guard baseline and paste it: `for f in scripts/validate_*.py; do python3 "$f";
  done`. It ends green.
  **Measured** on `7a34c57` (this session ran the sibling CR first; this baseline precedes both):
  `ran=43 failures=0`.
- **Red first:** Phase 2's output.
- **Bites.** Each is snapshot-and-restore, with one lever per check:
  - (b) Point a `speaks-for` at a missing doc.
  - (c) Make the DE pair declare one doc fewer than its EN page.
  - (d) Give a `wiki/de/` page `pair: none`.
  - (e) Delete the backlink from `en.account.key-model.md`.
  - Break one relative link in a wiki page: `validate_doc_links.py` goes red.

  Each is red, naming the page.
- **Pin** (c) and (e) as *rejected* cases in `scripts/validate_guard_gaps.py`. Also pin the limit as an
  *open* case: a page whose prose contradicts its declared doc stays green.
- Inbox #120 → done. This file is deleted after harvest.

## Retrospective

_(filled at implement)_
