#!/usr/bin/env python3
"""The Helm chart (ADR 0064) holds the structural invariants the compose recipe's guards hold — on rendered objects.

**What it asserts.** It renders `charts/veyq` with `helm template` once per values file in `charts/veyq/ci/` —
the set is the directory, never a list here, so a new file enrols itself; each satisfies the schema without an
operator, because the chart generates no secret and a bare render is refused by design — and, over every
render, asserts — deriving every anchor from the source rather than listing it:

  i.   **No rendered object carries the backend's ingress-ownership value.** The Kubernetes sink lists the
       Ingress objects it owns BY `app.kubernetes.io/managed-by: <its value>` and deletes over that list, so a
       chart object wearing that value is deleted by the first regenerate. Both the key and the value are read
       out of `KubernetesIngressSink.kt`'s `OWNER_LABEL`/`OWNER_VALUE` constants and never spelled here — this
       file is text, and a guard that scans for a literal must not become a copy of what it forbids. Every
       label map is checked: object metadata, pod templates, selectors.
  ii.  **The app's config-secret path is on a PersistentVolumeClaim, and its pod sets `fsGroup` to the image's
       runtime user.** The path is resolved the way `validate_config_secret_persistence.py` resolves it — the
       container's `VEYQ_SECURITY_CONFIG_SECRET_FILE` if set, else `VeyqConfig.kt`'s `@WithDefault` against
       the `Dockerfile`'s runtime `WORKDIR` — and the uid is the `Dockerfile`'s last `USER` in every `runtime-*`
       stage (which must agree). A claim comes up root-owned and, unlike a Docker named volume, inherits
       nothing from the image directory it covers; `fsGroup` is what makes it writable, so a chart without it
       reproduces the AccessDeniedException restart-loop on its first-ever install.
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
  vi.  **The admin surface is reachable by default through a ClusterIP Service and NO Ingress.** ADR 0065's
       localhost-only default, translated as a property: `kubectl port-forward` is this target's `127.0.0.1`
       bind, and it is what keeps first-run setup independent of DNS and TLS. The admin container is the one
       whose image repository is `veyq-admin`; an Ingress routing to its Service in a render that leaves
       `admin.domain` unset, or a Service type other than ClusterIP, is a red.
  vii. **A render that does not opt into `ingress.tls.acme` carries no trace of it** — no `Issuer`, and no
       `cert-manager.io/` key on any object's annotations or in the app's properties file. The opt-in is off by
       default so that nothing moves (ADR 0064).
  viii.**No render contains a cluster-scoped kind** (`ClusterIssuer`, `ClusterRole`, `ClusterRoleBinding`, and
       the rest of `CLUSTER_SCOPED`), and **a render that opts in contains exactly one `Issuer`, named
       `veyq-acme`**, solving HTTP-01 only. Nothing cluster-wide is the self-hostability bar ADR 0064 states;
       a `ClusterIssuer` is the easy wrong turn, which is why it is named.
  ix.  **In an opted-in render every `Ingress` carries `cert-manager.io/issuer: veyq-acme`, no second issuer
       annotation, and `spec.tls[0].secretName` equal to its own name** — the ingress-shim shape the backend
       projects under `per-domain`, held on the chart's own objects.
  x.   **In an opted-in render the app's properties file carries `tls.mode=per-domain` and the quoted issuer
       annotation.** This is the half that reaches tenant `Ingress` objects the backend writes at runtime, and
       it is asserted SEPARATELY from (ix): the annotation map has two consumers in the chart (the chart's own
       `Ingress` metadata and this file), and a helper wired into one of them is a render where (ix) is green
       and every tenant domain silently gets no certificate.
  xii. **The admin listener has a Service of its own, no Ingress names it, and the admin surface points at it.**
       The listener's port is the app container's `VEYQ_ADMIN_CHANNEL_PORT` if set, else `VeyqConfig.kt`'s
       `@WithDefault` on `AdminChannel.port()`; the app container must declare it. Every Service targeting that
       port selects the app pods and exposes that port ALONE — a second port on the public app Service would put
       it behind every Ingress aimed at that Service, the chart's, a projected tenant one or an operator's
       (ADR 0065). No Ingress in any render routes to such a Service, `admin.domain` set or not. The admin
       container's `BACKEND_URL` names one of them at its Service port — the public port 404s every Server-Owner
       operation, so an admin surface pointed there works for nothing.
  xiii.**A NetworkPolicy admits the public port from `networkPolicy.ingressFrom` exactly, and the admin port from
       the admin pod alone.** Every ingress rule of every NetworkPolicy selecting the app pods that reaches the
       admin port — a rule listing it by name or number, or a rule with no `ports` at all, which reaches every
       port — has a `from` whose every peer is a `podSelector.matchLabels` selecting the admin pod and no other
       pod in the render: no `ipBlock`, no `namespaceSelector`, no empty selector, no `matchExpressions` this
       cannot evaluate. Every rule that reaches a public port has a `from` equal to the values file's
       `ingressFrom` (ADR 0071 §3: "anywhere" is how any pod forged the forwarded client address). Policies are a
       union, so one permissive rule anywhere opens the port; that is why every rule is read. The positive control
       on the same predicate: some rule admits the public port from `ingressFrom`, or the policy has cut the
       ingress controller off from the app. Skipped in a render whose values turn policies off; (xvii) owns that.
  xv.  **A default-deny selects every rendered pod.** Some NetworkPolicy has both `Ingress` and `Egress` in
       `policyTypes`, no rule in either, and a `podSelector.matchLabels` that selects every workload's pod
       template — so every other policy is an allow-list rather than a widening of an open default.
  xvi. **The database's only peer is the app.** Every ingress rule of every policy selecting the bundled MariaDB
       pod (the container whose image repository is `mariadb`) admits only a bare `podSelector` matching the app
       pod and no other; and, the positive control, at least one rule does, or the default-deny cut the app off
       its database. A render without the bundled database (an operator's own) has no pod to check.
  xvii.**`networkPolicy.enabled=false` renders no NetworkPolicy at all,** the admin listener's included — the
       operator who turns it off owns reachability, and a policy left behind would be one they do not know they
       have. The positive control: the same file's default render carries some.
  xviii.**The schema refuses an enabled policy set with no ingress controller named, naming `ingressFrom`** — both
       an empty list and no key — while the same lint with it set passes. The chart never guesses the
       controller's namespace or labels (ADR 0071 §3), so an install without the key must fail and say why.
  xi.  **The schema refuses the three contradictions of the opt-in, naming the key** — `acme.enabled` with a
       mode other than `per-domain`, with an empty `email`, and beside an operator's own
       `cert-manager.io/cluster-issuer` annotation — each a `helm lint` that must exit non-zero AND whose output
       names the offending key; the same lint without the contradiction must pass (the positive control). An
       exit code alone cannot tell "the schema held" from any other failure. Measured on helm v4.0.1: lint
       prints the schema violation on STDOUT (`[ERROR] values.yaml: - at '/ingress/…'`) and only a summary on
       stderr, so both streams are read.
  xiv. **A digest the values pin is the digest the pod runs.** Where a values file sets `image.digest` /
       `admin.image.digest`, every container running that image renders as `…@<that digest>`; where it sets none,
       the image carries no digest at all. Some file in `ci/` must set both, or the digest render is checked by
       nobody, and `helm lint` with a digest that is not `sha256:<64 hex>` must be refused naming `digest`.

The app container is recognised the way the compose guards recognise the app service: an image whose
repository is `veyq` — never `veyq-admin` or any other veyq-namespaced sibling — with the datasource
environment as the fallback marker. A render with no app container is a failure, not a green run over nothing;
so is an empty `ci/` directory.

**The defect that bought it — none in the chart itself, which is new with this file; each assertion pins a
defect that was live elsewhere.** (i) is the sink's own deletion semantics, held on the backend half by
`KubernetesIngressSinkTest.anIngressThisSinkDoesNotOwnSurvivesARegenerate`; this is the chart half. (ii) is
inbox #26 finding (g), found at refine by measuring that a PVC does not seed ownership — the same
`AccessDeniedException` restart-loop the compose recipe already paid for once
(`validate_app_volume_ownership.py`'s docstring). (iii) is inbox #121's undecided update mechanism, stated as
a constraint so the day it is decided the constraint is findable. (iv) and (v) were measured at refine and are
pinned so they cannot be re-argued into the chart. (vii)–(xi) came with the ACME opt-in, each written red
against the chart before the opt-in existed; (x) apart from (ix) because removing the helper from the
ConfigMap alone was the injection that turned only one of them red. (xii) and (xiii) came with the admin
channel (uc.admin.admin-channel), both run red against the chart before its admin Service and policy existed.
(xiv) came with deploy-by-digest (cr-image-provenance-verifiable, ADR 0057 §3): no incident in the chart, which had
no digest value at all; it pins that the digest an operator verified is the one the pod pulls, and each of its
three halves was bitten by injection (template, schema pattern, the `ci/` file). The read-only root in (v), the
narrowed public port in (xiii) and (xv)–(xviii) came with the NetworkPolicy set (ADR 0071, inbox #162): live when
written — no container had a read-only root, and the one policy admitted the app's public port from any pod in the
cluster, which could therefore forge the forwarded address the per-IP rate limiter keys on. Run against that chart,
each of them was red; each was then bitten by a template or schema injection.

**Its limits — what a green run does NOT prove.** It renders the chart; it never installs it. So it proves what
the manifests say and nothing about a cluster: not that a StorageClass exists for the claim, not that the
ingress controller honours the annotations, not that cert-manager is installed or that an `Issuer` it accepts
ever obtains a certificate (no cluster runs here — HTTP-01 reachability, the CA, the account key's lifecycle are
all outside), not that the images exist to be pulled, not that a `restricted` namespace actually admits the pods
(the standard has fields this does not enumerate — it checks the four the chart sets, and a future field the
standard adds is out of reach). It renders only the values files in `ci/`, so a value path none of them sets is
checked at its default alone — `admin.domain` set, `mariadb.enabled` false, `ingress.tls.mode: shared` and
`per-domain` without the opt-in are rendered by nobody here. (xiii) proves the policy is written, not that a CNI
enforces it — on a cluster whose CNI ignores NetworkPolicy, any pod reaches the admin port (ADR 0065 residual
(ii)); the same holds for (xv)–(xvii), and the egress the policies narrow is rendered but asserted by nobody. (v)
requires the read-only root, not that each container has an `emptyDir` for every path it writes: a missed scratch
path renders green and fails at runtime, which is why ADR 0071 measures those paths by running the images. (xvi)
recognises the database by its image repository, so a database image of another name is out of reach. (ii) checks
the app container's mount and the pod's
`fsGroup`; it does not check the claim's access mode or that the same claim is not also mounted elsewhere. (xi)
checks three contradictions, not the schema's completeness. (xiv) proves the rendered reference, not that the
digest exists or is the one `infra/README.md` verified — the chart cannot see the verification. And it says nothing about the compose recipe, which
its sibling guards own (`validate_compose_posture.py` holds that target's half of ADR 0071).

**Why it sits OUTSIDE the `validate_*` glob.** It shells out to `helm`, which is not a repository artifact,
and the glob's contract (`trust-chain.yml`) is buildless and diff-free: a `validate_*` guard that needs a tool
outside the repo is green wherever that tool is missing. The precedent is `mutation_meta_check*.py`, which
sit outside the glob for the same reason (they need a built report). No CI workflow runs it today; run it by
hand and from any job that has `helm` on its PATH; without `helm` it exits 2 and says so, never 0:

    python3 scripts/chart_meta_check.py
"""
from __future__ import annotations  # `str | None` below — the repo's guards run on Python 3.9

