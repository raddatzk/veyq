#!/usr/bin/env python3
"""veyqctl — set up, run, upgrade and back up a veyq instance from its signed releases: on Docker Compose (ADR 0096),
or through Argo CD on Kubernetes (ADR 0126), optionally with a new server beneath it (veyq-server).

    veyqctl [--home DIR | --instance NAME] [--source SRC] <command> …

    init                                             set up a new instance; asks everything first, then sets it up
    connect [--connect-name N] [--make-current yes]  this computer's SSH tunnel and kubectl context, again
    tls production [--yes]                           GitOps: from Let's Encrypt's test certificates to real ones
    instances | use NAME                             your instances, and which one is current
    install [--set KEY=VALUE …] [--unsigned-local]   Compose: download, verify and start what VEYQ_RELEASE selects
    start | stop | status | logs                     Compose: the running stack
    verify | check [--release SEL]                   the selected release's signatures; what `upgrade` would take
    upgrade [--key-into FILE] [--push]               Compose: back up, then switch; GitOps: a commit
    backup [prune] --key-into FILE                   Compose: the database, the store's index and the key
    restore DIR --domain D --key-from F | rollback DIR --key-from F   Compose: from a backup
    self-update                                      Compose: this file, replaced by the installed release's

`veyqctl <command> --help` has every flag; `-v` / `--verbose` shows every program it starts, with its arguments.

One file, Python 3.9 and the standard library only, because an operator fetches it with `curl` onto a host that
already needs `python3` for backups: no `pip`, no virtualenv (ADR 0096 decision 2). It manages the instance's
lifecycle on the HOST and holds no Admin API credential — administration stays in admin-web (decision 1). Every
program it starts goes through `Host`, the seam the self-test replaces, and nothing unverified is pulled, written or
started. Its parts, in the order of the file — each says what it does at the head of its section:

    constants, Refused, Host        what every command shares; the identity constants scripts/ guards read by name
    sources, which release, plan    where releases come from; what a selector means against what runs
    programs, verification          the programs a command needs; cosign over the manifest and every image
    .env, the instance              Compose's home: `.env` from the template, `docker compose` aimed at it
    backup tool, upgrade, restore   Compose's lifecycle, in ADR 0096 decision 5's order
    instances                       which instance a command acts on
    the GitOps target               veyq's files in an Argo CD repository: the texts, verified pins, render
    the server path                 veyq-server verified and rendered, the host key, the bootstrap, connect
    init                            every question (Q) before the first step, kept in veyq-setup.json (InitRun)
    the GitOps verbs                verify, check, upgrade and tls on a GitOps instance
    the targets, main               Target: one set of verbs, each target doing its own or refusing

What proves it — a host tool, not a coverage surface (ADR 0040): `src/tools/veyqctl_selftest.py`, without Docker or
a cluster, drives every command and every refusal against stand-ins for each program it starts; `cosign verify-blob`
in the exact form it runs verified cosign's own signed release files (cr-signed-release-manifest) and veyq's releases
since 1.0 (the live GitOps runs of 2026-11-10); the live runs of cr-veyqctl installed, upgraded and restored an
instance from a mirror; and the stack lane (`scripts/run_stack_e2e.py`, ADR 0090) installs every candidate it boots
with this file's `install --unsigned-local`.

Its limits: the source still decides WHICH releases exist — `releases.json` and the GitHub listing are unsigned, so a
mirror can withhold a newer release or keep serving an older signed one (a freeze; `upgrade` refuses to go below the
running release, nothing here proves freshness), though it cannot serve a forged one; `backup` and `restore` check
the release's `instance_backup.py` against the manifest on disk and do not verify it again (cosign, and perhaps the
network, during a disaster restore would be a new dependency); it does not check that the Docker it talks to is the
host's own; an instance that was never set up has no tenant, so the backup tool refuses it and `upgrade` with it.
"""
from __future__ import annotations  # `str | None` below — the repo's scripts run on Python 3.9

import argparse
import base64
import contextlib
import datetime
import errno
import hashlib
import ipaddress
import json
import os
import re
import secrets
import shlex
import shutil
import socket
import ssl
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Callable, Iterable, NamedTuple

UPSTREAM = "raddatzk/veyq"
DEFAULT_HOME = "/opt/veyq"
GITHUB_API = f"https://api.github.com/repos/{UPSTREAM}/releases"
GITHUB_DOWNLOAD = f"https://github.com/{UPSTREAM}/releases/download"

# The identity that releases veyq (ADR 0057 §3): the same two values the Run area's `verify-a-release.md` passes to
# `cosign verify`, held to what signs by `scripts/validate_release_identity.py` assertion 7, which reads them here. The
# promotion's workflow (`release.yml`), not the workflow that signs every snapshot (`publish.yml`): a snapshot and its
# release are the same digest, so only the identity tells a release from a snapshot (ADR 0091 decision 5). The owner
# and repository are the upstream the chart's `home:` names, which the guard derives them from.
COSIGN_OIDC_ISSUER = "https://token.actions.githubusercontent.com"
COSIGN_IDENTITY_REGEXP = r"^https://github\.com/raddatzk/veyq/\.github/workflows/release\.yml@refs/heads/(main|release/[0-9]+\.x)$"
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


# How a refusal ends: what it left untouched, in the same words everywhere.
NOTHING_STOPPED = "Nothing was stopped."
NOTHING_WRITTEN = "Nothing was written."
NOTHING_CHANGED = "Nothing was changed."
NOTHING_PULLED = "Nothing was pulled, written or started."


class Refused(Exception):
    """A refusal: the message is the whole explanation, printed as is."""


# ---- output ------------------------------------------------------------------------------------------------

# One look for every command: a heading per step (`== …`), what happened beneath it as indented sentences, a check
# that passed as `✓ <what>  <detail>`, and what to do next at the end. The programs veyqctl starts are shown only with
# `--verbose`; one that fails is named in the refusal either way. Digests are shortened on screen — the files and
# `--verbose` carry them whole.

_unverified = False
_verbose = False
UNVERIFIED_PREFIX = "veyqctl [--unsigned-local: NOTHING VERIFIED]: "


def _print(line: str) -> None:
    print(f"{UNVERIFIED_PREFIX if _unverified else ''}{line}", flush=True)


def say(message: str) -> None:
    """A line of what happens, under the current heading. Under `--unsigned-local` each one says that nothing was
    verified."""
    for line in message.splitlines() or [""]:
        _print(f"   {line}" if line else "")


def heading(title: str) -> None:
    """A step: a blank line, then `== title`."""
    print(flush=True)
    _print(f"== {title}")


def checked(what: str, detail: str = "") -> None:
    """A check that passed: `✓ what  detail`, the details of a run's checks aligned."""
    say(f"✓ {what:<12}  {detail}".rstrip() if detail else f"✓ {what}")


def refused(message: str) -> None:
    """A refusal, set apart: `REFUSED:` and the whole explanation."""
    print(flush=True)
    lines = message.splitlines() or [""]
    _print(f"REFUSED: {lines[0]}")
    for line in lines[1:]:
        _print(f"   {line}")


def short(ref: str) -> str:
    """`name@sha256:<64 hex>` (or a bare digest) as `name@sha256:<12 hex>…` — enough to tell two apart on screen."""
    return re.sub(r"(sha256:[0-9a-f]{12})[0-9a-f]{52}", r"\1…", ref)


def set_unverified(on: bool) -> None:
    global _unverified
    _unverified = on


def set_verbose(on: bool) -> None:
    global _verbose
    _verbose = on


# ---- the host: every process veyqctl starts --------------------------------------------------------------------

def clean_env(extra: dict[str, str] | None = None) -> dict[str, str]:
    """This process's environment without `COMPOSE_*`, plus `extra`.

    A `COMPOSE_PROJECT_NAME`, `COMPOSE_FILE` or `COMPOSE_PROFILES` left in the operator's shell would change which
    stack a command addresses or what the recipe means; veyqctl names the project and the files itself.
    """
    env = {k: v for k, v in os.environ.items() if not k.startswith("COMPOSE_")}
    env.update(extra or {})
    return env


def yes_or_no(answer: str) -> str:
    """y, yes, n and no — in any case — as `yes` or `no`; anything else as it was typed."""
    return {"y": "yes", "yes": "yes", "n": "no", "no": "no"}.get(answer.strip().lower(), answer)


class Host:
    """Docker, cosign and the terminal, behind one seam the self-test replaces. Secrets never reach an argv here.

    `workdir` is where every command runs — the instance's home once it exists — so a compose file that happens to
    sit in the operator's current directory is never picked up by a command that names the project alone.
    """

    workdir: Path | None = None
    # With `--verbose` every program is shown before it runs — except while `init` only looks around to ask well (git,
    # kubectl reads): the questions are what the operator reads then.
    quiet = False
    # The cosign whose version passed the floor in this run: checked once, not before every signature.
    cosign_checked: str | None = None

    @contextlib.contextmanager
    def quietly(self):
        """Commands that only look around (to ask well) are not echoed; the questions are what the operator reads."""
        quiet, self.quiet = self.quiet, True
        try:
            yield
        finally:
            self.quiet = quiet

    def run(self, cmd: list[str], *, check: bool = True, stream: bool = False,
            env: dict[str, str] | None = None, stdin: bytes | None = None) -> subprocess.CompletedProcess:
        if _verbose and not self.quiet:
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
        return input(f"   {key}{shown}: ").strip() or None

    def section(self, title: str) -> None:
        heading(title)

    def prompt(self, question: str, default: str = "", hint: str = "", yes_no: bool = False,
               optional: bool = False) -> str | None:
        """A question at a terminal, as setup scripts ask: the hint above it, `[default]` that Enter takes, asked again
        while an answer is needed and none is given. None without a terminal (the caller then needs the flag).
        `yes_no`: shown as every confirmation is, `[Y/n]` or `[y/N]`, and answered `yes` or `no` (y and n too).
        `optional`: Enter is an answer too (""), which the question names — "Enter: create a new one"."""
        if not sys.stdin.isatty():
            return None
        print()
        if hint:
            print(f"   {hint}")
        shown = ({"yes": " [Y/n]", "no": " [y/N]"}.get(default, " [y/n]") if yes_no
                 else f" [{default}]" if default else "")
        while True:
            try:
                answer = yes_or_no(input(f"   {question}{shown}: ").strip()) if yes_no else input(
                    f"   {question}{shown}: ").strip()
            except EOFError:  # the input ended: the offer, or nothing (the caller refuses, naming the flag)
                print()
                return default or None
            if answer or default or optional:
                return answer or default
            print("   An answer is needed here.")

    def secret(self, question: str) -> str | None:
        """A hidden answer, typed twice; "" for Enter (the caller generates one); None without a terminal."""
        if not sys.stdin.isatty():
            return None
        import getpass
        while True:
            first = getpass.getpass(f"   {question}: ")
            if not first or getpass.getpass("   again: ") == first:
                return first
            print("   The two did not match, try again.", flush=True)

    def sleep(self, seconds: float) -> None:
        time.sleep(seconds)

    def background(self, cmd: list[str]) -> subprocess.Popen:
        """A command left running (the tunnel `init` opens to wait for Argo CD), its output discarded."""
        if _verbose:
            say("+ " + " ".join(shlex.quote(c) for c in cmd) + " &")
        return subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def resolve(self, name: str) -> set[str]:
        """The IPv4 addresses `name` resolves to here (an address resolves to itself); empty when it does not."""
        try:
            return {info[4][0] for info in socket.getaddrinfo(name, None, socket.AF_INET)}
        except OSError:
            return set()

    def server_release(self) -> tuple[str, bytes, bytes]:
        """veyq-server's newest release: its tag, manifest and Sigstore bundle — trusted after cosign, never before."""
        doc = json.loads(_http_get(f"https://api.github.com/repos/{SERVER_TEMPLATE_REPO}/releases/latest"))
        tag = str(doc.get("tag_name") or "")
        if not TAG.match(tag):
            raise Refused(f"veyq-server's newest release has no release tag ({tag!r})")
        base = f"https://github.com/{SERVER_TEMPLATE_REPO}/releases/download/{tag}"
        return (tag, _http_get(f"{base}/{SERVER_MANIFEST_FILE}"),
                _http_get(f"{base}/{SERVER_MANIFEST_FILE}.bundle"))

    def wait(self, prompt: str) -> bool:
        """Wait at a terminal until the person presses Enter; False without one, or when the input ended."""
        if not sys.stdin.isatty():
            return False
        print("\n".join(f"   {line}" for line in prompt.splitlines()[:-1]), flush=True)
        try:
            input(f"   {prompt.splitlines()[-1]} ")
        except EOFError:
            return False
        return True

    def confirm(self, prompt: str) -> bool | None:
        """True or False at a terminal; None without one (the caller then needs `--yes`)."""
        if not sys.stdin.isatty():
            return None
        return input(f"   {prompt} [y/N]: ").strip().lower() in ("y", "yes")

    def oci_digest(self, ref: str) -> str:
        """The digest `<registry>/<repository>:<tag>` resolves to now: the registry's manifest endpoint (the OCI
        distribution spec, so any registry), anonymously, with the bearer token a registry's 401 points at. Only a
        lookup — what it returns is trusted after `cosign verify` of that digest, never before."""
        name, _, tag = ref.rpartition(":")
        registry, _, repository = name.partition("/")
        if not (registry and repository and tag):
            raise Refused(f"{ref} is not `<registry>/<repository>:<tag>`")
        url = f"https://{registry}/v2/{repository}/manifests/{tag}"
        accept = ", ".join(("application/vnd.oci.image.index.v1+json", "application/vnd.oci.image.manifest.v1+json",
                            "application/vnd.docker.distribution.manifest.list.v2+json",
                            "application/vnd.docker.distribution.manifest.v2+json"))

        def head(token: str | None):
            headers = {"Accept": accept, "User-Agent": "veyqctl"}
            if token:
                headers["Authorization"] = f"Bearer {token}"
            request = urllib.request.Request(url, method="HEAD", headers=headers)
            return urllib.request.urlopen(request, timeout=60, context=_tls_context())

        try:
            try:
                response = head(None)
            except urllib.error.HTTPError as e:
                challenge = e.headers.get("WWW-Authenticate", "") if e.code == 401 else ""
                params = dict(re.findall(r'(\w+)="([^"]*)"', challenge))
                if not challenge.lower().startswith("bearer") or "realm" not in params:
                    raise Refused(f"HEAD {url} answered {e.code}") from None
                query = urllib.parse.urlencode({k: v for k, v in params.items() if k in ("service", "scope")})
                answer = json.loads(_http_get(f"{params['realm']}?{query}"))
                response = head(answer.get("token") or answer.get("access_token"))
        except urllib.error.HTTPError as e:
            raise Refused(f"HEAD {url} answered {e.code}") from None
        except (urllib.error.URLError, OSError, ValueError) as e:
            raise Refused(f"HEAD {url} failed: {e}") from None
        digest = response.headers.get("Docker-Content-Digest", "")
        if not re.match(r"^sha256:[0-9a-f]{64}$", digest):
            raise Refused(f"{ref}: the registry named no sha256 digest (`Docker-Content-Digest`: {digest!r})")
        return digest


# ---- sources: which releases exist, a manifest, an asset -------------------------------------------------------

# A release is the source's three answers — which releases exist, what one's manifest says, give me one asset — and
# three sources answer them the same way: the GitHub Releases of `raddatzk/veyq` (the default), an HTTP mirror and a
# local directory, both in the mirror layout `scripts/release_assets.py` produces (`releases.json`, `<tag>/<asset>`).
# Every source is verified the same way (below), so choosing a mirror is a decision about availability and freshness,
# never about what a release contains (decision 7). Prereleases are never installed, and an asset name with a
# directory in it (`../`) is refused: a release's assets are flat names, by construction of the producer.


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


def _tls_context() -> ssl.SSLContext:
    """The default verifying context with TLS 1.2 as its floor: on Python 3.9 `create_default_context()` still admits
    TLS 1.0 under the host's TLS library (measured 2026-11-05 under LibreSSL 2.8.3), and an operator's host may run 3.9.
    The assets are signature-checked either way; this keeps the transport from offering what no mirror should speak."""
    context = ssl.create_default_context()
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    return context


def _http_get(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "veyqctl", "Accept": "*/*"})
    try:
        with urllib.request.urlopen(request, timeout=120, context=_tls_context()) as response:
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

# `VEYQ_RELEASE` in `.env` selects the release (decision 4): `<major>.<minor>` pins one, `<major>` takes the newest of
# that major, `latest` the newest of all. `install` takes it from `--set VEYQ_RELEASE=…`, else `latest`, and writes it
# into `.env`. `upgrade` (and `check`, which says what `upgrade` would take) enters a major only from the release that
# major's release names as its entry (decision 5 step 1, `upgrade_target`): `majorEntry` in its signed manifest, the
# newest release of the previous major when it was cut — the one its upgrade proof ran from (ADR 0085 decision 10) —
# and never more than the next major per run. So `latest` from 1.3, with 2.5 entered from 1.9, goes to 1.9, and from
# 1.9 with 3.1 listed to the newest of major 2; a pin inside the next major is reached directly from its entry. A fix
# release of the old major cut after it (1.10) is no way in: 2.5 was never proved from it, and from 1.10 only a later
# release of major 2 (entered from 1.10) is taken, else the upgrade is refused. A next major with no release (only
# prereleases, or a mirror that lacks it) is refused, since going past it would cross two. Reading an entry fetches
# and verifies that release's manifest, so `check` verifies too.


def check_selector(selector: str) -> str:
    if not SELECTOR.match(selector or ""):
        raise Refused(f"{RELEASE_KEY}={selector!r} is not valid: use `<major>.<minor>` (stay on that release), `<major>` "
                      f"(the newest of that major) or `latest` (the newest)")
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


def upgrade_target(listed: list[Listed], running: tuple[int, int], chosen: Listed, where: str,
                   entry_of: Callable[[Listed], str | None]) -> Listed:
    """Where an `upgrade` from `running` toward `chosen` (above it) goes in this run — ADR 0096 decision 5 step 1.

    No run crosses more than one major, and a major is entered only from the release its own upgrade proof ran from
    (ADR 0085 decision 10): the one its signed manifest names as `majorEntry`, which `entry_of` reads — the newest
    release of the previous major when it was cut, a `release/<major>.x` fix included. Not simply the running major's
    newest NOW: a fix release cut after the next major's release (1.10 after 2.0, entered from 1.9) is one nobody
    proved 2.0 from, and its migrations may be ones 2.0 lacks. So the gate is `chosen` in the next major, or that
    major's newest when `chosen` lies beyond it; this run takes the gate when the instance runs its entry, else the
    entry on the way; and an instance past the entry is refused, since a later release of the next major (whose entry is
    the newest of the running major then) is the one that enters from it. Within a major `chosen` itself. "Newest"
    never counts a prerelease."""
    major = running[0]
    if chosen.major <= major:
        return chosen
    gate = chosen if chosen.major == major + 1 else newest_of(listed, major + 1)
    if gate is None:
        raise Refused(f"{chosen.release} is more than one major ahead, and {where} lists no release of major {major + 1} "
                      f"to go through: an upgrade crosses one major at a time. Nothing was stopped.")
    entry = entry_of(gate)
    if entry is None:
        raise Refused(f"the manifest of {gate.tag} does not name the release major {major + 1} is entered from "
                      f"(`majorEntry`), so no upgrade into it from major {major} was tested. Nothing was stopped.")
    at = tuple(int(x) for x in entry.split("."))
    if at == running:
        return gate
    if at > running:
        stop = next((r for r in listed if (r.major, r.minor) == at and not r.prerelease), None)
        if stop is None:
            raise Refused(f"{gate.release} is entered from {entry} (its manifest's `majorEntry`), but {where} does not "
                          f"list {entry}. Nothing was stopped.")
        return stop
    running_release = f"{running[0]}.{running[1]}"
    raise Refused(f"{gate.release} is entered from {entry} (its manifest's `majorEntry`), but this instance runs "
                  f"{running_release}, a later release of major {major}: an upgrade from {running_release} into "
                  f"{gate.release} was never tested, and {running_release} may have database changes {gate.release} "
                  f"lacks. A later release of major {major + 1} will be entered from {running_release} or after: "
                  + (f"select `{major + 1}` for the newest one, or wait for it." if gate is chosen and chosen != newest_of(listed, major + 1)
                     else "wait for it — none is listed yet.")
                  + " Nothing was stopped.")


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
    major_entry: str | None = None  # `majorEntry`: the previous major's release this one is entered from, or none
    tools: str | None = None  # `images.tools`: the backup gate's image, which only Helm runs (a GitOps instance needs it)


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
    # The release the producer derived as this major's entry (`release_assets.major_entry`): null where the previous
    # major has none, else one of the previous major's releases. The key itself is required — every manifest carries it.
    if "majorEntry" not in doc:
        raise Refused(f"the manifest of {tag} names no `majorEntry` — the release its major is entered from, or null")
    major_entry = doc["majorEntry"]
    entry = TAG.match(f"v{major_entry}") if isinstance(major_entry, str) else None
    if major_entry is not None and (entry is None or int(entry.group(1)) != int(release.split(".")[0]) - 1):
        raise Refused(f"the manifest of {tag} gives `majorEntry` {major_entry!r}, which is no release of the major "
                      f"before {release}")
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
    tools = images.get("tools")
    if tools is not None and not (isinstance(tools, str) and tools):
        raise Refused(f"the manifest of {tag} gives `images.tools` {tools!r}, which is no image reference")
    return Manifest(release, str(doc.get("date") or ""), images["app"], images["admin"], unscheduled, dict(assets),
                    bytes(raw), major_entry=major_entry, tools=tools)


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
        raise Refused(f"{source.describe()} has no {MANIFEST_BUNDLE_ASSET} for {chosen.tag} ({e}): without this "
                      f"signature nothing in the release is trusted. A mirror must copy it like every other file; a "
                      f"release you built locally has none — install it with --unsigned-local. Nothing was pulled, "
                      f"written or started.") from None
    verify_manifest(host, cosign, raw, bundle, f"the manifest of {chosen.tag} from {source.describe()}",
                    shown=f"{chosen.tag}, signed by veyq's release workflow")
    return parse_manifest(raw, chosen.tag)._replace(bundle=bytes(bundle))


def manifest_reader(host: Host, source: Source, unsigned_local: bool) -> Callable[[Listed], Manifest]:
    """[fetch_manifest] for one run, each release's manifest fetched and verified once: `upgrade_target` reads a
    major's entry from the same verified manifest the run then installs."""
    seen: dict[str, Manifest] = {}

    def read(chosen: Listed) -> Manifest:
        if chosen.tag not in seen:
            seen[chosen.tag] = fetch_manifest(host, source, chosen, unsigned_local)
        return seen[chosen.tag]
    return read


# ---- what a selector means against what runs: one plan for every verb ----------------------------------------

def entry_note(selector: str, chosen: Listed, target: Listed, then: str) -> str:
    """What a run that stops short of `chosen` says: the selected release, the rule, and the major `then` enters."""
    return (f"{RELEASE_KEY}={selector} selects {chosen.release}, but a new major is entered only from the release "
            f"its manifest names: {then} enters major {target.major + 1}")


class Plan(NamedTuple):
    """What a selector means against what runs (ADR 0096 decision 5, step 1) — one resolution for every verb."""
    source: Source
    listed: list[Listed]
    selector: str
    selection: Listed                            # what the selector selects
    running: str                                 # the release that runs, "" before the first install
    relation: str                                # "fresh", "same", "older" (no downgrade) or "newer"
    target: Listed | None                        # where an upgrade goes now: an entry on the way, else the selection
    manifest_of: Callable[[Listed], Manifest]    # the verified manifest of a release, read once


def plan(host: Host, source_spec: str | None, selector: str, running: str = "", unsigned_local: bool = False) -> Plan:
    """The releases the source lists, what `selector` selects, and — against `running` — where an upgrade goes: never
    below what runs, and into a later major only through the release its manifest names as its entry."""
    source = open_source(source_spec)
    listed = source.releases()
    selection = select(listed, selector, source.describe())
    manifest_of = manifest_reader(host, source, unsigned_local)
    current = tuple(int(x) for x in running.split(".")) if running else None
    if current is None:
        relation, target = "fresh", selection
    elif (selection.major, selection.minor) == current:
        relation, target = "same", None
    elif (selection.major, selection.minor) < current:
        relation, target = "older", None
    else:
        relation = "newer"
        target = upgrade_target(listed, current, selection, source.describe(), lambda r: manifest_of(r).major_entry)
    return Plan(source, listed, selector, selection, running, relation, target, manifest_of)


def say_selection(p: Plan, doing: str = "") -> None:
    """The release a run acts on and where it came from — under a heading of its own when `doing` names one."""
    if doing:
        heading(f"{doing} release {p.selection.release}")
        say(f"Selected by {RELEASE_KEY}={p.selector}, from {p.source.describe()}.")
    else:
        say(f"Release {p.selection.release}, selected by {RELEASE_KEY}={p.selector}, from {p.source.describe()}.")


def say_check(p: Plan, runs: str) -> None:
    """What `check` reports, on every target: what `upgrade` would do, and a newer major beyond the selection.
    `runs` is how the target says a release is in place ("running", "installed")."""
    heading("What `upgrade` would do")
    if p.relation == "same":
        say(f"Nothing: release {p.running} is {runs} already ({RELEASE_KEY}={p.selector}).")
    elif p.relation == "older":
        say(f"Refuse: {RELEASE_KEY}={p.selector} selects {p.selection.release}, older than the {runs} {p.running}.")
    else:
        say(f"Take release {p.target.release}" + (f", {runs} now: {p.running}" if p.running else "")
            + f" ({RELEASE_KEY}={p.selector}).")
        if p.target != p.selection:
            say(entry_note(p.selector, p.selection, p.target, "the `upgrade` after that one") + ".")
    beyond = newer_major(p.listed, p.selection)
    if beyond:
        say(f"A newer major exists: {beyond.release}. Read its release notes before you select it.")


# ---- the programs a command starts: checked before its first question ------------------------------------

# Every program a command starts, with where to get it. Checked before the command's first question, all at once and
# by name (cr-veyqctl-server-path): an operator learns what to install before answering anything, not halfway through.
PROGRAMS = {
    "git": "https://git-scm.com/downloads",
    "docker": "Docker Engine with its Compose plugin, https://docs.docker.com/engine/install/",
    "kubectl": "https://kubernetes.io/docs/tasks/tools/",
    "helm": "https://helm.sh/docs/intro/install/",
    "cosign": f"{COSIGN_MIN_VERSION} or later, https://docs.sigstore.dev/cosign/system_config/installation/",
    "sops": "https://github.com/getsops/sops/releases",
    "kubeseal": "https://github.com/bitnami-labs/sealed-secrets#kubeseal",
    "ssh": "OpenSSH's client, part of macOS and of every Linux distribution",
    "ssh-keyscan": "OpenSSH's client, part of macOS and of every Linux distribution",
    "ssh-keygen": "OpenSSH's client, part of macOS and of every Linux distribution",
    "age-keygen": "age, https://github.com/FiloSottile/age#installation",
    "copier": "https://copier.readthedocs.io/en/stable/#installation",
    "ansible-playbook": "ansible-core 2.18 or later, https://docs.ansible.com/ansible/latest/installation_guide/",
    "ansible-galaxy": "ansible-core 2.18 or later, https://docs.ansible.com/ansible/latest/installation_guide/",
}
# The server path's programs besides the GitOps ones (gh is optional: it enters the deploy key when signed in).
SERVER_PROGRAMS = ("sops", "ssh", "ssh-keyscan", "ssh-keygen", "age-keygen", "copier", "ansible-playbook",
                   "ansible-galaxy")
