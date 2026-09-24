# Concern: Web Content-Security-Policy

- **ID:** cc.security.csp
- **Type:** **Cross-cutting concern.** It is a horizontal, browser-enforced security mechanism for the `web/`
  surface and for the response headers of the app origin, and for the `admin-web/` console and its origin's headers (§ *admin-web*). It has no standalone end-user goal, so it is not a
  use-case, and it is not a feature substrate, so it is not an enabler. It is recorded here so that its policy,
  rationale and verification are traceable from the threat model.
- **Status:** **the header delivery described below is decided but not yet built**
  ([ADR 0069](../decisions/0069-backend-serves-the-web-app-shell.md)). Until it is built:
  - the build injects the same policy into `index.html` as a `<meta>` (without `frame-ancestors`, which a
    `<meta>` ignores);
  - the Vite dev server sends it as a header;
  - the app origin sends none of the other headers.

  Two consequences follow. A store on its own origin (the Kubernetes target) is blocked by `connect-src`, and a
  `<meta>` cannot be widened from outside.

## Protects against

The web client is where **all** content crypto happens. It holds decryption keys in memory and renders
**decrypted** content (Memory notes, terms, and share notes via `v-html`). The dominant residual risk is *content
injection*: a hostile string reaches the DOM, for example through a crafted shared note or a compromised
dependency, and turns into executing script. Without a CSP, such a script could send keys and plaintext to any
host.

The CSP is the browser-enforced backstop. Even if injection happened, `script-src 'self'` stops a foreign script
from running, and `connect-src` stops it from phoning home. It is **defense-in-depth**, not the first line. The
first line is that decrypted HTML is sanitised escape-first ([`renderMarkdown`](../../web/src/shared/markdown.ts)).
The CSP is what holds if that ever fails.

This does **not** re-blind the operator or a third party; it is orthogonal to
[security-model](security-model.md). Map tiles and geocoding remain a *consented* third-party boundary
([ADR 0016](../decisions/0016-geocoding-provider-neutrality.md)). The CSP only limits which origins the page may
talk to at all.

## Mechanism

[`csp.ts`](../../web/src/shared/security/csp.ts) is the **one source** of the policy. It is generated from the
app's own provider config ([`web/vite.config.ts`](../../web/vite.config.ts)) and delivered as a
**response header**, never as a `<meta>`. This follows [ADR 0069](../decisions/0069-backend-serves-the-web-app-shell.md):

- **In production** the web build emits the policy as a file inside `dist`. The backend's app shell reads it at
  startup, appends the store origin (below) and sends the result with every response it serves, `sw.js`
  included. A worker takes its policy from its own script's response, so this is also what puts the playback
  Service Worker under the policy. The shell refuses to start when `index.html` is present but the emitted
  policy is missing, or when `index.html` still carries a CSP `<meta>`.
- **In dev** the Vite dev server sends the same policy as a header, so dev and production match and violations
  surface while developing. Dev additionally allows `ws:` for HMR.

The policy:

```
default-src 'self'; base-uri 'self'; object-src 'none'; frame-src 'none'; frame-ancestors 'none';
script-src 'self'; worker-src 'self'; style-src 'self' 'unsafe-inline';
img-src 'self' data: blob: <tile-origin>; media-src 'self' blob:; font-src 'self';
connect-src 'self' https://api.pwnedpasswords.com <geocoder-origin> [<store-origin>]; form-action 'self'
```

Why each non-obvious source is there:

- **`script-src 'self'`, with no `unsafe-*`.** The entry is a hashed same-origin module. The app has no inline
  script, no `eval`/`Function` and no WebAssembly (Argon2id is pure-JS `@noble`), so scripts stay strictly
  same-origin.
- **`style-src 'unsafe-inline'`.** PrimeVue and `@primeuix` inject `<style>` tags at runtime, and the app uses
  `:style` bindings. Neither can carry a nonce. Style injection is far lower-risk than script, so this is an
  accepted gap.
- **`frame-ancestors 'none'`.** This is the anti-clickjacking rule: no page on any origin may frame the app. It
  only works in a header, which is one reason the policy is not a `<meta>`.
- **`img-src data: blob:` / `media-src blob:`.** These cover the favicon SVG and QR `data:` URLs, and the object
  URLs of decrypted media.
- **`connect-src https://api.pwnedpasswords.com`.** This is the client-direct HIBP k-anonymity password check.
  Only a 5-hex SHA-1 prefix leaves the device.
- **`<tile-origin>` (img-src) / `<geocoder-origin>` (connect-src).** These are the configured basemap and
  reverse-geocoder ([`mapDefaults.ts`](../../web/src/shared/state/mapDefaults.ts)). The default is
  OSM/Nominatim, and `VITE_VEYQ_MAP_*` overrides it. Both are derived from the **same** config the app fetches
  from, so a self-hosted map deployment stays covered without anyone editing the policy by hand: one source, no
  drift. A *per-device localStorage* override to an **arbitrary** host is limited to the origins the policy
  allows, because a policy fixed at build time cannot anticipate a runtime host.
