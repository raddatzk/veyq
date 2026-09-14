---
id: cr-veyq-helm-chart
title: charts/veyq — the Kubernetes deployment target, with per-domain TLS
status: planned
satisfies: [uc.admin.tenant-reachability, uc.admin.setup-server]
adrs: ["0064", "0063", "0013", "0056", "0057", "0065", "0067"]
invariants:
  - Operator-blind E2EE — the chart moves ciphertext-handling config, never key material; the config-secret
    KEK stays a file the app generates on its own volume and is never a chart-rendered value.
  - Self-hostable by default — `helm install ./charts/veyq` with no central infrastructure, no registry
    account, and no cluster-wide RBAC.
  - API-first — no contract changes here; this CR touches no `contracts/`.
---

## Goal

veyq becomes installable on an operator's Kubernetes cluster from `charts/veyq`, covering the same five
services `infra/docker-compose.prod.yml` covers, **including certificates for tenant domains**. ADR 0064
decided the chart and left exactly one thing open — TLS — because a chart cannot annotate `Ingress` objects the
backend writes at runtime. This CR settles that (decision (a) below: passthrough), which is why it has a
backend phase before the chart phase rather than being chart-only.

Phase 0 first makes the admin image run unprivileged — without it the chart cannot put the surface where
first-run setup happens onto a `restricted` namespace (§ (h)). Out of this CR and deliberately: the chart's OCI
release and Kubernetes-native backup. § *What this CR is not* says why for each.

---

## (a) TLS — settled: **passthrough**. The sink stamps what the chart tells it to.

The decision that shapes the CR, so it carries its measurements rather than its conclusion.

**The premise ADR 0064 and the sink's own KDoc state is wrong, and it is the premise the "chart-only" option
rests on.** Both say the Kubernetes sink emitting no TLS block *"is the same cut its Traefik counterpart makes
by referencing the `letsencrypt` resolver by name and never selecting or configuring one"*
(`KubernetesIngressSink.kt:37-39`, ADR 0064 § Decision). Measured, the Traefik counterpart emits a TLS block:

```
$ sed -n '77,78p;84,85p' backend/src/main/kotlin/com/veyq/app/shared/tenant/TraefikFileIngressSink.kt
      tls:
        certResolver: letsencrypt
      tls:
        certResolver: letsencrypt
```

That is a **reference** to a resolver it never configures. The cut is between *referencing* and *configuring* —
not between *emitting* and *not emitting*. The Kubernetes sink emits neither half, which is not the same cut;
it is a strictly weaker translation of it. The faithful translation is: emit `spec.tls` plus an
operator-supplied annotation (the reference), never create an `Issuer` or `Certificate` (the configuration).
**So the passthrough is not a new backend responsibility — it is the one the compose sink already carries,
finally expressed on the second sink.**

**TLS on this target is a precondition, not a preference — so "deferred" is not an available answer.** Measured
at `infra/docker-compose.prod.yml:188-192`, above the `traefik` service:

> TLS is a PRECONDITION here, not a deployment preference. `veyq.session.cookie.secure=true` is the shipped
> default, so over plain HTTP the refresh cookie is set and never sent back — "remember me" breaks silently.
> And every invitation link is built as `https://<domain>/tenant/accept#<token>` […]

A chart that cannot obtain a certificate for a tenant domain therefore ships an instance that boots, serves
every page, and then silently breaks sessions and mails links pointing at a scheme it does not answer — on the
deployment target that **gates V1** (ADR 0064 § Context, amended 2026-09-09). `veyq-acme` disappearing with
Traefik is not a volume going away; it is this property losing its mechanism.

**The wildcard option is not actually chart-only, and it re-imposes the limitation ADR 0063 refused.** Two
independent objections, either one sufficient:

1. *It cannot attach itself.* A `Certificate` the chart owns produces a `Secret`; an `Ingress` with no
   `spec.tls` references no `Secret`, so the controller serves that host no certificate. The only wiring
   between them is the **controller's** default-certificate setting (nginx `--default-ssl-certificate`,
   Traefik's default store) — which lives in the ingress controller's own chart, not in ours, on a controller
   ADR 0064 makes operator-chosen. "Chart-only" would in fact be "chart, plus an edit to a chart we do not
   ship."
2. *It is the rejected shortcut, one layer down.* ADR 0063 § Consequences rejects wildcard-subdomain routing
   because it *"forces every additional tenant onto a subdomain of the operator's own domain, permanently
   ruling out per-tenant custom domains"* (`0063-database-driven-tenant-ingress.md:122-126`). A wildcard
   *certificate* leaves routing intact and takes the same capability away at the TLS layer: the custom domain
   routes and the browser refuses it. Losing a decided capability on the target that gates V1 is a regression,
   not a deferral.

