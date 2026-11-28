#!/usr/bin/env python3
"""Offline self-test of `scripts/archive_backup.py`'s pure parts: the archive's reference and the refs it runs from, how
a registry's answer is read, the deterministic tar, `archive.json` and the manifest, and `pull`'s refusals — the last
against a stand-in `oras` serving a directory as the registry (it refuses what the real client refuses: a reference
it does not hold is `not found`, exit 1). No Docker, no network. `checks.yml`'s `guards` job runs it.

    python3 scripts/archive_backup_selftest.py
"""
from __future__ import annotations

import hashlib
import io
import json
import os
import pathlib
import stat
import sys
import tarfile
import tempfile
import textwrap

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import archive_backup as ab  # noqa: E402

failures: list[str] = []


def expect(label: str, ok: bool, detail: str = "") -> None:
    if not ok:
        failures.append(f"{label}{': ' + detail if detail else ''}")


def red(fn, *args) -> str:
    """The message `fn(*args)` is red with, or '' when it is not."""
    try:
        fn(*args)
    except ab.Red as error:
        return str(error)
    return ""


# ---- the reference, and the refs a run may write from

ref = ab.artifact_ref("v1.318", "ghcr.io/raddatzk")
expect("the reference", str(ref) == "ghcr.io/raddatzk/veyq-backups:1.318", str(ref))
expect("a digest reference", ref.at("sha256:" + "a" * 64) == "ghcr.io/raddatzk/veyq-backups@sha256:" + "a" * 64)
expect("major 0's reference", str(ab.artifact_ref("v0.2480", "localhost:5000/x")) == "localhost:5000/x/veyq-backups:0.2480")
for bad in ("1.318", "v1", "v1.318.1", "snapshot-1.318", "v1.318-SNAPSHOT", "", "v1.x"):
    expect(f"the tag {bad!r} is refused", "no release tag" in red(ab.artifact_ref, bad, "ghcr.io/raddatzk"))
for bad in ("GHCR.io/x", "ghcr.io/x/", "https://ghcr.io/x", ""):
    expect(f"the registry {bad!r} is refused", "no registry" in red(ab.artifact_ref, "v1.318", bad))
for good in (None, "refs/heads/main", "refs/heads/release/1.x", "refs/heads/release/12.x"):
    expect(f"the ref {good} may write", red(ab.check_ref, good) == "")
for bad in ("refs/heads/feature", "refs/heads/mainx", "refs/heads/release/1.y", "refs/pull/1/merge",
            "refs/tags/v1.318", "refs/heads/release/1.x/evil"):
    expect(f"the ref {bad} is refused", "nothing was written" in red(ab.check_ref, bad))

# ---- the identity: archive.yml's, on the branches a release is promoted from, never release.yml's

import re  # noqa: E402

identity = re.compile(ab.IDENTITY_REGEXP)
san = "https://github.com/raddatzk/veyq/.github/workflows/{}@refs/heads/{}"
for branch in ("main", "release/1.x"):
    expect(f"the identity accepts archive.yml on {branch}", identity.search(san.format("archive.yml", branch)) is not None)
for workflow, branch in (("release.yml", "main"), ("publish.yml", "main"), ("archive.yml", "feature"),
                         ("archive.ymlx", "main")):
    expect(f"the identity rejects {workflow} on {branch}", identity.search(san.format(workflow, branch)) is None)
expect("the workflow file the identity names exists", (ab.ROOT / ab.ARCHIVE_WORKFLOW).is_file())

# ---- a registry's answer

DESCRIPTOR = json.dumps({"mediaType": ab.MANIFEST_MEDIA_TYPE, "digest": "sha256:" + "b" * 64, "size": 10})
expect("a tag that exists is present", ab.classify(0, DESCRIPTOR, "") == ("present", "sha256:" + "b" * 64, DESCRIPTOR))


def state_of(returncode: int, stdout: str, stderr: str) -> str:
    """The state `classify` reads, or `red: <why>` — so a lever that turns an answer red names the case it broke."""
    try:
        return ab.classify(returncode, stdout, stderr).state
    except ab.Red as error:
        return f"red: {error}"


for label, said, want in (
        ("oras's not found is absent", 'Error response from registry: failed to find "x:1.0": x:1.0: not found', "absent"),
        ("MANIFEST_UNKNOWN is absent", "MANIFEST_UNKNOWN: manifest unknown", "absent"),
        # Measured 2026-11-27: GHCR answers an anonymous read of a repository that does not exist this way.
        ("GHCR's denied is unknown", "Error response from registry: denied: requested access to the resource is denied",
         "unknown")):
    got = state_of(1, "", said)
    expect(label, got == want, got)
expect("a refused connection is red", "other than" in red(ab.classify, 1, "", 'Get "http://localhost:1/v2/": dial tcp: '
                                                                              "connect: connection refused"))
expect("a present tag without a digest is red", "no sha256 digest" in red(ab.classify, 0, "{}", ""))

# ---- the tar, archive.json and the manifest


