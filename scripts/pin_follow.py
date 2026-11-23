#!/usr/bin/env python3
"""What has to move with a version that Renovate bumped and that no Renovate manager writes (ADR 0158 decision 4).

Renovate's `postUpgradeTasks` run this, one subcommand per kind of follow-up, after Renovate edited the tree;
`renovate.json5` names each call exactly in `allowedCommands`. Every subcommand reads the NEW version from the tree
Renovate already edited, obtains the value that must move with it from the upstream's own published bytes, and writes
it in place. Run twice, it writes the same bytes (the dates it records aside, which only move when a value does or
when `helm` re-measures). Standard library only: it runs in Renovate's container (Python 3.12, no pip packages) and
on the repository's Python 3.9. No language model anywhere, and no release-note prose is read beyond the one checksum
`helm` parses out of it.

  1. **helm** — the Helm tarball pin: `HELM_VERSION` and `HELM_SHA256` in the `env:` of `checks.yml`, `publish.yml`
     and `release.yml` (`validate_release_identity.py` holds the three equal). The SHA-256 of
     `helm-<v>-linux-amd64.tar.gz` is obtained three ways and taken only when all three agree: get.helm.sh's
     `.sha256sum`, the hash of the tarball itself (streamed, never kept), and the line naming that file in the GitHub
     release notes of `helm/helm` `<v>`. It is written to all three workflows, and checks.yml's "Measured <date>: …"
     sentence is moved to `<v>` and today's UTC date.
  2. **openapi-generator-jar** — `scripts/openapi_generator_jar.sha256` (ADR 0057 § 6). The version the three surfaces
     run (`src/web/` and `src/admin-web/` `openapitools.json`, the backend's version catalog) gets its line: the JAR is
     downloaded from Maven Central, must match Central's `.sha1` and `.md5` for the same path, and its SHA-256 is
     appended. Lines for versions nothing names any more are dropped. A version that already has a line is left alone.
  3. **pypi-hashes FILE** — a hashed requirements file in the form of `scripts/requirements-release.txt`: each
     `Name==version \\` block's `--hash=sha256:` lines become exactly the set PyPI's JSON metadata publishes for that
     version's manylinux x86_64 and aarch64 wheels (no package downloaded), ordered by Python version. The header's
     `Name X.Y` and `on <date>` move with it.

**What makes it refuse** (exit 1, nothing written), each a case in `pin_follow_selftest.py`: the three workflows name
different Helm versions, or one names none or two; the three Helm checksum sources disagree, or one is missing, or
the release is a draft or a prerelease; the three surfaces name different generator versions; the JAR does not match
Central's `.sha1` or `.md5`; a requirements block whose version PyPI publishes no such wheel for, a yanked version, or a
line in the file that is no hashed requirement. An upstream answering 429 or 5xx is retried with backoff (Maven
Central rate-limits CI) before it counts as a refusal.

**Its limits — what a green run does NOT prove.** All three Helm sources are served by the Helm project; they agree
when nobody tampered with one of them in transit or at rest separately, not when the project itself was compromised —
the tarball's gpg signature is not checked. The JAR's `.asc` continuity check that the pin file's header describes is
NOT done here (no gpg in Renovate's container): the new line is trust-on-first-write, checked only against Central's
own `.sha1`/`.md5`, and the header's record of how the previous line was obtained is not rewritten. The PyPI hashes are
PyPI's word for its own files, exactly as the requirements file's header says. Prose in a header beyond the version
and the date (which CPython versions the wheels cover, say) is not rewritten.

    python3 scripts/pin_follow.py [--root DIR] helm
    python3 scripts/pin_follow.py [--root DIR] openapi-generator-jar
    python3 scripts/pin_follow.py [--root DIR] pypi-hashes FILE

`--root` (default: this repository) is the tree to read and write; FILE is relative to it.
"""
from __future__ import annotations  # `str | None` below — the repo's scripts run on Python 3.9

import argparse
import dataclasses
import datetime
import hashlib
import json
import os
import pathlib
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Callable, Iterable

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import repo_layout  # noqa: E402

