#!/usr/bin/env python3
"""Guard that a containerised veyq keeps its config-secret KEK across a container recreate.

**What it asserts.** For every service in every compose file under `infra/` that runs the veyq **app**, the path
`veyq.security.config-secret-file` resolves to *inside that container* lives under a mount that survives the
container. Resolution follows the real rule rather than a paraphrase of it:

  1. if the service sets `VEYQ_SECURITY_CONFIG_SECRET_FILE`, that value wins;
  2. otherwise the `@WithDefault` on `VeyqConfig.Security.configSecretFile()`, resolved against the
     `Dockerfile`'s runtime `WORKDIR` — because the default is RELATIVE and therefore means "the process CWD".

That is three files that must agree — `Dockerfile`, `VeyqConfig.kt`, and a compose file — which no build, test
or type checks against each other. It also asserts that **every `runtime-*` target's `WORKDIR` agrees**: a
service that names a published `image:` cannot say which target it runs, so the relative default is only
resolvable while the two targets have not drifted apart. And it refuses to pass vacuously: zero app services
across all of `infra/` is a failure, not a green run over nothing.

**The defect that bought it.** ADR 0056 § Consequences surfaced it and this guard's CR fixed it: no image
declared a volume for the key file and no compose file mounted one, so a recreated app container regenerated
its KEK, the `veyq_instance` fingerprint reported `configSecretState: MISMATCH`, and the instance refused
member invites and password resets with `503 mail_unavailable` (ADR 0050 amendment, ADR 0055) — an instance
that boots, serves every page, and cannot admit a member. The stale `VeyqConfig.kt:224` line reference in the
`Dockerfile` comment that described the trap is itself the argument for a guard over prose: a line number in a
comment rots silently, a resolution rule does not.

**Its limit — what a green run does NOT prove.** It sees **compose files in this tree**, and only what they
say. It cannot prove the host actually persists the named volume (a `down -v`, a wiped disk, a volume driver
that lies), it cannot prove the *image* the service pulls is the one this `Dockerfile` builds, and it says
nothing at all about a deployment that is not compose — a bare `docker run`, a systemd unit. The Helm chart
(ADR 0064) has its own guard for the same path: `scripts/chart_meta_check.py` resolves it by this file's rule
and requires it on a PersistentVolumeClaim, outside the `validate_*` glob because it renders the chart with
`helm`. For everything else the `veyq_instance` fingerprint tripwire remains the only detection, by design.

Two accepted shapes are deliberately *not* violations, because rejecting them would punish correct
configuration and the repair it invited would break the deployment: a **host bind mount** persists exactly as
well as a named volume does, and a **docker secret** at `/run/secrets/<name>` is the K8s/docker-secret shape
`VeyqConfig.kt` names for an operator supplying their own key. Both are accepted; what stays rejected is an
unmounted path, a path under no mount at all, and a value this guard cannot resolve (an un-substituted
`${VAR}`), because an unresolvable path is not a proven one.
"""
from __future__ import annotations

import pathlib
import posixpath
import re
import sys

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the other guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOCKERFILE = ROOT / "Dockerfile"
CONFIG_KT = ROOT / "backend/src/main/kotlin/com/veyq/app/shared/config/VeyqConfig.kt"
INFRA = ROOT / "infra"

ENV_KEY = "VEYQ_SECURITY_CONFIG_SECRET_FILE"

# `FROM <image> AS <stage>` — the stage name is what a compose `build.target` names.
STAGE = re.compile(r"^\s*FROM\s+\S+(?:\s+--\S+)*\s+AS\s+(\S+)\s*$", re.IGNORECASE | re.MULTILINE)
WORKDIR = re.compile(r"^\s*WORKDIR\s+(\S+)\s*$", re.IGNORECASE)
# The @WithDefault that governs the path, anchored on the accessor it decorates rather than on "the first
# @WithDefault in the file" — there are dozens, and matching the marker is not the same as matching a paraphrase.
KT_DEFAULT = re.compile(r'@WithDefault\("([^"]*)"\)\s*\n\s*fun\s+configSecretFile\s*\(\s*\)')

