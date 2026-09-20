---
id: cr-admin-web-public-shell
title: admin-web gets web's public shell, its dark mode and its favicon
status: planned
adrs: ["0065"]
invariants:
  - no shared UI package — ADR 0065 keeps everything but the crypto a per-package copy, so this ports code, it never reaches into `web/`
  - a pure client of the Admin API — no backend surface, no fifth generated spec, no key material outside setup
  - four of web's eight stylesheets, still — `layout.css` is not imported, and the reason is in `admin-web/src/style.css`'s header
---

## Goal

`admin-web`'s first-run setup and sign-in cards render flush against the top-left corner of a flat page, while
every structurally identical screen in `web/` sits centred on the gradient background. The cause is one missing
wrapper, not a missing design system: all five copied stylesheets are byte-identical to web's, every class the
admin templates use resolves, and the centring class is **already in this package and referenced by nothing**.
Two smaller gaps travel with it — the dark theme is configured and unreachable, and the surface has no icon at
all. This CR closes all three, by porting web's shape rather than inventing one.

## Context & constraints

- Decision: [ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md) — this package is independent of
  `web/` in everything but `@veyq/crypto`. So each item below is a **port** (a copy with its own life), never an
  import across the package boundary and never a new shared UI package. `admin-web/CLAUDE.md` § Conventions
  states the same rule and is one of the docs this CR updates.
- **No `satisfies:`, on purpose.** This changes no acceptance criterion of any use-case: `uc.admin.setup-server`
  specifies the cold account birth and the kit confirmation, and all of that already works. Nothing to add to
  `docs/coverage/`; § Definition of done keeps `validate_coverage.py` green as a no-change check rather than as
  a deliverable. If a reader disagrees, the argument to beat is that a card's position is not a use-case
  postcondition.
- Guard that watches the neighbouring vocabulary: `scripts/validate_shell_route_gate.py` reads
  **`web/src/router.ts` only** (its `ROUTER` constant) and asserts `layout: 'app'` ⇒ `requiresUnlock: true`.
  This CR adds a `meta.layout` to a second router and therefore does not touch it — see Decision 4 for the word
  that must stay out of `admin-web/src/router.ts`, and why extending the guard here would be asserting a vault
  gate on a surface that has no vault.
- Surfaces touched: `admin-web/` only (`src/App.vue`, `src/router.ts`, `src/style.css`, `src/main.ts`, a new
  theme module + brand assets, two test files), plus two docs. No contracts, no backend, no `web/`.
- The measurement this CR rests on, re-runnable:
  ```
  grep -rn 'veyq-shell\|public-shell' web/src admin-web/src     # the dead twin and the live original
  grep -rn 'veyq-dark' admin-web/src                            # configured in main.ts, set by nobody
  ls admin-web/src/assets 2>/dev/null; ls admin-web/public 2>/dev/null   # neither exists
  ```

## Decisions

Every settled decision carries the line or command that settles it. The one this CR leans on hardest is
Decision 1 — spot-check that `.veyq-shell` really is unreferenced before building on it.

### Settled

1. **Reuse `.veyq-shell`, already in `admin-web/src/style.css:15`; do not import `layout.css`.** It is
   `min-height:100dvh; display:grid; place-items:center; padding:1.5rem; background:var(--app-background-pattern)`
   — the centring behaviour this CR needs, copied over with the rest of the design system and then never wired
   up. `grep -rn 'veyq-shell' admin-web/src` returns the definition and no user [M: 2026-09-20]. Importing
   `layout.css` instead would drag in the Memory shell, sidebar and topbar rules for a surface that has none of
   those — the exact reason `style.css`'s header gives for taking four sheets of eight.
2. **Align `.veyq-shell`'s values to web's *live* class, not to web's copy of the dead one.** `web/src/style.css`
   carries the same dead `.veyq-shell`; the class web actually renders is `.public-shell`
   (`web/src/styles/layout.css:60`): `padding: 2rem`, `width: 100%`, plus a `@media (max-width: 720px)` override
   to `align-items: start; min-height: 100dvh; padding: 1rem` (`layout.css:1382`). So the port takes `2rem`,
   `width: 100%` and that media query, and keeps `100dvh` (web's base rule still says `100vh`, and its own
   mobile override is the admission that `dvh` is the right one). The mobile override is not cosmetic: with
   `place-items: center` a card taller than the viewport overflows **upward, unreachable**, and the setup
   flow's Emergency-Kit step is the tallest card in either app.
