#!/usr/bin/env python3
"""Every package install a build of this repository runs is held to the deprecation gate — directly, or by a gated job
that installed the same lockfile first — and runs the one pinned pnpm.

**What it asserts.** The gate is `scripts/pnpm_install_no_deprecated.py` (root CLAUDE.md: no deprecated dependencies).
  0. **One pnpm.** The root `package.json` pins pnpm exactly in `packageManager` (`pnpm@<x.y.z>`), every other tracked
     `package.json` that names a `packageManager` names the same, and every place a build installs pnpm itself — the
     one npm install this repository runs, `npm install -g pnpm@<x.y.z>`, in a workflow step or a Dockerfile `RUN` —
     names that version. pnpm then installs the lockfile with the version that wrote it, and pnpm's own executables
     are verified against the integrity the lockfile's first document pins for them.
  1. **Workflows.** No `run:` step in a tracked `.github/workflows/*.y(a)ml` invokes pnpm's install family itself
     (`install`, `i`, `add`, `update`, `import`, `dedupe`, …) nor npm's (`ci`, `install`, every alias npm resolves to
     one of them) other than that pnpm bootstrap. An install there goes through the gate, which runs `pnpm install
     --frozen-lockfile` and is red on a deprecated package, so a bare one is an install the gate never sees. At least
     one step must call the gate — the positive control that this guard is anchored on the right name.
  2. **Dockerfiles.** Every tracked Dockerfile (found by name in the index, never listed) is read for its `RUN`
     instructions. One that runs a resolving install (`pnpm install` without `--frozen-lockfile`, `pnpm add`, any
     `npm` install but the bootstrap) is red: it does not install the lockfile, so no earlier scan of the lockfile
     speaks for it. One that runs `pnpm install --frozen-lockfile` is accepted **without** running the gate itself, on
     two premises this guard checks rather than trusts: (a) the stage `COPY`s the root `pnpm-lock.yaml` before that
     `RUN` — so it installs the file the gate scanned — and (b) no image built from it is published before the gate
     passed on the same commit: every workflow job that builds the Dockerfile (a job carrying its path as a value,
     e.g. a matrix `dockerfile:` entry) runs the gate itself or `needs:`, transitively, a job that does — or, when it
     starts beside the gate instead (ADR 0091 decision 2: the build does not wait for the tests), it has jobs that
     need it, the ones that turn its build into a published artifact, and every one of them needs a gating job. A
     Dockerfile with such an install that no workflow job builds is red too: nothing then covers its install.
  3. **Exactly one gated install per lockfile asks the registry** (inbox #312). The gate's registry half depends only
     on the lockfile, so every gated call of the same lockfile but one passes `--skip-registry-check`. Which lockfile a
     call installs is read from its `--package-dir <dir>` (`<dir>/pnpm-lock.yaml`; none is the root's). Red when none
     asks for a lockfile (nothing would look at what was deprecated after the lock was written) or more than one does
     (the same queries repeated per commit). The one that asks must cover the others of its lockfile: its workflow
     fires on every `push`/`pull_request` branch the other workflows gating that lockfile fire on (a missing
     `branches:` is every branch) and its path filter admits the lockfile's path; and for the root lockfile, every job
     that builds a Dockerfile `needs:` it, or is needed only by jobs that do (premise (b)'s rule).
  4. **Every tracked `pnpm-lock.yaml` is installed through the gate** by some workflow step — the root's and each
     package directory's outside the root workspace (`site/`, ADR 0087) — every `--package-dir` a step names holds a
     tracked lockfile, and no `package-lock.json` is tracked: the repository installs with pnpm, so one would be a
     second dependency tree nothing installs or scans.
Why the Dockerfiles are covered by (b) and not by calling the gate inside the image (inbox #294): the gate is
Python, so the image would need either a Python toolchain added to two build stages or a second copy of the gate's
logic in shell — both to scan a lockfile the gating job has already scanned at the same commit.

**The defect that bought it — no incident.** Built 2026-10-03 for inbox #293 and extended for #294, both left by
#232 (then for npm): the gate existed, all four workflow installs called it, and nothing would have noticed a fifth
that did not — the gate's own docstring said so as its limit. The two Dockerfiles' installs were, by the same
docstring, "ungated", which on reading turned out to be true of the image step and false of the lockfile: the image
workflow's `build` job `needs: test`, and `test` installs through the gate. That argument was prose; this makes it a
check. When the build stopped waiting for the tests (ADR 0091, 2026-10-16), the premise moved from "before the build
starts" to "before the build is published". Moved to pnpm 2026-11-03, when assertion 0 was added: the pnpm a build
installs is a second pin, and two Dockerfiles plus every workflow job naming it by hand drift without one.

**Its limits — what a green run does NOT prove.**
  - **Spellings it does not parse.** An install reached through `npx`, `pnpm dlx`, `yarn`, a shell variable or alias, a
    nested `sh -c '…'` string, a heredoc `RUN`, or a third-party `uses:` action that installs on its own is
    invisible; so is one in a script a step calls. Two tracked scripts run pnpm's install: the gate, and
    `run_web_e2e.py --upgrade`, which installs a PREDECESSOR's lockfile in a worktree of that older commit - one the
    gate passed when it was the candidate, deliberately not gated again (a package deprecated since would turn the
    upgrade proof red for a reason that is not the upgrade; its `prepare_predecessor` says so).
  - **Published, not built.** Premise (b)'s second form lets an image build install the lockfile before the gate's
    verdict, and a dispatch's build pushes its per-arch staging tags (`build-<sha>-<arch>`) in that window; what it
    holds is that no job that turns them into a release name runs without the gate. A registry write in the build
    job itself is not judged here — `validate_release_identity.py` holds every registry write to a dispatch.
  - **Declarations, not runs.** Premise (b) is the `needs:` graph as written: a gating step or job whose `if:` is
    an expression is counted as running (only a literal `false` is not), and a build job that checks out a
    different ref than the gating job is not detected. There is no remote, so no CI run has executed either.
  - **Trigger coverage (3) is compared by spelling.** Branch patterns are matched as exact strings (`release/**`
    on both sides, not glob containment), the path filter only against the one name `pnpm-lock.yaml` with
    `fnmatch` (close to, not identical with, GitHub's glob), and `workflow_dispatch` and other events are not
    compared. A skipping run on a commit that left the lockfile alone relies on the asking job's run at the
    commit that last changed it, so a package deprecated in between is caught by the asking workflow's next run.
  - **The pnpm pin (0) is a version, not a checksum.** The bootstrap trusts the registry's integrity for the pnpm
    package it fetches; what pins pnpm's bytes is the lockfile's `packageManagerDependencies` document, which pnpm
    reads when it switches to the pinned version — this guard does not check that document.
  - **What the gate itself cannot see** stays out of reach — its own docstring states that (registry-marked
    deprecation only, the pnpm tree only, install time only).

Buildless and diff-free, so it belongs in the trust-chain glob.
"""
from __future__ import annotations  # the repo's guards run on Python 3.9

