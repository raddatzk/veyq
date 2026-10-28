# ADR 0064 — Kubernetes is a first-class deployment target (Helm chart)

- Status: Accepted
- Built: yes
- Date: 2026-09-07
- Model: [release-and-deployment](../crosscutting/release-and-deployment.md)

## Context

veyq shipped one deployment artifact, the Compose recipe beside its signed images
([ADR 0057](0057-release-identity-and-deployment-recipe.md) §3). A V1 scope cut ([inbox #26](../inbox.md)) had dropped
Helm: the self-hostability bar was one `docker compose up`. That held while deployment was single-host and
single-tenant. [ADR 0063](0063-database-driven-tenant-ingress.md) made multi-domain, multi-tenant routing a real
capability, and running an instance on an operator's existing cluster is a shape worth supporting on its own terms.
**This ADR reverses that cut**: Kubernetes becomes a second, first-class target, packaged as a Helm chart, alongside
Compose — a real second target, not a thin community fork. **"Alongside" is about shape, not order: Kubernetes gates
V1.**

Forces:

- **Everything Compose decided has to be re-derived for an operator-chosen proxy.** [ADR 0013 §4](0013-blob-storage-presigned-direct-to-store.md)'s
  same-origin, prefix-preserving blob route is a property of one Traefik configuration; another implementation is not
  guaranteed to preserve it.
- **Release identity ([ADR 0057](0057-release-identity-and-deployment-recipe.md)) is image-specific.** A chart is a
  different artifact type and needs its own equivalent.
- **The backup guard was Compose-only** ([ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md)): a
  cluster target inherits a known, documented gap, not a silent new one.
- **A second component (the admin container, [ADR 0065](0065-admin-ui-cli-separate-service.md)) needs a cluster
  identity too.**

## Decision

**A Helm chart (`infra/kubernetes/charts/veyq`) ships alongside the Compose recipe, covering the same services**: `app`,
`mariadb` (with `mariadb.enabled: false` for an operator-managed database, which clusters commonly have), `garage`
(opt-in, below), an implementation-neutral **Gateway API** resource set, and the admin container. How each part looks
on both targets is [release-and-deployment](../crosscutting/release-and-deployment.md) § The two deployment targets;
the operator's preconditions and values are the Run area's [Kubernetes](../../site/src/content/docs/run/kubernetes.md).

**The store's bring-up is not this chart's to solve.** Helm builds nothing, so a locally built init image cannot ship;
[ADR 0067](0067-store-provisioning-from-a-declared-type.md) has the backend provision the store from a declared type,
so where the chart deploys `garage` it deploys nothing beside it.

- **Routing is the Gateway API, not `Ingress`.** `Ingress` is frozen, not deprecated, so the no-deprecated-dependencies
  rule did not force the move; the chart's ingress-nginx example, a retired controller, did. The move is taken because
  the chart has no release yet: switching now costs no operator a migration, after V1 every install would. *Rejected:*
  `Ingress` and Gateway side by side — two sinks, two template sets, two RBAC shapes and a doubled check matrix, for an
  API that gains nothing; and staying on `Ingress` with only the example replaced — every new install on the frozen
  API, the move deferred to when there are installs to move.

  **The chart owns its `Gateway`; the operator picks the implementation.** One `Gateway` in the release namespace, its
  class from `gateway.className`, a listener per chart host, and `allowedListeners` from the **same namespace only**.
  A listener on the `Gateway` wins a hostname conflict against any `ListenerSet` (Gateway API's own precedence), so a
  tenant domain cannot take the bootstrap, store or admin host — by the API, not only by the sink's exclusion.
  *Rejected:* an operator-provided `Gateway` named by `parentRef` — it fits Gateway API's role split, but the operator
  must then open `allowedListeners` to veyq's namespace, the chart's own hosts become `ListenerSet`s too, and both forms
  are two shapes to check. Stated cost: some implementations provision a load balancer per `Gateway`.

- **Projection reuses [ADR 0063](0063-database-driven-tenant-ingress.md)'s principle, not a new one.** The same
  backend projector has a second sink beside the Traefik file, writing Gateway API objects through the Kubernetes API
  under a **namespace-local ServiceAccount and Role** — never a `ClusterRole`, and no separate operator container: the
  one process that owns the projection on Compose owns it here, so there is one code path to keep correct. The Role
  reaches only `httproutes` and `listenersets`, and **nothing on `gateways`**: the backend cannot add or change a
  listener, and a tenant domain reaches the network only through a `ListenerSet` the chart's `Gateway` admits. The
  sink lists its objects by its own label, so an operator's route beside them is untouched; the namespace comes from
  the pod's ServiceAccount token, never a value, or every install would need its own image.

  **TLS is a passthrough on the sink: it stamps a reference the chart tells it to, and configures nothing.** The cut
  falls between *referencing* and *configuring* — the Compose sink, too, only references a resolver the Compose recipe
  configures. The sink's half: a listener's `certificateRefs` and an operator-supplied annotation map on every projected
  `ListenerSet` — never an `Issuer`, a `Certificate` or a `Secret`, which would need RBAC this design exists to avoid.
  The chart's half, as the recipe's counterpart: it **may template the one thing the recipe configures, an ACME issuer,
  as an opt-in** (`gateway.tls.acme.*`): one namespaced cert-manager `Issuer` answering HTTP-01 on the `Gateway`'s
  port-80 listener, stamped onto every `ListenerSet` through the same annotations, so a tenant created at runtime is a
  certificate order with no chart edit and no restart. It needs no backend RBAC — Helm applies it with the operator's
  credentials — and requires `per-domain`, refusing any other mode rather than overriding an explicit one. The chart
  does **not** install cert-manager or the Gateway API CRDs, template a `ClusterIssuer`, or let the backend speak ACME:
  cluster-scoped objects break the no-cluster-wide-RBAC bar, and `create` on `Secret`s cannot be narrowed in the
  namespace that holds the database and store credentials. Their absence is left to the API server, which fails the
  whole install on an unknown kind; a capability check would break every offline render (`helm template`, `helm lint`).

  The operator's preconditions (the Gateway API CRD and Kubernetes versions, an implementation serving `ListenerSet`,
  the cert-manager version and feature gates) are the Run area's. **Residue taken knowingly:** cert-manager's
  `ListenerSet` support is alpha, so per-domain certificates for runtime tenants rest on an alpha feature until it
  graduates; and on an implementation without `ListenerSet` support the chart's own hosts work and no tenant domain
  does under `per-domain`.

  Three named modes, never inferred: `none` (the default — TLS terminates upstream), `per-domain` (an HTTPS listener per
  host whose `certificateRefs` names the object's **own** name — the one mode that keeps per-tenant custom domains
  working), and `shared` (one listener, one pre-provisioned `Secret` — the wildcard certificate, an operator's choice
  rather than everyone's ceiling, because a wildcard *certificate* takes away at the TLS layer exactly the custom-domain
  capability [ADR 0063](0063-database-driven-tenant-ingress.md) refused to give up at the routing layer). The chart's own
  hosts follow the same mode. The `Secret` reuses the object name because that name is the one string already kept
  legal at the 253-character cap; a derived suffix would be an object the API server rejects, which on this sink's
  failure boundary strands every tenant's routing. Under `per-domain` and `shared` the chart renders the `http` →
  `https` redirect itself. Stated residue: a withdrawn domain's issued `Secret` stays unless cert-manager runs with
  owner references — not swept, because sweeping `Secret`s needs the RBAC this shape avoids. **TLS is a precondition,
  not a preference:** the refresh cookie is `Secure` and every mailed link is `https`, so an instance without a
  certificate boots and then silently breaks sessions — which is why `none`, the default for continuity, is loud in
  the chart's notes.
- **Garage gets its own host on Kubernetes**, wherever the chart brings it. The gateway implementation is the
  operator's and its prefix and Host behaviour are not guaranteed, so the chart takes the CORS branch
  [ADR 0013 §4](0013-blob-storage-presigned-direct-to-store.md) documents: Garage on its own host, a CORS policy on its
  bucket, presigned URLs signed against that host. **The policy is written at runtime, not templated** — its origins
  are the tenant domains, a runtime property of the `tenant` table — by the backend's store provisioning
  ([ADR 0067](0067-store-provisioning-from-a-declared-type.md), whose Decision 5 is the second signing mode a store on
  its own host needs). On the bundled store it can only be `*` (ADR 0013 §4 says why that is tolerable).
- **The bundled store is opt-in on this target, and stays the default on Compose.** `garage.enabled` defaults to
  `false`: on a cluster the bundled Garage is one node on one claim with no replication or versioning, so
  [ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md) §3's *keeping the objects is the store operator's
  job* falls on volume snapshots; it needs a host of its own; and a cluster operator commonly runs an S3 store already.
  The chart had no release, so the default costs no install a migration. Off, the chart renders none of Garage's
  objects and requires none of its values; the console then offers only a store of the operator's own
  ([ADR 0089](0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md) Decision 3).

  **On Compose Garage stays in the recipe and on**: it shares the app's origin and costs one container. An operator
  leaves it out with the instance's `compose.override.yml` ([ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md));
  the recipe's `app` → `garage` dependency is `required: false` so the override needs nothing more. *Rejected:* a
  Compose profile off by default — an instance upgraded with its old `.env` would lose the Garage its store is.

  **Backup and restore take the own store's key pair on both targets** (ADR 0089 Decision 7): the pair the console
  holds is sealed under the KEK the backup never reads. *Rejected:* a schema rule requiring the backup's store secret
  with `garage.enabled: false` — the store is set after the install, and the backup's refusal at the first capture names
  the value. **Turning Garage off under an instance whose store is the bundled one is not guarded**: Helm cannot read
  the store setting (`lookup` is blind under `helm template` and GitOps). Nothing is lost — the claims outlive the
  StatefulSet, so turning it on again restores the instance, which names the cause meanwhile.
- **The chart is released the same way the image is**: an OCI artifact in the same registry, by the same publish,
  with the same version, signed the same way — ADR 0057's release identity extended to a second artifact type rather
  than a separate channel. Its `version` and `appVersion` are **both** the release version, set at packaging, never by
  hand. Helm takes a two-part version **by exact name or digest only**: a range, or no `--version`, finds nothing, and
  `helm lint` warns. **That trade is taken on purpose**: one name for the release, its images and its chart is worth
  more than resolution by range, since veyq tells an operator to pin by digest and a chart that tracks "the latest" is
  the moving name the pins exist to avoid. *Rejected:* a chart `version` of `<major>.<minor>.0` beside an `appVersion`
  of `<major>.<minor>` — it buys ranges and costs the one shared name. The image repositories default to the
  upstream's own, written out for the reason the verify identity is: a fork overrides them, knowingly. A release
  refuses to republish the chart as it does the images; a release with its images and no chart is a partial release.
- **The chart generates nothing and every pod passes `restricted`**, to which
  [ADR 0071](0071-runtime-hardening-on-both-targets.md) adds a read-only root and a NetworkPolicy set. Every secret is
  the operator's, enforced by `values.schema.json`: a value Helm generated is re-rolled wherever `lookup` is blind, and
  a chart that generates values cannot be asserted because no two renders agree. The config-secret KEK is the one
  exception: the app generates it onto its own claim. The app runs one pod with `strategy: Recreate`, because the
  schema migrates at boot with no gate and a rolling update would start the new migrator before the old one stops.
- **A chart upgrade is checked by rendering both versions.** The app-level upgrade proof
  ([ADR 0057](0057-release-identity-and-deployment-recipe.md) Decision 7) proves the data; what breaks a `helm upgrade`
  is an **immutable field** (a selector, a StatefulSet's fields outside the mutable set, its `volumeClaimTemplates`, a
  PVC's access modes or class or a shrinking size, a `roleRef`, a kind changing under a name), an object disappearing
  with the data behind it, or an operator's values no longer passing the new schema. All of these are visible **without
  a cluster**, so `scripts/chart_upgrade_check.py` renders the predecessor's chart and the candidate's with the same
  values and compares them. The predecessor is the upgrade proof's; the check is **information until the first release
  and red from it**, so from then on it gates the publish.
- **Backup/restore is not this ADR's decision.** [ADR 0098](0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md)
  decides it.

## Consequences

- **Two deployment targets to keep honest against each other.** Every infra-level decision (routing, TLS,
  config-secret persistence) has a Compose *and* a Helm answer; a future ADR or CR that changes one checks the other —
  the discipline ADR 0057 keeps between the recipe's two homes.
- **The V1 scope cut of inbox #26 is reversed**, not silently contradicted.
- **A green chart-upgrade check renders; it never installs.** Not covered: a missing StorageClass or expansion
  support, admission, image pulls, data surviving a real `helm upgrade`, and a database password changed on upgrade.
  A cluster leg that would see those is a later step of its own (inbox #430). The mutable set is the API server's
  refusal as remembered, not measured on a cluster; a field it wrongly leaves out is a false red, never a false green.
- **Deferred, explicitly**: a `mariadb`-operator integration beyond the bundled StatefulSet; multi-cluster and
  multi-region topologies. None is a self-hosting concern.

## Related

- [ADR 0063](0063-database-driven-tenant-ingress.md) (the ingress-projection principle this chart's sink
  implements).
- [ADR 0013](0013-blob-storage-presigned-direct-to-store.md) §4 (same-origin vs. CORS branch).
- [ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md),
  [ADR 0057](0057-release-identity-and-deployment-recipe.md) (the compose-side precedents this generalizes).
- [ADR 0065](0065-admin-ui-cli-separate-service.md) (the admin container the chart also deploys).
- The Run area of the docs site: [Set up a new instance](../../site/src/content/docs/run/new-instance.mdx) (the
  `helm install` beside the compose one) and [Kubernetes](../../site/src/content/docs/run/kubernetes.md).
- [inbox #26](../inbox.md) (the reversed scope cut).
- Run area of the docs site: [Running your own instance](../../site/src/content/docs/run/index.md), [Plan your instance](../../site/src/content/docs/run/plan.md)
- Run area of the docs site: [Configuration reference](../../site/src/content/docs/run/configuration.md)
- Run area of the docs site: [Upgrade](../../site/src/content/docs/run/upgrade.mdx), [Verify a release](../../site/src/content/docs/run/verify-a-release.md) (the chart's signature check)
