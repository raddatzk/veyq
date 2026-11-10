#!/usr/bin/env python3
"""Pin what `src/tools/veyqctl.py` decides, without Docker, cosign or a network (cr-veyqctl).

`veyqctl` is a host tool, not a coverage surface (ADR 0040): this self-test and the live runs recorded in the CR are
what prove it. Everything here runs against a mirror directory this file writes and a stand-in for the host
(`FakeHost`) that records every command veyqctl would have started and answers the few it reads:

  1. **the resolver** — `VEYQ_RELEASE` per line of ADR 0096 decision 4 (a pin, a major, `latest`) against a mirror
     whose minors sort differently as strings (`1.12` after `1.7`), prereleases never taken and a pin to one refused,
     a selector that selects nothing refused, and the newer major `check` reports; and ADR 0096 decision 5 step 1 on
     top of it (`upgrade_target`): a later major entered only from the release its manifest names as `majorEntry`,
     never more than one major per run, a pin in the next major reached directly from its entry, a fix release of the
     old major cut after the new one (1.10 after 2.0, entered from 1.9) never a way in — from below it stops on 1.9,
     from 1.10 it waits for a release of major 2 entered from 1.10 —, a next major without a release refused, the
     entry read only from a verified manifest;
  2. **the manifest parse** — the fields veyqctl reads, and a refusal for each way one is wrong: another release's
     manifest, an image missing, `unscheduled` not a boolean, an asset name that is a path (`../`, a directory), an
     asset without a sha256, a required asset missing;
  3. **the assets on disk** — fetched into `releases/<release>/` only when every sha256 matches (a mismatch leaves
     nothing behind), and a release already there that differs is refused, never repaired;
  4. **the template merge** — install's `.env`: secrets generated in their shapes, the image lines from the manifest
     and never from `--set`, the operator's values given or asked and a missing one refused by name, a value the
     template does not assign kept below it, the file born `0600`; and which variables a compose file requires
     (`${X:?…}`, `${X?…}`, not `$${X:?…}` or `${X:-…}`);
  5. **the signature gate** — `--unsigned-local` refused for a registry reference and for an image that is not on
     the host; a local build refused without it; cosign missing refused; a failed `cosign verify` refused with no
     pull after it; and the exact identity and `name@digest` handed to cosign. And the manifest's own signature
     (`test_manifest_signature`, cr-signed-release-manifest): the exact `cosign verify-blob <manifest> --bundle
     <bundle>` argv with the same identity, the blob byte-identical to what the source served; a manifest changed by
     one byte after it was signed, a missing bundle, and an unsigned manifest that is also malformed each refused
     before anything else runs — the last by its signature, never by its parse; a cosign below
     `COSIGN_MIN_VERSION` refused by name (GHSA-fx35-mq7g-6g98); `--unsigned-local` fetching no bundle and still
     refusing registry references; and `verify` naming the manifest. `FakeHost` answers `verify-blob` with rc 0 only
     when the bundle file is the stand-in token over exactly that blob's sha256 and the identity is the pinned one;
  6. **the health verdict** `status` and every `up` end on: a finished one-shot (`restart: "no"`, exit 0) is
     healthy, a long-running service that exited 0 — what `stop` leaves — is not;
  7. **install end to end** on the stand-in host: the command order (verify before anything is written or started,
     `config` before `up`), the state it records (the project Compose named, which `start` then addresses by name),
     the manifest kept verbatim beside the release, a shell's `COMPOSE_*` dropped, and every printed line of an
     `--unsigned-local` run saying that nothing was verified; and a signed install with digest references: `verify-blob`
     before any `cosign verify`, both before any `docker` command or the home, the bundle kept beside the manifest, a
     directory left by an earlier attempt refused when its manifest is not the verified one; and `upgrade` toward a
     manifest changed after it was signed refused at step 2, every command before it a read;
  8. **`upgrade`** — `VEYQ_RELEASE` per line of ADR 0096 decision 4 against a running 1.3 (a pin to the running
     release does nothing and runs nothing, a major takes its newest, `latest` and `2` stop on 1.12, the last of major
     1, and say that the next `upgrade` enters major 2, which it then does, skipping a prerelease; `check` says the
     same; a pin below the running release is refused); decision 5's order (both images, the staged `.env` checked by Compose,
     then the app stopped, leg (c) copied out, the capture by the RUNNING release's tool and its check, the switch,
     `up` on the new recipe) with nothing stopped before step 4; the new `.env` (the new image lines, the operator's
     values and kept keys carried over); and the refusals — `--key-into` omitted, existing, inside `backups/` or in a
     directory veyqctl cannot search (as this Python and as 3.14 answer `exists()`) before any command, a value the new template or recipe requires named before anything stops (never the
     template's example), a failed backup switching nothing and starting the running release again;
  9. **`backup` / `restore`** — the order (stop, key out, capture, check, up / stop, restore, key in, up), the tool's
     Compose pointed at this instance with its credentials in the environment and in no argv, a tool that changed on
     disk refused before the app stops, the restored key on stdin of a one-off `app` container, the app left stopped
     after a failed restore; and the object pool (`test_pool`, V1–V4): `status` and `install` warning while the store
     in use is bundled (or none, or unreadable) and no pool is named, never changing the exit code; `backup` and
     `upgrade` running `copy-objects` before the app stops and the capture with `--objects-to` after it, the command
     line winning over `.env`, none without a pool, a failed early copy stopping nothing, a pool inside `releases/`
     refused; `restore --objects-from` absolute and never implied; an S3 pool's key only in the tool's environment;
     and `rollback` (`test_rollback`, O1 (a)): verify against the live store, stop, empty the database, the capture's
     release started once, stop, restore with the check only, key, start — and refused, nothing stopped, for a release
     gone from `releases/`, a capture that does not verify, and no terminal without `--yes`;
 10. **`self-update`** — replaced by the installed release's `veyqctl` with its mode kept, nothing to do the second
     time, refused (nothing replaced) when the installed copy no longer matches its manifest; for a signed release,
     the manifest on disk verified against its bundle first — refused when it was edited to match a swapped
     `veyqctl`, and when the bundle is gone.

 11. **the instance list and the GitOps target** (ADR 0126, cr-veyqctl-gitops-target) — `init --compose` installing
     as `install` and listing the home; `--home` over `--instance` over the current one; every Compose lifecycle verb
     refused on a GitOps instance, naming what does it there, running nothing; `init --gitops` verifying the blob,
     three images and the chart (its tag's digest, its Chart.yaml version) and rendering BEFORE the directory exists,
     then writing the Application, the values and resources/ per provider, the generated values never in the
     repository (handed over 0600, or `sops encrypt` on stdin); the cluster contract naming every gap; `upgrade`
     stopping on a major's entry, rewriting the four pins and three tags, committing its three files alone, pushing
     only with `--push` — and refusing drift, uncommitted changes, an unverified or mis-versioned chart, a failed render
     and a downgrade with the repository untouched; a hand-written veyqctl.json confirmed by `check` when its pins
     are its running release's as signed, and refused by `upgrade` when not.
What it cannot show: that Docker, cosign and the backup tool behave as the stand-in answers. That is the live runs' (the CR says
which). Not a `validate_*.py`: it asserts nothing about the repository, only about the tool. Runnable locally:

    python3 src/tools/veyqctl_selftest.py
"""
from __future__ import annotations  # the repo's scripts run on Python 3.9

import contextlib
import hashlib
import io
import json
import os
import pathlib
import shutil
import stat
import subprocess
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import veyqctl as vc  # noqa: E402

failures: list[str] = []


def check(label: str, ok: bool, detail: str = "") -> None:
    print(f"{'ok  ' if ok else 'FAIL'} {label}{(' — ' + detail) if detail and not ok else ''}")
    if not ok:
        failures.append(label)


@contextlib.contextmanager
def path_tests_as_in_python_3_14():
    """`Path.exists()`, `is_file()`, `is_dir()` and `is_symlink()` as Python 3.14 answers them — False for ANY OSError,
    where 3.9 to 3.13 raise every one but "nothing is there". CI runs 3.14 and this Mac 3.9; a case that must hold on
    both runs under this too. On 3.14 itself the wrapper changes nothing."""
    names = ("exists", "is_file", "is_dir", "is_symlink")
    own = {name: pathlib.Path.__dict__[name] for name in names if name in pathlib.Path.__dict__}
    tests = {name: getattr(pathlib.Path, name) for name in names}

    def swallowing(test):
        def as_in_3_14(self, *args, **kwargs):
            try:
                return test(self, *args, **kwargs)
            except OSError:
                return False
        return as_in_3_14

    for name, test in tests.items():
        setattr(pathlib.Path, name, swallowing(test))
    try:
        yield
    finally:
        for name in names:
            if name in own:
                setattr(pathlib.Path, name, own[name])
            else:
                delattr(pathlib.Path, name)


def refused(fn, *args, **kwargs) -> str:
    """The refusal's message, or '' when `fn` did not refuse."""
    try:
        with contextlib.redirect_stdout(io.StringIO()):
            fn(*args, **kwargs)
    except vc.Refused as e:
        return str(e)
    return ""


DIGEST_A = "sha256:" + "a" * 64
DIGEST_B = "sha256:" + "b" * 64
SIGNED_APP = f"ghcr.io/raddatzk/veyq:1.12@{DIGEST_A}"
SIGNED_ADMIN = f"ghcr.io/raddatzk/veyq-admin:1.12@{DIGEST_B}"
DB_ROOT = "db-root-secret-in-config"
STORE_TOKEN = "store-token-secret-in-config"

TEMPLATE = """# a template
VEYQ_DOMAIN=veyq.example.org
VEYQ_IMAGE=
VEYQ_ADMIN_IMAGE=
MARIADB_PASSWORD=
MARIADB_ROOT_PASSWORD=
GARAGE_RPC_SECRET=
GARAGE_ADMIN_TOKEN=
VEYQ_ACME_EMAIL=
#   ADMIN_PORT=8081
"""
COMPOSE = """# Every value is REQUIRED: `${VAR:?…}` fails the `up` (a comment, as the recipe's own header says it)
name: veyq
services:
  app:
    image: ${VEYQ_IMAGE:?set VEYQ_IMAGE}
    environment:
      A: ${MARIADB_PASSWORD:?x}
      B: ${VEYQ_DOMAIN:?x}
      C: ${VEYQ_HSTS_ENABLED:-true}
      D: $${ESCAPED:?not a variable}
      E: ${PLAIN_REQUIRED?set, may be empty}  # not ${TRAILING:?a comment}
      F: "a # in quotes is not a comment ${QUOTED:?x}"
"""


class FakeHost(vc.Host):
    """Records every command; answers `docker image inspect`, `cosign verify`, `config --format json` and `ps`."""

    HEALTHY = (
        {"ID": "c1", "Service": "app", "State": "running", "Health": "healthy", "ExitCode": 0, "_restart": "unless-stopped"},
        {"ID": "c2", "Service": "volume-ownership", "State": "exited", "Health": "", "ExitCode": 0, "_restart": "no"},
    )

    def __init__(self, *, local_images=(), signed=(), cosign=True, project="veyq", answers=None, ps=HEALTHY,
                 fail_tool: str = "", cosign_version: str = "v3.1.3", watch: pathlib.Path | None = None,
                 store_in_use: str | None = "bundled", ledger: str | None = None, fresh: bool | None = True,
                 garage: bool = True) -> None:
        self.garage = garage                # False: the override left the bundled store out (no `garage`, hint blank)
        self.store_in_use = store_in_use    # what the tool's `store-in-use` prints; None: it fails
        self.ledger = ledger                # what `erasures.log` in the app's volume holds; None: there is none
        self.fresh = fresh                  # the tool's `retention`: a capture younger than B; None: it fails
        self.commands: list[list[str]] = []
        self.blobs: list[bytes] = []        # what `cosign verify-blob` was handed as the blob, at the moment it ran
        self.watch = watch                  # a path whose existence is recorded per command (the home, say)
        self.watched: list[bool] = []
        self.cosign_version = cosign_version
        self.envs: list[dict] = []
        self.stdins: list[bytes] = []
        self.fail_tool = fail_tool
        self.local_images = set(local_images)
        self.signed = set(signed)
        self.cosign = cosign
        self.project = project
        self.answers = answers or {}
        self.ps = ps

    def run(self, cmd, *, check=True, stream=False, env=None, stdin=None):
        self.commands.append(list(cmd))
        self.watched.append(bool(self.watch and self.watch.exists()))
        self.envs.append(dict(env or {}))
        if stdin is not None:
            self.stdins.append(stdin)
        rc, out = 0, ""
        if cmd[0] == sys.executable and cmd[1].endswith(vc.BACKUP_ASSET):
            rc = 1 if cmd[2] == self.fail_tool else 0
            if cmd[2] == "store-in-use":
                rc, out = (1, "") if self.store_in_use is None else (rc, self.store_in_use + "\n")
            if cmd[2] == "retention":
                rc, out = (1, "") if self.fresh is None else (rc, json.dumps(
                    {"days": 30, "source": "the default, none set", "youngest": "20261001T000000Z" if self.fresh else
                     None, "youngestAt": None, "fresh": self.fresh}) + "\n")
            if cmd[2] == "restore" and not rc:  # the tool places leg (c) where veyqctl told it to
                key = cmd[cmd.index("--config-secret-file") + 1]
                pathlib.Path(key).write_bytes(b"restored-key")
        elif "read-file" in cmd:  # the erasure ledger read out of the app's volume by a one-off container
            rc, out = (3, "") if self.ledger is None else (0, self.ledger)
        elif cmd[:2] == ["docker", "cp"]:
            pathlib.Path(cmd[3]).write_bytes(b"the-instance-key")
        elif cmd[:3] == ["docker", "ps", "-aq"]:
            out = "cid-app\n"
        elif cmd[:3] == ["docker", "image", "inspect"]:
            rc = 0 if cmd[-1] in self.local_images else 1
        elif cmd[0].endswith("cosign") and cmd[1] == "verify":
            rc = 0 if cmd[2] in self.signed else 1
        elif cmd[0].endswith("cosign") and cmd[1] == "version":
            out = f"GitVersion:    {self.cosign_version}\nGitCommit:     0\n" if self.cosign_version else "no version\n"
        elif cmd[0].endswith("cosign") and cmd[1] == "verify-blob":
            # rc 0 iff the bundle is the stand-in signature over exactly this blob AND veyq's identity was asked for
            blob = pathlib.Path(cmd[2]).read_bytes()
            self.blobs.append(blob)
            bundle = pathlib.Path(cmd[cmd.index("--bundle") + 1]) if "--bundle" in cmd else None
            identity = (cmd[cmd.index("--certificate-oidc-issuer") + 1] == vc.COSIGN_OIDC_ISSUER
                        and cmd[cmd.index("--certificate-identity-regexp") + 1] == vc.COSIGN_IDENTITY_REGEXP
                        ) if {"--certificate-oidc-issuer", "--certificate-identity-regexp"} <= set(cmd) else False
            rc = 0 if bundle and bundle.is_file() and bundle.read_text() == bundle_token(blob) and identity else 1
        elif "config" in cmd and "--format" in cmd:
            services = {
                "app": {"environment": {"VEYQ_SECURITY_CONFIG_SECRET_FILE": "/var/lib/veyq/config-secret.key",
                                        "VEYQ_BUNDLED_STORE_ENDPOINT": "http://garage:3900",
                                        "VEYQ_BUNDLED_STORE_ADMIN_TOKEN": STORE_TOKEN}},
                "mariadb": {"environment": {"MARIADB_ROOT_PASSWORD": DB_ROOT}},
                "garage": {"networks": {"edge": None}},
            }
            if not self.garage:  # what the measured override merge composes: no `garage`, the hint blanked
                del services["garage"]
                services["app"]["environment"].update({"VEYQ_BUNDLED_STORE_ENDPOINT": "",
                                                       "VEYQ_BUNDLED_STORE_ADMIN_TOKEN": ""})
            out = json.dumps({"name": self.project, "services": services,
                              "networks": {"edge": {"name": f"{self.project}_edge"}}})
        elif "ps" in cmd and "--format" in cmd:
            out = "\n".join(json.dumps({k: v for k, v in r.items() if not k.startswith("_")}) for r in self.ps)
        elif cmd[:2] == ["docker", "inspect"]:
            out = "\n".join(f"{r['ID']}full {r['_restart']}" for r in self.ps if r["ID"] in cmd)
        elif "port" in cmd:
            out = "127.0.0.1:8081\n"
        result = subprocess.CompletedProcess(cmd, rc, out, "stand-in refusal" if rc else "")
        if check and rc:
            raise vc.Refused(f"`{' '.join(cmd[:4])} …` exited {rc}")
        return result

    def which(self, name):
        return f"/usr/bin/{name}" if (name != "cosign" or self.cosign) else None

    def ask(self, key, example):
        return self.answers.get(key)

    def wait(self, prompt):
        return False  # no terminal: a missing program is refused

    def prompt(self, question, default="", hint=""):
        """A terminal where each question gets its answer from `answers`, else Enter (the default, or nothing)."""
        return self.answers.get(question) or default or None

    def ran(self, *words: str) -> list[int]:
        """The indexes of every recorded command containing all of `words`."""
        return [i for i, c in enumerate(self.commands) if all(w in c for w in words)]


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


BUNDLE = "manifest.json.bundle"  # the publish's name for the manifest's signature (release_assets.MANIFEST_BUNDLE)


def bundle_token(blob: bytes) -> str:
    """The stand-in for a Sigstore bundle: valid for exactly one blob, as a real one is."""
    return f"stand-in sigstore bundle over sha256:{sha(blob)}\n"


def write_release(root: pathlib.Path, tag: str, *, prerelease=False, app="veyq:local", admin="veyq-admin:local",
                  files=None, manifest_edit=None, signed=False, entry=None) -> None:
    """One release in the mirror layout, as `release_assets.py` writes it — and, `signed`, as the publish leaves it:
    with the manifest's bundle beside it. `entry` is its `majorEntry` (null by default)."""
    files = files or {vc.COMPOSE_ASSET: COMPOSE, vc.TEMPLATE_ASSET: TEMPLATE, vc.BACKUP_ASSET: "# backup tool\n",
                      vc.SELF_ASSET: "# veyqctl\n", "garage.toml": "# garage\n"}
    d = root / tag
    d.mkdir(parents=True)
    for name, text in files.items():
        (d / name).write_text(text)
    manifest = {"release": tag[1:], "date": "2026-10-16", "images": {"app": app, "admin": admin},
                "unscheduled": False, "majorEntry": entry,
                "assets": {name: sha(text.encode()) for name, text in files.items()}}
    if manifest_edit:
        manifest_edit(manifest)
    # The producer's own form (indent 2, a final newline): a signature is over these bytes, and a verifier that
    # re-serialised the parsed manifest would hand cosign other ones.
    (d / vc.MANIFEST_ASSET).write_text(json.dumps(manifest, indent=2) + "\n")
    if signed:
        (d / BUNDLE).write_text(bundle_token((d / vc.MANIFEST_ASSET).read_bytes()))
    index = root / vc.RELEASES_INDEX
    doc = json.loads(index.read_text()) if index.is_file() else {"releases": []}
    doc["releases"].append({"tag": tag, "date": "2026-10-16", "prerelease": prerelease,
                            "manifest": f"{tag}/{vc.MANIFEST_ASSET}"})
    index.write_text(json.dumps(doc))


NULL = object()  # manifest_bytes(key=NULL): the key present with a JSON null (None removes it)


def manifest_bytes(**changes) -> bytes:
    doc = {"release": "1.12", "date": "2026-10-16", "images": {"app": SIGNED_APP, "admin": SIGNED_ADMIN},
           "unscheduled": False, "majorEntry": "0.9", "assets": {a: "0" * 64 for a in vc.REQUIRED_ASSETS}}
    for key, value in changes.items():
        if value is NULL:
            doc[key] = None
        elif value is None:
            doc.pop(key, None)
        else:
            doc[key] = value
    return json.dumps(doc).encode()


