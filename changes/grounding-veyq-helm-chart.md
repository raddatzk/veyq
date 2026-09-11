# Grounding package — the `charts/veyq` Helm chart CR

**This is not the CR.** It is the measured input a *fresh* refine session should start from, so that session
spends its context on decisions instead of re-measuring. Written at the tail of the
`cr-tenant-ingress-kubernetes-sink` implementation session — which is exactly why it is not the CR itself
(`docs/ai/README.md` § Session topology: *refine always runs fresh, never tacked onto an implementation's
tail*; *design-heavy, multi-surface, or novel-abstraction CRs stay split*).

Every claim is **measured** (file:line, or the command) unless marked *remembered*.

---

## 1. Where this sits

`docs/inbox.md` row 26 / #137, slice 4. Of the original five CRs, three are gone: the store bootstrap became
ADR 0067, blob-on-its-own-host shipped, the ingress sink shipped 2026-09-11. **What is left is the chart and
the chart's OCI release** — and whether those are two CRs or one is itself a refine question: ADR 0064 calls
the release *"a config addition to the existing `container.yml` workflow, not a new tool"*, which may not earn
its own CR. Decide it, don't inherit it.

---

## 2. What the compose stack IS — measured

Five services in `infra/docker-compose.prod.yml` (top-level `name: veyq` at :22; **no service carries a
`build:` stanza**, which `scripts/validate_release_identity.py` assertion 5 already enforces *for this exact
reason*: "a chart has nothing to build with").

| service | image | volumes | probes | Traefik |
|---|---|---|---|---|
| `mariadb` :25 | `mariadb:11.4` | `veyq-mariadb-data`→`/var/lib/mysql` | healthcheck :37-41 | no |
| `garage` :43 | `dxflrs/garage:v2.0.0` | **bind** `./garage.toml`→`/etc/garage.toml:ro`; meta/data/snapshots volumes | **none** | yes (`veyq-blobs`, prio 100, `PathPrefix(/veyq)`, port 3900, `passHostHeader`) |
| `app` :68 | `${VEYQ_IMAGE:?}` | `veyq-config-secret`→`/var/lib/veyq`; `veyq-traefik-dynamic`→`/etc/traefik/dynamic` rw | healthcheck `curl /api/setup`, `start_period: 60s` :117-125 | yes (`veyq-app`, prio 1, port 8080) |
| `admin` :168 | `${VEYQ_ADMIN_IMAGE:?}` | none | healthcheck baked into the image (`admin-web/Dockerfile:81-82`) | **conditional** on `${ADMIN_DOMAIN:+…}` |
| `traefik` :192 | `traefik:v3.6` | docker.sock, `veyq-acme`→`/acme`, `veyq-traefik-dynamic:ro` | none | *is* the proxy |

**Seven named volumes** (:235-245). On Kubernetes three of them change character and that is most of the
chart's design:

- `veyq-traefik-dynamic` **disappears** — it only existed for the file sink. Superseded by
  `KubernetesIngressSink`.
- `veyq-acme` **disappears** — ACME lived in Traefik. Nothing in the chart replaces it (→ decision (a)).
- `veyq-config-secret` and the three data volumes become PVCs (→ decision (b), the ownership trap).

**Ten required env values, four optional** (`infra/.env.example`, header states the rule at :5-7). Required:
`VEYQ_DOMAIN`, `VEYQ_IMAGE`, `VEYQ_ADMIN_IMAGE`, `MARIADB_PASSWORD`, `MARIADB_ROOT_PASSWORD`,
`GARAGE_RPC_SECRET`, `GARAGE_ADMIN_TOKEN`, `VEYQ_S3_ACCESS_KEY`, `VEYQ_S3_SECRET_KEY`, `VEYQ_ACME_EMAIL`.
Optional: `ADMIN_DOMAIN`, `ADMIN_PORT`, `VEYQ_ACME_CASERVER`, `VEYQ_MEM_LIMIT`. Two carry **shape**
constraints a cryptic runtime failure is the only current feedback for: `GARAGE_RPC_SECRET` exactly 64 hex,
`VEYQ_S3_ACCESS_KEY` = `GK` + 24 hex.

**Nothing Kubernetes-shaped exists in the tree yet — asked from two angles, which agree.** By *filename*:
zero `Chart.yaml` / `values.yaml` / `templates/*` / `deployment.yaml` / `kustomization*` across the 1060
tracked files, and `find -type d -name charts` → nothing. By *content*, repo-wide and including untracked and
ignored paths: four source files mention Helm or Kubernetes at all — `backend/build.gradle.kts`,
`admin-web/docker-entrypoint.d/30-veyq-backend-url.sh`, `infra/README.md`, `changes/README.md` — every other
hit is build output under `backend/build/`. So Kubernetes exists here only as ADR 0064 prose, the sink shipped
this week, commented-out config keys, and four sentences naming Helm as the target a compose-only guard cannot
cover. **The chart CR starts from zero artifacts, and that is measured rather than assumed.**

