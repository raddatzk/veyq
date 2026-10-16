# ADR 0013 — Blob storage: S3-only, presigned direct-to-store, no content dedup

- Status: Accepted
- Date: 2026-07-08

## Context

[ADR 0012](0012-content-key-hierarchy.md) fixed the content-key hierarchy down to **`K_blob`** (a per-blob
random key, wrapped under its Memory's `K_node`, encrypting the blob's `chunk_frame`s). What it did **not**
settle is *where the encrypted bytes live and how they travel* — the media transport. That is this ADR.

Forces:

- **Media bytes do not belong in the app server or the relational DB.** A Memory can hold large originals; the
  DB holds only metadata + the chunk manifest. We need an external object store.
- **Operator-blind (hard invariant).** Whatever moves or stores the bytes must only ever see **AEAD ciphertext**
  sealed client-side — never a key, never plaintext.
- **Self-hostable by default.** A self-hoster must be able to run the whole stack, storage included, without a
  cloud account.
- **The app server must not become a bandwidth bottleneck.** Proxying every media byte through the backend
  (client → base64-JSON → backend → store, and back) doubles the traffic through the app process and wastes
  ~33% on base64 — unacceptable for a gallery of large media.
- **The client must never hold the store's credentials.** S3 access/secret keys would grant unrestricted,
  non-expiring access to the whole bucket.