3. **`.veyq-shell-inner` lives beside `.veyq-shell` in `style.css`, not in a scoped block.** `width: min(460px, 100%)`,
   the same value web uses. web keeps it scoped inside `AppShell.vue`, which is precisely how `.veyq-shell` in
   web's own `style.css` died unnoticed — the pair belongs together, where a reader looking at one sees the other.
4. **The layout is chosen by `route.meta.layout`, mirroring web's `AppShell.vue`, and the card shell is the
   default.** Only the console opts out; setup, login and the `:pathMatch` catch-all fall through to the card
   shell. The opt-out word is web's own — **`layout: 'plain'`**, which `web/src/router.ts:49` already uses for
   exactly this meaning (*"the full viewport, not the narrow public card"*), so the two routers keep one
   vocabulary instead of two synonyms. The word that must **never** appear in this router is `'app'`: in web it
   means the vault-gated Memory shell, `scripts/validate_shell_route_gate.py` asserts that every route carrying
   it is also `requiresUnlock: true`, and this surface has no vault to unlock. A comment at the line says so.
   Two angles agree on the split [M: 2026-09-20]: the template roots
   (`for f in admin-web/src/views/*.vue; do sed -n '/<template>/{n;p;}' "$f"; done` → two `<Card class="login-card">`,
   one `<div class="admin-shell">`) and the route table (`grep -n "path: '" admin-web/src/router.ts` → three
   routes plus a redirect into the console). A future full-page screen that forgets its `meta` gets a visibly
   wrong 460px column, which is the failure mode worth having.
5. **The console keeps its own page shell and gets `--app-background-pattern-subtle`.** `AdminConsoleView`
   already self-centres with scoped `.admin-shell { max-width: 68rem; margin: 0 auto }`, so it needs background
   and height from the root and nothing else — which is exactly what web's authenticated `.app-shell` takes
   (`web/src/styles/layout.css:6`: `background: var(--app-background-pattern-subtle)`). `.admin-root` stays,
   narrowed to that one job.
6. **The unreachable state renders in the card shell.** It is a 460px message, not a page; `App.vue`'s
   `.admin-root__error { max-width: 32rem; margin: 4rem auto }` then has nothing left to do and goes. It stays a
   pre-emption of `<RouterView>` (admin's shape), not a banner above it (web's) — with no reachable backend there
   is no console to render underneath.
7. **The theme port is the system-following half only: `initializeTheme()`, no preference, no localStorage, no
   switcher.** web's `useThemeState.ts` also exports `setThemePreference` and a `themePreference` ref because
   `web/` has a settings screen to drive them; this surface has three screens and none of them is settings.
   Module name `src/shared/state/systemTheme.ts` — the exported function keeps web's name so the counterpart is
   obvious, the file name says which half it is. It sets `.veyq-dark` on `<html>` and nothing else: web also
   writes `.veyq-light` and `data-theme`, and `grep -rn 'veyq-light\|data-theme' web/src` returns only the module
   that writes them and its own test [M: 2026-09-20] — write-only state, not worth porting.
8. **The favicon is web's mechanism verbatim: the two brand SVGs copied to `admin-web/src/assets/brand/`,
   imported `?raw`, swapped as a `data:` URL when the theme flips** (`useThemeState.ts` § `applyFavicon`). The
   gate that decides whether it can work is one line away and passes: `admin-web/vite.config.ts:24` is
   `img-src 'self' data: blob:`, and that one CSP string serves both the dev header and the built `<meta>`
   (`vite.config.ts:41,47`), so dev and container behave identically. No `<link>` in `index.html` — same as web.
