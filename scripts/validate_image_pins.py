#!/usr/bin/env python3
"""Every third-party image this repository runs or builds from is pinned by digest, and by one digest (ADR 0057 §6).

**What it asserts.** It finds every image reference in the tracked tree by the KEY that carries one, never by a
list of files or names (§ *a guard derives its anchor set from the code*, changes/README.md):

  - in every tracked YAML file, every string under a key named `image` — compose services, chart values, any
    future manifest — except under a `matrix` key, where GitHub Actions keeps build variables, not images; a
    Helm template (a file under `<chart>/templates/` beside a tracked `Chart.yaml`) does not parse as YAML and is
    read line by line, and a value carrying `{{` is the template's, whose source is the values file. Any OTHER
    YAML file that does not parse is red, named: the line fallback is for templates, and for a broken workflow
    or compose file it would be a silent pass;
  - in every tracked `Dockerfile`/`Containerfile`, every `FROM` and every `COPY --from=`;
  - in every tracked Python file, every module-level `<NAME>IMAGE = "<ref>"` constant — how the backup tool names
    the helper containers it starts.

A reference is **external** unless it is a variable (`${VEYQ_IMAGE:?…}` — the operator's pin, which
`site/src/content/docs/run/verify-a-release.md` resolves to a verified digest), an earlier stage of the same
Dockerfile, `scratch`, or one of veyq's own published images (read out of `.github/workflows/checks.yml`'s
build matrix). Every external reference must be written `repo:tag@sha256:<64 hex>` — the tag so a person can
still read the version, the digest so what runs is what was pinned — and one `repo:tag` must carry one digest
everywhere it appears, so the dev stack, the release recipe, the chart and the backup tool cannot quietly run
different bits under the same name — and one repository one tag everywhere (2026-11-07), because Dependabot reaches
the compose files and the Dockerfiles but not the chart or the backup tool, so a tag bump it proposes would
otherwise leave those on the old version unremarked. The digest is the **multi-arch index** digest: a platform manifest's digest
would pin one architecture, and the publish builds amd64 and arm64. That is not checkable offline and is not
checked here (limit below).

**The defect that bought it.** Live when this guard was written (2026-09-25, cr-image-provenance-verifiable):
the signed release recipe ran `mariadb`, `garage` and `traefik` by tag, the chart did the same, the backup tool
started `rclone` and `mariadb` by tag, and both Dockerfiles built from tags — so a signature over the recipe
signed nothing about what actually ran (ADR 0057 §6). Its first run on that tree named all of them; nothing was
pinned by digest anywhere.

**Its limits — what a green run does NOT prove.** It reads text and contacts no registry, so it cannot tell an
index digest from a platform one, or a digest that exists from one that does not — the pins were resolved with
`docker buildx imagetools inspect` and checked to be indexes carrying amd64 and arm64 when they were written,
and a later bump owes the same check. It says nothing about what a pinned image installs at build time
(`apt-get`/`apk` inside a Dockerfile are not pinned by the `FROM` digest; the API fuzzing's image,
`tests/fuzz/Dockerfile`, pins its one `pip install` by wheel hash with `--require-hashes` against
`tests/fuzz/requirements.txt` — this guard reads its `FROM`, not that file), and nothing about build
inputs that are not images (`validate_build_input_pins.py`). An image named anywhere else — a `docker run` string built
at run time, a Markdown example, a shell script — is out of reach; the three keys above are where this
repository names the images it runs, and a fourth shape would be a site nobody asserted. That the tag and the
digest still agree (the tag may have moved since) is exactly what a pin gives up on purpose (ADR 0057
§ Consequences), and the updater's job, not this guard's.
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
WORKFLOW = ROOT / ".github/workflows/checks.yml"

# The flow surfaces and vendored trees — the same line the other guards draw.
SKIP_PREFIXES = ("changes/", "docs/", "site/src/content/")
PINNED = re.compile(r"^(?P<repo>[a-z0-9][a-z0-9._/-]*(?::[0-9]+)?(?:/[a-z0-9._-]+)*):(?P<tag>[A-Za-z0-9._-]+)@sha256:[0-9a-f]{64}$")
FROM = re.compile(r"^\s*FROM\s+(?:--\S+\s+)*(\S+)(?:\s+AS\s+(\S+))?\s*$", re.IGNORECASE)
COPY_FROM = re.compile(r"^\s*COPY\s+.*--from=(\S+)", re.IGNORECASE)
PY_CONST = re.compile(r"""^([A-Z][A-Z0-9_]*IMAGE)\s*=\s*["']([^"']+)["']""", re.MULTILINE)
YAML_LINE = re.compile(r"""^\s*(?:-\s+)?image:\s*["']?([^\s"'#]+)""")

errors: list[str] = []
sites: list[tuple[str, str, str]] = []  # (where, kind, reference)


def fail(message: str) -> None:
    errors.append(message)


def tracked() -> list[str]:
    out = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, text=True)
    if out.returncode != 0:
        fail(f"`git ls-files` failed, so no site could be found and this guard checked NOTHING: {out.stderr.strip()}")
        return []
    return [p for p in out.stdout.split("\0") if p and not p.startswith(SKIP_PREFIXES) and "node_modules/" not in p]


