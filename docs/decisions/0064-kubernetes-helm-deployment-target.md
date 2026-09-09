# ADR 0064 — Kubernetes is a first-class deployment target (Helm chart)

- Status: Accepted
- Date: 2026-09-07

## Context

veyq ships one deployment artifact today: `infra/docker-compose.prod.yml`, travelling inside the signed app
image ([ADR 0057 §3](0057-release-identity-and-deployment-recipe.md)). A **V1 scope cut recorded 2026-08-06**
([inbox #26](../inbox.md)) explicitly dropped Helm: *"zero k8s artefacts in the tree and the §10
self-hostability bar is literally one `docker compose up` + a JVM."* That reasoning held while veyq's
deployment story was single-host, single-tenant. It no longer fully does: [ADR 0063](0063-database-driven-tenant-ingress.md)
makes multi-domain, multi-tenant routing a real capability, and running a self-hosted instance on an
operator's existing Kubernetes cluster — rather than a dedicated compose host — is a deployment shape worth
supporting on its own terms, not only as a compose workaround. **This ADR reverses that cut**: Kubernetes
becomes a second, first-class deployment target, packaged as a Helm chart, alongside — not instead of —
docker-compose ([`docs/ai/README.md`](../ai/README.md)'s "structured over fast": a real second target, not a
thin community-maintained fork).

**Amended 2026-09-09, at refinement: "alongside" describes the two targets, and was silently read as a
statement about *timing* it never made.** Asked directly during this ADR's refinement, the owner decided
**Kubernetes gates V1** — an instance must be deployable onto a cluster before the release, not after it.
Compose is unaffected and stays the other supported target; what changes is that none of the work below is
post-V1 scope. This is recorded because the whole 0063/0065/0066 series was refined under the opposite
assumption without anyone stating it, and a sentence about *shape* ("alongside") is exactly the kind that
gets read as a sentence about *order*. `docs/inbox.md` § V1 and #26 carry the same correction.

Forces:

- **Everything docker-compose already decided has to be re-derived for an operator-chosen ingress
  controller**, not the one Traefik behavior the compose file assumes. In particular
  [ADR 0013 §4](0013-blob-storage-presigned-direct-to-store.md)'s same-origin, prefix-preserving blob route
  is a property of *that specific* Traefik configuration — nginx-ingress, a cloud load balancer, or a
  different Traefik Helm chart are not guaranteed to preserve it.
- **The release-identity story ([ADR 0057](0057-release-identity-and-deployment-recipe.md)) is
  image-specific.** A Helm chart is a different artifact type and needs its own equivalent, not a
  reinterpretation of "travels inside the image."
- **The backup guard is explicitly compose-only.** [ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md)
  names this directly: *"that guard sees compose files in this tree only: for Kubernetes ... the obligation
  is the operator's, and the `veyq_instance` fingerprint tripwire is the only detection."* A Kubernetes
  target inherits an already-known, already-documented gap, not a silent new one.
- **A second component (the admin container, [ADR 0065](0065-admin-ui-cli-separate-service.md)) needs a
  cluster identity too** if it is to manage its own Ingress state.

## Decision

**A `charts/veyq` Helm chart ships alongside `infra/`, covering the same services**: `app`, `mariadb` (with
a `mariadb.enabled: false` escape hatch onto an operator-managed database, which K8s deployments commonly
already have), `garage` (+ its init job), an ingress-controller-agnostic Ingress resource set, and the admin
container ([ADR 0065](0065-admin-ui-cli-separate-service.md)).

**Amended 2026-09-09, at refinement, measured: "`garage` (+ its init job)" names an image a chart cannot
pull.** `infra/docker-compose.prod.yml`'s `garage-init` service carries `build:` — `veyq-garage-init:v2.0.0` is
built **locally** from `infra/garage-init.Dockerfile` and is not one of the two images `container.yml`
publishes. Compose can build it on the operator's host; Helm cannot build anything. That Dockerfile exists for
one reason: the official Garage image is `scratch`-based and has no shell, so `garage-init.sh` cannot run
inside it. **Measured the same day, and it dissolves the constraint rather than costing a third artifact:**
Garage v2.0.0 exposes an Admin API (`:3903`, bearer token, plain JSON) that covers every step the script
takes — a bucket was created through `POST /v2/CreateBucket` and deleted again through
`POST /v2/DeleteBucket?id=…`, both 200, against the running dev store. So the `garage` binary is not required
and neither is a custom image; what remains is *where* the bootstrap lives, which is part of the runtime
store-configuration question this ADR now defers to (`docs/inbox.md` #135) rather than settling in passing.
Note what does **not** generalise: `CreateBucket` and `PutBucketCors` are S3 for any store, while
`layout assign`/`layout apply` and `key import`/`bucket allow` are Garage-specific (the latter two are IAM,
which no two stores spell alike) — a store bootstrap is inherently per-store, and any design that pretends
otherwise is wrong at the second store.

- **Ingress projection reuses [ADR 0063](0063-database-driven-tenant-ingress.md)'s principle, not a new
  one.** The same backend projector gets a second sink: instead of a Traefik dynamic file, it manages
  Kubernetes `Ingress` objects directly via the K8s API, scoped to a **namespace-local ServiceAccount +
  Role** the chart creates — never a cluster-wide `ClusterRole`; the backend manages ingress only in its own
  namespace. No separate operator/sidecar container: the same single backend process that already owns this
  projection for compose owns it here, so there is exactly one code path to keep correct, not two.
- **Garage gets its own Ingress host by default on Kubernetes**, not the compose same-origin path route.
  Because the ingress controller is operator-chosen and its exact prefix/Host-preservation behavior is not
  guaranteed, the chart defaults to the CORS-policy branch [ADR 0013 §4](0013-blob-storage-presigned-direct-to-store.md)
  already documents as the alternative for stores that aren't behind the specific bundled-Traefik route — the
  chart configures that CORS policy on Garage's bucket and signs presigned URLs against Garage's own Ingress
  host. (§4 is updated in place to name this as the Kubernetes branch.)

  **Amended 2026-09-09, at refinement, measured against the pinned Garage v2.0.0 — the branch stands, two of
  its premises do not.** The decision above was re-affirmed deliberately after the measurement; what changed is
  what it costs and who can do it.
  - **"The chart configures that CORS policy" cannot be true.** An `AllowedOrigin` list is the set of *web*
    origins, which is the tenant-domain set — a runtime property of the `tenant` table, which is the entire
    premise of [ADR 0063](0063-database-driven-tenant-ingress.md). A chart is deployment-time and cannot know
    it. So the policy's writer is a **runtime** component, not a template value.
  - **And projecting the real set would not work anyway.** Measured by driving `PutBucketCors` + a real
    `OPTIONS` preflight against a running Garage v2.0.0: a **single** exact origin is honoured correctly; a
    subdomain wildcard (`https://*.example.com`) is *accepted by `PutBucketCors` and then matches nothing*
    (403 on every preflight — a silent misconfiguration, not an error); and **two or more exact origins make
    Garage echo the whole list** as `Access-Control-Allow-Origin: https://a.example.com, https://b.other.org`,
    which is not a legal CORS response — the preflight answers 200 and the browser still blocks the fetch.
    **So on the bundled store, the only policy that works for a multi-tenant instance is `*`.** That is
    accepted here rather than worked around: the presigned URL *is* the capability, its TTL is short
    (`veyq.blob-store.presign-ttl-seconds`, 600s), and the fetch carries no cookie or credential — `*` widens
    who may *use* a URL they already hold, not who may obtain one. It is a posture statement all the same, so
    it is written here rather than left in a values file.
  - **The branch is not free on the backend, which this ADR did not say.** `S3BlobStore.endpointFor`
    (`backend/.../shared/blobstore/BlobStore.kt`) implements ADR 0063 by **swapping `public-endpoint`'s host
    for the resolved tenant's domain**. That is exactly right for the compose same-origin route and exactly
    wrong for a store on its own Ingress host, where every tenant must sign against the *same* store origin.
    The Kubernetes branch therefore needs a second signing mode, not only a chart template.
  - **Where the CORS write lives is deliberately left open here.** `PutBucketCors` is vendor-neutral S3, so
    the natural writer is whatever component owns the store connection — and that ownership is itself being
    re-decided (`docs/inbox.md` #135, runtime store configuration). Naming a home now would freeze the smaller
    question inside the larger one.
- **The chart is released the same way the image is.** Packaged and pushed as an **OCI artifact** to the same
  GHCR registry (`ghcr.io/<owner>/veyq-chart`), versioned `<major>.<minor>` in lockstep with the app image
  and **cosign-signed** the same way — extending [ADR 0057](0057-release-identity-and-deployment-recipe.md)'s
  release-identity story to a second artifact type rather than inventing a separate distribution channel
  (Helm's native OCI support makes this a config addition to the existing `container.yml` workflow, not a new
  tool).
- **Backup/restore stays explicitly out of this ADR's scope.** [ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md)'s
  gap statement is accurate and stays accurate; a Kubernetes-native backup story (a `CronJob` running
  `scripts/instance_backup.py`, or a StatefulSet snapshot strategy) is deferred to its own future decision,
  not folded into this one.

## Consequences

- **Two deployment targets to keep honest against each other.** Every infra-level decision (routing, TLS,
  config-secret persistence) now has a compose *and* a Helm answer; a future ADR/CR that changes one must
  check the other — the same discipline [ADR 0057](0057-release-identity-and-deployment-recipe.md) already
  keeps between the compose file's two homes (repo + inside the image).
- **`docs/inbox.md` #26 is corrected in place** to record the reversal and point here — the 2026-08-06
  reasoning is superseded, not silently contradicted.
- **The upgrade runbook** (`infra/README.md` § An upgrade, step by step) needs a Kubernetes equivalent
  (`helm upgrade`, the same migrate-at-start caveat inbox #26 already names as undecided) — scoped to the CR
  that actually builds the chart, not decided here.
- **Deferred, explicitly**: Kubernetes-native backup/restore; a `mariadb`-operator integration beyond the
  bundled StatefulSet; multi-cluster/multi-region topologies. None of these are self-hosting concerns — out
  of scope on the same grounds `docs/inbox.md` already uses for SaaS-era items.

## Related

- [ADR 0063](0063-database-driven-tenant-ingress.md) (the ingress-projection principle this chart's sink
  implements).
- [ADR 0013](0013-blob-storage-presigned-direct-to-store.md) §4 (same-origin vs. CORS branch).
- [ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md),
  [ADR 0057](0057-release-identity-and-deployment-recipe.md) (the compose-side precedents this generalizes).
- [ADR 0065](0065-admin-ui-cli-separate-service.md) (the admin container the chart also deploys).
- [inbox #26](../inbox.md) (the reversed scope cut).