USER_AGENT = "veyq-pin-follow"
MAX_BODY = 4 * 1024 * 1024  # a checksum file, a release, a PyPI JSON — never more; the tarball and the JAR are hashed
ATTEMPTS = 5
MAX_DELAY = 60

# --- where things are (repository-relative) ----------------------------------------------------------------------------

# The three workflows that carry the Helm pin; checks.yml's comment says how the pin was measured.
HELM_WORKFLOWS = (".github/workflows/checks.yml", ".github/workflows/publish.yml", ".github/workflows/release.yml")
HELM_MEASURED_IN = HELM_WORKFLOWS[0]
GENERATOR_CONFIGS = (repo_layout.rel(repo_layout.WEB / "openapitools.json"),
                     repo_layout.rel(repo_layout.ADMIN_WEB / "openapitools.json"))
VERSION_CATALOG = repo_layout.rel(repo_layout.BACKEND / "gradle/libs.versions.toml")
JAR_PIN_FILE = "scripts/openapi_generator_jar.sha256"

# --- upstream addresses ------------------------------------------------------------------------------------------------

HELM_TARBALL = "https://get.helm.sh/helm-{v}-linux-amd64.tar.gz"
HELM_RELEASE = "https://api.github.com/repos/helm/helm/releases/tags/{v}"
CENTRAL_JAR = ("https://repo1.maven.org/maven2/org/openapitools/openapi-generator-cli/{v}/"
               "openapi-generator-cli-{v}.jar")
PYPI_RELEASE = "https://pypi.org/pypi/{name}/{version}/json"

HELM_VERSION = re.compile(r"v\d+\.\d+\.\d+")
GENERATOR_VERSION = re.compile(r"\d+\.\d+\.\d+")
HEX64 = re.compile(r"[0-9a-f]{64}")


class Refusal(Exception):
    """Something disagrees: nothing is written, the command exits 1 with this message."""


def today() -> str:
    """Today's UTC date, `YYYY-MM-DD` — the date a measurement records (the selftest pins it)."""
    return datetime.datetime.now(datetime.timezone.utc).date().isoformat()


# --- HTTP: one fetch function (the selftest replaces it), and the retries around it -----------------------------------

@dataclasses.dataclass
class Fetched:
    status: int  # 0: no HTTP answer at all (DNS, connection, timeout)
    body: bytes = b""
    digests: "dict[str, str]" = dataclasses.field(default_factory=dict)
    retry_after: str = ""
    error: str = ""


def fetch(url: str, headers: "dict[str, str]", digests: "tuple[str, ...]" = ()) -> Fetched:
    """One GET of `url`. With `digests`, the body is streamed through those hashes and not kept; without, it is read
    whole (at most MAX_BODY bytes). Never raises for an HTTP status or a network failure — `get` decides."""
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, **headers})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:  # noqa: S310 — fixed https URLs only
            if digests:
                hashers = {name: hashlib.new(name, usedforsecurity=False) for name in digests}
                while True:
                    chunk = response.read(1 << 16)
                    if not chunk:
                        break
                    for hasher in hashers.values():
                        hasher.update(chunk)
                return Fetched(response.status, digests={name: h.hexdigest() for name, h in hashers.items()})
            body = response.read(MAX_BODY + 1)
            if len(body) > MAX_BODY:
                raise Refusal(f"{url} answered with more than {MAX_BODY} bytes — not what this tool reads there")
            return Fetched(response.status, body=body)
    except urllib.error.HTTPError as exc:
        retry_after = exc.headers.get("Retry-After", "") if exc.headers else ""
        return Fetched(exc.code, retry_after=retry_after)
    except (urllib.error.URLError, OSError) as exc:  # a timeout is an OSError
        return Fetched(0, error=str(getattr(exc, "reason", exc)))


sleep: Callable[[float], None] = time.sleep  # the selftest replaces it


def retryable(status: int) -> bool:
    return status == 0 or status == 429 or 500 <= status <= 599