import pathlib
import posixpath
import re
import shutil
import subprocess
import sys

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the other guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
CHART = ROOT / "charts" / "veyq"
CI_DIR = CHART / "ci"
SINK_KT = backend_tree.source_file("com.veyq.core.tenant.KubernetesIngressSink")
CONFIG_KT = backend_tree.source_file("com.veyq.core.config.VeyqConfig")
DOCKERFILE = ROOT / "Dockerfile"

ENV_KEY = "VEYQ_SECURITY_CONFIG_SECRET_FILE"
# The same anchors the compose guards use, for the same reasons.
STAGE = re.compile(r"^\s*FROM\s+\S+(?:\s+--\S+)*\s+AS\s+(\S+)\s*$", re.IGNORECASE | re.MULTILINE)
WORKDIR = re.compile(r"^\s*WORKDIR\s+(\S+)\s*$", re.IGNORECASE)
USER = re.compile(r"^\s*USER\s+(\S+)\s*$", re.IGNORECASE)
KT_DEFAULT = re.compile(r'@WithDefault\("([^"]*)"\)\s*\n\s*fun\s+configSecretFile\s*\(\s*\)')
# `const val OWNER_LABEL = "…"` / `const val OWNER_VALUE = "…"` — the sink's own constants, read not retyped.
KT_ADMIN_PORT = re.compile(r'interface\s+AdminChannel\s*\{.*?@WithDefault\("(\d+)"\)\s*\n\s*fun\s+port\s*\(\s*\)', re.DOTALL)
ADMIN_PORT_ENV = "VEYQ_ADMIN_CHANNEL_PORT"
KT_CONST = re.compile(r'const\s+val\s+(OWNER_LABEL|OWNER_VALUE)\s*=\s*"([^"]+)"')
APP_IMAGE = re.compile(r"(?:^|/)veyq(?::|$)")
DB_IMAGE = re.compile(r"(?:^|/)mariadb(?::|$)")
ADMIN_IMAGE = re.compile(r"(?:^|/)veyq-admin(?::|$)")
WORKLOADS = {"Deployment", "StatefulSet", "DaemonSet", "Job", "CronJob"}
# Kinds that exist only cluster-wide. The chart installs into one namespace and holds nothing beyond it.
CLUSTER_SCOPED = {
    "ClusterIssuer", "ClusterRole", "ClusterRoleBinding", "Namespace", "CustomResourceDefinition",
    "PersistentVolume", "StorageClass", "IngressClass", "PriorityClass",
    "ValidatingWebhookConfiguration", "MutatingWebhookConfiguration",
}
# The opt-in's contract names — fixed, like every object name in the chart (`_helpers.tpl`), and named in ADR 0064.
ISSUER_NAME = "veyq-acme"
ISSUER_ANNOTATION = "cert-manager.io/issuer"
CERT_MANAGER_PREFIX = "cert-manager.io/"
APP_PROPERTIES = "application.properties"
# (xi) the contradictions: the `--set` that makes an opted-in values file contradictory, and the key its refusal
# must name. `mode=shared` carries a secretName so the refusal cannot be the `shared` rule's instead.
CONTRADICTIONS = [
    (["--set", "ingress.tls.mode=shared", "--set", "ingress.tls.secretName=x"], "/ingress/tls/mode"),
    (["--set", "ingress.tls.acme.email="], "/ingress/tls/acme/email"),
    (["--set", "ingress.annotations.cert-manager\\.io/cluster-issuer=x"], "cert-manager.io~1cluster-issuer"),
]

