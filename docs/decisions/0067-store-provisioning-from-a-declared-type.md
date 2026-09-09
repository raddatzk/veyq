# ADR 0067 — veyq provisions its own object store, from a declared store type

- Status: Accepted
- Date: 2026-09-09

## Context

A fresh instance has an object store that is **running but not usable**: Garage comes up with no layout, no
bucket, no access key and no grant. Today `infra/garage-init.sh` closes that gap as a one-shot compose service,
and it needs a container of its own for a reason that has nothing to do with the work — the official Garage
image is `scratch`-based and has no shell, so `infra/garage-init.Dockerfile` exists purely to put the `garage`
CLI on an Alpine that has one. That image carries `build:` in `infra/docker-compose.prod.yml` and is not one of
the two images `.github/workflows/container.yml` publishes.

Forces:

- **A chart cannot build an image.** [ADR 0064](0064-kubernetes-helm-deployment-target.md) deploys `garage`
  "+ its init job", and on Kubernetes that job has nothing to pull. Compose hides this because it can build on
  the operator's host.
- **Self-hostable by default is a bar on the *result*, not on the command.** `docker compose up` and
  `helm install` must yield an instance that serves media, not one that starts and then fails at the first
  photo. A store bootstrap the operator has to remember is the same defect as a missing one.
- **A store bootstrap is inherently per-vendor, and no design makes it generic.** Of the four steps
  `garage-init.sh` performs, `bucket create` is S3 for anybody and a CORS policy is
  [ADR 0013 §4](0013-blob-storage-presigned-direct-to-store.md)'s `PutBucketCors`; `layout assign` /
  `layout apply` are Garage cluster mechanics with no S3 analogue at all; and `key import` / `bucket allow` are
  **IAM**, which AWS, Garage and Ceph each spell differently. There is no protocol to be neutral about.
- **The `garage` binary is not required.** Measured against the pinned v2.0.0: its Admin API (`:3903`, bearer
  token, plain JSON) covers every step — a bucket was created through `POST /v2/CreateBucket` and removed
  through `POST /v2/DeleteBucket?id=…`, both `200`. So the custom image serves only the shell, and the shell
  serves only the CLI.
- **This is the first provider discriminant in the codebase, and the neighbouring decision went the other
  way.** [ADR 0048](0048-provider-neutral-smtp-mail-transport.md) is titled *provider-neutral* and models mail
  as host/port/tls/username with no vendor named; `VeyqConfig.BlobStore` does the same for the store, listing
  "Garage, AWS S3, R2, B2, …" in a **comment** (`application.properties`) precisely so the code need not. That
  precedent is not being reversed — it is being read correctly. Mail is neutral because SMTP *is* one protocol
  every provider speaks. Provisioning has no such protocol, so neutrality there does not buy portability, it
  buys an empty abstraction over four incompatible APIs.

## Decision

**The store connection declares its type, and veyq provisions what that type allows it to.**

1. **A `type` on the store connection.** Two values to begin with: `garage`, which veyq can bring from
   *running* to *usable* end to end, and `s3`, which covers every other S3-compatible store and limits veyq to
   the steps that are genuinely S3. `s3` is the **default**, so an unknown store is never poked; the bundled
   deployments set `garage` themselves, which is exactly the case where veyq owns both sides.

   The type belongs to **the connection, not to the environment as such**. There is one connection today and
   the distinction costs nothing — but a SaaS instance where one tenant buys storage through veyq and another
   brings their own AWS bucket (`docs/inbox.md` #41) has several, each with its own type, and this is the shape
   that does not have to be undone for that.

2. **Provisioning is a separate seam from the data plane.** `BlobStore` stays the small port it is —
   presign / stat / delete — and a `StoreProvisioner` owns bring-up. Two reasons, and the second is the sharp
   one: bucket and IAM calls are not what a blob store *does*, and `scripts/validate_store_reclaim_arity.py`
   pins `StoreReclaimSweep` as the single caller of `blobStore.delete` precisely so object deletion has exactly
   one arm. Provisioning must not become a second one.

3. **Configuration is checked at boot; the network work is not.** The endpoint check
   (`BlobStoreStartupCheck`) stays as it is — a blank endpoint outside `%test` still fails startup, because it
   is a local, instant, config-only fact and [ADR 0013 §1](0013-blob-storage-presigned-direct-to-store.md)
   makes a byte path a hard requirement. Provisioning is the opposite kind of act: it reaches another
   container over the network, and a `StartupEvent` observer that can fail on an unreachable dependency is how
   [ADR 0063](0063-database-driven-tenant-ingress.md) restart-looped a production stack into serving nothing at
   all. So provisioning **runs in the background, is idempotent, retries with a bound, and logs loudly** — it
   never gates the boot, and an instance whose store is not yet provisioned still answers `/api/setup`.

4. **`infra/garage-init.sh`, `infra/garage-init.Dockerfile` and the `garage-init` compose service are
   deleted.** Their work moves into the backend, which already holds the store credentials and, for `garage`,
   additionally receives the admin endpoint and token. The unpublished image disappears rather than becoming a
   third signed release artifact, and [ADR 0064](0064-kubernetes-helm-deployment-target.md)'s chart is
   unblocked because there is nothing left for it to pull.

5. **"The store has its own address" becomes named signing behaviour, not a deployment's special case.**
   `S3BlobStore.endpointFor` swaps `public-endpoint`'s host for the resolved tenant's domain, which is right
   for the compose same-origin path route ([ADR 0063](0063-database-driven-tenant-ingress.md)) and wrong for
   any store reached at an origin of its own. Two things need the same mode and neither is a special case of
   the other: [ADR 0064](0064-kubernetes-helm-deployment-target.md)'s Kubernetes branch, where Garage gets its
   own Ingress host, and a SaaS tenant on their own `s3.amazonaws.com` bucket. It is therefore one mechanism
   with two callers, decided here rather than inside either of them.

**Not decided here: whether a store connection may belong to a tenant, and on what terms.** V1 is self-hosted
and one store serves every tenant, so this ADR configures exactly one. The SaaS question — a customer who buys
storage through veyq versus one who brings their own bucket — carries a trust decision that is not this ADR's:
SigV4 signs under the secret, so a *central* operator presigning against a customer's own bucket holds that
customer's infrastructure credentials. There are two standard answers (a credential scoped to the one bucket,
or an assumed role so no long-lived secret is held at all) with different costs, and picking one belongs with
`docs/inbox.md` #41 where the rest of the SaaS model lives. Content is E2EE either way — this is about the
customer's infrastructure, never their data.