def get(url: str, headers: "dict[str, str] | None" = None, digests: "tuple[str, ...]" = ()) -> Fetched:
    """`fetch`, retried with backoff while the answer is 429, 5xx or none; returns the last answer."""
    delay = 2.0
    for attempt in range(1, ATTEMPTS + 1):
        got = fetch(url, headers or {}, digests)
        if not retryable(got.status) or attempt == ATTEMPTS:
            return got
        wait = delay
        if got.retry_after.strip().isdigit():
            wait = float(int(got.retry_after.strip()))
        print(f"  {url} answered {got.status or got.error}; retry {attempt} of {ATTEMPTS - 1} in {min(wait, MAX_DELAY):.0f}s",
              file=sys.stderr)
        sleep(min(wait, MAX_DELAY))
        delay *= 2
    raise AssertionError("unreachable")


def get_ok(url: str, what: str, headers: "dict[str, str] | None" = None,
           digests: "tuple[str, ...]" = ()) -> Fetched:
    got = get(url, headers, digests)
    if got.status != 200:
        answer = f"HTTP {got.status}" if got.status else f"no answer ({got.error})"
        raise Refusal(f"{what}: {url} gave {answer} — not published (yet), or the upstream is down")
    return got


def checksum_file(body: bytes, length: int, filename: str, what: str) -> str:
    """The hex digest of a `sha256sum`-style file: `<hex>` alone, or `<hex>  <filename>` naming exactly that file."""
    try:
        tokens = body.decode("ascii").split()
    except UnicodeDecodeError:
        raise Refusal(f"{what} is not ASCII") from None
    digest = tokens[0].lower() if tokens else ""
    if not re.fullmatch(f"[0-9a-f]{{{length}}}", digest):
        raise Refusal(f"{what} does not start with a {length}-hex digest: {body[:120]!r}")
    if len(tokens) > 2 or (len(tokens) == 2 and tokens[1].lstrip("*") != filename):
        raise Refusal(f"{what} names another file than {filename}: {body[:200]!r}")
    return digest


def read(root: pathlib.Path, rel: str) -> str:
    path = root / rel
    if not path.is_file():
        raise Refusal(f"{rel} is missing under {root}")
    return path.read_text(encoding="utf-8")


def write_all(root: pathlib.Path, new: "dict[str, str]") -> None:
    """Write every changed file — only after every value was obtained and checked, so a refusal writes nothing."""
    for rel, text in new.items():
        path = root / rel
        if path.read_text(encoding="utf-8") != text:
            path.write_text(text, encoding="utf-8")
            print(f"  wrote {rel}")
        else:
            print(f"  {rel} already says so")


# --- 1. helm -----------------------------------------------------------------------------------------------------------

def env_line(text: str, key: str, rel: str) -> re.Match:
    found = list(re.finditer(rf"^(?P<prefix>[ \t]*{key}:[ \t]*)(?P<value>\S*)[ \t]*$", text, re.MULTILINE))
    if len(found) != 1:
        raise Refusal(f"{rel} has {len(found)} `{key}:` lines, not one")
    return found[0]


def helm_version(texts: "dict[str, str]") -> str:
    versions = {rel: env_line(text, "HELM_VERSION", rel).group("value").strip("\"'") for rel, text in texts.items()}
    if len(set(versions.values())) != 1:
        raise Refusal("the workflows pin different Helm versions — "
                      + ", ".join(f"{rel}: {v}" for rel, v in versions.items()))
    version = next(iter(versions.values()))
    if not HELM_VERSION.fullmatch(version):
        raise Refusal(f"HELM_VERSION `{version}` is no `vX.Y.Z`")
    return version


def github_headers() -> "dict[str, str]":
    headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"  # never printed: errors name the URL only
    return headers


def release_note_sum(release: dict, version: str, filename: str) -> str:
    """The one 64-hex sum on the one line of the release notes that names `filename`."""
    if release.get("tag_name") != version:
        raise Refusal(f"the GitHub release fetched for {version} is tagged {release.get('tag_name')!r}")
    if release.get("draft") or release.get("prerelease"):
        raise Refusal(f"Helm {version} is a draft or a prerelease on GitHub — not a version to pin")
    body = release.get("body")
    if not isinstance(body, str):
        raise Refusal(f"the GitHub release of Helm {version} has no notes to read the checksum from")
    named = re.compile(rf"(?<![\w.-]){re.escape(filename)}(?![\w-])")
    lines = [line for line in body.splitlines() if named.search(line)]
    if len(lines) != 1:
        raise Refusal(f"the GitHub release notes of Helm {version} name {filename} on {len(lines)} lines, not one")
    sums = set(re.findall(r"(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])", lines[0]))
    if len(sums) != 1:
        raise Refusal(f"the line naming {filename} in Helm {version}'s release notes carries {len(sums)} 64-hex sums, "
                      f"not one")
    return sums.pop().lower()


