#!/usr/bin/env python3
"""Produce one veyq release as files, in the mirror layout `veyqctl` reads (ADR 0096).

    python3 scripts/release_assets.py <out> --app <ref> --admin <ref> [--unscheduled]

writes, for the release the tree names (`v<major>.<minor>`, counted the way `ci.yml`'s version step counts it):

    <out>/releases.json           every release in <out>: tag, date, prerelease flag, manifest — merged, never replaced
    <out>/<tag>/manifest.json     release, date, both images, unscheduled
    <out>/<tag>/<asset>           the recipe set, and the tools that operate it

The same three questions — which releases exist, what does one's manifest say, give me one asset — are answered
by a local directory, an HTTP mirror and the GitHub Releases adapter alike, so the stack lane and the self-test run
the resolver an operator runs. A release is immutable: a `<tag>` already in `<out>` is refused, not overwritten.

**The recipe set is derived, never listed.** It is the production compose file, the `.env` template beside it,
and every file the compose file reads relative to itself — the spellings Compose has for that: a service's
`volumes` (short syntax and long-form `type: bind`), `env_file` (a string, a list, `{path: …}`), `label_file`,
and the top-level `configs.*.file` / `secrets.*.file`. The derivation refuses what it cannot resolve without an
operator's environment or another file: a `${…}` in such a path, a `../`, a top-level `include:`, a service's
`extends`. And it refuses a path with a directory in it, because a GitHub Release asset is a flat name — the
recipe reads only files beside it. Listing the set is how the in-image recipe lost `garage.toml` (ADR 0096):
the compose file read it, and the two `COPY` lines that carried the recipe did not know.

`scripts/validate_release_identity.py` assertion 4 holds the producer to this derivation: it runs the carry
step into a scratch directory and requires every derived file there, byte-identical to the tree.

**The images are named by digest.** A reference with a registry path (`/`) must carry `@sha256:<64 hex>`; only a
local build's bare `name:tag` may go without one, which is what `veyqctl --unsigned-local` accepts and nothing
else does. `--unscheduled` marks a major release that was not scheduled (cr-release-notices reads it), and is
refused on a release that does not change the major: an earlier `v<major>.*` in the git tags or in `<out>`.

Its limit: an absolute host path (`/srv/…`, `~/…`) in the compose file is the host's, unchecked and not
carried. A file the compose file does not name (Compose's own implicit `.env`, an override the operator adds)
is not part of the recipe, by construction.
"""
from __future__ import annotations  # `str | None` below — the repo's scripts run on Python 3.9

import argparse
import datetime
import json
import pathlib
import re
import shutil
import subprocess
import sys

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
COMPOSE = ROOT / "infra/docker-compose.prod.yml"
ENV_TEMPLATE = ROOT / "infra/.env.example"
# The tools that operate a release, by asset name. veyqctl joins when it exists (cr-veyqctl phase 2).
TOOLS = {"instance_backup.py": ROOT / "scripts/instance_backup.py"}
MANIFEST = "manifest.json"
RELEASES = "releases.json"

DIGEST_REF = re.compile(r"^[^\s@]+@sha256:[0-9a-f]{64}$")
LOCAL_REF = re.compile(r"^[a-z0-9][a-z0-9._-]*(?::[A-Za-z0-9._-]+)?$")
TAG = re.compile(r"^v([0-9]+)\.([0-9]+)$")


class ReleaseError(Exception):
    """A refusal: the message is the whole explanation, printed as is."""


# ---- the recipe set ----------------------------------------------------------------------------------------

def _short_volume_source(spec: str) -> str | None:
    """The source half of a short-syntax volume, or None for an anonymous one. A `:` inside `${…}` is not a split."""
    depth = 0
    for i, ch in enumerate(spec):
        if spec.startswith("${", i):
            depth += 1
        elif ch == "}" and depth:
            depth -= 1
        elif ch == ":" and not depth:
            return spec[:i]
    return None


