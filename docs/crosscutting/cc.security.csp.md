# Concern: Web Content-Security-Policy

- **ID:** cc.security.csp
- **Type:** **Cross-cutting concern.** It is a horizontal, browser-enforced security mechanism for the `src/web/`
  surface and for the response headers of the app origin, and for the `src/admin-web/` console and its origin's headers (§ *admin-web*). It has no standalone end-user goal, so it is not a
  use-case, and it is not a feature substrate, so it is not an enabler. It is recorded here so that its policy,
  rationale and verification are traceable from the threat model.
- **Status:** built as described below: by the backend's app shell
  ([ADR 0069](../decisions/0069-backend-serves-the-web-app-shell.md)) for the app origin, and by the admin
  container's nginx for the admin origin (§ *admin-web*). The admin origin's served headers were live-verified
  once (§ *Verification*); what no check covers is named there too.

## Protects against

The web client is where **all** content crypto happens. It holds decryption keys in memory and renders
**decrypted** content (Memory notes, terms, and share notes via `v-html`). The dominant residual risk is *content
injection*: a hostile string reaches the DOM, for example through a crafted shared note or a compromised
dependency, and turns into executing script. Without a CSP, such a script could send keys and plaintext to any
host.

The CSP is the browser-enforced backstop. Even if injection happened, `script-src 'self'` stops a foreign script
from running, and `connect-src` stops it from phoning home. It is **defense-in-depth**, not the first line. The
first line is that decrypted HTML is sanitised escape-first ([`renderMarkdown`](../../src/web/src/shared/markdown.ts)).
The CSP is what holds if that ever fails.

This does **not** re-blind the operator or a third party; it is orthogonal to
[security-model](security-model.md). Map tiles and geocoding remain a *consented* third-party boundary
([ADR 0016](../decisions/0016-geocoding-provider-neutrality.md)). The CSP only limits which origins the page may
talk to at all.

## Mechanism

