#!/usr/bin/env python3
"""veyqctl — install, run, upgrade and back up a veyq instance from its releases: on Docker Compose (ADR 0096), or as
veyq's half of an Argo CD repository (ADR 0126).

    veyqctl [--home DIR | --instance NAME] [--source SRC] <command> …

    init [--compose | --gitops DIR] [--name N] …     create an instance, list it and make it current (GitOps: below)
    connect [--connect-name N] [--make-current yes]  the SSH host and kubeconfig context of a set-up server, again
    tls production [--yes]                           GitOps: from Let's Encrypt staging to production
    instances | use NAME                             the instances listed in ~/.config/veyq, and which is current
    install [--set KEY=VALUE …] [--unsigned-local]   fetch the release VEYQ_RELEASE selects, verify it, write .env, up
    start | stop | status                            the stack, addressed by the project the recipe names
    logs [-f] [--tail N] [SERVICE …]                 its logs
    verify [--release SEL] [--unsigned-local]        the signatures of the selected release, and nothing else
    check [--release SEL] [--unsigned-local]         which release `upgrade` would take, and a newer major beyond it
    upgrade --key-into FILE [--objects-to P] [--unsigned-local]   toward what VEYQ_RELEASE selects (decision 5's order)
    backup --key-into FILE [--objects-to P]          the three legs, by the running release's `instance_backup.py`
    backup prune                                     every capture under backups/ older than B (a daily timer's)
    restore DIR --domain D (--key-from F | --without-key) [--objects-from P]   onto this instance, which holds no data
    rollback DIR --key-from F [--yes]                back to the release a capture was taken under, in place
    self-update                                      this file, replaced by the installed release's `veyqctl`

**Which instance** (ADR 0126 decision 2): `--home DIR` names one by its path; else `--instance NAME` one listed in
`~/.config/veyq/instances.json` (`$XDG_CONFIG_HOME/veyq` if set), which `init` writes and `use` switches; else the
current one there; else `/opt/veyq` — so a host with one Compose instance needs no list, and a run under `sudo`, whose
home differs, still finds it. The TARGET is read from the instance, never given: a directory holding `veyqctl.json` is
a GitOps instance, anything else a Compose home. `init` asks which to create (or takes `--compose` / `--gitops DIR`).

**A GitOps instance** is a directory in a checkout of the repository an Argo CD syncs: `application.yaml` (the chart by
digest, `oci://<registry>/<owner>/charts/veyq`), `values.yaml` (the three images by tag and digest, the domains, the
gateway, the Secrets' names), `resources/` (the namespace, the backup gate's claim, the Secrets from the provider
chosen — `sops` encrypted through the repository's `.sops.yaml`, `external-secrets` as ExternalSecrets on a named store,
`sealed-secrets` through `kubeseal`, or `none`) and `veyqctl.json` (the selector, the provider, the running release and
its four pins). `init --gitops DIR` checks the cluster against what veyq's half assumes (Argo CD, Gateway API v1.5+ and
its class, a StorageClass, cert-manager 1.21+ for the chart's ACME issuer; `--no-cluster-check` skips it), resolves and
verifies the release — its manifest, the three images (`images.tools` too), and the chart: the manifest does not name it,
so its tag is resolved to a digest at the registry (`Host.oci_digest`), that digest verified under the release identity,
and its Chart.yaml version read back (`helm show chart`) — renders the chart with the values (`helm template`), and only
then writes the directory. Every value is generated once: under `sops` encrypted into the repository, under every other
provider handed over once in `~/.config/veyq/<name>.secrets.json` (0600, never overwritten); a directory that is not
empty is refused, so nothing is generated twice — unless this checkout's `init` wrote it: every question comes before
the first step (the answers that decide the programs, the programs — waited for, never installed — then the rest), every
answer is kept in the repository's `veyq-setup.json` and every step done in `~/.config/veyq/init-<hash>.json` the moment
it exists (class `Progress`), and the same command continues there, reusing what it handed over — and a veyq half
written by hand is refused too: for one, the operator writes `veyqctl.json` beside it (the Run page's *A veyq half that
exists already*). **With the server set up too** (`--server yes`, offered in an empty repository; ADR 0126 decision 6),
`init` asks the server's questions as well, verifies veyq-server's newest release (its manifest under veyq-server's
release workflow, the commit it names checked out and its tree's sha256 compared), renders it with Copier, encrypts the
server's secrets (the admin password, Argo CD's deploy key) once, pins the host key on confirmation, writes veyq's half
into `gitops/apps/veyq`, enters the deploy key with `gh` when allowed (else shows it and waits), waits for the DNS
records, commits and pushes on confirmation, runs the bootstrap playbook on confirmation (not when the admin user logs
in already), connects this machine (`connect`) and waits until Argo CD reports everything Synced and Healthy. `verify` and `check` work as under Compose;
`check` also names drift (a pin the files no longer hold), whether the pins `veyqctl.json` records are the running
release's as it was signed, and whether the Secrets exist with every key. `upgrade` resolves as decision 5 step 1 does,
refuses drift, uncommitted changes and a running release whose pins do not verify — it stands on that release — then
verifies the new one, rewrites each pin it finds
exactly once and the three tags, renders, then commits its three files alone (`--push` pushes). Nothing else: Argo CD
applies the commit, and the chart's backup gate captures before the new release migrates (ADR 0098). The Compose
lifecycle verbs refuse on a GitOps instance, each naming what does its job there.

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
it into `.env`. `upgrade` (and `check`, which says what `upgrade` would take) enters a major only from the release that
major's release names as its entry (decision 5 step 1, `upgrade_target`): `majorEntry` in its signed manifest, the
newest release of the previous major when it was cut — the one its upgrade proof ran from (ADR 0085 decision 10) — and
never more than the next major per run. So `latest` from 1.3, with 2.5 entered from 1.9, goes to 1.9, and from 1.9
with 3.1 listed to the newest of major 2; a pin inside the next major is reached directly from its entry. A fix release
of the old major cut after it (1.10) is no way in: 2.5 was never proved from it, and from 1.10 only a later release of
major 2 (entered from 1.10) is taken, else the upgrade is refused. A next major with no release (only prereleases, or a
mirror that lacks it) is refused, since going past it would cross two. Reading an entry fetches and verifies that
release's manifest, so `check` verifies too.

**Nothing unverified is pulled or started** (ADR 0057). First the manifest: the publish signs it with `cosign
sign-blob` into `manifest.json.bundle`, an asset beside it, and `fetch_manifest` — the one entry that reads a manifest
from a source — runs the host's `cosign verify-blob` on the manifest's exact bytes against that bundle and the one
identity that releases veyq (GitHub's OIDC issuer and `release.yml` of `raddatzk/veyq` on `main` or a
`release/<major>.x` branch — the promotion's workflow, never `publish.yml`, whose signature only says "snapshot" (ADR 0091
decision 5) — the identity the Run area's *Verify a release* page documents, and `scripts/validate_release_identity.py`
assertion 7 holds both to what signs) BEFORE a single field of it is read — so no sha256 and no digest in it is
trusted unless veyq's release signed it, whether it came from GitHub, a mirror or a directory. A missing bundle is
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
(`VEYQ_BACKUP_STORE_*` in the shell) passes through, as the tool documents. Where `compose.override.yml` leaves the
bundled store out (no `garage` in the composed recipe, ADR 0064), that shell pair is the only one: the rclone container
runs on Docker's default network, no bundled pair is passed, and a run that reaches the store is refused before
anything stops while `VEYQ_BACKUP_STORE_ENDPOINT` / `_ACCESS_KEY` / `_SECRET_KEY` are not set. Leg (c) lives on a volume under Docker's
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
`uc.admin.install-and-upgrade-instance` stays `planned` for that reason): `src/tools/veyqctl_selftest.py`, without
Docker, holds the resolver, the manifest's signature and parse, the template merge, `upgrade`'s order and every
refusal, with a stand-in for cosign; `cosign verify-blob` in the exact form it runs was run against cosign's own
signed release files (cr-signed-release-manifest), never against a veyq release, none being published; the live
runs of cr-veyqctl installed, stopped and started an instance from a mirror directory with images built from the tree,
upgraded it from one commit's release to the next with a Memory in it, and restored the upgrade's backup onto a fresh
install; and the stack lane (`scripts/run_stack_e2e.py`, ADR 0090) installs every candidate it boots with this file's
`install --unsigned-local`, its overlay as the override file — `install`, the override and `.env` from the template,
on every run (`start`, `stop`, `upgrade`, `backup` and `restore` are not on that path).

Its limits: on Kubernetes it writes an Argo CD repository and starts nothing (ADR 0126; ADR 0096 decision 8 is replaced),
and the cluster it sets up with the server is veyq-server's (ADR 0126 decision 6); the source still decides
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
import base64
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
    # While `init` only looks around to ask well (git, kubectl reads), its commands are not echoed: the questions are
    # what the operator reads then. Everything that verifies, renders or writes is echoed as always.
    quiet = False

    def run(self, cmd: list[str], *, check: bool = True, stream: bool = False,
            env: dict[str, str] | None = None, stdin: bytes | None = None) -> subprocess.CompletedProcess:
        if not self.quiet:
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

    def section(self, title: str) -> None:
        print(f"\n== {title}", flush=True)

    def prompt(self, question: str, default: str = "", hint: str = "") -> str | None:
        """A question at a terminal, as setup scripts ask: the hint above it, `[default]` that Enter takes, asked again
        while an answer is needed and none is given. None without a terminal (the caller then needs the flag)."""
        if not sys.stdin.isatty():
            return None
        print()
        if hint:
            print(f"   {hint}")
        while True:
            try:
                answer = input(f"   {question}{f' [{default}]' if default else ''}: ").strip()
            except EOFError:  # the input ended: the offer, or nothing (the caller refuses, naming the flag)
                print()
                return default or None
            if answer or default:
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
        return input(f"veyqctl: {prompt} [y/N]: ").strip().lower() in ("y", "yes")

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

def check_selector(selector: str) -> str:
    if not SELECTOR.match(selector or ""):
        raise Refused(f"{RELEASE_KEY}={selector!r} selects nothing: it is `<major>.<minor>` (pinned), `<major>` (the "
                      f"newest of that major) or `latest` (the newest; `upgrade` enters each major from the release "
                      f"it names as its entry)")
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
        raise Refused(f"{chosen.release} lies beyond major {major + 1}, and {where} lists no release of major "
                      f"{major + 1} to enter it from: a run never crosses more than one major (ADR 0096 decision 5). "
                      f"Nothing was stopped.")
    entry = entry_of(gate)
    if entry is None:
        raise Refused(f"the manifest of {gate.tag} names no release major {major + 1} is entered from (`majorEntry`), so "
                      f"no upgrade into it from major {major} was proved (ADR 0096 decision 5). Nothing was stopped.")
    at = tuple(int(x) for x in entry.split("."))
    if at == running:
        return gate
    if at > running:
        stop = next((r for r in listed if (r.major, r.minor) == at and not r.prerelease), None)
        if stop is None:
            raise Refused(f"{gate.release} is entered from {entry} (its manifest's `majorEntry`), and {where} lists no "
                          f"release {entry} to go through (ADR 0096 decision 5). Nothing was stopped.")
        return stop
    running_release = f"{running[0]}.{running[1]}"
    raise Refused(f"{gate.release} is entered from {entry} (its manifest's `majorEntry`), and this instance runs "
                  f"{running_release}, a later release of major {major}: no upgrade from {running_release} into "
                  f"{gate.release} was proved, and {running_release} may carry migrations {gate.release} lacks (ADR "
                  f"0096 decision 5). A later release of major {major + 1} is entered from {running_release} or after: "
                  + (f"select `{major + 1}` for the newest one, or wait for it." if gate is chosen and chosen != newest_of(listed, major + 1)
                     else f"wait for it — none is listed yet.")
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
        raise Refused(f"{source.describe()} serves no {MANIFEST_BUNDLE_ASSET} for {chosen.tag} ({e}) — the manifest's "
                      f"signature, without which nothing it names is trusted. A mirror copies it like any other file of "
                      f"the release; a release produced locally from `docker build` has none and is installed with "
                      f"--unsigned-local. Nothing was pulled, written or started.") from None
    verify_manifest(host, cosign, raw, bundle, f"the manifest of {chosen.tag} from {source.describe()}")
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


# ---- verification ---------------------------------------------------------------------------------------------

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
            problem = (f"{what} needs {', '.join(missing)}, not on PATH. Nothing was written or started. "
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
        if not host.wait(shown + "\nInstall, then press Enter to look again (Ctrl-C: later — the same command goes on)"):
            raise Refused(shown)


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


def verify_manifest(host: Host, cosign: str, raw: bytes, bundle: bytes, what: str,
                    identity: str = COSIGN_IDENTITY_REGEXP, publisher: str = "veyq",
                    bundle_name: str = MANIFEST_BUNDLE_ASSET) -> None:
    """`cosign verify-blob` of the manifest's exact bytes against its bundle and the one identity that publishes it:
    veyq's release workflow, or (the server path) veyq-server's."""
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
        prune(host, home, state, backup_tool(host, home, state, store=False), required=False)
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
    return (f"{RELEASE_KEY}={selector} selects {chosen.release}, and a major is entered only from the release its "
            f"manifest names as its entry (ADR 0096 decision 5): {then} enters major {target.major + 1}")


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
        manifest_of = manifest_reader(host, source, args.unsigned_local)
        target = (upgrade_target(listed, current, chosen, source.describe(), lambda r: manifest_of(r).major_entry)
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
            raise Refused(f"the recipe gives `{service}` no {key}, which the backup reads — this release's recipe "
                          f"is not one this veyqctl knows how to back up")
        return str(value)

    if "garage" not in services:
        # The bundled store left out (ADR 0064: the instance's compose.override.yml disables it and blanks the hint),
        # so the composed config lists no `garage`: the store is the operator's own, reached on Docker's default
        # network, and no bundled pair is passed. Its pair comes from the shell ([backup_tool] refuses without it).
        return Facts(env("app", "VEYQ_SECURITY_CONFIG_SECRET_FILE"), DEFAULT_NETWORK, None, None,
                     env("mariadb", "MARIADB_ROOT_PASSWORD"))
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


def require_own_store_pair(facts: Facts) -> None:
    """With no Garage in the recipe, a run that reaches the store needs the operator's own pair in this shell (ADR 0064
    § backup and restore take the own store's pair) — refused here, before anything stops, naming what is missing."""
    if facts.store_endpoint is not None:
        return
    absent = [key for key in OWN_STORE_KEYS if not os.environ.get(key, "").strip()]
    if absent:
        raise Refused(f"this instance's recipe runs no bundled store (compose.override.yml leaves `garage` out), so the "
                      f"backup reaches a store of your own with the endpoint and key pair from this shell — and "
                      f"{', '.join(absent)} {'is' if len(absent) == 1 else 'are'} not set (the pair the console holds is "
                      f"sealed under the key the backup never reads). Export them and run it again. Nothing was stopped.")


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
    "upgrade copies the new objects into it: https://veyq.app/run/backup-and-restore/")


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
        say(f"the store setting in use could not be read ({str(e).splitlines()[0][:200]})")
        in_use = "unreadable"
    if in_use != "own":
        say(f"the store in use: {in_use or 'none named'}")
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
        say(f"WARNING: the captures older than B were not pruned ({str(e).splitlines()[0][:200]}); the next `backup`, "
            f"`upgrade`, `start` or `backup prune` tries again")