def test_resolver(tmp: pathlib.Path) -> None:
    mirror = tmp / "mirror"
    for tag, pre in (("v0.9", False), ("v1.3", False), ("v1.7", False), ("v1.12", False), ("v2.0", True)):
        write_release(mirror, tag, prerelease=pre)
    listed = vc.DirSource(mirror).releases()
    take = lambda sel: vc.select(listed, sel, "the mirror").release  # noqa: E731
    check("resolver: `1.3` pins exactly 1.3", take("1.3") == "1.3")
    check("resolver: `1` takes the newest of major 1, numerically (1.12 after 1.7)", take("1") == "1.12")
    check("resolver: `0` takes the newest of major 0", take("0") == "0.9")
    check("resolver: `latest` takes the newest of all and never a prerelease (2.0)", take("latest") == "1.12")
    check("resolver: a pin to a prerelease is refused, naming it",
          "prerelease" in refused(vc.select, listed, "2.0", "the mirror"))
    check("resolver: a major whose only release is a prerelease selects nothing",
          "selects no release" in refused(vc.select, listed, "2", "the mirror"))
    check("resolver: a pin that is not in the source is refused", "not in" in refused(vc.select, listed, "3.1", "x"))
    for bad in ("1.x", "v1.3", "", "1.2.3", "newest"):
        check(f"resolver: selector {bad!r} is refused", "selects nothing" in refused(vc.check_selector, bad))
    check("check: the newer major beyond `0`", vc.newer_major(listed, vc.select(listed, "0", "x")).release == "1.12")
    check("check: no newer major beyond `1` (2.0 is a prerelease)",
          vc.newer_major(listed, vc.select(listed, "1", "x")) is None)
    (tmp / "broken").mkdir()
    (tmp / "broken" / vc.RELEASES_INDEX).write_text('{"tags": []}')
    check("resolver: an index without `releases` is refused", "releases" in refused(vc.DirSource(tmp / "broken").releases))
    check("source: an asset name with `../` is refused before any path is built",
          "flat file name" in refused(vc.DirSource(mirror).fetch, "v1.3", "../releases.json"))
    check("source: an asset name with a directory is refused",
          "flat file name" in refused(vc.DirSource(mirror).fetch, "v1.3", "sub/garage.toml"))
    check("source: a tag that is not `v<major>.<minor>` is refused",
          "not a release tag" in refused(vc.DirSource(mirror).fetch, "../v1.3", vc.MANIFEST_ASSET))


def test_major_entry() -> None:
    """ADR 0096 decision 5 step 1, the resolution alone: where one `upgrade` from `running` goes. `entries` stands for
    each release's signed `majorEntry`; every read of one is recorded."""
    def listed(*releases: str, pre: tuple = ()) -> list:
        return [vc.Listed(f"v{r}", *(int(x) for x in r.split(".")), r in pre) for r in releases]

    def goes(rel: list, running: str, selector: str, entries: dict, read: list | None = None) -> str:
        chosen = vc.select(rel, selector, "the mirror")
        entry_of = lambda r: ((read if read is not None else []).append(r.release), entries.get(r.release))[1]  # noqa: E731
        return vc.upgrade_target(rel, tuple(int(x) for x in running.split(".")), chosen, "the mirror", entry_of).release

    two = listed("1.3", "1.9", "2.2", "2.5")
    three = listed("1.3", "1.9", "2.2", "2.5", "3.1")
    at19 = {"2.2": "1.9", "2.5": "1.9", "3.1": "2.5"}
    # the control: within a major nothing stops on the way, and no manifest is read for it
    read: list = []
    check("major entry: within a major, 1.3 → 1.9 directly, no entry read (control)",
          goes(two, "1.3", "1", at19, read) == "1.9" and read == [], str(read))
    read = []
    check("major entry: `latest` from 1.3 (2.5 entered from 1.9) stops on 1.9, reading 2.5's entry alone",
          goes(two, "1.3", "latest", at19, read) == "1.9" and read == ["2.5"], str(read))
    check("major entry: a pin in the next major (2.5) from 1.3 stops on 1.9", goes(two, "1.3", "2.5", at19) == "1.9")
    check("major entry: `2` from 1.3 stops on 1.9", goes(two, "1.3", "2", at19) == "1.9")
    check("major entry: from 1.9, its entry, a pin in major 2 (2.2) is reached directly",
          goes(two, "1.9", "2.2", at19) == "2.2")
    check("major entry: from 1.9, `latest` within the next major (2.5) is reached directly",
          goes(two, "1.9", "latest", at19) == "2.5")
    check("major entry: from 1.9 with 3.1 listed, `latest` enters major 2 only, at its newest (2.5)",
          goes(three, "1.9", "latest", at19) == "2.5")
    check("major entry: from 1.9, a pin in major 3 (3.1) goes to the newest of major 2",
          goes(three, "1.9", "3.1", at19) == "2.5")
    check("major entry: from 1.3 with 3.1 listed, `latest` stops on 1.9 first", goes(three, "1.3", "latest", at19) == "1.9")
    check("major entry: a prerelease (1.10) is never a way in — from 1.9 `latest` goes to 2.5",
          goes(listed("1.3", "1.9", "1.10", "2.5", pre=("1.10",)), "1.9", "latest", at19) == "2.5")
    gap = listed("1.9", "2.0", "3.1", pre=("2.0",))
    message = refused(lambda: goes(gap, "1.9", "latest", {"3.1": "2.0"}))
    check("major entry: a next major with only a prerelease is refused, naming it, never crossed",
          "no release of major 2" in message, message)

    # The late fix release (ADR 0085 decision 10): 2.0 was cut from 1.9, then `release/1.x` released 1.10. Nobody
    # proved 1.10 → 2.0, so it is no way in; 2.1, cut after 1.10, is entered from it.
    late = listed("1.3", "1.9", "1.10", "2.0")
    entries = {"2.0": "1.9"}
    check("late fix: `latest` from 1.3 stops on 2.0's entry 1.9, not on the newest of major 1 (1.10)",
          goes(late, "1.3", "latest", entries) == "1.9")
    check("late fix: from 1.9 `latest` enters 2.0", goes(late, "1.9", "latest", entries) == "2.0")
    message = refused(lambda: goes(late, "1.10", "latest", entries))
    check("late fix: from 1.10 nothing of major 2 is entered from it yet — refused, naming both and the wait",
          "entered from 1.9" in message and "runs 1.10" in message and "wait for it" in message
          and "Nothing was stopped" in message, message)
    check("late fix: …and within major 1 from 1.10 nothing is in the way of `1`", goes(late, "1.10", "1", entries) == "1.10")
    fixed = listed("1.3", "1.9", "1.10", "2.0", "2.1")
    entries = {"2.0": "1.9", "2.1": "1.10"}
    check("late fix: once 2.1 (entered from 1.10) is out, `latest` from 1.10 enters 2.1",
          goes(fixed, "1.10", "latest", entries) == "2.1")
    check("late fix: …and `latest` from 1.3 now stops on 1.10, 2.1's entry", goes(fixed, "1.3", "latest", entries) == "1.10")
    check("late fix: …while a pin to 2.0 from 1.9 is still reached directly", goes(fixed, "1.9", "2.0", entries) == "2.0")
    message = refused(lambda: goes(fixed, "1.10", "2.0", entries))
    check("late fix: a pin to 2.0 from 1.10 is refused, pointing at the major's newest",
          "entered from 1.9" in message and "select `2`" in message, message)

    message = refused(lambda: goes(two, "1.9", "latest", {}))
    check("major entry: a next-major release whose manifest names no entry is refused, never crossed",
          "names no release major 2 is entered from" in message, message)
    message = refused(lambda: goes(listed("1.3", "2.5"), "1.3", "latest", at19))
    check("major entry: an entry the source does not list is refused, naming it",
          "lists no release 1.9" in message, message)


def test_manifest() -> None:
    m = vc.parse_manifest(manifest_bytes(), "v1.12")
    check("manifest: the fields veyqctl reads",
          (m.release, m.app, m.admin, m.unscheduled, sorted(m.assets), m.major_entry) ==
          ("1.12", SIGNED_APP, SIGNED_ADMIN, False, sorted(vc.REQUIRED_ASSETS), "0.9"))
    check("manifest: a null `majorEntry` (no release of the major before) parses as none",
          vc.parse_manifest(manifest_bytes(majorEntry=NULL), "v1.12").major_entry is None)
    # A release names a third image, `images.tools` (ADR 0098: a cluster's backup gate runs it, Compose never does);
    # veyqctl reads the two it runs and still parses the manifest whole.
    tools = "ghcr.io/raddatzk/veyq-tools:1.12@sha256:" + "c" * 64
    m = vc.parse_manifest(manifest_bytes(images={"app": SIGNED_APP, "admin": SIGNED_ADMIN, "tools": tools}), "v1.12")
    check("manifest: one naming the tools image as well parses, and veyqctl runs the app and admin images",
          (m.app, m.admin) == (SIGNED_APP, SIGNED_ADMIN))
    cases = {
        "another release's manifest": (manifest_bytes(release="1.11"), "names release"),
        "an image missing": (manifest_bytes(images={"app": SIGNED_APP}), "both images"),
        "`unscheduled` not a boolean": (manifest_bytes(unscheduled="no"), "unscheduled"),
        "an asset name with `../`": (manifest_bytes(assets={"../veyqctl": "0" * 64}), "flat file name"),
        "an asset name with a directory": (manifest_bytes(assets={"conf/garage.toml": "0" * 64}), "flat file name"),
        "an asset without a sha256": (manifest_bytes(assets={**{a: "0" * 64 for a in vc.REQUIRED_ASSETS},
                                                             "garage.toml": "abc"}), "no sha256"),
        "a required asset missing": (manifest_bytes(assets={vc.COMPOSE_ASSET: "0" * 64}), "lacks"),
        "no `majorEntry`": (manifest_bytes(majorEntry=None), "names no `majorEntry`"),
        "a `majorEntry` of its own major": (manifest_bytes(majorEntry="1.11"), "no release of the major before"),
        "a `majorEntry` of a later major": (manifest_bytes(majorEntry="2.0"), "no release of the major before"),
        "a `majorEntry` that is no release": (manifest_bytes(majorEntry="v0.9"), "no release of the major before"),
        "a `majorEntry` that is no string": (manifest_bytes(majorEntry=9), "no release of the major before"),
        "no asset list": (manifest_bytes(assets=None), "lists no"),
        "not JSON": (b"{", "not JSON"),
    }
    for what, (raw, needle) in cases.items():
        message = refused(vc.parse_manifest, raw, "v1.12")
        check(f"manifest: {what} is refused", needle in message, message or "accepted")
    message = refused(vc.parse_manifest, manifest_bytes(release="2.1", majorEntry="0.9"), "v2.1")
    check("manifest: a `majorEntry` two majors back is refused", "no release of the major before" in message,
          message or "accepted")


def test_assets_on_disk(tmp: pathlib.Path) -> None:
    mirror = tmp / "mirror-assets"
    write_release(mirror, "v1.3")
    source = vc.DirSource(mirror)
    chosen = vc.select(source.releases(), "1.3", "x")
    manifest = vc.fetch_manifest(FakeHost(), source, chosen, True)
    home = tmp / "home-assets"
    with contextlib.redirect_stdout(io.StringIO()):
        dest = vc.fetch_release(source, chosen, manifest, home)
    check("assets: every manifest asset lands in releases/<release>/",
          sorted(p.name for p in dest.iterdir()) == sorted([*manifest.assets, vc.MANIFEST_ASSET]))
    (dest / "garage.toml").write_text("# tampered\n")
    check("assets: a release already on disk that differs is refused, not repaired",
          "does not" in refused(vc.fetch_release, source, chosen, manifest, home)
          and (dest / "garage.toml").read_text() == "# tampered\n")

    bad = tmp / "mirror-bad"
    write_release(bad, "v1.4", manifest_edit=lambda d: d["assets"].update({"garage.toml": "f" * 64}))
    source = vc.DirSource(bad)
    chosen = vc.select(source.releases(), "1.4", "x")
    home = tmp / "home-bad"
    message = refused(vc.fetch_release, source, chosen, vc.fetch_manifest(FakeHost(), source, chosen, True), home)
    check("assets: a file whose sha256 differs from the manifest is refused", "manifest says" in message, message)
    check("assets: …and nothing of that release is left on disk",
          not any((home / vc.RELEASES_DIR).iterdir()), str(list((home / vc.RELEASES_DIR).iterdir())))


def test_template(tmp: pathlib.Path) -> None:
    m = vc.parse_manifest(manifest_bytes(), "v1.12")
    given = {"VEYQ_DOMAIN": "veyq.test", "VEYQ_ACME_EMAIL": "ops@veyq.test", "ADMIN_PORT": "18081"}
    values = vc.install_values(TEMPLATE, m, given, lambda k, e: None)
    check("template: the image lines come from the manifest",
          (values["VEYQ_IMAGE"], values["VEYQ_ADMIN_IMAGE"]) == (SIGNED_APP, SIGNED_ADMIN))
    check("template: the RPC secret is 64 hex chars", len(values["GARAGE_RPC_SECRET"]) == 64
          and all(c in "0123456789abcdef" for c in values["GARAGE_RPC_SECRET"]))
    check("template: the other secrets are generated, and differ",
          len({values["MARIADB_PASSWORD"], values["MARIADB_ROOT_PASSWORD"], values["GARAGE_ADMIN_TOKEN"]}) == 3
          and all(len(values[k]) >= 32 for k in ("MARIADB_PASSWORD", "MARIADB_ROOT_PASSWORD", "GARAGE_ADMIN_TOKEN")))
    check("template: the operator's values are the given ones", values["VEYQ_DOMAIN"] == "veyq.test")
    check("template: VEYQ_RELEASE defaults to `latest` (the use-case's install default)", values["VEYQ_RELEASE"] == "latest")
    rendered = vc.render_env(TEMPLATE, values)
    lines = rendered.splitlines()
    check("template: rendered line by line — comments kept, the commented optional left a comment",
          lines[0] == "# a template" and "#   ADMIN_PORT=8081" in lines and "VEYQ_DOMAIN=veyq.test" in lines)
    check("template: a value the template does not assign is kept below it, under the marker",
          lines.index(vc.KEPT_HEADER) < lines.index("ADMIN_PORT=18081") and "VEYQ_RELEASE=latest" in lines)
    check("template: render → read is the identity on the values", vc.env_values(rendered) == values)

    asked = vc.install_values(TEMPLATE, m, {"VEYQ_ACME_EMAIL": "x@y"}, lambda k, e: f"asked-{k}-{e}")
    check("template: a value not given is asked, with the template's as the example",
          asked["VEYQ_DOMAIN"] == "asked-VEYQ_DOMAIN-veyq.example.org")
    message = refused(vc.install_values, TEMPLATE, m, {"VEYQ_DOMAIN": "veyq.test"}, lambda k, e: None)
    check("template: an operator value neither given nor asked is refused, named",
          "VEYQ_ACME_EMAIL" in message and "--set VEYQ_ACME_EMAIL" in message, message)
    check("template: the image lines cannot be --set",
          "release's to write" in refused(vc.install_values, TEMPLATE, m, {**given, "VEYQ_IMAGE": "x"}, lambda k, e: None))

    check("required: `${X:?…}`, `${X?…}` and one inside a quoted value are required; `${X:-…}`, Compose's escaped "
          "`$${X:?…}` and one in a comment (whole-line or trailing) are not",
          vc.required_names(COMPOSE) == ["VEYQ_IMAGE", "MARIADB_PASSWORD", "VEYQ_DOMAIN", "PLAIN_REQUIRED", "QUOTED"],
          str(vc.required_names(COMPOSE)))
    check("required: an empty or quoted-empty value counts as unset",
          vc.unset_required({"VEYQ_IMAGE": "x", "MARIADB_PASSWORD": '""', "VEYQ_DOMAIN": "d", "QUOTED": "q"}, COMPOSE)
          == ["MARIADB_PASSWORD", "PLAIN_REQUIRED"])

    target = tmp / "private.env"
    old = os.umask(0o022)
    try:
        vc.write_private(target, "A=1\n")
    finally:
        os.umask(old)
    check(".env: born 0600 under a permissive umask", stat.S_IMODE(target.stat().st_mode) == 0o600)


def test_signature_gate() -> None:
    local = vc.Manifest("1.12", "", "veyq:local", "veyq-admin:local", False, {})
    signed = vc.Manifest("1.12", "", SIGNED_APP, SIGNED_ADMIN, False, {})
    mixed = vc.Manifest("1.12", "", "veyq:local", SIGNED_ADMIN, False, {})

    host = FakeHost(local_images={"veyq:local", "veyq-admin:local"})
    message = refused(vc.verify_images, host, signed, True)
    check("gate: --unsigned-local with registry references is refused", "no registry host and no digest" in message)
    check("gate: --unsigned-local with ONE registry reference is refused, naming it",
          "admin `ghcr.io" in refused(vc.verify_images, host, mixed, True))
    check("gate: --unsigned-local for an image not on the host is refused (never pulled from Docker Hub)",
          "not on this host" in refused(vc.verify_images, FakeHost(local_images={"veyq:local"}), local, True))
    check("gate: --unsigned-local with two local builds on the host passes", refused(vc.verify_images, host, local, True) == "")
    check("gate: a local build without --unsigned-local is refused",
          "--unsigned-local" in refused(vc.verify_images, FakeHost(), local, False))
    check("gate: a registry reference without a digest is refused",
          "pinned by digest" in refused(vc.verify_images, FakeHost(),
                                        signed._replace(app="ghcr.io/raddatzk/veyq:1.12"), False))
    check("gate: no cosign on PATH is refused", "cosign is not on PATH" in refused(vc.verify_images, FakeHost(cosign=False),
                                                                                  signed, False))

    app_target = f"ghcr.io/raddatzk/veyq@{DIGEST_A}"
    admin_target = f"ghcr.io/raddatzk/veyq-admin@{DIGEST_B}"
    host = FakeHost(signed={app_target})
    message = refused(vc.verify_images, host, signed, False)
    check("gate: a signature that does not verify is refused, naming the image", admin_target in message, message)
    check("gate: …and nothing is pulled or started after it",
          not host.ran("pull") and not host.ran("up") and not host.ran("compose"))
    host = FakeHost(signed={app_target, admin_target})
    check("gate: both verified passes", refused(vc.verify_images, host, signed, False) == "")
    want = ["/usr/bin/cosign", "verify", app_target, "--certificate-oidc-issuer", vc.COSIGN_OIDC_ISSUER,
            "--certificate-identity-regexp", vc.COSIGN_IDENTITY_REGEXP]
    check("gate: cosign gets `name@digest` (the tag dropped) and exactly the pinned issuer and identity",
          host.commands[0] == want, str(host.commands[0]))
    port = vc.Manifest("1.12", "", f"localhost:5000/veyq:1.12@{DIGEST_A}", f"localhost:5000/veyq-admin@{DIGEST_B}", False, {})
    host = FakeHost(signed={f"localhost:5000/veyq@{DIGEST_A}", f"localhost:5000/veyq-admin@{DIGEST_B}"})
    check("gate: a registry with a port parses to `name@digest`", refused(vc.verify_images, host, port, False) == "")


def test_install(tmp: pathlib.Path) -> None:
    mirror = tmp / "mirror-install"
    write_release(mirror, "v1.3", app="veyq:local", admin="veyq-admin:local")
    home = tmp / "home-install"
    host = FakeHost(local_images={"veyq:local", "veyq-admin:local"}, project="veyq-proof")
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        rc = vc.main(["--home", str(home), "--source", str(mirror), "install", "--unsigned-local",
                      "--set", "VEYQ_DOMAIN=veyq.test", "--set", "VEYQ_ACME_EMAIL=ops@veyq.test",
                      "--set", "PLAIN_REQUIRED=1", "--set", "QUOTED=q", "--set", "VEYQ_RELEASE=1"], host)
    check("install: exits 0 on the stand-in host", rc == 0, out.getvalue()[-800:])
    lines = [l for l in out.getvalue().splitlines() if l]
    check("install --unsigned-local: EVERY printed line says nothing was verified",
          lines and all(l.startswith("veyqctl [--unsigned-local: NOTHING VERIFIED]: ") for l in lines),
          next((l for l in lines if "NOTHING VERIFIED" not in l), ""))
    inspect, config_q, config_json, up = (host.ran("image", "inspect"), host.ran("config", "--quiet"),
                                          host.ran("config", "--format"), host.ran("up"))
    check("install: images checked first, then `config`, then `up`, once each",
          len(up) == 1 and inspect and max(inspect) < min(config_q) < min(config_json) < up[0], str(host.commands))
    env = home / vc.ENV_FILE
    values = vc.env_values(env.read_text()) if env.is_file() else {}
    check("install: .env is 0600 and pins the images, the selector and the operator's values",
          env.is_file() and stat.S_IMODE(env.stat().st_mode) == 0o600 and values.get("VEYQ_IMAGE") == "veyq:local"
          and values.get("VEYQ_RELEASE") == "1" and values.get("VEYQ_DOMAIN") == "veyq.test"
          and values.get("PLAIN_REQUIRED") == "1")
    state = vc.read_state(home) if (home / vc.STATE_FILE).is_file() else None
    check("install: state records the release, the project Compose named, the source, and that it is unverified",
          state == vc.State("1.3", "veyq-proof", str(mirror.resolve()), True), str(state))
    up_cmd = host.commands[up[0]] if up else []
    check("install: Compose runs the release's recipe with the instance's .env, under the project Compose named",
          up_cmd[:7] == ["docker", "compose", "-p", "veyq-proof", "-f", str(home / "releases/1.3" / vc.COMPOSE_ASSET),
                         "--env-file"], str(up_cmd))
    check("install: the manifest beside the release is the source's own bytes",
          (home / "releases/1.3" / vc.MANIFEST_ASSET).read_bytes() == (mirror / "v1.3" / vc.MANIFEST_ASSET).read_bytes()
          and vc.read_release_manifest(home, "1.3").app == "veyq:local")
    check("install: a second install into the same home is refused",
          "already holds an instance" in refused(vc.cmd_install, vc.parser().parse_args(
              ["--home", str(home), "--source", str(mirror), "install", "--unsigned-local"]), host))

    (home / vc.OVERRIDE_FILE).write_text("services: {}\n")
    check("compose: the operator's override is laid over the recipe when it exists",
          vc.compose(home, "1.3", "p")[5:9] == [str(home / "releases/1.3" / vc.COMPOSE_ASSET), "-f",
                                               str(home / vc.OVERRIDE_FILE), "--env-file"])
    host = FakeHost(project="veyq-proof")
    with contextlib.redirect_stdout(io.StringIO()):
        rc = vc.main(["--home", str(home), "start"], host)
    started = host.commands[host.ran("up")[0]] if host.ran("up") else []
    check("start: the stack is addressed by the project install recorded, not re-derived",
          rc == 0 and started[2:4] == ["-p", "veyq-proof"], str(started))
    saved = dict(os.environ)
    os.environ.update({"COMPOSE_PROJECT_NAME": "someone-else", "COMPOSE_FILE": "/elsewhere.yml", "KEEP_ME": "1"})
    try:
        env = vc.clean_env({"EXTRA": "2"})
    finally:
        os.environ.clear()
        os.environ.update(saved)
    check("env: a COMPOSE_* variable from the shell never reaches a command veyqctl runs; the rest does",
          not any(k.startswith("COMPOSE_") for k in env) and env.get("KEEP_ME") == "1" and env.get("EXTRA") == "2")

    missing_home = tmp / "home-missing"
    host = FakeHost(local_images={"veyq:local", "veyq-admin:local"})
    with contextlib.redirect_stdout(io.StringIO()):
        rc = vc.main(["--home", str(missing_home), "--source", str(mirror), "install", "--unsigned-local",
                      "--set", "VEYQ_DOMAIN=veyq.test", "--set", "VEYQ_ACME_EMAIL=ops@veyq.test"], host)
    check("install: a variable the recipe requires and nothing gives is refused before `up`",
          rc == 1 and not host.ran("up") and not (missing_home / vc.ENV_FILE).exists())


