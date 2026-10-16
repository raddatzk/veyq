#!/usr/bin/env python3
"""veyqctl — install, run and verify a veyq instance on Docker Compose from a published release (ADR 0096).

    veyqctl [--home DIR] [--source SRC] <command> …

    install [--set KEY=VALUE …] [--unsigned-local]   fetch the release VEYQ_RELEASE selects, verify it, write .env, up
    start | stop | status                            the stack, addressed by the project the recipe names
    logs [-f] [--tail N] [SERVICE …]                 its logs
    verify [--release SEL] [--unsigned-local]        the signatures of the selected release, and nothing else
    check [--release SEL]                            which release `upgrade` would take, and a newer major beyond it

One file, Python 3.9 and the standard library only, because an operator fetches it with `curl` onto a host that
already needs `python3` for backups: no `pip`, no virtualenv (ADR 0096 decision 2). It manages the instance's
lifecycle on the HOST and holds no Admin API credential — administration stays in admin-web (decision 1).

**An instance lives in `--home`** (default `/opt/veyq`, decision 6):

    .env                   0600, the instance's values, written from the running release's `.env.example`
    compose.override.yml   optional: the operator's own Compose overlay, laid over the recipe on every command
    state.json             the running release, the Compose project it runs as, the source, how it was verified
    releases/<release>/    that release's assets, each checked against the sha256 its manifest names
    backups/               the captures an upgrade or `backup` takes

Every Compose command is `docker compose -p <project> -f releases/<release>/docker-compose.prod.yml
[-f compose.override.yml] --env-file .env`: the override file is Compose's own extension point, so an operator's
additions (a port, a network, a CA) survive an upgrade instead of being edited into a recipe the next release
replaces. `.env` keeps every key the template does not name, for the same reason. The project is what Compose names
at `install` — the recipe's `name:`, or a `COMPOSE_PROJECT_NAME` in `.env` — recorded in `state.json` and passed by
name from then on; a `COMPOSE_*` variable in the operator's shell is dropped from every command veyqctl runs, and
every command runs in the home, so neither a shell variable nor a compose file in the current directory can point
it at another stack.

**A release is the source's three answers** — which releases exist, what one's manifest says, give me one asset — and
three sources answer them the same way: the GitHub Releases of `raddatzk/veyq` (the default), an HTTP mirror and a
local directory, both in the mirror layout `scripts/release_assets.py` produces (`releases.json`, `<tag>/<asset>`).
Trusting a mirror is the operator's decision (decision 7). Prereleases are never installed, and an asset name with a
directory in it (`../`) is refused: a release's assets are flat names, by construction of the producer.

**`VEYQ_RELEASE` in `.env` selects the release** (decision 4): `<major>.<minor>` pins one, `<major>` takes the newest
of that major, `latest` the newest of all. `install` takes it from `--set VEYQ_RELEASE=…`, else `latest`, and writes
it into `.env`.

**Nothing unverified is pulled or started** (ADR 0057). The manifest names both images by `tag@digest`; each is
checked with the host's `cosign verify` against the one identity that publishes veyq — GitHub's OIDC issuer and
`ci.yml` of `raddatzk/veyq` on `main` or a `release/<major>.x` branch, the identity the Run area's *Verify a release*
page documents, and `scripts/validate_release_identity.py` assertion 7 holds both to what signs — before a single
image is pulled. `--unsigned-local` is the one way past it, for a release produced from a local `docker build`: it is
refused unless BOTH image references are a bare `name:tag` with no registry host and no digest, refused for an image
that is not on this host (a bare name would otherwise be pulled from Docker Hub's `library/`), and every line the run
prints says that nothing was verified.

**`.env` from the template.** `install` copies the release's `.env.example` line by line: the four secrets it knows
are generated, the two image lines come from the manifest, and every other value the template assigns is the
operator's — given with `--set KEY=VALUE`, or asked for at a terminal, the template's value shown as the example;
without a terminal a missing one is refused, named. A `--set` key the template does not assign is kept below it.
Written `0600`, in a home created `0700`.

**What proves it** — it is a host tool, not a coverage surface (ADR 0040; the use-case
`uc.admin.install-and-upgrade-instance` stays `planned` for that reason): `scripts/veyqctl_selftest.py`, without
Docker, holds the resolver, the manifest parse, the template merge and every refusal; the live runs of cr-veyqctl
installed, stopped and started an instance from a mirror directory with images built from the tree.

Its limits: it drives Compose only (Kubernetes upgrades with `helm upgrade`, decision 8); it cannot verify a mirror's
`releases.json` or a manifest beyond its shape and the assets' sha256 — the images' signatures are what bind a
release to veyq's publish; and it does not check that the Docker it talks to is the host's own.
"""
from __future__ import annotations  # `str | None` below — the repo's scripts run on Python 3.9

import argparse
import hashlib
import json
import os
import re
import secrets
import shlex
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Callable, NamedTuple

UPSTREAM = "raddatzk/veyq"
DEFAULT_HOME = "/opt/veyq"
GITHUB_API = f"https://api.github.com/repos/{UPSTREAM}/releases"
GITHUB_DOWNLOAD = f"https://github.com/{UPSTREAM}/releases/download"

