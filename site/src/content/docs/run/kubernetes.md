---
title: Kubernetes
description: What is specific to running veyq from its Helm chart.
sources:
  - docs/decisions/0064-kubernetes-helm-deployment-target.md
  - docs/decisions/0071-runtime-hardening-on-both-targets.md
  - docs/decisions/0094-a-clients-address-is-the-hop-the-network-vouches-for.md
  - docs/decisions/0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md
---

The install itself — `helm install` by the digests you verified, and the Server Owner created over a
port-forward — is on [Set up a new instance](/veyq/run/new-instance/#install), beside the Docker Compose one. This
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
  `kubectl -n veyq get gateway veyq`, and point every host's DNS there.
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
([Networking](/veyq/run/networking/#the-client-address-and-the-rate-limits)). `app.trustedProxies` stays unset
unless a load balancer sits in front of your gateway: it lists the hops in front of the gateway, never the
gateway's own data plane. A pod-IP range wide enough to survive a data-plane pod's restart trusts every pod beside
it. Whether the application learns a client's source port is `app.clientPort.source`: the PROXY protocol from your
gateway to the app's routes alone (never the store's), or a header your edge overwrites, configured both on your
gateway and here ([Networking](/veyq/run/networking/#where-the-source-port-comes-from)).

Generate the bundled store's secrets the same way as for compose: `openssl rand -hex 32` for `garageRpcSecret` (exactly
64 hex characters) and a long random value for `garageAdminToken`. The app's S3 key pair is derived from the admin token, so
the chart carries no access key. The store itself is not a value: after the install, the Server Owner chooses the
bundled store in the admin console (**Server → Object store**) or enters an S3-compatible one there, and checks it. An
operator who keeps key material out of values files names an existing `Secret` in `secrets.existingSecret` instead.

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

**Three hosts, two of them mandatory.** `domain` is the instance (the compose file's `VEYQ_DOMAIN`);
`storeDomain` is the object store on **its own host** — not a path under `domain`, because an operator-chosen
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
  `veyq-garage` and `veyq-admin` on the `Gateway`, and for each tenant a `ListenerSet` of its own under the tenant's
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

**The ACME opt-in: the chart's counterpart of `VEYQ_ACME_EMAIL`.** Set `gateway.tls.acme.enabled: true`, an
`email` and `mode: per-domain`, and the chart templates one namespaced cert-manager `Issuer`, `veyq-acme` (ACME
HTTP-01, its solver route on the `Gateway`'s port-80 listener), and adds `cert-manager.io/issuer: veyq-acme` and
`acme.cert-manager.io/http01-parentreffallback: "true"` to the annotation map — so they reach every tenant
`ListenerSet` the app projects later, exactly as a resolver reference does under compose, and each tenant's
challenge is answered on the `Gateway`'s one port-80 listener.

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
  staging certificate is not browser-trusted — an instance left on staging boots and every browser refuses it.
- **Refused, not resolved:** `values.schema.json` rejects the opt-in with any mode but `per-domain`, with no
  e-mail, or beside an issuer annotation of your own — two issuers on one object would be cert-manager's
  choice, not yours.
- **Lifecycle.** `helm uninstall` removes the `Issuer` and the `Gateway`. The `Certificate` cert-manager creates
  for a listener is owned by the `Gateway` or `ListenerSet` that carries it, so a tenant's goes when its domain is
  withdrawn; the issued `Secret` stays (the residue above). The ACME account key (`veyq-acme-account-key`) is
  cert-manager's `Secret`, not the chart's, and may remain after an uninstall.

**What the app's cluster identity can and cannot do.** A namespace-local `ServiceAccount` + `Role` with the
verbs on `httproutes` and `listenersets`, never a `ClusterRole`, and **nothing on `gateways`**: the app manages
tenant routing in its own namespace and nowhere else, and it can neither add nor change a listener on the chart's
`Gateway` — a tenant domain reaches the network only through a `ListenerSet` that `Gateway` admits, and it admits
them from this namespace alone. The namespace is read from the mounted token, so the same image serves every
install; do not set `veyq.tenant.ingress.kubernetes.namespace` in values, it is the outside-a-cluster override.

**The config-secret key lives on a `PersistentVolumeClaim`, and the claim is kept.** Same loss, same place as
under compose: without the key the instance boots and then refuses invites and resets. The claim
(`veyq-config-secret`) is annotated `helm.sh/resource-policy: keep`, so `helm uninstall` leaves it behind, and
the pod sets `fsGroup` to the image's runtime user because a claim comes up root-owned and — unlike a Docker
named volume — inherits nothing from the image. `scripts/chart_meta_check.py` holds that to the `Dockerfile`
(and the rest of the chart's structure), outside the buildless guard glob because it renders the chart with
`helm`. It checks the rendered manifests only: no `Gateway`, `ListenerSet` or certificate order of this chart has
run in a cluster yet.

**Backups are a choice the install makes, and a claim you create** ([ADR
0098](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md)).
With `backup.enabled` (the default) the app pod runs an init container, `backup-gate`, from the third image of the
release, `veyq-tools`, before the container that migrates: when the pod starts a different release than the
database holds, it captures the database and an index of the store onto `backup.existingClaim`. The chart creates
no claim and refuses an install that names none and does not say `backup.enabled: false`, naming `backup`. The gate
reaches the database and the store as the app does — it is the app pod, so the NetworkPolicy set gains no path —
and never mounts the config-secret claim, so no capture holds the key; export the key once yourself. What it
captures, refuses and keeps, and the restore Job: [Upgrade](/veyq/run/upgrade/) and [Backup and
restore](/veyq/run/backup-and-restore/#under-kubernetes). The values are on [Configuration](/veyq/run/configuration/).
The gate ran once on a kind cluster in a `restricted` namespace (2026-10-17), with its claim on kind's local-path
provisioner, which ignores `fsGroup`; on a storage class that applies it, the claim's directory is the pod's group's.
