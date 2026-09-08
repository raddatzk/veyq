#!/usr/bin/env python3
"""Every named volume the app WRITES must be a path the runtime image already owns.

**What it asserts.** For each compose service under `infra/` that runs the veyq app, take every mount whose
source is a **named volume declared in that file** and that is not read-only, and assert that every
`runtime-*` stage of the `Dockerfile` (a) creates the mount's container path and (b) `chown`s it to the
runtime user the stage's `USER` names. Both halves matter and neither implies the other: Docker seeds a fresh
named volume from the image directory it covers — ownership included — so a path the image does not create is
a volume that comes up `root:root`, and a path it creates but leaves root-owned is the same failure spelled
differently.

**The defect that bought it — measured, not reasoned.** `infra/docker-compose.prod.yml` mounts
`veyq-traefik-dynamic` at `/etc/traefik/dynamic` for the tenant-ingress projection (ADR 0063). No runtime
stage created that directory, so on a FIRST-EVER `docker compose up` of the production stack the volume came
up `root:root 0755`, the app runs as uid 10001, and `TenantIngressProjector.onStartup` died on its first
write:

    java.nio.file.AccessDeniedException: /etc/traefik/dynamic/.tenants.yml<random>.tmp
        at TenantIngressProjector.writeAtomically(TenantIngressProjector.kt:134)

Quarkus fails the startup event and the container restart-loops — so the documented production stack could
not start its app at all, on the one path every new operator takes. It survived because this repository has
no remote and the production stack had never actually been deployed (`docs/inbox.md` #26); it surfaced when
`cr-admin-web-extraction` brought the real stack up to prove a reachability claim.

What makes it worth a guard rather than a fix: the sibling mount on `/var/lib/veyq` was **correct**, and the
`Dockerfile` comment above it spells out this exact mechanism in six lines. The rule was known, written down,
and load-bearing — and the next volume added to the app service (a year later, by ADR 0063) did not inherit
it, because prose is not enumerated. So this guard derives the set from the compose file, not from a list:
adding a third volume enrols it automatically.

**Its limits — what a green run does NOT prove.** It reads compose files *in this tree* and the `Dockerfile`
*in this tree*; it cannot prove the image a service pulls was built from that `Dockerfile`, and it says
nothing about a non-compose deployment (Kubernetes, a bare `docker run`) — for those, ADR 0064's chart owns
its own volume semantics. It checks ownership and existence, never the MODE: a directory the app can write is
what the failure was about, and a mode narrow enough to break a *reader* (traefik reads this one) would be a
different guard over a different fact. And it deliberately ignores read-only mounts and host bind mounts:
a `:ro` mount is not written by definition, and a bind mount takes the host's ownership, which no image can
seed and no guard in this tree can see.

Buildless and diff-free, so it belongs in the trust-chain glob.
"""
from __future__ import annotations  # `str | None` below — the repo's guards run on Python 3.9

import pathlib
import re
import sys

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the other guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOCKERFILE = ROOT / "Dockerfile"
INFRA = ROOT / "infra"

# `FROM <image> AS <stage>` — the stage name is what a compose `build.target` names.
STAGE = re.compile(r"^\s*FROM\s+\S+(?:\s+--\S+)*\s+AS\s+(\S+)\s*$", re.IGNORECASE | re.MULTILINE)
USER = re.compile(r"^\s*USER\s+(\S+)\s*$", re.IGNORECASE)

# Same two independent exclusions the sibling guard uses: the store bootstrapper's image is
# `veyq-garage-init:v2.0.0`, which a naive `*veyq*` match would enrol as the application, and the admin
# surface's is `veyq-admin:<tag>` — a different deployable that mounts nothing.
NOT_THE_APP = {"garage-init", "admin"}
APP_IMAGE = re.compile(r"(?:^|/)veyq(?::|$)")

errors: list[str] = []


def mounts(service: dict) -> list[tuple[str, str, bool]]:
    """`(source, container_path, read_only)` for every volume, in both the short and the long form."""
    out: list[tuple[str, str, bool]] = []
    for entry in service.get("volumes") or []:
        if isinstance(entry, dict):
            source, target = str(entry.get("source", "")), str(entry.get("target", ""))
            read_only = bool(entry.get("read_only"))
        else:
            parts = str(entry).split(":")
            if len(parts) < 2:
                source, target, read_only = "", parts[0], False
            else:
                source, target = parts[0], parts[1]
                read_only = "ro" in parts[2:]
        if target:
            out.append((source, target.rstrip("/") or "/", read_only))
    return out


def is_app(name: str, service: dict) -> bool:
    if name in NOT_THE_APP:
        return False
    image = str(service.get("image") or "")
    if image and APP_IMAGE.search(image.split("${")[0] or image):
        return True
    # An `image:` built from an interpolated variable (`${VEYQ_IMAGE:?…}`) names nothing this guard can read,
    # so fall back to the marker that is not a name: only the app is given the datasource it migrates.
    env = service.get("environment") or {}
    keys = set(env) if isinstance(env, dict) else {str(i).partition("=")[0] for i in env}
    return "QUARKUS_DATASOURCE_JDBC_URL" in keys


