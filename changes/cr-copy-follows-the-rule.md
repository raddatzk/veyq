---
id: cr-copy-follows-the-rule
title: Every UI text follows the copy rule — the owner's text pass lands first, then both catalogs are swept
status: planned
satisfies: []
adrs: ["0086"]
invariants:
  - no screen changes what it does; only what it says changes
  - EN and DE change together; `const de: typeof en` keeps both blocks on the same keys
  - a key is deleted only after the styleguide's grep over both locale blocks finds no reader
  - the fixed legal texts are out of scope (`contracts/moderation/*.json`, `contracts/terms/*.json`): they are drafts for counsel and change only through their own decisions
  - what the reader should keep safe (a Secret Key, a recovery code, an Emergency Kit, the config-secret key) keeps the explanation the rule's exception gives it
---

## Goal

The owner set a copy direction on 2026-10-23, and it is now a rule: [ui-web-styleguide](../docs/crosscutting/ui-web-styleguide.md)
§ *Copy: short, and it says what happens*. A text says what happens and what it costs the reader, usually in one
sentence. It does not reassure. A consequence the reader cannot see stays, in a clause. What the reader should keep
safe gets an explanation sized to what its loss costs. The rule holds for `src/web/` and `src/admin-web/`.

The texts written before the rule do not follow it. The owner has started a pass by hand (worktree `veyq-i18n`, below).
This CR lands that pass and then sweeps the rest, so the rule is true of the shipped texts rather than only of the
styleguide. It exists so the sweep is not forgotten (owner 2026-10-23).

## Context & constraints

