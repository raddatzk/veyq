#!/usr/bin/env python3
"""The backup archive of ADR 0091 decision 6 (amended 2026-11-26): for one release, a backup of an instance that
release's own seeder filled, published once as one OCI artifact `<registry>/veyq-backups:<major>.<minor>` beside the
release's images. `.github/workflows/archive.yml` runs it after a promotion (`release.yml`'s `archive` job) and on a
dispatch for a release already out; lane L3 restores what it wrote (cr-stack-lane-l3-backup-archive phase 3).

**What `archive` does**, stage by stage. Stages 1 to 6 are lane L2's building block (`scripts/run_stack_upgrade.py`,
by import — one install and one seed, never a second copy):

  0. **unwritten** — the artifact's tag read in the registry first: a tag that exists is a notice, and the run writes
     nothing and builds nothing (written once, as Publish treats a commit with a `snapshot-` tag). A read the registry
     answers `not found` is "not archived yet"; one it answers `denied` is not known yet — GHCR answers that to a
     repository that does not exist — and the run goes on, the tag read again before it is set (`publish`). Any
     other failure is red: a read that failed is never taken for "nothing there".
  1. **preflight** — ports 80, 443 and the admin port free, the host's containers and volumes recorded; **browser
     image** — the overlay's Playwright image, which runs the seeder (no browser test runs here).
  2. **start release** — the release's published files as a mirror (L2's `start_release`), and the overlay without
     its release-check test double (L2's `override`).
  3. **verify** — the release's OWN `veyqctl verify --release <release>` against that mirror: its manifest and both
     images under the release identity, before anything is pulled. Then the app image its verified manifest names is
     pulled by digest: its `keytool` writes the app's truststore at the Pebble stage (L2 uses the candidate's image;
     here there is no candidate).
  4. **pebble**, **install**, **certificate** — as L2's: the release installed by its own `veyqctl`, verified.
  5. **start tree** — a worktree of the release's tag, installed by its own lockfile (L2's).
  6. **seed** — the release's own `upgrade.a-seed.e2e.test.ts` against its own instance (L2's), its record checked.
  7. **backup** — the release's own `veyqctl backup --key-into <key> --objects-to <pool>`: the cold capture, the
     objects into a directory pool, the config-secret key apart. Red unless exactly one capture is left in
     `home/backups/`, the key and the pool exist, the capture names exactly one tenant domain, and its Flyway schema
     version is the release's highest migration (its own pin file, `run_web_e2e.highest_migration`).
  8. **assemble** — `content/` = `capture/`, `pool/`, `config-secret.key`, `upgrade-record.json`, packed as ONE
     deterministic tar (sorted, mtime 0, owner 0, each file's own mode) and described by `archive.json` ([ARCHIVE]):
     the release, its highest migration, the domain the capture's tenant lives on (read from the capture, never
     assumed), the seeder's record version, the images the release's manifest named, and the SHA-256 of every file
     in the tar. Then the manifest ([manifest]), written here, so the same files are the same digest.
  9. **push** — the tag read again (another run may have written it meanwhile: a notice, nothing pushed), then the
     two layers and the empty config as blobs and the manifest BY DIGEST, untagged (`oras`): the tag is the last
     write, set by `publish` after the workflow signed the digest. A failure before the tag leaves an untagged
     manifest no reader names, and a re-run writes again.
 10. **evidence**, **teardown**, **host** — as L1's and L2's.

**The other commands.**
  - `check` — stage 0 alone (`needed=false` when the tag exists), so a workflow skips its installs for a release
    already archived.
  - `publish` — the tag read once more, then set on the digest (`oras tag`) and read back: it must name exactly that
    digest. A tag that exists by then (a concurrent run's) is a notice and this digest stays untagged. Here the
    registry must answer definitively: by now the repository exists.
  - `pull` — an artifact (by tag or digest) fetched into a directory and checked: its artifact type and layers, every
    blob against its descriptor's digest, the tar unpacked (no entry outside the directory, no link), and every file
    against `archive.json`'s SHA-256 — nothing more, nothing less. What lane L3 reads, and the local round trip.
  - `identity` — the issuer and the identity `archive.yml` signs under ([OIDC_ISSUER], [IDENTITY_REGEXP]), as
    `issuer=` / `identity=` lines: what the workflow verifies its signature against and lane L3 will pin.
    `scripts/validate_release_identity.py` holds the identity to `archive.yml` on the publishing branches and to
    nothing else — never `release.yml`, so an archive signature never verifies as a release.

**What it is not.** Not a `validate_*` guard: it needs Docker, cosign (`veyqctl`'s floor), `oras`, `gh`, the network
to GitHub and GHCR, host ports 80 and 443. It signs nothing: keyless signing needs the workflow's OIDC token, so
`archive.yml` signs the digest `archive` prints. Its self-test (`scripts/archive_backup_selftest.py`) holds the pure
parts — the reference, the tar, `archive.json`, the manifest, the registry's answers, the pull's refusals.

**Its limits.** It archives what the release's seeder writes through the API, nothing else (the seeder's header says
what). The instance runs the production recipe with lane L1's overlay (Pebble, Mailpit), not a Server Owner's host.
That the artifact holds test data only rests on the seeder: every secret in it — the throwaway owner's credentials,
the config-secret key — belongs to an instance this run creates and tears down. A registry that words "not found"
otherwise than `snapshot.NOT_FOUND` matches is refused, never overwritten.

Usage:
    python3 scripts/archive_backup.py check --tag v1.318 --registry ghcr.io/raddatzk [--ref …] [--outputs FILE]
    python3 scripts/archive_backup.py archive --tag v1.318 --registry ghcr.io/raddatzk [--plain-http] [--keep]
        [--ref refs/heads/main] [--outputs FILE]
    python3 scripts/archive_backup.py publish --tag v1.318 --registry … --digest sha256:… [--plain-http]
    python3 scripts/archive_backup.py pull --ref ghcr.io/raddatzk/veyq-backups:1.318 --into DIR [--plain-http]
    python3 scripts/archive_backup.py identity

`--outputs` (a CI job's `$GITHUB_OUTPUT`) receives `written=true|false`, `ref=` and `digest=`. Environment: lane L1's
(`VEYQ_STACK_WAIT_SECS`, `VEYQ_STACK_CERT_SECS`, `VEYQ_STACK_ADMIN_PORT`), `GH_TOKEN` for `gh`, `VEYQ_ORAS` for an
`oras` not on the PATH.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
from pathlib import Path
from typing import Iterable, NamedTuple

sys.path.insert(0, str(Path(__file__).resolve().parent))
from stack_lane import Red  # noqa: E402 - one verdict type with the stack lanes, so a stage's red is theirs too

ROOT = Path(__file__).resolve().parent.parent

# The workflow that signs an archive, and the identity its keyless signature carries: `job_workflow_ref`, the called
# workflow's own file whether `release.yml` calls it or it is dispatched, on the branches a release is promoted from.
ARCHIVE_WORKFLOW = ".github/workflows/archive.yml"
OIDC_ISSUER = "https://token.actions.githubusercontent.com"
IDENTITY_REGEXP = r"^https://github\.com/raddatzk/veyq/\.github/workflows/archive\.yml@refs/heads/(main|release/[0-9]+\.x)$"
# The refs `archive.yml` runs from: the ones its identity accepts, so a dispatch from another branch writes nothing
# that would never verify (the tag is written once).
PUBLISHING_REF = re.compile(r"^refs/heads/(main|release/[0-9]+\.x)$")

REPOSITORY = "veyq-backups"
TAG = re.compile(r"^v(\d+)\.(\d+)$")
REGISTRY = re.compile(r"^[a-z0-9][a-z0-9.-]*(?::[0-9]+)?(?:/[a-z0-9][a-z0-9._-]*)*$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
SOURCE = "https://github.com/raddatzk/veyq"

# The artifact: an OCI image manifest with an artifact type, the empty config, and two layers.
MANIFEST_MEDIA_TYPE = "application/vnd.oci.image.manifest.v1+json"
ARTIFACT_TYPE = "application/vnd.veyq.backup.v1"
EMPTY_MEDIA_TYPE = "application/vnd.oci.empty.v1+json"
EMPTY_CONFIG = b"{}"
ARCHIVE = "archive.json"
ARCHIVE_MEDIA_TYPE = "application/vnd.veyq.backup.archive.v1+json"
CONTENT = "backup.tar"
CONTENT_MEDIA_TYPE = "application/vnd.veyq.backup.content.v1.tar"
TITLE = "org.opencontainers.image.title"
# `archive.json`'s shape; a reader refuses another.
ARCHIVE_SCHEMA = 1
# The tar's four members, each a top-level name (`archive.json` names them again, for a reader).
CAPTURE_DIR = "capture"
POOL_DIR = "pool"
KEY_FILE = "config-secret.key"
RECORD_FILE = "upgrade-record.json"
MEMBERS = (CAPTURE_DIR, POOL_DIR, KEY_FILE, RECORD_FILE)


class Ref(NamedTuple):
    """Where one release's archive lives: `<registry>/veyq-backups:<major>.<minor>`."""
    repository: str
    tag: str

    def __str__(self) -> str:
        return f"{self.repository}:{self.tag}"

    def at(self, digest: str) -> str:
        return f"{self.repository}@{digest}"