def own_images() -> set[str]:
    doc = yaml.safe_load(WORKFLOW.read_text()) if WORKFLOW.is_file() else {}
    matrix = (((doc or {}).get("jobs") or {}).get("build") or {}).get("strategy", {}).get("matrix", {})
    names = {str(i) for i in (matrix.get("image") or [])}
    if not names:
        fail(f"{WORKFLOW.relative_to(ROOT)} declares no build matrix `image` list — veyq's own images cannot be told "
             f"apart from third-party ones")
    return names


def yaml_images(node, path: str = ""):
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "matrix":
                continue
            if key == "image" and isinstance(value, str):
                yield f"{path}.image".lstrip("."), value
            else:
                yield from yaml_images(value, f"{path}.{key}")
    elif isinstance(node, list):
        for index, item in enumerate(node):
            yield from yaml_images(item, f"{path}[{index}]")


def helm_template(rel: str, charts: set[str]) -> bool:
    """A file under `<chart>/templates/`, where `<chart>/Chart.yaml` is tracked — derived, not listed."""
    return any(rel.startswith(f"{chart}/templates/") for chart in charts)


files = tracked()
charts = {rel.rsplit("/", 1)[0] for rel in files if rel.endswith("/Chart.yaml")}
for rel in files:
    name = rel.rsplit("/", 1)[-1]
    path = ROOT / rel
    if name.endswith((".yml", ".yaml")):
        text = path.read_text()
        try:
            docs = list(yaml.safe_load_all(text))
        except yaml.YAMLError as exc:
            if not helm_template(rel, charts):
                # The line fallback was written for Helm templates; for any other file it would be a silent pass
                # over a broken one (inbox #239: a workflow that did not load at all went unremarked here).
                fail(f"{rel} does not parse as YAML ({str(exc).splitlines()[0]}), and it is not a Helm template — "
                     f"the only kind read line by line — so no image in it can be checked. Fix the file.")
                continue
            for number, line in enumerate(text.splitlines(), 1):
                match = YAML_LINE.match(line)
                if match and "{{" not in line:
                    sites.append((f"{rel}:{number}", "yaml", match.group(1)))
            continue
        for doc in docs:
            for key, value in yaml_images(doc):
                sites.append((f"{rel} ({key})", "yaml", value))
    elif re.fullmatch(r"(?:.+\.)?(?:Dockerfile|Containerfile)(?:\..+)?", name):
        stages: set[str] = set()
        for number, line in enumerate(path.read_text().splitlines(), 1):
            match = FROM.match(line)
            if match:
                if match.group(1).lower() not in stages:
                    sites.append((f"{rel}:{number}", "dockerfile", match.group(1)))
                if match.group(2):
                    stages.add(match.group(2).lower())
                continue
            match = COPY_FROM.match(line)
            if match and match.group(1).lower() not in stages and not match.group(1).isdigit():
                sites.append((f"{rel}:{number}", "dockerfile", match.group(1)))
    elif name.endswith(".py"):
        for match in PY_CONST.finditer(path.read_text()):
            line = path.read_text().count("\n", 0, match.start()) + 1
            sites.append((f"{rel}:{line} ({match.group(1)})", "python", match.group(2)))

