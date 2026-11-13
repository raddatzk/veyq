#!/usr/bin/env python3
"""The Helm chart (ADR 0064) holds the structural invariants the compose recipe's guards hold — on rendered objects.

**What it asserts.** It renders `infra/kubernetes/charts/veyq` with `helm template` once per values file in `infra/kubernetes/charts/veyq/ci/` —
the set is the directory, never a list here, so a new file enrols itself; each satisfies the schema without an
operator, because the chart generates no secret and a bare render is refused by design — and, over every
render, asserts — deriving every anchor from the source rather than listing it:

  i.   **No rendered object carries the backend's ingress-ownership value.** The Kubernetes sink lists the
       `HTTPRoute`s and `ListenerSet`s it owns BY `app.kubernetes.io/managed-by: <its value>` and deletes over
       that list, so a chart object wearing that value is deleted by the first regenerate. Both the key and the
       value are read out of `KubernetesGatewaySink.kt`'s `OWNER_LABEL`/`OWNER_VALUE` constants and never spelled
       here — this
       file is text, and a guard that scans for a literal must not become a copy of what it forbids. Every
       label map is checked: object metadata, pod templates, selectors.
  ii.  **The app's config-secret path is on a PersistentVolumeClaim, and its pod sets `fsGroup` to the image's
       runtime user.** The path is resolved the way `validate_config_secret_persistence.py` resolves it — the
       container's `VEYQ_SECURITY_CONFIG_SECRET_FILE` if set, else `VeyqConfig.kt`'s `@WithDefault` against
       the `Dockerfile`'s runtime `WORKDIR` — and the uid is the `Dockerfile`'s last `USER` in every `runtime-*`
       stage (which must agree). A claim comes up root-owned and, unlike a Docker named volume, inherits
       nothing from the image directory it covers; `fsGroup` is what makes it writable, so a chart without it
       reproduces the AccessDeniedException restart-loop on its first-ever install. **With
       `app.configSecret.existingSecret`** (ADR 0118) the path is instead on a READ-ONLY mount of a `secret` volume
       of exactly that Secret — the operator's key, which the server reads and never writes — and the erasure ledger
       (`VEYQ_ERASURE_LEDGER_FILE`, which otherwise defaults to the key's directory, now unwritable) is set and on a
       claim this render creates. Some file in `ci/` must render that branch.
  iii. **The app workload's update strategy is `Recreate`.** `quarkus.flyway.migrate-at-start=true` has no
       `%prod` gate, and the default RollingUpdate starts the new pod before the old one stops — two migrators
       on one schema during every `helm upgrade`. `replicas: 1` alone does not prevent that.
  iv.  **Rendering twice is byte-identical.** A chart that generates values cannot be asserted — no two renders
       agree — and a generated secret is re-rolled on every path where `lookup` is blind (`helm template`,
       `--dry-run`). Measured on helm v4.0.1 that `randAlphaNum` re-rolls per render; this is the check that
       keeps it out.
  v.   **Every pod is `restricted`-compliant, admin surface included, with a read-only root.** Each container's
       securityContext drops all capabilities, forbids privilege escalation, runs non-root, uses the runtime
       default seccomp profile, and sets `readOnlyRootFilesystem` (ADR 0071 §1, the one field beyond
       `restricted`). The admin image was made unprivileged for exactly this (ADR 0065,
       `validate_images_run_unprivileged.py`); this is the chart-side half of that property.
  vi.  **The admin surface is reachable by default through a ClusterIP Service and NO route.** ADR 0065's
       localhost-only default, translated as a property: `kubectl port-forward` is this target's `127.0.0.1`
       bind, and it is what keeps first-run setup independent of DNS and TLS. The admin container is the one
       whose image repository is `veyq-admin`; an `HTTPRoute` with a backend on its Service in a render that leaves
       `admin.domain` unset, or a Service type other than ClusterIP, is a red.
  vii. **A render that does not opt into `gateway.tls.acme` carries no trace of it** — no `Issuer`, and no
       `cert-manager.io/` key (the issuer's or `acme.cert-manager.io/`'s) on any object's annotations or in the
       app's properties file. The opt-in is off by default so that nothing moves (ADR 0064).
  viii.**No render contains a cluster-scoped kind** (`ClusterIssuer`, `ClusterRole`, `ClusterRoleBinding`,
       `GatewayClass`, and the rest of `CLUSTER_SCOPED`), and **a render that opts in contains exactly one `Issuer`, named
       `veyq-acme`**, solving HTTP-01 only. Nothing cluster-wide is the self-hostability bar ADR 0064 states;
       a `ClusterIssuer` is the easy wrong turn, which is why it is named.
  ix.  **In an opted-in render the `Gateway` carries `cert-manager.io/issuer: veyq-acme`, the
       `acme.cert-manager.io/http01-parentreffallback: "true"` annotation, and no second issuer annotation;
       and no `HTTPRoute` carries any `cert-manager.io/` annotation** — cert-manager reads the `Gateway` and the
       `ListenerSet`s, and a route carries no certificate (ADR 0064). The listeners' `certificateRefs` are
       (xxi)'s, held in every `per-domain` render whether or not it opts in.
  x.   **In an opted-in render the app's properties file carries `tls.mode=per-domain` and both quoted
       annotations.** This is the half that reaches the tenant `ListenerSet`s the backend writes at runtime, and
       it is asserted SEPARATELY from (ix): the annotation map has two consumers in the chart (the `Gateway`'s
       metadata and this file), and a helper wired into one of them is a render where (ix) is green and every
       tenant domain silently gets no certificate.
  xii. **The admin listener has a Service of its own, no route names it, and the admin surface points at it.**
       The listener's port is the app container's `VEYQ_ADMIN_CHANNEL_PORT` if set, else `VeyqConfig.kt`'s
       `@WithDefault` on `AdminChannel.port()`; the app container must declare it. Every Service targeting that
       port selects the app pods and exposes that port ALONE — a second port on the public app Service would put
       it behind every route aimed at that Service, the chart's, a projected tenant one or an operator's
       (ADR 0065). No `HTTPRoute` in any render has a backend on such a Service, `admin.domain` set or not. The admin
       container's `BACKEND_URL` names one of them at its Service port — the public port 404s every Server-Owner
       operation, so an admin surface pointed there works for nothing — and by its FULL name,
       `<service>.<namespace>.svc.<clusterDomain>`: the admin image's nginx resolves its upstream with its own
       `resolver`, which applies no search list, so a short name is NXDOMAIN on a cluster. The domain must be the
       render's `clusterDomain` (the chart's default where the file sets none), and one extra render of the first
       `ci/` file into another namespace and another `clusterDomain` must follow both, so neither can be spelled.
  xiii.**A NetworkPolicy admits the public port from `networkPolicy.ingressFrom` exactly, and the admin port from
       the admin pod alone.** Every ingress rule of every NetworkPolicy selecting the app pods that reaches the
       admin port — a rule listing it by name or number, or a rule with no `ports` at all, which reaches every
       port — has a `from` whose every peer is a `podSelector.matchLabels` selecting the admin pod and no other
       pod in the render: no `ipBlock`, no `namespaceSelector`, no empty selector, no `matchExpressions` this
       cannot evaluate. Every rule that reaches a public port has a `from` equal to the values file's
       `ingressFrom` (ADR 0071 §3: "anywhere" is how any pod forged the forwarded client address). Policies are a
       union, so one permissive rule anywhere opens the port; that is why every rule is read. The positive control
       on the same predicate: some rule admits the public port from `ingressFrom`, or the policy has cut the
       gateway's data plane off from the app. Skipped in a render whose values turn policies off; (xvii) owns that.
  xv.  **A default-deny selects every rendered pod.** Some NetworkPolicy has both `Ingress` and `Egress` in
       `policyTypes`, no rule in either, and a `podSelector.matchLabels` that selects every workload's pod
       template — so every other policy is an allow-list rather than a widening of an open default.
  xvi. **The database's peers are the app and, with the backup gate on, the daily prune.** Every ingress rule of
       every policy selecting the bundled MariaDB pod (the container whose image repository is `mariadb`) admits only
       bare `podSelector`s each matching exactly one of those pods and no other — the prune reads B there (ADR 0056
       decision 8) and nothing else may; and, the positive control, at least one rule admits the app, or the
       default-deny cut the app off its database. A render without the bundled database (an operator's own) has no
       pod to check.
  xvii.**`networkPolicy.enabled=false` renders no NetworkPolicy at all,** the admin listener's included — the
       operator who turns it off owns reachability, and a policy left behind would be one they do not know they
       have. The positive control: the same file's default render carries some.
  xviii.**The schema refuses an enabled policy set with no gateway data plane named, naming `ingressFrom`** — both
       an empty list and no key — while the same lint with it set passes. The chart never guesses the data
       plane's namespace or labels (ADR 0071 §3), so an install without the key must fail and say why.
  xi.  **The schema refuses the three contradictions of the opt-in, naming the key** — `acme.enabled` with a
       mode other than `per-domain`, with an empty `email`, and beside an operator's own
       `cert-manager.io/cluster-issuer` annotation — each a `helm lint` that must exit non-zero AND whose output
       names the offending key; the same lint without the contradiction must pass (the positive control). An
       exit code alone cannot tell "the schema held" from any other failure. Measured on helm v4.0.1: lint
       prints the schema violation on STDOUT (`[ERROR] values.yaml: - at '/gateway/…'`) and only a summary on
       stderr, so both streams are read.
  xiv. **A digest the values pin is the digest the pod runs.** Where a values file sets `image.digest` /
       `admin.image.digest` / `backup.image.digest`, every container (init containers included) running that image
       renders as `…@<that digest>`; where it sets none, the image carries no digest at all. Some file in `ci/` must
       set all three, or the digest render is checked by nobody, and `helm lint` with a digest that is not
       `sha256:<64 hex>` must be refused naming `digest`.
  xix. **The app container's store variables are the bundled-store hint, and no credential of a store**
       (cr-store-connection-in-admin-web phase 4, ADR 0089): no `VEYQ_BLOB_STORE_*` variable is present — the store
       setting lives in the database — and with `garage.enabled` `VEYQ_BUNDLED_STORE_ENDPOINT`, `_ADMIN_ENDPOINT` and
       `_ADMIN_TOKEN` are; without it no container of the render (the app's, the backup gate's, any) carries a
       `VEYQ_BUNDLED_STORE_*` variable (ADR 0064 § the bundled store is opt-in: a hint without the store makes the
       console offer one that is not there). Some file in `ci/` must render each side.
  xx.  **No rendered object is a `networking.k8s.io` `Ingress`** (ADR 0064: routing is the Gateway API, and the
       chart keeps nothing for `Ingress`). A render that brings one back is red whatever it routes.
  xxi. **Every route hangs off the chart's one `Gateway`, on the listener the TLS mode names, and the `Gateway`
       has the listeners the backend's sink attaches to.** Exactly one `Gateway`, named what the properties
       file's `gateway-name` says; `allowedListeners` from `Same` namespace only, so the backend's `ListenerSet`s
       attach and no other namespace's do; a plain-HTTP listener on port 80 with no hostname named the sink's
       `HTTP_LISTENER`; under `shared` an HTTPS listener with no hostname named the sink's `SHARED_HTTPS_LISTENER`,
       referencing the values' `secretName`. Both names are read out of `KubernetesGatewaySink.kt`, because a
       renamed listener leaves every tenant route unattached with no error on either side. Every rendered
       `HTTPRoute`'s every parent is that `Gateway` by a `sectionName` it has; under `none` every route's is the
       HTTP listener; otherwise every route but the redirect sits on an HTTPS listener, and under `per-domain` that
       listener's `hostname` is the route's one host and its `certificateRefs` the route's OWN name — the shape the
       sink gives a tenant's `ListenerSet`, held on the chart's own hosts. And under `per-domain`/`shared` exactly
       one route on the HTTP listener redirects to `https`, with no hostname, so cert-manager's host-and-exact-path
       challenge routes outrank it.
  xxii.**The backend's Role grants the two kinds the sink writes and nothing on `gateways` or `ingresses`.** Every
       rule naming `gateway.networking.k8s.io` grants only `httproutes` and `listenersets` (no `gateways`, no `*`),
       and some rule grants both — the positive control, or the sink could not reconcile at all (ADR 0064: the
       backend can neither add nor change a listener on the chart's `Gateway`).
  xxiii.**The PROXY protocol is on iff the probes poll the admin listener iff `app.clientPort.source` is
       `proxyProtocol`** (ADR 0094 decision 7). In that render the app container carries
       `QUARKUS_HTTP_PROXY_USE_PROXY_PROTOCOL=true` and `VEYQ_HTTP_CLIENT_PORT_SOURCE=proxy-protocol`, and every
       `httpGet` probe it has resolves (by name or number) to the admin listener's port, the one (xii) reads; in every
       other render neither variable says PROXY and every probe resolves to another port — the public listener, which
       the probes then also prove. A probe on the public port under PROXY is a pod that never becomes ready (a kubelet
       sends no PROXY header, and the app closes such a connection unanswered); PROXY off with the probes on the admin
       port is a public listener nothing checks. Some file in `ci/` must render `proxyProtocol`, or that branch is
       checked by nobody; and `helm lint` with a source outside the enum must be refused naming `clientPort`.
  xxiv.**With `backup.enabled`, the app's Deployment runs exactly one init container, `backup-gate`, on the tools
       image (`veyq-tools`), mounting the claim `backup.existingClaim` names and NOT the config-secret claim — the
       volume of the app container's mount covering the KEK path, found as (ii) finds it — with
       `VEYQ_BACKUP_TARGET_RELEASE` equal to the app image's tag** (ADR 0098 decisions 1, 2 and 4c: a capture on the
       backup claim can never hold the KEK, and the gate judges the release about to migrate).
  xxv.**With `backup.enabled: false`, the Deployment runs no init container and declares no volume its containers
       do not mount** — and, the positive control on the same file, rendering it with the other choice
       (`--set backup.enabled=…`) flips the gate: every file is rendered both ways.
  xxvi. **The schema refuses an install that makes neither backup choice, naming `/backup`** (the gate on with no
       claim), and accepts each of the two (a claim named; `enabled: false`) — ADR 0098's explicit choice (owner 2b).
  xxviii.**With `backup.enabled`, exactly one CronJob, `veyq-backup-prune`, prunes the backup claim on the tools
       image, its pod mounting the claim `backup.existingClaim` names and no other claim — never the config-secret
       claim — its one container running `prune` (ADR 0056 decision 8: the daily prune of every capture older than B;
       a capture on the claim can never hold the KEK, and nothing the prune writes is anywhere else); with the gate off,
       no CronJob at all.
  xxvii.**The bundled store keeps no request log, and runs only where it is enabled.** Every container whose image
       repository is `garage` sets `RUST_LOG`, and no directive in it is `info`, `debug` or `trace` (ADR 0092 § 5):
       unset, the image's own `garage=info` logs every S3 and admin request with the peer's address and the URI with
       its query. The compose recipe's half is `validate_compose_posture.py` assertion 6. The positive control: every
       render with `garage.enabled` has such a container, or the level is checked by nobody; and a render without it
       has none (ADR 0064: off, the chart renders none of Garage's objects).
  xxix.**Each of the bundled store's claims takes its own `storageClass`, else the shared one.** Every render with
       `garage.enabled` is rendered once more with the shared class and `data`'s own set to two probe names: the
       store's `data` claim template carries `data`'s, and `meta` and `snapshots` carry the shared one. The split is
       what lets the object blocks sit on a network share while `meta`'s LMDB stays on a local class (LMDB rules
       out remote filesystems); a template that reads only the shared key moves `meta` along with `data`, and one
       that drops the fallback leaves `meta` on the cluster's default class although the operator named one.

The app container is recognised the way the compose guards recognise the app service: an image whose
repository is `veyq` — never `veyq-admin` or any other veyq-namespaced sibling — with the datasource
environment as the fallback marker. A render with no app container is a failure, not a green run over nothing;
so is an empty `ci/` directory.

**The defect that bought it — none in the chart itself, which is new with this file; each assertion pins a
defect that was live elsewhere.** (i) is the sink's own deletion semantics, held on the backend half by
`KubernetesGatewaySinkTest.anHttpRouteThisSinkDoesNotOwnSurvivesARegenerate` and
`KubernetesGatewayTlsPerDomainTest.aListenerSetThisSinkDoesNotOwnSurvivesARegenerate`; this is the chart half. (ii) is
inbox #26 finding (g), found at refine by measuring that a PVC does not seed ownership — the same
`AccessDeniedException` restart-loop the compose recipe already paid for once
(`validate_app_volume_ownership.py`'s docstring). (iii) is the migration at boot with no gate, which ADR 0096
keeps (it decides who starts an upgrade, not how the schema moves), stated as a constraint so a change to it finds
this. (iv) and (v) were measured at refine and are
pinned so they cannot be re-argued into the chart. (vii)–(xi) came with the ACME opt-in, each written red
against the chart before the opt-in existed; (x) apart from (ix) because removing the helper from the
ConfigMap alone was the injection that turned only one of them red. (xii) and (xiii) came with the admin
channel (uc.admin.admin-channel), both run red against the chart before its admin Service and policy existed.
(xii)'s full-name half was live when written: the chart rendered `http://veyq-app-admin:8090`, which Compose's DNS
would have answered and a cluster's did not — the first install on a real cluster (single-node k3s, 2026-11-05) got
`could not be resolved (3: Host not found)` from nginx and a 502 on `/api/setup`, so first-run setup could not start.
Run against that template it was red; it was bitten by injection (the short name, the namespace spelled `default`).
(xiv) came with deploy-by-digest (cr-image-provenance-verifiable, ADR 0057 §3): no incident in the chart, which had
no digest value at all; it pins that the digest an operator verified is the one the pod pulls, and each of its
three halves was bitten by injection (template, schema pattern, the `ci/` file). The read-only root in (v), the
narrowed public port in (xiii) and (xv)–(xviii) came with the NetworkPolicy set (ADR 0071, inbox #162): live when
written — no container had a read-only root, and the one policy admitted the app's public port from any pod in the
cluster, which could therefore forge the forwarded address the per-IP rate limiter keys on. Run against that chart,
each of them was red; each was then bitten by a template or schema injection. (xx)–(xxii) came with the move from
`Ingress` to the Gateway API (cr-helm-gateway-api): the chart rendered three `Ingress` objects and a Role on
`ingresses` when they were written, and each was bitten by a template injection. (xxiii) came with the client port's
sources (cr-client-port-sources, ADR 0094 decision 7): no incident — the chart had no PROXY setting — built because the
three must move together and a probe that polls a PROXY listener fails only on a cluster; bitten by template
injections (the env without the probes, the probes without the env) and the `ci/` file's removal. (xxiv)–(xxvi) came with the backup
gate (cr-kubernetes-backup-gate, ADR 0098) — no incident: they pin, on the render, the decision that the gate never
mounts the KEK's claim and judges the app's own release, and that an install chooses; each was bitten by injection
(the config-secret mount, a hard-coded target, the schema's `anyOf` removed). The trap they record: give the gate
`QUARKUS_DATASOURCE_JDBC_URL` and the app-container recognition below takes it for the app and demands the KEK's
claim of it — the gate carries `VEYQ_BACKUP_DB_*` instead. (xxvii) came with a legal audit (2026-10-18), live when
written: the chart set no `RUST_LOG`, so the store ran at its image's `garage=info`; run against that chart it was red in
every render, and it was bitten by injection (the env removed from the template, `garage.logFilter=garage=info`).
(xix) and (xxvii) became conditional on `garage.enabled` when the bundled store turned opt-in on this target (ADR 0064,
inbox #521); each direction was bitten by injection — the StatefulSet, the app's hint and the gate's hint each
rendered unconditionally, the app's hint dropped where on, and no `ci/` file left turning it on.
(xxix) came with the per-claim store classes (2026-11-13) — no incident: an operator asked to put the store's blocks
on a Hetzner Storage Box over SMB, and the one shared class would have taken `meta`'s LMDB there too. It was bitten by
injection both ways: the template reading only the shared key (`data` on the shared class) and the fallback dropped
(`meta` and `snapshots` on no class).

**Its limits — what a green run does NOT prove.** It renders the chart; it never installs it. So it proves what
the manifests say and nothing about a cluster: not that a StorageClass exists for the claim, not that the
Gateway API CRDs are installed or that the rendered objects pass their schemas (no CRD schema is in this tree —
a field the CRD rejects renders green here), not that the implementation behind `gateway.className` accepts a
route, supports `ListenerSet` or the redirect's Extended `scheme`, not that cert-manager is installed or that an `Issuer` it accepts
ever obtains a certificate (no cluster runs here — HTTP-01 reachability, the CA, the account key's lifecycle are
all outside), not that the images exist to be pulled, not that a `restricted` namespace actually admits the pods
(the standard has fields this does not enumerate — it checks the four the chart sets, and a future field the
standard adds is out of reach). It renders only the values files in `ci/`, so a value path none of them sets is
checked at its default alone — `mariadb.enabled` false and `gateway.tls.mode: per-domain` without the opt-in are
rendered by nobody here (`shared`, with `admin.domain` set, is `shared-values.yaml`'s). (xiii) proves the policy is written, not that a CNI
enforces it — on a cluster whose CNI ignores NetworkPolicy, any pod reaches the admin port (ADR 0065 residual
(ii)); the same holds for (xv)–(xvii), and the egress the policies narrow is rendered but asserted by nobody. (v)
requires the read-only root, not that each container has an `emptyDir` for every path it writes: a missed scratch
path renders green and fails at runtime, which is why ADR 0071 measures those paths by running the images. (xvi)
recognises the database by its image repository, so a database image of another name is out of reach. (ii) checks
the app container's mount and the pod's
`fsGroup`; it does not check the claim's access mode or that the same claim is not also mounted elsewhere.
(xxiii) proves what the pod is told, not that the operator's Gateway sends PROXY to the app's routes and to them alone —
the chart renders no Gateway-implementation object — nor that a kubelet probe passes the NetworkPolicy on the admin
port (Kubernetes documents traffic from a pod's own node as always allowed; no cluster runs here). (xi)
checks three contradictions, not the schema's completeness. (xiv) proves the rendered reference, not that the
digest exists or is the one `site/src/content/docs/run/verify-a-release.md` verified — the chart cannot see the verification.
(xxiv) proves what the init container is handed, not what it does: the gate's decisions are
`src/tools/instance_backup_selftest.py`'s, and that the claim is writable under `fsGroup`, that `restricted` admits the
pod and that the gate captures cold were measured once, on a kind cluster (cr-kubernetes-backup-gate), not here. And
it recognises the config-secret claim only through the app container's KEK path; a key the app reads from elsewhere
is out of its reach. (xxvii) reads what the container is told, not what Garage does with it (that `warn` drops the
request line was measured on the pinned image), and the kubelet's log rotation, which bounds every container's log on
this target, is the cluster's and not in any manifest. And it says nothing about the compose recipe, which
its sibling guards own (`validate_compose_posture.py` holds that target's half of ADR 0071).

**Why it sits OUTSIDE the `validate_*` glob.** It shells out to `helm`, which is not a repository artifact,
and the glob's contract (`trust-chain.yml`) is buildless and diff-free: a `validate_*` guard that needs a tool
outside the repo is green wherever that tool is missing. The precedent is `mutation_meta_check*.py`, which
sit outside the glob for the same reason (they need a built report). `checks.yml`'s `chart` job runs it, with
Helm installed from the release tarball pinned there by SHA-256, and `publish` waits on it — a job
no run has executed yet (this repository has no remote). Run it locally with `helm` on the PATH; without `helm` it
exits 2 and says so, never 0:

    python3 scripts/chart_meta_check.py
"""
from __future__ import annotations  # `str | None` below — the repo's guards run on Python 3.9