errors: list[str] = []


def helm() -> str:
    found = shutil.which("helm")
    if found is None:
        print("chart meta check: `helm` is not on PATH — this guard cannot run, and a missing tool is not a green", file=sys.stderr)
        sys.exit(2)
    return found


def render(values: pathlib.Path, extra: list[str] | None = None) -> str:
    out = subprocess.run(
        [helm(), "template", "veyq", str(CHART), "-f", str(values), *(extra or [])],
        cwd=ROOT, capture_output=True, text=True,
    )
    if out.returncode != 0:
        errors.append(f"{values.name}: `helm template` failed:\n{out.stderr.strip()}")
        return ""
    return out.stdout


def lint(values: pathlib.Path, extra: list[str]) -> tuple[int, str]:
    out = subprocess.run(
        [helm(), "lint", str(CHART), "-f", str(values), *extra],
        cwd=ROOT, capture_output=True, text=True,
    )
    return out.returncode, out.stdout + out.stderr


def objects(rendered: str) -> list[dict]:
    return [doc for doc in yaml.safe_load_all(rendered) if isinstance(doc, dict) and doc.get("kind")]


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
    template_meta = ((spec.get("template") or {}).get("metadata") or {})
    if isinstance(template_meta.get("labels"), dict):
        found.append(("spec.template.metadata.labels", template_meta["labels"]))
    return found


