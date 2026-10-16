#!/usr/bin/env python3
"""Every service of the production compose recipe runs unprivileged, and the internet-facing proxy cannot reach the database.

**What it asserts** (ADR 0071), over `infra/docker-compose.prod.yml` as YAML with its merge keys resolved, and
over every service the file declares — the set is the file's, never a list here:

  1. **The posture, on every service:** `cap_drop` includes `ALL`, `security_opt` includes
     `no-new-privileges:true`, and `read_only` is true. No service adds a capability back, with the one exception
     in 2.
  2. **Every service whose image is not built from this repository sets a non-root `user:`.** An image is this
     repository's when it names one `.github/workflows/ci.yml` publishes (the same derivation as
     `validate_release_identity.py` assertion 5); those declare their `USER` in their Dockerfile, which
     `validate_images_run_unprivileged.py` holds, so a second copy here would only drift. The one service allowed
     to run as root is the **ownership step**, and only in that shape: it adds back `CHOWN` and nothing else,
     never restarts, has no network, and publishes nothing.
  3. **The ownership step chowns to the uid that writes.** It mounts each volume at `/chown/<uid>/…`, and its
     script reads the uid from that path. So every other service that mounts one of those volumes writable must
     run as that `<uid>` and must wait for the step (`condition: service_completed_successfully`). A mount of it
     outside `/chown/<digits>/` is one the script never touches.
  4. **Reachability is separated by peer.** Every service names its networks (or `network_mode: none`), so none
     lands on an implicit `default`. The proxy — every service publishing a port on an interface other than
     loopback — shares no network with the database, which is the service the app's `QUARKUS_DATASOURCE_JDBC_URL`
     names. The positive control on the same predicate: the app shares a network with each of them, or the
     separation would also have cut a path the stack uses.

**Scoped to the production file on purpose.** The dev stack (`infra/docker-compose.yml`) is not internet-facing:
it runs no app container, publishes its ports for a developer's host, and ships `change-me` passwords. A rule
that made its services unprivileged would guard a stack nobody deploys.

**The defect that bought it — live when this guard was written (cr-runtime-hardening-both-targets).** No service
of the production recipe set `user:`, `cap_drop`, `no-new-privileges` or `read_only`, Garage ran as uid 0 where
the chart runs it as 1000, and every service shared one network, so the internet-facing Traefik could open a
connection to MariaDB (ADR 0071 § Context). Its first run on that tree was red on every service: no posture
option anywhere, three third-party images as root, and all five on the implicit `default`, Traefik beside MariaDB.
Assertion 3 exists because the failure it catches is silent: a non-root Traefik whose `/acme` it cannot write
stays up and never obtains a certificate (ADR 0071, measured on `traefik:v3.6`).

**Its limits — what a green run does NOT prove.** It reads the file; it runs nothing. So a read-only root that
misses a scratch path, a `tmpfs:` a process cannot write, or a uid the image cannot run as are all green here
and fail only at runtime — which is why ADR 0071 proves a change to this file by a live `up`, not by this guard.
It cannot tell a uid that the image's own files agree with from one they do not. Assertion 3 covers only the
volumes the ownership step mounts: a new third-party service with a volume its image does not seed, and no
mount in the step, is green here and exits at its first write. The proxy is recognised by what it publishes, so
a proxy reached through some other mechanism (a host network, a second compose file) is out of reach. And it
says nothing about the chart, which `scripts/chart_meta_check.py` holds to the same posture and whose
NetworkPolicy set is this file's networks on the other target.

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
COMPOSE = ROOT / "infra" / "docker-compose.prod.yml"
WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
BUILD_JOB = "build"

# A veyq-namespaced image name where an image reference carries one — validate_release_identity.py's pattern, for
# the same reason: the recipe's own images arrive as `${VEYQ_IMAGE:?…, e.g. ghcr.io/<owner>/veyq:1.0}`.
VEYQ_IMAGE_NAME = re.compile(r"(?:^|[\s/])(veyq(?:-[a-z0-9]+)*)(?=[:@\s}]|$)")
NO_NEW_PRIVILEGES = {"no-new-privileges", "no-new-privileges:true", "no-new-privileges=true"}
OWNERSHIP_MOUNT = re.compile(r"^/chown/(\d+)/[^/]+$")
LOOPBACK = {"127.0.0.1", "::1", "[::1]", "localhost"}
JDBC_HOST = re.compile(r"^jdbc:[a-z]+://([^:/]+)")
INTERPOLATION = re.compile(r"\$\{[^}]*\}")

errors: list[str] = []


def fail(message: str) -> None:
    errors.append(f"{COMPOSE.relative_to(ROOT)}: {message}")


def published_images() -> set[str]:
    doc = yaml.safe_load(WORKFLOW.read_text()) or {}
    matrix = ((doc.get("jobs") or {}).get(BUILD_JOB) or {}).get("strategy", {}).get("matrix", {})
    return {str(i) for i in (matrix.get("image") or [])}


def uid_of(service: dict) -> str | None:
    user = service.get("user")
    return None if user is None else str(user).split(":", 1)[0]


def is_root(uid: str | None) -> bool:
    return uid is None or uid in {"0", "root"}


def env_of(service: dict) -> dict[str, str]:
    env = service.get("environment") or {}
    if isinstance(env, dict):
        return {str(k): str(v) for k, v in env.items()}
    return {str(i).partition("=")[0]: str(i).partition("=")[2] for i in env}


def mounts(service: dict) -> list[tuple[str, str, bool]]:
    """`(source, target, read_only)` for every volume entry, short or long form."""
    out: list[tuple[str, str, bool]] = []
    for entry in service.get("volumes") or []:
        if isinstance(entry, dict):
            out.append((str(entry.get("source", "")), str(entry.get("target", "")), bool(entry.get("read_only"))))
        else:
            parts = str(entry).split(":")
            if len(parts) >= 2:
                out.append((parts[0], parts[1], "ro" in parts[2:]))
    return out


def publishes_publicly(service: dict) -> bool:
    for entry in service.get("ports") or []:
        if isinstance(entry, dict):
            host_ip = str(entry.get("host_ip") or "")
        else:
            # `[ip:]host:container` — an ip is present only with three parts. A `${VAR:-default}` carries a colon of
            # its own, so each interpolation is collapsed to one token before the parts are counted.
            text = INTERPOLATION.sub("VAR", str(entry))
            host_ip = text.rsplit(":", 2)[0] if text.count(":") >= 2 else ""
        if host_ip not in LOOPBACK:
            return True
    return False


def networks_of(service: dict) -> set[str]:
    """The networks a service is on — Compose's implicit `default` when it names none, which is exactly the shared
    network 4 exists to catch, so it is counted rather than read as "no network"."""
    if service.get("network_mode"):
        return set()
    nets = service.get("networks")
    return set(nets) if isinstance(nets, (list, dict)) and nets else {"default"}


def waits_for(service: dict, step: str) -> bool:
    deps = service.get("depends_on") or {}
    if isinstance(deps, list):
        return False  # the short form waits for a start, never for a completion
    return ((deps.get(step) or {}).get("condition")) == "service_completed_successfully"


try:
    doc = yaml.safe_load(COMPOSE.read_text()) or {}
except (OSError, yaml.YAMLError) as e:
    print(f"COMPOSE POSTURE CHECK FAILED:\n  - {COMPOSE.relative_to(ROOT)} is not readable YAML: {e}")
    sys.exit(1)

services: dict[str, dict] = {n: s for n, s in (doc.get("services") or {}).items() if isinstance(s, dict)}
if not services:
    fail("declares no services — this guard would check NOTHING")
ours = published_images()
if not ours:
    errors.append(f"{WORKFLOW.relative_to(ROOT)}'s `{BUILD_JOB}` job publishes no image — every service would read as third-party")


def built_here(service: dict) -> bool:
    return any(name in ours for name in VEYQ_IMAGE_NAME.findall(str(service.get("image", ""))))


def is_ownership_step(service: dict) -> bool:
    return (
        [str(c).upper() for c in service.get("cap_add") or []] == ["CHOWN"]
        and str(service.get("restart")) == "no"
        and service.get("network_mode") == "none"
        and not service.get("ports")
    )


# --- 1 + 2: posture and user ---
steps: list[str] = []
for name, service in sorted(services.items()):
    if "ALL" not in [str(c).upper() for c in service.get("cap_drop") or []]:
        fail(f"service `{name}` does not `cap_drop: [ALL]` (ADR 0071 §1)")
    if not NO_NEW_PRIVILEGES & {str(o).replace(" ", "") for o in service.get("security_opt") or []}:
        fail(f"service `{name}` does not set `security_opt: [no-new-privileges:true]` (ADR 0071 §1)")
    if service.get("read_only") is not True:
        fail(f"service `{name}` does not set `read_only: true` — give each path it writes a volume or a `tmpfs:` (ADR 0071 §1)")
    ownership = is_ownership_step(service)
    if ownership:
        steps.append(name)
    elif service.get("cap_add"):
        fail(f"service `{name}` adds capabilities back ({service.get('cap_add')}) — only the ownership step may, and only CHOWN")
    if built_here(service):
        continue
    if is_root(uid_of(service)) and not ownership:
        fail(
            f"service `{name}` runs a third-party image as {'root' if service.get('user') is not None else 'the image default (root)'} — "
            f"set a non-root `user:`. Root is allowed only in the ownership step's shape: cap_add [CHOWN] alone, "
            f"restart \"no\", network_mode none, no ports."
        )

# --- 3: the ownership step chowns to the uid that writes ---
for step in steps:
    for source, target, _ in mounts(services[step]):
        match = OWNERSHIP_MOUNT.match(target)
        if match is None:
            fail(f"the ownership step `{step}` mounts `{source}` at `{target}`, outside `/chown/<uid>/<name>` — its script never chowns it")
            continue
        uid = match.group(1)
        writers = [(n, s) for n, s in services.items() if n != step
                   and any(src == source and not ro for src, _, ro in mounts(s))]
        if not writers:
            fail(f"the ownership step `{step}` chowns `{source}`, which no service mounts writable — a stale entry, or its writer moved")
        for writer, service in writers:
            if uid_of(service) != uid:
                fail(
                    f"service `{writer}` writes `{source}` as uid {uid_of(service)!r}, but the ownership step chowns it to "
                    f"{uid} — the service cannot write its own volume (a non-root Traefik then never obtains a certificate, silently)"
                )
            if not waits_for(service, step):
                fail(f"service `{writer}` writes `{source}` but does not wait for `{step}` with condition: service_completed_successfully")

# --- 4: networks ---
for name, service in sorted(services.items()):
    if service.get("network_mode") == "none":
        continue
    if not service.get("networks"):
        fail(f"service `{name}` names no network, so Compose puts it on an implicit `default` nobody separated (ADR 0071 §3)")

app = next((n for n, s in services.items() if "QUARKUS_DATASOURCE_JDBC_URL" in env_of(s)), None)
jdbc = JDBC_HOST.match(env_of(services[app]).get("QUARKUS_DATASOURCE_JDBC_URL", "")) if app else None
database = jdbc.group(1) if jdbc and jdbc.group(1) in services else None
proxies = sorted(n for n, s in services.items() if publishes_publicly(s))
if app is None:
    fail("no service carries QUARKUS_DATASOURCE_JDBC_URL — the app, and through it the database, cannot be found")
elif database is None:
    fail(f"the app's QUARKUS_DATASOURCE_JDBC_URL names no service of this file — the database cannot be found, so its separation is unchecked")
if not proxies:
    fail("no service publishes a port beyond loopback — the proxy cannot be found, so its separation is unchecked")
if database:
    for proxy in proxies:
        shared = networks_of(services[proxy]) & networks_of(services[database])
        if shared:
            fail(f"the internet-facing `{proxy}` shares {sorted(shared)} with the database `{database}` — it can open a connection to it (ADR 0071 §3)")
        if app and not networks_of(services[proxy]) & networks_of(services[app]):
            fail(f"control: the proxy `{proxy}` shares no network with the app `{app}` — the separation has cut the path every request takes")
    if app and not networks_of(services[database]) & networks_of(services[app]):
        fail(f"control: the database `{database}` shares no network with the app `{app}` — the separation has cut the app off its database")

if errors:
    print("COMPOSE POSTURE CHECK FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)
print(
    f"compose posture OK: {len(services)} services in {COMPOSE.relative_to(ROOT)} drop every capability, cannot "
    f"escalate and run read-only; every third-party image runs non-root except the ownership step "
    f"({', '.join(steps) or 'none'}), which chowns to the uid that writes; the proxy ({', '.join(proxies)}) shares no "
    f"network with the database ({database}), and the app reaches both"
)