import pathlib
import posixpath
import re
import subprocess
import sys

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here
import chart_render  # the one `helm template` both chart scripts render through
from chart_render import objects

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the other guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

import repo_layout  # noqa: E402 - where the deployment files and the test harnesses live (ADR 0106)

ROOT = pathlib.Path(__file__).resolve().parent.parent
CHART = repo_layout.CHART_DIR
CI_DIR = CHART / "ci"
SINK_KT = backend_tree.source_file("me.raddatz.veyq.core.tenant.KubernetesGatewaySink")
CONFIG_KT = backend_tree.source_file("me.raddatz.veyq.core.config.VeyqConfig")
DOCKERFILE = repo_layout.APP_DOCKERFILE

ENV_KEY = "VEYQ_SECURITY_CONFIG_SECRET_FILE"
LEDGER_ENV = "VEYQ_ERASURE_LEDGER_FILE"
# The same anchors the compose guards use, for the same reasons.
STAGE = re.compile(r"^\s*FROM\s+\S+(?:\s+--\S+)*\s+AS\s+(\S+)\s*$", re.IGNORECASE | re.MULTILINE)
WORKDIR = re.compile(r"^\s*WORKDIR\s+(\S+)\s*$", re.IGNORECASE)
USER = re.compile(r"^\s*USER\s+(\S+)\s*$", re.IGNORECASE)
KT_DEFAULT = re.compile(r'@WithDefault\("([^"]*)"\)\s*\n\s*fun\s+configSecretFile\s*\(\s*\)')
# `const val OWNER_LABEL = "…"` / `OWNER_VALUE` / the two listener names — the sink's own constants, read not retyped.
KT_ADMIN_PORT = re.compile(r'interface\s+AdminChannel\s*\{.*?@WithDefault\("(\d+)"\)\s*\n\s*fun\s+port\s*\(\s*\)', re.DOTALL)
ADMIN_PORT_ENV = "VEYQ_ADMIN_CHANNEL_PORT"
# (xii) the cluster domain a render without its own `clusterDomain` gets — the chart's default, read, never spelled —
# and the namespace and domain of the probe render that proves both are read rather than spelled in the template.
DEFAULT_CLUSTER_DOMAIN = str((yaml.safe_load((CHART / "values.yaml").read_text()) or {}).get("clusterDomain") or "")
PROBE_NAMESPACE, PROBE_CLUSTER_DOMAIN = "veyq-probe-ns", "probe.internal"
# (xxiii) the two variables the PROXY source sets together, and the probes they move.
PROXY_ENV = "QUARKUS_HTTP_PROXY_USE_PROXY_PROTOCOL"
PORT_SOURCE_ENV = "VEYQ_HTTP_CLIENT_PORT_SOURCE"
PROBES = ("startupProbe", "readinessProbe", "livenessProbe")
KT_CONST = re.compile(r'const\s+val\s+(OWNER_LABEL|OWNER_VALUE|HTTP_LISTENER|SHARED_HTTPS_LISTENER)\s*=\s*"([^"]+)"')
APP_IMAGE = re.compile(r"(?:^|/)veyq(?::|$)")
DB_IMAGE = re.compile(r"(?:^|/)mariadb(?::|$)")
ADMIN_IMAGE = re.compile(r"(?:^|/)veyq-admin(?::|$)")
TOOLS_IMAGE = re.compile(r"(?:^|/)veyq-tools(?::|$)")
# (xix) the bundled-store hint's variables — present on the app with garage.enabled, on no container without it.
BUNDLED_HINT = "VEYQ_BUNDLED_STORE_"
# (xxvii) the bundled store, by its image repository, and the RUST_LOG levels that log every request.
STORE_IMAGE = re.compile(r"(?:^|/)garage(?::|@|$)")
VERBOSE_LEVELS = {"info", "debug", "trace"}
# (xxiv)–(xxvi) the backup gate (ADR 0098): its container name and the variable its target release travels in.
GATE_NAME = "backup-gate"
GATE_TARGET_ENV = "VEYQ_BACKUP_TARGET_RELEASE"
# (xxviii) the daily prune of every capture older than B (ADR 0056 decision 8).
PRUNE_NAME = "veyq-backup-prune"
WORKLOADS = {"Deployment", "StatefulSet", "DaemonSet", "Job", "CronJob"}
# Kinds that exist only cluster-wide. The chart installs into one namespace and holds nothing beyond it.
CLUSTER_SCOPED = {
    "ClusterIssuer", "ClusterRole", "ClusterRoleBinding", "Namespace", "CustomResourceDefinition",
    "PersistentVolume", "StorageClass", "IngressClass", "GatewayClass", "PriorityClass",
    "ValidatingWebhookConfiguration", "MutatingWebhookConfiguration",
}
# The opt-in's contract names, named in ADR 0064. The Issuer's is the one derived name (`veyq.acmeIssuer`): one per ACME
# directory, so that switching the directory renames it and cert-manager re-orders every certificate by itself.
ACME_DIRECTORIES = {"https://acme-v02.api.letsencrypt.org/directory": "production",
                    "https://acme-staging-v02.api.letsencrypt.org/directory": "staging"}
