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
**decrypted** content as markup (share notes, terms, the privacy notice). The dominant residual risk is *content
injection*: a hostile string reaches the DOM, for example through a crafted shared note or a compromised
dependency, and turns into executing script. Without a CSP, such a script could send keys and plaintext to any
host.

The CSP is the browser-enforced backstop. Even if injection happened, `script-src 'self'` stops a foreign script
from running, and `connect-src` stops it from phoning home — except while `core.share-embedding` is on, which admits every
`https` origin (§ *Mechanism*, `https:`) and leaves that half to `script-src` and Trusted Types. `connect-src 'self'` was
never a tight half, either: a top-level navigation (`location`, `window.open` to a URL that carries the data; CSP has no
`navigate-to`) passes it, and so, in Chromium, do WebRTC and DNS prefetch. What it stops is the silent, repeated, bulk
`fetch`, and that is what `https:` gives back. Markup itself comes only from the escape-first renderer
([`renderMarkdown`](../../src/web/src/shared/markdown.ts)), and the policy makes the browser enforce that: under
Trusted Types no string reaches an HTML sink or a script URL unless one of the client's two policies made it
(§ *Trusted Types*, [ADR 0117](../decisions/0117-the-web-client-enforces-trusted-types.md)).

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
  surface while developing. Dev differs in two sources and one pair of directives: `ws:` for HMR, a style nonce, and
  no Trusted Types. Vite injects every stylesheet as a `<style>` element, so it stamps them with a nonce drawn fresh
  at each dev-server start (`html.cspNonce`) and the header names it; the build loads its CSS as files and carries no
  nonce. Vite's client writes its error overlay with `innerHTML`, and the dev server loads Vue without the build's
  patch (§ *Trusted Types*), so Trusted Types are the built policy's alone and lane L1 is where they run.

The policy:

```
default-src 'self'; base-uri 'self'; object-src 'none'; frame-src 'none'; frame-ancestors 'none';
script-src 'self' 'wasm-unsafe-eval'; worker-src 'self'; style-src 'self' <reka-style-hashes>;
img-src 'self' data: blob: [<tile-origin>]; media-src 'self' blob:; font-src 'self';
connect-src 'self' [<store-origin>] [<geocoder-origin>] [<breach-check-origin>] [<rates-origin>] [https:]; form-action 'self';
require-trusted-types-for 'script'; trusted-types veyq default
```

The bracketed sources are appended by the backend on every response; the built policy names none of them
(`verify:csp` refuses a scheme or a wildcard in its `connect-src`).
`<reka-style-hashes>` is one `'sha256-…'` per fixed `<style>` text a reka-ui component the client uses renders
(below).

Why each non-obvious source is there:

- **`script-src 'self' 'wasm-unsafe-eval'`, with no `'unsafe-eval'` and no `'unsafe-inline'`.** The entry is a hashed
  same-origin module. The app has no inline script and no `eval`/`Function` (Argon2id is pure-JS `@noble`), so scripts
  stay strictly same-origin. `'wasm-unsafe-eval'` lets the page compile WebAssembly, not evaluate JavaScript: the client
  encodes a space icon and a member picture with its own build of libwebp (`src/web/webp-encoder/`, [ADR 0127](../decisions/0127-the-web-client-encodes-webp-with-its-own-build-of-libwebp.md)),
  because Safari's canvas answers WebP with a PNG. An attacker who could hand the page a module would first need to run
  script in it, which `'self'` and Trusted Types refuse; one who runs script already holds the unlocked keys. The source
  is granted for the page, not for that module, so any further WebAssembly (pdf.js's own, inbox #837) is a decision
  of its own — what it is, where it comes from, how it is pinned — never a consequence of this source. The encoder is
  fetched as bytes from the origin and instantiated from them (never streamed), so it needs no `connect-src` beyond
  `'self'`.
- **`style-src 'self'` and hashes, no `'unsafe-inline'`** (inbox #523). The built CSS is same-origin files. Vue applies
  `:style` bindings, `v-show` and Leaflet's and pdf.js's positioning through the CSSOM (`el.style.x = …`), which
  `style-src` does not govern; what it governs is a `<style>` element and a `style="…"` attribute parsed from markup,
  and the app has neither (no static `style=` in a template, `v-safe-html` gets the escape-first markdown, which writes
  none). The one `<style>` a component we use renders is reka-ui's `SelectViewport`, a fixed rule that hides its
  list's scrollbar; a fixed text is allowed by its hash (`REKA_STYLE_HASHES` in `csp.ts`). pdf.js loads a PDF's fonts
  through the FontFace API, and the Emergency Kit's jsPDF draws text and a QR image only, so html2canvas (which would
  inject a `<style>` into a frame) is never loaded. Measured in a browser on 2026-10-23 (§ *Verification*). An
  injected `<style>` or `style=` attribute is now refused (`style-src-elem` / `style-src-attr`), so injected markup can
  no longer restyle the page, for example lay a fake control over a real one.
- **`frame-ancestors 'none'`.** This is the anti-clickjacking rule: no page on any origin may frame the app. It
  only works in a header, which is one reason the policy is not a `<meta>`.
- **`object-src 'none'` / `frame-src 'none'`.** The app embeds nothing, and a decrypted file is never given a frame: the
  browser's own PDF viewer needs a frame on a `blob:` URL, so Depot draws a PDF itself with pdf.js onto canvases instead
  (§ *A dependency that parses untrusted bytes*; [Depot § The viewer](../apps/depot.md#the-viewer-filedetail--fileviewer-inbox-412)).
  Widening either is a decision, not a tweak.
- **`img-src data: blob:` / `media-src blob:`.** These cover the favicon SVG and QR `data:` URLs, and the object
  URLs of decrypted media.
- **`<tile-origin>` (img-src), `<geocoder-origin>`, `<breach-check-origin>` and `<rates-origin>` (connect-src).** The
  outside services a member's browser calls directly: the basemap, the reverse geocoder, the client-direct HIBP
  k-anonymity password check (automatic in the password-choosing dialogs, over the finished password; only a 5-hex
  SHA-1 prefix leaves the device, [security model](security-model.md) § the breached-password check) and Expenses' day
  exchange rates (automatic where an entry's currency differs from its Pot's; only a date leaves the device, same §).
  Each is a Server-Owner setting and a register entry (`memories.map`, `memories.places`, `core.breached-password`,
  `expenses.exchange-rates`; [ADR 0088](../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) decision 7,
  [uc.admin.configure-external-services](../features/admin/use-cases/uc.admin.configure-external-services.md)). The
  backend appends a service's origin only while its address is configured and its entry is on at the instance
  level, so the client reads the address from the same manifest the policy follows: one source, no drift. The origins
  are a snapshot recomputed after every write that can change them, because the header is written on the event loop.
  A tenant owner's own switch acts through the tenant's set, not through the policy. A new instance is seeded with
  OSM tiles, Nominatim, the HIBP range API and Frankfurter's ECB rates; the dev server's header allows those four
  ([`mapDefaults.ts`](../../src/web/src/shared/state/mapDefaults.ts)).
- **`https:` (connect-src), while `core.share-embedding` is effective** ([ADR 0121](../decisions/0121-a-received-share-is-browsed-in-place-through-its-own-api.md)
  decision 2; on by default, owner 2026-11-06). A kept link's page browses its share in place, reading the share's own
  instance's API directly, and that instance can be any `https` origin; the server must not learn which ones (a list
  computed from the members' bookmarks would tell it). **What it costs:** an injected script may send what it reads to a
  host of its choice by a silent, repeatable `fetch` — the one way out `'self'` closed, since a navigation, and in Chromium
  WebRTC and DNS prefetch, pass it anyway; `script-src 'self'`, Trusted Types and the absence of inline script remain in
  front of it. The
  switch is per tenant (operator `*`, tenant owner `+`): the backend appends `https:` for the tenant whose domain serves
  the page, and for a host that claims no tenant at the instance level. Like the origins above it is read from a
  snapshot (`ShareEmbeddingHosts`, by domain), recomputed after every switch write and every tenant write that moves a
  domain, because the header is written on the event loop; a snapshot that is stale only ever names a domain it no longer
  knows, which then follows the instance level, at most as wide as the tenant it was. Off, the policy is as before. In
  development the Vite policy serves as if it were on and also admits `http://*.localhost:*`, the plain-http hosts a
  second local tenant lives on. `img-src` and `media-src` are unchanged: a share's media are decrypted into `blob:` URLs.
- **`<store-origin>`** is appended by the backend at runtime. Every client `fetch`es store objects and decrypts
  them; no store response is ever rendered ([ADR 0013](../decisions/0013-blob-storage-presigned-direct-to-store.md)
  §2). So `connect-src` is the only directive that needs the store. The origin comes from the signer's own
  endpoint resolution, so it cannot disagree with the URLs the backend signs:
  - `signing-mode=tenant-domain` (compose) appends **nothing**, because the store is same-origin by
    construction and `'self'` covers it;
  - `store-origin` (Kubernetes, [ADR 0064](../decisions/0064-kubernetes-helm-deployment-target.md)) appends
    the origin of `public-endpoint`;
  - `internal-endpoint` (a publicly reachable external S3) appends the origin of `endpoint`.

## Trusted Types

`require-trusted-types-for 'script'` makes a browser with Trusted Types refuse a plain string at every HTML sink and
every script-URL sink; `trusted-types veyq default` names the only two policies that may make a value for one, both
created by [`trustedTypes.ts`](../../src/web/src/shared/security/trustedTypes.ts) before any library module loads (the
first import of `main.ts`). Why this shape and not another is [ADR 0117](../decisions/0117-the-web-client-enforces-trusted-types.md).

- **`veyq`** is the sanitizer: its `createHTML` runs `renderMarkdown`. A template shows markup only as
  `markdownHtml(text)` through `v-safe-html`, which takes no string (by type, and at run time in every browser).
  Its second purpose, `parseInertXml`, parses a photo's XMP into a document of its own for the EXIF library.
- **`default`** answers for code that cannot name `veyq`: it passes the exact fixed markup in `LIBRARY_MARKUP` and, as
  a script URL, only a same-origin file under `assets/` or `sw.js`; anything else is refused.
- **Vue's `vue` policy** (a pass-through Vue writes `v-html` with) is compiled out of the build, and `verify:csp`
  checks it is gone.

The sinks, measured in the built bundle and the source on 2026-11-05 (`grep` of `dist/` for `innerHTML`, `outerHTML`,
`insertAdjacentHTML`, `document.write`, `DOMParser`, `createContextualFragment`, `srcdoc`, `new Worker`,
`importScripts`, `serviceWorker.register`, `createPolicy`, a `script` element's `src`):

| Where | Sink | How it passes |
|---|---|---|
| Our templates: share notes, terms, the operator section, the privacy notice | `innerHTML` | `markdownHtml` → `v-safe-html` (`veyq`) |
| `exifTags.ts` → ExifReader's XMP parse | `DOMParser.parseFromString` | `parseInertXml` handed as its `domParser` (`veyq`) |
| Our four workers (digest, frames, listing opener, media deriver), `@veyq/crypto`'s Argon2 worker, pdf.js' worker | `new Worker(url)` | `default`'s script-URL rule |
| The media service worker | `serviceWorker.register('/sw.js')` | `default`'s script-URL rule |
| Leaflet: the inline-SVG probe at load, the zoom buttons | `innerHTML` of a fixed literal | `default`'s `LIBRARY_MARKUP` |
| Leaflet: the attribution control | `innerHTML` of a joined string | not used: both maps add ours, built from nodes (`mapAttribution.ts`) |
| Leaflet: a `divIcon`'s `html` | `innerHTML` when it is a string | every icon hands an element |
| Leaflet: popups, layers and scale controls, VML probe | `innerHTML` | not used (no popup, no such control; VML only without SVG) |
| Vue: `v-html`, an `innerHTML` binding, compiled static content | `innerHTML` | none in the client (`v-html` unused; `verify:csp` refuses static content) |
| jsPDF's `html()`, html2canvas, DOMPurify, canvg | `innerHTML`, `DOMParser`, a policy `dompurify` | lazy chunks the client never loads (the Emergency Kit draws text and an image) |
| vue-i18n, reka-ui, qrcode, jsQR, pdf.js' page thread | none | |

A library upgrade that writes a new string, or changes one of the listed literals, is refused at run time and fails
lane L1 (§ *Verification*).

## A dependency that parses untrusted bytes: pdf.js (inbox #412)

A PDF is a peer's document, and `pdfjs-dist` (Mozilla, Apache-2.0, the legacy build) parses and draws it in the page's
origin, next to the keys. Its threat note, and what keeps it inside the policy above **without changing it**:

- **It runs in a worker** (`worker-src 'self'`): the parser, the interpreter and the image decoders run off the page
  thread, in a same-origin hashed module worker the build emits (`assets/pdf.worker.min-<hash>.js`, served with a script
  MIME type). The page thread holds only the drawing onto a canvas. A worker has no DOM and no access to the page's memory,
  but it shares the origin, so this is containment of a crash and of CPU, not a sandbox against a defect in pdf.js or in
  a decoder.
- **No code from strings:** the CSP has no `'unsafe-eval'`; the emitted pdf.js files and its two image decoders contain no
  `new Function(`/`eval(` (`pnpm run verify:pdf-worker` fails the build otherwise), and pdf.js 6 has no eval path to
  switch off.
- **The image decoders are plain JavaScript, not WebAssembly** ([ADR 0128](../decisions/0128-pdf-js-decodes-scanned-images-with-its-plain-javascript-decoders.md)):
  a scan's CCITT G4, JBIG2 and JPEG 2000 images are decoded by pdf.js's JavaScript builds of PDFium's JBIG2 decoder and of
  OpenJPEG (`jbig2_nowasm_fallback.js`, `openjpeg_nowasm_fallback.js`). The build copies exactly these two files, unchanged,
  from `pdfjs-dist`'s `wasm/` directory to `assets/pdfjs-<version>/decoders/`, so they are pinned with the rest of pdf.js
  (one package, one lockfile integrity), and their four notices go into the built licence file. `wasmUrl` points there;
  the worker imports a decoder the first time a document holds such an image — a same-origin module import that
  `script-src 'self'` admits. They parse untrusted bytes and are compiled from C, so they sit under the worker's
  containment and its limit like the rest of the parser, and the 32 M pixels per decoded image bound them too.
  `useWasm` stays `false` and no `.wasm` of pdf.js ships: `verify:pdf-worker` fails on a `wasm/` directory, on any `.wasm`
  but the WebP encoder's, and on a `decoders/` holding anything but those two files byte for byte the package's. The policy
  would compile pdf.js's WebAssembly since [ADR 0127](../decisions/0127-the-web-client-encodes-webp-with-its-own-build-of-libwebp.md)
  granted `'wasm-unsafe-eval'`; whether its speed on a large JPEG 2000 page and its ICC colour are worth a `.wasm` is
  inbox #837. Cost meanwhile: ICC-profiled colour falls back to its alternate colour space.
- **No network but the origin:** the document is our decrypted buffer (`{data}`, never a URL); the CMaps, standard fonts,
  ICC profile and image decoders pdf.js reads on demand are same-origin copies under `assets/pdfjs-<version>/`, and
  `connect-src 'self'` already covers the fetches, `script-src 'self'` the decoders' import. No origin was added.
- **No script, no navigation:** a PDF's JavaScript is never run (no scripting manager, no sandbox file) and there is no
  annotation layer, so a link in a PDF is pixels, not an element; the text layer that makes the text selectable is `<span>`s
  only (no `<a>`, no handler), so a link's text can be copied and never followed; the page holds no frame, object, embed,
  link or form control for a PDF to use. The text layer is built by pdf.js through the DOM and CSSOM (which
  `style-src` does not govern), its rules are in our own stylesheet, and it adds no origin.
- **A password** typed for a protected PDF goes to pdf.js (`onPassword`) in the worker and nowhere else: never stored,
  logged or sent.
- **Bounded:** 64 MiB per file, 1000 pages, 32 M pixels per decoded image, 16 M pixels per page canvas; destroyed (document
  and worker) on a new file, navigation and unmount.
- **Maintenance (measured 2026-11-12, `pnpm view pdfjs-dist time`):** 6.5.438, published 2026-11-01 and the latest, the one
  before it 6.4.299 of 2026-10-03, monthly releases, not deprecated; the project rule
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

- **`Cross-Origin-Opener-Policy: same-origin`.** A share link is opened from a mail or another site: the page
  keeps no handle on its opener, nor the opener on it. Nothing in the client opens a cross-origin window that must
  talk back (no OAuth or payment popup, no `window.open`; measured 2026-11-05 by `grep`).
- **`Cross-Origin-Resource-Policy: same-origin`.** No other origin embeds or reads a response of the instance
  no-cors: the client loads everything from its own origin, and no mail embeds an image from it. **One exception:** while
  `core.public-shares.embeddable` is effective, a guest's operation under `/api/shares/{shareId}`, `/api/setup` and
  `/api/moderation/key` answer any origin — `Cross-Origin-Resource-Policy: cross-origin` with `Access-Control-Allow-Origin:
  *`, never `Access-Control-Allow-Credentials` — so a member of another instance browses a link in place
  ([ADR 0121](../decisions/0121-a-received-share-is-browsed-in-place-through-its-own-api.md) decision 3, `ShareCrossOrigin`;
  `sharing.openapi.yaml` § Cross-origin has the headers and whose switch an id reads).
- **`Permissions-Policy`.** The features the client calls, for its own origin: `camera` (the Emergency Kit scanner),
  `clipboard-write` (copying a link, a guest id, a text), `fullscreen` (the file viewer, a video's control),
  `autoplay` (the lightbox's and the banner's video, the media deriver's muted `play()`) and `picture-in-picture` (a
  video's own control); every other feature Chromium knows off — the position and the microphone among them. A name
  Chromium does not know is left out, because it logs an error for it on every page (`bluetooth` and `web-share`,
  in lane L1 on 2026-11-05). The list is `AppShellRoutes.PERMISSIONS_POLICY`, with the call behind each feature; a feature the client starts to use is
  added there.
- **Not `Cross-Origin-Embedder-Policy`.** It would refuse the map tiles, which come from another origin that sends
  no CORP of its own ([ADR 0018](../decisions/0018-map-tiles-provider-neutrality.md)).

These six go on **every** response, the API's included: each acts on a document or a no-cors read and is inert on
JSON, and an API path opened as a page is a document like any other. The policy goes on the shell's responses only.

Store responses are **not** the app shell's. Wherever the store is reached, including on the app's own origin
through the compose path route, the rule that makes those responses inert is ADR 0013 §6.

## admin-web

The Server-Owner console ([ADR 0065](../decisions/0065-admin-ui-cli-separate-service.md)) is served by its own
container's nginx ([`src/admin-web/nginx.conf.template`](../../src/admin-web/nginx.conf.template)), and that nginx sends
every header of the admin origin.

- **The policy is a header nginx sends**, its one delivery: a header carries `frame-ancestors 'none'`, which a
  `<meta>` would ignore. Its one source is
  [`src/admin-web/src/shared/security/csp.ts`](../../src/admin-web/src/shared/security/csp.ts) (`ADMIN_CSP`); the
  template's `add_header Content-Security-Policy` line is held equal to it by `csp.test.ts`, and the Vite dev server
  sends it as a header too. It follows web's `style-src` rule (no `'unsafe-inline'`; reka's fixed `<style>` texts by
  hash, here `SelectViewport`'s and `ComboboxViewport`'s; on the dev server a nonce). It names `worker-src 'self'`
  although `script-src 'self'` already covers the Argon2 worker by fallback, so no edit of `script-src` can widen what
  may run as a worker. It is stricter than web's: there are no map providers, so the only origin besides its own is
  the HIBP range API's default origin in `connect-src`. A literal in the template cannot follow the Server Owner's
  setting, so the console's own first-run check reaches only that default origin: a mirror elsewhere reads there as
  "could not check", and no check runs at all while the instance's manifest carries no `core.breached-password`.
  `X-Frame-Options: DENY` stays for a browser older than `frame-ancestors`. The console does not enforce Trusted Types
  ([ADR 0117](../decisions/0117-the-web-client-enforces-trusted-types.md)): it renders no user content.
- **COOP, CORP and a Permissions-Policy**, as the app origin's (§ *The other response headers*): `same-origin` both,
  and of the features only `clipboard-write` (copying an invite link and an order's text) for its own origin.
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
  - `script-src` exactly `'self' 'wasm-unsafe-eval'`, in dev and production, and no `'unsafe-eval'`,
    `'unsafe-inline'` or `'unsafe-hashes'` on it (bitten 2026-11-12: `'unsafe-eval'` added to `buildCsp()` turns it red;
    the refusal before ADR 0127, `/script-src[^;]*unsafe-eval/`, matched `'wasm-unsafe-eval'` itself);
  - `object-src 'none'`;
  - no third-party origin baked in when providers are unset;
  - `ws:` and the style nonce in dev only;
  - `https:` and `http://*.localhost:*` in dev only, no scheme or wildcard in the built `connect-src` (ADR 0121);
  - `style-src` exactly `'self'` plus the reka hashes, no `'unsafe-inline'` in dev or production;
  - the hashes equal exactly those of the fixed `<style>` texts the reka-ui components the client imports render,
    recomputed from the installed reka-ui by following its own imports, so an upgrade that changes a text or a new
    import of a styling component turns it red;
  - `frame-ancestors 'none'` in dev and production alike;
  - `require-trusted-types-for 'script'` and `trusted-types veyq default` in the build, neither in dev.
  Bitten 2026-10-23: the old `style-src 'self' 'unsafe-inline'`, an emptied hash list and a probe component importing
  `ScrollAreaViewport` each turn it red.
- [`src/web/src/shared/security/trustedTypes.test.ts`](../../src/web/src/shared/security/trustedTypes.test.ts), over a
  stand-in Trusted Types factory: the module creates exactly the two policies the built CSP names; `markdownHtml` is the
  renderer's output as the policy's value; `veyq` makes nothing without one of its purposes; `v-safe-html` refuses a
  string, with and without Trusted Types; `default` passes exactly `LIBRARY_MARKUP` and the client's own script URLs (a
  foreign origin, `/api/`, `/blobs/`, `..`, `data:`, `blob:`, `javascript:` refused); a string assigned to `SafeHtml`
  does not compile (`@ts-expect-error`, held by `pnpm run typecheck`). Bitten 2026-11-05: a template that binds a
  string to `v-safe-html` fails `vue-tsc` (`Type 'string' is not assignable to type 'SafeHtml'`).
- [`src/web/scripts/assert-pdf-worker-in-dist.mjs`](../../src/web/scripts/assert-pdf-worker-in-dist.mjs) (`pnpm run verify:pdf-worker`,
  in `pnpm run check`) pins what lets pdf.js run under this policy: the same-origin worker, the lazy chunk, no eval in
  either nor in the image decoders, no WASM of pdf.js — the one `.wasm` it allows is the WebP encoder's, once, byte for
  byte the committed file — and `decoders/` holding exactly the two plain-JavaScript decoders, byte for byte `pdfjs-dist`'s
  (§ *A dependency that parses untrusted bytes*; bitten 2026-11-12: pdf.js's `openjpeg.wasm` copied into `dist/assets/`,
  its `jbig2.wasm` or `quickjs-eval.js` copied into `decoders/`, one flipped byte of a decoder and an `eval(0)` appended to
  one each turn it red). `pdfEngine.real.test.ts` decodes a CCITT G4, a JBIG2 and a JPEG 2000 image through our options to
  their pixels; without `wasmUrl` all three are red. **Run in a real browser 2026-10-12** against a local server sending
  the built header: the worker started, pages drew, a standard font loaded from the same origin, no violation fired.
  **Scans, 2026-11-12** (cr-pdf-scan-decoders), headless Chrome against a local server sending the built header on every
  response: the built `pdfjsRuntime` chunk, imported from `dist/` (its import of the main chunk installed the app's own
  Trusted Types policies), opened a CCITT G4, a JBIG2 and a JPEG 2000 PDF and drew each pixel for pixel; each document's
  worker imported its decoder from `assets/pdfjs-<version>/decoders/`, once; no `securitypolicyviolation`, no CSP or
  Trusted Types message in the console; with `decoders/` removed all three drew blank. That server typed `.js` itself;
  the backend's own typing is `AppShellSliceTest.pdfJsImageDecodersAreServedAsJavaScript` (a decoder under
  `assets/pdfjs-<version>/decoders/` is answered as `text/javascript;charset=UTF-8` with `nosniff`, measured 2026-11-12;
  bitten by an unsatisfiable type predicate). Not shown by either: the Depot viewer around the renderer.
- [`src/web/scripts/assert-csp-policy-in-dist.mjs`](../../src/web/scripts/assert-csp-policy-in-dist.mjs)
  (`pnpm run verify:csp`, run by `pnpm run check` and by the `Dockerfile`'s frontend stage) fails a `dist` that
  has no emitted policy or whose `index.html` carries a CSP `<meta>`, whose policy lacks the two Trusted Types
  directives, whose `script-src` is not exactly `'self' 'wasm-unsafe-eval'` (bitten 2026-11-12 with `'unsafe-eval'`
  added), or carries a scheme or wildcard in `connect-src` (the backend's `https:` is per tenant; bitten 2026-11-06:
  with the dev-only `https:` made unconditional it is red, naming `connect-src carries https:`), or whose scripts still create Vue's `vue` policy or a static vnode. Bitten 2026-11-05: without the build
  plugin that removes Vue's policy it is red (`a bundled script still creates Vue's \`vue\` policy`).
- The backend's app shell (`src/backend/core/src/main/kotlin/me/raddatz/veyq/core/appshell/`):
  - `AppShellTest` compares the appended store origin, per signing mode, with the origin of a URL the signer
    produced in that mode, and pins the fail-closed refusals (no policy, a CSP `<meta>`);
  - `AppShellSliceTest` runs over a fixture shell. It covers the fallback, with a positive control for every
    negative (not HTML, a missing chunk, `/api`, `/__media`, `/__download`), the `no-cache`/`immutable` split, the policy on
    shell responses and `sw.js` but not on `/api`, and the plain headers — COOP, CORP and the Permissions-Policy among
    them — on the shell, `sw.js`, an asset and `/api`, no COEP, and each feature granted to `'self'` at most;
  - `AppShellStoreOriginSliceTest` shows the store origin reaching a real response in `store-origin` mode;
  - `AppShellShareEmbeddingSliceTest` holds `https:` in `connect-src` while `core.share-embedding` is on (a tenant's
    domain and a host claiming none), gone everywhere while the Server Owner has it off, gone on one tenant's domain
    while that tenant has it off and kept on another's, and following a tenant whose domain moved;
  - `ShareCrossOriginSliceTest` holds the guest share API's CORS answer per path class (the switch on, off by the
    operator, off by the link's tenant), the curator's operations and a member's path as same-origin controls, no
    `Access-Control-Allow-Credentials` anywhere, and an id the gate does not serve answered exactly as an unknown one;
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
  policy the same way: same-origin scripts, `worker-src` exactly `'self'`, `style-src` exactly `'self'` plus its reka
  hashes (recomputed as web's are; the nonce in dev only), `connect-src` exactly `'self'` + the HIBP origin, no other
  third-party origin anywhere, `frame-ancestors 'none'`; and it reads `nginx.conf.template` as nginx does and holds its
  `Content-Security-Policy` equal to `ADMIN_CSP`, COOP and CORP `same-origin`, `X-Frame-Options: DENY`, and a
  Permissions-Policy whose one `(self)` feature is `clipboard-write`. Bitten 2026-10-23 with the old `style-src` and
  without `worker-src`; 2026-11-05 with the template's `frame-ancestors 'self'` and a CORP `cross-origin`.
- **Without `'unsafe-inline'`, measured in a browser on 2026-10-23** (inbox #523, #527), a document-level
  `securitypolicyviolation` listener and the console read on every screen:
  - web, the dev server and the built app behind the backend's own app shell (`dist` in `META-INF/resources`, one origin
    in front of shell and store as compose's proxy does): sign-in, all thirteen settings sections, a Memory created
    and opened, the report dialog's select (reka's `<style>` applied through its hash, its scrollbar hidden), the
    location editor and the level's map with tiles and a marker, the share dialog, a PDF uploaded and opened in Depot
    (worker started, a font loaded through FontFace, pages and text layer drawn) and an Emergency Kit made and saved
    through the email change's real flow in the build (jsPDF's chunk, a `blob:` download, no `<style>`, no frame).
    No violation from any of them;
  - admin-web, the dev server and the image built from `src/admin-web/Dockerfile` against a fresh instance: first-run
    setup with its Emergency Kit download, sign-in, the operator panel's country combobox and the store panel's select.
    No violation;
  - the control: an injected `<style>` and a `style="…"` attribute are refused on both.
  The console also showed, once, a `blob:` worker refused under `worker-src 'self'` in the built web app, as two
  earlier sessions had seen on the web and admin dev servers (#527). No code in either built bundle, their workers,
  `sw.js` or `@veyq/crypto` creates one (pdf.js wraps its worker in a `blob:` only for a cross-origin `workerSrc`, and
  ours is same-origin), and none of page load, sign-in, the settings, the PDF, the map, the kit download or the
  browser tooling's actions reproduced it alone; every worker the app starts loaded same-origin. It is refused as it
  should be and breaks nothing, and its source stays unlocated. The 400 seen at the same time was the cold start's
  session restore: `POST /api/token/refresh` with no refresh cookie, which now answers `401 no_session` by contract
  (`auth.openapi.yaml`, inbox #535; then `400 invalid_request`), with a stale one `401 refresh_rejected`, on both
  clients.
- **Lane L1's violation fixture** ([`tests/e2e/src/cspViolations.ts`](../../tests/e2e/src/cspViolations.ts), every
  spec's `test`; `tests/e2e/eslint.config.js` refuses Playwright's own): each context the suite opens reports every
  `securitypolicyviolation` of its documents and frames, and every CSP or Trusted Types message its pages' and workers'
  consoles log, and a test fails on what was reported since the previous one. It is what proves Trusted Types at run
  time; measured 2026-11-05 on the release candidate built from this tree (`scripts/run_stack_e2e.py`, the whole
  suite in one run, Chromium of the pinned Playwright image): 166 tests passed in all 38 spec files — first run and the
  console, the Memory round trip (a photo whose XMP the EXIF reader parses), lightbox, video playback and seeking through
  the service worker, the banner, the share password and guest pages, Depot with its file viewer and downloads, the
  safety number's QR scan by camera, the account, terms and privacy pages, moderation, Expenses, export and import —
  with no violation and no CSP or Trusted Types console message. Two tests failed on a visibility assertion and failed
  the same way on an image of the commit before this change, so they are not this policy's. Bitten the same day: an
  image whose `main.ts` assigns `'<b>bite</b>'` to an `innerHTML` (inside a `try`, so the app still runs) turns the
  first setup test red, naming `require-trusted-types-for enforce - Element innerHTML|<b>bite</b>` and Chromium's console
  report. **Not verified:** Firefox and Safari, which the lane does not drive; a browser without Trusted Types still runs
  the renderer and `v-safe-html`'s refusal, never the enforcement.
- The admin origin's headers (§ *admin-web*): the template is pinned by admin-web's `csp.test.ts`, and lane L1 drives
  the admin image (first-run setup, the console) under them; no workflow inspects the served headers themselves. Checked
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

For Server Owners, on the docs site: [Hardening](../../site/src/content/docs/run/hardening.md); for researchers,
[Known limitations](../../site/src/content/docs/security/known-limitations.md) (the `https:` widening).
