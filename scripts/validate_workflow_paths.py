#!/usr/bin/env python3
"""Every repository path a workflow or Dependabot names exists in the tree.

**What it asserts.** Over every tracked `.github/workflows/*.y(a)ml` and `.github/dependabot.yml`, each read as YAML
(a file that does not parse is red, never skipped):

  1. **Path filters.** Every pattern of an event's `paths:` / `paths-ignore:` and of a `dorny/paths-filter` step's
     `filters:` that names a place matches at least one tracked file. A negation (`!pattern`) is judged by its
     pattern. Matched as GitHub reads a filter glob: `*` and `?` stay inside one path segment, `**` crosses them,
     `**/` may match no directory. A pattern that names a kind of file anywhere (`**/<glob without a slash>`, e.g.
     `**/*.tsx`) names no place a move could leave, and is a language's catch-all that may match nothing yet: exempt.
  2. **Working directories.** Every `working-directory:` (a step's, a job's or a workflow's `defaults.run`) is a
     tracked directory.
  3. **Files a step names.** Every `dockerfile:` value in a job's matrix and every `file:` of a step, every
     `cache-dependency-path:`, is a tracked file.
  4. **Cache paths.** Every line of an `actions/cache` step's `path:` names a tracked file, or lies under a tracked
     directory directly followed by a build-output segment (`build`, `reports`, `dist`, `node_modules`, `.gradle`):
     a cache holds output, which no tree tracks, so its anchor is the project directory that writes it.
  5. **Dependabot.** Every `directory:` and `directories:` entry is `/` or a tracked directory.
  A value carrying a `${{ … }}` expression is resolved by the runner, not here, and is skipped.

**The defect that bought it** (cr-repo-layout-src, 2026-10-20). Stage 1 of ADR 0106 moved the deployment files and
found that no guard read these values: a `working-directory:` left on a moved surface fails its job at run time,
and a path filter left on a moved tree fails *silently* — the workflow just stops running for the changes it gates.
Its first run on the untouched tree was red on filters that already matched nothing: `codeql.yml`'s `docs/api/**`
(twice) and `load/**`, left behind when the specs moved to `contracts/` and the load scripts were deleted, and in
both of `ci.yml`'s `paths-ignore:` blocks the `!docs/api/**` re-include (same origin) and `.github` (a filter glob
without `/**` names a file of that name, never the directory). All were removed, which changes no run: each matched
nothing. The same run was red on `codeql.yml`'s `**/*.cjs` and `**/*.tsx`, which match nothing because no such file
exists yet — that is a language's catch-all, not a stale place, and is why rule 1 exempts a location-free pattern.

**Its limit — what a green run does NOT prove.** Existence, never aptness: a filter that matches some file can still
miss the one it was written for, a location-free pattern (rule 1) is not checked at all, and a filter list can omit a tree
entirely (`validate_mutation_trigger_paths.py`
holds the mutation filters to their manifest; nothing else holds a list to what it should cover). Path-like values
under other keys (an `upload-artifact` `path:`, a `run:` script's `cd`) are not read, and a `${{ }}` value is
skipped. The filter glob is GitHub's documented subset (`*`, `**`, `?`), not every extension `paths-filter`'s
picomatch accepts. It reads `git ls-files`, so a file not yet added does not count — commit before believing a green.
"""
from __future__ import annotations  # the repo's guards run on Python 3.9

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
DEPENDABOT = ".github/dependabot.yml"
EXPRESSION = "${{"
# The directory names a cache path may continue with below its project directory: what a build writes, never tracks.
OUTPUT_SEGMENTS = {"build", "reports", "dist", "node_modules", ".gradle"}

errors: list[str] = []


def tracked() -> list[str]:
    out = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, text=True)
    if out.returncode != 0:
        errors.append(f"`git ls-files` failed, so no path could be checked: {out.stderr.strip()}")
        return []
    return [p for p in out.stdout.split("\0") if p]


def glob_regex(pattern: str) -> re.Pattern[str]:
    """A filter glob as GitHub reads it: `**/` any directories or none, `**` anything, `*`/`?` within a segment."""
    out, i = "", 0
    while i < len(pattern):
        if pattern.startswith("**/", i):
            out, i = out + "(?:.*/)?", i + 3
        elif pattern.startswith("**", i):
            out, i = out + ".*", i + 2
        elif pattern[i] == "*":
            out, i = out + "[^/]*", i + 1
        elif pattern[i] == "?":
            out, i = out + "[^/]", i + 1
        else:
            out, i = out + re.escape(pattern[i]), i + 1
    return re.compile(out + r"\Z")