# Nothing needs excluding by name here any more. The one member this set ever held was the store bootstrapper,
# whose image a naive `image: *veyq*` would have claimed as the app; ADR 0067 moved that work into the backend
# and deleted the service from both compose files, so the member became genuinely dead. The set stays as the
# seam: a future veyq-namespaced deployable that is not the app belongs in it, and the empty set is a statement
# rather than an oversight.
NOT_THE_APP: set[str] = set()
# `ghcr.io/<owner>/veyq:<tag>` or a bare `veyq:<tag>`. The character after `veyq` has to be `:` or the end of
# the string, so a `veyq-<something>:<tag>` sibling is NOT claimed as the app — live today for the admin
# surface's `veyq-admin:<tag>`, which the release recipe names beside the app.
APP_IMAGE = re.compile(r"(?:^|/)veyq(?::|$)")

errors: list[str] = []


def env_map(service: dict) -> dict[str, str]:
    """`environment:` is legal as a mapping OR as a `KEY=VALUE` list. Enumerate both spellings, don't guess."""
    env = service.get("environment") or {}
    if isinstance(env, dict):
        return {str(k): "" if v is None else str(v) for k, v in env.items()}
    out: dict[str, str] = {}
    for item in env:
        key, _, value = str(item).partition("=")
        out[key] = value
    return out


def mounts(service: dict) -> list[tuple[str, str]]:
    """`(source, container_path)` for every volume, in both the short and the long form."""
    out: list[tuple[str, str]] = []
    for entry in service.get("volumes") or []:
        if isinstance(entry, dict):
            source, target = str(entry.get("source", "")), str(entry.get("target", ""))
        else:
            parts = str(entry).split(":")
            if len(parts) < 2:
                # An anonymous volume (`- /some/path`). It persists a restart and NOT a recreate, under a name
                # no `docker volume ls` identifies — precisely the shape this guard exists to reject.
                source, target = "", parts[0]
            else:
                source, target = parts[0], parts[1]
        if target:
            out.append((source, target))
    return out


def covers(container_path: str, mount_point: str) -> bool:
    mount_point = mount_point.rstrip("/") or "/"
    return container_path == mount_point or container_path.startswith(mount_point + "/")


def runtime_workdirs() -> dict[str, str]:
    """Every `runtime-*` stage's WORKDIR. There is more than one, and a guard anchored on one is no guard."""
    body = DOCKERFILE.read_text()
    found: dict[str, str] = {}
    stages = [(m.start(), m.group(1)) for m in STAGE.finditer(body)]
    for index, (offset, name) in enumerate(stages):
        end = stages[index + 1][0] if index + 1 < len(stages) else len(body)
        if not name.startswith("runtime-"):
            continue
        for line in body[offset:end].splitlines():
            match = WORKDIR.match(line)
            if match:
                found[name] = match.group(1)
    return found


def resolve(raw: str, workdir: str | None) -> str | None:
    if raw.startswith("/"):
        return posixpath.normpath(raw)
    if workdir is None:
        return None
    return posixpath.normpath(posixpath.join(workdir, raw))


workdirs = runtime_workdirs()
if not workdirs:
    errors.append("Dockerfile declares no `runtime-*` stage with a WORKDIR — the relative default cannot be resolved")

distinct = set(workdirs.values())
if len(distinct) > 1:
    # A service that names a published `image:` cannot say which target it runs, so the relative default is
    # only meaningful while both agree. Drift here is the finding, not a detail to paper over.
    errors.append(
        "the runtime stages disagree about WORKDIR (" +
        ", ".join(f"{name}={path}" for name, path in sorted(workdirs.items())) +
        f") — a compose service naming an image cannot say which one it runs, so `{ENV_KEY}`'s relative "
        f"default resolves to two different paths"
    )
workdir = next(iter(distinct)) if len(distinct) == 1 else None

