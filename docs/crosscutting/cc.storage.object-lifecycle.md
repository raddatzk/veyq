# Concern: The object store and the life of a stored object

- **ID:** cc.storage.object-lifecycle
- **Type:** **Cross-cutting concern** — the one object store every application's bytes live in (a Memory's media and
  derivatives, node attachments, Expenses' receipts), and what admin, moderation, erasure and backup do to it. How a
  blob is sealed and uploaded is one feature's enabler, [en.memory.media](../features/memory/enablers/en.memory.media.md).
- **Status:** built, except what [§ Decided, not built](#decided-not-built) lists.
- **Decisions:** [ADR 0013](../decisions/0013-blob-storage-presigned-direct-to-store.md) (S3 only, presigned
  direct-to-store, an object shared only inside one node, inert responses),
  [ADR 0067](../decisions/0067-store-provisioning-from-a-declared-type.md) (provisioning from a declared type),
  [ADR 0062](../decisions/0062-store-object-reclaim-is-a-durable-work-list.md) (reclaim and the reconcile),
  [ADR 0073](../decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md) (quota),
  [ADR 0056](../decisions/0056-instance-durable-state-and-cold-logical-backup.md) (the store in a backup),
  [ADR 0089](../decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md) (the store is a
  Server-Owner setting).
- **Operators read:** [Storage and email](../../site/src/content/docs/run/storage-and-email.md),
  [Operations](../../site/src/content/docs/run/operations.md) and [Plan your instance](../../site/src/content/docs/run/plan.md)
  on the docs site.

## One store, S3 only

`BlobStore` is a small port — `presignPut`, `presignGet`, `stat`, `delete`, `list` — with one production
implementation, `S3BlobStore`, on the vendor-neutral AWS SDK for Java v2, against any S3-compatible store or the
bundled Garage. `InMemoryBlobStore` exists only in the `%test` profile (metadata and the declare/verify control plane,
no bytes); it is never a dev or production fallback.

The store is a Server-Owner setting (`veyq_store_setting`, [uc.admin.configure-object-store](../features/admin/use-cases/uc.admin.configure-object-store.md)).
`SwitchingBlobStore` reads it on every call and rebuilds its delegate — the signing client and the presigner cache —
only when it changes; once the first blob exists, type, endpoint and bucket are fixed (ADR 0089 decision 6). Store
credentials are sealed under the config-secret key and never returned or logged; the bundled store's key pair is
derived from its hint and not stored.

**A boot needs no store.** An instance without one starts, answers `/api/setup`, refuses every upload with
`store_unavailable`, and refuses to create a tenant until a store has passed its check. No client asks in advance:
there is no store flag on `/api/setup` and no capability. `BlobStoreStartupCheck` refuses the boot only on local
configuration facts: a bundled bucket name that breaks S3's naming rules (it becomes a route path), an upload
retention not longer than the presign TTL, and a reconcile gap bound not longer than one period plus one tick.

## Provisioning from a declared type

The connection declares a `type` ([ADR 0067](../decisions/0067-store-provisioning-from-a-declared-type.md)):

- **`s3`** (the default, any store of the operator's own): veyq provisions **nothing** — no bucket, no CORS rule. The
  bucket must exist; the operator's CORS rule must allow `PUT` and `GET` from the instance's origins with the
  `Content-Type` header and expose `ETag`.
- **`garage`** (the bundled store, or a Garage the Server Owner enters with its admin endpoint and token):
  `GarageStoreProvisioner` brings it from running to usable over Garage's Admin API — layout, bucket, access key,
  grant, and the CORS rule `*` — each step checking and acting on its own, against a layout at any version.

Provisioning is its own seam (`StoreProvisioner`), not part of `BlobStore`. `StoreProvisioningRunner` runs it in the
background at boot, retries with a bound and never gates the boot; its outcome is readable at
`GET /api/instance/store-provisioning`, and it runs again as the first step of every check of a Garage store, which
also recovers a failed boot run. The check itself (`StoreCheck`: bucket, the probe object `veyq-check/probe`, a CORS
preflight) is ADR 0089 decision 2. The bundled stack's Garage admin token stays in the environment; an entered one is
sealed like the S3 secret.

The bundled bucket is **`blobs`** (`veyq.bundled-store.bucket`), a name no app page can have. Once an instance is
installed it does not change: a new default would hand every upgraded install an empty bucket.

## Transport, reachability and inert responses

Bytes travel client ↔ store over presigned URLs; the backend only authorizes, signs, verifies and attaches. The upload
mechanism — batches, windows of `veyq.blob.presign-window` chunks (64), the 600 s TTL, declare-then-verify, the
20 GiB per-file ceiling — is [en.memory.media](../features/memory/enablers/en.memory.media.md)'s.

- **Where a URL points.** Which origin a URL is signed for is a named mode, `SigningMode`
  ([ADR 0067](../decisions/0067-store-provisioning-from-a-declared-type.md) decision 5), one per deployment shape:
  - **`TENANT_DOMAIN`** — `public-endpoint` with its host swapped for the resolved tenant's domain. On Compose the
    bundled store shares each tenant's origin: `TraefikFileIngressSink` renders a `` PathPrefix(`/<bucket>/`) `` route
    (with the trailing slash, from the bucket setting) on every tenant domain, only while the store in use is the
    bundled one. Only a recipe that brings the store and its proxy can promise this, so it is the bundled store's
    default (`veyq.bundled-store.signing-mode`).
  - **`STORE_ORIGIN`** — `public-endpoint` verbatim, for a store on an origin of its own: the chart's opt-in Garage
    (`signing-mode=store-origin`), whose browser fetch crosses origins under the provisioned CORS rule `*`, and a store of
    the operator's own that names a public endpoint, under its own rule.
  - **`INTERNAL_ENDPOINT`** — the endpoint the backend itself uses, for a store already publicly reachable there.
    A blank `public-endpoint` lands here whatever mode is configured, since nothing else is left to sign against.

  In the latter two the tenant's domain never reaches the URL.
- **Nothing the store serves is renderable.** Every presigned `GET` signs `response-content-type=application/octet-stream`
  and `response-content-disposition=attachment`; every presigned `PUT` signs `content-type: application/octet-stream`
  and its chunk's exact `Content-Length`. Where the store shares the app's origin, the route also sets
  `X-Content-Type-Options: nosniff` and `Content-Security-Policy: sandbox; default-src 'none'`.

## What names a stored object

A blob's objects are `<storage_key>/<index>`, the storage key a server-minted UUID with no scope prefix. Four tables
name a storage key, and only they keep an object alive:

| Table | Names a key while |
|---|---|
| `veyq_upload_session` | an upload is open (a committed blob keeps its session's key) |
| `veyq_blob` | a blob exists |
| `veyq_store_reclaim` | its deletion is pending |
| `veyq_hold_pin` | a moderation hold pins it ([en.moderation.hold](../features/moderation/enablers/en.moderation.hold.md)) |

A blob row is referenced by **exactly one** record, from one of four arms — `veyq_media`, `veyq_media_derivative`,
`veyq_node_attachment`, `veyq_expenses_receipt` — each with a foreign key (`scripts/validate_blob_reference_arity.py`);
its `K_blob` wrap is sealed to its owner and role ([ADR 0072](../decisions/0072-a-seal-binds-its-object.md)). What may be
shared is the **object**: inside one node a cover, a banner or a gallery original equal to a file the node already
stores is a second blob row on the same storage key ([ADR 0013](../decisions/0013-blob-storage-presigned-direct-to-store.md) § 5).
So nothing may assume one row per key.

## Reclaim: a durable work list

Every path that removes a row naming a key records the key on `veyq_store_reclaim` **in the same transaction**
(`StoreReclaimStore.record`, `MANDATORY`), so the knowledge of which bytes to delete cannot die with the row. A shared
object is recorded only when its last blob row goes (`BlobUploadStore.deleteBlobRow`, under the row locks of every row
on it). A reclaim row holds `storage_key`, `chunk_count`, `created_at`, `attempts`, `next_attempt_at`, `abandoned_at` —
no scope, media or node, so an erased group leaves nothing that describes it.

- **Not before the URLs are dead.** A recorded key is due at `veyq.blob.upload.retention` (`PT1H`) after recording,
  which must exceed the presign TTL (enforced at boot): no PUT signed for it can still land.
- **One deleter.** `StoreReclaimSweep` is the only caller of `blobStore.delete`
  (`scripts/validate_store_reclaim_arity.py`), hourly, 500 rows a batch. `delete` returns the keys it could not
  delete; a refused key keeps its row, counts the attempt and backs off (`PT15M`); after five attempts the row is
  marked `abandoned_at` and logged once at `ERROR`. `attempts > 0` and `abandoned_at IS NOT NULL` are the counts of
  failed and given-up deletions. The sweep skips a key a blob row still names and a key a hold pins.
- A settled row writes a `STORAGE_KEY` line to the erasure ledger (ADR 0056 decision 8), so a restore's object check
  reads the key as erased, not lost.

## The reconcile

`StoreReconcileSweep` compares the bucket with the database, for objects no row claims — which an in-place rollback
produces (ADR 0056 § 7), since it restores a capture over a store that kept the failed release's uploads.

- **Listing.** `BlobStore.list(startAfter, maxKeys)` (ListObjectsV2 with `start-after`, a cursor that survives a
  restart); each tick (`PT1M`) lists up to 10 pages of 1000, one pass per period (`PT6H`).
- **No object exists unnamed.** The upload session commits before any URL is signed for its server-minted key, a
  committed blob inherits the session's key in the transaction that deletes the session row, and a dropped key waits
  out the TTL on the reclaim list. What is left is read order, which the next point removes.
- **Claims.** After each page (so an upload that began during the listing is seen), one statement — one snapshot, so a
  key promoted from session to blob between two reads cannot be missed by both — reads the four claim tables (`StoreReconcileStore.CLAIM_TABLES`, held to
  the migrations by `scripts/validate_store_reconcile_claims.py`); an object is explained when some row names its
  key with a `chunk_count` above its index. Each unexplained object is `stat`ed and counted only if still present and
  older than the upload retention by the store's own clock. The probe and any key not shaped `<uuid>/<index>` are
  counted apart as not veyq's.
- **Deletion only through the reclaim list, on three conditions:** the Server Owner confirmed the bucket is this
  instance's alone, for the store in use (a changed store setting voids it); the key stayed an orphan in every pass
  for the grace (`P30D`) since first seen (`veyq_store_orphan`); and the candidates are visible and the enqueueing
  pausable. A restore empties the reconcile's tables, and a listing gap longer than `veyq.store-reconcile.max-gap`
  (`P1D`) restarts every clock. Read and switched in admin-web's Server → Object store and by
  `veyq-admin store reconcile [--confirm-exclusive-bucket | --withdraw-exclusive-bucket | --pause | --resume]`.

## Quota

[ADR 0073](../decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md); the users' side is
[uc.admin.tenant-storage-quota](../features/admin/use-cases/uc.admin.tenant-storage-quota.md) and
[uc.sharing.share-link-storage-limit](../features/sharing/use-cases/uc.sharing.share-link-storage-limit.md).

- **A tenant's quota** is set by the Server Owner on the admin channel; none by default. **A per-link limit** is set
  by the Tenant Owner, one value for every `READ_WRITE` and `WRITE_ONLY` link of the tenant (`share_link_limit_bytes`,
  default 10 GiB, `0` switches link uploads off, no "unlimited").
- **Usage is a SUM at the check** (`StorageQuota`), never a counter: every blob in the tenant's scopes at its declared
  size, plus every open upload session's declared size, trash and scopes in their grace included, a shared object
  once. A link's usage is what its identities stored — media and their derivatives, receipts, attachments — and the
  sessions they opened, summed across applications. Reclaim rows and database-held material do not count.
- **The check runs at `start`**, under a pessimistic lock on the tenant row, and admits a request whole or refuses it
  whole with `quota_exceeded`; windows and `complete` do not check again. A link visitor is not told which limit was
  hit. The Tenant Owner sees their tenant's total, the Server Owner every tenant's; nobody sees a figure per member or
  per scope.

## The store in a backup

A capture holds the store as its **index**, not its objects ([ADR 0056](../decisions/0056-instance-durable-state-and-cold-logical-backup.md)
§ 3): every object's key, size and MD5 in the manifest's `store.index`, `backupFormat` 3
(`src/tools/instance_backup.py`). The objects stay in the store, whose own versioning or replication keeps them; a
capture copies them into an object pool only when one is named (`--objects-to`). A pool is **copied into, never synced**
— rclone `copy --immutable`, so a key whose bytes differ from the pool's copy fails the capture instead of replacing it
— and incrementally: a key it holds is not copied again. It **keeps exactly what its retained captures need**: each
capture that named it writes its index's keys into the pool's ledger once all of them are in the pool, the ledger keeps
the newest `--pool-keep` captures (3 by default) and none older than the backup bound B, and the prune deletes from the
pool what no kept entry names, never before the new entry is written. So an erasure reaches the pool within the
rotation, and a pool serves one instance
([ADR 0056](../decisions/0056-instance-durable-state-and-cold-logical-backup.md) § 3 says why). A restore holds the live
store, or the pool, against the index before it writes anything. `verify` tolerates objects the index holds and no blob
row names — pending reclaims and open uploads. A restore target is an instance holding no data; the app provisions its
bucket ([uc.admin.back-up-and-restore-instance](../features/admin/use-cases/uc.admin.back-up-and-restore-instance.md)).

## Development

The dev stack runs the bundled Garage (`--profile s3`, `infra/docker/docker-compose.yml`), so normal development
exercises the byte path; only the `%test` slice suite uses `InMemoryBlobStore`, which holds no bytes and cannot presign.
The backend's byte-transport e2e (`BlobBatchE2ETest`) runs only with `VEYQ_S3_E2E=1`, which no workflow sets
([coverage](../coverage/README.md) names it as a gap).

## Decided, not built

- **A tenant on its own bucket** — its own store connection, and its ceiling set by the Tenant Owner (ADR 0073
  decision 1; inbox #41). Its trap: a reclaim row carries no store, by design, so with several stores the sweep would
  not know where to delete; adding a column that maps one-to-one to a tenant would break the erasure rule above
  (recorded at `StoreReclaimStore`).
- **A quota per member** (ADR 0073 decision 7, inbox #179).
- **Resuming an upload across a page reload** (ADR 0013 § Consequences).
