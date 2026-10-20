#!/usr/bin/env python3
"""veyqctl — install, run, upgrade and back up a veyq instance on Docker Compose from its releases (ADR 0096).

    veyqctl [--home DIR] [--source SRC] <command> …

    install [--set KEY=VALUE …] [--unsigned-local]   fetch the release VEYQ_RELEASE selects, verify it, write .env, up
    start | stop | status                            the stack, addressed by the project the recipe names
    logs [-f] [--tail N] [SERVICE …]                 its logs
    verify [--release SEL] [--unsigned-local]        the signatures of the selected release, and nothing else
    check [--release SEL]                            which release `upgrade` would take, and a newer major beyond it
    upgrade --key-into FILE [--objects-to P] [--unsigned-local]   toward what VEYQ_RELEASE selects (decision 5's order)
    backup --key-into FILE [--objects-to P]          the three legs, by the running release's `instance_backup.py`
    backup prune                                     every capture under backups/ older than B (a daily timer's)
    restore DIR --domain D (--key-from F | --without-key) [--objects-from P]   onto this instance, which holds no data
    rollback DIR --key-from F [--yes]                back to the release a capture was taken under, in place
    self-update                                      this file, replaced by the installed release's `veyqctl`

One file, Python 3.9 and the standard library only, because an operator fetches it with `curl` onto a host that
already needs `python3` for backups: no `pip`, no virtualenv (ADR 0096 decision 2). It manages the instance's
lifecycle on the HOST and holds no Admin API credential — administration stays in admin-web (decision 1).

**An instance lives in `--home`** (default `/opt/veyq`, decision 6):

    .env                   0600, the instance's values, written from the running release's `env.example`
    compose.override.yml   optional: the operator's own Compose overlay, laid over the recipe on every command
    state.json             the running release, the Compose project it runs as, the source, how it was verified
    releases/<release>/    that release's assets, each checked against the sha256 its manifest names
    backups/               the captures an upgrade or `backup` takes: the database and the store's index each

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
Every source is verified the same way (below), so choosing a mirror is a decision about availability and freshness,
never about what a release contains (decision 7). Prereleases are never installed, and an asset name with a directory
in it (`../`) is refused: a release's assets are flat names, by construction of the producer.

**`VEYQ_RELEASE` in `.env` selects the release** (decision 4): `<major>.<minor>` pins one, `<major>` takes the newest
of that major, `latest` the newest of all. `install` takes it from `--set VEYQ_RELEASE=…`, else `latest`, and writes
it into `.env`. `upgrade` (and `check`, which says what `upgrade` would take) enters a major only from the last release
of the one before (decision 5 step 1, `upgrade_target`): a selector beyond the running major takes the running major's
newest release first when the running one is not it, and never more than the next major — so `latest` from 1.3, with
1.9 the newest of major 1, goes to 1.9, and from 1.9 with 3.1 listed to the newest of major 2. A pin inside the next
major is reached directly from its predecessor's last release; a next major with no release (only prereleases, or a
mirror that lacks it) is refused, since going past it would cross two.

**Nothing unverified is pulled or started** (ADR 0057). First the manifest: the publish signs it with `cosign
sign-blob` into `manifest.json.bundle`, an asset beside it, and `fetch_manifest` — the one entry that reads a manifest
from a source — runs the host's `cosign verify-blob` on the manifest's exact bytes against that bundle and the one
identity that publishes veyq (GitHub's OIDC issuer and `ci.yml` of `raddatzk/veyq` on `main` or a `release/<major>.x`
branch, the identity the Run area's *Verify a release* page documents, and `scripts/validate_release_identity.py`
assertion 7 holds both to what signs) BEFORE a single field of it is read — so no sha256 and no digest in it is
trusted unless veyq's publish signed it, whether it came from GitHub, a mirror or a directory. A missing bundle is
refused, and so is a cosign older than `COSIGN_MIN_VERSION` (3.1.3: every cosign up to 3.1.2 accepts a forged
legacy-format bundle without checking its identity, GHSA-fx35-mq7g-6g98). Then the images: the manifest names both by
`tag@digest`, and each is checked with `cosign verify` against the same identity, before a single image is pulled.
`--unsigned-local` is the one way past both, for a release produced from a local `docker build`: no bundle is fetched
and it is refused unless BOTH image references are a bare `name:tag` with no registry host and no digest, refused for
an image that is not on this host (a bare name would otherwise be pulled from Docker Hub's `library/`), and every line
the run prints says that nothing was verified.

**`.env` from the template.** `install` copies the release's `.env` template (`env.example`: the tree's
`infra/docker/.env.example`, published without the leading period a GitHub Release asset cannot keep) line by line: the four
secrets it knows are generated, the two image lines come from the manifest, and every other value the template assigns is the
operator's — given with `--set KEY=VALUE`, or asked for at a terminal, the template's value shown as the example;
without a terminal a missing one is refused, named. A `--set` key the template does not assign is kept below it.
Written `0600`, in a home created `0700`.

**`upgrade` runs only by hand, in ADR 0096 decision 5's order, and nothing stops before step 4:**
  1. resolve the release `.env`'s `VEYQ_RELEASE` selects; nothing to do when it is the running one, refused when it is
     an older one (there is no downgrade: going back is restoring a backup, ADR 0056 §6); one beyond the running
     major is entered from its predecessor's last release, one major per run (above), and a run that stops on the way
     ends by saying that the next `upgrade` enters the next major;
  2. verify its manifest, then both images (as `install` does), then fetch its assets into `releases/<release>/`;
  3. fill the new template from `.env` (`upgrade_values`): the image lines are the new release's, a key `.env` has
     keeps its value, a secret veyqctl knows how to make is made — and every other key the new template assigns, and
     every `${VAR:?…}` the new recipe or the override leaves unset, is refused, named; the result is staged as
     `.env.next` and Compose itself checks it (`config --quiet`);
  4. with an object pool named, copy the store's objects into it while the app still runs (`copy-objects`; a failure
     stops nothing); stop the app (the capture is cold, ADR 0056 §2: the database and the store keep running), back up
     the three legs into `backups/<stamp>-before-<release>/` — the database and the store's index, the pool topped up
     with what arrived since and pruned to its kept captures — and check the capture, then switch `.env` and the
     recipe, `up`, and wait until healthy. A failed backup switches nothing and starts the running release again.
     `--key-into` is required and refused before anything else runs.
The previous release's directory stays, so the diff and the way back are both on disk.

**`backup` and `restore` are the running release's own `instance_backup.py`** — shipped beside `veyqctl` as an asset,
checked against its manifest's sha256 before it runs — so the tool that captured is the tool its release documents.
veyqctl gives it what the operator gave it by hand: the database password, the bundled store's endpoint and admin
token (read from the instance's interpolated recipe, so through the environment, never an argv), the store's network,
and Compose pointed at THIS instance (`COMPOSE_PROJECT_NAME`, `COMPOSE_ENV_FILES`). A store of the operator's own
(`VEYQ_BACKUP_STORE_*` in the shell) passes through, as the tool documents. Leg (c) lives on a volume under Docker's
data root, which a host cannot always read (Docker Desktop keeps it in a VM) and which needs root where it can: so the
key comes out of the stopped app container with `docker cp`, into a `0700` directory in the home for as long as the
tool needs it, and a restored key goes back in through a one-off container of the `app` service running as the
image's own user — born the application's, `0600`, without root (the alternative `instance_backup.py`'s own refusal
names). `--key-into` is required for `backup` and `upgrade`, and refused when it exists or lies inside `backups/`.

**A capture holds the database and the store's index, not the objects** (ADR 0056 §3). The objects stay in the store;
an **object pool** keeps copies only when one is named — `.env`'s `VEYQ_BACKUP_OBJECTS_TO` (an absolute directory, never
inside `releases/`, or `s3:<bucket>[/<prefix>]` with `VEYQ_BACKUP_POOL_*` beside it, which reach the tool through its
environment), or `--objects-to` for one run of `backup` or `upgrade`. With a pool, both run the tool's `copy-objects`
before the app stops, so the cold top-up is short, and the capture with `--objects-to`. `restore` writes objects only
when told where from (`--objects-from`, never implied by `.env`): without it the tool checks every object the rows
require in the live store and writes none — the way back from an upgrade; with it, onto a fresh instance's empty store.
`status`, and `install` at its end, warn while the store in use (the tool's `store-in-use`) is the bundled one — or none
yet — and no pool is named: Garage keeps no object versions, so a lost store volume loses every object whatever
`backups/` holds. The warning never changes an exit code.

**Every capture is kept at most B days** (ADR 0056 decision 8): B is the instance setting the Server Owner sets in
admin-web or with `veyq-admin backup-retention set` (30 until set), and the running release's tool reads it from the
database (`instance_backup.py prune`, 30 when it cannot). `backup`, `upgrade` and `start` prune every capture under
`backups/` older than B, the newest included; `backup prune` does only that, for the daily timer the Run page gives; and
`status` warns while no capture younger than B is there (the tool's `retention`), never changing its exit code. A copy
moved off the host is the operator's to delete by B.

**The erasure ledger travels with leg (c)** (ADR 0056 decision 8): `erasures.log`, beside the key in the app's volume,
which no restore touches. `restore` and `rollback` read it out of that volume through a one-off container of the `app`
service — as they put the key in — and hand it to the tool (`--erasures`, `verify --against-store` too), so an object
erased after the capture reads as erased, not missing; with none there the check runs as before and the tool says so. The
application re-applies the ledger itself at its next start, before it serves.

**`rollback` is in place** (ADR 0096, the owner's O1 (a) of 2026-10-17): after a failed upgrade, the capture it began
with holds the database and the index, and its objects are still in this instance's store, which an upgrade never
rewrites. So `rollback <capture> --key-from F` refuses unless the capture's release is still in `releases/` and the
capture verifies against the live store, and asks before it empties the database (`--yes` without a terminal); then it
stops the app, empties the database (the tool's `empty-database`), switches `.env`'s image lines and `state.json` back to
that release (`upgrade_values` over its template), starts it once so it migrates the empty schema, stops it, restores
with the check only, places the key and starts. The store is never written; objects uploaded under the failed release
stay as orphans with no reclaim row, which the app's bucket reconcile counts and — after its grace, once the Server Owner
confirms the bucket again (the restore emptied that confirmation) — puts on the reclaim list (ADR 0062).

**`self-update`** replaces this file with the installed release's `veyqctl` after checking it against that release's
manifest — and, because it replaces the verifier itself, it first verifies that manifest on disk against the
`manifest.json.bundle` kept beside it, with the same `cosign verify-blob`; a manifest edited in the home, or one whose
bundle is gone, is refused and nothing is replaced. A release installed `--unsigned-local` has no bundle, and
`self-update` says it is NOT VERIFIED. `veyqctl.bundle`, the publish's signature of `veyqctl` alone, is for the copy an
operator fetches by hand, before any manifest is checked; `install` and `upgrade` do not fetch it, since the signed
manifest binds `veyqctl` by its sha256 like every other asset.

**What proves it** — it is a host tool, not a coverage surface (ADR 0040; the use-case
`uc.admin.install-and-upgrade-instance` stays `planned` for that reason): `scripts/veyqctl_selftest.py`, without
Docker, holds the resolver, the manifest's signature and parse, the template merge, `upgrade`'s order and every
refusal, with a stand-in for cosign; `cosign verify-blob` in the exact form it runs was run against cosign's own
signed release files (cr-signed-release-manifest), never against a veyq release, none being published; the live
runs of cr-veyqctl installed, stopped and started an instance from a mirror directory with images built from the tree,
upgraded it from one commit's release to the next with a Memory in it, and restored the upgrade's backup onto a fresh
install; and the stack lane (`scripts/run_stack_e2e.py`, ADR 0090) installs every candidate it boots with this file's
`install --unsigned-local`, its overlay as the override file — `install`, the override and `.env` from the template,
on every run (`start`, `stop`, `upgrade`, `backup` and `restore` are not on that path).

Its limits: it drives Compose only (Kubernetes upgrades with `helm upgrade`, decision 8); the source still decides
WHICH releases exist — `releases.json` and the GitHub listing are unsigned, so a mirror can withhold a newer release or
keep serving an older signed one (a freeze; `upgrade` refuses to go below the running release, and nothing here
proves freshness), though it can no longer serve a forged one; `backup` and `restore` check the release's
`instance_backup.py` against the manifest on disk, verified when it was fetched, and do not verify it again (cosign,
and perhaps the network, during a disaster restore would be a new dependency); it does not check that the Docker it
talks to is the host's own; and an instance that was never set up has no tenant, so the backup tool refuses it and
`upgrade` with it — such an instance is reinstalled.
"""
from __future__ import annotations  # `str | None` below — the repo's scripts run on Python 3.9

