# ADR 0013 — Blob storage: S3-only, presigned direct-to-store, a stored object shared only inside one node

- Status: Accepted
- Built: yes
- Date: 2026-07-08
- Model: [cc.storage.object-lifecycle](../crosscutting/cc.storage.object-lifecycle.md)
- Replaced in part: § 4 by ADR 0063 (built)

## Context

[ADR 0012](0012-content-key-hierarchy.md) fixed the content keys down to **`K_blob`**, a random key per blob that
seals its `chunk_frame`s. It did not settle where the encrypted bytes live and how they travel. Forces:

- **Media bytes do not belong in the app server or the database**, which hold metadata and the chunk manifest.
- **Operator-blind:** whatever moves or stores the bytes sees only AEAD ciphertext sealed on the client.
- **Self-hostable by default:** the whole stack, storage included, runs without a cloud account.
- **The app server must not be a bandwidth bottleneck.** Proxying every byte through the backend doubles its traffic
  and, as base64 JSON, wastes a third more.
- **The client must never hold the store's credentials**, which grant unrestricted, non-expiring access to the bucket.

## Decision

### 1. Storage is S3-only

`BlobStore` has one production implementation, `S3BlobStore`, on the **vendor-neutral AWS SDK for Java v2** — any
S3-compatible store, or the bundled Garage (S3-compatible, AGPL, built for self-hosting; it replaced MinIO when that
server was archived and its successor went commercial). A store is a **hard requirement of a working instance**: media
is not an optional feature, so an instance without a byte path is misconfigured, not gracefully degraded — it refuses
every upload with `store_unavailable` and creates no tenant until a store has passed its check. The store is a
Server-Owner setting ([ADR 0089](0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)), so the
boot itself needs none. The in-memory store exists only for the `%test` slice suite, never as a fallback. Store
credentials are held only sealed under the config-secret key ([ADR 0050](0050-operator-config-secret-encryption.md)),
never logged or returned.

### 2. Presigned direct-to-store is the only transport (no proxied fallback)

A presigned URL is a SigV4 signature the backend computes over one method, one object key and a short expiry: a
narrowly scoped capability the client uses with a plain `PUT`/`GET` and no credentials of its own. The backend handles
only the **control plane** — authorize, presign, verify, attach — and every byte travels client ↔ store. That is safe
because the store is blind and there is no installed base to keep a fallback for. Blindness makes what the store
*holds* safe; it cannot make what the store *serves* safe to open — that is § 6.

- **The control plane is batch-shaped from the start**: every endpoint takes a list and answers per item, which avoids
  `3N` round-trips for a gallery and a later singular-to-batch contract migration across clients.
- **An upload session is bound to its opener and signs one window of chunks at a time**, each window authorized again
  — open session, same opener, upload right still held — so a revoked membership or share stops an upload at its next
  window. Signing every chunk at `start` would make TTL × uplink the largest upload that can finish and turn `start`
  into a presign amplifier. **A longer TTL was the one-line alternative, rejected:** it keeps every leaked URL alive
  that long and lets a revoked uploader keep writing that long.

How an upload runs is [en.memory.media](../features/memory/enablers/en.memory.media.md).

### 3. Anti-abuse is declare-then-verify, and the frame size fixes what can be declared

The backend cannot see bytes mid-`PUT`, so it bounds them before and after:

- **The frame size is a contract constant** (256 KiB of plaintext per frame but the last, in the crypto vectors beside
  `chunk_frame`), so `chunkCount` follows from `byteSize` and `start` refuses any other pair. Range streaming
  ([ADR 0047](0047-inline-video-playback-range-streaming.md)) already depends on full frames.
- **Every presigned `PUT` signs its chunk's exact `Content-Length`**, so a URL is good for one frame of one size and
  the store refuses any other — plain S3 signing, so it holds on a store veyq never provisions.
- **`complete` stats every chunk** at its derived length — the line that still holds on a store that ignored the
  signed length — and purges a rejected item's staged objects.
- **`veyq.blob.max-bytes` is a sanity ceiling per file**, not a limit anyone plans around; a total is the storage
  quota ([ADR 0073](0073-storage-quota-belongs-to-whoever-pays-for-the-store.md)).
- **Abandoned uploads are reclaimed by the stale-session purge**, keyed on a session's last issued window: a `PUT` can
  only begin within the TTL of the window that signed it.

### 4. The store must be client-reachable (public endpoint + CORS)

A browser must reach the URL it is handed, so URLs are signed for an endpoint the client can reach — on a
multi-domain server, the resolved tenant's own domain ([ADR 0063](0063-database-driven-tenant-ingress.md)). CORS is a
browser rule configured on the store; veyq cannot add headers to a direct fetch. **Two same-origin strategies, chosen
per deployment target:** on Compose the bundled store shares each tenant's origin through a proxy path route, so no
CORS is needed; on Kubernetes an operator-chosen gateway cannot be assumed to preserve the prefix and Host that SigV4
signs, so the chart's Garage gets a host of its own and a CORS rule
([ADR 0064](0064-kubernetes-helm-deployment-target.md)). A store of the operator's own has its own origin, so no route
is rendered for it.

