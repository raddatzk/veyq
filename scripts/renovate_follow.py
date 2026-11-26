#!/usr/bin/env python3
"""What a Renovate pull request needs and only an executed build can write, done deterministically and without a
language model (ADR 0158 decision 4).

Renovate writes a version and everything a manager or a `postUpgradeTask` (scripts/pin_follow.py) can derive from
published bytes. Three follow-ups need the bumped tree EXECUTED, which never happens in Renovate's job, the one that
holds the App's token (`gradleWrapper` stays out of `allowedUnsafeExecutions` for the same reason):

  1. **gradle** — a bump of a Gradle build input (a build file, the version catalog, the wrapper's properties) in one
     of the two builds, src/backend/ and the root launcher, regenerates that build's `gradle/verification-metadata.xml`
     by the cold-home recipe of src/backend/CLAUDE.md. What the run wrote is accepted only as an append: the
     `<configuration>` unchanged, every committed component still there with exactly its committed checksums. Then only
     the versions the bump replaced, and that the cold Gradle home no longer holds, are pruned.
  2. **webp** — a bump of libwebp's tag (`src/web/webp-encoder/libwebp.pin`, Renovate's git-tags manager) or of the
     Emscripten image (the encoder's Dockerfile) rebuilds the encoder with `build.py --write` (ADR 0127). The tag's
     commit is resolved from upstream by `git ls-remote` on both sides; the pin written must name the tree's tag, that
     commit, the Dockerfile's image, and hash every output file it lists. Checks' `webp-encoder` job rebuilds those
     bytes from the pin and is red on any byte that differs, so forged bytes do not pass review unseen.
  3. **fuzz** — a Schemathesis bump (`SCHEMATHESIS` in tests/fuzz/lock.py, a regex manager) re-runs lock.py in the
     fuzzer's own base image, which rewrites tests/fuzz/requirements.txt: every package pinned by version and wheel hash.
     The file is accepted when every line is a comment, a `name==version \\` line or a `--hash=sha256:` line, and it pins
     the Schemathesis version lock.py names; Checks' fuzz job installs it with `pip install --require-hashes`.

Two workflows run it. `renovate-follow-derive.yml` runs on Renovate's pull requests with a read-only token and executes
what must be executed (`needs` says what), uploading what it wrote as an artifact. `renovate-follow.yml` starts on its
completion via `workflow_run` — its file, actions and this script are the default branch's, never the pull request's —
checks that the pull request is Renovate's and that nobody else pushed to it (`gate`), accepts what was derived as DATA
(`follow`), and pushes one commit to the `renovate/…` branch with a token minted for contents alone, so the push starts
`Checks` like Renovate's own.

**What it never reads.** Not the pull request's title or body (Renovate copies upstream release notes into it —
third-party text), no changelog, no commit message but the follow's own subject. The gate reads the pull request's
author, head and base refs and SHAs, and its commits' author logins; everything else is the tree's files, the derived
artifact and libwebp's tag list.

**What makes it refuse** (exit 1, nothing written), each a case in `renovate_follow_selftest.py`: a written metadata file
with a DOCTYPE, another `<configuration>`, a committed component missing, or a committed checksum changed; a derived
artifact missing what `needs` said was needed; a libwebp pin naming another tag, another commit than upstream's, another
image than the Dockerfile's, a file its hashes do not match, or a path outside the encoder's directory; a requirements
file with a line of another kind, or without the Schemathesis version lock.py names. A pull request whose branch carries
a commit by anyone but Renovate's App is left alone (`gate` answers `follow=false`): a person has taken it over.

**Its limits — what a green run does NOT prove.** It trusts what Renovate wrote — the trust the pull request already
asks the reviewer for. A new Gradle component's checksum is trusted on first write, as in the hand recipe; the recipe's
`quarkusDev` pass after a Quarkus bump is not run; a dependency the bump drops without replacing it is not pruned. The
encoder's bytes and the fuzzer's hashes are taken from the read-only run and proven by Checks after the push, not here.

    python3 scripts/renovate_follow.py gate --repo OWNER/NAME --pr N --head-sha SHA
    python3 scripts/renovate_follow.py needs --tree DIR --base SHA
    python3 scripts/renovate_follow.py libwebp-commit --tag vX.Y.Z
    python3 scripts/renovate_follow.py gradle-listing --gradle-home DIR
    python3 scripts/renovate_follow.py follow --tree DIR --base SHA --derived DIR --message FILE
"""
from __future__ import annotations  # `str | None` below — the repo's scripts run on Python 3.9

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from typing import Callable, Iterable

