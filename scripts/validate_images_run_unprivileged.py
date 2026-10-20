#!/usr/bin/env python3
"""Every runtime image this repository ships runs as a non-root user and binds no privileged port — and is what a
bare `docker build` of its Dockerfile builds.

**What it asserts.** For every tracked `Dockerfile` (found by name through `git ls-files`, never listed), every
`runtime-*` stage — the stages `.github/workflows/ci.yml` publishes — (a) ends on a `USER` instruction
that names a non-root user (not `root`, not uid `0`), and (b) `EXPOSE`s no port below 1024. Both halves are
stated *in the stage itself*: a base image that happens to end on a non-root user is not enough, because a base
can be swapped or re-pinned without anyone re-reading what it ends on, and a stage that `USER root`s for its own
`apk add` and never switches back is exactly the shape this guard exists to catch. The two halves are one
property: under a `restricted` PodSecurity namespace `runAsNonRoot: true` is mandatory and the kubelet refuses a
root pod outright, and a non-root process cannot bind a port below 1024 in the first place — so a stage that
passes (a) and fails (b) ships a container that starts and then dies binding its listener.

**The defect that bought it — a live one, and the same rule failing to travel for the second time.** The app
image's runtime stage has run as uid 10001 since the `Dockerfile` was recovered, and
`validate_app_volume_ownership.py` leans on that `USER` line. The admin image (ADR 0065) was added later from a
stock `nginx:<pinned>-alpine`, which runs its master as root and binds port 80 — `grep -rn '^USER ' src/admin-web/Dockerfile`
returned nothing on 2026-09-14. Under compose that is invisible; under ADR 0064's chart it is an instance whose
admin surface — the one where first-run setup happens — cannot be scheduled into a `restricted` namespace at all,
so nobody can set it up. The first version of this predicate, run against the untouched tree, was green on both
app stages of the time and red on exactly `src/admin-web/Dockerfile runtime-nginx` (`USER=None`, `EXPOSE 80`), which
is the whole argument for a guard over prose: the rule was written in the `Dockerfile` and in a sibling guard's
docstring, and the second image did not inherit it, because prose is not enumerated.

**Its limits — what a green run does NOT prove.** It reads Dockerfiles, so it proves what a *stage* declares
and nothing about what the base image does with it: an `ENTRYPOINT` that re-escalates (`su`, `gosu`, a
setuid binary) is a green run over a container that still ends up root. It resolves no names — a `USER veyq`
is accepted as non-root because the only root spellings are `root` and `0`, and this guard has no `/etc/passwd`
to check a name against, so a base image that maps some *name* to uid 0 would slip through. It reads `EXPOSE`,
which is documentation and not a bind: a process that listens on 80 without declaring it is invisible here,
and one that declares 8080 and listens on 80 is green. And it says nothing about the run-time user a deployment
sets *over* the image (`docker run --user`, a pod `securityContext`) — those can only lower the privilege this
guard proves the image starts from, and a chart's own settings are `scripts/chart_meta_check.py`'s to assert.
The stage set is `runtime-*` by name, the same convention `validate_app_volume_ownership.py` and
`validate_release_identity.py` anchor on; a published stage named otherwise is out of reach, and that is a
limit of the convention rather than of this file.

**Second assertion — a bare build builds what ships.** Every `(dockerfile, target)` pair in the build job's matrix
of `.github/workflows/ci.yml` (read out of the workflow, never listed) names that file's **last** stage,
because a `docker build` with no `--target` builds the last stage. Bought on 2026-09-28 (inbox #191): the app
`Dockerfile` ended on `runtime-native` while the release publishes `runtime-jvm`, so a bare build built the
unshipped target — and that target did not even build (its `microdnf install curl` conflicted with the base's
`curl-minimal`), which is how a plain `docker build .` met a failure no released image had. That target has since
been removed (inbox #200); the assertion stays, because every Dockerfile here still has build stages after which
the published one must come. Limit: it proves the order, not that the target builds; and a target published from
some other workflow is out of its reach.

Buildless and diff-free, so it belongs in the trust-chain glob.
"""
from __future__ import annotations  # `str | None` below — the repo's guards run on Python 3.9

import pathlib
import re
import subprocess
import sys

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the other guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
BUILD_JOB = "build"
# Any `FROM`, named or not — the last one is the stage a build without `--target` produces.
ANY_FROM = re.compile(r"^\s*FROM\s+\S+(?:\s+--\S+)*(?:\s+AS\s+(\S+))?\s*$", re.IGNORECASE | re.MULTILINE)

# `FROM <image> AS <stage>` — the stage name is what ci.yml's build matrix targets.
STAGE = re.compile(r"^\s*FROM\s+\S+(?:\s+--\S+)*\s+AS\s+(\S+)\s*$", re.IGNORECASE | re.MULTILINE)
USER = re.compile(r"^\s*USER\s+(\S+)\s*$", re.IGNORECASE)
EXPOSE = re.compile(r"^\s*EXPOSE\s+(.+?)\s*$", re.IGNORECASE)
# A tracked file whose basename contains `dockerfile` in any case — `Dockerfile`, `src/admin-web/Dockerfile`, and a
# `something.Dockerfile` a future image might use. Derived from the index, so a third image enrols itself.
DOCKERFILE_NAME = re.compile(r"(^|/)[^/]*dockerfile[^/]*$", re.IGNORECASE)
UNPRIVILEGED_PORT = 1024

