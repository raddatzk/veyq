#!/usr/bin/env python3
"""Run the pipeline locally, from `.github/workflows/ci.yml` itself (ADR 0091; cr-one-pipeline-per-commit phase 5).

**Why.** The repository has no remote, and will have none until the owner judges veyq ready to publish (ADR 0091
§ Consequences): until then the local run is the only run. A local run assembled by hand from memory is a second list
of stages, and a second list drifts from the one CI runs — so this reads the workflow and runs what it says. **No list
of stages lives here: the workflow is the list.** `nektos/act` was considered and is not used: it adds a runtime that
re-implements GitHub's runner, and its arm64, cache and registry gaps would surface as its failures rather than ours.

**What it does.** It reads the workflow from the commit it runs (`--rev`, default `HEAD`; uncommitted edits are not
in the run, as they are in no CI run), expands each job's matrix and keeps the legs of this host's architecture,
resolves `needs`, and runs every job the host can run — independent jobs in parallel (`--max-parallel`), each in
its own clone of the commit (as each CI job has its own checkout: two jobs' `npm ci` or Gradle builds in one tree
would trample each other), each under its `timeout-minutes`. It runs the `run:` steps with the host's own Java,
Node, Python and Docker, evaluating the GitHub expressions in them (`github`, `runner`, `env`, `matrix`, `needs`,
`steps`, `inputs`; the status functions) against a simulated `push` (or `--event pull_request`) to the current
branch. `GITHUB_OUTPUT`, `GITHUB_ENV`, `GITHUB_PATH` and `RUNNER_TEMP` behave as on a runner, and `CI=true` is set.

**A `uses:` step is not re-implemented.** Four are things the host already provides — `actions/checkout` (the
job's clone is its checkout), `actions/setup-java` (the host's JDK of that major, by `java_home` on macOS),
`actions/setup-node` (the host's Node), `docker/setup-buildx-action` (the host's Buildx) — and two only keep or
restore files on GitHub (`actions/upload-artifact`, `actions/cache`); those count as done. Every other `uses:` step
is **not run**, with its reason (a registry write, keyless signing, Buildx's action, a check of the wrapper JAR
against Gradle's checksums …), and so is a `run:` step whose expressions need what only GitHub has (`secrets.*`).
Where a stage's work sits in such a step, the workflow itself carries the host's stand-in, a `run:` step guarded by
`if: runner.environment != 'github-hosted'` (GitHub's own context: its hosted runners say `github-hosted`, this
runner says `local`) — the image build with the host's `docker build`, the chart job's check that the host's Helm is
the pinned version. So what the local run does is read in the workflow, beside what CI does, not in this file.

**What it prints, per job:** `green` (every step ran and passed), `green, partial` (passed, with the steps it
could not run named), `red` (a step failed, or the job outran its budget), `skipped` (the workflow's own `if:` is
false for this event, as it would be in CI), or `not run` with the reason — a job made only of steps it cannot
run, a leg of another architecture, a job excluded by `--job`/`--skip-job`, or a job that needs one that did not
run. **A job it did not run is never reported green.** Exit code: 1 on any red; 3 when nothing is red but something
did not run (the pipeline is then *not* green); 0 only when every job ran green or was skipped by the workflow.
`--dry-run` prints that plan without running anything. Each job's log, and `summary.json`, land in
`.pipeline-out/<run>/` (gitignored); the clones are removed afterwards (`--keep` keeps them).

**Sharing the host's one compose stack.** `scripts/run_web_e2e.py` brings up `infra/docker-compose.yml`, whose
compose project is named after its directory and whose containers have fixed names. From a clone, that is the same
project as the developer's dev stack with every bind mount pointing into a directory about to be deleted, so it
would recreate the developer's containers onto it. Every job therefore gets `VEYQ_INFRA_DIR` = the main checkout's
`infra/` (unless the caller set it), the procedure that script documents for a worktree. On a host already running
a Docker-heavy e2e or stack run, leave the Docker jobs out (`--skip-job web-e2e …`) and say so: they are reported
`not run`, never green.

**Its limits — what a green local run does NOT prove.** That the run on GitHub is green: the runner image, the
amd64 legs on an arm64 host (and the reverse), the `uses:` steps above, the path filters and `paths-ignore` (every
job runs whatever changed), concurrency groups and secrets are GitHub's. The host's tool versions are its own: a
Node or Java major other than the workflow's is run and named in the job's notes, not refused. A step's
`shell:` other than bash, sh or python is not run. Budgets are wall time on this machine, under whatever load it
carries — the summary prints the load average, and a job over budget under a load of 90 says more about the load
than about the job. The expression evaluator covers what the repository's workflows use; an expression it cannot
read makes that step or job not run, never green.

Usage (from anywhere; paths resolve against this checkout):

    python3 scripts/run_pipeline.py                         # every job of ci.yml, the host's legs
    python3 scripts/run_pipeline.py --dry-run               # the plan: what would run, what would not and why
    python3 scripts/run_pipeline.py --job frontend --job chart          # these jobs (and what they need)
    python3 scripts/run_pipeline.py --skip-job web-e2e --skip-job build # leave jobs out; they are listed as not run
    python3 scripts/run_pipeline.py --workflow path/to/forged.yml       # another workflow file (bite tests)
"""
from __future__ import annotations  # `X | None` in annotations — scripts run on the host's Python 3.9

import argparse
import concurrent.futures
import datetime
import json
import math
import os
import pathlib
import platform
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_WORKFLOW = ".github/workflows/ci.yml"
OUT = ROOT / ".pipeline-out"
DEFAULT_PARALLEL = 4

