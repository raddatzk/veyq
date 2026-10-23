#!/usr/bin/env python3
"""Where does the time go? One profile per hot path, client and server, on the release candidate (cr-profile-where-the-time-goes).

A READING, never a check: nothing it records can turn red because the product got slower, and it makes no coverage
claim (ADR 0040). Run by hand on one quiet machine; no CI job runs it. What it can refuse is a run whose files would be
unsafe or meaningless — a busy machine, a profile file holding a secret, a recording whose secret filter is unarmed.

    profile_hot_paths.py --app IMG --admin IMG [--scenario s1] [--runs 3] [--throttles 1,4] [--keep] [--ignore-load]

**What it profiles on.** A throwaway instance of the PRODUCTION recipe booted from the two candidate images, exactly as
lane L1 installs it — `scripts/run_stack_e2e.py`'s own stages (assets, release mirror, Pebble, `veyqctl install
--unsigned-local`, certificate), imported and called — never `quarkusDev` and never the Vite dev server: a dev build
profiles code the release does not ship. L1's overlay gets two additions for the app (`profile-overlay.yml` in the run
directory, kept by `veyqctl` as the instance's `compose.override.yml`): JFR through `JAVA_TOOL_OPTIONS` (the image's
entrypoint is fixed, and its Temurin JRE has no `jcmd`, so the recording starts with the JVM and is dumped when the app
stops), with the run directory's `jfr/` mounted to receive it; and `VEYQ_PROFILE_CANARY`, a random value in the app's
environment that the recording must not contain. Then it is set up by the e2e bootstrap — L1's own `setup` and
`scenarios` projects (first run, the tenant, its owner active, one Memory with one photo) — and not by the volume
harness, which `cr-volume-measurement-cax` deletes.

**The method (decision 2).**
  - **Client.** Chromium through Playwright, in the Playwright image the overlay pins, on the instance's networks
    (`tests/e2e/profile/`). Per run, a fresh context; what the operation needs typed is typed first, then the CPU
    profile of the main thread (CDP `Profiler`, 100 µs), a performance trace of every thread (the workers included) and
    the CPU throttle start, the operation runs, and all three stop. `--runs` runs at each of `--throttles`, one
    Playwright invocation per run, so the server side can be read per run. The machine idle at the start: the 1-minute
    load at most 3, or the run is refused (`--ignore-load` takes it anyway and marks it `quiet: false`).
  - **Server.** JFR with the `profile` settings, its execution and allocation SAMPLES — no heap dump on either side —
    and the events that record the process environment and the system properties OFF (`jdk.InitialEnvironmentVariable`,
    `jdk.InitialSystemProperty`): the recipe hands the app `QUARKUS_DATASOURCE_PASSWORD` and the store's admin token in
    its environment. One recording spans boot, bootstrap and every run; each run's window (`<stem>.window.json`, epoch
    milliseconds) cuts it to the same moment. And MariaDB's statement counters around each run, against an idle drift
    measured before the first, beside the API requests the page made in the window: statements per request.
  - **The recording's own control** (the bite, every run): after the runs the app stops (JFR dumps on exit) and the
    recording must contain neither the canary nor any secret of the instance's `.env`; then the app is recreated with
    the same recording minus the two `#enabled=false` and stopped again, and THAT recording must contain the canary.
    A filter whose control does not fire proves nothing, so the run is refused either way.

**What a run writes** (`.run/profiles/<stamp>/`, ignored by git): per run `<stem>.cpuprofile` (DevTools' format),
`<stem>.trace.json` (Perfetto / DevTools), `<stem>.window.json` (the window, its API requests, the page's metrics);
`jfr/run.jfr` and `jfr/control.jfr`; `server.json` (the counters, per run); `meta.json` (commit, machine, Docker VM,
browser, images, load, JFR options, trace categories, the date); and L1's own files of the bootstrap, the instance's
home with its secrets included. **Every one of them is a file of a signed-in session on a test instance**: the client
files name the account's objects, the bootstrap's `instance.json` holds the test owner's password and Secret Key. So
they never enter git, never leave this machine and are never attached to an issue; only the numbers move into docs. The
client files are checked for that owner's password and Secret Key before a run is accepted.

**Its limits.** One machine, Chromium only, synthetic content, a loopback network through the Docker VM. JFR's samples
are of the JVM's threads only (the database and the store are other containers, seen through the statement counters
and the request times). The statement counters are MariaDB's GLOBAL ones, so a background job inside a window counts;
the idle drift is subtracted as an estimate, never attributed. The CPU throttle is Chromium's emulation, not a slower
device. S2 and S3 (decision 1) join `--scenario` with their readings (the CR's phase 2).
"""
from __future__ import annotations