def recipe_reads(compose: pathlib.Path = COMPOSE) -> tuple[dict[str, list[str]], list[str]]:
    """Every file `compose` reads relative to itself, by asset name, with where it reads it; and the refusals.

    A path is RELATIVE when Compose resolves it against the file's directory: a short-syntax volume source that
    starts with `.`, and any non-absolute bind source, env file, label file, config or secret file. An absolute
    or `~` path is the host's (the limit); a short-syntax source that starts with neither is a named volume.
    """
    reads: dict[str, list[str]] = {}
    errors: list[str] = []
    name = compose.name
    try:
        doc = yaml.safe_load(compose.read_text()) or {}
    except (OSError, yaml.YAMLError) as e:
        return reads, [f"{name} cannot be read as YAML ({e}), so nothing it reads can be derived"]
    if not isinstance(doc, dict):
        return reads, [f"{name} is not a mapping, so nothing it reads can be derived"]

    if "include" in doc:
        errors.append(
            f"{name} has a top-level `include:` — the included files read paths relative to THEMSELVES and can "
            f"include again, so the set of files the recipe reads would no longer be this file's to derive. The "
            f"recipe is one file; inline what it includes."
        )

    def path(raw: object, where: str, *, short_volume: bool = False) -> None:
        if not isinstance(raw, str) or not raw:
            return
        if "${" in raw or re.search(r"\$[A-Za-z_]", raw):
            errors.append(
                f"{name} {where} reads `{raw}`, a path with a variable in it — which file the stack reads would "
                f"then be the operator's environment's to say, and a release cannot carry a file it cannot name"
            )
            return
        if raw.startswith(("/", "~")):
            return  # the host's: the limit
        if short_volume and not raw.startswith("."):
            return  # a named volume
        parts = pathlib.PurePosixPath(raw).parts
        if ".." in parts:
            errors.append(
                f"{name} {where} reads `{raw}`, outside the directory the recipe is installed into — a release "
                f"carries the files beside the compose file and nothing above it"
            )
            return
        rel = [p for p in parts if p != "."]
        if len(rel) != 1:
            errors.append(
                f"{name} {where} reads `{raw}`, a path with a directory in it — a release asset is a flat name "
                f"(a GitHub Release has no directories), so the recipe reads only files beside it"
            )
            return
        asset = rel[0]
        if not (compose.parent / asset).is_file():
            errors.append(
                f"{name} {where} reads `{raw}`, which is not a file beside it in the tree — the release would carry "
                f"a recipe that reads a file it does not have"
            )
            return
        reads.setdefault(asset, []).append(where)

    services = doc.get("services") or {}
    for svc, body in services.items() if isinstance(services, dict) else []:
        if not isinstance(body, dict):
            continue
        if "extends" in body:
            errors.append(
                f"{name} services.{svc} uses `extends` — it pulls a service in from another file, whose paths "
                f"resolve against THAT file; the recipe is one file, so write the service out"
            )
        for i, vol in enumerate(body.get("volumes") or []):
            where = f"services.{svc}.volumes[{i}]"
            if isinstance(vol, str):
                path(_short_volume_source(vol), where, short_volume=True)
            elif isinstance(vol, dict) and vol.get("type") == "bind":
                path(vol.get("source"), f"{where} (long-form bind)")
        for key in ("env_file", "label_file"):
            value = body.get(key)
            for i, item in enumerate(value if isinstance(value, list) else [value] if value else []):
                raw = item.get("path") if isinstance(item, dict) else item
                path(raw, f"services.{svc}.{key}" + (f"[{i}]" if isinstance(value, list) else ""))
    for section in ("configs", "secrets"):
        block = doc.get(section) or {}
        for key, body in block.items() if isinstance(block, dict) else []:
            if isinstance(body, dict) and "file" in body:
                path(body["file"], f"{section}.{key}.file")

    fixed = {COMPOSE.name, ENV_TEMPLATE.name, MANIFEST, *TOOLS}
    for asset in sorted(set(reads) & fixed):
        if asset not in (COMPOSE.name, ENV_TEMPLATE.name):
            errors.append(f"{name} reads a file named `{asset}`, which is also the name of a release asset of its own")
    return reads, errors


def recipe_set() -> dict[str, pathlib.Path]:
    """The recipe, by asset name: the compose file, the `.env` template, and every file the compose file reads."""
    reads, errors = recipe_reads()
    if errors:
        raise ReleaseError("the recipe set cannot be derived:\n  - " + "\n  - ".join(errors))
    files = {COMPOSE.name: COMPOSE, ENV_TEMPLATE.name: ENV_TEMPLATE}
    for asset in sorted(reads):
        files[asset] = COMPOSE.parent / asset
    for asset, source in files.items():
        if not source.is_file():
            raise ReleaseError(f"`{source.relative_to(ROOT)}` does not exist, so no release can carry `{asset}`")
    return files


def write_assets(dest: pathlib.Path) -> list[str]:
    """Copy the recipe set and the tools into `dest` (which must exist), and return the asset names written."""
    files = dict(recipe_set())
    for asset, source in TOOLS.items():
        if not source.is_file():
            raise ReleaseError(f"`{source.relative_to(ROOT)}` does not exist, so no release can carry `{asset}`")
        files[asset] = source
    for asset, source in files.items():
        shutil.copyfile(source, dest / asset)
    return sorted(files)


# ---- the release the tree names ----------------------------------------------------------------------------

def _git(root: pathlib.Path, *args: str) -> str:
    proc = subprocess.run(["git", *args], cwd=root, capture_output=True, text=True)
    if proc.returncode != 0:
        raise ReleaseError(f"git {' '.join(args)} failed: {proc.stderr.strip()}")
    return proc.stdout.strip()