COMPOSE_VERIFYING = ("install", "upgrade", "verify")  # what verifies a manifest: cosign, unless --unsigned-local


def compose_programs(command: str, unsigned_local: bool) -> list[str]:
    return ["docker"] + (["cosign"] if command in COMPOSE_VERIFYING and not unsigned_local else [])


GITOPS_PROGRAMS = {"init": ("git", "kubectl", "helm", "cosign"), "verify": ("helm", "cosign"),
                   "tls": ("git", "kubectl", "helm"),
                   "check": ("git", "kubectl", "helm", "cosign"), "upgrade": ("git", "helm", "cosign")}


# Homebrew's formula for a program, where it has one (all of them, macOS and Linux, measured 2026-11-10): shown as the
# one command that installs what is missing. veyqctl runs no installer itself (ADR 0126).
BREW = {"git": "git", "kubectl": "kubernetes-cli", "helm": "helm", "cosign": "cosign", "sops": "sops",
        "kubeseal": "kubeseal", "age-keygen": "age", "copier": "copier", "ansible-playbook": "ansible",
        "ansible-galaxy": "ansible"}


def require_programs(host: Host, names: Iterable[str], what: str, wait: bool = False) -> None:
    """Every program of `names` on PATH, cosign at its floor — else refused, naming each missing one and where to get
    it. With `wait` (init, at a terminal) it says so and waits for Enter to look again, rather than stopping."""
    names = list(dict.fromkeys(names))
    while True:
        missing = [name for name in names if not host.which(name)]
        problem = ""
        if missing:
            problem = (f"{what}: {', '.join(missing)} {'is' if len(missing) == 1 else 'are'} not installed. "
                       f"Install:" + "".join(f"\n  {name}: {PROGRAMS[name]}" for name in missing))
        elif "cosign" in names:
            try:
                check_cosign_version(host, host.which("cosign"))
            except Refused as e:
                missing, problem = ["cosign"], str(e)
        if not problem:
            return
        if not wait:
            raise Refused(problem)
        formulas = [BREW[name] for name in missing if name in BREW]
        shown = problem
        if formulas and host.which("brew"):
            verb = "upgrade" if missing == ["cosign"] and host.which("cosign") else "install"
            shown += f"\nWith Homebrew: brew {verb} {' '.join(dict.fromkeys(formulas))}"
        if not host.wait(shown + "\nPress Enter once installed (Ctrl-C to stop; run the same command again later)"):
            raise Refused(shown)


# ---- verification ---------------------------------------------------------------------------------------------

# Nothing unverified is pulled or started (ADR 0057). First the manifest: the publish signs it with `cosign sign-blob`
# into `manifest.json.bundle`, an asset beside it, and `fetch_manifest` — the one entry that reads a manifest from a
# source — runs the host's `cosign verify-blob` on the manifest's exact bytes against that bundle and the one identity
# that releases veyq (GitHub's OIDC issuer and `release.yml` of `raddatzk/veyq` on `main` or a `release/<major>.x`
# branch — the promotion's workflow, never `publish.yml`, whose signature only says "snapshot" (ADR 0091 decision 5) —
# the identity the Run area's *Verify a release* page documents, and `scripts/validate_release_identity.py` assertion
# 7 holds both to what signs) BEFORE a single field of it is read — so no sha256 and no digest in it is trusted unless
# veyq's release signed it, whether it came from GitHub, a mirror or a directory. A missing bundle is refused, and so
# is a cosign older than `COSIGN_MIN_VERSION` (3.1.3: every cosign up to 3.1.2 accepts a forged legacy-format bundle
# without checking its identity, GHSA-fx35-mq7g-6g98). Then the images: the manifest names both by `tag@digest`, and
# each is checked with `cosign verify` against the same identity, before a single image is pulled. `--unsigned-local`
# is the one way past both, for a release produced from a local `docker build`: no bundle is fetched and it is refused
# unless BOTH image references are a bare `name:tag` with no registry host and no digest, refused for an image that is
# not on this host (a bare name would otherwise be pulled from Docker Hub's `library/`), and every line the run prints
# says that nothing was verified.


def require_cosign(host: Host) -> str:
    cosign = host.which("cosign")
    if not cosign:
        raise Refused("cosign is not installed, and veyqctl downloads and starts nothing unverified. Install it: "
                      "https://docs.sigstore.dev/cosign/system_config/installation/")
    return cosign


def check_cosign_version(host: Host, cosign: str) -> None:
    """Refused below `COSIGN_MIN_VERSION`, by name: the manifest's bundle is only as safe as the cosign that reads it.
    Once per run and cosign."""
    if host.cosign_checked == cosign:
        return
    result = host.run([cosign, "version"], check=False)
    found = COSIGN_VERSION.search(f"{result.stdout or ''}\n{result.stderr or ''}")
    have = tuple(int(x) for x in found.groups()) if found else None
    floor = tuple(int(x) for x in COSIGN_MIN_VERSION.split("."))
    if have is None or have < floor:
        shown = ".".join(map(str, have)) if have else "a version `cosign version` does not state"
        raise Refused(f"cosign {shown} is older than {COSIGN_MIN_VERSION}, which veyqctl needs: every cosign up to 3.1.2 "
                      f"accepts a forged signature bundle (GHSA-fx35-mq7g-6g98). Install cosign {COSIGN_MIN_VERSION} "
                      f"or later. Nothing was pulled, written or started.")
    host.cosign_checked = cosign


def verify_manifest(host: Host, cosign: str, raw: bytes, bundle: bytes, what: str,
                    identity: str = COSIGN_IDENTITY_REGEXP, publisher: str = "veyq",
                    bundle_name: str = MANIFEST_BUNDLE_ASSET, shown: str = "") -> None:
    """`cosign verify-blob` of the manifest's exact bytes against its bundle and the one identity that publishes it:
    veyq's release workflow, or (the server path) veyq-server's. `shown` is the checklist's detail (default `what`)."""
    check_cosign_version(host, cosign)
    with tempfile.TemporaryDirectory(prefix="veyqctl-manifest-") as tmp:  # 0700: nobody swaps the blob in between
        blob, sig = Path(tmp) / MANIFEST_ASSET, Path(tmp) / MANIFEST_BUNDLE_ASSET
        blob.write_bytes(raw)
        sig.write_bytes(bundle)
        result = host.run([cosign, "verify-blob", str(blob), "--bundle", str(sig), "--certificate-oidc-issuer",
                           COSIGN_OIDC_ISSUER, "--certificate-identity-regexp", identity], check=False)
    if result.returncode != 0:
        raise Refused(
            f"{what} does not verify against its {bundle_name} and the identity that publishes {publisher} "
            f"({identity}): {(result.stderr or '').strip()[-800:]}\n"
            f"Nothing was pulled, written or started."
        )
    checked("manifest", shown or what)


def cosign_target(role: str, ref: str) -> str:
    """`<name>@sha256:<digest>` for a published reference; refused for anything that is not one."""
    m = DIGEST_REF.match(ref)
    if m:
        return f"{m.group('name')}@{m.group('digest')}"
    if LOCAL_REF.match(ref):
        raise Refused(f"the {role} image `{ref}` is a local build without a digest, so it has no signature to check. "
                      f"To run it anyway, unverified, pass --unsigned-local.")
    raise Refused(f"the {role} image `{ref}` is not pinned by digest "
                  f"(`<registry>/<path>:<tag>@sha256:<digest>`), so it cannot be verified")


def verify_images(host: Host, manifest: Manifest, unsigned_local: bool) -> None:
    """Both images, before anything is pulled: cosign against the identity that publishes veyq, or `--unsigned-local`."""
    refs = {"app": manifest.app, "admin": manifest.admin}
    if unsigned_local:
        foreign = [f"{role} `{ref}`" for role, ref in refs.items() if not LOCAL_REF.match(ref)]
        if foreign:
            raise Refused(
                f"--unsigned-local is only for a release you built locally: both images must be a plain "
                f"`name:tag`, without registry or digest. This release names {' and '.join(foreign)} — run it "
                f"without the flag, and it is verified."
            )
        for role, ref in refs.items():
            if host.run(["docker", "image", "inspect", "--format", "{{.Id}}", ref], check=False).returncode != 0:
                raise Refused(f"the {role} image `{ref}` is not on this computer. veyqctl never downloads a local name (it "
                              f"would come from Docker Hub), so build it first")
            say(f"✗ {role + ' image':<12}  {ref}: a local build, NOT VERIFIED")
        return
    targets = {role: cosign_target(role, ref) for role, ref in refs.items()}
    for role, target in targets.items():
        cosign_verify_ref(host, f"{role} image", target, NOTHING_PULLED)


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
        checked("files", f"in {dest} already, each as the manifest names it")
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
    checked("files", f"downloaded to {dest}, each as the manifest names it")
    return dest


def read_release_manifest(home: Path, release: str) -> Manifest:
    """The manifest of a release on disk: the source's own bytes, kept verbatim when it was fetched."""
    path = release_dir(home, release) / MANIFEST_ASSET
    try:
        return parse_manifest(path.read_bytes(), f"v{release}")
    except OSError as e:
        raise Refused(f"{path} cannot be read: {e}") from None


# ---- .env ------------------------------------------------------------------------------------------------------

# `.env` from the template. `install` copies the release's `.env` template (`env.example`: the tree's
# `infra/docker/.env.example`, published without the leading period a GitHub Release asset cannot keep) line by line:
# the four secrets it knows are generated, the two image lines come from the manifest, and every other value the
# template assigns is the operator's — given with `--set KEY=VALUE`, or asked for at a terminal, the template's value
# shown as the example; without a terminal a missing one is refused, named. A `--set` key the template does not assign
# is kept below it. Written `0600`, in a home created `0700`.


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

# An instance lives in `--home` (default `/opt/veyq`, decision 6):
#
#     .env                   0600, the instance's values, written from the running release's `env.example`
#     compose.override.yml   optional: the operator's own Compose overlay, laid over the recipe on every command
#     state.json             the running release, the Compose project it runs as, the source, how it was verified
#     releases/<release>/    that release's assets, each checked against the sha256 its manifest names
#     backups/               the captures an upgrade or `backup` takes: the database and the store's index each
#
# Every Compose command is `docker compose -p <project> -f releases/<release>/docker-compose.prod.yml [-f
# compose.override.yml] --env-file .env`: the override file is Compose's own extension point, so an operator's
# additions (a port, a network, a CA) survive an upgrade instead of being edited into a recipe the next release
# replaces. `.env` keeps every key the template does not name, for the same reason. The project is what Compose names
# at `install` — the recipe's `name:`, or a `COMPOSE_PROJECT_NAME` in `.env` — recorded in `state.json` and passed by
# name from then on; a `COMPOSE_*` variable in the operator's shell is dropped from every command veyqctl runs, and
# every command runs in the home, so neither a shell variable nor a compose file in the current directory can point it
# at another stack.


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
        raise Refused(f"{home} holds no instance ({STATE_FILE} is missing): run `veyqctl init` first, or pass --home") from None
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
        say(f"No container of {project} is running.")
        return False
    width = max(len(r.service) for r in rows)
    for r in rows:
        say(f"{'✓' if r.ok else '✗'} {r.service:<{width}}  {r.shown}{'' if r.ok else '   <- NOT HEALTHY'}")
    healthy = all(r.ok for r in rows)
    say("Every service is healthy." if healthy else "NOT every service is healthy: `veyqctl logs` shows why.")
    return healthy


def up(host: Host, base: list[str], project: str, *extra: str) -> None:
    result = host.run([*base, "up", "-d", "--wait", "--wait-timeout", str(WAIT_SECS), *extra], check=False, stream=True)
    healthy = report(host, project)
    if result.returncode != 0 or not healthy:
        raise Refused(f"veyq is not healthy (`up --wait` exited {result.returncode}): `veyqctl logs` shows why")


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
            raise Refused(f"{home} already holds an instance ({existing} exists): `veyqctl upgrade` updates it")
    given = parse_sets(args.set)
    selector = check_selector(given.get(RELEASE_KEY, "latest"))
    spec = source_spec(args, home)
    p = plan(host, spec, selector, unsigned_local=args.unsigned_local)
    source, chosen = p.source, p.selection
    say_selection(p, "Installing")
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
        raise Refused(f"the release needs {', '.join(missing)}, which have no value yet: pass "
                      + " ".join(f"--set {k}=…" for k in missing))
    heading("Configuration")
    write_private(home / ENV_FILE, render_env(template, values))
    say(f"Created {home / ENV_FILE}, readable by you only, and generated "
        f"{', '.join(k for k in SECRETS if k not in given)}.")
    unnamed = compose(home, manifest.release)
    host.run([*unnamed, "config", "--quiet"])
    project = project_name(host, unnamed)
    write_state(home, State(manifest.release, project, spec, args.unsigned_local))
    heading(f"Starting veyq {manifest.release}")
    up(host, compose(home, manifest.release, project), project)
    address = admin_address(host, project)
    heading(f"veyq {manifest.release} is running (project {project})")
    if address:
        say(f"Next: create the Server Owner in the admin console at {address}, from your computer through `ssh -L`.")
    pool_warning(host, home, read_state(home))
    return 0


def cmd_start(args: argparse.Namespace, host: Host) -> int:
    home = Path(args.home)
    state = read_state(home)
    heading(f"Starting veyq {state.release}")
    up(host, compose(home, state.release, state.project), state.project)
    try:
        prune(host, home, state, backup_tool(host, home, state, store=False), required=False)
    except Refused as e:  # the tool itself could not be settled: said, the start stands
        say(f"WARNING: the backups older than the retention period were not deleted ({str(e).splitlines()[0][:200]}).")
    return 0


def cmd_stop(args: argparse.Namespace, host: Host) -> int:
    state = read_state(Path(args.home))
    heading(f"Stopping veyq {state.release}")
    host.run([*by_project(state.project), "stop"], stream=True)
    say("Stopped. `veyqctl start` starts it again.")
    return 0


def cmd_status(args: argparse.Namespace, host: Host) -> int:
    home = Path(args.home)
    state = read_state(home)
    heading(f"veyq {state.release}" + (", installed with --unsigned-local: NOT VERIFIED" if state.unsigned_local else "")
            + f" (project {state.project})")
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
    p = plan(host, source_spec(args, home), selector, unsigned_local=args.unsigned_local)
    chosen = p.selection
    say_selection(p, "Verifying")
    manifest = fetch_manifest(host, p.source, chosen, args.unsigned_local)
    verify_images(host, manifest, args.unsigned_local)
    say("NOT VERIFIED (--unsigned-local)." if args.unsigned_local
        else f"Release {chosen.release} is veyq's, as signed. Nothing was downloaded.")
    return 0


def cmd_check(args: argparse.Namespace, host: Host) -> int:
    home = Path(args.home)
    try:
        running = read_state(home).release
    except Refused:
        running = ""
    say_check(plan(host, source_spec(args, home), selected(args, home), running, args.unsigned_local), "running")
    return 0


# ---- the running release's backup tool (ADR 0056) -------------------------------------------------------------

# `backup` and `restore` are the running release's own `instance_backup.py` — shipped beside `veyqctl` as an asset,
# checked against its manifest's sha256 before it runs — so the tool that captured is the tool its release documents.
# veyqctl gives it what the operator gave it by hand: the database password, the bundled store's endpoint and admin
# token (read from the instance's interpolated recipe, so through the environment, never an argv), the store's
# network, and Compose pointed at THIS instance (`COMPOSE_PROJECT_NAME`, `COMPOSE_ENV_FILES`). A store of the
# operator's own (`VEYQ_BACKUP_STORE_*` in the shell) passes through, as the tool documents. Where
# `compose.override.yml` leaves the bundled store out (no `garage` in the composed recipe, ADR 0064), that shell pair
# is the only one: the rclone container runs on Docker's default network, no bundled pair is passed, and a run that
# reaches the store is refused before anything stops while `VEYQ_BACKUP_STORE_ENDPOINT` / `_ACCESS_KEY` /
# `_SECRET_KEY` are not set. Leg (c) lives on a volume under Docker's data root, which a host cannot always read
# (Docker Desktop keeps it in a VM) and which needs root where it can: so the key comes out of the stopped app
# container with `docker cp`, into a `0700` directory in the home for as long as the tool needs it, and a restored key
# goes back in through a one-off container of the `app` service running as the image's own user — born the
# application's, `0600`, without root (the alternative `instance_backup.py`'s own refusal names). `--key-into` is
# required for `backup` and `upgrade`, and refused when it exists or lies inside `backups/`.
#
# A capture holds the database and the store's index, not the objects (ADR 0056 §3). The objects stay in the store; an
# object pool keeps copies only when one is named — `.env`'s `VEYQ_BACKUP_OBJECTS_TO` (an absolute directory, never
# inside `releases/`, or `s3:<bucket>[/<prefix>]` with `VEYQ_BACKUP_POOL_*` beside it, which reach the tool through
# its environment), or `--objects-to` for one run of `backup` or `upgrade`. With a pool, both run the tool's `copy-
# objects` before the app stops, so the cold top-up is short, and the capture with `--objects-to`. `restore` writes
# objects only when told where from (`--objects-from`, never implied by `.env`): without it the tool checks every
# object the rows require in the live store and writes none — the way back from an upgrade; with it, onto a fresh
# instance's empty store. `status`, and `install` at its end, warn while the store in use (the tool's `store-in-use`)
# is the bundled one — or none yet — and no pool is named: Garage keeps no object versions, so a lost store volume
# loses every object whatever `backups/` holds. The warning never changes an exit code.
#
# Every capture is kept at most B days (ADR 0056 decision 8): B is the instance setting the Server Owner sets in
# admin-web or with `veyq-admin backup-retention set` (30 until set), and the running release's tool reads it from the
# database (`instance_backup.py prune`, 30 when it cannot). `backup`, `upgrade` and `start` prune every capture under
# `backups/` older than B, the newest included; `backup prune` does only that, for the daily timer the Run page gives;
# and `status` warns while no capture younger than B is there (the tool's `retention`), never changing its exit code.
# A copy moved off the host is the operator's to delete by B.
#
# The erasure ledger travels with leg (c) (ADR 0056 decision 8): `erasures.log`, beside the key in the app's volume,
# which no restore touches. `restore` and `rollback` read it out of that volume through a one-off container of the
# `app` service — as they put the key in — and hand it to the tool (`--erasures`, `verify --against-store` too), so an
# object erased after the capture reads as erased, not missing; with none there the check runs as before and the tool
# says so. The application re-applies the ledger itself at its next start, before it serves.


class Facts(NamedTuple):
    """What the backup tool needs, read from the instance's own interpolated recipe — never from key names here."""
    key_path: str              # where the app keeps leg (c), inside its container
    network: str               # the network the store is on, for the tool's rclone container
    store_endpoint: str | None  # the bundled store, as that container reaches it; None: the recipe runs no Garage
    store_token: str | None     # the bundled store's admin token; the tool derives the S3 key pair from it
    db_password: str           # the database's root password, for the dump and the load


# The store of the operator's own, as the backup tool reads it from its environment — the pair the console holds is
# sealed under the KEK the backup never reads (ADR 0089 decision 7), so with no Garage in the recipe it comes from the
# operator's shell or nowhere. (The bucket and region have the tool's defaults.)
OWN_STORE_KEYS = ("VEYQ_BACKUP_STORE_ENDPOINT", "VEYQ_BACKUP_STORE_ACCESS_KEY", "VEYQ_BACKUP_STORE_SECRET_KEY")
# Docker's default network: where the rclone container runs when the recipe has no store of its own to join, and
# reaches the operator's store like any other host.
DEFAULT_NETWORK = "bridge"


def stack_facts(host: Host, base: list[str]) -> Facts:
    doc = json.loads(host.run([*base, "config", "--format", "json"]).stdout)
    services = doc.get("services") or {}

    def env(service: str, key: str) -> str:
        value = ((services.get(service) or {}).get("environment") or {}).get(key)
        if not value:
            raise Refused(f"the release's compose file gives `{service}` no {key}, which the backup needs: this veyqctl "
                          f"cannot back up this release")
        return str(value)

    if "garage" not in services:
        # The bundled store left out (ADR 0064: the instance's compose.override.yml disables it and blanks the hint),
        # so the composed config lists no `garage`: the store is the operator's own, reached on Docker's default
        # network, and no bundled pair is passed. Its pair comes from the shell ([backup_tool] refuses without it).
        return Facts(env("app", "VEYQ_SECURITY_CONFIG_SECRET_FILE"), DEFAULT_NETWORK, None, None,
                     env("mariadb", "MARIADB_ROOT_PASSWORD"))
    store_networks = list((services.get("garage") or {}).get("networks") or {})
    if not store_networks:
        raise Refused("the release's compose file puts the store (`garage`) on no network, so the backup cannot reach it")
    network = ((doc.get("networks") or {}).get(store_networks[0]) or {}).get("name") or store_networks[0]
    return Facts(env("app", "VEYQ_SECURITY_CONFIG_SECRET_FILE"), network, env("app", "VEYQ_BUNDLED_STORE_ENDPOINT"),
                 env("app", "VEYQ_BUNDLED_STORE_ADMIN_TOKEN"), env("mariadb", "MARIADB_ROOT_PASSWORD"))


def backup_env(home: Path, state: State, facts: Facts) -> dict[str, str]:
    """The tool's environment: its credentials (never an argv), and Compose pointed at THIS instance.

    The tool runs `docker compose -f <recipe> exec …` with no `-p` and no `--env-file`; the project and the `.env`
    reach it through Compose's own variables, so it addresses this instance and interpolates the recipe with its
    values. A store of the operator's own (`VEYQ_BACKUP_STORE_*` in the shell) passes through and wins, as the tool
    documents. With no Garage in the recipe no bundled pair is passed at all.
    """
    bundled = ({"VEYQ_BUNDLED_STORE_ENDPOINT": facts.store_endpoint, "VEYQ_BUNDLED_STORE_ADMIN_TOKEN": facts.store_token}
               if facts.store_endpoint and facts.store_token else {})
    return {"VEYQ_BACKUP_DB_PASSWORD": facts.db_password, **bundled, "COMPOSE_PROJECT_NAME": state.project,
            "COMPOSE_ENV_FILES": str(home / ENV_FILE)}


def verified_asset(home: Path, release: str, name: str) -> Path:
    """A file of a release on disk, refused unless it is still the one its manifest names — it is about to run."""
    manifest = read_release_manifest(home, release)
    path = release_dir(home, release) / name
    want = manifest.assets.get(name)
    if not want or not path.is_file() or sha256(path.read_bytes()) != want:
        raise Refused(f"{path} is not the {name} release {release}'s manifest names: a file of an installed "
                      f"release was changed on disk, so veyqctl does not run it")
    return path


def key_destination(home: Path, raw: str | None, why: str) -> Path:
    """`--key-into`: required, new, on an existing directory, and never beside the captures (ADR 0056 §1)."""
    if not raw:
        raise Refused(f"{why} needs --key-into FILE: where the config-secret key goes. Keep it apart from the backup "
                      f"— together they reveal every tenant's SMTP password. Nothing was stopped.")
    path = Path(raw).expanduser().absolute()
    # Not `path.exists()`: since Python 3.14 that answers False for a path it cannot look at, and this check would pass
    # a key it cannot see, for the capture to trip over after the app has stopped. The 3.13 rule, spelled out.
    try:
        path.stat()
        taken = True
    except OSError as e:
        if e.errno not in (errno.ENOENT, errno.ENOTDIR, errno.EBADF, errno.ELOOP):
            raise Refused(f"--key-into {path}: veyqctl cannot look into its directory ({e.strerror}) — name one it can "
                          f"write. Nothing was stopped.") from None
        taken = False
    if taken:
        raise Refused(f"--key-into {path} exists — most likely an earlier capture's key; name a new file")
    if not path.parent.is_dir():
        raise Refused(f"--key-into {path}: its directory does not exist")
    backups = (home / BACKUPS_DIR).absolute()
    if path == backups or backups in path.parents:
        raise Refused(f"--key-into {path} is inside {backups}, next to the backups — the one place the key must not go")
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


def require_own_store_pair(facts: Facts) -> None:
    """With no Garage in the recipe, a run that reaches the store needs the operator's own pair in this shell (ADR 0064
    § backup and restore take the own store's pair) — refused here, before anything stops, naming what is missing."""
    if facts.store_endpoint is not None:
        return
    absent = [key for key in OWN_STORE_KEYS if not os.environ.get(key, "").strip()]
    if absent:
        raise Refused(f"this instance uses a store of your own (compose.override.yml leaves `garage` out), and the backup "
                      f"reaches it with the endpoint and keys from this shell — but {', '.join(absent)} "
                      f"{'is' if len(absent) == 1 else 'are'} not set (the console's copy is encrypted). Export them "
                      f"and run it again. Nothing was stopped.")


def backup_tool(host: Host, home: Path, state: State, *, store: bool = True) -> BackupTool:
    """The running release's tool, checked, and what it needs — all of it settled BEFORE the app stops. `store=False`
    for a run that reads only the database (the prune, `status`'s readings), which needs no store pair."""
    facts = stack_facts(host, compose(home, state.release, state.project))
    if store:
        require_own_store_pair(facts)
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
    heading("Copying photos and files into the pool")
    say(f"Into {pool.target}, while veyq keeps running.")
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
    checked("backup", f"{target}: the database and the store's index")
    checked("key", f"{key_into}: the config-secret key")
    say("Keep the two apart, and encrypt both before they leave this server. "
        + (f"Photos and files are in the pool {pool.target}." if pooled else "Photos and files stay in the store only."))
    return target


BUNDLED_WARNING = (
    "WARNING: your photos and files are only in the bundled store, which keeps no older versions, and no pool is set "
    f"({POOL_TARGET_KEY} in .env). A backup holds the database and the store's index, not the files themselves: if "
    "the store's volume is lost, every photo and file is gone. Set a pool on another disk — "
    f"{POOL_TARGET_KEY}=/path/on/another/disk (or s3:<bucket> with VEYQ_BACKUP_POOL_*) — and every backup and upgrade "
    "copies new files into it: https://veyq.app/run/backup-and-restore/")