import fnmatch
import json
import pathlib
import posixpath
import re
import shlex
import subprocess
import sys

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the other guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

import workflow_calls  # a job that calls a reusable workflow is read as the jobs it calls

ROOT = pathlib.Path(__file__).resolve().parent.parent
GATE = "scripts/pnpm_install_no_deprecated.py"
SKIP_REGISTRY = "--skip-registry-check"  # the gate's opt-out of its registry half (inbox #312)
PACKAGE_DIR = "--package-dir"  # the gate's option naming a package directory with its own lockfile (ADR 0087)
GATE_CALL = re.compile(r"(?:^|[\s;&|(])(?:\S*/)?python3?\s+" + re.escape(GATE) + r"(?:\s|$)", re.MULTILINE)
WORKFLOW_NAME = re.compile(r"^\.github/workflows/[^/]+\.ya?ml$")
DOCKERFILE_NAME = re.compile(r"(^|/)[^/]*dockerfile[^/]*$", re.IGNORECASE)
LOCKFILE = "pnpm-lock.yaml"
NPM_LOCKFILE = "package-lock.json"
PACKAGE_MANAGER = re.compile(r"^pnpm@(\d+\.\d+\.\d+)$")

# npm's install family, by what it installs from (npm's own alias table, `npm help install` / `npm help ci`).
NPM_INSTALLS = {"ci", "clean-install", "ic", "install-clean", "isntall-clean", "install-ci-test", "cit",
                "install", "i", "in", "ins", "inst", "insta", "instal", "isnt", "isnta", "isntal", "isntall", "add",
                "install-test", "it"}
NPM_VALUE_OPTIONS = {"--prefix", "-C", "--workspace", "-w", "--userconfig", "--cache", "--registry", "--loglevel"}
# pnpm's install family (`pnpm help`): what writes node_modules or the lockfile.
PNPM_INSTALLS = {"install", "i", "add", "update", "up", "upgrade", "import", "dedupe", "install-test", "it",
                 "fetch", "ci", "clean-install", "ic"}
