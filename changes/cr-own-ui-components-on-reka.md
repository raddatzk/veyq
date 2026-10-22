---
id: cr-own-ui-components-on-reka
title: The clients own their UI components — a wrapper layer on PrimeVue 4.5.5, then Reka UI underneath, then PrimeVue out
status: planned
satisfies: []
adrs: ["0107", "0039", "0065", "0079"]
invariants:
  - no screen changes what it does; a behaviour change this CR makes on purpose is listed in § Decisions, nowhere else
  - no view, composable, application or test imports a component library; only the package's shared/ui/ layer does
  - web and admin-web share no UI code (ADR 0065); admin-web's layer is a copy, adapted, never an import
  - no step pulls in a PrimeUI-licensed release (primevue >= 5, @primeuix/themes >= 3, @primeuix/styled >= 1, primeicons >= 8)
  - every colour a component paints comes from an --app-* role the contrast guard checks, in both modes
---

## Goal

[ADR 0107](../docs/decisions/0107-the-clients-own-their-ui-components-reka-ui-underneath-primevue-out.md) takes
PrimeVue out of both clients, because its successor line is commercial and a client every operator receives cannot
carry PrimeTek's licence terms. This CR builds it in the order the ADR's decision 7 fixes: first a layer of our own
components on the PrimeVue 4.5.5 we run today, with every call site moved onto it and the screens unchanged; then
each wrapper's inside replaced by Reka UI or plain markup; then PrimeVue, `@primeuix/*` and `primeicons` removed.
Each phase leaves both clients working.

No use-case changes behaviour, so `satisfies` is empty; coverage files move only where a test they cite is renamed.

## Context & constraints

- **Substrate.** Measured on worktree `primevue-5-license-migration-572492` at `e48a9342` (clean), 2026-10-22. The
  implementer re-runs the locators below and spot-checks the row § Inventory marks.
- **Licences, re-measured at implement time** (ADR cost premise rule): `npm view primevue@4.5.5 license` → MIT;
  `npm view reka-ui license dependencies` → MIT, its dependencies MIT or Apache-2.0 (`@internationalized/*`);
  `npm view @lucide/vue license deprecated` → ISC, not deprecated. (`lucide-vue-next` **is** deprecated in favour of
  `@lucide/vue` — the npm gate would refuse it.) Each install runs through `scripts/npm_ci_no_deprecated.py`.
- **The caret already holds the pin.** `^4.5.5`, `^2.0.3`, `^7.0.0` never resolve to the PrimeUI majors; nothing to
  change before phase 4 removes them. A phase that runs `npm install` checks the lock still names those versions:
  `grep -nE '"node_modules/(primevue|@primeuix/[a-z-]+|@primevue/[a-z-]+|primeicons)"' -A1 package-lock.json`.
- **Reka injects no style**, except its `Splitter` (`dist/utils/style.js`), which this CR does not use.
- **Lint has a last-block-wins trap.** `src/web/eslint.config.js:160` explains why a later
  `no-restricted-imports` block silently replaces an earlier one's patterns. The library restriction (decision 2)
  joins each existing block's pattern list instead of adding a block of its own, and is bitten: a `primevue` import
  in a view, an application and a test must each fail lint, and an import inside `shared/ui/` must pass.
- **Guards that anchor on PrimeVue** (`grep -rniE "primevue|primeicons|primeuix|\.p-[a-z]|--p-|data-pc" scripts/`):
  `validate_web_contrast.py` reads `--p-*` pairs from both `base.css` files and is red when one is unset;
  `validate_stylesheet_anchors.py` treats a `.p-*` rule as anchored only while PrimeVue renders the class;
  `validate_guard_gaps.py` plants probes in `shared/ui/`. A phase that changes a guard runs `validate_guard_gaps.py
  --force` and bites the changed predicate (`changes/README.md` § Bite-proof a guard by snapshot-and-restore).

## Decisions

Settled here, so no phase reopens them. The owner may stop any of them at review.

1. **Home.** `src/web/src/shared/ui/` (core, importable by every application, `validate_web_core_imports_no_applications.py`
   unchanged) and `src/admin-web/src/shared/ui/`. The existing wrappers there stay and move onto the layer:
   `ConfirmDialog.vue`, `shared/state/useToast.ts`, `core/people/PersonAvatar.vue`,
   `features/memories/components/IsoDateField.vue`, which becomes the layer's one date field (`VeyqDateField`,
   decision 5): its model stays the calendar day as `YYYY-MM-DD`, and a call site holding a `Date` converts through
   `shared/isoDate`.