def artifact_ref(release_tag: str, registry: str) -> Ref:
    """The archive's reference for `v<major>.<minor>` under `registry` (`ghcr.io/<owner>`, or a local one)."""
    match = TAG.match(release_tag or "")
    if not match:
        raise Red(f"{release_tag!r} is no release tag v<major>.<minor>")
    if not REGISTRY.match(registry or ""):
        raise Red(f"{registry!r} is no registry namespace (host[:port][/path], lower case)")
    return Ref(f"{registry}/{REPOSITORY}", f"{int(match.group(1))}.{int(match.group(2))}")


def check_ref(ref: str | None) -> None:
    """The run's git ref (`$GITHUB_REF`) must be one the archive identity accepts; a local run names none."""
    if ref is not None and not PUBLISHING_REF.match(ref):
        raise Red(f"the run's ref is {ref}: an archive is signed on `main` or `release/<major>.x` only "
                  f"({ARCHIVE_WORKFLOW}'s identity), and the tag is written once — nothing was written")


# ---------------------------------------------------------------- the registry, through oras


def oras_binary() -> str:
    found = os.environ.get("VEYQ_ORAS") or shutil.which("oras")
    if not found:
        raise Red("no `oras` (the OCI client the archive is pushed and pulled with) on the PATH, nor VEYQ_ORAS")
    return found


