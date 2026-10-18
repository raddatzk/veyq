#!/usr/bin/env python3
"""Fuzz the API from its contracts against a live backend: Schemathesis, pinned by digest (ADR 0091 decision 9).

**What it does.** Boots one fresh fast-jar under `%e2e` — the profile whose rate limits do not refuse a run's volume —
with `run_web_e2e.py`'s own primitives (the shared compose stack's MariaDB and store, its own database
[DATABASE] and bucket [BUCKET], its own ports), births an instance on it with the real client crypto
(`web/scripts/fuzz-seed.mts`), and runs the pinned Schemathesis image four times over every spec file
`contract_specs.specs()` finds — never a list here:

    {public listener, admin listener} x {negative on every operation, positive on every GET with the seeded ids}

An operation marked `x-veyq-channel: admin` goes to the admin listener, every other one to the public listener; a spec
file with no operation for an invocation is not run for it. Before each spec file the runner makes sure the access
token still works and signs in again (`fuzz-seed.mts --login`) when it does not: a token lives 15 minutes, a run
longer, and a negative request a server wrongly accepted may end a session.

**What the image checks** is `infra/fuzz/schemathesis.toml` (the checks, the auth) and `infra/fuzz/hooks.py` (veyq's
two own checks: a refusal by location, and a refusal that echoes nothing). The seed is [SEED], the generation
deterministic: a red re-runs to the same request. Changing the seed is a commit (ADR 0091 decision 9).

**Red when** a failure is not in `infra/fuzz/baseline.json` (Schemathesis' own verdict, its exit code), an invocation
of a spec file exits non-zero for any other reason, an invocation ERRORED on an operation (a network error is no
green), or an invocation **tested no operation** — read from its JSON report, never from the exit code, because "the
guard held" and "nothing ran" exit alike (changes/README.md § a bite test must tell them apart). The baseline's own
rules — a reason per entry, never a 5xx, no expiry — are `scripts/validate_api_fuzz_baseline.py`'s.

**What it prints and keeps.** The run directory (`.fuzz-out/<stamp>/`, gitignored; `VEYQ_FUZZ_OUT` moves the root)
holds the boot log, the seed (`seed.json`, `token`: mode 0600, a throwaway instance's), and per invocation and spec
file Schemathesis' JUnit (with a `curl` line per failure), NDJSON, VCR cassette and JSON report. `summary.txt` — also
printed — names per invocation the operations tested, every failure by check and operation with its `curl` line, and
**the GET operations that never answered 2xx** in the positive invocations: information, not a verdict — there the
response schema went unchecked, because no seeded id reaches them (a node, a medium, a share, a moderation case).

**The image is our own** (`infra/fuzz/Dockerfile`, owner decision Q8 of cr-api-fuzzing): a Python base pinned by
digest, Schemathesis from PyPI with every package hash-pinned (`infra/fuzz/requirements.txt`, written by
`infra/fuzz/lock.py`). The official `schemathesis/schemathesis` image also installs TraceCov, a proprietary,
binary-only coverage plugin, and loads it on every run; ours carries only the MIT tool, and `hooks.py` refuses to load
if TraceCov is ever imported. The runner builds it before the first invocation, tagged by the hash of the directory's
build inputs ([IMAGE_INPUTS]), so a changed pin is a new image and an unchanged one a cache hit.

**Networking — the one step that differs between Docker Desktop and a Linux runner**, made the same command on both:
the container reaches the host's listeners through `--add-host <name>:host-gateway`, as `veyq.localhost` (the tenant's
domain, which the backend resolves the tenant from) and `veyq-admin.internal`. The jar binds every interface under
`%e2e` (Quarkus' production default host), and the admin channel admits a non-global peer (`AdminChannel`), which a
bridge or Docker Desktop's gateway is. Measured on Docker Desktop (macOS, arm64) only; a Linux runner is CI's.

**Sharing the developer's compose stack.** Like `run_web_e2e.py`, it uses `infra/docker-compose.yml`'s MariaDB and
store — its own database and bucket only, and `up --no-recreate`, so a running stack is never recreated. From a
git worktree, pin the stack to the main checkout's `infra/` (`VEYQ_INFRA_DIR=<main>/infra`) and the jar to the
worktree's (`VEYQ_JAR`), as that script's `compose_up` explains; `run_pipeline.py` does the first for every job.

**Measured** (2026-10-18, local: macOS arm64, Docker Desktop, a load average of ~12 from parallel sessions;
`MAX_EXAMPLES` 10): boot and seed 30 s; public-negative 130 s (175 operations, 16 spec files), public-positive 53 s (50
GETs), admin-negative 23 s (53 operations), admin-positive 5 s (15 GETs) — 236 s in all. With the jar build and `npm ci`
before it the job fits its 15 minutes on that machine; a CI runner has fewer cores, and its first run replaces these
numbers (ADR 0091 decision 1). `MAX_EXAMPLES` is the knob if it does not.

Usage:
    python3 scripts/run_api_fuzz.py                      # the whole run, as CI calls it
    python3 scripts/run_api_fuzz.py --baseline-update    # also merge this run's failures into infra/fuzz/baseline.json
    python3 scripts/run_api_fuzz.py --only <spec-key>    # one spec file (a local debugging convenience)

Prerequisite: the fast-jar (`cd backend && ./gradlew quarkusBuild`) and web's dependencies (`npm ci` at the root, which
generates web's API client). A stale jar answers for code the run never saw (`run_web_e2e.py`'s docstring).

Environment (all optional):
    VEYQ_JAR                The fast-jar. Default: backend/build/quarkus-app/quarkus-run.jar
    VEYQ_INFRA_DIR          The `infra/` whose compose stack to use (see above).
    VEYQ_FUZZ_PORT          The backend's public port. Default: 18480
    VEYQ_FUZZ_ADMIN_PORT    Its admin-listener port. Default: 18490
    VEYQ_FUZZ_OUT           The root of the run directories. Default: .fuzz-out
    VEYQ_FUZZ_MAX_EXAMPLES  Overrides [MAX_EXAMPLES] (a local sampling knob; CI uses the constant).
    VEYQ_FUZZ_INCLUDE_BY    An extra `--include-by` expression for every invocation (the bites' lever).
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import NamedTuple

# The backend's ports and the e2e primitives' module-level constants are read at import: this run's own ports are set
# first, so it never answers on (or kills) a developer's 8080/8090 or a web-e2e run's.
os.environ["VEYQ_PORT"] = os.environ.get("VEYQ_FUZZ_PORT", "18480")
os.environ["VEYQ_ADMIN_PORT"] = os.environ.get("VEYQ_FUZZ_ADMIN_PORT", "18490")

import contract_specs  # noqa: E402
import run_web_e2e as e2e  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
WEB = REPO / "web"
FUZZ = REPO / "infra" / "fuzz"
CONFIG = FUZZ / "schemathesis.toml"
BASELINE = FUZZ / "baseline.json"

# What the image is built from; their hash is its tag (`veyq-fuzz-schemathesis:<hash>`), so the tag names the pins.
IMAGE_INPUTS = ("Dockerfile", "requirements.txt")
IMAGE_REPO = "veyq-fuzz-schemathesis"
# Fixed, so a red re-runs to the same request (ADR 0091 decision 9). Changing it is a commit.
SEED = 20261018
# Test cases per operation and phase — the sampling knob that fits the job's 15 minutes (ADR 0091 decision 1).
MAX_EXAMPLES = int(os.environ.get("VEYQ_FUZZ_MAX_EXAMPLES", "10"))
PHASES = "examples,coverage,fuzzing"  # the stateful phase is the follow-up slice's (cr-api-fuzzing Q3)

# This run's OWN database and bucket: never `veyq` (the developer's), never a `veyq_e2e_*` (a web-e2e run's).
DATABASE = "veyq_fuzz"
STORE_INIT_DATABASE = "veyq_fuzz_store_init"
BUCKET = "veyq-fuzz"

HOST = "veyq.localhost"
ADMIN_HOST = "veyq-admin.internal"
PORT = e2e.PORT
ADMIN_PORT = e2e.ADMIN_PORT
RUN_ROOT = Path(os.environ.get("VEYQ_FUZZ_OUT", REPO / ".fuzz-out"))
ADMIN = '/x-veyq-channel == "admin"'
NOT_GET = "^(?!GET$)"  # every method but GET
METHODS = ("get", "put", "post", "delete", "patch", "head", "options")


def log(msg: str) -> None:
    print(f"[fuzz] {msg}", flush=True)


class Invocation(NamedTuple):
    name: str
    channel: str  # "public" | "admin"
    mode: str  # "negative" | "positive"


INVOCATIONS = (
    Invocation("public-negative", "public", "negative"),
    Invocation("public-positive", "public", "positive"),
    Invocation("admin-negative", "admin", "negative"),
    Invocation("admin-positive", "admin", "positive"),
)


def image_tag() -> str:
    digest = hashlib.sha256()
    for name in IMAGE_INPUTS:
        digest.update(name.encode() + b"\0" + (FUZZ / name).read_bytes() + b"\0")
    return f"{IMAGE_REPO}:{digest.hexdigest()[:16]}"


def build_image() -> str:
    """Our Schemathesis image (`infra/fuzz/Dockerfile`), built unless this very set of pins is built already."""
    tag = image_tag()
    if subprocess.run(["docker", "image", "inspect", tag], capture_output=True).returncode != 0:
        log(f"building {tag} from infra/fuzz/")
        e2e.run(["docker", "build", "--quiet", "-t", tag, str(FUZZ)])
    return tag


def operations(spec: Path, inv: Invocation) -> list[str]:
    """The operations of one spec file this invocation selects — `METHOD /path`, Schemathesis' own label."""
    doc = contract_specs.load(contract_specs.key(spec))
    out = []
    for path, item in (doc.get("paths") or {}).items():
        for method, op in item.items():
            if method not in METHODS:
                continue
            admin = op.get("x-veyq-channel") == "admin"
            if (inv.channel == "admin") != admin:
                continue
            if inv.mode == "positive" and method != "get":
                continue
            out.append(f"{method.upper()} {path}")
    return sorted(out)