---

## 3. What the backend ALREADY provides — so the CR does not re-plan it

The chart is, to a surprising degree, a wiring job:

- **The ingress sink** (`backend/.../shared/tenant/KubernetesIngressSink.kt`) — reconciles one `Ingress` per
  routable tenant domain, namespace from the ServiceAccount token, labels and **lists by**
  `app.kubernetes.io/managed-by: veyq-tenant-ingress`. Measured: 10 test cases, five bite tests by injection.
- **The bucket CORS policy is written at runtime by the backend, and it is `*`** —
  `StoreProvisionerFactory.kt:97`, invoked from `GarageStoreProvisioner.kt:136-139`. ADR 0013 §4:109-124
  carries the measurement of why `*` is the *only* working policy on the bundled store. **The chart must not
  try to template an origin list** — the multi-origin form is the silently-broken one.
- **`SigningMode.STORE_ORIGIN` exists** (`BlobStore.kt:217`). Garage-on-its-own-Ingress-host is config, not
  code: `veyq.blob-store.public-endpoint` = the store's Ingress host, `signing-mode=store-origin`.

New keys the chart sets (`application.properties:51-58`):

```
veyq.tenant.ingress.sink=kubernetes
veyq.tenant.ingress.kubernetes.ingress-class-name=<operator's controller>   # optional, passthrough
veyq.tenant.ingress.kubernetes.service-name=veyq-app                        # the chart's own Service name
veyq.tenant.ingress.kubernetes.service-port=8080
# veyq.tenant.ingress.kubernetes.namespace — do NOT set it. It is the OUTSIDE-a-cluster override; setting it
# in values.yaml re-introduces the per-install rebuild the whole design avoids.
```

---

## 4. The open decisions

### (a) TLS — a real hole, not a deferral. Settle this FIRST.

ADR 0064 § Decision now states it in place: the sink emits no TLS block; cert-manager is driven by an
**annotation on the `Ingress` object**; those objects are written at runtime by the backend; *a chart cannot
annotate what it does not template.* So TLS needs either a **passthrough** (a configurable annotation map +
`spec.tls` the sink stamps onto every object it builds) or a wildcard `Certificate` the chart owns outright.

This decides the CR's shape: passthrough ⇒ the CR has a backend phase; wildcard ⇒ chart-only, and per-tenant
custom domains lose TLS, which ADR 0063 explicitly rejected the wildcard *routing* shortcut to preserve.
The likely argument for passthrough: it is the exact analogue of the Traefik sink referencing `letsencrypt`
by name and never configuring a resolver.

Related and easy to miss: `veyq-acme` is where certificates live today. Deleting that volume without an
answer is how "TLS deferred" becomes "TLS absent".

### (b) The bootstrap-domain Ingress must NOT carry the sink's ownership label

New interaction, nothing in the tree states it. On compose the bootstrap domain is a **static Traefik label**
on the `app` service (:126-133) and `TenantIngressProjector` excludes that domain from the projection. On
Kubernetes the chart must template that router as its own `Ingress` — and if it copies the sink's
`app.kubernetes.io/managed-by: veyq-tenant-ingress` label, **the first regenerate deletes it**, because that
label is the selector the reconcile diffs over. Helm's own `managed-by: Helm` is what keeps them apart.

Both halves already agree (the projector's bootstrap filter, the sink's label selector); this is about not
breaking that agreement from the chart side. Obvious bite test: render the chart, apply, regenerate, assert
the bootstrap Ingress survives — the Kubernetes twin of
`TenantIngressProjectorTest.theBootstrapDomainIsNeverProjected`.

### (c) Two guards go silently incomplete the moment the chart lands

Both already state their own limit, so the CR must **decide**, not discover:

- `scripts/validate_app_volume_ownership.py:32-35` — *"says nothing about a non-compose deployment … ADR 0064's
  chart owns its own volume semantics."* This is inbox row 26 finding **(g)**: a PVC does **not** seed
  ownership from the image the way a Docker named volume does, so `/var/lib/veyq` (image: uid 10001,
  `chmod 0700`, `Dockerfile:123-126`) comes up root-owned and reproduces, on the chart's first-ever install,
  the exact `AccessDeniedException` restart-loop this row already paid for once. The fix (`fsGroup` /
  `securityContext`, or an `initContainer` `chown`) was out of scope for the ingress-sink CR and is **this
  CR's**.
- `scripts/validate_config_secret_persistence.py:26-30` — *"It sees compose files in this tree … it says
  nothing at all about a deployment that is not compose."* The chart is a third home for that path. It does
  already accept the `/run/secrets/<name>` shape, "the K8s/docker-secret shape `VeyqConfig.kt` names".

Extend both to read chart templates, or accept and state the gap. `changes/README.md` § *Promote one-shot
proofs to permanent guards* pushes toward extending; *pain before tool* pushes back. Argue it once, in the CR.

