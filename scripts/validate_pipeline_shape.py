#!/usr/bin/env python3
"""Every job of every workflow runs on a budget, and the guard gaps run when a guard changes (ADR 0091).

**What it asserts.** The anchor set is every job of every `.github/workflows/*.y(a)ml` in the directory, found by
reading the files, never listed (§ *a guard derives its anchor set from the code*, changes/README.md), so a
workflow added tomorrow is held the moment it exists:

  0. **Each file parses and has jobs.** A workflow YAML cannot load is one GitHub refuses, and one whose budgets
     nothing here sees.
  1. **Budget** (decision 1). Every job declares `timeout-minutes` as a positive whole number. The VALUES live in one
     place, How veyq is tested § Where and when each stage runs (the stages' Budget column and the line on the jobs
     that are not stages; ADR 0091 decision 1 holds the rule), and this guard keeps no second copy of them: it holds
     that a budget is there, that page says which. A job that calls a reusable
     workflow (`jobs.<id>.uses`) is exempt — GitHub refuses `timeout-minutes` there, and the called workflow's own
     jobs carry the budget (a local one is in this directory and held here).
  2. **The guard-gap stage's trigger** (decision 4). `validate_guard_gaps.py` tests the guards, not the commit, so:
     (a) a job that loops over the `scripts/validate_*.py` glob names `validate_guard_gaps.py` (the exclusion — the
     glob would otherwise run it on every push, the option decision 4 rejected); (b) at least one job runs it on its
     own; (c) each such job sits in a workflow with a `schedule` (the nightly full run), and its `if:` admits the
     schedule and a `needs.<job>.outputs.<filter> == 'true'`; (d) that needed job's one `dorny/paths-filter` step
     carries the filter, the job's output is that step's, and the filter lists every input `validate_guard_gaps.py`
     hashes for its local skip — read from that guard's `SKIP_INPUTS`, so the local skip and the CI filter cannot
     watch different trees.

**The defect that bought it.** No incident — built with cr-one-pipeline-per-commit (phase 3) because no job of the
seven workflows carried a `timeout-minutes` when ADR 0091 gave every stage a budget, so every one of them ran on
GitHub's default of 360 minutes: a hung e2e boot or a wedged Gradle daemon would have held a runner for six hours,
and a stage that had outgrown its budget would have stayed green instead of becoming a decision (ADR 0091: a stage
over its budget is sharded or moved, never made faster by weakening what ships). Assertion 2 came with phase 4,
when the guard gaps left the unfiltered glob: a filter that misses `scripts/` would let a guard change land with no
CI run of the cases that test it until the next nightly run, and nothing would say so.

**Its limits — what a green run does NOT prove.** That a budget is the page's: the value is read for its shape, never
compared to the table, so a job given 60 where the page says 15 is green here (the page is the values' home on
purpose — a copy here would be the second table that drifts). That a budget fits: whether a job finishes inside it
is a fact about a run, and only a run shows it (`scripts/run_pipeline.py` locally, CI once there is a remote). A
budget written as an expression (`${{ … }}`) is red, a false positive rather than a value this guard cannot read.
A step-level `timeout-minutes` is neither required nor read. Assertion 2 reads text and shape: it cannot see that
the filter fires on a real push (only a CI run shows it), that the `if:` is sound beyond the two terms it looks
for, or that a loop's mention of `validate_guard_gaps.py` really is an exclusion. Anchors outside the hashed inputs
(a coverage file a forgery edits, an ADR's wording) are seen by neither the filter nor the local skip — that is
`validate_guard_gaps.py`'s own documented limit, and the nightly run is what catches it.
"""
from __future__ import annotations  # the repo's guards run on Python 3.9

import ast
import pathlib
import re
import sys

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the other guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOWS = ROOT / ".github" / "workflows"
BUDGETS = "How veyq is tested § Where and when each stage runs"
GUARD_GAPS = "validate_guard_gaps.py"
GLOB = "scripts/validate_*.py"
PATHS_FILTER = re.compile(r"^dorny/paths-filter@")
NEEDS_OUTPUT = re.compile(r"needs\.([A-Za-z0-9_-]+)\.outputs\.([A-Za-z0-9_-]+)\s*==\s*'true'")
SCHEDULE_TERM = re.compile(r"github\.event_name\s*==\s*'schedule'")

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
                 f"stage's budget from {BUDGETS}")
        elif isinstance(budget, bool) or not isinstance(budget, int) or budget <= 0:
            fail(f"{where}: `timeout-minutes: {budget!r}` is not a positive whole number of minutes ({BUDGETS})")
        else:
            held += 1
    return held