def oras(*args: str, plain_http: bool = False, check: bool = True) -> subprocess.CompletedProcess:
    """One `oras` command; `--plain-http` for a local registry. Its output is captured, never streamed."""
    cmd = [oras_binary(), *args, *(["--plain-http"] if plain_http else [])]
    print("+", " ".join(cmd), flush=True)
    result = subprocess.run(cmd, text=True, capture_output=True)
    if check and result.returncode != 0:
        raise Red(f"`oras {' '.join(args[:2])}` exited {result.returncode}: {(result.stderr or '').strip()[-600:]}")
    return result


# What a registry answers for a reference that names nothing (scripts/snapshot.py's words for Docker Buildx's, which
# oras words the same way: `…: not found`), and for one it will not say anything about.
NOT_FOUND = re.compile(r"not.found|MANIFEST_UNKNOWN|NAME_UNKNOWN|\b404\b", re.IGNORECASE)
DENIED = re.compile(r"\bdenied\b|\bDENIED\b|\b401\b|\b403\b|unauthorized", re.IGNORECASE)


class Lookup(NamedTuple):
    state: str          # "present" | "absent" | "unknown"
    digest: str | None  # the manifest the tag names, when present
    said: str           # what the registry answered, for a message


def classify(returncode: int, stdout: str, stderr: str) -> Lookup:
    """`oras manifest fetch --descriptor` read as present (with its digest), absent, or unknown (denied). Anything else
    is red: a read that failed is never taken for "nothing there"."""
    if returncode == 0:
        try:
            digest = json.loads(stdout).get("digest")
        except (ValueError, AttributeError):
            digest = None
        if not isinstance(digest, str) or not DIGEST.match(digest):
            raise Red(f"the registry named the tag with no sha256 digest: {stdout.strip()[:300]}")
        return Lookup("present", digest, stdout.strip())
    said = (stderr or "").strip()[-400:]
    if NOT_FOUND.search(said):
        return Lookup("absent", None, said)
    if DENIED.search(said):
        return Lookup("unknown", None, said)
    raise Red(f"the registry read failed with something other than `not found` or `denied` ({said}) — refusing "
              f"to take it for an archive that is not there")


def lookup(ref: Ref, plain_http: bool) -> Lookup:
    result = oras("manifest", "fetch", "--descriptor", str(ref), plain_http=plain_http, check=False)
    return classify(result.returncode, result.stdout, result.stderr)


# ---------------------------------------------------------------- the artifact's bytes


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def content_files(content: Path) -> list[Path]:
    """Every regular file below `content`, sorted by its POSIX path; red on anything that is neither file nor dir."""
    found = []
    for path in sorted(content.rglob("*"), key=lambda p: p.relative_to(content).as_posix()):
        if path.is_symlink() or not (path.is_file() or path.is_dir()):
            raise Red(f"{path.relative_to(content)} is neither a file nor a directory — the archive carries only those")
        if path.is_file():
            found.append(path)
    return found