# Actions whose effect the host provides, or that only move files to or from GitHub. Everything else is not run.
HOST_ACTIONS = {
    "actions/checkout": "the job's clone of the commit is its checkout",
    "actions/setup-java": "the host's JDK",
    "actions/setup-node": "the host's Node",
    "docker/setup-buildx-action": "the host's Docker Buildx",
    "actions/upload-artifact": "keeps files on GitHub; the local run keeps its logs under .pipeline-out/",
    "actions/cache": "restores a GitHub cache; the host's own caches stand in",
}
WHY_NOT = {
    "docker/login-action": "a registry login — a local run writes to no registry",
    "docker/build-push-action": "Buildx's GitHub action (its cache, provenance, SBOM and push)",
    "docker/metadata-action": "image metadata for a registry push",
    "sigstore/cosign-installer": "keyless signing, which needs GitHub's OIDC token",
    "gradle/actions/wrapper-validation": "checks the wrapper JAR against Gradle's published checksums",
    "dorny/paths-filter": "reads the push's diff from GitHub",
    "github/codeql-action": "CodeQL's analysis on GitHub",
    "actions/upload-pages-artifact": "GitHub Pages",
    "actions/deploy-pages": "GitHub Pages",
}
STATUS_FUNCS = re.compile(r"\b(success|failure|always|cancelled)\s*\(")
EXPR = re.compile(r"\$\{\{(.*?)\}\}", re.S)

_print_lock = threading.Lock()
_procs: set = set()
_procs_lock = threading.Lock()
_stop = threading.Event()


def say(message: str) -> None:
    with _print_lock:
        print(message, flush=True)


# --- expressions -----------------------------------------------------------------------------------------------


class Unknown:
    """A value only GitHub has (a secret) or a job that did not run here: three-valued, never truthy, never falsy."""

    def __init__(self, why: str):
        self.why = why

    def __repr__(self) -> str:
        return f"Unknown({self.why})"


class ExprError(Exception):
    pass


TOKEN = re.compile(r"""\s*(?:(?P<num>0x[0-9a-fA-F]+|-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?)|'(?P<str>(?:[^']|'')*)'|"""
                   r"""(?P<op>==|!=|<=|>=|&&|\|\||[<>!().,\[\]*])|(?P<id>[A-Za-z_][A-Za-z0-9_-]*))""")


def tokenize(text: str) -> list:
    out, pos = [], 0
    text = text.strip()
    while pos < len(text):
        m = TOKEN.match(text, pos)
        if not m or m.end() == pos:
            raise ExprError(f"cannot read `{text[pos:pos + 20]}`")
        pos = m.end()
        if m.group("num") is not None:
            raw = m.group("num")
            out.append(("lit", int(raw, 16) if raw.lower().startswith("0x") else float(raw)))
        elif m.group("str") is not None:
            out.append(("lit", m.group("str").replace("''", "'")))
        elif m.group("op"):
            out.append(("op", m.group("op")))
        else:
            word = m.group("id")
            out.append(("lit", {"true": True, "false": False, "null": None}[word])
                       if word in ("true", "false", "null") else ("id", word))
    return out


class Parser:
    def __init__(self, tokens: list):
        self.t, self.i = tokens, 0

    def peek(self, value: str | None = None):
        tok = self.t[self.i] if self.i < len(self.t) else (None, None)
        return tok if value is None or tok == ("op", value) else None

    def take(self, value: str):
        if self.peek(value) is None:
            raise ExprError(f"expected `{value}`")
        self.i += 1

    def parse(self):
        node = self.or_()
        if self.i != len(self.t):
            raise ExprError(f"unexpected `{self.t[self.i][1]}`")
        return node

    def binary(self, ops: tuple, nxt):
        node = nxt()
        while self.i < len(self.t) and self.t[self.i][0] == "op" and self.t[self.i][1] in ops:
            op = self.t[self.i][1]
            self.i += 1
            node = ("bin", op, node, nxt())
        return node

    def or_(self):
        return self.binary(("||",), self.and_)

    def and_(self):
        return self.binary(("&&",), self.eq)

    def eq(self):
        return self.binary(("==", "!="), self.cmp)

    def cmp(self):
        return self.binary(("<", "<=", ">", ">="), self.unary)

    def unary(self):
        if self.peek("!"):
            self.i += 1
            return ("not", self.unary())
        return self.postfix()

    def postfix(self):
        node = self.primary()
        while True:
            if self.peek("."):
                self.i += 1
                if self.i >= len(self.t):
                    raise ExprError("expected a property name")
                kind, name = self.t[self.i]
                if kind != "id" and self.t[self.i] != ("op", "*"):
                    raise ExprError("expected a property name")
                self.i += 1
                node = ("get", node, ("lit", name))
            elif self.peek("["):
                self.i += 1
                index = self.or_()
                self.take("]")
                node = ("get", node, index)
            else:
                return node

    def primary(self):
        if self.i >= len(self.t):
            raise ExprError("unexpected end")
        kind, value = self.t[self.i]
        self.i += 1
        if kind == "lit":
            return ("lit", value)
        if kind == "op" and value == "(":
            node = self.or_()
            self.take(")")
            return node
        if kind == "id":
            if self.peek("("):
                self.i += 1
                args = []
                if not self.peek(")"):
                    args.append(self.or_())
                    while self.peek(","):
                        self.i += 1
                        args.append(self.or_())
                self.take(")")
                return ("call", value.lower(), args)
            return ("ctx", value)
        raise ExprError(f"unexpected `{value}`")


def truthy(v):
    if isinstance(v, Unknown):
        return v
    if isinstance(v, float) and math.isnan(v):
        return False
    return bool(v) if not isinstance(v, (dict, list)) else True


