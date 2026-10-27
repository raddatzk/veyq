#!/usr/bin/env python3
"""Where does the time go? One profile per hot path, client and server, on the release candidate (cr-profile-where-the-time-goes).

A READING, never a check: nothing it records can turn red because the product got slower, and it makes no coverage
claim (ADR 0040). Run by hand on one quiet machine; no CI job runs it. What it can refuse is a run whose files would be
unsafe or meaningless — a busy machine, a profile file holding a secret, a recording whose secret filter is unarmed.

    profile_hot_paths.py --app IMG --admin IMG [--scenario s1,s2,s3,crypto] [--runs 3] [--throttles 1,4] [--keep] [--ignore-load]
                         [--no-cpu-profile]

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

**The method.**
  - **Client.** Chromium through Playwright, in the Playwright image the overlay pins, on the instance's networks
    (`tests/e2e/profile/`). Per run, a fresh context; what the operation needs typed is typed first, then the CPU
    profile of the main thread (CDP `Profiler`, 100 µs), a performance trace of every thread (the workers included) and
    the CPU throttle start, the operation runs, and all three stop. `--runs` runs at each of `--throttles`, one
    Playwright invocation per run, so the server side can be read per run. The machine idle at the start: the 1-minute
    load at most 3, or the run is refused (`--ignore-load` takes it anyway and marks it `quiet: false`).
  - **Without the CPU profiler** (`--no-cpu-profile`, inbox #554): the same runs with CDP's `Profiler` left out, so no
    `<stem>.cpuprofile`; the trace and the throttle are unchanged. The profiler samples the page's main thread every
    100 µs and never a worker, so its overhead lands on the main thread alone, and a profiled total credits a move into a
    worker with that overhead too. The unprofiled total is the one to compare across such a move; the profiled run is
    the one that says where the time went. The switch's own control is one profiled run beside the unprofiled ones,
    whose total must be the larger. The trace's `disabled-by-default-v8.cpu_profiler` category still samples every
    thread, so this removes the main thread's profiler, not all sampling.
  - **Server.** JFR with the `profile` settings, its execution and allocation SAMPLES — no heap dump on either side —
    and the events that record the process environment and the system properties OFF (`jdk.InitialEnvironmentVariable`,
    `jdk.InitialSystemProperty`): the recipe hands the app `QUARKUS_DATASOURCE_PASSWORD` and the store's admin token in
    its environment. One recording spans boot, bootstrap and every run; each run's window (`<stem>.window.json`, epoch
    milliseconds) cuts it to the same moment. And MariaDB's statement counters around each run, against an idle drift
    measured before the first, beside the API requests the page made in the window: statements per request.
  - **Failed requests.** The page records every request of the window that got no answer and every answer of 400 or
    more (`<stem>.window.json`'s `failedRequests` and `httpErrors`); the driver judges them (`request_failures`): an
    error answer, a request that failed in the network and a `net::ERR_ABORTED` each refuse the run. The aborted ones
    are also named apart, in the refusal and as `abortedRequests` in server.json, because they are the ones to tell
    apart when reading why: the collection run's F7 (S2's store PUTs, a listing GET) was Chromium reporting an answer
    whose empty body the page never read as `ERR_ABORTED` — a product defect, fixed in the client — so an abort is a
    finding, not noise. Whether a deliberate abort (a superseded listing the page cancels) may ever pass is the owner's
    call; `ABORTED_REFUSES` is the one place it is taken.
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
device, and it reaches the page's main thread only, never a worker: at the V1 heavy stage the Argon2id worker took
5,354 ms at throttle 1 and 5,413 ms at 4 while the main thread rose ×4.5, so a throttle-4 number is a slower main
thread, and a worker's share of it runs at desktop speed.

**The scenarios** (`--scenario` takes several, comma-separated, run one after another on ONE instance, so their
readings share its seed):
  - `s1` unlock: a new device signs in and opens the space (`s1-unlock.profile.ts`).
  - `s2` upload: 50 phone-sized photos into one Chapter, a fresh Memory per run (`s2-upload.profile.ts`); the window's
    store requests are the wire (#261).
  - `s3` open a 1,000-photo Chapter (`s3-open-chapter.profile.ts`), seeded once before its first run by
    `s3-seed.profile.ts` through the browser, unprofiled.
  - `crypto`: the crypto micro-reading, AES-256-GCM through `@noble/ciphers` against WebCrypto over 4 MB and 64 MB
    (`crypto-aead.profile.ts`, its numbers in `<stem>.crypto.json`).
  The photos S2 and S3 upload are synthetic: ffmpeg's `testsrc2` with temporal noise, rendered on this host into the run
  directory's `fixtures/` before the first run (`render_photos`), so ffmpeg must be on the PATH for those two.
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
from urllib.parse import urlsplit

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
SCENARIOS = {"s1": "s1-unlock", "s2": "s2-upload", "s3": "s3-open-chapter", "crypto": "crypto-aead"}
# What a scenario needs on the instance before its first run, run once and never profiled.
SEEDS = {"s3": "s3-seed"}
# The synthetic photos per scenario: how many, their size, and ffmpeg's JPEG quality — S2's a phone's (12 MP, about 4 MB),
# S3's smaller, since S3 reads the grid's thumbnails and not the originals.
PHOTOS = {"s2": (50, "4032x3024", 2), "s3": (1000, "1280x960", 4)}
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
# Whether a request Chromium reports `net::ERR_ABORTED` refuses a run like any other failed request: yes, strict. The
# collection run's aborts (2026-10-23, F7) were the client leaving an empty answer's body unread — a defect — so an abort
# is ADR 0105's "failed request" until the owner decides that a deliberate one (a superseded GET the page cancels) may
# pass. Either way it is recorded apart (`abortedRequests` in server.json). The one place to change it.
ABORTED_REFUSES = True
ABORTED = "net::ERR_ABORTED"
# The one answer of 400 or more that is no error: the signed-out boot's `POST /api/token/refresh` (401 by design,
# src/web/src/app/sessionRefresh.ts). One definition, which tests/e2e/src/devices.ts `watchConsole` reads too.
SIGNED_OUT_BOOT = json.loads((repo_layout.E2E_DIR / "src" / "signedOutBoot.json").read_text(encoding="utf-8"))


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


def render_photos(workdir: Path, scenario: str) -> int:
    """The scenario's synthetic photos in `fixtures/<scenario>/` of the run directory (the browser's /out): a test
    pattern with temporal noise, so no two frames are alike (the upload deduplicates identical content), bit-exact and
    without metadata. Returns the bytes rendered."""
    count, size, quality = PHOTOS[scenario]
    target = workdir / "fixtures" / scenario
    target.mkdir(parents=True, exist_ok=True)
    sh(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"testsrc2=size={size}:rate=1,noise=alls=12:allf=t",
        "-frames:v", str(count), "-q:v", str(quality), "-map_metadata", "-1", "-fflags", "+bitexact",
        "-flags:v", "+bitexact", str(target / f"{scenario}-%04d.jpg")])
    rendered = sorted(target.glob("*.jpg"))
    if len(rendered) != count:
        raise Refused(f"ffmpeg rendered {len(rendered)} photos for {scenario}, not {count}")
    return sum(f.stat().st_size for f in rendered)


def seed_run(compose: list[str], workdir: Path, scenario: str) -> None:
    """The scenario's seed, once, unprofiled: the same browser leg with the seed's spec file."""
    seed = SEEDS[scenario]
    (workdir / "profile-spec.json").write_text(json.dumps(
        {"scenario": seed, "run": 0, "throttle": 1, "stem": seed, "cpuProfile": False}), encoding="utf-8")
    cmd = [*compose, "run", "--rm", "--no-deps", "-T", "-e", f"VEYQ_PROFILE_DOMAIN={stack.DOMAIN}",
           "browser", "-c", str(PROFILE_CONFIG), seed]
    started = time.monotonic()
    with (workdir / "profile-browser.log").open("a", encoding="utf-8") as log:
        log.write(f"\n== {seed}\n")
        log.flush()
        status = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT, text=True).returncode
    if status != 0:
        raise Refused(f"{seed}: Playwright exited {status} — profile-browser.log in the run directory says why")
    say(f"{seed}: seeded in {time.monotonic() - started:.0f}s")


def browser_run(compose: list[str], workdir: Path, scenario: str, run: int, throttle: int, cpu_profile: bool) -> dict:
    stem = f"{scenario}-t{throttle}-r{run}"
    (workdir / "profile-spec.json").write_text(json.dumps(
        {"scenario": scenario, "run": run, "throttle": throttle, "stem": stem, "cpuProfile": cpu_profile}),
        encoding="utf-8")
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
    recorded = {"stem": stem, **json.loads(window.read_text(encoding="utf-8"))}
    # The switch's own check: the run was taken the way it was asked, by the window's word and by the files it left.
    profiled_file = (workdir / f"{stem}.cpuprofile").is_file()
    if recorded.get("cpuProfile", True) != cpu_profile or profiled_file != cpu_profile:
        raise Refused(f"{stem}: asked for cpuProfile={cpu_profile}, the window says {recorded.get('cpuProfile')} and a "
                      f".cpuprofile {'exists' if profiled_file else 'does not exist'}")
    return recorded


def signed_out_boot(entry: str) -> bool:
    """Whether an `httpErrors` entry (`METHOD origin+path status`) is the signed-out boot's refresh ([SIGNED_OUT_BOOT])."""
    parts = entry.split()
    if len(parts) != 3 or not parts[2].isdigit():
        return False
    method, url, status = parts
    path = urlsplit(url).path
    return (method == SIGNED_OUT_BOOT["method"] and path == SIGNED_OUT_BOOT["path"]
            and int(status) in SIGNED_OUT_BOOT["statuses"])


