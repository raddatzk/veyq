---
id: cr-guest-first-load
title: What a link guest's first load carries, and a lighter one — every page lazy, the member's chrome apart, the entry in few files
status: in-progress
satisfies: []
adrs: ["0155", "0079", "0047"]
invariants:
  - "share view = member view: the guest's and the member's pages draw the same components; only where the code is fetched changes"
  - "no key-handling path changes shape: boot(), unlock and the share key's handling stay as they are, only the pages' loading moves"
  - "sw.js loads nothing but src/sw/ (the Service Worker runs no app module)"
---

> **Status: in progress** (owner go 2026-11-25, inbox #1310). Phase 1 (analysis) below; phase 2 builds what the decision
> log chose.

## Goal

A link guest opens a share on a phone, on a mobile network, with nothing cached ([ADR 0155](../docs/decisions/0155-the-web-draws-over-the-kotlin-core-application-by-application-after-a-measured-pilot.md)
decision 10). Measure what that first load fetches — the eager set (`main` and `index.html`'s modulepreloads) plus what
the share view of each guest kind fetches — by module and library, list what of it a guest could fetch lazily or not at
all, and build the worthwhile part without changing what anyone sees, without a size gate, and without the first paint
getting slower.

## Phase 1 — the analysis

**Method** (`scratchpad/g1310/tools/`, not committed): `vite build` of `94704dda1`; the eager set is the entry plus the
static closure of `index.html`'s modulepreloads, a guest path the eager set plus what the dynamic import of the kind's
chunk fetches (its `__vite__mapDeps` list: the chunk, its static imports and its CSS), as Depot's move D8 did — JS gzip -9
per file, the method that reproduced decision 10's last numbers to ±11 B. Attribution: each chunk's minified bytes mapped
to sources through its sourcemap, scaled by the chunk's own gz ratio (≈; a 1.6 MB chunk's ratio is an average).

**The eager set before: 752,751 B gz JS + 23,952 CSS in 32 files.** By module and library (≈ B gz):

| Part | ≈ B gz | Who needs it on a guest's first load |
|---|---:|---|
| the web's own `features/` (account, moderation, spaces, tenant, memories, depot, expenses) | 113,700 | the applications' registration and share pages yes; account/settings/moderation/spaces/tenant only through the router's member pages and the member's chrome |
| the web's `core/` (node, sharing, export, blob, search, people, …) | 81,600 | mostly yes — the share page *is* the member's level view (share view = member view) |
| the generated API clients (`api/generated`, all through `api/client.ts`) | 76,800 | sharing, content, blob, depot, moderation's reports yes; tenants, account, scopes, auth, tasks, operator mostly no |
| the core catalog (`i18n/index.ts`, both locales) + the applications' catalogs (~17,000) | 75,700 | one locale |
| `reka-ui` (the layer's primitives) | 75,500 | yes |
| the Kotlin platform module (`kotlin_veyq_mobile_core`) | 71,800 | yes; its words' enum `Msg` (~27,800) is inbox #1341's, being handled |
| unmapped (Vue's compiled templates, the runtime's glue) | 48,000 | spread over the above |
| Kotlin stdlib + coroutines + atomicfu | 48,600 | yes (the platform module reaches them) |
| Expenses' Kotlin module (`kotlin_veyq_mobile_apps_expenses`) | 17,400 | a Pot's guest only |
| the web's `shared/`, `views/`, `components/`, `app/`, `router.ts` | 34,000 | `views/` (11,500) and `components/app` mostly member-only |
| Vue, vue-router, vue-i18n + intlify, `@noble/*`, `@internationalized/date`, lucide, floating-ui, vueuse, … | ~90,000 | yes |
| CSS (`main-*.css`) | 23,952 | partly (the member chrome's and pages' scoped styles) |

**Why the member's code was eager** (`why.py`, the shortest static import chain from `main.ts`): `router.ts` imported
all 15 pages statically (`SettingsHubView` → account, spaces, tenant panels; `ModerationView`; `LoginView`; `SpaceView`;
`TenantJoinView`, …); `App.vue` → `AppShell.vue` imported the member's chrome (`AppSidebar`, `AppTopbar` → the search
field → `features/settings/sections` → moderation's appointment, the profile menu, the create-group dialog,
`FinishProfilePrompt`, `UnconfirmedEmailBanner`, `ShortcutsDialog`). The applications' registration (`applications.ts`)
reaches the share tree page, the level view and the node dialogs through `core/bookmark/kind.ts` — but those *are* the
guest's page too. `app/boot.ts` reaches unlock, the device wraps and the account API: a guest with a remembered member
session runs it as well, and it is a key-handling path — not touched.

**Per guest kind before** (JS gz; CSS beside): a Memory's share body 797,875 (+25,675 CSS), a Memory's page 894,254
(+35,656), a Depot file 767,333 (+25,905), a Depot folder 752,751 (the eager set alone; +23,952), a drop box 756,443
(+24,148), a Pot's page 783,318 (+25,715); a member's home 752,751 (+23,952).

**Candidates** (each measured as its own build against the same base; savings on every guest path unless named):

| # | Candidate | Saving | Risk | Call |
|---|---|---:|---|---|
| C1 | every page a lazy route chunk (`router.ts`), the first one fetched beside `boot()` | eager −64,226 JS, −2,783 CSS; a guest path −48,375 (Memory share body) to −48,623 (folder) once the share page's chunk is counted | the first page waits for its chunk (fetched in parallel with `boot()`, so no extra round trip); a member's first visit to settings or invitations fetches its chunk then | build |
| C2 | the member's chrome a chunk of its own (`MemberChrome.vue`, `defineAsyncComponent`), loaded with every app page | a further −23,142 (Memory share body) to −23,143 (folder) | the chrome must be there before an app page draws: the app routes' loader awaits it (`appPage`) | build |
| C3 | the entry's static graph as few files (`output.codeSplitting`: `$initial` → `entry`, npm → `vendor`, `src/sw`'s shared pair → `sw-shared`) | C1+C2 cut the eager set into 82 files: measured over HTTP/1.1 at 150 ms RTT a guest's first paint got **slower** (4.75 → 5.32 s) though 61 KB lighter; grouped: 5 files and a further −33,000 JS on the eager set, −33,797 (folder) to −34,959 (Memory page) on a guest path (one chunk compresses better than 82) | one big chunk changes with every release (a member re-downloads its 456,440 B gz then; the npm half, `vendor`, 167,953, stays cached); module evaluation order inside the group is the bundler's; `sw.js` must not import the app (a guard holds it) | build |
| C4 | one locale per first load (the active locale's catalog fetched, the other never) | −47,948 on every path (probe: the German halves removed) | the locale switch becomes asynchronous; the core catalog (`i18n/index.ts`) and each application's (`features/<owner>/i18n.ts`, the composition root's catalog half, ADR 0079) split per locale, so two guards (`validate_web_words.py`, `validate_web_core_imports_no_applications.py`) and the tests' synchronous catalog (`src/test/i18n.ts`) change with it | not now — the largest one left; its own row (inbox #1400) |
| C5 | the Pot's content check (`validateContent`) lazy | −1,574 only: Expenses' Kotlin module (16,757) stays eager through the rest of `features/expenses/register.ts`'s imports (the domain's format and codec) | the registry's `validateContent` is synchronous (`planImport`, ADR 0082 decision 10) | no — not worth it alone; with C6 |
| C6 | Expenses' Kotlin module out of the entry (the register's imports made lazy behind the registry's hooks) | ≈ −16,800 for every guest but a Pot's | a registry contract change (synchronous hooks become lazy) across three applications | not now (inbox #1401) |
| C7 | the generated API clients per API, so the entry carries only those its modules call | ≈ −15,000 to −20,000 (tenants, tasks, operator, favorites; account, scopes, auth stay — boot and the active space use them) | `api/client.ts` is imported by ~every module; account/auth sit on the unlock path | not now (inbox #1402) |
| C8 | the words' enum `Msg` in the platform module | ≈ −27,800 | — | inbox #1341, another agent's |

## Decision log

- **D1 — C1, C2, C3 built; C4, C6, C7 filed, C5 dropped.** By saving against risk: C1 and C2 move only *where* code is
  fetched — no component, no key path, no text changes; C3 is a bundler setting with two build-time guards. C4 is the
  largest left but changes the catalogs' layout two guards and ADR 0079 name, and makes the locale switch asynchronous —
  a change of its own, not a side effect of this one. *Cost:* the rows of phase 2.
- **D2 — The first route's chunk is fetched beside `boot()`** (`preloadRoute` in `router.ts`, called by `main.ts` before
  `await boot()`): without it the lazy page would be requested only after the server's answer — one round trip more than
  before. A page that needs an unlocked vault also fetches the sign-in page, where the gate sends a browser without one.
  *Cost:* a remembered member opening `/` fetches the sign-in page's chunk unused.
- **D3 — The chrome is entered with its page, not drawn after it.** `defineAsyncComponent` alone would mount the route
  first and the chrome a moment later; the app routes' loader awaits the same module (`appPage`), so the router resolves
  the page only once the chrome is loaded, and `defineAsyncComponent` then resolves in a microtask (before paint). The
  AppShell tests await `loadMemberChrome()` before mounting, as the router does.
- **D4 — The grouping keeps the npm libraries apart** (`vendor`): one chunk for the whole entry would be 2,190 B gz
  smaller but would change with every release; the libraries change with a dependency update. The Kotlin platform module
  stays in `entry` — it changes with almost every release anyway (decision 10).
- **D5 — Two build-time guards, not a size number** (decision 10 sets no gate): `memberCodeOutOfTheEntryPlugin` fails the
  build when one of the member's pages or the chrome is in the entry or its static imports (a module named but in no chunk
  fails too, so a rename cannot disarm it); `serviceWorkerAlonePlugin` fails it when `sw.js` loads anything outside
  `src/sw/` (the grouping first put `protocol`/`range` into the app's entry and `sw.js` imported it — found by reading
  `sw.js`, then held by the guard). Bitten: a static `SettingsHubView` import in `router.ts`, a static `AppSidebar` import
  in `AppShell.vue`, a renamed entry in the list, the `sw-shared` group removed — each build exits 1 naming the module.

## Phase 2 — built

(Filled in by the implementation commit: the before/after per path, the eager set, a member's first load, the timing.)
