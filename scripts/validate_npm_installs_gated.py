#!/usr/bin/env python3
"""Every npm install a workflow of this repository runs goes through the deprecation gate.

**What it asserts.** The gate is `scripts/npm_ci_no_deprecated.py` (root CLAUDE.md: no deprecated dependencies).
No `run:` step in a tracked `.github/workflows/*.y(a)ml` invokes npm's install family itself — `ci`, `install` and
every alias npm resolves to one of them (`i`, `add`, `clean-install`, `ic`, `it`, `cit`, the `isnt…` typo
aliases, …). An install there goes through the gate, which runs `npm ci` and is red on a deprecation warning, so a
bare one is an install the gate never sees. At least one step must call the gate — the positive control that this
guard is anchored on the right name.

**The defect that bought it — no incident.** Built 2026-10-03 for inbox #293, left by #232: the gate existed, all
four workflow installs called it, and nothing would have noticed a fifth that did not — the gate's own docstring
said so as its limit.

**Its limits — what a green run does NOT prove.**
  - **Spellings it does not parse.** An install reached through `npx`, `yarn`/`pnpm`, a shell variable or alias, a
    nested `sh -c '…'` string, or a third-party `uses:` action that installs on its own is invisible; so is one
    in a script a step calls (no tracked script but the gate runs npm's install today).
  - **Declarations, not runs.** It reads the workflows as written; there is no remote, so no CI run has executed.
  - **What the gate itself cannot see** stays out of reach — its own docstring states that (registry-marked
    deprecation only, the npm tree only, install time only).

Buildless and diff-free, so it belongs in the trust-chain glob.
"""
from __future__ import annotations  # the repo's guards run on Python 3.9

import pathlib
import re
import shlex
import subprocess
import sys

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the other guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
GATE = "scripts/npm_ci_no_deprecated.py"
GATE_CALL = re.compile(r"(?:^|[\s;&|(])(?:\S*/)?python3?\s+" + re.escape(GATE) + r"(?:\s|$)", re.MULTILINE)
WORKFLOW_NAME = re.compile(r"^\.github/workflows/[^/]+\.ya?ml$")

# npm's install family (npm's own alias table, `npm help install` / `npm help ci`): `ci` and its aliases,
# `install` and its aliases, and `install-ci-test` / `install-test`, which install before they test.
INSTALLS = {"ci", "clean-install", "ic", "install-clean", "isntall-clean", "install-ci-test", "cit",
            "install", "i", "in", "ins", "inst", "insta", "instal", "isnt", "isnta", "isntal", "isntall", "add",
            "install-test", "it"}
# Global options that take their value as the NEXT token (`npm --prefix web ci`); everything else starting with `-`
# is a flag or carries its value after `=`.
VALUE_OPTIONS = {"--prefix", "-C", "--workspace", "-w", "--userconfig", "--cache", "--registry", "--loglevel"}

errors: list[str] = []


def tracked() -> list[str]:
    out = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True)
    if out.returncode != 0:
        errors.append("`git ls-files` failed, so this guard cannot enumerate the files it should read")
        return []
    return out.stdout.splitlines()


def npm_installs(command: str) -> list[str]:
    """The npm install-family subcommands a shell command line invokes, e.g. ['ci']."""
    lexer = shlex.shlex(command, posix=True, punctuation_chars=True)
    lexer.whitespace_split = True
    try:
        tokens = list(lexer)
    except ValueError:  # an unbalanced quote: fall back to whitespace, which over- rather than under-reports
        tokens = command.split()
    found: list[str] = []
    for i, token in enumerate(tokens):
        if token != "npm" and not token.endswith("/npm"):
            continue
        j = i + 1
        while j < len(tokens) and tokens[j].startswith("-"):
            j += 2 if tokens[j] in VALUE_OPTIONS else 1
        if j < len(tokens) and tokens[j] in INSTALLS:
            found.append(tokens[j])
    return found


files = tracked()
workflows: dict[str, dict] = {}
for rel in sorted(f for f in files if WORKFLOW_NAME.match(f)):
    try:
        workflows[rel] = yaml.safe_load((ROOT / rel).read_text()) or {}
    except (OSError, yaml.YAMLError) as e:
        errors.append(f"{rel} does not load ({e}) — its installs could not be checked")
if not workflows:
    errors.append("no tracked .github/workflows/*.yml was found — this guard checked nothing")

gate_calls = 0
for rel, doc in workflows.items():
    for job_id, job in (doc.get("jobs") or {}).items():
        if not isinstance(job, dict):
            continue
        for index, step in enumerate(job.get("steps") or []):
            if not isinstance(step, dict) or not isinstance(step.get("run"), str):
                continue
            run = step["run"].replace("\\\n", " ")
            label = f"{rel}: job `{job_id}`, step {index + 1} ({step.get('name', 'unnamed')!r})"
            for line in run.splitlines():
                for sub in npm_installs(line):
                    errors.append(
                        f"{label} runs `npm {sub}` directly — install through `python3 {GATE}` (it runs `npm ci` "
                        f"and is red on a deprecated package), or this install is one the gate never sees."
                    )
            if GATE_CALL.search(run):
                gate_calls += 1
if workflows and gate_calls == 0:
    errors.append(f"no workflow step calls `{GATE}` — either every install vanished or the gate was renamed; "
                  f"re-anchor this guard")
if not (ROOT / GATE).is_file():
    errors.append(f"`{GATE}` does not exist — the gate this guard holds every install to is gone")

if errors:
    print("NPM INSTALLS GATED VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(f"npm installs gated OK: {gate_calls} workflow step(s) install through {GATE} and none bypasses it")