def request_failures(window: dict, aborted_refuses: bool = ABORTED_REFUSES) -> tuple[list[str], list[str]]:
    """The window's requests that refuse the run, and its aborted ones, recorded beside it.

    An answer of 400 or more refuses, and so does a request that failed in the network (a refused connection, a name
    that did not resolve, a TLS failure) — except the signed-out boot's refresh ([signed_out_boot]), which answers 401 by
    design. A `net::ERR_ABORTED` refuses only when [aborted_refuses] says so (`ABORTED_REFUSES`). A window
    written before `httpErrors` existed has none."""
    failed = list(window.get("failedRequests", []))
    aborted = [r for r in failed if r.rstrip().endswith(ABORTED)]
    refusing = [r for r in failed if r not in aborted] + [
        r for r in window.get("httpErrors", []) if not signed_out_boot(r)]
    if aborted_refuses:
        refusing += aborted
    return refusing, aborted


def client_files_clean(workdir: Path, stem: str, owner: dict, cpu_profile: bool) -> None:
    needles = {"password": owner["password"], "Secret Key": owner["secretKey"]}
    for suffix in ((".cpuprofile",) if cpu_profile else ()) + (".trace.json", ".window.json"):
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
        "cpuProfile": not args.no_cpu_profile,
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
    parser.add_argument("--scenario", type=lambda s: s.split(","), default=["s1"],
                        help=f"scenarios, comma-separated, run in this order on one instance: {', '.join(SCENARIOS)}")
    parser.add_argument("--runs", type=int, default=3, help="runs per throttle")
    parser.add_argument("--throttles", type=lambda s: [int(x) for x in s.split(",")], default=[1, 4],
                        help="CPU throttle rates, comma-separated")
    parser.add_argument("--keep", action="store_true", help="leave the instance running (no teardown)")
    parser.add_argument("--ignore-load", action="store_true",
                        help="profile on a busy machine anyway; the run is marked quiet: false")
    parser.add_argument("--no-cpu-profile", action="store_true",
                        help="leave the main thread's CPU profiler out (the trace stays): totals free of its overhead")
    args = parser.parse_args()
    unknown = [s for s in args.scenario if s not in SCENARIOS]
    if unknown:
        parser.error(f"unknown scenario(s) {', '.join(unknown)}; choose from {', '.join(SCENARIOS)}")

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
    say(f"profile run {stamp}: {', '.join(args.scenario)}, {args.runs} run(s) at throttle {args.throttles}, load {load:.2f}, "
        f"{'without' if args.no_cpu_profile else 'with'} the CPU profiler; files in {workdir.relative_to(ROOT)}")

    verdicts: list[str] = []
    started = False
    installed = None
    before = None
    try:
        before = stack.host_snapshot()
        for scenario in (s for s in args.scenario if s in PHOTOS):
            say(f"{scenario}: {render_photos(workdir, scenario) / 1048576:,.0f} MiB of synthetic photos rendered")
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

        for scenario in args.scenario:
            if scenario in SEEDS:
                seed_run(installed.compose, workdir, scenario)
            for throttle in args.throttles:
                for run in range(1, args.runs + 1):
                    c0 = counters(installed.compose)
                    window = browser_run(installed.compose, workdir, scenario, run, throttle, not args.no_cpu_profile)
                    c1 = counters(installed.compose)
                    client_files_clean(workdir, window["stem"], owner, not args.no_cpu_profile)
                    refusing, aborted = request_failures(window)
                    if refusing:
                        raise Refused(f"{window['stem']}: {len(refusing)} request(s) of the window failed, "
                                      f"{len(aborted)} of them {ABORTED} - "
                                      + "; ".join(refusing[:5]) + (" ..." if len(refusing) > 5 else ""))
                    secs = (window["endedAt"] - window["startedAt"]) / 1000
                    count = statements(c0, c1)
                    api = len(window["apiRequests"])
                    estimate = (count - idle * secs) / api if api else None
                    server["runs"].append({"stem": window["stem"], "startedAt": window["startedAt"],
                                           "endedAt": window["endedAt"], "statements": count, "apiRequests": api,
                                           "statementsPerRequest": estimate,
                                           "abortedRequests": len(aborted),
                                           "counters": {n: c1[n] - c0[n] for n in STATUS_VARS}})
                    say(f"{window['stem']}: {window['operationMs']:.0f} ms, {api} API request(s), {count} statement(s)"
                        + (f" ≈ {estimate:.1f} per request" if estimate is not None else "")
                        + (f"; {len(aborted)} aborted request(s), recorded ({ABORTED})" if aborted else ""))
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