# --------------------------------------------------------------------------------------------------------------
# The instance
# --------------------------------------------------------------------------------------------------------------

def stack_up() -> None:
    """MariaDB, the store and Mailpit (the seeded tenant mails through it), as `run_web_e2e.compose_up` brings them —
    but `--no-recreate`: a stack that is already running (a developer's) is used as it is, never recreated onto another
    checkout's files."""
    log(f"compose up --no-recreate mariadb garage mailpit, from {e2e.COMPOSE_FILE}")
    e2e.run(e2e.COMPOSE + ["up", "-d", "--no-recreate", "--wait", "mariadb", "garage", "mailpit"])


def provision_store(workdir: Path) -> None:
    """The store must carry the backend's key alias before [BUCKET] can be granted to it. A store some backend already
    provisioned is used as it is; a cold one (CI) gets one throwaway boot on its own database, as in
    `run_web_e2e.provision_store`, which proves the provisioner — this run proves the API."""
    if e2e.store_is_provisioned():
        log("store already provisioned")
        return
    log("booting a throwaway backend to provision the store")
    secret = workdir / "config-secret-store-init.key"
    boot_log = workdir / "backend-store-init.log"
    e2e.fresh_database(STORE_INIT_DATABASE)
    proc = e2e.boot(STORE_INIT_DATABASE, secret, boot_log)
    try:
        not_ready = e2e.wait_ready(time.time() + e2e.BOOT_SECS, proc)
        if not_ready:
            e2e.tail(boot_log)
            raise SystemExit(f"[fuzz] STORE BRING-UP BOOT FAILED: {not_ready}")
        deadline = time.time() + 120
        while time.time() < deadline:
            if e2e.store_is_provisioned():
                return
            time.sleep(1)
        e2e.tail(boot_log)
        raise SystemExit("[fuzz] STORE NOT PROVISIONED within 120 s of the boot")
    finally:
        e2e.kill(proc)
        secret.unlink(missing_ok=True)


