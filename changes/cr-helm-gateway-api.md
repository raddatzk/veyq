---
id: cr-helm-gateway-api
title: The Helm chart and the backend's Kubernetes sink route through the Gateway API instead of Ingress
status: planned
satisfies: ["uc.admin.tenant-reachability"]
adrs: ["0064", "0063", "0071", "0065"]
invariants:
  - "Self-hostable by default: the chart stays implementation-neutral (any GatewayClass), installs no CRD and no cluster-scoped object, and the backend's Role stays namespace-local"
  - "No deprecated dependencies: no rendered object, value, example or doc names the retired ingress-nginx controller; Traefik is the example, never a requirement"
  - "Operator-blind E2EE: unaffected — routing and TLS objects carry hostnames and Secret names only"
---

## Goal

Replace every Kubernetes `Ingress` veyq writes — the chart's three and the backend's one per tenant — with Gateway
API objects, as [ADR 0064](../docs/decisions/0064-kubernetes-helm-deployment-target.md) now decides: a chart-owned
`Gateway`, an `HTTPRoute` per host, and under `per-domain` TLS a `ListenerSet` per tenant domain. Nothing is kept
for `Ingress`: the chart has no release, so no install migrates.

## Context & constraints

- **The decision lives in ADR 0064** (routing, projection, TLS and preconditions bullets). This CR does not re-decide
  anything there; a disagreement found while building is a question back to the owner.
- **Measured at refine (2026-10-16, branch claude/intelligent-payne-de33d2 at 5e4ed9e1):**
  - fabric8 `kubernetes-model-gatewayapi` 7.7.0 is in `backend/gradle/verification-metadata.xml`, and its jar has
    `gatewayapi/v1/{Gateway,HTTPRoute,ListenerSet,ReferenceGrant}.class` (`unzip -l` on the Gradle-cache jar). That
    it is on `:core`'s *runtime* classpath through `quarkus-kubernetes-client` is remembered, not measured — check it
    with `./gradlew :core:dependencies --configuration runtimeClasspath | grep gatewayapi` before writing the sink.
  - The sink seam exists: `TenantIngressSinkProducer.ingressSink()` is a total `when` over `IngressSinkType`, and the
    projector never branches on the implementation. The change is one sink's body plus its config, not the projector.
  - `charts/veyq/ci/` has `lint-values.yaml` and `acme-values.yaml`, both rendered by `chart_meta_check.py`; both name
    ingress-nginx in `networkPolicy.ingressFrom` and `ingress.className: nginx`.
- **External facts the build relies on (measured on the web at refine, re-check at implement):** Gateway API v1.5.0
  (2026-02-27) has `ListenerSet` GA in the standard channel, and a `Gateway` admits `ListenerSet`s only through
  `spec.allowedListeners`. cert-manager reads `cert-manager.io/issuer` on a `Gateway` and on a `ListenerSet` (the
  latter alpha in 1.20, feature gate `ListenerSets`), needs a listener with `hostname`, `tls.mode: Terminate` and a
  same-namespace `certificateRefs` name, and `acme.cert-manager.io/http01-parentreffallback: "true"` puts a
  `ListenerSet`'s HTTP-01 route on the parent `Gateway`. Traefik's ListenerSet support was merged 2026-09-28 for v3.8;
  whether that has been released is unknown — the Run pages must not claim it.