## Consequences

- **`.github/workflows/container.yml` keeps its two images.** The alternative was a third, published, signed
  and versioned forever to serve a one-shot.
- **The backend gains Garage-specific code, deliberately and behind the type.** That is the honest cost of
  Decision 1, and it is bounded: `type: s3` reaches none of it, and a second bundled store would add a third
  value rather than change the shape. What it does **not** do is make the *data* path vendor-specific —
  `S3BlobStore` stays the vendor-neutral AWS SDK client CLAUDE.md's dependency rule requires.
- **The app holds one more operator secret**, the Garage admin token, alongside the store credentials it
  already holds. It stays in the environment; nothing here puts a credential in the database, so
  `application.properties`' standing claim that store credentials are *"never persisted, logged, or returned"*
  remains true.
- **A per-tenant store later has one trap, and it is not where anyone would look.**
  `veyq_store_reclaim` carries `storage_key` and `chunk_count` and nothing else, and `StoreReclaimStore.record`
  is `@Transactional(MANDATORY)` — the tombstone is written *in the same transaction that deletes the row*, so
  that objects outlive their metadata. `ScopePurge` is one of its callers and erases the scope with it. A live
  blob reaches its tenant in one join (`veyq_blob.scope_id` → `veyq_scope.tenant_id`); a reclaim row reaches
  nothing, **by design**. With more than one store the sweep would not know which one to delete from, and the
  bytes would leak silently. The remedy is one column filled at insert, while the tenant is still known — and
  because this is invisible from everywhere except that line, it is recorded as a comment there rather than
  only here.
- **Runtime-editable store settings stay out of reach for a specific reason.** `S3BlobStore` caches presigners
  in a `ConcurrentHashMap` keyed by resolved endpoint and never invalidates it — correct while the connection
  is fixed at boot, and the first thing that must change if it ever is not.
- **Rejected — publish `veyq-garage-init` as a third image.** It works, and it makes a permanent release
  artifact out of a container whose only contribution is owning a shell. Every release would sign and version
  it; every operator would pull it once and never think about it again.
- **Rejected — bundle the `garage` binary, or speak Garage's RPC, from the backend.** A vendor-locked client
  in the data path is what CLAUDE.md's dependency rule forbids, and the Admin API measurement makes it
  unnecessary: the same four steps are plain HTTP.
- **Rejected — rewrite the script in Python and run it from a stock image.** It removes the custom image but
  keeps a second component that needs the store credentials, and it cannot be idempotent about state the
  backend is already the authority on.
- **Rejected — a `veyq-admin store init` command.** Measured: the CLI signs in as Server Owner on every
  invocation (`admin-web/src/cli/adminSession.ts`), and no Server Owner exists before first-run setup — so the
  earliest it could run is *after* setup, leaving a window in which an instance boots healthy and fails at the
  first upload. It would also put a Garage-specific verb in a tool whose whole premise
  ([ADR 0065](0065-admin-ui-cli-separate-service.md)) is being a thin client of a vendor-neutral backend.
- **Rejected — no type, and provision generically.** The only two steps that generalise are `bucket create`
  and the CORS policy; stopping there leaves Garage unusable out of the box, which is the defect this ADR
  exists to remove.

## Related

- [ADR 0013](0013-blob-storage-presigned-direct-to-store.md) (the store port, §1's hard byte-path requirement
  and §4's CORS branch — whose policy this provisioning writes).
- [ADR 0048](0048-provider-neutral-smtp-mail-transport.md) (the provider-neutral precedent this reads rather
  than reverses).
- [ADR 0062](0062-store-object-reclaim-is-a-durable-work-list.md) (the reclaim tombstone whose shape the
  per-tenant trap above is about).
- [ADR 0063](0063-database-driven-tenant-ingress.md) (per-request signing, and the startup-observer lesson
  Decision 3 follows).
- [ADR 0064](0064-kubernetes-helm-deployment-target.md) (the chart this unblocks, and one of the two callers
  of Decision 5).
- [ADR 0065](0065-admin-ui-cli-separate-service.md) (the CLI whose premise rules it out as the bootstrap's
  home).
- [inbox #41](../inbox.md) (the SaaS model that owns the per-tenant store question and its trust decision).