def test_status() -> None:
    def healthy(*rows) -> bool:
        with contextlib.redirect_stdout(io.StringIO()):
            return vc.report(FakeHost(ps=rows), "p")
    one_shot = {"ID": "c2", "Service": "volume-ownership", "State": "exited", "Health": "", "ExitCode": 0, "_restart": "no"}
    app = {"ID": "c1", "Service": "app", "State": "running", "Health": "healthy", "ExitCode": 0, "_restart": "unless-stopped"}
    check("status: a running healthy service and a finished one-shot are healthy", healthy(app, one_shot))
    check("status: a long-running service stopped with exit 0 is NOT healthy (stop leaves exactly that)",
          not healthy({**app, "State": "exited"}, one_shot))
    check("status: a one-shot that failed is not healthy", not healthy(app, {**one_shot, "ExitCode": 1}))
    check("status: `starting` / `unhealthy` are not healthy",
          not healthy({**app, "Health": "starting"}) and not healthy({**app, "Health": "unhealthy"}))
    check("status: a project with no containers is not healthy", not healthy())


# ---- phase 3: upgrade, backup, restore, self-update ---------------------------------------------------------------

INSTALL_SETS = ["--set", "VEYQ_DOMAIN=veyq.test", "--set", "VEYQ_ACME_EMAIL=ops@veyq.test", "--set", "PLAIN_REQUIRED=1",
                "--set", "QUOTED=q", "--set", "ADMIN_PORT=18081"]


def upgrade_mirror(root: pathlib.Path) -> pathlib.Path:
    """v1.3 → v1.7 → v1.12 → v2.1 (entered from 1.12), and a prerelease v2.2; each release names images of its own."""
    for tag, pre in (("v1.3", False), ("v1.7", False), ("v1.12", False), ("v2.1", False), ("v2.2", True)):
        write_release(root, tag, prerelease=pre, app=f"veyq:{tag[1:]}", admin=f"veyq-admin:{tag[1:]}",
                      entry="1.12" if tag.startswith("v2.") else None)
    return root


def all_images() -> set:
    return {f"{n}:{r}" for n in ("veyq", "veyq-admin") for r in ("1.3", "1.4", "1.7", "1.12", "2.1", "2.2")}


def installed(tmp: pathlib.Path, name: str, mirror: pathlib.Path, pin: str, selector: str) -> pathlib.Path:
    """An instance installed at `pin` on the stand-in host, whose operator then set VEYQ_RELEASE to `selector`."""
    home = tmp / name
    with contextlib.redirect_stdout(io.StringIO()):
        rc = vc.main(["--home", str(home), "--source", str(mirror), "install", "--unsigned-local", *INSTALL_SETS,
                      "--set", f"VEYQ_RELEASE={pin}"], FakeHost(local_images=all_images(), project="veyq-proof"))
    assert rc == 0, f"install of {pin} failed"
    env = home / vc.ENV_FILE
    env.write_text("\n".join(f"{vc.RELEASE_KEY}={selector}" if line.startswith(f"{vc.RELEASE_KEY}=") else line
                             for line in env.read_text().splitlines()) + "\n")
    return home


def upgrade(home: pathlib.Path, host: FakeHost, *extra: str) -> tuple:
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        rc = vc.main(["--home", str(home), "upgrade", *extra], host)
    return rc, out.getvalue()


def first_of(host: FakeHost):
    """The index of the first recorded command containing all the words, or a sentinel past every command."""
    return lambda *words: (host.ran(*words) or [10 ** 6])[0]


def test_upgrade_selector(tmp: pathlib.Path) -> None:
    mirror = upgrade_mirror(tmp / "mirror-up")
    keys = tmp / "keys-up"
    keys.mkdir()
    out_of = {}
    for selector, want in (("1.3", "1.3"), ("1", "1.12"), ("latest", "1.12"), ("2", "1.12"), ("1.7", "1.7")):
        home = installed(tmp, f"home-up-{selector}", mirror, "1.3", selector)
        host = FakeHost(local_images=all_images(), project="veyq-proof")
        rc, out = upgrade(home, host, "--unsigned-local", "--key-into", str(keys / f"{selector}.key"))
        out_of[selector] = out
        check(f"upgrade: VEYQ_RELEASE={selector}, running 1.3, takes {want}"
              + (" — nothing to do, nothing run" if want == "1.3" else ""),
              rc == 0 and vc.read_state(home).release == want and (want != "1.3" or not host.commands), out[-400:])
    home = tmp / "home-up-latest"
    check("upgrade: a run that stops on 1.12, the last of major 1, says the next `upgrade` enters major 2",
          "selects 2.1" in out_of["latest"] and "the next `upgrade` enters major 2" in out_of["latest"].splitlines()[-1],
          out_of["latest"][-400:])
    host = FakeHost(local_images=all_images(), project="veyq-proof")
    rc, out = upgrade(home, host, "--unsigned-local", "--key-into", str(keys / "latest-2.key"))
    check("upgrade: that next `upgrade` from 1.12 enters major 2 at its newest (2.1, the prerelease 2.2 skipped)",
          rc == 0 and vc.read_state(home).release == "2.1" and "enters major" not in out, out[-400:])
    home = installed(tmp, "home-check", mirror, "1.3", "latest")
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        rc = vc.main(["--home", str(home), "check", "--unsigned-local"],
                     FakeHost(local_images=all_images(), project="veyq-proof"))
    check("check: from 1.3 with `latest`, `upgrade` would take 1.12 and the one after it enters major 2",
          rc == 0 and "would take release 1.12" in out.getvalue()
          and "the `upgrade` after that one enters major 2" in out.getvalue(), out.getvalue()[-400:])
    home = installed(tmp, "home-up-down", mirror, "1.12", "1.3")
    host = FakeHost(local_images=all_images(), project="veyq-proof")
    rc, out = upgrade(home, host, "--unsigned-local", "--key-into", str(keys / "down.key"))
    check("upgrade: VEYQ_RELEASE pinned BELOW the running release is refused (no downgrade), nothing run",
          rc == 1 and "older than the running" in out and not host.commands, out[-300:])


def test_upgrade_order(tmp: pathlib.Path) -> None:
    mirror = upgrade_mirror(tmp / "mirror-order")
    home = installed(tmp, "home-order", mirror, "1.3", "1")
    key = tmp / "order.key"
    host = FakeHost(local_images=all_images(), project="veyq-proof")
    rc, out = upgrade(home, host, "--unsigned-local", "--key-into", str(key))
    check("upgrade: exits 0 on the stand-in host", rc == 0, out[-600:])
    first = first_of(host)
    order = [first("image", "inspect", "veyq:1.12"),                           # 2. both images verified
             first("config", "--quiet", str(home / ".env.next")),               # 3. Compose checks the staged .env
             first("stop", "app"),                                              # 4. the app stops,
             first("docker", "cp"),                                             #    leg (c) comes out,
             first("capture"),                                                  #    the backup,
             first("verify", "--key-from"),                                     #    checked,
             first("up", str(home / "releases/1.12" / vc.COMPOSE_ASSET))]       #    switch and up
    check("upgrade: ADR 0096 decision 5's order — verify, the template, then stop, back up, check, switch, up",
          order == sorted(order) and order[-1] < 10 ** 6, str(order))
    check("upgrade: nothing stops before step 4", not any("stop" in c for c in host.commands[:first("stop", "app")]))
    values = vc.env_values((home / vc.ENV_FILE).read_text())
    check("upgrade: .env switched to the new image lines; the operator's values and the kept keys carried over",
          values.get("VEYQ_IMAGE") == "veyq:1.12" and values.get("VEYQ_ADMIN_IMAGE") == "veyq-admin:1.12"
          and values.get("VEYQ_DOMAIN") == "veyq.test" and values.get("ADMIN_PORT") == "18081"
          and values.get(vc.RELEASE_KEY) == "1" and not (home / ".env.next").exists())
    check("upgrade: the previous release's directory stays",
          (home / "releases/1.3" / vc.COMPOSE_ASSET).is_file() and vc.read_state(home).release == "1.12")
    capture = host.commands[first("capture")]
    tool_env = host.envs[first("capture")]
    into = capture[capture.index("--into") + 1]
    check("upgrade: the backup is the RUNNING release's tool, into backups/<stamp>-before-<new>",
          capture[1] == str(home / "releases/1.3" / vc.BACKUP_ASSET) and into.startswith(str(home / vc.BACKUPS_DIR))
          and into.endswith("-before-1.12") and capture[capture.index("--key-into") + 1] == str(key.absolute())
          and capture[capture.index("--docker-network") + 1] == "veyq-proof_edge", str(capture))
    check("upgrade: the tool's Compose is pointed at THIS instance, its credentials in the environment",
          tool_env.get("COMPOSE_PROJECT_NAME") == "veyq-proof" and tool_env.get("COMPOSE_ENV_FILES") == str(home / ".env")
          and tool_env.get("VEYQ_BACKUP_DB_PASSWORD") == DB_ROOT
          and tool_env.get("VEYQ_BUNDLED_STORE_ADMIN_TOKEN") == STORE_TOKEN)
    check("upgrade: no secret in any argv veyqctl started",
          not any(secret in arg for c in host.commands for arg in c for secret in (DB_ROOT, STORE_TOKEN)))
    key_copy = pathlib.Path(capture[capture.index("--config-secret-file") + 1])
    check("upgrade: leg (c) is copied out into the home, and gone after the capture",
          str(key_copy).startswith(str(home)) and not key_copy.exists())
    check("upgrade: every printed line of an --unsigned-local upgrade says nothing was verified",
          all(line.startswith("veyqctl [--unsigned-local: NOTHING VERIFIED]") for line in out.splitlines() if line))


def test_upgrade_refusals(tmp: pathlib.Path) -> None:
    mirror = upgrade_mirror(tmp / "mirror-ref")
    home = installed(tmp, "home-ref", mirror, "1.3", "1")
    before = ((home / vc.ENV_FILE).read_bytes(), (home / vc.STATE_FILE).read_bytes())

    def untouched(h: pathlib.Path = home, was: tuple = before) -> bool:
        return ((h / vc.ENV_FILE).read_bytes(), (h / vc.STATE_FILE).read_bytes()) == was and not (h / ".env.next").exists()

    host = FakeHost(local_images=all_images())
    rc, out = upgrade(home, host, "--unsigned-local")
    check("upgrade: --key-into omitted is refused before ANY command runs",
          rc == 1 and "--key-into" in out and not host.commands and untouched(), out[-300:])
    (tmp / "taken.key").write_text("an earlier capture's key")
    host = FakeHost(local_images=all_images())
    rc, out = upgrade(home, host, "--unsigned-local", "--key-into", str(tmp / "taken.key"))
    check("upgrade: an existing --key-into is refused before any command", rc == 1 and "exists" in out
          and not host.commands)
    host = FakeHost(local_images=all_images())
    rc, out = upgrade(home, host, "--unsigned-local", "--key-into", str(home / vc.BACKUPS_DIR / "k.key"))
    check("upgrade: --key-into inside backups/ (beside the dumps) is refused", rc == 1 and "beside the dumps" in out
          and not host.commands)
    if os.geteuid() != 0:  # root searches whatever the mode
        shut = tmp / "shut-keys"
        shut.mkdir()
        for mode, simulated in (("this python", contextlib.nullcontext()), ("py3.14", path_tests_as_in_python_3_14())):
            host = FakeHost(local_images=all_images())
            os.chmod(shut, 0o000)
            try:
                with simulated:
                    rc, out = upgrade(home, host, "--unsigned-local", "--key-into", str(shut / "k.key"))
            except OSError as e:
                rc, out = -1, f"traceback: {e!r}"
            finally:
                os.chmod(shut, 0o700)
            check(f"[{mode}] upgrade: a --key-into in a directory veyqctl cannot search is refused before any command, "
                  f"never taken for a new file", rc == 1 and "cannot look into" in out and not host.commands
                  and untouched(), out[-300:])

    host = FakeHost(local_images=all_images(), project="veyq-proof", fail_tool="capture")
    rc, out = upgrade(home, host, "--unsigned-local", "--key-into", str(tmp / "fail.key"))
    last_up = host.commands[host.ran("up")[-1]] if host.ran("up") else []
    check("upgrade: a failed backup switches nothing and starts the RUNNING release again",
          rc == 1 and untouched() and str(home / "releases/1.3" / vc.COMPOSE_ASSET) in last_up, out[-400:])

    mirror2 = tmp / "mirror-new-var"
    write_release(mirror2, "v1.3", app="veyq:1.3", admin="veyq-admin:1.3")
    write_release(mirror2, "v1.4", app="veyq:1.4", admin="veyq-admin:1.4",
                  files={vc.COMPOSE_ASSET: COMPOSE + "      G: ${VEYQ_NEW:?set VEYQ_NEW}\n      H: ${ONLY_COMPOSE:?x}\n",
                         vc.TEMPLATE_ASSET: TEMPLATE + "VEYQ_NEW=\nNEW_WITH_EXAMPLE=example.org\n",
                         vc.BACKUP_ASSET: "# backup tool\n", vc.SELF_ASSET: "# veyqctl\n", "garage.toml": "# g\n"})
    home2 = installed(tmp, "home-new-var", mirror2, "1.3", "1")
    before2 = ((home2 / vc.ENV_FILE).read_bytes(), (home2 / vc.STATE_FILE).read_bytes())
    host = FakeHost(local_images=all_images(), project="veyq-proof")
    rc, out = upgrade(home2, host, "--unsigned-local", "--key-into", str(tmp / "nv.key"))
    named = [k for k in ("VEYQ_NEW", "NEW_WITH_EXAMPLE", "ONLY_COMPOSE") if k in out]
    check("upgrade: every value the new template or recipe requires and .env lacks is refused, named — a "
          "template's example value is never taken for one",
          rc == 1 and named == ["VEYQ_NEW", "NEW_WITH_EXAMPLE", "ONLY_COMPOSE"], out[-500:])
    check("upgrade: …before anything stops — no stop, no backup, .env and state untouched",
          not host.ran("stop") and not host.ran("capture") and untouched(home2, before2))


def test_backup_restore(tmp: pathlib.Path) -> None:
    mirror = upgrade_mirror(tmp / "mirror-br")
    home = installed(tmp, "home-br", mirror, "1.3", "1.3")
    out = io.StringIO()
    host = FakeHost(local_images=all_images(), project="veyq-proof")
    with contextlib.redirect_stdout(out):
        rc = vc.main(["--home", str(home), "backup"], host)
    check("backup: --key-into omitted is refused before any command", rc == 1 and not host.commands)
    with contextlib.redirect_stdout(out):
        rc = vc.main(["--home", str(home), "backup", "--key-into", str(tmp / "b.key")], host)
    first = first_of(host)
    steps = [first("stop", "app"), first("docker", "cp"), first("capture"), first("verify", "--key-from"), first("up")]
    check("backup: stop the app, leg (c) out, capture, check it, the stack back up — in that order",
          rc == 0 and steps == sorted(steps) and steps[-1] < 10 ** 6, str(steps))

    tool = home / "releases/1.3" / vc.BACKUP_ASSET
    tool.write_text("# not the tool the manifest names\n")
    host = FakeHost(local_images=all_images(), project="veyq-proof")
    with contextlib.redirect_stdout(out):
        rc = vc.main(["--home", str(home), "backup", "--key-into", str(tmp / "b2.key")], host)
    check("backup: a release's backup tool that changed on disk is refused, before the app stops",
          rc == 1 and not host.ran("stop") and not host.ran("capture"))
    tool.write_text("# backup tool\n")

    capture_dir = tmp / "a-capture"
    host = FakeHost(local_images=all_images(), project="veyq-proof")
    with contextlib.redirect_stdout(out):
        rc = vc.main(["--home", str(home), "restore", str(capture_dir), "--domain", "family.veyq.test",
                      "--key-from", str(tmp / "b.key")], host)
    first = first_of(host)
    ran = host.commands[first("restore")] if host.ran("restore") else []
    place = host.commands[first("run", "--rm", "app", "place-key")] if host.ran("run", "--rm", "app", "place-key") else []
    check("restore: the running release's tool, onto the tenant domain given, with the key it was given",
          rc == 0 and ran[1] == str(tool) and ran[ran.index("--domain") + 1] == "family.veyq.test"
          and ran[ran.index("--key-from") + 1] == str((tmp / "b.key").absolute()), str(ran))
    check("restore: leg (c) goes into the app's volume through a one-off app container, on stdin, never an argv",
          place[:3] == ["docker", "compose", "-p"] and "--no-deps" in place
          and place[-1] == "/var/lib/veyq/config-secret.key" and host.stdins == [b"restored-key"]
          and not any("restored-key" in arg for arg in place), str(place))
    # ADR 0118: the image is distroless — a one-off container runs the image's own commands on its `java`, never a shell.
    check("restore: the key is placed by the image's own command on its java, never through a shell",
          place[place.index("--entrypoint") + 1:place.index("place-key")] == ["java", "app", "-jar",
                                                                               "/opt/veyq/image-commands.jar"]
          and not any(arg.endswith("sh") for arg in place), str(place))
    steps = [first("stop", "app"), first("restore"), first("run", "--rm", "app", "place-key"), first("up")]
    check("restore: stop the app, restore, place the key, up — in that order", steps == sorted(steps), str(steps))
    check("restore: the key's copy in the home is gone afterwards",
          not any(p.name.startswith(".key-") for p in home.iterdir()))
    host = FakeHost(local_images=all_images(), project="veyq-proof", fail_tool="restore")
    with contextlib.redirect_stdout(out):
        rc = vc.main(["--home", str(home), "restore", str(capture_dir), "--domain", "family.veyq.test",
                      "--key-from", str(tmp / "b.key")], host)
    check("restore: a failed restore leaves the app STOPPED — no up on a half-restored instance",
          rc == 1 and not host.ran("up") and not host.ran("run", "--rm", "app", "place-key"))


OWN_STORE = {"VEYQ_BACKUP_STORE_ENDPOINT": "https://s3.example.org", "VEYQ_BACKUP_STORE_ACCESS_KEY": "own-access",
             "VEYQ_BACKUP_STORE_SECRET_KEY": "own-secret-in-shell"}