import argparse
import datetime
import hashlib
import json
import os
import re
import secrets
import shlex
import shutil
import stat
import subprocess
import sys
import tempfile
import time
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
# The oldest cosign veyqctl verifies a manifest with. The publish signs it into a Sigstore bundle, which cosign 3 reads
# by default (no format flag, so none a later cosign may drop); and every cosign up to 3.1.2 accepts a forged bundle in
# the LEGACY format without checking its identity (GHSA-fx35-mq7g-6g98, fixed in 3.1.3) — a source that controls the
# manifest controls the bundle beside it, so it could hand exactly that one.
COSIGN_MIN_VERSION = "3.1.3"
COSIGN_VERSION = re.compile(r"GitVersion:\s*v?([0-9]+)\.([0-9]+)\.([0-9]+)")

COMPOSE_ASSET = "docker-compose.prod.yml"
TEMPLATE_ASSET = "env.example"  # validate_release_identity.py holds each `*_ASSET` to a name the producer writes
BACKUP_ASSET = "instance_backup.py"
SELF_ASSET = "veyqctl"
MANIFEST_ASSET = "manifest.json"
# The manifest's signature, an asset beside it: `cosign sign-blob` by the publish, under the images' identity.
MANIFEST_BUNDLE_ASSET = "manifest.json.bundle"
RELEASES_INDEX = "releases.json"
# Every release carries these; the manifest lists them with their sha256, and more (the recipe's other files).
REQUIRED_ASSETS = (COMPOSE_ASSET, TEMPLATE_ASSET, BACKUP_ASSET, SELF_ASSET)

ENV_FILE = ".env"
OVERRIDE_FILE = "compose.override.yml"
STATE_FILE = "state.json"
RELEASES_DIR = "releases"
BACKUPS_DIR = "backups"
LEDGER_FILE = "erasures.log"  # the erasure ledger beside the key (ADR 0056 decision 8; `instance_backup.py` LEDGER_FILE)

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

    def confirm(self, prompt: str) -> bool | None:
        """True or False at a terminal; None without one (the caller then needs `--yes`)."""
        if not sys.stdin.isatty():
            return None
        return input(f"veyqctl: {prompt} [y/N]: ").strip().lower() in ("y", "yes")


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
                      f"newest of that major) or `latest` (the newest; `upgrade` enters each major from the last "
                      f"release of the one before)")
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