def number(v) -> float:
    if v is None:
        return 0.0
    if isinstance(v, bool):
        return 1.0 if v else 0.0
    if isinstance(v, (int, float)):
        return float(v)
    if isinstance(v, str):
        s = v.strip()
        if s == "":
            return 0.0
        try:
            return float(int(s, 16)) if s.lower().startswith("0x") else float(s)
        except ValueError:
            return math.nan
    return math.nan


def equal(a, b):
    if isinstance(a, Unknown):
        return a
    if isinstance(b, Unknown):
        return b
    if isinstance(a, str) and isinstance(b, str):
        return a.lower() == b.lower()
    if type(a) is type(b) and not isinstance(a, (int, float)):
        return a == b if not isinstance(a, (dict, list)) else a is b
    if isinstance(a, (dict, list)) or isinstance(b, (dict, list)):
        return False
    return number(a) == number(b)


def text(v) -> str:
    if v is None:
        return ""
    if v is True:
        return "true"
    if v is False:
        return "false"
    if isinstance(v, float):
        return str(int(v)) if v.is_integer() else str(v)
    if isinstance(v, (dict, list)):
        return json.dumps(v)
    return str(v)


class Context:
    """What an expression can read: the contexts and the status functions of the place it is evaluated in."""

    def __init__(self, contexts: dict, status: dict):
        self.contexts, self.status = contexts, status

    def eval(self, node):
        kind = node[0]
        if kind == "lit":
            return node[1]
        if kind == "ctx":
            name = node[1]
            for key, value in self.contexts.items():
                if key.lower() == name.lower():
                    return value
            raise ExprError(f"unknown context `{name}`")
        if kind == "get":
            base, key = self.eval(node[1]), self.eval(node[2])
            if isinstance(base, Unknown):
                return base
            if isinstance(base, dict):
                if key in base:
                    return base[key]
                for k, v in base.items():
                    if isinstance(k, str) and isinstance(key, str) and k.lower() == key.lower():
                        return v
                return None
            if isinstance(base, list) and isinstance(key, (int, float)) and 0 <= int(key) < len(base):
                return base[int(key)]
            return None
        if kind == "not":
            v = truthy(self.eval(node[1]))
            return v if isinstance(v, Unknown) else not v
        if kind == "bin":
            op = node[1]
            if op in ("&&", "||"):
                a = self.eval(node[2])
                ta = truthy(a)
                if op == "&&" and ta is False or op == "||" and ta is True:
                    return a
                b = self.eval(node[3])
                if isinstance(ta, Unknown):
                    tb = truthy(b)
                    return b if (op == "&&" and tb is False) or (op == "||" and tb is True) else ta
                return b
            a, b = self.eval(node[2]), self.eval(node[3])
            if op in ("==", "!="):
                r = equal(a, b)
                return r if isinstance(r, Unknown) else (r if op == "==" else not r)
            for v in (a, b):
                if isinstance(v, Unknown):
                    return v
            x, y = number(a), number(b)
            return {"<": x < y, "<=": x <= y, ">": x > y, ">=": x >= y}[op]
        if kind == "call":
            return self.call(node[1], node[2])
        raise ExprError(f"cannot evaluate {kind}")

    def call(self, name: str, args: list):
        if name in self.status:
            return self.status[name]
        values = [self.eval(a) for a in args]
        for v in values:
            if isinstance(v, Unknown):
                return v
        if name == "contains" and len(values) == 2:
            hay, needle = values
            if isinstance(hay, list):
                return any(equal(x, needle) is True for x in hay)
            return text(needle).lower() in text(hay).lower()
        if name == "startswith" and len(values) == 2:
            return text(values[0]).lower().startswith(text(values[1]).lower())
        if name == "endswith" and len(values) == 2:
            return text(values[0]).lower().endswith(text(values[1]).lower())
        if name == "format" and values:
            out = text(values[0])
            for i, v in enumerate(values[1:]):
                out = out.replace("{" + str(i) + "}", text(v))
            return out
        if name == "fromjson" and len(values) == 1:
            return json.loads(text(values[0]))
        if name == "tojson" and len(values) == 1:
            return json.dumps(values[0])
        if name == "join" and values:
            sep = text(values[1]) if len(values) > 1 else ","
            return sep.join(text(v) for v in values[0]) if isinstance(values[0], list) else text(values[0])
        if name == "hashfiles":
            return Unknown("hashFiles()")
        raise ExprError(f"unknown function `{name}()`")


def evaluate(expr, ctx: Context):
    """A whole expression: `${{ … }}` around it is optional, as in an `if:`."""
    if isinstance(expr, bool) or expr is None or isinstance(expr, (int, float)):
        return expr
    s = str(expr).strip()
    m = re.fullmatch(r"\$\{\{(.*)\}\}", s, re.S)
    if m:
        s = m.group(1)
    return ctx.eval(Parser(tokenize(s)).parse())


def condition(expr, ctx: Context):
    """An `if:`; without a status function GitHub reads it as `success() && (…)`."""
    if expr is None:
        return ctx.status["success"]
    if isinstance(expr, bool):
        return truthy(ctx.status["success"]) if expr else False
    s = str(expr)
    value = evaluate(s, ctx)
    if not STATUS_FUNCS.search(s):
        ok = truthy(ctx.status["success"])
        if ok is False:
            return False
        v = truthy(value)
        if isinstance(ok, Unknown):
            return False if v is False else ok
        return v
    return truthy(value)


def substitute(value, ctx: Context) -> tuple[str, list[str]]:
    """Every `${{ … }}` in a string, and the reasons it cannot be (a value only GitHub has)."""
    unknown: list[str] = []

    def one(m):
        v = evaluate(m.group(1), ctx)
        if isinstance(v, Unknown):
            unknown.append(v.why)
            return ""
        return text(v)

    return EXPR.sub(one, text(value)), unknown