def test_backup_without_garage(tmp: pathlib.Path) -> None:
    """ADR 0064: a recipe whose override leaves the bundled store out is backed up with the own store's pair from the
    shell — on Docker's default network, with no bundled pair — and refused, naming the pair, before anything stops."""
    mirror = upgrade_mirror(tmp / "mirror-ng")
    home = installed(tmp, "home-ng", mirror, "1.3", "1.3")
    saved = dict(os.environ)
    try:
        for key in OWN_STORE:
            os.environ.pop(key, None)
        os.environ["VEYQ_BACKUP_STORE_ENDPOINT"] = "https://s3.example.org"  # one of three: the other two are named
        host = FakeHost(local_images=all_images(), project="veyq-ng", garage=False)
        rc, out = run_quiet(["--home", str(home), "backup", "--key-into", str(tmp / "ng0.key")], host)
        check("no garage: `backup` without the own store's pair is refused before anything stops, naming what is missing",
              rc == 1 and not host.ran("stop") and not host.ran("capture") and "VEYQ_BACKUP_STORE_ACCESS_KEY" in out
              and "VEYQ_BACKUP_STORE_SECRET_KEY" in out and "VEYQ_BACKUP_STORE_ENDPOINT" not in out, out)
        host = FakeHost(local_images=all_images(), project="veyq-ng", garage=False)
        rc, out = run_quiet(["--home", str(home), "backup", "prune"], host)
        check("no garage: `backup prune` reads only the database and needs no store pair", rc == 0 and host.ran("prune"), out)

        os.environ.update(OWN_STORE)
        host = FakeHost(local_images=all_images(), project="veyq-ng", garage=False)
        rc, out = run_quiet(["--home", str(home), "backup", "--key-into", str(tmp / "ng1.key")], host)
        first = first_of(host)
        ran = host.commands[first("capture")] if host.ran("capture") else []
        env = host.envs[first("capture")] if host.ran("capture") else {}
        check("no garage, the pair in the shell: the capture runs, its rclone container on Docker's default network",
              rc == 0 and ran[ran.index("--docker-network") + 1] == vc.DEFAULT_NETWORK, out + str(ran))
        check("no garage: no bundled pair is handed to the tool", not any(k.startswith("VEYQ_BUNDLED_STORE_") for k in env),
              str(sorted(env)))

        # The control on the same predicate: with a `garage` service, the same shell changes nothing — the store's
        # own network and the bundled pair, as before.
        host = FakeHost(local_images=all_images(), project="veyq-ng")
        rc, out = run_quiet(["--home", str(home), "backup", "--key-into", str(tmp / "ng2.key")], host)
        first = first_of(host)
        ran = host.commands[first("capture")] if host.ran("capture") else []
        env = host.envs[first("capture")] if host.ran("capture") else {}
        check("with garage: the store's network and the bundled pair, the shell's own pair notwithstanding",
              rc == 0 and ran[ran.index("--docker-network") + 1] == "veyq-ng_edge"
              and env.get("VEYQ_BUNDLED_STORE_ADMIN_TOKEN") == STORE_TOKEN, out + str(ran))
    finally:
        os.environ.clear()
        os.environ.update(saved)


def with_env(home: pathlib.Path, **values: str) -> None:
    """Lines an operator adds to `.env` (or uncomments from the template)."""
    with (home / vc.ENV_FILE).open("a") as f:
        f.writelines(f"{k}={v}\n" for k, v in values.items())


def run_quiet(argv: list, host: FakeHost) -> tuple:
    out = io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
        try:
            rc = vc.main(argv, host)
        except SystemExit as e:  # argparse: a flag this veyqctl does not know
            rc = e.code or 2
    return rc, out.getvalue()


POOL_SECRET = "pool-secret-in-env-file"


def test_pool(tmp: pathlib.Path) -> None:
    """ADR 0096 decisions 5 and 6, ADR 0056 §3: the pool `.env` names, the early copy, the warning (V1–V4)."""
    mirror = upgrade_mirror(tmp / "mirror-pool")
    unhealthy = ({**FakeHost.HEALTHY[0], "Health": "unhealthy"}, FakeHost.HEALTHY[1])

    # V1 — status warns while the bundled store has no pool; its exit code stays the health verdict.
    home = installed(tmp, "home-status", mirror, "1.3", "1.3")
    for label, host_kw, pool, rc_want, warns in (
            ("bundled, no pool, healthy: the warning, exit 0", {}, None, 0, True),
            ("no store chosen yet (none): the warning", {"store_in_use": "none"}, None, 0, True),
            ("a store setting it cannot read: says so, and the warning", {"store_in_use": None}, None, 0, True),
            ("a store of your own: no warning", {"store_in_use": "own"}, None, 0, False),
            ("unhealthy, bundled, no pool: exit 1 and the warning", {"ps": unhealthy}, None, 1, True),
    ):
        rc, out = run_quiet(["--home", str(home), "status"], FakeHost(project="veyq-proof", **host_kw))
        check(f"status: {label}", rc == rc_want and ("keeps no object versions" in out) == warns
              and (host_kw.get("store_in_use", "") is not None or "could not be read" in out), out[-400:])
    pooled = installed(tmp, "home-status-pool", mirror, "1.3", "1.3")
    with_env(pooled, VEYQ_BACKUP_OBJECTS_TO=str(tmp / "pool"))
    for label, ps, rc_want in (("healthy", FakeHost.HEALTHY, 0), ("unhealthy", unhealthy, 1)):
        rc, out = run_quiet(["--home", str(pooled), "status"], FakeHost(project="veyq-proof", ps=ps))
        check(f"status (control): a pool named in .env, {label} — no warning, exit {rc_want}",
              rc == rc_want and "keeps no object versions" not in out, out[-300:])
    host = FakeHost(local_images=all_images(), project="veyq-proof")
    rc, out = run_quiet(["--home", str(tmp / "home-install-warn"), "--source", str(mirror), "install",
                         "--unsigned-local", *INSTALL_SETS, "--set", "VEYQ_RELEASE=1.3"], host)
    check("install: ends with the same warning while no pool is named",
          rc == 0 and "keeps no object versions" in out.splitlines()[-1], out[-300:])

    # V2 — backup: the early copy before the stop, the capture after it into the same pool.
    home = installed(tmp, "home-pool-backup", mirror, "1.3", "1.3")
    with_env(home, VEYQ_BACKUP_OBJECTS_TO=str(tmp / "pool-a"))
    host = FakeHost(local_images=all_images(), project="veyq-proof")
    rc, out = run_quiet(["--home", str(home), "backup", "--key-into", str(tmp / "pool-b1.key")], host)
    first = first_of(host)
    early, cap = host.ran("copy-objects"), host.ran("capture")
    capture = host.commands[cap[0]] if cap else []
    check("backup with VEYQ_BACKUP_OBJECTS_TO: copy-objects into it BEFORE the app stops, the capture after with "
          "--objects-to the same pool",
          rc == 0 and len(early) == 1 and early[0] < first("stop", "app") < cap[0]
          and host.commands[early[0]][host.commands[early[0]].index("--objects-to") + 1] == str(tmp / "pool-a")
          and capture[capture.index("--objects-to") + 1] == str(tmp / "pool-a"), out[-400:])
    host = FakeHost(local_images=all_images(), project="veyq-proof")
    rc, out = run_quiet(["--home", str(home), "backup", "--key-into", str(tmp / "pool-b2.key"), "--objects-to",
                         str(tmp / "pool-cli")], host)
    capture = host.commands[host.ran("capture")[0]] if host.ran("capture") else []
    check("backup: --objects-to on the command line wins over .env",
          rc == 0 and capture[capture.index("--objects-to") + 1] == str(tmp / "pool-cli")
          and str(tmp / "pool-a") not in " ".join(" ".join(c) for c in host.commands), out[-300:])
    plain = installed(tmp, "home-no-pool", mirror, "1.3", "1.3")
    host = FakeHost(local_images=all_images(), project="veyq-proof")
    rc, out = run_quiet(["--home", str(plain), "backup", "--key-into", str(tmp / "pool-b3.key")], host)
    check("backup with no pool configured: no copy-objects, no --objects-to",
          rc == 0 and not host.ran("copy-objects") and not any("--objects-to" in c for c in host.commands), out[-300:])
    host = FakeHost(local_images=all_images(), project="veyq-proof")
    rc, out = run_quiet(["--home", str(plain), "backup", "--key-into", str(tmp / "pool-b4.key"), "--objects-to",
                         str(plain / vc.RELEASES_DIR / "pool")], host)
    check("backup: a pool inside releases/ is refused before the app stops",
          rc == 1 and "releases" in out and not host.ran("stop"), out[-300:])
    s3 = installed(tmp, "home-s3-pool", mirror, "1.3", "1.3")
    with_env(s3, VEYQ_BACKUP_OBJECTS_TO="s3:veyq-pool/inst", VEYQ_BACKUP_POOL_ENDPOINT="https://s3.example",
             VEYQ_BACKUP_POOL_ACCESS_KEY="AKpool", VEYQ_BACKUP_POOL_SECRET_KEY=POOL_SECRET)
    host = FakeHost(local_images=all_images(), project="veyq-proof")
    rc, out = run_quiet(["--home", str(s3), "backup", "--key-into", str(tmp / "pool-b5.key")], host)
    tool_calls = host.ran("copy-objects") + host.ran("capture")
    check("backup with an S3 pool: its credentials reach the tool through env, never argv",
          rc == 0 and len(tool_calls) == 2 and all(host.envs[i].get("VEYQ_BACKUP_POOL_SECRET_KEY") == POOL_SECRET
                                                  for i in tool_calls)
          and not any(POOL_SECRET in arg for c in host.commands for arg in c), out[-300:])

    # V3 — upgrade: the same order; a failed early copy stops nothing.
    home = installed(tmp, "home-pool-up", mirror, "1.3", "1")
    with_env(home, VEYQ_BACKUP_OBJECTS_TO=str(tmp / "pool-up"))
    host = FakeHost(local_images=all_images(), project="veyq-proof")
    rc, out = upgrade(home, host, "--unsigned-local", "--key-into", str(tmp / "pool-u1.key"))
    first = first_of(host)
    order = [first("config", "--quiet"), first("copy-objects"), first("stop", "app"), first("capture"),
             first("verify", "--key-from"), first("up", str(home / "releases/1.12" / vc.COMPOSE_ASSET))]
    capture = host.commands[first("capture")] if host.ran("capture") else []
    check("upgrade with a pool: template checked, copy-objects, stop, capture --objects-to, check, switch, up",
          rc == 0 and order == sorted(order) and order[-1] < 10 ** 6
          and capture[capture.index("--objects-to") + 1] == str(tmp / "pool-up"), f"{order} {out[-300:]}")
    home = installed(tmp, "home-pool-up-fail", mirror, "1.3", "1")
    with_env(home, VEYQ_BACKUP_OBJECTS_TO=str(tmp / "pool-up"))
    before = ((home / vc.ENV_FILE).read_bytes(), (home / vc.STATE_FILE).read_bytes())
    host = FakeHost(local_images=all_images(), project="veyq-proof", fail_tool="copy-objects")
    rc, out = upgrade(home, host, "--unsigned-local", "--key-into", str(tmp / "pool-u2.key"))
    check("upgrade: a failed early copy stops nothing — no stop, no capture, .env and state untouched",
          rc == 1 and not host.ran("stop") and not host.ran("capture") and not (home / ".env.next").exists()
          and ((home / vc.ENV_FILE).read_bytes(), (home / vc.STATE_FILE).read_bytes()) == before, out[-300:])

    # V4 — restore --objects-from: absolute, and an S3 pool's credentials through env.
    host = FakeHost(local_images=all_images(), project="veyq-proof")
    here = os.getcwd()
    os.chdir(tmp)
    expected = str(pathlib.Path(os.getcwd()) / "relative-pool")  # the cwd as the process sees it (/private/var on macOS)
    try:
        rc, out = run_quiet(["--home", str(s3), "restore", str(tmp / "a-capture"), "--domain", "family.veyq.test",
                             "--without-key", "--objects-from", "relative-pool"], host)
    finally:
        os.chdir(here)
    ran = host.commands[host.ran("restore")[0]] if host.ran("restore") else []
    check("restore --objects-from P: the tool is handed --objects-from with P absolute",
          rc == 0 and ran[ran.index("--objects-from") + 1] == expected, str(ran))
    host = FakeHost(local_images=all_images(), project="veyq-proof")
    rc, out = run_quiet(["--home", str(s3), "restore", str(tmp / "a-capture"), "--domain", "family.veyq.test",
                         "--without-key", "--objects-from", "s3:veyq-pool/inst"], host)
    i = host.ran("restore")[0] if host.ran("restore") else 0
    check("restore from an S3 pool: its credentials reach the tool through env, never argv",
          rc == 0 and host.envs[i].get("VEYQ_BACKUP_POOL_SECRET_KEY") == POOL_SECRET
          and not any(POOL_SECRET in arg for c in host.commands for arg in c), out[-300:])
    host = FakeHost(local_images=all_images(), project="veyq-proof")
    rc, out = run_quiet(["--home", str(s3), "restore", str(tmp / "a-capture"), "--domain", "family.veyq.test",
                         "--without-key"], host)
    ran = host.commands[host.ran("restore")[0]] if host.ran("restore") else []
    check("restore without --objects-from never implies .env's pool (objects go only into an empty store, by choice)",
          rc == 0 and "--objects-from" not in ran, str(ran))


def test_rollback(tmp: pathlib.Path) -> None:
    """O1 (a): `rollback` in place — every check before anything stops, then ADR 0098 decision 9's order in Compose."""
    mirror = upgrade_mirror(tmp / "mirror-rb")

    def upgraded(name: str) -> tuple:
        home = installed(tmp, name, mirror, "1.3", "1.12")
        rc, _ = upgrade(home, FakeHost(local_images=all_images(), project="veyq-proof"), "--unsigned-local",
                        "--key-into", str(tmp / f"{name}.key"))
        assert rc == 0 and vc.read_state(home).release == "1.12"
        capture = home / vc.BACKUPS_DIR / "20261017T000000Z-before-1.12"
        capture.mkdir(parents=True)
        (capture / "manifest.json").write_text(json.dumps({"app": {"version": "1.3"}, "domains": ["family.veyq.test"]}))
        if not (tmp / f"{name}.key").exists():  # the stand-in capture wrote no key; the operator kept one
            (tmp / f"{name}.key").write_bytes(b"k")
        return home, capture, tmp / f"{name}.key"

    home, capture, key = upgraded("home-rb")
    host = FakeHost(local_images=all_images(), project="veyq-proof")
    rc, out = run_quiet(["--home", str(home), "rollback", str(capture), "--key-from", str(key), "--yes"], host)
    first = first_of(host)
    old_tool = str(home / "releases/1.3" / vc.BACKUP_ASSET)
    order = [first("verify", "--against-store"), first("stop", "app"), first("empty-database", "--yes"),
             first("up", str(home / "releases/1.3" / vc.COMPOSE_ASSET))]
    restore_at = first("restore")
    order += [max(i for i in host.ran("stop", "app") if i < restore_at) if host.ran("stop", "app") else 10 ** 6,
              restore_at, first("run", "--rm", "app", "place-key"), host.ran("up")[-1] if host.ran("up") else 10 ** 6]
    restore = host.commands[restore_at] if host.ran("restore") else []
    check("rollback: verify against the live store, stop, empty the database, start the capture's release once, "
          "stop, restore with the check only, place the key, start — in that order",
          rc == 0 and order == sorted(order) and len(set(order)) == len(order) and order[-1] < 10 ** 6
          and "--objects-from" not in restore and restore[1] == old_tool
          and restore[restore.index("--domain") + 1] == "family.veyq.test", f"{order} {out[-400:]}")
    values = vc.env_values((home / vc.ENV_FILE).read_text())
    check("rollback: .env's image lines and the state name the capture's release again; the newer release stays",
          vc.read_state(home).release == "1.3" and values.get("VEYQ_IMAGE") == "veyq:1.3"
          and (home / "releases/1.12").is_dir() and not (home / ".env.rollback").exists(), str(values))

    for label, argv_extra, host_kw, prep, said in (
            ("the capture's release no longer in releases/", ["--yes"], {}, "drop-release", "no longer in the home"),
            ("a capture that does not verify against the live store", ["--yes"], {"fail_tool": "verify"}, None,
             "verify"),
            ("no terminal to confirm and no --yes", [], {}, None, "pass --yes"),
    ):
        home, capture, key = upgraded(f"home-rb-{label[:12].replace(' ', '-')}")
        if prep == "drop-release":
            shutil.rmtree(home / "releases/1.3")
        before = ((home / vc.ENV_FILE).read_bytes(), (home / vc.STATE_FILE).read_bytes())
        host = FakeHost(local_images=all_images(), project="veyq-proof", **host_kw)
        rc, out = run_quiet(["--home", str(home), "rollback", str(capture), "--key-from", str(key), *argv_extra], host)
        check(f"rollback: refused for {label} — nothing stopped, nothing emptied, .env and state untouched",
              rc == 1 and said in out and not host.ran("stop") and not host.ran("empty-database")
              and ((home / vc.ENV_FILE).read_bytes(), (home / vc.STATE_FILE).read_bytes()) == before
              and not (home / ".env.rollback").exists(), out[-300:])


LEDGER = '{"at":"2026-10-18T12:00:00Z","kind":"STORAGE_KEY","id":"k1"}\n'


def test_retention_and_ledger(tmp: pathlib.Path) -> None:
    """ADR 0056 decision 8 on Compose: every capture past B pruned at `backup`, `upgrade` and `start` and by `backup
    prune`; `status` warning while none is younger than B; the erasure ledger handed to `restore` and `rollback`."""
    mirror = upgrade_mirror(tmp / "mirror-b")
    home = installed(tmp, "home-b", mirror, "1.3", "1.3")
    backups = str((home / vc.BACKUPS_DIR).absolute())

    def prunes(host: FakeHost) -> list:
        return [c for c in host.commands if len(c) > 2 and c[1].endswith(vc.BACKUP_ASSET) and c[2] == "prune"]

    host = FakeHost(local_images=all_images(), project="veyq-proof")
    rc, out = run_quiet(["--home", str(home), "backup", "--key-into", str(tmp / "b-ret.key")], host)
    first = first_of(host)
    ran = prunes(host)
    check("backup: the captures past B are pruned after the capture and its check, before the stack is up again",
          rc == 0 and len(ran) == 1 and ran[0][ran[0].index("--captures") + 1] == backups
          and first("verify", "--key-from") < first("prune") < first("up"), out[-300:])
    check("backup: the prune runs with the tool's database credentials in its environment, never an argv",
          host.envs[first("prune")].get("VEYQ_BACKUP_DB_PASSWORD") == DB_ROOT
          and not any(DB_ROOT in a for a in host.commands[first("prune")]))

    host = FakeHost(local_images=all_images(), project="veyq-proof")
    rc, out = run_quiet(["--home", str(home), "backup", "prune"], host)
    check("backup prune: only the prune — nothing stops, no key is asked for, no capture",
          rc == 0 and len(prunes(host)) == 1 and not host.ran("stop") and not host.ran("capture"), out[-300:])
    host = FakeHost(local_images=all_images(), project="veyq-proof", fail_tool="prune")
    rc, out = run_quiet(["--home", str(home), "backup", "prune"], host)
    check("backup prune: a prune that fails is the command's failure", rc == 1, out[-300:])

    host = FakeHost(local_images=all_images(), project="veyq-proof")
    rc, out = run_quiet(["--home", str(home), "start"], host)
    first = first_of(host)
    check("start: the stack up, then the prune", rc == 0 and first("up") < first("prune") < 10 ** 6, out[-300:])
    host = FakeHost(local_images=all_images(), project="veyq-proof", fail_tool="prune")
    rc, out = run_quiet(["--home", str(home), "start"], host)
    check("start: a failed prune is said and the start stands", rc == 0 and "were not pruned" in out, out[-300:])

    up_home = installed(tmp, "home-b-up", mirror, "1.3", "1.12")
    host = FakeHost(local_images=all_images(), project="veyq-proof")
    rc, out = upgrade(up_home, host, "--unsigned-local", "--key-into", str(tmp / "b-up.key"))
    first = first_of(host)
    check("upgrade: the running release's tool prunes after the backup, before the switch's up",
          rc == 0 and first("capture") < first("prune") < host.ran("up")[-1]
          and host.commands[first("prune")][1] == str(up_home / "releases/1.3" / vc.BACKUP_ASSET), out[-300:])

    for label, fresh, warns in (("no capture younger than B: the warning", False, True),
                                ("a capture younger than B: no warning", True, False),
                                ("B unreadable: says so, no warning", None, False)):
        rc, out = run_quiet(["--home", str(home), "status"], FakeHost(project="veyq-proof", fresh=fresh))
        check(f"status: {label}, exit code the health verdict",
              rc == 0 and ("is younger than B" in out) == warns and (fresh is not None or "could not be read" in out),
              out[-400:])

    capture_dir = tmp / "a-capture-b"
    for label, ledger in (("the ledger the app's volume holds", LEDGER), ("no ledger in the volume", None)):
        host = FakeHost(local_images=all_images(), project="veyq-proof", ledger=ledger)
        rc, out = run_quiet(["--home", str(home), "restore", str(capture_dir), "--domain", "family.veyq.test",
                             "--key-from", str(tmp / "b-ret.key")], host)
        first = first_of(host)
        restore = host.commands[first("restore")] if host.ran("restore") else []
        read = host.commands[first("read-file")] if host.ran("read-file") else []
        handed = restore[restore.index("--erasures") + 1] if "--erasures" in restore else None
        check(f"restore: {label} — read from beside the key through a one-off app container, before the restore",
              rc == 0 and read[-1] == "/var/lib/veyq/erasures.log" and "--no-deps" in read
              and read[read.index("--entrypoint") + 1] == "java" and not any(arg.endswith("sh") for arg in read)
              and first("stop", "app") < first("read-file") < first("restore"), str(read))
        check(f"restore: {label} — " + ("handed to the tool as --erasures" if ledger else "no --erasures, the tool "
                                        "checks as before"),
              (handed is not None) == (ledger is not None), str(restore))

    def upgraded(name: str) -> tuple:
        rb = installed(tmp, name, mirror, "1.3", "1.12")
        rc, _ = upgrade(rb, FakeHost(local_images=all_images(), project="veyq-proof"), "--unsigned-local",
                        "--key-into", str(tmp / f"{name}.key"))
        assert rc == 0
        cap = rb / vc.BACKUPS_DIR / "20261017T000000Z-before-1.12"
        cap.mkdir(parents=True, exist_ok=True)
        (cap / "manifest.json").write_text(json.dumps({"app": {"version": "1.3"}, "domains": ["family.veyq.test"]}))
        if not (tmp / f"{name}.key").exists():  # the stand-in capture wrote no key; the operator kept one
            (tmp / f"{name}.key").write_bytes(b"k")
        return rb, cap, tmp / f"{name}.key"
    rb, cap, key = upgraded("home-b-rb")
    host = FakeHost(local_images=all_images(), project="veyq-proof", ledger=LEDGER)
    rc, out = run_quiet(["--home", str(rb), "rollback", str(cap), "--key-from", str(key), "--yes"], host)
    first = first_of(host)
    verify = host.commands[first("verify", "--against-store")] if host.ran("verify", "--against-store") else []
    restore = host.commands[first("restore")] if host.ran("restore") else []
    check("rollback: the ledger read before anything stops, handed to the verify against the live store and the restore",
          rc == 0 and first("read-file") < first("verify", "--against-store") < first("stop", "app")
          and "--erasures" in verify and "--erasures" in restore
          and not any(p.name.startswith(".ledger-") for p in rb.iterdir()), out[-400:])