ACME_DEFAULT_SERVER = "https://acme-v02.api.letsencrypt.org/directory"  # values.yaml's gateway.tls.acme.server


def acme_issuer_name(server: str) -> str:
    """`veyq.acmeIssuer`, read the other way: Let's Encrypt's two directories by name, any other CA by its URL's hash."""
    import hashlib
    return f"veyq-acme-{ACME_DIRECTORIES.get(server) or hashlib.sha256(server.encode()).hexdigest()[:8]}"


ISSUER_ANNOTATION = "cert-manager.io/issuer"
# A ListenerSet's HTTP-01 challenge goes to the parent Gateway's port-80 listener (cert-manager 1.21; ADR 0064).
FALLBACK_ANNOTATION = "acme.cert-manager.io/http01-parentreffallback"
# Matched as a substring: the issuer keys and `acme.cert-manager.io/` alike are the opt-in's trace.
CERT_MANAGER_PREFIX = "cert-manager.io/"
GATEWAY_GROUP = "gateway.networking.k8s.io"
APP_PROPERTIES = "application.properties"
# (xi) the contradictions: the `--set` that makes an opted-in values file contradictory, and the key its refusal
# must name. `mode=shared` carries a secretName so the refusal cannot be the `shared` rule's instead.
CONTRADICTIONS = [
    (["--set", "gateway.tls.mode=shared", "--set", "gateway.tls.secretName=x"], "/gateway/tls/mode"),
    (["--set", "gateway.annotations.cert-manager\\.io/cluster-issuer=x"], "cert-manager.io~1cluster-issuer"),
]

errors: list[str] = []


WHO = "chart meta check"


def render(values: pathlib.Path, extra: list[str] | None = None) -> str:
    # The render itself is chart_render's, shared with chart_upgrade_check.py; only the error bookkeeping is here.
    out = chart_render.template(WHO, CHART, values, extra)
    if out.returncode != 0:
        errors.append(f"{values.name}: `helm template` failed:\n{out.stderr.strip()}")
        return ""
    return out.stdout


def lint(values: pathlib.Path, extra: list[str]) -> tuple[int, str]:
    out = subprocess.run(
        [chart_render.helm(WHO), "lint", str(CHART), "-f", str(values), *extra],
        cwd=ROOT, capture_output=True, text=True,
    )
    return out.returncode, out.stdout + out.stderr


def name_of(obj: dict) -> str:
    return f"{obj.get('kind')}/{(obj.get('metadata') or {}).get('name')}"


def label_maps(obj: dict) -> list[tuple[str, dict]]:
    """Every map of labels an object carries — metadata, pod template, selectors — with where it was found."""
    found: list[tuple[str, dict]] = []
    meta = obj.get("metadata") or {}
    if isinstance(meta.get("labels"), dict):
        found.append(("metadata.labels", meta["labels"]))
    spec = obj.get("spec") or {}
    selector = spec.get("selector") or {}
    if isinstance(selector, dict):
        if isinstance(selector.get("matchLabels"), dict):
            found.append(("spec.selector.matchLabels", selector["matchLabels"]))
        elif obj.get("kind") == "Service":
            found.append(("spec.selector", selector))
    if pod_spec(obj) is not None and pod_labels(obj):
        found.append(("the pod template's metadata.labels", pod_labels(obj)))
    return found


def pod_spec(obj: dict) -> dict | None:
    if obj.get("kind") not in WORKLOADS:
        return None
    spec = obj.get("spec") or {}
    if obj["kind"] == "CronJob":
        spec = ((spec.get("jobTemplate") or {}).get("spec") or {})
    return ((spec.get("template") or {}).get("spec")) or None


def pod_labels(obj: dict) -> dict:
    """The labels of a workload's pod template — under `jobTemplate` for a CronJob, as [pod_spec] reads its spec."""
    spec = obj.get("spec") or {}
    if obj.get("kind") == "CronJob":
        spec = ((spec.get("jobTemplate") or {}).get("spec") or {})
    return ((spec.get("template") or {}).get("metadata") or {}).get("labels") or {}


def route_backends(obj: dict) -> list[str]:
    """Every Service an `HTTPRoute` sends traffic to — the backend refs of every rule (a ref's kind defaults to
    Service, and a ref of another kind is no Service of this chart)."""
    if obj.get("kind") != "HTTPRoute":
        return []
    return [
        ref.get("name")
        for rule in (obj.get("spec") or {}).get("rules") or []
        for ref in rule.get("backendRefs") or []
        if ref.get("kind", "Service") == "Service"
    ]


def is_app_container(container: dict) -> bool:
    image = str(container.get("image") or "")
    if image and APP_IMAGE.search(image):
        return True
    return any(e.get("name") == "QUARKUS_DATASOURCE_JDBC_URL" for e in container.get("env") or [])


def runtime_stages() -> dict[str, str]:
    body = DOCKERFILE.read_text()
    stages = [(m.start(), m.group(1)) for m in STAGE.finditer(body)]
    out: dict[str, str] = {}
    for index, (offset, name) in enumerate(stages):
        end = stages[index + 1][0] if index + 1 < len(stages) else len(body)
        if name.startswith("runtime-"):
            out[name] = body[offset:end]
    return out


def last_match(pattern: re.Pattern, stage_body: str) -> str | None:
    found = [m.group(1) for line in stage_body.splitlines() for m in [pattern.match(line)] if m]
    return found[-1] if found else None


def resolve_secret_path(container: dict, workdir: str | None, default: str | None) -> str | None:
    for e in container.get("env") or []:
        if e.get("name") == ENV_KEY:
            value = e.get("value")
            return posixpath.normpath(str(value)) if isinstance(value, str) and value.startswith("/") else None
    if default is None:
        return None
    if default.startswith("/"):
        return posixpath.normpath(default)
    return posixpath.normpath(posixpath.join(workdir, default)) if workdir else None


def covers(path: str, mount_point: str) -> bool:
    mount_point = mount_point.rstrip("/") or "/"
    return path == mount_point or path.startswith(mount_point + "/")


def check_own_key(tag: str, obj: dict, container: dict, volumes: dict, pvc_names: set, path: str, secret: str) -> None:
    """(ii) with app.configSecret.existingSecret: the key on a read-only mount of that Secret, the ledger on a claim."""
    mount = next((m for m in container.get("volumeMounts") or [] if covers(path, str(m.get("mountPath", "")))), None)
    source = ((volumes.get((mount or {}).get("name")) or {}).get("secret") or {})
    if mount is None or source.get("secretName") != secret:
        errors.append(f"(ii) {tag}: {name_of(obj)}: app.configSecret.existingSecret is `{secret}`, but `{path}` is not on a mount "
                      f"of that Secret ({mount}) — the app would generate a key of its own beside the operator's")
    elif mount.get("readOnly") is not True:
        errors.append(f"(ii) {tag}: {name_of(obj)}: the operator's key `{secret}` is mounted writable at `{mount.get('mountPath')}` — "
                      f"the server never writes a key that is there, so nothing needs to (ADR 0118)")
    ledger = next((e.get("value") for e in container.get("env") or [] if e.get("name") == LEDGER_ENV), None)
    claim_mount = next((m for m in container.get("volumeMounts") or []
                        if isinstance(ledger, str) and covers(posixpath.normpath(ledger), str(m.get("mountPath", "")))), None)
    claim = ((volumes.get((claim_mount or {}).get("name")) or {}).get("persistentVolumeClaim") or {}).get("claimName")
    if claim is None or claim not in pvc_names or (claim_mount or {}).get("readOnly"):
        errors.append(f"(ii) {tag}: {name_of(obj)}: with the key from a Secret, {LEDGER_ENV} is {ledger!r}, not on a writable claim this "
                      f"render creates — the ledger would default beside the key, into the read-only Secret (ADR 0056 decision 8)")