def retention_warning(host: Host, home: Path, state: State) -> None:
    """`status` (ADR 0056 decision 8): say it while no capture under `backups/` is younger than B — admin-web cannot see
    the captures. Never changes an exit code."""
    try:
        tool = backup_tool(host, home, state, store=False)
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
              "Run page gives a daily timer for it (https://veyq.app/run/backup-and-restore/)")


def read_ledger(host: Host, home: Path, state: State, key_path: str, into: Path) -> Path | None:
    """The erasure ledger out of the app's volume — `erasures.log` beside the key, which no restore touches — through a
    one-off container of the `app` service, as `place_key` writes the key in. The file at [into], or None when the
    volume holds none (an instance older than the ledger, or none written yet): the tool then checks as before."""
    ledger = str(Path(key_path).parent / LEDGER_FILE)
    result = host.run([*compose(home, state.release, state.project), "run", "--rm", "--no-deps", "-T", *IMAGE_COMMANDS,
                       "read-file", ledger], check=False)
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
    manifest_of = manifest_reader(host, source, args.unsigned_local)
    chosen = upgrade_target(listed, running, selection, source.describe(), lambda r: manifest_of(r).major_entry)
    say(f"step 1: {RELEASE_KEY}={selector} takes release {chosen.release} (running: {state.release})"
        + (f" — {selection.release} lies in a later major, entered from the release its manifest names"
           if chosen != selection else ""))

    # 2. its manifest verified, both images verified by the manifest's digests, then its assets into releases/<release>/
    manifest = manifest_of(chosen)
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
        prune(host, home, state, backup_tool(host, home, state, store=False), required=True)
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


# ---- instances: which one a command acts on (ADR 0126 decision 2) ----------------------------------------------