PNPM_VALUE_OPTIONS = {"--dir", "-C", "--filter", "-F", "--loglevel", "--reporter", "--workspace-root-dir"}
# Only `install`/`i` with `--frozen-lockfile` installs the lockfile as it is.
PNPM_LOCKFILE_INSTALLS = {"install", "i"}

errors: list[str] = []


def tracked() -> list[str]:
    out = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True)
    if out.returncode != 0:
        errors.append("`git ls-files` failed, so this guard cannot enumerate the files it should read")
        return []
    return out.stdout.splitlines()


def tokenize(command: str) -> list[str]:
    lexer = shlex.shlex(command, posix=True, punctuation_chars=True)
    lexer.whitespace_split = True
    try:
        return list(lexer)
    except ValueError:  # an unbalanced quote: fall back to whitespace, which over- rather than under-reports
        return command.split()


def subcommand(tokens: list[str], i: int, value_options: set[str]) -> tuple[int, str]:
    """The index and name of the subcommand after the tool at `tokens[i]`, global options skipped."""
    j = i + 1
    while j < len(tokens) and tokens[j].startswith("-"):
        j += 2 if tokens[j] in value_options else 1
    return j, (tokens[j] if j < len(tokens) else "")


def command_end(tokens: list[str], start: int) -> int:
    end = start
    while end < len(tokens) and tokens[end] not in {"&&", "||", ";", "|", "&", "(", ")"}:
        end += 1
    return end


def installs(command: str) -> list[tuple[str, str, list[str]]]:
    """(tool, subcommand, its arguments) for each npm or pnpm install-family invocation on a shell command line."""
    tokens = tokenize(command)
    found: list[tuple[str, str, list[str]]] = []
    for i, token in enumerate(tokens):
        tool = token.rsplit("/", 1)[-1]
        if tool == "npm":
            j, sub = subcommand(tokens, i, NPM_VALUE_OPTIONS)
            if sub in NPM_INSTALLS:
                found.append(("npm", sub, tokens[i + 1:command_end(tokens, j)]))
        elif tool == "pnpm":
            j, sub = subcommand(tokens, i, PNPM_VALUE_OPTIONS)
            if sub in PNPM_INSTALLS:
                found.append(("pnpm", sub, tokens[i + 1:command_end(tokens, j)]))
    return found


def pnpm_bootstrap(tool: str, args: list[str]) -> str:
    """The pnpm version an `npm install -g pnpm@<version>` installs, or "" when the npm install is anything else."""
    if tool != "npm" or not ({"-g", "--global"} & set(args)):
        return ""
    targets = [a for a in args if not a.startswith("-") and a not in NPM_INSTALLS]
    if len(targets) != 1 or not targets[0].startswith("pnpm@"):
        return ""
    return targets[0][len("pnpm@"):] or "?"


def frozen(args: list[str]) -> bool:
    return "--frozen-lockfile" in args or "--frozen-lockfile=true" in args


def tokens_of(command: str) -> list[str]:
    try:
        return shlex.split(command)
    except ValueError:  # an unbalanced quote: fall back to whitespace
        return command.split()


def gated_lockfile(line: str) -> str:
    """The lockfile a gate call on this line installs: `<dir>/pnpm-lock.yaml` for `--package-dir <dir>`, the root's
    otherwise. The gate refuses the `--package-dir=<dir>` spelling, so a step written that way is red in CI."""
    tokens = tokens_of(line)
    start = next((i for i, t in enumerate(tokens) if t == GATE or t.endswith("/" + GATE)), len(tokens))
    for i in range(start + 1, len(tokens) - 1):
        if tokens[i] == PACKAGE_DIR:
            return posixpath.normpath(posixpath.join(tokens[i + 1], LOCKFILE))
    return LOCKFILE


def literally_off(node: dict) -> bool:
    return node.get("if") is False or str(node.get("if", "")).strip() in {"false", "${{ false }}"}


def scalars(node) -> list[str]:
    if isinstance(node, dict):
        return [s for v in node.values() for s in scalars(v)]
    if isinstance(node, list):
        return [s for v in node for s in scalars(v)]
    return [node] if isinstance(node, str) else []


files = tracked()

