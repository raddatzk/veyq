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

The mutate scope is read from each project's own stryker.mutate.json — the SAME manifests the matching
stryker.config.mjs files consume, so a mutator and its check never drift. There are TWO since
cr-admin-web-extraction (ADR 0065) split the suite: `packages/crypto` (the shared suite) and `web`
(the six crypto-logic files that stayed). They are checked **per project, not as a union**: Stryker's test
runner is the project's own vitest, so each project produces its own report, and a diff touching only one
project's scope must be answered by THAT project's report — a union would let a healthy web report excuse a
crypto run that measured nothing. A project whose manifest is absent FAILS, each on its own: skipping it let
a moved project leave this check reading the other one and saying OK (cr-repo-layout-src) — so the selftest
fabricates both. Runnable locally too:

    python3 scripts/mutation_meta_check.py --base origin/main
"""
import argparse
import fnmatch
import json
import pathlib
import subprocess
import sys

import repo_layout  # where the code surfaces live (ADR 0106)

ROOT = pathlib.Path(__file__).resolve().parent.parent

# (repo-relative project dir) — the manifest is <dir>/stryker.mutate.json and the report
# <dir>/reports/mutation/mutation.json, mirroring each project's own stryker.config.mjs.
PROJECTS = (repo_layout.rel(repo_layout.WEB), repo_layout.rel(repo_layout.CRYPTO))


def changed_files(base: str) -> list[str]:
    """Repo-relative paths changed on HEAD's side since the merge-base with `base` (the PR's own diff)."""
    out = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...HEAD"],
        cwd=ROOT, capture_output=True, text=True, check=True,
    ).stdout
    return [line.strip() for line in out.splitlines() if line.strip()]


def mutate_scope(project: str) -> list[str]:
    """The project's mutate patterns, made repo-relative (Stryker's cwd is the project dir, so the manifest is
    project-relative)."""
    manifest = json.loads((ROOT / project / "stryker.mutate.json").read_text())
    return [f"{project}/{p}" for p in manifest["mutate"]]


def in_scope(path: str, patterns: list[str]) -> bool:
    # Exact-file entries are the norm (ADR 0045 §3 curates an explicit list); fnmatch covers a future glob.
    return any(path == pat or fnmatch.fnmatch(path, pat) for pat in patterns)


def report_mutant_count(report_path: pathlib.Path) -> int:
    report = json.loads(report_path.read_text())
    return sum(len(f.get("mutants", [])) for f in report.get("files", {}).values())


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--base", default="origin/main", help="PR base ref the run was scoped against")
    args = ap.parse_args()

    changed = changed_files(args.base)
    failed = False
    checked = 0

    for project in PROJECTS:
        if not (ROOT / project / "stryker.mutate.json").exists():
            print(f"::error::meta-check[{project}]: no {project}/stryker.mutate.json — the scope this check reads "
                  f"has moved or been deleted, so this project's run would be judged by nothing.")
            failed = True
            continue
        checked += 1
        scope = mutate_scope(project)
        touched = sorted(f for f in changed if in_scope(f, scope))

        if not touched:
            print(f"meta-check[{project}]: PR touches no file in the mutate scope {scope} — "
                  f"nothing to assert. OK.")
            continue

        report = ROOT / project / "reports" / "mutation" / "mutation.json"
        if not report.exists():
            print(f"::error::meta-check[{project}]: {touched} changed but no Stryker report at {report} "
                  f"— the gate ran nothing.")
            failed = True
            continue

        total = report_mutant_count(report)
        if total == 0:
            print(f"::error::meta-check[{project}]: PR changed {touched} but Stryker reported 0 mutants — a "
                  f"green run that measured nothing (ADR 0045 §4). Check the mutate scope and the manifest glob.")
            failed = True
            continue

        print(f"meta-check[{project}]: PR changed {touched}; Stryker reported {total} mutants (> 0). OK.")

    if checked == 0:
        print("::error::meta-check: no stryker.mutate.json found for any known project "
              f"{PROJECTS} — the scope this check reads has moved or been deleted.")
        return 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