**Rejected en route — the backend writing cert-manager `Certificate` objects itself.** It would need CRD RBAC
and would hard-wire one issuer implementation into a target whose whole premise is that the controller is the
operator's choice. An uninterpreted annotation map works with cert-manager, with Traefik's CRD annotations,
and with an operator who already has a `Secret` — and needs **no new RBAC at all**, because cert-manager
watches `Ingress` objects the sink is already permitted to write (ADR 0064 § Decision names the Role's verbs:
`list`, `create`, `update`, `delete` on `networking.k8s.io/ingresses`, namespace-local).

### The shape

Three new members under `veyq.tenant.ingress.kubernetes.*`, named-never-inferred — the shape
`IngressSinkType` and ADR 0067 Decision 1 already set for this repo:

| key | type | default | meaning |
|---|---|---|---|
| `tls.mode` | `none` \| `per-domain` \| `shared` | `none` | whether and how `spec.tls` is emitted |
| `tls.secret-name` | `Optional<String>` | — | required by `shared`; the one pre-provisioned TLS secret |
| `annotations` | `Map<String,String>` | empty | stamped verbatim on every projected `Ingress`, uninterpreted |

- `none` — no `spec.tls`. The right answer where TLS terminates upstream (a cloud LB, a service mesh), and the
  default for the same reason `traefik-file` is the sink default: no existing deployment moves.
- `per-domain` — one `spec.tls` entry per domain, `secretName` = **the `Ingress` object's own name**. This is
  cert-manager's ingress-shim shape and the one that keeps per-tenant custom domains working.
- `shared` — every projected `Ingress` points at one operator-supplied `Secret`. This is the wildcard
  certificate, kept as an **operator's choice** rather than as everyone's ceiling: an operator who bought one
  `*.example.com` certificate and runs no ACME gets a working instance, and gives up custom domains knowingly.

**`secretName` reuses `nameFor(domain)` rather than deriving a second name, and that is load-bearing.** A
`Secret` name is an RFC 1123 subdomain capped at 253 characters, and `contracts/api/tenants.openapi.yaml`
already allows a 253-character `domain` — the exact bound `nameFor`'s digest branch exists for
(`KubernetesIngressSink.kt:102-106`). A `-tls` suffix on a name already at the cap is an object the API server
rejects, which on this sink's failure boundary strands *every* tenant's routing, not one. `Ingress` and
`Secret` are different kinds in one namespace, so the identical name collides with nothing.

**Stated residue, not discovered later:** when a tenant is suspended and its `Ingress` withdrawn, cert-manager
does **not** delete the `Secret` it issued unless the operator runs it with `--enable-certificate-owner-ref`.
So a withdrawn domain leaves an orphaned TLS `Secret` in the namespace. That is operator-visible residue for a
domain that no longer routes — not a routing leak (the `Ingress` is gone) and not an access leak (suspension's
real guarantee is the request-boundary gate, `uc.admin.suspend-tenant` step 3, which
`uc.admin.tenant-reachability` § Quality notes already insists is the one that holds). Documented in the
chart's values and in `infra/README.md`; not swept, because sweeping `Secret`s would need RBAC this design
exists to avoid.

---

## The other decisions, settled here

Each carries the line that settles it, per `changes/README.md` § *a SETTLED decision carries the one line that
settles it*.

**(b) The chart's bootstrap `Ingress` must not carry the sink's ownership label.** The sink *lists by*
`app.kubernetes.io/managed-by: veyq-tenant-ingress` and diffs over that list
(`KubernetesIngressSink.kt:70`), so a chart object wearing that label is deleted by the first regenerate.
Helm's own `managed-by: Helm` keeps them apart. **Half of this is already proven and this CR must not disarm
it:** `KubernetesIngressSinkTest.anIngressThisSinkDoesNotOwnSurvivesARegenerate` bites the backend half, its
lever is an unlabelled `Ingress` in the namespace, and nothing in this CR removes that lever. The half that is
this CR's is the *chart* side, and it is a guard (phase 3), not a test.

**(c) The two compose guards keep their scope; the chart gets its own.** Both `validate_app_volume_ownership.py`
(limit, :32-37) and `validate_config_secret_persistence.py` (limit, :26-31) already state that they say nothing
about a non-compose deployment. **Do not extend them.** The ownership guard's own closing line is
*"Buildless and diff-free, so it belongs in the trust-chain glob"* — reading a Helm chart means rendering it,
which needs the `helm` binary, and a `validate_*` guard that needs a tool outside the repo is green wherever
that tool is missing. The repo already has the precedent for this exact split: `scripts/mutation_meta_check*.py`
sit **outside** the glob and say why. So: a new renderer-backed chart guard outside the glob (phase 3), and the
two existing guards get their limit sentences pointed at it instead of at ADR 0064's prose.