def test_self_update(tmp: pathlib.Path) -> None:
    mirror = upgrade_mirror(tmp / "mirror-su")
    home = installed(tmp, "home-su", mirror, "1.3", "1.3")
    bin_dir = tmp / "bin"
    bin_dir.mkdir()
    target = bin_dir / "veyqctl"
    target.write_text("# an older veyqctl\n")
    target.chmod(0o750)
    asset = home / "releases/1.3" / vc.SELF_ASSET
    saved = vc.SELF
    vc.SELF = target
    try:
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            rc = vc.main(["--home", str(home), "self-update"], FakeHost())
        check("self-update: replaced by the installed release's veyqctl, its mode kept",
              rc == 0 and target.read_bytes() == asset.read_bytes() and stat.S_IMODE(target.stat().st_mode) == 0o750,
              out.getvalue()[-300:])
        with contextlib.redirect_stdout(out):
            rc = vc.main(["--home", str(home), "self-update"], FakeHost())
        check("self-update: a second run finds nothing to replace", rc == 0 and "already" in out.getvalue())
        asset.write_text("# tampered after it was fetched\n")
        target.write_text("# an older veyqctl\n")
        with contextlib.redirect_stdout(out):
            rc = vc.main(["--home", str(home), "self-update"], FakeHost())
        check("self-update: an installed veyqctl that no longer matches its manifest is refused, nothing replaced",
              rc == 1 and target.read_text() == "# an older veyqctl\n")
    finally:
        vc.SELF = saved


# ---- the manifest's signature (cr-signed-release-manifest) ---------------------------------------------------------

SIGNED_TARGETS = {f"ghcr.io/raddatzk/veyq@{DIGEST_A}", f"ghcr.io/raddatzk/veyq-admin@{DIGEST_B}"}


def signed_mirror(root: pathlib.Path, *tags: str) -> pathlib.Path:
    """Releases as the publish leaves them: images by digest, the manifest's bundle beside it."""
    for tag in tags:
        write_release(root, tag, app=f"ghcr.io/raddatzk/veyq:{tag[1:]}@{DIGEST_A}",
                      admin=f"ghcr.io/raddatzk/veyq-admin:{tag[1:]}@{DIGEST_B}", signed=True)
    return root


def run_main(argv: list, host: FakeHost) -> tuple:
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        rc = vc.main([str(a) for a in argv], host)
    return rc, out.getvalue()


def tamper_one_byte(manifest: pathlib.Path) -> None:
    """One byte of the served manifest changed after its bundle was made (the date's last digit)."""
    raw = manifest.read_bytes()
    changed = raw.replace(b'"date": "2026-10-16"', b'"date": "2026-10-17"', 1)
    assert changed != raw and sum(a != b for a, b in zip(raw, changed)) == 1
    manifest.write_bytes(changed)


def is_cosign(cmd: list) -> bool:
    """A cosign call that verifies something; `cosign version` (the preflight's floor check) trusts nothing."""
    return cmd[0].endswith("cosign") and cmd[1:2] != ["version"]


def image_verifies(host: FakeHost) -> list:
    return [i for i, c in enumerate(host.commands) if is_cosign(c) and c[1] == "verify"]


def install_signed(home: pathlib.Path, mirror: pathlib.Path, host: FakeHost, pin: str = "1.3") -> tuple:
    return run_main(["--home", home, "--source", mirror, "install", *INSTALL_SETS, "--set", f"VEYQ_RELEASE={pin}"], host)


def test_manifest_signature(tmp: pathlib.Path) -> None:
    check("bundle name: veyqctl fetches the manifest's bundle by the name the publish writes",
          getattr(vc, "MANIFEST_BUNDLE_ASSET", None) == BUNDLE)

    # the positive: a signed release installs, the manifest verified first, then the images, then anything else
    mirror = signed_mirror(tmp / "mirror-signed", "v1.3")
    home = tmp / "home-signed"
    host = FakeHost(signed=SIGNED_TARGETS, project="veyq-proof", watch=home)
    rc, out = install_signed(home, mirror, host)
    check("signed install: exits 0 on the stand-in host", rc == 0, out[-600:])
    blob_at, verify_at = host.ran("verify-blob"), image_verifies(host)
    docker_at = [i for i, c in enumerate(host.commands) if c[0] == "docker"]
    check("signed install: `cosign verify-blob` of the manifest before any `cosign verify` of an image, both before "
          "any docker command and before the home exists",
          bool(blob_at and verify_at and docker_at) and max(blob_at) < min(verify_at) and max(verify_at) < min(docker_at)
          and not any(host.watched[i] for i in blob_at + verify_at), str([c[:2] for c in host.commands][:8]))
    argv = host.commands[blob_at[0]] if blob_at else []
    check("signed install: cosign gets exactly `verify-blob <manifest> --bundle <bundle>` and the pinned issuer and "
          "identity, no format flag",
          len(argv) == 9 and argv[:2] == ["/usr/bin/cosign", "verify-blob"]
          and pathlib.Path(argv[2]).name == vc.MANIFEST_ASSET and argv[3] == "--bundle"
          and pathlib.Path(argv[4]).name == BUNDLE
          and argv[5:] == ["--certificate-oidc-issuer", vc.COSIGN_OIDC_ISSUER,
                           "--certificate-identity-regexp", vc.COSIGN_IDENTITY_REGEXP], str(argv))
    check("signed install: the blob cosign checked is byte-identical to the manifest the source served",
          host.blobs == [(mirror / "v1.3" / vc.MANIFEST_ASSET).read_bytes()])
    check("signed install: the bundle is kept verbatim beside the manifest, and the state says verified",
          (home / "releases/1.3" / BUNDLE).is_file()
          and (home / "releases/1.3" / BUNDLE).read_bytes() == (mirror / "v1.3" / BUNDLE).read_bytes()
          and (home / vc.STATE_FILE).is_file() and not vc.read_state(home).unsigned_local)

    # bite (a): one byte of the served manifest changed after the bundle was made
    bad = signed_mirror(tmp / "mirror-tampered", "v1.3")
    tamper_one_byte(bad / "v1.3" / vc.MANIFEST_ASSET)
    home_a = tmp / "home-tampered"
    host = FakeHost(signed=SIGNED_TARGETS, watch=home_a)
    rc, out = install_signed(home_a, bad, host)
    check("bite (a): a manifest changed by one byte after it was signed is refused at `verify-blob`",
          rc == 1 and bool(host.ran("verify-blob")) and "does not verify" in out
          and "Nothing was pulled, written or started" in out, out[-500:])
    check("bite (a): …no docker command, no `cosign verify` of an image, the home not created",
          not any(c[0] == "docker" for c in host.commands) and not image_verifies(host) and not home_a.exists(),
          str([c[:3] for c in host.commands]))

    # bite (b): the bundle absent from the mirror
    nob = signed_mirror(tmp / "mirror-nobundle", "v1.3")
    (nob / "v1.3" / BUNDLE).unlink()
    home_b = tmp / "home-nobundle"
    host = FakeHost(signed=SIGNED_TARGETS, watch=home_b)
    rc, out = install_signed(home_b, nob, host)
    check("bite (b): a release whose source serves no bundle is refused, naming `manifest.json.bundle`, the source and "
          "`--unsigned-local` for a local release — with no cosign call and no home",
          rc == 1 and BUNDLE in out and str(nob.resolve()) in out and "--unsigned-local" in out
          and not any(is_cosign(c) for c in host.commands) and not home_b.exists(), out[-500:])

    # bite (c): unsigned AND malformed (names another release): the refusal is the signature's, never the parse's
    mal = signed_mirror(tmp / "mirror-malformed", "v1.3")
    m = mal / "v1.3" / vc.MANIFEST_ASSET
    doc = json.loads(m.read_text())
    doc["release"] = "1.2"
    m.write_text(json.dumps(doc))
    host = FakeHost(signed=SIGNED_TARGETS)
    rc, out = install_signed(tmp / "home-malformed", mal, host)
    check("bite (c): an unsigned manifest that also names another release is refused by its signature, not its parse "
          "— verification comes before trust",
          rc == 1 and "does not verify" in out and "names release" not in out, out[-400:])

    # cosign's floor: the manifest's bundle is a Sigstore bundle, and below 3.1.3 a legacy one bypasses the identity
    for version, why in (("v3.1.2", "the last release GHSA-fx35-mq7g-6g98 affects"), ("v2.6.5", "cosign 2"),
                         ("", "a cosign whose version cannot be read")):
        host = FakeHost(signed=SIGNED_TARGETS, cosign_version=version)
        rc, out = install_signed(tmp / f"home-cosign-{version or 'none'}", mirror, host)
        check(f"cosign floor: {why} is refused, naming the floor, before `verify-blob` runs",
              rc == 1 and vc.COSIGN_MIN_VERSION in out and "GHSA-fx35-mq7g-6g98" in out and not host.ran("verify-blob"),
              out[-400:])

    # controls: --unsigned-local with a producer-made mirror (no bundle) installs and asks for no bundle; with
    # registry references it is refused as today
    local = tmp / "mirror-local"
    write_release(local, "v1.3", app="veyq:local", admin="veyq-admin:local")
    fetched: list = []
    original = vc.DirSource.fetch

    def spy(self, tag, asset):
        fetched.append(asset)
        return original(self, tag, asset)

    vc.DirSource.fetch = spy
    try:
        host = FakeHost(local_images={"veyq:local", "veyq-admin:local"})
        rc, out = run_main(["--home", tmp / "home-local", "--source", local, "install", "--unsigned-local", *INSTALL_SETS,
                            "--set", "VEYQ_RELEASE=1.3"], host)
    finally:
        vc.DirSource.fetch = original
    check("control: --unsigned-local with a mirror the producer made (no bundle) installs, fetching no bundle and "
          "running no cosign", rc == 0 and BUNDLE not in fetched and not any(is_cosign(c) for c in host.commands),
          out[-400:])
    host = FakeHost(local_images={"veyq:local", "veyq-admin:local"})
    rc, out = run_main(["--home", tmp / "home-local-signed", "--source", mirror, "install", "--unsigned-local",
                        *INSTALL_SETS], host)
    check("control: --unsigned-local with registry references is refused as today",
          rc == 1 and "no registry host and no digest" in out, out[-300:])

    # `verify` names the manifest too
    host = FakeHost(signed=SIGNED_TARGETS)
    rc, out = run_main(["--home", tmp / "home-verify", "--source", mirror, "verify", "--release", "1.3"], host)
    check("verify: the manifest's signature, then both images, and the closing line names the manifest",
          rc == 0 and host.ran("verify-blob") and image_verifies(host)
          and max(host.ran("verify-blob")) < min(image_verifies(host)) and "manifest" in out.splitlines()[-1],
          out[-300:])

    # bite (e): upgrade toward a release whose manifest was changed after it was signed
    up_mirror = signed_mirror(tmp / "mirror-up-signed", "v1.3", "v1.4")
    tamper_one_byte(up_mirror / "v1.4" / vc.MANIFEST_ASSET)
    home_e = tmp / "home-up-signed"
    rc, out = install_signed(home_e, up_mirror, FakeHost(signed=SIGNED_TARGETS, project="veyq-proof"))
    env = home_e / vc.ENV_FILE
    env.write_text(env.read_text().replace(f"{vc.RELEASE_KEY}=1.3", f"{vc.RELEASE_KEY}=1.4"))
    before = (env.read_bytes(), (home_e / vc.STATE_FILE).read_bytes())
    host = FakeHost(signed=SIGNED_TARGETS, project="veyq-proof")
    rc, out = run_main(["--home", home_e, "upgrade", "--key-into", tmp / "up-signed.key"], host)
    check("bite (e): `upgrade` to a manifest changed after it was signed is refused at step 2, every command before "
          "the refusal a read — nothing stopped, fetched or switched",
          rc == 1 and "does not verify" in out and bool(host.ran("verify-blob"))
          and all(c[0].endswith("cosign") and c[1] in ("version", "verify-blob") for c in host.commands)
          and not (home_e / "releases/1.4").exists()
          and (env.read_bytes(), (home_e / vc.STATE_FILE).read_bytes()) == before, out[-500:])

    # an earlier attempt's directory: its manifest must be the one just verified, and a missing bundle is written
    release = home / "releases/1.3"
    source = vc.DirSource(mirror)
    chosen = vc.select(source.releases(), "1.3", "the mirror")
    with contextlib.redirect_stdout(io.StringIO()):
        verified = vc.fetch_manifest(FakeHost(), source, chosen, False)
    (release / BUNDLE).unlink()
    with contextlib.redirect_stdout(io.StringIO()):
        vc.fetch_release(source, chosen, verified, home)
    check("already there: a release directory without the bundle gets the verified one written beside its manifest",
          (release / BUNDLE).is_file() and (release / BUNDLE).read_bytes() == (mirror / "v1.3" / BUNDLE).read_bytes())
    kept = (release / vc.MANIFEST_ASSET).read_bytes()
    tamper_one_byte(release / vc.MANIFEST_ASSET)
    message = refused(vc.fetch_release, source, chosen, verified, home)
    check("already there: a manifest on disk that is not the one just verified is refused, never repaired",
          "just verified" in message and (release / vc.MANIFEST_ASSET).read_bytes() != kept, message)
    (release / vc.MANIFEST_ASSET).write_bytes(kept)

    # bite (d): self-update trusts the manifest on disk only once it verifies against the bundle beside it
    bin_dir = tmp / "bin-signed"
    bin_dir.mkdir()
    target = bin_dir / "veyqctl"
    saved = vc.SELF
    vc.SELF = target
    try:
        release = home / "releases/1.3"
        target.write_text("# an older veyqctl\n")
        host = FakeHost()
        rc, out = run_main(["--home", home, "self-update"], host)
        check("self-update (control): a signed release's veyqctl replaces this one after the manifest verifies",
              rc == 0 and target.read_bytes() == (release / vc.SELF_ASSET).read_bytes() and bool(host.ran("verify-blob")),
              out[-300:])
        target.write_text("# an older veyqctl\n")
        manifest_path, self_path = release / vc.MANIFEST_ASSET, release / vc.SELF_ASSET
        kept = (manifest_path.read_bytes(), self_path.read_bytes())
        self_path.write_text("# a swapped veyqctl\n")
        doc = json.loads(manifest_path.read_text())
        doc["assets"][vc.SELF_ASSET] = sha(self_path.read_bytes())
        manifest_path.write_text(json.dumps(doc))
        rc, out = run_main(["--home", home, "self-update"], FakeHost())
        check("bite (d): self-update with the manifest on disk edited to match a swapped veyqctl is refused, nothing "
              "replaced", rc == 1 and target.read_text() == "# an older veyqctl\n" and "does not verify" in out,
              out[-400:])
        manifest_path.write_bytes(kept[0])
        self_path.write_bytes(kept[1])
        (release / BUNDLE).unlink(missing_ok=True)
        rc, out = run_main(["--home", home, "self-update"], FakeHost())
        check("bite (d): self-update with the bundle gone from disk is refused, nothing replaced",
              rc == 1 and target.read_text() == "# an older veyqctl\n" and BUNDLE in out, out[-400:])
    finally:
        vc.SELF = saved


def test_entry_signed(tmp: pathlib.Path) -> None:
    """A major's entry is a field of a SIGNED manifest, read only once that manifest verified: a mirror that rewrites
    2.0's `majorEntry` to the late fix 1.10 (to route an instance through the path nobody proved) is refused by the
    signature, never routed by it; and an upgrade reads each manifest once, the one it installs included."""
    def mirror(name: str) -> pathlib.Path:
        root = tmp / name
        for tag, entry in (("v1.9", "0.4"), ("v1.10", "0.4"), ("v2.0", "1.9")):
            write_release(root, tag, app=f"ghcr.io/raddatzk/veyq:{tag[1:]}@{DIGEST_A}",
                          admin=f"ghcr.io/raddatzk/veyq-admin:{tag[1:]}@{DIGEST_B}", signed=True, entry=entry)
        return root

    good = vc.DirSource(mirror("mirror-entry-signed"))
    listed = good.releases()
    host = FakeHost(signed=SIGNED_TARGETS)
    manifest_of = vc.manifest_reader(host, good, False)
    with contextlib.redirect_stdout(io.StringIO()):
        target = vc.upgrade_target(listed, (1, 9), vc.select(listed, "latest", "x"), "x",
                                   lambda r: manifest_of(r).major_entry)
        manifest_of(vc.select(listed, "2.0", "x"))
    check("entry signed: from 1.9 the verified 2.0 manifest's entry admits 2.0", target.release == "2.0")
    check("entry signed: the manifest the run installs is the one it routed by, verified once",
          len(host.ran("verify-blob")) == 1, str(host.commands))

    forged = mirror("mirror-entry-forged")
    path = forged / "v2.0" / vc.MANIFEST_ASSET
    path.write_text(path.read_text().replace('"majorEntry": "1.9"', '"majorEntry": "1.10"'))
    source = vc.DirSource(forged)
    host = FakeHost(signed=SIGNED_TARGETS)
    manifest_of = vc.manifest_reader(host, source, False)
    message = refused(lambda: vc.upgrade_target(source.releases(), (1, 10), vc.select(source.releases(), "latest", "x"),
                                                "x", lambda r: manifest_of(r).major_entry))
    check("entry signed: a `majorEntry` rewritten after signing is refused at `verify-blob`, never routed by",
          "does not verify" in message and bool(host.ran("verify-blob")), message)


# ---- the GitOps target and the instance list (ADR 0126, cr-veyqctl-gitops-target) -------------------------------

def pin_of(release: str, role: str) -> str:
    """A release's stand-in digest for one of its four pins: distinct per release and role."""
    return "sha256:" + sha(f"{role} {release}".encode())


CLUSTER_OK = {
    "crd applications.argoproj.io": (0, "customresourcedefinition.apiextensions.k8s.io/applications.argoproj.io\n"),
    "crd gateways.gateway.networking.k8s.io": (0, "v1.5.1"),
    "get gatewayclass traefik": (0, "gatewayclass.gateway.networking.k8s.io/traefik\n"),
    "get gatewayclass -o jsonpath": (0, "traefik traefik.io/gateway-controller\n"),
    "-l app.kubernetes.io/name=traefik": (0, "traefik"),
    "get storageclass": (0, "storageclass.storage.k8s.io/local-path\n"),
    "crd certificates.cert-manager.io": (0, "customresourcedefinition.apiextensions.k8s.io/certificates\n"),
    "app.kubernetes.io/name=cert-manager": (0, "v1.21.3"),
    "get namespace": (0, "namespace/veyq\n"),
    "get secret veyq-secrets": (0, json.dumps({k: "eA==" for k in vc.SECRET_VALUES[vc.SECRETS_SECRET]})),
    "get secret veyq-config-secret-key": (0, json.dumps({vc.CONFIG_SECRET_KEY: "eA=="})),
}