# The identity that publishes veyq (ADR 0057 §3): the same two values the Run area's `verify-a-release.md` passes to
# `cosign verify`, held to what signs by `scripts/validate_release_identity.py` assertion 7, which reads them here.
COSIGN_OIDC_ISSUER = "https://token.actions.githubusercontent.com"
COSIGN_IDENTITY_REGEXP = r"^https://github\.com/raddatzk/veyq/\.github/workflows/ci\.yml@refs/heads/(main|release/[0-9]+\.x)$"

COMPOSE_ASSET = "docker-compose.prod.yml"
TEMPLATE_ASSET = ".env.example"
BACKUP_ASSET = "instance_backup.py"
SELF_ASSET = "veyqctl"
MANIFEST_ASSET = "manifest.json"
RELEASES_INDEX = "releases.json"
# Every release carries these; the manifest lists them with their sha256, and more (the recipe's other files).
REQUIRED_ASSETS = (COMPOSE_ASSET, TEMPLATE_ASSET, BACKUP_ASSET, SELF_ASSET)

ENV_FILE = ".env"
OVERRIDE_FILE = "compose.override.yml"
STATE_FILE = "state.json"
RELEASES_DIR = "releases"
BACKUPS_DIR = "backups"

WAIT_SECS = int(os.environ.get("VEYQ_WAIT_SECS", "600"))

TAG = re.compile(r"^v([0-9]+)\.([0-9]+)$")
SELECTOR = re.compile(r"^(?:latest|[0-9]+|[0-9]+\.[0-9]+)$")
# A release asset is a flat file name: no directory, no `..`, nothing a path could be built from.
ASSET_NAME = re.compile(r"^(?!\.\.?$)[A-Za-z0-9._-]+$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
# A local build's reference, exactly what `release_assets.py` accepts without a digest: no registry, no path.
LOCAL_REF = re.compile(r"^[a-z0-9][a-z0-9._-]*(?::[A-Za-z0-9._-]+)?$")
# A published reference: `<registry>/<path>[:<tag>]@sha256:<digest>`.
DIGEST_REF = re.compile(
    r"^(?P<name>[a-z0-9][a-z0-9._-]*(?::[0-9]+)?(?:/[a-z0-9][a-z0-9._-]*)+)(?::(?P<tag>[A-Za-z0-9._-]+))?"
    r"@(?P<digest>sha256:[0-9a-f]{64})$"
)
ASSIGNMENT = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)=(.*)$")
# `${NAME:?…}` / `${NAME?…}` in a compose file: a value the stack refuses to start without. `$${` is Compose's escape.
REQUIRED_VARIABLE = re.compile(r"(?<!\$)\$\{([A-Za-z_][A-Za-z0-9_]*):?\?")

# What `install` generates; the template names how (`openssl rand -hex 32` for the RPC secret, base64 for the rest).
SECRETS: dict[str, Callable[[], str]] = {
    "MARIADB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "MARIADB_ROOT_PASSWORD": lambda: secrets.token_urlsafe(24),
    "GARAGE_RPC_SECRET": lambda: secrets.token_hex(32),
    "GARAGE_ADMIN_TOKEN": lambda: secrets.token_urlsafe(24),
}
# What the release writes: the two image lines, from its manifest. Never the operator's to set.
MANAGED = {"VEYQ_IMAGE": "app", "VEYQ_ADMIN_IMAGE": "admin"}
RELEASE_KEY = "VEYQ_RELEASE"
KEPT_HEADER = "# ---- kept by veyqctl: values this release's template does not assign ----"


class Refused(Exception):
    """A refusal: the message is the whole explanation, printed as is."""


# ---- output ------------------------------------------------------------------------------------------------

_unverified = False


def say(message: str) -> None:
    """Every line veyqctl prints. Under `--unsigned-local` each one says that nothing was verified."""
    prefix = "veyqctl [--unsigned-local: NOTHING VERIFIED]" if _unverified else "veyqctl"
    for line in message.splitlines() or [""]:
        print(f"{prefix}: {line}", flush=True)


def set_unverified(on: bool) -> None:
    global _unverified
    _unverified = on


# ---- the host: every process veyqctl starts --------------------------------------------------------------------

def clean_env(extra: dict[str, str] | None = None) -> dict[str, str]:
    """This process's environment without `COMPOSE_*`, plus `extra`.

    A `COMPOSE_PROJECT_NAME`, `COMPOSE_FILE` or `COMPOSE_PROFILES` left in the operator's shell would change which
    stack a command addresses or what the recipe means; veyqctl names the project and the files itself.
    """
    env = {k: v for k, v in os.environ.items() if not k.startswith("COMPOSE_")}
    env.update(extra or {})
    return env


