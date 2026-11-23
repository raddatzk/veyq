#!/usr/bin/env python3
"""The hand-follows a Dependabot pull request needs, done deterministically and without a language model.

Run by `.github/workflows/dependabot-follow-derive.yml` (read-only) and `.github/workflows/dependabot-follow.yml`
(the one job that writes, and only to the Dependabot branch); `.github/dependabot.yml`'s header says how the two fit.
Every pull request Dependabot opened on 2026-11-12 needed the same mechanical follow by hand. Of the three follows,
two are gone with their ecosystems to Renovate (cr-renovate phase 5): the images (5b), whose regex managers write
every image site in one pull request, and Playwright (5c), whose library and image are one Renovate group. One
remains, and this file goes with it when Gradle moves (phase 5d):

  **gradle** — a Gradle bump regenerates `src/backend/gradle/verification-metadata.xml` by the cold-home recipe of
     `src/backend/CLAUDE.md` (PR #16). That run executes the bumped build, so it happens in the read-only workflow;
     what it wrote comes here as an artifact, as DATA, and is accepted only when it is an append: the
     `<configuration>` unchanged, every component already committed still there with exactly its committed
     checksums. Then only the versions the bump replaced, and that the cold Gradle home no longer holds, are pruned.

**What it never reads.** Not the pull request's title or body (Dependabot copies upstream release notes into it —
third-party text), no changelog, no commit message. The gate reads the pull request's author, head and base refs and
SHAs, and its commits' author logins; everything else is the tree's files.

**What makes it refuse** (exit 1, nothing written), each a case in `dependabot_follow_selftest.py`: a written
metadata file with a DOCTYPE, another `<configuration>`, a committed
component missing, or a committed checksum changed. A pull request whose branch carries a commit by anyone but
Dependabot or this workflow is left alone (`gate` answers `follow=false`): a person has taken it over.

**Its limits — what a green run does NOT prove.** It trusts what Dependabot wrote — exactly the trust the pull request
already asks the reviewer for. A new Gradle component's checksum is trusted on first
write, as in the hand recipe. The recipe's `quarkusDev` pass after a Quarkus bump is not run (it needs a database
and a store); a dependency the bump drops without replacing it is not pruned (no new version of it was written, so
nothing tells it apart from one only `quarkusDev` resolves); a Gradle wrapper bump's `scripts/gradle_wrapper_jar.sha256`
is not followed. The checks a person approves after the push are what prove the result.

    python3 scripts/dependabot_follow.py gate --repo OWNER/NAME --pr N --head-sha SHA
    python3 scripts/dependabot_follow.py gradle-needed --tree DIR --base SHA
    python3 scripts/dependabot_follow.py gradle-listing --gradle-home DIR
    python3 scripts/dependabot_follow.py follow --tree DIR --base SHA --derived DIR --message FILE
"""
from __future__ import annotations  # `str | None` below — the repo's scripts run on Python 3.9

import argparse
import json
import pathlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from typing import Callable, Iterable


METADATA = "src/backend/gradle/verification-metadata.xml"
GRADLE_INPUT = re.compile(r"^src/backend/(?:.+\.gradle\.kts|.+\.versions\.toml|gradle/wrapper/gradle-wrapper\.properties)$")
NS = "{https://schema.gradle.org/dependency-verification}"
COORDINATE = re.compile(r"^[^:\s]+:[^:\s]+:[^:\s]+$")
MAX_METADATA_BYTES = 32 << 20

DEPENDABOT = "dependabot[bot]"
FOLLOW_BOT = "github-actions[bot]"
BRANCH = re.compile(r"^dependabot/[A-Za-z0-9._/+-]+$")
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


# --- the tree ---------------------------------------------------------------------------------------------------------


def git(tree: pathlib.Path, *args: str) -> str:
    out = subprocess.run(["git", "-C", str(tree), *args], capture_output=True, text=True)
    if out.returncode != 0:
        raise Refusal(f"`git {' '.join(args)}` failed: {out.stderr.strip()}")
    return out.stdout


def changed_files(tree: pathlib.Path, base: str) -> list[str]:
    return [p for p in git(tree, "diff", "--name-only", "-z", base, "HEAD").split("\0") if p]