- **`<store-origin>`** is appended by the backend at runtime. Every client `fetch`es store objects and decrypts
  them; no store response is ever rendered ([ADR 0013](../decisions/0013-blob-storage-presigned-direct-to-store.md)
  §2). So `connect-src` is the only directive that needs the store. The origin comes from the signer's own
  endpoint resolution, so it cannot disagree with the URLs the backend signs:
  - `signing-mode=tenant-domain` (compose) appends **nothing**, because the store is same-origin by
    construction and `'self'` covers it;
  - `store-origin` (Kubernetes, [ADR 0064](../decisions/0064-kubernetes-helm-deployment-target.md)) appends
    the origin of `public-endpoint`;
  - `internal-endpoint` (a publicly reachable external S3) appends the origin of `endpoint`.

## The other response headers

The app shell sends these on the app origin alongside the policy
([ADR 0069](../decisions/0069-backend-serves-the-web-app-shell.md)):

- **`X-Content-Type-Options: nosniff`.** A response is only ever what its declared type says.
- **`Referrer-Policy: strict-origin-when-cross-origin`**, and deliberately not `no-referrer`. The default map
  providers' usage policies want a Referer from a web page, and ADR 0016 records that a policy which strips it
  puts every instance outside them. This value sends only the origin cross-origin, never a path.
- **`Strict-Transport-Security: max-age=15552000`** (180 days), on by default and switchable off per
  deployment (see [infra/README](../../infra/README.md)). It has no `includeSubDomains` and no `preload`:
  - tenant domains are arbitrary hosts, and a self-hoster's sibling hosts may still be HTTP-only;
  - a browser keeps the entry for the whole `max-age` even after the header is switched off, which is why the
    value is moderate.

  Over plain HTTP a browser ignores the header, so a local or LAN instance is unaffected.

Store responses are **not** the app shell's. Wherever the store is reached, including on the app's own origin
through the compose path route, the rule that makes those responses inert is ADR 0013 §6.

## admin-web

The Server-Owner console ([ADR 0065](../decisions/0065-admin-ui-cli-separate-service.md)) is served by its own
container's nginx ([`admin-web/nginx.conf.template`](../../admin-web/nginx.conf.template)), and that nginx sends
every header of the admin origin.

- **The policy stays a `<meta>`.** Its one source is
  [`admin-web/src/shared/security/csp.ts`](../../admin-web/src/shared/security/csp.ts), and
  `admin-web/vite.config.ts` delivers it as a `<meta>` in the build and as a header on the Vite dev server. It is
  stricter than web's: there are no map providers, so the only origin besides its own is the HIBP range API in
  `connect-src`. It carries **no `frame-ancestors`**, because a `<meta>` ignores it. Anti-framing is nginx's
  `X-Frame-Options: DENY`, which is the whole guard, not a second layer.

  It is not moved into a header. The app's policy needs a header because it contains a value that is only known
  at runtime, the store origin ([ADR 0069](../decisions/0069-backend-serves-the-web-app-shell.md)). This policy
  has no runtime value, and `X-Frame-Options` already covers the one directive a `<meta>` drops. Revisit if the
  admin policy gains a runtime value, or needs `frame-ancestors` or reporting of its own.
- **HSTS: the app's value, under the app's switch.** nginx sends the same `Strict-Transport-Security` as the app
  shell (§ *The other response headers*), and the one deployment switch turns it off on both origins. There is one
  switch and not two, because two could not be independent. The admin nginx passes the backend's `/api/*` responses
  through unchanged, and once the backend sends HSTS on those responses they reach the admin host as well.

  nginx sends the header, not the proxy in front of it. ADR 0069 rejects headers at the proxy for the app, and the
  same reason holds here: the admin surface has two public exposures, compose's `ADMIN_DOMAIN` router and the
  chart's `admin.domain` Ingress, and nginx is the only component they share. The default exposure (localhost-only,
  or a port-forward) is plain HTTP, where a browser ignores the header.

  Without `preload`, HSTS never protects the first visit. It keeps every later visit on HTTPS for its `max-age`.
  Preload is out of reach for arbitrary self-hosted domains, so a network attacker can still downgrade the first
  visit to an admin domain.

  **Not yet built.** Today this nginx sends no HSTS. It is planned in `cr-admin-origin-hsts`, which follows the
  app shell's switch.

## Verification

- [`web/src/shared/security/csp.test.ts`](../../web/src/shared/security/csp.test.ts) pins the directives:
  - no `unsafe` on script;
  - `object-src 'none'`;
  - no third-party origin baked in when providers are unset;
  - `ws:` in dev only;
  - no `frame-ancestors`, which is today's `<meta>`-era state.
- Live-verified (cr-0133): the app boots under the `<meta>` policy with **zero CSP violations**, and styles,
  fonts and HMR are intact.
- [`admin-web/src/shared/security/csp.test.ts`](../../admin-web/src/shared/security/csp.test.ts) pins admin-web's
  policy the same way: same-origin scripts, `connect-src` exactly `'self'` + the HIBP origin, no other
  third-party origin anywhere, no `frame-ancestors`.