# --- the workflow --------------------------------------------------------------------------------------------


def git(*args: str, cwd: pathlib.Path = ROOT) -> str:
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


def host_arch() -> str:
    m = platform.machine().lower()
    return "arm64" if m in ("arm64", "aarch64") else "amd64" if m in ("x86_64", "amd64") else m


def leg_arch(leg: dict) -> str | None:
    for v in leg.values():
        s = text(v).lower()
        for arch, names in (("amd64", ("amd64", "x86_64", "x64")), ("arm64", ("arm64", "aarch64"))):
            if s in names or any(s.endswith("/" + n) for n in names):
                return arch
    return None


def expand_matrix(job: dict) -> list:
    """Matrix legs with GitHub's include/exclude rules; [None] for a job without a matrix."""
    matrix = (job.get("strategy") or {}).get("matrix")
    if matrix is None:
        return [None]
    if not isinstance(matrix, dict):
        raise ExprError("a matrix given as an expression")
    dims = {k: v for k, v in matrix.items() if k not in ("include", "exclude")}
    base: list[dict] = [{}] if dims else []
    for key, values in dims.items():
        base = [dict(c, **{key: v}) for c in base for v in (values if isinstance(values, list) else [values])]
    for ex in matrix.get("exclude") or []:
        base = [c for c in base if not all(c.get(k) == v for k, v in ex.items())]
    extra: list[dict] = []
    for inc in matrix.get("include") or []:
        hits = [c for c in base if all(c.get(k) == v for k, v in inc.items() if k in dims)]
        for c in hits:
            c.update(inc)
        if not hits:
            extra.append(dict(inc))
    return [(c, [k for k in dims]) for c in base + extra]


class Job:
    def __init__(self, job_id: str, spec: dict, leg):
        self.job_id, self.spec = job_id, spec
        self.matrix, dims = (leg if leg is not None else (None, []))
        self.label = job_id if self.matrix is None else \
            f"{job_id} ({', '.join(text(self.matrix[d]) for d in dims if d in self.matrix) or 'leg'})"
        self.status = "pending"  # green | partial | red | skipped | not-run
        self.reason = ""
        self.notes: list[str] = []
        self.not_run_steps: list[str] = []
        self.outputs: dict = {}
        self.seconds = 0.0
        self.budget: float | None = None

    @property
    def result(self):
        """`needs.<id>.result` as GitHub would give it; Unknown when it did not run here."""
        return {"green": "success", "partial": "success", "red": "failure", "skipped": "skipped"}.get(
            self.status, Unknown(f"{self.label} did not run"))


def aggregate(legs: list) -> dict:
    results = [leg.result for leg in legs]
    if any(r == "failure" for r in results):
        result = "failure"
    elif any(isinstance(r, Unknown) for r in results):
        result = next(r for r in results if isinstance(r, Unknown))
    elif all(r == "skipped" for r in results):
        result = "skipped"
    else:
        result = "success"
    outputs: dict = {}
    for leg in legs:
        outputs.update(leg.outputs)
    return {"result": result, "outputs": outputs}


# --- running a job -------------------------------------------------------------------------------------------


def parse_command_file(path: pathlib.Path) -> dict:
    out: dict = {}
    if not path.exists():
        return out
    lines = path.read_text().splitlines()
    i = 0
    while i < len(lines):
        line = lines[i]
        if "<<" in line and ("=" not in line or line.index("<<") < line.index("=")):
            key, delim = line.split("<<", 1)
            buf = []
            i += 1
            while i < len(lines) and lines[i] != delim:
                buf.append(lines[i])
                i += 1
            out[key] = "\n".join(buf)
        elif "=" in line:
            key, value = line.split("=", 1)
            out[key] = value
        i += 1
    return out


def main_checkout() -> pathlib.Path:
    common = pathlib.Path(git("rev-parse", "--path-format=absolute", "--git-common-dir"))
    return common.parent


def setup_java(version: str, env: dict, notes: list) -> None:
    home = None
    if sys.platform == "darwin":
        r = subprocess.run(["/usr/libexec/java_home", "-v", version], capture_output=True, text=True)
        home = r.stdout.strip() if r.returncode == 0 else None
    if home:
        env["JAVA_HOME"] = home
        env["PATH"] = f"{home}/bin{os.pathsep}{env['PATH']}"
        notes.append(f"Java {version}: {home}")
        return
    r = subprocess.run(["java", "-version"], capture_output=True, text=True, env=env)
    found = re.search(r'version "(\d+)', r.stderr or "")
    major = found.group(1) if found else "?"
    if major != str(version).split(".")[0]:
        notes.append(f"host Java {major}, the workflow asks {version}")


def setup_node(version: str, env: dict, notes: list) -> None:
    r = subprocess.run(["node", "--version"], capture_output=True, text=True, env=env)
    found = r.stdout.strip()
    if found.lstrip("v").split(".")[0] != str(version).split(".")[0]:
        notes.append(f"host Node {found or 'missing'}, the workflow asks {version}")


def step_name(step: dict) -> str:
    lines = text(step.get("run")).strip().splitlines()
    return text(step.get("name") or step.get("uses") or (lines[0][:60] if lines else "a step"))


def action_of(uses: str) -> str:
    name = uses.split("@", 1)[0]
    parts = name.split("/")
    return "/".join(parts[:2]) if parts[0] != "gradle" else "/".join(parts[:3])


