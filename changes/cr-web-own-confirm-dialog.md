---
id: cr-web-own-confirm-dialog
title: the app's own confirmation dialog replaces window.confirm, and the sessions panel names the action its copy quotes
status: in-progress
satisfies: ["uc.account.sessions"]
adrs: ["0058"]
invariants:
  - no new mechanism — the dialog is DeleteNodeDialog's shape lifted into `shared/ui/`, driven by a wrapper with useToast's shape
  - the inline two-step confirm stays; this CR retires the NATIVE dialog only
  - "`admin-web/` is not touched — ADR 0065 makes its three sites a port, i.e. its own CR"
---

## Goal

Every destructive action outside the memory tree confirms through the **browser's** dialog: a grey OS box with
"OK"/"Abbrechen", no danger colour, no product voice, and — on the sessions panel — carrying the longest and
most consequential sentence the app owns (losing a device's saved key means the Emergency Kit). The app already
has the right shape one directory over: `DeleteNodeDialog.vue` is a PrimeVue `Dialog` with the styleguide's
`.veyq-dialog-action-row`, a filled `severity="danger"` primary and an inline error `Message`. It was simply
never generalised, and `SessionsPanel.vue:31` wrote the gap down as a convention: *"Confirmation is
`window.confirm`, as on that panel's per-row destructive action"*.

Two smaller defects on the same screen travel with it, both found from the same screenshot:

- The device list and the muted hint below it sit flush against each other, because `.management-list` carries a
  `margin-top` and nothing below, and `.settings-muted` is `margin: 0`.
- The hint tells the reader to use *"Abmelden und gespeicherten Schlüssel entfernen"* — a label that appears
  **nowhere on screen**. The escalation is an icon-only shield button whose name lives in `aria-label`/`title`,
  and it is offered only on rows for *other* devices the register knows to hold a served wrap
  ([ADR 0058](../docs/decisions/0058-revoke-never-takes-key-material.md), `deviceRegister.ts:277` excludes
  `thisDeviceId`). On an account with one device the sentence names an action that cannot exist there.

## Context & constraints

- **Three confirm mechanisms exist today and only one is wrong.** The native dialog (this CR's target), the
  **inline two-step confirm** (a label revealed in the row: `groups.removeConfirm`, `groups.deleteConfirm`,
  `kit.backupDownloadConfirm`, `memory.buckets.deleteBucketConfirm` [M: 2026-09-20]) and the **dedicated
  dialog** (`DeleteNodeDialog.vue`). The inline one is deliberate and stays — it is the in-row, low-ceremony
  form. Only the native one goes.
- Styleguide that governs the new component:
  [`docs/crosscutting/ui-web-styleguide.md`](../docs/crosscutting/ui-web-styleguide.md) § *Buttons & dialog
  actions* — one shared `.veyq-dialog-action-row`, cancel/secondary left, the destructive primary a **filled
  `severity="danger"`** on the right, "destructive = danger with an **explicit label**". That last clause is
  also the argument for item (b) below.
- Use-case this strengthens: [`uc.account.sessions`](../docs/features/account/use-cases/uc.account.sessions.md)
  § *Cutting a device off completely is therefore a second, separately named action* — **"separately named"** is
  the criterion the icon-only button half-keeps. Its coverage criterion is
  `removing-a-device-key-is-a-named-escalation`.
- Surfaces touched: `web/` only — two new files in `shared/`, four components, four component tests, one
  stylesheet, `i18n/index.ts` (both locale blocks), `eslint.config.js`, plus two docs and one coverage file.
  No contracts, no backend, no `admin-web/`.
- The measurement this CR rests on, re-runnable — **two angles, keyed on different things**:
  ```
  grep -rn "window\.confirm" web/src | grep -v '\.test\.ts\|/test/'     # angle 1: the call sites (+2 comments)
  grep -rho "t('[a-zA-Z.]*Confirm'" web/src | sort -u                   # angle 2: the copy keys
  grep -rln "window\.confirm" web/src | grep '\.test\.ts'               # the tests that stub it
  ```
  Angle 2 returns strictly more than angle 1, and the delta is exactly the inline-confirm keys above — which is
  what makes it a check rather than a second paraphrase of angle 1.

## Decisions

Every settled decision carries the line that settles it. The one this CR leans on hardest is **Decision 1** —
spot-check that `DeleteNodeDialog.vue`'s footer really is the styleguide shape before generalising it.

### Settled

1. **Own component + own wrapper, not PrimeVue's `ConfirmationService`.** New
   `web/src/shared/ui/ConfirmDialog.vue` (PrimeVue `Dialog` + `.veyq-dialog-action-row`, i.e.
   `DeleteNodeDialog.vue`'s footer verbatim) and `web/src/shared/state/useConfirm.ts`. PrimeVue's service would
   have to be re-templated through its `#container` slot to satisfy the styleguide anyway — at which point its
   one advantage is gone and a second dialog service remains.
2. **The wrapper's shape is `useToast.ts`'s shape, deliberately.** That module is the house precedent for "a
   thin wrapper that fixes app conventions so call sites pass only i18n strings": service-ish state in
   `shared/state/`, its surface mounted once in `App.vue` beside the two `<Toast>`s.
3. **`ask()` returns `Promise<boolean>`, so the call sites keep their shape.**
   `if (!(await ask({ … }))) return` replaces `if (!window.confirm(…)) return` — one line per site, and the
   guard clause that makes a declined confirmation a no-op stays visible at the site instead of moving into a
   callback.
4. **Single-flight: a second `ask()` while one is open resolves `false` and leaves the open dialog alone.** The
   alternatives are worse in the same direction — a queue makes a double-click ask twice, and replacing the
   open request silently re-points a "yes" at a different action. Every caller is a user-driven click, so a
   concurrent ask is a bug, and `false` is the safe reading of it.
5. **The dialog does NOT own the mutation.** It resolves and closes; the busy state stays on the row button
   that already has `:loading` (`SessionsPanel.vue`'s `busy`/`escalating`). The styleguide's "disable
   confirm/cancel while the mutation runs" applies to a dialog that runs the mutation itself (DeleteNodeDialog);
   pulling the mutation into this one would mean passing every call site's async action through it.
6. **Each site gets a header and an accept label; the existing `*Confirm` sentence stays the message.** The
   accept label **reuses the existing action label key** wherever one exists (`sessions.revoke`,
   `sessions.escalate`, `members.remove`, …) — a dialog whose primary reads differently from the button that
   opened it is a new way to be wrong. Headers are new keys (`*ConfirmTitle`), both locale blocks. The exact
   set is discovered at implement from the locator above; it is not frozen here.
7. **ESLint `no-alert: 'error'` for `web/src`, with the test files exempted** the same way the existing config
   exempts them from `no-explicit-any`. It is the guard that keeps the swap from being a one-time sweep, and it
   sits in the config that already describes itself as *"a baseline guardrail, not a strictness sweep"*.
   `admin-web/eslint.config.js` gets it only when its own port lands (§ *Explicitly not in this CR*).
8. **The spacer is a shared rule in `settings.css`, never a scoped block:**
   `.management-list + .settings-muted { margin-top: 0.75rem }`, symmetric to `.management-list`'s own
   `margin-top: 0.75rem` (`settings.css:647`, `:869` [M: 2026-09-20]). The sessions panel is where it was seen;
   it is the pattern that is wrong, not that component.
9. **(b) The escalation gets a visible label, and it is a SHORT one: "Sign out + remove key" / "Abmelden +
   Schlüssel entfernen"** (new key, `sessions.escalateShort`). The full sentence stays as the accessible name,
   the dialog header and the hint's subject. Rationale: the full label is 44 characters in DE and cannot sit in
   a row that already carries up to three actions; a label that names only the key drops the half that costs
   the session. Both halves survive in ~30 characters.
10. **(a) The hint is rewritten to describe when and where the action appears, and to quote the label a reader
    can actually see** (Decision 9's short one). Both locales. The rewrite must carry two facts the current text
    omits: it appears on **other** devices' rows, never your own (the local equivalent is untrust/forget on the
    device panel), and only where this app knows that device holds a saved key.
11. **The plain revoke stays icon-only.** The sign-out glyph is conventional and the row would otherwise carry
    two long labels; the escalation has no conventional glyph and is the one the copy names. If a later CR wants
    symmetry, it labels both — but that is a density change across every management row, not this CR.
12. **`SessionsPanel.vue:31`'s comment is part of the deliverable.** It states the retired convention as current
    house style; left alone, the next panel copies it back in.
13. **The coverage criterion `removing-a-device-key-is-a-named-escalation` gains a visibility clause** —
    *named **visibly** in the row, not only in its accessible name* — rather than letting (b) ride under the
    existing text [decided 2026-09-20]. Without the clause there is nothing for a revert of the label to redden:
    B4/B5 only guard the sentence that claims it, and a test whose criterion does not mention what it asserts is
    the exact shape `validate_coverage.py`'s own docstring calls out as beyond its reach. It adds no test file —
    the new cases live in the already-cited `SessionsPanel.test.ts`. The clause names the *visible* name and
    keeps the accessible one, so a future CR that shortens the row cannot satisfy it by deleting the label and
    leaving a tooltip.

### Open

- None. The one candidate was closed above (13).

### Explicitly not in this CR

- **`admin-web/`'s three sites** (`features/tenants/TenantDetailPanel.vue:94,113,132` [M: 2026-09-20]). ADR 0065
  gives that package no shared UI, so they need a **port** of the component — a copy with its own life, its own
  test and its own `no-alert` line. Doing it from here would make an admin-web regression this CR's, and the
  port is only worth writing once the dialog's shape has survived one surface. → `docs/inbox.md`.
- The inline two-step confirms (§ Context). Unifying the three mechanisms into two is a separate judgement.
- `SlideToConfirm.vue:8`'s comment says the current pattern for a destructive confirm is `window.confirm`. It is
  a *dated* observation inside a rationale, not a live convention — correct it only if phase 1 leaves it reading
  as present tense.

## Phases

- [x] **1 the dialog, the sweep and the guard** — new `web/src/shared/ui/ConfirmDialog.vue` +
  `web/src/shared/state/useConfirm.ts` (Decisions 1–5) with `useConfirm.test.ts` and `ConfirmDialog.test.ts`;
  mounted once in `App.vue` beside the toasts. Every site from the locator swapped (Decision 3, 6), new header
  keys in both locale blocks, `SessionsPanel.vue:31`'s comment corrected (Decision 12). The four test files that
  stub `window.confirm` drive the dialog instead. `eslint.config.js` gains `no-alert` (Decision 7).
  · Done when: `npm run check` green in `web/`; `grep -rn "window\.confirm" web/src` returns nothing outside a
  historical comment; the `no-alert` guard seen red by B3's lever before it is believed.
- [x] **2 the sessions panel says what it offers** — `settings.css` gains Decision 8's rule; the escalate button
  gains `sessions.escalateShort` as a visible label while keeping its full accessible name (Decision 9);
  `sessions.hint` rewritten in both locales (Decision 10); new cases in `SessionsPanel.test.ts` for B4/B5.
  · Done when: `npm run check` green; the gap between list and hint **measured** in the running dev server
  (`getBoundingClientRect`, not eyeballed) on both sessions sections; the row read back at desktop and at an
  emulated phone width to confirm the labelled button does not push the row's actions into a second line.
- [x] **3 docs** — `docs/crosscutting/ui-web-styleguide.md` § *Buttons & dialog actions*: the three mechanisms,
  which one is retired, and that `no-alert` is what enforces it; one line added to § *Checklist*.
  `docs/coverage/features/account/use-cases/uc.account.sessions.yaml`: Decision 13's clause on
  `removing-a-device-key-is-a-named-escalation`, plus the new test cases listed under the criterion they serve. `uc.account.sessions.md` needs **no** change — its
  *"offered on every row but your own"* already matches the code (`deviceRegister.ts:277`), which is why (a) is
  a copy defect and not a behaviour one.
  · Done when: `for f in scripts/validate_*.py; do python3 "$f"; done` green (≈5 min, start it in the
  background); `python3 scripts/validate_coverage.py` green with the new refs resolving.

## Bite-tests — per test, the method and the lever

| # | assertion | method | lever |
|---|---|---|---|
| B1 | `ask()` resolves `true` on accept, `false` on cancel, Escape and dismiss | live defect | write `useConfirm.test.ts` before the module exists → red on the untouched tree |
| B2 | a declined confirmation runs **no** mutation at the call site | injection | after the swap, delete the `if (!(await ask(…))) return` guard in `revoke()` → the declined case reddens by name, the accepted case stays green |
| B3 | `no-alert` actually bites | injection | add `window.confirm('x')` to a `src` file, run `npm run lint` → red; remove. Without this the guard is a line of config nobody has seen fail |
| B4 | the escalation's **visible text** names the action (not only its `aria-label`) | live defect | assert on rendered text before phase 2 → red; the lever afterwards is reverting to icon-only |
| B5 | the hint's quoted label and the button's label are the **same string**, in both locales | injection | change `sessions.escalateShort` in one locale only → red. This is the guard that keeps (a) and (b) from drifting apart in a later copy edit |
| B6 | single-flight: a second `ask()` while one is open resolves `false` and does not replace the open request | injection | make the store overwrite its pending resolver → the first request's promise never settles → red |

## Definition of done

- Phases 1–3 checked; every bite seen red by its own lever and recorded in the retrospective.
- `npm run check` green in `web/` (lint + typecheck + test + build).
- `grep -rn "window\.confirm" web/src | grep -v '\.test\.ts'` → empty (a comment may survive only if it reads as
  history; see § *Explicitly not in this CR*).
- `for f in scripts/validate_*.py; do python3 "$f"; done` green — **the baseline is the command, not a number**:
  run `ls scripts/validate_*.py | wc -l` before the first edit and again at the end; this CR adds no guard, so
  the two must agree.
- `admin-web/` provably untouched: `git diff --name-only main -- admin-web/` empty at the end, and the port
  filed in `docs/inbox.md`.
- Both locale blocks checked for every key added or removed (`grep -rho "t('sessions\.\w*'" web/src | sort -u`
  against both blocks — the styleguide's rule, because a missing i18n key fails only at runtime).
- The harvest before deletion: what this CR taught goes to the line it binds — a trap to a comment beside the
  code, a working lesson to `changes/README.md` — never to git.

## Machine hints — hold regardless of the CR's final shape

- The web dev server runs on `veyq.localhost:8081` with the S3 env from `.run/Run Backend.run.xml`; never start
  a second `quarkusDev` in `backend/`.
- Read the spacing back with `javascript_tool` (computed styles, bounding boxes), not screenshots — the whole
  diagnosis above was text, and a screenshot is the most expensive way to ask "is there a gap".
- Reproducing the *absent* escalation needs a second device holding a served wrap; the panel's own tests already
  construct that state, so B4/B5 belong in `SessionsPanel.test.ts` rather than in the browser.
- System python is 3.9; this CR adds no `scripts/*.py`.

## Retrospective

**What is built (commits on `worktree-cr-web-own-confirm-dialog`):** phase 1 `8e079cc`, phase 2 `e3bce91`,
phase 3 with this file. `npm run check` green in `web/` after each phase (1033 tests, 10 e2e files skipped for
lack of a backend); guard baseline `ls scripts/validate_*.py | wc -l` = **32** before the first edit and 32 at
the end, as this CR adds none. All six bites were seen red by their own lever, each restored by
snapshot-and-restore: B1 (force every settle to `true` -> 4 cases red), B2 (drop the `if (!(await ask(...)))`
line in `revoke()` -> the declined case red, the accepted ones green), B3 (`window.confirm('x')` in a `src`
file AND in a test file -> `no-alert` red in both), B4 (escalation back to icon-only -> the visible-text case
red), B5 (the DE label edited alone -> the hint case red), B6 (let a second `ask()` overwrite the pending
resolver -> the first promise never settles, red at the timeout).

**The one done-when still open.** Phase 2 asks for the row read back at desktop and at an emulated phone width.
The dev backend was not running and restarting it is the owner's, so the owner chose the no-login route: the
spacer was measured against the real stylesheet chain in a browser (0px -> 12px, on BOTH sessions sections), and
the wrap question was left to a live check. What the measurement DOES say about it, so the live check knows what
to look for: at 0.875rem in the app's font the new DE label is **208px**, the full sentence it replaces is
**320px** — wider on its own than the ~319px a row's action strip gets inside a 375px viewport, which is
Decision 9's 44-character argument in pixels. But label + icon + padding + the two icon-only buttons still comes
to roughly 337px, so the strip may not fit, and `.management-actions` is `flex-wrap: nowrap` — it would overflow
rather than wrap. **If the live read-back confirms it, the remedy is one rule in settings.css's <820px block
(`.management-actions { flex-wrap: wrap }`), not a shorter label** — below that breakpoint the row is already
single-column and left-aligned, so a second line there is the layout doing what it already does.

**Planning miss, and it is the interesting one: the CR carried a locator for the mechanism it REMOVES and prose
for the mechanisms it KEEPS — and the prose was wrong.** § Context names three mechanisms and says the dedicated
dialog is `DeleteNodeDialog.vue`. There are three of those (plus `DeviceTrustPanel.vue:100` and
`MemoryDetail.vue:645`), each hand-rolling the same `Dialog` + `.veyq-dialog-action-row` + `*ConfirmTitle`
shape. The same slip one size down: "the four test files that stub `window.confirm`" — there are five, the
fifth being `LoginView.test.ts`, which stubs it because it MOUNTS `LockedSignOut`. Neither cost much (both are
now inbox #144 and a corrected sweep), but both were found by running a command the CR did not carry, and both
sat in the sections that grade the design rather than in the phase plan. Root cause: the locator discipline was
applied to the CR's subject and not to its context. -> `changes/README.md` gains the rule.

**Second miss, smaller: `*ConfirmTitle` already existed, with a different voice.** Two keys (`untrustConfirmTitle`,
`purgeConfirmTitle`) ask a question and pair with a consequence-only body; this CR's ten reuse the same suffix
but must be imperative, because Decision 6 keeps the existing `*Confirm` sentence — which already asks — as the
message. Decided rather than left: match `memory.deleteFolderTitle`'s imperative voice, do not rewrite ten of
the most consequential sentences in the app to fit a naming convention, and record the split in inbox #144 so a
later unification weighs it once instead of rediscovering it.

**Deviation from Decision 7, deliberately:** `no-alert` is NOT exempted for test files. After the swap no test
stubs a native dialog, so the exemption would have had zero users — config nobody has seen fail, which is the
thing B3 exists to prevent — and a fixture is the easiest place to reintroduce one. Bitten in both directions.

**Next time:** when a CR sorts the code into kinds, carry a locator for every kind it names — the one it changes
and the ones it spares — because the sparing is a completeness claim too, and it is the one nothing re-checks.
