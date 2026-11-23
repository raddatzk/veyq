#!/usr/bin/env python3
"""A change to a Gradle verification metadata file only adds trust, never changes the trust already committed.

**What it asserts.** Every tracked file named `verification-metadata.xml` under a `gradle/` directory (found by name,
never listed: today the backend's and the dev launcher's), compared with the same file at a base commit:

  1. its `<configuration>` (`verify-metadata`, `verify-signatures`, the trusted artifacts and keys) is unchanged;
  2. every artifact the base commits and the change keeps (same component, same artifact name) has exactly the
     base's checksums: none changed, none of another kind, no `also-trust` added;
  3. every checksum the file carries is lowercase hex of a digest's length;
  4. the file still exists, parses as Gradle's verification metadata, declares no DOCTYPE or entity, and lists no
     component twice. A file that moved (gone at its base path, a new one elsewhere) is compared with its new path.

An entry the change drops is allowed, a whole component or one artifact of it: an old version a bump replaced (the
pruning `src/backend/CLAUDE.md`'s recipe does by hand), a dependency the build no longer resolves, a POM a Gradle
module file made unread (7f28ce7d2 dropped `kotlin-stdlib-2.3.21.pom`). Dropping one cannot widen trust: Gradle refuses
an artifact that has no entry, so a dropped entry the build still needs is red in the build.

    python3 scripts/verification_metadata_diff.py --base <commit>

`--base` is the commit the change is measured against: a pull request's base, a push's previous head. `checks.yml`'s
`guards` job fetches it; locally, `--base origin/main`. An empty or all-zero base (a branch's first push) checks only
rules 3 and 4.

**Why it is no `validate_*.py`.** The trust-chain guards read the present tree and never history (the trust-chain
skill's fourth edge), and this rule is about a change: one tree against another. So it runs beside the glob, in the
same job, with the base the event names. `verification_metadata_diff_selftest.py` holds its verdicts.

**The defect that bought it.** No incident: the rule is `scripts/dependabot_follow.py`'s, which accepted the metadata a
cold Gradle home wrote over a Dependabot bump only when it was an append (cr-renovate phase 4). The follow guarded the
one writer it ran; a hand edit, or a Renovate branch, was held to nothing. A changed checksum of an artifact already
pinned is the one edit in that file that silently accepts other bytes under a name the build already trusts, and in a
diff of a thousand components it is one line among hundreds of added ones.

**Its limit — what a green run does NOT prove.** That an ADDED checksum is the right one: a new component's hash is
trusted on first write, as Gradle's own `--write-verification-metadata` trusts it. That the file covers what the build
resolves: the build's own verification says that. A change to the trust rules (rule 1) or a re-published artifact
(rule 2) is sometimes legitimate; this check has no exception for either, so such a pull request merges red, by the
owner, as a decision someone has to take. It compares two trees, so it says nothing about the commits between them.
"""
from __future__ import annotations  # the repo's scripts run on Python 3.9

import argparse
import pathlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
NAME = "verification-metadata.xml"
NS = "{https://schema.gradle.org/dependency-verification}"
CHECKSUM = re.compile(r"[0-9a-f]{32,128}")
NO_BASE = re.compile(r"0*")


class Refused(Exception):
    pass


Artifacts = dict  # artifact name -> frozenset of (kind, value)


def parse(text: str, what: str) -> tuple[bytes, dict[tuple[str, str, str], Artifacts]]:
    """The file's canonical `<configuration>` and its components, each artifact with its set of checksums."""
    if re.search(r"<!(?:DOCTYPE|ENTITY)", text, re.IGNORECASE):
        raise Refused(f"{what} declares a DOCTYPE or an entity — Gradle writes neither")
    try:
        root = ET.fromstring(text)
    except ET.ParseError as exc:
        raise Refused(f"{what} does not parse as XML ({exc})") from None
    if root.tag != f"{NS}verification-metadata":
        raise Refused(f"{what} is no Gradle verification metadata (root `{root.tag}`)")
    configuration = root.find(f"{NS}configuration")
    canonical = ET.tostring(configuration) if configuration is not None else b""
    components: dict[tuple[str, str, str], Artifacts] = {}
    for component in root.iterfind(f"{NS}components/{NS}component"):
        key = (component.get("group", ""), component.get("name", ""), component.get("version", ""))
        if key in components:
            raise Refused(f"{what} lists {':'.join(key)} twice")
        artifacts: Artifacts = {}
        for artifact in component.iterfind(f"{NS}artifact"):
            sums = []
            for child in artifact:
                kind = child.tag.replace(NS, "")
                sums.append((kind, child.get("value", "")))
                for trusted in child.iterfind(f"{NS}also-trust"):
                    sums.append((kind + "+also-trust", trusted.get("value", "")))
            artifacts[artifact.get("name", "")] = frozenset(sums)
        components[key] = artifacts
    return canonical, components