def seed_cmd(workdir: Path, *extra: str) -> list[str]:
    return ["npx", "tsx", "scripts/fuzz-seed.mts", "--out", str(workdir), *extra]


def seed_env() -> dict[str, str]:
    return {
        **os.environ,
        "VEYQ_BASE": f"http://127.0.0.1:{PORT}",
        "VEYQ_ADMIN_BASE": f"http://127.0.0.1:{ADMIN_PORT}",
        "VEYQ_HOST": HOST,
    }


def seed(workdir: Path, *extra: str) -> None:
    result = subprocess.run(seed_cmd(workdir, *extra), cwd=str(WEB), env=seed_env(), text=True, capture_output=True)
    sys.stdout.write(result.stdout)
    if result.returncode != 0:
        sys.stdout.write(result.stderr)
        raise SystemExit(f"[fuzz] SEED FAILED ({' '.join(extra) or 'birth'}), exit {result.returncode}")


def token(workdir: Path) -> str:
    return (workdir / "token").read_text().strip()


def token_works(workdir: Path) -> bool:
    """The account-level read every signed-in member may make; anything but 200 means the token is spent."""
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}/api/account/key-material",
                                 headers={"Authorization": f"Bearer {token(workdir)}", "Host": HOST})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status == 200
    except (urllib.error.URLError, OSError):
        return False