def pool_warning(host: Host, home: Path, state: State) -> None:
    """ADR 0056 §3 (the bundled store keeps no history of its own): say it while it has no pool. Never changes an exit
    code — `status`'s is the health verdict. `none` (no store chosen yet) warns the same: the first store an instance
    gets is the bundled one by default. A store of the operator's own is theirs to keep durable, and is silent."""
    if pool_choice(home, None, POOL_TARGET_KEY).target:
        return
    try:
        tool = backup_tool(host, home, state, store=False)
        in_use = host.run([sys.executable, str(tool.path), "store-in-use", "--compose-file",
                           str(release_dir(home, state.release) / COMPOSE_ASSET)], env=tool.env).stdout.strip()
    except Refused as e:
        say(f"The store setting in use could not be read ({str(e).splitlines()[0][:200]}).")
        in_use = "unreadable"
    if in_use != "own":
        heading("Your photos and files")
        say(f"Store in use: {in_use or 'none set'}.")
        say(BUNDLED_WARNING)


# The app image's own commands (ADR 0118): the image is distroless, so a one-off container of the `app` service runs
# them on the image's `java` — there is no shell to run anything else. `src/backend/image/ImageCommands.java`.
IMAGE_COMMANDS = ["--entrypoint", "java", "app", "-jar", "/opt/veyq/image-commands.jar"]


def place_key(host: Host, home: Path, state: State, key: Path, key_path: str) -> None:
    """Leg (c) into the app's volume, written by the app image's own user through a one-off container of the service.

    The restored key must be the application's (uid 10001, `0600`) — a key born `root:root` there is one the app
    cannot read (inbox #188). A host path into the volume needs root and a Docker whose data root the host can see;
    a container of the service itself, run as the image's user, needs neither. The key travels on stdin, never argv,
    into a sibling born `0600` and renamed over the key (the image's `place-key`).
    """
    host.run([*compose(home, state.release, state.project), "run", "--rm", "--no-deps", "-T", *IMAGE_COMMANDS,
              "place-key", key_path], stdin=key.read_bytes())


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
        say(f"WARNING: the backups older than the retention period were not deleted ({str(e).splitlines()[0][:200]}); "
            f"the next `backup`, `upgrade`, `start` or `backup prune` tries again.")


def retention_warning(host: Host, home: Path, state: State) -> None:
    """`status` (ADR 0056 decision 8): say it while no capture under `backups/` is younger than B — admin-web cannot see
    the captures. Never changes an exit code."""
    try:
        tool = backup_tool(host, home, state, store=False)
        raw = host.run([sys.executable, str(tool.path), "retention", "--captures", str((home / BACKUPS_DIR).absolute()),
                        "--compose-file", str(release_dir(home, state.release) / COMPOSE_ASSET)], env=tool.env).stdout
        answer = json.loads(raw.strip().splitlines()[-1])
    except (Refused, ValueError, IndexError) as e:
        say(f"The retention period and the backups could not be read "
            f"({str(e).splitlines()[0][:200] if str(e) else type(e).__name__}).")
        return
    if not answer.get("fresh"):
        newest = answer.get("youngest")
        heading("Backups")
        say(f"WARNING: no backup in {home / BACKUPS_DIR} is younger than the retention period ({answer.get('days')} "
            f"days" + (f"; the newest, {newest}, is from {answer.get('youngestAt')}" if newest else "; there is none")
            + "). Older backups are deleted, so this instance has none to restore. Run `veyqctl backup` — the docs "
              "show a daily timer for it: https://veyq.app/run/backup-and-restore/")


def read_ledger(host: Host, home: Path, state: State, key_path: str, into: Path) -> Path | None:
    """The erasure ledger out of the app's volume — `erasures.log` beside the key, which no restore touches — through a
    one-off container of the `app` service, as `place_key` writes the key in. The file at [into], or None when the
    volume holds none (an instance older than the ledger, or none written yet): the tool then checks as before."""
    ledger = str(Path(key_path).parent / LEDGER_FILE)
    result = host.run([*compose(home, state.release, state.project), "run", "--rm", "--no-deps", "-T", *IMAGE_COMMANDS,
                       "read-file", ledger], check=False)
    if result.returncode != 0:
        say(f"No erasure ledger at {ledger} in the app's volume: the restore checks the objects without it.")
        return None
    into.write_text(result.stdout or "")
    os.chmod(into, 0o600)
    return into


# ---- upgrade (ADR 0096 decision 5) ------------------------------------------------------------------------------

# `upgrade` runs only by hand, in ADR 0096 decision 5's order, and nothing stops before step 4:
#   1. resolve the release `.env`'s `VEYQ_RELEASE` selects; nothing to do when it is the running one, refused when
#      it is an older one (there is no downgrade: going back is restoring a backup, ADR 0056 §6); one beyond the
#      running major is entered from its predecessor's last release, one major per run (above), and a run that stops
#      on the way ends by saying that the next `upgrade` enters the next major;
#   2. verify its manifest, then both images (as `install` does), then fetch its assets into `releases/<release>/`;
#   3. fill the new template from `.env` (`upgrade_values`): the image lines are the new release's, a key `.env` has
#      keeps its value, a secret veyqctl knows how to make is made — and every other key the new template assigns,
#      and every `${VAR:?…}` the new recipe or the override leaves unset, is refused, named; the result is staged as
#      `.env.next` and Compose itself checks it (`config --quiet`);
#   4. with an object pool named, copy the store's objects into it while the app still runs (`copy-objects`; a
#      failure stops nothing); stop the app (the capture is cold, ADR 0056 §2: the database and the store keep
#      running), back up the three legs into `backups/<stamp>-before-<release>/` — the database and the store's
#      index, the pool topped up with what arrived since and pruned to its kept captures — and check the capture,
#      then switch `.env` and the recipe, `up`, and wait until healthy. A failed backup switches nothing and starts
#      the running release again. `--key-into` is required and refused before anything else runs. The previous
#      release's directory stays, so the diff and the way back are both on disk.


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
    p = plan(host, spec, selector, state.release, args.unsigned_local)
    source, selection = p.source, p.selection
    if p.relation == "same":
        heading(f"veyq {state.release}: nothing to upgrade")
        say(f"Release {selection.release} is running already ({RELEASE_KEY}={selector}).")
        return 0
    if p.relation == "older":
        raise Refused(f"{RELEASE_KEY}={selector} selects {selection.release}, older than the running {state.release}. "
                      f"There is no downgrade: to go back, restore a backup taken under that release "
                      f"(`veyqctl rollback`). Nothing was stopped.")
    manifest_of, target = p.manifest_of, p.target
    heading(f"Upgrading veyq {state.release} to {target.release}")
    say(f"Step 1 of 4: release {target.release}, selected by {RELEASE_KEY}={selector}."
        + (f" {selection.release} is in a later major, which is entered only from the release its manifest names."
           if target != selection else ""))

    # 2. its manifest verified, both images verified by the manifest's digests, then its assets into releases/<release>/
    heading(f"Step 2 of 4: verifying and downloading {target.release}")
    manifest = manifest_of(target)
    verify_images(host, manifest, args.unsigned_local)
    directory = fetch_release(source, target, manifest, home)

    # 3. refuse if the new template requires a value .env lacks, naming it
    template = (directory / TEMPLATE_ASSET).read_text()
    values, missing = upgrade_values(template, manifest, old)
    missing += [k for k in unset_required(values, (directory / COMPOSE_ASSET).read_text(), override_text(home))
                if k not in missing]
    if missing:
        raise Refused(f"release {manifest.release} needs {', '.join(missing)}, which {home / ENV_FILE} does not set: "
                      f"add " + ", ".join(f"`{k}=…`" for k in missing) + " to it (the new "
                      f"{directory / TEMPLATE_ASSET} explains each) and run `upgrade` again. Nothing was stopped.")
    staged = home / f"{ENV_FILE}.next"
    write_private(staged, render_env(template, values))
    try:
        named = ["docker", "compose", "-p", state.project, *compose_files(home, manifest.release), "--env-file", str(staged)]
        host.run([*named, "config", "--quiet"])
    except Refused:
        staged.unlink()
        raise
    heading("Step 3 of 4: configuration")
    checked(".env", f"{home / ENV_FILE} has everything release {manifest.release} needs")
    try:
        tool = backup_tool(host, home, state)
        pool = pool_choice(home, args.objects_to, "--objects-to")
        if pool.target:
            copy_objects_early(host, home, state, tool, pool)
    except Refused:
        staged.unlink()
        raise

    # 4. back up; stop the app; switch to the new release's recipe and image lines; up; wait until healthy
    heading(f"Step 4 of 4: backing up, then switching to {manifest.release}")
    stopped = time.monotonic()
    stop_app(host, home, state)
    try:
        backup = capture(host, home, state, tool, key_into, f"{utc_stamp()}-before-{manifest.release}", pool)
    except Refused:
        staged.unlink()
        say(f"The backup failed, so nothing was switched; starting release {state.release} again.")
        up(host, compose(home, state.release, state.project), state.project)
        raise
    prune(host, home, state, tool, required=False)
    os.replace(staged, home / ENV_FILE)
    write_state(home, state._replace(release=manifest.release, source=spec, unsigned_local=args.unsigned_local))
    say(f"Switched to release {manifest.release}.")
    try:
        up(host, compose(home, manifest.release, state.project), state.project)
    except Refused as e:
        raise Refused(f"{e}\nRelease {manifest.release} is switched in but not healthy. To go back to release "
                      f"{state.release} (still in {release_dir(home, state.release)}): "
                      f"`veyqctl rollback {backup} --key-from {key_into}`.") from None
    heading(f"veyq {manifest.release} is running")
    say(f"Downtime: {time.monotonic() - stopped:.0f} s, from stopping veyq until release {manifest.release} was healthy.")
    say(f"Release {state.release} stays in {release_dir(home, state.release)} for a rollback.")
    if target != selection:
        say(entry_note(selector, selection, target, "the next `upgrade`") + ".")
    return 0


# ---- backup, restore, self-update ---------------------------------------------------------------------------------

# `rollback` is in place (ADR 0096, the owner's O1 (a) of 2026-10-17): after a failed upgrade, the capture it began
# with holds the database and the index, and its objects are still in this instance's store, which an upgrade never
# rewrites. So `rollback <capture> --key-from F` refuses unless the capture's release is still in `releases/` and the
# capture verifies against the live store, and asks before it empties the database (`--yes` without a terminal); then
# it stops the app, empties the database (the tool's `empty-database`), switches `.env`'s image lines and `state.json`
# back to that release (`upgrade_values` over its template), starts it once so it migrates the empty schema, stops it,
# restores with the check only, places the key and starts. The store is never written; objects uploaded under the
# failed release stay as orphans with no reclaim row, which the app's bucket reconcile counts and — after its grace,
# once the Server Owner confirms the bucket again (the restore emptied that confirmation) — puts on the reclaim list
# (ADR 0062).
#
# `self-update` replaces this file with the installed release's `veyqctl` after checking it against that release's
# manifest — and, because it replaces the verifier itself, it first verifies that manifest on disk against the
# `manifest.json.bundle` kept beside it, with the same `cosign verify-blob`; a manifest edited in the home, or one
# whose bundle is gone, is refused and nothing is replaced. A release installed `--unsigned-local` has no bundle, and
# `self-update` says it is NOT VERIFIED. `veyqctl.bundle`, the publish's signature of `veyqctl` alone, is for the copy
# an operator fetches by hand, before any manifest is checked; `install` and `upgrade` do not fetch it, since the
# signed manifest binds `veyqctl` by its sha256 like every other asset.


def cmd_backup(args: argparse.Namespace, host: Host) -> int:
    home = Path(args.home)
    if args.action == "prune":
        # ADR 0056 decision 8: only the age prune, for a daily timer — nothing stops, no key is needed.
        state = read_state(home)
        prune(host, home, state, backup_tool(host, home, state, store=False), required=True)
        return 0
    key_into = key_destination(home, args.key_into, "`backup`")
    state = read_state(home)
    tool = backup_tool(host, home, state)
    pool = pool_choice(home, args.objects_to, "--objects-to")
    if pool.target:
        copy_objects_early(host, home, state, tool, pool)
    heading(f"Backing up veyq {state.release}")
    stopped = time.monotonic()
    stop_app(host, home, state)
    try:
        capture(host, home, state, tool, key_into, utc_stamp(), pool)
        prune(host, home, state, tool, required=False)
    finally:
        heading(f"Starting veyq {state.release} again")
        up(host, compose(home, state.release, state.project), state.project)
        say(f"Downtime: {time.monotonic() - stopped:.0f} s, from stopping veyq until every service was healthy again.")
    return 0


# What a restore leaves to the Server Owner to check (ADR 0056 § 5): what tells a restore from a hope.
AFTER_RESTORE = ("Now check it: as the Server Owner, the admin console's Server → Config-secret key must say OK; look at "
                 "the store; and open a Memory with photos.")


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
    heading(f"Restoring {backup.name} onto veyq {state.release}")
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
            say("The restore did not finish, and veyq stays STOPPED. The output above says whether anything was "
                "written: if it refused at a check, nothing was, and `veyqctl start` brings veyq back as it was.")
            raise
        if key.is_file():
            place_key(host, home, state, key, tool.facts.key_path)
            checked("key", f"the config-secret key placed at {tool.facts.key_path} in the app's volume")
    heading(f"Starting veyq {state.release}")
    up(host, base, state.project)
    heading("Restored")
    say(AFTER_RESTORE)
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
        raise Refused(f"the backup was taken under release {target}, which is no longer on disk "
                      f"({release_dir(home, target)}). Install that release as a new instance and restore onto it; "
                      f"the backup stays in {BACKUPS_DIR}/. Nothing was stopped.")
    key_from = Path(args.key_from).absolute()
    if not key_from.is_file():
        raise Refused(f"--key-from {key_from} is not a file. Nothing was stopped.")
    domains = manifest.get("domains") or []
    domain = domains[0] if domains else unquoted(env_values((home / ENV_FILE).read_text()).get("VEYQ_DOMAIN", ""))

    # Everything that can refuse, before anything stops: the capture's release's tool, the template it needs, and
    # the capture against the live store — the in-place rollback writes no object, so every one must be there.
    running = compose(home, state.release, state.project)
    facts = stack_facts(host, running)
    require_own_store_pair(facts)
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
    heading(f"Checking {capture_dir.name} against the store")
    try:
        host.run(["docker", "compose", "-p", state.project, *compose_files(home, target), "--env-file", str(staged),
                  "config", "--quiet"])
        host.run([sys.executable, str(tool.path), "verify", str(capture_dir), "--key-from", str(key_from),
                  "--against-store", "--docker-network", facts.network, *erasures], env=tool.env, stream=True)
        checked("backup", "it matches the store: every file it needs is there")
        sure = True if args.yes else host.confirm(
            f"EMPTY the database of {state.project} (release {state.release}) and restore {capture_dir.name} under "
            f"release {target}? This cannot be undone")
        if sure is None:
            raise Refused("the rollback empties the database: without a terminal to confirm it, pass --yes. Nothing "
                          "was stopped.")
        if not sure:
            raise Refused("not confirmed. Nothing was stopped.")
    except Refused:
        staged.unlink()
        shutil.rmtree(ledger_dir, ignore_errors=True)
        raise

    heading(f"Rolling back to release {target}")
    stop_app(host, home, state)
    host.run([sys.executable, str(tool.path), "empty-database", "--yes", "--compose-file",
              str(release_dir(home, state.release) / COMPOSE_ASSET)], env=tool.env, stream=True)
    os.replace(staged, home / ENV_FILE)
    back = state._replace(release=target)
    write_state(home, back)
    say(f"The database is empty and release {target} is selected; starting it once so it creates the empty tables.")
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
            say(f"The restore did not finish: release {target} has an EMPTY database and veyq is STOPPED. The output "
                f"above says why. Fix it, then run `veyqctl restore {capture_dir} --domain {domain} --key-from {key_from}`.")
            raise
        if key.is_file():
            place_key(host, home, back, key, facts.key_path)
    shutil.rmtree(ledger_dir, ignore_errors=True)
    up(host, base, state.project)
    heading(f"Rolled back to release {target}, from {capture_dir.name}")
    say(f"Release {state.release} stays in {release_dir(home, state.release)}. VEYQ_RELEASE in .env is unchanged, so "
        f"the next `upgrade` moves forward again: set it to {target} to stay.")
    say(AFTER_RESTORE)
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
            raise Refused(f"release {state.release} on disk has no manifest that verifies ({e}), so its `veyqctl` cannot "
                          f"be trusted. Nothing was replaced.") from None
        heading(f"Verifying release {state.release} on disk")
        verify_manifest(host, require_cosign(host), raw, bundle, f"the manifest of release {state.release} in {directory}",
                        shown=f"release {state.release}, in {directory}")
    asset = verified_asset(home, state.release, SELF_ASSET)
    target = SELF.resolve()
    if target == asset.resolve():
        say(f"{target} is release {state.release}'s own copy: nothing to replace.")
        return 0
    data = asset.read_bytes()
    if target.is_file() and target.read_bytes() == data:
        say(f"{target} is release {state.release}'s veyqctl already: nothing to replace.")
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
    heading(f"veyqctl is now release {state.release}'s")
    say(f"{target}, sha256 {sha256(data)[:16]}…, as its manifest names."
        + (" That release was installed --unsigned-local: NOT VERIFIED." if state.unsigned_local else ""))
    return 0


# ---- instances: which one a command acts on (ADR 0126 decision 2) ----------------------------------------------

# Which instance (ADR 0126 decision 2): `--home DIR` names one by its path; else `--instance NAME` one listed in
# `~/.config/veyq/instances.json` (`$XDG_CONFIG_HOME/veyq` if set), which `init` writes and `use` switches; else the
# current one there; else `/opt/veyq` — so a host with one Compose instance needs no list, and a run under `sudo`,
# whose home differs, still finds it. The TARGET is read from the instance, never given: a directory holding
# `veyqctl.json` is a GitOps instance, anything else a Compose home. `init` asks which to create (or takes `--compose`
# / `--gitops DIR`).


GITOPS_FILE = "veyqctl.json"  # a GitOps instance's own file: target, selector, secrets provider, paths, pins
REGISTRY_FILE = "instances.json"  # in config_dir(): the instances a user manages, and which is current
INSTANCE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$")


def config_dir() -> Path:
    return Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config") / "veyq"


def check_instance_name(name: str) -> str:
    if not INSTANCE_NAME.match(name or ""):
        raise Refused(f"instance name {name!r}: letters, digits, '.', '_' and '-', starting with a letter or digit")
    return name


def read_registry() -> dict:
    path = config_dir() / REGISTRY_FILE
    if not path.is_file():
        return {"current": None, "instances": {}}
    try:
        doc = json.loads(path.read_text())
    except (OSError, ValueError) as e:
        raise Refused(f"{path} cannot be read as veyqctl's list of instances: {e}") from None
    instances = doc.get("instances") if isinstance(doc, dict) else None
    if not isinstance(instances, dict) or not all(isinstance(k, str) and isinstance(v, str) for k, v in instances.items()):
        raise Refused(f"{path} is not veyqctl's list of instances (`instances`: name → path)")
    current = doc.get("current")
    return {"current": current if current in instances else None, "instances": dict(instances)}


def write_registry(registry: dict) -> None:
    directory = config_dir()
    directory.mkdir(parents=True, exist_ok=True)
    (directory / REGISTRY_FILE).write_text(json.dumps(registry, indent=2, sort_keys=True) + "\n")


def target_of(path: Path) -> str:
    """The instance's target, read from the instance (ADR 0126 decision 2): its own file says GitOps; anything else —
    a Compose home, or a home not created yet — is Compose. `TARGETS[target_of(home)]` acts on it."""
    return "gitops" if (path / GITOPS_FILE).is_file() else "compose"


def name_is_free(name: str, path: Path) -> None:
    """Refused while the name lists another instance that is still there; one whose directory is gone (moved or
    deleted) gives its name up — `register` then lists this one under it."""
    listed = read_registry()["instances"].get(check_instance_name(name))
    if listed is None or Path(listed) == path:
        return
    if not Path(listed).exists():
        say(f"The instance named {name} was at {listed}, which no longer exists: this one takes its name.")
        return
    raise Refused(f"there is already an instance named {name} ({listed}): choose another name with --name")


def register(name: str, path: Path) -> None:
    name_is_free(name, path)
    registry = read_registry()
    registry["instances"][name] = str(path)
    registry["current"] = name
    write_registry(registry)
    say(f"{name} is now this computer's current instance (`veyqctl instances` lists them).")


def instance_label(path: Path) -> str:
    """How a heading names an instance: its name on this computer, else its directory."""
    for name, listed in read_registry()["instances"].items():
        if Path(listed).resolve() == path.resolve():
            return name
    return str(path)


def resolve_home(args: argparse.Namespace) -> Path:
    """Which instance a command acts on: `--home` (a path), else `--instance`, else the current one, else /opt/veyq."""
    if args.home:
        return Path(args.home)
    registry = read_registry()
    if args.instance:
        listed = registry["instances"].get(args.instance)
        if listed is None:
            raise Refused(f"there is no instance named {args.instance}: `veyqctl instances` lists them")
        return Path(listed)
    if registry["current"]:
        return Path(registry["instances"][registry["current"]])
    return Path(DEFAULT_HOME)


def cmd_instances(args: argparse.Namespace, host: Host) -> int:
    registry = read_registry()
    if not registry["instances"]:
        say(f"No instances yet: every command acts on {DEFAULT_HOME}. `veyqctl init` creates one and lists it.")
        return 0
    rows = sorted(registry["instances"].items())
    width = max(len(name) for name, _ in rows)
    for name, path in rows:
        mark = "*" if name == registry["current"] else " "
        say(f"{mark} {name:<{width}}  {target_of(Path(path)):<7}  {path}")
    say("")
    say("* is the current one; `veyqctl use NAME` switches.")
    return 0


def cmd_use(args: argparse.Namespace, host: Host) -> int:
    registry = read_registry()
    if args.name not in registry["instances"]:
        raise Refused(f"no instance named {args.name}: `veyqctl instances` lists them")
    registry["current"] = args.name
    write_registry(registry)
    say(f"{args.name} is now the current instance: {target_of(Path(registry['instances'][args.name]))}, "
        f"{registry['instances'][args.name]}.")
    return 0


# ---- the GitOps target: veyq's half of an Argo CD repository (ADR 0126 decisions 3-5 and 7) ---------------------

# A GitOps instance is a directory in a checkout of the repository an Argo CD syncs: `application.yaml` (the chart by
# digest), `values.yaml` (the three images by tag and digest, the domains, the gateway, the Secrets' names),
# `resources/` (the namespace, the backup gate's claim, the two Secrets from the chosen provider: `sops`, `external-
# secrets`, `sealed-secrets` or `none`) and `veyqctl.json` (the selector, the provider, the running release and its
# four pins). The chart is not named by a release's manifest: its tag is resolved to a digest at the registry
# (`Host.oci_digest`), that digest verified under the release identity, and its Chart.yaml version read back. Every
# generated value is made once: encrypted into the repository under `sops`, else handed over once in
# `~/.config/veyq/<name>.secrets.json` (0600, never overwritten). A directory with files `init` did not write is
# refused; files written by hand are taken over by writing `veyqctl.json` beside them (the Run page's *A veyq half
# that exists already*). `check` also names drift (a pin the files no longer hold), whether the recorded pins are the
# running release's as signed, and whether the Secrets exist with every key; `upgrade` refuses drift, uncommitted
# changes and a running release whose pins do not verify, then verifies the new release, rewrites each pin found
# exactly once and the three tags, renders, and commits its three files alone (`--push` pushes). Argo CD applies the
# commit; the chart's backup gate captures before the new release migrates (ADR 0098).


PROVIDERS = ("sops", "external-secrets", "sealed-secrets", "none")
# Let's Encrypt's two ACME directories (the chart's gateway.tls.acme.server): staging's certificates are not
# browser-trusted, and it has none of production's tight rate limits — where a new instance starts (ADR 0126).
ACME_DIRECTORIES = {"production": "https://acme-v02.api.letsencrypt.org/directory",
                    "staging": "https://acme-staging-v02.api.letsencrypt.org/directory"}
# What a veyq half written by hand must look like, and its veyqctl.json: the Run page, named where `init` refuses one.
EXISTING_HALF_DOCS = "https://veyq.app/run/gitops/#a-veyq-half-that-exists-already"
SECRETS_SECRET = "veyq-secrets"  # the chart's `secrets.existingSecret`
CONFIG_SECRET = "veyq-config-secret-key"  # the chart's `app.configSecret.existingSecret`
CONFIG_SECRET_KEY = "config-secret.key"
# Each Secret's keys and how a value is made — the chart's contract (values.yaml § secrets, app.configSecret). The
# config-secret key is base64 of 32 random bytes (ADR 0050); the RPC secret exactly 64 hex characters.
SECRET_VALUES: dict[str, dict[str, Callable[[], str]]] = {
    SECRETS_SECRET: {
        "mariadbPassword": lambda: secrets.token_urlsafe(30),
        "mariadbRootPassword": lambda: secrets.token_urlsafe(30),
        "garageRpcSecret": lambda: secrets.token_hex(32),
        "garageAdminToken": lambda: secrets.token_urlsafe(36),
    },
    CONFIG_SECRET: {CONFIG_SECRET_KEY: lambda: base64.b64encode(secrets.token_bytes(32)).decode()},
}
PIN_ROLES = ("chart", "app", "admin", "tools")
# values.yaml's top-level key each image digest sits under, as `init` writes it (and the chart reads it).
VALUES_IMAGE_KEYS = {"image": "app", "admin": "admin", "backup": "tools"}
GATEWAY_BUNDLE_MIN = (1, 5)  # the Gateway API the chart's ListenerSets need (values.yaml § gateway)
CERT_MANAGER_MIN = (1, 21)  # the parent-ref fallback the chart's ACME issuer needs (values.yaml § gateway.tls.acme)
HELM_API_VERSIONS = ("gateway.networking.k8s.io/v1", "gateway.networking.k8s.io/v1/GatewayClass",
                     "cert-manager.io/v1", "cert-manager.io/v1/Issuer")


class GitOps(NamedTuple):
    directory: Path  # the instance: the directory holding veyqctl.json, the Application, the values, resources/
    doc: dict        # veyqctl.json

    @property
    def application(self) -> Path:
        return self.directory / self.doc["application"]

    @property
    def values(self) -> Path:
        return self.directory / self.doc["values"]

    @property
    def namespace(self) -> str:
        return self.doc.get("namespace") or "veyq"