def selects(match_labels: dict, labels: dict) -> bool:
    return all(labels.get(k) == v for k, v in match_labels.items())


def port_hits(entry, port_name: str | None, port_number: int) -> bool:
    """Whether a Service targetPort or a NetworkPolicy port entry reaches the given container port."""
    if isinstance(entry, dict):
        port, end = entry.get("port"), entry.get("endPort")
        if port is None:
            return True  # a NetworkPolicy port entry with no port matches every port of its protocol
        if isinstance(port, int) and isinstance(end, int):
            return port <= port_number <= end
        return port_hits(port, port_name, port_number)
    return entry == port_number or (port_name is not None and entry == port_name)


def backup_enabled(values: dict) -> bool:
    return ((values.get("backup") or {}).get("enabled", True)) is not False  # the chart's default is true


def garage_enabled(values: dict) -> bool:
    return ((values.get("garage") or {}).get("enabled", False)) is True  # the chart's default is false (ADR 0064)


def every_container(spec: dict | None) -> list[dict]:
    return list((spec or {}).get("containers") or []) + list((spec or {}).get("initContainers") or [])


def check_image_digests(tag: str, values: dict, rendered: list[dict]) -> None:
    """(xiv) a digest the values set is the digest the pod runs, and one they leave unset adds none."""
    wanted = {
        "app": (((values.get("image") or {}).get("digest")) or "", APP_IMAGE),
        "admin": ((((values.get("admin") or {}).get("image") or {}).get("digest")) or "", ADMIN_IMAGE),
        "tools": ((((values.get("backup") or {}).get("image") or {}).get("digest")) or "", TOOLS_IMAGE),
    }
    for which, (digest, pattern) in wanted.items():
        if digest:
            digests_set.add(which)
        images = [
            str(c.get("image") or "")
            for obj in rendered
            for c in every_container(pod_spec(obj))
            if pattern.search(str(c.get("image") or "").split("@", 1)[0])
        ]
        if not images and which == "tools" and not backup_enabled(values):
            continue  # the gate is off: no container runs the tools image, by (xxv)
        if not images:
            errors.append(f"(xiv) {tag}: no container runs the {which} image, so its digest reached nothing this could check")
        for image in images:
            if digest and not image.endswith(f"@{digest}"):
                errors.append(
                    f"(xiv) {tag}: the {which} image renders as `{image}` although the values pin `{digest}` — the "
                    f"operator verified that digest (https://veyq.app/run/verify-a-release/), and the pod pulls something else"
                )
            if not digest and "@" in image:
                errors.append(f"(xiv) {tag}: the {which} image renders as `{image}` with a digest the values never set")


def image_tag(image: str) -> str | None:
    """The tag of `registry/repo:tag[@digest]` — None for an image without one."""
    last = image.split("@", 1)[0].rsplit("/", 1)[-1]
    return last.split(":", 1)[1] if ":" in last else None


def check_backup_gate(tag: str, enabled: bool, claim: str | None, rendered: list[dict]) -> bool:
    """(xxiv) with the gate on, exactly one init container `backup-gate` on the tools image, mounting the named claim
    and NOT the config-secret claim, its target release the app image's tag; (xxv) with it off, no init container
    and no volume no main container mounts. Returns whether the render carries the gate."""
    app = next(((o, s, c) for o in rendered if o.get("kind") == "Deployment" for s in [pod_spec(o)]
                for c in (s or {}).get("containers") or [] if is_app_container(c)), None)
    if app is None:
        return False  # reported elsewhere: no app container
    obj, spec, container = app
    inits = spec.get("initContainers") or []
    volumes = {v.get("name"): v for v in spec.get("volumes") or []}

    def claim_of(volume: str | None) -> str | None:
        return ((volumes.get(volume) or {}).get("persistentVolumeClaim") or {}).get("claimName")

    if not enabled:
        mounted = {m.get("name") for c in spec.get("containers") or [] for m in c.get("volumeMounts") or []}
        if inits:
            errors.append(f"(xxv) {tag}: backup.enabled is false and {name_of(obj)} still runs init container(s) {[c.get('name') for c in inits]}")
        for leftover in sorted(set(volumes) - mounted):
            errors.append(f"(xxv) {tag}: backup.enabled is false and {name_of(obj)} still declares volume `{leftover}`, which no container mounts")
        return bool(inits)
    gates = [c for c in inits if c.get("name") == GATE_NAME]
    if len(inits) != 1 or len(gates) != 1:
        errors.append(f"(xxiv) {tag}: {name_of(obj)} runs init containers {[c.get('name') for c in inits]} — with backup.enabled, exactly one, `{GATE_NAME}`")
        return bool(gates)
    gate = gates[0]
    image = str(gate.get("image") or "")
    if not TOOLS_IMAGE.search(image.split("@", 1)[0]):
        errors.append(f"(xxiv) {tag}: `{GATE_NAME}` runs `{image}`, not the veyq-tools image — the one capture implementation is released there (ADR 0098)")
    gate_claims = {claim_of(m.get("name")): m.get("name") for m in gate.get("volumeMounts") or []}
    if claim not in gate_claims:
        errors.append(f"(xxiv) {tag}: `{GATE_NAME}` mounts claims {sorted(c for c in gate_claims if c)}, not backup.existingClaim `{claim}` — the captures would go nowhere the operator named")
    # The KEK's claim, found the way (ii) finds it: the app container's mount covering the resolved config-secret path.
    path = resolve_secret_path(container, workdir, default_path)
    kek = next((m.get("name") for m in container.get("volumeMounts") or [] if path and covers(path, str(m.get("mountPath", "")))), None)
    if kek is not None and (kek in gate_claims.values() or (claim_of(kek) and claim_of(kek) in gate_claims)):
        errors.append(
            f"(xxiv) {tag}: `{GATE_NAME}` mounts `{kek}`, the config-secret claim — a capture on the backup claim could then "
            f"hold the KEK, the leaked backup ADR 0056 §1 exists to prevent; the gate never reads the key (ADR 0098 decision 4c)"
        )
    target = next((e.get("value") for e in gate.get("env") or [] if e.get("name") == GATE_TARGET_ENV), None)
    app_release = image_tag(str(container.get("image") or ""))
    if target is None or target != app_release:
        errors.append(
            f"(xxiv) {tag}: `{GATE_NAME}`'s {GATE_TARGET_ENV} is {target!r}, but the app container starts release "
            f"{app_release!r} — the gate would judge a release change that is not the one about to migrate"
        )
    return True
def check_backup_prune(tag: str, enabled: bool, claim: str | None, rendered: list[dict]) -> bool:
    """(xxviii) with the gate on, one CronJob `veyq-backup-prune` on the tools image, mounting the named claim and no
    other — the config-secret claim never — its one container running `prune`; with it off, none. Returns whether one
    is rendered."""
    jobs = [o for o in rendered if o.get("kind") == "CronJob"]
    if not enabled:
        if jobs:
            errors.append(f"(xxviii) {tag}: backup.enabled is false and the chart still renders {[name_of(o) for o in jobs]}")
        return bool(jobs)
    prunes = [o for o in jobs if name_of(o) == f"CronJob/{PRUNE_NAME}"]
    if len(prunes) != 1:
        errors.append(f"(xxviii) {tag}: with backup.enabled, exactly one CronJob `{PRUNE_NAME}`; rendered {[name_of(o) for o in jobs]}")
        return bool(prunes)
    spec = pod_spec(prunes[0]) or {}
    containers = spec.get("containers") or []
    claims = sorted(((v.get("persistentVolumeClaim") or {}).get("claimName") or "") for v in spec.get("volumes") or []
                    if v.get("persistentVolumeClaim"))
    if claims != [claim]:
        errors.append(f"(xxviii) {tag}: `{PRUNE_NAME}` mounts the claims {claims} — exactly backup.existingClaim "
                      f"`{claim}`, and never the config-secret claim: a prune on the KEK's claim could reach the key")
    if len(containers) != 1 or not TOOLS_IMAGE.search(str(containers[0].get("image") or "").split("@", 1)[0]) \
            or (containers[0].get("args") or [None])[0] != "prune":
        errors.append(f"(xxviii) {tag}: `{PRUNE_NAME}` must run one container, the veyq-tools image's `prune`; it runs "
                      f"{[(c.get('image'), c.get('args')) for c in containers]}")
    if spec.get("initContainers"):
        errors.append(f"(xxviii) {tag}: `{PRUNE_NAME}` runs init containers, which nothing asked of it")
    return True


def check_client_port(tag: str, values: dict, rendered: list[dict]) -> None:
    """(xxiii) PROXY env iff probes on the admin listener iff app.clientPort.source is proxyProtocol."""
    source = ((values.get("app") or {}).get("clientPort") or {}).get("source") or "none"
    if source == "proxyProtocol":
        proxy_rendered.add(tag)
    for obj in rendered:
        spec = pod_spec(obj)
        for container in (spec or {}).get("containers") or []:
            if not is_app_container(container) or default_admin_port is None:
                continue
            env = {e.get("name"): str(e.get("value")) for e in container.get("env") or []}
            admin_port = int(env.get(ADMIN_PORT_ENV) or default_admin_port)
            admin_name = next((p.get("name") for p in container.get("ports") or [] if p.get("containerPort") == admin_port), None)
            proxy_env = env.get(PROXY_ENV) == "true"
            source_env = env.get(PORT_SOURCE_ENV)
            probes = {kind: (container.get(kind) or {}).get("httpGet") for kind in PROBES if container.get(kind)}
            on_admin = {kind: port_hits((get or {}).get("port"), admin_name, admin_port) for kind, get in probes.items()}
            want = source == "proxyProtocol"
            if proxy_env != want or (source_env == "proxy-protocol") != want:
                errors.append(
                    f"(xxiii) {tag}: {name_of(obj)}: app.clientPort.source is {source!r} but the container carries "
                    f"{PROXY_ENV}={env.get(PROXY_ENV)!r} and {PORT_SOURCE_ENV}={source_env!r} — the PROXY protocol and the "
                    f"port source are set together exactly for proxyProtocol, or the app refuses to start"
                )
            if want and not {"startupProbe", "readinessProbe"} <= set(probes):
                errors.append(f"(xxiii) {tag}: {name_of(obj)}: under proxyProtocol the app has probes {sorted(probes)}, not at least startup and readiness")
            for kind, admin in on_admin.items():
                if admin != want:
                    errors.append(
                        f"(xxiii) {tag}: {name_of(obj)}: the {kind} polls port {probes[kind].get('port')!r} with "
                        f"app.clientPort.source {source!r} — under the PROXY protocol a probe must poll the admin listener "
                        f"(the public port closes a connection with no PROXY header unanswered), and otherwise the public one"
                    )


def check_store_log(tag: str, garage: bool, rendered: list[dict]) -> None:
    """(xxvii) the bundled store's container sets RUST_LOG, at no level that logs every request — and runs only with
    garage.enabled."""
    stores = [c for obj in rendered for c in every_container(pod_spec(obj)) if STORE_IMAGE.search(str(c.get("image") or ""))]
    if not garage:
        if stores:
            errors.append(f"(xxvii) {tag}: garage.enabled is off and {len(stores)} container(s) still run the store's image — "
                          f"off renders none of Garage's objects (ADR 0064 § the bundled store is opt-in)")
        return
    if not stores:
        errors.append(f"(xxvii) {tag}: garage.enabled is on and no container runs the store's image — its log level is checked by nobody")
    for container in stores:
        env = {e.get("name"): str(e.get("value")) for e in container.get("env") or [] if "value" in e}
        rust_log = env.get("RUST_LOG")
        levels = {d.rpartition("=")[2].strip().lower() for d in (rust_log or "").split(",") if d.strip()}
        if not levels:
            errors.append(f"(xxvii) {tag}: the store container sets no RUST_LOG — the image's own `garage=info` logs every "
                          f"request with the peer's address and the URI with its query (ADR 0092 § 5)")
        elif levels & VERBOSE_LEVELS:
            errors.append(f"(xxvii) {tag}: the store container sets RUST_LOG={rust_log} — {sorted(levels & VERBOSE_LEVELS)} "
                          f"logs every request with the peer's address (ADR 0092 § 5)")


