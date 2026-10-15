---
title: Kubernetes
description: What is specific to running veyq from its Helm chart.
sources:
  - docs/decisions/0064-kubernetes-helm-deployment-target.md
  - docs/decisions/0071-runtime-hardening-on-both-targets.md
---

The install itself — `helm install` by the digests you verified, and the Server Owner created over a
port-forward — is on [Set up a new instance](/veyq/run/new-instance/#install), beside the Docker Compose one. This
page is what is specific to the chart.

**Reachability is default-deny, and you name the ingress controller** ([ADR
0071](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0071-runtime-hardening-on-both-targets.md)). The chart renders a NetworkPolicy set that
admits only the paths the stack uses. The controller's namespace and labels are yours, so the chart never guesses
them: an install without `networkPolicy.ingressFrom` fails and names the key. **A CNI that does not enforce
NetworkPolicy renders the set and enforces none of it.** On such a cluster, or if you write your own policies, set
`networkPolicy.enabled: false`, which removes every policy the chart renders, including the one on the admin
listener. `app.trustedProxies` stays unset unless your proxy has a stable address, such as a host-network
controller or an external load balancer. A pod-IP range wide enough to survive a controller restart trusts every
pod beside it.

Generate the bundled store's secrets the same way as for compose: `openssl rand -hex 32` for `garageRpcSecret` (exactly
64 hex characters) and a long random value for `garageAdminToken`. The app's S3 key pair is derived from the admin token, so
the chart carries no access key. The store itself is not a value: after the install, the Server Owner chooses the
bundled store in the admin console (**Server → Store**) or enters an S3-compatible one there, and checks it. An
operator who keeps key material out of values files names an existing `Secret` in `secrets.existingSecret` instead.

**The admin listener has a `Service` of its own, `veyq-app-admin`, and a `NetworkPolicy` keeps every other pod off
it.** It is deliberately not a second port on `veyq-app`: that Service is what the chart's `Ingress`, every
projected tenant `Ingress` and any `Ingress` of yours aim at, so a port on it would sit behind all of them. The
`veyq-app` policy admits the public port from `networkPolicy.ingressFrom` and the admin port from the admin pod
alone. **A CNI that
does not enforce NetworkPolicy leaves the admin port reachable from any pod in the cluster** — the Service
separation still keeps every Ingress off it, but in-cluster reachability is then only as narrow as your cluster
([ADR 0065](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0065-admin-ui-cli-separate-service.md) residual (ii)). Every pod passes the `restricted` PodSecurity standard — the admin image runs
unprivileged for exactly this — so the namespace can carry that label from the start. Every container also has a
read-only root filesystem.

**Three hosts, two of them mandatory.** `domain` is the instance (the compose file's `VEYQ_DOMAIN`);
`storeDomain` is the object store on **its own host** — not a path under `domain`, because an operator-chosen
ingress controller is not guaranteed to preserve the prefix and `Host` header a SigV4 signature needs, so
presigned URLs are signed against the store's own origin (`store-origin` signing,
[ADR 0067](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0067-store-provisioning-from-a-declared-type.md) Decision 5) under the `*` CORS
policy the app writes on first start ([ADR 0013 §4](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0013-blob-storage-presigned-direct-to-store.md)
measures why `*` is the only policy the bundled store can express for many tenants). `admin.domain` is the
optional third.

**TLS is a passthrough, and it is a precondition here as much as under compose.** The app only ever stamps a
reference: `ingress.annotations` verbatim on every `Ingress` — the chart's own three, and every tenant `Ingress`
the app writes at runtime — and `spec.tls` by a named mode, `ingress.tls.mode`. The issuer that reference names is
yours, or — opted in — the one the chart templates (below):

- `none` (the default, so that nothing moves): no `spec.tls` anywhere. Right where TLS terminates upstream of
  the ingress controller. Wrong everywhere else — the refresh cookie is `Secure` and every mailed link is
  `https`, so an instance nothing terminates TLS for boots, serves every page, and silently breaks sessions.