class Host:
    """Docker, cosign and the terminal, behind one seam the self-test replaces. Secrets never reach an argv here.

    `workdir` is where every command runs — the instance's home once it exists — so a compose file that happens to
    sit in the operator's current directory is never picked up by a command that names the project alone.
    """

    workdir: Path | None = None

    def run(self, cmd: list[str], *, check: bool = True, stream: bool = False,
            env: dict[str, str] | None = None, stdin: bytes | None = None) -> subprocess.CompletedProcess:
        say("+ " + " ".join(shlex.quote(c) for c in cmd))
        cwd = self.workdir if self.workdir and self.workdir.is_dir() else None
        try:
            if stdin is not None:  # bytes in (a key), text out
                raw = subprocess.run(cmd, input=stdin, env=clean_env(env), cwd=cwd, capture_output=True)
                result = subprocess.CompletedProcess(cmd, raw.returncode, raw.stdout.decode("utf-8", "replace"),
                                                     raw.stderr.decode("utf-8", "replace"))
            else:
                result = subprocess.run(cmd, text=True, env=clean_env(env), cwd=cwd, capture_output=not stream)
        except FileNotFoundError:
            raise Refused(f"`{cmd[0]}` is not on PATH") from None
        if check and result.returncode != 0:
            tail = (result.stderr or "").strip()[-1500:] if not stream else ""
            raise Refused(f"`{' '.join(cmd[:4])} …` exited {result.returncode}" + (f": {tail}" if tail else ""))
        return result

    def which(self, name: str) -> str | None:
        return shutil.which(name)

    def ask(self, key: str, example: str) -> str | None:
        if not sys.stdin.isatty():
            return None
        shown = f" (e.g. {example})" if example else ""
        return input(f"veyqctl: {key}{shown}: ").strip() or None


# ---- sources: which releases exist, a manifest, an asset -------------------------------------------------------

class Listed(NamedTuple):
    tag: str
    major: int
    minor: int
    prerelease: bool

    @property
    def release(self) -> str:
        return f"{self.major}.{self.minor}"


def check_asset_name(name: str) -> str:
    if not isinstance(name, str) or not ASSET_NAME.match(name):
        raise Refused(f"asset name {name!r} is not a flat file name — a release's assets are files beside each other, "
                      f"never a path (`../`, a directory) into the host")
    return name


def _listed(tag: object, prerelease: object) -> Listed | None:
    m = TAG.match(tag) if isinstance(tag, str) else None
    if not m:
        return None  # not a release of this shape: nothing veyqctl installs
    return Listed(tag, int(m.group(1)), int(m.group(2)), bool(prerelease))


class Source:
    name = "source"

    def releases(self) -> list[Listed]:
        raise NotImplementedError

    def fetch(self, tag: str, asset: str) -> bytes:
        raise NotImplementedError

    def describe(self) -> str:
        return self.name


class DirSource(Source):
    """A local directory in the mirror layout."""

    def __init__(self, root: Path) -> None:
        self.root = root.resolve()
        self.name = str(self.root)

    def releases(self) -> list[Listed]:
        index = self.root / RELEASES_INDEX
        try:
            doc = json.loads(index.read_text())
        except (OSError, ValueError) as e:
            raise Refused(f"{index} cannot be read as the mirror's release index: {e}") from None
        return _parse_index(doc, str(index))

    def fetch(self, tag: str, asset: str) -> bytes:
        check_asset_name(asset)
        if not TAG.match(tag):
            raise Refused(f"{tag!r} is not a release tag")
        path = self.root / tag / asset
        try:
            return path.read_bytes()
        except OSError as e:
            raise Refused(f"{path} cannot be read: {e}") from None


def _http_get(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "veyqctl", "Accept": "*/*"})
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return response.read()
    except (urllib.error.URLError, OSError) as e:
        raise Refused(f"GET {url} failed: {e}") from None


class HttpSource(Source):
    """An HTTP(S) mirror in the mirror layout."""

    def __init__(self, base: str) -> None:
        self.base = base.rstrip("/")
        self.name = self.base

    def releases(self) -> list[Listed]:
        url = f"{self.base}/{RELEASES_INDEX}"
        try:
            doc = json.loads(_http_get(url))
        except ValueError as e:
            raise Refused(f"{url} is not the mirror's release index: {e}") from None
        return _parse_index(doc, url)

    def fetch(self, tag: str, asset: str) -> bytes:
        check_asset_name(asset)
        if not TAG.match(tag):
            raise Refused(f"{tag!r} is not a release tag")
        return _http_get(f"{self.base}/{tag}/{asset}")


class GitHubSource(Source):
    """The GitHub Releases of the upstream: the default, never a requirement (ADR 0096 decision 7)."""

    name = f"github.com/{UPSTREAM} releases"

    def releases(self) -> list[Listed]:
        listed: list[Listed] = []
        for page in range(1, 11):
            try:
                doc = json.loads(_http_get(f"{GITHUB_API}?per_page=100&page={page}"))
            except ValueError as e:
                raise Refused(f"the GitHub releases listing is not JSON: {e}") from None
            if not isinstance(doc, list) or not doc:
                break
            for item in doc:
                if isinstance(item, dict) and not item.get("draft"):
                    entry = _listed(item.get("tag_name"), item.get("prerelease"))
                    if entry:
                        listed.append(entry)
        return listed

    def fetch(self, tag: str, asset: str) -> bytes:
        check_asset_name(asset)
        if not TAG.match(tag):
            raise Refused(f"{tag!r} is not a release tag")
        return _http_get(f"{GITHUB_DOWNLOAD}/{tag}/{asset}")


