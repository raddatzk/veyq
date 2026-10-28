# ADR 0067 — veyq provisions its own object store, from a declared store type

- Status: Accepted
- Built: yes
- Date: 2026-09-09
- Model: [cc.storage.object-lifecycle](../crosscutting/cc.storage.object-lifecycle.md)
- Replaced in part: decision 3 by ADR 0089 (built)

## Context

A fresh store is running but not usable: Garage comes up with no layout, bucket, access key, grant or CORS rule. A
one-shot `garage-init` compose service closed that gap from an image of its own that existed only to put the `garage`
CLI on a shell — an image no release published.

- **A chart cannot build an image** ([ADR 0064](0064-kubernetes-helm-deployment-target.md)), so on Kubernetes that init
  job had nothing to pull.
- **Self-hostable by default is a bar on the result:** `docker compose up` and `helm install` must yield an instance
  that serves media, not one that fails at the first photo. A bootstrap the operator must remember is the same defect as
  a missing one.
- **A store bootstrap is per-vendor, and no design makes it generic.** Layout is Garage cluster mechanics with no S3
  analogue; keys and grants are IAM, which AWS, Garage and Ceph spell differently. Only `bucket create` and
  `PutBucketCors` are S3 for anybody.
- **The `garage` binary is not needed:** Garage's Admin API (plain JSON over HTTP, a bearer token) covers every step.
- **This is the first provider discriminant in the codebase**, beside a precedent that went the other way: mail is
  provider-neutral ([ADR 0048](0048-provider-neutral-smtp-mail-transport.md)) because SMTP *is* one protocol every
  provider speaks. Provisioning has no such protocol; neutrality there buys an empty abstraction over incompatible APIs.

## Decision

**The store connection declares its type, and veyq provisions what that type allows it to.**

1. **A `type` on the store connection: `garage` or `s3`.** `garage` is the store veyq can bring from running to usable
   end to end — the bundled one, where veyq owns both sides. `s3` covers every other store, is the **default**, and
   under it veyq provisions **nothing at all** — not even the two generic steps. The CORS rule veyq writes is `*`, the
   only policy that works on the bundled Garage with several tenant domains
   ([ADR 0013](0013-blob-storage-presigned-direct-to-store.md) § 4); a store that returns the matched origin correctly
   does not need it, and writing it to somebody's own bucket would silently widen who may use a URL they hold, on
   infrastructure veyq has measured nothing about. `bucket create` is at best a no-op on a bucket the operator already
   pointed at, at worst a bucket made in their account under a name they did not choose. **An unknown store is never
   poked.** `s3` is still a type, not a boolean, because a genuinely safe generic step would go there. The type belongs
   to the connection, not the environment, so several connections (a SaaS tenant on its own bucket, inbox #41) need no
   reshaping.

2. **Provisioning is a separate seam from the data plane.** `BlobStore` stays presign / stat / delete / list and a
   `StoreProvisioner` owns bring-up: bucket and IAM calls are not what a blob store does, and object deletion has
   exactly one arm ([ADR 0062](0062-store-object-reclaim-is-a-durable-work-list.md) Decision 3) that provisioning must
   not become a second of.

3. **Neither the store configuration nor the network work gates the boot.** The store is a Server-Owner setting
   ([ADR 0089](0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)), so at boot there may be
   none: the instance starts, answers `/api/setup` and refuses uploads until a store has passed its check. The boot
   refuses only local, instant facts about the environment's own part. Provisioning reaches another container over the
   network, and a startup observer that can fail on an unreachable dependency is how
   [ADR 0063](0063-database-driven-tenant-ingress.md) restart-looped a stack into serving nothing; so provisioning runs
   in the background, retries with a bound, and reports its outcome on the Server Owner's instance surface — a log line
   reaches whoever reads logs, while the failure reaches the operator as a photo that will not upload. **Each step is
   idempotent on its own**, not the bootstrap as a whole, so a store provisioned before a step existed still receives it,
   and a node whose layout is already applied at any version is not a failure.

4. **The `garage-init` script, its image and its compose service are deleted from every stack**, development included.
   Their work moves into the backend, which already holds the store credentials. A dev-only sidecar was rejected for
   what it hides: with it, the store is always provisioned before the backend starts, so the fresh path would never run
   outside a unit test on the one target where running it is cheapest.

5. **"The store has its own address" is named signing behaviour.** Signing for the resolved tenant's domain is right for
   the Compose same-origin route and wrong for a store at an origin of its own; Kubernetes' own-host Garage and a tenant
   on its own S3 bucket both need the second mode, so it is one mechanism with two callers, decided here rather than
   inside either.

**Not decided here: whether a store connection may belong to a tenant, and on what terms.** V1 configures one store
for every tenant. A central operator presigning against a customer's own bucket holds that customer's infrastructure
credentials; choosing between a credential scoped to the bucket and an assumed role belongs with inbox #41. Content is
E2EE either way.

## Consequences

- **No image for provisioning** joins the release. (A later image, `veyq-tools`, exists for a different job — the
  cluster's backup gate, [ADR 0098](0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md), which runs on
  every release change.)
- **The backend gains Garage-specific code, bounded structurally:** under `type: s3` no provisioner is constructed, so
  there is no branch for an `s3` instance to fall down. The data path stays the vendor-neutral S3 client.
- **The Garage admin token has two homes:** the bundled stack's stays in the environment (Garage needs it at its start,
  and the bundled key pair is derived from it); a Garage the Server Owner enters has its token sealed under the
  config-secret key ([ADR 0050](0050-operator-config-secret-encryption.md)).
