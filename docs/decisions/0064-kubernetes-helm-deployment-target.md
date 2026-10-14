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

**"Alongside" is a statement about the two targets, not about their order: Kubernetes gates V1.** An
instance must be deployable onto a cluster before the release, not after it; compose is unaffected and stays
the other supported target. Worth saying explicitly because the 0063/0065/0066 series was refined under the
opposite assumption without anyone stating it — a sentence about *shape* reads all too easily as one about
*sequence*. `docs/inbox.md` § V1 and #26 carry the same line.

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
already have), `garage`, an ingress-controller-agnostic Ingress resource set, and the admin container
([ADR 0065](0065-admin-ui-cli-separate-service.md)).

**The store's bring-up is not this chart's to solve.** It once travelled with a `garage-init` sidecar whose
image was built locally and never published — which a chart cannot pull, because Helm builds nothing.
[ADR 0067](0067-store-provisioning-from-a-declared-type.md) removes it: the backend provisions the store
itself from a declared store type, so the chart deploys `garage` and nothing beside it.

- **Ingress projection reuses [ADR 0063](0063-database-driven-tenant-ingress.md)'s principle, not a new
  one.** The same backend projector gets a second sink: instead of a Traefik dynamic file, it manages
  Kubernetes `Ingress` objects directly via the K8s API, scoped to a **namespace-local ServiceAccount +
  Role** the chart creates — never a cluster-wide `ClusterRole`; the backend manages ingress only in its own
  namespace. No separate operator/sidecar container: the same single backend process that already owns this
  projection for compose owns it here, so there is exactly one code path to keep correct, not two.

  **What the sink already does, which the chart must match rather than re-decide.** It reconciles: one
  `Ingress` per routable domain, named `veyq-tenant-<domain>`, labelled
  `app.kubernetes.io/managed-by: veyq-tenant-ingress`, and it **lists by that label** — so the Role needs
  `list`, `create`, `update` **and `delete`** on `networking.k8s.io/ingresses` in the one namespace, and an
  operator's own `Ingress` beside it is untouched. The namespace is read from the pod's mounted ServiceAccount
  token, never from a chart value: a required namespace setting would mean one image per install, which is the
  property the compose/admin-web work already paid for. `veyq.tenant.ingress.kubernetes.service-name`/`-port`
  name the `Service` the rules point at, so renaming the app `Service` in the chart means setting them.

  **TLS is a passthrough on the sink: it stamps a reference the chart tells it to, and configures nothing.**
  The compose sink is not TLS-free — it emits `tls.certResolver: letsencrypt` on every router, a *reference* to
  a resolver the compose **recipe** configures (`VEYQ_ACME_EMAIL`, the CA directory, HTTP-01). The cut is
  between referencing and configuring, not between emitting and not emitting — and it falls between the *sink*
  and the *recipe*, not between the backend and the whole deployment. So the faithful translation has two
  halves. The sink's: `spec.tls` plus an operator-supplied annotation map on every projected `Ingress` — never
  an `Issuer`, a `Certificate` or a `Secret`, which would need RBAC this design exists to avoid (cert-manager
  watches the `Ingress` objects the sink is already permitted to write). The chart's, as the recipe's
  counterpart: it **may template the one thing the recipe configures — an ACME issuer — as an opt-in**,
  `ingress.tls.acme.*`: one **namespaced** cert-manager `Issuer` (`veyq-acme`; ACME HTTP-01; the operator's
  e-mail, required as `VEYQ_ACME_EMAIL` is; a CA directory URL defaulting to Let's Encrypt production exactly as
  `VEYQ_ACME_CASERVER` does), and `cert-manager.io/issuer: veyq-acme` stamped through the same annotation map
  every `Ingress` already receives — the chart's own and the projected ones alike, so a tenant created at
  runtime is a certificate order with no chart edit and no restart. It needs no backend RBAC at all: Helm
  applies the `Issuer` with the operator's credentials, and the backend's `Role` keeps its `Ingress` verbs and
  nothing else. It requires `mode: per-domain` and refuses any other mode in `values.schema.json` rather than
  overriding an explicit `shared` silently — ACME is a way of *obtaining* the per-domain certificates, not a
  fourth mode, and the sink's `when` over the three modes stays total. What the chart does **not** do, each for
  the same reason: install cert-manager (a subchart is cluster-scoped CRDs plus a `ClusterRole` — the
  no-cluster-wide-RBAC bar the self-hostability story states), template a `ClusterIssuer` (cluster-scoped), or
  let the backend speak ACME itself (`create` on `Secret`s cannot be narrowed by `resourceNames`, in the
  namespace that holds the database and store credentials; it would also have to route HTTP-01, and it would
  reimplement cert-manager). cert-manager is therefore a **precondition the operator meets**, like the ingress
  controller — a CNCF-graduated project with two supported releases at any time. Its absence is left to the API
  server: `helm install` builds every object before it creates any, and an unknown kind fails the whole release
  with *"ensure CRDs are installed first"*; a `.Capabilities.APIVersions.Has` check would buy a friendlier
  sentence at the price of breaking every offline render (`helm template` knows no cluster, `helm lint` has no
  `--api-versions`), so there is none. The ACME account key is cert-manager's `Secret`, generated by it — the
  chart still generates nothing (below).

  Three named modes under `veyq.tenant.ingress.kubernetes.tls.*`, never inferred: `none` (the default — TLS
  terminates upstream, and no existing deployment moves), `per-domain` (one `spec.tls` per domain whose
  `secretName` is the object's **own** name, cert-manager's ingress-shim shape — the one that keeps per-tenant
  custom domains working), and `shared` (every object references one pre-provisioned `Secret`: the wildcard
  certificate, kept as an operator's choice rather than as everyone's ceiling, because a wildcard *certificate*
  takes away at the TLS layer exactly the custom-domain capability
  [ADR 0063](0063-database-driven-tenant-ingress.md) refused to give up at the routing layer). The chart's own
  `Ingress` objects follow the same mode, so the operator decides once. Why `secretName` reuses the object name
  rather than deriving a second one: a `Secret` name has the same 253-character cap an object name has,
  `contracts/api/tenants.openapi.yaml` allows a 253-character domain, and the object name is the one string
  already kept legal at that cap — a `-tls` suffix on it is an object the API server rejects, which on this
  sink's failure boundary strands *every* tenant's routing. Stated residue: the `Certificate` ingress-shim
  creates is owned by its `Ingress` and goes with it, but a withdrawn domain's issued `Secret` is not deleted
  by cert-manager unless it runs with `--enable-certificate-owner-ref` — operator-visible, not a routing or
  access leak, and not swept, because sweeping `Secret`s would need the RBAC this shape avoids. **TLS on this
  target is a precondition, not a preference:** the refresh cookie is `Secure` by default and every mailed link
  is `https`, so a chart that cannot obtain a certificate for a tenant domain ships an instance that boots and
  then silently breaks sessions — which is why `none` is the default for continuity and the chart's notes say
  so loudly.
