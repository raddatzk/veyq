# ADR 0069 — The backend serves the web app shell

- Status: Accepted
- Date: 2026-09-23

## Context

The release image carries the web client inside the backend. The `Dockerfile` copies `web/dist` into
`META-INF/resources`, and Quarkus serves those files as plain static resources. Nothing else takes part: no
route, no filter and no header of veyq's own. Every deployment target inherits that, because compose's Traefik,
the Helm chart's Gateway and a self-hoster's own proxy all sit in front of the same image. Measured on 2026-09-23
against a fast-jar built with `dist` in that layout:

- **No SPA fallback.** `/` and `/index.html` answer 200. `/recover`, `/tenant/accept`, `/s/<id>` and `/login`,
  loaded directly as HTML, answer **404** `Resource not found`. The router uses `createWebHistory()`, and a direct
  load is exactly what a mailed recovery or tenant-invite link and a share link are. So is every reload of any
  screen but `/`. [ADR 0015](0015-operator-blind-readable-urls.md) already assumed a fallback returning
  `index.html`, but nothing built it.
- **The CSP cannot follow the store.** The policy ships as a build-time `<meta>` with
  `connect-src 'self' https://api.pwnedpasswords.com <geocoder>`. The Helm target signs presigned URLs for the
  store's own host (`signing-mode=store-origin`, [ADR 0064](0064-kubernetes-helm-deployment-target.md)). A fetch
  from the served shell to a store on another origin fires `securitypolicyviolation` on `connect-src`, so on
  Kubernetes no upload or download works. A response header cannot widen a `<meta>`: a browser enforces every
  policy it is given, so their intersection is what holds. With the `<meta>` in place, the store origin cannot
  be added anywhere.
- **No security headers.** `frame-ancestors` does nothing in a `<meta>`. `X-Content-Type-Options`,
  `Referrer-Policy` and HSTS are not sent at all.
- **Yesterday's shell after an upgrade.** `index.html` and `sw.js` are served
  `public, immutable, max-age=86400`. A browser that loaded the shell within the last day keeps it without
  revalidating after an upgrade. It asks for hashed chunks the new image no longer has, and it talks to a newer
  API than the one it was built for.

## Decision

**A small backend component, the app shell, serves `web/dist` on every target.** It lives in the backend
because the backend is the one component every deployment has in common. The rule that store responses are
inert lives in the signer for the same reason ([ADR 0013](0013-blob-storage-presigned-direct-to-store.md) §6).

1. **SPA fallback.** A `GET` or `HEAD` that asks for HTML (`Accept` names `text/html`) is answered with
   `index.html` (200) when it is outside `/api/`, `/__media/` and `/__download/` and nothing else answers it. Nothing else here
   includes a real file or a route the backend serves. Every other request keeps the answer it gets today:
   - an unknown `/api` path stays a 404;
   - a missing hashed chunk stays a 404 instead of HTML parsed as script, because a script fetch does not ask
     for HTML.

   The fallback does **not** list the router's paths. A second copy of `router.ts` would drift, and the router's
   own catch-all already sends an unknown path to sign-in. The `#fragment` never reaches the server, so the
   secrets in `/recover#…` and `/tenant/accept#…` stay in the browser, which is what ADR 0015 relies on.
2. **The CSP is a response header, with no `<meta>` in production.**
   [`csp.ts`](../../web/src/shared/security/csp.ts) remains the one source of the policy:
   - The web build emits the policy as a file inside `dist`.
   - The backend reads that file at startup and appends, per response, the origin a browser fetches store objects
     from to `connect-src`. That origin comes from the signer's own endpoint resolution, so the policy cannot
     disagree with the URLs the backend signs. The rules per signing mode are in
     [cc.security.csp](../crosscutting/cc.security.csp.md). It appends the origins of the outside services in use
     too (the map tiles to `img-src`, the geocoder and the breached-password check to `connect-src`), which are the
     Server Owner's settings since [ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)
     decision 7 was built: the built policy names none of them.
   - The header goes on every response the shell serves, including `sw.js`. A worker takes its policy from its
     own script's response, so the playback Service Worker gets a policy for the first time. That worker is the
     part of the client that fetches store bytes and holds decrypted media.
3. **Fail closed.** The shell refuses to start when `index.html` is on the classpath and either:
   - the emitted policy is missing, or
   - `index.html` still carries a CSP `<meta>`.

   The CSP is the backstop of an operator-blind client, so an image without it must fail loudly rather than
   boot quietly. A returning `<meta>` would also block every store fetch on Kubernetes again, and no CI run
   would notice. A backend with no shell on its classpath serves the API only. This is dev, where Vite serves
   the app, and the jar the CI e2e run builds.
4. **Security headers.** The app sends `frame-ancestors 'none'` (inside the policy),
   `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin` and HSTS. HSTS is on by
   default with a moderate `max-age`, and a deployment can switch it off. Values and reasons are in
   [cc.security.csp](../crosscutting/cc.security.csp.md).
5. **Caching.** `index.html`, every fallback answer and `sw.js` are sent as `Cache-Control: no-cache`, so the
   next load after an upgrade revalidates the shell. The content-hashed `/assets/*` stay `immutable`.

## Consequences

- **Rejected: headers at the proxy.** A Traefik middleware covers compose only. The Helm chart's gateway
  implementation is the operator's choice, and header configuration is specific to each implementation. Neither covers
  a self-hoster's own proxy. That is three copies of one policy, and none of them can read the store origin the
  backend signs for.
- **Rejected: a separate static web container**, as admin-web runs nginx. admin-web is separate for its own
  reason ([ADR 0065](0065-admin-ui-cli-separate-service.md): a localhost-only admin plane). For the app, a
  separate container would add one on every target and a second place to keep the policy.
- **Rejected: Quinoa.** It would put a Node toolchain inside the backend build. The `Dockerfile` already builds
  `dist` in its own stage.
- **Rejected: keep the `<meta>` and add a header.** The two policies intersect (Context), which is the whole
  reason the Kubernetes target breaks.
- **`dist` served by anything else has no CSP.** Every deployment target serves it through the backend, and
  that is the point of this decision. `vite preview` and a hand-rolled static server are not deployment
  targets.
- **The backend gains a build input from `web/`,** namely the emitted policy file. When it is missing, the
  backend fails to boot (Decision 3); it never downgrades silently.
- **HSTS stays moderate.** There is no `includeSubDomains` and no `preload`, for two reasons:
  - tenant domains are arbitrary hosts, and a self-hoster's sibling hosts may still be HTTP-only;
  - a browser keeps HSTS for the whole `max-age` even after the header is switched off, which is also why the
    `max-age` is moderate.
- **Operator visibility is unchanged.** The operator already sees request paths (ADR 0015), and the fallback
  adds nothing the server did not see before.