GITOPS_FILE = "veyqctl.json"  # a GitOps instance's own file: target, selector, secrets provider, paths, pins
REGISTRY_FILE = "instances.json"  # in config_dir(): the instances a user manages, and which is current
INSTANCE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$")
# What does a Compose verb's job on a GitOps instance, said when it refuses there.
COMPOSE_ONLY = {
    "install": "`init` writes a GitOps instance; `install` is Compose's",
    "start": "Argo CD runs the workloads",
    "stop": "Argo CD runs the workloads (switch the Application's automated sync off to stop it changing them)",
    "status": "Argo CD shows the Application's health (`kubectl -n <namespace> get pods` the pods')",
    "logs": "`kubectl -n <namespace> logs deploy/veyq-app`",
    "backup": "the chart's backup gate captures before every release change, and `backup.request` asks for one "
              "(ADR 0098)",
    "restore": "the chart's restore Job (Run area: Backup and restore, under Helm)",
    "rollback": "the chart's restore Job onto the previous release, pinned back (ADR 0098 decision 9)",
    "self-update": "fetch the release's veyqctl as Verify a release says; a GitOps instance holds no release's files",
}


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
    a Compose home, or a home not created yet — is Compose."""
    return "gitops" if (path / GITOPS_FILE).is_file() else "compose"


def name_is_free(name: str, path: Path) -> None:
    listed = read_registry()["instances"].get(check_instance_name(name))
    if listed is not None and Path(listed) != path:
        raise Refused(f"an instance named {name} is listed already, at {listed} — pick another with --name")


def register(name: str, path: Path) -> None:
    name_is_free(name, path)
    registry = read_registry()
    registry["instances"][name] = str(path)
    registry["current"] = name
    write_registry(registry)
    say(f"instance {name} ({target_of(path)}, {path}) is listed in {config_dir() / REGISTRY_FILE} and is current")


def resolve_home(args: argparse.Namespace) -> Path:
    """Which instance a command acts on: `--home` (a path), else `--instance`, else the current one, else /opt/veyq."""
    if args.home:
        return Path(args.home)
    registry = read_registry()
    if args.instance:
        listed = registry["instances"].get(args.instance)
        if listed is None:
            raise Refused(f"no instance named {args.instance} in {config_dir() / REGISTRY_FILE}: `veyqctl instances` "
                          f"lists them")
        return Path(listed)
    if registry["current"]:
        return Path(registry["instances"][registry["current"]])
    return Path(DEFAULT_HOME)


def cmd_instances(args: argparse.Namespace, host: Host) -> int:
    registry = read_registry()
    if not registry["instances"]:
        say(f"no instance is listed in {config_dir() / REGISTRY_FILE}; every command acts on {DEFAULT_HOME} "
            f"(`init` lists the instance it creates)")
        return 0
    for name, path in sorted(registry["instances"].items()):
        mark = "*" if name == registry["current"] else " "
        say(f"{mark} {name}  {target_of(Path(path))}  {path}")
    return 0


def cmd_use(args: argparse.Namespace, host: Host) -> int:
    registry = read_registry()
    if args.name not in registry["instances"]:
        raise Refused(f"no instance named {args.name}: `veyqctl instances` lists them")
    registry["current"] = args.name
    write_registry(registry)
    say(f"current instance: {args.name} ({target_of(Path(registry['instances'][args.name]))}, "
        f"{registry['instances'][args.name]})")
    return 0


ARGO_APPLICATION = re.compile(r"(?m)^apiVersion:\s*argoproj\.io/v1alpha1\s*$[\s\S]*?^kind:\s*Application\s*$")


def suggested_gitops_directory(host: Host) -> str:
    """Where veyq's half fits the repository this runs in, relative to the current directory: beside the Argo CD
    Applications the repository already holds — `<their common parent>/veyq` when each sits in a directory of its own
    under one parent (`gitops/apps/traefik/application.yaml` → `gitops/apps/veyq`) — else `apps/veyq`."""
    result = host.run(["git", "rev-parse", "--show-toplevel"], check=False)
    top = Path((result.stdout or "").strip()).resolve() if result.returncode == 0 and (result.stdout or "").strip() else None
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

    def answer(self, key: str) -> str | None:
        value = self.answers.get(key)
        return value if isinstance(value, str) and value else None

    def record(self, key: str, value: str) -> None:
        if self.answers.get(key) != value:
            self.answers[key] = value
            self.setup["veyq"][self.rel] = self.answers
            atomic_write(self.setup_path, json.dumps(self.setup, indent=2, sort_keys=True) + "\n")

    def server_answer(self, key: str) -> str | None:
        value = self.server.get(key)
        return value if isinstance(value, str) and value else None

    def record_server(self, key: str, value: str) -> None:
        if self.server.get(key) != value:
            self.server[key] = value
            self.setup["server"] = self.server
            atomic_write(self.setup_path, json.dumps(self.setup, indent=2, sort_keys=True) + "\n")

    def local(self, key: str) -> str | None:
        value = self.doc["local"].get(key)
        return value if isinstance(value, str) and value else None

    def record_local(self, key: str, value: str) -> None:
        self.doc["local"][key] = value
        config_dir().mkdir(parents=True, exist_ok=True)
        atomic_write(self.path, json.dumps(self.doc, indent=2) + "\n")

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
        raise Refused(f"{path} is not JSON ({e}): it holds init's answers — fix it, or move it away to answer again")
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


def attach_progress(host: Host, checkout: Path, rel: str) -> bool:
    """This directory's progress on `host`; when a run stopped here, say so and ask whether to go on (yes without a
    terminal: the point of resuming is that nothing need be typed again). Returns whether it resumes."""
    progress = Progress(checkout, rel)
    if progress.resumed:
        host.section("veyqctl init — a run here stopped before it finished")
        kept = ", ".join(f"{k}={v}" for k, v in progress.answers.items())
        print(f"   Its answers ({kept or 'none yet'}) are in {progress.setup_path}, its steps in {progress.path}.",
              flush=True)
        if host.confirm("Continue it (no: start over, asking everything again)?") is False:
            progress.start_over()
    host.progress = progress
    return progress.resumed


def resumed_answer(host: Host, key: str, question: str) -> str | None:
    """An answer veyq-setup.json holds, shown as it is taken."""
    progress = getattr(host, "progress", None)
    value = progress.answer(key) if progress else None
    if value:
        print(f"   {question}: {value}   (from {SETUP_FILE})", flush=True)
    return value


def recorded(host: Host, key: str, value: str) -> str:
    progress = getattr(host, "progress", None)
    if progress and value:
        progress.record(key, value)
    return value


def cmd_init(args: argparse.Namespace, host: Host) -> int:
    """Create an instance, list it and make it current: Compose (what `install` does) or GitOps (ADR 0126). Every
    question comes before the first step: the setup answers, then the programs, then the rest."""
    target = "compose" if args.compose else ("gitops" if args.gitops else None)
    resume: tuple[Path, str] | None = None
    if not args.compose and host.which("git"):
        # A GitOps run started here before and stopped? Its answers are in the checkout's veyq-setup.json.
        quiet, host.quiet = host.quiet, True
        here = host.run(["git", "rev-parse", "--show-toplevel"], check=False)
        host.quiet = quiet
        found = (here.stdout or "").strip() if here.returncode == 0 else ""
        top_here = Path(found).resolve() if found else None
        rel = setup_directory(top_here) if top_here and not args.gitops else None
        if rel:
            attach_progress(host, top_here, rel)
            target, resume = "gitops", (top_here, rel)
    if target is None:
        host.section("1. Setup")
        answer = (host.prompt("compose or gitops", hint="compose: veyq on Docker Compose on this host. gitops: veyq's "
                              "half of a repository an Argo CD syncs, upgraded by commit.") or "").strip().lower()
        if answer not in ("compose", "gitops"):
            raise Refused("`init` needs a target: --compose (Docker Compose on this host) or --gitops DIR (veyq's "
                          "directory in an Argo CD repository's checkout); without a terminal, pass one")
        target = answer
    if target == "compose":
        home = Path(args.home or DEFAULT_HOME)
        name = args.name or "veyq"
        name_is_free(name, home)
        host.section("2. Programs")
        require_programs(host, compose_programs("install", args.unsigned_local), "`init --compose`", wait=True)
        args.home = str(home)
        host.workdir = home
        result = cmd_install(args, host)
        if result == 0:
            register(name, home)
        return result
    if args.unsigned_local:
        raise Refused("--unsigned-local is Compose's: a GitOps instance runs only what verifies (ADR 0126 decision 3)")
    # git finds the checkout, before any other question: the one program checked ahead of the rest.
    require_programs(host, ["git"], "`init --gitops`", wait=True)
    host.quiet = True
    if resume:
        host.section("1. Setup")
        return gitops_init(args, host, (resume[0] / resume[1]).resolve())
    if args.gitops:  # else the section began with the target's question
        host.section("1. Setup")
    if args.server is None:
        # Offered `yes` in a repository with nothing committed yet: the server path starts from an empty one.
        here = host.run(["git", "ls-files"], check=False)
        fresh = not here.returncode and not (here.stdout or "").strip()
        args.server = (host.prompt("set up the server too: yes or no", "yes" if fresh else "no",
                                   "yes: a fresh Debian server and its cluster (k3s, Argo CD, Traefik, cert-manager) "
                                   "from veyq-server, then veyq on it. no: veyq's half for a cluster Argo CD runs "
                                   "already.") or ("yes" if fresh else "no")).strip().lower()
    if args.server not in ("yes", "no"):
        raise Refused(f"--server {args.server}: yes or no")
    offered = suggested_gitops_directory(host)
    if args.server == "yes":
        found = host.run(["git", "rev-parse", "--show-toplevel"], check=False)
        if not found.returncode and (found.stdout or "").strip():
            offered = os.path.relpath(Path(found.stdout.strip()).resolve() / SERVER_APPS / "veyq", Path.cwd().resolve())
    directory = args.gitops or host.prompt(
        "directory", offered, "veyq's directory in your checkout of the repository Argo CD syncs; offered beside the "
        "Applications it already holds.") or offered
    if not directory:
        raise Refused("`init --gitops DIR` needs the directory veyq's half goes into, inside a Git checkout")
    path = Path(directory).expanduser().resolve()
    if getattr(host, "progress", None) is None:
        top = repository_of(host, path)
        rel = path.relative_to(top).as_posix() if path != top else ""
        if rel:
            attach_progress(host, top, rel)
    return gitops_init(args, host, path)


# ---- the GitOps target: veyq's half of an Argo CD repository (ADR 0126 decisions 3-5 and 7) ---------------------

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
        raise Refused("the cluster does not meet what veyq's half assumes (ADR 0126 decision 4): " + "; ".join(missing)
                      + ". Nothing was written. (--no-cluster-check writes a repository for a cluster set up later.)")
    say("the cluster meets veyq's contract: Argo CD, the Gateway API and its class, a StorageClass"
        + (", cert-manager" if acme else ""))


def chart_repository(app_ref: str) -> str:
    """The chart beside the release's images: `<registry>/<owner>/charts/veyq` for `<registry>/<owner>/veyq` — where
    the promotion pushes it (ADR 0064)."""
    m = DIGEST_REF.match(app_ref)
    if not m:
        raise Refused(f"the app image `{app_ref}` is not pinned by digest, so no chart can be found beside it")
    return f"{m.group('name').rsplit('/', 1)[0]}/charts/veyq"


def cosign_verify_ref(host: Host, role: str, target: str) -> None:
    cosign = require_cosign(host)
    result = host.run([cosign, "verify", target, "--certificate-oidc-issuer", COSIGN_OIDC_ISSUER,
                       "--certificate-identity-regexp", COSIGN_IDENTITY_REGEXP], check=False)
    if result.returncode != 0:
        raise Refused(f"the {role} {target} does not verify against the identity that releases veyq "
                      f"({COSIGN_IDENTITY_REGEXP}): {(result.stderr or '').strip()[-800:]}\nNothing was written.")
    say(f"{role} {target}: signature verified")


def verify_gitops_release(host: Host, manifest: Manifest, chart: str) -> dict[str, str]:
    """The release's four pins, each verified under the release identity (Verify a release): the manifest's three
    images, and the chart its tag resolves to, verified by its own signature and its Chart.yaml version read back —
    the manifest does not name the chart (cr-veyqctl-gitops-target § Decisions)."""
    if not manifest.tools:
        raise Refused(f"the manifest of {manifest.release} names no tools image (`images.tools`), which the chart's "
                      f"backup gate runs — a GitOps instance installs no release without it")
    verify_images(host, manifest, False)
    tools = cosign_target("tools", manifest.tools)
    cosign_verify_ref(host, "tools image", tools)
    digest = host.oci_digest(f"{chart}:{manifest.release}")
    cosign_verify_ref(host, "chart", f"{chart}@{digest}")
    shown = host.run(["helm", "show", "chart", f"oci://{chart}@{digest}"], check=False)
    version = re.search(r"(?m)^version:\s*\"?([^\"\s]+)\"?\s*$", shown.stdout or "") if not shown.returncode else None
    if version is None or version.group(1) != manifest.release:
        raise Refused(f"the chart {chart}@{digest}, which {chart}:{manifest.release} resolves to, is version "
                      f"{version.group(1) if version else '(unreadable)'}, not {manifest.release}. Nothing was written.")
    pin = lambda ref: DIGEST_REF.match(ref).group("digest")  # noqa: E731 — every ref verified above is one
    return {"chart": digest, "app": pin(manifest.app), "admin": pin(manifest.admin), "tools": pin(manifest.tools)}


def render(host: Host, chart: str, digest: str, namespace: str, values: Path) -> None:
    """`helm template` of the pinned chart with these values: the check that what veyqctl wrote is the chart's input."""
    cmd = ["helm", "template", "veyq", f"oci://{chart}@{digest}", "-n", namespace, "-f", str(values)]
    for api in HELM_API_VERSIONS:
        cmd += ["--api-versions", api]
    result = host.run(cmd, check=False)
    if result.returncode != 0:
        raise Refused(f"`helm template` of the chart with {values.name} failed: {(result.stderr or '').strip()[-800:]}")
    say(f"the chart renders with {values.name}")


def git(host: Host, top: Path, *cmd: str, check: bool = True) -> subprocess.CompletedProcess:
    return host.run(["git", "-C", str(top), *cmd], check=check)


def repository_of(host: Host, directory: Path) -> Path:
    directory = directory.resolve()
    probe = directory
    while not probe.is_dir() and probe != probe.parent:  # `init` writes a directory that may not exist yet, nor its parent
        probe = probe.parent
    result = host.run(["git", "-C", str(probe), "rev-parse", "--show-toplevel"], check=False)
    if result.returncode != 0 or not (result.stdout or "").strip():
        raise Refused(f"{directory} is not inside a Git checkout: a GitOps instance is a directory of the repository "
                      f"Argo CD syncs")
    top = Path(result.stdout.strip()).resolve()
    try:
        directory.relative_to(top)
    except ValueError:
        raise Refused(f"{directory} is not inside its checkout {top}") from None
    return top


def option(args: argparse.Namespace, host: Host, attr: str, question: str, default: str = "", hint: str = "") -> str:
    """A flag's value, else asked at a terminal (Enter takes `default`), else `default`; refused when there is none."""
    value = getattr(args, attr) or resumed_answer(host, attr, question) or host.prompt(question, default, hint) or default
    if not value:
        raise Refused(f"--{attr.replace('_', '-')} is required ({question}): pass it, or run `init` at a terminal")
    return recorded(host, attr, value)


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
                 gateway_class: str, tls: str, acme_email: str, tls_secret: str, peer_namespace: str,
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
        lines += ["    mode: per-domain", "    acme:", "      enabled: true", f"      email: {acme_email}",
                  f"      server: {ACME_DIRECTORIES[acme_directory]}"]
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


def hand_over(name: str, namespace: str, values: dict[str, dict[str, str]], remote: dict[str, str] | None) -> Path:
    """The generated values, once, in a file only the operator can read — outside the repository, never overwritten."""
    path = config_dir() / f"{name}.secrets.json"
    if path.exists():
        raise Refused(f"{path} exists: an earlier `init` handed values over there. Nothing was written — move it "
                      f"away once its values are where they belong, then run `init` again")
    config_dir().mkdir(parents=True, exist_ok=True)
    doc = {"namespace": namespace, "secrets": values}
    if remote:
        doc["remoteKeys"] = remote
    write_private(path, json.dumps(doc, indent=2) + "\n")
    return path


# init's flags that shape what it writes and that it asks no question for (a default, or derived): recorded in
# veyq-setup.json like an answer and taken from it when not given, so a resumed or repeated run writes the same.
SETTLED_FLAGS = {"no_bundled_store": False, "namespace": None, "storage_class": None, "backup_size": "20Gi",
                 "secret_store_kind": "ClusterSecretStore", "remote_prefix": "veyq-", "application_name": "veyq",
                 "argocd_namespace": "argocd"}


def settle_flags(args: argparse.Namespace, host: Host) -> None:
    for attr, default in SETTLED_FLAGS.items():
        given = getattr(args, attr)
        if given == default:
            kept = resumed_answer(host, attr, f"--{attr.replace('_', '-')}")
            if kept is not None:
                given = (kept == "true") if isinstance(default, bool) else kept
                setattr(args, attr, given)
        if given not in (None, ""):
            recorded(host, attr, ("true" if given else "false") if isinstance(default, bool) else str(given))


