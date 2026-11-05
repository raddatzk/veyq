# ADR 0117 — The web client enforces Trusted Types

- Status: Accepted
- Built: yes
- Date: 2026-11-05
- Model: [cc.security.csp](../crosscutting/cc.security.csp.md)

## Context

The web client holds every unlocked key in the page. A script that runs there reads them, so an XSS in the client
defeats end-to-end encryption on any instance, a self-hosted one included, and no server-side setting helps
([security-model](../crosscutting/security-model.md)). The CSP already stops a foreign script from loading and from
phoning home (`script-src 'self'`, `connect-src`), but what decides whether hostile markup reaches the DOM was a
convention: 12 `v-html` bindings, each fed by the escape-first Markdown renderer and each carrying a comment that said
so (`grep -rn 'v-html="' src/web/src`, 2026-11-05). Nothing enforced it, and the bundled libraries write `innerHTML`
and start workers from plain strings of their own.

Trusted Types makes the browser refuse a plain string at every HTML sink (`innerHTML`, `DOMParser`, …) and every
script-URL sink (`new Worker(url)`, `serviceWorker.register`, a script's `src`) once the policy says
`require-trusted-types-for 'script'`. A value is accepted only when a named policy made it, and the policy names that
may exist are listed in the CSP (`trusted-types`).

## Decision

1. **The built CSP enforces Trusted Types**: `require-trusted-types-for 'script'; trusted-types veyq default`, no
   other name and no `'allow-duplicates'`. The dev server sends neither directive: Vite's client writes its error
   overlay with `innerHTML`, and the dev server loads Vue unpatched (4). Lane L1 runs the built policy.
2. **One policy of our own, `veyq`, IS the sanitizer.** Its `createHTML` runs the escape-first Markdown renderer over
   what it is given; the policy object never leaves `src/web/src/shared/security/trustedTypes.ts`. Markup reaches a
   template only as `markdownHtml(text)` through the directive `v-safe-html`, whose value type is opaque, so a string
   does not compile, and which refuses a string at run time in every browser — where the browser has Trusted Types the
   value is the policy's `TrustedHTML`, elsewhere a wrapper only that module can make. `v-html` is not used
   (`vue/no-v-html`). The policy has one more purpose behind a token only the module holds: parsing a photo's XMP block
   as XML into a document that is never part of the page (`parseInertXml`, handed to the EXIF library), because a
   `DOMParser` is an HTML sink too.
3. **A `default` policy carries the libraries, and passes only what is listed.** The browser consults `default` for
   a plain string that reaches a sink, which is what code that cannot be pointed at `veyq` does. It passes exactly
   the fixed markup the bundled libraries write as strings (Leaflet's inline-SVG probe at load and its two zoom-button
   labels, `LIBRARY_MARKUP`), and as a script URL only the client's own built scripts (same origin, under `assets/` or
   `sw.js`); everything else it refuses. That covers every worker the build starts — Vite bundles a worker only from
   the literal `new Worker(new URL('…', import.meta.url))`, so that call cannot take a value from a policy — the
   Argon2 worker in the shared `@veyq/crypto` package, pdf.js' own `new Worker(workerSrc)` and the media service
   worker's registration.
4. **Vue's own `vue` policy is compiled out of the build.** Vue's runtime creates a policy named `vue` whose
   `createHTML` passes every string through and writes `v-html` and an `innerHTML` binding with it. Allowed, it would
   let a plain string in a `v-html` past the browser unseen; refused, it would report a violation on every page load.
   A build plugin (`src/web/vite.config.ts`) removes its creation, failing the build unless it finds the one line in the
   one copy of Vue; `verify:csp` fails a `dist` that still creates it, or that carries compiled static content
   (`createStaticVNode`), which Vue writes with `innerHTML` from a string.
5. **Leaflet's attribution line is ours.** Leaflet's attribution control joins its line into a string and writes it
   with `innerHTML`; that string carries the Server Owner's attribution text, so no exact list can hold it. Both maps
   switch the control off and add one that builds the same line from nodes, the attribution as text.
6. **Lane L1 fails a test on any CSP violation** a page, frame or worker it drove reported
   (`tests/e2e/src/cspViolations.ts`); every spec takes `test` from there (`tests/e2e/eslint.config.js`). A refusal
   under Trusted Types happens only at run time, in a real browser, so this is the guard.

## Consequences

- An XSS needs more than a string reaching a sink: in a browser with Trusted Types the sink refuses it whatever code
  put it there, ours or a library's, and the only ways to markup are the renderer and an exact list of library
  literals. A browser without Trusted Types still gets the renderer and the run-time refusal of `v-safe-html`, never
  the enforcement of the libraries' sinks.
- **A library upgrade can break a screen at run time.** A changed literal, a new `innerHTML` or a worker from a
  `blob:` URL is refused; L1 names it. The fix is a reading of what the library writes, then an entry in
  `LIBRARY_MARKUP` (fixed markup only) or code that hands it an element instead.
- **Not covered:** the admin console (`src/admin-web/`) does not enforce Trusted Types; it renders no user content.
  Firefox and Safari were not exercised: lane L1 drives Chromium only.

### Alternatives

- **Only a sanitizing `default` policy**, with no named one: every sink, ours included, would take a plain string and
  sanitize it on the way. It would hide where markup is made (no call site says so), would run the Markdown renderer
  over library markup it would mangle, and leaves the guarantee to whatever the default policy accepts. A named
  policy behind a typed directive makes the one door visible and compile-time checked; `default` stays an exact list.
- **Allowing Vue's `vue` policy** (no build plugin): the common setup, and it makes `v-html` of any string pass
  enforcement — the one sink the change exists to close.
- **A typed component instead of a directive** (`<SafeHtml :html>`): the same type check, and an extra component in
  the tree of every markdown block; the directive leaves the DOM as it was.
- **Patching Leaflet** for its inline-SVG probe instead of listing the literal: a second dependency patch for a
  constant string that carries no data.

## Related

- [ADR 0069](0069-backend-serves-the-web-app-shell.md) (the policy as a header the app shell sends).
- Run area of the docs site: [Hardening](../../site/src/content/docs/run/hardening.md)