- **The route's prefix is the bucket, taken from the setting and never spelled**, so a changed bucket cannot 404 every
  object with nothing reporting it, and it carries the trailing slash: the proxy's path prefix is a raw string prefix,
  so without it the store would also claim every app path that begins with the bucket's name. The bundled bucket is
  **`blobs`** — the store's own vocabulary, never product language, and a word no app page can be.
- **On the bundled store, `*` is the only working CORS policy for more than one tenant domain.** Garage accepts a
  subdomain wildcard and then matches no origin, and answers several exact origins with their comma-joined list — not a
  legal `Access-Control-Allow-Origin`, so the browser blocks a fetch the server sees succeed. `*` is tolerable for a
  reason, not by resignation: the presigned URL **is** the capability, its TTL is short and the fetch carries no
  credential, so `*` widens who may *use* a URL they already hold, never who may obtain one. Enumerating the tenant
  origins — what [ADR 0063](0063-database-driven-tenant-ingress.md)'s projector would make easy — is the broken form;
  this paragraph exists so nobody "improves" it back. Stores that return the matched origin are unaffected.
- **No client-facing store flag.** A byte path is required (§ 1), so a client never soft-gates upload on one; the
  capability manifest ([ADR 0032](0032-capability-discovery-version-gating.md)) carries no store entry.

### 5. One stored object for an equal file inside one node; nothing wider

`K_blob` is random per blob, so identical content in two Memories is two ciphertexts and two objects. **Scope-wide
dedup stays declined:** any scheme that saves storage lets one object serve several references, and the blind server
must see that, if only to reclaim it — so it learns which media are the same content. The leak is in the *sharing of
storage*, not the detection, and on a self-hosted instance the operator pays for their own disk. The `dedup_token`
vector stays unused.

**Inside one node, for a cover, a banner and a gallery original, the client shares.** Where a picked file equals what
the node already stores — and the stored bytes are the very bytes the slot holds — the new slot is a **second blob row
on the same stored object** with its own `K_blob` wrap, instead of a second upload:

- **Detection is the client's**, by a SHA-256 of the plaintext sealed in the blob's own metadata; the server compares
  nothing. Only a member who may write the scope shares, only from a live, unblocked blob of the same node; a guest's
  upload is never shared.
- **Each reference still names exactly one blob row** ([ADR 0082](0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md));
  what is shared is the object, which goes only with the last row naming it.
- **The operator learns which existing blob a new row shares** — which medium a cover or banner shows. Accepted
  ([ADR 0102](0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md) § What the server sees): the same
  reader already sees both rows' sizes, and the owner preferred the saved storage.
- A copy and an import do not keep the sharing; each uploads every blob as its own.

### 6. Store responses are inert — enforced by the signer

The server cannot tell ciphertext from anything else — that is the invariant working — so a member or share contributor
can `PUT` plaintext through their own URL, and the store serves it back with whatever type the uploader sent. Where the
store shares the app's origin, a planted `text/html` object opened from a link is a document on the app origin without
the app's CSP, holding every capability the app holds.

**Nothing the store serves is renderable, and the signer — not the store, not the proxy — guarantees it**, so the rule
holds on any S3 store veyq never provisions: every presigned `GET` signs a forced `application/octet-stream` type and an
`attachment` disposition (which also neutralizes objects stored before the rule), and every presigned `PUT` signs
`content-type: application/octet-stream`, so the uploader no longer chooses the stored type. Where the store shares the
app's origin, the proxy also sets `nosniff` and a sandboxing CSP — a belt for a future presign path that forgets the
overrides. It costs a client one request header: no client renders a store response, it fetches frames and opens them.
Every place that serves uploader-chosen bytes on the app origin applies the same rule, the playback Service Worker
included ([ADR 0047](0047-inline-video-playback-range-streaming.md)).

## Consequences

- The backend never streams media bytes.
- **Backend-signed presigning needs the store secret in plaintext at signing time** (SigV4 is an HMAC under it). Fine in
  both models: the backend is always the store's rightful owner, and holding S3 credentials never decrypts media.
- **A leaked URL grants one object and one method** (and, for a `PUT`, one size) until it expires; access control is
  enforced at every window's issuance.
- **The client streams**, one frame at a time, so its memory is bounded by the frames in flight rather than the file;
  holding the whole file would put the real ceiling in the browser tab, around 1–2 GiB.
- **Development runs against a real store** — the bundled Garage, `--profile s3` — so the byte path is exercised in
  normal development; only the `%test` slice suite uses the in-memory store, and an opt-in e2e (`VEYQ_S3_E2E=1`)
  covers the byte transport.
- **The cost of declining scope-wide dedup:** the same file in two nodes, or twice in one gallery, is stored twice.
- **Deferred: resuming an upload across a page reload.** Within a page a session already outlives any URL; surviving a
  reload would add persisting the session and its wrap, and finding the gaps by `stat` — additive, waiting for a need.

Security area of the docs site: [Cryptography](../../site/src/content/docs/security/cryptography.md).