def gitops_init(args: argparse.Namespace, host: Host, directory: Path) -> int:
    top = repository_of(host, directory)
    rel = directory.relative_to(top).as_posix()
    if rel in ("", "."):
        raise Refused("veyq's half goes into a directory of its own inside the checkout, never its root")
    progress = getattr(host, "progress", None)
    if progress and progress.answers and (directory / GITOPS_FILE).is_file():
        differs = [f"--{key.replace('_', '-')} {getattr(args, key)} (answered: {value})"
                   for key, value in progress.answers.items()
                   if getattr(args, key, None) and getattr(args, key) != SETTLED_FLAGS.get(key)  # given, not a default
                   and str(getattr(args, key)) != value]
        if differs:
            raise Refused(f"{directory} is written from {SETUP_FILE}'s answers, and {'; '.join(differs)} differs: init "
                          f"does not change a written half — `upgrade` moves its release, anything else is an edit of "
                          f"its files. Nothing was written.")
        if not progress.done("written"):
            say(f"veyq's half is in {directory}, written from {SETUP_FILE}'s answers: nothing to write")
        if progress.answer("server") == "yes":  # the server's steps are this machine's: they run again if not done
            args._server = server_questions(args, host, top, progress.answer("name") or top.name)
            server_prepare(args, host, top, args._server, progress.answer("project") or "",
                           progress.answer("repo_url") or "", progress.answer("revision") or "")
            args._domains = [d for d in (progress.answer("domain"), progress.answer("store_domain")) if d]
        return gitops_init_finish(args, host, directory, top, read_gitops(directory))
    if directory.exists() and any(directory.iterdir()):
        raise Refused(f"{directory} holds files already: `init` writes a fresh directory and never overwrites. To have "
                      f"veyqctl manage a veyq half that exists, write {GITOPS_FILE} beside it as "
                      f"{EXISTING_HALF_DOCS} says, then run `veyqctl check`. Nothing was written.")
    settle_flags(args, host)
    name = option(args, host, "name", "instance name", top.name,
                  "How this machine lists it (`veyqctl instances`); not the cluster's.")
    name_is_free(name, directory)
    server = option(args, host, "server", "set up the server too: yes or no", "no") == "yes"
    if server:
        # veyq-server's repository encrypts with SOPS (KSOPS in Argo CD), and its root syncs gitops/apps/.
        if args.secrets not in (None, "sops"):
            raise Refused(f"--secrets {args.secrets}: with the server set up, the secrets are SOPS's (veyq-server's "
                          f"repository decrypts with KSOPS)")
        args.secrets = "sops"
        if (top / SERVER_APPS) not in directory.parents:
            raise Refused(f"with the server set up, veyq's half goes under {top / SERVER_APPS}, which the root "
                          f"Application syncs (e.g. {top / SERVER_APPS / 'veyq'})")
    has_sops = server or any((p / ".sops.yaml").is_file() for p in [directory, *directory.parents]
                             if p == top or top in p.parents)
    provider = option(args, host, "secrets", f"secrets: {', '.join(PROVIDERS)}", "sops" if has_sops else "",
                      "Where the chart's two Secrets come from"
                      + (" (this repository has a .sops.yaml)." if has_sops and not server else "."))
    if provider not in PROVIDERS:
        raise Refused(f"--secrets {provider}: one of {', '.join(PROVIDERS)}")
    if provider == "sops" and not has_sops:
        raise Refused(f"--secrets sops needs a .sops.yaml in {top} or a directory above {directory} naming the "
                      f"repository's keys: sops encrypts with its creation rules. Nothing was written.")

    # Every program the run starts, now that the answers above decided which (ADR 0126): waited for, never installed.
    host.section("2. Programs")
    needed = [*GITOPS_PROGRAMS["init"], *({"sops": ["sops"], "sealed-secrets": ["kubeseal"]}.get(provider, [])),
              *(SERVER_PROGRAMS if server else ())]
    require_programs(host, needed, "`init --gitops`", wait=True)
    print(f"   all there: {', '.join(dict.fromkeys(needed))}", flush=True)
    if server and not host.which("gh"):
        print("   gh: not there — optional: without it you enter Argo CD's deploy key at GitHub yourself", flush=True)

    if server:
        args._server = server_questions(args, host, top, name)
        # The cluster veyq-server sets up is not there yet: what it will be is known, so nothing of it is asked.
        args.context = args._server["connect_name"]
        args.no_cluster_check = True
        args.gateway_class = args.gateway_class or "traefik"
        args.gateway_namespace = args.gateway_namespace or "traefik"
        args.gateway_pod_label = args.gateway_pod_label or "app.kubernetes.io/name=traefik"
        args.admin_host_access = args.admin_host_access or args._server["host_access"]
        # k3s gives its first node 10.42.0.0/24 (measured on a veyq-server cluster, 2026-11-10).
        args.admin_host_source = args.admin_host_source or node_gateway("10.42.0.0/24")
    else:
        host.section("3. The cluster")
    # Asked, with kubectl's current context offered, so the cluster `init` checks is seen before it does.
    current = "" if server else (host.run(["kubectl", "config", "current-context"], check=False).stdout or "").strip()
    context = recorded(host, "context", args.context or resumed_answer(host, "context", "kubeconfig context")
                       or host.prompt("kubeconfig context", current, "The cluster Argo CD runs in: init checks it, "
                                      "`check` reads the Secrets there.") or current)
    k = kubectl(context)
    reachable = not args.no_cluster_check
    classes: list[tuple[str, str]] = []
    cert_manager = False
    if reachable:
        listed_classes = host.run(k + ["get", "gatewayclass", "-o", "jsonpath={range .items[*]}{.metadata.name} "
                                       "{.spec.controllerName}{\"\\n\"}{end}"], check=False)
        classes = [tuple(line.split(" ", 1)) for line in (listed_classes.stdout or "").splitlines()
                   if " " in line] if not listed_classes.returncode else []
        cert_manager = not host.run(k + ["get", "crd", "certificates.cert-manager.io", "-o", "name"],
                                    check=False).returncode

    host.section("4. veyq")
    domain = option(args, host, "domain", "veyq's domain", "",
                    "Its own address: mailed links point here; it cannot be changed later. Needs an A record.")
    garage = not args.no_bundled_store
    store_domain = option(args, host, "store_domain", "the bundled store's host", f"store.{domain}",
                          "Where uploads and downloads go, for every tenant. Needs an A record too.") if garage else ""
    gateway_class = option(args, host, "gateway_class", "GatewayClass", classes[0][0] if len(classes) == 1 else "",
                           "The Gateway API implementation the chart's Gateway is handed to"
                           + (f" (the cluster's: {', '.join(c for c, _ in classes)})." if classes else "."))
    controller = dict(classes).get(gateway_class, "")
    # Traefik's data-plane pods are its own Deployment's: offered from the cluster when the class is Traefik's.
    traefik_ns = ""
    if controller.startswith("traefik.io/"):
        found = host.run(k + ["get", "deploy", "-A", "-l", "app.kubernetes.io/name=traefik", "-o",
                              "jsonpath={.items[0].metadata.namespace}"], check=False)
        traefik_ns = (found.stdout or "").strip() if not found.returncode else ""
    peer_namespace = option(args, host, "gateway_namespace", "its data-plane pods' namespace", traefik_ns,
                            "The pods that proxy the traffic: the only peers the NetworkPolicy lets reach veyq.")
    peer_label = option(args, host, "gateway_pod_label", "a label those pods carry (key=value)",
                        "app.kubernetes.io/name=traefik" if traefik_ns else "")
    if "=" not in peer_label:
        raise Refused(f"--gateway-pod-label {peer_label}: `<key>=<value>`")
    tls = recorded(host, "tls", args.tls or ("acme" if args.acme_email else "") or option(
        args, host, "tls", "TLS: acme, shared or none", "acme" if cert_manager or server else "",
        "acme: certificates per host from Let's Encrypt through the chart's cert-manager issuer"
        + (" (cert-manager runs)" if cert_manager else "") + "; shared: one Secret of yours; none: TLS ends before "
        "the cluster."))
    if tls not in ("acme", "shared", "none"):
        raise Refused(f"--tls {tls}: acme (the chart's cert-manager issuer), shared (one Secret) or none")
    own_email = (git(host, top, "config", "user.email", check=False).stdout or "").strip()
    acme_email = option(args, host, "acme_email", "ACME contact", own_email,
                        "Where Let's Encrypt sends expiry and revocation notices.") if tls == "acme" else ""
    acme_directory = option(
        args, host, "acme_directory", "Let's Encrypt: staging or production", "staging" if server else "production",
        "staging: certificates no browser trusts, without production's rate limits — where a new instance starts; "
        "`veyqctl tls production` switches once everything runs.") if tls == "acme" else "production"
    if acme_directory not in ACME_DIRECTORIES:
        raise Refused(f"--acme-directory {acme_directory}: staging or production")
    tls_secret = option(args, host, "tls_secret", "the TLS Secret for every host") if tls == "shared" else ""
    namespace = args.namespace or "veyq"
    argocd_nodeport = reachable and (host.run(k + ["-n", args.argocd_namespace, "get", "svc", "argocd-server", "-o",
                                                   "jsonpath={.spec.type}"], check=False).stdout or "").strip() == "NodePort"
    admin_host = option(args, host, "admin_host_access", "veyq's administration through the tunnel: yes or no",
                        "yes" if argocd_nodeport else "no",
                        f"A host-only NodePort ({ADMIN_HOST_PORT}) beside Argo CD's, for a server whose firewall keeps "
                        "NodePorts off the public side (veyq-server's)" + (" — Argo CD's is one here." if argocd_nodeport
                                                                            else "; without it, `kubectl port-forward`."))
    if admin_host not in ("yes", "no"):
        raise Refused(f"--admin-host-access {admin_host}: yes or no")
    admin_source = ""
    if admin_host == "yes":
        cidr = (host.run(k + ["get", "nodes", "-o", "jsonpath={.items[0].spec.podCIDR}"], check=False).stdout or "") \
            if reachable else ""
        admin_source = option(args, host, "admin_host_source", "the node's pod-network gateway (a /32)",
                              node_gateway(cidr), "Where a NodePort's traffic reaches the pod from; the NetworkPolicy "
                              "admits it alone.")

    if not args.no_cluster_check:
        host.quiet = False
        check_cluster(host, context, gateway_class, args.storage_class or "", tls == "acme")
        host.quiet = True
    # The Argo CD project the Application joins: offered from the cluster — the one project besides `default` when
    # there is exactly one (a project that limits sources and destinations, which `default` does not), else `default`.
    listed_projects = subprocess.CompletedProcess([], 1, "", "") if server else host.run(
        k + ["-n", args.argocd_namespace, "get", "appprojects", "-o", "name"], check=False)
    projects = [line.split("/", 1)[-1] for line in (listed_projects.stdout or "").split()
                if not listed_projects.returncode]
    own = [p for p in projects if p != "default"]
    host.section("5. Argo CD and the release")
    own_project = re.sub(r"[^a-z0-9.-]", "-", name.lower()).strip("-.") or "server"
    project = option(args, host, "project", "Argo CD project",
                     own_project if server else own[0] if len(own) == 1 else "default",
                     "The project the Application joins: one of your own limits its sources and destinations.")
    store = remote_prefix = store_kind = ""
    if provider == "external-secrets":
        store = option(args, host, "secret_store", "the SecretStore the ExternalSecrets read from")
        store_kind = args.secret_store_kind
        remote_prefix = args.remote_prefix
    repo_url = args.repo_url or resumed_answer(host, "repo_url", "repository URL") or (
        git(host, top, "remote", "get-url", "origin", check=False).stdout or "").strip()
    github = GITHUB_REPO.fullmatch(repo_url)
    if server and github:  # Argo CD reads it with the deploy key: over SSH
        repo_url = f"git@github.com:{github.group(1)}.git"
    recorded(host, "repo_url", repo_url)
    if not repo_url:
        raise Refused("--repo-url is required: the URL Argo CD reads this repository from (no `origin` remote)")
    revision = args.revision or resumed_answer(host, "revision", "branch") or (
        git(host, top, "rev-parse", "--abbrev-ref", "HEAD", check=False).stdout or "").strip()
    if revision == "HEAD" or not revision:  # an empty repository: the branch its first commit goes to
        revision = (git(host, top, "symbolic-ref", "--short", "HEAD", check=False).stdout or "").strip()
    if not revision or revision == "HEAD":
        raise Refused("--revision is required: the branch Argo CD follows (the checkout is on none)")
    recorded(host, "revision", revision)

    source = open_source(args.source)
    listed = source.releases()
    # Which releases `upgrade` follows: offered as the newest release's major, so a new major is entered only when
    # its owner sets `latest` or the next major, knowingly (ADR 0085: a major removes something).
    newest = select(listed, "latest", source.describe())
    selector = check_selector(option(args, host, "release", "selector", str(newest.major),
                                     "Which releases `upgrade` follows: <major>.<minor> pinned, <major> its newest, "
                                     "latest the newest of all. A new major removes something: enter it knowingly."))
    host.quiet = False
    if server:  # every question is answered: the steps begin, the server half first (veyq's encrypts with its rules)
        server_prepare(args, host, top, args._server, project, repo_url, revision)
        args._domains = [domain] + ([store_domain] if garage else [])
        host.section("7. veyq's half")
    chosen = select(listed, selector, source.describe())
    say(f"{RELEASE_KEY}={selector} takes release {chosen.release} from {source.describe()}")
    manifest = fetch_manifest(host, source, chosen, False)
    chart = chart_repository(manifest.app)
    pins = verify_gitops_release(host, manifest, chart)

    handed_before = progress.done("handed") if progress else None
    if handed_before and Path(handed_before).is_file():
        # The last run handed these over and stopped before writing: the same values, never new ones.
        values = json.loads(Path(handed_before).read_text())["secrets"]
    else:
        values = {secret: {key: make() for key, make in keys.items()} for secret, keys in SECRET_VALUES.items()}
    files: dict[str, str] = {
        # The Application's name is the cluster's, never the instance's: `--name` is how this machine lists it.
        "application.yaml": application_text(name=args.application_name, argocd_namespace=args.argocd_namespace,
                                             project=project,
                                             chart=chart, digest=pins["chart"], rel=rel, repo_url=repo_url,
                                             revision=revision, namespace=namespace, values="values.yaml"),
        "values.yaml": stack_values(chosen.release, pins, domain=domain, store_domain=store_domain, garage=garage,
                                    gateway_class=gateway_class, tls=tls, acme_email=acme_email, tls_secret=tls_secret,
                                    peer_namespace=peer_namespace, peer_label=peer_label, provider=provider,
                                    acme_directory=acme_directory),
        "resources/namespace.yaml": namespace_text(namespace),
        "resources/backup-pvc.yaml": backup_claim_text(namespace, args.storage_class or "", args.backup_size),
    }
    listed = ["namespace.yaml", "backup-pvc.yaml"]
    if admin_host == "yes":
        files["resources/admin-host-access.yaml"] = admin_host_access_text(namespace, admin_source)
        listed.append("admin-host-access.yaml")
    generators: list[str] = []
    handed: Path | None = None
    if provider == "sops":
        for secret, file in ((SECRETS_SECRET, "secrets.sops.yaml"), (CONFIG_SECRET, "config-secret-key.sops.yaml")):
            target = directory / "resources" / file
            plain = secret_text(secret, namespace, values[secret]).encode()
            encrypted = sops_run(host, ["sops", "encrypt", "--filename-override", str(target), "--input-type",
                                        "yaml", "--output-type", "yaml", "/dev/stdin"], target, stdin=plain).stdout
            if not encrypted or "ENC[" not in encrypted:
                raise Refused(f"sops did not encrypt {file} (no ENC[…] in its output): check {top}/.sops.yaml's "
                              f"creation rules. Nothing was written.")
            files[f"resources/{file}"] = encrypted
        files["resources/secret-generator.yaml"] = """# KSOPS: decrypts the listed SOPS files in Argo CD's repo server.
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
        generators.append("secret-generator.yaml")
    elif provider == "sealed-secrets":
        for secret, file in ((SECRETS_SECRET, "secrets.sealed.yaml"), (CONFIG_SECRET, "config-secret-key.sealed.yaml")):
            sealed = host.run(["kubeseal", *(["--context", context] if context else []), "--format", "yaml"],
                              stdin=secret_text(secret, namespace, values[secret]).encode()).stdout
            if "SealedSecret" not in (sealed or ""):
                raise Refused(f"kubeseal returned no SealedSecret for {secret}. Nothing was written.")
            files[f"resources/{file}"] = sealed
            listed.append(file)
    elif provider == "external-secrets":
        for secret, file in ((SECRETS_SECRET, "secrets.external.yaml"),
                             (CONFIG_SECRET, "config-secret-key.external.yaml")):
            files[f"resources/{file}"] = external_secret_text(secret, namespace, store, store_kind, remote_prefix)
            listed.append(file)
    kustomization = ("# What the chart expects and does not create, written by veyqctl (ADR 0126).\n"
                     "apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources:\n"
                     + "".join(f"  - {f}\n" for f in listed)
                     + ("generators:\n" + "".join(f"  - {f}\n" for f in generators) if generators else ""))
    files["resources/kustomization.yaml"] = kustomization
    g = GitOps(directory, {"target": "gitops", "release": selector, "secrets": provider,
                           "application": "application.yaml", "values": "values.yaml", "chart": chart,
                           "namespace": namespace, "context": context or "", "source": args.source or "",
                           "running": chosen.release, "pins": pins})

    with tempfile.TemporaryDirectory(prefix="veyqctl-values-") as tmp:
        staged = Path(tmp) / "values.yaml"
        staged.write_text(files["values.yaml"])
        render(host, chart, pins["chart"], namespace, staged)
    if provider != "sops":
        remote = ({remote_key(remote_prefix, k): v for keys in values.values() for k, v in keys.items()}
                  if provider == "external-secrets" else None)
        handed = Path(handed_before) if handed_before and Path(handed_before).is_file() else \
            hand_over(name, namespace, values, remote)
        if progress:
            progress.mark("handed", str(handed))
    (directory / "resources").mkdir(parents=True, exist_ok=True)
    for relative, text in files.items():
        (directory / relative).write_text(text)
    write_gitops(g)
    say(f"wrote veyq's half into {directory}: {', '.join(sorted(files))} and {GITOPS_FILE} (release {chosen.release})")
    if progress:
        progress.mark("written")
    return gitops_init_finish(args, host, directory, top, g)


def gitops_init_finish(args: argparse.Namespace, host: Host, directory: Path, top: Path, g: GitOps) -> int:
    """What follows the written half — listing it and what is left to the person — reached again by a run that resumes
    after the files were written."""
    progress = getattr(host, "progress", None)
    name = (progress.answer("name") if progress else None) or args.name or top.name
    provider, context = g.doc["secrets"], g.doc.get("context") or None
    rel = directory.relative_to(top).as_posix()
    handed = progress.done("handed") if progress else None
    handed = Path(handed) if handed else None
    store = (progress.answer("secret_store") if progress else None) or args.secret_store or ""
    repo_url = (progress.answer("repo_url") if progress and getattr(args, "_server", None) else None) \
        or args.repo_url or (git(host, top, "remote", "get-url", "origin", check=False).stdout or "").strip()
    register(name, directory)
    if handed:
        say(f"the generated values are in {handed} (0600), once: "
            + ("put each under its remote key into the store `" + store + "` reads, " if provider == "external-secrets"
               else "create the two Secrets from it in the namespace, " if provider == "none"
               else "they are sealed into the repository; ")
            + "then move the file off this machine")
    say(f"KEEP THE CONFIG-SECRET KEY in a password manager as well (`{CONFIG_SECRET_KEY}` of {CONFIG_SECRET}): without "
        f"it no backup can be read (ADR 0050)"
        + (f" — `sops decrypt --extract '[\"stringData\"][\"{CONFIG_SECRET_KEY}\"]' "
           f"{directory / 'resources/config-secret-key.sops.yaml'}`" if provider == "sops" else ""))
    if getattr(args, "_server", None):
        server_finish(args, host, top, directory, args._server, g, repo_url, (progress.answer("revision") if progress
                      else None) or args.revision, args._domains)
    else:
        say(f"next: review, `git -C {top} add {rel} {SETUP_FILE} && git commit`, push, give Argo CD read access to "
            f"{repo_url} (a deploy key), and register the Application once: `{' '.join(kubectl(context))} apply -f "
            f"{directory / 'application.yaml'}`")
    if progress:
        progress.clear_steps()
    return 0


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


def server_option(args: argparse.Namespace, host: Host, attr: str, question: str, default: str = "", hint: str = "",
                  pattern: str = "") -> str:
    """A server answer: the flag, else veyq-setup.json's `server` section, else asked until it matches `pattern`."""
    progress = getattr(host, "progress", None)
    value = getattr(args, attr, None) or ""
    if not value and progress and progress.server_answer(attr):
        value = progress.server_answer(attr)
        print(f"   {question}: {value}   (from {SETUP_FILE})", flush=True)
    while not value:
        answer = host.prompt(question, default, hint)
        if answer is None:
            value = default
            break
        if pattern and not re.fullmatch(pattern, answer):
            print("   That does not look right, try again.", flush=True)
            continue
        value = answer
    if not value:
        raise Refused(f"--{attr.replace('_', '-')} is required ({question}): pass it, or run `init` at a terminal")
    if pattern and not re.fullmatch(pattern, value):
        raise Refused(f"--{attr.replace('_', '-')} {value}: not {question}")
    if progress:
        progress.record_server(attr, value)
    return value


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
    result = host.run(["age-keygen", "-y", str(key_file)], check=False)
    recipient = (result.stdout or "").strip()
    if result.returncode or not re.fullmatch(AGE_RECIPIENT_RE, recipient):
        raise Refused(f"{key_file} holds no age key (`age-keygen -y` derives none from it)")
    return recipient