# The two Gradle builds: the directory that holds `gradlew`, and the inputs whose change can change what it resolves.
GRADLE_BUILDS = {
    "backend": ("src/backend", re.compile(
        r"^src/backend/(?:.+\.gradle\.kts|.+\.versions\.toml|gradle/wrapper/gradle-wrapper\.properties)$")),
    "root": ("", re.compile(r"^(?:settings\.gradle\.kts|launcher/.+\.gradle\.kts|gradle/wrapper/gradle-wrapper\.properties)$")),
}
NS = "{https://schema.gradle.org/dependency-verification}"
COORDINATE = re.compile(r"^[^:\s]+:[^:\s]+:[^:\s]+$")
MAX_METADATA_BYTES = 32 << 20

WEBP_DIR = "src/web/webp-encoder"
WEBP_INPUT = re.compile(r"^src/web/webp-encoder/(?:Dockerfile|libwebp\.pin)$")
LIBWEBP = "https://github.com/webmproject/libwebp"
STABLE_TAG = re.compile(r"^v\d+\.\d+\.\d+$")
SHA1 = re.compile(r"^[0-9a-f]{40}$")
MAX_WEBP_BYTES = 8 << 20

FUZZ_LOCK = "tests/fuzz/lock.py"
FUZZ_REQUIREMENTS = "tests/fuzz/requirements.txt"
SCHEMATHESIS_PIN = re.compile(r'^SCHEMATHESIS = "schemathesis==(?P<version>[0-9][0-9A-Za-z.+!-]*)"$', re.MULTILINE)
REQUIREMENT = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*==[A-Za-z0-9.+!_-]+ \\$")
HASH_LINE = re.compile(r"^    --hash=sha256:[0-9a-f]{64}(?: \\)?$")
MAX_REQUIREMENTS_BYTES = 1 << 20

RENOVATE = "veyq-renovate[bot]"
FOLLOW_SUBJECT = "chore(deps): follow the bump where Renovate does not write"
BRANCH = re.compile(r"^renovate/[A-Za-z0-9._/+-]+$")
BASE_BRANCH = re.compile(r"^(?:main|release/[A-Za-z0-9._-]+)$")


class Refusal(Exception):
    """The follow cannot be done mechanically; a person decides. Nothing is written."""


# --- Gradle verification metadata -------------------------------------------------------------------------------------


def parse_metadata(text: str, what: str) -> tuple[bytes, dict[tuple[str, str, str], dict[str, frozenset]]]:
    if len(text) > MAX_METADATA_BYTES:
        raise Refusal(f"{what} is larger than {MAX_METADATA_BYTES} bytes")
    if re.search(r"<!(?:DOCTYPE|ENTITY)", text, re.IGNORECASE):
        raise Refusal(f"{what} declares a DOCTYPE or an entity — Gradle writes neither")
    try:
        root = ET.fromstring(text)
    except ET.ParseError as exc:
        raise Refusal(f"{what} does not parse as XML ({exc})") from None
    if root.tag != f"{NS}verification-metadata":
        raise Refusal(f"{what} is no Gradle verification metadata (root `{root.tag}`)")
    configuration = root.find(f"{NS}configuration")
    canonical = ET.tostring(configuration) if configuration is not None else b""
    components: dict[tuple[str, str, str], dict[str, frozenset]] = {}
    for component in root.iterfind(f"{NS}components/{NS}component"):
        key = (component.get("group", ""), component.get("name", ""), component.get("version", ""))
        if key in components:
            raise Refusal(f"{what} lists {':'.join(key)} twice")
        artifacts = {}
        for artifact in component.iterfind(f"{NS}artifact"):
            sums = []
            for child in artifact.iter():
                if child is artifact or child.tag.endswith("also-trust"):
                    continue
                value = child.get("value", "")
                sums.append((child.tag.replace(NS, ""), value))
                for trusted in child.iterfind(f"{NS}also-trust"):
                    sums.append((child.tag.replace(NS, "") + "+also-trust", trusted.get("value", "")))
            artifacts[artifact.get("name", "")] = frozenset(sums)
        components[key] = artifacts
    return canonical, components