def _parse_index(doc: object, where: str) -> list[Listed]:
    entries = doc.get("releases") if isinstance(doc, dict) else None
    if not isinstance(entries, list):
        raise Refused(f"{where} has no `releases` list")
    listed = []
    for item in entries:
        if isinstance(item, dict):
            entry = _listed(item.get("tag"), item.get("prerelease"))
            if entry:
                listed.append(entry)
    return listed


def open_source(spec: str | None) -> Source:
    if not spec:
        return GitHubSource()
    if spec.startswith(("https://", "http://")):
        return HttpSource(spec)
    return DirSource(Path(spec))


# ---- which release ---------------------------------------------------------------------------------------------

def check_selector(selector: str) -> str:
    if not SELECTOR.match(selector or ""):
        raise Refused(f"{RELEASE_KEY}={selector!r} selects nothing: it is `<major>.<minor>` (pinned), `<major>` (the "
                      f"newest of that major) or `latest` (the newest, crossing majors)")
    return selector


def select(listed: list[Listed], selector: str, where: str) -> Listed:
    """The release `selector` takes from `listed`. Prereleases never; a pin to one is refused, named."""
    check_selector(selector)
    if "." in selector:
        major, minor = (int(x) for x in selector.split("."))
        exact = [r for r in listed if (r.major, r.minor) == (major, minor)]
        if not exact:
            raise Refused(f"release {selector} is not in {where}")
        if exact[0].prerelease:
            raise Refused(f"{exact[0].tag} is a prerelease, and veyqctl never installs one")
        return exact[0]
    candidates = [r for r in listed if not r.prerelease and (selector == "latest" or r.major == int(selector))]
    if not candidates:
        raise Refused(f"{RELEASE_KEY}={selector} selects no release in {where}")
    return max(candidates, key=lambda r: (r.major, r.minor))


def newer_major(listed: list[Listed], chosen: Listed) -> Listed | None:
    beyond = [r for r in listed if not r.prerelease and r.major > chosen.major]
    return max(beyond, key=lambda r: (r.major, r.minor)) if beyond else None


# ---- the manifest ----------------------------------------------------------------------------------------------

class Manifest(NamedTuple):
    release: str
    date: str
    app: str
    admin: str
    unscheduled: bool
    assets: dict  # asset name -> sha256 hex
    raw: bytes = b""  # the manifest as the source served it, kept verbatim beside the release


def parse_manifest(raw: bytes, tag: str) -> Manifest:
    try:
        doc = json.loads(raw)
    except ValueError as e:
        raise Refused(f"the manifest of {tag} is not JSON: {e}") from None
    if not isinstance(doc, dict):
        raise Refused(f"the manifest of {tag} is not an object")
    release = doc.get("release")
    if release != tag[1:]:
        raise Refused(f"the manifest of {tag} names release {release!r} — a manifest names its own release")
    images = doc.get("images")
    if not isinstance(images, dict) or not all(isinstance(images.get(k), str) and images.get(k) for k in ("app", "admin")):
        raise Refused(f"the manifest of {tag} does not name both images (`images.app`, `images.admin`)")
    unscheduled = doc.get("unscheduled")
    if not isinstance(unscheduled, bool):
        raise Refused(f"the manifest of {tag} has no boolean `unscheduled`")
    assets = doc.get("assets")
    if not isinstance(assets, dict) or not assets:
        raise Refused(f"the manifest of {tag} lists no `assets` — veyqctl takes a release's files from that list")
    for name, digest in assets.items():
        check_asset_name(name)
        if name == MANIFEST_ASSET:
            raise Refused(f"the manifest of {tag} lists itself as an asset")
        if not isinstance(digest, str) or not SHA256.match(digest):
            raise Refused(f"the manifest of {tag} gives asset {name} no sha256")
    missing = [a for a in REQUIRED_ASSETS if a not in assets]
    if missing:
        raise Refused(f"the manifest of {tag} lacks {', '.join(missing)}, which every release carries")
    return Manifest(release, str(doc.get("date") or ""), images["app"], images["admin"], unscheduled, dict(assets),
                    bytes(raw))


def fetch_manifest(source: Source, chosen: Listed) -> Manifest:
    return parse_manifest(source.fetch(chosen.tag, MANIFEST_ASSET), chosen.tag)


# ---- verification ---------------------------------------------------------------------------------------------

def cosign_target(role: str, ref: str) -> str:
    """`<name>@sha256:<digest>` for a published reference; refused for anything that is not one."""
    m = DIGEST_REF.match(ref)
    if m:
        return f"{m.group('name')}@{m.group('digest')}"
    if LOCAL_REF.match(ref):
        raise Refused(f"the {role} image `{ref}` is a local build with no digest, so there is no signature to check. "
                      f"Pass --unsigned-local to run it knowingly unverified.")
    raise Refused(f"the {role} image `{ref}` is not a registry reference pinned by digest "
                  f"(`<registry>/<path>:<tag>@sha256:<digest>`), so it cannot be verified (ADR 0057)")