class GitOpsHost(FakeHost):
    """FakeHost plus what the GitOps target starts — git, kubectl, helm, sops, kubeseal — and the registry lookup."""

    def __init__(self, *, top: pathlib.Path, cluster=None, chart_version=None, dirty=False, kubeseal=True,
                 render_fails=False, interrupt: tuple = (), interrupt_at: int = 1, empty_repo=False, **kwargs) -> None:
        super().__init__(**kwargs)
        self.empty_repo = empty_repo  # `git ls-files` lists nothing: a repository with nothing committed yet
        self.interrupt = list(interrupt)  # words of a command at which the terminal "closes" (KeyboardInterrupt), once,
        self.interrupt_at = interrupt_at  # at the n-th such command
        self.top, self.dirty, self.kubeseal, self.render_fails = top, dirty, kubeseal, render_fails
        self.cluster = dict(CLUSTER_OK, **(cluster or {}))
        self.chart_version = chart_version  # what `helm show chart` says; None: the release the digest belongs to
        self.charts: dict[str, str] = {}

    def oci_digest(self, ref: str) -> str:
        self.commands.append(["oci-digest", ref])
        self.watched.append(bool(self.watch and self.watch.exists()))
        release = ref.rsplit(":", 1)[1]
        self.charts[pin_of(release, "chart")] = release
        return pin_of(release, "chart")

    def which(self, name):
        return None if name == "kubeseal" and not self.kubeseal else super().which(name)

    def run(self, cmd, *, check=True, stream=False, env=None, stdin=None):
        if self.interrupt and all(word in cmd for word in self.interrupt):
            self.interrupt_at -= 1
            if self.interrupt_at == 0:
                self.interrupt = []
                raise KeyboardInterrupt
        if cmd[0] not in ("git", "kubectl", "helm", "sops", "kubeseal"):
            return super().run(cmd, check=check, stream=stream, env=env, stdin=stdin)
        self.commands.append(list(cmd))
        self.watched.append(bool(self.watch and self.watch.exists()))
        if stdin is not None:
            self.stdins.append(stdin)
        rc, out = self.answer(cmd)
        if check and rc:
            raise vc.Refused(f"`{' '.join(cmd[:4])} …` exited {rc}")
        return subprocess.CompletedProcess(cmd, rc, out, "stand-in refusal" if rc else "")

    def answer(self, cmd) -> tuple:
        joined = " ".join(cmd)
        if cmd[0] == "git":
            if "--show-toplevel" in cmd:  # as git: `-C` must name a directory that exists; without it, the cwd's
                where = pathlib.Path(cmd[cmd.index("-C") + 1]) if "-C" in cmd else pathlib.Path.cwd()
                return (0, f"{self.top}\n") if where.is_dir() else (128, "")
            if "ls-files" in cmd:
                return 0, "" if self.empty_repo else "apps/argocd/application.yaml\n"
            if "get-url" in cmd:
                return 0, "git@example.org:ops/cluster.git\n"
            if "--abbrev-ref" in cmd:
                return 0, "main\n"
            if "diff" in cmd:
                return (1 if self.dirty else 0), ""
            if "user.email" in cmd:
                return 0, "owner@example.org\n"
            return 0, ""
        if cmd[0] == "kubectl":
            for key, answer in self.cluster.items():
                if key in joined:
                    return answer
            return 0, ""
        if cmd[0] == "helm" and cmd[1] == "show":
            digest = cmd[3].rsplit("@", 1)[1]
            return 0, f"apiVersion: v2\nname: veyq\nversion: {self.chart_version or self.charts.get(digest, '?')}\n"
        if cmd[0] == "helm":
            return (1, "") if self.render_fails else (0, "kind: Deployment\n")
        if cmd[0] == "sops":
            return 0, "apiVersion: v1\nkind: Secret\nstringData:\n    k: ENC[AES256_GCM,data:stand-in]\nsops:\n    age: []\n"
        return 0, "apiVersion: bitnami.com/v1alpha1\nkind: SealedSecret\n"  # kubeseal


def gitops_mirror(root: pathlib.Path, *releases: tuple) -> pathlib.Path:
    """Signed releases whose manifests name all three images by digest, `tools` included."""
    for tag, entry in releases:
        r = tag[1:]

        def with_tools(m, r=r):
            m["images"]["tools"] = f"ghcr.io/raddatzk/veyq-tools:{r}@{pin_of(r, 'tools')}"
        write_release(root, tag, app=f"ghcr.io/raddatzk/veyq:{r}@{pin_of(r, 'app')}",
                      admin=f"ghcr.io/raddatzk/veyq-admin:{r}@{pin_of(r, 'admin')}", signed=True, entry=entry,
                      manifest_edit=with_tools)
    return root


def gitops_signed(*releases: str, without: tuple = ()) -> set:
    names = {"app": "ghcr.io/raddatzk/veyq", "admin": "ghcr.io/raddatzk/veyq-admin",
             "tools": "ghcr.io/raddatzk/veyq-tools", "chart": "ghcr.io/raddatzk/charts/veyq"}
    return {f"{names[role]}@{pin_of(r, role)}" for r in releases for role in names if (r, role) not in without}


INIT = ["--domain", "veyq.example.org", "--gateway-class", "traefik", "--acme-email", "ops@example.org",
        "--gateway-namespace", "traefik", "--gateway-pod-label", "app.kubernetes.io/name=traefik"]


def tree_text(root: pathlib.Path) -> str:
    return "\n".join(p.read_text() for p in sorted(root.rglob("*")) if p.is_file())


def snapshot(d: pathlib.Path) -> dict:
    return {p.name: p.read_bytes() for p in d.iterdir() if p.is_file()}


def test_instances(tmp: pathlib.Path) -> None:
    rc, out = run_main(["instances"], FakeHost())
    check("instances: none listed says so and names /opt/veyq", rc == 0 and vc.DEFAULT_HOME in out, out)
    mirror = upgrade_mirror(tmp / "inst-mirror")
    home = tmp / "inst-compose"
    rc, out = run_main(["--home", home, "--source", mirror, "init", "--compose", "--name", "test", "--unsigned-local",
                        *INSTALL_SETS, "--set", "VEYQ_RELEASE=1.3"],
                       FakeHost(local_images=all_images(), project="veyq-proof"))
    registry = json.loads((vc.config_dir() / vc.REGISTRY_FILE).read_text())
    check("init --compose: installs as `install` does, then lists the home as `test` and makes it current",
          rc == 0 and registry == {"current": "test", "instances": {"test": str(home)}}
          and (home / vc.STATE_FILE).is_file(), out[-600:])
    rc, out = run_main(["--home", tmp / "elsewhere", "--source", mirror, "init", "--compose", "--name", "test",
                        "--unsigned-local", *INSTALL_SETS], FakeHost(local_images=all_images()))
    check("init: a name listed for another path is refused before anything is installed",
          rc == 1 and "listed already" in out and not (tmp / "elsewhere").exists(), out[-400:])
    gitops_home = tmp / "inst-gitops"
    gitops_home.mkdir()
    (gitops_home / vc.GITOPS_FILE).write_text("{}")
    registry["instances"]["cluster"] = str(gitops_home)
    vc.write_registry(registry)
    for verb in ("start", "backup", "rollback", "self-update"):
        argv = ["--instance", "cluster", verb] + (["/tmp/capture", "--key-from", "/tmp/k"] if verb == "rollback" else [])
        host = FakeHost()
        rc, out = run_main(argv, host)
        check(f"a GitOps instance refuses `{verb}`, names what does it there, and runs nothing",
              rc == 1 and "is Compose's" in out and vc.COMPOSE_ONLY[verb][:20] in out and not host.commands, out)
    rc, out = run_main(["--instance", "nope", "status"], FakeHost())
    check("--instance of no listed name is refused, naming `veyqctl instances`", rc == 1 and "instances" in out, out)
    rc, out = run_main(["use", "nope"], FakeHost())
    check("use: an unlisted name is refused", rc == 1 and "no instance named nope" in out, out)
    rc, out = run_main(["use", "cluster"], FakeHost())
    check("use: switches the current instance", rc == 0 and vc.read_registry()["current"] == "cluster", out)
    check("the current instance is the one a command acts on, --home wins over it, --instance acts on another once",
          vc.resolve_home(vc.parser().parse_args(["status"])) == gitops_home
          and vc.resolve_home(vc.parser().parse_args(["--home", "/x", "status"])) == pathlib.Path("/x")
          and vc.resolve_home(vc.parser().parse_args(["--instance", "test", "status"])) == home)
    rc, out = run_main(["--instance", "test", "upgrade", "--push"], FakeHost())
    check("upgrade --push on a Compose home is refused before anything runs", rc == 1 and "GitOps" in out, out)
    rc, out = run_main(["instances"], FakeHost())
    check("instances: each listed with its target and path, the current one marked",
          "* cluster  gitops" in out and "  test  compose" in out, out)
    vc.write_registry({"current": None, "instances": {}})


def test_programs_checked_first(tmp: pathlib.Path) -> None:
    """Every program a command needs is checked before its first question or command, all of them named at once."""
    top = tmp / "programs-repo"
    top.mkdir()

    class Missing(GitOpsHost):
        def __init__(self, missing, installs=False, **kwargs):
            super().__init__(top=top, **kwargs)
            self.missing, self.asked, self.installs, self.waited = set(missing), [], installs, []
            self.asked_at: list[int] = []

        def which(self, name):
            return None if name in self.missing else super().which(name)

        def wait(self, prompt):
            """A terminal where the person installs what is missing, when `installs` says so, and presses Enter."""
            self.waited.append(set(self.missing))
            if self.installs:
                self.missing = set()
            return self.installs

        def prompt(self, question, default="", hint=""):
            self.asked.append(question)
            self.asked_at.append(len(self.commands))  # how many commands had run when it was asked
            return super().prompt(question, default, hint)

    host = Missing({"kubectl", "helm"})
    rc, out = run_main(["init", "--gitops", top / "apps" / "veyq", *INIT, "--secrets", "none"], host)
    check("init --gitops without kubectl and helm: after the setup answers, both named with where to get them, "
          "waited for, refused without a terminal — no cluster question, no command but git's",
          rc == 1 and "kubectl, helm, not on PATH" in out and vc.PROGRAMS["helm"] in out
          and vc.PROGRAMS["kubectl"] in out and host.waited == [{"kubectl", "helm"}]
          and "kubeconfig context" not in host.asked and all(c[0] == "git" for c in host.commands)
          and "brew install kubernetes-cli helm" in out, out[-400:])
    host = Missing({"sops"}, answers={"secrets: sops, external-secrets, sealed-secrets, none": "sops"})
    (top / ".sops.yaml").write_text("creation_rules: []\n")
    rc, out = run_main(["init", "--gitops", top / "apps" / "veyq-sops", *INIT], host)
    check("the secrets answer decides the programs: sops chosen and missing is named in the one check",
          rc == 1 and "needs sops, not on PATH" in out and "kubeconfig context" not in host.asked, out[-400:])
    (top / ".sops.yaml").unlink()
    mirror = gitops_mirror(tmp / "programs-mirror", ("v1.0", None))
    host = Missing({"helm"}, installs=True, signed=gitops_signed("1.0"))
    rc, out = run_main(["--source", mirror, "init", "--gitops", top / "apps" / "veyq-waited", "--name", "waited", *INIT,
                        "--secrets", "none"], host)
    check("a program installed while init waits is found on Enter, and the same run goes on to write veyq's half",
          rc == 0 and host.waited == [{"helm"}] and (top / "apps" / "veyq-waited" / vc.GITOPS_FILE).is_file(),
          out[-400:])
    steps = [i for i, c in enumerate(host.commands) if c[0].endswith("cosign") and "version" not in c
             or c[:2] == ["helm", "template"] or c[:1] == ["sops"]]
    check("no question after the run's first step (the release's verification, the render, the encryption)",
          host.asked and steps and max(host.asked_at) <= steps[0], f"{list(zip(host.asked, host.asked_at))} {steps[:1]}")
    host = Missing({"git"}, answers={"compose or gitops": "gitops"})
    rc, out = run_main(["init"], host)
    check("init with gitops chosen at its prompt and no git is refused before the next question",
          rc == 1 and "needs git, not on PATH" in out and host.asked == ["compose or gitops"], out[-400:])
    host = Missing(set(), cosign_version="v3.1.2")
    rc, out = run_main(["init", "--gitops", top / "apps" / "veyq", *INIT, "--secrets", "none"], host)
    check("init --gitops with a cosign below the floor is refused after the setup answers, before the cluster's",
          rc == 1 and "older than" in out and "kubeconfig context" not in host.asked, out[-400:])
    home = tmp / "programs-home"
    home.mkdir()
    (home / vc.GITOPS_FILE).write_text("{}")
    host = Missing({"helm"})
    rc, out = run_main(["--home", home, "upgrade"], host)
    check("a GitOps verb without helm is refused naming it, before reading the instance",
          rc == 1 and "`upgrade` of a GitOps instance needs helm" in out and not host.commands, out[-400:])
    host = Missing({"docker", "cosign"})
    rc, out = run_main(["--home", tmp / "programs-compose", "install"], host)
    check("a Compose install without docker and cosign is refused naming both, before any command or file",
          rc == 1 and "`install` needs docker, cosign, not on PATH" in out and not host.commands
          and not (tmp / "programs-compose").exists(), out[-400:])
    host = Missing({"cosign"})
    rc, out = run_main(["--home", tmp / "programs-compose", "status"], host)
    check("a Compose command that verifies nothing does not need cosign", "cosign" not in out, out[-400:])


def test_gitops_init(tmp: pathlib.Path) -> None:
    mirror = gitops_mirror(tmp / "g-mirror", ("v1.0", None), ("v1.1", None), ("v2.0", "1.1"))
    signed = gitops_signed("1.0", "1.1", "2.0")
    top = tmp / "repo"
    top.mkdir()  # `apps/` does not exist yet either: init finds the checkout from the nearest directory that does
    d = top / "apps" / "veyq"
    host = GitOpsHost(top=top, signed=signed, watch=d)
    rc, out = run_main(["--source", mirror, "init", "--gitops", d, "--release", "1.0", *INIT, "--secrets",
                        "external-secrets", "--secret-store", "vault"], host)
    files = {p.relative_to(d).as_posix() for p in d.rglob("*") if p.is_file()}
    check("init --gitops: writes the Application, the values, resources/ with ExternalSecrets, and veyqctl.json",
          rc == 0 and files == {"application.yaml", "values.yaml", vc.GITOPS_FILE, "resources/kustomization.yaml",
                                "resources/namespace.yaml", "resources/backup-pvc.yaml",
                                "resources/secrets.external.yaml", "resources/config-secret-key.external.yaml"},
          out[-800:] + str(files))
    values, application = (d / "values.yaml").read_text(), (d / "application.yaml").read_text()
    check("the values pin the release's three images by tag and digest, the Application its chart by digest",
          all(pin_of("1.0", r) in values for r in ("app", "admin", "tools")) and values.count('tag: "1.0"') == 3
          and f"targetRevision: {pin_of('1.0', 'chart')}" in application
          and "repoURL: oci://ghcr.io/raddatzk/charts/veyq" in application
          and "$values/apps/veyq/values.yaml" in application and "path: apps/veyq/resources" in application
          and "git@example.org:ops/cluster.git" in application and "targetRevision: main" in application
          and "\n  name: veyq\n" in application and "\n  name: repo\n" not in application,
          values + application)
    check("the values name the Secrets, the gateway, ACME and the data-plane peer the operator gave",
          "existingSecret: veyq-secrets" in values and "existingSecret: veyq-config-secret-key" in values
          and "className: traefik" in values and "mode: per-domain" in values and "email: ops@example.org" in values
          and "kubernetes.io/metadata.name: traefik" in values and "storeDomain: store.veyq.example.org" in values)
    external = (d / "resources" / "secrets.external.yaml").read_text()
    check("external-secrets: each key read from a remote key under the prefix, through the named store",
          "key: veyq-mariadb-password" in external and "name: vault" in external
          and "kind: ClusterSecretStore" in external and "deletionPolicy: Retain" in external, external)
    handed = vc.config_dir() / "repo.secrets.json"
    doc = json.loads(handed.read_text()) if handed.is_file() else {}
    generated = [v for keys in doc.get("secrets", {}).values() for v in keys.values()]
    check("the generated values are handed over once, 0600, outside the repository — and none is in it",
          stat.S_IMODE(handed.stat().st_mode) == 0o600 and len(generated) == 5
          and not any(v in tree_text(top) for v in generated)
          and doc.get("remoteKeys", {}).get("veyq-config-secret-key") == doc["secrets"]["veyq-config-secret-key"][
              vc.CONFIG_SECRET_KEY], str(doc)[:300])
    key = base64_32 = doc["secrets"]["veyq-config-secret-key"][vc.CONFIG_SECRET_KEY]
    import base64 as b64
    check("the config-secret key is base64 of 32 bytes, the RPC secret 64 hex characters",
          len(b64.b64decode(base64_32)) == 32
          and len(doc["secrets"]["veyq-secrets"]["garageRpcSecret"]) == 64, key)
    verified = [i for i, c in enumerate(host.commands) if is_cosign(c) and c[1] == "verify"]
    renders = host.ran("helm", "template")
    check("everything is verified and rendered before the directory exists: blob, three images, chart by its tag's "
          "digest, Chart.yaml's version",
          len(verified) == 4 and renders and not any(host.watched[: renders[0] + 1])
          and host.ran("oci-digest", "ghcr.io/raddatzk/charts/veyq:1.0") and host.ran("helm", "show", "chart")
          and min(host.ran("verify-blob")) < min(verified) < renders[0], str(host.commands[:12]))
    meta = json.loads((d / vc.GITOPS_FILE).read_text())
    check("veyqctl.json: the target, the selector, the provider, the chart, the running release and its four pins",
          meta["target"] == "gitops" and meta["release"] == "1.0" and meta["secrets"] == "external-secrets"
          and meta["running"] == "1.0" and meta["pins"] == {r: pin_of("1.0", r) for r in vc.PIN_ROLES}
          and meta["chart"] == "ghcr.io/raddatzk/charts/veyq", str(meta))
    check("init --gitops lists the instance under the repository's name and makes it current",
          vc.read_registry() == {"current": "repo", "instances": {"repo": str(d.resolve())}})
    setup = json.loads((top / vc.SETUP_FILE).read_text())
    check("init --gitops kept every answer in the repository's veyq-setup.json, by veyq's directory, no secret",
          setup["veyq"]["apps/veyq"].get("domain") == "veyq.example.org"
          and setup["veyq"]["apps/veyq"].get("secrets") == "external-secrets"
          and not any(k in json.dumps(setup) for k in ("mariadbPassword", "rpcSecret")), json.dumps(setup))
    before = tree_text(top)
    rc, out = run_main(["--source", mirror, "init", "--gitops", d, *INIT, "--secrets", "none"], GitOpsHost(top=top, signed=signed))
    check("a second init into the written directory with an answer that differs is refused, naming it, and changes "
          "nothing", rc == 1 and "--secrets none (answered: external-secrets) differs" in out
          and tree_text(top) == before, out[-300:])
    host = GitOpsHost(top=top, signed=signed)
    rc, out = run_main(["--source", mirror, "init", "--gitops", d], host)
    check("init again in the written checkout asks nothing and writes nothing (its answers are veyq-setup.json's)",
          rc == 0 and "nothing to write" in out and tree_text(top) == before and not host.ran("helm", "template"),
          out[-300:])
    stray = top / "apps" / "veyq-stray"
    stray.mkdir(parents=True)
    (stray / "values.yaml").write_text("{}\n")
    before = tree_text(top)
    rc, out = run_main(["--source", mirror, "init", "--gitops", stray, "--name", "stray", *INIT, "--secrets", "none"],
                       GitOpsHost(top=top, signed=signed))
    check("init into a directory holding files no answers describe is refused, pointing at how a half that exists is "
          "managed, and changes nothing", rc == 1 and "holds files already" in out and vc.EXISTING_HALF_DOCS in out
          and tree_text(top) == before, out[-300:])
    stray.joinpath("values.yaml").unlink()
    stray.rmdir()
    rc, out = run_main(["--instance", "repo", "--source", mirror, "verify"], GitOpsHost(top=top, signed=signed))
    check("verify on a GitOps instance: manifest, three images and chart", rc == 0 and "chart" in out
          and "nothing was written" in out, out[-400:])
    rc, out = run_main(["--instance", "repo", "--source", mirror, "check"], GitOpsHost(top=top, signed=signed))
    check("check on a GitOps instance: the pinned release, and the Secrets with every key",
          rc == 0 and "pinned already" in out and "exist with every key" in out, out[-500:])
    rc, out = run_main(["--instance", "repo", "--source", mirror, "check"], GitOpsHost(top=top, signed=signed, cluster={
        "get secret veyq-config-secret-key": (1, "")}))
    check("check: a Secret the provider did not create is named, exit 1", rc == 1 and "veyq-config-secret-key (missing)"
          in out, out[-400:])

    # the cluster contract, every gap at once, nothing written
    d2 = top / "apps" / "veyq-2"
    rc, out = run_main(["--source", mirror, "init", "--gitops", d2, "--name", "two", *INIT, "--secrets", "none"],
                       GitOpsHost(top=top, signed=signed, cluster={
                           "crd gateways.gateway.networking.k8s.io": (0, "v1.4.0"),
                           "get gatewayclass traefik": (1, ""),
                           "app.kubernetes.io/name=cert-manager": (0, "v1.20.2")}))
    check("init: a cluster below the contract is refused naming each gap, nothing written",
          rc == 1 and "v1.5 or later" in out and "GatewayClass traefik" in out and "cert-manager 1.21" in out
          and not d2.exists(), out[-600:])
    rc, out = run_main(["--source", mirror, "init", "--gitops", d2, "--name", "two", *INIT, "--secrets", "none"],
                       GitOpsHost(top=top, signed=signed, chart_version="0.9"))
    check("init: a chart whose Chart.yaml is another release is refused, nothing written",
          rc == 1 and "version 0.9, not 2.0" in out and not d2.exists(), out[-400:])
    rc, out = run_main(["--source", mirror, "init", "--gitops", d2, "--name", "two", *INIT, "--secrets", "none"],
                       GitOpsHost(top=top, signed=gitops_signed("1.0", "1.1", "2.0", without=(("2.0", "tools"),))))
    check("init: a tools image that does not verify is refused, nothing written",
          rc == 1 and "tools image" in out and "does not verify" in out and not d2.exists(), out[-400:])

    # sops: encrypted in the repository through the repository's creation rules; sealed-secrets needs kubeseal
    d3 = top / "apps" / "veyq-sops"
    host = GitOpsHost(top=top, signed=signed)
    rc, out = run_main(["--source", mirror, "init", "--gitops", d3, "--name", "sops", *INIT, "--secrets", "sops"], host)
    check("init --secrets sops without a .sops.yaml is refused, nothing written",
          rc == 1 and ".sops.yaml" in out and not d3.exists(), out[-300:])
    (top / ".sops.yaml").write_text("creation_rules: []\n")
    host = GitOpsHost(top=top, signed=signed)
    rc, out = run_main(["--source", mirror, "init", "--gitops", d3, "--name", "sops", *INIT, "--secrets", "sops"], host)
    sops_runs = host.ran("sops", "encrypt")
    check("init --secrets sops: both Secrets through `sops encrypt` on stdin, never argv; KSOPS generator; no hand-over",
          rc == 0 and len(sops_runs) == 2 and all(b"stringData" in s for s in host.stdins)
          and "ENC[" in (d3 / "resources" / "secrets.sops.yaml").read_text()
          and "generators:" in (d3 / "resources" / "kustomization.yaml").read_text()
          and not (vc.config_dir() / "sops.secrets.json").exists()
          and "sops decrypt --extract" in out, out[-500:])
    # where init offers veyq's half: beside the Argo CD Applications the repository holds (a repository of its own)
    layout = tmp / "layout"
    layout.mkdir()
    cwd = os.getcwd()
    os.chdir(layout)
    try:
        check("init offers apps/veyq where the repository holds no Applications in one place",
              vc.suggested_gitops_directory(GitOpsHost(top=layout)) == "apps/veyq")
        apps = layout / "gitops" / "apps"
        for app in ("traefik", "cert-manager"):
            (apps / app).mkdir(parents=True)
            (apps / app / "application.yaml").write_text("apiVersion: argoproj.io/v1alpha1\nkind: Application\n")
        (layout / "gitops" / "root.yaml").write_text("apiVersion: argoproj.io/v1alpha1\nkind: Application\n")
        offered = vc.suggested_gitops_directory(GitOpsHost(top=layout))
        host = GitOpsHost(top=layout, signed=signed,
                          answers={"compose or gitops": "gitops"})
        rc, out = run_main(["--source", mirror, "init", "--name", "beside", *INIT, "--secrets", "none"], host)
    finally:
        os.chdir(cwd)
    check("init offers veyq's directory beside the repository's Argo CD Applications, and Enter takes it",
          offered == "gitops/apps/veyq" and rc == 0 and (apps / "veyq" / vc.GITOPS_FILE).is_file(),
          f"{offered} {out[-300:]}")

    # what init offers when it is not told: kubectl's current context, the cluster's one project besides `default`,
    # the newest release's major as the selector
    d5 = top / "apps" / "veyq-offered"
    host = GitOpsHost(top=top, signed=signed, cluster={
        "config current-context": (0, "my-cluster\n"),
        "get appprojects": (0, "appproject.argoproj.io/default\nappproject.argoproj.io/ops\n")})
    rc, out = run_main(["--source", mirror, "init", "--gitops", d5, "--name", "offered", *INIT, "--secrets", "none"],
                       host)
    meta = json.loads((d5 / vc.GITOPS_FILE).read_text()) if (d5 / vc.GITOPS_FILE).exists() else {}
    check("init offers kubectl's current context, the one project besides default, and the newest major — and uses them",
          rc == 0 and meta.get("context") == "my-cluster" and meta.get("release") == "2"
          and meta.get("running") == "2.0" and "project: ops" in (d5 / "application.yaml").read_text()
          and all("--context" in c and "my-cluster" in c for c in host.commands
                  if c[0] == "kubectl" and "current-context" not in c), out[-400:])
    d6 = top / "apps" / "veyq-enter"
    (top / ".sops.yaml").write_text("creation_rules: []\n")
    host = GitOpsHost(top=top, signed=signed, answers={"veyq's domain": "veyq.example.org"})
    rc, out = run_main(["--source", mirror, "init", "--gitops", d6, "--name", "enter"], host)
    values = (d6 / "values.yaml").read_text() if (d6 / "values.yaml").exists() else ""
    check("init with Enter for every offer: the cluster's one GatewayClass, Traefik's namespace and label, acme while "
          "cert-manager runs, sops beside a .sops.yaml — only the domain typed",
          rc == 0 and "className: traefik" in values and "kubernetes.io/metadata.name: traefik" in values
          and "app.kubernetes.io/name: traefik" in values and "mode: per-domain" in values
          and "storeDomain: store.veyq.example.org" in values
          and "email: owner@example.org" in values
          and json.loads((d6 / vc.GITOPS_FILE).read_text())["secrets"] == "sops", out[-500:])
    d7 = top / "apps" / "veyq-admin-host"
    host = GitOpsHost(top=top, signed=signed, answers={"veyq's domain": "veyq.example.org"}, cluster={
        "get svc argocd-server": (0, "NodePort"), "spec.podCIDR": (0, "10.42.0.0/24")})
    rc, out = run_main(["--source", mirror, "init", "--gitops", d7, "--name", "admin-host"], host)
    access = (d7 / "resources" / "admin-host-access.yaml").read_text() if (d7 / "resources").exists() else ""
    check("init offers veyq's admin through the tunnel where Argo CD's server is a NodePort, the policy admitting the "
          "node's pod-network gateway from its podCIDR",
          rc == 0 and "nodePort: 30081" in access and "cidr: 10.42.0.1/32" in access
          and "  - admin-host-access.yaml" in (d7 / "resources" / "kustomization.yaml").read_text(), out[-400:])
    check("init offers no admin NodePort where Argo CD's server is none (no host firewall to keep it host-only)",
          not (d6 / "resources" / "admin-host-access.yaml").exists())
    rc, out = run_main(["--source", mirror, "init", "--gitops", top / "apps" / "veyq-nodomain", "--name", "nodomain"],
                       GitOpsHost(top=top, signed=signed))
    check("init refuses a question with no offer and no answer (the domain), naming the flag",
          rc == 1 and "--domain is required" in out and not (top / "apps" / "veyq-nodomain").exists(), out[-300:])
    host = GitOpsHost(top=top, signed=signed, cluster={
        "get appprojects": (0, "appproject.argoproj.io/default\nappproject.argoproj.io/a\nappproject.argoproj.io/b\n")})
    rc, out = run_main(["--source", mirror, "init", "--gitops", top / "apps" / "veyq-two", "--name", "two-projects",
                        *INIT, "--secrets", "none"], host)
    check("init offers `default` when the cluster has several projects of its own (it does not choose between them)",
          rc == 0 and "project: default" in (top / "apps" / "veyq-two" / "application.yaml").read_text(), out[-300:])
    rc, out = run_main(["--source", mirror, "init", "--gitops", top / "apps" / "veyq-sealed", "--name", "sealed",
                        *INIT, "--secrets", "sealed-secrets"], GitOpsHost(top=top, signed=signed, kubeseal=False))
    check("init --secrets sealed-secrets without kubeseal is refused", rc == 1 and "kubeseal" in out, out[-300:])