def accept_gradle(committed: str, written: str, cold: Iterable[str]) -> str:
    """The metadata to commit: `written` (what the cold-home run wrote over `committed`) when it is an append, with the
    versions the bump replaced pruned where the cold Gradle home no longer holds them."""
    cold = set(cold)
    for entry in cold:
        if not COORDINATE.match(entry):
            raise Refusal(f"the cold-cache listing carries `{entry[:80]}`, which is no group:name:version")
    base_configuration, base = parse_metadata(committed, "the committed metadata")
    new_configuration, new = parse_metadata(written, "the written metadata")
    if new_configuration != base_configuration:
        raise Refusal("the written metadata's <configuration> differs from the committed one — a write adds "
                      "components, it never changes the trust rules")
    for key, artifacts in sorted(base.items()):
        if key not in new:
            raise Refusal(f"{':'.join(key)} is committed but missing from the written metadata — Gradle's write only "
                          f"adds, so this file was not written by it")
        for name, sums in sorted(artifacts.items()):
            if new[key].get(name) != sums:
                raise Refusal(f"{':'.join(key)}'s `{name}` is committed with other checksums than the written ones — an "
                              f"artifact already pinned now hashes differently; that is never followed mechanically")
    for key, artifacts in sorted(new.items()):
        for name, sums in sorted(artifacts.items()):
            if not sums or any(not re.fullmatch(r"[0-9a-f]{32,128}", value) for _, value in sums):
                raise Refusal(f"{':'.join(key)}'s `{name}` carries no well-formed checksum")

    added = new.keys() - base.keys()
    replaced = {(g, n) for g, n, _ in added}
    prune = sorted(key for key in new.keys() - added
                   if key[:2] in replaced and ":".join(key) not in cold)
    out = written
    for group, name, version in prune:
        block = re.compile(
            r'^[ \t]*<component group="' + re.escape(group) + r'" name="' + re.escape(name) + r'" version="'
            + re.escape(version) + r'">\n(?:.*\n)*?[ \t]*</component>\n', re.MULTILINE)
        out, count = block.subn("", out, count=2)
        if count != 1:
            raise Refusal(f"{group}:{name}:{version} is to be pruned but appears {count} times in Gradle's layout")
    return out


def cold_listing(gradle_home: pathlib.Path) -> list[str]:
    """Every group:name:version the cold Gradle home downloaded (`caches/modules-2/files-2.1/<g>/<n>/<v>/…`)."""
    root = gradle_home / "caches" / "modules-2" / "files-2.1"
    if not root.is_dir():
        raise Refusal(f"{root} does not exist — the cold-home run downloaded nothing, or Gradle moved its cache")
    return sorted(f"{g.name}:{n.name}:{v.name}" for g in root.iterdir() if g.is_dir()
                  for n in g.iterdir() if n.is_dir() for v in n.iterdir() if v.is_dir())


# --- the WebP encoder -------------------------------------------------------------------------------------------------


def libwebp_commit(tag: str, lister: Callable[[], str] | None = None) -> str:
    """The commit libwebp's tag names upstream: an annotated tag's peeled line, else a lightweight tag's own line."""
    if not STABLE_TAG.match(tag):
        raise Refusal(f"`{tag[:40]}` is no stable libwebp tag (vX.Y.Z)")
    listing = lister() if lister else subprocess.run(
        ["git", "ls-remote", "--tags", LIBWEBP, f"refs/tags/{tag}", f"refs/tags/{tag}^{{}}"],
        capture_output=True, text=True, check=False).stdout
    own = peeled = None
    for line in listing.splitlines():
        sha, _, ref = line.partition("\t")
        if ref == f"refs/tags/{tag}^{{}}":
            peeled = sha
        elif ref == f"refs/tags/{tag}":
            own = sha
    commit = peeled or own
    if not commit or not SHA1.match(commit):
        raise Refusal(f"libwebp names no commit for {tag} — the tag is gone or the listing failed")
    return commit