# --- 0. one pnpm --------------------------------------------------------------------------------------------
def package_manager(rel: str) -> str | None:
    try:
        return json.loads((ROOT / rel).read_text()).get("packageManager")
    except (OSError, ValueError) as e:
        errors.append(f"{rel} does not load ({e}) — its packageManager could not be checked")
        return None


pinned = ""
root_pm = package_manager("package.json") if "package.json" in files else None
m = PACKAGE_MANAGER.match(root_pm or "")
if m:
    pinned = m.group(1)
else:
    errors.append(f"the root package.json pins no exact pnpm in `packageManager` (found {root_pm!r}; want "
                  f"`pnpm@<x.y.z>`) — every install would run whichever pnpm the machine has")
for rel in sorted(f for f in files if posixpath.basename(f) == "package.json" and f != "package.json"):
    pm = package_manager(rel)
    if pm is not None and pm != root_pm:
        errors.append(f"{rel} pins `packageManager: {pm}`, the root pins `{root_pm}` — one pnpm installs every lockfile")


def check_bootstrap(where: str, tool: str, args: list[str]) -> bool:
    """True when this npm install is the pnpm bootstrap (red here if its version is not the pinned one)."""
    version = pnpm_bootstrap(tool, args)
    if not version:
        return False
    if version != pinned:
        errors.append(f"{where} installs `pnpm@{version}`, but the root package.json pins `pnpm@{pinned}` in "
                      f"`packageManager` — install the pinned one")
    return True


# --- 1. workflows -------------------------------------------------------------------------------------------
workflows: dict[str, dict] = {}
for rel in sorted(f for f in files if WORKFLOW_NAME.match(f)):
    try:
        workflows[rel] = yaml.safe_load((ROOT / rel).read_text()) or {}
    except (OSError, yaml.YAMLError) as e:
        errors.append(f"{rel} does not load ({e}) — its installs could not be checked")
if not workflows:
    errors.append("no tracked .github/workflows/*.yml was found — this guard checked nothing")

# What GitHub runs, per workflow that a change or a person starts: a job that calls a reusable workflow is the jobs
# it calls (workflow_calls.py, the one reading of a call), so the pipeline's install and image build are judged in the
# run of each caller (`checks.yml`, `publish.yml`), with qualified ids and `needs`. A called workflow nobody calls
# is read on its own, so no step goes unread. Each job keeps its SITE — the file and job id that define it — so a
# step two callers run is one install, not two.
SITE = "__site__"
runs: dict[str, dict] = {}
called_files: set[str] = set()
for rel, doc in workflows.items():
    if workflow_calls.triggers(doc) <= {"workflow_call"}:
        continue
    try:
        expanded = workflow_calls.expand(doc, file=rel)
    except workflow_calls.CallError as e:
        errors.append(f"{rel}: {e} — its installs could not be checked")
        continue
    runs[rel] = {}
    for ej in expanded:
        if ej.call:
            called_files.add(ej.file)
        if isinstance(ej.job, dict):
            runs[rel][ej.id] = {**ej.job, "needs": list(ej.needs), SITE: (ej.file, ej.local)}
for rel, doc in workflows.items():
    if rel not in runs and rel not in called_files:
        runs[rel] = {job_id: {**job, SITE: (rel, job_id)} for job_id, job in (doc.get("jobs") or {}).items()
                     if isinstance(job, dict)}

# lockfile -> run (the starting workflow) -> jobs with a step that installs that lockfile through the gate
gating: dict[str, dict[str, set[str]]] = {}
# lockfile -> (site, step label) of each gate call of that lockfile that asks the registry, once per defining step
asking: dict[str, list[tuple[tuple, str]]] = {}
gate_calls = 0
bootstraps = 0
seen_steps: set[tuple] = set()
for rel, jobs_of_run in runs.items():
    for job_id, job in jobs_of_run.items():
        site_file, site_job = job[SITE]
        for index, step in enumerate(job.get("steps") or []):
            if not isinstance(step, dict) or not isinstance(step.get("run"), str):
                continue
            step_site = (site_file, site_job, index)
            first = step_site not in seen_steps
            seen_steps.add(step_site)
            run = step["run"].replace("\\\n", " ")
            label = f"{site_file}: job `{site_job}`, step {index + 1} ({step.get('name', 'unnamed')!r})"
            for line in (run.splitlines() if first else []):
                for tool, sub, args in installs(line):
                    if check_bootstrap(label, tool, args):
                        bootstraps += 1
                        continue
                    errors.append(
                        f"{label} runs `{tool} {sub}` directly — install through `python3 {GATE}` (it runs `pnpm "
                        f"install --frozen-lockfile` and is red on a deprecated package), or this install is one the "
                        f"gate never sees."
                    )
            if GATE_CALL.search(run):
                gate_calls += first
                if not literally_off(step) and not literally_off(job):
                    for line in run.splitlines():
                        if not GATE_CALL.search(line):
                            continue
                        lockfile = gated_lockfile(line)
                        gating.setdefault(lockfile, {}).setdefault(rel, set()).add(job_id)
                        asking.setdefault(lockfile, [])
                        if first and SKIP_REGISTRY not in tokens_of(line):
                            asking[lockfile].append(((site_file, site_job), label))
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