def newest_of(listed: list[Listed], major: int) -> Listed | None:
    of = [r for r in listed if not r.prerelease and r.major == major]
    return max(of, key=lambda r: (r.major, r.minor)) if of else None


def upgrade_target(listed: list[Listed], running: tuple[int, int], chosen: Listed, where: str) -> Listed:
    """Where an `upgrade` from `running` toward `chosen` (above it) goes in this run — ADR 0096 decision 5 step 1.

    A major is entered only from the last release of the one before, the one path the lanes test (ADR 0091 decision
    6), and no run crosses more than one major: so `chosen` itself within the running major, or in the next one when
    the running release is the newest of its own; otherwise the stop on the way — the running major's newest release,
    or (from that one) the next major's newest. "Newest" never counts a prerelease."""
    major = running[0]
    if chosen.major <= major:
        return chosen
    last = newest_of(listed, major)
    if last is not None and (last.major, last.minor) > running:
        return last
    if chosen.major == major + 1:
        return chosen
    entry = newest_of(listed, major + 1)
    if entry is None:
        raise Refused(f"{chosen.release} lies beyond major {major + 1}, and {where} lists no release of major "
                      f"{major + 1} to enter it from: a run never crosses more than one major (ADR 0096 decision 5). "
                      f"Nothing was stopped.")
    return entry


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
    bundle: bytes = b""  # its signature as the source served it, verified; empty under --unsigned-local


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


def fetch_manifest(host: Host, source: Source, chosen: Listed, unsigned_local: bool) -> Manifest:
    """The one entry that trusts a manifest: its bundle verified against the identity that publishes veyq FIRST, and
    only then a single field of it read. Under `--unsigned-local` no bundle is fetched (a release produced from a local
    build has none), and `verify_images` refuses such a release unless both of its images are local builds."""
    if unsigned_local:
        return parse_manifest(source.fetch(chosen.tag, MANIFEST_ASSET), chosen.tag)
    cosign = require_cosign(host)
    raw = source.fetch(chosen.tag, MANIFEST_ASSET)
    try:
        bundle = source.fetch(chosen.tag, MANIFEST_BUNDLE_ASSET)
    except Refused as e:
        raise Refused(f"{source.describe()} serves no {MANIFEST_BUNDLE_ASSET} for {chosen.tag} ({e}) — the manifest's "
                      f"signature, without which nothing it names is trusted. A mirror copies it like any other file of "
                      f"the release; a release produced locally from `docker build` has none and is installed with "
                      f"--unsigned-local. Nothing was pulled, written or started.") from None
    verify_manifest(host, cosign, raw, bundle, f"the manifest of {chosen.tag} from {source.describe()}")
    return parse_manifest(raw, chosen.tag)._replace(bundle=bytes(bundle))


# ---- verification ---------------------------------------------------------------------------------------------

def require_cosign(host: Host) -> str:
    cosign = host.which("cosign")
    if not cosign:
        raise Refused("cosign is not on PATH, and nothing is pulled or started unverified "
                      "(https://docs.sigstore.dev/cosign/system_config/installation/)")
    return cosign


def check_cosign_version(host: Host, cosign: str) -> None:
    """Refused below `COSIGN_MIN_VERSION`, by name: the manifest's bundle is only as safe as the cosign that reads it."""
    result = host.run([cosign, "version"], check=False)
    found = COSIGN_VERSION.search(f"{result.stdout or ''}\n{result.stderr or ''}")
    have = tuple(int(x) for x in found.groups()) if found else None
    floor = tuple(int(x) for x in COSIGN_MIN_VERSION.split("."))
    if have is None or have < floor:
        shown = ".".join(map(str, have)) if have else "a version `cosign version` does not state"
        raise Refused(f"cosign {shown} is older than {COSIGN_MIN_VERSION}, the oldest veyqctl verifies a manifest with: "
                      f"the publish signs it into a Sigstore bundle, which cosign 3 reads by default, and every cosign "
                      f"up to 3.1.2 accepts a forged bundle in the legacy format without checking its identity "
                      f"(GHSA-fx35-mq7g-6g98) — a source could hand exactly that one. Install cosign "
                      f"{COSIGN_MIN_VERSION} or later. Nothing was pulled, written or started.")


def verify_manifest(host: Host, cosign: str, raw: bytes, bundle: bytes, what: str) -> None:
    """`cosign verify-blob` of the manifest's exact bytes against its bundle and the one identity that publishes veyq."""
    check_cosign_version(host, cosign)
    with tempfile.TemporaryDirectory(prefix="veyqctl-manifest-") as tmp:  # 0700: nobody swaps the blob in between
        blob, sig = Path(tmp) / MANIFEST_ASSET, Path(tmp) / MANIFEST_BUNDLE_ASSET
        blob.write_bytes(raw)
        sig.write_bytes(bundle)
        result = host.run([cosign, "verify-blob", str(blob), "--bundle", str(sig), "--certificate-oidc-issuer",
                           COSIGN_OIDC_ISSUER, "--certificate-identity-regexp", COSIGN_IDENTITY_REGEXP], check=False)
    if result.returncode != 0:
        raise Refused(
            f"{what} does not verify against its {MANIFEST_BUNDLE_ASSET} and the identity that publishes veyq "
            f"({COSIGN_IDENTITY_REGEXP}): {(result.stderr or '').strip()[-800:]}\n"
            f"Nothing was pulled, written or started."
        )
    say(f"{what}: signature verified")


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
    cosign = require_cosign(host)
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
        on_disk = dest / MANIFEST_ASSET
        if not on_disk.is_file() or on_disk.read_bytes() != manifest.raw:
            raise Refused(f"{on_disk} is not the manifest of release {manifest.release} that was just verified — a "
                          f"release does not change; move {dest} aside and run again")
        for name, want in manifest.assets.items():
            path = dest / name
            if not path.is_file() or sha256(path.read_bytes()) != want:
                raise Refused(f"{path} is not the {name} release {manifest.release} names — a release does not "
                              f"change; move {dest} aside and run again")
        bundle = dest / MANIFEST_BUNDLE_ASSET
        if manifest.bundle and not bundle.is_file():
            bundle.write_bytes(manifest.bundle)
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
        if manifest.bundle:
            (staging / MANIFEST_BUNDLE_ASSET).write_bytes(manifest.bundle)
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
    manifest = fetch_manifest(host, source, chosen, args.unsigned_local)
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
    pool_warning(host, home, read_state(home))
    return 0


def cmd_start(args: argparse.Namespace, host: Host) -> int:
    home = Path(args.home)
    state = read_state(home)
    up(host, compose(home, state.release, state.project), state.project)
    try:
        prune(host, home, state, backup_tool(host, home, state), required=False)
    except Refused as e:  # the tool itself could not be settled: said, the start stands
        say(f"WARNING: the captures older than B were not pruned ({str(e).splitlines()[0][:200]})")
    return 0


def cmd_stop(args: argparse.Namespace, host: Host) -> int:
    state = read_state(Path(args.home))
    host.run([*by_project(state.project), "stop"], stream=True)
    say(f"project {state.project} stopped; `veyqctl start` brings it back")
    return 0


