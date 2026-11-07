# ADR 0121 — A received share is browsed in place, through its own instance's API

- Status: Accepted
- Built: yes (its live two-origin pass ran 2026-11-06, § Consequences)
- Date: 2026-11-06
- Model: [en.sharing.public-link](../features/sharing/enablers/en.sharing.public-link.md)

## Context

[ADR 0120](0120-a-received-link-is-kept-as-a-bookmark-node-that-opens-and-reports-where-the-share-lives.md) keeps a
received link as a bookmark node that opens the share page in its place. The owner wants more (2026-11-06, inbox #829):
the share browsed **inside the space**, as if it were a folder there, for a link of any veyq instance, and written to
where the link allows it.

Three things stood in the way:

- **This client may not talk to another origin.** `connect-src 'self'` (plus the store and a few operator-listed
  services, [ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) decision 7) is the
  backstop against injected code sending keys anywhere ([cc.security.csp](../crosscutting/cc.security.csp.md)).
- **No instance answers another origin.** The API uses no CORS: a preflight is `405`, every response carries
  `Cross-Origin-Resource-Policy: same-origin` (`UndeclaredMethodFilter`, `AppShellRoutes`).
- **A guest's access belongs to one page.** `GuestAccess`, its tree, the gates (password, terms, the note's
  acceptance, naming), the guest's identity in local storage and every public client assume the share page of this
  origin.

An iframe of the foreign share page is ruled out by both sides' `frame-src 'none'` / `frame-ancestors 'none'`, and a
proxy through this instance would make it learn what each member reads.

## Decision