def verify_images(host: Host, manifest: Manifest, unsigned_local: bool) -> None:
    """Both images, before anything is pulled: cosign against the identity that publishes veyq, or `--unsigned-local`."""
    refs = {"app": manifest.app, "admin": manifest.admin}
    if unsigned_local:
        foreign = [f"{role} `{ref}`" for role, ref in refs.items() if not LOCAL_REF.match(ref)]
        if foreign:
            raise Refused(
                f"--unsigned-local is for a release produced from a local `docker build` only: both image references "
                f"must be a bare `name:tag`, with no registry host and no digest. This release names "
                f"{' and '.join(foreign)} — run it without the flag, and it is verified."
            )
        for role, ref in refs.items():
            if host.run(["docker", "image", "inspect", "--format", "{{.Id}}", ref], check=False).returncode != 0:
                raise Refused(f"the {role} image `{ref}` is not on this host. A local reference is never pulled — "
                              f"a bare name would come from Docker Hub's `library/` — so build it first")
            say(f"{role} image {ref}: a local build, NOT VERIFIED")
        return
    targets = {role: cosign_target(role, ref) for role, ref in refs.items()}
    cosign = host.which("cosign")
    if not cosign:
        raise Refused("cosign is not on PATH, and nothing is pulled or started unverified "
                      "(https://docs.sigstore.dev/cosign/system_config/installation/)")
    for role, target in targets.items():
        result = host.run([cosign, "verify", target, "--certificate-oidc-issuer", COSIGN_OIDC_ISSUER,
                           "--certificate-identity-regexp", COSIGN_IDENTITY_REGEXP], check=False)
        if result.returncode != 0:
            raise Refused(
                f"the {role} image {target} does not verify against the identity that publishes veyq "
                f"({COSIGN_IDENTITY_REGEXP}): {(result.stderr or '').strip()[-800:]}\n"
                f"Nothing was pulled, written or started."
            )
        say(f"{role} image {target}: signature verified")


# ---- a release on disk ----------------------------------------------------------------------------------------

def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def release_dir(home: Path, release: str) -> Path:
    return home / RELEASES_DIR / release


def fetch_release(source: Source, chosen: Listed, manifest: Manifest, home: Path) -> Path:
    """The release's assets in `releases/<release>/`, each matching its manifest's sha256.

    A directory already there (an earlier attempt) is checked, never overwritten: a release is immutable, so a
    file that differs is a finding, not something to repair silently.
    """
    dest = release_dir(home, manifest.release)
    if dest.is_dir():
        for name, want in manifest.assets.items():
            path = dest / name
            if not path.is_file() or sha256(path.read_bytes()) != want:
                raise Refused(f"{path} is not the {name} release {manifest.release} names — a release does not "
                              f"change; move {dest} aside and run again")
        say(f"release {manifest.release}: already in {dest}, every asset matches its manifest")
        return dest
    staging = dest.parent / f".{manifest.release}.partial"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    try:
        for name, want in sorted(manifest.assets.items()):
            data = source.fetch(chosen.tag, name)
            if sha256(data) != want:
                raise Refused(f"{name} of {chosen.tag} from {source.describe()} has sha256 {sha256(data)}, its "
                              f"manifest says {want}")
            (staging / name).write_bytes(data)
        (staging / MANIFEST_ASSET).write_bytes(manifest.raw)
        os.replace(staging, dest)
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    say(f"release {manifest.release}: {len(manifest.assets)} asset(s) in {dest}, each matching its manifest")
    return dest


def read_release_manifest(home: Path, release: str) -> Manifest:
    """The manifest of a release on disk: the source's own bytes, kept verbatim when it was fetched."""
    path = release_dir(home, release) / MANIFEST_ASSET
    try:
        return parse_manifest(path.read_bytes(), f"v{release}")
    except OSError as e:
        raise Refused(f"{path} cannot be read: {e}") from None


# ---- .env ------------------------------------------------------------------------------------------------------

def env_values(text: str) -> dict[str, str]:
    """`KEY=value` lines, in order; a later one wins, as Compose reads it."""
    values: dict[str, str] = {}
    for line in text.splitlines():
        m = ASSIGNMENT.match(line)
        if m:
            values[m.group(1)] = m.group(2)
    return values