def fresh_token(workdir: Path) -> None:
    if not token_works(workdir):
        log("the access token no longer works — signing in again")
        seed(workdir, "--login")
        if not token_works(workdir):
            raise SystemExit("[fuzz] a fresh sign-in yields no working token")


# --------------------------------------------------------------------------------------------------------------
# The invocations
# --------------------------------------------------------------------------------------------------------------

def positive_config(workdir: Path, ids: dict[str, str]) -> Path:
    """`schemathesis.toml` plus the seeded ids as fixed parameters — the positive invocations' copy only."""
    lines = [CONFIG.read_text(), "", "# Appended by scripts/run_api_fuzz.py: the seeded ids (fuzz-seed.mts).",
             "[parameters]"]
    lines += [f'"path.{name}" = "{value}"' for name, value in sorted(ids.items())]
    path = workdir / "reports" / "positive.toml"
    path.write_text("\n".join(lines) + "\n")
    return path


class Result(NamedTuple):
    inv: Invocation
    spec: str
    expected: list[str]
    exit_code: int
    report: dict | None
    report_dir: Path
    seconds: float


def run_one(image: str, inv: Invocation, spec: Path, workdir: Path, config: Path, baseline: Path, update: bool,
            expected: list[str]) -> Result:
    key = contract_specs.key(spec)
    rel = f"{inv.name}/{key.replace('/', '__').removesuffix('.openapi.yaml')}"
    out = workdir / "reports" / rel
    out.mkdir(parents=True, exist_ok=True)
    reports = workdir / "reports"
    url = f"http://{HOST}:{PORT}" if inv.channel == "public" else f"http://{ADMIN_HOST}:{ADMIN_PORT}"
    container_config = f"/out/{config.relative_to(reports).as_posix()}" if config.is_relative_to(reports) \
        else config.relative_to(REPO).as_posix()
    container_baseline = f"/out/{baseline.relative_to(reports).as_posix()}" if baseline.is_relative_to(reports) \
        else baseline.relative_to(REPO).as_posix()
    cmd = [
        "docker", "run", "--rm",
        "--add-host", f"{HOST}:host-gateway", "--add-host", f"{ADMIN_HOST}:host-gateway",
        "--user", f"{os.getuid()}:{os.getgid()}", "-e", "HOME=/tmp",
        "-v", f"{REPO}:/repo:ro", "-v", f"{reports}:/out", "-w", "/repo",
        # The token by NAME only: its value travels in this process' environment, never on a command line.
        "-e", "VEYQ_FUZZ_TOKEN",
        image,
        "--config-file", container_config, "--no-color",
        "run", f"contracts/api/{key}", "--url", url,
        "--mode", inv.mode, "--phases", PHASES,
        "--max-examples", str(MAX_EXAMPLES), "--seed", str(SEED), "--generation-deterministic",
        "--continue-on-failure",
        "--baseline", container_baseline,
        "--report", "junit,ndjson,vcr,json", "--report-dir", f"/out/{rel}",
        "--report-json-path", f"/out/{rel}/report.json",
    ]
    cmd += ["--include-by", ADMIN] if inv.channel == "admin" else ["--exclude-by", ADMIN]
    if inv.mode == "positive":
        # An exclusion, not `--include-method GET`: Schemathesis ORs its inclusions, so beside the admin channel's
        # `--include-by` an inclusion would select every admin operation (measured: the first run's admin-positive
        # invocation tested 59 operations where 15 are GETs). Exclusions are ANDed.
        cmd += ["--exclude-method-regex", NOT_GET]
    if os.environ.get("VEYQ_FUZZ_INCLUDE_BY"):
        cmd += ["--include-by", os.environ["VEYQ_FUZZ_INCLUDE_BY"]]
    if update:
        cmd += ["--baseline-update"]
    env = {**os.environ, "VEYQ_FUZZ_TOKEN": token(workdir)}
    started = time.time()
    with (out / "schemathesis.log").open("w") as handle:
        code = subprocess.run(cmd, env=env, stdout=handle, stderr=subprocess.STDOUT).returncode
    seconds = time.time() - started
    try:
        report = json.loads((out / "report.json").read_text())
    except (OSError, ValueError):
        report = None
    return Result(inv, key, expected, code, report, out, seconds)