def pack(content: Path, out: Path) -> dict[str, str]:
    """`content` as one deterministic tar at `out` — entries sorted, mtime 0, owner 0 and no names, each entry's own
    permission bits — and every file's SHA-256 by its path in the tar. The same files give the same bytes."""
    tops = sorted(p.name for p in content.iterdir())
    if tops != sorted(MEMBERS):
        raise Red(f"the archive's content holds {tops}, not exactly {sorted(MEMBERS)}")
    entries = sorted(content.rglob("*"), key=lambda p: p.relative_to(content).as_posix())
    files = {}
    with tarfile.open(out, "w", format=tarfile.PAX_FORMAT) as tar:
        for path in entries:
            name = path.relative_to(content).as_posix()
            if path.is_symlink() or not (path.is_file() or path.is_dir()):
                raise Red(f"{name} is neither a file nor a directory — the archive carries only those")
            info = tarfile.TarInfo(name)
            info.mtime = 0
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            info.mode = path.stat().st_mode & 0o777
            if path.is_dir():
                info.type = tarfile.DIRTYPE
                tar.addfile(info)
            else:
                info.size = path.stat().st_size
                with path.open("rb") as handle:
                    tar.addfile(info, handle)
                files[name] = sha256_file(path)
    return files


def archive_document(release_tag: str, highest: str, domain: str, record_version: int, images: dict[str, str],
                     files: dict[str, str]) -> dict:
    """`archive.json`: what a reader needs before it unpacks anything, and what it checks after."""
    return {
        "schema": ARCHIVE_SCHEMA,
        "release": release_tag[1:],
        "tag": release_tag,
        "highestMigration": highest,
        "domain": domain,
        "recordVersion": record_version,
        "images": dict(sorted(images.items())),
        "capture": CAPTURE_DIR,
        "pool": POOL_DIR,
        "key": KEY_FILE,
        "record": RECORD_FILE,
        "files": dict(sorted(files.items())),
    }


def canonical(doc: dict) -> bytes:
    """One JSON spelling of a document: sorted keys, no spaces — so the same content is the same digest."""
    return json.dumps(doc, sort_keys=True, separators=(",", ":")).encode()


def descriptor(media_type: str, data: bytes | None = None, path: Path | None = None, title: str | None = None) -> dict:
    if path is not None:
        size, digest = path.stat().st_size, sha256_file(path)
    else:
        assert data is not None
        size, digest = len(data), hashlib.sha256(data).hexdigest()
    out = {"mediaType": media_type, "digest": f"sha256:{digest}", "size": size}
    if title is not None:
        out["annotations"] = {TITLE: title}
    return out


def manifest(archive_bytes: bytes, content_tar: Path, release: str) -> bytes:
    """The artifact's OCI image manifest (image-spec 1.1's artifact form): the empty config, `archive.json` and the tar
    as layers, the release and the source as annotations. No timestamp, so it is a function of its files."""
    doc = {
        "schemaVersion": 2,
        "mediaType": MANIFEST_MEDIA_TYPE,
        "artifactType": ARTIFACT_TYPE,
        "config": descriptor(EMPTY_MEDIA_TYPE, EMPTY_CONFIG),
        "layers": [descriptor(ARCHIVE_MEDIA_TYPE, archive_bytes, title=ARCHIVE),
                   descriptor(CONTENT_MEDIA_TYPE, path=content_tar, title=CONTENT)],
        "annotations": {
            "org.opencontainers.image.source": SOURCE,
            "org.opencontainers.image.version": release,
            "org.opencontainers.image.description": f"veyq {release}: a backup of an instance its own seeder filled "
                                                    f"(test data only)",
        },
    }
    return canonical(doc)


# ---------------------------------------------------------------- reading an artifact back (`pull`, and lane L3)