- **Remembered, verify when building:** Gateway API's route precedence ranks a route naming a host and an exact path
  (cert-manager's challenge) above a host-less redirect route on the same listener; the minimum Kubernetes version the
  v1.5 CRDs support (sets `Chart.yaml`'s `kubeVersion`, today `>=1.25.0-0`); whether cert-manager owns the
  `Certificate` it creates for a `ListenerSet` by that `ListenerSet` (the orphaned-`Secret` residue is stated either way).
- **Names stay where only the API changes.** The chart's objects keep `veyq-app`, `veyq-garage`, `veyq-admin`; the
  tenant objects keep `veyq-tenant-<domain>` (digest past 253) and the label value `veyq-tenant-ingress`, so the
  ownership constant `chart_meta_check.py` (i) reads does not move. `IngressSinkType.KUBERNETES` and the
  `veyq.tenant.ingress.*` prefix stay: "ingress" there is ADR 0063's projection, not the Kubernetes kind. The class
  `KubernetesIngressSink` is renamed `KubernetesGatewaySink`, and its tests with it, because the name would now lie.
- **Config:** `veyq.tenant.ingress.kubernetes.ingress-class-name` goes; `…kubernetes.gateway-name` (the parent
  `Gateway`, same namespace) comes. Chart values move from `ingress.*` to `gateway.*` (`className`, `annotations`,
  `tls.mode`, `tls.secretName`, `tls.acme.*`). `networkPolicy.ingressFrom` keeps its name: there "ingress" is the
  NetworkPolicy direction, and its peers are now the gateway's data-plane pods (which some implementations run in
  the `Gateway`'s own namespace — the values comment says so).

### Inventory — the locators

Run both; the second found seven files the first missed at refine (the unreachable and shared-without-secret tests,
`app-configmap.yaml`, `validate_run_config_reference.py`, `hardening.md`, `plan.md`, `docs/inbox.md`).

```bash
grep -rlE "\bIngress\b|ingressClass|IngressClass|networking\.k8s\.io|ingress-shim|ingress-nginx" charts scripts site/src/content docs backend infra
```

```bash
grep -rlE "ingress\.(className|annotations|tls)|ingress-class-name|kubernetes\.(tls|annotations|ingress)|IngressTlsMode|KubernetesIngressSink" charts scripts site/src/content docs backend .github
```

| Area | What changes |
|---|---|
| `backend/core/.../tenant/KubernetesIngressSink.kt` → `KubernetesGatewaySink.kt` | Per domain an `HTTPRoute` (host → app `Service`) and, under `per-domain`, a `ListenerSet` (one HTTPS listener, `certificateRefs` = own name, the annotation map) the route attaches to; under `none` the route's parent is the `Gateway`'s HTTP listener, under `shared` its HTTPS listener (`sectionName`). Lists **each** kind by the owner label and deletes stale ones of each; the whole reconcile stays inside the one `try`. KDoc follows. |
| `TenantIngressSinkProducer.kt`, `TenantIngressSink.kt` (KDoc), `VeyqConfig.kt`, `application.properties` | `gateway-name` in, `ingress-class-name` out; the producer's disabled-with-error paths unchanged. |
| backend tests: `KubernetesIngressSink*Test`, `KubernetesIngressTls*Test`, and the `\bIngress\b` hits in `AppShellTest`, `StoreOriginPresignSliceTest`, `S3BlobStorePresigningTest` | Renamed and ported. **Keep the defences**: an unowned `HTTPRoute` *and* an unowned `ListenerSet` survive a regenerate; a withdrawn domain's route and `ListenerSet` both go (the deletion bite); `none`/`shared` write no `ListenerSet` (with a positive control that `per-domain` writes one); annotations land on the `ListenerSet` and on no `HTTPRoute`. |
| `charts/veyq/templates/`: `app-ingress.yaml`, `admin.yaml`, `garage.yaml`, `_helpers.tpl`, `issuer.yaml`, `app-rbac.yaml`, `app-configmap.yaml`, `app-deployment.yaml`, `app-service.yaml`, `networkpolicy.yaml`, `NOTES.txt` | A new `gateway.yaml` (`Gateway` `veyq`: HTTP :80 without hostname, an HTTPS listener per chart host by mode, `allowedListeners` same-namespace, the annotation map); the three `Ingress` become `HTTPRoute`s; one redirect `HTTPRoute` on :80 under `per-domain`/`shared`; the `Issuer`'s solver becomes `gatewayHTTPRoute` with the `Gateway`'s HTTP listener as parent; the annotation helper adds the parent-ref-fallback annotation when ACME is on; the Role moves to `httproutes` + `listenersets` and grants nothing on `gateways`; comments that say Ingress follow. |
| `charts/veyq/values.yaml`, `values.schema.json`, `ci/*.yaml`, `Chart.yaml` | `ingress.*` → `gateway.*`; the ingress-nginx examples become Traefik's; `kubeVersion` per the v1.5 CRDs. |
| `scripts/chart_meta_check.py` | Every assertion over `Ingress` re-stated over the new kinds — (i) ownership value on no rendered object of any kind, (vi)/(xii) no route reaches the admin listener's Service, (vii)/(ix) ACME trace absent/present on the `Gateway`, (xi)/(xviii) refusal paths `/gateway/…`, `SINK_KT` to the renamed class — plus: **no rendered object is a `networking.k8s.io` `Ingress`**; every rendered `HTTPRoute`'s parent is the chart's `Gateway`, every HTTPS listener's `certificateRefs` is its own derived name under `per-domain`, and the Role names no `gateways`. Each new assertion is bitten by a render that breaks it. |
| `scripts/validate_run_config_reference.py` | Follows the config key rename (it reads the Run configuration reference). |
| ADRs | 0064: delete the "decided, not yet built" paragraph. 0063: the Kubernetes-sink bullet and the bootstrap-domain line say `HTTPRoute`/`ListenerSet`. 0013 §4, 0065, 0067 Decision 5, 0069, 0071 §3/§4: "Ingress host" / "ingress controller" wording where it means the kind. |
| `docs/crosscutting/cc.security.csp.md`, `docs/features/admin/use-cases/uc.admin.tenant-reachability.md`, `uc.admin.setup-server.md`, `docs/coverage/.../uc.admin.tenant-reachability.yaml` | Wording; the coverage file's test names follow the renamed tests. |
| `docs/inbox.md` | Rows naming `KubernetesIngressSink` or `ingress.*` values: repoint, don't rewrite history. |
| Site, Run area: `kubernetes.md`, `networking.md`, `configuration.md`, `new-instance.mdx`, `first-run.mdx`, `index.md`, `plan.md`, `hardening.md`, `troubleshooting.md` | Preconditions (Gateway API CRDs ≥ v1.5 standard channel, a GatewayClass; under `per-domain` ListenerSet support and cert-manager ≥ 1.20 with Gateway API + `ListenerSets` on — alpha, said plainly), the Traefik example, the values rename, the same "only the rendered chart is checked" honesty `networking.md` already states. |

## Phases

1. **Backend sink** — the renamed sink, config, producer and ported tests. `./gradlew check` green in `backend/`.
2. **Chart** — templates, values, schema, ci values, RBAC, NOTES, `chart_meta_check.py` with its bites. Proof:
   `python3 scripts/chart_meta_check.py` green, and `scripts/chart_upgrade_check.py` run and read (information-only
   while `release: none`; it will list the kind changes, which is expected).
3. **Docs** — ADRs, use cases, coverage, inbox rows, site pages. Proof: the `trust-chain` job's validators green.

One conventional commit per phase on `main`, after the owner's go.

## Definition of done

- No rendered object, backend source or Run page names `Ingress` as the kind veyq writes (both locators above come
  back with only ADR 0064's rejected-alternative sentence and ADR 0063's projection sense).
- Backend: the ported sink tests green with their controls; `chart_meta_check.py` green with each new assertion
  shown to fail on a breaking render.
- The Role grants nothing on `gateways`, `ingresses` or any cluster-scoped resource.
- Stated, not hidden: no `Gateway`, `ListenerSet` or certificate order has run in a cluster (inbox #430's kind leg
  is where that becomes observable).

## Retrospective