def classify_uses(uses: str) -> tuple[str, str]:
    action = action_of(uses)
    if action in HOST_ACTIONS:
        return "host", HOST_ACTIONS[action]
    for key, why in WHY_NOT.items():
        if action == key or action.startswith(key):
            return "not-run", why
    return "not-run", "a `uses:` step with no local equivalent"


def kill(proc: subprocess.Popen) -> None:
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(proc.pid, sig)
        except ProcessLookupError:
            return
        try:
            proc.wait(timeout=10)
            return
        except subprocess.TimeoutExpired:
            continue


class Run:
    def __init__(self, args, workflow: dict, sha: str, branch: str):
        self.args, self.workflow, self.sha, self.branch = args, workflow, sha, branch
        stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        self.out = OUT / stamp
        self.work = pathlib.Path(tempfile.mkdtemp(prefix="veyq-pipeline-"))
        self.infra = os.environ.get("VEYQ_INFRA_DIR") or str(main_checkout() / "infra")
        self.os_name = {"darwin": "macOS", "linux": "Linux"}.get(sys.platform, sys.platform)

    def github(self) -> dict:
        event = self.args.event
        return {"event_name": event, "ref": f"refs/heads/{self.branch}", "ref_name": self.branch,
                "sha": self.sha, "repository": "local/veyq", "repository_owner": "local", "actor": "local",
                "run_id": "0", "run_number": "0", "workflow": self.workflow.get("name", ""),
                "base_ref": "main" if event == "pull_request" else "", "head_ref": self.branch if event ==
                "pull_request" else "", "event": {}, "server_url": "https://github.com", "token": Unknown("github.token")}

    def runner(self, temp: str = "") -> dict:
        return {"os": self.os_name, "arch": {"arm64": "ARM64", "amd64": "X64"}.get(host_arch(), host_arch()),
                "temp": temp, "name": "local", "environment": "local"}

    def contexts(self, job: Job, needs: dict, env: dict, steps: dict, temp: str = "") -> dict:
        return {"github": self.github(), "runner": self.runner(temp), "env": env, "matrix": job.matrix or {},
                "needs": needs, "steps": steps, "inputs": {}, "secrets": Unknown("secrets.*, which only GitHub has"),
                "vars": Unknown("vars.*, which only GitHub has"), "job": {"status": "success"}, "strategy": {}}

    # --- one job -----------------------------------------------------------------------------------------

    def run_job(self, job: Job, needs: dict) -> None:
        started = time.monotonic()
        log_path = self.out / f"{re.sub(r'[^A-Za-z0-9_.-]+', '_', job.label)}.log"
        temp = self.work / f"{re.sub(r'[^A-Za-z0-9_.-]+', '_', job.label)}.tmp"
        clone = self.work / re.sub(r"[^A-Za-z0-9_.-]+", "_", job.label)
        try:
            with open(log_path, "w") as log:
                self._run_job(job, needs, log, clone, temp, started)
        except Exception as e:  # a defect of this runner reds the job; it is never read as green
            job.status, job.reason = "red", f"the runner failed: {e!r}"
        finally:
            job.seconds = time.monotonic() - started
            if not self.args.keep:
                shutil.rmtree(clone, ignore_errors=True)
                shutil.rmtree(temp, ignore_errors=True)

    def _run_job(self, job: Job, needs: dict, log, clone: pathlib.Path, temp: pathlib.Path, started: float) -> None:
        spec = job.spec
        log.write(f"# {job.label} — commit {self.sha}, event {self.args.event}\n")
        git("clone", "--quiet", "--shared", "--no-checkout", str(pathlib.Path(git("rev-parse", "--path-format=absolute",
                                                                               "--git-common-dir"))), str(clone))
        git("checkout", "--quiet", "--detach", self.sha, cwd=clone)
        temp.mkdir(parents=True, exist_ok=True)
        env = dict(os.environ)
        env.update({"CI": "true", "GITHUB_WORKSPACE": str(clone), "GITHUB_SHA": self.sha,
                    "GITHUB_REF": f"refs/heads/{self.branch}", "GITHUB_REF_NAME": self.branch,
                    "GITHUB_EVENT_NAME": self.args.event, "GITHUB_REPOSITORY": "local/veyq",
                    "GITHUB_REPOSITORY_OWNER": "local", "RUNNER_TEMP": str(temp), "RUNNER_OS": self.os_name,
                    "RUNNER_ARCH": self.runner()["arch"], "VEYQ_INFRA_DIR": self.infra})
        steps_ctx: dict = {}
        exposed: dict = {}  # the `env` context: workflow, job and GITHUB_ENV values
        for scope in (self.workflow.get("env") or {}, spec.get("env") or {}):
            for key, value in scope.items():
                ctx = Context(self.contexts(job, needs, exposed, steps_ctx, str(temp)), self.status(True))
                v, unknown = substitute(value, ctx)
                if unknown:
                    job.status, job.reason = "not-run", f"its env `{key}` needs {unknown[0]}"
                    return
                exposed[key] = v
        defaults = dict(((self.workflow.get("defaults") or {}).get("run") or {}))
        defaults.update(((spec.get("defaults") or {}).get("run") or {}))
        deadline = started + job.budget * 60 if job.budget else None
        failed = False
        ran = 0
        for index, step in enumerate(spec.get("steps") or [], 1):
            name = step_name(step)
            ctx = Context(self.contexts(job, needs, exposed, steps_ctx, str(temp)), self.status(not failed))
            try:
                go = condition(step.get("if"), ctx)
            except ExprError as e:
                go = Unknown(f"its `if:` ({e})")
            sid = step.get("id")
            if go is False:
                log.write(f"\n## {name}: skipped by its if\n")
                if sid:
                    steps_ctx[sid] = {"outputs": {}, "outcome": "skipped", "conclusion": "skipped"}
                continue
            if isinstance(go, Unknown):
                job.not_run_steps.append(f"{name} ({go.why})")
                continue
            if "uses" in step:
                kind, why = classify_uses(step["uses"])
                if kind == "host":
                    with_ = step.get("with") or {}
                    if action_of(step["uses"]) == "actions/setup-java" and with_.get("java-version"):
                        setup_java(text(with_["java-version"]), env, job.notes)
                    if action_of(step["uses"]) == "actions/setup-node" and with_.get("node-version"):
                        setup_node(text(with_["node-version"]), env, job.notes)
                    log.write(f"\n## {name}: provided by the host ({why})\n")
                else:
                    job.not_run_steps.append(f"{name} ({why})")
                    log.write(f"\n## {name}: NOT RUN ({why})\n")
                continue
            if "run" not in step:
                continue
            step_env = dict(exposed)
            unknown: list[str] = []
            for key, value in (step.get("env") or {}).items():
                v, u = substitute(value, ctx)
                unknown += u
                step_env[key] = v
            script, u = substitute(step["run"], ctx)
            unknown += u
            workdir, u = substitute(step.get("working-directory") or defaults.get("working-directory") or ".", ctx)
            unknown += u
            if unknown:
                job.not_run_steps.append(f"{name} (needs {unknown[0]})")
                log.write(f"\n## {name}: NOT RUN (needs {unknown[0]})\n")
                continue
            shell = text(step.get("shell") or defaults.get("shell") or "")
            script_path = temp / f"step-{index}.sh"
            script_path.write_text(script)
            command = {"": ["bash", "-e", str(script_path)],
                       "bash": ["bash", "--noprofile", "--norc", "-eo", "pipefail", str(script_path)],
                       "sh": ["sh", "-e", str(script_path)],
                       "python": [sys.executable, str(script_path)]}.get(shell)
            if command is None:
                job.not_run_steps.append(f"{name} (shell `{shell}`)")
                continue
            files = {k: temp / f"{k.lower()}-{index}" for k in ("GITHUB_OUTPUT", "GITHUB_ENV", "GITHUB_PATH")}
            for f in files.values():
                f.write_text("")
            run_env = dict(env)
            run_env.update(step_env)
            run_env.update({k: str(v) for k, v in files.items()})
            log.write(f"\n## {name}\n$ {shell or 'bash -e'} (in {workdir})\n{script}\n")
            log.flush()
            timeout = None
            if deadline:
                timeout = max(deadline - time.monotonic(), 0.1)
            if step.get("timeout-minutes"):
                timeout = min(timeout or math.inf, float(step["timeout-minutes"]) * 60)
            proc = subprocess.Popen(command, cwd=clone / workdir, env=run_env, stdout=log, stderr=subprocess.STDOUT,
                                    start_new_session=True)
            with _procs_lock:
                _procs.add(proc)
            try:
                code = proc.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                kill(proc)
                job.status = "red"
                job.reason = f"over its budget of {text(job.budget)} min, in `{name}`" if deadline and \
                    time.monotonic() >= deadline - 0.5 else f"`{name}` outran its timeout-minutes"
                log.write(f"\n## {name}: KILLED — {job.reason}\n")
                return
            finally:
                with _procs_lock:
                    _procs.discard(proc)
            if _stop.is_set():
                job.status, job.reason = "red", "interrupted"
                return
            ran += 1
            outputs = parse_command_file(files["GITHUB_OUTPUT"])
            exposed.update(parse_command_file(files["GITHUB_ENV"]))
            env.update(parse_command_file(files["GITHUB_ENV"]))
            for line in files["GITHUB_PATH"].read_text().splitlines():
                if line.strip():
                    env["PATH"] = f"{line.strip()}{os.pathsep}{env['PATH']}"
            outcome = "success" if code == 0 else "failure"
            log.write(f"## {name}: exit {code}\n")
            if sid:
                steps_ctx[sid] = {"outputs": outputs, "outcome": outcome,
                                  "conclusion": "success" if code == 0 or step.get("continue-on-error") is True
                                  else "failure"}
            if code != 0 and step.get("continue-on-error") is not True:
                failed = True
                job.status, job.reason = "red", f"`{name}` exited {code}"
        if failed:
            return
        if ran == 0:
            job.status = "not-run"
            job.reason = "no step it can run here: " + "; ".join(job.not_run_steps) if job.not_run_steps else \
                "no step it can run here"
            return
        ctx = Context(self.contexts(job, needs, exposed, steps_ctx, str(temp)), self.status(True))
        for key, value in (spec.get("outputs") or {}).items():
            v, _ = substitute(value, ctx)
            job.outputs[key] = v
        job.status = "partial" if job.not_run_steps else "green"

    @staticmethod
    def status(ok) -> dict:
        return {"success": ok, "failure": (not ok) if not isinstance(ok, Unknown) else ok, "always": True,
                "cancelled": False}