PROBE_SHARED_CLASS, PROBE_DATA_CLASS = "veyq-probe-shared", "veyq-probe-net"


def check_store_claims(tag: str, values_file: pathlib.Path) -> None:
    """(xxix) the store's `data` claim takes its own storageClass; `meta` and `snapshots` fall back to the shared one."""
    probe = ["--set", f"garage.persistence.storageClass={PROBE_SHARED_CLASS}",
             "--set", f"garage.persistence.data.storageClass={PROBE_DATA_CLASS}"]
    rendered = render(values_file, probe)
    if not rendered:
        errors.append(f"(xxix) {tag}: the render with {' '.join(probe)} failed, so the store's claim classes are unchecked")
        return
    stores = [o for o in objects(rendered)
              if any(STORE_IMAGE.search(str(c.get("image") or "")) for c in every_container(pod_spec(o)))]
    templates = {(t.get("metadata") or {}).get("name"): (t.get("spec") or {}).get("storageClassName")
                 for o in stores for t in (o.get("spec") or {}).get("volumeClaimTemplates") or []}
    want = {"meta": PROBE_SHARED_CLASS, "data": PROBE_DATA_CLASS, "snapshots": PROBE_SHARED_CLASS}
    for claim, cls in want.items():
        if claim not in templates:
            errors.append(f"(xxix) {tag}: the store has no `{claim}` claim template — its class is checked by nobody")
        elif templates[claim] != cls:
            errors.append(f"(xxix) {tag}: with {' '.join(probe)} the store's `{claim}` claim has class "
                          f"{templates[claim]!r}, not {cls!r} — each claim takes its own class, else the shared one, "
                          f"so `data` can sit on a network share while `meta`'s LMDB stays local")


def check_admin_channel(tag: str, rendered: list[dict], policy_enabled: bool, ingress_from: list,
                        cluster_domain: str, namespace: str | None = None) -> None:
    """(xii) and (xiii): the admin listener's Service, the routes that must not name it, and the policy.

    `cluster_domain` is the render's `clusterDomain`; `namespace`, when given, the namespace it was rendered into —
    the probe render passes both, so a namespace or a domain spelled into the template instead of read is a red."""
    workloads = [(o, s) for o in rendered if (s := pod_spec(o))]
    labels_of = {name_of(o): pod_labels(o) for o, _ in workloads}
    app = next(((o, c) for o, s in workloads for c in s.get("containers") or [] if is_app_container(c)), None)
    admin = next(((o, c) for o, s in workloads for c in s.get("containers") or [] if ADMIN_IMAGE.search(str(c.get("image") or ""))), None)
    if app is None or admin is None or default_admin_port is None:
        return  # reported elsewhere: no app container, no admin container, or no port anchor
    app_labels, admin_labels = labels_of[name_of(app[0])], labels_of[name_of(admin[0])]
    env = {e.get("name"): e.get("value") for e in app[1].get("env") or []}
    admin_port = int(env.get(ADMIN_PORT_ENV) or default_admin_port)
    declared = next((p for p in app[1].get("ports") or [] if p.get("containerPort") == admin_port), None)
    if declared is None:
        errors.append(f"(xii) {tag}: the app container declares no containerPort {admin_port} — the admin listener has no port a Service or a policy can name")
        return
    port_name = declared.get("name")
    public = [p for p in app[1].get("ports") or [] if p is not declared]

    # (xii) the listener's own Service
    listener_services: dict[str, int] = {}
    for obj in rendered:
        if obj.get("kind") != "Service":
            continue
        spec = obj.get("spec") or {}
        ports = spec.get("ports") or []
        hits = [p for p in ports if port_hits(p.get("targetPort", p.get("port")), port_name, admin_port)]
        if not hits or not selects(spec.get("selector") or {}, app_labels) or not spec.get("selector"):
            continue
        name = (obj.get("metadata") or {}).get("name")
        listener_services[name] = hits[0].get("port")
        if len(hits) != len(ports):
            errors.append(
                f"(xii) {tag}: {name_of(obj)} exposes the admin listener beside another port — every route aimed at "
                f"that Service could then reach it. The listener gets a Service of its own (ADR 0065)."
            )
    if not listener_services:
        errors.append(f"(xii) {tag}: no Service selects the app pods on the admin listener's port {admin_port} — the admin surface has nothing to reach")
    for obj in rendered:
        for backend in route_backends(obj):
            if backend in listener_services:
                errors.append(f"(xii) {tag}: {name_of(obj)} routes to {backend}, the admin listener's Service — no route may, admin.domain set or not (ADR 0065)")
    backend_url = next((e.get("value") for e in admin[1].get("env") or [] if e.get("name") == "BACKEND_URL"), None)
    target = re.fullmatch(r"https?://([^:/]+):(\d+)/?", str(backend_url or ""))
    service, _, suffix = (target.group(1) if target else "").partition(".")
    if target is None or listener_services.get(service) != int(target.group(2)):
        errors.append(
            f"(xii) {tag}: the admin container's BACKEND_URL is {backend_url!r}, not the admin listener's Service "
            f"({', '.join(f'{n}:{p}' for n, p in listener_services.items()) or 'none'}) — the public port 404s every Server-Owner operation"
        )
    else:
        # The Service's FULL name: nginx's `resolver` asks for the name as written, with no search list, so a short
        # name is NXDOMAIN on a cluster and first-run setup 502s (measured on a k3s cluster, 2026-11-05).
        full = re.fullmatch(r"([a-z0-9]([-a-z0-9]*[a-z0-9])?)\.svc\.(.+)", suffix)
        if full is None or full.group(3) != cluster_domain or (namespace is not None and full.group(1) != namespace):
            wanted = f"{service}.{namespace or '<namespace>'}.svc.{cluster_domain}"
            errors.append(
                f"(xii) {tag}: the admin container's BACKEND_URL names {target.group(1)!r}, not the Service's full name "
                f"{wanted!r} — the admin image's nginx resolves its upstream with its own `resolver`, which applies no "
                f"search list, so any other form is NXDOMAIN on a cluster and first-run setup 502s"
            )

    # (xiii) the policy
    if not policy_enabled:
        return  # (xvii) asserts that a disabled render carries no policy at all
    others = [labels for n, labels in labels_of.items() if n != name_of(admin[0])]
    public_from_ingress = False
    for policy, rule in ingress_rules(rendered, app_labels):
        ports = rule.get("ports")
        peers = rule.get("from")
        if ports is None or any(port_hits(p, q.get("name"), q.get("containerPort")) for p in ports for q in public):
            # The public port's peers are exactly the operator's ingressFrom — not "anywhere", which is what let any
            # pod in the cluster forge the forwarded client address (ADR 0071 §3, cc.security.rate-limit).
            if peers != ingress_from:
                errors.append(
                    f"(xiii) {tag}: {name_of(policy)} admits the app's public port from {peers!r}, not exactly "
                    f"networkPolicy.ingressFrom {ingress_from!r} (ADR 0071 §3)"
                )
            else:
                public_from_ingress = True
        if ports is not None and not any(port_hits(p, port_name, admin_port) for p in ports):
            continue
        if not only_pod(peers, admin_labels, others):
            errors.append(
                f"(xiii) {tag}: {name_of(policy)} admits the admin listener's port from {peers!r} — only a podSelector "
                f"matching the admin pod and no other pod may reach it (ADR 0065)"
            )
    if not public_from_ingress:
        errors.append(f"(xiii) {tag}: no NetworkPolicy rule admits the app's public port from networkPolicy.ingressFrom — the gateway's data plane could not reach the app")


def ingress_rules(rendered: list[dict], labels: dict) -> list[tuple[dict, dict]]:
    """Every ingress rule of every NetworkPolicy that selects a pod with these labels — policies are a union, so one
    permissive rule anywhere opens a port, and that is why every rule is read."""
    return [
        (o, rule)
        for o in rendered
        if o.get("kind") == "NetworkPolicy"
        and selects(((o.get("spec") or {}).get("podSelector") or {}).get("matchLabels") or {}, labels)
        and "Ingress" in ((o.get("spec") or {}).get("policyTypes") or ["Ingress"])
        for rule in (o.get("spec") or {}).get("ingress") or []
    ]


def only_pod(peers, target: dict, others: list[dict]) -> bool:
    """Every peer is a bare `podSelector.matchLabels` that selects the target pod and no other rendered pod — no
    `ipBlock`, no `namespaceSelector`, no empty selector, no `matchExpressions` this cannot evaluate."""
    return bool(peers) and all(
        set(peer) == {"podSelector"} and set(peer["podSelector"] or {}) == {"matchLabels"}
        and peer["podSelector"]["matchLabels"]
        and selects(peer["podSelector"]["matchLabels"], target)
        and not any(selects(peer["podSelector"]["matchLabels"], labels) for labels in others)
        for peer in peers
    )


def check_network_policies(tag: str, rendered: list[dict]) -> None:
    """(xv) the default-deny selects every pod, and (xvi) the database admits the app alone."""
    pods = {name_of(o): pod_labels(o) for o in rendered if pod_spec(o)}
    policies = [o for o in rendered if o.get("kind") == "NetworkPolicy"]
    deny = [
        o for o in policies
        if {"Ingress", "Egress"} <= set((o.get("spec") or {}).get("policyTypes") or [])
        and not (o.get("spec") or {}).get("ingress") and not (o.get("spec") or {}).get("egress")
        and set((((o.get("spec") or {}).get("podSelector")) or {})) <= {"matchLabels"}
        and all(selects(((o.get("spec") or {}).get("podSelector") or {}).get("matchLabels") or {}, labels) for labels in pods.values())
    ]
    if not deny:
        errors.append(
            f"(xv) {tag}: no NetworkPolicy denies both directions to every rendered pod "
            f"({', '.join(sorted(pods))}) — default-deny is what makes every other policy an allow-list (ADR 0071 §3)"
        )

    workloads = [(o, c) for o in rendered for c in ((pod_spec(o) or {}).get("containers") or [])]
    app = next((o for o, c in workloads if is_app_container(c)), None)
    database = next((o for o, c in workloads if DB_IMAGE.search(str(c.get("image") or "").split("@", 1)[0])), None)
    if database is None or app is None:
        return  # an operator-managed database renders no pod here, and a missing app is reported elsewhere
    prune = next((o for o in rendered if o.get("kind") == "CronJob" and name_of(o) == f"CronJob/{PRUNE_NAME}"), None)
    allowed = [o for o in (app, prune) if o is not None]
    rules = ingress_rules(rendered, pods[name_of(database)])
    for policy, rule in rules:
        peers = rule.get("from") or []
        if not peers or not all(
            any(only_pod([peer], pods[name_of(target)],
                         [labels for name, labels in pods.items() if name not in {name_of(target), name_of(database)}])
                for target in allowed)
            for peer in peers
        ):
            errors.append(f"(xvi) {tag}: {name_of(policy)} admits the database from {rule.get('from')!r} — its only "
                          f"peers are the app pod and the daily backup prune (ADR 0071 §3, ADR 0056 decision 8)")
    if not any(only_pod([peer], pods[name_of(app)], []) for _, rule in rules for peer in rule.get("from") or []):
        errors.append(f"(xvi) {tag}: no NetworkPolicy rule admits the app to the database — the default-deny would cut the app off it")
    if prune is not None and not any(only_pod([peer], pods[name_of(prune)], []) for _, rule in rules for peer in rule.get("from") or []):
        errors.append(f"(xvi) {tag}: no NetworkPolicy rule admits the daily backup prune to the database — it would read "
                      f"no B and prune at the default 30 days (ADR 0056 decision 8)")


