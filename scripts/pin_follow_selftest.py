#!/usr/bin/env python3
"""Offline self-test of `pin_follow.py`: each follow-up on a minimal fake tree in a temp directory, against forged
upstream answers — and the forgeries it must refuse: one Helm checksum source that disagrees with the other two (each
source once), workflows that pin different Helm versions, a JAR that does not match Central's `.sha1` or `.md5`,
surfaces that run different generator versions, a PyPI release with no wheel for the file. Also: a 429 and a 503 are
retried, and every refusal leaves the tree as it was. No network: the module's one fetch function is replaced.

    python3 scripts/pin_follow_selftest.py
"""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
import pathlib
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import pin_follow as pf  # noqa: E402

failures: list[str] = []
checks = 0
TODAY = "2031-02-03"
pf.today = lambda: TODAY
pf.sleep = lambda seconds: None


def check(label: str, ok: bool, detail: str = "") -> None:
    global checks
    checks += 1
    print(f"  {'ok  ' if ok else 'FAIL'} {label}")
    if not ok:
        failures.append(f"{label}{': ' + detail if detail else ''}")


# --- the fake upstream -------------------------------------------------------------------------------------------------

class Upstream:
    """URL → body (or a list of statuses/bodies served in turn). Digests are computed from the forged bytes, the way
    the real fetch streams them."""

    def __init__(self, routes: dict) -> None:
        self.routes = {url: list(v) if isinstance(v, list) else [v] for url, v in routes.items()}
        self.calls: list[str] = []
        self.headers: list[dict] = []

    def __call__(self, url: str, headers: dict, digests: tuple = ()) -> pf.Fetched:
        self.calls.append(url)
        self.headers.append(dict(headers))
        answers = self.routes.get(url)
        if not answers:
            return pf.Fetched(404)
        answer = answers.pop(0) if len(answers) > 1 else answers[0]
        if isinstance(answer, int):
            return pf.Fetched(answer, retry_after="1" if answer == 429 else "")
        if digests:
            return pf.Fetched(200, digests={n: hashlib.new(n, answer).hexdigest() for n in digests})
        return pf.Fetched(200, body=answer)


def quiet(fn, *args):
    """Run `fn`, swallowing its progress lines; return the Refusal it raised, or None."""
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        try:
            fn(*args)
        except pf.Refusal as refusal:
            return refusal
    return None


def snapshot(root: pathlib.Path) -> dict:
    return {p.relative_to(root).as_posix(): p.read_text() for p in sorted(root.rglob("*")) if p.is_file()}


def refuses(label: str, root: pathlib.Path, upstream: Upstream, fn, *args, says: str = "") -> None:
    pf.fetch = upstream
    before = snapshot(root)
    refusal = quiet(fn, *args)
    check(label, refusal is not None and says in str(refusal) and snapshot(root) == before,
          f"refusal={refusal!r}, tree unchanged={snapshot(root) == before}")


def write_tree(root: pathlib.Path, files: dict) -> None:
    for rel, text in files.items():
        (root / rel).parent.mkdir(parents=True, exist_ok=True)
        (root / rel).write_text(text)


def sha(n: int) -> str:
    return f"{n:064x}"


# --- 1. helm -----------------------------------------------------------------------------------------------------------

OLD_HELM, NEW_HELM = "v4.3.2", "v4.4.0"
TARBALL = b"forged helm tarball bytes"
TARBALL_SHA = hashlib.sha256(TARBALL).hexdigest()
FILENAME = f"helm-{NEW_HELM}-linux-amd64.tar.gz"
URL = pf.HELM_TARBALL.format(v=NEW_HELM)
RELEASE_URL = pf.HELM_RELEASE.format(v=NEW_HELM)


def workflow(version: str, comment: bool) -> str:
    head = ("# Helm for the `chart` job: checked against this SHA-256. Measured 2026-11-22: get.helm.sh's\n"
            f"# helm-{OLD_HELM}-linux-amd64.tar.gz.sha256sum, the tarball's own hash and the checksum in the GitHub\n"
            f"# release notes of helm/helm {OLD_HELM} all agree. Bumping it is part of cutting a release.\n") if comment else ""
    return (f"name: x\n{head}env:\n  HELM_VERSION: {version}\n  HELM_SHA256: {sha(1)}\n\njobs:\n"
            "  chart:\n    steps:\n      - run: echo \"${HELM_VERSION}\"\n")


def helm_tree(versions: tuple = (NEW_HELM, NEW_HELM, NEW_HELM)) -> dict:
    return {rel: workflow(v, rel == pf.HELM_MEASURED_IN) for rel, v in zip(pf.HELM_WORKFLOWS, versions)}


