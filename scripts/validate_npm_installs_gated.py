#!/usr/bin/env python3
"""Every npm install a build of this repository runs is held to the deprecation gate — directly, or by a gated job
that installed the same lockfile first.

**What it asserts.** The gate is `scripts/npm_ci_no_deprecated.py` (root CLAUDE.md: no deprecated dependencies).
  1. **Workflows.** No `run:` step in a tracked `.github/workflows/*.y(a)ml` invokes npm's install family itself —
     `ci`, `install` and every alias npm resolves to one of them (`i`, `add`, `clean-install`, `ic`, `it`, `cit`,
     the `isnt…` typo aliases, …). An install there goes through the gate, which runs `npm ci` and is red on a
     deprecation warning, so a bare one is an install the gate never sees. At least one step must call the
     gate — the positive control that this guard is anchored on the right name.
  2. **Dockerfiles.** Every tracked Dockerfile (found by name in the index, never listed) is read for its `RUN`
     instructions. One that runs the resolving half of the family (`npm install`, `npm i`, …) is red: it does not
     install the lockfile, so no earlier scan of the lockfile speaks for it. One that runs `npm ci` is accepted
     **without** running the gate itself, on two premises this guard checks rather than trusts: (a) the stage
     `COPY`s the root `package-lock.json` before that `RUN` — so it installs the file the gate scanned — and (b)
     every workflow job that builds the Dockerfile (a job carrying its path as a value, e.g. a matrix
     `dockerfile:` entry) runs the gate itself or `needs:`, transitively, a job that does, so the gate has passed
     on the same commit before the image build starts. A Dockerfile with an `npm ci` that no workflow job builds
     is red too: nothing then covers its install.
  3. **Exactly one gated install asks the registry** (inbox #312). The gate's registry half — the locked packages
     an install skipped, i.e. other platforms' bindings — depends only on the lockfile, so every gated call but
     one passes `--skip-registry-check`. Red when none asks (nothing would look at another platform's bindings)
     or more than one does (the same ~54 queries repeated per commit). The one that asks must cover the others:
     its workflow fires on every `push`/`pull_request` branch the other gating workflows fire on (a missing
     `branches:` is every branch) and its path filter admits `package-lock.json`, and every job that builds a
     Dockerfile `needs:` it — the arm64 image is where those bindings are actually installed.
Why the Dockerfiles are covered by (b) and not by calling the gate inside the image (inbox #294): the gate is
Python, so the image would need either a Python toolchain added to two build stages or a second copy of the gate's
logic in shell — both to scan a lockfile the gating job has already scanned at the same commit.

**The defect that bought it — no incident.** Built 2026-10-03 for inbox #293 and extended for #294, both left by
#232: the gate existed, all four workflow installs called it, and nothing would have noticed a fifth that did not —
the gate's own docstring said so as its limit. The two Dockerfiles' `npm ci` were, by the same docstring,
"ungated", which on reading turned out to be true of the image step and false of the lockfile: `container.yml`'s
`build` job `needs: test`, and `test` installs through the gate. That argument was prose; this makes it a check.

**Its limits — what a green run does NOT prove.**
  - **Spellings it does not parse.** An install reached through `npx`, `yarn`/`pnpm`, a shell variable or alias, a
    nested `sh -c '…'` string, a heredoc `RUN`, or a third-party `uses:` action that installs on its own is
    invisible; so is one in a script a step calls. Two tracked scripts run npm's install: the gate, and
    `run_web_e2e.py --upgrade`, which installs a PREDECESSOR's lockfile in a worktree of that older commit - one the
    gate passed when it was the candidate, deliberately not gated again (a package deprecated since would turn the
    upgrade proof red for a reason that is not the upgrade; its `prepare_predecessor` says so).
  - **Declarations, not runs.** Premise (b) is the `needs:` graph as written: a gating step or job whose `if:` is
    an expression is counted as running (only a literal `false` is not), and a build job that checks out a
    different ref than the gating job is not detected. There is no remote, so no CI run has executed either.
  - **Trigger coverage (3) is compared by spelling.** Branch patterns are matched as exact strings (`release/**`
    on both sides, not glob containment), the path filter only against the one name `package-lock.json` with
    `fnmatch` (close to, not identical with, GitHub's glob), and `workflow_dispatch` and other events are not
    compared. A skipping run on a commit that left the lockfile alone relies on the asking job's run at the
    commit that last changed it, so a package deprecated in between is caught by the asking workflow's next run.
  - **What the gate itself cannot see** stays out of reach — its own docstring states that (registry-marked
    deprecation only, the npm tree only, install time only).

Buildless and diff-free, so it belongs in the trust-chain glob.
"""
from __future__ import annotations  # the repo's guards run on Python 3.9

