#!/usr/bin/env python3
"""Pin what `scripts/veyqctl.py` decides, without Docker, cosign or a network (cr-veyqctl).

`veyqctl` is a host tool, not a coverage surface (ADR 0040): this self-test and the live runs recorded in the CR are
what prove it. Everything here runs against a mirror directory this file writes and a stand-in for the host
(`FakeHost`) that records every command veyqctl would have started and answers the few it reads:

  1. **the resolver** — `VEYQ_RELEASE` per line of ADR 0096 decision 4 (a pin, a major, `latest`) against a mirror
     whose minors sort differently as strings (`1.12` after `1.7`), prereleases never taken and a pin to one refused,
     a selector that selects nothing refused, and the newer major `check` reports; and ADR 0096 decision 5 step 1 on
     top of it (`upgrade_target`): a later major entered only from the running major's last release, never more than
     one major per run, a pin in the next major reached directly from its predecessor's last, a prerelease never the
     "last" of a major, a next major without a release refused;
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
     pull after it; and the exact identity and `name@digest` handed to cosign;
  6. **the health verdict** `status` and every `up` end on: a finished one-shot (`restart: "no"`, exit 0) is
     healthy, a long-running service that exited 0 — what `stop` leaves — is not;
  7. **install end to end** on the stand-in host: the command order (verify before anything is written or started,
     `config` before `up`), the state it records (the project Compose named, which `start` then addresses by name),
     the manifest kept verbatim beside the release, a shell's `COMPOSE_*` dropped, and every printed line of an
     `--unsigned-local` run saying that nothing was verified;
  8. **`upgrade`** — `VEYQ_RELEASE` per line of ADR 0096 decision 4 against a running 1.3 (a pin to the running
     release does nothing and runs nothing, a major takes its newest, `latest` and `2` stop on 1.12, the last of major
     1, and say that the next `upgrade` enters major 2, which it then does, skipping a prerelease; `check` says the
     same; a pin below the running release is refused); decision 5's order (both images, the staged `.env` checked by Compose,
     then the app stopped, leg (c) copied out, the capture by the RUNNING release's tool and its check, the switch,
     `up` on the new recipe) with nothing stopped before step 4; the new `.env` (the new image lines, the operator's
     values and kept keys carried over); and the refusals — `--key-into` omitted, existing or inside `backups/`
     before any command, a value the new template or recipe requires named before anything stops (never the
     template's example), a failed backup switching nothing and starting the running release again;
  9. **`backup` / `restore`** — the order (stop, key out, capture, check, up / stop, restore, key in, up), the tool's
     Compose pointed at this instance with its credentials in the environment and in no argv, a tool that changed on
     disk refused before the app stops, the restored key on stdin of a one-off `app` container, the app left stopped
     after a failed restore;
 10. **`self-update`** — replaced by the installed release's `veyqctl` with its mode kept, nothing to do the second
     time, refused (nothing replaced) when the installed copy no longer matches its manifest.

What it cannot show: that Docker, cosign and the backup tool behave as the stand-in answers. That is the live runs' (the CR says
which). Not a `validate_*.py`: it asserts nothing about the repository, only about the tool. Runnable locally:

    python3 scripts/veyqctl_selftest.py
"""
from __future__ import annotations  # the repo's scripts run on Python 3.9