- `per-domain`: one `spec.tls` per `Ingress` whose `secretName` is the object's own name, which is
  cert-manager's ingress-shim shape. Every tenant domain — a custom domain included — gets its own certificate
  with no chart edit and no restart, from `ingress.tls.acme`'s issuer or, bringing your own, from the one
  `cert-manager.io/cluster-issuer: <issuer>` in the annotations names. Test a deployment against a **staging**
  directory: each new tenant is a certificate order, and what Let's Encrypt production limits is not what it
  sounds like — 5 **failed** validations per hostname lock that hostname out for an **hour**, while the week is a
  cap of 50 **issued** certificates per registered domain ([rate limits](https://letsencrypt.org/docs/rate-limits/)).
  Residue worth knowing: when a tenant is suspended and its `Ingress`
  withdrawn, cert-manager does **not** delete the `Secret` it issued unless it runs with
  `--enable-certificate-owner-ref`. A withdrawn domain therefore leaves an orphaned TLS `Secret` in the
  namespace — visible to you, not a routing leak (the `Ingress` is gone) and not an access leak (a suspended
  tenant is locked out by the app's scope-access gate, not by routing). The chart does not sweep them, because
  sweeping `Secret`s would need the RBAC this design exists to avoid.
- `shared`: every `Ingress` points at the one `Secret` you name in `ingress.tls.secretName` — a wildcard
  certificate you obtained yourself. A working instance with no ACME at all, at the price of per-tenant custom
  domains: a custom domain routes and the browser refuses it. The app refuses `shared` without a secret name
  — loud, and with tenant routing disabled — rather than projecting routes without TLS.

**The ACME opt-in: the chart's counterpart of `VEYQ_ACME_EMAIL`.** Set `ingress.tls.acme.enabled: true`, an
`email` and `mode: per-domain`, and the chart templates one namespaced cert-manager `Issuer`, `veyq-acme` (ACME
HTTP-01, its solver on `ingress.className`), and adds `cert-manager.io/issuer: veyq-acme` to the annotation map —
so it reaches every tenant `Ingress` the app projects later, exactly as a resolver reference does under compose.

```yaml
ingress:
  className: nginx
  tls:
    mode: per-domain
    acme:
      enabled: true
      email: ops@example.org
      # server: https://acme-staging-v02.api.letsencrypt.org/directory   # while testing; default is production
```

- **Preconditions the chart cannot meet for you.** cert-manager runs in the cluster — the chart does not install
  it (its CRDs and `ClusterRole` are cluster-wide), and without it `helm install` fails whole, before creating
  anything. Port 80 of every host reaches the ingress controller, since HTTP-01 answers there. A tenant's DNS
  points here **before** the tenant is created: the creation is the order, and cert-manager checks reachability
  itself before it asks the CA to validate, so an order for a name that does not resolve yet waits there.
- **`server` defaults to Let's Encrypt production**, as `VEYQ_ACME_CASERVER` does; any ACME CA is one URL. A
  staging certificate is not browser-trusted — an instance left on staging boots and every browser refuses it.
- **Refused, not resolved:** `values.schema.json` rejects the opt-in with any mode but `per-domain`, with no
  e-mail, or beside an issuer annotation of your own — two issuers on one `Ingress` would be cert-manager's
  choice, not yours.
- **Lifecycle.** `helm uninstall` removes the `Issuer`. The `Certificate` cert-manager creates for an `Ingress`
  is owned by that `Ingress`, so it goes when a tenant's domain is withdrawn; the issued `Secret` stays (the
  residue above). The ACME account key (`veyq-acme-account-key`) is cert-manager's `Secret`, not the chart's,
  and may remain after an uninstall.

**What the app's cluster identity can and cannot do.** A namespace-local `ServiceAccount` + `Role` with the
`Ingress` verbs, never a `ClusterRole`: the app manages routing in its own namespace and nowhere else. The
namespace is read from the mounted token, so the same image serves every install; do not set
`veyq.tenant.ingress.kubernetes.namespace` in values, it is the outside-a-cluster override.

**The config-secret key lives on a `PersistentVolumeClaim`, and the claim is kept.** Same loss, same place as
under compose: without the key the instance boots and then refuses invites and resets. The claim
(`veyq-config-secret`) is annotated `helm.sh/resource-policy: keep`, so `helm uninstall` leaves it behind, and
the pod sets `fsGroup` to the image's runtime user because a claim comes up root-owned and — unlike a Docker
named volume — inherits nothing from the image. `scripts/chart_meta_check.py` holds that to the `Dockerfile`
(and the rest of the chart's structure), outside the buildless guard glob because it renders the chart with
`helm`.