import argparse
import json
import os
import platform
import re
import secrets
import subprocess
import sys
import time
from pathlib import Path

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

import repo_layout  # noqa: E402 - where the deployment files and the test harnesses live (ADR 0106)
import run_stack_e2e as stack  # noqa: E402 - lane L1's own stages, so the instance is installed as L1 installs it

ROOT = Path(__file__).resolve().parent.parent
PROFILES = ROOT / ".run" / "profiles"
PROFILE_CONFIG = (repo_layout.E2E_DIR / "profile" / "playwright.config.ts").relative_to(repo_layout.E2E_DIR)
SCENARIOS = {"s1": "s1-unlock"}
LOAD_LIMIT = 3.0
IDLE_SECS = 20
# Where the app writes its recording: a bind mount of the run directory's `jfr/`, since the recipe's /tmp is a tmpfs
# that a stopped container no longer has.
JFR_MOUNT = "/profile"
JFR_SETTINGS = "profile"
JFR_FILTERED = ("jdk.InitialEnvironmentVariable", "jdk.InitialSystemProperty")
CANARY_VAR = "VEYQ_PROFILE_CANARY"
# A `.env` entry whose name says it is a secret: what the app (or a neighbour) holds that a recording must not. A value
# that is a path or an address is a locator, not a secret, and may rightly occur in a recording (a file event).
SECRET_NAME = re.compile(r"(PASSWORD|SECRET|TOKEN|KEY)")
STATUS_VARS = ("Questions", "Com_select", "Com_insert", "Com_update", "Com_delete", "Com_stmt_execute", "Com_commit")
STATEMENTS = ("Com_select", "Com_insert", "Com_update", "Com_delete", "Com_stmt_execute")


class Refused(Exception):
    """A run the driver will not take, or a file it will not accept, and why."""


def say(msg: str) -> None:
    print(msg, flush=True)


def sh(cmd: list[str], check: bool = True, **kw) -> subprocess.CompletedProcess:
    result = subprocess.run(cmd, text=True, capture_output=True, **kw)
    if check and result.returncode != 0:
        raise Refused(f"`{' '.join(cmd[:5])} …` exited {result.returncode}: {(result.stderr or '').strip()[-1500:]}")
    return result


# ---------------------------------------------------------------- the server's recording


def jfr_options(filename: str, filtered: bool = True) -> str:
    """The app's JAVA_TOOL_OPTIONS: a recording from JVM start, dumped to the mount on exit; `filtered` is the run's."""
    options = [f"filename={JFR_MOUNT}/{filename}", "dumponexit=true", f"settings={JFR_SETTINGS}"]
    if filtered:
        options += [f"{event}#enabled=false" for event in JFR_FILTERED]
    return "-XX:StartFlightRecording:" + ",".join(options)


def with_recording(tool_options: str, recording: str) -> str:
    """`tool_options` with its recording (if any) replaced by `recording`, every other option kept as it was."""
    kept = [o for o in tool_options.split() if not o.startswith("-XX:StartFlightRecording")]
    return " ".join([*kept, recording])


def profile_overlay(source: Path, target: Path, jfr_dir: Path, canary: str) -> None:
    """L1's overlay plus the app's recording and the canary; nothing else of L1's instance changes."""
    doc = yaml.safe_load(source.read_text(encoding="utf-8"))
    app = doc.setdefault("services", {}).setdefault("app", {})
    environment = app.setdefault("environment", {})
    if not isinstance(environment, dict):
        raise Refused("the L1 overlay's app environment is no longer a mapping — teach profile_overlay its new shape")
    # Appended, never replacing: L1's overlay already passes the JVM the run's truststore through the same variable.
    environment["JAVA_TOOL_OPTIONS"] = with_recording(environment.get("JAVA_TOOL_OPTIONS", ""), jfr_options("run.jfr"))
    environment[CANARY_VAR] = canary
    app.setdefault("volumes", []).append(f"{jfr_dir}:{JFR_MOUNT}")
    target.write_text("# Written by scripts/profile_hot_paths.py: tests/e2e/stack/docker-compose.e2e.yml plus the app's "
                      "JFR recording and the canary it must not contain.\n" + yaml.safe_dump(doc, sort_keys=False),
                      encoding="utf-8")