def verdicts(rel: str, base: str | None, head: str | None) -> list[str]:
    """Why `head` (the change's file) is refused against `base` (the base commit's), or nothing."""
    if head is None:
        return [f"{rel} is deleted — without it Gradle verifies nothing; a change that ends verification is no update"]
    try:
        head_configuration, head_components = parse(head, rel)
    except Refused as exc:
        return [str(exc)]
    errors = []
    for key, artifacts in sorted(head_components.items()):
        for name, sums in sorted(artifacts.items()):
            if not sums or any(not CHECKSUM.fullmatch(value) for _, value in sums):
                errors.append(f"{rel}: {':'.join(key)}'s `{name}` carries no well-formed checksum")
    if base is None:
        return errors
    try:
        base_configuration, base_components = parse(base, f"{rel} at the base")
    except Refused as exc:
        return errors + [f"{exc} — so the change cannot be compared with it"]
    if head_configuration != base_configuration:
        errors.append(f"{rel}: the <configuration> changed — an update adds components, it never changes the trust "
                      f"rules (what is verified, which artifacts and keys are trusted)")
    for key, artifacts in sorted(base_components.items()):
        if key not in head_components:
            continue  # dropped whole: a replaced version or a dependency gone; the build refuses one it still needs
        for name, sums in sorted(artifacts.items()):
            now = head_components[key].get(name)
            if now is not None and now != sums:  # dropped: allowed, as a whole component
                errors.append(f"{rel}: {':'.join(key)}'s `{name}` is committed with other checksums than the base's — "
                              f"an artifact already pinned would be trusted under new bytes")
    return errors


def git(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True)


def tracked() -> list[str]:
    out = git("ls-files", "-z")
    if out.returncode != 0:
        raise Refused(f"`git ls-files` failed: {out.stderr.strip()}")
    return [p for p in out.stdout.split("\0") if p]


def at_base(base: str, rel: str) -> str | None:
    out = git("cat-file", "-e", f"{base}:{rel}")
    if out.returncode != 0:
        return None
    shown = git("show", f"{base}:{rel}")
    if shown.returncode != 0:
        raise Refused(f"`git show {base}:{rel}` failed: {shown.stderr.strip()}")
    return shown.stdout


def is_metadata(rel: str) -> bool:
    parts = rel.split("/")
    return parts[-1] == NAME and len(parts) >= 2 and parts[-2] == "gradle"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--base", required=True, help="the commit the change is measured against")
    args = parser.parse_args(argv)
    base = None if NO_BASE.fullmatch(args.base.strip()) else args.base.strip()
    try:
        if base is not None and git("rev-parse", "--verify", "--quiet", f"{base}^{{commit}}").returncode != 0:
            raise Refused(f"the base `{base}` is no commit in this checkout — fetch it first")
        here = {rel for rel in tracked() if is_metadata(rel)}
        there = set()
        if base is not None:
            listed = git("ls-tree", "-r", "--name-only", base)
            if listed.returncode != 0:
                raise Refused(f"`git ls-tree {base}` failed: {listed.stderr.strip()}")
            there = {rel for rel in listed.stdout.splitlines() if is_metadata(rel)}
    except Refused as exc:
        print(f"VERIFICATION METADATA CHECK FAILED:\n  - {exc}")
        return 1
    if not here and not there:
        print(f"VERIFICATION METADATA CHECK FAILED:\n  - no tracked gradle/{NAME} — re-anchor this check")
        return 1
    errors = []
    # A file gone from its base path and a new one elsewhere is a move (408ce9c73 moved backend/ under src/), compared
    # across it; a file gone with no new one beside it ends verification.
    gone, new = sorted(there - here), sorted(here - there)
    moved = dict(zip(gone, new)) if len(gone) == len(new) else {}
    for rel in sorted(here | there):
        if rel in moved.values():
            continue
        target = moved.get(rel, rel if rel in here else None)
        head = (ROOT / target).read_text(encoding="utf-8") if target is not None else None
        try:
            old = at_base(base, rel) if base is not None and rel in there else None
        except Refused as exc:
            errors.append(str(exc))
            continue
        errors.extend(verdicts(target or rel, old, head))
    if errors:
        print("VERIFICATION METADATA CHECK FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    against = f"against {base}" if base is not None else "with no base to compare (a first push): form only"
    print(f"verification metadata OK: {', '.join(sorted(here))} {against} — the trust rules and every kept pin unchanged")
    return 0


if __name__ == "__main__":
    sys.exit(main())