def pod_spec(obj: dict) -> dict | None:
    if obj.get("kind") not in WORKLOADS:
        return None
    spec = obj.get("spec") or {}
    if obj["kind"] == "CronJob":
        spec = ((spec.get("jobTemplate") or {}).get("spec") or {})
    return ((spec.get("template") or {}).get("spec")) or None


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


def check_image_digests(tag: str, values: dict, rendered: list[dict]) -> None:
    """(xiv) a digest the values set is the digest the pod runs, and one they leave unset adds none."""
    wanted = {
        "app": (((values.get("image") or {}).get("digest")) or "", APP_IMAGE),
        "admin": ((((values.get("admin") or {}).get("image") or {}).get("digest")) or "", ADMIN_IMAGE),
    }
    for which, (digest, pattern) in wanted.items():
        if digest:
            digests_set.add(which)
        images = [
            str(c.get("image") or "")
            for obj in rendered
            for c in ((pod_spec(obj) or {}).get("containers") or [])
            if pattern.search(str(c.get("image") or "").split("@", 1)[0])
        ]
        if not images:
            errors.append(f"(xiv) {tag}: no container runs the {which} image, so its digest reached nothing this could check")
        for image in images:
            if digest and not image.endswith(f"@{digest}"):
                errors.append(
                    f"(xiv) {tag}: the {which} image renders as `{image}` although the values pin `{digest}` — the "
                    f"operator verified that digest (infra/README.md § Verifying a release), and the pod pulls something else"
                )
            if not digest and "@" in image:
                errors.append(f"(xiv) {tag}: the {which} image renders as `{image}` with a digest the values never set")