def check_manifest(raw: bytes) -> tuple[dict, dict]:
    """The two layer descriptors of an archive's manifest, by title; red on any other shape."""
    try:
        doc = json.loads(raw)
    except ValueError:
        raise Red("the artifact's manifest is not JSON") from None
    if not isinstance(doc, dict) or doc.get("mediaType") != MANIFEST_MEDIA_TYPE or doc.get("artifactType") != ARTIFACT_TYPE:
        raise Red(f"the artifact is no veyq backup (mediaType {doc.get('mediaType') if isinstance(doc, dict) else '?'}, "
                  f"artifactType {doc.get('artifactType') if isinstance(doc, dict) else '?'}; want {ARTIFACT_TYPE})")
    layers = doc.get("layers")
    if not isinstance(layers, list) or len(layers) != 2:
        raise Red(f"the artifact has {len(layers) if isinstance(layers, list) else 'no'} layers, not two")
    by_title = {}
    for layer in layers:
        title = ((layer or {}).get("annotations") or {}).get(TITLE) if isinstance(layer, dict) else None
        by_title[title] = layer
    want = {ARCHIVE: ARCHIVE_MEDIA_TYPE, CONTENT: CONTENT_MEDIA_TYPE}
    for title, media_type in want.items():
        layer = by_title.get(title)
        if layer is None or layer.get("mediaType") != media_type or not DIGEST.match(str(layer.get("digest"))):
            raise Red(f"the artifact carries no {title} layer of type {media_type} with a sha256 digest")
    return by_title[ARCHIVE], by_title[CONTENT]


def check_archive(doc: object) -> dict:
    if not isinstance(doc, dict) or doc.get("schema") != ARCHIVE_SCHEMA:
        raise Red(f"archive.json is not of schema {ARCHIVE_SCHEMA}")
    for key in ("release", "tag", "highestMigration", "domain"):
        if not isinstance(doc.get(key), str) or not doc[key]:
            raise Red(f"archive.json's `{key}` is not a non-empty string")
    files = doc.get("files")
    if not isinstance(files, dict) or not files or not all(
            isinstance(k, str) and isinstance(v, str) and re.fullmatch(r"[0-9a-f]{64}", v) for k, v in files.items()):
        raise Red("archive.json's `files` is not a non-empty map of path to SHA-256")
    return doc


def unpack(tar_path: Path, into: Path) -> None:
    """The tar into `into`: regular files and directories only, every name inside it."""
    into.mkdir(parents=True, exist_ok=True)
    base = into.resolve()
    with tarfile.open(tar_path, "r") as tar:
        for member in tar.getmembers():
            target = (base / member.name).resolve()
            if member.name.startswith("/") or (target != base and base not in target.parents):
                raise Red(f"the archive's tar names {member.name!r}, outside the directory it is unpacked into")
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
                os.chmod(target, (member.mode & 0o777) | 0o700)
            elif member.isfile():
                target.parent.mkdir(parents=True, exist_ok=True)
                source = tar.extractfile(member)
                assert source is not None
                with target.open("wb") as out:
                    shutil.copyfileobj(source, out)
                os.chmod(target, member.mode & 0o777)
            else:
                raise Red(f"the archive's tar holds {member.name!r}, which is neither a file nor a directory")


def check_files(content: Path, files: dict[str, str]) -> None:
    """Every file below `content` is one `archive.json` names, with its SHA-256, and every one it names is there."""
    found = {p.relative_to(content).as_posix(): sha256_file(p) for p in content_files(content)}
    missing = sorted(set(files) - set(found))
    extra = sorted(set(found) - set(files))
    changed = sorted(name for name in set(files) & set(found) if files[name] != found[name])
    if missing or extra or changed:
        raise Red("the unpacked archive is not the one archive.json describes: "
                  + "; ".join(f"{what} {', '.join(names[:5])}" for what, names in
                              (("missing", missing), ("not described", extra), ("changed", changed)) if names))


def pull(ref: str, into: Path, plain_http: bool) -> dict:
    """Fetch an archive into `into` (`archive.json`, `backup.tar` and the unpacked `content/`), checked throughout."""
    repository = ref.split("@", 1)[0] if "@" in ref else ref.rsplit(":", 1)[0]
    into.mkdir(parents=True, exist_ok=False)
    oras("manifest", "fetch", ref, "--output", str(into / "manifest.json"), plain_http=plain_http)
    raw = (into / "manifest.json").read_bytes()
    if "@" in ref and f"sha256:{hashlib.sha256(raw).hexdigest()}" != ref.split("@", 1)[1]:
        raise Red(f"the manifest fetched for {ref} does not hash to its digest")
    archive_layer, content_layer = check_manifest(raw)
    for layer, name in ((archive_layer, ARCHIVE), (content_layer, CONTENT)):
        oras("blob", "fetch", f"{repository}@{layer['digest']}", "--output", str(into / name), plain_http=plain_http)
        got = f"sha256:{sha256_file(into / name)}"
        if got != layer["digest"] or (into / name).stat().st_size != layer.get("size"):
            raise Red(f"the {name} layer fetched is {got}, {(into / name).stat().st_size} B — its descriptor says "
                      f"{layer['digest']}, {layer.get('size')} B")
    doc = check_archive(json.loads((into / ARCHIVE).read_text(encoding="utf-8")))
    unpack(into / CONTENT, into / "content")
    check_files(into / "content", doc["files"])
    print(f"pull: {ref} — veyq {doc['release']}, highest migration {doc['highestMigration']}, tenant domain "
          f"{doc['domain']}, {len(doc['files'])} file(s), every one as archive.json describes it", flush=True)
    return doc


