---
title: Kubernetes
description: What is specific to running veyq from its Helm chart.
sources:
  - docs/crosscutting/release-and-deployment.md
  - docs/decisions/0064-kubernetes-helm-deployment-target.md
  - docs/decisions/0071-runtime-hardening-on-both-targets.md
  - docs/decisions/0094-a-clients-address-is-the-hop-the-network-vouches-for.md
  - docs/decisions/0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md
  - docs/decisions/0118-the-app-image-is-distroless-and-carries-its-own-commands.md
---

The install itself — `helm install` by the digests you verified, and the Server Owner created over a
port-forward — is on [Set up a new instance](/run/new-instance/#install), beside the Docker Compose one. This
page is what is specific to the chart.

**Routing is the Gateway API, and the chart owns its `Gateway`** ([ADR
0064](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0064-kubernetes-helm-deployment-target.md)). The
chart renders one `Gateway`, `veyq`, and an `HTTPRoute` per host; the application writes an `HTTPRoute` per tenant
(and, under `per-domain` TLS, a `ListenerSet`) into its own namespace. Which implementation serves the `Gateway` is
yours: name its `GatewayClass` in `gateway.className`. Traefik is the example these pages use, never a requirement.
What the cluster must have **before** `helm install` — the chart installs no CRD and nothing cluster-scoped:

- **Kubernetes 1.31 or later** (the chart's `kubeVersion`; the floor the Gateway API v1.5 CRDs were tested on).
- **The Gateway API CRDs, v1.5 or later, standard channel.** Without them `helm install` fails whole, before it
  creates anything.
- **A Gateway API implementation** serving the class you name. Find the address it gives the `Gateway` with
  `kubectl -n veyq get gateway veyq`, and point every host's DNS there. **Traefik** attaches a `Gateway` listener
  to the entrypoint whose *container* port is the listener's port, and the chart's `Gateway` listens on 80 and 443
  while Traefik's Helm chart defaults its entrypoints to 8000 and 8443: with those defaults the `Gateway` is never
  programmed. Set `ports.web.port: 80` and `ports.websecure.port: 443` (and the same `exposedPort`s) in Traefik's
  values, plus the pod sysctl `net.ipv4.ip_unprivileged_port_start: "0"` under `podSecurityContext.sysctls`, so its
  unprivileged process may bind them ([Set up a new instance](/run/new-instance/#install) has the snippet; measured
  with Traefik 3.7.17 on k3s, 2026-11-05). With `kubectl -n veyq get gateway veyq` the `Gateway` then shows
  `PROGRAMMED True`.
- **Under `per-domain` TLS, an implementation that supports `ListenerSet`.** Every tenant domain gets its listener
  that way. Not every released implementation does yet: Traefik merged it for 3.8, while its latest release when
  these pages were written (3.7.15, 2026-10-15) ignores `ListenerSet`s — there the chart's own hosts get their
  certificates and no tenant domain does. Check your implementation's Gateway API conformance before choosing
  `per-domain`.

**Reachability is default-deny, and you name the gateway's data plane** ([ADR
0071](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0071-runtime-hardening-on-both-targets.md)). The chart renders a NetworkPolicy set that
admits only the paths the stack uses. The pods that carry the traffic are your implementation's — some run one
shared data plane in their own namespace, others start a proxy per `Gateway` in this release's namespace — so the
chart never guesses them: an install without `networkPolicy.ingressFrom` fails and names the key (`ingressFrom` is
the NetworkPolicy direction, not the Kubernetes kind). For Traefik installed by its Helm chart into `traefik`:

```yaml
networkPolicy:
  ingressFrom:
    - namespaceSelector: {matchLabels: {kubernetes.io/metadata.name: traefik}}
      podSelector: {matchLabels: {app.kubernetes.io/name: traefik}}
```

 **A CNI that does not enforce
NetworkPolicy renders the set and enforces none of it.** On such a cluster, or if you write your own policies, set
`networkPolicy.enabled: false`, which removes every policy the chart renders, including the one on the admin
listener. Without an enforced policy any pod can reach the app and write the whole `X-Forwarded-For` header, so
the rate limits can key on a forged address; the install notes say so
([Networking](/run/networking/#the-client-address-and-the-rate-limits)). `app.trustedProxies` stays unset
unless a load balancer sits in front of your gateway: it lists the hops in front of the gateway, never the
gateway's own data plane. A pod-IP range wide enough to survive a data-plane pod's restart trusts every pod beside
it. Whether the application learns a client's source port is `app.clientPort.source`: the PROXY protocol from your
gateway to the app's routes alone (never the store's), or a header your edge overwrites, configured both on your
gateway and here ([Networking](/run/networking/#where-the-source-port-comes-from)).

**The bundled store is opt-in here** (`garage.enabled`, `false` by default; [ADR
0064](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0064-kubernetes-helm-deployment-target.md)). The store
itself is not a value. Off, the Server Owner enters an S3-compatible store of your own in the admin console
(**Server → Object store**) after the install and checks it; on, the app sets the bundled store itself once it has set
Garage up, and checks it. No tenant is created before a store passes. Off, the chart renders
nothing of Garage and the console offers no bundled store; the backup gate then reads that store through
`backup.store.existingSecret` ([Backup and restore](/run/backup-and-restore/#under-kubernetes)).

Turn it on only knowing what it is on a cluster: **one Garage node on one claim**, with no replication and no object
versions, so keeping its objects is your volume snapshots' job; and a host of its own, `storeDomain`, with its DNS,
its certificate and its listener. With `garage.enabled: true`, set `storeDomain` and generate its secrets the same
way as for compose: `openssl rand -hex 32` for `secrets.garageRpcSecret` (exactly 64 hex characters) and a long random
value for `secrets.garageAdminToken`. The app's S3 key pair is derived from the admin token, so the chart carries no
access key, and the app sets the bundled store itself; the console offers **Use the bundled store** to return to it. An operator who keeps key material out of values
files names an existing `Secret` in `secrets.existingSecret` instead. Turning it off again under an instance whose
store is the bundled one is not refused: the app then names the cause, and Garage's claims stay behind, so `true`
again restores it. **Deleting those claims deletes the objects.**

**The admin listener has a `Service` of its own, `veyq-app-admin`, and a `NetworkPolicy` keeps every other pod off
it.** It is deliberately not a second port on `veyq-app`: that Service is what the chart's `HTTPRoute`, every
projected tenant route and any route of yours aim at, so a port on it would sit behind all of them. The
`veyq-app` policy admits the public port from `networkPolicy.ingressFrom` and the admin port from the admin pod
alone. **A CNI that
does not enforce NetworkPolicy leaves the admin port reachable from any pod in the cluster** — the Service
separation still keeps every route off it, but in-cluster reachability is then only as narrow as your cluster
([ADR 0065](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0065-admin-ui-cli-separate-service.md) residual (ii)). Every pod passes the `restricted` PodSecurity standard — the admin image runs
unprivileged for exactly this — so the namespace can carry that label from the start. Every container also has a
read-only root filesystem.

**Up to three hosts, one of them mandatory.** `domain` is the instance (the compose file's `VEYQ_DOMAIN`);
with `garage.enabled`, `storeDomain` is the bundled store on **its own host** — not a path under `domain`, because an operator-chosen
gateway implementation is not guaranteed to preserve the prefix and `Host` header a SigV4 signature needs, so
presigned URLs are signed against the store's own origin (`store-origin` signing,
[ADR 0067](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0067-store-provisioning-from-a-declared-type.md) Decision 5) under the `*` CORS
policy the app writes on first start ([ADR 0013 §4](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0013-blob-storage-presigned-direct-to-store.md)
measures why `*` is the only policy the bundled store can express for many tenants). `admin.domain` is the
optional third.

**TLS is a passthrough, and it is a precondition here as much as under compose.** The app only ever stamps a
reference: which listener a route attaches to, chosen by a named mode, `gateway.tls.mode`, and under `per-domain` a
certificate name plus `gateway.annotations` verbatim on every tenant `ListenerSet` it writes. The chart's `Gateway`
carries the same annotations and follows the same mode for its own hosts. The issuer that reference names is yours,
or — opted in — the one the chart templates (below):

- `none` (the default, so that nothing moves): the `Gateway` listens on plain HTTP only, and every route attaches
  there. Right where TLS terminates upstream of the gateway. Wrong everywhere else — the refresh cookie is `Secure`
  and every mailed link is `https`, so an instance nothing terminates TLS for boots, serves every page, and silently
  breaks sessions.
- `per-domain`: an HTTPS listener per host whose certificate `Secret` is named after the host's route — `veyq-app`,
  `veyq-garage` (with `garage.enabled`) and `veyq-admin` on the `Gateway`, and for each tenant a `ListenerSet` of its own under the tenant's
  object name. A listener on the `Gateway` wins a hostname conflict against any `ListenerSet`, so a tenant cannot
  take the instance's, the store's or the admin host. Every tenant domain — a custom domain included — gets its own
  certificate with no chart edit and no restart, from `gateway.tls.acme`'s issuer or, bringing your own, from the
  one `cert-manager.io/cluster-issuer: <issuer>` in the annotations names. Test a deployment against a **staging**
  directory: each new tenant is a certificate order, and what Let's Encrypt production limits is not what it
  sounds like — 5 **failed** validations per hostname lock that hostname out for an **hour**, while the week is a
  cap of 50 **issued** certificates per registered domain ([rate limits](https://letsencrypt.org/docs/rate-limits/)).
  Residue worth knowing: when a tenant is suspended and its `ListenerSet`
  withdrawn, cert-manager does **not** delete the `Secret` it issued unless it runs with
  `--enable-certificate-owner-ref`. A withdrawn domain therefore leaves an orphaned TLS `Secret` in the
  namespace — visible to you, not a routing leak (the listener is gone) and not an access leak (a suspended
  tenant is locked out by the app's scope-access gate, not by routing). The chart does not sweep them, because
  sweeping `Secret`s would need the RBAC this design exists to avoid.
- `shared`: one HTTPS listener with no hostname, using the one `Secret` you name in `gateway.tls.secretName` — a
  wildcard certificate you obtained yourself — and every route attaches to it. A working instance with no ACME and
  no `ListenerSet` at all, at the price of per-tenant custom domains: a custom domain routes and the browser refuses
  it. The app refuses `shared` without a secret name — loud, and with tenant routing disabled — rather than
  projecting routes without TLS.

Under `per-domain` and `shared` the chart also redirects port 80 to `https` with an `HTTPRoute` of its own
(`veyq-https-redirect`). Its `scheme` field is a Gateway API *Extended* feature (`HTTPRouteSchemeRedirect`):
Traefik 3.7 supports it, and an implementation that does not leaves port 80 unredirected while HTTPS still serves.

**The ACME opt-in: the chart's counterpart of Traefik's resolver under Compose.** Set `gateway.tls.acme.enabled:
true` and `mode: per-domain` — no account e-mail: Let's Encrypt keeps no contact since June 2025 — and the chart templates one namespaced cert-manager `Issuer`, named for its
directory — `veyq-acme-production`, `veyq-acme-staging`, or `veyq-acme-<hash>` for another CA — (ACME HTTP-01, its
solver route on the `Gateway`'s port-80 listener), and adds `cert-manager.io/issuer: <that name>` and
`acme.cert-manager.io/http01-parentreffallback: "true"` to the annotation map — so they reach every tenant
`ListenerSet` the app projects later, exactly as a resolver reference does under compose, and each tenant's
challenge is answered on the `Gateway`'s one port-80 listener. Switching `gateway.tls.acme.server` (from staging to
production, say) renames the `Issuer`, so cert-manager re-orders every certificate from the new directory by itself and
keeps serving the old ones until the new are Ready; a server URL changed under one unchanged name would not have
re-ordered anything.

```yaml
gateway:
  className: traefik
  tls:
    mode: per-domain
    acme:
      enabled: true
      email: ops@example.org
      # server: https://acme-staging-v02.api.letsencrypt.org/directory   # while testing; default is production
```

- **Preconditions the chart cannot meet for you.** cert-manager **1.21 or later** runs in the cluster — the
  parent-ref fallback annotation first ships in 1.21 — with its Gateway API support **and** its `ListenerSet`
  support switched on. The second is **alpha**, so per-tenant certificates rest on an alpha feature until it
  graduates. In cert-manager's Helm values:

  ```yaml
  config:
    gatewayAPI:
      enabled: true
      enableListenerSet: true
    featureGates:
      ListenerSets: true
  ```

  The chart does not install cert-manager (its CRDs and `ClusterRole` are cluster-wide), and without it `helm
  install` fails whole, before creating anything. Your gateway implementation supports `ListenerSet` (above). Port
  80 of every host reaches the `Gateway`, since HTTP-01 answers there. A tenant's DNS points here **before** the
  tenant is created: the creation is the order, and cert-manager checks reachability itself before it asks the CA
  to validate, so an order for a name that does not resolve yet waits there.
- **`server` defaults to Let's Encrypt production**, as `VEYQ_ACME_CASERVER` does; any ACME CA is one URL. A
  staging certificate is not browser-trusted — an instance left on staging boots and every browser refuses it. The
  app trusts it no more: with `garage.enabled`, its check of the bundled store fails (a PKIX error in its log) while
  `storeDomain` has a staging certificate, retries with backoff, and passed about a minute after production
  certificates replaced it (k3s, 2026-11-05). cert-manager keeps an issued certificate when you only change
  `server`: delete the issued `Secret`s (`veyq-app`, `veyq-garage`, `veyq-admin`) so it orders new ones.
- **Refused, not resolved:** `values.schema.json` rejects the opt-in with any mode but `per-domain`, with no
  e-mail, or beside an issuer annotation of your own — two issuers on one object would be cert-manager's
  choice, not yours.
- **Lifecycle.** `helm uninstall` removes the `Issuer` and the `Gateway`. The `Certificate` cert-manager creates
  for a listener is owned by the `Gateway` or `ListenerSet` that carries it, so a tenant's goes when its domain is
  withdrawn; the issued `Secret` stays (the residue above). The ACME account key (`<issuer>-account-key`, one per directory) is
  cert-manager's `Secret`, not the chart's, and may remain after an uninstall.

**What the app's cluster identity can and cannot do.** A namespace-local `ServiceAccount` + `Role` with the
verbs on `httproutes` and `listenersets`, never a `ClusterRole`, and **nothing on `gateways`**: the app manages
tenant routing in its own namespace and nowhere else, and it can neither add nor change a listener on the chart's
`Gateway` — a tenant domain reaches the network only through a `ListenerSet` that `Gateway` admits, and it admits
them from this namespace alone. The namespace is read from the mounted token, so the same image serves every
install; do not set `veyq.tenant.ingress.kubernetes.namespace` in values, it is the outside-a-cluster override.

**The config-secret key lives on a `PersistentVolumeClaim`, and the claim is kept.** Same loss, same place as
under compose: without the key the instance boots and then refuses invites and resets. The claim
(`veyq-config-secret`) is annotated `helm.sh/resource-policy: keep`, so `helm uninstall` leaves it behind (and for
Argo CD, which ignores that policy, `argocd.argoproj.io/sync-options: Prune=false,Delete=false`; see
[below](#deploying-with-argo-cd)), and
the pod sets `fsGroup` to the image's runtime user because a claim comes up root-owned and — unlike a Docker
named volume — inherits nothing from the image. Or give it your own key as a Secret, `app.configSecret.existingSecret`,
which the app reads read-only; the claim then keeps only the erasure ledger
([Storage and email](/run/storage-and-email/#the-config-secret-key)). `scripts/chart_meta_check.py` holds that to the `Dockerfile`
(and the rest of the chart's structure), outside the buildless guard glob because it renders the chart with
`helm`. It checks the rendered manifests only. On a cluster the chart ran once, by the owner (2026-11-05:
`0.2265-SNAPSHOT` on single-node k3s with Traefik 3.7.17, cert-manager 1.21.3 and Argo CD 3.5.7, `per-domain` with the
ACME opt-in and `garage.enabled`): the `Gateway` was programmed, cert-manager issued certificates for the instance's
and the store's host, the app migrated and set up the bundled store, and the store passed its check. No tenant
domain, and so no `ListenerSet`, is recorded from that run; Traefik 3.7 ignores `ListenerSet`s anyway (above).

**Backups are a choice the install makes, and a claim you create** ([ADR
0098](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md)).
With `backup.enabled` (the default) the app pod runs an init container, `backup-gate`, from the third image of the
release, `veyq-tools`, before the container that migrates: when the pod starts a different release than the
database holds, it captures the database and an index of the store onto `backup.existingClaim`. The chart creates
no claim and refuses an install that names none and does not say `backup.enabled: false`, naming `backup`. The gate
reaches the database and the store as the app does — it is the app pod, so the NetworkPolicy set gains no path —
and never mounts the config-secret claim, so no capture holds the key; export the key once yourself. Every capture
older than B — the instance's backup retention, 30 days until the Server Owner sets it — is deleted at every start and
by a daily CronJob, `veyq-backup-prune` (`backup.prune.schedule`), on the gate's image and claim: the one pod the
chart adds for it, which reaches the database to read B (its own NetworkPolicy, and the database's admits it) and
writes only the claim, on the app pod's node. What it captures, refuses and keeps, and the restore Job: [Upgrade](/run/upgrade/) and [Backup and
restore](/run/backup-and-restore/#under-kubernetes). The values are on [Configuration](/run/configuration/).
The gate ran once on a kind cluster in a `restricted` namespace (2026-10-17), with its claim on kind's local-path
provisioner, which ignores `fsGroup`; on a storage class that applies it, the claim's directory is the pod's group's.
On a first install it waits, bounded, for the bundled MariaDB that starts beside it, rather than failing until the
database is up.

## Deploying with Argo CD

[`veyqctl`](/run/gitops/) writes veyq's part of the repository Argo CD syncs — the `Application`, the values, the
namespace, the backup claim and the Secrets — with everything below already set, and upgrades it by commit.

Argo CD renders the chart with `helm template` and applies the result itself, so two things differ from `helm
install` (both seen on Argo CD 3.5.7, 2026-11-05):

- **Compare on the server's side.** The API server fills defaults into the `Gateway`, the `HTTPRoute`s and the
  StatefulSets (a `backendRef`'s `group`, `kind` and `weight`; a `volumeClaimTemplate`'s fields) that the rendered
  manifests leave out, so with Argo CD's default diff those objects stay `OutOfSync` for good. Annotate the
  `Application` with `argocd.argoproj.io/compare-options: ServerSideDiff=true`; that run also synced with
  `ServerSideApply=true`.
- **Helm's `keep` does not apply.** Argo CD ignores `helm.sh/resource-policy: keep`, so it is not what protects the
  config-secret claim there. The chart also annotates the claim `argocd.argoproj.io/sync-options:
  Prune=false,Delete=false`: a sync never prunes it, and deleting the `Application` with its resources leaves it
  behind (Argo CD's documented sync options, not yet run on a cluster). A chart published before that annotation —
  `0.2265-SNAPSHOT` and older — carries only Helm's: there the claim is safe from a sync only with pruning off
  (`syncPolicy.automated.prune: false`, the run's setting, or `Prune=false` in the `Application`'s sync options), and
  deleting the `Application` with its resources (its `resources-finalizer.argocd.argoproj.io` finalizer, or a
  cascading `argocd app delete`) deletes it. With `app.configSecret.existingSecret` the key is in a `Secret` of your
  own, which your GitOps tool owns, and the claim holds the erasure ledger alone.

The backup gate is part of the Deployment, so a sync runs it as `helm upgrade` does ([Upgrade](/run/upgrade/)); a
restore pauses automated sync first ([Backup and restore](/run/backup-and-restore/#under-kubernetes)).