def check_admin_channel(tag: str, rendered: list[dict], policy_enabled: bool, ingress_from: list) -> None:
    """(xii) and (xiii): the admin listener's Service, the Ingresses that must not name it, and the policy."""
    workloads = [(o, s) for o in rendered if (s := pod_spec(o))]
    pod_labels = {name_of(o): ((o.get("spec") or {}).get("template") or {}).get("metadata", {}).get("labels") or {} for o, _ in workloads}
    app = next(((o, c) for o, s in workloads for c in s.get("containers") or [] if is_app_container(c)), None)
    admin = next(((o, c) for o, s in workloads for c in s.get("containers") or [] if ADMIN_IMAGE.search(str(c.get("image") or ""))), None)
    if app is None or admin is None or default_admin_port is None:
        return  # reported elsewhere: no app container, no admin container, or no port anchor
    app_labels, admin_labels = pod_labels[name_of(app[0])], pod_labels[name_of(admin[0])]
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
                f"(xii) {tag}: {name_of(obj)} exposes the admin listener beside another port — every Ingress aimed at "
                f"that Service could then reach it. The listener gets a Service of its own (ADR 0065)."
            )
    if not listener_services:
        errors.append(f"(xii) {tag}: no Service selects the app pods on the admin listener's port {admin_port} — the admin surface has nothing to reach")
    for obj in rendered:
        if obj.get("kind") != "Ingress":
            continue
        spec = obj.get("spec") or {}
        backends = [((spec.get("defaultBackend") or {}).get("service") or {}).get("name")]
        backends += [((p.get("backend") or {}).get("service") or {}).get("name")
                     for r in spec.get("rules") or [] for p in ((r.get("http") or {}).get("paths") or [])]
        for backend in backends:
            if backend in listener_services:
                errors.append(f"(xii) {tag}: {name_of(obj)} routes to {backend}, the admin listener's Service — no Ingress may, admin.domain set or not (ADR 0065)")
    backend_url = next((e.get("value") for e in admin[1].get("env") or [] if e.get("name") == "BACKEND_URL"), None)
    target = re.fullmatch(r"https?://([^:/]+):(\d+)/?", str(backend_url or ""))
    if target is None or listener_services.get(target.group(1)) != int(target.group(2)):
        errors.append(
            f"(xii) {tag}: the admin container's BACKEND_URL is {backend_url!r}, not the admin listener's Service "
            f"({', '.join(f'{n}:{p}' for n, p in listener_services.items()) or 'none'}) — the public port 404s every Server-Owner operation"
        )

    # (xiii) the policy
    if not policy_enabled:
        return  # (xvii) asserts that a disabled render carries no policy at all
    others = [labels for n, labels in pod_labels.items() if n != name_of(admin[0])]
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
        errors.append(f"(xiii) {tag}: no NetworkPolicy rule admits the app's public port from networkPolicy.ingressFrom — the ingress controller could not reach the app")


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
    pods = {name_of(o): ((o.get("spec") or {}).get("template") or {}).get("metadata", {}).get("labels") or {}
            for o in rendered if pod_spec(o)}
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
    app_labels = pods[name_of(app)]
    others = [labels for name, labels in pods.items() if name not in {name_of(app), name_of(database)}]
    rules = ingress_rules(rendered, pods[name_of(database)])
    for policy, rule in rules:
        if not only_pod(rule.get("from"), app_labels, others):
            errors.append(f"(xvi) {tag}: {name_of(policy)} admits the database from {rule.get('from')!r} — its only peer is the app pod (ADR 0071 §3)")
    if not rules:
        errors.append(f"(xvi) {tag}: no NetworkPolicy rule admits the app to the database — the default-deny would cut the app off it")