def release_body(other_sum: str = TARBALL_SHA) -> bytes:
    body = ("## Installation\n\n"
            f"- [MacOS arm64](https://get.helm.sh/helm-{NEW_HELM}-darwin-arm64.tar.gz) ([checksum](https://get.helm.sh/"
            f"helm-{NEW_HELM}-darwin-arm64.tar.gz.sha256sum) / {sha(7)})\n"
            f"- [Linux amd64](https://get.helm.sh/{FILENAME}) ([checksum](https://get.helm.sh/{FILENAME}.sha256sum) / "
            f"{other_sum})\n"
            f"- [Linux arm64](https://get.helm.sh/helm-{NEW_HELM}-linux-arm64.tar.gz) ([checksum](https://get.helm.sh/"
            f"helm-{NEW_HELM}-linux-arm64.tar.gz.sha256sum) / {sha(8)})\n")
    return json.dumps({"tag_name": NEW_HELM, "draft": False, "prerelease": False, "body": body}).encode()


def helm_upstream(sumfile: str = TARBALL_SHA, tarball: bytes = TARBALL, noted: str = TARBALL_SHA) -> Upstream:
    return Upstream({URL + ".sha256sum": f"{sumfile}  {FILENAME}\n".encode(), URL: tarball,
                     RELEASE_URL: release_body(noted)})


print("helm")
with tempfile.TemporaryDirectory() as scratch:
    root = pathlib.Path(scratch)
    write_tree(root, helm_tree())
    pf.fetch = upstream = helm_upstream()
    refusal = quiet(pf.follow_helm, root)
    after = snapshot(root)
    check("helm: the agreed sum is written to all three workflows",
          refusal is None and all(f"HELM_SHA256: {TARBALL_SHA}\n" in after[rel] for rel in pf.HELM_WORKFLOWS),
          str(refusal))
    measured = after[pf.HELM_MEASURED_IN]
    check("helm: checks.yml's Measured sentence names the new version and today",
          f"Measured {TODAY}: get.helm.sh's\n# helm-{NEW_HELM}-linux-amd64" in measured
          and f"helm/helm {NEW_HELM} all agree." in measured and OLD_HELM not in measured, measured)
    check("helm: the rest of the workflow is untouched",
          all(after[rel].replace(TARBALL_SHA, sha(1)) == workflow(NEW_HELM, False) for rel in pf.HELM_WORKFLOWS[1:]))
    check("helm: the tarball was fetched for its hash only", upstream.calls.count(URL) == 1)
    before = snapshot(root)
    quiet(pf.follow_helm, root)
    check("helm: a second run changes nothing (idempotent)", snapshot(root) == before)

    write_tree(root, helm_tree())
    refuses("helm FORGERY: get.helm.sh's .sha256sum disagrees with the other two", root,
            helm_upstream(sumfile=sha(9)), pf.follow_helm, root, says="disagree")
    refuses("helm FORGERY: the tarball's own hash disagrees with the other two", root,
            helm_upstream(tarball=b"other bytes"), pf.follow_helm, root, says="disagree")
    refuses("helm FORGERY: the GitHub release notes disagree with the other two", root,
            helm_upstream(noted=sha(9)), pf.follow_helm, root, says="disagree")
    write_tree(root, helm_tree((NEW_HELM, NEW_HELM, OLD_HELM)))
    refuses("helm: workflows pinning different versions are refused before any fetch", root, Upstream({}),
            pf.follow_helm, root, says="different Helm versions")
    check("helm: ... and nothing was fetched", pf.fetch.calls == [])
    write_tree(root, helm_tree())
    draft = Upstream({URL + ".sha256sum": f"{TARBALL_SHA}  {FILENAME}\n".encode(), URL: TARBALL,
                      RELEASE_URL: json.dumps({**json.loads(release_body()), "prerelease": True}).encode()})
    refuses("helm: a prerelease is refused", root, draft, pf.follow_helm, root, says="prerelease")
    refuses("helm: a version get.helm.sh does not serve is refused", root, Upstream({}), pf.follow_helm, root,
            says="HTTP 404")


# --- 2. openapi-generator-jar ------------------------------------------------------------------------------------------

JAR = b"forged generator jar"
JAR_URL = pf.CENTRAL_JAR.format(v="7.24.0")
JAR_NAME = "openapi-generator-cli-7.24.0.jar"
PIN_HEADER = "# The OpenAPI generator JAR, pinned by SHA-256.\n#\n# Format: `<version> <sha256>`, one per line.\n"


def generator_tree(web: str = "7.24.0", admin: str = "7.24.0", backend: str = "7.24.0", pins: str = "") -> dict:
    config = lambda v: json.dumps({"spaces": 2, "generator-cli": {"version": v, "storageDir": "x"}}, indent=2) + "\n"
    return {pf.GENERATOR_CONFIGS[0]: config(web), pf.GENERATOR_CONFIGS[1]: config(admin),
            pf.VERSION_CATALOG: f'[libraries]\nopenapi-generator = "org.openapitools:openapi-generator:{backend}"\n',
            pf.JAR_PIN_FILE: PIN_HEADER + (pins or f"7.23.0 {sha(5)}\n")}