- **Garage gets its own Ingress host by default on Kubernetes**, not the compose same-origin path route.
  Because the ingress controller is operator-chosen and its exact prefix/Host-preservation behavior is not
  guaranteed, the chart defaults to the CORS-policy branch [ADR 0013 §4](0013-blob-storage-presigned-direct-to-store.md)
  already documents as the alternative for stores that aren't behind the specific bundled-Traefik route —
  Garage gets its own Ingress host, a CORS policy on its bucket, and presigned URLs signed against that host.
  (§4 is updated in place to name this as the Kubernetes branch.)

  Two things about that branch are easy to get wrong, and both are settled elsewhere rather than here.
  **The policy is written at runtime, not templated.** An `AllowedOrigin` list is the set of *web* origins,
  which is the tenant-domain set — a runtime property of the `tenant` table, and the entire premise of
  [ADR 0063](0063-database-driven-tenant-ingress.md). A chart is deployment-time and cannot know it, so the
  writer is the backend's store provisioning
  ([ADR 0067](0067-store-provisioning-from-a-declared-type.md)). **And on the bundled store the policy can
  only be `*`** — [ADR 0013 §4](0013-blob-storage-presigned-direct-to-store.md) carries the measurement and
  why that is tolerable here.

  **The branch is not free on the backend.** `S3BlobStore.endpointFor` swaps `public-endpoint`'s host for the
  resolved tenant's domain, which is right for the compose same-origin route and wrong for a store on its own
  Ingress host, where every tenant signs against the *same* store origin. That second signing mode is
  [ADR 0067](0067-store-provisioning-from-a-declared-type.md)'s Decision 5 — one mechanism, of which this
  chart is one of two callers.