MEASURED = re.compile(r"Measured (?P<date>\d{4}-\d{2}-\d{2}): get\.helm\.sh's(?P<rest>.*?)all agree\.", re.DOTALL)


def follow_helm(root: pathlib.Path) -> None:
    texts = {rel: read(root, rel) for rel in HELM_WORKFLOWS}
    version = helm_version(texts)
    filename = f"helm-{version}-linux-amd64.tar.gz"
    tarball = HELM_TARBALL.format(v=version)

    published = checksum_file(get_ok(tarball + ".sha256sum", "get.helm.sh's checksum").body, 64, filename,
                              f"{filename}.sha256sum")
    hashed = get_ok(tarball, "the Helm tarball", digests=("sha256",)).digests["sha256"]
    raw = get_ok(HELM_RELEASE.format(v=version), "the GitHub release of helm/helm", github_headers()).body
    try:
        release = json.loads(raw)
    except ValueError:
        raise Refusal(f"the GitHub release of Helm {version} is not JSON") from None
    if not isinstance(release, dict):
        raise Refusal(f"the GitHub release of Helm {version} is not a JSON object")
    noted = release_note_sum(release, version, filename)

    if not (published == hashed == noted):
        raise Refusal(f"the three SHA-256 sources for {filename} disagree — get.helm.sh's .sha256sum: {published}, "
                      f"the tarball itself: {hashed}, the GitHub release notes: {noted}")

    new: dict[str, str] = {}
    for rel, text in texts.items():
        line = env_line(text, "HELM_SHA256", rel)
        new[rel] = text[:line.start("value")] + published + text[line.end("value"):]
    measured = list(MEASURED.finditer(new[HELM_MEASURED_IN]))
    if len(measured) != 1:
        raise Refusal(f"{HELM_MEASURED_IN} has {len(measured)} \"Measured <date>: get.helm.sh's … all agree.\" "
                      f"sentences, not one — the comment drifted from the form this tool rewrites")
    sentence = measured[0]
    rewritten = (f"Measured {today()}: get.helm.sh's"
                 + HELM_VERSION.sub(version, sentence.group("rest")) + "all agree.")
    text = new[HELM_MEASURED_IN]
    new[HELM_MEASURED_IN] = text[:sentence.start()] + rewritten + text[sentence.end():]
    print(f"helm {version}: {published} (get.helm.sh, the tarball and the GitHub release agree)")
    write_all(root, new)


# --- 2. openapi-generator-jar ------------------------------------------------------------------------------------------

def generator_version(root: pathlib.Path) -> str:
    versions: dict[str, str] = {}
    for rel in GENERATOR_CONFIGS:
        try:
            config = json.loads(read(root, rel))
            versions[rel] = config["generator-cli"]["version"]
        except (ValueError, KeyError, TypeError):
            raise Refusal(f"{rel} names no generator-cli.version") from None
    catalog = re.findall(r"""["']org\.openapitools:openapi-generator:([^"']+)["']""", read(root, VERSION_CATALOG))
    if len(catalog) != 1:
        raise Refusal(f"{VERSION_CATALOG} names org.openapitools:openapi-generator {len(catalog)} times, not once")
    versions[VERSION_CATALOG] = catalog[0]
    if len(set(versions.values())) != 1:
        raise Refusal("the three surfaces run different generator versions — "
                      + ", ".join(f"{rel}: {v}" for rel, v in versions.items()))
    version = next(iter(versions.values()))
    if not isinstance(version, str) or not GENERATOR_VERSION.fullmatch(version):
        raise Refusal(f"generator version {version!r} is no `X.Y.Z`")
    return version