# ---------------------------------------------------------------- the run (needs the stack: imported only here)


def outputs(path: Path | None, **values: str) -> None:
    for key, value in values.items():
        print(f"{key}={value}", flush=True)
    if path is not None:
        with path.open("a", encoding="utf-8") as handle:
            handle.writelines(f"{key}={value}\n" for key, value in values.items())


def unwritten(args: argparse.Namespace) -> tuple[Ref, Lookup]:
    """Stage 0: the tag and the run's ref checked, the archive's tag read — present is a notice, nothing written."""
    ref = artifact_ref(args.tag, args.registry)
    check_ref(args.ref)
    first = lookup(ref, args.plain_http)
    if first.state == "present":
        print(f"::notice::{ref} is archived already ({first.digest}): an archive is written once — nothing was "
              f"written", flush=True)
    elif first.state == "unknown":
        print(f"[archive] the registry will not say whether {ref} exists ({first.said}) — most likely the repository "
              f"does not exist yet; the tag is read again before anything is pushed", flush=True)
    else:
        print(f"[archive] {ref} is not archived yet", flush=True)
    return ref, first


def run_check(args: argparse.Namespace) -> int:
    ref, first = unwritten(args)
    outputs(args.outputs, needed="false" if first.state == "present" else "true", ref=str(ref),
            repository=ref.repository)
    return 0