- **The chart is released the same way the image is.** Packaged and pushed as an **OCI artifact** to the same
  GHCR registry, at `ghcr.io/<owner>/charts/veyq`, by the same publish as the images, with the same version, and
  **cosign-signed** the same way — extending [ADR 0057](0057-release-identity-and-deployment-recipe.md)'s
  release-identity story to a second artifact type rather than inventing a separate distribution channel
  (Helm's native OCI support makes this a config addition to the existing `container.yml` workflow, not a new
  tool). The path has a `charts/` segment because `helm push` appends the chart's **name** to the target: pushed
  to `oci://ghcr.io/<owner>` the chart `veyq` would land on `ghcr.io/<owner>/veyq`, the app image's own
  repository. The chart's `version` and `appVersion` are **both** the release version (`<major>.<minor>`, or
  `<major>.<minor>-SNAPSHOT` for a snapshot), set at packaging from the publish's version, never by hand. Helm
  takes a two-part version **by name only** — measured with Helm 4.0.1 against a local OCI registry: it packages,
  pushes and installs `--version 0.3`, `--version 0.4-SNAPSHOT` and `oci://…@sha256:<digest>` unchanged, but
  `helm lint` warns that `0.3` *"is not a valid SemVerV2"*, and with no `--version`, or a range, Helm finds no
  tag at all (*"unable to locate any tags"*, `--devel` or not): its tag listing skips every tag that is not
  three-part SemVer. (A three-part control, `0.5.0` beside `0.6.0-SNAPSHOT`, resolves `0.5.0`, and `--devel`
  the snapshot.) So the chart needs no patch level to be installed the way the operator page installs it — by
  the verified digest, or by its exact version — and in exchange a version range or "the latest chart" selects
  nothing, a snapshot included. **That trade is taken on purpose.** One name for the release, its images and its
  chart is worth more here than resolution by range: veyq tells an operator to pin by digest, never to follow a
  range, and a chart that silently tracks "the latest" is exactly the moving name the pins exist to avoid.
  *Rejected:* a chart `version` of `<major>.<minor>.0` beside an `appVersion` of `<major>.<minor>`. It buys ranges
  and the tools that resolve charts by them, and costs the one name the release and its chart share. The image repositories
  default to the upstream's own (`ghcr.io/raddatzk/veyq`, `ghcr.io/raddatzk/veyq-admin`), written out for the
  same reason the verify identity is: a fork that deploys its own images overrides them, knowingly. A release
  refuses to republish the chart as it does the images; a release with two images and no chart is a partial
  release. **Built, publish unobserved:** `container.yml`'s publish packages, pushes and signs the chart, and
  `scripts/validate_release_identity.py` holds it there — but its only proof is a workflow run, and no CI run has
  ever executed in this repository (no remote). The first proof is the staged run [infra/README](../../infra/README.md)
  § Cutting a release lists; until its refused repeat release has run, this stays *unobserved*, not *released*.
  In the tree, `Chart.yaml` carries the placeholder `0.0-dev` no publish uses, because the minor is a commit count
  no tree can hold, and `helm install ./charts/veyq` from a checkout sets the image tags itself. The image
  repositories' defaults are held to the names infra/README verifies by the same guard; `chart_meta_check.py`
  runs in `container.yml`'s test job.
- **The chart generates nothing and every pod passes `restricted`**, to which
  [ADR 0071](0071-runtime-hardening-on-both-targets.md) adds a read-only root and a NetworkPolicy set whose
  ingress-controller peer the operator must name. Every secret is the operator's to supply
  (or to name in an existing `Secret`), enforced by `values.schema.json` — a value Helm generated would be
  re-rolled on any render where `lookup` is blind, and a chart that generates values cannot be asserted
  because no two renders agree. The config-secret KEK is the one exception and stays one: the app generates
  it onto its own PersistentVolumeClaim, which the pod makes writable with `fsGroup` = the image's runtime
  user, because a claim inherits nothing from the image directory it covers (a Docker named volume does).
  The app runs one pod with `strategy: Recreate` — the schema migrates at boot with no gate, and RollingUpdate
  would start the new migrator before the old one stops. `scripts/chart_meta_check.py` holds all of this on
  the rendered manifests, outside the buildless `validate_*` glob because it needs `helm`.
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
- **The upgrade runbook has its Kubernetes equivalent** — `infra/README.md` § Running on Kubernetes carries
  `helm upgrade` with the same migrate-at-start caveat (one pod, `Recreate`, the backup as the only way back)
  and the orphaned-TLS-`Secret` residue.
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