def base_text(tree: pathlib.Path, base: str, rel: str) -> str | None:
    out = subprocess.run(["git", "-C", str(tree), "show", f"{base}:{rel}"], capture_output=True, text=True)
    return out.stdout if out.returncode == 0 else None


def gradle_needed(changed: Iterable[str]) -> bool:
    return any(GRADLE_INPUT.match(rel) for rel in changed)


def follow(tree: pathlib.Path, base: str, derived: pathlib.Path | None) -> tuple[dict[str, str], list[str]]:
    changed = changed_files(tree, base)
    out: dict[str, str] = {}
    notes: list[str] = []

    if gradle_needed(changed):
        if derived is None:
            raise Refusal("this pull request moves a Gradle build input, and no derived metadata came with it")
        files = {p.name for p in derived.iterdir()} if derived.is_dir() else set()
        if not {"verification-metadata.xml", "cold-cache.json"} <= files:
            raise Refusal(f"this pull request moves a Gradle build input, and the read-only run's artifact lacks the "
                          f"written metadata or the cold-cache listing ({sorted(files)})")
        listing = json.loads((derived / "cold-cache.json").read_text())
        if not isinstance(listing, list) or not all(isinstance(e, str) for e in listing):
            raise Refusal("the cold-cache listing is not a list of strings")
        committed = (tree / METADATA).read_text()
        accepted = accept_gradle(committed, (derived / "verification-metadata.xml").read_text(), listing)
        if accepted != committed:
            out[METADATA] = accepted
            notes.append(f"{METADATA} regenerated from a cold Gradle home and pruned")
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
    branch and base it would push to and diff against. Raises on a pull request that is not Dependabot's."""
    pr = api(f"repos/{repo}/pulls/{number}")
    if not isinstance(pr, dict):
        raise Refusal("the pull request could not be read")
    login = (pr.get("user") or {}).get("login")
    head, base = pr.get("head") or {}, pr.get("base") or {}
    if login != DEPENDABOT:
        raise Refusal(f"pull request {number} was opened by `{login}`, not {DEPENDABOT}")
    if (head.get("repo") or {}).get("full_name") != repo or (base.get("repo") or {}).get("full_name") != repo:
        raise Refusal(f"pull request {number}'s head or base is not in {repo}")
    if head.get("sha") != head_sha:
        return {"follow": "false", "reason": "the branch moved since the run that derived it"}
    branch, base_ref = str(head.get("ref", "")), str(base.get("ref", ""))
    if not BRANCH.match(branch) or ".." in branch:
        raise Refusal(f"`{branch[:120]}` is not a Dependabot branch name")
    if not BASE_BRANCH.match(base_ref):
        raise Refusal(f"`{base_ref[:120]}` is not a branch this tool follows into")
    if pr.get("state") != "open":
        return {"follow": "false", "reason": "the pull request is not open"}
    commits = api(f"repos/{repo}/pulls/{number}/commits")
    authors = {((c.get("author") or {}).get("login")) for c in commits} if isinstance(commits, list) else {None}
    if not commits or authors - {DEPENDABOT, FOLLOW_BOT}:
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
    p = sub.add_parser("gradle-needed")
    p.add_argument("--tree", required=True, type=pathlib.Path)
    p.add_argument("--base", required=True)
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
        elif args.command == "gradle-needed":
            print(f"needed={'true' if gradle_needed(changed_files(args.tree, args.base)) else 'false'}")
        elif args.command == "gradle-listing":
            print(json.dumps(cold_listing(args.gradle_home), indent=0))
        else:
            out, notes = follow(args.tree, args.base, args.derived)
            for rel, text in sorted(out.items()):
                (args.tree / rel).write_text(text)
            if notes:
                args.message.write_text("chore(deps): follow the bump where Dependabot does not write\n\n"
                                        "Written by scripts/dependabot_follow.py (.github/workflows/dependabot-follow.yml):\n"
                                        + "".join(f"- {n}\n" for n in notes))
            print("\n".join(notes) if notes else "nothing to follow")
    except Refusal as exc:
        print(f"::error::not followed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