def content_tree(root: pathlib.Path, extra: str | None = None) -> pathlib.Path:
    content = root / "content"
    (content / ab.CAPTURE_DIR).mkdir(parents=True)
    (content / ab.CAPTURE_DIR / "manifest.json").write_text('{"domains": ["veyq.test"]}')
    (content / ab.CAPTURE_DIR / "database.sql").write_text("-- dump\n")
    (content / ab.POOL_DIR / "veyq-pool" / "captures").mkdir(parents=True)
    (content / ab.POOL_DIR / "blob-1").write_bytes(b"\x00\x01ciphertext")
    (content / ab.POOL_DIR / "veyq-pool" / "captures" / "c1").write_text("blob-1\n")
    (content / ab.KEY_FILE).write_bytes(b"k" * 32)
    os.chmod(content / ab.KEY_FILE, 0o600)
    (content / ab.RECORD_FILE).write_text('{"version": 1}')
    if extra:
        (content / extra).write_text("x")
    return content


with tempfile.TemporaryDirectory() as tmp:
    base = pathlib.Path(tmp)
    one, two = base / "one", base / "two"
    files_one = ab.pack(content_tree(one), one / "a.tar")
    files_two = ab.pack(content_tree(two), two / "a.tar")
    expect("the same files pack to the same bytes", (one / "a.tar").read_bytes() == (two / "a.tar").read_bytes())
    expect("every file is hashed by its path", sorted(files_one) == [
        "capture/database.sql", "capture/manifest.json", "config-secret.key", "pool/blob-1",
        "pool/veyq-pool/captures/c1", "upgrade-record.json"], str(sorted(files_one)))
    expect("the hash is the file's", files_one["config-secret.key"] == hashlib.sha256(b"k" * 32).hexdigest())
    with tarfile.open(one / "a.tar") as tar:
        members = tar.getmembers()
        expect("no entry carries a time or an owner",
               all(m.mtime == 0 and m.uid == 0 and m.gid == 0 and not m.uname and not m.gname for m in members))
        expect("the entries are sorted", [m.name for m in members] == sorted(m.name for m in members))
        expect("the key keeps its mode", stat.S_IMODE(tar.getmember("config-secret.key").mode) == 0o600)
    (one / "content" / ab.POOL_DIR / "blob-1").write_bytes(b"\x00\x01ciphertexT")
    ab.pack(one / "content", one / "b.tar")
    expect("one changed byte changes the tar", (one / "b.tar").read_bytes() != (two / "a.tar").read_bytes())
    three = base / "three"
    expect("a fifth member is refused", "not exactly" in red(ab.pack, content_tree(three, "stray.txt"), three / "a.tar"))
    four = base / "four"
    tree4 = content_tree(four)
    os.symlink("/etc/passwd", tree4 / ab.CAPTURE_DIR / "link")
    expect("a link is refused", "neither a file nor a directory" in red(ab.pack, tree4, four / "a.tar"))

    doc = ab.archive_document("v1.318", "V202611221402", "veyq.test", 1, {"veyq-admin": "sha256:2", "veyq": "sha256:1"},
                              files_two)
    expect("archive.json names the release", (doc["release"], doc["tag"], doc["schema"]) == ("1.318", "v1.318", 1))
    expect("archive.json names the domain and the migration", (doc["domain"], doc["highestMigration"])
           == ("veyq.test", "V202611221402"))
    expect("archive.json passes its own check", ab.check_archive(json.loads(ab.canonical(doc))) is not None)
    for key in ("domain", "highestMigration", "release"):
        broken = {k: v for k, v in doc.items() if k != key}
        expect(f"archive.json without `{key}` is refused", f"`{key}`" in red(ab.check_archive, broken))
    expect("archive.json of another schema is refused", "schema" in red(ab.check_archive, {**doc, "schema": 2}))
    expect("archive.json without files is refused", "`files`" in red(ab.check_archive, {**doc, "files": {}}))
    archive_bytes = ab.canonical(doc)
    raw = ab.manifest(archive_bytes, two / "a.tar", "1.318")
    expect("the manifest is a function of its files", raw == ab.manifest(ab.canonical(dict(doc)), two / "a.tar", "1.318"))
    parsed = json.loads(raw)
    expect("the manifest's type", (parsed["mediaType"], parsed["artifactType"], parsed["config"]["mediaType"])
           == (ab.MANIFEST_MEDIA_TYPE, ab.ARTIFACT_TYPE, ab.EMPTY_MEDIA_TYPE))
    expect("the config is the empty blob's", parsed["config"]["digest"]
           == "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a")
    expect("no timestamp in the manifest", "created" not in raw.decode())
    archive_layer, content_layer = ab.check_manifest(raw)
    expect("the content layer is the tar", content_layer["digest"] == "sha256:" + ab.sha256_file(two / "a.tar"))
    expect("the archive layer is archive.json", archive_layer["digest"]
           == "sha256:" + hashlib.sha256(archive_bytes).hexdigest())
    for label, mutate in (
            ("another artifact type", lambda d: d.update(artifactType="application/vnd.other")),
            ("one layer", lambda d: d.update(layers=d["layers"][:1])),
            ("an untitled layer", lambda d: d["layers"][1].pop("annotations")),
            ("a layer of another type", lambda d: d["layers"][0].update(mediaType="application/json"))):
        forged = json.loads(raw)
        mutate(forged)
        expect(f"a manifest with {label} is refused", red(ab.check_manifest, json.dumps(forged).encode()) != "")

    # ---- unpack and check: the round trip, and what a forged tar or a moved file is
    out = base / "out"
    ab.unpack(two / "a.tar", out)
    expect("the round trip gives back every file", red(ab.check_files, out, files_two) == "")
    expect("the round trip gives back the bytes", (out / "pool" / "blob-1").read_bytes() == b"\x00\x01ciphertext")
    expect("the round trip keeps the key's mode", stat.S_IMODE((out / ab.KEY_FILE).stat().st_mode) == 0o600)
    (out / "pool" / "blob-1").write_bytes(b"other")
    expect("a changed file is named", "changed pool/blob-1" in red(ab.check_files, out, files_two))
    (out / "pool" / "blob-1").unlink()
    expect("a missing file is named", "missing pool/blob-1" in red(ab.check_files, out, files_two))
    (out / "pool" / "stray").write_text("x")
    expect("an undescribed file is named", "not described pool/stray" in red(ab.check_files, out, files_two))
    for label, name, kind in (("an entry outside the directory", "../escape", tarfile.REGTYPE),
                              ("an absolute entry", "/tmp/escape", tarfile.REGTYPE),
                              ("a link", "capture/link", tarfile.SYMTYPE)):
        forged = base / f"forged-{kind!r}-{len(name)}.tar"
        with tarfile.open(forged, "w") as tar:
            info = tarfile.TarInfo(name)
            info.type = kind
            if kind == tarfile.SYMTYPE:
                info.linkname = "/etc/passwd"
                tar.addfile(info)
            else:
                info.size = 1
                tar.addfile(info, io.BytesIO(b"x"))
        expect(f"a tar with {label} is refused", red(ab.unpack, forged, base / f"into-{len(name)}-{kind!r}") != "")

    # ---- pull, against a stand-in oras serving a directory (blobs/<hex>, tags/<tag>)
    registry = base / "registry"
    (registry / "blobs").mkdir(parents=True)
    (registry / "tags").mkdir()

    def put(data: bytes) -> str:
        hexd = hashlib.sha256(data).hexdigest()
        (registry / "blobs" / hexd).write_bytes(data)
        return f"sha256:{hexd}"

    stub = base / "oras"
    stub.write_text(textwrap.dedent(f"""\
        #!{sys.executable}
        import pathlib, shutil, sys
        store = pathlib.Path({str(registry)!r})
        args = [a for a in sys.argv[1:] if a != "--plain-http"]
        out = pathlib.Path(args[args.index("--output") + 1])
        target = args[2]
        if "@" in target:
            source = store / "blobs" / target.split("@sha256:", 1)[1]
        else:
            source = store / "tags" / target.rsplit(":", 1)[1]
        if args[:2] not in (["manifest", "fetch"], ["blob", "fetch"]) or not source.is_file():
            sys.stderr.write("Error response from registry: " + target + ": not found\\n")
            sys.exit(1)
        shutil.copyfile(source, out)
        """))
    stub.chmod(0o755)
    os.environ["VEYQ_ORAS"] = str(stub)
    put(archive_bytes)
    put((two / "a.tar").read_bytes())
    digest = put(raw)
    (registry / "tags" / "1.318").write_bytes(raw)
    got = ab.pull(f"reg/veyq-backups@{digest}", base / "pulled", False)
    expect("pull by digest reads archive.json", got.get("domain") == "veyq.test")
    expect("pull gives back the files", (base / "pulled" / "content" / ab.KEY_FILE).read_bytes() == b"k" * 32)
    expect("pull by tag", red(ab.pull, "reg/veyq-backups:1.318", base / "pulled-tag", False) == "")
    expect("pull of a tag that is not there is red", "not found" in red(ab.pull, "reg/veyq-backups:1.0", base / "p0", False))
    (registry / "blobs" / ("%s" % hashlib.sha256(raw + b" ").hexdigest())).write_bytes(raw)
    expect("a manifest served under another digest is red",
           "does not hash" in red(ab.pull, "reg/veyq-backups@sha256:" + hashlib.sha256(raw + b" ").hexdigest(),
                                  base / "p2", False))
    content_hex = content_layer["digest"].split(":", 1)[1]
    (registry / "blobs" / content_hex).write_bytes((one / "b.tar").read_bytes())
    expect("a layer whose bytes are not its digest is red", "descriptor says" in red(ab.pull, f"reg/veyq-backups@{digest}",
                                                                                     base / "p3", False))

if failures:
    print("ARCHIVE BACKUP SELFTEST FAILED:")
    for f in failures:
        print("  -", f)
    sys.exit(1)
print("archive backup selftest OK: the reference and the refs that may write, the archive identity, the registry's "
      "answers (present, absent, unknown, red), one tar for the same files with no time or owner, archive.json and the "
      "manifest as functions of their files, and pull's refusals (a forged manifest, tar, layer or file)")