def app_properties(rendered: list[dict]) -> str | None:
    for obj in rendered:
        if obj.get("kind") == "ConfigMap" and APP_PROPERTIES in (obj.get("data") or {}):
            return str(obj["data"][APP_PROPERTIES])
    return None


def check_rbac(tag: str, rendered: list[dict]) -> None:
    """(xx) no Ingress, and (xxii) the Role: the sink's two kinds, nothing on gateways or ingresses."""
    for obj in rendered:
        if obj.get("kind") == "Ingress" and str(obj.get("apiVersion", "")).startswith("networking.k8s.io/"):
            errors.append(f"(xx) {tag}: {name_of(obj)} is a networking.k8s.io Ingress — routing is the Gateway API (ADR 0064), and the chart keeps nothing for Ingress")
    granted: set[str] = set()
    for obj in rendered:
        if obj.get("kind") != "Role":
            continue
        for rule in obj.get("rules") or []:
            resources = set(rule.get("resources") or [])
            if "ingresses" in resources:
                errors.append(f"(xxii) {tag}: {name_of(obj)} grants {rule.get('verbs')} on ingresses — the sink writes Gateway API objects only")
            if GATEWAY_GROUP not in (rule.get("apiGroups") or []) and "*" not in (rule.get("apiGroups") or []):
                continue
            extra = sorted(resources - {"httproutes", "listenersets"})
            if extra:
                errors.append(
                    f"(xxii) {tag}: {name_of(obj)} grants {rule.get('verbs')} on {extra} in {GATEWAY_GROUP} — the backend's Role "
                    f"names httproutes and listenersets only; on `gateways` it could add a listener the chart's Gateway never admitted (ADR 0064)"
                )
            granted |= resources & {"httproutes", "listenersets"}
    if rendered and granted != {"httproutes", "listenersets"}:
        errors.append(f"(xxii) {tag}: no Role grants the sink both httproutes and listenersets (granted: {sorted(granted) or 'none'}) — it could not reconcile tenant routing")


def check_gateway(tag: str, tls: dict, rendered: list[dict], properties: str) -> None:
    """(xxi) one Gateway, the sink's listeners on it, and every route on the listener its TLS mode names."""
    mode = tls.get("mode") or "none"  # the chart's default
    gateways = [o for o in rendered if o.get("kind") == "Gateway"]
    configured = next((line.split("=", 1)[1].strip() for line in properties.splitlines()
                       if line.strip().startswith("veyq.tenant.ingress.kubernetes.gateway-name=")), None)
    if len(gateways) != 1:
        errors.append(f"(xxi) {tag}: the render holds {len(gateways)} Gateways ({[name_of(o) for o in gateways]}) — the chart owns exactly one")
        return
    gateway = gateways[0]
    name = (gateway.get("metadata") or {}).get("name")
    spec = gateway.get("spec") or {}
    if configured != name:
        errors.append(f"(xxi) {tag}: the app's properties file names gateway-name={configured!r}, but the chart's Gateway is {name!r} — every tenant ListenerSet and route would name a parent that does not exist")
    allowed = ((spec.get("allowedListeners") or {}).get("namespaces") or {}).get("from")
    if allowed != "Same":
        errors.append(f"(xxi) {tag}: {name_of(gateway)} admits ListenerSets from {allowed!r}, not `Same` — the backend's ListenerSets attach from this namespace and no other namespace's may")
    listeners = {l.get("name"): l for l in spec.get("listeners") or []}
    if len(listeners) != len(spec.get("listeners") or []):
        errors.append(f"(xxi) {tag}: {name_of(gateway)} repeats a listener name — a route's sectionName could not say which one it means")
    http = listeners.get(http_listener)
    if http_listener and (http is None or http.get("protocol") != "HTTP" or http.get("port") != 80 or http.get("hostname")):
        errors.append(f"(xxi) {tag}: {name_of(gateway)} has no hostname-less HTTP listener on port 80 named `{http_listener}` (the sink's HTTP_LISTENER): {http!r}")
    if mode == "shared" and shared_listener:
        shared = listeners.get(shared_listener)
        refs = [c.get("name") for c in (((shared or {}).get("tls") or {}).get("certificateRefs") or [])]
        if shared is None or shared.get("protocol") != "HTTPS" or shared.get("hostname") or refs != [tls.get("secretName")]:
            errors.append(
                f"(xxi) {tag}: under `shared` {name_of(gateway)} needs a hostname-less HTTPS listener named `{shared_listener}` (the "
                f"sink's SHARED_HTTPS_LISTENER) referencing {tls.get('secretName')!r} — found {shared!r}"
            )
    redirects = 0
    for route in (o for o in rendered if o.get("kind") == "HTTPRoute"):
        rspec = route.get("spec") or {}
        is_redirect = any(f.get("type") == "RequestRedirect" for rule in rspec.get("rules") or [] for f in rule.get("filters") or [])
        for parent in rspec.get("parentRefs") or []:
            listener = listeners.get(parent.get("sectionName"))
            if parent.get("kind", "Gateway") != "Gateway" or parent.get("group", GATEWAY_GROUP) != GATEWAY_GROUP or parent.get("name") != name or listener is None:
                errors.append(f"(xxi) {tag}: {name_of(route)} has parent {parent!r} — every chart route hangs off a listener of the chart's Gateway `{name}`, named by sectionName")
                continue
            if is_redirect:
                if listener is not http or rspec.get("hostnames") or (((rspec.get("rules") or [{}])[0].get("filters") or [{}])[0].get("requestRedirect") or {}).get("scheme") != "https":
                    errors.append(f"(xxi) {tag}: {name_of(route)} redirects, but not host-less to https on the HTTP listener — cert-manager's challenge routes must outrank it, and only https is the point")
                redirects += 1
            elif mode == "none":
                if listener is not http:
                    errors.append(f"(xxi) {tag}: {name_of(route)} sits on listener `{parent.get('sectionName')}` under tls.mode none — every route attaches to the HTTP listener, as the sink's do")
            elif listener.get("protocol") != "HTTPS":
                errors.append(f"(xxi) {tag}: {name_of(route)} sits on the plain-HTTP listener under tls.mode {mode} — it must be served over HTTPS, as the sink's routes are")
            elif mode == "per-domain":
                refs = [c.get("name") for c in ((listener.get("tls") or {}).get("certificateRefs") or [])]
                own = (route.get("metadata") or {}).get("name")
                if [listener.get("hostname")] != list(rspec.get("hostnames") or []) or refs != [own]:
                    errors.append(
                        f"(xxi) {tag}: {name_of(route)} sits on listener `{parent.get('sectionName')}` (hostname {listener.get('hostname')!r}, "
                        f"certificateRefs {refs}) — under per-domain a host's listener covers exactly its route's host and references "
                        f"the route's OWN name {own!r}, the shape the sink gives every tenant ListenerSet"
                    )
    wanted = 0 if mode == "none" else 1
    if redirects != wanted:
        errors.append(f"(xxi) {tag}: {redirects} redirect route(s) under tls.mode {mode}, expected {wanted} — the chart, not an implementation's default, redirects :80 to https whenever HTTPS is served")


# --- the anchors, read from the source ---

consts = dict((m.group(1), m.group(2)) for m in KT_CONST.finditer(SINK_KT.read_text()))
owner_label, owner_value = consts.get("OWNER_LABEL"), consts.get("OWNER_VALUE")
http_listener, shared_listener = consts.get("HTTP_LISTENER"), consts.get("SHARED_HTTPS_LISTENER")
if not owner_label or not owner_value:
    errors.append(f"{SINK_KT.relative_to(ROOT)}: OWNER_LABEL/OWNER_VALUE constants not found — the ownership anchor has moved")
if not http_listener or not shared_listener:
    errors.append(f"{SINK_KT.relative_to(ROOT)}: HTTP_LISTENER/SHARED_HTTPS_LISTENER constants not found — the listener-name anchor has moved")

stages = runtime_stages()
users = {name: last_match(USER, body) for name, body in stages.items()}
workdirs = {name: last_match(WORKDIR, body) for name, body in stages.items()}
if not stages:
    errors.append("Dockerfile declares no `runtime-*` stage — there is no runtime user to hold fsGroup to")
if len(set(users.values())) != 1 or None in users.values():
    errors.append(f"the runtime stages disagree about USER ({users}) — fsGroup cannot be held to one uid")
if len(set(workdirs.values())) != 1:
    errors.append(f"the runtime stages disagree about WORKDIR ({workdirs}) — the relative default resolves to two paths")
runtime_user = next(iter(set(users.values()))) if len(set(users.values())) == 1 else None
workdir = next(iter(set(workdirs.values()))) if len(set(workdirs.values())) == 1 else None
kt_match = KT_DEFAULT.search(CONFIG_KT.read_text())
default_path = kt_match.group(1) if kt_match else None
if default_path is None:
    errors.append(f"no `@WithDefault(...)` on configSecretFile() in {CONFIG_KT.relative_to(ROOT)}")
admin_port_match = KT_ADMIN_PORT.search(CONFIG_KT.read_text())
default_admin_port = int(admin_port_match.group(1)) if admin_port_match else None
if default_admin_port is None:
    errors.append(f"no `@WithDefault(...)` on AdminChannel.port() in {CONFIG_KT.relative_to(ROOT)} — the admin listener's port anchor has moved")


# --- one render per values file ---