def pin_fields(text: str, what: str) -> tuple[dict[str, str], dict[str, str]]:
    """`libwebp.pin`'s `tag`, `commit`, `image` and its `sha256 <hex> <path>` lines."""
    fields: dict[str, str] = {}
    hashes: dict[str, str] = {}
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if parts[0] in ("tag", "commit", "image") and len(parts) == 2 and parts[0] not in fields:
            fields[parts[0]] = parts[1]
        elif parts[0] == "sha256" and len(parts) == 3 and re.fullmatch(r"[0-9a-f]{64}", parts[1]) \
                and parts[2] not in hashes:
            hashes[parts[2]] = parts[1]
        else:
            raise Refusal(f"{what} carries a line it never writes: `{line[:80]}`")
    if set(fields) != {"tag", "commit", "image"} or not hashes:
        raise Refusal(f"{what} lacks its tag, commit, image or hashes")
    return fields, hashes


def dockerfile_image(text: str) -> str:
    froms = [line.split()[1] for line in text.splitlines() if line.upper().startswith("FROM ")]
    if len(froms) != 1:
        raise Refusal(f"the encoder's Dockerfile has {len(froms)} FROM lines, not one")
    return froms[0]


def accept_webp(tree: pathlib.Path, derived: pathlib.Path, commit_of: Callable[[str], str] = libwebp_commit
                ) -> dict[str, bytes]:
    """The encoder's files to commit: what the read-only run's `build.py --write` wrote, held to the tree and upstream."""
    pin_rel = f"{WEBP_DIR}/libwebp.pin"
    written = derived / "webp" / "libwebp.pin"
    if not written.is_file():
        raise Refusal("the encoder's inputs moved, and the read-only run's artifact lacks the written libwebp.pin")
    fields, hashes = pin_fields(written.read_text(), "the written libwebp.pin")
    tree_fields, _ = pin_fields((tree / pin_rel).read_text(), f"{pin_rel} in the pull request")
    if fields["tag"] != tree_fields["tag"]:
        raise Refusal(f"the written pin names {fields['tag']}, the pull request {tree_fields['tag']}")
    upstream = commit_of(fields["tag"])
    if fields["commit"] != upstream:
        raise Refusal(f"the written pin names commit {fields['commit']} for {fields['tag']}; libwebp names {upstream}")
    image = dockerfile_image((tree / WEBP_DIR / "Dockerfile").read_text())
    if fields["image"] != image:
        raise Refusal(f"the written pin names image {fields['image']}, the Dockerfile builds FROM {image}")
    out: dict[str, bytes] = {pin_rel: written.read_bytes()}
    for rel, sha in sorted(hashes.items()):
        parts = pathlib.PurePosixPath(rel).parts
        if not parts or ".." in parts or rel.startswith("/") or rel == "libwebp.pin":
            raise Refusal(f"the written pin hashes `{rel[:80]}`, which is no file of the encoder's directory")
        source = derived / "webp" / rel
        if not source.is_file() or source.is_symlink() or source.stat().st_size > MAX_WEBP_BYTES:
            raise Refusal(f"the artifact lacks `{rel}`, or it is no plain file of a size the encoder writes")
        data = source.read_bytes()
        if hashlib.sha256(data).hexdigest() != sha:
            raise Refusal(f"`{rel}` does not hash to the line the written pin gives it")
        out[f"{WEBP_DIR}/{rel}"] = data
    return out


# --- the fuzzer's lock --------------------------------------------------------------------------------------------------