def app_properties(rendered: list[dict]) -> str | None:
    for obj in rendered:
        if obj.get("kind") == "ConfigMap" and APP_PROPERTIES in (obj.get("data") or {}):
            return str(obj["data"][APP_PROPERTIES])
    return None


# --- the anchors, read from the source ---

consts = dict((m.group(1), m.group(2)) for m in KT_CONST.finditer(SINK_KT.read_text()))
owner_label, owner_value = consts.get("OWNER_LABEL"), consts.get("OWNER_VALUE")
if not owner_label or not owner_value:
    errors.append(f"{SINK_KT.relative_to(ROOT)}: OWNER_LABEL/OWNER_VALUE constants not found — the ownership anchor has moved")

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
    ingress = values.get("ingress") or {}
    acme = bool((((ingress.get("tls") or {}).get("acme")) or {}).get("enabled"))
    admin_domain = bool((values.get("admin") or {}).get("domain"))

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

            # (ii) the KEK path on a claim, and fsGroup = the image's runtime user.
            path = resolve_secret_path(container, workdir, default_path)
            if path is None:
                errors.append(f"(ii) {tag}: {name_of(obj)}: the config-secret path could not be resolved for container `{container.get('name')}`")
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

    if first and app_containers == 0:
        errors.append(f"{tag}: no container in the render runs the app image — either the chart stopped deploying the app, or this guard no longer recognises it")

    # (vi) the admin surface: a ClusterIP Service, and no Ingress unless admin.domain is set.
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
            if obj.get("kind") != "Ingress":
                continue
            for rule in (obj.get("spec") or {}).get("rules") or []:
                for path in ((rule.get("http") or {}).get("paths") or []):
                    backend = ((path.get("backend") or {}).get("service") or {}).get("name")
                    if backend in admin_services:
                        errors.append(
                            f"(vi) {tag}: {name_of(obj)} routes {rule.get('host')} to the admin surface with admin.domain unset — "
                            f"the admin surface is exposed by an Ingress only when the operator sets admin.domain; by "
                            f"default it is reachable through port-forward alone."
                        )

    network_policy = values.get("networkPolicy") or {}
    policy_enabled = network_policy.get("enabled", True) is not False  # the chart's default is true
    check_admin_channel(tag, rendered, policy_enabled, network_policy.get("ingressFrom") or [])
    if policy_enabled:
        check_network_policies(tag, rendered)
    check_image_digests(tag, values, rendered)

    if not first:
        return rendered
    issuers = [o for o in rendered if o.get("kind") == "Issuer"]
    properties = app_properties(rendered)
    if properties is None:
        errors.append(f"(vii/x) {tag}: no ConfigMap in the render carries `{APP_PROPERTIES}` — the app's Kubernetes-only settings have no home")
        properties = ""

    if not acme:
        # (vii) no trace of the opt-in when it is off.
        for obj in issuers:
            errors.append(f"(vii) {tag}: {name_of(obj)} is rendered although ingress.tls.acme is not enabled — the opt-in is off by default so that nothing moves")
        for obj in rendered:
            for key in ((obj.get("metadata") or {}).get("annotations") or {}):
                if str(key).startswith(CERT_MANAGER_PREFIX):
                    errors.append(f"(vii) {tag}: {name_of(obj)} carries annotation `{key}` although ingress.tls.acme is not enabled and the values name none")
        if CERT_MANAGER_PREFIX in properties and CERT_MANAGER_PREFIX not in yaml.safe_dump(ingress.get("annotations") or {}):
            errors.append(f"(vii) {tag}: the app's properties file mentions `{CERT_MANAGER_PREFIX}` although ingress.tls.acme is not enabled and the values name none")
        return rendered

    # (viii) exactly one Issuer, named veyq-acme, HTTP-01 only.
    if len(issuers) != 1 or (issuers[0].get("metadata") or {}).get("name") != ISSUER_NAME:
        errors.append(
            f"(viii) {tag}: ingress.tls.acme is enabled, so the render must contain exactly one Issuer named `{ISSUER_NAME}` — "
            f"found {[name_of(o) for o in issuers] or 'none'}"
        )
    for obj in issuers:
        solvers = (((obj.get("spec") or {}).get("acme") or {}).get("solvers")) or []
        if not solvers or any(set(s) - {"http01", "selector"} for s in solvers):
            errors.append(f"(viii) {tag}: {name_of(obj)} solvers are {solvers!r} — the opt-in solves HTTP-01 only, which needs no DNS-provider Secret the chart would have to reference")

    # (ix) every Ingress: the issuer annotation, no second issuer, the ingress-shim secretName.
    ingresses = [o for o in rendered if o.get("kind") == "Ingress"]
    if not ingresses:
        errors.append(f"(ix) {tag}: the render contains no Ingress — there is nothing for the issuer annotation to reach")
    for obj in ingresses:
        meta = obj.get("metadata") or {}
        annotations = meta.get("annotations") or {}
        if annotations.get(ISSUER_ANNOTATION) != ISSUER_NAME:
            errors.append(f"(ix) {tag}: {name_of(obj)} carries {ISSUER_ANNOTATION}={annotations.get(ISSUER_ANNOTATION)!r}, not `{ISSUER_NAME}` — cert-manager orders no certificate for it")
        others = sorted(k for k in annotations if str(k).startswith(CERT_MANAGER_PREFIX) and "issuer" in str(k) and k != ISSUER_ANNOTATION)
        if others:
            errors.append(f"(ix) {tag}: {name_of(obj)} carries a second issuer annotation {others} beside `{ISSUER_ANNOTATION}` — which one wins is cert-manager's choice, not the operator's")
        tls = (obj.get("spec") or {}).get("tls") or []
        secret = (tls[0] or {}).get("secretName") if tls else None
        if secret != meta.get("name"):
            errors.append(f"(ix) {tag}: {name_of(obj)} has spec.tls[0].secretName {secret!r} — under the opt-in (mode per-domain) it must be the object's own name, the ingress-shim shape")

    # (x) the properties file: what reaches the tenant Ingress objects the backend writes at runtime.
    lines = {line.strip() for line in properties.splitlines()}
    for wanted in (
        "veyq.tenant.ingress.kubernetes.tls.mode=per-domain",
        f'veyq.tenant.ingress.kubernetes.annotations."{ISSUER_ANNOTATION}"={ISSUER_NAME}',
    ):
        if wanted not in lines:
            errors.append(
                f"(x) {tag}: the app's properties file lacks `{wanted}` — the chart's own Ingress objects may carry the "
                f"issuer, but every tenant Ingress the backend projects would get no certificate"
            )

    # (xi) the schema refuses each contradiction, naming the key — and accepts the file without one.
    code, output = lint(values_file, [])
    if code != 0:
        errors.append(f"(xi) {tag}: `helm lint` fails on the file itself, so a refusal below proves nothing:\n{output.strip()}")
    else:
        for extra, key in CONTRADICTIONS:
            code, output = lint(values_file, extra)
            if code == 0:
                errors.append(f"(xi) {tag}: `helm lint {' '.join(extra)}` passed — the schema must refuse that contradiction of ingress.tls.acme")
            elif key not in output:
                errors.append(f"(xi) {tag}: `helm lint {' '.join(extra)}` failed, but its output never names `{key}` — a failure for some other reason is not the schema holding:\n{output.strip()}")
    return rendered