- **A per-tenant store later has a trap:** a reclaim row carries no store, so with several stores the sweep would not
  know where to delete — and a store column that is one-to-one with a tenant is exactly the residue
  [ADR 0062](0062-store-object-reclaim-is-a-durable-work-list.md) Decision 6 forbids. Whoever takes #41 owes a design
  that satisfies both; the trap is recorded at `StoreReclaimStore` in the code.
- **A restore target is an instance that holds no data, not one with no tables.** With the backend as the only
  provisioner, a bucket exists only after the app started, and that start migrates the schema, so "empty" can no longer
  mean "no tables" ([uc.admin.back-up-and-restore-instance](../features/admin/use-cases/uc.admin.back-up-and-restore-instance.md)
  A5). Kubernetes never had a sidecar and always faced this; one redefinition fixes both targets.
- **The bundled bucket's name is fixed once an instance is installed.** The provisioner creates whatever the setting
  names, so a changed default would hand every upgraded install an empty bucket whose old objects nothing reads or
  reclaims. An install that must change it pins the old name, which every route follows.
- **The store is runtime-editable, so nothing caches its connection** beyond a delegate rebuilt when the setting
  changes; once the first blob exists, type, endpoint and bucket are fixed (ADR 0089 decision 6).
- **Rejected — publish the init image as a third release artifact.** A permanent signed artifact for a container whose
  only contribution is owning a shell.
- **Rejected — bundle the `garage` binary or speak Garage's RPC.** A vendor-locked client in the data path, and the
  Admin API makes it unnecessary.
- **Rejected — rewrite the script in Python on a stock image.** It keeps a second component holding the store
  credentials, and cannot be idempotent about state the backend is the authority on.
- **Rejected — a `veyq-admin store init` command.** The CLI signs in as Server Owner, who does not exist before
  first-run setup, leaving a window in which an instance boots healthy and fails at the first upload; and it would put a
  Garage verb in a thin client of a vendor-neutral backend ([ADR 0065](0065-admin-ui-cli-separate-service.md)).
- **Rejected — no type, provision generically.** The two generic steps leave Garage unusable out of the box — too little
  to reach the goal, where under `s3` they are too much to do to a store veyq does not own.

Run area of the docs site: [Storage and email](../../site/src/content/docs/run/storage-and-email.md).
