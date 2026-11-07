#!/usr/bin/env python3
"""Read a workflow the way GitHub runs it: a job that CALLS a reusable workflow is the jobs of that workflow.

**Why one module.** veyq's pipeline is one reusable workflow (`.github/workflows/pipeline.yml`, `on: workflow_call`)
called by two workflows, Checks and Publish (ADR 0091 decision 2). A reader that looks at a caller's `jobs:` sees one
job with `uses:` and no steps, and a reader that looks at the called file sees jobs with no trigger. Four readers need
the jobs as a run has them — `validate_coverage.py` (which Gradle runs a push or a pull request makes),
`validate_pnpm_installs_gated.py` (which install a workflow's image builds wait for), `validate_release_identity.py`
(what a publishing run writes, and with which permission) and `run_pipeline.py` (what to run locally). Each writing its
own expansion would be four readings of one GitHub rule, and they would not stay one. So this module is the rule.

**What `expand()` gives** for a caller workflow: every job of the run, in file order, a call job replaced by the called
workflow's jobs. Each carries
  - `id`: the caller's own job id, or `<call job>/<called job>` for a called one (a `/` is no job id character, so
    the two never collide);
  - `file`: the workflow file that defines it, `inputs`: the called workflow's inputs (its declared defaults, then
    the call job's `with:`), `env`: the workflow-level `env` of the file that defines it (a caller's workflow-level
    `env` is not propagated into the called workflow — GitHub's docs, "Reusing workflows");
  - `permissions`: the token the job gets. A job's own block, else its workflow's top-level one; for a called job
    with neither, the call job's grant (else the caller's top-level). A called job or workflow that asks for more
    than the call job grants is a `CallError` — GitHub: the caller's permissions "can be only downgraded (not
    elevated) by the called workflow";
  - `needs`: qualified ids. A called job's own needs are qualified; a called job with none waits for what the call
    job needs; a caller job that needs a call job waits for every called job. `needs_names` maps each qualified
    need to the name the job's expressions use for it (`needs.version…` inside the called file).
`calls()` gives, per call job, its file, its called job ids and `on.workflow_call.outputs` (name → value expression),
which is what a caller job's `needs.<call job>.outputs.<name>` reads.

**What it refuses** (`CallError`): a call to another repository's workflow (`owner/repo/…@ref` — veyq calls only
its own, read from the same commit), a call inside a called workflow (one level is all veyq uses; deeper would need
this module to grow, not to guess), a called file without `on.workflow_call`, a `with:` key the called workflow
does not declare, a required input left out.

**Its limits.** It reads YAML; it evaluates no expression — a call job's `if:` is handed on as `call_if`, an input
given as `${{ … }}` stays that text. It does not know the repository's default token permissions: where neither
the call job nor the caller says, there is no ceiling to check against, and none is checked. Matrices are not
expanded (each reader does that). The selftest, `workflow_calls_selftest.py`, holds every rule above.
"""
from __future__ import annotations  # `X | None` in annotations — scripts run on the host's Python 3.9

import pathlib
from typing import Callable, NamedTuple

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
LOCAL_PREFIX = "./"
LEVEL = {"none": 0, "read": 1, "write": 2}


class CallError(Exception):
    """A call this module cannot read the way GitHub would run it, or one GitHub refuses."""


class Job(NamedTuple):
    id: str                   # caller job id, or "<call job>/<called job>"
    local: str                # the id inside the file that defines it
    job: dict                 # the job's YAML
    file: str                 # the workflow file defining it, repo-relative
    call: str | None          # the call job it came through, or None
    inputs: dict              # the called workflow's inputs (defaults, then `with:`); {} for a caller job
    env: dict                 # the defining file's workflow-level `env`
    permissions: dict | None  # scope -> "read" | "write" | "none"; None: the repository's default
    needs: tuple              # qualified ids
    needs_names: dict         # qualified need -> the name the job's expressions use (None: not visible to them)
    call_if: object           # the call job's `if:`, or None


def read_disk(rel: str) -> str:
    return (ROOT / rel).read_text()