def gated_before_published(jobs: dict, builder: str, wanted: set[str]) -> bool:
    """Whether a job of `wanted` has passed before the image `builder` builds can be published: the builder needs one
    itself, or it has dependants — the jobs that turn its build into a published artifact — and every one does."""
    if needs_closure(jobs, builder) & wanted:
        return True
    after = [j for j in jobs if j != builder and builder in needs_closure(jobs, j)]
    return bool(after) and all(needs_closure(jobs, j) & wanted for j in after)


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
pnpm_images: set[str] = set()  # Dockerfiles that run `pnpm install --frozen-lockfile`
for rel in sorted(dockerfiles):
    lock_copied = False
    for number, keyword, args in instructions((ROOT / rel).read_text()):
        if keyword == "FROM":
            lock_copied = False  # a new stage starts with none of the previous stage's files
        elif keyword in {"COPY", "ADD"} and "--from" not in args:
            sources = [t for t in args.split() if not t.startswith("--")][:-1]
            lock_copied = lock_copied or LOCKFILE in sources
        elif keyword == "RUN":
            for tool, sub, sub_args in installs(run_command(args)):
                where = f"{rel}:{number}"
                if check_bootstrap(where, tool, sub_args):
                    bootstraps += 1
                    continue
                if tool != "pnpm" or sub not in PNPM_LOCKFILE_INSTALLS or not frozen(sub_args):
                    errors.append(f"{where} runs `{tool} {sub}`{'' if tool == 'npm' else ' without --frozen-lockfile'}, "
                                  f"which does not install the lockfile as it is — no gate's scan of the lockfile covers "
                                  f"it; use `pnpm install --frozen-lockfile`")
                    continue
                image_installs += 1
                pnpm_images.add(rel)
                if not lock_copied:
                    errors.append(f"{where} runs `pnpm {sub}` in a stage that has not copied the root `{LOCKFILE}` "
                                  f"before it — so it is not the lockfile the gate scanned")
                builders = [(wf, job_id) for wf, jobs_of_run in runs.items()
                            for job_id, job in jobs_of_run.items() if rel in scalars(job)]
                if not builders:
                    errors.append(f"{where} runs `pnpm {sub}`, but no workflow job builds `{rel}` — so no gated job "
                                  f"runs before it and nothing covers this install")
                for wf, job_id in builders:
                    jobs = runs[wf]
                    if not gated_before_published(jobs, job_id, gating.get(LOCKFILE, {}).get(wf, set())):
                        errors.append(
                            f"{where} runs `pnpm {sub}`, and `{wf}` job `{job_id}` builds it without running "
                            f"`{GATE}` itself or `needs:`-ing a job that does, and is needed by no job that "
                            f"publishes only after one does — so an image of a lockfile no gate has scanned on that "
                            f"commit can be published"
                        )


# --- 3. the one registry-asking install ----------------------------------------------------------------------
def triggers(doc: dict) -> dict:
    on = doc.get("on", doc.get(True))  # PyYAML reads a bare `on:` key as the boolean True (YAML 1.1)
    if isinstance(on, str):
        return {on: {}}
    if isinstance(on, list):
        return {event: {} for event in on}
    return {event: (spec or {}) for event, spec in (on or {}).items()}


def admits_lockfile(spec: dict, lockfile: str) -> bool:
    if "paths" in spec:
        positive = [p for p in spec["paths"] if not p.startswith("!")]
        return any(fnmatch.fnmatch(lockfile, p) for p in positive)
    ignored = [p for p in spec.get("paths-ignore") or [] if not p.startswith("!")]
    return not any(fnmatch.fnmatch(lockfile, p) for p in ignored)


