#!/usr/bin/env python3
"""Diff-aware meta-check for the crypto mutation gate (ADR 0045 §4).

The gate runs Stryker over the full curated scope every run, incremental-cache-accelerated — NOT
git-diff-scoped (`--since`): StrykerJS OSS has no diff scoping (that is the commercial arcmutate, ADR
0045 §1). So a green "100%, 0 mutants" is never a correctly-empty run; it is a BROKEN one — a mis-scoped
glob, a renamed file the manifest missed — that measured nothing. A green gate that measured nothing is a
claim nothing backs (ai/README.md rule 4). This asserts the one fact that catches it: **when the PR's
diff intersects the mutate scope, the Stryker report must carry > 0 mutants.**

Not a `validate_*.py`: the trust-chain job runs those on every push with no build, but this one needs
the Stryker report, which only the mutation CI job produces — so it is invoked from that job, not the
glob. It is a permanent guard all the same, standing alongside the config's break threshold.

The mutate scope is read from web/stryker.mutate.json — the SAME manifest stryker.config.mjs consumes,
so the mutator and this check never drift. Runnable locally too:

    python3 scripts/mutation_meta_check.py --base origin/main
"""
import argparse
import fnmatch
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
WEB = ROOT / "web"
MANIFEST = WEB / "stryker.mutate.json"
REPORT = WEB / "reports" / "mutation" / "mutation.json"


def changed_files(base: str) -> list[str]:
    """Repo-relative paths changed on HEAD's side since the merge-base with `base` (the PR's own diff)."""
    out = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...HEAD"],
        cwd=ROOT, capture_output=True, text=True, check=True,
    ).stdout
    return [line.strip() for line in out.splitlines() if line.strip()]


def mutate_scope() -> list[str]:
    """The mutate patterns, made repo-relative (Stryker's cwd is web/, so the manifest is web-relative)."""
    manifest = json.loads(MANIFEST.read_text())
    return [f"web/{p}" for p in manifest["mutate"]]


def in_scope(path: str, patterns: list[str]) -> bool:
    # Exact-file entries are the norm (ADR 0045 §3 curates an explicit list); fnmatch covers a future glob.
    return any(path == pat or fnmatch.fnmatch(path, pat) for pat in patterns)


def report_mutant_count() -> int:
    report = json.loads(REPORT.read_text())
    return sum(len(f.get("mutants", [])) for f in report.get("files", {}).values())


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--base", default="origin/main", help="PR base ref the run was scoped against")
    args = ap.parse_args()

    scope = mutate_scope()
    touched = sorted(f for f in changed_files(args.base) if in_scope(f, scope))

    if not touched:
        print(f"meta-check: PR touches no file in the mutate scope {scope} — nothing to assert. OK.")
        return 0

    if not REPORT.exists():
        print(f"::error::meta-check: {touched} changed but no Stryker report at {REPORT} "
              f"— the gate ran nothing.")
        return 1

    total = report_mutant_count()
    if total == 0:
        print(f"::error::meta-check: PR changed {touched} but Stryker reported 0 mutants — a green run that "
              f"measured nothing (ADR 0045 §4). Check the mutate scope and the manifest glob.")
        return 1

    print(f"meta-check: PR changed {touched}; Stryker reported {total} mutants (> 0). OK.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