- **The owner's pass.** Worktree `.claude/worktrees/veyq-i18n`, branch `i18n` at `35aed256` (2026-10-21), **uncommitted**:
  9 files, +27/−37, last edited 2026-10-21 10:22. They are `contracts/platform/capabilities.json`, both
  `i18n/index.ts`, both `PasswordBreachNotice.vue` (the field's HIBP note removed), and four tests that asserted that
  note. A probe merge against `main` at `e780bb74` (`git merge-tree --write-tree`, nothing touched) merged 7 files
  cleanly. It conflicted in the 2 `PasswordBreachNotice.vue`: `main` turned `<Message severity="warn">` into
  `<VeyqMessage tone="warn">` (ADR 0107) on the line after the removed note. **Re-check that the worktree is still
  uncommitted before phase 1**; if the owner landed it themselves, phase 1 shrinks to its review items.
- **Review items on the owner's pass** (orchestrator, 2026-10-23, against the rule):
  - EN wording: "Whatever this **entity** offers", "**Regarding** where this server stores …", "secrets**..**",
    "**implicitly** disabled/deactivated"; DE: "Was diese Instanz überhaupt anbietet, abschalten wirkt sich sofort aus."
  - The public-link switch's hint lost its unseen consequence: that nothing is deleted and links work again once it is
    back on. The rule keeps that, in a clause.
  - The config-secret key's panel (`sub`), agreed with the owner 2026-10-23:
    - DE: *Geheimnisse, die der Server selbst braucht, etwa das SMTP-Passwort eines Tenants, versiegelt er unter einem
      Konfigurationsschlüssel, den er beim ersten Start erzeugt. Geht der Schlüssel verloren, musst du sie alle neu
      eingeben; bis dahin sind auch die Medien nicht erreichbar. Diese Anzeige sagt, ob er noch da ist und ob er der
      richtige ist.*
    - EN: *Secrets the server needs itself, such as a tenant's SMTP password, are sealed under a config key it creates
      on first start. If the key is lost, you have to enter all of them again; until then, media are unreachable too.
      This shows whether the key is present and is the one they were sealed with.*
  - Removing the HIBP field note is sound: the privacy notice names the check, its recipient and its switch
    (ADR 0093 § Legal basis, Still open 7), and no use-case asks for the note at the field.
- **Two CRs write the same catalogs** (`changes/README.md` § two CRs that write the same page name each other).
  `cr-scope-rotation-rekeys-nodes` rewrites `rotation.*` and the site's `account/replace-your-keys` to the rule, so
  this CR's sweeps skip `rotation.*`. `cr-node-header-actions-by-priority` adds header keys. Phase 1 here lands
  first, before either, because it carries the owner's uncommitted work.
- **The site.** A Use page that quotes a UI text (a label, a button) changes with it, EN and DE (`changes/README.md`
  § the published docs move with the change). A page's own prose is not in scope.

### Inventory (measured on `main` at `e780bb74`; re-run at each phase)

| Catalog | EN texts | Reassurance candidates |
|---|---|---|
| `src/web/src/i18n/index.ts` (core) | 1,482 | 57 |
| `src/web/src/features/memories/i18n.ts` | 223 | 2 |
| `src/web/src/features/depot/i18n.ts` | 144 | 0 |
| `src/admin-web/src/i18n/index.ts` | 598 | 27 |
| `contracts/platform/capabilities.json` | 38 (16 over 200 characters) | 1 |

Locators:
- EN texts in a catalog (EN half only): `d=$(grep -n -m1 '^const de' <file> | cut -d: -f1); head -n $d <file> | grep -cE "^ +\"?[A-Za-z0-9_]+\"?: *['\"\`]"`
- Reassurance candidates (a lead, not a verdict; each hit is read):
  `R='ciphertext|encrypt|sealed|never (the|shown|sees|leaves|reads)|operator can|server (never|cannot|can.t|sees)|only you|safe|secure|private to'; head -n $d <file> | grep -niE "$R"`
- `capabilities.json`: walk every object with `en`/`de` strings; count, length over 200, and the same pattern.
- Length is a second lead: an EN text over 200 characters is read against the rule.

## Decisions this CR takes

1. **The rule decides, not taste.** Each changed text cites which bullet of § *Copy* it follows. A text that already
   follows the rule stays, however it is worded.
2. **One consequence clause survives where the reader cannot see the effect on others** (links, guests, members).
3. **The exception is applied, not widened.** Only the four named things get an explanation of a loss.
4. **No new keys for the sweep.** A shorter text replaces the old value under the same key. A key whose text becomes
   empty is deleted, after the grep.

## Open questions (owner)

- **Q1: Does the owner review each sweep's texts before merge?** **Recommendation:** yes. Each sweep phase presents
  a before/after table (key, EN, DE, the rule bullet) in its session, and merges after the owner's answer. Texts are
  the product's voice; a rule is not enough to decide every one.
- **Q2: Does the sweep cover the site's Use pages' own prose?** **Recommendation:** no. The rule is a UI rule; the
  site explains on purpose. A Use page changes only where it quotes a changed UI text.

## Phases

- [ ] **1 the owner's pass**: commit the `veyq-i18n` worktree's changes onto `main` with the owner's consent, in their
  name or as theirs (ask). Resolve the two conflicts: take `main`'s `VeyqMessage` line and drop the note. Apply the
  review items above, and show them to the owner first. · Done when: `npm run check` in `src/web/` and `src/admin-web/`
  green; every `scripts/validate_*.py` except `validate_guard_gaps.py` green; the worktree is clean (the owner removes
  it or keeps it).
- [ ] **2 admin-web and `capabilities.json`**: the sweep (Q1). · Done when: each inventory hit is changed or kept with
  its reason in the phase's table; checks green; a browser pass over the console's changed screens, light and dark.
- [ ] **3 web (core, Memories, Depot)**: the sweep, `rotation.*` skipped. · Done when: as phase 2, over the web's
  changed screens and the site pages that quote them.

## Definition of done

Every phase's Done-when; the inventory's reassurance candidates are each changed or kept with a reason; the site's
quoting pages match; this CR deleted after its lessons are moved where they bind.

## Retrospective

_Filled at implementation._