def accept_lock(tree: pathlib.Path, derived: pathlib.Path) -> bytes:
    written = derived / "fuzz" / "requirements.txt"
    if not written.is_file() or written.is_symlink() or written.stat().st_size > MAX_REQUIREMENTS_BYTES:
        raise Refusal("Schemathesis moved, and the read-only run's artifact lacks a plain requirements.txt")
    pin = SCHEMATHESIS_PIN.search((tree / FUZZ_LOCK).read_text())
    if not pin:
        raise Refusal(f"{FUZZ_LOCK} names no `SCHEMATHESIS = \"schemathesis==<version>\"`")
    text = written.read_text()
    lines = text.splitlines()
    for number, line in enumerate(lines, 1):
        if line.startswith("#") and all(not REQUIREMENT.match(l) for l in lines[:number - 1]):
            continue  # the header, before the first requirement
        if not (REQUIREMENT.match(line) or HASH_LINE.match(line)):
            raise Refusal(f"the written requirements.txt line {number} is no requirement or hash line: `{line[:80]}`")
    if f"schemathesis=={pin.group('version')} \\" not in lines:
        raise Refusal(f"the written requirements.txt does not pin schemathesis=={pin.group('version')}, which "
                      f"{FUZZ_LOCK} names")
    return text.encode()


# --- the tree ---------------------------------------------------------------------------------------------------------


def git(tree: pathlib.Path, *args: str) -> str:
    out = subprocess.run(["git", "-C", str(tree), *args], capture_output=True, text=True)
    if out.returncode != 0:
        raise Refusal(f"`git {' '.join(args)}` failed: {out.stderr.strip()}")
    return out.stdout


def changed_files(tree: pathlib.Path, base: str) -> list[str]:
    return [p for p in git(tree, "diff", "--name-only", "-z", base, "HEAD").split("\0") if p]


def needs(changed: Iterable[str]) -> dict[str, str]:
    """Which follow-ups the change needs, as `key=true|false` for $GITHUB_OUTPUT."""
    changed = list(changed)
    out = {f"gradle_{name}": any(rx.match(rel) for rel in changed) for name, (_, rx) in GRADLE_BUILDS.items()}
    out["webp"] = any(WEBP_INPUT.match(rel) for rel in changed)
    out["fuzz"] = FUZZ_LOCK in changed
    return {key: "true" if value else "false" for key, value in out.items()}


def follow(tree: pathlib.Path, base: str, derived: pathlib.Path | None,
           commit_of: Callable[[str], str] = libwebp_commit) -> tuple[dict[str, bytes], list[str]]:
    out: dict[str, bytes] = {}
    notes: list[str] = []
    if git(tree, "log", "-1", "--format=%s", "HEAD").strip() == FOLLOW_SUBJECT:
        return out, notes  # the head is this tool's own commit; the derive run derived nothing on it
    wanted = needs(changed_files(tree, base))
    if "true" in wanted.values() and (derived is None or not derived.is_dir()):
        raise Refusal(f"this pull request needs {sorted(k for k, v in wanted.items() if v == 'true')}, and no derived "
                      f"artifact came with it")
    for name, (directory, _) in GRADLE_BUILDS.items():
        if wanted[f"gradle_{name}"] != "true":
            continue
        rel = f"{directory}/gradle/verification-metadata.xml".lstrip("/")
        here = derived / "gradle" / name
        if not (here / "verification-metadata.xml").is_file() or not (here / "cold-cache.json").is_file():
            raise Refusal(f"the {name} build's inputs moved, and the read-only run's artifact lacks its written "
                          f"metadata or its cold-cache listing")
        listing = json.loads((here / "cold-cache.json").read_text())
        if not isinstance(listing, list) or not all(isinstance(e, str) for e in listing):
            raise Refusal(f"the {name} build's cold-cache listing is not a list of strings")
        committed = (tree / rel).read_text()
        accepted = accept_gradle(committed, (here / "verification-metadata.xml").read_text(), listing)
        if accepted != committed:
            out[rel] = accepted.encode()
            notes.append(f"{rel} regenerated from a cold Gradle home and pruned")
    if wanted["webp"] == "true":
        for rel, data in accept_webp(tree, derived, commit_of).items():
            if (tree / rel).read_bytes() != data:
                out[rel] = data
                notes.append(f"{rel} rebuilt by build.py --write")
    if wanted["fuzz"] == "true":
        data = accept_lock(tree, derived)
        if (tree / FUZZ_REQUIREMENTS).read_bytes() != data:
            out[FUZZ_REQUIREMENTS] = data
            notes.append(f"{FUZZ_REQUIREMENTS} re-locked by lock.py")
    return out, notes


# --- the gate ---------------------------------------------------------------------------------------------------------