def read_gitops(directory: Path) -> GitOps:
    path = directory / GITOPS_FILE
    try:
        doc = json.loads(path.read_text())
    except (OSError, ValueError) as e:
        raise Refused(f"{path} cannot be read: {e}") from None
    problems = []
    if not isinstance(doc, dict) or doc.get("target") != "gitops":
        raise Refused(f"{path} is not a GitOps instance's file (`target`: gitops)")
    if not SELECTOR.match(str(doc.get("release", ""))):
        problems.append("`release` is no selector")
    if doc.get("secrets") not in PROVIDERS:
        problems.append(f"`secrets` is none of {', '.join(PROVIDERS)}")
    for key in ("application", "values"):
        if not isinstance(doc.get(key), str) or not ASSET_NAME.match(doc[key]):
            problems.append(f"`{key}` is no file name beside it")
    if not isinstance(doc.get("chart"), str) or not re.match(r"^[a-z0-9][a-z0-9.:-]*(/[a-z0-9._-]+)+$", doc["chart"]):
        problems.append("`chart` is no `<registry>/<repository>`")
    if not TAG.match(f"v{doc.get('running', '')}"):
        problems.append("`running` is no release")
    pins = doc.get("pins")
    if not isinstance(pins, dict) or any(not isinstance(pins.get(r), str) or not re.match(r"^sha256:[0-9a-f]{64}$", pins[r])
                                         for r in PIN_ROLES):
        problems.append(f"`pins` does not give {', '.join(PIN_ROLES)} each a sha256 digest")
    if problems:
        raise Refused(f"{path}: " + "; ".join(problems))
    return GitOps(directory, doc)


def write_gitops(g: GitOps) -> None:
    (g.directory / GITOPS_FILE).write_text(json.dumps(g.doc, indent=2) + "\n")


def kubectl(context: str | None) -> list[str]:
    return ["kubectl"] + (["--context", context] if context else [])


def version_of(text: str) -> tuple[int, ...] | None:
    m = re.search(r"v?([0-9]+)\.([0-9]+)(?:\.([0-9]+))?", text or "")
    return tuple(int(x) for x in m.groups() if x is not None) if m else None


def check_cluster(host: Host, context: str | None, gateway_class: str, storage_class: str, acme: bool) -> None:
    """ADR 0126 decision 4: what veyq's half assumes of the cluster, every gap named at once, before anything is
    written."""
    k, missing = kubectl(context), []
    if host.run(k + ["get", "crd", "applications.argoproj.io", "-o", "name"], check=False).returncode:
        missing.append("Argo CD (no CRD applications.argoproj.io)")
    bundle = host.run(k + ["get", "crd", "gateways.gateway.networking.k8s.io", "-o",
                           r"jsonpath={.metadata.annotations.gateway\.networking\.k8s\.io/bundle-version}"], check=False)
    found = version_of(bundle.stdout) if not bundle.returncode else None
    if bundle.returncode:
        missing.append("the Gateway API CRDs")
    elif found is None or found[:2] < GATEWAY_BUNDLE_MIN:
        missing.append(f"Gateway API v{'.'.join(map(str, GATEWAY_BUNDLE_MIN))} or later (found "
                       f"{bundle.stdout.strip() or 'no bundle-version'})")
    if host.run(k + ["get", "gatewayclass", gateway_class, "-o", "name"], check=False).returncode:
        missing.append(f"the GatewayClass {gateway_class} (`kubectl get gatewayclass` lists the cluster's)")
    classes = host.run(k + ["get", "storageclass", "-o", "name"], check=False)
    names = [line.split("/", 1)[-1] for line in (classes.stdout or "").split()] if not classes.returncode else []
    if not names or (storage_class and storage_class not in names):
        missing.append(f"the StorageClass {storage_class}" if storage_class else "a StorageClass")
    if acme:
        if host.run(k + ["get", "crd", "certificates.cert-manager.io", "-o", "name"], check=False).returncode:
            missing.append("cert-manager (no CRD certificates.cert-manager.io), which the chart's ACME issuer needs")
        else:
            label = host.run(k + ["get", "deploy", "-A", "-l", "app.kubernetes.io/name=cert-manager,"
                                  "app.kubernetes.io/component=controller", "-o",
                                  r"jsonpath={.items[0].metadata.labels.app\.kubernetes\.io/version}"], check=False)
            have = version_of(label.stdout) if not label.returncode else None
            if have is None or have[:2] < CERT_MANAGER_MIN:
                missing.append(f"cert-manager {'.'.join(map(str, CERT_MANAGER_MIN))} or later for the chart's ACME "
                               f"issuer (found {label.stdout.strip() or 'no version label'})")
    if missing:
        raise Refused("your cluster lacks what veyq needs: " + "; ".join(missing)
                      + ". Nothing was written. (For a cluster you set up later: --no-cluster-check.)")
    checked("cluster", "Argo CD, the Gateway API and its class, a StorageClass" + (", cert-manager" if acme else ""))


def chart_repository(app_ref: str) -> str:
    """The chart beside the release's images: `<registry>/<owner>/charts/veyq` for `<registry>/<owner>/veyq` — where
    the promotion pushes it (ADR 0064)."""
    m = DIGEST_REF.match(app_ref)
    if not m:
        raise Refused(f"the app image `{app_ref}` is not pinned by digest, so no chart can be found beside it")
    return f"{m.group('name').rsplit('/', 1)[0]}/charts/veyq"


def cosign_verify_ref(host: Host, what: str, target: str, untouched: str) -> None:
    """`cosign verify` of one image or chart by digest, under the identity that publishes veyq; refused otherwise,
    saying what it left untouched."""
    result = host.run([require_cosign(host), "verify", target, "--certificate-oidc-issuer", COSIGN_OIDC_ISSUER,
                       "--certificate-identity-regexp", COSIGN_IDENTITY_REGEXP], check=False)
    if result.returncode != 0:
        raise Refused(f"the {what} {target} does not verify against the identity that publishes veyq "
                      f"({COSIGN_IDENTITY_REGEXP}): {(result.stderr or '').strip()[-800:]}\n{untouched}")
    checked(what, short(target))


def verify_gitops_release(host: Host, manifest: Manifest, chart: str) -> dict[str, str]:
    """The release's four pins, each verified under the release identity (Verify a release): the manifest's three
    images, and the chart its tag resolves to, verified by its own signature and its Chart.yaml version read back —
    the manifest does not name the chart (cr-veyqctl-gitops-target § Decisions)."""
    if not manifest.tools:
        raise Refused(f"the manifest of {manifest.release} names no tools image (`images.tools`), which the chart needs "
                      f"for its backups: this release cannot be installed through Argo CD")
    verify_images(host, manifest, False)
    tools = cosign_target("tools", manifest.tools)
    cosign_verify_ref(host, "tools image", tools, NOTHING_WRITTEN)
    digest = host.oci_digest(f"{chart}:{manifest.release}")
    cosign_verify_ref(host, "chart", f"{chart}@{digest}", NOTHING_WRITTEN)
    shown = host.run(["helm", "show", "chart", f"oci://{chart}@{digest}"], check=False)
    version = re.search(r"(?m)^version:\s*\"?([^\"\s]+)\"?\s*$", shown.stdout or "") if not shown.returncode else None
    if version is None or version.group(1) != manifest.release:
        raise Refused(f"the chart {chart}@{digest}, which {chart}:{manifest.release} resolves to, is version "
                      f"{version.group(1) if version else '(unreadable)'}, not {manifest.release}. Nothing was written.")
    pin = lambda ref: DIGEST_REF.match(ref).group("digest")  # noqa: E731 — every ref verified above is one
    return {"chart": digest, "app": pin(manifest.app), "admin": pin(manifest.admin), "tools": pin(manifest.tools)}


def render(host: Host, chart: str, digest: str, namespace: str, values: str) -> None:
    """`helm template` of the pinned chart with these values (the text of a values.yaml, before it is written): the
    check that what veyqctl writes is the chart's input."""
    with tempfile.TemporaryDirectory(prefix="veyqctl-values-") as tmp:
        staged = Path(tmp) / "values.yaml"
        staged.write_text(values)
        cmd = ["helm", "template", "veyq", f"oci://{chart}@{digest}", "-n", namespace, "-f", str(staged)]
        for api in HELM_API_VERSIONS:
            cmd += ["--api-versions", api]
        result = host.run(cmd, check=False)
    if result.returncode != 0:
        raise Refused(f"`helm template` of the chart with your values.yaml failed: {(result.stderr or '').strip()[-800:]}")
    checked("the chart renders with your values.yaml")


def git(host: Host, top: Path, *cmd: str, check: bool = True) -> subprocess.CompletedProcess:
    return host.run(["git", "-C", str(top), *cmd], check=check)


def checkout_top(host: Host) -> Path | None:
    """The Git checkout the current directory is in (its real path), or None outside one."""
    found = host.run(["git", "rev-parse", "--show-toplevel"], check=False)
    top = (found.stdout or "").strip() if found.returncode == 0 else ""
    return Path(top).resolve() if top else None


def repository_of(host: Host, directory: Path) -> Path:
    directory = directory.resolve()
    probe = directory
    while not probe.is_dir() and probe != probe.parent:  # `init` writes a directory that may not exist yet, nor its parent
        probe = probe.parent
    result = host.run(["git", "-C", str(probe), "rev-parse", "--show-toplevel"], check=False)
    if result.returncode != 0 or not (result.stdout or "").strip():
        raise Refused(f"{directory} is not inside a Git repository: veyq's files go into the repository Argo CD syncs")
    top = Path(result.stdout.strip()).resolve()
    try:
        directory.relative_to(top)
    except ValueError:
        raise Refused(f"{directory} is not inside its checkout {top}") from None
    return top


def sops_run(host: Host, cmd: list[str], target: Path, **kwargs) -> subprocess.CompletedProcess:
    """sops, run where its creation rules are found: it looks for .sops.yaml from its working directory upward, not
    from the file it is told about (measured 2026-11-10: from elsewhere, "config file not found")."""
    rules = next((d for d in target.parents if (d / ".sops.yaml").is_file()), None)
    workdir, host.workdir = host.workdir, rules or host.workdir
    try:
        return host.run(cmd, **kwargs)
    finally:
        host.workdir = workdir


def stack_values(release: str, pins: dict[str, str], *, domain: str, store_domain: str, garage: bool,
                 gateway_class: str, tls: str, tls_secret: str, peer_namespace: str,
                 peer_label: str, provider: str, acme_directory: str = "production") -> str:
    label_key, _, label_value = peer_label.partition("=")
    lines = [
        "# veyq's chart values, written by veyqctl (ADR 0126). No secret here: the Secrets come from resources/"
        f" ({provider}).",
        "# Read with the chart's own values.yaml and https://veyq.app/run/kubernetes/. `veyqctl upgrade` rewrites the",
        "# three images' tag and digest; edit anything else as the chart's values describe.",
        f"domain: {domain}",
    ]
    if garage:
        lines.append(f"storeDomain: {store_domain}")
    lines += [
        "",
        "image:",
        f'  tag: "{release}"',
        f"  digest: {pins['app']}",
        "admin:",
        "  image:",
        f'    tag: "{release}"',
        f"    digest: {pins['admin']}",
        "backup:",
        "  # The backup gate captures onto resources/backup-pvc.yaml's claim before every release change (ADR 0098).",
        "  enabled: true",
        "  existingClaim: veyq-backup",
        "  image:",
        f'    tag: "{release}"',
        f"    digest: {pins['tools']}",
        "",
        "app:",
        "  configSecret:",
        f"    existingSecret: {CONFIG_SECRET}",
        f"    key: {CONFIG_SECRET_KEY}",
        "secrets:",
        f"  existingSecret: {SECRETS_SECRET}",
        "garage:",
        f"  enabled: {'true' if garage else 'false'}",
        "",
        "gateway:",
        f"  className: {gateway_class}",
        "  tls:",
    ]
    if tls == "acme":
        lines += ["    mode: per-domain", "    acme:", "      enabled: true", f"      server: {ACME_DIRECTORIES[acme_directory]}"]
    elif tls == "shared":
        lines += ["    mode: shared", f"    secretName: {tls_secret}"]
    else:
        lines += ["    mode: none"]
    lines += [
        "",
        "# The Gateway's data-plane pods, the only peers allowed to reach the app, the store and the HTTP-01 solvers.",
        "networkPolicy:",
        "  enabled: true",
        "  ingressFrom:",
        "    - namespaceSelector:",
        "        matchLabels:",
        f"          kubernetes.io/metadata.name: {peer_namespace}",
        "      podSelector:",
        "        matchLabels:",
        f"          {label_key}: {label_value}",
    ]
    return "\n".join(lines) + "\n"


def application_text(*, name: str, argocd_namespace: str, project: str, chart: str, digest: str, rel: str,
                     repo_url: str, revision: str, namespace: str, values: str) -> str:
    return f"""# veyq, written by veyqctl (ADR 0126): the chart by digest, with {values} and resources/ from this repository.
# Register it once (`kubectl apply -f` this file, or let an app of apps pick it up). `veyqctl upgrade` rewrites the
# chart's digest below.
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: {name}
  namespace: {argocd_namespace}
  annotations:
    # Diff against what the API server makes of the manifests (defaults on the Gateway, the HTTPRoutes and the
    # StatefulSets' claim templates), or those objects stay OutOfSync.
    argocd.argoproj.io/compare-options: ServerSideDiff=true
spec:
  project: {project}
  sources:
    - repoURL: oci://{chart}
      targetRevision: {digest}
      path: .
      helm:
        releaseName: veyq
        valueFiles:
          - $values/{rel}/{values}
    - repoURL: {repo_url}
      targetRevision: {revision}
      path: {rel}/resources
      ref: values
  destination:
    server: https://kubernetes.default.svc
    namespace: {namespace}
  syncPolicy:
    # Self-heal, never prune: Argo CD ignores helm.sh/resource-policy: keep, so an object that leaves the chart shows
    # as "requires pruning" and is removed by hand after a look. Switch automated sync off while a restore runs.
    automated:
      prune: false
      selfHeal: true
    syncOptions:
      - ServerSideApply=true
"""


def namespace_text(namespace: str) -> str:
    return f"""apiVersion: v1
kind: Namespace
metadata:
  name: {namespace}
  annotations:
    argocd.argoproj.io/sync-wave: "-1"
  labels:
    # veyq's pods pass `restricted`, so the namespace enforces it.
    pod-security.kubernetes.io/enforce: restricted
    pod-security.kubernetes.io/warn: restricted
    pod-security.kubernetes.io/audit: restricted
"""


def backup_claim_text(namespace: str, storage_class: str, size: str) -> str:
    klass = f"\n  storageClassName: {storage_class}" if storage_class else ""
    return f"""# The backup gate's claim (the chart's backup.existingClaim): the way back from a failed upgrade.
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: veyq-backup
  namespace: {namespace}
  annotations:
    # Never pruned or deleted by Argo CD.
    argocd.argoproj.io/sync-options: Prune=false,Delete=false
spec:
  accessModes: [ReadWriteOnce]{klass}
  resources:
    requests:
      storage: {size}
"""


def secret_text(name: str, namespace: str, values: dict[str, str]) -> str:
    keep = "\n    argocd.argoproj.io/sync-options: Prune=false,Delete=false" if name == CONFIG_SECRET else ""
    data = "".join(f"  {key}: {json.dumps(value)}\n" for key, value in values.items())
    return f"""apiVersion: v1
kind: Secret
metadata:
  name: {name}
  namespace: {namespace}
  annotations:
    argocd.argoproj.io/sync-wave: "-1"{keep}
type: Opaque
stringData:
{data}"""


ADMIN_HOST_PORT = 30081  # the admin surface's host-only NodePort (veyq-server's firewall keeps it off the public side)


def admin_host_access_text(namespace: str, node_source: str) -> str:
    """veyq's administration surface on a host-only NodePort, so the ssh tunnel that reaches the cluster reaches it too.
    The chart keeps its admin Service a ClusterIP and a NetworkPolicy admitting nothing (ADR 0065: localhost-only, by
    `kubectl port-forward`); this adds a second Service and a policy admitting the node alone — a NodePort's traffic
    reaches the pod masqueraded to the node's pod-network gateway — and only a host firewall that drops NodePorts on the
    public interface (veyq-server's) makes it host-only. Offered where Argo CD's own server is a NodePort already."""
    selector = ("      app.kubernetes.io/name: veyq\n      app.kubernetes.io/instance: veyq\n"
                "      app.kubernetes.io/component: admin\n")
    return f"""# veyq's administration through the ssh tunnel: a host-only NodePort beside the chart's ClusterIP (veyqctl init).
apiVersion: v1
kind: Service
metadata:
  name: veyq-admin-host
  namespace: {namespace}
spec:
  type: NodePort
  selector:
{selector.replace("      ", "    ")}  ports:
    - name: http
      port: 8080
      targetPort: http
      nodePort: {ADMIN_HOST_PORT}
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: veyq-admin-host
  namespace: {namespace}
spec:
  podSelector:
    matchLabels:
{selector}  policyTypes: [Ingress]
  ingress:
    - from:
        - ipBlock:
            cidr: {node_source}
      ports:
        - port: 8080
"""


def node_gateway(pod_cidr: str) -> str:
    """The first address of the node's pod network — where a NodePort's traffic comes from inside it — as a /32."""
    try:
        return f"{next(ipaddress.ip_network(pod_cidr.strip(), strict=False).hosts())}/32"
    except (ValueError, StopIteration):
        return ""


def remote_key(prefix: str, key: str) -> str:
    return prefix + re.sub(r"(?<!^)(?=[A-Z])", "-", key).lower().replace(".", "-")


def external_secret_text(name: str, namespace: str, store: str, store_kind: str, prefix: str) -> str:
    data = "".join(f"    - secretKey: {key}\n      remoteRef:\n        key: {remote_key(prefix, key)}\n"
                   for key in SECRET_VALUES[name])
    return f"""apiVersion: external-secrets.io/v1
kind: ExternalSecret
metadata:
  name: {name}
  namespace: {namespace}
  annotations:
    argocd.argoproj.io/sync-wave: "-1"
spec:
  refreshInterval: 1h
  secretStoreRef:
    kind: {store_kind}
    name: {store}
  target:
    name: {name}
    creationPolicy: Owner
    # Kept when this ExternalSecret goes: the config-secret key above all must outlive any change here.
    deletionPolicy: Retain
  data:
{data}"""


# KSOPS: decrypts the two SOPS files in Argo CD's repo server (the server path's and `--secrets sops`).
KSOPS_GENERATOR = """# KSOPS: decrypts the listed SOPS files in Argo CD's repo server.
apiVersion: viaduct.ai/v1
kind: ksops
metadata:
  name: veyq-secrets
  annotations:
    config.kubernetes.io/function: |
      exec:
        path: ksops
files:
  - secrets.sops.yaml
  - config-secret-key.sops.yaml
"""


def hand_over(name: str, namespace: str, values: dict[str, dict[str, str]], remote: dict[str, str] | None) -> Path:
    """The generated values, once, in a file only the operator can read — outside the repository, never overwritten."""
    path = config_dir() / f"{name}.secrets.json"
    if path.exists():
        raise Refused(f"{path} exists from an earlier `init`. Nothing was written: once its values are stored where "
                      f"they belong, move the file away and run `init` again")
    config_dir().mkdir(parents=True, exist_ok=True)
    doc = {"namespace": namespace, "secrets": values}
    if remote:
        doc["remoteKeys"] = remote
    write_private(path, json.dumps(doc, indent=2) + "\n")
    return path


# ---- the server path: veyq-server sets up the server and its cluster beneath veyq's half (ADR 0126 decision 6) ------

SERVER_TEMPLATE_REPO = "raddatzk/veyq-server"
SERVER_TEMPLATE_URL = f"https://github.com/{SERVER_TEMPLATE_REPO}.git"
# veyq-server's release asset, not veyq's (named apart from the `*_ASSET` names a veyq release holds).
SERVER_MANIFEST_FILE = "veyq-server-manifest.json"
# The one workflow that releases veyq-server; its manifest names the commit and the sha256 of its tree.
SERVER_IDENTITY_REGEXP = r"^https://github\.com/raddatzk/veyq-server/\.github/workflows/release\.yml@refs/heads/main$"
SERVER_SECRETS = "ansible/inventory/group_vars/server/secrets.sops.yaml"
SERVER_SECRETS_TEMPLATE = "secret-templates/ansible-server.yaml"
SERVER_KNOWN_HOSTS = "ansible/known_hosts"
SERVER_APPS = "gitops/apps"  # what the root Application syncs: veyq's Application is listed in its kustomization
AGE_KEY_FILE = ".age/key.txt"  # gitignored by the template; its other copy is the owner's password manager
ANSIBLE_CORE_FLOOR = (2, 18)
CONNECT_PORTS = {"api": 16443, "argocd": 18443, "admin": 18081}
HOSTNAME_RE = r"[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+"
IPV4_RE = r"(\d{1,3}\.){3}\d{1,3}"
SSH_PUBLIC_KEY_RE = r"(ssh-ed25519|ecdsa-sha2-nistp(256|384|521)|ssh-rsa) [A-Za-z0-9+/]+=*"
AGE_RECIPIENT_RE = r"age1[0-9a-z]{58}"
GITHUB_REPO = re.compile(r"(?:git@github\.com:|ssh://git@github\.com/|https://github\.com/)([\w.-]+/[\w.-]+?)(?:\.git)?/?")
ONE_PASSWORD_AGENT = Path.home() / "Library/Group Containers/2BUA8C4S2C.com.1password/t/agent.sock"


def ssh_key_candidates(host: Host) -> list[tuple[str, str]]:
    """The operator's public SSH keys: ~/.ssh/*.pub and what the agents hold (the shell's and 1Password's)."""
    found: dict[str, str] = {}
    for pub in sorted((Path.home() / ".ssh").glob("*.pub")):
        try:
            line = pub.read_text().strip()
        except OSError:
            continue
        m = re.match(SSH_PUBLIC_KEY_RE, line)
        if m:
            found.setdefault(m.group(0), pub.name)
    sockets = [s for s in (os.environ.get("SSH_AUTH_SOCK"), str(ONE_PASSWORD_AGENT)) if s and Path(s).exists()]
    if host.which("ssh-add"):
        for sock in sockets:
            listed = host.run(["ssh-add", "-L"], check=False, env={"SSH_AUTH_SOCK": sock})
            for line in (listed.stdout or "").splitlines() if not listed.returncode else []:
                m = re.match(SSH_PUBLIC_KEY_RE, line.strip())
                if m:
                    found.setdefault(m.group(0), line.strip()[m.end():].strip() or "agent")
    return list(found.items())


def age_recipient_of(host: Host, key_file: Path) -> str:
    if not key_file.is_file():
        raise Refused(f"{key_file} does not exist: name the age key file you keep (--age-key), or run `init` at a "
                      f"terminal, where Enter creates a new one")
    result = host.run(["age-keygen", "-y", str(key_file)], check=False)
    recipient = (result.stdout or "").strip()
    if result.returncode or not re.fullmatch(AGE_RECIPIENT_RE, recipient):
        raise Refused(f"{key_file} holds no age key (`age-keygen -y` derives none from it)")
    return recipient


def tree_sha256(root: Path) -> str:
    """veyq-server's `scripts/tree_hash.py` over a checked-out tree: sorted `<mode> <sha256> <path>` lines, the files
    git tracks (no .git), mode as git records it (120000 a link, 100755 executable, else 100644)."""
    lines: list[bytes] = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if not (Path(dirpath) == root and d == ".git")]
        for name in filenames + [d for d in dirnames if (Path(dirpath) / d).is_symlink()]:
            path = Path(dirpath) / name
            relative = os.fsencode(path.relative_to(root).as_posix())
            if path.is_symlink():
                mode, data = b"120000", os.fsencode(os.readlink(path))
            else:
                mode = b"100755" if path.stat().st_mode & 0o111 else b"100644"
                data = path.read_bytes()
            lines.append(mode + b" " + hashlib.sha256(data).hexdigest().encode() + b" " + relative)
    lines.sort(key=lambda line: line.split(b" ", 2)[2])
    return hashlib.sha256(b"\n".join(lines) + b"\n").hexdigest()


def verify_server_template(host: Host, into: Path) -> dict[str, str]:
    """veyq-server's newest release, verified before Copier reads a byte of it (ADR 0126 decision 6): the manifest's
    signature under veyq-server's release workflow, then the commit it names checked out into `into` and its tree's
    sha256 compared. Returns the manifest."""
    tag, raw, bundle = host.server_release()
    verify_manifest(host, require_cosign(host), raw, bundle, f"veyq-server {tag}'s manifest",
                    identity=SERVER_IDENTITY_REGEXP, publisher="veyq-server",
                    bundle_name=f"{SERVER_MANIFEST_FILE}.bundle")
    try:
        doc = json.loads(raw)
    except ValueError as e:
        raise Refused(f"veyq-server {tag}'s manifest is not JSON: {e}") from None
    commit, tree, release = str(doc.get("commit", "")), str(doc.get("tree_sha256", "")), str(doc.get("release", ""))
    if not (re.fullmatch(r"[0-9a-f]{40}", commit) and re.fullmatch(r"[0-9a-f]{64}", tree) and f"v{release}" == tag):
        raise Refused(f"veyq-server {tag}'s manifest does not name its release, a commit and a tree hash: {doc}")
    host.run(["git", "clone", "--quiet", "--no-checkout", SERVER_TEMPLATE_URL, str(into)])
    host.run(["git", "-C", str(into), "-c", "advice.detachedHead=false", "checkout", "--quiet", commit])
    found = tree_sha256(into)
    if found != tree:
        raise Refused(f"veyq-server {release} does not match its signed manifest: commit {commit[:12]} has the tree "
                      f"sha256 {found}, its signed manifest says {tree}. Nothing was added to your repository.")
    checked("veyq-server", f"{release}: commit {commit[:12]}, its files as signed")
    return {"release": release, "commit": commit}


def render_server(host: Host, top: Path, template: Path, commit: str, answers: dict[str, str], project: str,
                  repo_url: str, branch: str) -> None:
    """Copier renders veyq-server into the checkout with init's answers; `.copier-answers.yml` then names veyq-server's
    public repository, where `copier update` finds later releases (verified as this one was)."""
    data = {"server_address": answers["server_address"], "node_name": answers["node_name"],
            "admin_user": answers["admin_user"], "admin_ssh_public_key": answers["admin_ssh_public_key"],
            "age_recipient": answers["age_recipient"], "repo_url": repo_url, "branch": branch, "project": project,
            "host_access": "true" if answers["host_access"] == "yes" else "false"}
    cmd = ["copier", "copy", "--trust", "--defaults", "--quiet", "--vcs-ref", commit, str(template), str(top)]
    for key, value in data.items():
        cmd += ["-d", f"{key}={value}"]
    host.run(cmd)
    answers_file = top / ".copier-answers.yml"
    text = answers_file.read_text()
    answers_file.write_text(re.sub(r"(?m)^_src_path: .*$", f"_src_path: {SERVER_TEMPLATE_URL}", text, count=1))