# --------------------------------------------------------------------------------------------------------------
# The verdict
# --------------------------------------------------------------------------------------------------------------

def junit_failures(out: Path) -> list[tuple[str, str]]:
    """(operation, message) per failed test case of a JUnit report — the message carries the check, the curl line."""
    found = []
    for xml in out.glob("*.xml"):
        try:
            root = ET.parse(xml).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            for child in case:
                if child.tag in ("failure", "error"):
                    found.append((case.get("name") or "?", (child.get("message") or "") + "\n" + (child.text or "")))
    return found


def statuses_by_operation(out: Path) -> dict[str, set[int]]:
    """Every status each operation answered, read from the NDJSON event stream (its recorded interactions)."""
    seen: dict[str, set[int]] = {}

    def walk(node: object, label: str | None) -> None:
        if isinstance(node, dict):
            here = node.get("label") if isinstance(node.get("label"), str) else label
            response = node.get("response")
            if isinstance(response, dict) and isinstance(response.get("status_code"), int) and here:
                seen.setdefault(here, set()).add(response["status_code"])
            for value in node.values():
                walk(value, here)
        elif isinstance(node, list):
            for value in node:
                walk(value, label)

    for nd in out.glob("*.ndjson"):
        for line in nd.read_text(errors="replace").splitlines():
            try:
                walk(json.loads(line), None)
            except ValueError:
                continue
    return seen


