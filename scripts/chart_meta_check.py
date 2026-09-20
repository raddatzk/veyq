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
  v.   **Every pod is `restricted`-compliant, admin surface included.** Each container's securityContext drops
       all capabilities, forbids privilege escalation, runs non-root and uses the runtime default seccomp
       profile. The admin image was made unprivileged for exactly this (ADR 0065,
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
  xi.  **The schema refuses the three contradictions of the opt-in, naming the key** — `acme.enabled` with a
       mode other than `per-domain`, with an empty `email`, and beside an operator's own
       `cert-manager.io/cluster-issuer` annotation — each a `helm lint` that must exit non-zero AND whose output
       names the offending key; the same lint without the contradiction must pass (the positive control). An
       exit code alone cannot tell "the schema held" from any other failure. Measured on helm v4.0.1: lint
       prints the schema violation on STDOUT (`[ERROR] values.yaml: - at '/ingress/…'`) and only a summary on
       stderr, so both streams are read.

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
ConfigMap alone was the injection that turned only one of them red.

**Its limits — what a green run does NOT prove.** It renders the chart; it never installs it. So it proves what
the manifests say and nothing about a cluster: not that a StorageClass exists for the claim, not that the
ingress controller honours the annotations, not that cert-manager is installed or that an `Issuer` it accepts
ever obtains a certificate (no cluster runs here — HTTP-01 reachability, the CA, the account key's lifecycle are
all outside), not that the images exist to be pulled, not that a `restricted` namespace actually admits the pods
(the standard has fields this does not enumerate — it checks the four the chart sets, and a future field the
standard adds is out of reach). It renders only the values files in `ci/`, so a value path none of them sets is
checked at its default alone — `admin.domain` set, `mariadb.enabled` false, `ingress.tls.mode: shared` and
`per-domain` without the opt-in are rendered by nobody here. (ii) checks the app container's mount and the pod's
`fsGroup`; it does not check the claim's access mode or that the same claim is not also mounted elsewhere. (xi)
checks three contradictions, not the schema's completeness. And it says nothing about the compose recipe, which
its two siblings own.

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

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the other guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
CHART = ROOT / "charts" / "veyq"
CI_DIR = CHART / "ci"
SINK_KT = ROOT / "backend/src/main/kotlin/com/veyq/app/shared/tenant/KubernetesIngressSink.kt"
CONFIG_KT = ROOT / "backend/src/main/kotlin/com/veyq/app/shared/config/VeyqConfig.kt"
DOCKERFILE = ROOT / "Dockerfile"

ENV_KEY = "VEYQ_SECURITY_CONFIG_SECRET_FILE"
# The same anchors the compose guards use, for the same reasons.
STAGE = re.compile(r"^\s*FROM\s+\S+(?:\s+--\S+)*\s+AS\s+(\S+)\s*$", re.IGNORECASE | re.MULTILINE)
WORKDIR = re.compile(r"^\s*WORKDIR\s+(\S+)\s*$", re.IGNORECASE)
USER = re.compile(r"^\s*USER\s+(\S+)\s*$", re.IGNORECASE)
KT_DEFAULT = re.compile(r'@WithDefault\("([^"]*)"\)\s*\n\s*fun\s+configSecretFile\s*\(\s*\)')
# `const val OWNER_LABEL = "…"` / `const val OWNER_VALUE = "…"` — the sink's own constants, read not retyped.
KT_CONST = re.compile(r'const\s+val\s+(OWNER_LABEL|OWNER_VALUE)\s*=\s*"([^"]+)"')
APP_IMAGE = re.compile(r"(?:^|/)veyq(?::|$)")
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


def render(values: pathlib.Path) -> str:
    out = subprocess.run(
        [helm(), "template", "veyq", str(CHART), "-f", str(values)],
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
renders = {f.name: check(f) for f in values_files}

if errors:
    print("CHART META CHECK FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

summary = "; ".join(f"{name}: {len(objs)} objects" for name, objs in renders.items())
print(
    f"chart meta check OK ({summary}): no object carries the sink's ownership value or is cluster-scoped; the app's "
    f"config-secret path is on a claim with fsGroup={runtime_user}; strategy Recreate; every render byte-identical "
    f"twice; every container restricted-compliant; the admin surface on a ClusterIP Service and no Ingress; the ACME "
    f"opt-in absent where off, and where on one `{ISSUER_NAME}` Issuer on every Ingress and in the properties file, "
    f"its contradictions refused by the schema"
)
