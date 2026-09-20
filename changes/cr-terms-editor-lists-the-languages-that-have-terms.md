---
id: cr-terms-editor-lists-the-languages-that-have-terms
title: The terms editor lists the languages that carry terms, and the template asks before it writes
status: planned
satisfies:
  - uc.admin.tenant-terms
adrs: []
invariants:
  - All docs in English; the published wiki is EN+DE. The terms CONTENT is the tenant owner's, in either locale.
---

## Goal

`web/src/features/tenant/TenantTermsPanel.vue` renders the DE and the EN textarea unconditionally, so a tenant
that has terms in one language shows an empty box for the other with nothing saying whether that is deliberate.
`insertTemplate()` drops an 11-clause template into *every* empty locale at once, silently skipping the ones
that already hold text — restraint the owner never asked for and cannot see.

The wanted shape is the one
[`LocaleMarkdownEditor.vue`](../web/src/features/memory/components/LocaleMarkdownEditor.vue) already has for
share notes: a list of the languages that exist, a row that opens an editor dialog, and an explicit act to add
a language that is missing. This CR lifts that component to `shared/ui/` and rebuilds the terms panel on it.

## Context & constraints

- [uc.admin.tenant-terms](../docs/features/admin/use-cases/uc.admin.tenant-terms.md) is the governing doc and is
  **stale in four places** — its goal bullet, main-flow step 2, § *What the terms are here* and its
  *"No terms set yet"* alternate flow all describe terms as *assembled from a clause template*. That was true of
  the pre-E2EE editor. The correction is part of this work, not a follow-up, and it names the **shape** of the
  new behaviour rather than only striking the old sentence.
- **No contracts phase and no backend phase.** `LocalizedMarkdown` (`contracts/api/_common.openapi.yaml`) already
  carries optional `de`/`en` with `maxLength: 20000`, and `TenantTerms` / `TenantTermsUpdate`
  (`contracts/api/tenants.openapi.yaml`) already express "a locale may be absent". The wire already says what
  the UI is about to start meaning.
- The locale set is bounded at two (`SUPPORTED_LOCALES` in `web/src/i18n/index.ts`). `LocaleMarkdownEditor`
  carries its own private `ALL_LOCALES` and a hand-written `l === 'de' ? … : …` label — a **third** copy of a
  two-element set. The lift collapses it.

### The decision this CR rests on, and its counter-argument

**Lift, do not copy.** The component has no runtime coupling to the memory feature — one *type-only* import of
what is literally `Record<string, string>` — and it already reads its language labels from the **`terms.*`**
i18n namespace, so a memory-feature component already depends on tenant-terms strings. It is not share-owned; it
is merely stored there.

The honest counter-argument, recorded so the next reader weighs it instead of re-deriving it:
`docs/ai/README.md` § *Spike when the shape is unclear* says the clean shared abstractions in this repo came
out of **five** real usages, not a guess. This is two. The lift is therefore taken with a **minimal** props
surface driven by what the terms panel actually needs — not a general-purpose editor invented for a third
caller nobody has.

### Two shapes that are wrong and look right

1. **The panel's data shape is the real work, not its location.** `TenantTermsPanel` holds `de` and `en` as two
   flat refs and flattens the DTO into them on load. In that shape `''` and *absent* are indistinguishable —
   which is precisely the defect. Moving to a `LocaleMap` where **presence of a key means the language exists**
   is what makes "list only the languages that carry terms" expressible at all. A CR that lifts the component
   and keeps the two refs has moved a file and fixed nothing.
2. **"Add a language" is a choice of at most one.** With two supported locales, the dropdown holds exactly one
   option or zero. A `Select` plus an *Add* button is a control for a choice that does not exist; the honest
   form is a single button naming the language it would add, and it disappears when both exist.

## Phases

- [ ] 1 web — lift `LocaleMarkdownEditor.vue` to `web/src/shared/ui/`, give it the props surface the terms panel
      needs (`:locales`, neutral i18n keys, a row-actions slot, a vetoable remove), collapse its private locale
      set onto `SUPPORTED_LOCALES`, and repoint `ShareCreateDialog.vue`. **The share note's behaviour does not
      change in this phase.** ·
      Done when: `cd web && npm run check` green, and the component has its first test (every `shared/ui`
      component in this repo owns a colocated one — this component has **none** today, and its only caller stubs
      it out, so phase 1 is also the first time its behaviour is pinned at all).
- [ ] 2 web — rebuild `TenantTermsPanel.vue` on it: `LocaleMap` instead of two refs, rows only for locales that
      exist, an explicit add, and a template insert that **asks** — including when it would overwrite. ·
      Done when: `cd web && npm run check` green.
- [ ] 3 docs + coverage — correct the four stale passages in `uc.admin.tenant-terms.md` (name the new shape, do
      not merely strike the old sentence), and its plain-language EN+DE section with them. Coverage YAML. ·
      Done when: `python3 scripts/validate_coverage.py` + `python3 scripts/validate_doc_links.py` green.

## Locator — run these, do not trust a count from this file

```bash
# every user of the component and of the type it takes
grep -rn 'LocaleMarkdownEditor\|LocaleMap' web/src

# every place the locale set is spelled out
grep -rn 'SUPPORTED_LOCALES\|LOCALE_LABELS\|ALL_LOCALES\|localeDe\|localeEn' web/src

# the i18n keys the component carries today, which phase 1 makes neutral
grep -n "noteSelectLang\|noteAddLanguage\|noteEmpty\|noteEditTitle\|noteMarkdownLabel\|notePlaceholder\|noteRemoveLang" web/src/i18n/index.ts

# the prose that still promises a clause template
grep -rn -i 'clause template\|Bausteine\|building blocks' docs/ wiki/
```

## Bite tests — per test, the lever that reddens it

1. **A locale with no terms is not rendered as an empty box.** **Lever: injection** — seed the panel with terms
   in one locale only and assert exactly one row; then make the panel render from `SUPPORTED_LOCALES` instead of
   from the map's keys and watch it go red. This is the test that distinguishes the real fix from a lift.
2. **An empty string is not the same as a missing language.** Seed a locale whose value is `''` and assert it is
   rendered as an existing-but-empty language, not as absent. **Lever:** collapse the map with a
   `filter(v => v)`; red. This pins the distinction phase 2 exists to create, which no test can see from the
   outside otherwise.
3. **Insert asks, and asks again when it would overwrite.** Two cases, not one: an empty locale and a locale
   that already holds text. **Lever:** restore the silent `if (!trim()) …` skip; the overwrite case goes red
   while the empty case stays green — which is what says the dialog is about the overwrite and not about the
   insert.
4. **The share note still behaves as it did.** A phase-1 control: the lifted component, driven as
   `ShareCreateDialog` drives it, produces the same map for the same input. **Lever:** make one of the new props
   required without a default; red. Without this the lift has no way to prove it was behaviour-preserving for
   the caller it did not set out to change.

## Definition of done

`cd web && npm run check` green; `python3 scripts/validate_coverage.py` green; the four stale passages in
`uc.admin.tenant-terms.md` are corrected by shape, not struck.

**Structural invariant, promoted to a guard:** this CR rests on *"one locale-markdown editor exists, and it
lives in `shared/ui/`"*. Its one-shot proof —

```bash
grep -rln 'LocaleMarkdownEditor' web/src/features/ --include=*.vue   # imports only, never a definition
```

— becomes a permanent check, written so it derives its anchor from the component's own filename rather than
listing the callers. Run the predicate against the **untouched** tree first and record what it returns there; a
guard that is green before the work is a guard that proves nothing.

## Retrospective

<!-- filled at close -->