def skip_inputs() -> list[str]:
    """`SKIP_INPUTS` of validate_guard_gaps.py, read from its source as the literal it is."""
    source = ROOT / "scripts" / GUARD_GAPS
    try:
        tree = ast.parse(source.read_text())
    except (OSError, SyntaxError) as e:
        fail(f"cannot read SKIP_INPUTS from scripts/{GUARD_GAPS}: {e}")
        return []
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "SKIP_INPUTS"
                                                for t in node.targets):
            try:
                value = ast.literal_eval(node.value)
            except ValueError:
                break
            if isinstance(value, (tuple, list)) and value and all(isinstance(v, str) for v in value):
                return list(value)
    fail(f"scripts/{GUARD_GAPS} has no literal SKIP_INPUTS tuple of paths — re-anchor this guard's assertion 2")
    return []


def run_text(job: dict) -> str:
    return "\n".join(s["run"] for s in job.get("steps") or []
                     if isinstance(s, dict) and isinstance(s.get("run"), str))


def check_guard_gap_trigger(docs: dict[str, dict]) -> int:
    wanted = [f"{i.rstrip('/')}/**" for i in skip_inputs()]
    runners = 0
    for rel, doc in docs.items():
        jobs = doc["jobs"]
        triggers = doc.get("on", doc.get(True))  # YAML 1.1 reads a bare `on` key as the boolean true
        for job_id, job in jobs.items():
            if not isinstance(job, dict):
                continue
            text = run_text(job)
            where = f"{rel} › {job_id}"
            if GLOB in text:
                if GUARD_GAPS not in text:
                    fail(f"{where} loops over {GLOB} without naming {GUARD_GAPS} as its exclusion, so the guard gaps "
                         f"run on every commit — ADR 0091 decision 4 runs them when a guard changes, and nightly")
                continue
            if GUARD_GAPS not in text:
                continue
            runners += 1
            if not (isinstance(triggers, dict) and triggers.get("schedule")):
                fail(f"{where} runs {GUARD_GAPS}, and {rel} has no `schedule` — decision 4's nightly full run is "
                     f"missing")
            condition = job.get("if") if isinstance(job.get("if"), str) else ""
            if not SCHEDULE_TERM.search(condition):
                fail(f"{where}: its `if:` does not admit `github.event_name == 'schedule'`, so the nightly run skips it")
            need = NEEDS_OUTPUT.search(condition)
            if not need:
                fail(f"{where}: its `if:` names no `needs.<job>.outputs.<filter> == 'true'`, so no path filter gates it")
                continue
            filter_job_id, output = need.group(1), need.group(2)
            needs = job.get("needs")
            needs = [needs] if isinstance(needs, str) else (needs or [])
            filter_job = jobs.get(filter_job_id)
            if filter_job_id not in needs or not isinstance(filter_job, dict):
                fail(f"{where}: its `if:` reads `{filter_job_id}`, which is not one of its `needs`")
                continue
            steps = [s for s in filter_job.get("steps") or []
                     if isinstance(s, dict) and PATHS_FILTER.match(str(s.get("uses", "")))]
            if len(steps) != 1:
                fail(f"{rel} › {filter_job_id} has {len(steps)} dorny/paths-filter step(s), not one")
                continue
            step = steps[0]
            exported = str((filter_job.get("outputs") or {}).get(output, ""))
            if f"steps.{step.get('id')}.outputs.{output}" not in exported:
                fail(f"{rel} › {filter_job_id}: output `{output}` is not the paths-filter step's "
                     f"(`steps.{step.get('id')}.outputs.{output}`), it is `{exported}`")
            try:
                filters = yaml.safe_load(str((step.get("with") or {}).get("filters", ""))) or {}
            except yaml.YAMLError:
                filters = {}
            patterns = filters.get(output) if isinstance(filters, dict) else None
            patterns = patterns if isinstance(patterns, list) else []
            missing = [w for w in wanted if w not in patterns]
            if missing:
                fail(f"{rel} › {filter_job_id}: filter `{output}` misses {missing}, inputs {GUARD_GAPS} hashes (its "
                     f"SKIP_INPUTS) — a change there would land with no CI run of the guard gaps until the nightly one")
    if runners == 0:
        fail(f"no workflow job runs {GUARD_GAPS} on its own — ADR 0091 decision 4's stage is missing from CI")
    return runners


def main() -> int:
    files = workflow_files()
    if not files:
        fail(f"no workflow under {WORKFLOWS.relative_to(ROOT)} — re-anchor this guard")
    held = 0
    docs: dict[str, dict] = {}
    for path in files:
        doc = load(path)
        if doc is None:
            continue
        rel = path.relative_to(ROOT).as_posix()
        docs[rel] = doc
        held += check_budgets(rel, doc["jobs"])
    runners = check_guard_gap_trigger(docs)
    if errors:
        for e in errors:
            print(f"::error::pipeline shape: {e}")
        return 1
    print(f"pipeline shape: {held} job(s) in {len(files)} workflow(s), each on a budget ({BUDGETS} holds the values); "
          f"{runners} guard-gap job(s), path-filtered on what {GUARD_GAPS} hashes and run nightly")
    return 0


if __name__ == "__main__":
    sys.exit(main())