### (d) `admin-web` runs as root — measured, and Kubernetes cares

`admin-web/Dockerfile` has **no `USER` directive anywhere** (the app image has `USER 10001` in both runtime
targets). Under a `restricted` PodSecurity standard — the default posture on many clusters — `runAsNonRoot`
rejects that pod outright. Either the chart ships a `securityContext` that an nginx-as-root image tolerates,
or the image needs an unprivileged nginx. **Found late this is expensive; found here it is a paragraph.**

Its exposure modes also need translating, not copying (ADR 0065:143-150): compose's "localhost-only" is a
`127.0.0.1` port bind; the Kubernetes equivalent is *no Ingress + ClusterIP + `kubectl port-forward`*. Keep the
**property** — first-run setup independent of DNS and TLS — not the mechanism.

### (e) Secret generation on `helm upgrade` — the classic trap, and it is fatal here

Ten required values, most of them secrets. The idiomatic Helm move is generate-if-absent via `lookup`. But
`lookup` returns empty during `helm template` and dry-run, so the naive form **regenerates on upgrade**.
Rotating `GARAGE_RPC_SECRET` breaks the store; losing the config-secret KEK is ADR 0056's *"boots cleanly and
then refuses invites and resets"*. Either the chart requires the operator to supply them (with a
`values.schema.json` enforcing the two shape constraints in §2), or it generates them **once** into a Secret
it never rewrites — and the CR must say which, with the bite test that proves an upgrade does not rotate.

### (f) `garage.toml` becomes a ConfigMap — and that closes a known residual gap

Today it is a **host bind mount** (`./garage.toml`), which a chart cannot do. ADR 0057's open item —
*"the in-image recipe is the two files only, so `garage.toml` still comes from the repo and an extracted
recipe is not yet self-sufficient"* — is the same fact. Templating it as a ConfigMap is the chart's only
option and incidentally narrows that gap; worth stating so the next reader connects them.

### (g) migrate-at-start vs. replicas

`quarkus.flyway.migrate-at-start=true` with no `%prod` gate is, per inbox #121, *"today's undecided update
mechanism"*. On Kubernetes a rolling update runs N pods and migrate-at-start with `replicas > 1` is a race.
Either the chart pins `replicas: 1` and says why, or the upgrade story gets decided here. ADR 0064 §
Consequences already scopes the `helm upgrade` runbook (`infra/README.md` § *An upgrade, step by step*, :357)
to *"the CR that actually builds the chart"* — so it is this CR's either way.

Also chart-side because compose supplies them and the image does not: the app's **probes** (`/api/setup`,
`start_period: 60s` ⇒ a `startupProbe`) and its **resources** (`mem_limit`, plus the JVM's
`-XX:MaxRAMPercentage=60.0` at `Dockerfile:143` — the limit and the percentage have to agree).

---

## 5. Locators — run them; do not rebuild the set from them

```
python3 -c "import yaml;d=yaml.safe_load(open('infra/docker-compose.prod.yml'));print(sorted(d['services']));print(sorted(d.get('volumes') or {}))"
grep -nE '\$\{[A-Z_]+:\?' infra/docker-compose.prod.yml          # required, from the syntax not from prose
grep -n -B6 'mkdir\|chown' Dockerfile                             # the ownership mechanism a PVC does not inherit
grep -ln 'docker-compose\|Dockerfile' scripts/validate_*.py       # every guard that goes incomplete
grep -rn 'USER ' Dockerfile admin-web/Dockerfile                  # decision (d)
```

**Second angle** (the refinement checklist's rule — the first command paraphrases what you already believe):
§2 answers *"which services and volumes"*. Ask the different question too — **"which of `VeyqConfig`'s `veyq.*`
members does the compose deployment actually set?"** Measured answer today: 9 of them, on `app`. The gap
between that and all of `VeyqConfig` is the finding — everything compose does not set is a knob `values.yaml`
does not owe an operator either, and a chart exposing all of `VeyqConfig` is a chart nobody can read.

---

## 6. Retrospective input from the session that just ended

Two rules harvested into `changes/README.md`, both of which bear on this CR:

- *A before/after snapshot taken inside the test is blind to what STARTUP already did.* A chart CR's proofs are
  largely "the rendered manifest contains X" — the same failure mode: an assertion satisfied by state that was
  already there.
- *A new external object's OPTIONAL knobs are not its MANDATORY fields.* The ingress-sink CR inventoried a
  Kubernetes resource's tunables and omitted the field it cannot exist without. **A chart CR inventories eight
  or nine resource kinds. Ask the question per kind.**

And one not yet a rule, because one occurrence is not a pattern: the first `./gradlew check` of that session
returned `BUILD SUCCESSFUL` with `:test UP-TO-DATE` and ran nothing. The chart equivalent is `helm lint` /
`helm template` succeeding on templates that rendered **empty**. Assert the rendered artifact — the object
kinds and their count — never the command's exit code.
