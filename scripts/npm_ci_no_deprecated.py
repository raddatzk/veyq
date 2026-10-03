#!/usr/bin/env python3
"""`npm ci`, refused when npm reports a deprecated package — the npm half of the root CLAUDE.md dependency rule.

**What it asserts.** It runs `npm ci` from the repository root (any extra arguments are passed through), echoes
npm's output as it comes, and exits non-zero when npm fails **or** when any line of its output is npm's
deprecation warning (`npm warn deprecated <name>@<version>: …`, either case of `warn`), naming every package it
saw. The registry marks a package deprecated in its packument and `npm ci` prints that mark for every installed
`name@version` that carries it, so the whole resolved tree is covered, transitive dependencies included — which
is where the one defect so far sat. Two things keep a green from meaning "nothing was looked at":
  - `--loglevel=notice` (npm's default) is appended **last**, so neither an `.npmrc` nor an argument passed
    through can raise the level past `warn` and hide the line this reads;
  - npm's install summary (`added N packages` / `up to date`) must be in the captured output — the positive
    control that npm's output reached this process at all; without it the run is red, not green.

**The defect that bought it** (inbox #213 → #232, 2026-10-01). `@vue/test-utils` pulled `js-beautify`, which
pulled `glob@10.5.0`, deprecated by its maintainer; `npm ci` printed the warning on every install and nothing
failed, so the rule *"never keep a deprecated dependency"* was held by whoever happened to read an install log.
It was found by a review, not by the build. Bitten against that lockfile (the tree before `f21c50b`): red,
naming `glob@10.5.0`.

**Its limits — what a green run does NOT prove.** Only what the registry marks `deprecated`: a package that is
archived, abandoned or unmaintained without that mark passes (the root CLAUDE.md rule is wider; `web/CLAUDE.md`
§ jsqr is the standing example — neither archived nor deprecated, and stale). Only the npm tree: Gradle has no
such mark, and the Dockerfiles' own `npm ci` installs the same lockfile ungated. Only at install time: a package
deprecated after the last run is caught by the next one, not before. And only where it is called — every
`npm ci` in `.github/workflows/` goes through it today, but nothing enforces that a new one will. It needs the
network and a full install, so it is not a buildless `validate_*.py`; it replaces `npm ci` in the CI jobs
instead. Runnable locally the same way:

    python3 scripts/npm_ci_no_deprecated.py            # a full install, as CI does
    python3 scripts/npm_ci_no_deprecated.py --ignore-scripts
"""
from __future__ import annotations  # the repo's scripts run on Python 3.9

import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

DEPRECATED = re.compile(r"^npm (?:warn|WARN) deprecated (?P<pkg>\S+?):?(?:\s|$)")
SUMMARY = re.compile(r"^(?:added|removed|changed) \d+ packages?|^up to date")


def scan(lines: list[str]) -> tuple[list[str], bool]:
    """The deprecated packages npm named, and whether its install summary was seen."""
    deprecated = [m.group("pkg") for m in (DEPRECATED.match(line) for line in lines) if m]
    return deprecated, any(SUMMARY.match(line) for line in lines)


def main(argv: list[str]) -> int:
    cwd = ROOT
    cmd = ["npm", "ci", *argv, "--loglevel=notice"]
    proc = subprocess.Popen(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                            errors="replace")
    assert proc.stdout is not None
    lines: list[str] = []
    for line in proc.stdout:
        sys.stdout.write(line)
        lines.append(line.rstrip("\n"))
    code = proc.wait()
    if code != 0:
        print(f"npm ci failed (exit {code}) in {cwd}", file=sys.stderr)
        return code
    deprecated, summary = scan(lines)
    if deprecated:
        print(f"\nREFUSED: npm ci installed {len(deprecated)} deprecated package(s) — replace them now "
              "(root CLAUDE.md: no deprecated dependencies):", file=sys.stderr)
        for pkg in deprecated:
            print(f"  - {pkg}", file=sys.stderr)
        return 1
    if not summary:
        print("\nREFUSED: npm ci printed no install summary, so this process cannot tell 'no deprecation "
              "warning' from 'no output' — was the install silenced?", file=sys.stderr)
        return 1
    print("npm ci: no deprecated package in the installed tree.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