def run_archive(args: argparse.Namespace) -> int:
    ref, first = unwritten(args)
    if first.state == "present":
        outputs(args.outputs, written="false", ref=str(ref), digest=first.digest or "")
        return 0

    import run_stack_upgrade as l2  # lane L2's building block: the release installed and seeded
    import stack_lane as lane

    stamp = time.strftime("%Y%m%d-%H%M%S")
    workdir = lane.RUN_ROOT / f"archive-{args.tag}-{stamp}"
    workdir.mkdir(parents=True)
    project = f"veyq-archive-{stamp}"
    stage = lane.Stages()
    state: dict = {}

    def verify(start) -> str:
        """The release's own `veyqctl verify`, then its app image pulled by the digest the verified manifest names."""
        home = workdir / "verify-home"
        home.mkdir()
        status = l2.step("verify", [sys.executable, start.release.tag / "veyqctl", "--home", home, "--source",
                                    start.release.source, "verify", "--release", start.tag[1:]], "verify.log", workdir)
        if status != 0:
            raise Red(f"{start.tag}'s `veyqctl verify` exited {status} — verify.log in the run directory says why")
        app = str(start.manifest.get("images", {}).get("app") or "")
        if "@sha256:" not in app:
            raise Red(f"{start.tag}'s manifest names its app image {app!r}, with no digest")
        lane.run("docker", "pull", app, capture=False)
        return app

    def backup(installed) -> None:
        """The release's own `veyqctl backup`, the pool and the key apart; its capture's facts checked."""
        veyqctl = installed.home / "releases" / installed.release / "veyqctl"
        key, pool = workdir / "key" / KEY_FILE, workdir / POOL_DIR
        key.parent.mkdir(mode=0o700)
        status = l2.step("backup", [sys.executable, veyqctl, "--home", installed.home, "backup", "--key-into", key,
                                    "--objects-to", pool], "backup.log", workdir,
                         {"VEYQ_WAIT_SECS": str(lane.WAIT_SECS)})
        if status != 0:
            raise Red(f"{args.tag}'s `veyqctl backup` exited {status} — backup.log in the run directory says why")
        captures = [p for p in (installed.home / "backups").iterdir() if p.is_dir()]
        if len(captures) != 1 or not key.is_file() or not pool.is_dir():
            raise Red(f"`veyqctl backup` left {len(captures)} capture(s), {'a' if key.is_file() else 'no'} key "
                           f"and {'a' if pool.is_dir() else 'no'} pool — not one of each")
        facts = json.loads((captures[0] / "manifest.json").read_text(encoding="utf-8"))
        domains = facts.get("domains") or []
        if len(domains) != 1 or not isinstance(domains[0], str):
            raise Red(f"the capture names the tenant domain(s) {domains}, not exactly one")
        schema = str((facts.get("database") or {}).get("schemaVersion") or "")
        highest = l2.proof.highest_migration(l2.proof.pinned_migrations(state["tree"].pins))
        if schema != highest:
            raise Red(f"the capture's Flyway schema is at {schema or 'nothing'}, and {args.tag}'s pin file's "
                           f"highest migration is V{highest} — the release's schema state is not the one it names")
        state.update(capture=captures[0], key=key, pool=pool, domain=domains[0], highest=f"V{highest}")
        print(f"backup: {captures[0].name}, tenant domain {domains[0]}, schema V{schema}, pool {pool.name}", flush=True)

    def assemble() -> Path:
        content = workdir / "artifact" / "content"
        content.mkdir(parents=True)
        shutil.copytree(state["capture"], content / CAPTURE_DIR)
        shutil.copytree(state["pool"], content / POOL_DIR)
        shutil.copy2(state["key"], content / KEY_FILE)
        shutil.copy2(workdir / l2.GROUP_DIR / l2.proof.UPGRADE_RECORD_FILE, content / RECORD_FILE)
        tar = workdir / "artifact" / CONTENT
        files = pack(content, tar)
        images = l2.predecessor_of(state["installed"].home, state["start"])["images"]
        doc = archive_document(args.tag, state["highest"], state["domain"], int(state["record"]["version"]), images,
                               files)
        archive_bytes = canonical(doc)
        (workdir / "artifact" / ARCHIVE).write_bytes(archive_bytes)
        (workdir / "artifact" / "manifest.json").write_bytes(manifest(archive_bytes, tar, args.tag[1:]))
        print(f"assemble: {len(files)} file(s), {tar.stat().st_size} B of tar; archive.json "
              f"{json.dumps({k: v for k, v in doc.items() if k != 'files'})}", flush=True)
        return workdir / "artifact"

    def push(artifact: Path) -> str | None:
        again = lookup(ref, args.plain_http)
        if again.state == "present":
            print(f"::notice::{ref} was archived meanwhile ({again.digest}) — nothing was pushed", flush=True)
            state["meanwhile"] = again.digest
            return None
        raw = (artifact / "manifest.json").read_bytes()
        empty = artifact / "empty.json"
        empty.write_bytes(EMPTY_CONFIG)
        for path, media_type in ((empty, EMPTY_MEDIA_TYPE), (artifact / ARCHIVE, ARCHIVE_MEDIA_TYPE),
                                 (artifact / CONTENT, CONTENT_MEDIA_TYPE)):
            oras("blob", "push", ref.repository, str(path), "--media-type", media_type, "--descriptor",
                 plain_http=args.plain_http)
        pushed = oras("manifest", "push", "--descriptor", ref.repository, str(artifact / "manifest.json"),
                      plain_http=args.plain_http).stdout
        digest = f"sha256:{hashlib.sha256(raw).hexdigest()}"
        if json.loads(pushed).get("digest") != digest:
            raise Red(f"the registry took the manifest as {json.loads(pushed).get('digest')}, not {digest}")
        print(f"push: {ref.at(digest)} (untagged; the tag is set once the digest is signed)", flush=True)
        return digest

    before = stage("preflight", lambda: (lane.ports_free(), lane.host_snapshot())[1])
    stage("browser image", lane.browser_image)
    start = stage("start release", l2.start_release, args.tag, f"{args.tag} (archived)", workdir)
    override_file = stage("override", l2.override, workdir) if start else None
    started = False
    digest = None
    try:
        if start and override_file:
            state["start"] = start
            values = l2.install_values(start, project, workdir)
            app = stage("verify", verify, start)
            started = not stage.red
            pebble_id = stage("pebble", lane.pebble, start.release, workdir, values, app, override_file)
            state["installed"] = stage("install", l2.install, start, workdir, values, pebble_id, override_file)
            stage("certificate", lane.certificate, workdir)
            state["tree"] = stage("start tree", l2.start_tree, start, workdir)
            state["record"] = stage("seed", l2.seed, state["installed"], state["tree"], workdir, stamp)
            stage("backup", backup, state["installed"])
            artifact = stage("assemble", assemble)
            digest = stage("push", push, artifact)
    finally:
        if started:
            stage("evidence", lane.evidence, project, workdir)
            if not args.keep:
                stage("teardown", lane.teardown, project, workdir)
        if state.get("tree") is not None:
            l2.proof.remove_predecessor(state["tree"])
        if before is not None:
            stage("host", lambda: lane.host_untouched(before, lane.host_snapshot(), project, started and not args.keep))

    print(f"\n== summary — the archive of {args.tag}, {project}, run directory {workdir.relative_to(ROOT)}")
    for name, secs, verdict in stage.stages:
        print(f"  {name:<13} {secs:7.1f}s  {verdict}")
    print(f"  {'total':<13} {stage.total():7.1f}s")
    print("steps:")
    for done in l2.STEPS:
        print(f"  {done.stage:<18} {done.outcome:<24} {done.command}")
    if stage.red:
        print("RED\n  " + "\n  ".join(stage.red))
        return 1
    if digest is None:
        outputs(args.outputs, written="false", ref=str(ref), digest=state.get("meanwhile") or "")
        print("GREEN (archived meanwhile by another run; nothing pushed)")
        return 0
    outputs(args.outputs, written="true", ref=str(ref), digest=digest)
    print(f"GREEN — {args.tag} installed, seeded by its own seeder, backed up by its own veyqctl and pushed as "
          f"{ref.at(digest)}")
    return 0