def check(values_file: pathlib.Path) -> list[dict]:
    tag = values_file.name
    values = yaml.safe_load(values_file.read_text()) or {}
    gateway = values.get("gateway") or {}
    tls = gateway.get("tls") or {}
    acme = bool((tls.get("acme") or {}).get("enabled"))
    acme_server = (tls.get("acme") or {}).get("server") or ACME_DEFAULT_SERVER
    ISSUER_NAME = acme_issuer_name(acme_server)  # noqa: N806 — the name every opted-in assertion below expects
    admin_domain = bool((values.get("admin") or {}).get("domain"))
    garage = garage_enabled(values)

    first = render(values_file)
    second = render(values_file) if first else ""
    if first and first != second:
        errors.append(f"(iv) {tag}: rendering the chart twice produced different output — something in the templates is non-deterministic")

    rendered = objects(first) if first else []
    if first and not rendered:
        errors.append(f"{tag}: `helm template` rendered no objects at all — the chart's templates/ is empty or every template is conditional")

    pvc_names = {(o.get("metadata") or {}).get("name") for o in rendered if o.get("kind") == "PersistentVolumeClaim"}
    app_containers = 0

    for obj in rendered:
        # (i) the ownership value, on any label map, anywhere.
        if owner_label and owner_value:
            for where, labels in label_maps(obj):
                if str(labels.get(owner_label)) == owner_value:
                    errors.append(
                        f"(i) {tag}: {name_of(obj)} carries {owner_label}={owner_value} in {where} — that is the backend's "
                        f"ingress-ownership value, and the sink deletes every object wearing it that is not in its "
                        f"desired set. A chart object must carry Helm's value there, never the sink's."
                    )

        # (viii) nothing cluster-scoped, in any render.
        if obj.get("kind") in CLUSTER_SCOPED:
            errors.append(
                f"(viii) {tag}: {name_of(obj)} is cluster-scoped — the chart installs into one namespace and holds "
                f"nothing beyond it (ADR 0064); an ACME issuer is a namespaced `Issuer`, never a `ClusterIssuer`."
            )

        spec = pod_spec(obj)
        if spec is None:
            continue
        pod_ctx = spec.get("securityContext") or {}
        volumes = {v.get("name"): v for v in spec.get("volumes") or []}
        claim_templates = {(t.get("metadata") or {}).get("name") for t in (obj.get("spec") or {}).get("volumeClaimTemplates") or []}

        for container in list(spec.get("containers") or []) + list(spec.get("initContainers") or []):
            # (v) restricted-compliance, every container.
            ctx = container.get("securityContext") or {}
            problems = []
            if ctx.get("allowPrivilegeEscalation") is not False:
                problems.append("allowPrivilegeEscalation must be false")
            if ctx.get("readOnlyRootFilesystem") is not True:
                problems.append("readOnlyRootFilesystem must be true (ADR 0071 §1) — give each path it writes an emptyDir")
            drop = [str(c).upper() for c in ((ctx.get("capabilities") or {}).get("drop") or [])]
            if "ALL" not in drop:
                problems.append("capabilities.drop must include ALL")
            if ctx.get("runAsNonRoot") is not True and pod_ctx.get("runAsNonRoot") is not True:
                problems.append("runAsNonRoot must be true")
            seccomp = (ctx.get("seccompProfile") or pod_ctx.get("seccompProfile") or {}).get("type")
            if seccomp not in {"RuntimeDefault", "Localhost"}:
                problems.append("seccompProfile.type must be RuntimeDefault or Localhost")
            if problems:
                errors.append(f"(v) {tag}: {name_of(obj)} container `{container.get('name')}` is not restricted-compliant: " + "; ".join(problems))

            if not is_app_container(container):
                continue
            app_containers += 1

            # (xix) the object store reaches the app as the bundled-store HINT and nothing else (ADR 0089) — and the
            # hint only where the chart brings the store (garage.enabled; off, (xix) below holds every container).
            env_names = {e.get("name") for e in container.get("env") or []}
            leaked = sorted(n for n in env_names if str(n).startswith("VEYQ_BLOB_STORE_"))
            if leaked:
                errors.append(
                    f"(xix) {tag}: {name_of(obj)} passes {', '.join(leaked)} — the store is a Server-Owner setting held in the database "
                    f"(ADR 0089); the only store variables an app container carries are the VEYQ_BUNDLED_STORE_* hint's, whose S3 key "
                    f"pair is derived from the admin token."
                )
            missing_hint = sorted({"VEYQ_BUNDLED_STORE_ENDPOINT", "VEYQ_BUNDLED_STORE_ADMIN_ENDPOINT", "VEYQ_BUNDLED_STORE_ADMIN_TOKEN"} - env_names)
            if garage and missing_hint:
                errors.append(f"(xix) {tag}: {name_of(obj)} lacks the bundled-store hint {', '.join(missing_hint)} — the console could not offer \"Use the bundled store\".")

            # (iii) one migrator at a time.
            if obj.get("kind") == "Deployment":
                strategy = ((obj.get("spec") or {}).get("strategy") or {}).get("type")
                if strategy != "Recreate":
                    errors.append(
                        f"(iii) {tag}: {name_of(obj)} runs the app image with strategy `{strategy}` — RollingUpdate starts the "
                        f"new pod before the old one stops, so a `helm upgrade` runs two schema migrators at once. "
                        f"It must be `Recreate`."
                    )
            else:
                errors.append(f"(iii) {tag}: {name_of(obj)} runs the app image as a {obj.get('kind')} — the app is a Deployment with strategy Recreate")

            # (ii) the KEK path on a claim — or on the operator's Secret, read-only, with the ledger on a claim — and
            # fsGroup = the image's runtime user.
            path = resolve_secret_path(container, workdir, default_path)
            own_secret = ((values.get("app") or {}).get("configSecret") or {}).get("existingSecret") or ""
            if own_secret:
                own_secret_rendered.add(tag)
            if path is None:
                errors.append(f"(ii) {tag}: {name_of(obj)}: the config-secret path could not be resolved for container `{container.get('name')}`")
            elif own_secret:
                check_own_key(tag, obj, container, volumes, pvc_names, path, own_secret)
            else:
                mount = next((m for m in container.get("volumeMounts") or [] if covers(path, str(m.get("mountPath", "")))), None)
                if mount is None:
                    errors.append(f"(ii) {tag}: {name_of(obj)}: `{path}` is under no volumeMount of container `{container.get('name')}` — the KEK would not survive the pod")
                else:
                    volume = volumes.get(mount.get("name"))
                    claim = (volume or {}).get("persistentVolumeClaim") or {}
                    if mount.get("name") in claim_templates:
                        pass  # a StatefulSet claim template — persistent by construction
                    elif not claim:
                        errors.append(f"(ii) {tag}: {name_of(obj)}: `{path}` is mounted from volume `{mount.get('name')}`, which is not a PersistentVolumeClaim: {volume}")
                    elif claim.get("claimName") not in pvc_names:
                        errors.append(f"(ii) {tag}: {name_of(obj)}: `{path}` is on claim `{claim.get('claimName')}`, which this render does not create")
            if runtime_user is not None:
                fs_group = pod_ctx.get("fsGroup")
                if str(fs_group) != runtime_user:
                    errors.append(
                        f"(ii) {tag}: {name_of(obj)}: pod securityContext.fsGroup is {fs_group!r}, but the Dockerfile's runtime USER is "
                        f"{runtime_user} — a PersistentVolumeClaim comes up root-owned and inherits nothing from the image, so "
                        f"without fsGroup={runtime_user} the app cannot write its own config-secret key on a first install."
                    )

    # (xix) off: no container of the render — the app's, the backup gate's, any — is handed a bundled-store variable.
    if not garage:
        for obj in rendered:
            for container in every_container(pod_spec(obj)):
                hinted = sorted(str(e.get("name")) for e in container.get("env") or [] if str(e.get("name")).startswith(BUNDLED_HINT))
                if hinted:
                    errors.append(f"(xix) {tag}: garage.enabled is off and {name_of(obj)} container `{container.get('name')}` is "
                                  f"handed {', '.join(hinted)} — a hint without the store makes the console offer one that "
                                  f"is not there (ADR 0064 § the bundled store is opt-in)")

    if first and app_containers == 0:
        errors.append(f"{tag}: no container in the render runs the app image — either the chart stopped deploying the app, or this guard no longer recognises it")

    # (vi) the admin surface: a ClusterIP Service, and no route unless admin.domain is set.
    admin_selectors: list[dict] = []
    for obj in rendered:
        spec = pod_spec(obj)
        if spec and any(ADMIN_IMAGE.search(str(c.get("image") or "")) for c in spec.get("containers") or []):
            admin_selectors.append(((obj.get("spec") or {}).get("template") or {}).get("metadata", {}).get("labels") or {})
    if first and not admin_selectors:
        errors.append(f"(vi) {tag}: no container in the render runs the admin image — the chart stopped deploying the admin surface, or this guard no longer recognises it")
    admin_services: set[str] = set()
    for obj in rendered:
        if obj.get("kind") != "Service":
            continue
        selector = (obj.get("spec") or {}).get("selector") or {}
        if any(selector and all(labels.get(k) == v for k, v in selector.items()) for labels in admin_selectors):
            admin_services.add((obj.get("metadata") or {}).get("name"))
            if (obj.get("spec") or {}).get("type", "ClusterIP") != "ClusterIP":
                errors.append(f"(vi) {tag}: {name_of(obj)} fronts the admin surface with type {(obj.get('spec') or {}).get('type')} — it must be ClusterIP, reached by port-forward")
    if first and admin_selectors and not admin_services:
        errors.append(f"(vi) {tag}: the admin pod has no Service selecting it — port-forward has nothing to reach")
    if not admin_domain:
        for obj in rendered:
            if any(backend in admin_services for backend in route_backends(obj)):
                errors.append(
                    f"(vi) {tag}: {name_of(obj)} routes {(obj.get('spec') or {}).get('hostnames')} to the admin surface with "
                    f"admin.domain unset — the admin surface is exposed by a route only when the operator sets admin.domain; "
                    f"by default it is reachable through port-forward alone."
                )

    network_policy = values.get("networkPolicy") or {}
    policy_enabled = network_policy.get("enabled", True) is not False  # the chart's default is true
    check_admin_channel(tag, rendered, policy_enabled, network_policy.get("ingressFrom") or [],
                        values.get("clusterDomain") or DEFAULT_CLUSTER_DOMAIN)
    if policy_enabled:
        check_network_policies(tag, rendered)
    check_image_digests(tag, values, rendered)
    check_rbac(tag, rendered)
    enabled = backup_enabled(values)
    if first:
        check_backup_gate(tag, enabled, (values.get("backup") or {}).get("existingClaim"), rendered)
        check_backup_prune(tag, enabled, (values.get("backup") or {}).get("existingClaim"), rendered)
        # (xxv) and its positive control on the same file: the other choice renders the other shape.
        flip = ["--set", "backup.enabled=false"] if enabled else ["--set", "backup.enabled=true", "--set", "backup.existingClaim=flipped-backup"]
        flipped = render(values_file, flip)
        has_gate = check_backup_gate(f"{tag} {' '.join(flip)}", not enabled, None if enabled else "flipped-backup",
                                     objects(flipped) if flipped else [])
        has_prune = check_backup_prune(f"{tag} {' '.join(flip)}", not enabled, None if enabled else "flipped-backup",
                                       objects(flipped) if flipped else [])
        if flipped and has_prune == enabled:
            errors.append(f"(xxviii) {tag}: flipping backup.enabled did not flip the daily prune")
        if not flipped:
            errors.append(f"(xxv) {tag}: the render with {' '.join(flip)} failed, so the other backup choice is unchecked")
        elif has_gate == enabled:
            errors.append(f"(xxv) {tag}: flipping backup.enabled did not flip the gate — the switch renders nothing different")
    check_client_port(tag, values, rendered)
    check_store_log(tag, garage, rendered)
    if first and garage:
        check_store_claims(tag, values_file)
    (garage_on if garage else garage_off).add(tag)

    if not first:
        return rendered
    issuers = [o for o in rendered if o.get("kind") == "Issuer"]
    properties = app_properties(rendered)
    if properties is None:
        errors.append(f"(vii/x) {tag}: no ConfigMap in the render carries `{APP_PROPERTIES}` — the app's Kubernetes-only settings have no home")
        properties = ""
    check_gateway(tag, tls, rendered, properties)

    if not acme:
        # (vii) no trace of the opt-in when it is off.
        for obj in issuers:
            errors.append(f"(vii) {tag}: {name_of(obj)} is rendered although gateway.tls.acme is not enabled — the opt-in is off by default so that nothing moves")
        named = yaml.safe_dump(gateway.get("annotations") or {})
        for obj in rendered:
            for key in ((obj.get("metadata") or {}).get("annotations") or {}):
                if CERT_MANAGER_PREFIX in str(key) and str(key) not in named:
                    errors.append(f"(vii) {tag}: {name_of(obj)} carries annotation `{key}` although gateway.tls.acme is not enabled and the values name none")
        if CERT_MANAGER_PREFIX in properties and CERT_MANAGER_PREFIX not in named:
            errors.append(f"(vii) {tag}: the app's properties file mentions `{CERT_MANAGER_PREFIX}` although gateway.tls.acme is not enabled and the values name none")
        return rendered

    # (viii) exactly one Issuer, named for its directory, HTTP-01 only — and another directory renames it, the Gateway's
    # annotation and the properties file's following: the rename is what makes cert-manager re-order (veyq.acmeIssuer).
    other_server = next(u for u in ACME_DIRECTORIES if u != acme_server)
    switched = render(values_file, ["--set", f"gateway.tls.acme.server={other_server}"])
    renamed = acme_issuer_name(other_server)
    switched_objects = objects(switched) if switched else []
    if not ([(o.get("metadata") or {}).get("name") for o in switched_objects if o.get("kind") == "Issuer"] == [renamed]
            and all(((o.get("metadata") or {}).get("annotations") or {}).get(ISSUER_ANNOTATION) == renamed
                    for o in switched_objects if o.get("kind") == "Gateway")
            and f'annotations."{ISSUER_ANNOTATION}"={renamed}' in (app_properties(switched_objects) or "")):
        errors.append(f"(viii) {tag}: with gateway.tls.acme.server={other_server} the Issuer, the Gateway's annotation and "
                      f"the properties file must all name `{renamed}` — a switch that keeps the name re-orders no "
                      f"certificate, and the old directory's stay served")
    if len(issuers) != 1 or (issuers[0].get("metadata") or {}).get("name") != ISSUER_NAME:
        errors.append(
            f"(viii) {tag}: gateway.tls.acme is enabled, so the render must contain exactly one Issuer named `{ISSUER_NAME}` — "
            f"found {[name_of(o) for o in issuers] or 'none'}"
        )
    for obj in issuers:
        solvers = (((obj.get("spec") or {}).get("acme") or {}).get("solvers")) or []
        if not solvers or any(set(s) - {"http01", "selector"} for s in solvers):
            errors.append(f"(viii) {tag}: {name_of(obj)} solvers are {solvers!r} — the opt-in solves HTTP-01 only, which needs no DNS-provider Secret the chart would have to reference")

    # (ix) the Gateway: the issuer and the parent-ref fallback, no second issuer; no route annotated at all.
    gateways = [o for o in rendered if o.get("kind") == "Gateway"]
    if not gateways:
        errors.append(f"(ix) {tag}: the render contains no Gateway — there is nothing for the issuer annotation to reach")
    for obj in gateways:
        annotations = (obj.get("metadata") or {}).get("annotations") or {}
        if annotations.get(ISSUER_ANNOTATION) != ISSUER_NAME:
            errors.append(f"(ix) {tag}: {name_of(obj)} carries {ISSUER_ANNOTATION}={annotations.get(ISSUER_ANNOTATION)!r}, not `{ISSUER_NAME}` — cert-manager orders no certificate for it")
        if annotations.get(FALLBACK_ANNOTATION) != "true":
            errors.append(f"(ix) {tag}: {name_of(obj)} carries {FALLBACK_ANNOTATION}={annotations.get(FALLBACK_ANNOTATION)!r}, not \"true\" — a tenant ListenerSet's challenge would need a port-80 listener of its own")
        others = sorted(k for k in annotations if str(k).startswith(CERT_MANAGER_PREFIX) and "issuer" in str(k) and k != ISSUER_ANNOTATION)
        if others:
            errors.append(f"(ix) {tag}: {name_of(obj)} carries a second issuer annotation {others} beside `{ISSUER_ANNOTATION}` — which one wins is cert-manager's choice, not the operator's")
    for obj in rendered:
        if obj.get("kind") == "HTTPRoute":
            stamped = sorted(k for k in ((obj.get("metadata") or {}).get("annotations") or {}) if CERT_MANAGER_PREFIX in str(k))
            if stamped:
                errors.append(f"(ix) {tag}: {name_of(obj)} carries {stamped} — an HTTPRoute holds no certificate; the annotations belong on the Gateway and the ListenerSets (ADR 0064)")

    # (x) the properties file: what reaches the tenant ListenerSets the backend writes at runtime.
    lines = {line.strip() for line in properties.splitlines()}
    for wanted in (
        "veyq.tenant.ingress.kubernetes.tls.mode=per-domain",
        f'veyq.tenant.ingress.kubernetes.annotations."{ISSUER_ANNOTATION}"={ISSUER_NAME}',
        f'veyq.tenant.ingress.kubernetes.annotations."{FALLBACK_ANNOTATION}"=true',
    ):
        if wanted not in lines:
            errors.append(
                f"(x) {tag}: the app's properties file lacks `{wanted}` — the chart's Gateway may carry the issuer, but "
                f"every tenant ListenerSet the backend projects would get no certificate"
            )

    # (xi) the schema refuses each contradiction, naming the key — and accepts the file without one.
    code, output = lint(values_file, [])
    if code != 0:
        errors.append(f"(xi) {tag}: `helm lint` fails on the file itself, so a refusal below proves nothing:\n{output.strip()}")
    else:
        for extra, key in CONTRADICTIONS:
            code, output = lint(values_file, extra)
            if code == 0:
                errors.append(f"(xi) {tag}: `helm lint {' '.join(extra)}` passed — the schema must refuse that contradiction of gateway.tls.acme")
            elif key not in output:
                errors.append(f"(xi) {tag}: `helm lint {' '.join(extra)}` failed, but its output never names `{key}` — a failure for some other reason is not the schema holding:\n{output.strip()}")
    return rendered