def ssh_keypair(host: Host, comment: str) -> tuple[str, str]:
    """A new ed25519 key pair (private, public), made in a private temporary directory and gone with it."""
    with tempfile.TemporaryDirectory(prefix="veyqctl-key-") as tmp:
        key = Path(tmp) / "key"
        host.run(["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", comment, "-f", str(key)])
        return key.read_text(), Path(f"{key}.pub").read_text().strip()


def server_secrets(host: Host, top: Path, answers: dict[str, str], repo_name: str, age_env: dict[str, str]) -> str:
    """The server half's secrets, encrypted once (a file that exists is never made again): the admin password (as
    given, or generated), its salt, and Argo CD's deploy key. Returns the deploy key's public half."""
    target = top / SERVER_SECRETS
    if target.is_file():
        private = sops_run(host, ["sops", "decrypt", "--extract", '["argocd_repo_ssh_private_key"]', str(target)],
                           target, env=age_env).stdout
        with tempfile.TemporaryDirectory(prefix="veyqctl-key-") as tmp:
            key = Path(tmp) / "key"
            write_private(key, private if private.endswith("\n") else private + "\n")
            return host.run(["ssh-keygen", "-y", "-f", str(key)]).stdout.strip()
    alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    password = answers.get("admin_password") or "".join(secrets.choice(alphabet) for _ in range(32))
    private, public = ssh_keypair(host, f"argocd@{repo_name.split('/')[-1]}")
    text = (top / SERVER_SECRETS_TEMPLATE).read_text()
    text = text.replace("__ADMIN_PASSWORD__", json.dumps(password))
    text = re.sub(r"__RANDOM_(\d+)__", lambda m: "".join(secrets.choice(alphabet) for _ in range(int(m.group(1)))),
                  text)
    text = text.replace("__DEPLOY_KEY__", "|\n" + "".join(f"  {line}\n" for line in private.splitlines()).rstrip("\n"))
    left = re.findall(r"__[A-Z0-9_]+__", text)
    if left:
        raise Refused(f"{SERVER_SECRETS_TEMPLATE} asks for values this veyqctl does not know ({', '.join(left)}): "
                      f"veyq-server is newer than this veyqctl. Update veyqctl. Nothing was encrypted.")
    encrypted = sops_run(host, ["sops", "encrypt", "--filename-override", str(target), "--input-type", "yaml",
                                "--output-type", "yaml", "/dev/stdin"], target, stdin=text.encode()).stdout
    if not encrypted or "ENC[" not in encrypted:
        raise Refused(f"sops did not encrypt {SERVER_SECRETS}: check the rules in .sops.yaml")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(encrypted)
    checked("secrets", "your sudo password" + (" (generated)" if not answers.get("admin_password") else "")
            + f" and Argo CD's deploy key, encrypted into {SERVER_SECRETS}")
    return public


def pinned_host_key(top: Path) -> str:
    lines = [line.strip() for line in (top / SERVER_KNOWN_HOSTS).read_text().splitlines()
             if line.strip() and not line.lstrip().startswith("#")] if (top / SERVER_KNOWN_HOSTS).is_file() else []
    return lines[-1] if lines else ""


def fingerprint(host: Host, line: str) -> str:
    return (host.run(["ssh-keygen", "-lf", "-"], stdin=(line + "\n").encode()).stdout.split() or ["?", "?"])[1]


def pin_host_key(host: Host, top: Path, address: str) -> str:
    """The server's ed25519 host key in ansible/known_hosts, once its fingerprint is confirmed against the provider's
    console — asked, as trusting it cannot be taken back once Ansible logs in."""
    pinned = pinned_host_key(top)
    scanned = host.run(["ssh-keyscan", "-T", "10", "-t", "ed25519", address], check=False).stdout or ""
    line = next((l.strip() for l in scanned.splitlines() if l.strip() and not l.startswith("#")), "")
    if pinned and (not line or line.split()[1:3] == pinned.split()[1:3]):
        return pinned  # the pinned key, or the server not answering now: nothing to decide
    if not line:
        raise Refused(f"{address} does not answer on port 22: is the server running and reachable? Then run the same "
                      f"command again.")
    if pinned:  # a rebuilt server under the same address presents a new key
        print(f"\n   The server shows the fingerprint {fingerprint(host, line)} (ED25519), but "
              f"{fingerprint(host, pinned)} was saved before. Was the server reinstalled?", flush=True)
    else:
        print(f"\n   The server shows the fingerprint {fingerprint(host, line)} (ED25519).", flush=True)
    if host.confirm("Is this the fingerprint your provider shows for the server?") is not True:
        raise Refused(f"the fingerprint of {address} was not confirmed, so veyqctl does not log in. Compare it with your "
                      f"provider's console and run the same command again.")
    known = top / SERVER_KNOWN_HOSTS
    kept = [l for l in known.read_text().splitlines() if not l.strip() or l.lstrip().startswith("#")] \
        if known.is_file() else []
    known.write_text("\n".join(kept + [line]) + "\n")
    return line


# The systems veyq-server's roles set up are the platforms their meta/main.yml name (veyq-server's scripts/systems.py
# reads the same block, and its release signs that list into its manifest as `systems`). veyqctl reads them from the
# roles in the checkout — the ones Ansible will run — and compares the server's /etc/os-release before anything leaves
# this machine: a system the roles were not written for fails here, by name, not halfway through the bootstrap as root.
OS_RELEASE_FIELD = re.compile(r'(?m)^(ID|VERSION_CODENAME|PRETTY_NAME)="?([^"\n]*)"?\s*$')


def role_systems(top: Path) -> list[tuple[str, str]]:
    """(platform, version) pairs every role under ansible/roles/ names in its meta/main.yml `platforms:` block, in the
    first role's order — empty when no role names any (a template from before the roles named them)."""
    common: list[tuple[str, str]] | None = None
    for meta in sorted((top / "ansible" / "roles").glob("*/meta/main.yml")):
        block = re.search(r"(?m)^  platforms:\n((?:    .*\n?)*)", meta.read_text())
        pairs, name = [], ""
        for line in (block.group(1) if block else "").splitlines():
            m = re.fullmatch(r"    - name: (\S+)", line) or re.fullmatch(r"        - (\S+)", line)
            if m and line.startswith("    - name:"):
                name = m.group(1)
            elif m and name:
                pairs.append((name, m.group(1)))
        common = pairs if common is None else [p for p in common if p in pairs]
    return common or []


def check_server_system(host: Host, top: Path, address: str, admin_user: str) -> None:
    """The server's /etc/os-release against the systems the roles set up, read over SSH with the pinned host key only:
    as root on a fresh server, as the admin user on one already set up. Refused, changing nothing, when neither logs in
    or the system is not one of them."""
    systems = role_systems(top)
    if not systems:
        say("The roles name no system, so the server's is not checked (a veyq-server release before 0.6).")
        return
    known = top / SERVER_KNOWN_HOSTS
    tried = []
    for user in ("root", admin_user):
        read = host.run(["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15", "-o", "ClearAllForwardings=yes",
                         "-o", f"UserKnownHostsFile={known}", "-o", "StrictHostKeyChecking=yes", f"{user}@{address}",
                         "cat /etc/os-release"], check=False)
        if read.returncode == 0:
            break
        tried.append(f"{user}: {((read.stderr or '').strip().splitlines() or ['no answer'])[-1]}")
    else:
        raise Refused(f"neither root nor {admin_user} logs in to {address} with your key ({'; '.join(tried)}), so its "
                      f"system cannot be read and the bootstrap could not run. A fresh server lets root log in with "
                      f"your key; a stock Ubuntu cloud image logs in as `ubuntu` instead: put your key into root's "
                      f"authorized_keys. Nothing was pushed, and nothing changed on the server.")
    fields = dict(OS_RELEASE_FIELD.findall(read.stdout or ""))
    system, codename = fields.get("ID", "").lower(), fields.get("VERSION_CODENAME", "")
    shown = fields.get("PRETTY_NAME") or f"{system} {codename}".strip() or "a system without /etc/os-release"
    if not any(name.lower() == system and version == codename for name, version in systems):
        names = ", ".join(f"{name} {version}" for name, version in systems)
        raise Refused(f"{address} runs {shown}, but veyq-server's roles set up {names} only (their meta/main.yml). "
                      f"Install one of those and run the same command again. Nothing was pushed, and nothing changed "
                      f"on the server.")
    checked("system", f"{shown}, one veyq-server's roles set up")


def list_veyq_application(top: Path, directory: Path) -> None:
    """veyq's Application into the root's gitops/apps/kustomization.yaml, where the template leaves its place."""
    kustomization = top / SERVER_APPS / "kustomization.yaml"
    entry = f"  - {(directory / 'application.yaml').relative_to(top / SERVER_APPS).as_posix()}"
    text = kustomization.read_text()
    if re.search(rf"(?m)^{re.escape(entry)}\s*$", text):
        return
    marker = re.search(r"(?m)^  # veyq: .*$", text)
    text = text[:marker.start()] + entry + text[marker.end():] if marker else text.rstrip("\n") + f"\n{entry}\n"
    kustomization.write_text(text)


def deploy_key_at_forge(host: Host, repo_url: str, public: str) -> None:
    """Argo CD's deploy key, read-only, at the forge: entered with gh when it is signed in and the person says yes;
    else shown with where it goes, and waited for."""
    m = GITHUB_REPO.fullmatch(repo_url)
    repo = m.group(1) if m else ""
    if repo and host.which("gh") and not host.run(["gh", "auth", "status", "--hostname", "github.com"],
                                                  check=False).returncode:
        listed = host.run(["gh", "repo", "deploy-key", "list", "--repo", repo], check=False).stdout or ""
        if public.split()[1] in listed:
            checked("deploy key", f"on {repo} already")
            return
        if host.confirm(f"Add Argo CD's deploy key to {repo} on GitHub (read-only)?") is True:
            with tempfile.TemporaryDirectory(prefix="veyqctl-key-") as tmp:
                pub = Path(tmp) / "key.pub"
                pub.write_text(public + "\n")
                host.run(["gh", "repo", "deploy-key", "add", str(pub), "--repo", repo, "--title",
                          f"{public.split()[-1]} (read-only)"])
            checked("deploy key", f"added to {repo}, read-only")
            return
    where = f"https://github.com/{repo}/settings/keys/new" if repo else f"your forge's settings of {repo_url}"
    print(f"\n   Add this as a deploy key at {where}, read-only (leave write access off):\n\n   {public}\n",
          flush=True)
    if not host.wait("Press Enter once it is added"):
        raise Refused(f"add Argo CD's deploy key at {where} first, then run the same command again: {public}")


def await_dns(host: Host, address: str, names: list[str]) -> None:
    """The A records veyq's hosts need, checked to resolve to the server; waited for (each host is a certificate
    order the moment Argo CD syncs veyq)."""
    want = host.resolve(address)
    while True:
        wrong = [n for n in names if not want or host.resolve(n) - want or not host.resolve(n)]
        if not wrong:
            checked("DNS", f"{', '.join(names)} resolve to {', '.join(sorted(want))}")
            return
        target = ", ".join(sorted(want)) or address
        message = (f"Create an A record for {', '.join(wrong)} pointing to {target} (no AAAA record: the cluster "
                   f"speaks IPv4 only).\nPress Enter to check again (Ctrl-C to stop; run the same command again later)")
        if not host.wait(message):
            raise Refused(f"{', '.join(wrong)} do not point to {target} yet: create the A records, then run the same "
                          f"command again")


def commit_and_push(host: Host, top: Path, branch: str, message: str) -> None:
    """Everything init wrote, committed and pushed on confirmation; then a check that the remote has it: the bootstrap
    applies the AppProject and root from this checkout, and the root's first sync takes the remote's copy over them."""
    status = git(host, top, "status", "--porcelain", check=False).stdout or ""
    changed = [line[3:] for line in status.splitlines() if line.strip()]
    if changed:
        print(f"\n   {len(changed)} new or changed: " + ", ".join(changed[:8]) + (" …" if len(changed) > 8 else ""),
              flush=True)
        if host.confirm(f"Commit them and push to origin/{branch}?") is not True:
            raise Refused("not committed: the server is set up from what is pushed. Run the same command again when ready.")
        git(host, top, "add", "--all")
        git(host, top, "commit", "--quiet", "-m", message)
        git(host, top, "push", "--quiet", "-u", "origin", branch)
    elif git(host, top, "rev-parse", "--verify", "--quiet", f"origin/{branch}", check=False).returncode:
        if host.confirm(f"Push to origin/{branch}?") is not True:
            raise Refused("not pushed: the server is set up from what is pushed. Run the same command again when ready.")
        git(host, top, "push", "--quiet", "-u", "origin", branch)
    git(host, top, "fetch", "--quiet", "origin", branch)
    if git(host, top, "diff", "--quiet", "HEAD", f"origin/{branch}", "--", "gitops", ".sops.yaml",
           check=False).returncode:
        raise Refused(f"origin/{branch} differs from your checkout under gitops/: push first — Argo CD takes what is "
                      f"pushed, not what is here")


def trust_host_key(host: Host, address: str, line: str) -> None:
    """The pinned key into the user's known_hosts (ssh -G names it), replacing that address's entries of the same key
    type only — ssh's UpdateHostKeys adds the server's other types, which are no conflict."""
    configured = host.run(["ssh", "-G", address], check=False).stdout or ""
    found = re.search(r"(?m)^userknownhostsfile (\S+)", configured)
    known = Path(os.path.expanduser(found.group(1) if found else "~/.ssh/known_hosts"))
    kind, key = line.split()[1], line.split()[2]
    entries = host.run(["ssh-keygen", "-F", address, "-f", str(known)], check=False).stdout or "" \
        if known.is_file() else ""
    same_type = [e for e in entries.splitlines() if not e.startswith("#") and len(e.split()) > 2 and e.split()[1] == kind]
    if same_type and all(e.split()[2] == key for e in same_type):
        return
    known.parent.mkdir(mode=0o700, exist_ok=True)
    if same_type:
        host.run(["ssh-keygen", "-R", address, "-f", str(known)], check=False)
    with open(known, "a") as f:
        f.write(line + "\n")
    checked("fingerprint", f"the server's, saved in {known}")


def ansible_core(host: Host) -> tuple[int, ...]:
    shown = host.run(["ansible-playbook", "--version"], check=False).stdout or ""
    m = re.search(r"core (\d+)\.(\d+)", shown)
    return (int(m.group(1)), int(m.group(2))) if m else (0, 0)


def bootstrap(host: Host, top: Path, answers: dict[str, str], pinned: str, age_env: dict[str, str]) -> None:
    """The bootstrap playbook, asked first (it runs as root on the server and cannot be undone) — unless the admin
    user logs in already: the server is set up, and root's login is off by then."""
    address = answers["server_address"]
    trust_host_key(host, address, pinned)
    probe = host.run(["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15", "-o", "ClearAllForwardings=yes",
                      f"{answers['admin_user']}@{address}", "true"], check=False)
    if not probe.returncode:
        say(f"{answers['admin_user']} logs in to {address} already: the server is bootstrapped, nothing to run.")
        return
    if ansible_core(host) < ANSIBLE_CORE_FLOOR:
        raise Refused(f"ansible-core {'.'.join(map(str, ANSIBLE_CORE_FLOOR))} or later is needed, but "
                      f"`ansible-playbook --version` shows an older one")
    if host.confirm(f"Set up the server now? This logs in to {address} as root, creates {answers['admin_user']}, "
                    f"switches off root and password logins, and installs the firewall, Kubernetes and Argo CD. It "
                    f"cannot be undone") is not True:
        raise Refused("the server was not set up. Run the same command again when ready.")
    workdir, host.workdir = host.workdir, top / "ansible"
    try:
        host.run(["ansible-galaxy", "collection", "install", "-r", "requirements.yml", "-p", "collections"])
        host.run(["ansible-playbook", "playbooks/bootstrap.yml"], stream=True, env=age_env)
    finally:
        host.workdir = workdir


# veyqctl's block — or the one affenbande's scripts/connect.py wrote before it, which this one replaces.
SSH_BLOCK = re.compile(r"^# >>> (?P<name>\S+) \((?:veyqctl connect|scripts/connect\.py)\)\n(?P<body>.*?)^# <<< (?P=name)\n\n?",
                       re.S | re.M)
SSH_OWNED = re.compile(r"\s*(HostName|User|LocalForward|ExitOnForwardFailure|ServerAliveInterval)\s", re.I)


def connect(host: Host, name: str, address: str, admin_user: str, argocd: bool, admin: bool, make_current: bool) -> None:
    """This machine's way to the server (what `veyqctl connect` does at any time, again after a rebuild): a block in
    ~/.ssh/config — the tunnel to the API and, with host access, to Argo CD and veyq's administration — and the
    cluster as a kubeconfig context."""
    config = Path.home() / ".ssh" / "config"
    text = config.read_text() if config.is_file() else ""
    old = next((m for m in SSH_BLOCK.finditer(text) if m.group("name") == name), None)
    rest = (text[:old.start()] + text[old.end():]) if old else text
    if re.search(rf"(?mi)^\s*Host\s+(\S+\s+)*{re.escape(name)}(\s|$)", rest):
        raise Refused(f"{config} has a `Host {name}` of its own, which veyqctl does not touch: choose another name "
                      f"(--connect-name)")
    kept = [line for line in (old.group("body").splitlines()[1:] if old else []) if line.strip()
            and not SSH_OWNED.match(line)]
    forwards = [f"  LocalForward {CONNECT_PORTS['api']} 127.0.0.1:6443"]
    # Argo CD's and veyq-admin's NodePorts at the server's own address: they answer there in every kube-proxy mode.
    if argocd:
        forwards.append(f"  LocalForward {CONNECT_PORTS['argocd']} {address}:30443")
    if admin:
        forwards.append(f"  LocalForward {CONNECT_PORTS['admin']} {address}:{ADMIN_HOST_PORT}")
    block = "\n".join([f"# >>> {name} (veyqctl connect)", f"Host {name}", f"  HostName {address}",
                       f"  User {admin_user}", *kept, *forwards, "  ExitOnForwardFailure yes",
                       "  ServerAliveInterval 30", f"# <<< {name}"]) + "\n"
    config.parent.mkdir(mode=0o700, exist_ok=True)
    write_private(config, block + ("\n" + rest.lstrip("\n") if rest.strip() else ""))
    checked("SSH", f"`Host {name}` in {config}")

    remote = host.run(["ssh", "-o", "BatchMode=yes", "-o", "ClearAllForwardings=yes", name, "cat ~/.kube/config"]).stdout
    renamed = re.sub(r"(?m)^(\s*(?:- )?(?:name|cluster|user|current-context): )default$", rf"\g<1>{name}", remote)
    renamed, count = re.subn(r"(?m)^(\s*server: )https://127\.0\.0\.1:6443$",
                             rf"\g<1>https://127.0.0.1:{CONNECT_PORTS['api']}", renamed)
    if count != 1:
        raise Refused(f"the server's kubeconfig does not name one API server at https://127.0.0.1:6443 ({count})")
    kubeconfig = Path.home() / ".kube" / "config"
    kubeconfig.parent.mkdir(mode=0o700, exist_ok=True)
    if kubeconfig.is_file():
        for what in ("context", "cluster", "user"):
            host.run(["kubectl", "config", f"delete-{what}", name], check=False, env={"KUBECONFIG": str(kubeconfig)})
    with tempfile.TemporaryDirectory(prefix="veyqctl-kube-") as tmp:
        fresh = Path(tmp) / "config"
        write_private(fresh, renamed)
        paths = f"{kubeconfig}:{fresh}" if kubeconfig.is_file() else str(fresh)
        merged = host.run(["kubectl", "config", "view", "--flatten"], env={"KUBECONFIG": paths}).stdout
    write_private(kubeconfig, merged)
    checked("kubectl", f"the context {name} in {kubeconfig}; it works while the tunnel is open")
    if make_current:
        host.run(["kubectl", "config", "use-context", name], env={"KUBECONFIG": str(kubeconfig)})


def await_synced(host: Host, name: str, application: str, timeout: float) -> None:
    """Argo CD's applications Synced and Healthy, read through a tunnel opened for the wait and closed after."""
    tunnel = host.background(["ssh", "-N", "-o", "ExitOnForwardFailure=yes", name])
    try:
        deadline = time.monotonic() + timeout
        last = ""
        while True:
            listed = host.run(["kubectl", "--context", name, "-n", "argocd", "get", "applications", "-o", "json"],
                              check=False)
            try:
                items = json.loads(listed.stdout or "{}").get("items") or [] if not listed.returncode else []
            except ValueError:
                items = []
            states = {i["metadata"]["name"]: (i.get("status", {}).get("sync", {}).get("status", "?"),
                                              i.get("status", {}).get("health", {}).get("status", "?")) for i in items}
            pending = {n: s for n, s in states.items() if s != ("Synced", "Healthy")}
            if application in states and not pending:
                checked("Argo CD", f"{', '.join(sorted(states))} Synced and Healthy")
                return
            shown = ", ".join(f"{n} {'/'.join(s)}" for n, s in sorted(pending.items())) or "no application yet"
            if shown != last:
                say(f"Waiting for Argo CD: {shown}")
                last = shown
            if time.monotonic() > deadline:
                raise Refused(f"not everything runs after {int(timeout)} s ({shown}). Look with `kubectl --context {name} -n "
                              f"argocd get applications` while `ssh -N {name}` is open, or run the same command again "
                              f"to keep waiting.")
            host.sleep(10)
    finally:
        tunnel.terminate()


def server_prepare(run: InitRun) -> None:
    """4. Setting up: veyq-server — the server half into the checkout, before veyq's: veyq-server verified and
    rendered (once), its secrets encrypted (once), the host key pinned (asked)."""
    host, top, answers = run.host, run.top, run.server_answers
    host.section(Step.DO_SERVER)
    if not (top / ".copier-answers.yml").is_file():
        with tempfile.TemporaryDirectory(prefix="veyqctl-veyq-server-") as tmp:
            template = Path(tmp) / "veyq-server"
            release = verify_server_template(host, template)
            render_server(host, top, template, release["commit"], answers, run.project, run.repo_url, run.revision)
        checked("veyq-server", f"{release['release']}, added to your repository")
    else:
        checked("veyq-server", f"in {top} already (.copier-answers.yml)")
    m = GITHUB_REPO.fullmatch(run.repo_url)
    run.deploy_key = server_secrets(host, top, answers, m.group(1) if m else top.name, run.age_env())
    run.pinned = pin_host_key(host, top, answers["server_address"])
    check_server_system(host, top, answers["server_address"], answers["admin_user"])


def server_finish(run: InitRun, g: GitOps, repo_url: str) -> None:
    """What follows the written halves on the server path, each step said as it comes: veyq's Application listed for
    the root, the deploy key at the forge, the DNS records, commit and push, the bootstrap, this machine connected,
    and the wait until Argo CD reports everything Synced and Healthy."""
    host, top, answers = run.host, run.top, run.server_answers
    name = answers["connect_name"]
    admin = (run.directory / "resources" / "admin-host-access.yaml").is_file()
    list_veyq_application(top, run.directory)
    host.section(Step.DO_ACCESS)
    deploy_key_at_forge(host, repo_url, run.deploy_key)
    host.section(Step.DO_DNS)
    await_dns(host, answers["server_address"], run.domains)
    host.section(Step.DO_PUSH)
    commit_and_push(host, top, run.revision, f"veyq-server and veyq {g.doc['running']}, set up by veyqctl init")
    host.section(Step.DO_BOOTSTRAP)
    bootstrap(host, top, answers, run.pinned, run.age_env())
    host.section(Step.DO_CONNECT)
    connect(host, name, answers["server_address"], answers["admin_user"], answers["host_access"] == "yes", admin,
            answers["make_current"] == "yes")
    await_synced(host, name, run.args.application_name, run.args.sync_timeout)
    host.section(Step.DONE)
    lines = [f"Open the tunnel: ssh -N {name}",
             "Argo CD: " + (f"https://localhost:{CONNECT_PORTS['argocd']}" if answers["host_access"] == "yes"
                            else f"kubectl --context {name} -n argocd port-forward svc/argocd-server 8080:443")
             + ", user admin. Its first password: kubectl --context " + name + " -n argocd get secret "
             "argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d — change it, then delete that "
             "Secret."]
    if admin:
        lines.append(f"veyq's admin page: http://localhost:{CONNECT_PORTS['admin']} — create the Server Owner there.")
    lines.append(f"Your sudo password: sops decrypt --extract '[\"admin_password\"]' {top / SERVER_SECRETS}")
    lines.append(f"Keep the age key ({run.age_env().get('SOPS_AGE_KEY_FILE', 'its file')}) in your password manager. "
                 f"With it and this repository, `veyqctl init` sets the server up again.")
    print("\n".join(f"   {line}" for line in lines), flush=True)


def cmd_connect(args: argparse.Namespace, host: Host) -> int:
    """This machine's SSH host and kubeconfig context for the server the current checkout's veyq-setup.json describes:
    what `init` does at its end, again at any time (a rebuild's new cluster certificate, another machine)."""
    require_programs(host, ["git", "ssh", "kubectl"], "`connect`")
    top = checkout_top(host)
    setup = read_setup(top) if top else {}
    server = setup.get("server") or {}
    if not server.get("server_address") or not server.get("admin_user"):
        raise Refused(f"run `connect` in the repository of a server veyqctl set up ({top or 'this directory'} has no "
                      f"server in its {SETUP_FILE})")
    name = args.connect_name or server.get("connect_name") or top.name
    if not re.fullmatch(r"[A-Za-z0-9._-]+", name):
        raise Refused(f"--connect-name {name}: letters, digits, `.`, `_` and `-`")
    admin = any((v or {}).get("admin_host_access") == "yes" for v in (setup.get("veyq") or {}).values())
    connect(host, name, server["server_address"], server["admin_user"], server.get("host_access") == "yes", admin,
            args.make_current == "yes")
    return 0


# ---- init: every question before the first step, then the steps (ADR 0126) ----------------------------------------

ARGO_APPLICATION = re.compile(r"(?m)^apiVersion:\s*argoproj\.io/v1alpha1\s*$[\s\S]*?^kind:\s*Application\s*$")


def suggested_gitops_directory(host: Host) -> str:
    """Where veyq's half fits the repository this runs in, relative to the current directory: beside the Argo CD
    Applications the repository already holds — `<their common parent>/veyq` when each sits in a directory of its own
    under one parent (`gitops/apps/traefik/application.yaml` → `gitops/apps/veyq`) — else `apps/veyq`."""
    top = checkout_top(host)
    if top is None or not top.is_dir():
        return "apps/veyq"
    parents: set[Path] = set()
    for path in top.rglob("*.yaml"):
        relative = path.relative_to(top)
        if len(relative.parts) > 5 or any(part.startswith(".") or part == "node_modules" for part in relative.parts):
            continue
        try:
            if ARGO_APPLICATION.search(path.read_text(errors="replace")[:4000]) and len(relative.parts) >= 3:
                parents.add(path.parent.parent)
        except OSError:
            continue
    if len(parents) != 1:
        return "apps/veyq"
    target = parents.pop() / "veyq"
    try:
        return os.path.relpath(target, Path.cwd().resolve())
    except ValueError:
        return str(target)