1. **A bookmark's page shows its share in place.** Where the share can be embedded (decision 4), the bookmark's page
   draws the link's content under the bookmark's header: core's level at a folder, the root kind's `detail` at any
   other node, a `WRITE_ONLY` link's drop page — the views the share page draws, handed a guest's access. A row's
   plain click opens the bookmark's page, no longer the link; **Open on its own page** keeps ADR 0120 decision 5. The
   navigation inside the share is the page's own (a breadcrumb under the header); the space's URL names the bookmark
   only, so no node id of another instance enters this space's addresses. As built (`core/bookmark/BookmarkEmbed.vue`,
   `core/sharing/ShareTreePage.vue`'s `embedded`):
   - **The switch decides the row too.** `core.share-embedding` is read through `useCapabilities().hasCapability` (the
     tenant's set once loaded, else the instance's). Off: no instance is asked, the page is ADR 0120's without a line and
     the row opens the link. On: the row's plain click opens the bookmark's page. **Open on its own page** leads the
     bookmark's header (`header.order`) in both states.
   - **Navigation inside the share is the page's own state**, never `?node=`; each crumb's real `href` is the bookmark's
     page (`route.fullPath`), its plain click prevented and run in place.
   - **The word is "server"** (de: *Server*), not "instance", in the bookmark's and the embed's texts (owner 2026-11-06).
   - **One `h1`, the bookmark's.** The embedded node's own header is core's, named by an `h2` a size smaller
     (`provideNestedNodeHeading`, `core/node/nodeHeaderContribution.ts`; owner 2026-11-07).
   - **Known limit:** the embed's stored view mode and tab are the member's account preferences (the share page's are
     the guest's).
2. **This client may connect to any `https` origin while embedding is on** (owner, 2026-11-06). The app shell's
   `connect-src` gains `https:` for a tenant whose `core.share-embedding` is effective (ADR 0088: switched by the
   operator `*` and the tenant owner `+`; on by default, owner 2026-11-06); off, the policy is as before and a bookmark
   opens as in ADR 0120. In development the Vite policy also admits `http://*.localhost:*`. `img-src` and `media-src`
   are unchanged: a share's media are decrypted into `blob:` URLs as everywhere. **What it costs:** the CSP no longer
   stops an injected script from sending what it reads to a host of its choice; `script-src 'self'`, Trusted Types
   ([ADR 0117](0117-the-web-client-enforces-trusted-types.md)) and the absence of inline script remain the defences in
   front of it. The owner chose this over an operator-kept list of partner instances, which keeps the backstop but
   reaches only listed instances. As built:
   - **The tenant a page's policy follows** is the one whose domain serves the page (`TenantHostResolver.hostOf`); a
     host that claims no tenant follows the instance level. The header is written on the event loop, so the answer is a
     per-domain snapshot (`ShareEmbeddingHosts`, behind the app shell's seam `HostConnectSources`) refreshed after the
     Server Owner's and a tenant owner's switch write and after every tenant write that moves a domain. A stale snapshot
     is at most as wide as the tenant was; the domain move is the one write that could widen, so it refreshes. It is the one
     tenant-dependent source of the served CSP (ADR 0088, § Consequences).
   - **The dev policy serves as if the switch were on** (`https:` and `http://*.localhost:*`, `shared/security/csp.ts`);
     `verify:csp` refuses a scheme or wildcard in the built `connect-src`. The dev server answers no CORS of its own
     (`server.cors: false`): Vite's default answered a `*.localhost` preflight itself and echoed the origin on proxied
     answers, so a switched-off `core.public-shares.embeddable` still embedded in dev.
3. **An instance lets its links be embedded elsewhere** under `core.public-shares.embeddable` (`*` and `+`, a child of
   `core.public-shares`, on by default). Where it is effective, the public share API — `/api/shares/{shareId}` and
   everything below it — answers any origin: a preflight `OPTIONS` is `204` with `Access-Control-Allow-Origin: *`, the
   methods it serves and the share headers (`X-Share-Password`, `X-Contributor-Token`, `X-Share-Write`,
   `If-None-Match`, `Content-Type`), `Access-Control-Expose-Headers: ETag`; a response carries
   `Access-Control-Allow-Origin: *` and `Cross-Origin-Resource-Policy: cross-origin`. **Never credentials**: these
   endpoints take no cookie and no bearer, the whole link is their capability
   ([ADR 0076](0076-a-guest-write-proves-it-holds-the-links-key.md)), and any HTTP client already reaches them; CORS only
   lets a browser do what `curl` does. The two pre-auth reads a foreign client needs, `/api/setup` and
   `/api/moderation/key`, answer the same way at instance level. Everything else stays same-origin. As built
   (`ShareCrossOrigin`, a pre-matching filter ahead of `UndeclaredMethodFilter`; the app shell writes the headers):
   - **Only a guest's operations.** On those paths only the methods whose operation takes no bearer (`@PermitAll`, held
     to the contract's operations without `bearerAuth` by `ApiMethodIndexContractTest`) are offered; a curator's
     operation on a link (revoke, reactivate, reissue, its sealed name, `PATCH`/`DELETE`) stays same-origin.
   - **No existence oracle.** The switch is read for the tenant of a link the guest gate serves; for any other id —
     unknown, revoked, expired, blocked, its role switched off — at the instance level, which is what an unknown id
     gets. Only a served link, whose existence its own answer already shows, can differ by its tenant's switch.
   - **`Access-Control-Max-Age: 600`.** A switch turned off acts on the actual answer, which then carries no
     `Access-Control-Allow-Origin`, cached preflight or not; the age bounds only how long a method stays offered.
   - **Not covered:** an answer Vert.x writes before JAX-RS (a body over the request limit) carries no CORS header, and
     a preflight is not rate-limited (the limiters are post-matching); it costs the guest gate's reads.

   The bundled store
   already answers any origin ([ADR 0013](0013-blob-storage-presigned-direct-to-store.md) § 4); an operator's own store
   must too, which the Run docs say.
4. **Embedding is decided by what the share's instance says of itself** — the first use of capability discovery across
   instances ([ADR 0032](0032-capability-discovery-version-gating.md), ADR 0088). The client reads the share's
   `/api/setup` (this origin's own needs no CORS) and embeds only where its major equals this client's
   ([ADR 0085](0085-one-major-moved-on-a-yearly-train.md)'s rule for the web client) and it serves `core.public-shares`
   and `core.public-shares.embeddable`; anything else — a refusal, another major, a missing entry, a network error —
   falls back to ADR 0120's open, with one line saying why. A write's own capability (`.contribute`) is the share's
   server's to refuse, as on its page. As built (`core/sharing/shareInstance.ts`): `embeddability` is one pure function
   over what was read, its refusals `switchedOff`, `unreachable` (no answer, or no version or capability list),
   `otherVersion`, `noLinks` and `notEmbeddable`; the fallback line names each but `switchedOff`. "No answer" is its own
   unavailable (`ShareUnavailableError('unreachable')`, where the generated client got no HTTP response — the network,
   or a browser that withheld a cross-origin answer); every refusal stays `not_found`. The share page treats both alike;
   a bookmark's page whose resolve ends `unreachable` after the setup check passed (the link's tenant switched
   `.embeddable` off, so its answers carry no CORS) falls back, and any refusal is the share page's unavailable card, in
   place.
5. **A guest inside a member's page.** The embedded view is given a `GuestAccess` of its own, under its own `NodeHost`
   (the guest's doors) provided below the bookmark's page; nothing of the member's access, keys or doors reaches it, and
   nothing of it reaches the member's tree. `GuestAccess` carries the share's API base (`''` for this origin), and every
   guest read and write takes it — the resolve, the poll, the terms, the blobs, the tree writes, the identities, the
   reports and each application's guest client. What a guest keeps on the device (its identity, its acceptance of the
   terms and of a note) is keyed by the share's origin as well as its id. As built:
   - **Where a guest call goes is a `ShareAt`** (`core/sharing/shareAt.ts`): a bare id (this origin) or `{ shareId,
     apiBase }`; a `GuestAccess` carries `apiBase` (required) and so is one, and a view hands `access` to every guest call.
     Every public factory takes `apiBase` as a **required** argument, so no guest call reaches a base its caller did not
     choose; the this-origin factories pass `''`. A head records where it was resolved (`PublicShareHead.apiBase`, absent
     for this origin), and what starts from a head goes there. `fetch` sends no cookie to another origin by default and no
     factory sets `credentials`; the wire tests assert no `Authorization` and no `include`.
   - **Device keys:** this origin's are byte-identical to before (`<prefix>:<id>`), another instance's
     `<prefix>@<origin>:<id>` (`originKey`/`deviceKey`). The character after the prefix tells them apart, so no id a
     `/s/:shareId` route hands the share page (vue-router decodes `%2F`, so it can hold anything) reads another origin's
     entry. Keyed so: the guest's session (a drop-box guest's too), a note's acceptance, the accepted terms (per tenant
     AND origin), Memories' record of its own uploads, the presigner cache, the guest-notice read cache.
   - **Isolation:** the embed's region provides `NO_DOORS`, no core node actions and no header sink; core's page of the
     link provides the guest's own below, and a `WRITE_ONLY` drop page finds none of the member's.
6. **The gates run inline, in the share page's order**: the password (the bookmark's, entered once; else the form), the
   share's tenant's terms, the note's acceptance, and — for a `READ_WRITE` write — the guest's naming. The components
   are the share page's: `core/sharing/shareGates.ts` (the state machine) and `ShareGates.vue`, extracted from the share
   page unchanged, which still passes its id bare. Embedded, the cards head with `h2`, and the guest uploader's
   statements of reasons on a blocked placeholder are not asked there — the share's own page shows them; the
   removal-order notice is, of the share's instance.