import fnmatch
import json
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
SKIP_REGISTRY = "--skip-registry-check"  # the gate's opt-out of its registry half (inbox #312)
GATE_CALL = re.compile(r"(?:^|[\s;&|(])(?:\S*/)?python3?\s+" + re.escape(GATE) + r"(?:\s|$)", re.MULTILINE)
WORKFLOW_NAME = re.compile(r"^\.github/workflows/[^/]+\.ya?ml$")
DOCKERFILE_NAME = re.compile(r"(^|/)[^/]*dockerfile[^/]*$", re.IGNORECASE)
LOCKFILE = "package-lock.json"

# npm's install family, by what it installs from (npm's own alias table, `npm help install` / `npm help ci`).
# The lockfile-faithful half: `ci` and its aliases, and `install-ci-test` (`ci` then `test`).
LOCKFILE_INSTALLS = {"ci", "clean-install", "ic", "install-clean", "isntall-clean", "install-ci-test", "cit"}
# The resolving half: `install` and its aliases, and `install-test` (`install` then `test`).
RESOLVING_INSTALLS = {"install", "i", "in", "ins", "inst", "insta", "instal", "isnt", "isnta", "isntal",
                      "isntall", "add", "install-test", "it"}
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
        if j < len(tokens) and tokens[j] in LOCKFILE_INSTALLS | RESOLVING_INSTALLS:
            found.append(tokens[j])
    return found


def literally_off(node: dict) -> bool:
    return node.get("if") is False or str(node.get("if", "")).strip() in {"false", "${{ false }}"}


def scalars(node) -> list[str]:
    if isinstance(node, dict):
        return [s for v in node.values() for s in scalars(v)]
    if isinstance(node, list):
        return [s for v in node for s in scalars(v)]
    return [node] if isinstance(node, str) else []


# --- 1. workflows -------------------------------------------------------------------------------------------
files = tracked()
workflows: dict[str, dict] = {}
for rel in sorted(f for f in files if WORKFLOW_NAME.match(f)):
    try:
        workflows[rel] = yaml.safe_load((ROOT / rel).read_text()) or {}
    except (OSError, yaml.YAMLError) as e:
        errors.append(f"{rel} does not load ({e}) — its installs could not be checked")
if not workflows:
    errors.append("no tracked .github/workflows/*.yml was found — this guard checked nothing")

gating_jobs: dict[str, set[str]] = {}  # workflow -> jobs with a step that runs the gate
asking: list[tuple[str, str, str]] = []  # (workflow, job, step label) of each gate call that asks the registry
gate_calls = 0
for rel, doc in workflows.items():
    gating_jobs[rel] = set()
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
                if not literally_off(step) and not literally_off(job):
                    gating_jobs[rel].add(job_id)
                    for line in run.splitlines():
                        if GATE_CALL.search(line) and SKIP_REGISTRY not in line.split():
                            asking.append((rel, job_id, label))
if workflows and gate_calls == 0:
    errors.append(f"no workflow step calls `{GATE}` — either every install vanished or the gate was renamed; "
                  f"re-anchor this guard")
if not (ROOT / GATE).is_file():
    errors.append(f"`{GATE}` does not exist — the gate this guard holds every install to is gone")


def needs_closure(jobs: dict, job_id: str) -> set[str]:
    seen: set[str] = set()
    todo = [job_id]
    while todo:
        current = todo.pop()
        if current in seen:
            continue
        seen.add(current)
        needs = (jobs.get(current) or {}).get("needs") or []
        todo.extend([needs] if isinstance(needs, str) else list(needs))
    return seen


# --- 2. Dockerfiles -----------------------------------------------------------------------------------------
def instructions(body: str) -> list[tuple[int, str, str]]:
    """(line number, keyword, arguments) per instruction, continuation lines joined and comment lines dropped."""
    out: list[tuple[int, str, str]] = []
    buf, start = "", 0
    for number, line in enumerate(body.splitlines(), 1):
        if line.lstrip().startswith("#"):
            continue
        if not buf:
            start = number
        if line.rstrip().endswith("\\"):
            buf += line.rstrip()[:-1] + " "
            continue
        buf += line
        if buf.strip():
            keyword, _, args = buf.strip().partition(" ")
            out.append((start, keyword.upper(), args.strip()))
        buf = ""
    return out


def run_command(args: str) -> str:
    if args.startswith("["):
        try:
            return " ".join(shlex.quote(a) for a in json.loads(args))
        except ValueError:
            return args
    return args