import contextlib
import hashlib
import io
import json
import os
import pathlib
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
                 fail_tool: str = "") -> None:
        self.commands: list[list[str]] = []
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
        self.envs.append(dict(env or {}))
        if stdin is not None:
            self.stdins.append(stdin)
        rc, out = 0, ""
        if cmd[0] == sys.executable and cmd[1].endswith(vc.BACKUP_ASSET):
            rc = 1 if cmd[2] == self.fail_tool else 0
            if cmd[2] == "restore" and not rc:  # the tool places leg (c) where veyqctl told it to
                key = cmd[cmd.index("--config-secret-file") + 1]
                pathlib.Path(key).write_bytes(b"restored-key")
        elif cmd[:2] == ["docker", "cp"]:
            pathlib.Path(cmd[3]).write_bytes(b"the-instance-key")
        elif cmd[:3] == ["docker", "ps", "-aq"]:
            out = "cid-app\n"
        elif cmd[:3] == ["docker", "image", "inspect"]:
            rc = 0 if cmd[-1] in self.local_images else 1
        elif cmd[0].endswith("cosign") and cmd[1] == "verify":
            rc = 0 if cmd[2] in self.signed else 1
        elif "config" in cmd and "--format" in cmd:
            out = json.dumps({
                "name": self.project,
                "services": {
                    "app": {"environment": {"VEYQ_SECURITY_CONFIG_SECRET_FILE": "/var/lib/veyq/config-secret.key",
                                            "VEYQ_BUNDLED_STORE_ENDPOINT": "http://garage:3900",
                                            "VEYQ_BUNDLED_STORE_ADMIN_TOKEN": STORE_TOKEN}},
                    "mariadb": {"environment": {"MARIADB_ROOT_PASSWORD": DB_ROOT}},
                    "garage": {"networks": {"edge": None}},
                },
                "networks": {"edge": {"name": f"{self.project}_edge"}},
            })
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

    def ran(self, *words: str) -> list[int]:
        """The indexes of every recorded command containing all of `words`."""
        return [i for i, c in enumerate(self.commands) if all(w in c for w in words)]


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_release(root: pathlib.Path, tag: str, *, prerelease=False, app="veyq:local", admin="veyq-admin:local",
                  files=None, manifest_edit=None) -> None:
    """One release in the mirror layout, as `release_assets.py` writes it."""
    files = files or {vc.COMPOSE_ASSET: COMPOSE, vc.TEMPLATE_ASSET: TEMPLATE, vc.BACKUP_ASSET: "# backup tool\n",
                      vc.SELF_ASSET: "# veyqctl\n", "garage.toml": "# garage\n"}
    d = root / tag
    d.mkdir(parents=True)
    for name, text in files.items():
        (d / name).write_text(text)
    manifest = {"release": tag[1:], "date": "2026-10-16", "images": {"app": app, "admin": admin},
                "unscheduled": False, "assets": {name: sha(text.encode()) for name, text in files.items()}}
    if manifest_edit:
        manifest_edit(manifest)
    (d / vc.MANIFEST_ASSET).write_text(json.dumps(manifest))
    index = root / vc.RELEASES_INDEX
    doc = json.loads(index.read_text()) if index.is_file() else {"releases": []}
    doc["releases"].append({"tag": tag, "date": "2026-10-16", "prerelease": prerelease,
                            "manifest": f"{tag}/{vc.MANIFEST_ASSET}"})
    index.write_text(json.dumps(doc))


def manifest_bytes(**changes) -> bytes:
    doc = {"release": "1.12", "date": "2026-10-16", "images": {"app": SIGNED_APP, "admin": SIGNED_ADMIN},
           "unscheduled": False, "assets": {a: "0" * 64 for a in vc.REQUIRED_ASSETS}}
    for key, value in changes.items():
        if value is None:
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
    """ADR 0096 decision 5 step 1, the resolution alone: where one `upgrade` from `running` goes."""
    def listed(*releases: str, pre: tuple = ()) -> list:
        return [vc.Listed(f"v{r}", *(int(x) for x in r.split(".")), r in pre) for r in releases]

    def goes(rel: list, running: str, selector: str) -> str:
        chosen = vc.select(rel, selector, "the mirror")
        return vc.upgrade_target(rel, tuple(int(x) for x in running.split(".")), chosen, "the mirror").release

    two = listed("1.3", "1.9", "2.2", "2.5")
    three = listed("1.3", "1.9", "2.2", "2.5", "3.1")
    # the control: within a major nothing stops on the way
    check("major entry: within a major, 1.3 → 1.9 directly (control)", goes(two, "1.3", "1") == "1.9")
    check("major entry: `latest` from 1.3 (1.9 the newest of major 1, 2.5 of all) stops on 1.9",
          goes(two, "1.3", "latest") == "1.9")
    check("major entry: a pin in the next major (2.5) from 1.3 stops on 1.9", goes(two, "1.3", "2.5") == "1.9")
    check("major entry: `2` from 1.3 stops on 1.9", goes(two, "1.3", "2") == "1.9")
    check("major entry: from 1.9, the last of major 1, a pin in major 2 (2.2) is reached directly",
          goes(two, "1.9", "2.2") == "2.2")
    check("major entry: from 1.9, `latest` within the next major (2.5) is reached directly",
          goes(two, "1.9", "latest") == "2.5")
    check("major entry: from 1.9 with 3.1 listed, `latest` enters major 2 only, at its newest (2.5)",
          goes(three, "1.9", "latest") == "2.5")
    check("major entry: from 1.9, a pin in major 3 (3.1) goes to the newest of major 2", goes(three, "1.9", "3.1") == "2.5")
    check("major entry: from 1.3 with 3.1 listed, `latest` stops on 1.9 first", goes(three, "1.3", "latest") == "1.9")
    check("major entry: a prerelease (1.10) is never the last of a major — from 1.9 `latest` goes to 2.5",
          goes(listed("1.3", "1.9", "1.10", "2.5", pre=("1.10",)), "1.9", "latest") == "2.5")
    gap = listed("1.9", "2.0", "3.1", pre=("2.0",))
    message = refused(lambda: goes(gap, "1.9", "latest"))
    check("major entry: a next major with only a prerelease is refused, naming it, never crossed",
          "no release of major 2" in message, message)