def app_container(compose: list[str]) -> str:
    return sh([*compose, "ps", "-aq", "app"], env=stack.name_only_env()).stdout.strip()


def stop_app(compose: list[str], recording: Path) -> int:
    """Stop the app gracefully (JFR dumps on exit) and return the recording's size."""
    sh(["docker", "stop", "-t", "120", app_container(compose)])
    if not recording.is_file() or recording.stat().st_size == 0:
        raise Refused(f"the app stopped but left no recording at {recording.relative_to(ROOT)}")
    return recording.stat().st_size


def wait_healthy(compose: list[str], timeout: float = 600) -> float:
    start = time.monotonic()
    while time.monotonic() - start < timeout:
        health = sh(["docker", "inspect", "--format", "{{.State.Health.Status}}", app_container(compose)],
                    check=False).stdout.strip()
        if health == "healthy":
            return time.monotonic() - start
        time.sleep(2)
    raise Refused(f"the recreated app was not healthy within {timeout:.0f}s")


def control_recording(installed: stack.Installed, recording: Path) -> None:
    """The same recording minus the filter: the instance's override file gains the unfiltered options, the app is
    recreated with them, and stopped once healthy — the recording the filter is measured against."""
    override = installed.home / "compose.override.yml"
    doc = yaml.safe_load(override.read_text(encoding="utf-8"))
    environment = doc["services"]["app"]["environment"]
    environment["JAVA_TOOL_OPTIONS"] = with_recording(environment["JAVA_TOOL_OPTIONS"], jfr_options(recording.name, filtered=False))
    override.write_text(yaml.safe_dump(doc, sort_keys=False), encoding="utf-8")
    sh([*installed.compose, "up", "-d", "--no-deps", "--force-recreate", "app"], env=stack.name_only_env())
    say(f"control: app recreated without the filter, healthy after {wait_healthy(installed.compose):.0f}s")
    stop_app(installed.compose, recording)


def hits(path: Path, needles: dict[str, str]) -> list[str]:
    """The names of the needles whose bytes occur in the file. Raw bytes on purpose: JFR writes an ASCII string as its
    bytes in each of its string encodings, and a trace or profile is JSON."""
    data = path.read_bytes()
    return [name for name, value in needles.items() if value.encode("utf-8") in data]


def env_secrets(env: Path) -> dict[str, str]:
    found = {}
    for line in env.read_text(encoding="utf-8").splitlines():
        m = stack.ASSIGNMENT.match(line)
        if m and SECRET_NAME.search(m.group(1)) and len(m.group(2)) >= 8 and not re.search(r"[/:]", m.group(2)):
            found[m.group(1)] = m.group(2)
    return found


# ---------------------------------------------------------------- the database's counters


def counters(compose: list[str]) -> dict[str, int]:
    """MariaDB's global statement counters, read with the container's own root password (never on this host's argv)."""
    names = ",".join(f"'{n}'" for n in STATUS_VARS)
    out = sh([*compose, "exec", "-T", "mariadb", "sh", "-c",
              f"mariadb -uroot -p\"$MARIADB_ROOT_PASSWORD\" -N -B -e \"SHOW GLOBAL STATUS WHERE Variable_name IN ({names})\""],
             env=stack.name_only_env()).stdout
    return {name: int(value) for name, value in (line.split("\t") for line in out.strip().splitlines())}


def statements(before: dict[str, int], after: dict[str, int]) -> int:
    return sum(after[n] - before[n] for n in STATEMENTS)


# ---------------------------------------------------------------- the browser leg


def browser_run(compose: list[str], workdir: Path, scenario: str, run: int, throttle: int) -> dict:
    stem = f"{scenario}-t{throttle}-r{run}"
    (workdir / "profile-spec.json").write_text(json.dumps(
        {"scenario": scenario, "run": run, "throttle": throttle, "stem": stem}), encoding="utf-8")
    cmd = [*compose, "run", "--rm", "--no-deps", "-T", "-e", f"VEYQ_PROFILE_DOMAIN={stack.DOMAIN}",
           "browser", "-c", str(PROFILE_CONFIG), SCENARIOS[scenario]]
    with (workdir / "profile-browser.log").open("a", encoding="utf-8") as log:
        log.write(f"\n== {stem}\n")
        log.flush()
        status = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT, text=True).returncode
    window = workdir / f"{stem}.window.json"
    if status != 0 or not window.is_file():
        raise Refused(f"{stem}: Playwright exited {status}" + ("" if window.is_file() else " and wrote no window")
                      + " — profile-browser.log in the run directory says why")
    return {"stem": stem, **json.loads(window.read_text(encoding="utf-8"))}