dockerfiles = [f for f in files if DOCKERFILE_NAME.search(f)]
image_installs = 0
npm_images: set[str] = set()  # Dockerfiles that run `npm ci`
for rel in sorted(dockerfiles):
    lock_copied = False
    for number, keyword, args in instructions((ROOT / rel).read_text()):
        if keyword == "FROM":
            lock_copied = False  # a new stage starts with none of the previous stage's files
        elif keyword in {"COPY", "ADD"} and "--from" not in args:
            sources = [t for t in args.split() if not t.startswith("--")][:-1]
            lock_copied = lock_copied or LOCKFILE in sources
        elif keyword == "RUN":
            for sub in npm_installs(run_command(args)):
                where = f"{rel}:{number}"
                if sub in RESOLVING_INSTALLS:
                    errors.append(f"{where} runs `npm {sub}`, which resolves versions instead of installing the "
                                  f"lockfile — no gate's scan of the lockfile covers it; use `npm ci`")
                    continue
                image_installs += 1
                npm_images.add(rel)
                if not lock_copied:
                    errors.append(f"{where} runs `npm {sub}` in a stage that has not copied the root `{LOCKFILE}` "
                                  f"before it — so it is not the lockfile the gate scanned")
                builders = [(wf, job_id) for wf, doc in workflows.items()
                            for job_id, job in (doc.get("jobs") or {}).items()
                            if isinstance(job, dict) and rel in scalars(job)]
                if not builders:
                    errors.append(f"{where} runs `npm {sub}`, but no workflow job builds `{rel}` — so no gated job "
                                  f"runs before it and nothing covers this install")
                for wf, job_id in builders:
                    jobs = workflows[wf].get("jobs") or {}
                    if not needs_closure(jobs, job_id) & gating_jobs[wf]:
                        errors.append(
                            f"{where} runs `npm {sub}`, and `{wf}` job `{job_id}` builds it without running "
                            f"`{GATE}` itself or `needs:`-ing a job that does — so the image installs a lockfile "
                            f"no gate has scanned on that commit"
                        )


# --- 3. the one registry-asking install ----------------------------------------------------------------------
def triggers(doc: dict) -> dict:
    on = doc.get("on", doc.get(True))  # PyYAML reads a bare `on:` key as the boolean True (YAML 1.1)
    if isinstance(on, str):
        return {on: {}}
    if isinstance(on, list):
        return {event: {} for event in on}
    return {event: (spec or {}) for event, spec in (on or {}).items()}


def admits_lockfile(spec: dict) -> bool:
    if "paths" in spec:
        positive = [p for p in spec["paths"] if not p.startswith("!")]
        return any(fnmatch.fnmatch(LOCKFILE, p) for p in positive)
    ignored = [p for p in spec.get("paths-ignore") or [] if not p.startswith("!")]
    return not any(fnmatch.fnmatch(LOCKFILE, p) for p in ignored)


if workflows and len(asking) != 1:
    if not asking:
        errors.append(f"no gated install asks the registry — every `{GATE}` call passes `{SKIP_REGISTRY}`, so no job "
                      f"looks at the locked packages another platform installs (the arm64 image's bindings)")
    else:
        errors.append(f"{len(asking)} gated installs ask the registry, but its answer depends only on the lockfile "
                      f"— pass `{SKIP_REGISTRY}` on all but one: " + "; ".join(label for _, _, label in asking))
elif asking:
    asking_wf, asking_job, asking_label = asking[0]
    asking_on = triggers(workflows[asking_wf])
    for wf in sorted(w for w, jobs in gating_jobs.items() if jobs and w != asking_wf):
        for event, spec in triggers(workflows[wf]).items():
            if event not in ("push", "pull_request"):
                continue
            if event not in asking_on:
                errors.append(f"`{wf}` installs through the gate on `{event}`, but the one registry-asking install "
                              f"({asking_label}) never runs on `{event}` — those runs' lockfile goes unasked")
                continue
            mine = asking_on[event].get("branches")
            theirs = spec.get("branches")
            if mine is not None and (theirs is None or not set(theirs) <= set(mine)):
                errors.append(f"`{wf}` installs through the gate on `{event}` to branches {theirs or 'any'}, but the "
                              f"one registry-asking install ({asking_label}) only fires on {mine}")
    for event in ("push", "pull_request"):
        if event in asking_on and not admits_lockfile(asking_on[event]):
            errors.append(f"`{asking_wf}`'s `{event}` path filter excludes `{LOCKFILE}`, so the one registry-asking "
                          f"install ({asking_label}) does not run on the change that matters to it")
    for wf, doc in workflows.items():
        jobs = doc.get("jobs") or {}
        for job_id, job in jobs.items():
            if not isinstance(job, dict) or not any(rel in scalars(job) for rel in npm_images):
                continue
            if wf != asking_wf or asking_job not in needs_closure(jobs, job_id):
                errors.append(f"`{wf}` job `{job_id}` builds an image without `needs:`-ing the one registry-asking "
                              f"install ({asking_label}) — the locked packages that image installs and the gating "
                              f"platform skipped are then unasked on that commit")

if errors:
    print("NPM INSTALLS GATED VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(f"npm installs gated OK: {gate_calls} workflow step(s) install through {GATE} and none bypasses it; "
      f"{image_installs} Dockerfile `npm ci` install the scanned lockfile behind a gated job; the registry is asked "
      f"by exactly one ({asking[0][0]} job `{asking[0][1]}`), which covers the others")