def verdict(results: list[Result]) -> tuple[list[str], list[str]]:
    """(problems, summary lines)."""
    problems: list[str] = []
    lines: list[str] = []
    by_inv: dict[str, list[Result]] = {}
    for r in results:
        by_inv.setdefault(r.inv.name, []).append(r)
    never_2xx: list[str] = []
    for name, rs in by_inv.items():
        tested = sum((r.report or {}).get("operations", {}).get("tested", 0) or 0 for r in rs)
        expected = sum(len(r.expected) for r in rs)
        seconds = sum(r.seconds for r in rs)
        lines.append(f"== {name}: {tested} of {expected} operations tested, {len(rs)} spec files, {seconds:.0f} s")
        for r in rs:
            ops = (r.report or {}).get("operations") or {}
            where = f"{name} {r.spec}"
            if r.report is None:
                problems.append(f"{where}: no JSON report (exit {r.exit_code}) — {r.report_dir / 'schemathesis.log'}")
                continue
            if not ops.get("tested"):
                problems.append(f"{where}: tested no operation (selected {ops.get('selected')}, expected "
                                f"{len(r.expected)}) — an invocation that ran nothing is no green")
            if ops.get("errored"):
                problems.append(f"{where}: {ops['errored']} operation(s) errored — "
                                f"{[e.get('title') for e in r.report.get('errors') or []]}")
            new = (r.report.get("baseline") or {}).get("new")
            failures = r.report.get("failures") or []
            if r.exit_code != 0:
                problems.append(f"{where}: exit {r.exit_code}"
                                + (f", {new} failure(s) not in the baseline" if new else ""))
            for group in failures:
                lines.append(f"   {r.spec}: [{group.get('code') or group.get('type')}] {group.get('title')} "
                             f"x{group.get('count')} on {', '.join(group.get('operations') or [])}")
            for op, message in junit_failures(r.report_dir):
                curl = next((ln.strip() for ln in message.splitlines() if ln.strip().startswith("curl ")), None)
                lines.append(f"      {op}: {message.strip().splitlines()[0][:160] if message.strip() else ''}"
                             + (f"\n         {curl}" if curl else ""))
            if r.inv.mode == "positive":
                statuses = statuses_by_operation(r.report_dir)
                for op in r.expected:
                    if not any(200 <= s < 300 for s in statuses.get(op, set())):
                        never_2xx.append(f"{op} ({sorted(statuses.get(op, set()))})")
    if never_2xx:
        lines.append(f"== GET operations that never answered 2xx — their response schemas went unchecked "
                     f"(information, no verdict): {len(never_2xx)}")
        lines += [f"   {x}" for x in sorted(never_2xx)]
    return problems, lines


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--baseline-update", action="store_true",
                        help="merge this run's failures into infra/fuzz/baseline.json (each needs a reason by hand)")
    parser.add_argument("--only", action="append", default=[], help="run only this spec key (repeatable)")
    args = parser.parse_args(argv)

    if not e2e.JAR.is_file():
        raise SystemExit(f"[fuzz] no fast-jar at {e2e.JAR} — `cd backend && ./gradlew quarkusBuild` first")
    if e2e.port_is_taken():
        raise SystemExit(f"[fuzz] something already answers on {e2e.READY_URL} — not ours to fuzz")
    specs = [p for p in contract_specs.specs() if not args.only or contract_specs.key(p) in args.only]
    if not specs:
        raise SystemExit("[fuzz] no spec file selected")

    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    workdir = RUN_ROOT / stamp
    (workdir / "reports").mkdir(parents=True)
    log(f"run directory {workdir}")
    started = time.time()

    image = build_image()

    stack_up()
    provision_store(workdir)
    e2e.fresh_bucket(BUCKET)
    e2e.fresh_database(DATABASE)
    secret = workdir / "config-secret.key"
    boot_log = workdir / "backend.log"
    proc = e2e.boot(DATABASE, secret, boot_log, bucket=BUCKET)
    results: list[Result] = []
    try:
        not_ready = e2e.wait_ready(time.time() + e2e.BOOT_SECS, proc)
        if not_ready:
            e2e.tail(boot_log)
            raise SystemExit(f"[fuzz] BOOT FAILED: {not_ready}")
        log(f"backend ready on {PORT} (admin {ADMIN_PORT}) after {time.time() - started:.0f} s")
        seed(workdir)
        ids = json.loads((workdir / "seed.json").read_text())["ids"]
        configs = {"negative": CONFIG, "positive": positive_config(workdir, ids)}
        baseline = BASELINE
        if args.baseline_update:
            baseline = workdir / "reports" / "baseline.json"
            shutil.copyfile(BASELINE, baseline)
        for inv in INVOCATIONS:
            for spec in specs:
                expected = operations(spec, inv)
                if not expected:
                    continue
                fresh_token(workdir)
                r = run_one(image, inv, spec, workdir, configs[inv.mode], baseline, args.baseline_update, expected)
                ops = (r.report or {}).get("operations") or {}
                log(f"{inv.name} {r.spec}: exit {r.exit_code}, tested {ops.get('tested')}/{len(expected)}, "
                    f"{r.seconds:.0f} s")
                results.append(r)
        if args.baseline_update:
            shutil.copyfile(baseline, BASELINE)
            log(f"baseline merged into {BASELINE.relative_to(REPO)} — give every new entry its `reason`")
    finally:
        e2e.kill(proc)
        secret.unlink(missing_ok=True)

    problems, lines = verdict(results)
    lines.append(f"== total {time.time() - started:.0f} s, max-examples {MAX_EXAMPLES}, seed {SEED}")
    summary = "\n".join(lines) + "\n"
    (workdir / "summary.txt").write_text(summary + "".join(f"RED: {p}\n" for p in problems))
    print(summary, flush=True)
    if problems:
        for p in problems:
            print(f"[fuzz] RED: {p}", flush=True)
        return 1
    log("GREEN")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