def jar_upstream(sha1: str = "", md5: str = "") -> Upstream:
    return Upstream({JAR_URL: JAR,
                     JAR_URL + ".sha1": (sha1 or hashlib.sha1(JAR).hexdigest()).encode(),
                     JAR_URL + ".md5": f"{md5 or hashlib.md5(JAR).hexdigest()}  {JAR_NAME}\n".encode()})


print("openapi-generator-jar")
with tempfile.TemporaryDirectory() as scratch:
    root = pathlib.Path(scratch)
    write_tree(root, generator_tree())
    pf.fetch = jar_upstream()
    refusal = quiet(pf.follow_generator_jar, root)
    pins = (root / pf.JAR_PIN_FILE).read_text()
    check("jar: the new version's line is appended and the unnamed old one dropped, header kept",
          refusal is None and pins == PIN_HEADER + f"7.24.0 {hashlib.sha256(JAR).hexdigest()}\n", f"{refusal!r} {pins!r}")
    pf.fetch = Upstream({})
    check("jar: a second run fetches nothing and changes nothing (idempotent)",
          quiet(pf.follow_generator_jar, root) is None and pf.fetch.calls == []
          and (root / pf.JAR_PIN_FILE).read_text() == pins)

    write_tree(root, generator_tree())
    refuses("jar FORGERY: the JAR does not match Central's .sha1", root, jar_upstream(sha1="0" * 40),
            pf.follow_generator_jar, root, says="SHA-1")
    refuses("jar FORGERY: the JAR does not match Central's .md5", root, jar_upstream(md5="0" * 32),
            pf.follow_generator_jar, root, says="MD5")
    write_tree(root, generator_tree(admin="7.23.0"))
    refuses("jar: src/admin-web/ running another version is refused", root, jar_upstream(),
            pf.follow_generator_jar, root, says="different generator versions")
    write_tree(root, generator_tree(backend="7.23.0"))
    refuses("jar: the backend's catalog naming another version is refused", root, jar_upstream(),
            pf.follow_generator_jar, root, says="different generator versions")
    write_tree(root, generator_tree(pins="7.24.0 nothex\n"))
    refuses("jar: a malformed pin line is refused", root, jar_upstream(), pf.follow_generator_jar, root,
            says="no `<version> <sha256>`")

    write_tree(root, generator_tree())
    flaky = jar_upstream()
    flaky.routes[JAR_URL] = [429, 503, JAR]
    pf.fetch = flaky
    refusal = quiet(pf.follow_generator_jar, root)
    check("retry: a 429 then a 503 from Central are retried until the JAR comes",
          refusal is None and flaky.calls.count(JAR_URL) == 3
          and f"7.24.0 {hashlib.sha256(JAR).hexdigest()}" in (root / pf.JAR_PIN_FILE).read_text(), repr(refusal))
    write_tree(root, generator_tree())
    refuses("retry: an upstream that never stops answering 429 is refused after the last attempt", root,
            Upstream({JAR_URL + ".sha1": 429}), pf.follow_generator_jar, root, says="HTTP 429")
    check("retry: ... after exactly ATTEMPTS tries", pf.fetch.calls.count(JAR_URL + ".sha1") == pf.ATTEMPTS)


# --- 3. pypi-hashes ----------------------------------------------------------------------------------------------------

REQ = "scripts/requirements-x.txt"
REQ_HEADER = ("# Installed only as these exact wheels.\n#\n"
              "# PyYAML 6.0.3, the manylinux wheels for x86_64 and aarch64. The hashes are PyPI's own, read from\n"
              "# its JSON metadata (no package downloaded), on 2026-11-06:\n")
PYPI_URL = pf.PYPI_RELEASE.format(name="PyYAML", version="6.0.4")


def pypi_release(files: list, version: str = "6.0.4", yanked: bool = False) -> bytes:
    return json.dumps({"info": {"version": version, "yanked": yanked},
                       "urls": [{"filename": f, "digests": {"sha256": sha(n)}} for f, n in files]}).encode()