def interrupted(argv: list, host: FakeHost) -> str:
    """A run whose terminal goes away at the host's `interrupt` point: what it printed until then."""
    out = io.StringIO()
    try:
        with contextlib.redirect_stdout(out):
            vc.main([str(a) for a in argv], host)
    except KeyboardInterrupt:
        return out.getvalue()
    raise AssertionError("the run was not interrupted")


def test_init_resumes(tmp: pathlib.Path) -> None:
    mirror, signed = tmp / "g-mirror", gitops_signed("1.0", "1.1", "2.0")
    top = tmp / "resume"
    top.mkdir()
    d = top / "apps" / "veyq"
    answers = {"veyq's domain": "resume.example.org", "secrets: sops, external-secrets, sealed-secrets, none": "none",
               "instance name": "resumed"}
    argv = ["--source", mirror, "init", "--gitops", d]
    interrupted(argv, GitOpsHost(top=top, signed=signed, answers=answers, interrupt=("helm", "template")))
    progress = vc.Progress(top.resolve(), "apps/veyq")
    check("an interrupted init kept its answers at once in the repository's veyq-setup.json, its steps outside it, no "
          "secret in either", progress.answer("domain") == "resume.example.org" and progress.answer("secrets") == "none"
          and progress.setup_path == top.resolve() / vc.SETUP_FILE and not progress.path.is_relative_to(top.resolve())
          and not d.exists() and "secrets" not in json.dumps(progress.doc["steps"]), str(progress.answers))
    rc, out = run_main(argv, GitOpsHost(top=top, signed=signed))  # no answers at all this time
    values = (d / "values.yaml").read_text() if (d / "values.yaml").exists() else ""
    check("the same command continues: nothing asked again, the answers taken (and shown), the instance written",
          rc == 0 and "domain: resume.example.org" in values and f"(from {vc.SETUP_FILE})" in out
          and "a run here stopped" in out and vc.read_registry()["instances"].get("resumed") == str(d.resolve())
          and not progress.path.exists(), out[-600:])

    # stopped after the values were handed over and the files written: the same values, the files not rewritten
    top2 = tmp / "resume2"
    top2.mkdir()
    d2 = top2 / "apps" / "veyq"
    argv2 = ["--source", mirror, "init", "--gitops", d2]
    answers2 = dict(answers, **{"instance name": "resumed2"})
    interrupted(argv2, GitOpsHost(top=top2, signed=signed, answers=answers2, interrupt=("remote", "get-url"),
                                       interrupt_at=2))
    handed = vc.config_dir() / "resumed2.secrets.json"
    before = handed.read_text() if handed.exists() else ""
    written = (d2 / "values.yaml").read_text() if (d2 / "values.yaml").exists() else ""
    host = GitOpsHost(top=top2, signed=signed)
    rc, out = run_main(argv2, host)
    check("resumed after the files were written: no second hand-over, no new value, nothing rewritten, listed",
          rc == 0 and before and handed.read_text() == before and (d2 / "values.yaml").read_text() == written
          and not host.ran("helm", "template") and vc.read_registry()["instances"].get("resumed2") == str(d2.resolve()),
          out[-500:])


def test_init_resumes_from_flags(tmp: pathlib.Path) -> None:
    """Flags given once are answers too: a run started with them and continued with none writes what they said."""
    mirror, signed = tmp / "g-mirror", gitops_signed("1.0", "1.1", "2.0")
    top = tmp / "resume-flags"
    top.mkdir()
    d = top / "apps" / "veyq"
    interrupted(["--source", mirror, "init", "--gitops", d, "--name", "flags", *INIT, "--secrets", "none",
                 "--no-bundled-store", "--backup-size", "5Gi", "--namespace", "web", "--revision", "live"],
                GitOpsHost(top=top, signed=signed, interrupt=("helm", "template")))
    # --no-cluster-check: nothing read from the cluster offers `acme`, so only the recorded answer can give it
    rc, out = run_main(["--source", mirror, "init", "--gitops", d, "--no-cluster-check"], GitOpsHost(top=top, signed=signed))
    values = (d / "values.yaml").read_text() if (d / "values.yaml").exists() else ""
    claim = (d / "resources/backup-pvc.yaml").read_text() if (d / "resources/backup-pvc.yaml").exists() else ""
    application = (d / "application.yaml").read_text() if (d / "application.yaml").exists() else ""
    check("continued with no flag: TLS derived from --acme-email, the store left out, the claim's size, the namespace "
          "and the branch are the first run's",
          rc == 0 and "ops@example.org" in values and "store." not in values and "storage: 5Gi" in claim
          and "namespace: web" in claim and "targetRevision: live" in application, out[-500:] + values[:300])
    before = tree_text(top)
    host = GitOpsHost(top=top, signed=signed)
    rc, out = run_main(["init"], host)
    check("`init` alone in the written checkout takes veyq-setup.json's directory, asks nothing, writes nothing",
          rc == 0 and "nothing to write" in out and tree_text(top) == before and not host.ran("helm", "template"),
          out[-400:])


SERVER_IP = "203.0.113.10"
HOST_KEY = "server.example.org ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHostKeyOfTheFreshServer"
AGE_PUBLIC = "age1" + "q" * 58
TEMPLATE_FILES = {  # a veyq-server release as the stand-in clone checks it out: what Copier renders from it
    "copier.yml": "_subdirectory: template\n",
    "template/.sops.yaml.jinja": "creation_rules: []\n",
    "scripts/check.sh": "#!/bin/sh\n",
}


def write_template(into: pathlib.Path) -> None:
    for relative, text in TEMPLATE_FILES.items():
        path = into / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    (into / "scripts/check.sh").chmod(0o755)


class ServerHost(GitOpsHost):
    """GitOpsHost plus the server path's programs — veyq-server's release and clone, copier, age-keygen, ssh and its
    tools, gh, ansible — each answering as the program would, and a terminal whose confirmations are `confirms`
    (a substring of the question → the answer; True when none matches)."""

    def __init__(self, *, confirms=None, waits=True, gh=True, tamper_manifest=False, tamper_tree=False,
                 host_key=HOST_KEY, bootstrapped=False, dns=None, remote_differs=False, **kwargs) -> None:
        super().__init__(empty_repo=True, **kwargs)
        self.confirms, self.waits, self.gh = dict(confirms or {}), waits, gh
        self.tamper_manifest, self.tamper_tree, self.host_key = tamper_manifest, tamper_tree, host_key
        self.bootstrapped = bootstrapped
        self.remote_differs = remote_differs  # origin's gitops/ is not this checkout's after the push
        self.dns = dns if dns is not None else {}
        self.asked_confirm: list[str] = []
        self.waited: list[str] = []
        self.tunnels: list = []
        # git's state outlives a run, as a repository's does: kept beside the checkout
        self.state = self.top.parent / f"{self.top.name}.git-state"
        state = self.state.read_text().split() if self.state.is_file() else []
        self.committed, self.pushed = "committed" in state, "pushed" in state
        self.bootstrapped = self.bootstrapped or "bootstrapped" in state  # the server's, outliving a run too
        self.env_at: dict[int, dict] = {}  # the environment of each command this stand-in answered, by its index
        with tempfile.TemporaryDirectory() as tmp:
            write_template(pathlib.Path(tmp))
            tree = vc.tree_sha256(pathlib.Path(tmp))
        self.manifest = json.dumps({"release": "0.1", "commit": "c" * 40, "tree_sha256": tree,
                                    "date": "2026-11-10"}, indent=2).encode()

    def server_release(self):
        self.commands.append(["server-release"])
        self.watched.append(False)
        bundle = bundle_token(self.manifest).encode()
        raw = self.manifest.replace(b"2026-11-10", b"2026-11-11") if self.tamper_manifest else self.manifest
        return "v0.1", raw, bundle

    def confirm(self, prompt):
        self.asked_confirm.append(prompt)
        return next((v for k, v in self.confirms.items() if k in prompt), True)

    def wait(self, prompt):
        self.waited.append(prompt)
        return self.waits

    def secret(self, question):
        return ""  # Enter: generated

    def sleep(self, seconds):
        pass

    def resolve(self, name):
        return {SERVER_IP} if name == "server.example.org" else set(self.dns.get(name, {SERVER_IP}))

    def background(self, cmd):
        self.commands.append(list(cmd) + ["&"])
        self.watched.append(False)
        host = self

        class Tunnel:
            def terminate(self):
                host.tunnels.append("closed")
        return Tunnel()

    def which(self, name):
        return None if name == "gh" and not self.gh else super().which(name)

    def run(self, cmd, *, check=True, stream=False, env=None, stdin=None):
        tool = pathlib.Path(cmd[0]).name
        if tool == "cosign" and cmd[1] == "verify-blob" and b"tree_sha256" in pathlib.Path(cmd[2]).read_bytes():
            blob = pathlib.Path(cmd[2]).read_bytes()
            bundle = pathlib.Path(cmd[cmd.index("--bundle") + 1]).read_text()
            ok = bundle == bundle_token(blob) and cmd[cmd.index("--certificate-identity-regexp") + 1] == \
                vc.SERVER_IDENTITY_REGEXP
            return self.record(cmd, check, 0 if ok else 1, "", env)
        if cmd[:2] == ["git", "clone"]:
            pathlib.Path(cmd[-1]).mkdir(parents=True)
            return self.record(cmd, check, 0, "", env)
        if cmd[0] == "git" and "checkout" in cmd and "-C" in cmd:
            into = pathlib.Path(cmd[cmd.index("-C") + 1])
            write_template(into)
            if self.tamper_tree:
                (into / "copier.yml").write_text("_subdirectory: elsewhere\n")
            return self.record(cmd, check, 0, "", env)
        if cmd[0] == "git" and "-C" in cmd and pathlib.Path(cmd[cmd.index("-C") + 1]) == self.top.resolve():
            sub = cmd[cmd.index("-C") + 2:]
            if sub[:2] == ["status", "--porcelain"]:
                return self.record(cmd, check, 0, "" if self.committed else "?? gitops/\n?? ansible/\n", env)
            if sub[:1] == ["commit"]:
                self.committed = True
            if sub[:1] == ["push"]:
                self.pushed = True
            self.state.write_text(" ".join(w for w, on in (("committed", self.committed), ("pushed", self.pushed),
                                                           ("bootstrapped", self.bootstrapped)) if on))
            if sub[:2] == ["rev-parse", "--verify"]:
                return self.record(cmd, check, 0 if self.pushed else 1, "", env)
            if sub[:2] == ["diff", "--quiet"] and "HEAD" in sub:
                return self.record(cmd, check, 0 if self.pushed and not self.remote_differs else 1, "", env)
            if sub[:1] == ["symbolic-ref"]:
                return self.record(cmd, check, 0, "main\n", env)
            if sub[:3] == ["remote", "get-url", "origin"]:
                return self.record(cmd, check, 0, "https://github.com/owner/raddatz.me.git\n", env)
        if tool == "copier":
            self.render(pathlib.Path(cmd[cmd.index("-d") - 1]), dict(c.split("=", 1) for c in cmd[cmd.index("-d") + 1::2]))
            return self.record(cmd, check, 0, "", env)
        if tool == "age-keygen":
            if cmd[1] == "-o":
                pathlib.Path(cmd[2]).write_text("AGE-SECRET-KEY-1STANDIN\n")
            return self.record(cmd, check, 0, AGE_PUBLIC + "\n" if cmd[1] == "-y" else "", env)
        if tool == "ssh-keygen":
            if "-f" in cmd and "-t" in cmd:
                key = pathlib.Path(cmd[cmd.index("-f") + 1])
                key.write_text("-----BEGIN OPENSSH PRIVATE KEY-----\nstand-in\n-----END OPENSSH PRIVATE KEY-----\n")
                pathlib.Path(f"{key}.pub").write_text(f"ssh-ed25519 AAAAdeploykey {cmd[cmd.index('-C') + 1]}\n")
            out = {"-lf": f"256 SHA256:{sha((stdin or b'').decode().split()[2].encode())[:43]} host (ED25519)\n"
                   if stdin else "", "-y": "ssh-ed25519 AAAAdeploykey argocd@raddatz.me\n"}.get(cmd[1], "")
            return self.record(cmd, check, 0, out, env)
        if tool == "ssh-keyscan":
            return self.record(cmd, check, 0, f"# {cmd[-1]}:22 SSH-2.0\n{self.host_key}\n" if self.host_key else "",
                               env)
        if tool == "ssh":
            if cmd[1] == "-G":
                return self.record(cmd, check, 0, f"userknownhostsfile {pathlib.Path.home()}/.ssh/known_hosts\n", env)
            if cmd[-1] == "true":
                return self.record(cmd, check, 0 if self.bootstrapped else 255, "", env)
            if "cat ~/.kube/config" in cmd:
                return self.record(cmd, check, 0, KUBECONFIG_K3S, env)
        if tool == "gh":
            return self.record(cmd, check, 0, "", env)
        if tool == "ansible-playbook":
            if "--version" in cmd:
                return self.record(cmd, check, 0, "ansible-playbook [core 2.21.5]\n", env)
            self.bootstrapped = True
            self.state.write_text((self.state.read_text() if self.state.is_file() else "") + " bootstrapped")
            return self.record(cmd, check, 0, "", env)
        if tool == "ansible-galaxy":
            return self.record(cmd, check, 0, "", env)
        if cmd[:3] == ["kubectl", "config", "view"]:
            last = (env or {}).get("KUBECONFIG", "").split(":")[-1]
            return self.record(cmd, check, 0, pathlib.Path(last).read_text() if last else "", env)
        if cmd[0] == "kubectl" and "applications" in cmd:
            items = [{"metadata": {"name": n}, "status": {"sync": {"status": "Synced"}, "health": {"status": "Healthy"}}}
                     for n in ("argocd", "traefik", "veyq")]
            return self.record(cmd, check, 0, json.dumps({"items": items}), env)
        if cmd[0] == "sops" and cmd[1] == "decrypt":
            return self.record(cmd, check, 0, "-----BEGIN OPENSSH PRIVATE KEY-----\nstand-in\n-----END OPENSSH "
                                              "PRIVATE KEY-----\n", env)
        return super().run(cmd, check=check, stream=stream, env=env, stdin=stdin)

    def record(self, cmd, check, rc, out, env):
        self.commands.append(list(cmd))
        self.watched.append(False)
        self.env_at[len(self.commands) - 1] = dict(env or {})
        if check and rc:
            raise vc.Refused(f"`{' '.join(cmd[:4])} …` exited {rc}")
        return subprocess.CompletedProcess(cmd, rc, out, "stand-in refusal" if rc else "")

    def render(self, top: pathlib.Path, data: dict) -> None:
        """What Copier writes from veyq-server: the files veyqctl reads and changes after rendering."""
        files = {
            ".copier-answers.yml": f"_commit: v0.1\n_src_path: /tmp/veyqctl-veyq-server-x/veyq-server\n"
                                   f"server_address: {data['server_address']}\n",
            ".sops.yaml": f"creation_rules:\n  - path_regex: gitops/.*\\.sops\\.yaml$\n    age: {data['age_recipient']}\n",
            "secret-templates/ansible-server.yaml": "admin_password: __ADMIN_PASSWORD__\nadmin_password_salt: "
                                                    "__RANDOM_16__\nargocd_repo_ssh_private_key: __DEPLOY_KEY__\n",
            "ansible/known_hosts": "# The server's SSH host key, pinned.\n",
            "ansible/requirements.yml": "collections: []\n",
            "gitops/root.yaml": f"spec:\n  source:\n    targetRevision: {data['branch']}\n",
            "gitops/apps/kustomization.yaml": "resources:\n  - project.yaml\n  # veyq: `veyqctl init` writes "
                                              "gitops/apps/veyq/ and adds its Application here.\n",
        }
        for relative, text in files.items():
            (top / relative).parent.mkdir(parents=True, exist_ok=True)
            (top / relative).write_text(text)
        self.rendered = data