def cmd_status(args: argparse.Namespace, host: Host) -> int:
    home = Path(args.home)
    state = read_state(home)
    say(f"release {state.release}" + (" (installed --unsigned-local: NOT VERIFIED)" if state.unsigned_local else ""))
    healthy = report(host, state.project)
    pool_warning(host, home, state)
    retention_warning(host, home, state)
    return 0 if healthy else 1


def cmd_logs(args: argparse.Namespace, host: Host) -> int:
    state = read_state(Path(args.home))
    cmd = [*by_project(state.project), "logs", "--no-color", "--timestamps"]
    if args.follow:
        cmd.append("--follow")
    if args.tail:
        cmd += ["--tail", str(args.tail)]
    return host.run([*cmd, *args.service], check=False, stream=True).returncode


def selected(args: argparse.Namespace, home: Path) -> str:
    if getattr(args, "release", None):
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
    say(f"{RELEASE_KEY}={selector} takes release {chosen.release} from {source.describe()}")
    manifest = fetch_manifest(host, source, chosen, args.unsigned_local)
    verify_images(host, manifest, args.unsigned_local)
    say(f"release {chosen.release}: " + ("NOT VERIFIED (--unsigned-local)" if args.unsigned_local
                                          else "its manifest and both images verified; nothing was pulled"))
    return 0


def entry_note(selector: str, chosen: Listed, target: Listed, then: str) -> str:
    """What a run that stops short of `chosen` says: the selected release, the rule, and the major `then` enters."""
    return (f"{RELEASE_KEY}={selector} selects {chosen.release}, and a major is entered only from the last release of "
            f"the one before (ADR 0096 decision 5): {then} enters major {target.major + 1}")


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
        current = tuple(int(x) for x in running.split(".")) if running else None
        target = (upgrade_target(listed, current, chosen, source.describe())
                  if current and (chosen.major, chosen.minor) > current else chosen)
        say(f"{RELEASE_KEY}={selector}: `upgrade` would take release {target.release}"
            + (f" (running: {running})" if running else ""))
        if target != chosen:
            say(entry_note(selector, chosen, target, "the `upgrade` after that one"))
    beyond = newer_major(listed, chosen)
    if beyond:
        say(f"a newer major exists beyond {RELEASE_KEY}={selector}: {beyond.release} — read its notes before "
            f"selecting it")
    return 0


# ---- the running release's backup tool (ADR 0056) -------------------------------------------------------------

class Facts(NamedTuple):
    """What the backup tool needs, read from the instance's own interpolated recipe — never from key names here."""
    key_path: str        # where the app keeps leg (c), inside its container
    network: str         # the network the store is on, for the tool's rclone container
    store_endpoint: str  # the bundled store, as that container reaches it
    store_token: str     # the bundled store's admin token; the tool derives the S3 key pair from it
    db_password: str     # the database's root password, for the dump and the load


def stack_facts(host: Host, base: list[str]) -> Facts:
    doc = json.loads(host.run([*base, "config", "--format", "json"]).stdout)
    services = doc.get("services") or {}

    def env(service: str, key: str) -> str:
        value = ((services.get(service) or {}).get("environment") or {}).get(key)
        if not value:
            raise Refused(f"the recipe gives `{service}` no {key}, which the backup reads — this release's recipe "
                          f"is not one this veyqctl knows how to back up")
        return str(value)

    store_networks = list((services.get("garage") or {}).get("networks") or {})
    if not store_networks:
        raise Refused("the recipe puts the store (`garage`) on no network, so the backup's rclone container cannot "
                      "reach it")
    network = ((doc.get("networks") or {}).get(store_networks[0]) or {}).get("name") or store_networks[0]
    return Facts(env("app", "VEYQ_SECURITY_CONFIG_SECRET_FILE"), network, env("app", "VEYQ_BUNDLED_STORE_ENDPOINT"),
                 env("app", "VEYQ_BUNDLED_STORE_ADMIN_TOKEN"), env("mariadb", "MARIADB_ROOT_PASSWORD"))


def backup_env(home: Path, state: State, facts: Facts) -> dict[str, str]:
    """The tool's environment: its credentials (never an argv), and Compose pointed at THIS instance.

    The tool runs `docker compose -f <recipe> exec …` with no `-p` and no `--env-file`; the project and the `.env`
    reach it through Compose's own variables, so it addresses this instance and interpolates the recipe with its
    values. A store of the operator's own (`VEYQ_BACKUP_STORE_*` in the shell) passes through and wins, as the tool
    documents.
    """
    return {"VEYQ_BACKUP_DB_PASSWORD": facts.db_password, "VEYQ_BUNDLED_STORE_ENDPOINT": facts.store_endpoint,
            "VEYQ_BUNDLED_STORE_ADMIN_TOKEN": facts.store_token, "COMPOSE_PROJECT_NAME": state.project,
            "COMPOSE_ENV_FILES": str(home / ENV_FILE)}


def verified_asset(home: Path, release: str, name: str) -> Path:
    """A file of a release on disk, refused unless it is still the one its manifest names — it is about to run."""
    manifest = read_release_manifest(home, release)
    path = release_dir(home, release) / name
    want = manifest.assets.get(name)
    if not want or not path.is_file() or sha256(path.read_bytes()) != want:
        raise Refused(f"{path} is not the {name} release {release}'s manifest names — a file of an installed "
                      f"release changed on disk, and veyqctl does not run it")
    return path


def key_destination(home: Path, raw: str | None, why: str) -> Path:
    """`--key-into`: required, new, on an existing directory, and never beside the captures (ADR 0056 §1)."""
    if not raw:
        raise Refused(f"{why} needs --key-into FILE: the backup's leg (c), the config-secret key, goes to a medium of "
                      f"its own — the dump plus the key opens every tenant's SMTP password (ADR 0056 §1). Nothing "
                      f"was stopped.")
    path = Path(raw).expanduser().absolute()
    if path.exists():
        raise Refused(f"--key-into {path} exists — most likely an earlier capture's key; name a new file")
    if not path.parent.is_dir():
        raise Refused(f"--key-into {path}: its directory does not exist")
    backups = (home / BACKUPS_DIR).absolute()
    if path == backups or backups in path.parents:
        raise Refused(f"--key-into {path} is inside {backups}, beside the dumps — the one place leg (c) must not go")
    return path


def app_container(host: Host, project: str) -> str:
    """The project's `app` container, running or stopped — by its labels, never a one-off `run` of the service."""
    ids = host.run(["docker", "ps", "-aq", "--filter", f"label=com.docker.compose.project={project}", "--filter",
                    "label=com.docker.compose.service=app", "--filter", "label=com.docker.compose.oneoff=False"]
                   ).stdout.split()
    if not ids:
        raise Refused(f"project {project} has no app container to take the config-secret key from")
    return ids[0]


class BackupTool(NamedTuple):
    path: Path
    facts: Facts
    env: dict


def backup_tool(host: Host, home: Path, state: State) -> BackupTool:
    """The running release's tool, checked, and what it needs — all of it settled BEFORE the app stops."""
    facts = stack_facts(host, compose(home, state.release, state.project))
    return BackupTool(verified_asset(home, state.release, BACKUP_ASSET), facts, backup_env(home, state, facts))


POOL_TARGET_KEY = "VEYQ_BACKUP_OBJECTS_TO"
POOL_KEYS = ("VEYQ_BACKUP_POOL_ENDPOINT", "VEYQ_BACKUP_POOL_ACCESS_KEY", "VEYQ_BACKUP_POOL_SECRET_KEY",
             "VEYQ_BACKUP_POOL_REGION", "VEYQ_BACKUP_POOL_KEEP")