def parse(text: str, where: str = "") -> dict:
    data = yaml.safe_load(text) or {}
    if not isinstance(data, dict):
        raise CallError(f"{where}: not a workflow (top level is {type(data).__name__})")
    return data


def triggers(data: dict) -> set:
    """The workflow's events. YAML 1.1 reads a bare `on:` key as the boolean True."""
    on = data.get("on", data.get(True))
    if isinstance(on, str):
        return {on}
    if isinstance(on, list):
        return set(on)
    return set(on or ())


def is_call(job) -> bool:
    return isinstance(job, dict) and isinstance(job.get("uses"), str)


def needs_list(job: dict) -> list:
    n = job.get("needs") or []
    return [n] if isinstance(n, str) else list(n)


def local_target(uses: str, where: str) -> str:
    """`./.github/workflows/x.yml` → `.github/workflows/x.yml`; any other call is refused."""
    if not uses.startswith(LOCAL_PREFIX) or "@" in uses:
        raise CallError(f"{where}: calls `{uses}` — only a workflow of this repository, by `./.github/workflows/…`, "
                        f"is read (from the caller's own commit)")
    rel = uses[len(LOCAL_PREFIX):]
    if not rel.startswith(".github/workflows/") or not rel.endswith((".yml", ".yaml")):
        raise CallError(f"{where}: calls `{uses}`, which is not a workflow file under .github/workflows/")
    return rel


def permission_map(value, where: str) -> dict | None:
    """A `permissions:` value as scope -> level. `read-all`/`write-all` become the `*` scope; `{}` is all none."""
    if value is None:
        return None
    if isinstance(value, str):
        if value in ("read-all", "write-all"):
            return {"*": value.split("-")[0]}
        raise CallError(f"{where}: permissions `{value}` is neither a map nor read-all/write-all")
    if not isinstance(value, dict):
        raise CallError(f"{where}: permissions is a {type(value).__name__}")
    out = {}
    for scope, level in value.items():
        if level not in LEVEL:
            raise CallError(f"{where}: permission {scope}: {level!r} is not read, write or none")
        out[str(scope)] = level
    return out


def level_of(perms: dict, scope: str) -> int:
    return LEVEL[perms.get(scope, perms.get("*", "none"))]


def exceeding(request: dict, grant: dict) -> list:
    """The scopes `request` asks for above `grant` (each scope, and `*`, compared as GitHub ranks them)."""
    scopes = (set(request) | set(grant)) - {"*"}
    over = [f"{s}: {request.get(s, request.get('*', 'none'))} (granted {grant.get(s, grant.get('*', 'none'))})"
            for s in sorted(scopes) if level_of(request, s) > level_of(grant, s)]
    if "*" in request and level_of(request, "*") > level_of(grant, "*"):
        over.append(f"*: {request['*']} (granted {grant.get('*', 'none')})")
    return over


def _inputs(called: dict, call_job: dict, where: str) -> dict:
    on = called.get("on", called.get(True))
    spec = (on.get("workflow_call") if isinstance(on, dict) else None) or {}
    declared = spec.get("inputs") or {}
    given = call_job.get("with") or {}
    unknown = sorted(set(given) - set(declared))
    if unknown:
        raise CallError(f"{where}: `with:` gives {unknown}, which the called workflow does not declare")
    out = {}
    for name, decl in declared.items():
        decl = decl or {}
        if name in given:
            out[name] = given[name]
        elif "default" in decl:
            out[name] = decl["default"]
        elif decl.get("required"):
            raise CallError(f"{where}: the required input `{name}` is not given")
        else:
            out[name] = {"boolean": False, "number": 0}.get(decl.get("type"), "")
    return out


def _call_outputs(called: dict) -> dict:
    on = called.get("on", called.get(True))
    spec = (on.get("workflow_call") if isinstance(on, dict) else None) or {}
    return {name: (o or {}).get("value") for name, o in (spec.get("outputs") or {}).items()}