kt_match = KT_DEFAULT.search(CONFIG_KT.read_text()) if CONFIG_KT.is_file() else None
if not kt_match:
    errors.append(
        f"no `@WithDefault(...)` found on configSecretFile() in {CONFIG_KT.relative_to(ROOT)} — the default "
        f"this guard resolves against has moved or been renamed"
    )
default_path = kt_match.group(1) if kt_match else None

checked = 0
for compose in sorted(list(INFRA.glob("*.yml")) + list(INFRA.glob("*.yaml"))):
    rel = compose.relative_to(ROOT).as_posix()
    try:
        doc = yaml.safe_load(compose.read_text()) or {}
    except yaml.YAMLError as e:
        errors.append(f"{rel} is not readable YAML: {e}")
        continue
    declared = set((doc.get("volumes") or {}).keys()) if isinstance(doc.get("volumes"), dict) else set()

    for name, service in (doc.get("services") or {}).items():
        if not isinstance(service, dict) or name in NOT_THE_APP:
            continue
        build = service.get("build")
        target = build.get("target") if isinstance(build, dict) else None
        env = env_map(service)
        is_app = (
            (isinstance(target, str) and target.startswith("runtime-"))
            or APP_IMAGE.search(str(service.get("image") or ""))
            or "VEYQ_IMAGE" in str(service.get("image") or "")
            or any(k.startswith("QUARKUS_") or k.startswith("VEYQ_BLOB_STORE_") for k in env)
        )
        if not is_app:
            continue
        checked += 1
        where = f"{rel} service `{name}`"

        raw = env.get(ENV_KEY, default_path)
        if raw is None:
            continue  # the Kotlin default could not be read; already reported once, above
        if "${" in raw:
            errors.append(
                f"{where}: {ENV_KEY} is `{raw}`, which this guard cannot resolve. An unresolvable path is not "
                f"a proven one — write the container path literally."
            )
            continue

        base = workdirs.get(target) if target else workdir
        path = resolve(raw, base)
        if path is None:
            errors.append(f"{where}: the config-secret path `{raw}` is relative and no WORKDIR resolves it")
            continue

        secrets = {str(s.get("source", s)) if isinstance(s, dict) else str(s) for s in (service.get("secrets") or [])}
        if any(path == f"/run/secrets/{s}" for s in secrets):
            continue  # the operator supplies their own key as a docker secret — the shape VeyqConfig.kt names

        hit = next(((source, target_path) for source, target_path in mounts(service) if covers(path, target_path)), None)
        if hit is None:
            errors.append(
                f"{where}: the config-secret key resolves to `{path}`, which no volume mounts. Every "
                f"`up -d --force-recreate` then regenerates the KEK and the instance reports "
                f"configSecretState: MISMATCH, refusing member invites and password resets with "
                f"503 mail_unavailable (ADR 0056 § Consequences). Mount a named volume over it, or point "
                f"{ENV_KEY} at one."
            )
            continue
        source, mount_point = hit
        if not source:
            errors.append(
                f"{where}: `{path}` sits under the ANONYMOUS volume `{mount_point}`. That survives a restart "
                f"and not a recreate, under a name no `docker volume ls` identifies — which is worse than no "
                f"mount, because it looks like persistence. Name the volume."
            )
        elif source in declared or source.startswith("/") or source.startswith("."):
            continue  # a declared named volume, or a host bind mount — both persist a recreate
        else:
            errors.append(
                f"{where}: `{path}` sits under mount `{source}:{mount_point}`, but `{source}` is neither a "
                f"volume declared in this file nor a host path — nothing says it persists"
            )

if not checked and not errors:
    # The `NoE2eFilesError` lesson, in another surface: a guard that checked nothing and a guard whose subject
    # is sound report the same green.
    errors.append(
        f"no veyq app service found in any compose file under {INFRA.relative_to(ROOT)}/ — this guard checked "
        f"NOTHING and would report green either way"
    )

if errors:
    print("CONFIG-SECRET PERSISTENCE VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(f"config-secret persistence OK: {checked} app service(s) keep their KEK across a recreate "
      f"(default `{default_path}` resolved against WORKDIR {workdir})")