errors: list[str] = []


def tracked_dockerfiles() -> list[pathlib.Path]:
    out = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True)
    if out.returncode != 0:
        errors.append("`git ls-files` failed, so this guard cannot enumerate the Dockerfiles it should read")
        return []
    return [ROOT / line for line in out.stdout.splitlines() if DOCKERFILE_NAME.search(line)]


def runtime_stages(body: str) -> dict[str, str]:
    """Each `runtime-*` stage's body, keyed by stage name."""
    stages = [(m.start(), m.group(1)) for m in STAGE.finditer(body)]
    out: dict[str, str] = {}
    for index, (offset, name) in enumerate(stages):
        end = stages[index + 1][0] if index + 1 < len(stages) else len(body)
        if name.startswith("runtime-"):
            out[name] = body[offset:end]
    return out


def final_user(stage_body: str) -> str | None:
    """The LAST `USER` in the stage — the one the ENTRYPOINT actually runs as. A stage may switch to root for
    its own package install; what matters is where it ends."""
    found = [m.group(1) for line in stage_body.splitlines() for m in [USER.match(line)] if m]
    return found[-1] if found else None


def is_root(user: str) -> bool:
    # `USER root`, `USER 0`, `USER 0:0`, `USER root:root` — the user half decides, the group is irrelevant.
    name = user.split(":")[0]
    return name.lower() == "root" or name == "0"


def exposed_ports(stage_body: str) -> list[tuple[str, str]]:
    """Every `(port, raw-token)` a stage EXPOSEs — `EXPOSE 80`, `EXPOSE 8080/tcp`, `EXPOSE 80 443`."""
    out: list[tuple[str, str]] = []
    for line in stage_body.splitlines():
        m = EXPOSE.match(line)
        if not m:
            continue
        for token in m.group(1).split():
            out.append((token.split("/")[0], token))
    return out


checked = 0
for dockerfile in sorted(tracked_dockerfiles()):
    rel = dockerfile.relative_to(ROOT).as_posix()
    stages = runtime_stages(dockerfile.read_text())
    if not stages:
        errors.append(f"{rel}: declares no `runtime-*` stage — there is no published image to check")
        continue
    for stage, body in sorted(stages.items()):
        checked += 1
        user = final_user(body)
        if user is None:
            errors.append(
                f"{rel}: stage `{stage}` declares no USER, so it runs as whatever its base image ends on — "
                f"root for a stock server image. A `restricted` PodSecurity namespace refuses that pod outright; "
                f"end the stage on a non-root `USER`, and say so in the stage rather than trusting the base."
            )
        elif is_root(user):
            errors.append(
                f"{rel}: stage `{stage}` ends on `USER {user}` — a stage may switch to root for its own build "
                f"steps, but the LAST USER is the one the container runs as, and it must not be root."
            )
        low = [(port, raw) for port, raw in exposed_ports(body) if port.isdigit() and int(port) < UNPRIVILEGED_PORT]
        for port, raw in low:
            errors.append(
                f"{rel}: stage `{stage}` EXPOSEs `{raw}` — a port below {UNPRIVILEGED_PORT} is one a non-root "
                f"process cannot bind, so the container would start and then die on its listener. Bind an "
                f"unprivileged port and let the deployment map it."
            )

def published_targets() -> list[tuple[str, str]]:
    """Every `(dockerfile, target)` the build matrix publishes, from its `include` entries."""
    try:
        doc = yaml.safe_load(WORKFLOW.read_text()) or {}
    except (OSError, yaml.YAMLError) as e:
        errors.append(f"{WORKFLOW.relative_to(ROOT)} does not load ({e}) — no published target could be checked")
        return []
    matrix = ((doc.get("jobs") or {}).get(BUILD_JOB) or {}).get("strategy", {}).get("matrix", {})
    return [(str(i["dockerfile"]), str(i["target"])) for i in (matrix.get("include") or [])
            if isinstance(i, dict) and "dockerfile" in i and "target" in i]


targets = published_targets()
if not targets:
    errors.append(f"{WORKFLOW.relative_to(ROOT)}'s `{BUILD_JOB}` matrix names no (dockerfile, target) pair — "
                  f"re-anchor this guard; it checked no published target")
for rel, target in targets:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"{WORKFLOW.relative_to(ROOT)} publishes `{target}` from `{rel}`, which does not exist")
        continue
    froms = list(ANY_FROM.finditer(path.read_text()))
    last = froms[-1].group(1) if froms else None
    if last != target:
        errors.append(
            f"{rel}: the release publishes `{target}`, but the file's last stage is `{last}` — a bare "
            f"`docker build` builds the last stage, so it would build something the release never ships. "
            f"Move `{target}` to the end of the file."
        )

if checked == 0:
    # A guard that inspected nothing must not report success: no tracked Dockerfile, or a convention drift that
    # renamed every runtime stage, both land here and both are findings.
    errors.append("no `runtime-*` stage was found in any tracked Dockerfile — this guard checked nothing")

if errors:
    print("IMAGES RUN UNPRIVILEGED VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(f"images run unprivileged OK: {checked} runtime stage(s) end on a non-root USER and EXPOSE only ports >= {UNPRIVILEGED_PORT}; "
      f"{len(targets)} published target(s) are their file's last stage")