This ADR decides it **trimmed to what the first media slice needs** and aligned with the
[no-dedup decision](#5-no-content-dedup) below.

## Decision

### 1. Storage is S3-only

`BlobStore` has exactly one production implementation, `S3BlobStore`, which speaks to any S3-compatible
endpoint via the **vendor-neutral AWS SDK for Java v2** (not any single vendor's client) — AWS S3, Cloudflare
R2, Backblaze B2, Wasabi, Garage, … — or the **bundled Garage** an operator runs alongside the stack
(`docker compose --profile s3`; **Garage** is S3-compatible, AGPL, and built for self-hosting — it replaced
MinIO when the latter's OSS server was archived in 2026 and its successor AIStor went commercial, cr-0029).
A configured S3 store is a **hard requirement of any real instance**: outside `%test` an instance with no
store set refuses every upload with `store_unavailable` and cannot create a tenant until a store has passed its check
(media is not an optional feature — an instance without a byte path is misconfigured, not gracefully degraded).
*(Edited when [ADR 0089](0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md) was built: the
store is a Server-Owner setting now, so the boot no longer fails on a blank endpoint, and the `veyq.blob-store.*`
keys named below are the setting's fields.)* The `InMemoryBlobStore` survives **only in the
`%test` profile** for the hermetic slice suite (metadata + wraps + the declare/verify control plane, no byte
transport) — it is **never** a dev/prod fallback. Store credentials are entered by the Server Owner and persisted
only **sealed** under the config-secret key ([ADR 0089](0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)
decision 4, [ADR 0050](0050-operator-config-secret-encryption.md)) — the bundled store's pair is derived from its
hint and not stored at all; they are never logged, never in the clear in the database, and never returned by any
endpoint.

### 2. Presigned direct-to-store is the only transport (no proxied fallback)

A **presigned URL** is an object URL plus a SigV4 signature the backend (the secret-key holder) computes over
a *specific* request — one method, one object key, a short expiry. It is a narrowly-scoped capability: a `PUT`
URL cannot `GET`, grants nothing about any other object, and expires in minutes. The client uses it with a
plain `PUT`/`GET` and **no credentials of its own**; the store just verifies the signature.

- **Upload:** the client asks the backend to **start a batch of blobs** (each declaring `byteSize`, the
  `chunkCount` that follows from it (§3), the `K_blob` wrap, and optionally the blob's metadata sealed under that
  `K_blob` — the `blob-meta` seal, which the blob row keeps write-once and every read of the blob serves back
  unchanged); the backend opens a session per blob, **bound
  to whoever opened it** — an account, or a share contributor's identity — and returns presigned `PUT` URLs for
  the blob's **first window** of chunks. The client asks for **each next window** as it goes (the same
  operation re-signs a window whose URLs expired), and every such request is **authorized again**: the session
  must be open, the caller must be its opener, and the opener must still hold the upload right — membership, or
  an active share that allows uploads. A revocation therefore stops an in-flight upload at its next window, not
  only the next upload. The client `PUT`s the **raw sealed frame bytes** (no base64, no JSON) straight to the
  store, then asks the backend to **complete + attach a batch** — which **stats each object** in the store to
  confirm presence + exact size, writes the chunk manifest, enforces the byte budget, promotes the staged
  objects to a blob, and creates the media record (deleting + rejecting that item if verification fails).
  Completing is authorized like a window: only the session's opener can complete it.
- **Download:** the client asks for **presigned `GET` URLs for a batch of blobs'** chunks, fetches the raw
  frames directly from the store, and `openFrame`s them (the AAD position check still applies).

The **control plane is batch-shaped from the start** — every endpoint takes a list and returns a **per-item
result** (partial success: item 3 may fail while 1+2 succeed). This avoids `3N` round-trips for an
`N`-photo × (original/preview/thumbnail) gallery and, decisively, avoids a later **singular→batch contract
migration across three clients**. The first media slice exercises lists of length 1; the gallery/curation
slice simply sends longer lists.

**Why windows, not one signature per chunk at `start`.** A presigned URL carries its own expiry, so signing
every chunk once at `start` makes **TTL × uplink** — not any configured size — the largest upload that can
finish: ~1.5 GB at 20 Mbit/s under the 600 s TTL. It also makes `start` a presign amplifier, since one request
could ask for 256 items × every chunk of each. A window of `veyq.blob.presign-window` chunks (default 64, i.e.
16 MiB) bounds what one request signs, and the TTL then has to cover one window rather than one file — at the
default, an uplink down to ~0.22 Mbit/s. The TTL itself stays short and unchanged. A client whose window expires
anyway (a stall, a very slow link) re-signs from the first chunk it has not sent. **A longer TTL was the one-line
alternative, and it is rejected:** it keeps every leaked URL alive that long, and it lets an uploader whose right
was revoked keep writing for that long, where a window stops them at the next request.

**No proxied byte path.** The backend handles only the **control plane** (authorize, presign, verify, attach);
every byte travels client↔store. This is safe because the store is **blind** (only AEAD ciphertext behind
per-object short-TTL URLs) and this is **greenfield** (no installed base to keep a fallback for). Blindness
makes what the store *holds* safe to hold; it cannot make what the store *serves* safe to open, because nothing
makes a client upload ciphertext — that half is §6.

### 3. Anti-abuse is declare-then-verify, and the frame size fixes what can be declared

The backend cannot see bytes mid-`PUT`, so it bounds them before and after:

- **The frame size is a contract constant.** Every frame but the last carries exactly **256 KiB of
  plaintext**, i.e. **262 172 bytes** sealed (a 12-byte nonce and a 16-byte tag around it); the last carries
  the remainder, and an empty blob is a single 28-byte frame. The constant lives in the crypto vectors beside
  `chunk_frame` ([`v1.json`](../../contracts/crypto/vectors/v1.json)), and every client and the backend take it
  from there — range streaming ([ADR 0047](0047-inline-video-playback-range-streaming.md)) already depends on
  every frame but the last being full. So `chunkCount` follows from `byteSize` (`⌈byteSize / 262 172⌉`), and
  `start` rejects any other value, and any `byteSize` whose last frame could not exist.
- **Every presigned `PUT` signs its chunk's exact `Content-Length`**, so a URL is good for one frame of one
  size and the store refuses anything else. Measured against the pinned Garage v2.0.0 on 2026-09-23 by the
  security review that raised this (a stdlib SigV4 presigner against the dev store):

  | request | result |
  |---|---|
  | presigned `PUT` signing `content-length: N`, sent with N bytes | 200 |
  | the same URL, sent with a different length | 403 |
  | the same URL, re-sent with N bytes after it succeeded | 200 — the same object again, at the same size |

  The last row is harmless: it rewrites the uploader's own staged object with a frame of the size it already
  had. All of it is plain S3 signing, so it holds on a store veyq never provisions as well.
- **`complete` stats every chunk** and rejects the item unless each one is present at exactly its derived
  length. The signed length is the first line; this is the one that still holds on a store that ignored it.
  A rejected item's staged objects are purged.
- **`veyq.blob.max-bytes` is a sanity ceiling, not a limit anyone plans around** (default 20 GiB). With the TTL
  no longer bounding an upload (§2), it is what keeps one declaration from being absurd. It bounds a file,
  never a total. A total is the **storage quota** ([ADR 0073](0073-storage-quota-belongs-to-whoever-pays-for-the-store.md)):
  a tenant's quota and a per-link limit, checked at `start`, where an open session's declared size is reserved
  against them — which is also what bounds the open sessions of a link, or of a tenant with a quota. A member of a
  tenant without one is bounded per file only.
- **Abandoned uploads are reclaimed by the stale-session GC**, which keys on a session's **last issued window**,
  not on its start. A `PUT` can only begin within the TTL of the window that signed it (S3 honours a signature
  valid at request start, so one frame may still be streaming), which is why a session with no new window for
  `veyq.blob.upload.retention` can receive no further byte — the bound the retention rests on, now per window.

### 4. The store must be client-reachable (public endpoint + CORS)

For a browser/mobile client to reach a presigned URL the **object store endpoint must be reachable by the
client**, which may differ from the backend's internal endpoint. Presigned URLs are signed for
`veyq.blob-store.public-endpoint` (empty ⇒ sign against the internal endpoint, i.e. external S3 or any
publicly-reachable store); `S3BlobStore` keeps a second client purely for signing (local HMAC, never connects).
**On a multi-domain server** ([ADR 0063](0063-database-driven-tenant-ingress.md)), the value actually signed
against is the **resolved tenant's own domain**, not a single instance-wide config value —
`veyq.blob-store.public-endpoint` remains only the bootstrap-domain default, used until a tenant row supplies
its own.

**CORS is a browser rule, and it is configured on the store** — veyq cannot inject headers into a direct
fetch: none needed for a bundled store behind a Traefik
**path route** (same origin); otherwise a bucket/account CORS policy allowing `PUT`+`GET` from the web origin
and the `Content-Type` request header every `PUT` carries (§6), exposing `ETag`. For the bundled store behind
Traefik, route the bucket path **without** stripping the prefix and with the Host header preserved (SigV4 signs
host+path+query).

**That prefix is the bucket, so every route takes it from `veyq.blob-store.bucket` and none spells it.**
Path-style signing makes an object's path `/<bucket>/<key>`, so a route that names the bucket itself serves the
default and nothing else — changing the setting, as a restore onto another store may
([uc.admin.back-up-and-restore-instance](../features/admin/use-cases/uc.admin.back-up-and-restore-instance.md)
A6), would 404 every object with nothing reporting it. `TraefikFileIngressSink` renders every blobs router from
the setting, and because the value lands in the routing file of every tenant, `BlobStoreStartupCheck` refuses a
name that breaks S3's bucket-naming rules rather than trusting it. The rule is `` PathPrefix(`/<bucket>/`) `` **with**
the trailing slash: Traefik's `PathPrefix` is a raw string prefix (`strings.HasPrefix` in v3.6's
`pkg/muxer/http/matcher.go`), so without it the store also claims every app path that merely begins with the
bucket's name. A browser only fetches object URLs, which always carry the slash.

**The bundled bucket is `blobs`.** It shares the app's origin, whose pages are top-level paths
(`web/src/router.ts`), so the name is a page the app can never have. `blobs` is the store's own vocabulary
(`BlobStore`, `veyq.blob-store.*`, the `veyq-blobs` router), never product language; a page-like word reserves a
page — `storage` is the one file storage (inbox #52) would want. Nothing a user controls reaches that segment, since
a Memory's location lives in the URL fragment (`web/src/features/memory/location.ts`), so there is no
reserved-name list and none is needed. Once an instance is installed the name is fixed:
[ADR 0067](0067-store-provisioning-from-a-declared-type.md) § Consequences.

**Two same-origin strategies, chosen per deployment target.** The bundled-store-behind-Traefik path route
above is the **docker-compose** answer, and depends on that specific proxy's prefix/Host-preservation
behavior. The **Kubernetes** target ([ADR 0064](0064-kubernetes-helm-deployment-target.md)) cannot assume an
operator-chosen ingress controller preserves it, so it defaults to the CORS-policy branch instead — Garage
gets its own Ingress host there, not a path route.

**What "a bucket CORS policy" can actually express on the bundled store — measured against the pinned Garage
v2.0.0, not inferred from the S3 API it implements.** `PutBucketCors` and `GetBucketCors` work, and a real
`OPTIONS` preflight is answered from the stored rule. Three results constrain every design above this line:

| policy written | `PutBucketCors` | what a preflight does |
|---|---|---|
| one exact origin | 200 | correct — `access-control-allow-origin: <that origin>` |
| `https://*.example.com` | **200** | **403 for every origin** — accepted and then matched by nothing |
| `*` | 200 | correct — `access-control-allow-origin: *` |
| two or more exact origins | 200 | 200, and `access-control-allow-origin: <origin-a>, <origin-b>` |

The last row is the one that decides things: a comma-joined value is not a legal
`Access-Control-Allow-Origin` (it is one origin or `*`), so the preflight succeeds and the **browser blocks
the fetch anyway** — a failure that reads as a working configuration from the server side. Garage echoes the
configured list rather than the matched origin. The subdomain-wildcard row fails the same way one step
earlier. **Therefore, on the bundled store, an instance with more than one tenant domain has exactly one
working policy: `*`.** Enumerating the real origin set — which is what a naïve reading of "allowing `PUT`+`GET`
from the web origin" invites, and what [ADR 0063](0063-database-driven-tenant-ingress.md)'s projector would
make easy — produces the broken row, silently. `*` is the accepted answer there, and it is tolerable for a
specific reason rather than by resignation: the presigned URL **is** the capability, its TTL is short
(`veyq.blob-store.presign-ttl-seconds`, 600s), and the fetch carries no cookie or credential — so `*` widens
who may *use* a URL they already hold, never who may obtain one. This table is here so the next reader does
not "improve" it back into the multi-origin form. It is a property of the **bundled** store, not of the
branch: AWS S3 and other implementations return the matched origin correctly, which is why a tenant on their
own bucket is unaffected. The policy is written by
[ADR 0067](0067-store-provisioning-from-a-declared-type.md)'s store provisioning.

Because a byte path is a **hard requirement** (§1), there is
**no client-facing store-availability flag**: the earlier advisory `directTransport` on `/api/setup` is
**removed** — an instance with no store set refuses every upload with a reason, and no tenant can be created
before a store has passed its check (ADR 0089 decisions 5 and 6), so a client never needs to soft-gate media
upload on it. (The capability-discovery manifest, [ADR 0032](0032-capability-discovery-version-gating.md),
therefore does **not** carry an infra-preflight `directTransport`/`media.*` entry.)

### 5. No content dedup

`K_blob` is **random per blob** (ADR 0012, verbatim): identical content in two Memories yields **two
independent ciphertexts and two stored objects** — no cross-blob sharing. We **decline content dedup** for the
media path. Rationale (decided as a sparring trade-off):

- Any scheme that actually *saves* storage must let a single stored object be **referenced by multiple
  Memories**, and the blind server **must** see that multi-reference (if only for garbage collection). So
  dedup — whether client- or server-detected — **necessarily leaks the scope-internal equality structure** of
  blobs to the operator ("these two media are the same content"). The leak is in the *sharing of storage*, not
  in the *detection*; client-side detection only moves who notices first.
- That equality-metadata leak runs against the **operator-blind** core promise, and on a **self-hostable** app
  the operator pays for their own storage — so the savings are weakly motivated.
- Therefore `K_blob` stays random, the `dedup_token` vector in `v1.json` stays **unused**, and ADR 0012's
  "random `K_blob`" stays literally correct. If storage pressure ever
  justifies it, **scope-wide convergent dedup** (`K_blob = HKDF(K_SC, content)`, accepting the equality leak)
  is a deliberate later slice — not a default.

### 6. Store responses are inert — enforced by the signer

**The server cannot tell ciphertext from anything else** — that is the operator-blind invariant working as
intended — so any member or share contributor can `PUT` arbitrary plaintext through their own presigned URL,
and `complete` verifies only the size (§3). The store then serves it back with whatever `Content-Type` the
uploader sent. Where the store shares the app's origin (the compose path route, §4), a planted `text/html`
object opened from a link is a **document on the app origin without a CSP** (the app's policy is a header on
the app shell's own responses, [ADR 0069](0069-backend-serves-the-web-app-shell.md), and a store response is
not one of them), holding every capability the app holds — its storage, its
refresh cookie, a remember-me key it can *use* though not export, and the genuine domain to draw a fake unlock
screen on.

Measured against the pinned Garage v2.0.0 on 2026-09-23 (a stdlib SigV4 presigner against the dev store):

| request | result |
|---|---|
| presigned `PUT` signing only `host`, sent with `Content-Type: text/html` | 200 — stored as sent |
| presigned `GET` of that object, direct and through the Traefik same-origin route | `Content-Type: text/html`, no `nosniff`, no CSP |
| presigned `GET` signing `response-content-type=application/octet-stream` + `response-content-disposition=attachment` | served `application/octet-stream`, `attachment` |
| that `GET` URL with the override removed | 403 — the override is part of the signature |
| presigned `PUT` signing `content-type: application/octet-stream`, sent as `text/html` | 403 |
| the same, sent as `application/octet-stream` | 200 — stored as `application/octet-stream` |

**Decision: nothing the store serves is renderable, and the signer — not the store, not the proxy — is what
guarantees it,** so the rule holds on the bundled store and on any S3 store veyq never provisions
([ADR 0067](0067-store-provisioning-from-a-declared-type.md)'s `type: s3`):

- every presigned `GET` signs `response-content-type=application/octet-stream` and
  `response-content-disposition=attachment` — which also neutralises objects stored before this rule;
- every presigned `PUT` signs `content-type: application/octet-stream`, so a client must send exactly that
  header and the store refuses anything else — the uploader no longer chooses the stored type at all;
- where the store shares the app origin — the compose route for the bootstrap domain **and** every projected
  tenant domain — the proxy also sets `X-Content-Type-Options: nosniff` and
  `Content-Security-Policy: sandbox; default-src 'none'` on store responses: a belt that holds even for a
  future presign path that forgets the overrides. The Kubernetes target serves the store on its own origin
  (§4) and relies on the signer alone.

It costs a legitimate client one request header and nothing else: no client renders a store response — every
client `fetch`es the frames and opens them (§2), so the type the store reports is never read. The header is not
CORS-safelisted, so on the cross-origin branch (§4) the store's policy must allow it; the policy veyq provisions
allows every header, an operator's own must name it. The rule is not specific to the store: every place that
serves uploader-chosen bytes on the app origin applies it, and the playback Service Worker does so in
[ADR 0047](0047-inline-video-playback-range-streaming.md).

## Consequences

- The backend never streams media bytes: large galleries/downloads don't flow through the app process, and
  base64 overhead disappears.
- **Backend-signed presigning requires the backend to hold the store secret in plaintext at signing time**
  (SigV4 is an HMAC under the secret). Fine in both deployment models: the backend is always the store's
  rightful owner (self-hosted = the operator's own S3; central = the provider's bucket). End users do **not**
  bring their own S3, so there is no per-tenant credential the backend must be blind to. Content is E2EE
  regardless — holding S3 creds never lets the backend decrypt media (no content key).
- **Security:** a leaked presigned URL grants only that one object + method (+ for a `PUT`, that one size)
  until it expires; keep the TTL short. Credentials never reach the client; access control is enforced at URL
  issuance — every window, not only the first (§2).
- **The client streams.** It reads, seals and sends a blob one frame at a time, so its memory is bounded by
  the frames in flight, not by the file. Holding the whole file (and its sealed copy) would put the real
  ceiling in the browser tab, around 1–2 GiB, and make §3's ceiling a number no upload reaches.
- **Dev vs test:** `%dev` now runs against a **real store** (the bundled Garage, `docker compose --profile s3`)
  like prod — a missing endpoint fails the dev backend loudly rather than silently degrading, so the full byte
  path is exercised in normal development. Only the `%test` slice suite keeps the in-memory stub (metadata + wraps
  + declare/verify control plane, no byte transfer); the byte transport itself is additionally covered by the
  opt-in e2e (`VEYQ_S3_E2E=1`).
- **Cost of no-dedup:** duplicate media is stored once per blob. Accepted for operator-blindness.
- **Deferred:** **resuming across a page reload.** Within one page the hard part already exists — a session
  outlives any one URL, and a chunk whose `PUT` failed or whose window expired is re-signed and re-sent without
  restarting the file (§2). What surviving a reload would add is the client persisting the session and its
  `K_blob` wrap, and finding its gaps by `stat`. It is additive (a new operation, no contract break) and waits
  for a real need.

## Related

- [ADR 0012](0012-content-key-hierarchy.md) (`K_blob` per blob, random, under `K_node`),
  [ADR 0017](0017-crypto-primitives-and-post-quantum-strategy.md) (`chunk_frame` is vector-pinned).
- [ADR 0063](0063-database-driven-tenant-ingress.md) (per-tenant-domain presigning on a multi-domain server),
  [ADR 0064](0064-kubernetes-helm-deployment-target.md) (the Kubernetes CORS branch of §4).
- [ADR 0047](0047-inline-video-playback-range-streaming.md) (the playback Service Worker, which applies §6 to
  the decrypted bytes it serves).
- Enabler: [en.memory.media](../features/memory/enablers/en.memory.media.md) (the blob/media data layer this
  transport carries). Feature: [Memory](../features/memory/_index.md).
- Security area of the docs site: [Cryptography](../../site/src/content/docs/security/cryptography.md)
