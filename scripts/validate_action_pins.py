#!/usr/bin/env python3
"""Every action a workflow runs is pinned by commit SHA, with the full version it resolves to as a comment.

**What it asserts.** The anchor set is every `uses:` under every tracked `.github/workflows/*.y(a)ml` and every
tracked `action.y(a)ml`, found by YAML key at any depth (so step-level and job-level alike), never listed
(§ *a guard derives its anchor set from the code*, changes/README.md):

  0. **Each file parses.** A workflow YAML cannot load is one GitHub refuses, and one whose pins nothing here sees.
  1. **R1 — shape.** Each value is local (`./…`), a `docker://…@sha256:<64 hex>`, or `owner/repo[/path]@<40
     lowercase hex>`. A tag or branch (`@v4`, `@main`) is a pointer its owner can move; a SHA is not.
  2. **R2 — comment.** The source line of every `owner/repo` pin ends in `# v<MAJOR>.<MINOR>.<PATCH>` (an
     optional pre-release suffix allowed). The set of `uses:` found by YAML and the set found by reading lines
     must be equal, so a flow-style step (`- {uses: …}`) or any `uses:` whose comment cannot be located is red,
     never skipped.
  3. **R3 — one pin per action.** One `owner/repo` carries one `<sha> # <version>` everywhere, keyed on the
     repository rather than the path, so `codeql-action/init` and `codeql-action/analyze` cannot split.

**The defect that bought it** (inbox #189 → #201, cr-workflow-actions-pinned-by-commit, 2026-09-28). Nothing held
the SHA pins at all, and two things were already wrong under them. `setup-gradle` was pinned to an annotated
*tag object*, not a commit — the only one of 13 pins, found only through the GitHub API. And all 39 sites carried
a major-only comment (`# v4`), which Dependabot rewrites only when the comment ENDS with a tag still sitting on
the old SHA (dependabot-core `version_commenter.rb` `previous_version_from_comment`): a floating major moves off
at the next release, so the bump would move the SHA to v7 and leave `# v4` standing — a comment that lies. A
guard checking only the 40-hex shape was green on that tree.

**Its limits — what a green run does NOT prove.** It reads files and contacts nothing, so it cannot tell a commit
SHA from a tag-object SHA (the very defect above), nor that the comment names the version the SHA resolves to,
nor that the action runs on a runtime GitHub still has: those are checked by resolving each pin through the API
when it is bumped (`git/commits/<sha>` 200 and `git/tags/<sha>` 404, `runs.using` read from `action.yml` at that
commit), trust-on-first-write like `validate_image_pins.py`'s index digests. R2 does not apply to `docker://`
pins (the digest is the whole identity; none exist today). A `uses:` spelled inside a block scalar (a `run: |`
that echoes one) is found by the line read and not by YAML, so it is red — a false positive, never a silent
pass. A GitHub repository setting that enforces SHA pins at run time is the complement this file cannot be.
"""
from __future__ import annotations  # `str | None` below — the repo's guards run on Python 3.9

import collections
import pathlib
import re
import subprocess
import sys

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the other guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOW_DIR = ".github/workflows/"

LOCAL = re.compile(r"^\./")
DOCKER = re.compile(r"^docker://[^@\s]+@sha256:[0-9a-f]{64}$")
REMOTE = re.compile(r"^(?P<repo>[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)(?:/[^@\s]+)?@(?P<sha>[0-9a-f]{40})$")
# A `uses:` key on its own line, as a block mapping key or the first key of a sequence item. A comment line (the
# first non-blank character is `#`) never matches, which is the point: codeql.yml carries a commented example.
USES_LINE = re.compile(r"""^\s*(?:-\s+)?uses:\s*(?P<value>["']?[^\s#"']+["']?)(?P<rest>.*)$""")
FULL_VERSION_COMMENT = re.compile(r"^\s+#\s*(?P<version>v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)\s*$")

errors: list[str] = []


def fail(message: str) -> None:
    errors.append(message)


def anchor_files() -> list[str]:
    out = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, text=True)
    if out.returncode != 0:
        fail(f"`git ls-files` failed, so no site could be found and this guard checked NOTHING: {out.stderr.strip()}")
        return []
    files = [p for p in out.stdout.split("\0") if p and "node_modules/" not in p]
    return sorted(p for p in files
                  if (p.startswith(WORKFLOW_DIR) and p.endswith((".yml", ".yaml")))
                  or pathlib.PurePosixPath(p).name in ("action.yml", "action.yaml"))