SETUP_FILE = "veyq-setup.json"


class Progress:
    """What a GitOps `init` asked and did, written the moment it is answered or done (ADR 0126: every question before
    the first step; the same command continues an interrupted run, asking nothing twice).

    The answers are the repository's: `veyq-setup.json` at the checkout's root, veyq's half keyed by its directory —
    committed with what they produced, so `init` asks nothing again in that checkout, here or on another machine. Never
    a secret: generated values go into the repository encrypted, or into the hand-over file. Which steps are done is this
    machine's: a file in config_dir(), keyed by checkout and directory, never inside the repository."""

    def __init__(self, checkout: Path, rel: str) -> None:
        self.checkout, self.rel = checkout, rel
        self.setup_path = checkout / SETUP_FILE
        key = f"{checkout}\0{rel}".encode()
        self.path = config_dir() / f"init-{hashlib.sha256(key).hexdigest()[:16]}.json"
        self.setup = read_setup(checkout)
        self.answers: dict[str, str] = dict(self.setup.setdefault("veyq", {}).get(rel) or {})
        try:
            doc = json.loads(self.path.read_text()) if self.path.is_file() else {}
        except (OSError, ValueError):
            doc = {}
        self.server: dict[str, str] = dict(self.setup.get("server") or {})
        # What is this machine's, not the repository's: the age key file's path, the steps done.
        self.doc = {"checkout": str(checkout), "directory": rel, "steps": dict(doc.get("steps") or {}),
                    "local": dict(doc.get("local") or {})}
        # A run to go on with: answers or steps, and veyq's half not written yet.
        self.resumed = bool(self.answers or self.doc["steps"]) and not (checkout / rel / GITOPS_FILE).is_file()

    def get(self, section: str, key: str) -> str | None:
        """A kept answer: from veyq-setup.json's `veyq` (this directory's) or `server` section, or this machine's
        `local` values."""
        value = {"veyq": self.answers, "server": self.server, "local": self.doc["local"]}[section].get(key)
        return value if isinstance(value, str) and value else None

    def drop(self, section: str, *keys: str) -> None:
        """Forget repository answers that no longer apply (veyq-setup.json written only when one was there)."""
        kept = self.answers if section == "veyq" else self.server
        if not any(k in kept for k in keys):
            return
        for k in keys:
            kept.pop(k, None)
        if section == "veyq":
            self.setup["veyq"][self.rel] = kept
        else:
            self.setup["server"] = kept
        atomic_write(self.setup_path, json.dumps(self.setup, indent=2, sort_keys=True) + "\n")

    def put(self, section: str, key: str, value: str) -> None:
        """Keep an answer the moment it is given: the repository's sections in veyq-setup.json, `local` here."""
        if section == "local":
            self.doc["local"][key] = value
            config_dir().mkdir(parents=True, exist_ok=True)
            atomic_write(self.path, json.dumps(self.doc, indent=2) + "\n")
            return
        kept = self.answers if section == "veyq" else self.server
        if kept.get(key) != value:
            kept[key] = value
            if section == "veyq":
                self.setup["veyq"][self.rel] = kept
            else:
                self.setup["server"] = kept
            atomic_write(self.setup_path, json.dumps(self.setup, indent=2, sort_keys=True) + "\n")

    def answer(self, key: str) -> str | None:
        return self.get("veyq", key)

    def done(self, step: str):
        return self.doc["steps"].get(step)

    def mark(self, step: str, data: object = True) -> None:
        self.doc["steps"][step] = data
        config_dir().mkdir(parents=True, exist_ok=True)
        atomic_write(self.path, json.dumps(self.doc, indent=2) + "\n")

    def clear_steps(self) -> None:
        """The steps done, once the run finished; what is this machine's (the age key's path) stays."""
        self.doc["steps"] = {}
        if self.doc["local"]:
            atomic_write(self.path, json.dumps(self.doc, indent=2) + "\n")
        else:
            self.path.unlink(missing_ok=True)

    def start_over(self) -> None:
        """Forget this directory's answers and steps (the person said no to going on)."""
        self.path.unlink(missing_ok=True)
        self.doc["steps"], self.doc["local"], self.answers, self.server = {}, {}, {}, {}
        dropped = [self.setup["veyq"].pop(self.rel, None), self.setup.pop("server", None)]
        if any(part is not None for part in dropped):
            atomic_write(self.setup_path, json.dumps(self.setup, indent=2, sort_keys=True) + "\n")
        self.resumed = False


def atomic_write(path: Path, text: str) -> None:
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(text)
    tmp.replace(path)


def read_setup(checkout: Path) -> dict:
    """The checkout's veyq-setup.json, or an empty one; refused when it is there and not an object of sections."""
    path = checkout / SETUP_FILE
    if not path.is_file():
        return {}
    try:
        doc = json.loads(path.read_text())
    except ValueError as e:
        raise Refused(f"{path} is not valid JSON ({e}). It holds your answers: fix it, or move it away and answer again")
    if not isinstance(doc, dict) or not isinstance(doc.get("veyq", {}), dict):
        raise Refused(f"{path} is not init's answers (an object with a `veyq` section by directory)")
    return doc


def setup_directory(checkout: Path) -> str | None:
    """veyq's directory veyq-setup.json answers for that `init` with no argument takes: the one a run did not finish,
    else the only one there is (written: init says so and writes nothing)."""
    answered = list(read_setup(checkout).get("veyq") or {})
    open_ = [rel for rel in answered if not (checkout / rel / GITOPS_FILE).is_file()]
    if len(open_) == 1:
        return open_[0]
    return answered[0] if len(answered) == 1 and not open_ else None


class Question(NamedTuple):
    """One question `init` asks. Its key is the flag's dest AND the answer's key where it is kept — veyq-setup.json's
    `veyq` or `server` section, or this machine's progress file (`local`): renaming one loses committed answers."""
    key: str
    text: str
    hint: str = ""
    choices: tuple = ()      # an answer outside them is refused
    pattern: str = ""        # an answer must match it in full
    section: str = "veyq"
    flag: str = ""           # the flag, when it is not `--<key>`
    required: bool = True    # False: an empty answer stands (the kubeconfig context: kubectl's current one)

    @property
    def option(self) -> str:
        return self.flag or f"--{self.key.replace('_', '-')}"

    @property
    def yes_no(self) -> bool:
        return self.choices == ("yes", "no")

    def accepts(self, value: str) -> bool:
        return (not self.choices or value in self.choices) and (not self.pattern or bool(re.fullmatch(self.pattern,
                                                                                                        value)))

    def expects(self) -> str:
        return f"one of {', '.join(self.choices)}" if self.choices else f"not {self.text}"


class Q:
    """Every question of `init`, in the order it asks them. Offers read from the cluster or the repository are given
    where it is asked (`default=`); everything else about a question is here."""
    # 1. Setup: what decides the programs the run needs
    TARGET = Question("target", "compose or gitops", "compose: veyq on Docker Compose on this host. gitops: via Argo "
                      "CD in a Kubernetes cluster.", ("compose", "gitops"))
    SERVER = Question("server", "set up a new server too", "yes: veyqctl sets up a fresh server with "
                      "everything veyq needs. no: you already run a Kubernetes cluster with Argo CD.", ("yes", "no"))
    DIRECTORY = Question("gitops", "directory", "Where in your repository veyq's files go.")
    NAME = Question("name", "instance name", "How this computer names the instance (`veyqctl instances`); with a new "
                    "server, also its SSH host and kubectl context (`ssh -N <name>`, `kubectl --context <name>`).")
    SECRETS = Question("secrets", f"secrets: {', '.join(PROVIDERS)}", "Where veyq's passwords and keys are kept.",
                       PROVIDERS)
    # 3. Questions: the server
    SERVER_ADDRESS = Question("server_address", "server address", "The new server's DNS name or IP address. You log "
                              "in to it as root with your SSH key; veyq-server's README says which system it needs.",
                              pattern=f"{HOSTNAME_RE}|{IPV4_RE}", section="server")
    NODE_NAME = Question("node_name", "node name", "The server's name as Kubernetes shows it (`kubectl get nodes`) "
                         "and in the repository's Ansible inventory. Not asked: derived from the address.",
                         pattern=r"[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?", section="server")
    ADMIN_USER = Question("admin_user", "admin user", "Your login on the server. Logging in as root is switched off.",
                          pattern=r"(?!root$|ci$)[a-z_][a-z0-9_-]{0,31}", section="server")
    SSH_KEY_PICK = "your SSH key: its number, or paste a public key"
    SSH_KEY = Question("admin_ssh_public_key", "your SSH public key", "The key you log in to the server with.",
                       pattern=SSH_PUBLIC_KEY_RE, section="server", flag="--admin-ssh-key")
    AGE_KEY = Question("age_key", "age key file (Enter: create a new one)", "The key that encrypts your repository's "
                       "secrets. Keep a copy in your password manager.")
    HOST_ACCESS = Question("host_access", "open Argo CD and veyq's admin page directly while connected to the server",
                           f"yes: while you are connected, both are at localhost:{CONNECT_PORTS['argocd']} and "
                           f"localhost:{CONNECT_PORTS['admin']}, without `kubectl port-forward`. Neither is reachable "
                           "from the internet either way.", ("yes", "no"), section="server")
    CONNECT_NAME = Question("connect_name", "name for the server on this computer", "Used for `ssh -N <name>` and "
                            "`kubectl --context <name>`. Not asked: the instance's name.", pattern=r"[A-Za-z0-9._-]+",
                            section="server")
    MAKE_CURRENT = Question("make_current", "make it kubectl's default context", "yes: plain `kubectl` "
                            "talks to this server from now on. no: use `--context <name>`.", ("yes", "no"),
                            section="local")
    PASSWORD = "your sudo password on the server (hidden, Enter: generate one)"  # never kept: encrypted, only
    # 3. Questions: your cluster
    CONTEXT = Question("context", "kubeconfig context", "The cluster Argo CD runs in.", required=False)
    # 3. Questions: veyq
    DOMAIN = Question("domain", "veyq's domain", "The address veyq is reached at. It cannot be changed later.")
    BUNDLED_STORE = Question("bundled_store", "use the bundled store for photos and files", "yes: veyq runs its own "
                             "S3-compatible store (Garage) beside it, on this cluster — it needs a storage domain, and "
                             "backups keep the files only with a pool. no: you enter an S3-compatible store of your own "
                             "in the admin console after the install; no tenant before it passed its check.",
                             ("yes", "no"))
    STORE_DOMAIN = Question("store_domain", "storage domain", "Where photos and files are uploaded to and downloaded "
                            "from.")
    GATEWAY_CLASS = Question("gateway_class", "GatewayClass", "Which Gateway API implementation handles veyq's "
                             "traffic.")
    GATEWAY_NAMESPACE = Question("gateway_namespace", "namespace of its proxy pods", "Only these pods may reach veyq.")
    GATEWAY_POD_LABEL = Question("gateway_pod_label", "a label of those pods (key=value)", pattern=r"[^=\s]+=\S*")
    TLS = Question("tls", "TLS: acme, shared or none", "acme: certificates from Let's Encrypt, one per address (needs "
                   "cert-manager). shared: one certificate of yours. none: TLS ends in front of the cluster.",
                   ("acme", "shared", "none"))
    ACME_DIRECTORY = Question("acme_directory", "Let's Encrypt: staging or production", "Start with staging: test "
                              "certificates, without production's limits. Switch later with `veyqctl tls production`.",
                              tuple(ACME_DIRECTORIES))
    TLS_SECRET = Question("tls_secret", "name of your TLS Secret")
    ADMIN_HOST_ACCESS = Question("admin_host_access", "open veyq's admin page directly while connected to the server",
                                 "yes: a NodePort beside Argo CD's, so no `kubectl port-forward` is needed. Only where "
                                 "the host firewall keeps NodePorts closed from outside, as veyq-server's does.",
                                 ("yes", "no"))
    ADMIN_HOST_SOURCE = Question("admin_host_source", "the node's pod network gateway (a /32)", "Where the tunnel's "
                                 "traffic comes from. The offer is usually right.")
    # 3. Questions: Argo CD and updates
    PROJECT = Question("project", "Argo CD project", "The Argo CD project veyq belongs to.")
    SECRET_STORE = Question("secret_store", "name of the SecretStore")
    RELEASE = Question("release", "updates")


class Step:
    """The headings `init` shows, in the order INIT_INTRO promises."""
    SETUP = "1. Setup"
    PROGRAMS = "2. Programs"
    ASK_SERVER = "3. Questions: the server"
    ASK_CLUSTER = "3. Questions: your cluster"
    ASK_VEYQ = "3. Questions: veyq"
    ASK_ARGO = "3. Questions: Argo CD and updates"
    DO_SERVER = "4. Setting up: veyq-server"
    DO_VEYQ = "4. Setting up: veyq"
    DO_ACCESS = "4. Setting up: Argo CD's access to your repository"
    DO_DNS = "4. Setting up: DNS"
    DO_PUSH = "4. Setting up: commit and push"
    DO_BOOTSTRAP = "4. Setting up: the server"
    DO_CONNECT = "4. Setting up: this computer"
    DONE = "Done"


INIT_INTRO = """
veyqctl init — sets up a veyq instance: on Docker Compose on this host, or via a Git repository an Argo CD syncs.

  1. Setup      what to create, and where
  2. Programs   check what tools are missing
  3. Questions  how you want to configure your veyq instance
  4. Setting up perform the actions to set up your veyq instance

Your desired configuration is kept in the repository's veyq-setup.json so you can rebuild your whole environment with
this file"""
SERVER_INTRO = """
   With a new server, veyqctl does this after your answers: it adds veyq-server to your repository, encrypts the
   server's passwords, asks you to confirm the server's fingerprint, adds veyq, gives Argo CD read access to your
   repository, waits for your DNS records, commits and pushes, sets up the server, connects this computer and waits
   until everything runs. Steps that cannot be undone are asked first."""

# init's flags that shape what it writes and that it asks no question for (a default, or derived): kept in
# veyq-setup.json like an answer and taken from it when not given, so a resumed or repeated run writes the same.
SETTLED_FLAGS = ("namespace", "storage_class", "backup_size", "secret_store_kind", "remote_prefix",
                 "application_name", "argocd_namespace")
# Those that mean something only with --secrets external-secrets: settled once the secrets answer is known, and kept
# only then — a sops or a Secret-less instance neither records nor shows a store it has none of (raddatz.me,
# 2026-11-11: "ClusterSecretStore" shown on a sops run, never asked).
EXTERNAL_SECRETS_FLAGS = ("secret_store_kind", "remote_prefix")


def init_defaults() -> dict:
    """`init`'s flag defaults, as the parser has them — one source for what "not given" means."""
    return vars(parser().parse_args(["init"]))


class InitRun:
    """One GitOps `init`: where it writes, every answer as it is given, and what one step hands the next. The answers
    are kept the moment they exist (Progress), so an interrupted run asks nothing twice."""

    def __init__(self, args: argparse.Namespace, host: Host, directory: Path, progress: Progress | None = None) -> None:
        self.args, self.host, self.directory = args, host, directory
        self.top = repository_of(host, directory)
        self.rel = directory.relative_to(self.top).as_posix()
        if self.rel in ("", "."):
            raise Refused("veyq's files need a directory of their own in your repository, not its top level")
        self.progress = progress or Progress(self.top, self.rel)
        self.defaults = init_defaults()
        # 1. Setup
        self.name = self.provider = ""
        self.server = False
        # 3. Questions: the server (its answers, as veyq-server's Copier questions take them)
        self.server_answers: dict[str, str] = {}
        # 3. Questions: the cluster and veyq
        self.context = self.domain = self.store_domain = self.gateway_class = self.peer_namespace = ""
        self.peer_label = self.tls = self.acme_directory = self.tls_secret = ""
        self.admin_host, self.admin_source, self.garage = "no", "", True
        # 3. Questions: Argo CD and updates
        self.project = self.secret_store = self.repo_url = self.revision = self.selector = ""
        self.release_plan: Plan | None = None
        # 4. What one step hands the next on the server path
        self.deploy_key = self.pinned = ""
        self.domains: list[str] = []

    @property
    def namespace(self) -> str:
        return self.args.namespace or "veyq"

    def ask(self, q: Question, default: str = "", hint: str = "", given: str | None = None) -> str:
        """The answer to `q`: `given` (else its flag), else what veyq-setup.json keeps (shown as taken), else asked at
        a terminal (Enter takes `default`; asked again, twice at most, while it does not fit) — refused when there is
        none or it does not fit; kept at once."""
        flag = given if given is not None else getattr(self.args, q.key, None)
        value = "" if flag in (None, "", False) else str(flag)
        if not value:
            kept = self.progress.get(q.section, q.key)
            if kept:
                value = kept
                if q.section != "local":
                    print(f"   {q.text}: {kept}   (from {SETUP_FILE})", flush=True)
        tries = 0
        while not value:
            answer = self.host.prompt(q.text, default, hint or q.hint, yes_no=q.yes_no)
            if answer is None:  # no terminal, or the input ended: the offer, if any
                value = default
                break
            if q.yes_no:
                answer = yes_or_no(answer)
            if q.accepts(answer) or tries == 2:
                value = answer
                break
            print("   That does not look right, try again.", flush=True)
            tries += 1
        if not value and not q.required:
            return ""
        if not value:
            raise Refused(f"{q.option} is required ({q.text}): pass it, or run `init` at a terminal")
        if not q.accepts(value):
            raise Refused(f"{q.option} {value}: {q.expects()}")
        self.progress.put(q.section, q.key, value)
        return value

    def kept(self, key: str, shown: str) -> str | None:
        """An answer veyq-setup.json keeps for a value init finds rather than asks, shown as it is taken."""
        value = self.progress.get("veyq", key)
        if value:
            print(f"   {shown}: {value}   (from {SETUP_FILE})", flush=True)
        return value

    def settle(self, keys: Iterable[str] | None = None) -> None:
        """SETTLED_FLAGS (or `keys` of them): a flag given is kept; one left at its default takes what veyq-setup.json
        keeps, and says so only where that differs from the default — a default kept is no news."""
        for key in (keys if keys is not None else [k for k in SETTLED_FLAGS if k not in EXTERNAL_SECRETS_FLAGS]):
            given, default = getattr(self.args, key), self.defaults[key]
            if given == default:
                kept = self.progress.get("veyq", key)
                if kept is not None:
                    given = (kept == "true") if isinstance(default, bool) else kept
                    setattr(self.args, key, given)
                    shown_default = ("true" if default else "false") if isinstance(default, bool) else str(default)
                    if str(kept) != shown_default:
                        print(f"   --{key.replace('_', '-')}: {kept}   (from {SETUP_FILE})", flush=True)
            if given not in (None, ""):
                self.progress.put("veyq", key, ("true" if given else "false") if isinstance(default, bool)
                                  else str(given))

    def settle_secrets(self) -> None:
        """The external-secrets flags, once the secrets answer is known: settled for external-secrets, else dropped
        from veyq-setup.json where an earlier run kept them."""
        if self.provider == "external-secrets":
            self.settle(EXTERNAL_SECRETS_FLAGS)
        else:
            self.progress.drop("veyq", *EXTERNAL_SECRETS_FLAGS)

    def age_env(self) -> dict[str, str]:
        """The environment of a command that decrypts: the age key file this machine keeps for the repository."""
        key_file = self.progress.get("local", "age_key_file")
        return {"SOPS_AGE_KEY_FILE": key_file} if key_file else {}


def attach_progress(host: Host, checkout: Path, rel: str) -> Progress:
    """This directory's progress; when a run stopped here, say so and ask whether to go on (yes without a terminal:
    the point of resuming is that nothing need be typed again)."""
    progress = Progress(checkout, rel)
    if progress.resumed:
        host.section("A previous run stopped here")
        print(f"   Your answers so far are in {progress.setup_path}.", flush=True)
        if host.confirm("Continue where it stopped (no: start over and answer everything again)?") is False:
            progress.start_over()
    return progress


def cmd_init(args: argparse.Namespace, host: Host) -> int:
    """Create an instance, list it and make it current: Compose (what `install` does) or GitOps (ADR 0126). Every
    question comes before the first step: the setup answers, then the programs, then the rest."""
    print(INIT_INTRO, flush=True)
    target = "compose" if args.compose else ("gitops" if args.gitops else None)
    resume: tuple[Path, str] | None = None
    if not args.compose and host.which("git"):
        # A GitOps run started here before and stopped? Its answers are in the checkout's veyq-setup.json.
        with host.quietly():
            top_here = checkout_top(host)
        rel = setup_directory(top_here) if top_here and not args.gitops else None
        if rel:
            target, resume = "gitops", (top_here, rel)
    if target is None:
        host.section(Step.SETUP)
        target = (host.prompt(Q.TARGET.text, hint=Q.TARGET.hint) or "").strip().lower()
        if target not in Q.TARGET.choices:
            raise Refused("`init` needs to know where veyq runs: --compose (Docker Compose on this host) or --gitops DIR "
                          "(through Argo CD; DIR is where in your repository veyq's files go)")
    if target == "compose":
        return compose_init(args, host)
    if args.unsigned_local:
        raise Refused("--unsigned-local is for Compose: through Argo CD, veyq installs only what verifies")
    # git finds the checkout, before any other question: the one program checked ahead of the rest.
    require_programs(host, ["git"], "`init --gitops`", wait=True)
    host.quiet = True  # until the steps begin, only the questions are shown (gitops_init switches it back)
    if resume:
        host.section(Step.SETUP)
        progress = attach_progress(host, *resume)
        return gitops_init(InitRun(args, host, (resume[0] / resume[1]).resolve(), progress))
    if args.gitops:  # else the section began with the target's question
        host.section(Step.SETUP)
    if args.server is None:
        # Offered `yes` in a repository with nothing committed yet: the server path starts from an empty one.
        listed = host.run(["git", "ls-files"], check=False)
        offer = "yes" if not listed.returncode and not (listed.stdout or "").strip() else "no"
        args.server = yes_or_no(host.prompt(Q.SERVER.text, offer, Q.SERVER.hint, yes_no=True) or offer)
    if args.server not in Q.SERVER.choices:
        raise Refused(f"{Q.SERVER.option} {args.server}: {Q.SERVER.expects()}")
    offered = suggested_gitops_directory(host)
    top_here = checkout_top(host) if args.server == "yes" else None
    if top_here:
        offered = os.path.relpath(top_here / SERVER_APPS / "veyq", Path.cwd().resolve())
    directory = args.gitops or host.prompt(Q.DIRECTORY.text, offered, Q.DIRECTORY.hint) or offered
    if not directory:
        raise Refused("`init --gitops DIR` needs DIR: where in your Git repository veyq's files go")
    path = Path(directory).expanduser().resolve()
    top = repository_of(host, path)
    rel = path.relative_to(top).as_posix() if path != top else ""
    return gitops_init(InitRun(args, host, path, attach_progress(host, top, rel) if rel else None))


def compose_init(args: argparse.Namespace, host: Host) -> int:
    """`init --compose`: `install` into --home, then listed and made current."""
    home = Path(args.home or DEFAULT_HOME)
    name = args.name or "veyq"
    name_is_free(name, home)
    host.section(Step.PROGRAMS)
    require_programs(host, compose_programs("install", args.unsigned_local), "`init --compose`", wait=True)
    args.home = str(home)
    host.workdir = home
    result = cmd_install(args, host)
    if result == 0:
        register(name, home)
    return result


def gitops_init(run: InitRun) -> int:
    """The GitOps `init`, in the order INIT_INTRO promises: the setup answers, the programs, every other question, then
    the steps — none of which runs before the last question is answered."""
    host = run.host
    if run.progress.answers and (run.directory / GITOPS_FILE).is_file():
        return continue_written(run)
    if run.directory.exists() and any(run.directory.iterdir()):
        raise Refused(f"{run.directory} holds files already, and `init` never overwrites. To let veyqctl manage veyq "
                      f"files you wrote yourself, add a {GITOPS_FILE} as {EXISTING_HALF_DOCS} explains, then run "
                      f"`veyqctl check`. {NOTHING_WRITTEN}")
    ask_setup(run)
    host.section(Step.PROGRAMS)
    # Every program the run starts, now that the answers above decided which (ADR 0126): waited for, never installed.
    needed = [*GITOPS_PROGRAMS["init"], *({"sops": ["sops"], "sealed-secrets": ["kubeseal"]}.get(run.provider, [])),
              *(SERVER_PROGRAMS if run.server else ())]
    require_programs(host, needed, "`init --gitops`", wait=True)
    print(f"   All installed: {', '.join(dict.fromkeys(needed))}", flush=True)
    if run.server and not host.which("gh"):
        print("   gh is not installed (optional): you will add Argo CD's deploy key on GitHub yourself.", flush=True)
    if run.server:
        ask_server(run)
    cluster = ask_cluster(run)
    ask_veyq(run, cluster)
    if cluster.reachable:
        host.quiet = False
        check_cluster(host, run.context, run.gateway_class, run.args.storage_class or "", run.tls == "acme")
        host.quiet = True
    ask_argo(run, cluster)
    # Every question is answered: the steps begin, echoed — the server half first (veyq's encrypts with its rules).
    host.quiet = False
    if run.server:
        server_prepare(run)
    return finish_init(run, write_half(run))


def ask_setup(run: InitRun) -> None:
    """1. Setup: the name, whether the server is set up too, and where the secrets come from — what decides the
    programs the run needs."""
    run.settle()
    run.name = run.ask(Q.NAME, run.top.name)
    name_is_free(run.name, run.directory)
    run.server = run.ask(Q.SERVER, "no") == "yes"
    if run.server:
        print(SERVER_INTRO, flush=True)
        # veyq-server's repository encrypts with SOPS (KSOPS in Argo CD), and its root syncs gitops/apps/.
        if run.args.secrets not in (None, "sops"):
            raise Refused(f"--secrets {run.args.secrets}: with a new server, the secrets are encrypted with sops (the "
                          f"server decrypts them with KSOPS)")
        run.args.secrets = "sops"
        if (run.top / SERVER_APPS) not in run.directory.parents:
            raise Refused(f"with a new server, veyq's files go under {run.top / SERVER_APPS}, which Argo CD reads "
                          f"(e.g. {run.top / SERVER_APPS / 'veyq'})")
    has_sops = run.server or any((p / ".sops.yaml").is_file() for p in [run.directory, *run.directory.parents]
                                 if p == run.top or run.top in p.parents)
    run.provider = run.ask(Q.SECRETS, "sops" if has_sops else "", Q.SECRETS.hint + (
        " Your repository has a .sops.yaml, so sops fits." if has_sops and not run.server else ""))
    if run.provider == "sops" and not has_sops:
        raise Refused(f"--secrets sops needs a .sops.yaml (in {run.top} or above {run.directory}) that names your keys. "
                      f"{NOTHING_WRITTEN}")
    run.settle_secrets()