# --- scheduling ----------------------------------------------------------------------------------------------


def needs_of(spec: dict) -> list[str]:
    n = spec.get("needs") or []
    return [n] if isinstance(n, str) else list(n)


def plan_job(run: Run, job: Job, groups: dict) -> tuple[str, str]:
    """Decide, before running, whether a job runs: ('run'|'skipped'|'not-run', reason)."""
    spec = job.spec
    needs_ctx = {n: aggregate(groups[n]) for n in needs_of(spec) if n in groups}
    if job.matrix is not None:
        arch = leg_arch(job.matrix)
        if arch and arch != host_arch():
            return "not-run", f"the {arch} leg; this host is {host_arch()}"
    statuses = [aggregate(groups[n])["result"] for n in needs_of(spec) if n in groups]
    missing = [n for n in needs_of(spec) if n not in groups]
    if missing:
        return "not-run", f"needs {', '.join(missing)}, which the workflow does not define"
    if any(r == "failure" for r in statuses):
        ok = False
    elif any(isinstance(r, Unknown) for r in statuses):
        ok = next(r for r in statuses if isinstance(r, Unknown))
    else:
        ok = all(r == "success" for r in statuses)
    ctx = Context(run.contexts(job, needs_ctx, {}, {}), Run.status(ok))
    try:
        go = condition(spec.get("if"), ctx)
    except ExprError as e:
        return "not-run", f"its `if:` cannot be read here ({e})"
    if go is False:
        red = [n for n in needs_of(spec) if aggregate(groups[n])["result"] == "failure"]
        if red and spec.get("if") is None:
            return "skipped", f"needs {', '.join(red)}, which is red"
        return "skipped", f"its `if:` is false for a {run.args.event}, as in CI"
    if isinstance(go, Unknown):
        return "not-run", f"needs what did not run: {go.why}"
    runs_on, unknown = substitute(spec.get("runs-on") or "", ctx)
    if unknown or re.search(r"windows|macos", runs_on, re.I):
        return "not-run", f"runs on `{runs_on or unknown[0]}`"
    budget = spec.get("timeout-minutes")
    job.budget = float(budget) if isinstance(budget, (int, float)) and not isinstance(budget, bool) else None
    return "run", ""