def server_questions(args: argparse.Namespace, host: Host, top: Path, name: str) -> dict[str, str]:
    """The server's questions (what a setup script asked), each recorded in veyq-setup.json's `server` section; the
    age key's path is this machine's and kept in the progress file. No action happens here but a new age key."""
    host.section("3. The server")
    answers: dict[str, str] = {}
    answers["server_address"] = server_option(
        args, host, "server_address", "server address (DNS name or IPv4)", "",
        "A fresh Debian 13 server, reachable as root over SSH with your key.", f"{HOSTNAME_RE}|{IPV4_RE}")
    address = answers["server_address"]
    answers["node_name"] = server_option(
        args, host, "node_name", "node name", "server" if address[0].isdigit() else address.split(".")[0],
        "The cluster's node.", r"[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")
    own = re.sub(r"[^a-z0-9_-]", "", os.environ.get("USER", "").split(".")[0].lower())
    answers["admin_user"] = server_option(
        args, host, "admin_user", "admin user", own if own not in ("root", "ci") else "",
        "The account the bootstrap creates; root's SSH login is switched off.", r"(?!root$|ci$)[a-z_][a-z0-9_-]{0,31}")
    progress = getattr(host, "progress", None)
    if not (args.admin_ssh_public_key or (progress and progress.server_answer("admin_ssh_public_key"))):
        candidates = ssh_key_candidates(host)
        if candidates:
            print("\n   Your public SSH keys:", flush=True)
            for i, (key, label) in enumerate(candidates, 1):
                print(f"   {i}) {key.split()[0]} …{key.split()[1][-12:]}  {label}", flush=True)
        picked = host.prompt("admin SSH key: a number, or paste a public key", "1" if len(candidates) == 1 else "",
                             "The key the admin user logs in with.") or ""
        if picked.isdigit() and 1 <= int(picked) <= len(candidates):
            args.admin_ssh_public_key = candidates[int(picked) - 1][0]
        elif picked:
            args.admin_ssh_public_key = picked
    if args.admin_ssh_public_key:  # the key alone: its comment is the operator's, not the server's
        m = re.match(SSH_PUBLIC_KEY_RE, args.admin_ssh_public_key.strip())
        args.admin_ssh_public_key = m.group(0) if m else args.admin_ssh_public_key
    answers["admin_ssh_public_key"] = server_option(args, host, "admin_ssh_public_key", "admin SSH public key",
                                                    pattern=SSH_PUBLIC_KEY_RE)
    # The age key: the one SOPS encrypts the repository's secrets to. Its private half stays on this machine (and in
    # the owner's password manager); only its public half is in the repository.
    key_file = Path(args.age_key).expanduser() if args.age_key else None
    if key_file is None and progress and progress.local("age_key_file"):
        key_file = Path(progress.local("age_key_file"))
    if key_file is None and (top / AGE_KEY_FILE).is_file():
        key_file = top / AGE_KEY_FILE
    if key_file is None:
        answer = host.prompt("age key file (Enter: create a new one in .age/key.txt)", "",
                             "The key the repository's secrets are encrypted to; keep its only other copy in a "
                             "password manager.")
        if answer:
            key_file = Path(answer).expanduser()
        elif answer is None:
            raise Refused("--age-key is required (the age key file the secrets are encrypted to): pass it, or run "
                          "`init` at a terminal, where Enter creates one")
        else:
            key_file = top / AGE_KEY_FILE
            key_file.parent.mkdir(mode=0o700, exist_ok=True)
            host.run(["age-keygen", "-o", str(key_file)])
            key_file.chmod(0o600)
            say(f"made a new age key in {key_file} (0600, gitignored). Put a copy into your password manager now: "
                f"without it nothing in the repository can be decrypted, and no lost server set up again.")
            if not host.wait("Kept it in your password manager? Press Enter"):
                raise Refused(f"the new age key in {key_file} needs a copy in your password manager first")
    key_file = key_file.resolve()
    recipient = age_recipient_of(host, key_file)
    kept = progress.server_answer("age_recipient") if progress else None
    if kept and kept != recipient:
        raise Refused(f"{key_file} is the age key of {recipient}, and {SETUP_FILE} names {kept}: the repository's "
                      f"secrets are encrypted to that one — give its key file (--age-key)")
    answers["age_recipient"] = recipient
    if progress:
        progress.record_server("age_recipient", recipient)
        progress.record_local("age_key_file", str(key_file))
    answers["host_access"] = server_option(
        args, host, "host_access", "Argo CD and veyq's administration through the SSH tunnel: yes or no", "yes",
        "Host-only NodePorts the server's firewall keeps off the public side.", "yes|no")
    answers["connect_name"] = server_option(
        args, host, "connect_name", "this machine's name for the server (SSH host and kubeconfig context)", name,
        "What `ssh -N <name>` opens the tunnel with and `kubectl --context <name>` reaches the cluster by.",
        r"[A-Za-z0-9._-]+")
    current = (progress.local("make_current") if progress else None) or args.make_current or (
        host.prompt("make it kubectl's current context: yes or no", "no") or "no")
    if current not in ("yes", "no"):
        raise Refused(f"--make-current {current}: yes or no")
    answers["make_current"] = current
    if progress:
        progress.record_local("make_current", current)
    if not (top / SERVER_SECRETS).is_file():
        # Never recorded, never a flag: it goes into the repository encrypted, only.
        answers["admin_password"] = host.secret("admin user's sudo password (hidden, Enter: generate one)") or ""
    return answers


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
        raise Refused(f"veyq-server {release}'s commit {commit[:12]} checked out to a tree whose sha256 is {found}, and "
                      f"its signed manifest says {tree}. Nothing was rendered.")
    say(f"veyq-server {release}: commit {commit[:12]}, tree verified")
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


