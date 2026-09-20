---
id: cr-shell-preferences-into-profile-menu
title: One door — theme and the settings gear move into the profile menu
status: planned
satisfies: [uc.settings.appearance-language]
adrs: ["0022"]
invariants: []
---

## Goal

The app shell offers device-local preferences in two opposite corners and the settings hub behind two
different doors: the top bar carries a theme dropdown **and** a gear to `/settings`, while the profile menu at
the sidebar's foot carries a language submenu **and** its own *Settings* entry to the same route. Theme and
language are one pair — the settings hub itself presents them as a single *Appearance and language* block —
so splitting them across the shell's two ends is arbitrary, and two gears pointing at one route is plain
duplication. This CR gives the profile menu both quick-pickers and the single entry into the hub, and leaves
the top bar to what is actually contextual: the breadcrumb, the invitations badge and the upload tray. It is
web-only: no contract, no backend, no wire change.

## Context & constraints

- Use-case: [uc.settings.appearance-language](../docs/features/settings/use-cases/uc.settings.appearance-language.md).
  Its main flow **already** names the profile menu for *both* axes (step 1: *"The user opens the profile /
  settings surface (the sidebar ProfileMenu)"*) — the doc never learned about the top-bar theme control. This
  is the *doc ahead* case: the code moves to the doc, not the other way round.
- ADR: [0022 — Settings & administration information architecture](../docs/decisions/0022-settings-information-architecture.md).
  This CR contradicts it in two places and therefore **edits it** (§1–4 stand; not superseded):
  - § Decision, opening sentence — *"One scope-contextual settings hub, **reached from a gear in the top
    bar**"*.
  - §5 — *"The avatar menu keeps a **single Settings entry** that opens the hub […] **Theme** is the
    exception: it is a very frequent one-tap toggle, so its quick control lives in the **top bar** (beside
    search), cycling system → light → dark; language stays out of the top bar because it grows more
    options."*
- **The argument that lost, recorded because it is the good one.** ADR 0022's own §Context warns against the
  *category error* of putting space/server concerns under a menu that is about *me* — and the hub holds group,
  tenant and server sections. Keeping the gear in the top bar was therefore the ADR-faithful option and was
  weighed (2026-09-20) against moving it; the move was chosen. The reconciliation the ADR must carry: the
  profile menu gains **one door**, not the hub's contents — *"about me"* describes where the handle is, not
  what is behind it — and the top bar is 2.1rem tall below the compact threshold
  (`web/src/styles/layout.css` § the narrow-viewport block), where every control removed is real room.
- **Operator-blindness is untouched and no invariant is at risk.** Theme and language are device-local
  (`localStorage`, `useThemeState` / `i18n/index.ts`); nothing here adds, moves or renames anything that
  reaches the server. `invariants:` is empty deliberately — not unexamined.
- **Out of scope: `admin-web/`.** It follows the OS colour scheme and exposes no theme control at all
  (`admin-web/src/shared/state/systemTheme.ts`), so it has nothing to consolidate.

### Locators — run them, don't read the lists below as a tally

The sites are found from two angles keyed on different things: the **prose claim** (what must be corrected)
and the **symbols** (what must move). They overlap only partly, which is the point.

**L1 — the claim**, across the whole tree (`changes/README.md`'s own rule text is the expected noise):

```bash
grep -rni "top-bar theme\|topbar theme\|theme.*top bar\|top bar.*theme\|one-tap\|quick toggle\|quick-picker\|cycle-button" \
  --exclude-dir=node_modules --exclude-dir=dist --exclude-dir=.git .
```

**L2 — the symbols**, which L1 cannot see (a component importing `setThemePreference` states no claim):

```bash
grep -rn "setThemePreference\|useThemeState\|setLocalePreference\|localePreference\|topbar-icon-button\|/settings'" \
  web/src --include='*.vue' --include='*.ts'
```

**L3 — the doors into the hub**, the set this CR's whole point is to shrink to one. A count, not a reading —
the done-when is about the *set*:

```bash
grep -rn "push('/settings')\|to=\"/settings\"" web/src --include='*.vue' | grep -v SettingsHubView
```

(`SettingsHubView.vue`'s own `back()` is excluded by hand: it is navigation *inside* the hub, not a door into
it. That exception is why this stays a test assertion rather than a `scripts/` guard — see phase 3.)

## Phases

- [ ] **1 docs — ADR 0022 records one door and retires the theme exception** · edited in place, **not**
      superseded: the hub's structure, the collections model and the configuration-vs-content boundary (§1–4)
      are not what changes. Per site, the shape of the correction:
      - § Decision opening sentence — **edited**: the hub is reached from the profile menu's *Settings* entry.
      - §5 — **rewritten whole**, not patched. The avatar menu carries the theme *and* language quick-pickers,
        the single *Settings* entry into the hub, and sign-out; the top bar carries no preferences and no
        gear. Two clauses are **deleted rather than narrowed** because they were already false before this CR
        and would otherwise survive a careful edit: *"(beside search)"* — search left the top bar with cr-0030
        (`AppTopbar.vue:5`) — and *"cycling system → light → dark"* — cr-0085 made it a labelled dropdown. The
        **theme exception itself is deleted**: theme and language now sit on the same shelf, which is the
        point of the CR.
      - § Consequences / rejected alternatives — **added**: the top-bar gear as the hub's contextual door, the
        option turned down, with the category-error argument that recommended it and the reconciliation above
        (one door ≠ the hub's contents). An ADR that only records the winner hides the fact that this was
        close.
      - `uc.settings.appearance-language` is **read end to end, including the bilingual reader-facing
        section** — a fragment grep is blind to the same claim said in different words, and the owning doc is
        where a restatement is likeliest. Expectation from the current tree: nothing to change, because the
        main flow already names the ProfileMenu. Confirm that; do not assume it.
      · **Done when:** `python3 scripts/validate_adr_citations.py`, `python3 scripts/validate_doc_links.py`,
      `python3 scripts/validate_changes.py` green, and **L1 re-run returns no surviving top-bar-theme claim
      under `docs/`**.

- [ ] **2 web — the controls move** ·
      - `ProfileMenu.vue`: an **Appearance ▸** submenu built exactly like the existing language one (labels
        from `shell.theme{System,Light,Dark}`, `pi pi-check` on the active entry, `command` →
        `setThemePreference`). Order: account row · separator · Appearance · Language · separator · Settings ·
        separator · Sign out.
      - `AppTopbar.vue`: delete the theme button and its `Menu`, the `useThemeState`/`setThemePreference`
        import, `THEME_ICON`, `themeMenu`, `themeLabel`, `themeMenuItems`, and the gear button. **The whole
        scoped `<style>` block goes with them** — `.topbar-icon-button` has exactly two users and both are
        these buttons (L2); `InvitationsBadge` and `UploadStatusMenu` style their own triggers
        (`.invitations-trigger` / `.uploads-trigger`). One **dangling citation** falls out of that deletion
        that no symbol grep flags as a site: `UploadStatusMenu.vue:139` cites *"Same 2.25rem (36px) square +
        radius as .topbar-icon-button"* as its size reference — repoint or inline it in the same edit.
      - The header comments that this CR makes false are **edited**, each to describe the shipped shape:
        `AppTopbar.vue:22–24`, `ProfileMenu.vue:4–5` and `:38`, `AppearancePanel.vue:4`, `AppSidebar.vue:5`
        (the last is already wrong today and becomes right — it still needs *settings* added to its list).
      - i18n hygiene, folded in because the same lines are being touched: the language menu's *System* row
        borrows the **theme** key `shell.themeSystem` (`ProfileMenu.vue:31`, `AppearancePanel.vue:20`). Add a
        neutral `shell.systemDefault` (EN/DE both *System*) and point both at it. No visible string changes.
      - Tests: `AppTopbar.test.ts` loses `describe('AppTopbar theme dropdown (cr-0085)')` and keeps the
        breadcrumb block. A new `ProfileMenu.test.ts` takes those two assertions over against the profile menu
        (the labelled System/Light/Dark list applies the picked one; the trigger opens a menu), adds one that
        the language submenu still applies a locale, and asserts **L3's property**: the shell offers exactly
        one route to `/settings`.
      - **Bite the new test before trusting it** (`ProfileMenu.test.ts` is this CR's load-bearing evidence and
        phase 3 cites it as coverage): stub the theme submenu's `command` to a no-op and confirm the theme
        assertion goes **red**; restore. A test that cannot be made red is not evidence.
      · **Done when:** `npm run check` green, **L2 re-run shows `AppTopbar.vue` free of theme/settings
      symbols**, and **L3 returns exactly one line**.

- [ ] **3 coverage** · `docs/coverage/features/settings/use-cases/uc.settings.appearance-language.yaml`:
      - `theme-quick-picker` — the AC text is **rewritten** (top bar → profile menu) and its
        `web.unit` path repointed `AppTopbar.test.ts` → `ProfileMenu.test.ts`. The cr-0085 comment above it is
        **replaced, not appended to**: it explains a control that will no longer exist there.
      - **New AC `single-settings-door`** — *the shell offers exactly one entry into the settings hub* —
        covered by the new `ProfileMenu.test.ts` assertion. This is the property the CR exists for and the one
        that rots first (the next surface that wants a gear will add a second). **Why a test and not a
        `scripts/` guard:** the honest predicate has a legitimate exception (`SettingsHubView`'s own `back()`,
        see L3), and a guard that must carry a hand-maintained exception list over a UI convention costs more
        than it holds. If a second exception ever appears, that is the signal to promote it.
      · **Done when:** `python3 scripts/validate_coverage.py` green.

## Definition of done

**Baseline, run before the first edit** — so a red afterwards is attributable to this CR, and so the number
is produced rather than remembered:

```bash
for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED (pre-existing): $f"; done
ls scripts/validate_*.py | wc -l
```

This CR **adds no guard**, so that count must be identical at the end — it cannot be confirmed by the CR's own
work, which is the failure mode this baseline exists to avoid.

- `npm run check` green in `web/`. `./gradlew check` is **not** in scope: no backend file is touched.
- Every `scripts/validate_*.py` green, count unchanged against the baseline.
- `docs/coverage/` updated for `uc.settings.appearance-language` — the rewritten AC plus the new one, each
  with a test behind it.
- One **hand pass on a narrow viewport** (below the compact threshold, where the profile button becomes the
  shell's only door and the top bar is 2.1rem): the submenu's popup anchoring is the one thing the component
  tests cannot see. Everything else is covered by phase 2's checks.

## Retrospective

_To be written at close — planning, execution, and process/collaboration (were the agreed conventions honored:
language, workflow, the memory notes?), both directions, ending with one concrete "next time"._
