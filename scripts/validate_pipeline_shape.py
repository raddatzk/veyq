#!/usr/bin/env python3
"""Every job of every workflow runs on a budget (ADR 0091 decision 1).

**What it asserts.** The anchor set is every job of every `.github/workflows/*.y(a)ml` in the directory, found by
reading the files, never listed (§ *a guard derives its anchor set from the code*, changes/README.md), so a
workflow added tomorrow is held the moment it exists:

  0. **Each file parses and has jobs.** A workflow YAML cannot load is one GitHub refuses, and one whose budgets
     nothing here sees.
  1. **Budget.** Every job declares `timeout-minutes` as a positive whole number. The VALUES live in one place,
     ADR 0091 decision 1 (the stages' table and the line on the jobs that are not stages), and this guard keeps
     no second copy of them: it holds that a budget is there, the ADR says which. A job that calls a reusable
     workflow (`jobs.<id>.uses`) is exempt — GitHub refuses `timeout-minutes` there, and the called workflow's own
     jobs carry the budget (a local one is in this directory and held here).

**The defect that bought it.** No incident — built with cr-one-pipeline-per-commit (phase 3) because no job of the
seven workflows carried a `timeout-minutes` when ADR 0091 gave every stage a budget, so every one of them ran on
GitHub's default of 360 minutes: a hung e2e boot or a wedged Gradle daemon would have held a runner for six hours,
and a stage that had outgrown its budget would have stayed green instead of becoming a decision (ADR 0091: a stage
over its budget is sharded or moved, never made faster by weakening what ships).

**Its limits — what a green run does NOT prove.** That a budget is the ADR's: the value is read for its shape, never
compared to the table, so a job given 60 where the ADR says 15 is green here (the ADR is the values' home on
purpose — a copy here would be the second table that drifts). That a budget fits: whether a job finishes inside it
is a fact about a run, and only a run shows it (`scripts/run_pipeline.py` locally, CI once there is a remote). A
budget written as an expression (`${{ … }}`) is red, a false positive rather than a value this guard cannot read.
A step-level `timeout-minutes` is neither required nor read.
"""
from __future__ import annotations  # the repo's guards run on Python 3.9

import pathlib
import sys

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the other guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOWS = ROOT / ".github" / "workflows"
ADR = "ADR 0091 decision 1"

errors: list[str] = []


def fail(message: str) -> None:
    errors.append(message)


def workflow_files() -> list[pathlib.Path]:
    return sorted(p for p in WORKFLOWS.glob("*") if p.suffix in (".yml", ".yaml") and p.is_file())


def load(path: pathlib.Path) -> dict | None:
    rel = path.relative_to(ROOT).as_posix()
    try:
        doc = yaml.safe_load(path.read_text())
    except yaml.YAMLError as e:
        fail(f"{rel} does not parse as YAML, so GitHub would refuse it and no budget in it can be checked: "
             f"{str(e).splitlines()[0]}")
        return None
    if not isinstance(doc, dict) or not isinstance(doc.get("jobs"), dict) or not doc["jobs"]:
        fail(f"{rel} declares no `jobs:` mapping — a workflow with no job is one GitHub refuses")
        return None
    return doc


def check_budgets(rel: str, jobs: dict) -> int:
    held = 0
    for job_id, job in jobs.items():
        where = f"{rel} › {job_id}"
        if not isinstance(job, dict):
            fail(f"{where} is not a mapping")
            continue
        if "uses" in job:  # a reusable-workflow call: GitHub refuses timeout-minutes here
            continue
        budget = job.get("timeout-minutes")
        if budget is None:
            fail(f"{where} declares no `timeout-minutes`, so it runs on GitHub's 360-minute default — give it its "
                 f"stage's budget from {ADR}")
        elif isinstance(budget, bool) or not isinstance(budget, int) or budget <= 0:
            fail(f"{where}: `timeout-minutes: {budget!r}` is not a positive whole number of minutes ({ADR})")
        else:
            held += 1
    return held


def main() -> int:
    files = workflow_files()
    if not files:
        fail(f"no workflow under {WORKFLOWS.relative_to(ROOT)} — re-anchor this guard")
    held = 0
    for path in files:
        doc = load(path)
        if doc is None:
            continue
        held += check_budgets(path.relative_to(ROOT).as_posix(), doc["jobs"])
    if errors:
        for e in errors:
            print(f"::error::pipeline shape: {e}")
        return 1
    print(f"pipeline shape: {held} job(s) in {len(files)} workflow(s), each on a budget ({ADR} holds the values)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
