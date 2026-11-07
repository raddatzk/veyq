#!/usr/bin/env python3
"""Every class the backend mutation gate mutates must also TRIGGER it — and start the backend gate.

**What it asserts.** For each entry in `src/backend/core/pitest.scope.json` (`targetClasses` + `targetTests`), derive
the `.kt` file it names — package → directory, class → filename, a trailing `*` stripped — and assert that
file (a) exists on disk and (b) is listed in **both** `paths:` blocks of `.github/workflows/mutation.yml` AND in
its `changes` job's `backend` filter. The blocks are checked **separately, not as a union**: Actions supports no
YAML anchors, so the PR trigger, the main-branch backstop and the filter are hand-duplicated and can drift apart —
which is exactly the failure this guard exists for. A **subset** check: they legitimately also list the manifest,
`build.gradle.kts`, the meta-checks and the workflow itself, and extra entries are not this guard's business.

**And the workflow's gate picking holds** (since the two mutation workflows became one, 2026-11-07,
cr-workflows-split-pipeline-callers). The workflow starts on the union of both gates' scopes, and the `changes`
job's `dorny/paths-filter` step says which gate runs. So (c) every entry of every filter is in both trigger
blocks — a filtered path that does not start the workflow is a gate that never runs for it; (d) the job that runs
PIT (`gradlew … pitest`) and the job that runs Stryker (`pnpm run mutation`) — found by what they run, never by
name — each need the filter's job and carry `needs.<it>.outputs.<filter> == 'true'` in their `if:` (`backend`,
`crypto`), and that job's output is the filter step's own (`steps.<id>.outputs.<filter>`) — a gate reading the
other gate's filter runs on the wrong changes and never on its own.

**The defect that bought it — measured, not hypothetical.** `a8cc4ce4` (2026-07-19) created the workflow when
the scope held three classes; the list was correct. `b15807a7` (2026-07-20) added `ShareAccessPolicy` and
`MembershipPolicy` to the manifest and did **not** touch the workflow (`git show --stat b15807a7 | grep -c
mutation-backend.yml` → 0; the backend gate's workflow of that time). From that day a PR touching **only** one of those two classes never triggered the
assertion-strength gate at all — the check silently did not run on exactly the change it exists for. The
workflow's own comment asserted the opposite (*"a new policy enters via a pitest.scope.json edit, which is
listed here, so adding one always triggers the gate"*): true for the adding commit, false for every later edit
to that class. The manual list broke on the first policy added after it existed, one day later. Inbox #114.

Enforcing the manifest's *"an EXPLICIT list, never a package sweep"* rule falls out for free: a package glob
(`me.raddatz.veyq.core.sharing.*`) derives no filename and is rejected here with that reason named.

**Its limit — a path filter cannot cover transitive dependencies.** `MembershipPolicy` imports
`core.membership.MembershipEntity`; `RemovalPolicy` imports that plus `core.scope.ScopeEntity`. A change
to those moves a mutation score without touching any listed path, and no path-based trigger list can see it —
this is a principled limit of the mechanism, not a hole to plug (pinned as a case in
`validate_guard_gaps.py`). Two narrower ones: it proves a path is **listed**, never that GitHub Actions
actually dispatches on it (a malformed filter elsewhere, or a disabled workflow, is outside); and it says
nothing about whether the gate, once triggered, is strict enough — that is
`mutation_meta_check_backend.py`'s job and the build's `mutationThreshold`.

Buildless and diff-free, so it belongs in the trust-chain glob (unlike its two `mutation_meta_check*.py`
siblings, which need a built report and therefore run from the mutation jobs).
"""
from __future__ import annotations  # `str | None` below — the repo's guards run on Python 3.9

import json
import pathlib
import sys

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here
import repo_layout  # where the code surfaces live (ADR 0106)

try:
    import yaml
except ImportError:
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
MANIFEST = repo_layout.BACKEND / "core" / "pitest.scope.json"  # :core owns PIT (it mutates core classes only)
WORKFLOW = ROOT / ".github" / "workflows" / "mutation.yml"
PATHS_FILTER = "dorny/paths-filter"
# What each gate runs, and the filter that must start it.
GATES = {"backend": "pitest", "crypto": "pnpm run mutation"}

SOURCE_ROOTS = {"targetClasses": backend_tree.main_roots,
                "targetTests": lambda: backend_tree.test_roots() + backend_tree.fixture_roots()}

errors: list[str] = []


def derive_path(entry: str, source_roots: list[pathlib.Path]) -> str | None:
    """A PIT class glob → the repo-relative .kt file that declares it, under whichever source root holds it
    (`backend_tree.source_file`). `None` when the entry names no single file (a package sweep), which the manifest
    forbids anyway."""
    name = entry.rstrip("*")
    name = name.split("$", 1)[0]  # a nested class lives in its outer class's file
    if not name or name.endswith("."):
        return None
    return backend_tree.source_file(name, source_roots).relative_to(ROOT).as_posix()


def trigger_blocks() -> dict[str, list[str]]:
    """The workflow's `paths:` filters, keyed by trigger. NOTE: PyYAML is YAML 1.1, where the bare key `on` is
    the BOOLEAN True — `data["on"]` raises KeyError on a perfectly valid workflow. This is not a typo."""
    data = yaml.safe_load(WORKFLOW.read_text())
    on = data.get("on", data.get(True)) or {}
    return {trigger: (on.get(trigger) or {}).get("paths") or [] for trigger in ("pull_request", "push")}


