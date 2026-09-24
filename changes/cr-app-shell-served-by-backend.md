---
id: cr-app-shell-served-by-backend
title: The backend serves the app shell — SPA fallback, the CSP as a header, security headers, a revalidated shell
status: planned
satisfies: [cc.security.csp]
adrs: ["0069", "0013", "0015", "0016", "0064"]
invariants: [operator-blind E2EE, self-hostable by default]
---

## Goal

The release image serves `web/dist` as plain Quarkus static files, and every deployment target inherits what
that gets wrong. Measured 2026-09-23 against a fast-jar built with `dist` in `META-INF/resources` (the
`Dockerfile`'s layout):
- **Deep links 404.** A direct load of `/recover`, `/tenant/accept`, `/s/<id>` or `/login` returns 404
  `Resource not found`. Only `/` and `/index.html` answer 200, so every mailed recovery or invite link, every
  share link and every reload off `/` is broken in production.
- **The store is blocked on Kubernetes.** The CSP is a build-time `<meta>` whose `connect-src` cannot reach a
  store on its own origin. The Helm target is exactly that case: a page-context fetch to such a store fires
  `securitypolicyviolation` on `connect-src`.
- **No headers.** The app origin sends no `frame-ancestors`, `nosniff`, `Referrer-Policy` or HSTS.
- **The shell is cached.** `index.html` and `sw.js` go out as `public, immutable, max-age=86400`.

After this CR a small backend component, the **app shell**, serves the client on every target. It falls back
to `index.html` for HTML navigations, sends the policy from `csp.ts` as a response header with the store's
origin appended, sets the security headers and revalidates the shell. It also refuses to boot a shell that has
no policy.

## Context & constraints

- **The decision:** [ADR 0069](../docs/decisions/0069-backend-serves-the-web-app-shell.md). **The policy, the
  per-signing-mode store-origin rule and the header values:**
  [cc.security.csp](../docs/crosscutting/cc.security.csp.md). Both are written; this CR only builds them. Do
  not re-decide where the shell lives (backend, not proxy) or the header values here. Both were settled with
  the owner on 2026-09-23, including the fold-in of the caching fix and fail-closed.
- **Operator-blind stays intact.** The server serves a static shell and learns nothing new. The `#fragment`
  that carries the recovery and invite secrets never reaches it ([ADR 0015](../docs/decisions/0015-operator-blind-readable-urls.md)).
- **Self-hostable by default.** The shell is inside the one image every target runs, so a self-hoster's own
  proxy needs no configuration for any of this. HSTS has to be switchable on both targets without editing a
  template.
- **`Referrer-Policy` is `strict-origin-when-cross-origin`, never `no-referrer`.** See
  [ADR 0016](../docs/decisions/0016-geocoding-provider-neutrality.md): the default tile and geocoder providers
  require a Referer from web pages.
- **Where the shell hooks in.** It goes in at the Vert.x level, not as a JAX-RS catch-all resource, for two
  reasons:
  - a catch-all resource competes with the API's own path matching;
  - under `quarkus.security.jaxrs.deny-unannotated-endpoints=true` it would also need `@PermitAll`.

  Today nothing of veyq's sits there. Locator:
  `grep -rn "ServerRequestFilter\|ServerResponseFilter\|Observes Router\|@Route\b\|RouteFilter" backend/src/main/kotlin`.
  `TenantSuspensionFilter` is a RESTEasy filter, so it is expected not to see static responses (not
  measured). If so, a suspended tenant keeps getting the shell and its API refuses as before, which is not new.
- **The store origin comes from the signer.** `S3BlobStore.endpointFor` resolves which endpoint a presigned URL
  names. The origin appended to `connect-src` is derived from that same resolution, exposed by the blob store,
  and never re-derived from config in a second place. Locator:
  `grep -n "endpointFor\|SigningMode\.\|pathStyleAccessEnabled" backend/src/main/kotlin/com/veyq/app/shared/blobstore/BlobStore.kt`.
  Signing is path-style everywhere, so the endpoint's origin is the fetched origin.
- **Out of scope:**
  - Store responses and the playback Service Worker's synthesized responses. They are not the shell's
    ([ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md) §6, ADR 0047) and belong to
    `cr-inert-store-responses`.
  - admin-web, which has its own nginx with a fallback and headers, and shares nothing with `web/`. Its HSTS
    is `cr-admin-origin-hsts` (see § Ordering).
  - `Permissions-Policy` and COOP/COEP, which are not decided.

## Ordering

**With `cr-inert-store-responses`: no ordering constraint.** The two CRs harden disjoint responses: that one the
store's and the Service Worker's, this one the app shell's. On compose, Traefik routes the store's `/veyq` path
before the app, so the backend never sees a store response, and the two CSPs (`sandbox` there, the app policy
here) never meet on one response. They share files only textually: `infra/docker-compose.prod.yml` (labels on
the store service there, one env line on the app service here), `infra/README.md` and ADR 0013. The second to
land rebases.

**With `cr-upload-windowed-presign`: no ordering constraint, only overlap.** That CR reshapes the presign sites
in `BlobStore`, and phase 1 here adds a read of the signer's endpoint resolution beside them. Whichever lands
second rebases; phase 1's test anchors on whatever presign call exists by then, not on a symbol name.

**With `cr-admin-origin-hsts`: this CR first.** That CR makes the admin container a second reader of phase 3's
HSTS switch (cc.security.csp § *admin-web* says why the two origins share one). So phase 3 names the `.env` key
and the Helm value for the deployment rather than for the app, and writes the `infra/README.md` sentence so that
the second origin can join it.

This CR does put the Service Worker under the app policy for the first time (phase 2). On compose the worker's
store fetches are same-origin, so `'self'` covers them in either order.

**Within this CR: backend first, then the web switch.** Each phase is ordered so the tree never serves less
protection than the one before it:
- Phase 1 sends the header only when an emitted policy exists, which no real `dist` has yet. The `<meta>` stays
  in force.
- Phase 2 moves the policy from `<meta>` to header and turns on fail-closed in the same phase. Web first would
  leave a window with no CSP at all; fail-closed first would stop a phase-1 image from booting.

## Phases

- [ ] 1 backend — **the app shell**, with the header sent only when an emitted policy is present. Measure the
      trust-chain baseline before the first edit:
      `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`.
      - **Fallback.** A `GET` or `HEAD` naming `text/html` in `Accept`, outside `/api/` and `/__media/`, that no
        real file or backend route answers, gets `index.html` (200). It never shadows something that answers.
      - **Caching.** `Cache-Control: no-cache` on `index.html`, on every fallback answer and on `sw.js`. Hashed
        `/assets/*` keep Quarkus' `immutable`.
      - **Headers.** `nosniff`, `Referrer-Policy` and HSTS go on **every** response, `/api` and errors
        included. The CSP goes on every shell response, `sw.js` included (ADR 0069 Decision 2). HSTS takes
        `veyq.http.hsts.enabled` (default `true`) and `veyq.http.hsts.max-age` (default 180 days) in
        `VeyqConfig`.
      - **Store origin.** The blob store exposes the client-facing store origin: none for `tenant-domain` or the
        in-memory store, the `public-endpoint` origin for `store-origin`, the `endpoint` origin for
        `internal-endpoint`. The shell appends it to `connect-src`, once at boot.
      - **No shell on the classpath.** The backend serves the API with the plain headers and no fallback. This
        is the CI e2e jar and dev.

      Tests (`@QuarkusTest` over a fixture shell: an `index.html` with no `<meta>`, an asset, `sw.js` and a
      policy file; the implementer picks between the shared test classpath and a test profile). **The defect is
      live, so write the deep-link test first and see it red (404) before the fallback exists.** Every negative
      carries a positive control on the same predicate:
      - `/recover` and `/s/x`, loaded as HTML, → 200 with the fixture shell, `HEAD` included.
      - `/recover` with `Accept: application/json` → 404. Control: the same path as HTML → 200.
      - `/assets/missing-x.js` with `Accept: */*` → 404. Control: the fixture's real asset → 200.
      - `/api/<unknown>` as HTML → 404, not the shell. Control: `/api/setup` answers.
      - `/__media/x` as HTML → 404. Control: `/recover` with identical headers → 200.
      - The `Cache-Control` split: shell and `sw.js` versus the asset.
      - `nosniff` and `Referrer-Policy` on a shell response **and** an `/api` response.
      - HSTS present by default and absent when disabled.
      - The CSP equals the fixture policy plus the store origin, per signing mode: the three modes and the
        in-memory store.
      - **The appended origin is the origin of a URL the signer actually produced in that mode**, not a
        second reading of the config. This is what makes "cannot disagree with the signer" a tested claim.
      - The no-shell branch: no fallback, plain headers present.

      · Done when: `./gradlew check` green, and the deep-link test has been seen red before the fallback.
- [ ] 2 web + backend — **switch the policy from `<meta>` to header, and fail closed.**
      - In build mode, the Vite CSP plugin emits the policy as a file in `dist` and injects **no** `<meta>`. The
        dev-server header stays as it is.
      - `buildCsp` adds `frame-ancestors 'none'` unconditionally, so the dev header gets it too and dev still
        matches production. The `csp.test.ts` case that pins its absence flips.
      - A `dist` check next to `verify:no-backdoor` asserts that the policy file exists and that `index.html`
        carries no CSP `<meta>`. It runs in `npm run check` and in the `Dockerfile`'s frontend stage, so a
        plugin regression fails CI rather than a self-hoster's boot.
      - The backend's shell refuses to start when `index.html` is present and either the policy is missing or
        a CSP `<meta>` is present (ADR 0069 Decision 3).

      Locator: `grep -n "cspPlugin\|transformIndexHtml\|buildCsp" web/vite.config.ts web/src/shared/security/*.ts`.

      Bite:
      - Temporarily re-enable the `<meta>` injection. The dist check goes red, and the fail-closed unit test
        with a `<meta>` fixture goes red.
      - Drop the policy from the fixture. Fail-closed refuses.

      · Done when: `npm run check` (web) and `./gradlew check` green, both bites seen red.
- [ ] 3 infra — **the HSTS switch on both targets**, without editing a template:
      - compose: an `infra/.env.example` entry, passed through with default `true` in
        `infra/docker-compose.prod.yml`;
      - Helm: a value, with its `values.schema.json` entry, rendered into the app's env in `app-deployment.yaml`.

      `infra/README.md` names the switch once for both targets, together with the reason to use it: the site
      is also served over plain HTTP somewhere, or the operator's own proxy already sets HSTS. Locator for the
      env blocks: `grep -n "PROXY_ADDRESS_FORWARDING" infra/docker-compose.prod.yml charts/veyq/templates/app-deployment.yaml`.
      No proxy changes are needed: Traefik and the Ingress carry nothing for the app.

      · Done when: `helm lint charts/veyq -f charts/veyq/ci/lint-values.yaml` and a `helm template` with the
        value flipped both render the env; trust-chain guards green (the ones that read the compose and chart
        files: `grep -ln "charts/veyq\|docker-compose.prod\|\.env\.example" scripts/validate_*.py`).
- [ ] 4 docs — **what is built stops reading as decided.**
      - `cc.security.csp` updates its Status line (the header delivery is built) and its Verification (the new
        backend tests, the dist check, the live proof below).
      - ADR 0016's and `geocode.ts`'s *"the repository sets no `Referrer-Policy`"* now reads: it sets
        `strict-origin-when-cross-origin`, which keeps the origin in Referer, and `no-referrer` still would not.
      - ADR 0013 §6's parenthetical *"the web CSP is a `<meta>` in `index.html`"* now says what is true: the
        app's policy is a header on the shell's own responses, and a store response is not one.
      - The "deferred to the future web-serving component" comments in `csp.ts` and `vite.config.ts` go.
      - Inbox #158 becomes DONE.

      Locator:
      `grep -rn -i -E "meta.{0,20}(csp|content-security)|<meta>.{0,40}(csp|polic)|Referrer-Policy|served elsewhere|web-serving component|frame-ancestors" docs web/src web/vite.config.ts | grep -v docs/inbox.md`,
      plus `grep -rn "Deferred\|deferred" docs/crosscutting/cc.security.csp.md web/src/shared/security`.
      ADR 0047's *"a document on the app origin without a CSP"* stays: it is about the Service Worker's
      synthesized response, which the shell never serves.

      · Done when: trust-chain guards green.

## Definition of done

- Every phase is done. `./gradlew check` and `npm run check` are green, and every trust-chain guard that was
  green at the phase-1 baseline is still green.
- **Live proof**, against a fast-jar built with `dist` exactly as this session measured it:
  1. build `web/dist`;
  2. copy it into `backend/src/main/resources/META-INF/resources`;
  3. run `./gradlew quarkusBuild -x test`;
  4. **delete the copy**;
  5. run the jar against a throwaway MariaDB.

  Then:
  - `/recover`, `/tenant/accept` and `/s/<id>`, loaded in a browser, render the app.
  - The shell's response carries the CSP with `frame-ancestors 'none'`, plus `nosniff`, `Referrer-Policy`,
    HSTS and `no-cache`. An `/api` response carries the three plain headers.
  - With `VEYQ_BLOB_STORE_SIGNING_MODE=store-origin` and a `PUBLIC_ENDPOINT` on another origin, a
    page-context `fetch` to that origin raises **no `securitypolicyviolation`** (the violation event is the
    discriminator; the fetch may still fail on CORS against the dev store), where today's build raises one.
  - The app boots to sign-in with zero CSP violations in the console.
- **Not proven by any of the above, and named so nobody reads it as covered:**
  - Video playback through the Service Worker under its first-ever policy is not exercised unless the
    implementer uploads and plays a video against the jar. If that is skipped, say so in the retrospective.
  - The Kubernetes path itself (a real Ingress and a store on its own host) is shown only through the jar in
    `store-origin` mode, never on a cluster.
- Coverage: this CR satisfies a cross-cutting concern, which has no coverage YAML. Its record is
  `cc.security.csp` § Verification (phase 4).

## Retrospective