def client_files_clean(workdir: Path, stem: str, owner: dict) -> None:
    needles = {"password": owner["password"], "Secret Key": owner["secretKey"]}
    for suffix in (".cpuprofile", ".trace.json", ".window.json"):
        found = hits(workdir / f"{stem}{suffix}", needles)
        if found:
            raise Refused(f"{stem}{suffix} contains the owner's {' and '.join(found)} — the file is not one to keep")


# ---------------------------------------------------------------- the run's record


def meta(args: argparse.Namespace, load: float, canary_var: str) -> dict:
    dirty = sh(["git", "status", "--porcelain", "--untracked-files=no"], cwd=ROOT).stdout.strip() != ""
    docker = sh(["docker", "info", "--format", "{{.NCPU}} {{.MemTotal}} {{.ServerVersion}}"]).stdout.split()
    machine = {"platform": platform.platform(), "machine": platform.machine(), "cores": os.cpu_count()}
    if sys.platform == "darwin":
        machine["cpu"] = sh(["sysctl", "-n", "machdep.cpu.brand_string"], check=False).stdout.strip()
        machine["memory"] = int(sh(["sysctl", "-n", "hw.memsize"], check=False).stdout.strip() or 0)
    return {
        "cr": "cr-profile-where-the-time-goes",
        "date": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "commit": sh(["git", "rev-parse", "HEAD"], cwd=ROOT).stdout.strip() + ("+dirty" if dirty else ""),
        "machine": machine,
        "dockerVm": {"cpus": int(docker[0]), "memory": int(docker[1]), "engine": docker[2]},
        "images": {name: sh(["docker", "image", "inspect", "--format", "{{.Id}}", ref]).stdout.strip()
                   for name, ref in (("app", args.app), ("admin", args.admin))},
        "load1AtStart": load,
        "quiet": load <= LOAD_LIMIT,
        "scenario": args.scenario,
        "runs": args.runs,
        "throttles": args.throttles,
        "jfr": {"run": jfr_options("run.jfr"), "control": jfr_options("control.jfr", filtered=False),
                "canaryVariable": canary_var},
        "traceCategories": "tests/e2e/profile/profiling.ts TRACE_CATEGORIES",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--app", required=True, help="the candidate app image (a local build's bare name:tag)")
    parser.add_argument("--admin", required=True, help="the candidate admin image")
    parser.add_argument("--scenario", choices=sorted(SCENARIOS), default="s1")
    parser.add_argument("--runs", type=int, default=3, help="runs per throttle (decision 2: 3)")
    parser.add_argument("--throttles", type=lambda s: [int(x) for x in s.split(",")], default=[1, 4],
                        help="CPU throttle rates, comma-separated (decision 2: 1,4)")
    parser.add_argument("--keep", action="store_true", help="leave the instance running (no teardown)")
    parser.add_argument("--ignore-load", action="store_true",
                        help="profile on a busy machine anyway; the run is marked quiet: false")
    args = parser.parse_args()

    load = os.getloadavg()[0]
    if load > LOAD_LIMIT and not args.ignore_load:
        say(f"REFUSED: the 1-minute load is {load:.2f}, above {LOAD_LIMIT} — a reading on a busy machine is the other "
            "processes' (wait, or --ignore-load to take it marked quiet: false)")
        return 2
    stamp = time.strftime("%Y%m%d-%H%M%S")
    workdir = PROFILES / stamp
    jfr_dir = workdir / "jfr"
    jfr_dir.mkdir(parents=True)
    jfr_dir.chmod(0o777)  # the app writes as the image's uid 10001, not as this host's user
    project = f"veyq-prof-{stamp}"
    canary = f"veyq-profile-canary-{secrets.token_hex(12)}"
    os.environ.setdefault("VEYQ_STACK_WAIT_SECS", "900")
    os.environ.setdefault("VEYQ_STACK_CERT_SECS", "300")
    say(f"profile run {stamp}: {args.scenario}, {args.runs} run(s) at throttle {args.throttles}, load {load:.2f}; "
        f"files in {workdir.relative_to(ROOT)}")

    verdicts: list[str] = []
    started = False
    installed = None
    before = None
    try:
        before = stack.host_snapshot()
        stack.ports_free()
        stack.browser_pins()
        stack.browser_image()
        release = stack.assets(args.app, args.admin, workdir)
        mirror = stack.release_mirror(release, args.app, args.admin, workdir)
        values = stack.run_values(project, workdir, mirror)
        overlay = workdir / "profile-overlay.yml"
        profile_overlay(stack.OVERLAY, overlay, jfr_dir, canary)
        stack.OVERLAY = overlay  # what the pebble and install stages lay over the recipe (install keeps it)
        started = True
        pebble_id = stack.pebble(release, workdir, values, args.app)
        installed = stack.install(release, workdir, values, pebble_id)
        stack.certificate(workdir)

        say("\n== bootstrap: L1's setup and scenarios projects (first run, tenant, owner active, one Memory)")
        stack.browser(installed.compose, workdir, mirror, ["--project=scenarios"])
        owner = json.loads((workdir / "instance.json").read_text(encoding="utf-8"))["owner"]

        say(f"\n== idle drift: the statement counters over {IDLE_SECS}s with nothing driving the instance")
        idle_from = counters(installed.compose)
        time.sleep(IDLE_SECS)
        idle = statements(idle_from, counters(installed.compose)) / IDLE_SECS
        server = {"idleStatementsPerSec": idle, "runs": []}

        for throttle in args.throttles:
            for run in range(1, args.runs + 1):
                c0 = counters(installed.compose)
                window = browser_run(installed.compose, workdir, args.scenario, run, throttle)
                c1 = counters(installed.compose)
                client_files_clean(workdir, window["stem"], owner)
                secs = (window["endedAt"] - window["startedAt"]) / 1000
                count = statements(c0, c1)
                api = len(window["apiRequests"])
                estimate = (count - idle * secs) / api if api else None
                server["runs"].append({"stem": window["stem"], "startedAt": window["startedAt"],
                                       "endedAt": window["endedAt"], "statements": count, "apiRequests": api,
                                       "statementsPerRequest": estimate,
                                       "counters": {n: c1[n] - c0[n] for n in STATUS_VARS}})
                say(f"{window['stem']}: {window['operationMs']:.0f} ms, {api} API request(s), {count} statement(s)"
                    + (f" ≈ {estimate:.1f} per request" if estimate is not None else ""))
        (workdir / "server.json").write_text(json.dumps(server, indent=2), encoding="utf-8")

        say("\n== the recording: the app stops, JFR dumps")
        needles = {CANARY_VAR: canary, **env_secrets(installed.env)}
        size = stop_app(installed.compose, jfr_dir / "run.jfr")
        found = hits(jfr_dir / "run.jfr", needles)
        say(f"run.jfr: {size:,} bytes; of {', '.join(needles)}: {', '.join(found) or 'none'} found")
        if found:
            raise Refused(f"the run's recording contains {', '.join(found)} — the filter does not hold")
        verdicts.append(f"bite, filtered: run.jfr holds no canary and none of the .env's {len(needles) - 1} secrets")

        say("\n== the control: the same recording without the filter")
        control_recording(installed, jfr_dir / "control.jfr")
        found = hits(jfr_dir / "control.jfr", needles)
        say(f"control.jfr: of {', '.join(needles)}: {', '.join(found) or 'none'} found")
        if CANARY_VAR not in found:
            raise Refused("the control recording does not contain the canary — the check would pass on any recording")
        verdicts.append(f"bite, control: control.jfr holds the canary (and {', '.join(f for f in found if f != CANARY_VAR) or 'no secret'})")

        (workdir / "meta.json").write_text(json.dumps(meta(args, load, CANARY_VAR), indent=2), encoding="utf-8")
    except (Refused, stack.Red) as error:
        say(f"\nREFUSED: {error}")
        verdicts.append(f"REFUSED: {error}")
    finally:
        if started:
            stack.evidence(project, workdir)
            if not args.keep:
                stack.teardown(project, workdir)
        if before is not None:
            try:
                stack.host_untouched(before, stack.host_snapshot(), project, started and not args.keep)
            except stack.Red as error:
                verdicts.append(f"REFUSED: {error}")

    say(f"\n== {project}: files in {workdir.relative_to(ROOT)}" + (" (instance kept running)" if args.keep else ""))
    for verdict in verdicts:
        say(f"  {verdict}")
    return 1 if any(v.startswith("REFUSED") for v in verdicts) else 0


if __name__ == "__main__":
    sys.exit(main())