7. **Writes are the link's guest's.** A `READ_WRITE` link's create, upload, move and abandon, a `WRITE_ONLY` link's
   drop, go through the share's API under the guest identity this device holds for it; the share's operator records the
   member's address per write as for any guest, with this page's origin as the request's `Origin`
   ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 10).
8. **A report from the embedded view is a guest's report to the share's instance**, sealed to that instance's
   moderation key (ADR 0120 decision 6 unchanged). As built: the published key and whether Report is offered are held
   per instance (`publishedKey(fresh, apiBase)`, `useReportOffered(apiBase)`), and `reportBaseOf(access)` is a guest's
   link's base, a member's `''`, so a foreign guest's Report shows by the foreign instance's key and seals to it.

## Consequences

- **This instance learns nothing more than ADR 0120 says**: the member's browser talks to the share's instance
  directly. That instance learns the member's address and every read and write, as when the link is opened.
- **The CSP widening is per tenant and visible**: `cc.security.csp` and the Security pages name `https:` and the switch
  that removes it; an operator who wants the backstop back switches `core.share-embedding` off.
- **What the backstop was, honestly.** `connect-src 'self'` never was a tight one: a top-level navigation (`location`,
  `window.open` to an attacker's URL that carries the data; CSP has no `navigate-to`) passes it, and in Chromium so do
  WebRTC and DNS prefetch. What `https:` adds is the silent, repeated, bulk `fetch` — the channel that drains a space
  without a visible page change ([cc.security.csp](../crosscutting/cc.security.csp.md)).
- **A bookmark may point at a hostile "veyq" server.** Whoever mints a link chooses its host, and a server that answers
  the discovery of decision 4 as a veyq of this major is embedded. Then: (1) this client parses responses that server
  shapes — and seals, since it holds the link's key — and draws them with the rules every peer's content meets (the
  decrypted bytes decide the kind, text as text, no markup; a PDF through pdf.js within `PDF_MAX_PAGES` and its pixel
  caps, `shared/pdf/pdfOptions.ts`; the image and video header parsers' own bounds, `shared/image/imageHeader.ts`,
  `videoHeader.ts`). No byte cap of the client's own bounds a JSON answer or a chunk: the generated clients read a whole
  body, the chunk reader a whole frame (`core/blob/readScheduler.ts`), so a hostile server can exhaust the tab's memory —
  a denial of service to that page, nothing more. (2) It follows the presigned URLs that server hands out to any `https`
  host: a `GET` with no credentials, its answer carrying nothing of this instance — tracking, not data. (3) That server
  learns the member's address and the time **each time the bookmark's page is opened** — before this ADR only when the
  member chose to open the link.
- **Write laundering.** Answering every origin, the guest write endpoints included, lets a hostile page that knows a
  `READ_WRITE` or `WRITE_ONLY` link make an unsuspecting visitor's browser write through it — no credential is needed,
  the link is the capability — and the share's guest-write record (ADR 0093 § 10) would then name the visitor's address
  as the writer's. Before this ADR the missing preflight answer stopped such a write. The address alone is therefore no
  longer enough evidence: the record also keeps the request's `Origin`, which a browser states and no page can change,
  so a write through the share's own page or a veyq page embedding it tells itself apart from one through an unrelated
  page, whose address is then read as possibly a bystander's. The origin proves nothing the other way (a client that is
  no browser chooses it), and the write itself still lands, as it would from `curl`.