class PoolChoice(NamedTuple):
    target: str | None   # an absolute directory or `s3:<bucket>[/<prefix>]`, or None: no pool
    env: dict            # the pool's VEYQ_BACKUP_POOL_* from `.env`, for the tool's environment — never an argv


def pool_choice(home: Path, given: str | None, flag: str) -> PoolChoice:
    """The object pool for this run (ADR 0056 §3, ADR 0096 decision 6): `--objects-to`/`--objects-from` when given,
    else `.env`'s VEYQ_BACKUP_OBJECTS_TO; its credentials from `.env`'s VEYQ_BACKUP_POOL_*. A directory is absolute and
    never inside `releases/` (a release's directory is replaced by the release it names); inside `backups/` is allowed
    — the Run page says why another disk is better."""
    values = env_values((home / ENV_FILE).read_text()) if (home / ENV_FILE).is_file() else {}
    env = {key: unquoted(values[key]) for key in POOL_KEYS if unquoted(values.get(key, ""))}
    raw = given if given else unquoted(values.get(POOL_TARGET_KEY, ""))
    if not raw:
        return PoolChoice(None, env)
    if raw.startswith("s3:"):
        return PoolChoice(raw, env)
    path = Path(raw).expanduser()
    if not path.is_absolute():
        if not given:
            raise Refused(f"{POOL_TARGET_KEY}={raw} in {home / ENV_FILE} is not an absolute path (nor s3:<bucket>) — "
                          f"name the pool's directory in full. Nothing was stopped.")
        path = path.absolute()
    releases = (home / RELEASES_DIR).absolute()
    if path == releases or releases in path.parents:
        raise Refused(f"{flag} {path} is inside {releases}, whose directories belong to the releases. Nothing was "
                      f"stopped.")
    return PoolChoice(str(path), env)


def copy_objects_early(host: Host, home: Path, state: State, tool: BackupTool, pool: PoolChoice) -> None:
    """The hot early copy (ADR 0096 decision 5 step 4): the whole bucket into the pool while the app still runs, so the
    cold top-up after the stop copies only what arrived since. Objects are immutable; a failure stops nothing."""
    say(f"copying the store's objects into the pool {pool.target} while the app still runs")
    host.run([sys.executable, str(tool.path), "copy-objects", "--objects-to", pool.target, "--docker-network",
              tool.facts.network], env={**tool.env, **pool.env}, stream=True)


def capture(host: Host, home: Path, state: State, tool: BackupTool, key_into: Path, name: str,
            pool: PoolChoice | None = None) -> Path:
    """Legs (a), (b) and (c) of the stopped instance, by the running release's own backup tool, then verified."""
    facts, env = tool.facts, {**tool.env, **(pool.env if pool else {})}
    pooled = ["--objects-to", pool.target] if pool and pool.target else []
    target = home / BACKUPS_DIR / name
    # Leg (c) lives on a volume, under Docker's data root — not a path this host can always read (Docker Desktop
    # keeps it in a VM). `docker cp` takes it out of the stopped app container instead, into a 0700 directory in
    # the home, for exactly as long as the tool needs it.
    with tempfile.TemporaryDirectory(prefix=".key-", dir=home) as tmp:
        key = Path(tmp) / "config-secret.key"
        host.run(["docker", "cp", f"{app_container(host, state.project)}:{facts.key_path}", str(key)])
        host.run([sys.executable, str(tool.path), "capture", "--compose-file", str(release_dir(home, state.release) / COMPOSE_ASSET),
                  "--docker-network", facts.network, "--config-secret-file", str(key), "--key-into", str(key_into),
                  "--into", str(target), *pooled], env=env, stream=True)
    host.run([sys.executable, str(tool.path), "verify", str(target), "--key-from", str(key_into)], env=env, stream=True)
    say(f"backup: {target} (the database and the store's index), leg (c) at {key_into} — keep them apart, encrypt "
        f"both before they leave this host; " + (f"the objects are in the pool {pool.target}" if pooled else
                                                  "the objects stay in the store, which alone keeps them"))
    return target


BUNDLED_WARNING = (
    "WARNING: this instance stores its photos and files in the bundled store, which keeps no object versions, and "
    f"no object pool is named ({POOL_TARGET_KEY} in .env). A backup holds the database and the store's INDEX, not "
    "the objects: a lost store volume loses every photo and file, whatever backups/ holds. Name a pool on another "
    f"disk — {POOL_TARGET_KEY}=/path/on/another/disk (or s3:<bucket> with VEYQ_BACKUP_POOL_*) — and every backup and "
    "upgrade copies the new objects into it: https://raddatzk.github.io/veyq/run/backup-and-restore/")


def pool_warning(host: Host, home: Path, state: State) -> None:
    """ADR 0096 decision 6: say it while the bundled store has no pool. Never changes an exit code — `status`'s is the
    health verdict. `none` (no store chosen yet) warns the same: the first store an instance gets is the bundled one by
    default. A store of the operator's own is theirs to keep durable, and is silent."""
    if pool_choice(home, None, POOL_TARGET_KEY).target:
        return
    try:
        tool = backup_tool(host, home, state)
        in_use = host.run([sys.executable, str(tool.path), "store-in-use", "--compose-file",
                           str(release_dir(home, state.release) / COMPOSE_ASSET)], env=tool.env).stdout.strip()
    except Refused as e:
        say(f"the store setting in use could not be read ({str(e).splitlines()[0][:200]})")
        in_use = "unreadable"
    if in_use != "own":
        say(f"the store in use: {in_use or 'none named'}")
        say(BUNDLED_WARNING)


def place_key(host: Host, home: Path, state: State, key: Path, key_path: str) -> None:
    """Leg (c) into the app's volume, written by the app image's own user through a one-off container of the service.

    The restored key must be the application's (uid 10001, `0600`) — a key born `root:root` there is one the app
    cannot read (inbox #188). A host path into the volume needs root and a Docker whose data root the host can see;
    a container of the service itself, run as the image's user, needs neither. The key travels on stdin, never argv.
    """
    script = 'umask 077 && cat > "$1.veyqctl" && mv -f "$1.veyqctl" "$1"'
    host.run([*compose(home, state.release, state.project), "run", "--rm", "--no-deps", "-T", "--entrypoint", "/bin/sh",
              "app", "-c", script, "place-key", key_path], stdin=key.read_bytes())


def stop_app(host: Host, home: Path, state: State) -> None:
    """The capture is cold: the writer stops, the database and the store keep running (ADR 0056 §2)."""
    host.run([*compose(home, state.release, state.project), "stop", "app"], stream=True)


def utc_stamp() -> str:
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def prune(host: Host, home: Path, state: State, tool: BackupTool, *, required: bool) -> None:
    """Every capture under `backups/` older than B, by the running release's tool (ADR 0056 decision 8): B from the
    database, which runs. At `backup`, `upgrade` and `start` a failure is said and changes nothing else (`required`
    False); `backup prune` exists only for this, so there it is the command's failure."""
    argv = [sys.executable, str(tool.path), "prune", "--captures", str((home / BACKUPS_DIR).absolute()),
            "--compose-file", str(release_dir(home, state.release) / COMPOSE_ASSET)]
    try:
        host.run(argv, env=tool.env, stream=True)
    except Refused as e:
        if required:
            raise
        say(f"WARNING: the captures older than B were not pruned ({str(e).splitlines()[0][:200]}); the next `backup`, "
            f"`upgrade`, `start` or `backup prune` tries again")