def run_publish(args: argparse.Namespace) -> int:
    ref = artifact_ref(args.tag, args.registry)
    if not DIGEST.match(args.digest or ""):
        raise Red(f"{args.digest!r} is no sha256 digest")
    now = lookup(ref, args.plain_http)
    if now.state == "unknown":
        raise Red(f"the registry will not say whether {ref} exists ({now.said}) — the tag is set only on a definite "
                  f"`not found`")
    if now.state == "present":
        same = now.digest == args.digest
        print(f"::notice::{ref} names {now.digest} already{'' if same else f'; {args.digest} stays untagged'} — "
              f"an archive is written once, nothing was written", flush=True)
        outputs(args.outputs, tagged="false")
        return 0
    oras("tag", ref.at(args.digest), ref.tag, plain_http=args.plain_http)
    back = lookup(ref, args.plain_http)
    if back.state != "present" or back.digest != args.digest:
        raise Red(f"after `oras tag`, {ref} names {back.digest or back.state}, not {args.digest}")
    print(f"publish: {ref} = {args.digest}", flush=True)
    outputs(args.outputs, tagged="true")
    return 0


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    check = sub.add_parser("check", help="stage 0 alone: is the release archived already? (needed=true|false)")
    check.add_argument("--tag", required=True)
    check.add_argument("--registry", required=True)
    check.add_argument("--ref", default=None)
    check.add_argument("--plain-http", action="store_true")
    check.add_argument("--outputs", type=Path, default=None, metavar="FILE", help="append needed=, ref=, repository=")
    archive = sub.add_parser("archive", help="install, seed, back up and push one release's archive (untagged)")
    archive.add_argument("--tag", required=True, help="the release, v<major>.<minor>")
    archive.add_argument("--registry", required=True, help="the registry namespace, e.g. ghcr.io/<owner>")
    archive.add_argument("--ref", default=None, help="the run's git ref ($GITHUB_REF); refused unless a publishing branch")
    archive.add_argument("--plain-http", action="store_true", help="a local registry without TLS")
    archive.add_argument("--keep", action="store_true", help="leave the stack running (no teardown)")
    archive.add_argument("--outputs", type=Path, default=None, metavar="FILE", help="append written=, ref=, digest=")
    publish = sub.add_parser("publish", help="set the archive's tag on a pushed (and signed) digest")
    publish.add_argument("--tag", required=True)
    publish.add_argument("--registry", required=True)
    publish.add_argument("--digest", required=True)
    publish.add_argument("--plain-http", action="store_true")
    publish.add_argument("--outputs", type=Path, default=None, metavar="FILE", help="append tagged=")
    pulled = sub.add_parser("pull", help="fetch an archive into a directory, checked")
    pulled.add_argument("--ref", required=True, help="<registry>/veyq-backups:<major>.<minor> or …@sha256:…")
    pulled.add_argument("--into", required=True, type=Path, help="a directory that does not exist yet")
    pulled.add_argument("--plain-http", action="store_true")
    sub.add_parser("identity", help="print the issuer and the identity archive.yml signs under")
    args = parser.parse_args(list(argv) if argv is not None else None)
    try:
        if args.command == "identity":
            print(f"issuer={OIDC_ISSUER}")
            print(f"identity={IDENTITY_REGEXP}")
            return 0
        if args.command == "check":
            return run_check(args)
        if args.command == "archive":
            return run_archive(args)
        if args.command == "publish":
            return run_publish(args)
        pull(args.ref, args.into, args.plain_http)
        return 0
    except Red as error:
        print(f"RED\n  {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