def sops_env(host: Host) -> dict[str, str]:
    progress = getattr(host, "progress", None)
    key_file = progress.local("age_key_file") if progress else None
    return {"SOPS_AGE_KEY_FILE": key_file} if key_file else {}


def server_secrets(host: Host, top: Path, answers: dict[str, str], repo_name: str) -> str:
    """The server half's secrets, encrypted once (a file that exists is never made again): the admin password (as
    given, or generated), its salt, and Argo CD's deploy key. Returns the deploy key's public half."""
    target = top / SERVER_SECRETS
    if target.is_file():
        private = sops_run(host, ["sops", "decrypt", "--extract", '["argocd_repo_ssh_private_key"]', str(target)],
                           target, env=sops_env(host)).stdout
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
        raise Refused(f"{SERVER_SECRETS_TEMPLATE} holds markers veyqctl does not fill ({', '.join(left)}): a veyq-server "
                      f"release newer than this veyqctl? Nothing was encrypted.")
    encrypted = sops_run(host, ["sops", "encrypt", "--filename-override", str(target), "--input-type", "yaml",
                                "--output-type", "yaml", "/dev/stdin"], target, stdin=text.encode()).stdout
    if not encrypted or "ENC[" not in encrypted:
        raise Refused(f"sops did not encrypt {SERVER_SECRETS} (no ENC[…] in its output): check .sops.yaml")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(encrypted)
    say(f"wrote {SERVER_SECRETS}: the admin password" + (" (generated)" if not answers.get("admin_password") else "")
        + ", its salt and Argo CD's deploy key, encrypted to the age key")
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
        raise Refused(f"{address} did not answer on port 22 (ssh-keyscan): is the server up and reachable? The same "
                      f"command goes on from here.")
    if pinned:  # a rebuilt server under the same address presents a new key
        print(f"\n   {address} presents {fingerprint(host, line)} (ED25519), and ansible/known_hosts pins "
              f"{fingerprint(host, pinned)}: a rebuilt server?", flush=True)
    else:
        print(f"\n   {address} presents {fingerprint(host, line)} (ED25519).", flush=True)
    if host.confirm("Does it match the fingerprint in your provider's console (or the server's first-boot log)? "
                    "Yes pins it in ansible/known_hosts, and in ~/.ssh/known_hosts before the bootstrap") is not True:
        raise Refused(f"the host key of {address} was not confirmed: nothing logs in to it. Compare it in your "
                      f"provider's console and run the same command again.")
    known = top / SERVER_KNOWN_HOSTS
    kept = [l for l in known.read_text().splitlines() if not l.strip() or l.lstrip().startswith("#")] \
        if known.is_file() else []
    known.write_text("\n".join(kept + [line]) + "\n")
    return line


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
            say(f"Argo CD's deploy key is entered at {repo} already")
            return
        if host.confirm(f"Enter Argo CD's deploy key at {repo}, read-only, with gh?") is True:
            with tempfile.TemporaryDirectory(prefix="veyqctl-key-") as tmp:
                pub = Path(tmp) / "key.pub"
                pub.write_text(public + "\n")
                host.run(["gh", "repo", "deploy-key", "add", str(pub), "--repo", repo, "--title",
                          f"{public.split()[-1]} (read-only)"])
            say(f"entered Argo CD's deploy key at {repo}, read-only")
            return
    where = f"https://github.com/{repo}/settings/keys/new" if repo else f"your forge's settings of {repo_url}"
    print(f"\n   Enter this deploy key at {where}, read-only (write access off):\n\n   {public}\n", flush=True)
    if not host.wait("Press Enter once it is entered"):
        raise Refused(f"Argo CD's deploy key is to be entered at {where} first: {public}")


def await_dns(host: Host, address: str, names: list[str]) -> None:
    """The A records veyq's hosts need, checked to resolve to the server; waited for (each host is a certificate
    order the moment Argo CD syncs veyq)."""
    want = host.resolve(address)
    while True:
        wrong = [n for n in names if not want or host.resolve(n) - want or not host.resolve(n)]
        if not wrong:
            say(f"DNS: {', '.join(names)} resolve to {', '.join(sorted(want))}")
            return
        target = ", ".join(sorted(want)) or address
        message = (f"Set an A record for each of {', '.join(wrong)} to {target} (no AAAA: the cluster is IPv4 only).\n"
                   f"Press Enter to look again (Ctrl-C: later — the same command goes on)")
        if not host.wait(message):
            raise Refused(f"{', '.join(wrong)} do not resolve to {target} yet: set the A records and run the same "
                          f"command again")


def commit_and_push(host: Host, top: Path, branch: str, message: str) -> None:
    """Everything init wrote, committed and pushed on confirmation; then the push check `play` made: the bootstrap
    applies the AppProject and root from this checkout, and the root's first sync takes the remote's copy over them."""
    status = git(host, top, "status", "--porcelain", check=False).stdout or ""
    changed = [line[3:] for line in status.splitlines() if line.strip()]
    if changed:
        print(f"\n   {len(changed)} new or changed: " + ", ".join(changed[:8]) + (" …" if len(changed) > 8 else ""),
              flush=True)
        if host.confirm(f"Commit them ({message!r}) and push to origin {branch}?") is not True:
            raise Refused("not committed: the bootstrap needs everything on the remote. The same command asks again.")
        git(host, top, "add", "--all")
        git(host, top, "commit", "--quiet", "-m", message)
        git(host, top, "push", "--quiet", "-u", "origin", branch)
    elif git(host, top, "rev-parse", "--verify", "--quiet", f"origin/{branch}", check=False).returncode:
        if host.confirm(f"Push to origin {branch}?") is not True:
            raise Refused("not pushed: the bootstrap needs everything on the remote. The same command asks again.")
        git(host, top, "push", "--quiet", "-u", "origin", branch)
    git(host, top, "fetch", "--quiet", "origin", branch)
    if git(host, top, "diff", "--quiet", "HEAD", f"origin/{branch}", "--", "gitops", ".sops.yaml",
           check=False).returncode:
        raise Refused(f"origin/{branch} differs from this checkout under gitops/: push first — Argo CD's first sync "
                      f"takes the remote's copy over what the bootstrap applied")


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
    say(f"{known}: {address}'s {kind} key is the pinned one")


def ansible_core(host: Host) -> tuple[int, ...]:
    shown = host.run(["ansible-playbook", "--version"], check=False).stdout or ""
    m = re.search(r"core (\d+)\.(\d+)", shown)
    return (int(m.group(1)), int(m.group(2))) if m else (0, 0)


def bootstrap(host: Host, top: Path, answers: dict[str, str], pinned: str) -> None:
    """The bootstrap playbook, asked first (it runs as root on the server and cannot be undone) — unless the admin
    user logs in already: the server is set up, and root's login is off by then."""
    address = answers["server_address"]
    trust_host_key(host, address, pinned)
    probe = host.run(["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15", "-o", "ClearAllForwardings=yes",
                      f"{answers['admin_user']}@{address}", "true"], check=False)
    if not probe.returncode:
        say(f"{answers['admin_user']} logs in to {address} already: the server is bootstrapped, nothing to run")
        return
    if ansible_core(host) < ANSIBLE_CORE_FLOOR:
        raise Refused(f"ansible-core {'.'.join(map(str, ANSIBLE_CORE_FLOOR))} or later is needed (the playbooks "
                      f"assert it); `ansible-playbook --version` names an older one first on PATH")
    if host.confirm(f"Run the bootstrap: as root on {address} it creates {answers['admin_user']}, switches root's and "
                    f"password logins off, sets the firewall, installs k3s and Argo CD?") is not True:
        raise Refused("the bootstrap was not run. The same command asks again.")
    workdir, host.workdir = host.workdir, top / "ansible"
    try:
        host.run(["ansible-galaxy", "collection", "install", "-r", "requirements.yml", "-p", "collections"])
        host.run(["ansible-playbook", "playbooks/bootstrap.yml"], stream=True, env=sops_env(host))
    finally:
        host.workdir = workdir