KUBECONFIG_K3S = """apiVersion: v1
clusters:
- cluster:
    certificate-authority-data: c3RhbmQtaW4=
    server: https://127.0.0.1:6443
  name: default
contexts:
- context:
    cluster: default
    user: default
  name: default
current-context: default
kind: Config
users:
- name: default
  user:
    client-certificate-data: c3RhbmQtaW4=
"""


def test_server_path(tmp: pathlib.Path) -> None:
    """`init` from an empty repository to a running instance, against stand-ins for every program it starts: each
    confirmation's no stops before its step, each refusal changes nothing outside the repository."""
    home_before = os.environ.get("HOME")
    home = tmp / "server-home"
    (home / ".ssh").mkdir(parents=True)
    (home / ".ssh" / "id_ed25519.pub").write_text("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOperatorsOwnKey me@laptop\n")
    os.environ["HOME"] = str(home)
    try:
        mirror, signed = tmp / "g-mirror", gitops_signed("1.0", "1.1", "2.0")
        age_key = tmp / "server-age.txt"
        age_key.write_text("AGE-SECRET-KEY-1STANDIN\n")
        answers = {"compose or gitops": "gitops", "server address (DNS name or IPv4)": "server.example.org",
                   "veyq's domain": "raddatz.me",
                   "admin user": "kevin", "admin SSH key: a number, or paste a public key": "1"}

        def fresh(name):
            top = tmp / name
            top.mkdir()
            return top

        def outside():  # what the run may change outside the repository: the user's SSH and kube files
            return {p.relative_to(home).as_posix(): p.read_text() for p in home.rglob("*") if p.is_file()}

        def run(top, **kw):
            host = ServerHost(top=top, signed=signed, answers=dict(answers), **kw)
            cwd = os.getcwd()
            os.chdir(top)
            try:
                rc, out = run_main(["--source", mirror, "init", "--age-key", age_key, "--release", "1.0"], host)
            finally:
                os.chdir(cwd)
            return host, rc, out

        # refusals before anything is rendered: a manifest changed after signing, a tree that is not the signed one
        before = outside()
        for label, kw, said in (("a veyq-server manifest that does not verify", {"tamper_manifest": True},
                                 "the identity that publishes veyq-server"),
                                ("a checked-out tree whose sha256 is not the manifest's", {"tamper_tree": True},
                                 "its signed manifest says")):
            top = fresh(f"server-{len(label)}")
            host, rc, out = run(top, **kw)
            check(f"server path: {label} is refused before Copier runs, nothing outside the repository changed",
                  rc == 1 and said in out and not host.ran("copier") and not (top / ".copier-answers.yml").exists()
                  and outside() == before, out[-500:])

        # the host key not confirmed: nothing logs in, nothing pushed, ~/.ssh untouched; then the same command goes on
        top = fresh("server-run")
        host, rc, out = run(top, confirms={"provider's console": False})
        check("server path: a host key not confirmed is refused — not pinned, nothing pushed, ~/.ssh and ~/.kube "
              "untouched", rc == 1 and "not confirmed" in out and not vc.pinned_host_key(top) and not host.pushed
              and outside() == before and not host.ran("ansible-playbook"), out[-500:])
        rendered = (top / ".copier-answers.yml").read_text()
        secrets_file = (top / vc.SERVER_SECRETS).read_text()
        setup = json.loads((top / vc.SETUP_FILE).read_text())
        check("server path: the answers are veyq-setup.json's server section (no secret, the age key's public half "
              "only), the secrets file encrypted, .copier-answers.yml naming veyq-server's public repository",
              setup["server"]["server_address"] == "server.example.org" and setup["server"]["age_recipient"] ==
              AGE_PUBLIC and "AGE-SECRET" not in json.dumps(setup) and "ENC[" in secrets_file
              and f"_src_path: {vc.SERVER_TEMPLATE_URL}" in rendered and host.rendered["repo_url"] ==
              "git@github.com:owner/raddatz.me.git" and host.rendered["admin_ssh_public_key"].endswith("Key")
              and host.rendered["age_recipient"] == AGE_PUBLIC, json.dumps(setup))

        host, rc, out = run(top, confirms={"Commit them": False})
        check("server path: resumed, nothing asked again, the host key pinned; commit declined: nothing pushed, no "
              "bootstrap", rc == 1 and vc.pinned_host_key(top) == HOST_KEY and not host.ran("copier")
              and not host.pushed and not host.ran("ansible-playbook") and (top / vc.SERVER_SECRETS).read_text()
              == secrets_file and "admin user: kevin   (from veyq-setup.json)" in out, out[-500:])

        host, rc, out = run(top, remote_differs=True)
        check("server path: origin's gitops/ differing from the checkout after the push is refused before the "
              "bootstrap (its first sync would take the remote's copy)", rc == 1 and "push first" in out
              and not [c for c in host.commands if c[:2] == ["ansible-playbook", "playbooks/bootstrap.yml"]],
              out[-400:])

        host, rc, out = run(top, confirms={"Run the bootstrap": False})
        check("server path: pushed, then the bootstrap declined: ansible-playbook never ran, ~/.ssh/config untouched",
              rc == 1 and host.pushed and not [c for c in host.commands if c[0] == "ansible-playbook" and
                                               "--version" not in c]
              and not (home / ".ssh" / "config").exists(), out[-500:])

        host, rc, out = run(top)
        boot = [i for i, c in enumerate(host.commands) if c[:2] == ["ansible-playbook", "playbooks/bootstrap.yml"]]
        add = host.ran("deploy-key", "add")
        config = (home / ".ssh" / "config").read_text() if (home / ".ssh" / "config").exists() else ""
        kube = (home / ".kube" / "config").read_text() if (home / ".kube" / "config").exists() else ""
        check("server path: the whole run — the deploy key entered read-only with gh, veyq's Application listed for "
              "the root, the bootstrap with the age key's file, the tunnel and context written, Argo CD waited for",
              rc == 0 and boot and add and "--allow-write" not in host.commands[add[0]]
              and host.env_at[boot[0]].get("SOPS_AGE_KEY_FILE") == str(age_key.resolve())
              and "  - veyq/application.yaml" in (top / "gitops/apps/kustomization.yaml").read_text()
              and "Host server-run" in config and "LocalForward 16443 127.0.0.1:6443" in config
              and "LocalForward 18443 server.example.org:30443" in config and "name: server-run" in kube
              and "https://127.0.0.1:16443" in kube and host.tunnels == ["closed"]
              and HOST_KEY in (home / ".ssh" / "known_hosts").read_text() and "Synced and Healthy" in out, out[-900:])

        host, rc, out = run(top)
        check("server path: init again on a set-up server — nothing rendered, nothing committed, no bootstrap (the "
              "admin logs in already)", rc == 0 and not host.ran("copier") and not host.ran("commit")
              and not [c for c in host.commands if c[:2] == ["ansible-playbook", "playbooks/bootstrap.yml"]],
              f"rc={rc} copier={host.ran('copier')} commit={host.ran('commit')} " + out[-300:])

        host, rc, out = run(top, host_key=HOST_KEY.replace("Fresh", "Rebuilt"), confirms={"provider's console": False})
        check("server path: a rebuilt server's new host key is shown beside the pinned one and, unconfirmed, refused",
              rc == 1 and "a rebuilt server?" in out and vc.pinned_host_key(top) == HOST_KEY, out[-500:])

        top2 = fresh("server-dns")
        host, rc, out = run(top2, waits=False, gh=False, dns={"raddatz.me": set()})
        check("server path: without gh the deploy key is shown with GitHub's page and waited for (no terminal: "
              "refused, nothing pushed)", rc == 1 and "github.com/owner/raddatz.me/settings/keys/new" in out
              and not host.pushed, out[-500:])

        (home / ".ssh" / "config").write_text((home / ".ssh" / "config").read_text() + "\nHost other\n  User x\n")
        cwd = os.getcwd()
        os.chdir(top)
        try:
            rc, out = run_main(["connect"], ServerHost(top=top))
            again = (home / ".ssh" / "config").read_text()
            check("connect: the block rewritten in place, the owner's own Host kept",
                  rc == 0 and again.count("# >>> server-run") == 1 and "Host other" in again, out[-300:])
            (home / ".ssh" / "config").write_text("Host server-run\n  User mine\n")
            rc, out = run_main(["connect"], ServerHost(top=top))
            check("connect: never shadows a `Host` of the owner's own", rc == 1 and "of its own" in out
                  and (home / ".ssh" / "config").read_text() == "Host server-run\n  User mine\n", out[-300:])
        finally:
            os.chdir(cwd)
    finally:
        if home_before is None:
            os.environ.pop("HOME", None)
        else:
            os.environ["HOME"] = home_before


def test_gitops_upgrade(tmp: pathlib.Path) -> None:
    mirror = tmp / "g-mirror"  # test_gitops_init's: 1.0, 1.1, 2.0 entered from 1.1
    signed = gitops_signed("1.0", "1.1", "2.0")
    top = tmp / "repo"
    d = top / "apps" / "veyq"
    meta = json.loads((d / vc.GITOPS_FILE).read_text())
    (d / vc.GITOPS_FILE).write_text(json.dumps({**meta, "release": "latest"}))
    host = GitOpsHost(top=top, signed=signed)
    rc, out = run_main(["--instance", "repo", "--source", mirror, "upgrade"], host)
    values, application = (d / "values.yaml").read_text(), (d / "application.yaml").read_text()
    meta = json.loads((d / vc.GITOPS_FILE).read_text())
    commits = host.ran("git", "commit")
    check("upgrade latest from 1.0: stops on 1.1, major 2's entry, and says the next upgrade enters major 2",
          rc == 0 and meta["running"] == "1.1" and "enters major 2" in out, out[-600:])
    check("upgrade: the four pins and the three tags rewritten, nothing of 1.0 left; veyqctl.json records 1.1's",
          all(pin_of("1.1", r) in values for r in ("app", "admin", "tools")) and values.count('tag: "1.1"') == 3
          and pin_of("1.1", "chart") in application and pin_of("1.0", "chart") not in application
          and meta["pins"] == {r: pin_of("1.1", r) for r in vc.PIN_ROLES})
    check("upgrade: verified and rendered before any file is written, then a commit of its three files alone, no push",
          commits and host.ran("helm", "template")[0] < commits[0]
          and host.commands[commits[0]][-3:] == [str(d.resolve() / f) for f in ("application.yaml", "values.yaml",
                                                                                  vc.GITOPS_FILE)]
          and "veyq 1.1: upgraded from 1.0" in " ".join(host.commands[commits[0]])
          and not host.ran("git", "push"), str(host.commands[-4:]))
    host = GitOpsHost(top=top, signed=signed)
    rc, out = run_main(["--instance", "repo", "--source", mirror, "upgrade", "--push"], host)
    check("upgrade --push from 1.1 enters major 2 (2.0) and pushes", rc == 0 and json.loads(
        (d / vc.GITOPS_FILE).read_text())["running"] == "2.0" and host.ran("git", "push"), out[-400:])
    rc, out = run_main(["--instance", "repo", "--source", mirror, "upgrade"], GitOpsHost(top=top, signed=signed))
    check("upgrade on the newest: nothing to do", rc == 0 and "nothing to do" in out, out[-200:])

    # every refusal leaves the repository untouched and commits nothing
    d4 = top / "apps" / "veyq-refusals"
    rc, out = run_main(["--source", mirror, "init", "--gitops", d4, "--name", "refusals", "--release", "1.0", *INIT,
                        "--secrets", "none"], GitOpsHost(top=top, signed=signed))
    assert rc == 0, out
    meta = json.loads((d4 / vc.GITOPS_FILE).read_text())
    (d4 / vc.GITOPS_FILE).write_text(json.dumps({**meta, "release": "1"}))
    before = snapshot(d4)
    cases = [
        ("uncommitted changes", GitOpsHost(top=top, signed=signed, dirty=True), None, "not committed"),
        ("a chart that does not verify", GitOpsHost(top=top, signed=gitops_signed(
            "1.0", "1.1", "2.0", without=(("1.1", "chart"),))), None, "does not verify"),
        ("a chart of another version", GitOpsHost(top=top, signed=signed, chart_version="1.0"), None, "not 1.1"),
        ("a render that fails", GitOpsHost(top=top, signed=signed, render_fails=True), None, "helm template"),
        ("a digest edited by hand (drift)", GitOpsHost(top=top, signed=signed),
         lambda: (d4 / "values.yaml").write_text((d4 / "values.yaml").read_text().replace(pin_of("1.0", "admin"),
                                                                                          DIGEST_B)), "no longer pin"),
    ]
    for label, host, edit, words in cases:
        if edit:
            edit()
        snap = snapshot(d4)
        rc, out = run_main(["--instance", "refusals", "--source", mirror, "upgrade"], host)
        check(f"upgrade refuses {label}, writes nothing and commits nothing",
              rc == 1 and words in out and snapshot(d4) == snap and not host.ran("git", "commit"), out[-400:])
    for name, data in before.items():
        (d4 / name).write_bytes(data)
    (d4 / vc.GITOPS_FILE).write_text(json.dumps({**meta, "release": "1.0", "running": "1.1"}))
    rc, out = run_main(["--instance", "refusals", "--source", mirror, "upgrade"], GitOpsHost(top=top, signed=signed))
    check("upgrade below the pinned release is refused (no downgrade)", rc == 1 and "below the pinned" in out, out[-300:])


ADOPTED_VALUES = """# hand-written, as affenbande's
domain: cloud.example.org
image:
  # renovate: datasource=docker depName=ghcr.io/raddatzk/veyq
  tag: "1.0"
  digest: {app}
admin:
  image:
    tag: "1.0"
    digest: {admin}
backup:
  enabled: true
  image:
    tag: 1.0
    digest: {tools}
"""
ADOPTED_APPLICATION = """spec:
  sources:
    - repoURL: oci://ghcr.io/raddatzk/charts/veyq
      # renovate: datasource=docker depName=ghcr.io/raddatzk/charts/veyq version=1.0
      targetRevision: {chart}
      path: .
"""


def hand_written(d: pathlib.Path, pins: dict, files_admin: str, running: str = "1.0") -> None:
    """A veyq half written by hand, as affenbande's, and the veyqctl.json its owner wrote beside it (the Run page's
    *A veyq half that exists already*): the files pin `pins` but for the admin image, which they pin `files_admin`."""
    d.mkdir(parents=True, exist_ok=True)
    (d / "application.yaml").write_text(ADOPTED_APPLICATION.format(**pins))
    (d / "values.yaml").write_text(ADOPTED_VALUES.format(**{**pins, "admin": files_admin}))
    (d / vc.GITOPS_FILE).write_text(json.dumps({
        "target": "gitops", "release": "1", "secrets": "sops", "application": "application.yaml",
        "values": "values.yaml", "chart": "ghcr.io/raddatzk/charts/veyq", "namespace": "veyq", "running": running,
        "pins": {**pins, "admin": files_admin}}, indent=2))


def test_gitops_hand_written(tmp: pathlib.Path) -> None:
    mirror, signed = tmp / "g-mirror", gitops_signed("1.0", "1.1", "2.0")
    top = tmp / "hand"
    pins = {r: pin_of("1.0", r) for r in vc.PIN_ROLES}
    unsigned = tmp / "hand" / "gitops" / "apps" / "unsigned"
    hand_written(unsigned, pins, DIGEST_B)  # the files and veyqctl.json agree, and the release never signed DIGEST_B
    rc, out = run_main(["--home", unsigned, "--source", mirror, "check"], GitOpsHost(top=top, signed=signed))
    check("check: a hand-written veyqctl.json pinning what its running release did not sign is named, exit 1",
          rc == 1 and "NOT RELEASE 1.0" in out and DIGEST_B in out and "DRIFT" not in out, out[-500:])
    before = snapshot(unsigned)
    host = GitOpsHost(top=top, signed=signed)
    rc, out = run_main(["--home", unsigned, "--source", mirror, "upgrade"], host)
    check("upgrade refuses to start from a running release whose pins do not verify, the repository untouched",
          rc == 1 and "did not sign" in out and snapshot(unsigned) == before and not host.ran("git", "commit"),
          out[-400:])
    d = top / "gitops" / "apps" / "veyq"
    hand_written(d, pins, pins["admin"])
    rc, out = run_main(["--home", d, "--source", mirror, "check"], GitOpsHost(top=top, signed=signed))
    check("check: a hand-written veyqctl.json pinning its release's signed pins is confirmed",
          rc == 0 and "release 1.0's, as it was signed" in out, out[-400:])
    host = GitOpsHost(top=top, signed=signed)
    rc, out = run_main(["--home", d, "--source", mirror, "upgrade"], host)
    values, application = (d / "values.yaml").read_text(), (d / "application.yaml").read_text()
    check("a hand-written layout upgrades like a written one: its comments kept, the Renovate hint moved with it",
          rc == 0 and "# hand-written, as affenbande's" in values and values.count('tag: "1.1"') == 3
          and "version=1.1" in application and pin_of("1.1", "chart") in application and host.ran("git", "commit"),
          out[-400:] + values)
    rc, out = run_main(["--source", mirror, "init", "--gitops", d, "--secrets", "sops", *INIT],
                       GitOpsHost(top=top, signed=signed))
    check("init into a hand-written half is refused, naming veyqctl.json and the page that says how",
          rc == 1 and vc.GITOPS_FILE in out and vc.EXISTING_HALF_DOCS in out, out[-300:])


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="veyqctl-selftest-") as raw:
        tmp = pathlib.Path(raw)
        # The instance list lives in the user's config: never the real one (ADR 0126 decision 2).
        os.environ["XDG_CONFIG_HOME"] = str(tmp / "config")
        test_resolver(tmp)
        test_major_entry()
        test_manifest()
        test_assets_on_disk(tmp)
        test_template(tmp)
        test_signature_gate()
        test_status()
        test_install(tmp)
        test_upgrade_selector(tmp)
        test_upgrade_order(tmp)
        test_upgrade_refusals(tmp)
        test_backup_restore(tmp)
        test_backup_without_garage(tmp)
        test_pool(tmp)
        test_rollback(tmp)
        test_retention_and_ledger(tmp)
        test_self_update(tmp)
        test_manifest_signature(tmp)
        test_entry_signed(tmp)
        test_instances(tmp)
        test_gitops_init(tmp)
        test_init_resumes(tmp)
        test_init_resumes_from_flags(tmp)
        test_gitops_upgrade(tmp)
        test_gitops_hand_written(tmp)
        test_server_path(tmp)
        test_programs_checked_first(tmp)
    if failures:
        print(f"\nveyqctl self-test FAILED: {len(failures)} check(s)")
        return 1
    print("\nveyqctl self-test OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