values_files = sorted(CI_DIR.glob("*.yaml"))
if not values_files:
    errors.append(f"{CI_DIR.relative_to(ROOT)} holds no values file — nothing would be rendered, and a run over nothing is not a green")
digests_set: set[str] = set()
proxy_rendered: set[str] = set()
own_secret_rendered: set[str] = set()
garage_on: set[str] = set()
garage_off: set[str] = set()
renders = {f.name: check(f) for f in values_files}

# (xiv) the digest path is rendered by some file, and the schema refuses a digest that is not one.
for which in ("app", "admin", "tools"):
    if values_files and which not in digests_set:
        errors.append(f"(xiv) no file in {CI_DIR.relative_to(ROOT)} sets the {which} image's digest, so the `repository:tag@digest` render is checked by nobody")
# (xxiii) the PROXY branch is rendered by some file.
# (xix)/(xxvii) both sides of garage.enabled are rendered by some file, or one direction is checked by nobody.
for side, files in (("on", garage_on), ("off", garage_off)):
    if values_files and not files:
        errors.append(f"(xix/xxvii) no file in {CI_DIR.relative_to(ROOT)} renders garage.enabled {side}, so that side's store "
                      f"objects and hint are checked by nobody")
if values_files and not own_secret_rendered:
    errors.append(f"(ii) no file in {CI_DIR.relative_to(ROOT)} sets app.configSecret.existingSecret, so the operator's-key render — the "
                  f"Secret mount and the ledger on the claim — is checked by nobody")
if values_files and not proxy_rendered:
    errors.append(f"(xxiii) no file in {CI_DIR.relative_to(ROOT)} sets app.clientPort.source: proxyProtocol, so the PROXY render — its env and its probes on the admin listener — is checked by nobody")
if values_files:
    base = values_files[0]
    # (xvii) off means off: a render with the switch off carries no NetworkPolicy at all — and, the positive control
    # on the same predicate, the same file's own render carries some.
    enabled_policies = sum(o.get("kind") == "NetworkPolicy" for o in renders.get(base.name) or [])
    disabled = render(base, ["--set", "networkPolicy.enabled=false"])
    disabled_policies = [name_of(o) for o in objects(disabled) if o.get("kind") == "NetworkPolicy"] if disabled else []
    if not enabled_policies:
        errors.append(f"(xvii) {base.name}: the render renders no NetworkPolicy, so the switch below turns off nothing")
    if not disabled:
        errors.append(f"(xvii) {base.name}: the render with networkPolicy.enabled=false failed, so what it leaves is unchecked")
    elif disabled_policies:
        errors.append(f"(xvii) {base.name}: networkPolicy.enabled=false still renders {disabled_policies} — the switch must remove every policy the chart renders")
    # (xviii) the schema refuses an enabled policy with no ingressFrom, naming the key — both an empty list and no
    # key at all — while the same lint with it set passes.
    code, output = lint(base, [])
    if code != 0:
        errors.append(f"(xviii) {base.name}: `helm lint` fails on the file itself, so a refusal below proves nothing:\n{output.strip()}")
    else:
        for extra in (["--set-json", "networkPolicy.ingressFrom=[]"], ["--set", "networkPolicy.ingressFrom=null"]):
            code, output = lint(base, extra)
            if code == 0:
                errors.append(f"(xviii) `helm lint {' '.join(extra)}` passed — an enabled policy set with no gateway data plane named must be refused")
            elif "ingressFrom" not in output:
                errors.append(f"(xviii) `helm lint {' '.join(extra)}` failed, but its output never names `ingressFrom`:\n{output.strip()}")
    # (xxvi) the backup choice is explicit: neither (the gate on, no claim) is refused naming `backup`, and each of the
    # two choices passes — the file's own and the gate switched off.
    neither = ["--set", "backup.enabled=true", "--set", "backup.existingClaim="]
    for extra, refused in ((neither, True), (["--set", "backup.enabled=false"], False),
                           (["--set", "backup.enabled=true", "--set", "backup.existingClaim=a-claim"], False)):
        code, output = lint(base, extra)
        if refused and code == 0:
            errors.append(f"(xxvi) `helm lint {' '.join(extra)}` passed — an install that neither names backup.existingClaim nor says backup.enabled: false must be refused (ADR 0098, owner 2b)")
        elif refused and "/backup" not in output:
            errors.append(f"(xxvi) `helm lint {' '.join(extra)}` failed, but its output never names `/backup`:\n{output.strip()}")
        elif not refused and code != 0:
            errors.append(f"(xxvi) `helm lint {' '.join(extra)}` failed — that is a choice the schema must accept:\n{output.strip()}")
    # (xii) the admin surface's upstream is the Service's full name in the release's OWN namespace and cluster domain:
    # rendered into another of each, both must follow — a namespace or a domain spelled into the template is a red.
    probe = render(base, ["--namespace", PROBE_NAMESPACE, "--set", f"clusterDomain={PROBE_CLUSTER_DOMAIN}"])
    if probe:
        base_values = yaml.safe_load(base.read_text()) or {}
        base_policy = base_values.get("networkPolicy") or {}
        check_admin_channel(f"{base.name} (namespace {PROBE_NAMESPACE}, clusterDomain {PROBE_CLUSTER_DOMAIN})",
                            objects(probe), base_policy.get("enabled", True) is not False,
                            base_policy.get("ingressFrom") or [], PROBE_CLUSTER_DOMAIN, PROBE_NAMESPACE)
    else:
        errors.append(f"(xii) {base.name}: the render into namespace {PROBE_NAMESPACE} failed, so the admin upstream's full name is unchecked")
    code, output = lint(base, ["--set", "app.clientPort.source=bogus"])
    if code == 0:
        errors.append("(xxiii) `helm lint --set app.clientPort.source=bogus` passed — the schema must refuse a port source outside its enum")
    elif "clientPort" not in output:
        errors.append(f"(xxiii) `helm lint --set app.clientPort.source=bogus` failed, but its output never names `clientPort`:\n{output.strip()}")
    for key in ("image.digest", "admin.image.digest", "backup.image.digest"):
        code, output = lint(values_files[0], ["--set", f"{key}=sha256:not-a-digest"])
        if code == 0:
            errors.append(f"(xiv) `helm lint --set {key}=sha256:not-a-digest` passed — the schema must refuse a digest that is not `sha256:<64 hex>`")
        elif "digest" not in output:
            errors.append(f"(xiv) `helm lint --set {key}=sha256:not-a-digest` failed, but its output never names `digest`:\n{output.strip()}")

if errors:
    print("CHART META CHECK FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

summary = "; ".join(f"{name}: {len(objs)} objects" for name, objs in renders.items())
print(
    f"chart meta check OK ({summary}): no object carries the sink's ownership value or is cluster-scoped; the app's "
    f"config-secret path is on a claim with fsGroup={runtime_user}, or read-only on the operator's Secret with the ledger on the claim "
    f"(in {', '.join(sorted(own_secret_rendered))}); strategy Recreate; every render byte-identical "
    f"twice; every container restricted-compliant with a read-only root; the admin surface on a ClusterIP Service and "
    f"no route; the admin listener on a Service of its own that no route names and the admin surface points at, "
    f"admitted by policy from the admin pod alone; a default-deny over every pod, the public port admitted from "
    f"ingressFrom exactly and the database from the app and the daily backup prune alone, no policy when switched off, and no install without "
    f"ingressFrom; the ACME "
    f"opt-in absent where off, and where on one Issuer named for its ACME directory, on the Gateway and in the "
    f"properties file, renamed by another directory, "
    f"its contradictions refused by the schema; a pinned image digest is the digest the pod runs; no Ingress, every "
    f"route on the one Gateway on the listener its TLS mode names, the sink's listeners present, and a Role on "
    f"httproutes/listenersets with nothing on gateways; the PROXY protocol on exactly with the probes on the admin "
    f"listener, in {', '.join(sorted(proxy_rendered))}, and a port source outside the enum refused; the backup gate on the tools image where on — the named claim, "
    f"never the config-secret claim, the app's release as its target — nothing of it where off, and no install that makes neither choice; the daily prune of every capture past B on that claim alone, and none where off; "
    f"the bundled store's objects and hint only with garage.enabled (on in {', '.join(sorted(garage_on))}), its RUST_LOG below info, "
    f"each of its claims on its own storageClass else the shared one"
)