**(d) `/var/lib/veyq` on a PVC comes up root-owned — the chart must fix it, and this is inbox row 26 finding
(g).** A Docker named volume is seeded from the image directory it covers, ownership included; a PVC is not.
The image creates and `chown`s the path (`Dockerfile:123-126`, `USER 10001` at :138), which is precisely what a
PVC does not inherit — so a first-ever `helm install` reproduces the `AccessDeniedException` restart-loop
`validate_app_volume_ownership.py`'s docstring records. Settled: `podSecurityContext.fsGroup: 10001` on the app
pod, not an `initContainer` `chown` — `fsGroup` is declarative, needs no extra container, and is what the
`restricted` PodSecurity standard expects. Asserted by the chart guard, derived from the `Dockerfile`'s `USER`
rather than restated.

**(e) The operator supplies the secrets; the chart generates nothing.** Measured on the installed
helm (v4.0.1), which is the version this decision has to hold for:

```
$ helm template probe ./probe            # a template calling (lookup "v1" "Secret" ...)
  lookupResult: "map[]"                  # ← empty in template/dry-run, on v4 as on v3
$ helm template probe ./probe | grep generated; helm template probe ./probe | grep generated
  generated: "3RPtrtn0"
  generated: "MipPOrg7"                  # ← randAlphaNum re-rolls on every render
```

So generate-if-absent regenerates on any path where `lookup` is blind. Rotating `GARAGE_RPC_SECRET` breaks the
store; losing the config-secret KEK is ADR 0056's *"boots cleanly and then refuses invites and resets"*. The
second line is the other half of the argument: a chart that generates values **cannot be asserted**, because no
two renders agree — and every proof in phase 2 is a statement about a rendered manifest. Shapes are enforced by
`values.schema.json`, which helm v4 does apply to `helm template`, naming the path and the pattern:

```
$ helm template probe ./probe --set rpcSecret=deadbeef
Error: values don't meet the specifications of the schema(s) in the following chart(s):
probe:
- at '/rpcSecret': 'deadbeef' does not match pattern '^[0-9a-f]{64}$'
rc=1
```

The **config-secret KEK is the exception and stays one**: the app generates it into
`/var/lib/veyq/config-secret.key` on its own PVC, exactly as under compose. It is key material, it never
becomes a chart value, and (d) is what makes that path writable.

**(f) `garage.toml` becomes a `ConfigMap`.** It is a host bind mount today
(`infra/docker-compose.prod.yml:51`), which a chart cannot do. This incidentally narrows ADR 0057's residual
open item — *"the in-image recipe is the two files only, so `garage.toml` still comes from the repo"* (inbox row
26, narrowed 2026-09-10): after
this CR the file has a second, self-sufficient home. Narrowed, not closed; the compose recipe still reads it
from the repo.

**(g) `replicas: 1` AND `strategy: Recreate` for the app — the second half is the one that matters.**
`quarkus.flyway.migrate-at-start=true` has no `%prod` gate and is, per inbox #121, today's undecided update
mechanism; two pods migrating concurrently is a race. `replicas: 1` alone does **not** prevent that: the
default `RollingUpdate` with `maxSurge: 25%` starts the new pod before terminating the old one, so a `helm
upgrade` briefly runs two. `Recreate` is what makes "one migrator at a time" true. Stated in the chart's values
with a pointer to #121, so the day the update mechanism is decided the constraint is findable.

**Probes and resources are chart-side because compose supplies them and the image does not.** `startupProbe` on
`GET /api/setup` — unauthenticated, served only after Flyway migrates, and this backend has **no**
`smallrye-health` extension, so there is no `/q/health/ready` to poll
(`infra/docker-compose.prod.yml:118-120`, and cr-e2e-orchestrator already paid for assuming otherwise).
`failureThreshold × periodSeconds` must cover compose's `start_period: 60s` (:125). `readinessProbe` on the same
path. **No `livenessProbe` by default**, and the
values say why: a liveness probe on a JVM in a GC pause converts a slow instance into a restart loop.
`resources.limits.memory` defaults to `1Gi` to agree with compose's `mem_limit: ${VEYQ_MEM_LIMIT:-1g}`, and the
values file states that `-XX:MaxRAMPercentage=60.0` (`Dockerfile:143`) is a percentage **of that limit** — the
two numbers are one decision written in two files.


**(h) The admin image runs unprivileged — phase 0, before anything else.** Measured: `grep -rn '^USER '
admin-web/Dockerfile` returns **nothing**, so `nginx:1.29-alpine` runs its master as root and binds port 80.
Under a `restricted` PodSecurity namespace `runAsNonRoot: true` is mandatory and the kubelet refuses that pod
outright — and the admin surface is where first-run setup happens, so the chart would install an instance nobody
can set up. First cut as a separate image CR (it is provable by compose alone); **pulled in as phase 0 by the
owner on 2026-09-14**, so the chart ships every component `restricted`-compliant and no values file carries a
documented exception.