for lockfile in sorted(asking):
    calls = asking[lockfile]
    if len(calls) != 1:
        if not calls:
            errors.append(f"no gated install of `{lockfile}` asks the registry — every `{GATE}` call of it passes "
                          f"`{SKIP_REGISTRY}`, so no job looks at what was deprecated after the lock was written")
        else:
            errors.append(f"{len(calls)} gated installs of `{lockfile}` ask the registry, but its answer depends only "
                          f"on that lockfile — pass `{SKIP_REGISTRY}` on all but one: "
                          + "; ".join(label for _, label in calls))
        continue
    asking_site, asking_label = calls[0]
    # The runs that carry the asking step — one workflow, or every caller of the pipeline that holds it — and what
    # starts them, together.
    asking_ids = {wf: {j for j, job in jobs.items() if job[SITE] == asking_site} for wf, jobs in runs.items()}
    asking_wfs = sorted(wf for wf, ids in asking_ids.items() if ids)
    asking_on: dict = {}
    for wf in asking_wfs:
        for event, spec in triggers(workflows[wf]).items():
            asking_on.setdefault(event, spec)
    for wf in sorted(w for w, jobs in gating[lockfile].items() if jobs and w not in asking_wfs):
        for event, spec in triggers(workflows[wf]).items():
            if event not in ("push", "pull_request"):
                continue
            if event not in asking_on:
                errors.append(f"`{wf}` installs `{lockfile}` through the gate on `{event}`, but the one "
                              f"registry-asking install of it ({asking_label}) never runs on `{event}` — those runs' "
                              f"lockfile goes unasked")
                continue
            mine = asking_on[event].get("branches")
            theirs = spec.get("branches")
            if mine is not None and (theirs is None or not set(theirs) <= set(mine)):
                errors.append(f"`{wf}` installs `{lockfile}` through the gate on `{event}` to branches "
                              f"{theirs or 'any'}, but the one registry-asking install of it ({asking_label}) only "
                              f"fires on {mine}")
    for event in ("push", "pull_request"):
        if event in asking_on and not admits_lockfile(asking_on[event], lockfile):
            errors.append(f"`{', '.join(asking_wfs)}`'s `{event}` path filter excludes `{lockfile}`, so the one registry-asking "
                          f"install of it ({asking_label}) does not run on the change that matters to it")
    if lockfile != LOCKFILE:
        continue  # only the root lockfile is installed by an image (section 2)
    for wf, jobs in runs.items():
        for job_id, job in jobs.items():
            if not any(rel in scalars(job) for rel in pnpm_images):
                continue
            if not asking_ids.get(wf) or not gated_before_published(jobs, job_id, asking_ids[wf]):
                errors.append(f"`{wf}` job `{job_id}` builds an image, and neither it nor every job that publishes "
                              f"its build `needs:` the one registry-asking install ({asking_label}) — the locked "
                              f"packages that image installs are then unasked before it ships")


# --- 4. every tracked lockfile is installed through the gate -------------------------------------------------
lockfiles = {f for f in files if posixpath.basename(f) == LOCKFILE}
if workflows and LOCKFILE not in lockfiles:
    errors.append(f"the root `{LOCKFILE}` is not tracked — this guard reads tracked files, so it is anchored wrong")
for lockfile in sorted(lockfiles - set(gating)):
    errors.append(f"`{lockfile}` is tracked, but no workflow step installs it through `{GATE}` "
                  f"(`{PACKAGE_DIR} {posixpath.dirname(lockfile) or '.'}`) — a dependency tree the gate never sees")
for lockfile in sorted(set(gating) - lockfiles):
    errors.append(f"a workflow step installs `{lockfile}` through `{GATE}`, but no such lockfile is tracked — the "
                  f"`{PACKAGE_DIR}` it names installs nothing the repository pins")
for stray in sorted(f for f in files if posixpath.basename(f) == NPM_LOCKFILE):
    errors.append(f"`{stray}` is tracked, but the repository installs with pnpm — a second dependency tree nothing "
                  f"installs or scans; delete it")

if errors:
    print("PNPM INSTALLS GATED VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

askers = "; ".join(f"{lockfile} by {calls[0][1]}" for lockfile, calls in sorted(asking.items()))
print(f"pnpm installs gated OK: pnpm@{pinned} pinned, and the {bootstraps} place(s) that install pnpm install it; "
      f"{gate_calls} workflow step(s) install through {GATE} and none bypasses it; {image_installs} Dockerfile "
      f"`pnpm install --frozen-lockfile` install the scanned lockfile, published only behind a gated job; every tracked "
      f"lockfile is installed through the gate, and each is asked of the registry by exactly one install ({askers}), "
      f"which covers the others")