def retention_warning(host: Host, home: Path, state: State) -> None:
    """`status` (ADR 0056 decision 8): say it while no capture under `backups/` is younger than B — admin-web cannot see
    the captures. Never changes an exit code."""
    try:
        tool = backup_tool(host, home, state)
        raw = host.run([sys.executable, str(tool.path), "retention", "--captures", str((home / BACKUPS_DIR).absolute()),
                        "--compose-file", str(release_dir(home, state.release) / COMPOSE_ASSET)], env=tool.env).stdout
        answer = json.loads(raw.strip().splitlines()[-1])
    except (Refused, ValueError, IndexError) as e:
        say(f"B and the captures could not be read ({str(e).splitlines()[0][:200] if str(e) else type(e).__name__})")
        return
    if not answer.get("fresh"):
        newest = answer.get("youngest")
        say(f"WARNING: no capture under {home / BACKUPS_DIR} is younger than B ({answer.get('days')} days"
            + (f"; the newest, {newest}, is from {answer.get('youngestAt')}" if newest else "; there is none")
            + "). Every capture is deleted at B, so this instance has no backup to restore: run `veyqctl backup` — the "
              "Run page gives a daily timer for it (https://raddatzk.github.io/veyq/run/backup-and-restore/)")


def read_ledger(host: Host, home: Path, state: State, key_path: str, into: Path) -> Path | None:
    """The erasure ledger out of the app's volume — `erasures.log` beside the key, which no restore touches — through a
    one-off container of the `app` service, as `place_key` writes the key in. The file at [into], or None when the
    volume holds none (an instance older than the ledger, or none written yet): the tool then checks as before."""
    ledger = str(Path(key_path).parent / LEDGER_FILE)
    script = 'test -f "$1" || exit 3; cat "$1"'
    result = host.run([*compose(home, state.release, state.project), "run", "--rm", "--no-deps", "-T", "--entrypoint",
                       "/bin/sh", "app", "-c", script, "read-ledger", ledger], check=False)
    if result.returncode != 0:
        say(f"no erasure ledger at {ledger} in the app's volume: the restore's object check runs without it")
        return None
    into.write_text(result.stdout or "")
    os.chmod(into, 0o600)
    return into


# ---- upgrade (ADR 0096 decision 5) ------------------------------------------------------------------------------

def upgrade_values(template: str, manifest: Manifest, old: dict[str, str]) -> tuple[dict[str, str], list[str]]:
    """The next `.env`: the new template, filled from the running one — and what only the operator can give.

    The image lines are the new release's; a key `.env` has keeps its value; a secret veyqctl knows how to make is
    made; every other key the new template assigns and `.env` lacks is the operator's, so it is returned as missing
    (the template's assigned keys are its required ones — its optional ones are comments). Every key of `.env` the
    new template does not name is kept below it.
    """
    values: dict[str, str] = {}
    missing = []
    for key, _ in template_assignments(template):
        if key in MANAGED:
            values[key] = getattr(manifest, MANAGED[key])
        elif key in old:
            values[key] = old[key]
        elif key in SECRETS:
            values[key] = SECRETS[key]()
        else:
            missing.append(key)
    for key, value in old.items():
        values.setdefault(key, value)
    return values, missing


def cmd_upgrade(args: argparse.Namespace, host: Host) -> int:
    """ADR 0096 decision 5, in its order; nothing is stopped before step 4."""
    home = Path(args.home)
    key_into = key_destination(home, args.key_into, "`upgrade` backs up before the new release migrates, so it")
    state = read_state(home)
    old = env_values((home / ENV_FILE).read_text())
    selector = selected(args, home)

    # 1. the release VEYQ_RELEASE selects; stop if it is the running one
    spec = source_spec(args, home)
    source = open_source(spec)
    listed = source.releases()
    selection = select(listed, selector, source.describe())
    running = tuple(int(x) for x in state.release.split("."))
    if (selection.major, selection.minor) == running:
        say(f"{RELEASE_KEY}={selector}: release {selection.release} runs already — nothing to upgrade")
        return 0
    if (selection.major, selection.minor) < running:
        raise Refused(f"{RELEASE_KEY}={selector} selects {selection.release}, older than the running {state.release}. "
                      f"An upgrade migrates the schema and there is no downgrade: going back is restoring a backup "
                      f"taken under that release (ADR 0056 §6). Nothing was stopped.")
    chosen = upgrade_target(listed, running, selection, source.describe())
    say(f"step 1: {RELEASE_KEY}={selector} takes release {chosen.release} (running: {state.release})"
        + (f" — {selection.release} lies in a later major, entered from the last release of the one before"
           if chosen != selection else ""))

    # 2. its manifest verified, both images verified by the manifest's digests, then its assets into releases/<release>/
    manifest = fetch_manifest(host, source, chosen, args.unsigned_local)
    verify_images(host, manifest, args.unsigned_local)
    directory = fetch_release(source, chosen, manifest, home)
    say(f"step 2: release {manifest.release} fetched and " + ("NOT verified (--unsigned-local)" if args.unsigned_local
                                                             else "its manifest and both images verified"))

    # 3. refuse if the new template requires a value .env lacks, naming it
    template = (directory / TEMPLATE_ASSET).read_text()
    values, missing = upgrade_values(template, manifest, old)
    missing += [k for k in unset_required(values, (directory / COMPOSE_ASSET).read_text(), override_text(home))
                if k not in missing]
    if missing:
        raise Refused(f"release {manifest.release} requires {', '.join(missing)}, which {home / ENV_FILE} does not "
                      f"give: add " + ", ".join(f"`{k}=…`" for k in missing) + " to it (the new template, "
                      f"{directory / TEMPLATE_ASSET}, says what each is) and run `upgrade` again. Nothing was stopped.")
    staged = home / f"{ENV_FILE}.next"
    write_private(staged, render_env(template, values))
    try:
        named = ["docker", "compose", "-p", state.project, *compose_files(home, manifest.release), "--env-file", str(staged)]
        host.run([*named, "config", "--quiet"])
    except Refused:
        staged.unlink()
        raise
    say(f"step 3: release {manifest.release}'s template is satisfied by {home / ENV_FILE}")
    try:
        tool = backup_tool(host, home, state)
        pool = pool_choice(home, args.objects_to, "--objects-to")
        if pool.target:
            copy_objects_early(host, home, state, tool, pool)
    except Refused:
        staged.unlink()
        raise

    # 4. back up; stop the app; switch to the new release's recipe and image lines; up; wait until healthy
    stopped = time.monotonic()
    stop_app(host, home, state)
    try:
        backup = capture(host, home, state, tool, key_into, f"{utc_stamp()}-before-{manifest.release}", pool)
    except Refused:
        staged.unlink()
        say(f"the backup failed — nothing was switched; starting release {state.release} again")
        up(host, compose(home, state.release, state.project), state.project)
        raise
    prune(host, home, state, tool, required=False)
    os.replace(staged, home / ENV_FILE)
    write_state(home, state._replace(release=manifest.release, source=spec, unsigned_local=args.unsigned_local))
    say(f"step 4: backed up to {backup}; switched {home / ENV_FILE} and the recipe to release {manifest.release}")
    try:
        up(host, compose(home, manifest.release, state.project), state.project)
    except Refused as e:
        raise Refused(f"{e}\nRelease {manifest.release} is switched in but not healthy. The way back is on disk: "
                      f"release {state.release}'s recipe in {release_dir(home, state.release)}, and the backup "
                      f"{backup} with its key at {key_into} — `veyqctl rollback {backup} --key-from {key_into}` "
                      f"restores it in place under release {state.release}.") from None
    say(f"downtime: {time.monotonic() - stopped:.0f} s, from stopping the app to release {manifest.release} healthy")
    say(f"release {manifest.release} runs; release {state.release} stays in {release_dir(home, state.release)}")
    if chosen != selection:
        say(entry_note(selector, selection, chosen, "the next `upgrade`"))
    return 0