def follow_generator_jar(root: pathlib.Path) -> None:
    version = generator_version(root)
    text = read(root, JAR_PIN_FILE)
    kept: list[str] = []
    pinned: dict[str, str] = {}
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            kept.append(line)
            continue
        parts = stripped.split()
        if len(parts) != 2 or not HEX64.fullmatch(parts[1]) or parts[0] in pinned:
            raise Refusal(f"{JAR_PIN_FILE}: `{stripped}` is no `<version> <sha256>` line, or a second one for its version")
        pinned[parts[0]] = parts[1]
        if parts[0] == version:
            kept.append(line)
        else:
            print(f"  dropping the line for {parts[0]}: nothing names that version any more")

    if version not in pinned:
        jar = CENTRAL_JAR.format(v=version)
        name = jar.rsplit("/", 1)[1]
        sha1 = checksum_file(get_ok(jar + ".sha1", "Maven Central's .sha1").body, 40, name, f"{name}.sha1")
        md5 = checksum_file(get_ok(jar + ".md5", "Maven Central's .md5").body, 32, name, f"{name}.md5")
        digests = get_ok(jar, "the generator JAR", digests=("sha256", "sha1", "md5")).digests
        if digests["sha1"] != sha1:
            raise Refusal(f"{name} hashes to SHA-1 {digests['sha1']}, Central's .sha1 says {sha1}")
        if digests["md5"] != md5:
            raise Refusal(f"{name} hashes to MD5 {digests['md5']}, Central's .md5 says {md5}")
        kept.append(f"{version} {digests['sha256']}")
        print(f"openapi-generator-cli {version}: {digests['sha256']} (matches Central's .sha1 and .md5)")
    else:
        print(f"openapi-generator-cli {version} is already pinned")
    write_all(root, {JAR_PIN_FILE: "\n".join(kept) + "\n"})


# --- 3. pypi-hashes ----------------------------------------------------------------------------------------------------

REQUIREMENT = re.compile(r"^(?P<name>[A-Za-z0-9][A-Za-z0-9._-]*)==(?P<version>[A-Za-z0-9.+!_-]+)[ \t]*\\[ \t]*$")
HASH_LINE = re.compile(r"^(?P<indent>[ \t]+)--hash=sha256:(?P<sha>[0-9a-f]{64})[ \t]*(?P<cont>\\)?[ \t]*$")
WHEEL = re.compile(r"^(?P<stem>.+)-(?P<py>[^-]+)-(?P<abi>[^-]+)-(?P<plat>[^-]+)\.whl$")
# The oldest CPython that runs the repository's scripts (the dev Mac's /usr/bin/python3): a wheel built for an older
# CPython's version-specific ABI installs on no machine these files serve, and the files never carried one.
OLDEST_CPYTHON = 9  # 3.9


def python_key(tag: str) -> "tuple[int, int]":
    found = re.match(r"(?:cp|py)(\d)(\d*)", tag)
    return (int(found.group(1)), int(found.group(2) or -1)) if found else (99, 99)


def wheel_hashes(release: dict, name: str, version: str) -> "list[str]":
    """PyPI's SHA-256 of every manylinux x86_64/aarch64 wheel of `name==version`, by Python version, then ABI, then
    platform — the order the file keeps."""
    info = release.get("info") if isinstance(release.get("info"), dict) else {}
    if info.get("version") != version:
        raise Refusal(f"PyPI answered {name} {info.get('version')!r} for {version}")
    if info.get("yanked"):
        raise Refusal(f"{name} {version} is yanked on PyPI")
    chosen: list[tuple[tuple, str]] = []
    for url in release.get("urls") or []:
        wheel = WHEEL.match(str(url.get("filename", "")))
        if not wheel:
            continue
        platforms = wheel.group("plat").split(".")
        if not any(p.startswith("manylinux") and (p.endswith("_x86_64") or p.endswith("_aarch64")) for p in platforms):
            continue
        abi = re.fullmatch(r"cp3(\d+)t?", wheel.group("abi"))
        if abi and int(abi.group(1)) < OLDEST_CPYTHON:
            continue
        sha = (url.get("digests") or {}).get("sha256", "")
        if not HEX64.fullmatch(str(sha)):
            raise Refusal(f"PyPI lists {url.get('filename')} without a 64-hex SHA-256")
        arch = "aarch64" if any(p.endswith("_aarch64") for p in platforms) else "x86_64"
        chosen.append(((python_key(wheel.group("py")), wheel.group("abi"), arch, wheel.group("plat")), sha))
    if not chosen:
        raise Refusal(f"PyPI publishes no manylinux x86_64 or aarch64 wheel for {name} {version}")
    hashes: list[str] = []
    for _, sha in sorted(chosen):
        if sha not in hashes:
            hashes.append(sha)
    return hashes