def build_jobs(workflow: dict) -> dict:
    groups: dict = {}
    for job_id, spec in (workflow.get("jobs") or {}).items():
        try:
            legs = expand_matrix(spec)
        except ExprError as e:
            j = Job(job_id, spec, None)
            j.status, j.reason = "not-run", f"its matrix cannot be read here ({e})"
            groups[job_id] = [j]
            continue
        groups[job_id] = [Job(job_id, spec, leg) for leg in legs]
    return groups


def selected(groups: dict, wanted: list[str]) -> set:
    keep: set = set()
    todo = list(wanted)
    while todo:
        j = todo.pop()
        if j in keep or j not in groups:
            continue
        keep.add(j)
        todo += needs_of(groups[j][0].spec)
    return keep


def order(groups: dict) -> list[str]:
    done: list[str] = []
    seen: set = set()

    def visit(j: str, stack: tuple) -> None:
        if j in seen or j not in groups:
            return
        if j in stack:
            raise SystemExit(f"run_pipeline: `needs` cycle through {j}")
        for n in needs_of(groups[j][0].spec):
            visit(n, stack + (j,))
        seen.add(j)
        done.append(j)

    for j in groups:
        visit(j, ())
    return done


def dry_run(run: Run, groups: dict) -> None:
    for job_id in order(groups):
        for job in groups[job_id]:
            if job.status != "pending":
                say(f"- {job.label}: NOT RUN — {job.reason}")
                continue
            decision, reason = plan_job(run, job, groups)
            if decision != "run":
                job.status, job.reason = decision, reason
                say(f"- {job.label}: {decision.upper()} — {reason}")
                continue
            ctx = Context(run.contexts(job, {n: aggregate(groups[n]) for n in needs_of(job.spec)}, {}, {}),
                          Run.status(True))
            lines, runnable = [], 0
            for step in job.spec.get("steps") or []:
                name = step_name(step)
                try:
                    go = condition(step.get("if"), ctx)
                except ExprError as e:
                    go = Unknown(str(e))
                if go is False:
                    lines.append(f"    skip     {name} (its if is false here)")
                elif isinstance(go, Unknown):
                    lines.append(f"    decided  {name} (its if reads {go.why})")
                    runnable += "run" in step
                elif "uses" in step:
                    kind, why = classify_uses(step["uses"])
                    lines.append(f"    {'host    ' if kind == 'host' else 'NOT RUN '} {name} ({why})")
                    if kind != "host":
                        job.not_run_steps.append(f"{name} ({why})")
                else:
                    lines.append(f"    run      {name}")
                    runnable += 1
            if runnable == 0:
                job.status, job.reason = "not-run", "no step it can run here: " + "; ".join(job.not_run_steps)
                say(f"- {job.label}: NOT-RUN — {job.reason}")
                continue
            job.status = "green"  # assumed for the plan of what needs it; nothing ran
            say(f"- {job.label}: would run (budget {text(job.budget)} min; needs "
                f"{', '.join(needs_of(job.spec)) or 'nothing'})")
            for line in lines:
                say(line)
    for jobs in groups.values():
        for job in jobs:
            if job.status == "green":
                job.status = "planned"


def execute(run: Run, groups: dict) -> None:
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=run.args.max_parallel)
    running: dict = {}
    try:
        while True:
            progressed = False
            for job_id in order(groups):
                needs = needs_of(groups[job_id][0].spec)
                if any(leg.status in ("pending", "running") for n in needs if n in groups for leg in groups[n]):
                    continue
                for job in groups[job_id]:
                    if job.status != "pending":
                        continue
                    decision, reason = plan_job(run, job, groups)
                    progressed = True
                    if decision != "run":
                        job.status, job.reason = decision, reason
                        say(f"[{decision}] {job.label} — {reason}")
                        continue
                    job.status = "running"
                    say(f"[start] {job.label} (budget {text(job.budget)} min)")
                    needs_ctx = {n: aggregate(groups[n]) for n in needs if n in groups}
                    running[pool.submit(run.run_job, job, needs_ctx)] = job
            if not running and not progressed:
                break
            if running:
                finished, _ = concurrent.futures.wait(list(running), return_when=concurrent.futures.FIRST_COMPLETED)
                for f in finished:
                    job = running.pop(f)
                    f.result()
                    if job.status in ("running", "pending"):
                        job.status, job.reason = "red", "the runner lost track of it"
                    tail = f" — {job.reason}" if job.reason else ""
                    say(f"[{job.status}] {job.label} {job.seconds / 60:.1f} min{tail}")
    except KeyboardInterrupt:
        _stop.set()
        with _procs_lock:
            for proc in list(_procs):
                kill(proc)
        pool.shutdown(wait=True, cancel_futures=True)
        raise
    pool.shutdown(wait=True)


