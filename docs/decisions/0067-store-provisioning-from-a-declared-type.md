# ADR 0067 — veyq provisions its own object store, from a declared store type

- Status: Accepted
- Date: 2026-09-09

## Context

A fresh instance has an object store that is **running but not usable**: Garage comes up with no layout, no
bucket, no access key and no grant. Today `infra/garage-init.sh` closes that gap as a one-shot compose service,
and it needs a container of its own for a reason that has nothing to do with the work — the official Garage
image is `scratch`-based and has no shell, so `infra/garage-init.Dockerfile` exists purely to put the `garage`
CLI on an Alpine that has one. That image carries `build:` in `infra/docker/docker-compose.prod.yml` and is not one of
the images the publish workflow (then `container.yml`, `ci.yml` since ADR 0091) publishes.

Forces:

- **A chart cannot build an image.** [ADR 0064](0064-kubernetes-helm-deployment-target.md) deploys `garage`
  "+ its init job", and on Kubernetes that job has nothing to pull. Compose hides this because it can build on
  the operator's host.
- **Self-hostable by default is a bar on the *result*, not on the command.** `docker compose up` and
  `helm install` must yield an instance that serves media, not one that starts and then fails at the first
  photo. A store bootstrap the operator has to remember is the same defect as a missing one.
- **A store bootstrap is inherently per-vendor, and no design makes it generic.** `garage-init.sh` performs
  five calls: `layout assign` / `layout apply` are Garage cluster mechanics with no S3 analogue at all;
  `bucket create` is S3 for anybody; and `key import` / `bucket allow` are **IAM**, which AWS, Garage and Ceph
  each spell differently. There is no protocol to be neutral about. A sixth step joins them here, and it is
  **new work rather than moved work**: [ADR 0013 §4](0013-blob-storage-presigned-direct-to-store.md)'s
  `PutBucketCors`, which nothing in this repository writes today — measured, it appears in no code and in no
  `infra/` file. It is the one step that *would* generalise to any S3 store — and Decision 1 nonetheless does
  not run it for `type: s3`, for a reason §4 itself supplies. Being unprecedented here, it is also the only
  step with no working example to copy, and §4's measured table is what bounds the policy it may write.
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
   *running* to *usable* end to end, and `s3`, which covers every other S3-compatible store. `s3` is the
   **default**, and under it veyq provisions **nothing at all**; the bundled deployments pass the
   app a hint ([ADR 0089](0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md) decision 3)
   and the Server Owner chooses the bundled store, which is `garage` — exactly the case where veyq owns both sides.

   **Nothing at all, and not "only the generic steps" — the distinction is the whole safety of the default.**
   Two of the six steps do generalise (`bucket create`, and `PutBucketCors`), so "run just those under `s3`" is
   the reading this decision is most likely to be misread as, and it is wrong on both counts.
   [ADR 0013 §4](0013-blob-storage-presigned-direct-to-store.md) measured the CORS policy veyq would write and
   found `*` to be the only one that works — **on the bundled store**, and §4 says so in as many words: *"It is
   a property of the bundled store, not of the branch: AWS S3 and other implementations return the matched
   origin correctly, which is why a tenant on their own bucket is unaffected."* Unaffected, and therefore not
   in need of the policy: it is justified for a store veyq created and for no other. Writing it to
   somebody's own bucket would silently widen who may use a URL they hold, on infrastructure this project has
   measured nothing about, by default, on first boot. And `bucket create` is no better: an operator who pointed
   veyq at a bucket has one, so the step is at best a no-op and at worst a bucket created in their account
   under a name they did not choose. **An unknown store is never poked** is therefore the operative half, and
   what `s3` limits veyq to is the empty set.

   The clause that remains useful is the *ceiling*: should a genuinely safe generic step ever appear, `s3` is
   where it goes, and that is the reason a type exists rather than a boolean. It is a place, not a promise.

   The type belongs to **the connection, not to the environment as such**. There is one connection today and
   the distinction costs nothing — but a SaaS instance where one tenant buys storage through veyq and another
   brings their own AWS bucket (`docs/inbox.md` #41) has several, each with its own type, and this is the shape
   that does not have to be undone for that.

2. **Provisioning is a separate seam from the data plane.** `BlobStore` stays the small port it is —
   presign / stat / delete — and a `StoreProvisioner` owns bring-up. Two reasons, and the second is the sharp
   one: bucket and IAM calls are not what a blob store *does*, and `scripts/validate_store_reclaim_arity.py`
   pins `StoreReclaimSweep` as the single caller of `blobStore.delete` precisely so object deletion has exactly
   one arm. Provisioning must not become a second one.

3. **The store configuration never gates the boot, and neither does the network work.** *(Edited when
   [ADR 0089](0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md) was built; this
   decision first said a blank endpoint outside `%test` fails startup.)* The store is a Server-Owner setting in
   the database, so at boot there may be none: the instance starts, answers `/api/setup` and refuses uploads with
   `store_unavailable` until a store is set and has passed its check ([ADR 0013 §1](0013-blob-storage-presigned-direct-to-store.md)'s
   byte path is required of a *working* instance, and a tenant cannot be created without one). What the boot
   still refuses are the local, instant facts about the environment's own part — the bundled hint's bucket
   name, and an upload retention that outlasts the presign TTL (`BlobStoreStartupCheck`). Provisioning is the
   opposite kind of act: it reaches another container over the network, and a `StartupEvent` observer that can
   fail on an unreachable dependency is how
   [ADR 0063](0063-database-driven-tenant-ingress.md) restart-looped a production stack into serving nothing at
   all. So provisioning **runs in the background, retries with a bound, and never gates the boot** — an
   instance whose store is not yet provisioned still answers `/api/setup` — and its outcome is **readable on
   the Server-Owner instance surface**, not only in a log. It runs from the stored setting of type *Garage*, or
   from the bundled hint, once at boot and again as the first step of every check of a *Garage* store — a passing
   check is also what recovers a `FAILED` boot run without a restart; what the Server Owner *checks* afterwards
   (bucket, probe object, CORS preflight) is [ADR 0089](0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)
   decision 2, and writes nothing but its probe object into a store of type *S3-compatible*. A log line reaches whoever is already looking at
   logs; the failure it describes reaches the operator as a photo that will not upload, which is the
   experience this decision exists to remove. The nearest precedent for such a field is `configSecretState`,
   and it is the right one: server self-description, no user data.

   **Idempotent per step, not per bootstrap.** `garage-init.sh` returns early the moment `bucket info`
   succeeds, which is correct for a script whose steps always shipped together and wrong for a provisioner
   whose step set grows — a store provisioned before CORS existed would never receive a policy. Each step
   checks and acts on its own. The same reasoning removes a second shortcut: `garage-init.sh` hardcodes
   `layout apply --version 1`, so it only ever initialises a **fresh** node. That limit is a property of the
   script, not of the act; the provisioner must succeed against a node whose layout is already applied at any
   version.

4. **`infra/garage-init.sh`, `infra/garage-init.Dockerfile` and the `garage-init` compose service are
   deleted — from every stack, not only the release recipe.** Their work moves into the backend, which already
   holds the store credentials and, for `garage`, additionally receives the admin endpoint and token. The
   unpublished image disappears rather than becoming a third signed release artifact, and
   [ADR 0064](0064-kubernetes-helm-deployment-target.md)'s chart is unblocked because there is nothing left for
   it to pull.

   **Development runs the production shape, and that is the point rather than a side effect.** A dev-only
   sidecar would have been defensible — compose can build on a developer's host, which is the one thing Helm
   cannot do — and it was rejected for what it hides: with the sidecar present the store is always already
   provisioned by the time the backend starts, so the *fresh* provisioning path would never run outside a unit
   test, on the one target where it is cheapest to run it. Deleting it everywhere means every `docker compose
   --profile s3 up` followed by a backend start exercises the real bring-up against a real Garage. The cost is
   that a store is no longer usable **without a backend**, which two callers depended on: `scripts/run_web_e2e.py`
   needs a bucket before its first suite boots, and a restore target needs an initialised empty bucket. The
   first is the orchestrator's own problem to solve; the second is a decision, and it is the Consequence below.

5. **"The store has its own address" becomes named signing behaviour, not a deployment's special case.**
   `S3BlobStore.endpointFor` swaps `public-endpoint`'s host for the resolved tenant's domain, which is right
   for the compose same-origin path route ([ADR 0063](0063-database-driven-tenant-ingress.md)) and wrong for
   any store reached at an origin of its own. Two things need the same mode and neither is a special case of
   the other: [ADR 0064](0064-kubernetes-helm-deployment-target.md)'s Kubernetes branch, where Garage gets its
   own host on the chart's `Gateway`, and a SaaS tenant on their own `s3.amazonaws.com` bucket. It is therefore one mechanism
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

- **The publish workflow (`.github/workflows/ci.yml` since ADR 0091) gains no image for provisioning.** The alternative was a
  third, published, signed and versioned forever to serve a one-shot. A third image did come later, for a different
  job — `veyq-tools`, the cluster's backup gate ([ADR 0098](0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md)),
  which runs on every release change rather than once — and that reason stands for provisioning.
- **The backend gains Garage-specific code, deliberately and behind the type.** That is the honest cost of
  Decision 1, and it is bounded **structurally rather than by care**: under `type: s3` no provisioner is
  constructed at all, so there is no branch for a `type: s3` instance to fall down and nothing to keep
  correct — a shared path that "mostly" branches is the version of this that stops being bounded. A second
  bundled store would add a third value rather than change the shape. What it does **not** do is make the *data* path vendor-specific —
  `S3BlobStore` stays the vendor-neutral AWS SDK client CLAUDE.md's dependency rule requires.
- **The Garage admin token has two homes, by who owns the store.** The bundled stack's token stays in the
  environment (the bundled hint: Garage's own container needs it at its start, and the bundled S3 key pair is
  *derived* from it, so nothing is sealed for the bundled case). A Garage store the Server Owner enters has its
  admin token sealed in the database under the config-secret key like the S3 secret
  ([ADR 0050](0050-operator-config-secret-encryption.md)) — write-only on every surface, never returned or logged.
- **A per-tenant store later has one trap, and it is not where anyone would look.**
  `veyq_store_reclaim` carries `storage_key` and `chunk_count` and nothing else, and `StoreReclaimStore.record`
  is `@Transactional(MANDATORY)` — the tombstone is written *in the same transaction that deletes the row*, so
  that objects outlive their metadata. `ScopePurge` is one of its callers and erases the scope with it. A live
  blob reaches its tenant in one join (`veyq_blob.scope_id` → `veyq_scope.tenant_id`); a reclaim row reaches
  nothing, **by design**. With more than one store the sweep would not know which one to delete from, and the
  bytes would leak silently. The obvious remedy — one column filled at insert, while the store is still known —
  is **not free, and the reason is one decision away**:
  [ADR 0062 §6](0062-store-object-reclaim-is-a-durable-work-list.md) forbids exactly that shape on exactly that
  row (*"No `scope_id`, no `media_id`, no node. Group erasure must leave nothing behind that describes the
  erased group."*), and a store id that is one-to-one with a tenant is such a residue. Whoever takes #41 owes
  a design that satisfies both, not a column. Because this is invisible from everywhere except that line, the
  trap **and** the tension are recorded as a comment there rather than only here.
- **A restore target can no longer be prepared without the app, so the gate that defines one moves from
  "no tables" to "no data".** [uc.admin.back-up-and-restore-instance](../features/admin/use-cases/uc.admin.back-up-and-restore-instance.md)
  A5 defines a restore onto an *empty* instance, and `src/tools/instance_backup.py` implements *empty* as **zero
  tables in the target database**. That worked because the store's bucket came from a sidecar while the
  database stayed untouched. With the backend as the only provisioner the two preconditions become mutually
  unreachable: the bucket exists only after the app has started, and the same start migrates the schema — the
  app has no mode that does one without the other, `quarkus.flyway.migrate-at-start=true` carries no profile
  gate. So *empty* is redefined as **holding no data**: a freshly installed, freshly migrated instance is
  precisely the shape a restore targets, and refusing it was always the wrong reading of A5. A5's purpose is
  untouched — nothing reconciles two *populated* instances.

  **This is not a cost this ADR introduces; it is one it stops hiding.** On Kubernetes there has never been a
  sidecar — [ADR 0064](0064-kubernetes-helm-deployment-target.md)'s chart deploys `garage` and the app and
  nothing beside them — so a cluster restore has always faced exactly this wall, which ADR 0064 left to a decision
  of its own ([ADR 0098](0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md)). Compose merely had
  a sidecar in front of it. Redefining the gate fixes both targets with one change, where a compose-shaped
  remedy would have fixed neither.
- **The bucket's default name is fixed once an instance is installed.** Neither recipe sets
  `veyq.bundled-store.bucket`, so every bundled install runs on the default, and `GarageStoreProvisioner.ensureBucket`
  creates whatever the setting names when no bucket answers to it. A changed default would therefore hand every
  upgraded install a new, empty bucket: its media stays in the old one, every presigned read fails, and the
  reclaim sweep deletes against a bucket that never held the objects — S3 answers a delete of a missing key with
  success, so the old bytes are never reclaimed either. An install that must change the name pins the old one in
  its configuration instead, which every route follows ([ADR 0013 §4](0013-blob-storage-presigned-direct-to-store.md)).
  The default in `application.properties` carries the same warning, where the next person to change it looks.
- **The store is runtime-editable, so nothing caches its connection.** `SwitchingBlobStore` reads the current
  resolution on every call and rebuilds its delegate — the signing client and `S3BlobStore`'s presigner cache —
  only when the resolution is a different object, so a changed setting replaces both together. Once the first blob
  exists, type, endpoint and bucket are fixed ([ADR 0089](0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)
  decision 6): the presigner cache is correct because the store a stored blob lives in cannot change under it.
- **Rejected — publish `veyq-garage-init` as a third image.** It works, and it makes a permanent release
  artifact out of a container whose only contribution is owning a shell. Every release would sign and version
  it; every operator would pull it once and never think about it again.
- **Rejected — bundle the `garage` binary, or speak Garage's RPC, from the backend.** A vendor-locked client
  in the data path is what CLAUDE.md's dependency rule forbids, and the Admin API measurement makes it
  unnecessary: every one of those steps is plain HTTP.
- **Rejected — rewrite the script in Python and run it from a stock image.** It removes the custom image but
  keeps a second component that needs the store credentials, and it cannot be idempotent about state the
  backend is already the authority on.
- **Rejected — a `veyq-admin store init` command.** Measured: the CLI signs in as Server Owner on every
  invocation (`src/admin-web/src/cli/adminSession.ts`), and no Server Owner exists before first-run setup — so the
  earliest it could run is *after* setup, leaving a window in which an instance boots healthy and fails at the
  first upload. It would also put a Garage-specific verb in a tool whose whole premise
  ([ADR 0065](0065-admin-ui-cli-separate-service.md)) is being a thin client of a vendor-neutral backend.
- **Rejected — no type, and provision generically.** The only two steps that generalise are `bucket create`
  and the CORS policy; stopping there leaves Garage unusable out of the box, which is the defect this ADR
  exists to remove. Note this is rejected for a *different* reason than the one that makes Decision 1's `s3`
  provision nothing: here the generic pair is too little to reach the goal, there it is too much to do to a
  store veyq does not own. Both readings end at "a type is required"; only the second says what the default
  may do.

## Related

- [ADR 0013](0013-blob-storage-presigned-direct-to-store.md) (the store port, §1's hard byte-path requirement
  and §4's CORS branch — whose policy this provisioning writes).
- [ADR 0048](0048-provider-neutral-smtp-mail-transport.md) (the provider-neutral precedent this reads rather
  than reverses).
- [ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md) and
  [uc.admin.back-up-and-restore-instance](../features/admin/use-cases/uc.admin.back-up-and-restore-instance.md)
  (the restore whose target-preparation this changes, and whose A5 gate moves with it).
- [ADR 0062](0062-store-object-reclaim-is-a-durable-work-list.md) (the reclaim tombstone whose shape the
  per-tenant trap above is about).
- [ADR 0063](0063-database-driven-tenant-ingress.md) (per-request signing, and the startup-observer lesson
  Decision 3 follows).
- [ADR 0064](0064-kubernetes-helm-deployment-target.md) (the chart this unblocks, and one of the two callers
  of Decision 5).
- [ADR 0065](0065-admin-ui-cli-separate-service.md) (the CLI whose premise rules it out as the bootstrap's
  home).
- [inbox #41](../inbox.md) (the SaaS model that owns the per-tenant store question and its trust decision).
- Run area of the docs site: [Storage and email](../../site/src/content/docs/run/storage-and-email.md)