def runtime_stages() -> dict[str, str]:
    """Each `runtime-*` stage's body, keyed by stage name. There is more than one, and a guard anchored on
    one is no guard — the published image could have been built from either target."""
    body = DOCKERFILE.read_text()
    stages = [(m.start(), m.group(1)) for m in STAGE.finditer(body)]
    out: dict[str, str] = {}
    for index, (offset, name) in enumerate(stages):
        end = stages[index + 1][0] if index + 1 < len(stages) else len(body)
        if name.startswith("runtime-"):
            out[name] = body[offset:end]
    return out


def runtime_user(stage_body: str) -> str | None:
    """The LAST `USER` in the stage — the one the ENTRYPOINT actually runs as."""
    found = [m.group(1) for line in stage_body.splitlines() for m in [USER.match(line)] if m]
    return found[-1] if found else None


# The argument list of ONE shell command: everything up to the newline, the line-continuation backslash or the
# next `&&`. Getting this boundary right is the whole predicate — see `creates` below.
def _arguments(command: str, stage_body: str) -> list[str]:
    return [m.group(1) for m in re.finditer(rf"\b{command}((?:[^\n\\&]|&(?!&))*)", stage_body)]


def creates(stage_body: str, path: str) -> bool:
    """Does the stage `mkdir` this exact path?

    The argument list is bounded to a SINGLE command on purpose, and that bound is the guard's own bite test.
    The first version of this predicate was `mkdir\s+(?:-\S+\s+)*(?:\S+\s+)*<path>` — and `\s` matches
    newlines, so it happily chewed across `&& chmod …` and `&& chown …` and found the path in a LATER
    command's arguments. Under it, deleting a path from `mkdir` while leaving it in `chown` read as green: the
    guard passed a Dockerfile that could not seed the volume. Caught by biting the guard on exactly that edit,
    which is why the boundary is spelled out rather than left to `\s`.

    Matched as a whole word so `/var/lib/veyq` never satisfies a claim about `/var/lib/veyq-other`."""
    return any(
        re.search(rf"(?:^|\s){re.escape(path)}(?=\s|$)", args) for args in _arguments("mkdir", stage_body)
    )


def chowns(stage_body: str, path: str, user: str) -> bool:
    """Does the stage `chown` this exact path to the runtime user? The uid is matched as it appears in the
    `USER` line, so a stage that runs as `10001` must chown to `10001:` (a `veyq:veyq` chown would be a
    different claim this guard cannot resolve to a uid without reading an /etc/passwd it does not have)."""
    for args in _arguments("chown", stage_body):
        parts = args.split()
        while parts and parts[0].startswith("-"):
            parts.pop(0)
        if not parts or parts[0].split(":")[0] != user:
            continue
        if path in parts[1:]:
            return True
    return False


stages = runtime_stages()
if not stages:
    errors.append("Dockerfile declares no `runtime-*` stage — there is no image for a volume to be seeded from")

checked = 0
for compose in sorted(INFRA.glob("*.yml")) + sorted(INFRA.glob("*.yaml")):
    try:
        doc = yaml.safe_load(compose.read_text()) or {}
    except yaml.YAMLError as exc:
        errors.append(f"{compose.relative_to(ROOT)}: invalid YAML: {exc}")
        continue
    declared = set(doc.get("volumes") or {})
    for name, service in (doc.get("services") or {}).items():
        if not isinstance(service, dict) or not is_app(name, service):
            continue
        for source, target, read_only in mounts(service):
            if source not in declared or read_only:
                continue  # a host bind mount takes the host's ownership; a `:ro` mount is not written
            checked += 1
            for stage, body in sorted(stages.items()):
                user = runtime_user(body)
                if user is None:
                    errors.append(
                        f"{compose.relative_to(ROOT)}: service `{name}` mounts `{source}` at `{target}`, but "
                        f"Dockerfile stage `{stage}` declares no USER — the volume's ownership cannot be checked"
                    )
                    continue
                if not creates(body, target):
                    errors.append(
                        f"{compose.relative_to(ROOT)}: service `{name}` mounts the named volume `{source}` at "
                        f"`{target}`, but Dockerfile stage `{stage}` never creates that directory. Docker seeds "
                        f"a fresh named volume from the image directory it covers — with nothing to seed from "
                        f"it comes up root:root, and the app (USER {user}) cannot write it. Add it to the "
                        f"stage's `mkdir -p` and `chown`."
                    )
                elif not chowns(body, target, user):
                    errors.append(
                        f"{compose.relative_to(ROOT)}: service `{name}` mounts the named volume `{source}` at "
                        f"`{target}`; Dockerfile stage `{stage}` creates that directory but never chowns it to "
                        f"`{user}` — a root-owned seed is the same failure as no seed at all."
                    )

if checked == 0:
    # A guard that inspected nothing must not report success: the app service losing its volumes, or this
    # guard's app detection drifting, both land here and both are findings.
    errors.append(
        "no writable named-volume mount was found on any app service under infra/ — either the production "
        "stack stopped persisting anything, or this guard no longer recognises the app service"
    )

if errors:
    print("APP VOLUME OWNERSHIP VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(
    f"app volume ownership OK: {checked} writable named-volume mount(s) are created and owned by the runtime "
    f"user in every runtime stage ({', '.join(sorted(stages))})"
)
