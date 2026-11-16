---
id: cr-web-client-never-starts-blank
title: The web client never starts blank — a start screen in index.html, a start watchdog that reloads once, and a Reload button where the start stops
status: planned
satisfies: ["uc.account.remember-this-device"]
adrs: ["0136", "0117", "0069", "0047"]
invariants:
  - "no inline script and no new CSP source: the watchdog is a same-origin script file, the start screen plain markup (cc.security.csp, ADR 0117)"
  - "the watchdog imports nothing, so it runs when the app's chunks do not load (ADR 0136 decision 2)"
  - "a hang reloads the page at most once per 5 minutes per tab; where sessionStorage throws, never automatically (ADR 0136 decision 2)"
  - "nothing about the start is stored beyond that one timestamp, and nothing is sent (ADR 0136 decision 3)"
  - "a hang at start drops no convenience tier (uc.account.remember-this-device E2, E5)"
---

## Goal

Build [ADR 0136](../docs/decisions/0136-the-web-client-never-starts-blank.md): a veyq tab that iOS Safari reloads after a
long time in the background, and whose start then hangs, shows the start screen, reloads itself once after 15 s, and if
that hangs too says "Taking longer than usual" with a **Reload** button and the step it waits for — never a white page
([uc.account.remember-this-device](../docs/features/account/use-cases/uc.account.remember-this-device.md) E5).
Single phase, `src/web/` and `site/` only; no contract, no backend.

## Context & constraints

- ADR 0136 holds every decision and the rejected alternatives; read it first. The cause is **not** measured: the fix
  must work whichever boot step hangs.
- The CSP ([cc.security.csp](../docs/crosscutting/cc.security.csp.md)): `script-src 'self' 'wasm-unsafe-eval'`, no
  `'unsafe-inline'`; `style-src 'self'` plus hashes, so **no `style="…"` attribute** in `index.html` either; Trusted Types
  enforced in the built app (ADR 0117), so the watchdog sets `textContent` / `hidden` / attributes only, never HTML.
- `index.html` and every path outside `/assets/` are served `no-cache` (`AppShellRoutes`), so an unhashed watchdog path
  is revalidated on every start, like `sw.js`.
- The app chooses its locale from `localStorage['veyq:locale']`, else the OS language (anything not German is English)
  (`src/web/src/i18n/index.ts`). The watchdog cannot import i18n (it must stay import-free), so it carries its three
  strings in English and German itself, and reads the same key.

## Inventory

| What | Where | Change |
|---|---|---|
| Start screen | `src/web/index.html` | `#app` holds the start screen: wordmark + progress indicator, no text; a hidden "slow" panel (heading, the step line, a Reload `<button type="button">`) with ids the watchdog fills. A `<script type="module" src=…>` for the watchdog **before** `/src/main.ts`. |
| Its styling | `src/web/src/style.css` (or a sheet it imports) | the start screen and the slow panel, from the existing tokens; `prefers-color-scheme` and `prefers-reduced-motion` (no animation then). Centred, fits a phone. |
| Watchdog | new `src/web/src/boot/watchdog.ts` | import-free. Exports nothing the app needs; runs on load. Waits for the app's `mounted` signal; on none after 15 s, or on `failed` at once: once-per-5-minutes reload (timestamp in `sessionStorage`, key `veyq:boot-reload-at`; a throwing storage means no auto-reload), else unhide the slow panel, fill the step line from the document's step marker, wire the button to `location.reload()`. Strings EN/DE here. |
| Build entry | `src/web/vite.config.ts` | the watchdog as its own `rollupOptions.input`, beside `media-sw`. Whether it lands hashed under `/assets/` (Vite rewriting the `index.html` tag) or unhashed is the implementer's call; it must stay a single file with no imports in the build output (see Done). |
| Step marker + signals | `src/web/src/app/boot.ts`, `src/web/src/main.ts` | `boot()` sets the step it awaits (`status`, `session`, `vault`) on `document.documentElement` (a `data-` attribute); `main.ts` signals `mounted` after `app.mount`, and `failed` from a `catch` on `bootstrap()` and on `router.isReady()`. One small shared module for the attribute/event names is fine for the app side; the watchdog repeats the names, and a unit test holds both equal. |
| Chunk failures | `src/web/src/main.ts` | `window.addEventListener('vite:preloadError', …)`: `preventDefault()` and hand it to the same once-per-5-minutes reload (signal `failed`). |
| Unreachable banner | `src/web/src/components/app/AppShell.vue` (`serverState.unreachable`) | a **Reload** button beside `common.unreachable` (`veyq-banner` pattern, the styleguide's button), `location.reload()`. EN/DE keys in `src/web/src/i18n/index.ts`. Grep for other places that render `common.unreachable` *as the boot state* and give them the same, or say why not. |
| Use docs | `site/src/content/docs/help/offline.mdx` + `site/src/content/docs/de/help/offline.mdx` | a section "Taking longer than usual": what it means, Reload, that nothing is lost; that it names the step, worth quoting in a report. The banner section gains the button. `sources:` gains `uc.account.remember-this-device`. |
| Coverage | `docs/coverage/features/account/use-cases/uc.account.remember-this-device.yaml` | criterion `a-hung-start-is-never-blank` with its tests. |
| ADR | `docs/decisions/0136-…md` | `Built: yes` at the end. |

## Tests

- **Watchdog unit test** (vitest, fake timers, a fresh `document` per case): no signal → reload after 15 s; a second
  start within 5 min → no reload, slow panel visible, step line names the marked step, button reloads; `mounted` before
  15 s → nothing; `failed` → at once; `sessionStorage` throwing → panel, no reload; German locale from the stored key and
  from the OS. It loads the watchdog the way the page does (its module evaluated on its own).
- **Import-free**: a test (or a step of the existing build check) reads the watchdog's built output and fails on any
  `import` — **bite it** by adding one import.
- **Names agree**: the watchdog's signal/attribute names and locale key equal the app's.
- **`main.ts`/`boot.ts`**: the step marker moves through `status` → `session` → `vault`; a rejecting `boot()` signals
  `failed`.
- **AppShell**: the unreachable banner shows the Reload button.
- **Lane L1** (`tests/e2e/`, written, run with the heavy stage at V1's end): a page whose setup-status request is held
  (`page.route` never fulfilling) shows the start screen, reloads once, then shows the slow panel naming `status`;
  releasing the request after the reload lands on sign-in. Its own spec file under `tests/e2e/tests/features/account/`.
- **CSP**: the built page with its policy throws no violation on a normal start (lane L1's existing CSP listener, if
  there is one — check; otherwise assert none in the new spec).

## Machine hints (planned shape — re-derive)

- The browser pass can stall a request with the pane's or Playwright's routing; a real iOS reproduction is not
  available on this Mac (the CoreSimulator is broken), so the device check is the owner's, after release.

## Done when

- `pnpm run check` in `src/web/` green; the e2e package's check green; `site/` check green; the trust chain green
  (`/usr/bin/python3` for the guards).
- A browser pass on the built shell: a held setup-status request shows the start screen, one reload, then the slow
  panel in German with the step; a normal start shows no panel and no CSP violation in the console.
- The coverage criterion, the site sections EN+DE, ADR 0136 `Built: yes`; this CR deleted.

## Retrospective

(filled in by the implementer)
