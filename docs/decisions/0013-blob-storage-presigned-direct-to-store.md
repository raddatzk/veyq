# ADR 0013 — Blob storage: S3-only, presigned direct-to-store, no content dedup

- Status: Accepted
- Date: 2026-07-08

## Context

[ADR 0012](0012-content-key-hierarchy.md) fixed the content-key hierarchy down to **`K_blob`** (a per-blob
random key, wrapped under its Memory's `K_mem`, encrypting the blob's `chunk_frame`s). What it did **not**
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
A configured S3 store is a **hard requirement of any real instance**: in `%dev` and `%prod` a missing/blank
`veyq.blob-store.endpoint` is a **fail-loud startup error** (media is not an optional feature — an instance
without a byte path is misconfigured, not gracefully degraded). The `InMemoryBlobStore` survives **only in the
`%test` profile** for the hermetic slice suite (metadata + wraps + the declare/verify control plane, no byte
transport) — it is **never** a dev/prod fallback. Store credentials come from operator env, are never persisted
to the DB, never logged, masked in any config dump, and never returned by any endpoint.

### 2. Presigned direct-to-store is the only transport (no proxied fallback)

A **presigned URL** is an object URL plus a SigV4 signature the backend (the secret-key holder) computes over
a *specific* request — one method, one object key, a short expiry. It is a narrowly-scoped capability: a `PUT`
URL cannot `GET`, grants nothing about any other object, and expires in minutes. The client uses it with a
plain `PUT`/`GET` and **no credentials of its own**; the store just verifies the signature.

- **Upload:** the client asks the backend to **start a batch of blobs** (each declaring `chunkCount` +
  `byteSize` + the `K_blob` wrap); the backend opens a session per blob and returns a **presigned `PUT` URL per
  chunk key**. The client `PUT`s the **raw sealed frame bytes** (no base64, no JSON) straight to the store,
  then asks the backend to **complete + attach a batch** — which **stats each object** in the store to confirm
  presence + size, writes the chunk manifest, enforces the byte budget, promotes the staged objects to a blob,
  and creates the media record (deleting + rejecting that item if verification fails).
- **Download:** the client asks for **presigned `GET` URLs for a batch of blobs'** chunks, fetches the raw
  frames directly from the store, and `openFrame`s them (the AAD position check still applies).

The **control plane is batch-shaped from the start** — every endpoint takes a list and returns a **per-item
result** (partial success: item 3 may fail while 1+2 succeed). This avoids `3N` round-trips for an
`N`-photo × (original/preview/thumbnail) gallery and, decisively, avoids a later **singular→batch contract
migration across three clients**. The first media slice exercises lists of length 1; the gallery/curation
slice simply sends longer lists.

**No proxied byte path.** The backend handles only the **control plane** (authorize, presign, verify, attach);
every byte travels client↔store. This is safe because the store is **blind** (only AEAD ciphertext behind
per-object short-TTL URLs) and this is **greenfield** (no installed base to keep a fallback for).

### 3. Anti-abuse is declare-then-verify

The backend cannot see bytes mid-`PUT`, so per-frame size is bounded at **declare** time (`start` caps
`byteSize`/`chunkCount`; the open-session cap still applies) and the **actual** object sizes are verified
against the budget at `complete`, **purging** staged objects that exceed it. Abandoned uploads are reclaimed by
the stale-OPEN-session GC.

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
**path route** (same origin); otherwise a bucket/account CORS policy allowing `PUT`+`GET` from the web origin,
exposing `ETag`. For the bundled store behind Traefik, route the bucket path **without** stripping the prefix and with the
Host header preserved (SigV4 signs host+path+query).

**Two same-origin strategies, chosen per deployment target.** The bundled-store-behind-Traefik path route
above is the **docker-compose** answer, and depends on that specific proxy's prefix/Host-preservation
behavior. The **Kubernetes** target ([ADR 0064](0064-kubernetes-helm-deployment-target.md)) cannot assume an
operator-chosen ingress controller preserves it, so it defaults to the CORS-policy branch instead — Garage
gets its own Ingress host there, not a path route.

Because a byte path is a **hard requirement** (§1), there is
**no client-facing store-availability flag**: the earlier advisory `directTransport` on `/api/setup` is
**removed** — an instance either has a working store (it started) or fails loud at startup, so a client never
needs to soft-gate media upload on it. (The capability-discovery manifest, [ADR 0032](0032-capability-discovery-version-gating.md),
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

## Consequences

- The backend never streams media bytes: large galleries/downloads don't flow through the app process, and
  base64 overhead disappears.
- **Backend-signed presigning requires the backend to hold the store secret in plaintext at signing time**
  (SigV4 is an HMAC under the secret). Fine in both deployment models: the backend is always the store's
  rightful owner (self-hosted = the operator's own S3; central = the provider's bucket). End users do **not**
  bring their own S3, so there is no per-tenant credential the backend must be blind to. Content is E2EE
  regardless — holding S3 creds never lets the backend decrypt media (no content key).
- **Security:** a leaked presigned URL grants only that one object + method until it expires; keep the TTL
  short. Credentials never reach the client; access control is enforced at URL issuance (the caller must hold
  a grant for the scope).
- **Dev vs test:** `%dev` now runs against a **real store** (the bundled Garage, `docker compose --profile s3`)
  like prod — a missing endpoint fails the dev backend loudly rather than silently degrading, so the full byte
  path is exercised in normal development. Only the `%test` slice suite keeps the in-memory stub (metadata + wraps
  + declare/verify control plane, no byte transfer); the byte transport itself is additionally covered by the
  opt-in e2e (`VEYQ_S3_E2E=1`).
- **Cost of no-dedup:** duplicate media is stored once per blob. Accepted for operator-blindness.
- **Deferred (their own later slices, not this ADR):**
  - **Resumable uploads** — the single most expensive add (persistent upload sessions + `stat`-based gap
    detection + stale-session GC + per-client retry). It is gated on **large media / video** (small photos can
    just re-upload on failure) and is **additive without a contract break** (a new resume endpoint + a session
    table), so it waits.
  - **Thumbnail/preview** derivatives, and **public-share** transport mirrors.

  The first media slice ships the **batch-shaped** control plane (start → presign-PUT → complete+attach /
  presign-GET, all list-valued) with clients sending length-1 lists; resume is *not* in it.

## Related

- [ADR 0012](0012-content-key-hierarchy.md) (`K_blob` per blob, random, under `K_mem`),
  [ADR 0017](0017-crypto-primitives-and-post-quantum-strategy.md) (`chunk_frame` is vector-pinned).
- [ADR 0063](0063-database-driven-tenant-ingress.md) (per-tenant-domain presigning on a multi-domain server),
  [ADR 0064](0064-kubernetes-helm-deployment-target.md) (the Kubernetes CORS branch of §4).
- Enabler: [en.memory.media](../features/memory/enablers/en.memory.media.md) (the blob/media data layer this
  transport carries). Feature: [Memory](../features/memory/_index.md).
