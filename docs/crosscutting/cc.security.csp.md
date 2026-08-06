# Concern: Web Content-Security-Policy

- **ID:** cc.security.csp
- **Type:** **Cross-cutting concern** — a horizontal browser-enforced security mechanism for the `web/` surface;
  it has no standalone end-user goal (not a use-case) and is not a feature substrate (not an enabler). Recorded
  here so its policy + rationale + verification are traceable from the threat model.
- **Status:** baseline shipped as a `<meta>` policy + dev-server header; the header-only directives
  (`frame-ancestors`, HSTS) are **deferred** to the future production web-serving component (see *Deferred* below).

## Protects against

The web client is where **all** content crypto happens: it holds decryption keys in memory and renders
**decrypted** content (Memory notes, terms, share notes via `v-html`). The dominant residual risk is *content
injection* — a hostile string that reaches the DOM (a crafted shared note, a compromised dependency) and turns
into executing script. Without a CSP, such a script could exfiltrate keys/plaintext to any host. The CSP is the
browser-enforced backstop: even if injection happened, `script-src 'self'` blocks a foreign script from running
and `connect-src` blocks it from phoning home. It is **defense-in-depth**, not the first line — the first line
is that decrypted HTML is escape-first sanitised ([`renderMarkdown`](../../web/src/shared/markdown.ts)); the CSP
is what holds if that ever fails.

This does **not** re-blind the operator or a third party — it is orthogonal to
[security-model](security-model.md). Map tiles/geocoding remain a *consented* third-party boundary
([ADR 0016](../decisions/0016-geocoding-provider-neutrality.md)); the CSP simply scopes which origins the page
may talk to at all.

## Mechanism

A single policy string is generated at build time from the app's own provider config and applied two ways
([`web/vite.config.ts`](../../web/vite.config.ts) → [`csp.ts`](../../web/src/shared/security/csp.ts)):

- **`<meta http-equiv="Content-Security-Policy">`** injected into the built `index.html` — ships inside the
  static bundle, needs no server, works on any host (**self-hostable by default**).
- **The same value as a `Content-Security-Policy` response header on the Vite dev server** — so dev == prod and
  violations surface while developing (the dev value additionally allows `ws:` for HMR).

The baseline policy:

```
default-src 'self'; base-uri 'self'; object-src 'none'; frame-src 'none';
script-src 'self'; worker-src 'self'; style-src 'self' 'unsafe-inline';
img-src 'self' data: blob: <tile-origin>; media-src 'self' blob:; font-src 'self';
connect-src 'self' https://api.pwnedpasswords.com <geocoder-origin>; form-action 'self'
```

Why each non-obvious source:

- **`script-src 'self'`, no `unsafe-*`.** The entry is a hashed same-origin module; the app has no inline
  script, no `eval`/`Function`, and no WebAssembly (Argon2id is pure-JS `@noble`), so scripts stay strictly
  same-origin.
- **`style-src 'unsafe-inline'`** — PrimeVue / `@primeuix` inject runtime `<style>` tags and the app uses
  `:style` bindings; neither is nonce-able. Style injection is far lower-risk than script — an accepted gap.
- **`img-src data: blob:` / `media-src blob:`** — favicon SVG + QR `data:` URLs, and decrypted-media object
  URLs.
- **`connect-src https://api.pwnedpasswords.com`** — the client-direct HIBP k-anonymity password check (only a
  5-hex SHA-1 prefix leaves the device).
- **`<tile-origin>` (img-src) / `<geocoder-origin>` (connect-src)** — the configured basemap + reverse-geocoder
  ([`mapDefaults.ts`](../../web/src/shared/state/mapDefaults.ts), default OSM/Nominatim, overridable via
  `VITE_VEYQ_MAP_*`). Derived from the **same** config the app fetches from, so a self-hosted map deployment
  stays covered without hand-editing the policy — one source, no drift. The consented map feature therefore
  keeps working under the CSP; only a *per-device localStorage* override to an **arbitrary** host is
  constrained to origins the operator's policy allows (a static policy can't anticipate a runtime host).
- **The presigned object store** is same-origin (`/veyq`) in the reference deployment (ADR 0013 §4), so
  `connect-src 'self'` covers it. A **split-origin** store deployment must add the store host at the serving
  layer.

## Verification

- [`web/src/shared/security/csp.test.ts`](../../web/src/shared/security/csp.test.ts) pins the directives (no
  `unsafe` on script, `object-src 'none'`, no third-party origin baked when providers are unset, `ws:` dev-only,
  no `frame-ancestors`).
- Live-verified (cr-0133): the app boots under the policy with **zero CSP violations**, styles/fonts/HMR intact.

## Deferred (own follow-up, needs the prod web server)

`frame-ancestors 'none'` (anti-clickjacking) and HSTS are **response-header-only** — a `<meta>` CSP silently
ignores `frame-ancestors`. There is no production web-serving component in the repo yet (infra routes only the
store; web is "added in a fuller deployment"), so these are deferred to whatever serves `web/dist` in
production (a static server or a Traefik middleware), which should emit the full CSP as a header **plus**
`frame-ancestors 'none'`, `Strict-Transport-Security`, and re-assert the policy. The `<meta>` baseline stands
in the meantime for the script/connect/style/img backstop.