def unquoted(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        return value[1:-1]
    return value


def template_assignments(template: str) -> list[tuple[str, str]]:
    return [(m.group(1), m.group(2)) for m in map(ASSIGNMENT.match, template.splitlines()) if m]


def yaml_without_comments(text: str) -> str:
    """The YAML with its comments dropped: a `#` at a line's start or after whitespace, outside quotes.

    Compose interpolates values, never comments — and the recipe's own comments spell `${VAR:?…}` to explain it.
    """
    lines = []
    for line in text.splitlines():
        quote = ""
        cut = len(line)
        for i, ch in enumerate(line):
            if quote:
                if ch == quote:
                    quote = ""
            elif ch in "\"'":
                quote = ch
            elif ch == "#" and (i == 0 or line[i - 1] in " \t"):
                cut = i
                break
        lines.append(line[:cut])
    return "\n".join(lines)


def required_names(*compose_texts: str) -> list[str]:
    """Every variable a compose file refuses to start without (`${NAME:?…}`, `${NAME?…}`), in order of appearance."""
    seen: dict[str, None] = {}
    for text in compose_texts:
        for m in REQUIRED_VARIABLE.finditer(yaml_without_comments(text)):
            seen.setdefault(m.group(1), None)
    return list(seen)


def render_env(template: str, values: dict[str, str]) -> str:
    """The template, line by line, with every value filled in; every value it does not assign kept below it."""
    assigned = {k for k, _ in template_assignments(template)}
    lines = []
    for line in template.splitlines():
        m = ASSIGNMENT.match(line)
        lines.append(f"{m.group(1)}={values.get(m.group(1), m.group(2))}" if m else line)
    kept = [k for k in values if k not in assigned]
    if kept:
        lines += ["", KEPT_HEADER] + [f"{k}={values[k]}" for k in kept]
    return "\n".join(lines) + "\n"


def install_values(template: str, manifest: Manifest, given: dict[str, str],
                   ask: Callable[[str, str], str | None]) -> dict[str, str]:
    """The first `.env`: secrets generated, image lines from the manifest, the operator's values given or asked."""
    for key in given:
        if key in MANAGED:
            raise Refused(f"{key} is the release's to write (its manifest names the image); it cannot be --set")
    values: dict[str, str] = {}
    missing = []
    for key, example in template_assignments(template):
        if key in MANAGED:
            values[key] = getattr(manifest, MANAGED[key])
        elif key in given:
            values[key] = given[key]
        elif key in SECRETS:
            values[key] = SECRETS[key]()
        elif key == RELEASE_KEY:
            values[key] = "latest"
        else:
            answer = ask(key, example)
            if answer is None:
                missing.append(key)
            else:
                values[key] = answer
    if missing:
        raise Refused(f"the release's template asks for {', '.join(missing)}, which only you can give: pass "
                      + " ".join(f"--set {k}=…" for k in missing) + " (or run it at a terminal to be asked)")
    for key, value in given.items():
        values.setdefault(key, value)
    values.setdefault(RELEASE_KEY, given.get(RELEASE_KEY, "latest"))
    return values


def unset_required(values: dict[str, str], *compose_texts: str) -> list[str]:
    return [name for name in required_names(*compose_texts) if not unquoted(values.get(name, ""))]


def write_private(path: Path, text: str) -> None:
    """Born 0600 — never written readable and chmodded after."""
    tmp = path.with_name(f".{path.name}.tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(text)
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)


# ---- the instance ----------------------------------------------------------------------------------------------

class State(NamedTuple):
    release: str
    project: str
    source: str
    unsigned_local: bool


def read_state(home: Path) -> State:
    path = home / STATE_FILE
    try:
        doc = json.loads(path.read_text())
        return State(doc["release"], doc["project"], doc.get("source") or "", bool(doc.get("unsignedLocal")))
    except FileNotFoundError:
        raise Refused(f"{home} holds no instance ({STATE_FILE} is missing) — `veyqctl install` first, or pass "
                      f"--home") from None
    except (OSError, ValueError, KeyError) as e:
        raise Refused(f"{path} cannot be read: {e}") from None


def write_state(home: Path, state: State) -> None:
    doc = {"release": state.release, "project": state.project, "source": state.source,
           "unsignedLocal": state.unsigned_local}
    write_private(home / STATE_FILE, json.dumps(doc, indent=2) + "\n")


def compose_files(home: Path, release: str) -> list[str]:
    files = ["-f", str(release_dir(home, release) / COMPOSE_ASSET)]
    if (home / OVERRIDE_FILE).is_file():
        files += ["-f", str(home / OVERRIDE_FILE)]
    return files


def compose(home: Path, release: str, project: str | None = None) -> list[str]:
    """The recipe, the override and `.env` — and, once the instance has one, its project by name."""
    named = ["-p", project] if project else []
    return ["docker", "compose", *named, *compose_files(home, release), "--env-file", str(home / ENV_FILE)]


def by_project(project: str) -> list[str]:
    """The stack addressed by its project name alone — `stop`, `status` and `logs` work even when `.env` does not."""
    return ["docker", "compose", "-p", project]


def project_name(host: Host, base: list[str]) -> str:
    out = host.run([*base, "config", "--format", "json"]).stdout
    try:
        name = json.loads(out)["name"]
    except (ValueError, KeyError) as e:
        raise Refused(f"`docker compose config` named no project: {e}") from None
    return name


def override_text(home: Path) -> str:
    path = home / OVERRIDE_FILE
    return path.read_text() if path.is_file() else ""


class Service(NamedTuple):
    service: str
    state: str
    health: str
    exit_code: int
    one_shot: bool  # `restart: "no"` — the recipe's volume-ownership step, whose job ends with exit 0

    @property
    def ok(self) -> bool:
        if self.state == "running":
            return self.health in ("", "healthy")
        return self.one_shot and self.state == "exited" and self.exit_code == 0

    @property
    def shown(self) -> str:
        if self.state == "running":
            return self.health or "running"
        return f"{self.state} ({self.exit_code})"


def services(host: Host, project: str) -> list[Service]:
    out = host.run([*by_project(project), "ps", "-a", "--format", "json"]).stdout.strip()
    try:
        rows = json.loads(out) if out.startswith("[") else [json.loads(line) for line in out.splitlines() if line]
    except ValueError as e:
        raise Refused(f"`docker compose ps` answered something that is not JSON: {e}") from None
    # A stopped long-running service also exits 0; only the restart policy tells it from a one-shot that finished.
    restart: dict[str, str] = {}
    ids = [r.get("ID") for r in rows if r.get("ID")]
    if ids:
        inspected = host.run(["docker", "inspect", "--format", "{{.Id}} {{.HostConfig.RestartPolicy.Name}}", *ids],
                             check=False).stdout
        for line in inspected.splitlines():
            cid, _, policy = line.partition(" ")
            restart[cid] = policy.strip()
    policy = lambda r: next((p for cid, p in restart.items() if cid.startswith(r.get("ID") or "-")), "?")  # noqa: E731
    return sorted((Service(r.get("Service", "?"), r.get("State", "?"), r.get("Health", "") or "",
                           int(r.get("ExitCode") or 0), policy(r) in ("", "no")) for r in rows),
                  key=lambda s: s.service)


def report(host: Host, project: str) -> bool:
    rows = services(host, project)
    if not rows:
        say(f"project {project}: no containers")
        return False
    width = max(len(r.service) for r in rows)
    for r in rows:
        say(f"  {r.service:<{width}}  {r.shown}{'' if r.ok else '   <- NOT HEALTHY'}")
    healthy = all(r.ok for r in rows)
    say(f"project {project}: {'every service healthy' if healthy else 'NOT every service is healthy'}")
    return healthy


def up(host: Host, base: list[str], project: str, *extra: str) -> None:
    result = host.run([*base, "up", "-d", "--wait", "--wait-timeout", str(WAIT_SECS), *extra], check=False, stream=True)
    healthy = report(host, project)
    if result.returncode != 0 or not healthy:
        raise Refused(f"the stack is not healthy (`up --wait` exited {result.returncode}); `veyqctl logs` says why")


def admin_address(host: Host, project: str) -> str:
    out = host.run([*by_project(project), "port", "admin", "8080"], check=False).stdout.strip()
    return out.splitlines()[0] if out else ""


# ---- commands ---------------------------------------------------------------------------------------------------

def parse_sets(pairs: list[str]) -> dict[str, str]:
    given = {}
    for pair in pairs or []:
        key, sep, value = pair.partition("=")
        if not sep or not re.match(r"^[A-Za-z_][A-Za-z0-9_]*$", key):
            raise Refused(f"--set {pair!r} is not KEY=VALUE")
        given[key] = value
    return given


def source_spec(args: argparse.Namespace, home: Path) -> str:
    if args.source:
        return str(Path(args.source).resolve()) if not args.source.startswith(("http://", "https://")) else args.source
    try:
        return read_state(home).source
    except Refused:
        return ""


def cmd_install(args: argparse.Namespace, host: Host) -> int:
    home = Path(args.home)
    for existing in (home / STATE_FILE, home / ENV_FILE):
        if existing.exists():
            raise Refused(f"{existing} exists — {home} already holds an instance; `veyqctl upgrade` moves it on")
    given = parse_sets(args.set)
    selector = check_selector(given.get(RELEASE_KEY, "latest"))
    spec = source_spec(args, home)
    source = open_source(spec)
    chosen = select(source.releases(), selector, source.describe())
    say(f"{RELEASE_KEY}={selector} takes release {chosen.release} from {source.describe()}")
    manifest = fetch_manifest(source, chosen)
    verify_images(host, manifest, args.unsigned_local)

    home.mkdir(parents=True, exist_ok=True, mode=0o700)
    (home / BACKUPS_DIR).mkdir(exist_ok=True, mode=0o700)
    host.workdir = home
    directory = fetch_release(source, chosen, manifest, home)
    template = (directory / TEMPLATE_ASSET).read_text()
    values = install_values(template, manifest, given, host.ask)
    values[RELEASE_KEY] = selector
    missing = unset_required(values, (directory / COMPOSE_ASSET).read_text(), override_text(home))
    if missing:
        raise Refused(f"the recipe requires {', '.join(missing)}, which nothing gave a value: pass "
                      + " ".join(f"--set {k}=…" for k in missing))
    write_private(home / ENV_FILE, render_env(template, values))
    say(f"wrote {home / ENV_FILE} (0600) from release {manifest.release}'s template; "
        f"generated {', '.join(k for k in SECRETS if k not in given)}")
    unnamed = compose(home, manifest.release)
    host.run([*unnamed, "config", "--quiet"])
    project = project_name(host, unnamed)
    write_state(home, State(manifest.release, project, spec, args.unsigned_local))
    up(host, compose(home, manifest.release, project), project)
    address = admin_address(host, project)
    say(f"release {manifest.release} runs as project {project}."
        + (f" First-run setup: the admin console on {address} (ssh -L to it from your machine)." if address else ""))
    return 0


def cmd_start(args: argparse.Namespace, host: Host) -> int:
    home = Path(args.home)
    state = read_state(home)
    up(host, compose(home, state.release, state.project), state.project)
    return 0


def cmd_stop(args: argparse.Namespace, host: Host) -> int:
    state = read_state(Path(args.home))
    host.run([*by_project(state.project), "stop"], stream=True)
    say(f"project {state.project} stopped; `veyqctl start` brings it back")
    return 0


def cmd_status(args: argparse.Namespace, host: Host) -> int:
    state = read_state(Path(args.home))
    say(f"release {state.release}" + (" (installed --unsigned-local: NOT VERIFIED)" if state.unsigned_local else ""))
    return 0 if report(host, state.project) else 1


def cmd_logs(args: argparse.Namespace, host: Host) -> int:
    state = read_state(Path(args.home))
    cmd = [*by_project(state.project), "logs", "--no-color", "--timestamps"]
    if args.follow:
        cmd.append("--follow")
    if args.tail:
        cmd += ["--tail", str(args.tail)]
    return host.run([*cmd, *args.service], check=False, stream=True).returncode


def selected(args: argparse.Namespace, home: Path) -> str:
    if args.release:
        return check_selector(args.release)
    env = home / ENV_FILE
    if env.is_file():
        value = unquoted(env_values(env.read_text()).get(RELEASE_KEY, ""))
        if value:
            return check_selector(value)
    return "latest"


def cmd_verify(args: argparse.Namespace, host: Host) -> int:
    home = Path(args.home)
    selector = selected(args, home)
    source = open_source(source_spec(args, home))
    chosen = select(source.releases(), selector, source.describe())
    manifest = fetch_manifest(source, chosen)
    say(f"{RELEASE_KEY}={selector} takes release {chosen.release} from {source.describe()}")
    verify_images(host, manifest, args.unsigned_local)
    say(f"release {chosen.release}: " + ("NOT VERIFIED (--unsigned-local)" if args.unsigned_local
                                          else "both images verified; nothing was pulled"))
    return 0


def cmd_check(args: argparse.Namespace, host: Host) -> int:
    home = Path(args.home)
    selector = selected(args, home)
    source = open_source(source_spec(args, home))
    listed = source.releases()
    chosen = select(listed, selector, source.describe())
    try:
        running = read_state(home).release
    except Refused:
        running = ""
    if running == chosen.release:
        say(f"{RELEASE_KEY}={selector}: release {chosen.release} runs already; `upgrade` would do nothing")
    else:
        say(f"{RELEASE_KEY}={selector}: `upgrade` would take release {chosen.release}"
            + (f" (running: {running})" if running else ""))
    beyond = newer_major(listed, chosen)
    if beyond:
        say(f"a newer major exists beyond {RELEASE_KEY}={selector}: {beyond.release} — read its notes before "
            f"selecting it")
    return 0


# ---- main ------------------------------------------------------------------------------------------------------

def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="veyqctl", description="Install, run and verify a veyq instance on Docker "
                                "Compose from a published release (ADR 0096).")
    p.add_argument("--home", default=DEFAULT_HOME, help=f"the instance's directory (default {DEFAULT_HOME})")
    p.add_argument("--source", default=None,
                   help=f"where releases come from: a directory or an http(s) URL in the mirror layout "
                        f"(default: the GitHub Releases of {UPSTREAM}, or the source the instance was installed from)")
    sub = p.add_subparsers(dest="command", required=True)

    install = sub.add_parser("install", help="fetch, verify and start the release VEYQ_RELEASE selects")
    install.add_argument("--set", action="append", metavar="KEY=VALUE", help="a .env value (repeatable)")
    install.add_argument("--unsigned-local", action="store_true",
                         help="a release produced from a local docker build: no signature is checked")
    install.set_defaults(func=cmd_install)

    for name, func, text in (("start", cmd_start, "bring the stack up and wait until it is healthy"),
                             ("stop", cmd_stop, "stop every service of the stack"),
                             ("status", cmd_status, "every service's state; exit 1 unless all are healthy")):
        sub.add_parser(name, help=text).set_defaults(func=func)

    logs = sub.add_parser("logs", help="the stack's logs")
    logs.add_argument("-f", "--follow", action="store_true")
    logs.add_argument("--tail", type=int, default=None)
    logs.add_argument("service", nargs="*")
    logs.set_defaults(func=cmd_logs)

    verify = sub.add_parser("verify", help="check the selected release's signatures, nothing else")
    verify.add_argument("--release", default=None, help="a selector instead of .env's VEYQ_RELEASE")
    verify.add_argument("--unsigned-local", action="store_true", help="accept a local build's release, unverified")
    verify.set_defaults(func=cmd_verify)

    check = sub.add_parser("check", help="which release `upgrade` would take, and a newer major beyond it")
    check.add_argument("--release", default=None, help="a selector instead of .env's VEYQ_RELEASE")
    check.set_defaults(func=cmd_check)
    return p


def main(argv: list[str] | None = None, host: Host | None = None) -> int:
    args = parser().parse_args(argv)
    set_unverified(bool(getattr(args, "unsigned_local", False)))
    host = host or Host()
    host.workdir = Path(args.home)
    try:
        return args.func(args, host)
    except Refused as e:
        say(f"REFUSED: {e}")
        return 1
    finally:
        set_unverified(False)


if __name__ == "__main__":
    sys.exit(main())