def load(rel: str) -> object:
    try:
        return yaml.safe_load((ROOT / rel).read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as e:
        errors.append(f"{rel}: does not parse as YAML, so none of its paths was checked: {e}")
        return None


def walk(node: object, path: tuple = ()):
    """Every (key path, key, value) under a YAML node, at any depth."""
    if isinstance(node, dict):
        for k, v in node.items():
            yield path, k, v
            yield from walk(v, path + (k,))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from walk(v, path + (i,))


def lines(value: object) -> list[str]:
    """A scalar or a block of lines (`path: |`) as its non-blank, non-comment entries."""
    if isinstance(value, list):
        return [str(v) for v in value]
    return [s.strip() for s in str(value).splitlines() if s.strip() and not s.strip().startswith("#")]


class Tree:
    def __init__(self, files: list[str]):
        self.files = files
        self.file_set = set(files)
        self.dirs = {str(pathlib.PurePosixPath(f).parents[i]) for f in files
                     for i in range(len(pathlib.PurePosixPath(f).parts) - 1)}

    def matches(self, pattern: str) -> bool:
        rx = glob_regex(pattern)
        return any(rx.match(f) for f in self.files)

    def is_dir(self, path: str) -> bool:
        return path.strip("/") in self.dirs or path.strip("/") in ("", ".")

    def holds_cache(self, path: str) -> bool:
        parts = pathlib.PurePosixPath(path).parts
        if path in self.file_set:
            return True
        for n in range(len(parts) - 1, 0, -1):
            if "/".join(parts[:n]) in self.dirs:
                return parts[n] in OUTPUT_SEGMENTS
        return False


def names_a_place(pattern: str) -> bool:
    """False for `**/<a glob without a slash>`: a kind of file anywhere, which no move can leave behind (rule 1)."""
    return not (pattern.startswith("**/") and "/" not in pattern[3:])


def check_workflow(rel: str, doc: object, tree: Tree) -> int:
    count = 0
    for at, key, value in walk(doc):
        where = f"{rel}: {'.'.join('on' if p is True else str(p) for p in at + (key,))}"
        if key in ("paths", "paths-ignore") and at and at[0] in ("on", True):
            for pattern in (p for p in lines(value) if names_a_place(p.lstrip("!"))):
                count += 1
                if not tree.matches(pattern.lstrip("!")):
                    errors.append(f"{where}: `{pattern}` matches no tracked file — the filter is dead")
        elif key == "filters" and isinstance(value, str):
            try:
                groups = yaml.safe_load(value) or {}
            except yaml.YAMLError as e:
                errors.append(f"{where}: the filter block does not parse: {e}")
                continue
            for group, patterns in (groups.items() if isinstance(groups, dict) else []):
                for pattern in (p for p in lines(patterns) if names_a_place(p.lstrip("!"))):
                    count += 1
                    if not tree.matches(pattern.lstrip("!")):
                        errors.append(f"{where}.{group}: `{pattern}` matches no tracked file — the filter is dead")
        elif key == "working-directory":
            count += 1
            if EXPRESSION not in str(value) and not tree.is_dir(str(value)):
                errors.append(f"{where}: `{value}` is no tracked directory")
        elif key in ("dockerfile", "file", "cache-dependency-path"):
            for entry in lines(value):
                count += 1
                if EXPRESSION not in entry and entry not in tree.file_set:
                    errors.append(f"{where}: `{entry}` is no tracked file")
        elif key == "uses" and isinstance(value, str) and value.split("@")[0].startswith("actions/cache"):
            step = doc
            for p in at:
                step = step[p]
            for entry in lines((step.get("with") or {}).get("path", "")):
                count += 1
                if EXPRESSION not in entry and not tree.holds_cache(entry):
                    errors.append(f"{where}: the cache path `{entry}` lies under no tracked project directory")
    return count


def check_dependabot(doc: object, tree: Tree) -> int:
    count = 0
    for update in (doc or {}).get("updates", []) if isinstance(doc, dict) else []:
        entries = ([update["directory"]] if "directory" in update else []) + list(update.get("directories", []))
        for entry in entries:
            count += 1
            if not tree.is_dir(entry):
                errors.append(f"{DEPENDABOT}: {update.get('package-ecosystem')}: `{entry}` is no tracked directory")
    return count


def main() -> int:
    files = tracked()
    tree = Tree(files)
    workflows = sorted(f for f in files if f.startswith(WORKFLOW_DIR) and f.endswith((".yml", ".yaml")))
    if files and not workflows:
        errors.append(f"no tracked workflow under {WORKFLOW_DIR} — re-anchor this guard")
    count = sum(check_workflow(rel, load(rel), tree) for rel in workflows)
    if DEPENDABOT in tree.file_set:
        count += check_dependabot(load(DEPENDABOT), tree)
    else:
        errors.append(f"no tracked {DEPENDABOT} — re-anchor this guard")
    if not errors and count == 0:
        errors.append("read no path at all from the workflows — re-anchor this guard")
    if errors:
        print("WORKFLOW-PATH VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"workflow paths OK: {count} path(s) in {len(workflows)} workflow(s) and {DEPENDABOT} name tracked files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