WHEELS = [
    ("pyyaml-6.0.4-cp310-cp310-manylinux2014_x86_64.manylinux_2_17_x86_64.whl", 21),
    ("pyyaml-6.0.4-cp39-cp39-manylinux2014_x86_64.manylinux_2_17_x86_64.whl", 12),
    ("pyyaml-6.0.4-cp39-cp39-manylinux2014_aarch64.manylinux_2_17_aarch64.whl", 11),
    ("pyyaml-6.0.4-cp310-cp310-manylinux2014_aarch64.manylinux_2_17_aarch64.whl", 20),
    ("pyyaml-6.0.4-cp38-cp38-manylinux2014_x86_64.manylinux_2_17_x86_64.whl", 99),  # older than any runner
    ("pyyaml-6.0.4-cp310-cp310-musllinux_1_1_x86_64.whl", 98),  # not manylinux
    ("pyyaml-6.0.4-cp310-cp310-macosx_11_0_arm64.whl", 97),  # not linux
    ("pyyaml-6.0.4-cp310-cp310-manylinux_2_17_i686.whl", 96),  # not x86_64 / aarch64
    ("PyYAML-6.0.4.tar.gz", 95),  # the sdist
]


print("pypi-hashes")
with tempfile.TemporaryDirectory() as scratch:
    root = pathlib.Path(scratch)
    bumped = REQ_HEADER + f"PyYAML==6.0.4 \\\n    --hash=sha256:{sha(1)} \\\n    --hash=sha256:{sha(2)}\n"
    write_tree(root, {REQ: bumped})
    pf.fetch = Upstream({PYPI_URL: pypi_release(WHEELS)})
    refusal = quiet(pf.follow_pypi_hashes, root, REQ)
    expected = (REQ_HEADER.replace("PyYAML 6.0.3", "PyYAML 6.0.4").replace("on 2026-11-06", f"on {TODAY}")
                + "PyYAML==6.0.4 \\\n" + "".join(f"    --hash=sha256:{sha(n)} \\\n" for n in (11, 12, 20))
                + f"    --hash=sha256:{sha(21)}\n")
    got = (root / REQ).read_text()
    check("pypi: the hash lines become exactly PyPI's manylinux x86_64/aarch64 set, by Python version; header moves",
          refusal is None and got == expected, f"{refusal!r}\n{got}")
    check("pypi: a second run changes nothing (idempotent, the date included)",
          quiet(pf.follow_pypi_hashes, root, REQ) is None and (root / REQ).read_text() == expected)

    write_tree(root, {REQ: bumped})
    refuses("pypi: a version with no such wheel (an empty set) is refused", root,
            Upstream({PYPI_URL: pypi_release(WHEELS[4:])}), pf.follow_pypi_hashes, root, REQ, says="no manylinux")
    write_tree(root, {REQ: bumped})
    refuses("pypi: a yanked version is refused", root, Upstream({PYPI_URL: pypi_release(WHEELS, yanked=True)}),
            pf.follow_pypi_hashes, root, REQ, says="yanked")
    write_tree(root, {REQ: REQ_HEADER + "PyYAML==6.0.4\n"})
    refuses("pypi: a requirement without hashes is refused", root, Upstream({PYPI_URL: pypi_release(WHEELS)}),
            pf.follow_pypi_hashes, root, REQ, says="no `Name==version")
    write_tree(root, {REQ: bumped.rstrip("\n") + " \\\n"})
    refuses("pypi: hash lines that never end are refused", root, Upstream({PYPI_URL: pypi_release(WHEELS)}),
            pf.follow_pypi_hashes, root, REQ, says="ending without")


# --- the command line --------------------------------------------------------------------------------------------------

print("command line")
with tempfile.TemporaryDirectory() as scratch:
    root = pathlib.Path(scratch)
    write_tree(root, helm_tree())
    pf.fetch = helm_upstream(noted=sha(9))
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()) as err:
        code = pf.main(["--root", str(root), "helm"])
    check("cli: a refusal exits 1 and says REFUSED", code == 1 and "REFUSED" in err.getvalue(), err.getvalue())
    pf.fetch = helm_upstream()
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        code = pf.main(["--root", str(root), "helm"])
    check("cli: a good run exits 0", code == 0 and TARBALL_SHA in (root / pf.HELM_WORKFLOWS[2]).read_text())

    write_tree(root, helm_tree())
    token = "ghs_selftest_token_never_printed"
    pf.os.environ["GITHUB_TOKEN"] = token
    pf.fetch = upstream = helm_upstream(noted=sha(9))
    with contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()) as err:
        pf.main(["--root", str(root), "helm"])
    del pf.os.environ["GITHUB_TOKEN"]
    sent = {url: h.get("Authorization", "") for url, h in zip(upstream.calls, upstream.headers)}
    check("cli: the GitHub token goes to api.github.com only, as a bearer token, and is never printed",
          sent[RELEASE_URL] == f"Bearer {token}" and sent[URL] == "" and sent[URL + ".sha256sum"] == ""
          and token not in out.getvalue() + err.getvalue(), str(sent))


if failures:
    print(f"PIN FOLLOW SELF-TEST FAILED ({len(failures)} of {checks}):")
    for f in failures:
        print("  -", f)
    sys.exit(1)
print(f"pin follow self-test OK: {checks} checks")