def _expand(data: dict, read: Callable[[str], str], file: str) -> tuple[list, dict]:
    jobs = data.get("jobs") or {}
    if not isinstance(jobs, dict):
        raise CallError(f"{file}: `jobs` is not a map")
    top = permission_map(data.get("permissions"), f"{file}: permissions")
    env = data.get("env") or {}
    calls: dict = {}
    for call_id, call_job in jobs.items():
        if not is_call(call_job):
            continue
        where = f"{file} › {call_id}"
        rel = local_target(call_job["uses"], where)
        called = parse(read(rel), rel)
        if "workflow_call" not in triggers(called):
            raise CallError(f"{where}: calls {rel}, which has no `on.workflow_call`")
        nested = called.get("jobs") or {}
        if not isinstance(nested, dict) or not nested:
            raise CallError(f"{where}: calls {rel}, which has no jobs")
        for nid, njob in nested.items():
            if is_call(njob):
                raise CallError(f"{rel} › {nid}: calls `{njob['uses']}` — a call inside a called workflow is not "
                                f"read here (one level is all veyq uses)")
        calls[call_id] = {"file": rel, "data": called, "job": call_job,
                          "jobs": [f"{call_id}/{nid}" for nid in nested], "outputs": _call_outputs(called),
                          "inputs": _inputs(called, call_job, where)}

    def qualify(names: list) -> tuple[list, dict]:
        out, named = [], {}
        for n in names:
            if n in calls:
                for q in calls[n]["jobs"]:
                    if q not in named:
                        out.append(q)
                        named[q] = None
            elif n not in named:
                out.append(n)
                named[n] = n
        return out, named

    result = []
    for jid, job in jobs.items():
        if not isinstance(job, dict):
            raise CallError(f"{file} › {jid}: a job is not a map")
        if jid not in calls:
            needs, names = qualify(needs_list(job))
            perms = permission_map(job.get("permissions"), f"{file} › {jid}") if "permissions" in job else top
            result.append(Job(jid, jid, job, file, None, {}, env, perms, tuple(needs), names, None))
            continue
        call = calls[jid]
        rel, called = call["file"], call["data"]
        grant = permission_map(job.get("permissions"), f"{file} › {jid}") if "permissions" in job else top
        called_top = permission_map(called.get("permissions"), f"{rel}: permissions")
        if called_top is not None and grant is not None and exceeding(called_top, grant):
            raise CallError(f"{rel}: its top-level permissions ask for more than {file} › {jid} grants: "
                            f"{', '.join(exceeding(called_top, grant))}")
        outer_needs, _ = qualify(needs_list(job))
        for nid, njob in called["jobs"].items():
            where = f"{rel} › {nid}"
            if "permissions" in njob:
                perms = permission_map(njob.get("permissions"), where)
                if grant is not None and exceeding(perms, grant):
                    raise CallError(f"{where} asks for more than {file} › {jid} grants: "
                                    f"{', '.join(exceeding(perms, grant))}")
            else:
                perms = called_top if called_top is not None else grant
            own = needs_list(njob)
            needs = [f"{jid}/{n}" for n in own]
            names = {f"{jid}/{n}": n for n in own}
            if not own:
                for q in outer_needs:
                    needs.append(q)
                    names[q] = None
            result.append(Job(f"{jid}/{nid}", nid, njob, rel, jid, call["inputs"], called.get("env") or {}, perms,
                              tuple(needs), names, job.get("if")))
    return result, calls


def expand(data: dict, read: Callable[[str], str] = read_disk, file: str = "") -> list:
    """Every job of a run of the workflow `data` (read from `file`), call jobs replaced by the jobs they call."""
    return _expand(data, read, file)[0]


def calls(data: dict, read: Callable[[str], str] = read_disk, file: str = "") -> dict:
    """call job id -> {"file", "jobs" (qualified ids), "outputs" (name -> value expression), "inputs", "job"}."""
    return {k: {kk: vv for kk, vv in v.items() if kk != "data"} for k, v in _expand(data, read, file)[1].items()}


def load(rel: str, read: Callable[[str], str] = read_disk) -> dict:
    return parse(read(rel), rel)