def filters() -> tuple[str | None, str | None, dict[str, list[str]]]:
    """(the filter job's id, its filter step's id, filter name -> paths) of the one `dorny/paths-filter` step."""
    data = yaml.safe_load(WORKFLOW.read_text())
    found = []
    for job_id, job in (data.get("jobs") or {}).items():
        for step in (job or {}).get("steps") or []:
            if isinstance(step, dict) and str(step.get("uses") or "").split("@", 1)[0] == PATHS_FILTER:
                spec = yaml.safe_load(str((step.get("with") or {}).get("filters") or "")) or {}
                found.append((job_id, step.get("id"), {str(k): [str(p) for p in v or []] for k, v in spec.items()}))
    if len(found) != 1:
        errors.append(f"{WORKFLOW.name}: {len(found)} `{PATHS_FILTER}` step(s), not one — the gates are picked by one "
                      f"filter step, whose filters this guard holds to the triggers")
        return None, None, {}
    return found[0]


def check_gates(filter_job: str | None, filter_step: str | None, named: dict[str, list[str]]) -> None:
    """(c) every filter entry triggers the workflow on both events; (d) each gate is started by its own filter."""
    blocks = trigger_blocks()
    for name, paths in named.items():
        for path in paths:
            for trigger, listed in blocks.items():
                if path not in listed:
                    errors.append(f"{WORKFLOW.name}: the `{name}` filter lists '{path}', which the '{trigger}' "
                                  f"`paths:` trigger does not — a change to it alone never starts the workflow, so "
                                  f"its gate never runs")
    data = yaml.safe_load(WORKFLOW.read_text())
    jobs = data.get("jobs") or {}
    outputs = (jobs.get(filter_job) or {}).get("outputs") or {}
    for gate, runs in GATES.items():
        gate_jobs = [j for j, job in jobs.items() if any(runs in str((s or {}).get("run") or "")
                                                         for s in (job or {}).get("steps") or [])]
        if len(gate_jobs) != 1:
            errors.append(f"{WORKFLOW.name}: {len(gate_jobs)} job(s) run `{runs}`, not one — the `{gate}` gate is "
                          f"found by what it runs")
            continue
        job = jobs[gate_jobs[0]]
        needs = job.get("needs") or []
        needs = [needs] if isinstance(needs, str) else list(needs)
        condition = " ".join(str(job.get("if") or "").split())
        if gate not in named:
            errors.append(f"{WORKFLOW.name}: no `{gate}` filter — nothing says when the `{gate}` gate runs")
        if filter_job not in needs or f"needs.{filter_job}.outputs.{gate} == 'true'" not in condition:
            errors.append(f"{WORKFLOW.name}: job `{gate_jobs[0]}` (the {gate} gate) is not started by the `{gate}` "
                          f"filter — it must need `{filter_job}` and carry `needs.{filter_job}.outputs.{gate} == "
                          f"'true'` in its `if:` (found `{condition or 'none'}`)")
        if str(outputs.get(gate) or "").replace(" ", "") != f"${{{{steps.{filter_step}.outputs.{gate}}}}}":
            errors.append(f"{WORKFLOW.name}: job `{filter_job}`'s output `{gate}` is `{outputs.get(gate)}`, not its "
                          f"filter step's `steps.{filter_step}.outputs.{gate}`")


def main() -> int:
    scope = json.loads(MANIFEST.read_text())
    blocks = trigger_blocks()
    filter_job, filter_step, named = filters()
    check_gates(filter_job, filter_step, named)
    if "backend" in named:
        blocks["backend filter"] = named["backend"]

    for trigger, paths in blocks.items():
        if not paths:
            errors.append(f"{WORKFLOW.name}: trigger '{trigger}' has no `paths:` filter — the gate would run "
                          f"on every push (or not at all). Expected the scope's files to be listed there.")

    checked = 0
    for key, source_roots in SOURCE_ROOTS.items():
        for entry in scope.get(key) or []:
            rel = derive_path(entry, source_roots())
            if rel is None:
                errors.append(f"pitest.scope.json: '{entry}' ({key}) names no single class — a package sweep. "
                              f"The manifest's own rule is an EXPLICIT list (ADR 0045 §2); it also leaves this "
                              f"guard unable to derive a trigger path.")
                continue
            checked += 1

            if not (ROOT / rel).exists():
                errors.append(f"pitest.scope.json: '{entry}' ({key}) derives {rel}, which does not exist — a "
                              f"renamed or moved class. PIT would mutate nothing for it.")

            for trigger, paths in blocks.items():
                if paths and rel not in paths:
                    errors.append(f"{WORKFLOW.name}: '{rel}' is in the mutate scope ('{entry}') but missing "
                                  f"from the '{trigger}' list — a change to it alone would never "
                                  f"start the backend mutation gate (inbox #114).")

    if errors:
        print("MUTATION TRIGGER PATH VALIDATION FAILED:")
        for e in errors:
            print("  -", e)
        return 1

    print(f"mutation trigger paths OK: {checked} scoped file(s) exist and are listed in "
          f"{', '.join(blocks)}; every entry of the {', '.join(named)} filters triggers the workflow, and each gate "
          f"is started by its own filter")
    print("  scope: the scope's OWN files trigger the gate. NOT transitive dependencies — see the docstring.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