# ---- backup, restore, self-update ---------------------------------------------------------------------------------

def cmd_backup(args: argparse.Namespace, host: Host) -> int:
    home = Path(args.home)
    if args.action == "prune":
        # ADR 0056 decision 8: only the age prune, for a daily timer — nothing stops, no key is needed.
        state = read_state(home)
        prune(host, home, state, backup_tool(host, home, state), required=True)
        return 0
    key_into = key_destination(home, args.key_into, "`backup`")
    state = read_state(home)
    tool = backup_tool(host, home, state)
    pool = pool_choice(home, args.objects_to, "--objects-to")
    if pool.target:
        copy_objects_early(host, home, state, tool, pool)
    stopped = time.monotonic()
    stop_app(host, home, state)
    try:
        capture(host, home, state, tool, key_into, utc_stamp(), pool)
        prune(host, home, state, tool, required=False)
    finally:
        up(host, compose(home, state.release, state.project), state.project)
        say(f"downtime: {time.monotonic() - stopped:.0f} s, from stopping the app to every service healthy again")
    return 0


def cmd_restore(args: argparse.Namespace, host: Host) -> int:
    """The running release's tool restores onto THIS instance, which must hold no data yet (ADR 0056 §4, A5)."""
    home = Path(args.home)
    state = read_state(home)
    backup = Path(args.backup).absolute()
    base = compose(home, state.release, state.project)
    tool = backup_tool(host, home, state)
    key_args = ["--without-key"] if args.without_key else (["--key-from", str(Path(args.key_from).absolute())]
                                                           if args.key_from else [])
    # Only when given: a restore copies objects only into an EMPTY store, so `.env`'s pool is never implied — without
    # it the restore checks the objects in the live store (the way back from an upgrade) and writes none.
    pool = pool_choice(home, args.objects_from, "--objects-from") if args.objects_from else PoolChoice(None, {})
    source = ["--objects-from", pool.target] if pool.target else []
    stop_app(host, home, state)
    with tempfile.TemporaryDirectory(prefix=".key-", dir=home) as tmp:
        key = Path(tmp) / "config-secret.key"
        ledger = read_ledger(host, home, state, tool.facts.key_path, Path(tmp) / LEDGER_FILE)
        erasures = ["--erasures", str(ledger)] if ledger else []
        try:
            host.run([sys.executable, str(tool.path), "restore", str(backup), "--domain", args.domain, "--compose-file",
                      str(release_dir(home, state.release) / COMPOSE_ASSET), "--docker-network", tool.facts.network,
                      "--config-secret-file", str(key), *key_args, *source, *erasures], env={**tool.env, **pool.env},
                     stream=True)
        except Refused:
            say("the restore did not finish; the app stays STOPPED — the tool above says whether anything was written "
                "(a refusal at its gates wrote nothing: `veyqctl start` brings the instance back as it was)")
            raise
        if key.is_file():
            place_key(host, home, state, key, tool.facts.key_path)
            say(f"leg (c) placed at {tool.facts.key_path} in the app's volume, as the app's own user")
    up(host, base, state.project)
    say("restored. Now read the config-secret state as the Server Owner (admin console → Server → Config-secret key "
        "must say OK), check the store, and open a Memory that has media — that is what separates a restore from a "
        "hope (ADR 0056 §5)")
    return 0


def cmd_rollback(args: argparse.Namespace, host: Host) -> int:
    """Back to the release a capture was taken under, IN PLACE (ADR 0096, owner 2026-10-17 O1 (a)) — the cluster's
    rollback (ADR 0098 decision 9) in Compose's form. The capture holds the database and the store's index; its objects
    are still in this instance's own store, which an upgrade never rewrites, so nothing but the database moves back:
    every check runs first, then stop, empty the database, switch `.env` and the state to the capture's release, start
    it once (it migrates the empty schema; the bucket exists), stop, restore with the check only, place the key, start.
    Objects uploaded under the failed release stay in the store as orphans with no reclaim row, for the app's bucket
    reconcile to count and, after its grace on a bucket the Server Owner confirms again, reclaim (ADR 0062)."""
    home = Path(args.home)
    state = read_state(home)
    capture_dir = Path(args.capture).absolute()
    try:
        manifest = json.loads((capture_dir / "manifest.json").read_text())
    except (OSError, ValueError) as e:
        raise Refused(f"{capture_dir} is not a capture this tool reads ({e}). Nothing was stopped.") from None
    target = ((manifest.get("app") or {}).get("version") or "")
    if not target:
        raise Refused(f"{capture_dir} names no release it was taken under (manifest app.version), so there is no "
                      f"release to roll back to. Nothing was stopped.")
    if not (release_dir(home, target) / MANIFEST_ASSET).is_file():
        raise Refused(f"the capture was taken under release {target}, whose directory {release_dir(home, target)} is no "
                      f"longer in the home — install it as a new instance and restore onto that ({BACKUPS_DIR}/ keeps "
                      f"the capture). Nothing was stopped.")
    key_from = Path(args.key_from).absolute()
    if not key_from.is_file():
        raise Refused(f"--key-from {key_from} is not a file. Nothing was stopped.")
    domains = manifest.get("domains") or []
    domain = domains[0] if domains else unquoted(env_values((home / ENV_FILE).read_text()).get("VEYQ_DOMAIN", ""))

    # Everything that can refuse, before anything stops: the capture's release's tool, the template it needs, and
    # the capture against the live store — the in-place rollback writes no object, so every one must be there.
    running = compose(home, state.release, state.project)
    facts = stack_facts(host, running)
    tool = BackupTool(verified_asset(home, target, BACKUP_ASSET), facts, backup_env(home, state, facts))
    old_manifest = read_release_manifest(home, target)
    template = (release_dir(home, target) / TEMPLATE_ASSET).read_text()
    values, missing = upgrade_values(template, old_manifest, env_values((home / ENV_FILE).read_text()))
    if missing:
        raise Refused(f"release {target}'s template requires {', '.join(missing)}, which .env lacks. Nothing was stopped.")
    staged = home / f"{ENV_FILE}.rollback"
    write_private(staged, render_env(template, values))
    # The erasure ledger, read once while the running release's app volume is there: an object erased after the capture
    # is erased, not missing, for the verify below and the restore after the stop.
    ledger_dir = Path(tempfile.mkdtemp(prefix=".ledger-", dir=home))
    ledger = read_ledger(host, home, state, facts.key_path, ledger_dir / LEDGER_FILE)
    erasures = ["--erasures", str(ledger)] if ledger else []
    try:
        host.run(["docker", "compose", "-p", state.project, *compose_files(home, target), "--env-file", str(staged),
                  "config", "--quiet"])
        host.run([sys.executable, str(tool.path), "verify", str(capture_dir), "--key-from", str(key_from),
                  "--against-store", "--docker-network", facts.network, *erasures], env=tool.env, stream=True)
        say(f"the capture verifies against the live store: every object its rows require is there")
        sure = True if args.yes else host.confirm(
            f"EMPTY the database of project {state.project} (release {state.release}) and restore {capture_dir.name} "
            f"under release {target}?")
        if sure is None:
            raise Refused("the rollback empties the database; without a terminal to confirm it, pass --yes. Nothing "
                          "was stopped.")
        if not sure:
            raise Refused("not confirmed. Nothing was stopped.")
    except Refused:
        staged.unlink()
        shutil.rmtree(ledger_dir, ignore_errors=True)
        raise

    stop_app(host, home, state)
    host.run([sys.executable, str(tool.path), "empty-database", "--yes", "--compose-file",
              str(release_dir(home, state.release) / COMPOSE_ASSET)], env=tool.env, stream=True)
    os.replace(staged, home / ENV_FILE)
    back = state._replace(release=target)
    write_state(home, back)
    say(f"the database is empty; .env and the state name release {target}. Starting it once, so it migrates the "
        f"empty schema")
    base = compose(home, target, state.project)
    up(host, base, state.project)
    stop_app(host, home, back)
    with tempfile.TemporaryDirectory(prefix=".key-", dir=home) as tmp:
        key = Path(tmp) / "config-secret.key"
        try:
            host.run([sys.executable, str(tool.path), "restore", str(capture_dir), "--domain", domain, "--compose-file",
                      str(release_dir(home, target) / COMPOSE_ASSET), "--docker-network", facts.network,
                      "--config-secret-file", str(key), "--key-from", str(key_from), *erasures], env=tool.env,
                     stream=True)
        except Refused:
            say(f"the restore did not finish; release {target} runs on an EMPTY database, its app STOPPED — the tool "
                f"above says why. Fix it and run `veyqctl restore {capture_dir} --domain {domain} --key-from "
                f"{key_from}`")
            raise
        if key.is_file():
            place_key(host, home, back, key, facts.key_path)
    shutil.rmtree(ledger_dir, ignore_errors=True)
    up(host, base, state.project)
    say(f"rolled back to release {target} from {capture_dir.name}; release {state.release} stays in "
        f"{release_dir(home, state.release)}. .env keeps VEYQ_RELEASE as it was, so the next `upgrade` moves forward "
        f"again — pin it to {target} to stay. Now read the config-secret state as the Server Owner, check the store, "
        f"and open a Memory that has media (ADR 0056 §5)")
    return 0