Settled: base `nginxinc/nginx-unprivileged:1.29-alpine`, the minor the current stage already pins. Upstream is
actively maintained and vendor-neutral (`nginx/docker-nginx-unprivileged`: not archived, last push 2026-09-02; Hub
tag `1.29-alpine` active, updated 2026-05-11). The lever was run once at refine, against the pulled image:

```
$ docker image inspect nginxinc/nginx-unprivileged:1.29-alpine --format 'user={{.Config.User}} exposed={{json .Config.ExposedPorts}}'
user=101 exposed={"8080/tcp":{}}
$ docker run --rm --entrypoint sh nginxinc/nginx-unprivileged:1.29-alpine -c '…'
uid=101
conf.d WRITABLE          # 30-veyq-backend-url.sh renders /etc/nginx/conf.d/default.conf at start
5:pid        /tmp/nginx.pid;
/usr/bin/envsubst        # the hook's only tool
resolv.conf readable     # the hook's VEYQ_RESOLVERS source
apk as 101: FAILS        # ← the trap, below
$ docker run -d -p 127.0.0.1:18089:8080 nginxinc/nginx-unprivileged:1.29-alpine; curl http://127.0.0.1:18089/
http=200
```

So the entrypoint hook needs **no change**. What does:

- **`USER root` over the build steps, then `USER 101` last — both halves, in that order.** The base ends on
  `USER 101`, and this stage's `RUN apk add --no-cache nodejs`, `rm -f`, `chmod +x` and the `printf` wrapper all
  run after its `FROM`; measured above, `apk add` as 101 fails. Without the first switch the build dies on its
  first `RUN`. With it and without the second, the image silently runs as root again — the defect this phase
  exists to remove, with a green build on top.
- **Port 80 → 8080 at every site, found from two angles.** Keyed on the number
  (`listen 80|EXPOSE 80|:80"|127.0.0.1/`): `admin-web/Dockerfile:80` (`EXPOSE`), `:82` (the `HEALTHCHECK`
  URL), `admin-web/nginx.conf.template:16`, `infra/docker-compose.prod.yml:179` (the `127.0.0.1` port map) and
  `:166` (the comment explaining it). Keyed on the admin service instead: **`infra/docker-compose.prod.yml:186`**,
  `loadbalancer.server.port=80` inside the `${ADMIN_DOMAIN:+…}` label, which the first grep did not return. That
  is the own-domain exposure mode — exactly the one a default compose run never exercises, so missing it would
  surface only at an operator's. Both locators ship in § Inventory.
- **The CLI is unaffected — checked, not assumed.** `docker compose exec admin veyq-admin …` now runs as 101;
  `git grep` for `fs`/`os` imports across `admin-web/src` returns nothing, so the CLI writes no file and needs no
  writable home.
- **ADR 0065 edited in place** — its § Consequences names the final stage `nginx:<pinned>-alpine`
  (`0065-admin-ui-cli-separate-service.md:128-129`). Edited, not appended: the base changed, the decision did
  not. `container.yml:163` targets the stage by name, `runtime-nginx`, which stays.

**Promoted to a guard, because this exact rule already failed to travel once.** The app image's non-root user is
load-bearing prose in the `Dockerfile` and in `validate_app_volume_ownership.py`, and the admin image, added
later, did not inherit it — `changes/README.md` § *promote one-shot proofs*, word for word. So phase 0 adds
`scripts/validate_images_run_unprivileged.py` **inside** the glob (it reads Dockerfiles only, so it is buildless):
every `runtime-*` stage of every tracked `Dockerfile` ends on a non-root `USER`, and every port it `EXPOSE`s is
≥ 1024. The stage set is derived from the files, so a third image enrols itself.