- **The register grows by two entries**, both switchable; an instance that switches `core.public-shares.embeddable` off
  is opened in a new tab by every other instance, as before.
- **Proven live, and what is not.** A two-origin pass (2026-11-06: one backend, two tenants on `veyq.localhost` and
  `other.localhost`, headless Chrome with a network capture) showed the embed with the stored password, every call to the
  share's origin answered `Access-Control-Allow-Origin: *` and CORP `cross-origin` with no credential sent or allowed,
  preflights `204` with `Max-Age: 600`, no request of this origin carrying the share id; navigation, naming, a folder, an
  upload to the share's store, a move and a report `201` at the share's instance; Open on its own page with no opener or
  referrer; `.embeddable` off for the share's tenant → the fallback line, `core.share-embedding` off here → ADR 0120's page
  with no request to the other origin; a curator's `DELETE` preflight offered `GET` only, a member path's preflight `405`.
  **Not proven live:** the backend-served per-tenant CSP losing and gaining `https:` (the dev server serves no shell;
  `AppShellShareEmbeddingSliceTest` is the proof), a report sealed to a *distinct* instance's moderation key (one backend
  held one key), and any browser but Chromium.
- **Deferred: the embed in an isolated document.** A sandboxed iframe of an `/embed.html` on this origin, without
  `allow-same-origin`, under a policy of its own with `connect-src https:`, the app's document keeping `'self'`. It
  contains script injected through FOREIGN content — today a foreign share renders in the member's document, beside the
  member's keys — but not a targeted injection in the app itself: the embed then becomes a confused deputy (a crafted
  link whose host carries the data), and a navigation still leaves. Its cost is of class L: a second policy, CORS on
  `/assets`, a second entry point, a `postMessage`/`MessagePort` contract, a storage proxy, no worker or service worker
  in the opaque origin, dialogs clipped to the frame. It is the answer once hostile foreign content is prioritised as a
  threat of its own, settled first by a one-hour browser spike of the bundle loading in the sandbox; the owner kept
  the switch for now (2026-11-06).
- **Rejected:** a proxy through this instance (it would learn every read); an iframe (both sides forbid framing); a
  `connect-src` computed from each member's bookmarks (the server would learn the origins); an operator-kept partner
  list (it keeps the backstop; the owner chose against it); `Access-Control-Allow-Credentials` (no endpoint needs it, and
  it would invite cookie-bearing calls).

## Related

- On the docs site: [Storage and email](../../site/src/content/docs/run/storage-and-email.md) (an own store's CORS rule),
  [Hardening](../../site/src/content/docs/run/hardening.md) (the CSP's widening and the CORP exception) and
  [Known limitations](../../site/src/content/docs/security/known-limitations.md) (what the widening gives up).