SELF = Path(__file__)


def cmd_self_update(args: argparse.Namespace, host: Host) -> int:
    """This file, replaced by the installed release's `veyqctl` — the one its manifest names, checked by sha256."""
    home = Path(args.home)
    state = read_state(home)
    if not state.unsigned_local:
        # `self-update` replaces the verifier itself, so the manifest on disk is verified again before its sha256 of
        # `veyqctl` is believed: a manifest edited in the home to match a swapped `veyqctl` must not install it.
        directory = release_dir(home, state.release)
        try:
            raw, bundle = (directory / MANIFEST_ASSET).read_bytes(), (directory / MANIFEST_BUNDLE_ASSET).read_bytes()
        except OSError as e:
            raise Refused(f"release {state.release} on disk has no verifiable manifest ({e}): its "
                          f"{MANIFEST_BUNDLE_ASSET} is kept beside the manifest at `install` and `upgrade`, and "
                          f"without it the `veyqctl` the manifest names is not trusted. Nothing was replaced.") from None
        verify_manifest(host, require_cosign(host), raw, bundle, f"the manifest of release {state.release} in {directory}")
    asset = verified_asset(home, state.release, SELF_ASSET)
    target = SELF.resolve()
    if target == asset.resolve():
        say(f"{target} is release {state.release}'s own copy — nothing to replace")
        return 0
    data = asset.read_bytes()
    if target.is_file() and target.read_bytes() == data:
        say(f"{target} is already release {state.release}'s veyqctl")
        return 0
    staged = target.with_name(f".{target.name}.new")
    mode = stat.S_IMODE(target.stat().st_mode) if target.exists() else 0o755
    try:
        staged.write_bytes(data)
        os.chmod(staged, mode)
        os.replace(staged, target)
    except OSError as e:
        staged.unlink(missing_ok=True)
        raise Refused(f"{target} cannot be replaced: {e}") from None
    say(f"{target} is now release {state.release}'s veyqctl (sha256 {sha256(data)[:16]}…, as its manifest names)"
        + (" — that release was installed --unsigned-local: NOT VERIFIED" if state.unsigned_local else ""))
    return 0


# ---- main ------------------------------------------------------------------------------------------------------

def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="veyqctl", description="Install, run, upgrade and back up a veyq instance on "
                                "Docker Compose from its releases (ADR 0096).")
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

    upgrade = sub.add_parser("upgrade", help="move toward the release .env's VEYQ_RELEASE selects, entering a major "
                                             "only from the last release of the one before (ADR 0096 decision 5)")
    upgrade.add_argument("--key-into", metavar="FILE", help="where the backup's leg (c) goes — required, its own medium")
    upgrade.add_argument("--unsigned-local", action="store_true",
                         help="a release produced from a local docker build: no signature is checked")
    upgrade.add_argument("--objects-to", metavar="POOL", help=f"the object pool for this run's backup, instead of "
                                                               f".env's {POOL_TARGET_KEY}")
    upgrade.set_defaults(func=cmd_upgrade)

    backup = sub.add_parser("backup", help="capture the three legs with the running release's backup tool; `backup "
                                           "prune` only deletes the captures older than B")
    backup.add_argument("action", nargs="?", choices=["prune"],
                        help="prune: delete every capture under backups/ older than B, the newest too, and nothing "
                             "else — the daily timer's command (ADR 0056 decision 8)")
    backup.add_argument("--key-into", metavar="FILE", help="where leg (c) goes — required, its own medium")
    backup.add_argument("--objects-to", metavar="POOL", help=f"the object pool for this run, instead of .env's "
                                                              f"{POOL_TARGET_KEY}: an absolute directory or s3:<bucket>")
    backup.set_defaults(func=cmd_backup)

    restore = sub.add_parser("restore", help="restore a capture onto this instance, which must hold no data yet")
    restore.add_argument("backup", help="the capture directory")
    # Required, never defaulted: the tool's domain gate is a deliberate statement by the operator (ADR 0056 §4), and
    # a capture's domains are its TENANTS' — `.env`'s VEYQ_DOMAIN is the bootstrap domain, not one of them.
    restore.add_argument("--domain", required=True, help="the tenant domain the capture holds (ADR 0056 §4)")
    key = restore.add_mutually_exclusive_group()
    key.add_argument("--key-from", metavar="FILE", help="leg (c), from wherever the capture's key was kept")
    key.add_argument("--without-key", action="store_true", help="restore knowingly without leg (c)")
    restore.add_argument("--objects-from", metavar="POOL",
                         help="copy the objects from this pool into this instance's EMPTY store — a restore onto a "
                              "fresh instance; without it the objects are checked in the live store, none written")
    restore.set_defaults(func=cmd_restore)

    rollback = sub.add_parser("rollback", help="back to the release a capture was taken under, in place: empty the "
                                               "database, start that release once, restore with the check only")
    rollback.add_argument("capture", help="the capture directory, taken under the release to go back to")
    rollback.add_argument("--key-from", required=True, metavar="FILE", help="the capture's leg (c)")
    rollback.add_argument("--yes", action="store_true", help="empty the database without asking (no terminal)")
    rollback.set_defaults(func=cmd_rollback)

    update = sub.add_parser("self-update", help="replace this veyqctl with the installed release's")
    update.set_defaults(func=cmd_self_update)
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