[`csp.ts`](../../src/web/src/shared/security/csp.ts) is the **one source** of the policy. It is generated from the
app's own provider config ([`src/web/vite.config.ts`](../../src/web/vite.config.ts)) and delivered as a
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
img-src 'self' data: blob: [<tile-origin>]; media-src 'self' blob:; font-src 'self';
connect-src 'self' [<store-origin>] [<geocoder-origin>] [<breach-check-origin>]; form-action 'self'
```

The bracketed origins are appended by the backend on every response; the built policy names none of them.

Why each non-obvious source is there:

- **`script-src 'self'`, with no `unsafe-*`.** The entry is a hashed same-origin module. The app has no inline
  script, no `eval`/`Function` and no WebAssembly (Argon2id is pure-JS `@noble`), so scripts stay strictly
  same-origin.
- **`style-src 'unsafe-inline'`.** The app uses `:style` bindings, which cannot carry a nonce (no component library
  injects a `<style>` tag any more, ADR 0107; tightening the directive is inbox #523). Style injection is far
  lower-risk than script, so this is an accepted gap.
- **`frame-ancestors 'none'`.** This is the anti-clickjacking rule: no page on any origin may frame the app. It
  only works in a header, which is one reason the policy is not a `<meta>`.
- **`object-src 'none'` / `frame-src 'none'`.** The app embeds nothing, and a decrypted file is never given a frame: the
  browser's own PDF viewer needs a frame on a `blob:` URL, so Depot draws a PDF itself with pdf.js onto canvases instead
  (§ *A dependency that parses untrusted bytes*; [Depot § The viewer](../apps/depot.md#the-viewer-filedetail--fileviewer-inbox-412)).
  Widening either is a decision, not a tweak.
- **`img-src data: blob:` / `media-src blob:`.** These cover the favicon SVG and QR `data:` URLs, and the object
  URLs of decrypted media.
- **`<tile-origin>` (img-src), `<geocoder-origin>` and `<breach-check-origin>` (connect-src).** The outside services
  a member's browser calls directly: the basemap, the reverse geocoder and the client-direct HIBP k-anonymity
  password check (automatic in the password-choosing dialogs, over the finished password; only a 5-hex SHA-1 prefix
  leaves the device, [security model](security-model.md) § the breached-password check). Each is a Server-Owner
  setting and a register entry (`memories.map`, `memories.places`, `core.breached-password`;
  [ADR 0088](../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) decision 7,
  [uc.admin.configure-external-services](../features/admin/use-cases/uc.admin.configure-external-services.md)). The
  backend appends a service's origin only while its address is configured and its entry is on at the instance
  level, so the client reads the address from the same manifest the policy follows: one source, no drift. The origins
  are a snapshot recomputed after every write that can change them, because the header is written on the event loop.
  A tenant owner's own switch acts through the tenant's set, not through the policy. A new instance is seeded with
  OSM tiles, Nominatim and the HIBP range API; the dev server's header allows those three
  ([`mapDefaults.ts`](../../src/web/src/shared/state/mapDefaults.ts)).
- **`<store-origin>`** is appended by the backend at runtime. Every client `fetch`es store objects and decrypts
  them; no store response is ever rendered ([ADR 0013](../decisions/0013-blob-storage-presigned-direct-to-store.md)
  §2). So `connect-src` is the only directive that needs the store. The origin comes from the signer's own
  endpoint resolution, so it cannot disagree with the URLs the backend signs:
  - `signing-mode=tenant-domain` (compose) appends **nothing**, because the store is same-origin by
    construction and `'self'` covers it;
  - `store-origin` (Kubernetes, [ADR 0064](../decisions/0064-kubernetes-helm-deployment-target.md)) appends
    the origin of `public-endpoint`;
  - `internal-endpoint` (a publicly reachable external S3) appends the origin of `endpoint`.

## A dependency that parses untrusted bytes: pdf.js (inbox #412)

A PDF is a peer's document, and `pdfjs-dist` (Mozilla, Apache-2.0, the legacy build) parses and draws it in the page's
origin, next to the keys. Its threat note, and what keeps it inside the policy above **without changing it**:

- **It runs in a worker** (`worker-src 'self'`): the parser and the interpreter run off the page thread, in a same-origin
  hashed module worker the build emits (`assets/pdf.worker.min-<hash>.js`, served with a script MIME type). The page thread
  holds only the drawing onto a canvas. A worker has no DOM and no access to the page's memory, but it shares the origin,
  so this is containment of a crash and of CPU, not a sandbox against a pdf.js defect.
- **No code from strings:** the CSP has no `'unsafe-eval'`; the emitted pdf.js files contain no `new Function(`/`eval(`
  (`npm run verify:pdf-worker` fails the build otherwise), and pdf.js 6 has no eval path to switch off.
- **No WebAssembly:** `useWasm: false`, the `.wasm` decoders are not shipped (`verify:pdf-worker` fails on a `.wasm` or a
  `wasm/` directory), because compiling them needs `'wasm-unsafe-eval'`. Cost: JBIG2 and JPEG 2000 images (scans) and ICC
  transforms do not render; the page shows gaps. Granting `'wasm-unsafe-eval'` is the owner's decision.
- **No network but the origin:** the document is our decrypted buffer (`{data}`, never a URL); the CMaps, standard fonts
  and ICC profile pdf.js reads on demand are same-origin copies under `assets/pdfjs-<version>/`, and `connect-src 'self'`
  already covers them. No origin was added.
- **No script, no navigation:** a PDF's JavaScript is never run (no scripting manager, no sandbox file) and there is no
  annotation layer, so a link in a PDF is pixels, not an element; the text layer that makes the text selectable is `<span>`s
  only (no `<a>`, no handler), so a link's text can be copied and never followed; the page holds no frame, object, embed,
  link or form control for a PDF to use. The text layer is built by pdf.js through the DOM and CSSOM (`style-src 'self'
  'unsafe-inline'` already allows it), its rules are in our own stylesheet, and it adds no origin.
- **A password** typed for a protected PDF goes to pdf.js (`onPassword`) in the worker and nowhere else: never stored,
  logged or sent.
- **Bounded:** 64 MiB per file, 1000 pages, 32 M pixels per decoded image, 16 M pixels per page canvas; destroyed (document
  and worker) on a new file, navigation and unmount.
- **Maintenance (measured 2026-10-12):** 6.4.299, published 2026-10-03, monthly releases, not deprecated; the project rule
  on unmaintained dependencies applies — a deprecation or an abandonment makes replacing it in-scope.

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
container's nginx ([`src/admin-web/nginx.conf.template`](../../src/admin-web/nginx.conf.template)), and that nginx sends
every header of the admin origin.

- **The policy stays a `<meta>`.** Its one source is
  [`src/admin-web/src/shared/security/csp.ts`](../../src/admin-web/src/shared/security/csp.ts), and
  `src/admin-web/vite.config.ts` delivers it as a `<meta>` in the build and as a header on the Vite dev server. It is
  stricter than web's: there are no map providers, so the only origin besides its own is the HIBP range API's
  default origin in `connect-src`. A `<meta>` built into a static file cannot follow the Server Owner's setting, so
  the console's own first-run check reaches only that default origin: a mirror elsewhere reads there as "could not
  check", and no check runs at all while the instance's manifest carries no `core.breached-password`. It carries **no `frame-ancestors`**, because a `<meta>` ignores it. Anti-framing is nginx's
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
  chart's `admin.domain` route, and nginx is the only component they share. The default exposure (localhost-only,
  or a port-forward) is plain HTTP, where a browser ignores the header.

  Without `preload`, HSTS never protects the first visit. It keeps every later visit on HTTPS for its `max-age`.
  Preload is out of reach for arbitrary self-hosted domains, so a network attacker can still downgrade the first
  visit to an admin domain.

  The value is a literal in the template. The switch reaches nginx through the image's entrypoint hook
  ([`30-veyq-backend-url.sh`](../../src/admin-web/docker-entrypoint.d/30-veyq-backend-url.sh)), which renders the
  directive whole or commented out and stops the container on any value but `true`/`false`: a mistyped
  security switch fails loudly rather than landing silently on or off. The header reaches every response,
  `index.html` included, through `add_header_inherit merge` at the server level; the template's comment carries
  the inheritance trap that makes this necessary.

## Verification

- [`src/web/src/shared/security/csp.test.ts`](../../src/web/src/shared/security/csp.test.ts) pins the directives:
  - no `unsafe` on script;
  - `object-src 'none'`;
  - no third-party origin baked in when providers are unset;
  - `ws:` in dev only;
  - `frame-ancestors 'none'` in dev and production alike.
- [`src/web/scripts/assert-pdf-worker-in-dist.mjs`](../../src/web/scripts/assert-pdf-worker-in-dist.mjs) (`npm run verify:pdf-worker`,
  in `npm run check`) pins what lets pdf.js run under this policy: the same-origin worker, the lazy chunk, no eval, no WASM
  (§ *A dependency that parses untrusted bytes*). **Run in a real browser 2026-10-12** against a local server sending the
  built header: the worker started, pages drew, a standard font loaded from the same origin, no violation fired.
- [`src/web/scripts/assert-csp-policy-in-dist.mjs`](../../src/web/scripts/assert-csp-policy-in-dist.mjs)
  (`npm run verify:csp`, run by `npm run check` and by the `Dockerfile`'s frontend stage) fails a `dist` that
  has no emitted policy or whose `index.html` carries a CSP `<meta>`.
- The backend's app shell (`src/backend/core/src/main/kotlin/me/raddatz/veyq/core/appshell/`):
  - `AppShellTest` compares the appended store origin, per signing mode, with the origin of a URL the signer
    produced in that mode, and pins the fail-closed refusals (no policy, a CSP `<meta>`);
  - `AppShellSliceTest` runs over a fixture shell. It covers the fallback, with a positive control for every
    negative (not HTML, a missing chunk, `/api`, `/__media`, `/__download`), the `no-cache`/`immutable` split, the policy on
    shell responses and `sw.js` but not on `/api`, and the plain headers on both;
  - `AppShellStoreOriginSliceTest` shows the store origin reaching a real response in `store-origin` mode;
    `AppShellHstsOffSliceTest` the HSTS switch; `AppShellAbsentSliceTest` the no-shell branch.
- Live-verified on 2026-09-24 (cr-app-shell-served-by-backend), against a fast-jar built with `dist` in
  `META-INF/resources` and run in `store-origin` mode with the store on another origin:
  - `/recover`, `/tenant/accept`, `/s/<id>` and `/login` load the app, where they answered 404 before;
  - the shell carries the policy with `frame-ancestors 'none'` and the store origin, plus `nosniff`,
    `Referrer-Policy`, HSTS and `no-cache`, and `/api` carries the three plain headers;
  - a page-context `fetch` to the store origin raises no `securitypolicyviolation`, while the same fetch to an
    origin not in the policy raises one on `connect-src`;
  - the app boots with zero CSP violations in the console. That instance had no account yet, so the screen
    it booted to was "not set up" rather than sign-in.
- **Not verified:** video playback through the Service Worker under its policy. The worker registered on none of
  the screens reached, so only its script's response headers were checked. The Kubernetes path itself (a real
  Gateway and a store on its own host) is shown only through the jar in `store-origin` mode, never on a cluster.
- [`src/admin-web/src/shared/security/csp.test.ts`](../../src/admin-web/src/shared/security/csp.test.ts) pins admin-web's
  policy the same way: same-origin scripts, `connect-src` exactly `'self'` + the HIBP origin, no other
  third-party origin anywhere, no `frame-ancestors`.
- The admin origin's headers (§ *admin-web*) have no CI test: no workflow starts the admin container. Checked
  once on 2026-09-27 (cr-admin-origin-hsts), without nginx:
  - the entrypoint hook, run against the template, renders the HSTS directive for the switch unset, empty and
    `true`, renders it commented out for `false`, and exits 1 naming `VEYQ_HSTS_ENABLED` for `bogus` and
    `TRUE`;
  - `docker compose config` and `helm template` carry the one deployment value to the app and the admin
    container alike, both ways;
  - nginx 1.30.0's headers module merges `add_header_inherit` into a location that does not set it, so the
    server-level `merge` reaches `= /index.html` (read in its source, `ngx_http_headers_merge_conf`).
- **Served, measured 2026-09-28** (the built `src/admin-web/Dockerfile` image, run with no backend on
  127.0.0.1:18081). The image builds and starts on the 1.30 base. **Switch unset:** `/`, `/setup`, a hashed
  asset and `/api/setup` (a 502) all carry `Strict-Transport-Security: max-age=15552000` plus
  `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy` and `X-Robots-Tag`; `/` and `/setup` also
  `Cache-Control: no-store`. **Switch `false`:** the same four headers everywhere, no STS anywhere.
  **Switch `bogus`:** the container exits 1 with `veyq-admin: VEYQ_HSTS_ENABLED must be 'true' or 'false',
  got 'bogus'`. **Bite:** with `add_header_inherit merge;` removed from a template mounted over the image's,
  `/setup` keeps only `Cache-Control` while the asset keeps all five — the merge is what carries them.
- **Not verified:** that a browser records the admin host as an HSTS host. That needs a trusted certificate on
  a real admin domain, and a browser ignores STS on a certificate error.

For Server Owners, on the docs site: [Hardening](../../site/src/content/docs/run/hardening.md).