class ClusterView(NamedTuple):
    """What `init` reads from the cluster to offer answers: nothing when it is not reachable or not there yet."""
    kubectl: list[str]
    reachable: bool
    classes: list[tuple[str, str]]   # (GatewayClass, controller)
    cert_manager: bool
    argocd_nodeport: bool


def ask_cluster(run: InitRun) -> ClusterView:
    """3. Questions: your cluster — its kubeconfig context, offered as kubectl's current one, then what it offers."""
    host, args = run.host, run.args
    if run.server:
        given = run.server_answers["connect_name"]  # the context `connect` will write; the cluster is not there yet
    else:
        host.section(Step.ASK_CLUSTER)
        given = None
    current = "" if run.server else (host.run(["kubectl", "config", "current-context"], check=False).stdout or "").strip()
    run.context = run.ask(Q.CONTEXT, current, given=given if given is not None else args.context)
    k = kubectl(run.context)
    if args.no_cluster_check or run.server:  # not reachable yet, or not there yet: nothing is read from it
        return ClusterView(k, False, [], False, False)
    listed = host.run(k + ["get", "gatewayclass", "-o", "jsonpath={range .items[*]}{.metadata.name} "
                           "{.spec.controllerName}{\"\\n\"}{end}"], check=False)
    classes = [tuple(line.split(" ", 1)) for line in (listed.stdout or "").splitlines()
               if " " in line] if not listed.returncode else []
    cert_manager = not host.run(k + ["get", "crd", "certificates.cert-manager.io", "-o", "name"], check=False).returncode
    nodeport = (host.run(k + ["-n", args.argocd_namespace, "get", "svc", "argocd-server", "-o", "jsonpath={.spec.type}"],
                         check=False).stdout or "").strip() == "NodePort"
    return ClusterView(k, True, classes, cert_manager, nodeport)


def ask_veyq(run: InitRun, cluster: ClusterView) -> None:
    """3. Questions: veyq — its domains, the gateway, TLS and the admin page's way in."""
    host, args = run.host, run.args
    host.section(Step.ASK_VEYQ)
    run.domain = run.ask(Q.DOMAIN)
    run.garage = run.ask(Q.BUNDLED_STORE, "yes", given="no" if args.no_bundled_store else None) == "yes"
    run.store_domain = run.ask(Q.STORE_DOMAIN, f"store.{run.domain}") if run.garage else ""
    # A veyq-server cluster is not there yet, but what it will be is known: Traefik, in `traefik` — not asked.
    known = {"gateway_class": "traefik", "gateway_namespace": "traefik",
             "gateway_pod_label": "app.kubernetes.io/name=traefik"} if run.server else {}
    classes = cluster.classes
    run.gateway_class = run.ask(Q.GATEWAY_CLASS, classes[0][0] if len(classes) == 1 else "",
                                Q.GATEWAY_CLASS.hint[:-1] + (f" (in your cluster: {', '.join(c for c, _ in classes)})."
                                                             if classes else "."),
                                given=args.gateway_class or known.get("gateway_class"))
    # Traefik's data-plane pods are its own Deployment's: offered from the cluster when the class is Traefik's.
    traefik_ns = ""
    if dict(classes).get(run.gateway_class, "").split("/", 1)[0] == "traefik.io":  # the controller's domain
        found = host.run(cluster.kubectl + ["get", "deploy", "-A", "-l", "app.kubernetes.io/name=traefik", "-o",
                                            "jsonpath={.items[0].metadata.namespace}"], check=False)
        traefik_ns = (found.stdout or "").strip() if not found.returncode else ""
    run.peer_namespace = run.ask(Q.GATEWAY_NAMESPACE, traefik_ns,
                                 given=args.gateway_namespace or known.get("gateway_namespace"))
    run.peer_label = run.ask(Q.GATEWAY_POD_LABEL, "app.kubernetes.io/name=traefik" if traefik_ns else "",
                             given=args.gateway_pod_label or known.get("gateway_pod_label"))
    run.tls = run.ask(Q.TLS, "acme" if cluster.cert_manager or run.server else "")
    if run.tls == "acme":  # no account e-mail: Let's Encrypt keeps no contact since June 2025
        run.acme_directory = run.ask(Q.ACME_DIRECTORY, "staging" if run.server else "production")
    else:
        run.acme_directory = "production"
    run.tls_secret = run.ask(Q.TLS_SECRET) if run.tls == "shared" else ""
    # On a new server the tunnel answer covers veyq's admin page too, and its node's pod network is known: k3s gives
    # its first node 10.42.0.0/24 (measured on a veyq-server cluster, 2026-11-10).
    offered = "yes" if cluster.argocd_nodeport else "no"
    run.admin_host = run.ask(Q.ADMIN_HOST_ACCESS, offered, Q.ADMIN_HOST_ACCESS.hint + (
        "" if offered == "yes" else " Without it, reach it with `kubectl port-forward`."),
        given=args.admin_host_access or run.server_answers.get("host_access"))
    if run.admin_host == "yes":
        cidr = (host.run(cluster.kubectl + ["get", "nodes", "-o", "jsonpath={.items[0].spec.podCIDR}"],
                         check=False).stdout or "") if cluster.reachable else ""
        run.admin_source = run.ask(Q.ADMIN_HOST_SOURCE, node_gateway(cidr), given=args.admin_host_source or (
            node_gateway("10.42.0.0/24") if run.server else None))


def ask_argo(run: InitRun, cluster: ClusterView) -> None:
    """3. Questions: Argo CD and updates — the project, the store (external-secrets), where Argo CD reads the
    repository from, and which releases `upgrade` follows."""
    host, args = run.host, run.args
    # The project: offered from the cluster — its one project besides `default` (one of your own limits sources and
    # destinations, `default` does not), else `default`; on a new server, the instance's own.
    projects: list[str] = []
    if cluster.reachable:
        listed = host.run(cluster.kubectl + ["-n", args.argocd_namespace, "get", "appprojects", "-o", "name"],
                          check=False)
        projects = [line.split("/", 1)[-1] for line in (listed.stdout or "").split() if not listed.returncode]
    own = [p for p in projects if p != "default"]
    host.section(Step.ASK_ARGO)
    own_project = re.sub(r"[^a-z0-9.-]", "-", run.name.lower()).strip("-.") or "server"
    run.project = run.ask(Q.PROJECT, own_project if run.server else own[0] if len(own) == 1 else "default")
    if run.provider == "external-secrets":
        run.secret_store = run.ask(Q.SECRET_STORE)
    # Where Argo CD reads the repository from, and the branch it follows: found, never asked (flags override).
    run.repo_url = args.repo_url or run.kept("repo_url", "repository URL") or (
        git(host, run.top, "remote", "get-url", "origin", check=False).stdout or "").strip()
    github = GITHUB_REPO.fullmatch(run.repo_url)
    if run.server and github:  # Argo CD reads it with the deploy key: over SSH
        run.repo_url = f"git@github.com:{github.group(1)}.git"
    if not run.repo_url:
        raise Refused("--repo-url is required: the URL Argo CD reads your repository from (there is no `origin` remote)")
    run.progress.put("veyq", "repo_url", run.repo_url)
    run.revision = args.revision or run.kept("revision", "branch") or (
        git(host, run.top, "rev-parse", "--abbrev-ref", "HEAD", check=False).stdout or "").strip()
    if run.revision in ("", "HEAD"):  # an empty repository: the branch its first commit goes to
        run.revision = (git(host, run.top, "symbolic-ref", "--short", "HEAD", check=False).stdout or "").strip()
    if run.revision in ("", "HEAD"):
        raise Refused("--revision is required: the branch Argo CD follows (your checkout is on no branch)")
    run.progress.put("veyq", "revision", run.revision)
    # Which releases `upgrade` follows: offered as the newest release's major, so a new major is entered only when its
    # owner sets `latest` or the next major, knowingly (ADR 0085: a major removes something).
    newest = plan(host, args.source, "latest")
    run.selector = check_selector(run.ask(Q.RELEASE, str(newest.selection.major), (
        f"Which versions `veyqctl upgrade` installs: {newest.selection.major} stays on version "
        f"{newest.selection.major}, latest always takes the newest, {newest.selection.major}."
        f"{newest.selection.minor} stays exactly there.")))
    run.release_plan = newest._replace(selector=run.selector, selection=select(newest.listed, run.selector,
                                                                             newest.source.describe()))


def write_half(run: InitRun) -> GitOps:
    """4. Setting up: veyq — the release verified, the files assembled and rendered, then written. A value generated
    once is never made again: an interrupted run takes the ones it handed over."""
    host, args, p = run.host, run.args, run.release_plan
    host.section(Step.DO_VEYQ)
    say_selection(p)
    manifest = fetch_manifest(host, p.source, p.selection, False)
    chart = chart_repository(manifest.app)
    pins = verify_gitops_release(host, manifest, chart)
    handed_before = run.progress.done("handed")
    if handed_before and Path(handed_before).is_file():
        values = json.loads(Path(handed_before).read_text())["secrets"]
    else:
        values = {secret: {key: make() for key, make in keys.items()} for secret, keys in SECRET_VALUES.items()}
    files = gitops_files(run, pins, chart, values)
    g = GitOps(run.directory, {"target": "gitops", "release": run.selector, "secrets": run.provider,
                               "application": "application.yaml", "values": "values.yaml", "chart": chart,
                               "namespace": run.namespace, "context": run.context or "", "source": args.source or "",
                               "running": p.selection.release, "pins": pins})
    render(host, chart, pins["chart"], run.namespace, files["values.yaml"])
    if run.provider != "sops":
        remote = ({remote_key(args.remote_prefix, k): v for keys in values.values() for k, v in keys.items()}
                  if run.provider == "external-secrets" else None)
        handed = Path(handed_before) if handed_before and Path(handed_before).is_file() else \
            hand_over(run.name, run.namespace, values, remote)
        run.progress.mark("handed", str(handed))
    (run.directory / "resources").mkdir(parents=True, exist_ok=True)
    for relative, text in files.items():
        (run.directory / relative).write_text(text)
    write_gitops(g)
    checked("files", f"veyq's, written into {run.rel} (release {p.selection.release})")
    run.progress.mark("written")
    return g


def gitops_files(run: InitRun, pins: dict[str, str], chart: str, values: dict[str, dict[str, str]]) -> dict[str, str]:
    """veyq's files by their path in its directory: the Application, the values, and resources/ — what the chart needs
    and does not create, the two Secrets in the form the provider wants."""
    host, args, namespace = run.host, run.args, run.namespace
    files: dict[str, str] = {
        # The Application's name is the cluster's, never the instance's: `--name` is how this machine lists it.
        "application.yaml": application_text(name=args.application_name, argocd_namespace=args.argocd_namespace,
                                             project=run.project, chart=chart, digest=pins["chart"], rel=run.rel,
                                             repo_url=run.repo_url, revision=run.revision, namespace=namespace,
                                             values="values.yaml"),
        "values.yaml": stack_values(run.release_plan.selection.release, pins, domain=run.domain,
                                    store_domain=run.store_domain, garage=run.garage, gateway_class=run.gateway_class,
                                    tls=run.tls, tls_secret=run.tls_secret,
                                    peer_namespace=run.peer_namespace, peer_label=run.peer_label,
                                    provider=run.provider, acme_directory=run.acme_directory),
        "resources/namespace.yaml": namespace_text(namespace),
        "resources/backup-pvc.yaml": backup_claim_text(namespace, args.storage_class or "", args.backup_size),
    }
    resources, generators = ["namespace.yaml", "backup-pvc.yaml"], []
    if run.admin_host == "yes":
        files["resources/admin-host-access.yaml"] = admin_host_access_text(namespace, run.admin_source)
        resources.append("admin-host-access.yaml")
    pairs = ((SECRETS_SECRET, "secrets"), (CONFIG_SECRET, "config-secret-key"))
    if run.provider == "sops":
        for secret, stem in pairs:
            target = run.directory / "resources" / f"{stem}.sops.yaml"
            encrypted = sops_run(host, ["sops", "encrypt", "--filename-override", str(target), "--input-type", "yaml",
                                        "--output-type", "yaml", "/dev/stdin"], target,
                                 stdin=secret_text(secret, namespace, values[secret]).encode()).stdout
            if not encrypted or "ENC[" not in encrypted:
                raise Refused(f"sops did not encrypt {target.name}: check the rules in {run.top}/.sops.yaml. "
                              f"{NOTHING_WRITTEN}")
            files[f"resources/{target.name}"] = encrypted
        files["resources/secret-generator.yaml"] = KSOPS_GENERATOR
        generators.append("secret-generator.yaml")
    elif run.provider == "sealed-secrets":
        for secret, stem in pairs:
            sealed = host.run(["kubeseal", *(["--context", run.context] if run.context else []), "--format", "yaml"],
                              stdin=secret_text(secret, namespace, values[secret]).encode()).stdout
            if "SealedSecret" not in (sealed or ""):
                raise Refused(f"kubeseal returned no SealedSecret for {secret}. {NOTHING_WRITTEN}")
            files[f"resources/{stem}.sealed.yaml"] = sealed
            resources.append(f"{stem}.sealed.yaml")
    elif run.provider == "external-secrets":
        for secret, stem in pairs:
            files[f"resources/{stem}.external.yaml"] = external_secret_text(secret, namespace, run.secret_store,
                                                                           args.secret_store_kind, args.remote_prefix)
            resources.append(f"{stem}.external.yaml")
    files["resources/kustomization.yaml"] = (
        "# What the chart expects and does not create, written by veyqctl (ADR 0126).\n"
        "apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources:\n"
        + "".join(f"  - {f}\n" for f in resources)
        + ("generators:\n" + "".join(f"  - {f}\n" for f in generators) if generators else ""))
    return files


def continue_written(run: InitRun) -> int:
    """`init` in a checkout whose veyq files are written: a flag differing from an answer is refused (`init` changes
    nothing written); on the server path, the steps that are this machine's run again where not done."""
    defaults = run.defaults
    differs = [f"--{key.replace('_', '-')} {getattr(run.args, key)} (answered: {value})"
               for key, value in run.progress.answers.items()
               if getattr(run.args, key, None) and getattr(run.args, key) != defaults.get(key)  # given, not a default
               and str(getattr(run.args, key)) != value]
    if differs:
        raise Refused(f"{run.directory} is set up already, and {'; '.join(differs)} differs from your earlier answer. "
                      f"`init` does not change it: `upgrade` installs a newer release; anything else, edit the files. "
                      f"{NOTHING_WRITTEN}")
    if not run.progress.done("written"):
        say(f"veyq's files are in {run.rel}, written from {SETUP_FILE}'s answers: nothing to write.")
    answers = run.progress.answers
    run.name = answers.get("name") or run.top.name
    run.server = answers.get("server") == "yes"
    if run.server:  # the server's steps are this machine's: they run again where not done
        ask_server(run)
        run.project, run.repo_url, run.revision = (answers.get(k, "") for k in ("project", "repo_url", "revision"))
        run.domains = [d for d in (answers.get("domain"), answers.get("store_domain")) if d]
        run.host.quiet = False
        server_prepare(run)
    return finish_init(run, read_gitops(run.directory))


def finish_init(run: InitRun, g: GitOps) -> int:
    """What follows the written files — listed on this machine, what is left to the person or, with the server, the
    server's steps — reached again by a run that resumes after the files were written."""
    host, args = run.host, run.args
    provider, context = g.doc["secrets"], g.doc.get("context") or None
    handed = run.progress.done("handed")
    store = run.progress.get("veyq", "secret_store") or args.secret_store or ""
    repo_url = (run.progress.get("veyq", "repo_url") if run.server else None) or args.repo_url or (
        git(host, run.top, "remote", "get-url", "origin", check=False).stdout or "").strip()
    register(run.progress.get("veyq", "name") or args.name or run.top.name, run.directory)
    if handed:
        say(f"The generated values are in {handed} (0600), once: "
            + ("put each under its remote key into the store `" + store + "` reads, " if provider == "external-secrets"
               else "create the two Secrets from it in the namespace, " if provider == "none"
               else "they are sealed into the repository; ")
            + "then move the file off this machine.")
    say("Keep veyq's config-secret key in your password manager too: without it, no backup can be read"
        + (f". Read it with: sops decrypt --extract '[\"stringData\"][\"{CONFIG_SECRET_KEY}\"]' "
           f"{(run.directory / 'resources/config-secret-key.sops.yaml').relative_to(run.top)}" if provider == "sops"
           else f" (`{CONFIG_SECRET_KEY}` in the Secret {CONFIG_SECRET})") + ".")
    if run.server:
        if not run.domains:
            run.domains = [d for d in (run.domain, run.store_domain) if d]
        server_finish(run, g, repo_url)
    else:
        heading("Next")
        say(f"1. Review, then `git -C {run.top} add {run.rel} {SETUP_FILE} && git commit`, and push.")
        say(f"2. Give Argo CD read access to {repo_url} (a deploy key).")
        say(f"3. Register the Application once: `{' '.join(kubectl(context))} apply -f "
            f"{run.directory / 'application.yaml'}`")
    run.progress.clear_steps()
    return 0


def ask_server(run: InitRun) -> None:
    """3. Questions: the server (what a setup script asked), each kept in veyq-setup.json's `server` section; the age
    key's path and the context choice are this machine's. No action happens here but a new age key."""
    host = run.host
    host.section(Step.ASK_SERVER)
    a = run.server_answers
    a["server_address"] = run.ask(Q.SERVER_ADDRESS)
    address = a["server_address"]
    # Not asked: k3s needs one fixed name per node (the bootstrap waits for it), but nothing of veyq's depends on
    # it — the chart pins no pod to a node. Derived from the address; --node-name or an earlier answer wins.
    derived = "server" if address[0].isdigit() else address.split(".")[0]
    a["node_name"] = run.ask(Q.NODE_NAME, given=run.args.node_name or run.progress.get("server", "node_name")
                             or derived)
    own = re.sub(r"[^a-z0-9_-]", "", os.environ.get("USER", "").split(".")[0].lower())
    a["admin_user"] = run.ask(Q.ADMIN_USER, own if own not in ("root", "ci") else "")
    if not (run.top / SERVER_SECRETS).is_file():
        # The login's password beside the login (owner 2026-11-11). Never kept, never a flag: it goes into the
        # repository encrypted, only.
        a["admin_password"] = host.secret(Q.PASSWORD) or ""
    a["admin_ssh_public_key"] = run.ask(Q.SSH_KEY, given=pick_ssh_key(run))
    key_file = age_key_file(run)
    recipient = age_recipient_of(host, key_file)
    print(f"\n   Using the age key in {key_file}.", flush=True)
    kept = run.progress.get("server", "age_recipient")
    if kept and kept != recipient:
        raise Refused(f"{key_file} is not the age key your repository's secrets are encrypted to ({SETUP_FILE} names "
                      f"{kept}, this file is {recipient}): pass the right key file with --age-key")
    a["age_recipient"] = recipient
    run.progress.put("server", "age_recipient", recipient)
    run.progress.put("local", "age_key_file", str(key_file))
    a["host_access"] = run.ask(Q.HOST_ACCESS, "yes")
    # Not asked: one repository, one server, one instance — the server takes the instance's name as its SSH host
    # and kubectl context. --connect-name or an earlier answer wins.
    a["connect_name"] = run.ask(Q.CONNECT_NAME, given=run.args.connect_name or run.progress.get("server", "connect_name")
                                or run.name)
    a["make_current"] = run.ask(Q.MAKE_CURRENT, "no")


def pick_ssh_key(run: InitRun) -> str | None:
    """The admin's public key: the flag, else what veyq-setup.json keeps, else one of the operator's keys picked by its
    number (or pasted) — the key alone, its comment is the operator's."""
    given = run.args.admin_ssh_public_key
    if not given and not run.progress.get("server", "admin_ssh_public_key"):
        candidates = ssh_key_candidates(run.host)
        if candidates:
            print("\n   Your public SSH keys:", flush=True)
            for i, (key, label) in enumerate(candidates, 1):
                print(f"   {i}) {key.split()[0]} …{key.split()[1][-12:]}  {label}", flush=True)
        picked = run.host.prompt(Q.SSH_KEY_PICK, "1" if len(candidates) == 1 else "", Q.SSH_KEY.hint) or ""
        if picked.isdigit() and 1 <= int(picked) <= len(candidates):
            given = candidates[int(picked) - 1][0]
        elif picked:
            given = picked
    if given:
        m = re.match(SSH_PUBLIC_KEY_RE, given.strip())
        return m.group(0) if m else given
    return None


def age_key_file(run: InitRun) -> Path:
    """The age key the repository's secrets are encrypted to: --age-key, this machine's kept path, the checkout's
    .age/key.txt, a file named at the terminal — or a new one there, kept only once its copy is in a password
    manager. Its private half stays on this machine; only its public half is in the repository."""
    host = run.host
    key_file = Path(run.args.age_key).expanduser() if run.args.age_key else None
    kept = run.progress.get("local", "age_key_file")
    if key_file is None and kept:
        if Path(kept).is_file():
            key_file = Path(kept)
        else:  # an earlier run's key, deleted since (a fresh start): asked again, not refused
            say(f"The age key an earlier run used, {kept}, is gone: name one, or create a new one.")
    if key_file is None and (run.top / AGE_KEY_FILE).is_file():
        key_file = run.top / AGE_KEY_FILE
    if key_file is None:
        answer = host.prompt(Q.AGE_KEY.text, "", Q.AGE_KEY.hint, optional=True)
        if answer:
            key_file = Path(answer).expanduser()
        elif answer is None:
            raise Refused("--age-key is required (the age key file that encrypts your secrets): pass it, or run `init` "
                          "at a terminal, where Enter creates one")
        else:
            key_file = run.top / AGE_KEY_FILE
            key_file.parent.mkdir(mode=0o700, exist_ok=True)
            host.run(["age-keygen", "-o", str(key_file)])
            key_file.chmod(0o600)
            say(f"Created a new age key in {key_file}. Copy it into your password manager now: without it, nothing in "
                f"your repository can be decrypted, and a lost server cannot be set up again.")
            if not host.wait("Press Enter once it is in your password manager"):
                raise Refused(f"copy the new age key ({key_file}) into your password manager first, then run the same "
                              f"command again")
    return key_file.resolve()


# ---- the GitOps verbs: verify, check, upgrade, tls --------------------------------------------------------------

def values_digests(text: str) -> dict[str, str]:
    """The image digests under values.yaml's `image:`, `admin:` and `backup:` (the line under each block's `image`),
    by role — what `init` writes, and a half written by hand must hold."""
    found: dict[str, list[str]] = {}
    block = None
    for line in text.splitlines():
        top_key = re.match(r"^([A-Za-z][A-Za-z0-9]*):", line)
        if top_key:
            block = VALUES_IMAGE_KEYS.get(top_key.group(1))
            continue
        m = re.match(r"^\s+digest:\s*(sha256:[0-9a-f]{64})\s*(#.*)?$", line)
        if m and block:
            found.setdefault(block, []).append(m.group(1))
    return {role: digests[0] for role, digests in found.items() if len(digests) == 1}


def application_pin(text: str) -> tuple[str, str]:
    """The chart's `oci://` repository and the digest it is pinned by, each exactly once in the Application."""
    repos = re.findall(r"(?m)^\s*-?\s*repoURL:\s*oci://(\S+)\s*$", text)
    digests = re.findall(r"(?m)^\s*targetRevision:\s*(sha256:[0-9a-f]{64})\s*$", text)
    if len(repos) != 1 or len(digests) != 1:
        raise Refused(f"the Application names {len(repos)} oci:// chart source(s) and {len(digests)} digest pin(s); "
                      f"veyqctl takes over one of each")
    return repos[0], digests[0]


def rewrite_once(text: str, old: str, new: str, what: str) -> str:
    count = text.count(old)
    if count != 1:
        raise Refused(f"{what}: {old} appears {count} times, not once, so veyqctl cannot tell which to change. Nothing "
                      f"was changed.")
    return text.replace(old, new)


def rewrite_tags(text: str, old: str, new: str, what: str) -> str:
    pattern = re.compile(rf'(?m)^(\s+tag:\s*)"?{re.escape(old)}"?(\s*(?:#.*)?)$')
    result, count = pattern.subn(lambda m: f'{m.group(1)}"{new}"{m.group(2)}', text)
    if count != 3:
        raise Refused(f"{what}: {count} image tag line(s) name {old}, not the three (app, admin, tools). Nothing was "
                      f"changed.")
    return result


def running_differs(host: Host, source: Source, listed: list[Listed], g: GitOps) -> list[str]:
    """Where veyqctl.json's pins are not the running release's, as it was signed: the four pins verified again under
    the release identity and compared. `upgrade` stands on the running release (no downgrade, a major only from its
    entry), so a veyqctl.json written by hand — or one whose `running` was edited — is checked before it is trusted."""
    running = select(listed, g.doc["running"], source.describe())
    heading(f"The installed release, {running.release}")
    pins = verify_gitops_release(host, fetch_manifest(host, source, running, False), g.doc["chart"])
    return [f"{role} {g.doc['pins'][role]} (release {running.release}: {pins[role]})" for role in PIN_ROLES
            if g.doc["pins"][role] != pins[role]]


def gitops_verify(args: argparse.Namespace, host: Host, g: GitOps) -> int:
    p = plan(host, args.source or g.doc.get("source") or None, check_selector(args.release or g.doc["release"]))
    chosen = p.selection
    say_selection(p, "Verifying")
    verify_gitops_release(host, fetch_manifest(host, p.source, chosen, False), g.doc["chart"])
    say(f"Release {chosen.release} is veyq's, as signed. Nothing was written.")
    return 0


def gitops_drift(g: GitOps) -> list[str]:
    """Where the files no longer pin what veyqctl.json records."""
    drift = []
    try:
        chart, chart_pin = application_pin(g.application.read_text())
        images = values_digests(g.values.read_text())
    except (OSError, Refused) as e:
        return [str(e)]
    if chart != g.doc["chart"] or chart_pin != g.doc["pins"]["chart"]:
        drift.append(f"{g.doc['application']} pins oci://{chart}@{chart_pin}")
    for role in ("app", "admin", "tools"):
        if images.get(role) != g.doc["pins"][role]:
            drift.append(f"{g.doc['values']} pins {role} {images.get(role, '(none)')}")
    return drift