def summary(run: Run, groups: dict, load_before: str) -> int:
    jobs = [job for job_id in order(groups) for job in groups[job_id]]
    width = max(len(j.label) for j in jobs)
    say("\n" + "=" * 100)
    say(f"pipeline {run.args.workflow} at {run.sha[:12]} ({run.args.event} to {run.branch}), host {run.os_name} "
        f"{host_arch()}")
    say(f"load average before: {load_before}; after: {load_average()}")
    for j in jobs:
        timing = f"{j.seconds / 60:5.1f} min" if j.seconds else "         "
        budget = f"/ {text(j.budget)}" if j.budget else ""
        verdict = {"partial": "green, partial"}.get(j.status, j.status)
        say(f"  {j.label:<{width}}  {verdict:<15} {timing} {budget}")
        if j.reason:
            say(f"  {'':<{width}}    {j.reason}")
        for s in (j.not_run_steps if j.status != "not-run" else []):
            say(f"  {'':<{width}}    step not run: {s}")
        for n in j.notes:
            say(f"  {'':<{width}}    note: {n}")
    red = [j.label for j in jobs if j.status == "red"]
    gaps = [j.label for j in jobs if j.status in ("not-run", "partial", "planned")]
    if run.args.dry_run:
        verdict, code = "DRY RUN — nothing ran, nothing is green", 3
    elif red:
        verdict, code = f"RED — {', '.join(red)}", 1
    elif gaps:
        verdict, code = f"NOT GREEN — no job red, but not run here (whole or in part): {', '.join(gaps)}", 3
    else:
        verdict, code = "GREEN — every job ran green, or was skipped by the workflow as in CI", 0
    say(verdict)
    say(f"logs: {run.out}")
    (run.out / "summary.json").write_text(json.dumps({
        "workflow": run.args.workflow, "commit": run.sha, "event": run.args.event, "verdict": verdict,
        "jobs": [{"job": j.label, "status": j.status, "reason": j.reason, "minutes": round(j.seconds / 60, 2),
                  "budget": j.budget, "steps_not_run": j.not_run_steps, "notes": j.notes} for j in jobs]}, indent=2))
    return code


def load_average() -> str:
    try:
        return " ".join(f"{x:.1f}" for x in os.getloadavg())
    except OSError:
        return "?"


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="Run ci.yml locally (see the module docstring).")
    ap.add_argument("--workflow", default=DEFAULT_WORKFLOW,
                    help="the workflow (default: ci.yml, read from the commit); a path to a file is read from disk")
    ap.add_argument("--rev", default="HEAD", help="the commit to run (default HEAD)")
    ap.add_argument("--event", choices=("push", "pull_request"), default="push")
    ap.add_argument("--job", action="append", default=[], help="run only this job (and what it needs); repeatable")
    ap.add_argument("--skip-job", action="append", default=[], help="leave this job out (reported not run)")
    ap.add_argument("--max-parallel", type=int, default=DEFAULT_PARALLEL)
    ap.add_argument("--dry-run", action="store_true", help="print the plan; run nothing")
    ap.add_argument("--keep", action="store_true", help="keep the jobs' clones")
    args = ap.parse_args(argv)

    sha = git("rev-parse", "--verify", f"{args.rev}^{{commit}}")
    try:
        branch = git("symbolic-ref", "--short", "-q", "HEAD")
    except subprocess.CalledProcessError:
        branch = "HEAD"
    if args.workflow == DEFAULT_WORKFLOW or not pathlib.Path(args.workflow).exists():
        source = git("show", f"{sha}:{args.workflow}")
        where = f"{args.workflow} at {sha[:12]}"
    else:
        source = pathlib.Path(args.workflow).read_text()
        where = f"{args.workflow} (from disk)"
    workflow = yaml.safe_load(source) or {}
    if git("status", "--porcelain", "--untracked-files=no"):
        say("note: the working tree has uncommitted changes; they are not in this run (it runs the commit)")
    run = Run(args, workflow, sha, branch)
    run.out.mkdir(parents=True, exist_ok=True)
    groups = build_jobs(workflow)
    if not groups:
        say(f"run_pipeline: {where} has no jobs")
        return 1
    unknown = [j for j in args.job + args.skip_job if j not in groups]
    if unknown:
        say(f"run_pipeline: no job {unknown} in {where} (it has {', '.join(groups)})")
        return 2
    keep = selected(groups, args.job) if args.job else set(groups)
    for job_id, jobs in groups.items():
        for job in jobs:
            if job_id not in keep:
                job.status, job.reason = "not-run", "not selected (--job)"
            elif job_id in args.skip_job:
                job.status, job.reason = "not-run", "left out (--skip-job)"
    say(f"run_pipeline: {where}, commit {sha[:12]}, a {args.event} to {branch}; host {run.os_name} {host_arch()}, "
        f"up to {args.max_parallel} jobs at once; VEYQ_INFRA_DIR={run.infra}")
    load_before = load_average()
    try:
        if args.dry_run:
            dry_run(run, groups)
        else:
            execute(run, groups)
    finally:
        if not args.keep:
            shutil.rmtree(run.work, ignore_errors=True)
    return summary(run, groups, load_before)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