def gh_api(path: str) -> object:
    out = subprocess.run(["gh", "api", "--paginate", "--slurp", path], capture_output=True, text=True)
    if out.returncode != 0:
        raise Refusal(f"`gh api {path}` failed: {out.stderr.strip()}")
    pages = json.loads(out.stdout)
    if pages and all(isinstance(p, list) for p in pages):
        return [item for page in pages for item in page]
    return pages[0] if len(pages) == 1 else pages


def gate(repo: str, number: int, head_sha: str, api: Callable[[str], object] = gh_api) -> dict[str, str]:
    """Whether this run may follow pull request `number`, read from GitHub (never from the run's own inputs), and the
    branch and base it would push to and diff against. Raises on a pull request that is not Renovate's."""
    pr = api(f"repos/{repo}/pulls/{number}")
    if not isinstance(pr, dict):
        raise Refusal("the pull request could not be read")
    login = (pr.get("user") or {}).get("login")
    head, base = pr.get("head") or {}, pr.get("base") or {}
    if login != RENOVATE:
        raise Refusal(f"pull request {number} was opened by `{login}`, not {RENOVATE}")
    if (head.get("repo") or {}).get("full_name") != repo or (base.get("repo") or {}).get("full_name") != repo:
        raise Refusal(f"pull request {number}'s head or base is not in {repo}")
    if head.get("sha") != head_sha:
        return {"follow": "false", "reason": "the branch moved since the run that derived it"}
    branch, base_ref = str(head.get("ref", "")), str(base.get("ref", ""))
    if not BRANCH.match(branch) or ".." in branch:
        raise Refusal(f"`{branch[:120]}` is not a Renovate branch name")
    if not BASE_BRANCH.match(base_ref):
        raise Refusal(f"`{base_ref[:120]}` is not a branch this tool follows into")
    if pr.get("state") != "open":
        return {"follow": "false", "reason": "the pull request is not open"}
    commits = api(f"repos/{repo}/pulls/{number}/commits")
    authors = {((c.get("author") or {}).get("login")) for c in commits} if isinstance(commits, list) else {None}
    if not commits or authors - {RENOVATE}:
        return {"follow": "false", "reason": f"the branch carries commits by {sorted(map(str, authors))} — a person has "
                                             f"taken it over"}
    return {"follow": "true", "branch": branch, "base_ref": base_ref}


# --- command line -----------------------------------------------------------------------------------------------------


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("gate")
    p.add_argument("--repo", required=True)
    p.add_argument("--pr", required=True, type=int)
    p.add_argument("--head-sha", required=True)
    p = sub.add_parser("needs")
    p.add_argument("--tree", required=True, type=pathlib.Path)
    p.add_argument("--base", required=True)
    p = sub.add_parser("libwebp-commit")
    p.add_argument("--tag", required=True)
    p = sub.add_parser("gradle-listing")
    p.add_argument("--gradle-home", required=True, type=pathlib.Path)
    p = sub.add_parser("follow")
    p.add_argument("--tree", required=True, type=pathlib.Path)
    p.add_argument("--base", required=True)
    p.add_argument("--derived", type=pathlib.Path)
    p.add_argument("--message", required=True, type=pathlib.Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "gate":
            for key, value in gate(args.repo, args.pr, args.head_sha).items():
                print(f"{key}={value}")
        elif args.command == "needs":
            for key, value in needs(changed_files(args.tree, args.base)).items():
                print(f"{key}={value}")
        elif args.command == "libwebp-commit":
            print(f"commit={libwebp_commit(args.tag)}")
        elif args.command == "gradle-listing":
            print(json.dumps(cold_listing(args.gradle_home), indent=0))
        else:
            out, notes = follow(args.tree, args.base, args.derived)
            for rel, data in sorted(out.items()):
                (args.tree / rel).write_bytes(data)
            if notes:
                args.message.write_text(f"{FOLLOW_SUBJECT}\n\nWritten by scripts/renovate_follow.py "
                                        f"(.github/workflows/renovate-follow.yml):\n" + "".join(f"- {n}\n" for n in notes))
            print("\n".join(notes) if notes else "nothing to follow")
    except Refusal as exc:
        print(f"::error::not followed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