def test_manifest() -> None:
    m = vc.parse_manifest(manifest_bytes(), "v1.12")
    check("manifest: the fields veyqctl reads",
          (m.release, m.app, m.admin, m.unscheduled, sorted(m.assets)) ==
          ("1.12", SIGNED_APP, SIGNED_ADMIN, False, sorted(vc.REQUIRED_ASSETS)))
    cases = {
        "another release's manifest": (manifest_bytes(release="1.11"), "names release"),
        "an image missing": (manifest_bytes(images={"app": SIGNED_APP}), "both images"),
        "`unscheduled` not a boolean": (manifest_bytes(unscheduled="no"), "unscheduled"),
        "an asset name with `../`": (manifest_bytes(assets={"../veyqctl": "0" * 64}), "flat file name"),
        "an asset name with a directory": (manifest_bytes(assets={"conf/garage.toml": "0" * 64}), "flat file name"),
        "an asset without a sha256": (manifest_bytes(assets={**{a: "0" * 64 for a in vc.REQUIRED_ASSETS},
                                                             "garage.toml": "abc"}), "no sha256"),
        "a required asset missing": (manifest_bytes(assets={vc.COMPOSE_ASSET: "0" * 64}), "lacks"),
        "no asset list": (manifest_bytes(assets=None), "lists no"),
        "not JSON": (b"{", "not JSON"),
    }
    for what, (raw, needle) in cases.items():
        message = refused(vc.parse_manifest, raw, "v1.12")
        check(f"manifest: {what} is refused", needle in message, message or "accepted")


def test_assets_on_disk(tmp: pathlib.Path) -> None:
    mirror = tmp / "mirror-assets"
    write_release(mirror, "v1.3")
    source = vc.DirSource(mirror)
    chosen = vc.select(source.releases(), "1.3", "x")
    manifest = vc.fetch_manifest(source, chosen)
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
    message = refused(vc.fetch_release, source, chosen, vc.fetch_manifest(source, chosen), home)
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
    """v1.3 → v1.7 → v1.12 → v2.1, and a prerelease v2.2; each release names images of its own."""
    for tag, pre in (("v1.3", False), ("v1.7", False), ("v1.12", False), ("v2.1", False), ("v2.2", True)):
        write_release(root, tag, prerelease=pre, app=f"veyq:{tag[1:]}", admin=f"veyq-admin:{tag[1:]}")
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
        rc = vc.main(["--home", str(home), "check"], FakeHost(local_images=all_images(), project="veyq-proof"))
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
    place = host.commands[first("run", "--rm", "app")] if host.ran("run", "--rm", "app") else []
    check("restore: the running release's tool, onto the tenant domain given, with the key it was given",
          rc == 0 and ran[1] == str(tool) and ran[ran.index("--domain") + 1] == "family.veyq.test"
          and ran[ran.index("--key-from") + 1] == str((tmp / "b.key").absolute()), str(ran))
    check("restore: leg (c) goes into the app's volume through a one-off app container, on stdin, never an argv",
          place[:3] == ["docker", "compose", "-p"] and "--no-deps" in place
          and place[-1] == "/var/lib/veyq/config-secret.key" and host.stdins == [b"restored-key"]
          and not any("restored-key" in arg for arg in place), str(place))
    steps = [first("stop", "app"), first("restore"), first("run", "--rm", "app"), first("up")]
    check("restore: stop the app, restore, place the key, up — in that order", steps == sorted(steps), str(steps))
    check("restore: the key's copy in the home is gone afterwards",
          not any(p.name.startswith(".key-") for p in home.iterdir()))
    host = FakeHost(local_images=all_images(), project="veyq-proof", fail_tool="restore")
    with contextlib.redirect_stdout(out):
        rc = vc.main(["--home", str(home), "restore", str(capture_dir), "--domain", "family.veyq.test",
                      "--key-from", str(tmp / "b.key")], host)
    check("restore: a failed restore leaves the app STOPPED — no up on a half-restored instance",
          rc == 1 and not host.ran("up") and not host.ran("run", "--rm", "app"))


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


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="veyqctl-selftest-") as raw:
        tmp = pathlib.Path(raw)
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
        test_self_update(tmp)
    if failures:
        print(f"\nveyqctl self-test FAILED: {len(failures)} check(s)")
        return 1
    print("\nveyqctl self-test OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