ours = own_images()
digest_of: dict[str, dict[str, list[str]]] = {}
external = 0
for where, kind, ref in sites:
    if ref.startswith("$") or ref == "scratch":
        continue  # the operator's pin, or no image at all
    # The image's own name: the last path segment, without its tag or digest (a registry port is in an earlier one).
    if ref.split("@", 1)[0].rsplit("/", 1)[-1].split(":", 1)[0] in ours:
        continue  # veyq's own images are pinned by the operator, against a verified digest
    external += 1
    match = PINNED.match(ref)
    if not match:
        fail(
            f"{where}: `{ref}` is not pinned as `repo:tag@sha256:<64 hex>`. A tag moves, and a signed recipe that "
            f"names one signs nothing about what runs (ADR 0057 §6). Resolve the tag's multi-arch INDEX digest "
            f"(`docker buildx imagetools inspect <ref>`) and write both."
        )
        continue
    key = f"{match.group('repo')}:{match.group('tag')}"
    digest_of.setdefault(key, {}).setdefault(ref.split("@", 1)[1], []).append(where)

# One repository, one tag. Since 2026-11-07 Dependabot reaches the compose files and the Dockerfiles but not the chart
# (its helm updater skips digest pins) nor the backup tool's constants, so a bump it proposes moves only some of a
# repository's sites. A digest-only move is caught above (one `repo:tag`, one digest); a TAG move would leave the chart
# on the old tag under a different name, which the digest rule cannot see. So every site of a repository names the same
# tag, and the pull request is red until the sites Dependabot does not reach follow.
tags_of: dict[str, dict[str, list[str]]] = {}
for key, digests in digest_of.items():
    repo, tag = key.rsplit(":", 1)
    tags_of.setdefault(repo, {}).setdefault(tag, []).extend(w for ws in digests.values() for w in ws)
for repo, tags in sorted(tags_of.items()):
    if len(tags) > 1:
        listing = "; ".join(f"`{t}` at {', '.join(w)}" for t, w in sorted(tags.items()))
        fail(
            f"`{repo}` is pinned under {len(tags)} different tags ({listing}). One image, one version everywhere: "
            f"a bump that reaches only the compose files (Dependabot reaches neither the chart nor the backup tool) "
            f"must be followed by hand at every other site in the same change."
        )

for key, digests in sorted(digest_of.items()):
    if len(digests) > 1:
        listing = "; ".join(f"{d[:19]}… at {', '.join(w)}" for d, w in sorted(digests.items()))
        fail(
            f"`{key}` is pinned to {len(digests)} different digests ({listing}). One name, one set of bits: the "
            f"dev stack, the release recipe, the chart and the backup tool would otherwise run different images "
            f"under the same tag, and a restore rehearsed on one proves nothing about the other."
        )

kinds = {kind for _, kind, _ in sites}
for kind in ("yaml", "dockerfile", "python"):
    if kind not in kinds:
        # A site kind that finds nothing and a site kind whose images are all pinned report the same green.
        fail(f"no image reference of kind `{kind}` was found anywhere — that half of this guard checked NOTHING")

if errors:
    print("IMAGE PIN VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(
    f"image pins OK: {external} third-party image reference(s) across {len({w.split(':')[0].split(' ')[0] for w, _, _ in sites})} "
    f"file(s) are each `repo:tag@sha256:…`, {len(tags_of)} repositories are each pinned under one tag, and "
    f"{len(digest_of)} distinct `repo:tag` carry one digest each"
)