The predicate was run against the untouched tree before this paragraph was written (`changes/README.md` § *run a
new guard's predicate against the UNTOUCHED tree*), and it is green exactly where it must be and red exactly where
phase 0 says:

```
$ git ls-files | grep -i -E '(^|/)[^/]*dockerfile[^/]*$'
Dockerfile
admin-web/Dockerfile
$ <the predicate, over every runtime-* stage>
green Dockerfile runtime-jvm: USER=('10001', 138) low-EXPOSE=[]
green Dockerfile runtime-native: USER=('10001', 167) low-EXPOSE=[]
RED   admin-web/Dockerfile runtime-nginx: USER=None low-EXPOSE=[('80', 80)]
```

So the admin half is a **live defect** (bite 0a, free) and the app half is not — it needs an injection (bite 0b).

---

## What this CR is **not**

- **The chart's OCI release.** ADR 0064 calls it *"a config addition to the existing `container.yml` workflow,
  not a new tool"*, and that is true — but its Definition of done is a workflow run, and **no CI run has ever
  executed in this repository** (`git remote -v` is empty; `changes/README.md` § the trust-chain guards, inbox
  row 26 item (1)). A phase whose only proof is unavailable is a phase that ships a green tick for nothing.
  It is also genuinely independent work — the chart has to exist before it can be packaged — which
  `changes/README.md` § *Phases only above real size* makes a separate CR by rule. **V1 is unaffected:** ADR
  0064 gates V1 on the instance being *deployable* onto a cluster, and `helm install ./charts/veyq` is that.
  Stays as the remaining sub-slice on inbox row 26.
- **Kubernetes-native backup/restore.** ADR 0064 defers it explicitly and ADR 0056's gap statement stays
  accurate. Unchanged by this CR.

---

## Context & constraints

- [ADR 0064](../docs/decisions/0064-kubernetes-helm-deployment-target.md) — the chart, its scope, the RBAC
  shape, and the TLS hole this CR closes. **Amended in place by phase 4**, since its § Decision currently
  states TLS as undecided and states the Traefik-parity claim § (a) measures as wrong.
- [ADR 0063](../docs/decisions/0063-database-driven-tenant-ingress.md) — the projection principle, and the
  wildcard rejection § (a) leans on.
- [ADR 0013 §4](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md) — the store's CORS branch.
  **The chart must not template an origin list**: on the bundled store the only working policy is `*`, it is
  written at runtime by `StoreProvisionerFactory.kt:97` (`CORS_ORIGINS = listOf("*")`) (called from
  `GarageStoreProvisioner.kt:136-139`), and
  the multi-origin form is the silently-broken one.
- [ADR 0067](../docs/decisions/0067-store-provisioning-from-a-declared-type.md) Decision 5 — Garage on its own
  Ingress host is **configuration, not code**: `veyq.blob-store.public-endpoint` = the store's Ingress host,
  `veyq.blob-store.signing-mode=store-origin` (`BlobStore.kt:217`). The chart sets two values; it builds
  nothing.
- [ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md):143-150 — the admin surface's exposure
  modes. **Translate the property, not the mechanism**: compose's "localhost-only" is a `127.0.0.1` port bind;
  the Kubernetes equivalent is no `Ingress`, a `ClusterIP` `Service`, and `kubectl port-forward`. What must
  survive is *first-run setup independent of DNS and TLS*.
- [ADR 0057](../docs/decisions/0057-release-identity-and-deployment-recipe.md) — release identity.
  `scripts/validate_release_identity.py` assertion 5 already forbids a `build:` stanza in the prod compose
  file *because a chart has nothing to build with*; the chart must stay in that world — it deploys published
  images only.
- **Invariant at risk, and the one to keep loudest:** ADR 0064's *"namespace-local ServiceAccount + Role,
  never a cluster-wide `ClusterRole`"*. A chart that reaches for a `ClusterRole` because it is easier hands
  the backend every namespace's `Ingress` objects.
- **Do not set `veyq.tenant.ingress.kubernetes.namespace` in `values.yaml`.** It is the outside-a-cluster
  override; in-cluster the namespace comes from the pod's mounted ServiceAccount token
  (`TenantIngressSinkProducer.kt:54-58`). Setting it in the chart re-introduces the per-install rebuild the
  whole design avoids.

---

## Inventory — run the locators, do not rebuild the set from them

Measured against `main` @ `fe3c749`; phase 0 re-measured 2026-09-14 with `main` unmoved. **Spot-check one row**
— the one this CR leans on hardest is the `admin-web` `USER` line in § (h), because a single grep decides whether
phase 0 exists at all.

```bash
# the five services and the seven named volumes the chart must cover
python3 -c "import yaml;d=yaml.safe_load(open('infra/docker-compose.prod.yml'));print(sorted(d['services']));print(sorted(d.get('volumes') or {}))"

# required env, read off the syntax rather than off prose. Digits in the class, comment lines out: the
# grounding's `[A-Z_]+` form silently dropped VEYQ_S3_ACCESS_KEY and VEYQ_S3_SECRET_KEY — the two values
# with shape constraints values.schema.json exists for — and picked up a `${VAR:?` from a comment.
grep -vE '^\s*#' infra/docker-compose.prod.yml | grep -oE '\$\{[A-Z0-9_]+:\?' | sort -u

# the ownership mechanism a PVC does not inherit (decision (d))
grep -n 'USER\|mkdir\|chown\|WORKDIR\|MaxRAMPercentage' Dockerfile

# phase 0's premise — expect NO admin-web line until phase 0 lands
grep -rn '^USER ' Dockerfile admin-web/Dockerfile

# phase 0's port sites, from two angles: keyed on the number, then on the admin service.
# Only the second returns infra/docker-compose.prod.yml:186.
git grep -n -E 'listen 80|EXPOSE 80|:80"|127\.0\.0\.1/' -- admin-web infra
git grep -n -E 'server\.port=80|ADMIN_PORT' -- admin-web infra

# the label the chart must never stamp — read the VALUE from the source, never retype it
grep -n 'OWNER_LABEL\|OWNER_VALUE' backend/src/main/kotlin/com/veyq/app/shared/tenant/KubernetesIngressSink.kt

# every prose site claiming the second sink is unbuilt (phase 4) — whole tree, fragment, not a directory list
grep -rn 'not built' docs/

# the guard baseline, as the command that produces it. RUN IT BEFORE THE FIRST EDIT:
# a red after that is this CR's.
for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED: $f"; done; ls scripts/validate_*.py | wc -l
```

**Second angle, keyed on something different.** The first question is *"which compose services and volumes?"*,
and it is a paraphrase of the chart's own structure. Ask instead: **"for each `veyq.*` value the chart sets,
which cluster object must exist for that value to be TRUE?"** — the question that caught the ingress sink's
missing mandatory fields, per `changes/README.md` § *a new external object's OPTIONAL knobs*.

```bash
python3 -c "import yaml;d=yaml.safe_load(open('infra/docker-compose.prod.yml'));e=d['services']['app']['environment'];k=[x for x in e if x.startswith('VEYQ_')];print(len(k));[print(' ',x) for x in k]"
```

Measured today: **10** keys, and the second angle's answers are what the first angle does not produce —

| value | the object it asserts into existence |
|---|---|
| `tenant.ingress.kubernetes.service-name=veyq-app` (default) | a `Service` **literally named** `veyq-app` — rename it in the chart and this must move with it |
| `tenant.ingress.kubernetes.namespace` **unset** | a mounted ServiceAccount token ⇒ `ServiceAccount` + `Role` + `RoleBinding` |
| `blob-store.public-endpoint` = the store's own host | a second `Ingress`, for `garage` |
| `blob-store.endpoint=http://…:3900` internal | a `Service` for `garage`, and a **separate** admin port 3903 that is deliberately not exposed |
| `security.config-secret-file=/var/lib/veyq/…` | a PVC **and** `fsGroup` — see (d) |
| `tenant.ingress.dynamic-config-path` | **nothing** — it must be *absent* on this target, or the producer builds a file sink |

*(The grounding package recorded 9 for this command; it returns 10. The disagreement is why the CR ships the
command and not either number.)*

**Ask the mandatory-fields question once per kind.** The chart renders ~14 kinds — `Deployment`×2,
`StatefulSet`×2, `Service`×4, `Ingress`×2–3, `ServiceAccount`, `Role`, `RoleBinding`, `Secret`, `ConfigMap`,
`PersistentVolumeClaim` — and `changes/README.md` § *a new external object's OPTIONAL knobs* says the
inventory's silent failure is listing the tunable half. Per kind: *could this object be constructed from
exactly the values listed?*

---

## Phases

- [ ] **0 admin image — runs unprivileged** (§ (h)). **Guard first, and record its red:** add
      `scripts/validate_images_run_unprivileged.py` and run it on the untouched tree, where it must fail naming
      `admin-web/Dockerfile` `runtime-nginx` and nothing else. Then the fix: base
      `nginxinc/nginx-unprivileged:1.29-alpine`, `USER root` over the build steps and `USER 101` last, 80 → 8080
      at every site both locators return, ADR 0065:128-129 edited in place. ·
      **Done when:** the new guard is green and the rest of the glob stays green; the prod compose file still
      interpolates with the port change — **not** bare and **not** against `infra/.env.example`, both of which
      fail on unset `MARIADB_ROOT_PASSWORD`/`VEYQ_ACME_EMAIL` (measured 2026-09-14), but against a scratch
      env file holding a dummy for every name the required-env locator in § Inventory returns, which is rc=0
      today: `docker compose --env-file <scratch> -f infra/docker-compose.prod.yml config -q`. **And the image
      runs**, not only builds (`changes/README.md` § *a build step that emits an executable artifact*):
      `docker image inspect` reports `User` = `101`, the container reaches `healthy`, `GET /` answers 200 on
      8080, the rendered `/etc/nginx/conf.d/default.conf` carries the `BACKEND_URL` it was started with, and
      `docker exec <c> veyq-admin --help` prints the command table (`admin-web/src/cli/cli.ts:72-73`), proving
      the Node wrapper still starts under uid 101.

- [ ] **1 backend — the TLS passthrough.** Three members on `VeyqConfig.Tenant.Ingress.Kubernetes`
      (`tls.mode` as a named enum, `tls.secret-name`, `annotations`); `KubernetesIngressSink` stamps
      `metadata.annotations` and, per mode, `spec.tls`; `secretName` = the object's own `nameFor(domain)`;
      `TenantIngressSinkProducer` refuses `shared` with no `secret-name` the way it already refuses an
      unresolvable namespace — **loud and disabled, never a failed boot** (`TenantIngressSinkProducer.kt:60-67`
      is the shape). Correct the KDoc's Traefik-parity claim at `KubernetesIngressSink.kt:37-39` in the same
      commit — it is the sentence § (a) disproves. ·
      **Done when:** `./gradlew check` green **and** the five cases below are present, with the run's own
      output read rather than its exit code (`changes/README.md` § *an exit code from a chain*).

- [ ] **2 chart — `charts/veyq`.** `Chart.yaml`, `values.yaml`, `values.schema.json` (the two shape
      constraints: `^[0-9a-f]{64}$` for the RPC secret, `^GK[0-9a-f]{24}$` for the S3 access key), and the
      templates for every kind in the inventory. `mariadb.enabled: false` escape hatch onto an
      operator-managed database (ADR 0064 § Decision). No generated secrets (e). `Recreate` + `replicas: 1`
      (g). `fsGroup: 10001` (d). `garage.toml` as a `ConfigMap` (f). Helm's own `managed-by` on everything, the
      sink's label on nothing (b). Every component's `securityContext` is `restricted`-compliant — admin
      included, which is what phase 0 buys. Four facts the retired grounding package carried, easy to lose:
      the admin container sets `BACKEND_URL=http://veyq-app:8080`, because its hook defaults to the compose
      service name `app:8080` (`admin-web/docker-entrypoint.d/30-veyq-backend-url.sh:14`), and listens on 8080
      after phase 0; `garage` has **no** healthcheck in compose, so its probes are this chart's to decide, not
      to copy; `veyq.tenant.ingress.kubernetes.ingress-class-name` is a passthrough value, absent by default;
      `VEYQ_ACME_EMAIL`/`VEYQ_ACME_CASERVER` have **no** chart equivalent — ACME lived in Traefik and on this
      target the issuer is the operator's (§ (a)), so a `values.yaml` copied from `infra/.env.example` would
      demand an email nothing reads. ·
      **Done when:** `helm lint charts/veyq` is green **and** `helm template` renders the expected kinds and
      counts. **Not the exit code** — measured on helm v4.0.1, a chart whose `templates/` is empty passes
      both: `helm lint` → `1 chart(s) linted, 0 chart(s) failed`, rc=0; `helm template` → no output, rc=0.
      The done-when is the object set, produced by the phase-3 guard.

- [ ] **3 guard — `scripts/chart_meta_check.py`** (that name, or any name **outside** the `validate_*` glob),
      with the three docstring fields `changes/README.md` mandates, and its *why it sits outside the glob*
      paragraph: it shells out to `helm`, which is not a repo artifact, and the glob's contract is buildless
      and diff-free. It renders the chart with default values and asserts, **deriving every anchor from the
      source rather than listing it**: (i) no rendered object carries the sink's ownership value — read out of
      `KubernetesIngressSink.kt`, never retyped, so the guard's own file cannot become a copy of what it
      forbids (`changes/README.md` § *a guard that scans for a literal*); (ii) every container running the app
      image mounts `veyq.security.config-secret-file`'s resolved path from a PVC, and its pod sets `fsGroup`
      equal to the `Dockerfile`'s runtime `USER`; (iii) the app workload's `strategy` is `Recreate`;
      (iv) rendering twice produces byte-identical output. Then point
      `validate_app_volume_ownership.py`'s and `validate_config_secret_persistence.py`'s **limit** paragraphs
      at this guard instead of at ADR 0064's prose. ·
      **Done when:** the guard is green on the finished chart, every injection below bites, and
      `for f in scripts/validate_*.py; do python3 "$f"; done` is still green over the **committed** tree.

- [ ] **4 docs.** `infra/README.md` gains a Kubernetes section and the `helm upgrade` runbook ADR 0064 §
      Consequences scopes to *"the CR that actually builds the chart"* — including the `Recreate`/migrate-at-start
      caveat and the orphaned-TLS-`Secret` residue. ADR 0064 amended in place: TLS decided, the Traefik-parity
      sentence corrected, the OCI release named as the remaining sub-slice. Sweep the stale claim: `grep -rn
      'not built' docs/` returns three sites the ingress-sink CR left behind
      (`uc.admin.tenant-reachability.md:115`, `:144`, `features/admin/_index.md:31`) — the sink shipped in
      `d677e36`. **Re-run that sweep at this phase, not only now**: phases 1–3 author new prose about the same
      subject. Say per site whether it is *edited, narrowed or deleted*, and read
      `uc.admin.tenant-reachability.md` **end to end** rather than only the grep hits — it is the owning
      document, and a fragment grep is blind to the same claim said in different words. ·
      **Done when:** `python3 scripts/validate_doc_links.py` and the rest of the glob green, and the sweep
      returns no site that still calls the second sink unbuilt.

- [ ] **5 coverage.** `uc.admin.tenant-reachability` gains one criterion — *a tenant domain gets a certificate,
      by a named mode, and a custom domain is not excluded from it* — which is main-flow **step 3**'s promise
      (*"for each domain that appears, obtains a certificate for it"*) finally having a Kubernetes half.
      `uc.admin.setup-server` gains the admin surface's Kubernetes exposure — no `Ingress`, a `ClusterIP`
      `Service`, `kubectl port-forward`, on a `restricted` namespace — keeping the property ADR 0065 names:
      first-run setup independent of DNS and TLS. No PodSecurity `gaps:` entry; phase 0 closed it. ·
      **Done when:** `python3 scripts/validate_coverage.py` green.

---

## Bite tests — per test, the method and the lever

`changes/README.md` § *the CR says PER bite-test how it will be bitten*. Marking the method also orders the
phase: the live defects run first, against the least-changed tree there will be.

| # | what it pins | method | lever |
|---|---|---|---|
| 0a | the admin image's runtime stage ends non-root | **live defect** — the guard is red on the untouched tree, naming `runtime-nginx` | none; record the red before the fix |
| 0b | the guard also covers the **app** image, which has no live defect | **injection** | delete `USER 10001` from `Dockerfile`'s `runtime-jvm` stage; the guard must redden naming `runtime-jvm`, not merely "a stage" |
| 0c | the built admin image serves as uid 101 | **live defect** | none — today's image reports an empty `User`, so the `inspect` assertion is red before the fix |
| 1 | `per-domain` emits one `spec.tls` per domain with `secretName` = the object's name | **live defect** — phase 1 introduces the behaviour, so it is red on the untouched tree | none needed |
| 2 | a 253-character domain yields a `secretName` the API server accepts | **live defect** | none — drive the same domain `nameFor`'s digest branch already exists for |
| 3 | `none` (the default) emits **no** `spec.tls` and no annotations | **injection** — green from the first run | set `tls.mode=none` explicitly and flip the sink's emit branch to unconditional; the case must redden **by name** |
| 4 | `shared` with no `secret-name` disables the sink loudly instead of failing the boot | **live defect** | none |
| 5 | the annotation map is stamped verbatim and **uninterpreted** | **live defect** | none — assert a key the backend knows nothing about (`example.com/whatever`) survives the round trip |
| 6 | no chart-rendered object carries the sink's ownership value (b) | **injection**, phase 3 | add `app.kubernetes.io/managed-by: veyq-tenant-ingress` to the bootstrap `Ingress` template, re-render, guard must redden naming that object |
| 7 | the config-secret path is on a PVC with `fsGroup` (d) | **injection**, phase 3 | drop `fsGroup` from the app pod's `podSecurityContext`; guard reddens |
| 8 | the app workload cannot roll two pods (g) | **injection**, phase 3 | change `strategy: Recreate` → `RollingUpdate`; guard reddens |
| 9 | the chart renders deterministically (e) | **injection**, phase 3 | introduce one `randAlphaNum` into any template; the two-render comparison reddens. *Measured above that it does.* |
| 10 | `values.schema.json` rejects a malformed `GARAGE_RPC_SECRET` | **live defect** | `helm template … --set` a 63-hex value; expect rc=1 and the pattern named — *measured above* |

**Levers 0b and 6–9 all survive this CR's own phases** (`changes/README.md` § *a bite's lever must survive*):
0b edits the app `Dockerfile`, which no later phase touches; 6–9 edit chart templates phase 3 does not
constrain; phase 4 touches only prose. Lever 3 is the one
to watch — if phase 1's emit branch is later refactored, re-read which assertion reddened, not that the test
did.

**Restore protocol for every injection:** `cp <file> /tmp/snap` first; restore with `command cp -f`; confirm by
comparing the file's **bytes to the snapshot** and `git status --porcelain` before and after the whole run —
**not** `git diff --quiet`, which reports "still dirty" for every case while the CR's own edits are
uncommitted. Each injection asserts its own arrival (the **new** text present **and** the bytes differ from the
snapshot) before its run starts; an unconfirmed injection scores as a failed bite, never as a pass.

**Confirm the commit path works before phase 0** — one throwaway commit. A phased CR's "verifiable state" is
the committed tree, and the glob sees files, not commits.

---

## Definition of done

- The admin image built **and run** as uid 101 (phase 0), per that phase's done-when.
- `./gradlew check` green over the committed tree (phase 1), read as output and not as a tick.
- `helm lint charts/veyq` green **and** the phase-3 guard green — the guard, not the lint, is the proof the
  chart rendered anything at all.
- `for f in scripts/validate_*.py; do python3 "$f"; done` green over the **committed** tree, measured against
  the baseline the inventory's command produces *before the first edit*. There is no remote, so a local run
  over the committed tree is the strongest available proof and this done-when says exactly that rather than
  "green in CI".
- Every bite test in the table demonstrated, each by the method it names.
- `docs/coverage/` updated for both `satisfies:` use-cases, `python3 scripts/validate_coverage.py` green.
- The structural invariants are held by the phase-0 and phase-3 guards rather than by a one-shot grep
  (`changes/README.md` § *promote one-shot proofs*).

## Retrospective

*(Written at implement-session close — planning, execution, and process/collaboration; both directions; ending
with one concrete "next time".)*