def follow_pypi_hashes(root: pathlib.Path, rel: str) -> None:
    lines = read(root, rel).splitlines()
    out: list[str] = []
    header_end: int | None = None
    blocks: list[tuple[str, str]] = []
    changed = False
    i = 0
    while i < len(lines):
        line = lines[i]
        requirement = REQUIREMENT.match(line)
        if not requirement:
            if line.strip() and not line.lstrip().startswith("#"):
                raise Refusal(f"{rel}:{i + 1}: `{line.strip()}` is no `Name==version \\` line of a hashed requirement")
            out.append(line)
            i += 1
            continue
        if header_end is None:
            header_end = len(out)
        name, version = requirement.group("name"), requirement.group("version")
        out.append(line)
        i += 1
        old: list[str] = []
        indent = "    "
        closed = False
        while i < len(lines):
            hashed = HASH_LINE.match(lines[i])
            if not hashed:
                break
            if not old:
                indent = hashed.group("indent")
            old.append(hashed.group("sha"))
            i += 1
            if hashed.group("cont") is None:
                closed = True
                break
        if not old or not closed:
            raise Refusal(f"{rel}: {name}=={version} is not followed by `--hash=sha256:` lines ending without `\\`")
        url = PYPI_RELEASE.format(name=urllib.parse.quote(name), version=urllib.parse.quote(version))
        try:
            release = json.loads(get_ok(url, f"PyPI's metadata of {name} {version}").body)
        except ValueError:
            raise Refusal(f"PyPI's metadata of {name} {version} is not JSON") from None
        if not isinstance(release, dict):
            raise Refusal(f"PyPI's metadata of {name} {version} is not a JSON object")
        new = wheel_hashes(release, name, version)
        changed = changed or new != old
        out.extend(f"{indent}--hash=sha256:{sha}" + (" \\" if n < len(new) - 1 else "") for n, sha in enumerate(new))
        blocks.append((name, version))
        print(f"{name} {version}: {len(new)} wheel hashes" + ("" if new != old else " (unchanged)"))
    if not blocks:
        raise Refusal(f"{rel} holds no `Name==version \\` requirement")

    header = out[:header_end]
    for n, line in enumerate(header):
        if not line.lstrip().startswith("#"):
            continue
        moved = line
        for name, version in blocks:
            moved = re.sub(rf"(?<![\w.-]){re.escape(name)} \d+(?:\.\d+)+(?![\w.])", f"{name} {version}", moved)
        if moved != line:
            changed = True
            header[n] = moved
    if changed:
        header = [re.sub(r"\bon \d{4}-\d{2}-\d{2}\b", f"on {today()}", line) if line.lstrip().startswith("#") else line
                  for line in header]
    write_all(root, {rel: "\n".join(header + out[header_end:]) + "\n"})


# --- command line ------------------------------------------------------------------------------------------------------

def main(argv: "Iterable[str] | None" = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--root", type=pathlib.Path, default=repo_layout.ROOT,
                        help="the tree to read and write (default: this repository)")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("helm", help="the Helm tarball's SHA-256 in the three workflows")
    sub.add_parser("openapi-generator-jar", help="the generator JAR's line in scripts/openapi_generator_jar.sha256")
    pypi = sub.add_parser("pypi-hashes", help="a hashed requirements file's wheel hashes")
    pypi.add_argument("file", help="the requirements file, relative to --root")
    args = parser.parse_args(list(argv) if argv is not None else None)
    root = args.root.resolve()
    try:
        if args.command == "helm":
            follow_helm(root)
        elif args.command == "openapi-generator-jar":
            follow_generator_jar(root)
        else:
            follow_pypi_hashes(root, args.file)
    except Refusal as refusal:
        print(f"pin_follow {args.command}: REFUSED — {refusal}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