def tree_release(root: pathlib.Path = ROOT) -> str:
    """`<major>.<minor>` exactly as `ci.yml`'s `veyq-version` step counts it (ADR 0057 §1).

    That step is the authority; validate_release_identity.py runs both against one throwaway history and holds
    them to the same answer, so this copy cannot drift from it unnoticed.
    """
    major = (root / "VERSION").read_text().strip() if (root / "VERSION").is_file() else ""
    if not re.fullmatch(r"[0-9]+", major):
        raise ReleaseError(f"VERSION must contain the major alone (the minor is counted from the history), got: {major!r}")
    if _git(root, "rev-parse", "--is-shallow-repository") != "false":
        raise ReleaseError("shallow checkout: the minor counts commits since VERSION last changed and needs the full history")
    base = _git(root, "log", "-1", "--format=%H", "--", "VERSION")
    if not base:
        raise ReleaseError("no commit touches VERSION, so there is no point to count the minor from")
    return f"{major}.{_git(root, 'rev-list', '--count', f'{base}..HEAD')}"


def check_image(role: str, ref: str) -> None:
    if DIGEST_REF.match(ref) or LOCAL_REF.match(ref):
        return
    raise ReleaseError(
        f"--{role} `{ref}` names a registry image without a digest. A release is deployed by the digests its manifest "
        f"names (ADR 0057); only a local build's bare `name:tag` goes without one, for `veyqctl --unsigned-local`."
    )


def earlier_releases_of_major(out: pathlib.Path, major: int, minor: int) -> list[str]:
    tags = _git(ROOT, "tag", "--list", f"v{major}.*").split()
    try:
        listed = json.loads((out / RELEASES).read_text()).get("releases", [])
    except FileNotFoundError:
        listed = []
    tags += [str(r.get("tag")) for r in listed if isinstance(r, dict)]
    earlier = set()
    for tag in tags:
        m = TAG.match(tag)
        if m and int(m.group(1)) == major and int(m.group(2)) < minor:
            earlier.add(tag)
    return sorted(earlier)


def produce(out: pathlib.Path, app: str, admin: str, unscheduled: bool) -> pathlib.Path:
    check_image("app", app)
    check_image("admin", admin)
    release = tree_release()
    tag = f"v{release}"
    major, minor = (int(x) for x in release.split("."))
    out.mkdir(parents=True, exist_ok=True)
    if unscheduled and (earlier := earlier_releases_of_major(out, major, minor)):
        raise ReleaseError(
            f"--unscheduled marks a release that changes the major, and {tag} does not: {', '.join(earlier)} "
            f"already released major {major}"
        )
    dest = out / tag
    if dest.exists():
        raise ReleaseError(f"{dest} already exists — a release is immutable; remove it to produce {tag} again")
    date = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")
    dest.mkdir()
    try:
        write_assets(dest)
        manifest = {"release": release, "date": date, "images": {"app": app, "admin": admin}, "unscheduled": unscheduled}
        (dest / MANIFEST).write_text(json.dumps(manifest, indent=2) + "\n")
    except BaseException:
        shutil.rmtree(dest, ignore_errors=True)
        raise
    index = out / RELEASES
    releases = json.loads(index.read_text()).get("releases", []) if index.is_file() else []
    releases = [r for r in releases if r.get("tag") != tag]
    releases.append({"tag": tag, "date": date, "prerelease": False, "manifest": f"{tag}/{MANIFEST}"})
    releases.sort(key=lambda r: tuple(int(x) for x in TAG.match(r["tag"]).groups()) if TAG.match(r["tag"]) else (-1, -1))
    index.write_text(json.dumps({"releases": releases}, indent=2) + "\n")
    return dest


def main() -> int:
    parser = argparse.ArgumentParser(description="Produce one veyq release as files, in the mirror layout.")
    parser.add_argument("out", type=pathlib.Path, help="the mirror directory; the release lands in <out>/<tag>/")
    parser.add_argument("--app", required=True, help="the app image, by digest (or a local build's name:tag)")
    parser.add_argument("--admin", required=True, help="the admin image, by digest (or a local build's name:tag)")
    parser.add_argument("--unscheduled", action="store_true", help="mark an unscheduled major release")
    args = parser.parse_args()
    try:
        dest = produce(args.out, args.app, args.admin, args.unscheduled)
    except ReleaseError as e:
        print(f"release_assets: {e}", file=sys.stderr)
        return 1
    for asset in sorted(p.name for p in dest.iterdir()):
        print(dest / asset)
    print(f"{dest.name}: {len(list(dest.iterdir()))} asset(s); {args.out / RELEASES} lists it")
    return 0


if __name__ == "__main__":
    sys.exit(main())