def yaml_uses(node: object, seen: set | None = None) -> list[str]:
    """Every string value under a `uses` key, at any depth — each written one once: a YAML alias (`steps:
    *build-steps`, checks.yml's two image builds) is the anchored node itself, which PyYAML hands over as the SAME
    object, so it is read where it is written and not again where it is aliased, as the line count reads it."""
    seen = set() if seen is None else seen
    found: list[str] = []
    if isinstance(node, (dict, list)):
        if id(node) in seen:
            return found
        seen.add(id(node))
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "uses" and isinstance(value, str):
                found.append(value)
            else:
                found.extend(yaml_uses(value, seen))
    elif isinstance(node, list):
        for item in node:
            found.extend(yaml_uses(item, seen))
    return found


def main() -> int:
    files = anchor_files()
    if not any(p.startswith(WORKFLOW_DIR) for p in files):
        fail(f"no tracked workflow under {WORKFLOW_DIR} — re-anchor this guard")
    pins: dict[str, set[str]] = collections.defaultdict(set)  # repo -> {"<sha> # <version>"}
    sites: dict[str, list[str]] = collections.defaultdict(list)
    checked = 0
    for rel in files:
        text = (ROOT / rel).read_text()
        try:
            doc = yaml.safe_load(text)
        except yaml.YAMLError as e:
            fail(f"{rel} does not parse as YAML, so GitHub would refuse it and no pin in it can be checked: "
                 f"{str(e).splitlines()[0]} ({getattr(e, 'problem_mark', '')})")
            continue
        by_yaml = collections.Counter(yaml_uses(doc))
        by_line: collections.Counter[str] = collections.Counter()
        for number, line in enumerate(text.splitlines(), 1):
            m = USES_LINE.match(line)
            if not m:
                continue
            value = m.group("value").strip("\"'")
            by_line[value] += 1
            where = f"{rel}:{number}"
            checked += 1
            if LOCAL.match(value):
                continue
            if value.startswith("docker://"):
                if not DOCKER.match(value):
                    fail(f"{where}: `{value}` is a container action without an `@sha256:<64 hex>` digest")
                continue
            remote = REMOTE.match(value)
            if not remote:
                fail(f"{where}: `{value}` is not pinned by a 40-hex lowercase commit SHA — a tag or branch is a "
                     f"pointer its owner can move. Resolve the release tag to its COMMIT and pin that")
                continue
            comment = FULL_VERSION_COMMENT.match(m.group("rest"))
            if not comment:
                fail(f"{where}: `{value}` carries no `# v<major>.<minor>.<patch>` comment (has `{m.group('rest').strip()}`)"
                     f" — Dependabot rewrites only a comment that ends with a tag still on the old SHA, so a "
                     f"major-only one goes stale at the first bump")
                continue
            pins[remote.group("repo")].add(f"{remote.group('sha')} # {comment.group('version')}")
            sites[remote.group("repo")].append(where)
        if by_yaml != by_line:
            only_yaml = sorted((by_yaml - by_line).elements())
            only_line = sorted((by_line - by_yaml).elements())
            fail(f"{rel}: the `uses:` read as YAML and the ones read as lines differ (YAML only: {only_yaml}; "
                 f"lines only: {only_line}) — a flow-style step or a `uses:` this guard cannot locate a comment "
                 f"for; write it as a block-style `uses:` line")
    for repo, variants in sorted(pins.items()):
        if len(variants) > 1:
            fail(f"{repo} is pinned {len(variants)} ways ({'; '.join(sorted(variants))}) across "
                 f"{', '.join(sites[repo])} — one action, one pin")
    if checked == 0 and not errors:
        fail("no `uses:` line was found in any tracked workflow or action — this guard checked nothing")
    if errors:
        for e in errors:
            print(f"::error::action pins: {e}")
        return 1
    print(f"action pins: {checked} `uses:` site(s) in {len(files)} file(s), {len(pins)} action(s), each pinned "
          f"by one commit SHA with its full version")
    return 0


if __name__ == "__main__":
    sys.exit(main())