values_files = sorted(CI_DIR.glob("*.yaml"))
if not values_files:
    errors.append(f"{CI_DIR.relative_to(ROOT)} holds no values file — nothing would be rendered, and a run over nothing is not a green")
digests_set: set[str] = set()
renders = {f.name: check(f) for f in values_files}

# (xiv) the digest path is rendered by some file, and the schema refuses a digest that is not one.
for which in ("app", "admin"):
    if values_files and which not in digests_set:
        errors.append(f"(xiv) no file in {CI_DIR.relative_to(ROOT)} sets the {which} image's digest, so the `repository:tag@digest` render is checked by nobody")
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
                errors.append(f"(xviii) `helm lint {' '.join(extra)}` passed — an enabled policy set with no ingress controller named must be refused")
            elif "ingressFrom" not in output:
                errors.append(f"(xviii) `helm lint {' '.join(extra)}` failed, but its output never names `ingressFrom`:\n{output.strip()}")
    for key in ("image.digest", "admin.image.digest"):
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
    f"config-secret path is on a claim with fsGroup={runtime_user}; strategy Recreate; every render byte-identical "
    f"twice; every container restricted-compliant with a read-only root; the admin surface on a ClusterIP Service and "
    f"no Ingress; the admin listener on a Service of its own that no Ingress names and the admin surface points at, "
    f"admitted by policy from the admin pod alone; a default-deny over every pod, the public port admitted from "
    f"ingressFrom exactly and the database from the app alone, no policy when switched off, and no install without "
    f"ingressFrom; the ACME "
    f"opt-in absent where off, and where on one `{ISSUER_NAME}` Issuer on every Ingress and in the properties file, "
    f"its contradictions refused by the schema; a pinned image digest is the digest the pod runs"
)