SSH_BLOCK = re.compile(r"^# >>> (?P<name>\S+) \(veyqctl connect\)\n(?P<body>.*?)^# <<< (?P=name)\n\n?", re.S | re.M)
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
        raise Refused(f"{config} has a `Host {name}` of its own: veyqctl never shadows it — choose another name "
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
    say(f"{config}: `Host {name}` — `ssh -N {name}` opens the tunnel")

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
    say(f"{kubeconfig}: context {name} (through the tunnel, https://127.0.0.1:{CONNECT_PORTS['api']})")
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
                say(f"Argo CD: {', '.join(sorted(states))} Synced and Healthy")
                return
            shown = ", ".join(f"{n} {'/'.join(s)}" for n, s in sorted(pending.items())) or "no application yet"
            if shown != last:
                say(f"waiting for Argo CD: {shown}")
                last = shown
            if time.monotonic() > deadline:
                raise Refused(f"Argo CD did not report everything Synced and Healthy within {int(timeout)} s ({shown}). "
                              f"Look with `kubectl --context {name} -n argocd get applications` through `ssh -N {name}`; "
                              f"the same command goes on waiting.")
            host.sleep(10)
    finally:
        tunnel.terminate()


def server_prepare(args: argparse.Namespace, host: Host, top: Path, answers: dict[str, str], project: str,
                   repo_url: str, branch: str) -> None:
    """The server half into the checkout, before veyq's: veyq-server verified and rendered (once), its secrets
    encrypted (once), the host key pinned (asked)."""
    progress = getattr(host, "progress", None)
    host.section("6. veyq-server")
    if not (top / ".copier-answers.yml").is_file():
        with tempfile.TemporaryDirectory(prefix="veyqctl-veyq-server-") as tmp:
            template = Path(tmp) / "veyq-server"
            release = verify_server_template(host, template)
            render_server(host, top, template, release["commit"], answers, project, repo_url, branch)
        say(f"rendered veyq-server {release['release']} into {top}")
        if progress:
            progress.mark("rendered", release["release"])
    else:
        say(f"veyq-server is rendered in {top} already (.copier-answers.yml)")
    m = GITHUB_REPO.fullmatch(repo_url)
    args._deploy_key = server_secrets(host, top, answers, m.group(1) if m else top.name)
    args._pinned = pin_host_key(host, top, answers["server_address"])


def server_finish(args: argparse.Namespace, host: Host, top: Path, directory: Path, answers: dict[str, str],
                  g: "GitOps", repo_url: str, branch: str, domains: list[str]) -> None:
    """What follows the written halves on the server path, each step said as it comes: veyq's Application listed for
    the root, the deploy key at the forge, the DNS records, commit and push, the bootstrap, this machine connected,
    and the wait until Argo CD reports everything Synced and Healthy."""
    name = answers["connect_name"]
    list_veyq_application(top, directory)
    host.section("7. The deploy key")
    deploy_key_at_forge(host, repo_url, args._deploy_key)
    host.section("8. DNS")
    await_dns(host, answers["server_address"], domains)
    host.section("9. Commit and push")
    commit_and_push(host, top, branch, f"veyq-server and veyq {g.doc['running']}, set up by veyqctl init")
    host.section("10. Bootstrap")
    bootstrap(host, top, answers, args._pinned)
    host.section("11. Connect")
    connect(host, name, answers["server_address"], answers["admin_user"], answers["host_access"] == "yes",
            (directory / "resources" / "admin-host-access.yaml").is_file(), answers["make_current"] == "yes")
    await_synced(host, name, args.application_name, args.sync_timeout)
    host.section("Done")
    tunnel = f"`ssh -N {name}` opens the tunnel"
    say(f"{tunnel}; Argo CD's first password: `kubectl --context {name} -n argocd get secret "
        f"argocd-initial-admin-secret -o jsonpath='{{.data.password}}' | base64 -d` — sign in as admin at "
        + (f"https://localhost:{CONNECT_PORTS['argocd']}" if answers["host_access"] == "yes"
           else f"`kubectl --context {name} -n argocd port-forward svc/argocd-server 8080:443`")
        + ", change it, then delete that Secret")
    if (directory / "resources" / "admin-host-access.yaml").is_file():
        say(f"the Server Owner: veyq's administration at http://localhost:{CONNECT_PORTS['admin']} (through the tunnel)")
    say(f"the admin user's sudo password: `sops decrypt --extract '[\"admin_password\"]' {top / SERVER_SECRETS}`")
    say(f"KEEP THE AGE KEY ({sops_env(host).get('SOPS_AGE_KEY_FILE', 'its file')}) in your password manager: with it "
        f"and this repository the server is set up again by `veyqctl init`")


def cmd_connect(args: argparse.Namespace, host: Host) -> int:
    """This machine's SSH host and kubeconfig context for the server the current checkout's veyq-setup.json describes:
    what `init` does at its end, again at any time (a rebuild's new cluster certificate, another machine)."""
    require_programs(host, ["git", "ssh", "kubectl"], "`connect`")
    found = host.run(["git", "rev-parse", "--show-toplevel"], check=False)
    top = Path((found.stdout or "").strip()).resolve() if not found.returncode and (found.stdout or "").strip() else None
    setup = read_setup(top) if top else {}
    server = setup.get("server") or {}
    if not server.get("server_address") or not server.get("admin_user"):
        raise Refused(f"`connect` runs in a checkout whose {SETUP_FILE} describes a server (`veyqctl init` with the "
                      f"server set up writes it); {top or 'this directory'} has none")
    name = args.connect_name or server.get("connect_name") or top.name
    if not re.fullmatch(r"[A-Za-z0-9._-]+", name):
        raise Refused(f"--connect-name {name}: letters, digits, `.`, `_` and `-`")
    admin = any((v or {}).get("admin_host_access") == "yes" for v in (setup.get("veyq") or {}).values())
    connect(host, name, server["server_address"], server["admin_user"], server.get("host_access") == "yes", admin,
            args.make_current == "yes")
    return 0


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
        raise Refused(f"{what}: {old} appears {count} times, not once — veyqctl rewrites a pin only where it finds it "
                      f"exactly once. Nothing was changed.")
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
    pins = verify_gitops_release(host, fetch_manifest(host, source, running, False), g.doc["chart"])
    return [f"{role} {g.doc['pins'][role]} (release {running.release}: {pins[role]})" for role in PIN_ROLES
            if g.doc["pins"][role] != pins[role]]


def gitops_verify(args: argparse.Namespace, host: Host, g: GitOps) -> int:
    source = open_source(args.source or g.doc.get("source") or None)
    selector = check_selector(args.release or g.doc["release"])
    chosen = select(source.releases(), selector, source.describe())
    say(f"{RELEASE_KEY}={selector} takes release {chosen.release} from {source.describe()}")
    verify_gitops_release(host, fetch_manifest(host, source, chosen, False), g.doc["chart"])
    say(f"release {chosen.release}: its manifest, three images and chart verified; nothing was written")
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
    source = open_source(args.source or g.doc.get("source") or None)
    selector = check_selector(args.release or g.doc["release"])
    listed = source.releases()
    chosen = select(listed, selector, source.describe())
    running = g.doc["running"]
    current = tuple(int(x) for x in running.split("."))
    if chosen.release == running:
        say(f"{RELEASE_KEY}={selector}: release {running} is pinned already; `upgrade` would do nothing")
    elif (chosen.major, chosen.minor) < current:
        say(f"{RELEASE_KEY}={selector} selects {chosen.release}, below the pinned {running}: `upgrade` would refuse")
    else:
        manifest_of = manifest_reader(host, source, False)
        target = upgrade_target(listed, current, chosen, source.describe(), lambda r: manifest_of(r).major_entry)
        say(f"{RELEASE_KEY}={selector}: `upgrade` would take release {target.release} (pinned: {running})")
        if target != chosen:
            say(entry_note(selector, chosen, target, "the `upgrade` after that one"))
    beyond = newer_major(listed, chosen)
    if beyond:
        say(f"a newer major exists beyond {RELEASE_KEY}={selector}: {beyond.release} — read its notes before selecting it")
    drift = gitops_drift(g)
    for line in drift:
        say(f"DRIFT: {line}, not what {GITOPS_FILE} records")
    differs = running_differs(host, source, listed, g)
    if differs:
        say(f"NOT RELEASE {running}: {GITOPS_FILE} pins " + "; ".join(differs) + " — `upgrade` refuses until it pins "
            f"what that release signed")
    else:
        say(f"the pins in {GITOPS_FILE} are release {running}'s, as it was signed")
    drift = drift + differs
    namespace = g.doc.get("namespace") or "veyq"
    k = kubectl(g.doc.get("context") or None)
    if host.run(k + ["get", "namespace", namespace, "-o", "name"], check=False).returncode:
        say(f"the cluster or the namespace {namespace} is not reachable: the Secrets were not checked")
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
        say(f"the Secrets the chart reads are not complete ({g.doc['secrets']}): " + "; ".join(absent))
    else:
        say(f"the Secrets {SECRETS_SECRET} and {CONFIG_SECRET} exist with every key")
    return 1 if drift or absent else 0


def gitops_upgrade(args: argparse.Namespace, host: Host, g: GitOps) -> int:
    """ADR 0126 decision 3: ADR 0096 decision 5's resolve and verify, then a commit instead of a restart."""
    if args.unsigned_local or args.key_into or args.objects_to:
        raise Refused("--unsigned-local, --key-into and --objects-to are Compose's: on a GitOps instance the chart's "
                      "backup gate captures before the new release starts (ADR 0098)")
    source = open_source(args.source or g.doc.get("source") or None)
    selector = check_selector(g.doc["release"])
    listed = source.releases()
    chosen = select(listed, selector, source.describe())
    running = g.doc["running"]
    current = tuple(int(x) for x in running.split("."))
    if chosen.release == running:
        say(f"{RELEASE_KEY}={selector}: release {running} is pinned already; nothing to do")
        return 0
    if (chosen.major, chosen.minor) < current:
        raise Refused(f"{RELEASE_KEY}={selector} selects {chosen.release}, below the pinned {running}: there is no "
                      f"downgrade (going back is a restore, ADR 0098). Nothing was changed.")
    manifest_of = manifest_reader(host, source, False)
    target = upgrade_target(listed, current, chosen, source.describe(), lambda r: manifest_of(r).major_entry)
    drift = gitops_drift(g)
    if drift:
        raise Refused("the files no longer pin what " + GITOPS_FILE + " records: " + "; ".join(drift)
                      + ". Nothing was changed: put them back, or record the new ones in " + GITOPS_FILE + ".")
    top = repository_of(host, g.directory)
    paths = [str(g.application), str(g.values), str(g.directory / GITOPS_FILE)]
    if git(host, top, "diff", "--quiet", "HEAD", "--", *paths, check=False).returncode:
        raise Refused("the Application, the values or veyqctl.json have changes not committed: an upgrade commits "
                      "only its own. Nothing was changed.")
    differs = running_differs(host, source, listed, g)
    if differs:
        raise Refused(f"{GITOPS_FILE} says release {running} runs, and pins what it did not sign: " + "; ".join(differs)
                      + f". Nothing was changed: an upgrade starts from a running release that verifies "
                      f"({EXISTING_HALF_DOCS}).")
    manifest = manifest_of(target)
    pins = verify_gitops_release(host, manifest, g.doc["chart"])
    old = g.doc["pins"]
    app_text = rewrite_once(g.application.read_text(), old["chart"], pins["chart"], g.doc["application"])
    app_text = app_text.replace(f"version={running}", f"version={target.release}")  # a Renovate hint, if any
    values_text = g.values.read_text()
    for role in ("app", "admin", "tools"):
        values_text = rewrite_once(values_text, old[role], pins[role], g.doc["values"])
    values_text = rewrite_tags(values_text, running, target.release, g.doc["values"])
    with tempfile.TemporaryDirectory(prefix="veyqctl-values-") as tmp:
        staged = Path(tmp) / "values.yaml"
        staged.write_text(values_text)
        render(host, g.doc["chart"], pins["chart"], g.doc.get("namespace") or "veyq", staged)
    g.application.write_text(app_text)
    g.values.write_text(values_text)
    write_gitops(g._replace(doc={**g.doc, "running": target.release, "pins": pins}))
    git(host, top, "add", "--", *paths)
    git(host, top, "commit", "-m", f"veyq {target.release}: upgraded from {running} by veyqctl", "--", *paths)
    say(f"release {target.release} pinned and committed (from {running}); the chart's backup gate captures before it "
        f"starts")
    if target != chosen:
        say(entry_note(selector, chosen, target, "the next `upgrade`"))
    if args.push:
        git(host, top, "push")
        say("pushed: Argo CD applies it at its next sync")
    else:
        say(f"not pushed: `git -C {top} push` (or `upgrade --push` next time), and Argo CD applies it")
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

    def __exit__(self, *exc):
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
        say("the values name Let's Encrypt's production directory already: nothing to do")
        return 0
    if f"server: {staging}" not in text:
        raise Refused(f"{g.values} names no Let's Encrypt staging directory under gateway.tls.acme (TLS acme with "
                      f"`server: {staging}`): nothing to switch")
    top = repository_of(host, g.directory)
    if git(host, top, "diff", "--quiet", "HEAD", "--", str(g.values), check=False).returncode:
        raise Refused(f"{g.values} has changes not committed: `tls production` commits only its own. Nothing was "
                      f"changed.")
    switched = rewrite_once(text, f"server: {staging}", f"server: {production}", g.doc["values"])
    namespace = g.doc.get("namespace") or "veyq"
    with tempfile.TemporaryDirectory(prefix="veyqctl-values-") as tmp:
        staged = Path(tmp) / "values.yaml"
        staged.write_text(switched)
        render(host, g.doc["chart"], g.doc["pins"]["chart"], namespace, staged)
    branch = (git(host, top, "rev-parse", "--abbrev-ref", "HEAD", check=False).stdout or "").strip()
    if not args.yes and host.confirm(f"Switch to Let's Encrypt production: commit {g.doc['values']} and push to "
                                     f"origin {branch}?") is not True:
        raise Refused("not switched: nothing was changed (without a terminal, pass --yes)")
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
    say("committed and pushed: Argo CD applies the Issuer's new directory at its next sync")
    k = kubectl(g.doc.get("context") or None) + ["-n", namespace]
    with server_tunnel(host, top):
        deadline = time.monotonic() + args.timeout
        while (host.run(k + ["get", "issuer", "veyq-acme", "-o", "jsonpath={.spec.acme.server}"], check=False).stdout
               or "").strip() != production:
            if time.monotonic() > deadline:
                raise Refused(f"the Issuer veyq-acme does not name the production directory after {int(args.timeout)} s:"
                              f" has Argo CD synced? The same command goes on (it is pushed already).")
            host.sleep(10)
        listed = host.run(k + ["get", "certificates", "-o",
                               "jsonpath={range .items[*]}{.spec.secretName}{\"\\n\"}{end}"], check=False).stdout or ""
        secrets_ = sorted({line.strip() for line in listed.splitlines() if line.strip()})
        if secrets_:
            print(f"\n   The certificates staging issued: {', '.join(secrets_)} (namespace {namespace}).", flush=True)
            if not args.yes and host.confirm("Delete them, so cert-manager orders production ones? This cannot be "
                                             "undone; until the new ones are issued, those hosts serve none") is not True:
                raise Refused("the staging certificates stay until they renew; `veyqctl tls production` deletes them "
                              "when you run it again")
            host.run(k + ["delete", "secret", *secrets_])
        while True:
            states = host.run(k + ["get", "certificates", "-o", "jsonpath={range .items[*]}{.metadata.name} "
                                   "{.status.conditions[?(@.type==\"Ready\")].status}{\"\\n\"}{end}"],
                              check=False).stdout or ""
            pending = [line.split()[0] for line in states.splitlines() if line.split() and line.split()[-1] != "True"]
            if states.strip() and not pending:
                say("every certificate is issued by Let's Encrypt production")
                return 0
            if time.monotonic() > deadline:
                raise Refused(f"not issued within {int(args.timeout)} s: {', '.join(pending) or 'no certificate'} — "
                              f"`kubectl describe certificate` tells why")
            host.sleep(10)


def compose_tls(args: argparse.Namespace, host: Host) -> int:
    raise Refused("`tls` is a GitOps instance's: under Compose, Traefik's ACME resolver takes its directory from .env")


GITOPS_VERBS = {"verify": gitops_verify, "check": gitops_check, "upgrade": gitops_upgrade, "tls": gitops_tls}


# ---- main ------------------------------------------------------------------------------------------------------

def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="veyqctl", description="Install, run, upgrade and back up a veyq instance on "
                                "Docker Compose from its releases (ADR 0096).")
    p.add_argument("--home", default=None, help=f"the instance's directory, which wins over --instance (default: the "
                                                 f"current instance, else {DEFAULT_HOME})")
    p.add_argument("--instance", default=None, metavar="NAME", help="act on this listed instance once, without `use`")
    p.add_argument("--source", default=None,
                   help=f"where releases come from: a directory or an http(s) URL in the mirror layout "
                        f"(default: the GitHub Releases of {UPSTREAM}, or the source the instance was installed from)")
    sub = p.add_subparsers(dest="command", required=True)

    sub.add_parser("instances", help="the instances listed in ~/.config/veyq, their target and path; * is current"
                   ).set_defaults(func=cmd_instances)
    use = sub.add_parser("use", help="make a listed instance the current one")
    use.add_argument("name")
    use.set_defaults(func=cmd_use)

    init = sub.add_parser("init", help="create an instance, list it and make it current: Compose (as `install`) or "
                                       "GitOps (veyq's half of an Argo CD repository, ADR 0126)")
    which = init.add_mutually_exclusive_group()
    which.add_argument("--compose", action="store_true", help="Docker Compose on this host, in --home")
    which.add_argument("--gitops", metavar="DIR", help="veyq's directory in a checkout of the repository Argo CD syncs")
    init.add_argument("--name", help="the instance's name in the list (default: veyq; GitOps: the repository's)")
    init.add_argument("--set", action="append", metavar="KEY=VALUE", help="Compose: a .env value (repeatable)")
    init.add_argument("--unsigned-local", action="store_true", help="Compose: a release from a local docker build")
    init.add_argument("--release", help="GitOps: the selector veyqctl.json keeps (asked; offered: the newest major)")
    init.add_argument("--secrets", choices=PROVIDERS, help="GitOps: where the Secrets come from")
    init.add_argument("--domain", help="GitOps: veyq's domain")
    init.add_argument("--store-domain", help="GitOps: the bundled store's own host (default store.<domain>)")
    init.add_argument("--no-bundled-store", action="store_true", help="GitOps: no Garage; set a store of your own in "
                                                                     "the admin console after the install")
    init.add_argument("--gateway-class", help="GitOps: the GatewayClass the chart's Gateway uses")
    init.add_argument("--tls", choices=("acme", "shared", "none"), help="GitOps: the chart's TLS (acme: its "
                                                                         "cert-manager issuer)")
    init.add_argument("--acme-directory", choices=tuple(ACME_DIRECTORIES),
                      help="GitOps: Let's Encrypt's staging or production directory (server path: staging first)")
    init.add_argument("--acme-email", help="GitOps: the ACME account contact (implies --tls acme)")
    init.add_argument("--tls-secret", help="GitOps --tls shared: the TLS Secret every host uses")
    init.add_argument("--gateway-namespace", help="GitOps: the namespace of the Gateway's data-plane pods")
    init.add_argument("--gateway-pod-label", help="GitOps: a KEY=VALUE label those pods carry")
    init.add_argument("--namespace", help="GitOps: veyq's namespace (default veyq)")
    init.add_argument("--context", help="GitOps: the kubeconfig context of the cluster (asked; offered: kubectl's "
                                        "current)")
    init.add_argument("--no-cluster-check", action="store_true", help="GitOps: write for a cluster not reachable yet")
    init.add_argument("--storage-class", help="GitOps: the backup claim's StorageClass (default: the cluster's)")
    init.add_argument("--backup-size", default="20Gi", help="GitOps: the backup claim's size (default 20Gi)")
    init.add_argument("--secret-store", help="GitOps --secrets external-secrets: the store's name")
    init.add_argument("--secret-store-kind", default="ClusterSecretStore", choices=("SecretStore", "ClusterSecretStore"))
    init.add_argument("--remote-prefix", default="veyq-", help="GitOps --secrets external-secrets: the remote keys' "
                                                              "prefix (default veyq-)")
    init.add_argument("--repo-url", help="GitOps: the URL Argo CD reads this repository from (default: origin)")
    init.add_argument("--revision", help="GitOps: the branch Argo CD follows (default: the checkout's)")
    init.add_argument("--admin-host-access", choices=("yes", "no"), help="GitOps: veyq's administration on a host-only "
                                                                       "NodePort through the tunnel (asked)")
    init.add_argument("--admin-host-source", help="GitOps: the node's pod-network gateway the admin policy admits (a /32; "
                                                  "offered from the node's podCIDR)")
    init.add_argument("--project", help="GitOps: the Argo CD project (asked; offered: the cluster's one project "
                                        "besides `default`, else `default`)")
    init.add_argument("--application-name", default="veyq", help="GitOps: the Argo CD Application's name (default "
                                                                 "veyq) — not --name, which only names it here")
    init.add_argument("--argocd-namespace", default="argocd", help="GitOps: Argo CD's namespace (default argocd)")
    init.add_argument("--server", choices=("yes", "no"),
                      help="GitOps: set up the server and its cluster too (veyq-server; ADR 0126 decision 6)")
    init.add_argument("--server-address", help="server path: the server's DNS name or IPv4 (a fresh Debian 13)")
    init.add_argument("--node-name", help="server path: the cluster node's name")
    init.add_argument("--admin-user", help="server path: the account the bootstrap creates")
    init.add_argument("--admin-ssh-key", dest="admin_ssh_public_key", help="server path: its SSH public key")
    init.add_argument("--age-key", help="server path: the age key file the repository's secrets are encrypted to")
    init.add_argument("--host-access", choices=("yes", "no"),
                      help="server path: Argo CD and veyq's administration as host-only NodePorts (the tunnel)")
    init.add_argument("--connect-name", help="server path: this machine's SSH host and kubeconfig context name")
    init.add_argument("--make-current", choices=("yes", "no"), help="server path: make that context kubectl's current")
    init.add_argument("--sync-timeout", type=float, default=1800.0,
                      help="server path: seconds to wait for Argo CD to report Synced and Healthy (default 1800)")
    init.set_defaults(func=cmd_init)

    tls = sub.add_parser("tls", help="GitOps: from Let's Encrypt staging to production (commit, push, new certificates)")
    tls.add_argument("directory", choices=("production",), help="the directory to switch to")
    tls.add_argument("--yes", action="store_true", help="confirm the commit and push and the certificates' deletion")
    tls.add_argument("--timeout", type=float, default=900.0, help="seconds to wait for Argo CD and cert-manager")
    tls.set_defaults(func=compose_tls)

    connected = sub.add_parser("connect", help="this machine's SSH host and kubeconfig context for the server a "
                               "checkout's veyq-setup.json describes — again after a rebuild")
    connected.add_argument("--connect-name", help="the name (default: the one veyq-setup.json holds)")
    connected.add_argument("--make-current", choices=("yes", "no"), default="no",
                           help="make the context kubectl's current one (default no)")
    connected.set_defaults(func=cmd_connect)

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
    check.add_argument("--unsigned-local", action="store_true",
                       help="read a local build's manifests unverified (a major's entry is read from one)")
    check.set_defaults(func=cmd_check)

    upgrade = sub.add_parser("upgrade", help="move toward the release .env's VEYQ_RELEASE selects, entering a major "
                                             "only from the release it names as its entry (ADR 0096 decision 5)")
    upgrade.add_argument("--key-into", metavar="FILE", help="where the backup's leg (c) goes — required, its own medium")
    upgrade.add_argument("--unsigned-local", action="store_true",
                         help="a release produced from a local docker build: no signature is checked")
    upgrade.add_argument("--objects-to", metavar="POOL", help=f"the object pool for this run's backup, instead of "
                                                               f".env's {POOL_TARGET_KEY}")
    upgrade.add_argument("--push", action="store_true", help="GitOps: push the upgrade's commit")
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
    try:
        if args.command in ("instances", "use", "init", "connect"):
            return args.func(args, host)
        home = resolve_home(args)
        args.home = str(home)
        host.workdir = home
        if target_of(home) == "gitops":
            if args.command in COMPOSE_ONLY:
                raise Refused(f"`{args.command}` is Compose's, and {home} is a GitOps instance: {COMPOSE_ONLY[args.command]}")
            if getattr(args, "unsigned_local", False):
                raise Refused("--unsigned-local is Compose's: a GitOps instance pins only what verifies")
            # Resolved: git names the checkout by its real path (macOS' /var is /private/var), and every path veyqctl
            # compares with it or hands to `git add` must be the same one.
            require_programs(host, GITOPS_PROGRAMS[args.command], f"`{args.command}` of a GitOps instance")
            return GITOPS_VERBS[args.command](args, host, read_gitops(home.resolve()))
        if getattr(args, "push", False):
            raise Refused(f"--push is a GitOps instance's, and {home} is a Compose home")
        if args.command not in ("self-update", "tls"):
            require_programs(host, compose_programs(args.command, bool(getattr(args, "unsigned_local", False))),
                             f"`{args.command}`")
        return args.func(args, host)
    except Refused as e:
        say(f"REFUSED: {e}")
        return 1
    finally:
        set_unverified(False)


if __name__ == "__main__":
    sys.exit(main())