2. **Names.** Components are `Veyq<Thing>` (`VeyqButton`, `VeyqDialog`, `VeyqMenu`, …), matching the `veyq-` class
   prefix the sheets already use. Their props are ours: e.g. a button's `variant: 'primary' | 'secondary' | 'danger'`
   and `appearance: 'filled' | 'outlined' | 'text'`, not PrimeVue's `severity`/`outlined`/`text` booleans. The menu
   model is a type of ours (`VeyqMenuItem`) replacing `primevue/menuitem` (9 files), with only the keys used today
   (label, icon, command, url, target, items, separator, disabled, visible, class, badge, key).
3. **Classes and test hooks are the layer's.** Every `.p-*` the sheets, component `<style>` blocks, unit tests,
   goldens and e2e reach into moves in phase 1 onto a `veyq-*` class or a role the wrapper renders. After phase 1,
   no file outside `shared/ui/` names a `.p-*` class, a `--p-*` variable or a `data-pc-*`/`data-p-*` attribute.
4. **Mapping in phase 2/3** (Reka primitive or our markup):

   | Today (PrimeVue) | Underneath after the swap |
   |---|---|
   | Button, InputText, Textarea (auto-resize), Password (always `feedback=false`, toggle-mask), Message, Card, ProgressSpinner, IconField/InputIcon, Breadcrumb, AvatarGroup | our markup |
   | Checkbox · ToggleSwitch · SelectButton | Reka `Checkbox` · `Switch` · `ToggleGroup` (single) |
   | Select · Select with `filter` (`OperatorPanel.vue`) | Reka `Select` · `Combobox` |
   | Dialog (incl. the mobile nav at `position="bottom"`) | Reka `Dialog` |
   | Menu (popup) · TieredMenu (`ProfileMenu.vue`) | Reka `DropdownMenu` · `DropdownMenu` with `Sub` |
   | Popover · the hover-opened one in `MemoryParticipants.vue` | Reka `Popover` · `HoverCard` |
   | Tabs/TabList/Tab/TabPanels/TabPanel | Reka `Tabs` |
   | DatePicker (single date only) | Reka `DatePicker` (segmented field in the locale's order + calendar) |
   | Toast + ToastService + `useToast` (incl. the undo group's custom container) | Reka `Toast`, behind the same `useToast`/`useUndo` API (ADR 0039) |
   | `v-tooltip.bottom` directive | a `VeyqTooltip` component (Reka `Tooltip`) |
   | Avatar · ProgressBar | Reka `Avatar` · `Progress` |
   | `pi pi-*` icons, `pi-spin` | `VeyqIcon` taking our icon names, rendering `@lucide/vue`; a spinning variant in CSS |

5. **Behaviour changes made on purpose** — the only ones:
   - **No dialog drags.** PrimeVue's `draggable` defaults to true (`primevue/dialog/BaseDialog.vue`), and a
     `mousedown` on the title bar moves the dialog — mouse only, no touch. Only `ConfirmDialog` ever set it, to false
     (`grep -rn "draggable" src/*/src --include='*.vue'`). Nobody chose dragging, and Reka has none.
   - **A password field's accessible name is its label only.** Today every `<Password>` sits inside a wrapping
     `<label>` and renders a visually hidden `aria-live` span with the catalog's `primevue.passwordPrompt`, so the
     name is the label's text plus that span: "Current password Enter a password", "Aktuelles Passwort Passwort
     eingeben". With `feedback` always false the span has no job. Four e2e matchers (`grep -rn "PrimeVue's Password"
     tests/e2e`) match the name's start (`/^Password\b/`), so they keep passing; their comment — which calls the span
     a placeholder — is false and goes, and the matcher may tighten to the whole name.
   - **A date is typed into segments**, not as free text: day, month and year are each a `role="spinbutton"`
     segment, typed as digits or stepped by the arrow keys, in the order and with the separators of the field's
     locale (Reka's `DateField`; empty segments read `tt.mm.jjjj` in German and `mm/dd/yyyy` in English). Pasting a
     whole date as text, which `IsoDateField`'s on-blur parse accepts today (also in ISO form), is not carried
     over. **Every date field follows the language** (owner 2026-10-22): `tt.mm.jjjj` in German, `mm/dd/yyyy` in
     English — the share and drop-box expiry fields and the moderation deadlines too, which show a fixed
     `yy-mm-dd` today whatever the language (`grep -rn 'date-format="yy-mm-dd"' src/*/src --include='*.vue'`). So
     the layer has one date field, `VeyqDateField`, and those call sites move onto it in phase 1, where it still
     wraps PrimeVue's DatePicker with the language's format as `IsoDateField` does — the format change lands there,
     before the swap. **The calendar to pick from stays** (Reka `DatePickerTrigger` + `DatePickerContent` + `DatePickerCalendar`):
     the calendar button opens it on every date field, as `show-icon` does today, and on the Memory date it opens on
     focus too (Reka's controlled `open`), as `show-on-focus` does; days outside min/max are disabled, the week starts
     on the catalog's `firstDayOfWeek` (Reka's `weekStartsOn`). PrimeVue's click on the month or year heading, which
     switches the panel to a month or year grid, has no counterpart in Reka's date picker: the calendar steps by
     month, and a distant year is typed into the year segment. The model (`YYYY-MM-DD`, no time or zone) and the
     min/max bounds stay. No e2e spec types into a date
     field (`grep -rnE "expiry|renew-until|veyq-date-field" tests/e2e --include='*.ts' --exclude-dir=node_modules`).
   - **A breadcrumb item is always a real link.** The `href="#"` trap in `src/web/CLAUDE.md` (it cleared the share key
     from the URL fragment) goes away with PrimeVue's Breadcrumb; the line is rewritten, not deleted, if our
     breadcrumb still needs a rule.
6. **Texts PrimeVue rendered** (`app/primeVueLocale.ts` in each package, the catalog's `primevue.*` block plus
   `common.close`) move into the wrappers that render them, read from the catalog; the block is renamed to the
   layer's own (`ui.*`) in EN and DE, and `primeVueLocale.ts` is deleted in phase 4 with its test.
7. **Colours.** The `--p-*` block in each `base.css` retires; wrappers paint from `--app-*` roles directly, and the
   component reads of `--p-*` (`grep -rhoE "var\(--p-[a-z0-9-]+" src/*/src --include='*.vue' --include='*.css' | sort | uniq -c`)
   move onto the role they stood for. `validate_web_contrast.py`'s pairs move with them in the same phase, and the
   guard is bitten: a lightened danger fill must still turn it red. Dark mode stays on `.veyq-dark`; nothing reads
   PrimeVue's `darkModeSelector` after phase 4.

## Inventory

Run each locator at the phase that needs it; the counts in the refine conversation are not carried here.

| What | Locator |
|---|---|
| PrimeVue imports per surface | `for d in src/web src/admin-web tests/e2e; do echo "== $d"; grep -rhoE "from ['\"]primevue/[a-z/]+['\"]" $d --include='*.vue' --include='*.ts' --exclude-dir=node_modules \| sort \| uniq -c \| sort -rn; done` |
| Files importing PrimeVue (the call sites phase 1 moves) | `grep -rlE "from ['\"](primevue\|@primevue\|@primeuix)" src/web/src src/admin-web/src` |
| Global install, preset, icon CSS | `grep -rnE "use\(PrimeVue\|ToastService\|primeicons.css\|app.directive\('tooltip'" src/*/src` |
| Tests mounting PrimeVue (**spot-check this row**: phase 4 removes every one) | `grep -rlE "from 'primevue/config'" src/web/src src/admin-web/src`; `grep -rn "ToastService" src/*/src/test/setup.ts` |
| Unit tests on PrimeVue DOM or components | `grep -rlE "\.p-[a-z]\|data-pc-\|data-p-" src/*/src --include='*.ts'`; `grep -rlE "from 'primevue/" src/*/src --include='*.test.ts'`; stubs by name: `grep -rnE "stubs: *\{[^}]*(Dialog\|Button\|Menu\|Toast\|DatePicker\|Select\|Popover\|TieredMenu)" src/*/src --include='*.ts'` |
| Goldens holding `p-*` classes | `grep -lE '\bp-[a-z]' src/web/src/views/__snapshots__/*/*.txt` |
| e2e on PrimeVue DOM | `grep -rnE "\.p-[a-z]\|data-p-\|PrimeVue" tests/e2e --include='*.ts' --exclude-dir=node_modules` |
| `.p-*` selectors in sheets and `<style>` | `grep -rcE "\.p-[a-z]" src/web/src src/admin-web/src --include='*.css' --include='*.vue' \| grep -v ':0$'`; `grep -rnE ":deep\(\.p-" src --include='*.vue'` |
| `--p-*` writes and reads | `grep -rcE "\-\-p-[a-z]" src/*/src --include='*.css' --include='*.vue' \| grep -v ':0$'` |
| `:pt` pass-through | `grep -rnE '[ :]pt="' src/*/src --include='*.vue'` |
| Icons (names; TS models; tests asserting them) | `grep -rhoE "\bpi-[a-z][a-z0-9-]*" src/web/src src/admin-web/src --include='*.vue' --include='*.ts' --exclude='*.test.ts' \| sort -u`; `grep -rlE "icon: *'pi pi-" src/*/src --include='*.ts'`; `grep -rlE "\bpi-[a-z]" src/*/src --include='*.test.ts'`; the dynamic one: `grep -rn 'pi pi-\${' src/*/src` |
| Docs and comments naming PrimeVue | `grep -rliE "primevue\|primeicons\|primeuix\|prime ?vue" docs site src/*/CLAUDE.md CLAUDE.md changes scripts src/*/src tests --exclude-dir=node_modules` |
| Guards anchoring on it | `grep -rniE "primevue\|primeicons\|primeuix\|\.p-[a-z]\|--p-\|data-pc" scripts/` |

**Published docs.** `site/` names no PrimeVue (`grep -rliE "primevue|primeicons" site/src`), and decision 5 changes
no step a Use page describes, and no page shows a date in either format (`grep -rnE "[0-9]{4}-[0-9]{2}-[0-9]{2}|YYYY|JJJJ|TT\.MM|dd\.mm|mm/dd" site/src/content/docs --include='*.md*'`
returns only Run and Security pages). Re-check in phase 1, where the date format changes, with that command and
`grep -rniE "datum|date|drag|zieh|verschieb" site/src/content/docs --include='*.md*'`, and update EN and DE together if a
page describes typing a date or moving a dialog.

## Phases

### Phase 1 — web: the layer on PrimeVue 4.5.5, every call site on it

The wrappers of decisions 1–3 exist in `src/web/src/shared/ui/`, implemented on PrimeVue 4.5.5; every view,
composable, application and test uses them; every `.p-*`/`--p-*`/`data-p*` reach outside `shared/ui/` has moved
onto the layer's classes, roles or `--app-*` roles; the lint restriction is on and bitten. `VeyqIcon` maps our names
onto `pi` classes for now. Goldens are regenerated once, and their diff is reviewed to contain class renames only.

Done when: `npm run check` in `src/web/` green; the lint bite (constraints) red where it must be and green in
`shared/ui/`; `grep -rnE "from ['\"](primevue|@primeuix)" src/web/src | grep -v '/shared/ui/' | grep -v 'main.ts'`
empty, and the `.p-`/`--p-`/`data-p` locators empty outside `shared/ui/` and `styles/base.css`'s routing block; the e2e
specs' selectors moved (the suite's run follows the owner's V1-end rule, so this CR carries it as an open item);
**browser pass** against the dev instance with one space holding a Memory with chapters and a banner, a Depot with
a drop box, a share link and a second member: sign-in, the space home, a Memory's tabs, a row's "more" menu and the
"New" menu, the profile menu's submenu, the move dialog's breadcrumb, a confirm dialog, the date range of a Memory,
an upload with its toast and an undo toast, the participants' hover card, settings (appearance, terms), the mobile
nav at 375 px — each compared with the same screen on `main`, in light and dark.

### Phase 2 — web: non-overlay wrappers on our markup and Reka

Button, the text fields, Password, Textarea, Message, Card, spinner, icon field, breadcrumb, avatar and avatar
group, checkbox, switch, toggle group, progress and `VeyqIcon` (now `@lucide/vue`) leave PrimeVue (decision 4). Their
texts come from the catalog (decision 6), their colours from `--app-*` (decision 7), and the contrast guard's pairs
for them move in this phase.

Done when: `npm run check` green; the contrast guard green and bitten; `validate_stylesheet_anchors.py` green;
`validate_guard_gaps.py --force` green; the four password e2e matchers corrected; browser pass as in phase 1 for the
screens holding these components, light and dark, plus keyboard: tab order and focus ring on a form, the password
reveal toggle, a checkbox and a switch by Space.

### Phase 3 — web: overlay wrappers on Reka

Dialog, the menus, popover and hover card, select and combobox, tabs, date picker, tooltip and toast leave PrimeVue
(decision 4); `useToast`/`useUndo` keep their API (ADR 0039).

Done when: `npm run check` green; the guards as in phase 2; browser pass as in phase 1 for every overlay, plus
keyboard and focus: Escape closes and focus returns to the opener, a menu by arrow keys, the submenu, a select by
typing, tabs by arrows, a date typed into segments in EN and DE and its min/max refused, a toast read by the screen
reader's live region (the DOM shows `role="status"` or `aria-live`).

### Phase 4 — web: PrimeVue out

`primevue`, `@primeuix/themes` and `primeicons` leave `src/web/package.json`; `main.ts` installs no PrimeVue; the
`--p-*` block and `app/primeVueLocale.ts` with its test are gone; every test mounts without the PrimeVue plugin and
`setup.ts` without `ToastService`.

Done when: the inventory's import, install, `--p-` and `pi-` locators return nothing for `src/web/`; `npm run check`
green; `npm ls primevue @primeuix/themes primeicons --workspace src/web` empty; the built bundle carries no
`<style>` injection (`grep -c "createElement(\"style\")" dist/assets/*.js` over the built web, with any hit explained);
a final browser pass over phase 1's list in light and dark.

### Phase 5 — admin-web: the same, in one phase

admin-web gets its copy of the layer (only the wrappers it uses: the import locator for `src/admin-web`), its call
sites, tests and sheets move, PrimeVue leaves its `package.json`, and its contrast `SHEETS` move as web's did.
`OperatorPanel.vue`'s filtered select is the Combobox; `StatementDeliveryNotice.vue`'s closable message keeps its
close.

Done when: `npm run check` in `src/admin-web/` green; the locators empty for `src/admin-web/`; the root
`npm ls primevue @primeuix/themes primeicons` empty; the contrast guard green and bitten on the admin sheet;
**browser pass** at the admin surface: first-run setup, sign-in, tenant list and creation, a tenant's detail,
release notices, the operator panel's filtered select, a statement-delivery notice closed, light and dark.

### Phase 6 — docs and harvest

Every doc the docs locator finds says what is true now: `docs/crosscutting/ui-web-styleguide.md` (§ Contrast's routing
block, the button rules, the locale paragraph — rewritten for the layer, and a paragraph on the layer's rule:
views import `shared/ui/`, never a library), `docs/crosscutting/cc.security.csp.md` and `src/web/src/shared/security/csp.ts`
(the `style-src` reason now names only `:style` bindings; tightening is inbox #523, not this CR),
`docs/architecture/README.md`, both `CLAUDE.md`s (titles, the stack line, the Breadcrumb, Avatar and Menu rules),
`docs/apps/depot.md`, ADR 0039 (it adopts the toast channel, rendered by the layer) and ADR 0107's status line.
ADR 0105's mention stays: it records why PrimeVue's `VirtualScroller` was rejected, still true. `changes/README.md`'s
lazy-`--p-*` lesson stays as a lesson with its library named as past. Inbox #523's row stays open; this CR's own row
(#524) is deleted when the CR is.

Done when: the docs locator's remaining hits are each one of the kept mentions named above; every
`scripts/validate_*.py` green (`for f in scripts/validate_*.py; do python3 "$f" || echo "RED $f"; done`); the
retrospective written; the CR deleted after its lessons are moved where they bind.

## Open items

- **The e2e suites' run** (web-e2e, L1) follows the owner's V1-end rule; their selectors move in phases 1–5, and a red
  there at V1 end that names a `veyq-*` hook or a role from this CR goes back to the layer.
- **Inbox #523**: whether `style-src 'unsafe-inline'` can go once PrimeVue is out.