def gitops_check(args: argparse.Namespace, host: Host, g: GitOps) -> int:
    p = plan(host, args.source or g.doc.get("source") or None, check_selector(args.release or g.doc["release"]),
             g.doc["running"])
    source, listed, running = p.source, p.listed, p.running
    say_check(p, "installed")
    differs = running_differs(host, source, listed, g)
    if differs:
        say(f"✗ NOT RELEASE {running}: {GITOPS_FILE} pins " + "; ".join(differs) + ". `upgrade` refuses until it "
            "pins what that release signed.")
    else:
        checked(GITOPS_FILE, f"pins what {running} signed")
    heading("Your files")
    drift = gitops_drift(g)
    for line in drift:
        say(f"✗ DRIFT: {line}, not what {GITOPS_FILE} records")
    if not drift:
        checked("files", f"{g.doc['application']} and {g.doc['values']} pin what {GITOPS_FILE} records")
    drift = drift + differs
    namespace = g.namespace
    k = kubectl(g.doc.get("context") or None)
    heading("The cluster")
    if host.run(k + ["get", "namespace", namespace, "-o", "name"], check=False).returncode:
        say(f"The cluster or the namespace {namespace} is not reachable, so the Secrets were not checked.")
        return 1 if drift else 0
    absent = []
    for secret, keys in SECRET_VALUES.items():
        got = host.run(k + ["-n", namespace, "get", "secret", secret, "-o", "jsonpath={.data}"], check=False)
        try:
            have = set(json.loads(got.stdout or "{}")) if not got.returncode else None
        except ValueError:
            have = None
        if have is None:
            absent.append(f"{secret} (missing)")
        elif set(keys) - have:
            absent.append(f"{secret} without {', '.join(sorted(set(keys) - have))}")
    if absent:
        say(f"✗ The Secrets the chart reads are not complete ({g.doc['secrets']}): " + "; ".join(absent) + ".")
    else:
        checked("Secrets", f"{SECRETS_SECRET} and {CONFIG_SECRET} exist with every key")
    return 1 if drift or absent else 0


def gitops_upgrade(args: argparse.Namespace, host: Host, g: GitOps) -> int:
    """ADR 0126 decision 3: ADR 0096 decision 5's resolve and verify, then a commit instead of a restart."""
    if args.key_into or args.objects_to:  # --unsigned-local is refused in main() already
        raise Refused("--unsigned-local, --key-into and --objects-to are for Compose: on a GitOps instance veyq backs "
                      "itself up before the new release starts")
    p = plan(host, args.source or g.doc.get("source") or None, check_selector(g.doc["release"]), g.doc["running"])
    source, listed, selector, selection, running = p.source, p.listed, p.selector, p.selection, p.running
    heading(f"veyq {instance_label(g.directory)}: {running} installed, {RELEASE_KEY}={selector} selects "
            f"{selection.release}")
    if p.relation == "same":
        say("Nothing to do.")
        return 0
    if p.relation == "older":
        raise Refused(f"{RELEASE_KEY}={selector} selects {selection.release}, older than the installed {running}: "
                      f"there is no downgrade (going back means restoring a backup). Nothing was changed.")
    manifest_of, target = p.manifest_of, p.target
    drift = gitops_drift(g)
    if drift:
        raise Refused("the files no longer match what " + GITOPS_FILE + " records: " + "; ".join(drift)
                      + ". Nothing was changed: put them back, or record the new values in " + GITOPS_FILE + ".")
    top = repository_of(host, g.directory)
    paths = [str(g.application), str(g.values), str(g.directory / GITOPS_FILE)]
    if git(host, top, "diff", "--quiet", "HEAD", "--", *paths, check=False).returncode:
        raise Refused("application.yaml, values.yaml or veyqctl.json have uncommitted changes: commit or undo them "
                      "first, as `upgrade` commits only its own. Nothing was changed.")
    differs = running_differs(host, source, listed, g)
    if differs:
        raise Refused(f"{GITOPS_FILE} says release {running} is installed, but pins what that release did not sign: "
                      + "; ".join(differs) + f". Nothing was changed: an upgrade starts only from a release that "
                      f"verifies ({EXISTING_HALF_DOCS}).")
    checked(GITOPS_FILE, f"pins what {running} signed")
    heading(f"Verifying {target.release}")
    manifest = manifest_of(target)
    pins = verify_gitops_release(host, manifest, g.doc["chart"])
    old = g.doc["pins"]
    app_text = rewrite_once(g.application.read_text(), old["chart"], pins["chart"], g.doc["application"])
    app_text = app_text.replace(f"version={running}", f"version={target.release}")  # a Renovate hint, if any
    values_text = g.values.read_text()
    for role in ("app", "admin", "tools"):
        values_text = rewrite_once(values_text, old[role], pins[role], g.doc["values"])
    values_text = rewrite_tags(values_text, running, target.release, g.doc["values"])
    render(host, g.doc["chart"], pins["chart"], g.namespace, values_text)
    g.application.write_text(app_text)
    g.values.write_text(values_text)
    write_gitops(g._replace(doc={**g.doc, "running": target.release, "pins": pins}))
    git(host, top, "add", "--", *paths)
    git(host, top, "commit", "-m", f"veyq {target.release}: upgraded from {running} by veyqctl", "--", *paths)
    heading(f"Committed: veyq {target.release}, upgraded from {running}")
    if target != selection:
        say(entry_note(selector, selection, target, "the next `upgrade`") + ".")
    if args.push:
        git(host, top, "push")
        say("Pushed: Argo CD applies it at its next sync.")
    else:
        say(f"Next: git -C {top} push   (or `upgrade --push` next time)")
        say("Argo CD applies it at its next sync after the push.")
    say(f"veyq backs itself up before {target.release} starts.")
    return 0


class server_tunnel:
    """The SSH tunnel to a veyq-server cluster, open while the block runs, when the checkout's veyq-setup.json names
    one (the context reaches the API only through it); nothing otherwise."""

    def __init__(self, host: Host, top: Path) -> None:
        name = (read_setup(top).get("server") or {}).get("connect_name")
        self.host, self.name, self.process = host, name, None

    def __enter__(self):
        if self.name:
            self.process = self.host.background(["ssh", "-N", "-o", "ExitOnForwardFailure=yes", self.name])
            self.host.sleep(3)  # the forward is up before the first read
        return self

    def __exit__(self, *_exc):
        if self.process is not None:
            self.process.terminate()
        return False


def gitops_tls(args: argparse.Namespace, host: Host, g: GitOps) -> int:
    """`tls production`: from Let's Encrypt's staging directory to production (ADR 0126). The values' ACME server
    rewritten, rendered, committed and pushed on confirmation; once Argo CD applied the Issuer, the certificates staging
    issued are deleted on confirmation (it cannot be undone), so cert-manager orders production ones; then waited for."""
    staging, production = ACME_DIRECTORIES["staging"], ACME_DIRECTORIES["production"]
    text = g.values.read_text()
    if f"server: {production}" in text and staging not in text:
        say("The values name Let's Encrypt's production directory already: nothing to do.")
        return 0
    if f"server: {staging}" not in text:
        raise Refused(f"{g.values} does not use Let's Encrypt's test certificates: nothing to switch")
    top = repository_of(host, g.directory)
    if git(host, top, "diff", "--quiet", "HEAD", "--", str(g.values), check=False).returncode:
        raise Refused(f"{g.values} has uncommitted changes: commit or undo them first, as `tls production` commits "
                      f"only its own. Nothing was changed.")
    heading(f"veyq {instance_label(g.directory)}: from Let's Encrypt's test certificates to real ones")
    switched = rewrite_once(text, f"server: {staging}", f"server: {production}", g.doc["values"])
    namespace = g.namespace
    render(host, g.doc["chart"], g.doc["pins"]["chart"], namespace, switched)
    branch = (git(host, top, "rev-parse", "--abbrev-ref", "HEAD", check=False).stdout or "").strip()
    if not args.yes and host.confirm(f"Switch to Let's Encrypt production? This commits {g.doc['values']} and pushes to "
                                     f"origin {branch}?") is not True:
        raise Refused("not switched, nothing was changed (without a terminal, pass --yes)")
    g.values.write_text(switched)
    paths = [str(g.values)]
    setup = read_setup(top)
    rel = g.directory.relative_to(top).as_posix()
    if rel in (setup.get("veyq") or {}):  # a rebuild from veyq-setup.json starts on production from now on
        setup["veyq"][rel]["acme_directory"] = "production"
        atomic_write(top / SETUP_FILE, json.dumps(setup, indent=2, sort_keys=True) + "\n")
        paths.append(str(top / SETUP_FILE))
    git(host, top, "add", "--", *paths)
    git(host, top, "commit", "-m", "veyq: TLS from Let's Encrypt production, by veyqctl tls production", "--", *paths)
    git(host, top, "push")
    checked("committed", "and pushed: Argo CD applies the Issuer's new directory at its next sync")
    heading("Waiting for Argo CD and the new certificates")
    k = kubectl(g.doc.get("context") or None) + ["-n", namespace]
    with server_tunnel(host, top):
        deadline = time.monotonic() + args.timeout
        while (host.run(k + ["get", "issuer", "veyq-acme", "-o", "jsonpath={.spec.acme.server}"], check=False).stdout
               or "").strip() != production:
            if time.monotonic() > deadline:
                raise Refused(f"the Issuer veyq-acme does not name the production directory after {int(args.timeout)} s: has "
                              f"Argo CD synced yet? Run the same command again (the change is pushed already).")
            host.sleep(10)
        listed = host.run(k + ["get", "certificates", "-o",
                               "jsonpath={range .items[*]}{.spec.secretName}{\"\\n\"}{end}"], check=False).stdout or ""
        certificate_names = sorted({line.strip() for line in listed.splitlines() if line.strip()})
        if certificate_names:  # the Secrets' names: what holds each certificate, nothing of their content
            print(f"\n   The test certificates: {', '.join(certificate_names)} (namespace {namespace}).", flush=True)
            if not args.yes and host.confirm("Delete them so production ones are issued? This cannot be undone, and "
                                             "the sites have no certificate until the new ones arrive") is not True:
                raise Refused("the test certificates stay for now; run `veyqctl tls production` again to replace them")
            host.run(k + ["delete", "secret", *certificate_names])
        while True:
            states = host.run(k + ["get", "certificates", "-o", "jsonpath={range .items[*]}{.metadata.name} "
                                   "{.status.conditions[?(@.type==\"Ready\")].status}{\"\\n\"}{end}"],
                              check=False).stdout or ""
            pending = [line.split()[0] for line in states.splitlines() if line.split() and line.split()[-1] != "True"]
            if states.strip() and not pending:
                checked("certificates", "every one issued by Let's Encrypt production")
                return 0
            if time.monotonic() > deadline:
                raise Refused(f"not issued after {int(args.timeout)} s: {', '.join(pending) or 'no certificate'} — "
                              f"`kubectl describe certificate` shows why")
            host.sleep(10)


# ---- the targets: one set of verbs, each target doing its own or refusing (ADR 0126 decision 2) ---------------------

# What does a Compose verb's job on a GitOps instance, said when it refuses there.
COMPOSE_ONLY = {
    "install": "`init` writes a GitOps instance; `install` is Compose's",
    "start": "Argo CD runs the workloads",
    "stop": "Argo CD runs the workloads (switch the Application's automated sync off to stop it changing them)",
    "status": "Argo CD shows the Application's health (`kubectl -n <namespace> get pods` the pods')",
    "logs": "`kubectl -n <namespace> logs deploy/veyq-app`",
    "backup": "veyq backs itself up before every release change, and the chart's `backup.request` asks for one",
    "restore": "the chart's restore Job (the docs: Backup and restore, under Helm)",
    "rollback": "the chart's restore Job, onto the previous release pinned back",
    "self-update": "fetch the release's veyqctl as Verify a release says; a GitOps instance holds no release's files",
}


class Target:
    """One kind of instance. A verb runs in main()'s order: refused when this target has no such verb (naming what
    does its job here), refused for another target's flag, its programs checked, then run. A third target — plain Helm,
    say — is one more subclass in TARGETS and a value `target_of` reads."""
    name = ""
    REFUSES: dict[str, str] = {}        # verb -> what does its job on this target
    FOREIGN_FLAGS: dict[str, str] = {}  # args dest -> the refusal: another target's flag

    def __init__(self, args: argparse.Namespace, host: Host, home: Path) -> None:
        self.args, self.host, self.home = args, host, home

    def run(self, verb: str) -> int:
        if verb in self.REFUSES:
            raise Refused(self.REFUSES[verb])
        for dest, why in self.FOREIGN_FLAGS.items():
            if getattr(self.args, dest, False):
                raise Refused(why)
        programs = self.programs(verb)
        if programs:
            require_programs(self.host, programs, self.what(verb))
        return self.verb(verb)

    def programs(self, verb: str) -> list[str]:
        raise NotImplementedError

    def what(self, verb: str) -> str:
        return f"`{verb}`"

    def verb(self, verb: str) -> int:
        raise NotImplementedError


class ComposeTarget(Target):
    """veyq on Docker Compose in a home directory (ADR 0096): every verb is its cmd_* function."""
    name = "compose"
    REFUSES = {"tls": "`tls` works on a GitOps instance only. Under Compose, set the certificate directory in .env"}

    def __init__(self, args: argparse.Namespace, host: Host, home: Path) -> None:
        super().__init__(args, host, home)
        self.FOREIGN_FLAGS = {"push": f"--push is for a GitOps instance, and {home} runs on Compose"}

    def programs(self, verb: str) -> list[str]:
        return [] if verb == "self-update" else compose_programs(verb, bool(getattr(self.args, "unsigned_local", False)))

    def verb(self, verb: str) -> int:
        return self.args.func(self.args, self.host)


class GitOpsTarget(Target):
    """veyq's files in a repository an Argo CD syncs (ADR 0126): a verb's result is a commit, never a restart."""
    name = "gitops"
    FOREIGN_FLAGS = {"unsigned_local": "--unsigned-local is for Compose: a GitOps instance installs only what verifies"}
    VERBS = {"verify": gitops_verify, "check": gitops_check, "upgrade": gitops_upgrade, "tls": gitops_tls}

    def __init__(self, args: argparse.Namespace, host: Host, home: Path) -> None:
        super().__init__(args, host, home)
        self.REFUSES = {verb: f"`{verb}` is for Compose, and {home} is a GitOps instance: {instead}"
                        for verb, instead in COMPOSE_ONLY.items()}

    def programs(self, verb: str) -> list[str]:
        return list(GITOPS_PROGRAMS[verb])

    def what(self, verb: str) -> str:
        return f"`{verb}` of a GitOps instance"

    def verb(self, verb: str) -> int:
        # Resolved: git names the checkout by its real path (macOS' /var is /private/var), and every path veyqctl
        # compares with it or hands to `git add` must be the same one.
        return self.VERBS[verb](self.args, self.host, read_gitops(self.home.resolve()))


TARGETS: dict[str, type[Target]] = {"compose": ComposeTarget, "gitops": GitOpsTarget}


# ---- main ------------------------------------------------------------------------------------------------------

def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="veyqctl", description="Set up, run, upgrade and back up a veyq instance: on "
                                "Docker Compose, or through Argo CD on Kubernetes.")
    p.add_argument("--home", default=None, help=f"the instance's directory; wins over --instance (default: the current "
                                                 f"instance, else {DEFAULT_HOME})")
    p.add_argument("--instance", default=None, metavar="NAME", help="act on this instance once, without switching to it")
    p.add_argument("--source", default=None,
                   help=f"where releases come from: a mirror directory or http(s) URL (default: the GitHub "
                        f"releases of {UPSTREAM}, or where the instance was installed from)")
    sub = p.add_subparsers(dest="command", required=True)

    sub.add_parser("instances", help="list your instances; * marks the current one"
                   ).set_defaults(func=cmd_instances)
    use = sub.add_parser("use", help="switch to another instance")
    use.add_argument("name")
    use.set_defaults(func=cmd_use)

    init = sub.add_parser("init", help="set up a new instance: on Docker Compose, or through Argo CD — optionally "
                                       "with a new server too. Asks everything first.")
    which = init.add_mutually_exclusive_group()
    which.add_argument("--compose", action="store_true", help="on Docker Compose on this host (in --home)")
    which.add_argument("--gitops", metavar="DIR", help="through Argo CD: where in your repository veyq's files go")
    init.add_argument("--name", help="the instance's name on this computer (default: veyq; GitOps: the repository's name)")
    init.add_argument("--set", action="append", metavar="KEY=VALUE", help="Compose: set a .env value (repeatable)")
    init.add_argument("--unsigned-local", action="store_true", help="Compose: a release you built locally (not verified)")
    init.add_argument("--release", help="GitOps: which versions `upgrade` installs, e.g. 1, latest or 1.2 (asked)")
    init.add_argument("--secrets", choices=PROVIDERS, help="GitOps: where veyq's passwords and keys are kept")
    init.add_argument("--domain", help="GitOps: veyq's domain")
    init.add_argument("--store-domain", help="GitOps: the storage domain for photos and files (default store.<domain>)")
    init.add_argument("--no-bundled-store", action="store_true", help="GitOps: no bundled store; set your own in the "
                                                                     "admin console after the install")
    init.add_argument("--gateway-class", help="GitOps: the GatewayClass that handles veyq's traffic")
    init.add_argument("--tls", choices=("acme", "shared", "none"), help="GitOps: certificates from Let's Encrypt "
                                                                         "(acme), one of yours (shared), or none")
    init.add_argument("--acme-directory", choices=tuple(ACME_DIRECTORIES),
                      help="GitOps: Let's Encrypt's test (staging) or real (production) certificates")
    init.add_argument("--tls-secret", help="GitOps --tls shared: the name of your TLS Secret")
    init.add_argument("--gateway-namespace", help="GitOps: the namespace of the gateway's proxy pods")
    init.add_argument("--gateway-pod-label", help="GitOps: a KEY=VALUE label of those pods")
    init.add_argument("--namespace", help="GitOps: veyq's namespace (default veyq)")
    init.add_argument("--context", help="GitOps: the kubeconfig context of your cluster (asked)")
    init.add_argument("--no-cluster-check", action="store_true", help="GitOps: don't check the cluster (not reachable yet)")
    init.add_argument("--storage-class", help="GitOps: the StorageClass for backups (default: the cluster's)")
    init.add_argument("--backup-size", default="20Gi", help="GitOps: the backup volume's size (default 20Gi)")
    init.add_argument("--secret-store", help="GitOps --secrets external-secrets: the SecretStore's name")
    init.add_argument("--secret-store-kind", default="ClusterSecretStore", choices=("SecretStore", "ClusterSecretStore"),
                      help="GitOps --secrets external-secrets: the store's kind (default ClusterSecretStore)")
    init.add_argument("--remote-prefix", default="veyq-", help="GitOps --secrets external-secrets: the prefix of "
                                                              "the keys in your vault (default veyq-)")
    init.add_argument("--repo-url", help="GitOps: the URL Argo CD reads your repository from (default: origin)")
    init.add_argument("--revision", help="GitOps: the branch Argo CD follows (default: the current one)")
    init.add_argument("--admin-host-access", choices=("yes", "no"), help="GitOps: veyq's admin page through an SSH "
                                                                       "tunnel (asked)")
    init.add_argument("--admin-host-source", help="GitOps: where the tunnel's traffic comes from (a /32; usually "
                                                  "as offered)")
    init.add_argument("--project", help="GitOps: the Argo CD project veyq belongs to (asked)")
    init.add_argument("--application-name", default="veyq", help="GitOps: the Argo CD Application's name (default "
                                                                 "veyq); --name only names it on this computer")
    init.add_argument("--argocd-namespace", default="argocd", help="GitOps: Argo CD's namespace (default argocd)")
    init.add_argument("--server", choices=("yes", "no"),
                      help="GitOps: set up a new server too, with everything veyq needs (veyq-server)")
    init.add_argument("--server-address", help="new server: its DNS name or IP address")
    init.add_argument("--node-name", help="new server: a short name for it inside Kubernetes")
    init.add_argument("--admin-user", help="new server: your login on it")
    init.add_argument("--admin-ssh-key", dest="admin_ssh_public_key", help="new server: the SSH public key you log in with")
    init.add_argument("--age-key", help="new server: the age key file that encrypts your repository's secrets")
    init.add_argument("--host-access", choices=("yes", "no"),
                      help="new server: open Argo CD and veyq's admin page directly while connected to the server")
    init.add_argument("--connect-name", help="new server: its name on this computer (SSH host and kubeconfig context)")
    init.add_argument("--make-current", choices=("yes", "no"), help="new server: make it kubectl's default context")
    init.add_argument("--sync-timeout", type=float, default=1800.0,
                      help="new server: how long to wait for everything to run, in seconds (default 1800)")
    init.set_defaults(func=cmd_init)

    tls = sub.add_parser("tls", help="switch from Let's Encrypt's test certificates to real ones")
    tls.add_argument("directory", choices=("production",), help="the certificates to switch to")
    tls.add_argument("--yes", action="store_true", help="don't ask before committing, pushing and deleting the test certificates")
    tls.add_argument("--timeout", type=float, default=900.0, help="how long to wait for Argo CD and the new certificates, in seconds")
    tls.set_defaults(func=None)  # a verb of the targets: GitOps runs it, Compose refuses it

    connected = sub.add_parser("connect", help="connect this computer to your server again (SSH tunnel and kubectl "
                               "context), e.g. after a rebuild")
    connected.add_argument("--connect-name", help="the server's name on this computer (default: from veyq-setup.json)")
    connected.add_argument("--make-current", choices=("yes", "no"), default="no",
                           help="make it kubectl's default context (default no)")
    connected.set_defaults(func=cmd_connect)

    install = sub.add_parser("install", help="download, verify and start the release .env's VEYQ_RELEASE selects")
    install.add_argument("--set", action="append", metavar="KEY=VALUE", help="set a .env value (repeatable)")
    install.add_argument("--unsigned-local", action="store_true",
                         help="a release you built locally: no signature is checked")
    install.set_defaults(func=cmd_install)

    for name, func, text in (("start", cmd_start, "start veyq and wait until it is healthy"),
                             ("stop", cmd_stop, "stop veyq"),
                             ("status", cmd_status, "show every service's state; exit code 1 unless all are healthy")):
        sub.add_parser(name, help=text).set_defaults(func=func)

    logs = sub.add_parser("logs", help="show veyq's logs")
    logs.add_argument("-f", "--follow", action="store_true")
    logs.add_argument("--tail", type=int, default=None)
    logs.add_argument("service", nargs="*")
    logs.set_defaults(func=cmd_logs)

    verify = sub.add_parser("verify", help="check the selected release's signatures; changes nothing")
    verify.add_argument("--release", default=None, help="check this release instead of .env's VEYQ_RELEASE")
    verify.add_argument("--unsigned-local", action="store_true", help="a release you built locally (not verified)")
    verify.set_defaults(func=cmd_verify)

    check = sub.add_parser("check", help="show which release `upgrade` would install, and whether a newer major exists")
    check.add_argument("--release", default=None, help="check this release instead of .env's VEYQ_RELEASE")
    check.add_argument("--unsigned-local", action="store_true",
                       help="read the manifests of releases you built locally (not verified)")
    check.set_defaults(func=cmd_check)

    upgrade = sub.add_parser("upgrade", help="upgrade to the release VEYQ_RELEASE selects (Compose: backs up first; "
                                             "GitOps: commits)")
    upgrade.add_argument("--key-into", metavar="FILE", help="Compose: where the backup's config-secret key goes — required, keep it apart")
    upgrade.add_argument("--unsigned-local", action="store_true",
                         help="Compose: a release you built locally: no signature is checked")
    upgrade.add_argument("--objects-to", metavar="POOL", help=f"Compose: where this backup copies photos and files, "
                                                               f"instead of .env's {POOL_TARGET_KEY}")
    upgrade.add_argument("--push", action="store_true", help="GitOps: push the commit, so Argo CD applies it")
    upgrade.set_defaults(func=cmd_upgrade)

    backup = sub.add_parser("backup", help="back up the database, the store's index and the config-secret key; "
                                           "`backup prune` only deletes old backups")
    backup.add_argument("action", nargs="?", choices=["prune"],
                        help="prune: delete every backup older than the retention period, nothing else (for a "
                             "daily timer)")
    backup.add_argument("--key-into", metavar="FILE", help="where the config-secret key goes — required, keep it "
                                                            "apart")
    backup.add_argument("--objects-to", metavar="POOL", help=f"where this backup copies photos and files, instead of "
                                                              f".env's {POOL_TARGET_KEY}: a directory or s3:<bucket>")
    backup.set_defaults(func=cmd_backup)

    restore = sub.add_parser("restore", help="restore a backup onto this instance, which must hold no data yet")
    restore.add_argument("backup", help="the backup's directory")
    # Required, never defaulted: the tool's domain gate is a deliberate statement by the operator (ADR 0056 §4), and
    # a capture's domains are its TENANTS' — `.env`'s VEYQ_DOMAIN is the bootstrap domain, not one of them.
    restore.add_argument("--domain", required=True, help="the tenant domain in the backup, as a deliberate confirmation")
    key = restore.add_mutually_exclusive_group()
    key.add_argument("--key-from", metavar="FILE", help="the backup's config-secret key, from where you kept it")
    key.add_argument("--without-key", action="store_true", help="restore without the config-secret key, knowingly")
    restore.add_argument("--objects-from", metavar="POOL",
                         help="copy photos and files from this pool into this instance's EMPTY store (a fresh "
                              "instance); without it they are only checked in the store")
    restore.set_defaults(func=cmd_restore)

    rollback = sub.add_parser("rollback", help="go back to the release a backup was taken under: empties the database "
                                               "and restores the backup (asks first)")
    rollback.add_argument("capture", help="the backup's directory")
    rollback.add_argument("--key-from", required=True, metavar="FILE", help="the backup's config-secret key")
    rollback.add_argument("--yes", action="store_true", help="don't ask before emptying the database")
    rollback.set_defaults(func=cmd_rollback)

    update = sub.add_parser("self-update", help="replace this veyqctl with the one of the installed release")
    update.set_defaults(func=cmd_self_update)
    # Before the command or after it: `veyqctl -v upgrade` and `veyqctl upgrade -v` both. SUPPRESS on the command's
    # own, or its default would overwrite the one given before it.
    verbose_help = "show every program veyqctl starts, with its arguments"
    p.add_argument("-v", "--verbose", action="store_true", help=verbose_help)
    for command in sub.choices.values():
        command.add_argument("-v", "--verbose", action="store_true", default=argparse.SUPPRESS, help=verbose_help)
    return p


def main(argv: list[str] | None = None, host: Host | None = None) -> int:
    args = parser().parse_args(argv)
    set_unverified(bool(getattr(args, "unsigned_local", False)))
    set_verbose(bool(getattr(args, "verbose", False)))
    host = host or Host()
    try:
        if args.command in ("instances", "use", "init", "connect"):
            return args.func(args, host)
        home = resolve_home(args)
        args.home = str(home)
        host.workdir = home
        return TARGETS[target_of(home)](args, host, home).run(args.command)
    except Refused as e:
        refused(str(e))
        return 1
    finally:
        set_unverified(False)
        set_verbose(False)


if __name__ == "__main__":
    sys.exit(main())