9. **Nothing the CLI bundle reaches may import a `?raw` asset.** `npm run build:cli` is bare esbuild with no
   plugins (`admin-web/package.json` § scripts), so a Vite-only import specifier on a path reachable from
   `src/cli/main.ts` fails the bundle. `systemTheme.ts` is reached only from `main.ts`, which the CLI does not
   enter — and `build:cli` sits inside `npm run check` precisely so this is proven rather than assumed. Typing
   needs no change: `vite/client` declares `*?raw`, and `admin-web/src/env.d.ts` already references it, byte-identical
   to web's.
10. **The refusal card gets `margin-inline: auto`.** With the console on the page shell, the `forbidden` branch's
    `<Card class="login-card">` (460px) would otherwise sit at the left edge of a 68rem column. One line in
    `AdminConsoleView`'s scoped block; the three-verdict structure its test pins is untouched.
11. **The green primary button is not a defect and is out of scope.** Neither package overrides PrimeVue's
    primary palette (`grep -rn 'definePreset\|--p-primary' web/src admin-web/src` → nothing [M: 2026-09-20]);
    both pass raw `Aura`, whose primary is emerald, and web's primary buttons are the same green. `--app-accent`
    (#5b4df5) is used only by hand-rolled controls in `ui.css`. A purple primary would be a new preset **both**
    packages need — a separate decision, not a port.
12. **The missing icon glyph was a misread.** On the running dev server `document.fonts` reports
    `primeicons: loaded`, `import 'primeicons/primeicons.css'` is present at `admin-web/src/main.ts:10`, and
    `pi-server` renders [M: 2026-09-20, browser pane at `localhost:5174`]. Nothing to fix; recorded so the
    hypothesis is not re-opened.

### Open

- None. Two candidates were closed above (11, 12); one cleanup deliberately left out is below.

### Explicitly not in this CR

`web/src/style.css`'s own dead `.veyq-shell`, and web's write-only `.veyq-light` / `data-theme`. Both are
`web/` cleanups found while measuring this one; deleting them from here would make a `web/` regression this
CR's, for no gain. They belong in `docs/inbox.md`.

## Phases

- [ ] **1 shell** — `admin-web/src/style.css`: `.veyq-shell` takes web's live values (Decision 2) and gains
  `.veyq-shell-inner` beside it (Decision 3), both with a comment naming `web/src/styles/layout.css:60` as the
  original and saying that the pair is a port, not an import. `router.ts`: `meta: { layout: 'plain' }` on the
  console route, with one line on why the default is the card shell and why `'app'` is not the word (Decision 4).
  `App.vue`: two branches like web's `AppShell.vue` — card shell (`.veyq-shell` > `.veyq-shell-inner`) when
  unreachable or when the route is not `layout: 'plain'`, else `.admin-root` narrowed to `min-height` + `--app-background-pattern-subtle`;
  `.admin-root__error` deleted. `AdminConsoleView.vue`: Decision 10's one line. New `src/App.test.ts`: the
  three cases in § Bite-tests.
  · Done when: `npm run check` green in `admin-web/`; `/login`, `/setup` and `/` each read back through the
  running dev server with the shell class, the inner wrapper's width and the card's `x` measured, not
  eyeballed; the 720px override checked at an emulated phone width with the Emergency-Kit step open (its top
  edge must be reachable).
- [ ] **2 theme + favicon** — copy `web/src/assets/brand/logo-{light,dark}.svg` to
  `admin-web/src/assets/brand/`; new `src/shared/state/systemTheme.ts` (Decision 7 + 8: the media-query
  listener, the `.veyq-dark` toggle, `applyFavicon`, `svgDataUrl`), called from `main.ts` before `createApp`
  exactly where web calls it; new `systemTheme.test.ts`.
  · Done when: `npm run check` green — including `npm run build:cli`, which is the proof for Decision 9;
  `/login` and `/` read back with the emulated colour scheme flipped both ways (`.veyq-dark` present/absent on
  `<html>`, the PrimeVue surfaces dark, the favicon `<link>`'s `href` changed), and the console checked for a
  panel that `dark.css` was never exercised against.
- [ ] **3 docs** — `admin-web/CLAUDE.md` § Conventions: the copied-styles paragraph names the shell and the
  theme bootstrap as ports with their `web/` originals, and says `layout.css` is still not imported and why.
  `docs/crosscutting/ui-web-styleguide.md` § Source of truth: one sentence that `admin-web/src/styles/` is a
  five-sheet copy the same rules govern — and while in that paragraph, **`nine stylesheets` is wrong**
  (`ls web/src/styles/ | wc -l` → 8, and the paragraph's own list names eight) [M: 2026-09-20].
  · Done when: `for f in scripts/validate_*.py; do python3 "$f"; done` green (≈5 min, start it in the
  background); the styleguide paragraph re-read whole, not only the sentence corrected — a count wrong in one
  place is usually wrong in two.

## Bite-tests — per test, the method and the lever

| # | assertion | method | lever |
|---|---|---|---|
| B1 | a card route mounts `.veyq-shell` > `.veyq-shell-inner` | live defect | write `App.test.ts` before touching `App.vue` → red on the untouched tree; green after phase 1 |
| B2 | the console route mounts `.admin-root` and **no** `.veyq-shell-inner` | injection | after phase 1, drop `meta.layout` from the console route → the console renders in a 460px column → red; restore. Without this lever B1 passes on an `App.vue` that wraps everything |
| B3 | `serverState.unreachable` renders the message inside `.veyq-shell-inner` | live defect | same ordering as B1 |
| B4 | `initializeTheme()` adds `.veyq-dark` when the OS prefers dark | live defect | write `systemTheme.test.ts` before the module exists → red |
| B5 | it removes `.veyq-dark` when the OS prefers light | injection | replace the `classList.toggle(…, next === 'dark')` with a bare `add` → B4 still green, B5 red; restore |
| B6 | the favicon `<link>`'s `href` differs between the two schemes | live defect | ordering as B4; it also fails if the two SVGs were copied as the same file, which is the copy mistake worth catching |

## Definition of done

- Phases 1–3 checked; every bite above seen red by its own lever and recorded in the retrospective.
- `npm run check` green in `admin-web/` (lint + typecheck + test + build + **build:cli**).
- `for f in scripts/validate_*.py; do python3 "$f"; done` green — **the baseline is the command, not a
  number**: run `ls scripts/validate_*.py | wc -l` before the first edit and again at the end; this CR adds no
  guard, so the two must agree.
- `python3 scripts/validate_coverage.py` green with **no** new criterion and no coverage file touched
  (§ Context).
- `web/` provably untouched: `git diff --name-only main -- web/` empty at the end.
- The container build run once before believing the new imports — `docker build -f admin-web/Dockerfile .`, which
  never sees `web/`'s `node_modules` (`admin-web/CLAUDE.md` § Conventions: a green local check does not prove this
  package's dependencies are declared). Nothing new is *installed* here, but two new asset files are, and the
  Dockerfile's copy set is the thing being tested.
- The harvest before deletion: what this CR taught goes to the line it binds — a port trap to a comment beside
  the ported code, a working lesson to `changes/README.md` — never to git.

## Machine hints — hold regardless of the CR's final shape

- The web dev server runs on `veyq.localhost:8081` and the admin one on `localhost:5174`; both were live while
  this CR was written. `/setup` is only reachable on an instance whose setup is **not** complete — on the
  current local DB it redirects to `/login` (`router.ts` § `beforeEach`), so phase 1's setup check needs either a
  fresh database or the guard temporarily stepped around. The two views share the shell, so `/login` proves most
  of it; the Emergency-Kit height check (Decision 2) is the part that genuinely needs `/setup`.
- Read the layout back with `javascript_tool` (computed styles, bounding boxes) rather than screenshots — the
  whole diagnosis above was text, and a screenshot is the most expensive way to ask "is it centred".
- System python is 3.9; a new `scripts/*.py` using `X | None` needs `from __future__ import annotations`. This CR
  adds none.
- The full `validate_*` glob takes ≈5 min — start it in the background at the beginning of phase 3.

## Retrospective

_Filled at implement. Planning and execution misses, root cause, one concrete change; both directions; end with
"next time"._
