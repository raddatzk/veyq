#!/usr/bin/env python3
"""The volume harness's driver: does a small host carry a veyq instance, and up to what volume (cr-volume-measurement-local).

TEMPORARY. `cr-volume-measurement-cax` runs it once more against a real CAX11, writes the sizing recommendation into the
Run area and deletes it with the rest of the harness (`web/scripts/seed-volume.mts`, `web/scripts/measure-volume.mts`,
`web/scripts/volumeHarness.mts`, `web/scripts/volumePrelude.mts`, `e2e/measure/`, the bench files).

**What it measures on.** A throwaway instance of the PRODUCTION recipe, never the dev stack: `boot` installs the
candidate images the way lane L1 does — `scripts/run_stack_e2e.py`'s own stages (assets, release mirror, Pebble,
`veyqctl install --unsigned-local`, certificate), imported and called, stopped before L1's browser suite would set the
instance up — and lays a measurement overlay over L1's (`measure-overlay.yml` in the instance directory, which
`veyqctl` keeps as the instance's `compose.override.yml`): the recipe's five services pinned together to two cores
(`cpuset`), the test doubles (Pebble, Mailpit, the release mirror, the browser) on the other cores, and Mailpit's API
published on loopback for the member seeder. Memory is not capped as a whole — Compose has no group limit — so the
4 GB host is a JUDGED budget: the recipe's containers' summed memory against 80 % of it (Q1). The app keeps the
recipe's own `mem_limit`. The instance directory holds everything the instance owns, generated test credentials
included (`accounts.json`); readings go to `.run/measurements/` (gitignored). Neither ever enters git.

    measure_volume.py --instance DIR fixtures                      the JPEG/WebP fixtures the seeders upload
    measure_volume.py --instance DIR boot --app IMG --admin IMG    a fresh instance (ports 80/443 must be free)
    measure_volume.py --instance DIR seed <seed-volume args>       web/scripts/seed-volume.mts against it
    measure_volume.py --instance DIR invite-limit <N|default>      the per-tenant invite limit while seeding members, app recreated
    measure_volume.py --instance DIR server <axis>                 the server leg, with the sampler
    measure_volume.py --instance DIR browser <axis> [...]          the browser leg, e2e/measure/volume.ts
    measure_volume.py --instance DIR scenario <idle|five|upload>   a household scenario, with the sampler
    measure_volume.py --instance DIR stage <axis> <N>              seed to N, then the server and the browser leg
    measure_volume.py --instance DIR sample --seconds S            the sampler alone
    measure_volume.py --instance DIR oom-bite                      the sampler's bite: a tiny memory limit, live
    measure_volume.py --instance DIR status | teardown
    measure_volume.py judge <readings dir>                         Q1's rule over one stage's readings

Axes: `photos` (the 'All photos' Chapter), `bare` (the 'No derivatives' Chapter, a bite's target), `tree` (the
realistic group), `wide` (one Memory holding every Chapter), `members` (the tenant).

**The sampler** runs its commands through `--prefix` (empty here, `ssh <host>` on the CAX): `docker stats`
streamed for the project's containers (about one frame a second: CPU %, memory), `docker events` for the project (an
OOM kill, a die, a restart is an event, never a quiet gap), and a host probe — locally a busybox container on the
outside cores reading the Docker VM's `/proc` (load, MemAvailable), remotely the host's own. Locally it also records
this Mac's load, the quietness the readings depend on.

**Its limits.** Locally the two cores are two of the Docker VM's vCPUs, which macOS schedules on any core it likes, an
M4 Pro core being much faster than an Ampere one: a local reading is a pre-check, never a sizing answer. The query
count is MariaDB's GLOBAL `Com_select`, so it counts every client — the leg measures the idle drift around its calls and
refuses a reading when the drift exceeds the instance's idle baseline (a browser left open, a sweep). Times are taken
in the Node client on this host: they include TLS and the loopback, not a real network.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import shutil
import signal
import socket
import ssl
import statistics
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "web"
E2E = ROOT / "e2e"
READINGS = ROOT / ".run" / "measurements"
DOMAIN = "veyq.test"  # run_stack_e2e.DOMAIN: Pebble's certificate, Traefik's alias, the tenant's domain
CONNECT = ("127.0.0.1", 443)
SMTP_FOR_APP = "mail.relay.test:1025"  # the overlay's Mailpit, as the app reaches it on the `admin` network
RECIPE = ("app", "mariadb", "garage", "traefik", "admin")
DOUBLES = ("pebble", "mailpit", "release-mirror", "browser")
INVITE_ENV = "VEYQ_RATELIMIT_INVITE_PER_TENANT_MAX_REQUESTS"
GIB = 1024 ** 3
PROBE_IMAGE = "busybox:1.38.0@sha256:fd7dc98638c8e305f4dc34e979f1c0fdfdcaeb0fbf8fcff77ae834b6da3d7e6e"  # the recipe's own
PROBE_LOOP = ('while :; do echo "$(date +%s) $(cut -d" " -f1-3 /proc/loadavg) '
              '$(grep -E "^(MemTotal|MemAvailable):" /proc/meminfo | tr -s " " | cut -d" " -f2 | tr "\\n" " ")"; '
              'sleep 1; done')
ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")


class Refused(Exception):
    """A reading the harness will not take, or a step it will not run, and why."""


def say(msg: str) -> None:
    print(msg, flush=True)


def run(cmd: list[str], check: bool = True, capture: bool = True, **kw: Any) -> subprocess.CompletedProcess:
    result = subprocess.run(cmd, text=True, capture_output=capture, **kw)
    if check and result.returncode != 0:
        raise Refused(f"`{' '.join(cmd[:5])} …` exited {result.returncode}: {(result.stderr or '').strip()[-1500:]}")
    return result


def name_only_env() -> dict[str, str]:
    return {k: v for k, v in os.environ.items() if k not in ("COMPOSE_FILE", "COMPOSE_PROJECT_NAME")}


def mac_load() -> float:
    return os.getloadavg()[0]


def uptime_line(prefix: list[str]) -> str:
    return run([*prefix, "uptime"], check=False).stdout.strip()


# ---------------------------------------------------------------- the instance


class Instance:
    """The throwaway instance `boot` left, as `instance.json` in its directory describes it."""

    def __init__(self, directory: Path, prefix: list[str]):
        self.dir = directory
        self.prefix = prefix
        state = directory / "instance.json"
        if not state.is_file():
            raise Refused(f"no instance at {directory} (no instance.json) — `boot` one first")
        self.state = json.loads(state.read_text(encoding="utf-8"))
        self.project: str = self.state["project"]
        self.compose: list[str] = self.state["compose"]
        self.home = Path(self.state["home"])

    def docker(self, *args: str, check: bool = True, **kw: Any) -> subprocess.CompletedProcess:
        return run([*self.prefix, "docker", *args], check=check, **kw)

    def container(self, service: str) -> str:
        out = self.docker("ps", "-aq", "--filter", f"label=com.docker.compose.project={self.project}",
                          "--filter", f"label=com.docker.compose.service={service}").stdout.split()
        if len(out) != 1:
            raise Refused(f"{len(out)} containers for service {service} of {self.project}, not one")
        return out[0]

    def names(self, services: tuple[str, ...]) -> dict[str, str]:
        """service -> container name, for the services of `services` that run."""
        out = self.docker("ps", "--filter", f"label=com.docker.compose.project={self.project}", "--format",
                          '{{.Label "com.docker.compose.service"}} {{.Names}}').stdout.split("\n")
        pairs = dict(line.split(" ", 1) for line in out if line.strip())
        return {s: pairs[s] for s in services if s in pairs}

    def env(self) -> dict[str, str]:
        """What web/scripts/volumeHarness.mts reads (seed-volume.mts' header lists it)."""
        return {
            **os.environ,
            "VEYQ_HOST": DOMAIN,
            "VEYQ_BASE": f"https://{DOMAIN}",
            "VEYQ_ADMIN_BASE": f"http://127.0.0.1:{self.state['admin_port']}",
            "VEYQ_MAILPIT": f"http://127.0.0.1:{self.state['mailpit_port']}",
            "VEYQ_STAMP": self.state["stamp"],
            "VEYQ_MEASURE_DIR": str(self.dir),
            "VEYQ_MEASURE_CONNECT": f"{CONNECT[0]}:{CONNECT[1]}",
            "VEYQ_MEASURE_SMTP": SMTP_FOR_APP,
        }

    def com_select(self) -> int:
        """MariaDB's global SELECT counter, read with the container's own root password (never on this host's argv)."""
        out = self.docker("exec", self.container("mariadb"), "sh", "-c",
                          "mariadb -uroot -p\"$MARIADB_ROOT_PASSWORD\" -N -B -e \"SHOW GLOBAL STATUS LIKE 'Com_select'\"").stdout
        return int(out.split()[-1])

    def setup_status(self) -> int:
        """GET https://<domain>/api/setup over the pinned connection, trusting the run's Pebble root only."""
        ctx = ssl.create_default_context(cafile=str(self.dir / "pebble-root.pem"))
        try:
            with socket.create_connection(CONNECT, timeout=5) as raw:
                with ctx.wrap_socket(raw, server_hostname=DOMAIN) as tls:
                    tls.sendall(f"GET /api/setup HTTP/1.1\r\nHost: {DOMAIN}\r\nConnection: close\r\n\r\n".encode())
                    line = tls.recv(64).decode("latin-1").split("\r\n", 1)[0]
            return int(line.split()[1])
        except (OSError, ssl.SSLError, IndexError, ValueError):
            return 0

    def wait_healthy(self, timeout: float = 600) -> float:
        start = time.monotonic()
        app = self.container("app")
        while time.monotonic() - start < timeout:
            health = self.docker("inspect", "--format", "{{.State.Health.Status}}", app, check=False).stdout.strip()
            if health == "healthy" and self.setup_status() == 200:
                return time.monotonic() - start
            time.sleep(1)
        raise Refused(f"the app was not healthy within {timeout:.0f}s")

    def restart_app(self) -> float:
        start = time.monotonic()
        self.docker("restart", self.container("app"))
        self.wait_healthy()
        return time.monotonic() - start


# ---------------------------------------------------------------- fixtures


def fixtures(directory: Path) -> None:
    """The bytes every seeded photo carries, made with ImageMagick: a plasma picture is photo-like enough
    for JPEG to compress it the way it compresses a photo. A tenth of the photos are portrait."""
    out = directory / "fixtures"
    out.mkdir(parents=True, exist_ok=True)
    magick = shutil.which("magick")
    if not magick:
        raise Refused("ImageMagick (`magick`) is needed for the fixtures")
    dims: dict[str, list[int]] = {}

    def jpeg(name: str, w: int, h: int, quality: int, noise: float = 0.5) -> int:
        # Plasma plus a little sensor-like noise: plain plasma is smoother than a photo and compresses too well.
        path = out / f"{name}.jpg"
        run([magick, "-seed", "7", "-size", f"{w}x{h}", "plasma:fractal", "-attenuate", str(noise), "+noise", "Gaussian",
             "-quality", str(quality), str(path)])
        dims[name] = [w, h]
        return path.stat().st_size

    for orientation, (w, h) in (("landscape", (4, 3)), ("portrait", (3, 4))):
        jpeg(f"thumb-{orientation}", 128 * w, 128 * h, 80)  # TIER_SPECS.THUMBNAIL: 512 long edge, quality 0.8
        jpeg(f"preview-{orientation}", 512 * w, 512 * h, 85)  # TIER_SPECS.PREVIEW: 2048 long edge, quality 0.85
        jpeg(f"original-{orientation}", 256 * w, 256 * h, 85)  # a small original
        # The upload scenario's phone original: 12 MP, about 4 MB — the highest quality that stays under 4.4 MB.
        for quality in (98, 96, 94, 92, 90, 88, 85, 80):
            if jpeg(f"phone-{orientation}", 1008 * w, 1008 * h, quality, noise=0.9) <= 4_400_000:
                break
    for k in range(5):
        path = out / f"avatar-{k}.webp"
        for quality in (70, 55, 40, 25):
            run([magick, "-seed", str(k + 11), "-size", "256x256", "plasma:fractal", "-quality", str(quality), str(path)])
            if path.stat().st_size <= 16_000:  # the avatar pad's budget is 16 KiB minus a 4-byte header
                break
    (out / "dims.json").write_text(json.dumps(dims, indent=2), encoding="utf-8")
    for p in sorted(out.iterdir()):
        say(f"  {p.name:<24} {p.stat().st_size:>10,} bytes")


# ---------------------------------------------------------------- boot / teardown


def measurement_overlay(source: Path, target: Path, cpuset: str, outside: str) -> None:
    """L1's overlay plus what measuring needs: the pin, the doubles off the pinned cores, Mailpit's API on loopback."""
    doc = yaml.safe_load(source.read_text(encoding="utf-8"))
    services = doc.setdefault("services", {})
    for name in RECIPE + ("volume-ownership",):
        services.setdefault(name, {})["cpuset"] = cpuset
    for name in DOUBLES:
        if name in services:
            services[name]["cpuset"] = outside
    services["mailpit"]["ports"] = ["127.0.0.1::8025"]
    # L1 turns the store's per-request log back on for its wire sweep's control; an operator's instance runs the
    # recipe's `garage=warn`, and so does the measured one — a log line per thumbnail is a cost no household pays.
    garage_env = (services.get("garage") or {}).get("environment") or {}
    garage_env.pop("RUST_LOG", None)
    if "garage" in services and not garage_env:
        services["garage"].pop("environment", None)
    target.write_text("# Written by scripts/measure_volume.py boot: infra/e2e/docker-compose.e2e.yml plus the "
                      "measurement's pins.\n" + yaml.safe_dump(doc, sort_keys=False), encoding="utf-8")


def boot(directory: Path, app: str, admin: str, cpuset: str, outside: str) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    left = [p.name for p in directory.iterdir() if p.name != "fixtures" and not p.name.startswith("torn-")]
    if left:
        raise Refused(f"{directory} still holds an instance's files ({', '.join(sorted(left)[:5])}) — `teardown` it first")
    os.environ.setdefault("VEYQ_STACK_WAIT_SECS", "900")
    os.environ.setdefault("VEYQ_STACK_CERT_SECS", "300")
    sys.path.insert(0, str(ROOT / "scripts"))
    import run_stack_e2e as stack  # noqa: E402 - lane L1's own stages, so the instance is installed as L1 installs it

    started = time.monotonic()
    stamp = time.strftime("%m%d%H%M")
    project = f"veyq-vol-{stamp}"
    try:
        stack.ports_free()
    except stack.Red as error:
        raise Refused(f"busy: {error} — another stack (an L1 lane?) holds them; wait and retry, never tear it down")
    stack.browser_pins()
    stack.browser_image()
    # The two stages print their paths relative to the repository root; this instance lives outside it.
    real_root = stack.ROOT
    stack.ROOT = Path("/")
    try:
        release = stack.assets(app, admin, directory)
        mirror = stack.release_mirror(release, app, admin, directory)
    finally:
        stack.ROOT = real_root
    values = stack.run_values(project, directory, mirror)
    overlay = directory / "measure-overlay.yml"
    measurement_overlay(stack.OVERLAY, overlay, cpuset, outside)
    stack.OVERLAY = overlay  # what the pebble and install stages lay over the recipe (install keeps it as the override)
    try:
        pebble_id = stack.pebble(release, directory, values, app)
        installed = stack.install(release, directory, values, pebble_id)
        stack.certificate(directory)
    except stack.Red as error:
        run(["docker", "compose", "-p", project, "down", "-v"], check=False, cwd=directory, env=name_only_env())
        raise Refused(f"boot failed and was torn down: {error}")
    port = run([*installed.compose, "port", "mailpit", "8025"], cwd=directory, env=name_only_env()).stdout.strip()
    state = {
        "project": project, "stamp": f"v{stamp}", "home": str(installed.home), "compose": installed.compose,
        "release": installed.release, "domain": DOMAIN, "admin_port": stack.ADMIN_PORT,
        "mailpit_port": int(port.rsplit(":", 1)[1]), "cpuset": cpuset, "outside": outside,
        "app_image": app, "admin_image": admin, "booted_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
    }
    (directory / "instance.json").write_text(json.dumps(state, indent=2), encoding="utf-8")
    say(f"boot: {project} up in {time.monotonic() - started:.0f}s at https://{DOMAIN} (127.0.0.1:443), admin "
        f"127.0.0.1:{stack.ADMIN_PORT}, Mailpit 127.0.0.1:{state['mailpit_port']}; recipe on cores {cpuset}")


def status(inst: Instance) -> dict:
    """The pins and limits as Docker holds them, per recipe container."""
    report = {}
    for service, name in inst.names(RECIPE + DOUBLES).items():
        info = json.loads(inst.docker("inspect", name).stdout)[0]
        host = info["HostConfig"]
        report[service] = {
            "cpuset": host.get("CpusetCpus"), "memory": host.get("Memory"), "memorySwap": host.get("MemorySwap"),
            "restartCount": info.get("RestartCount"), "oomKilled": info["State"].get("OOMKilled"),
            "health": (info["State"].get("Health") or {}).get("Status"), "startedAt": info["State"].get("StartedAt"),
        }
    return report


def teardown(inst: Instance) -> None:
    run(["docker", "compose", "-p", inst.project, "down", "-v"], cwd=inst.dir, env=name_only_env(), capture=False)
    # Everything the instance owned (its home, release, credentials, manifest) moves aside, so the next `boot` starts
    # from the fixtures alone and no seeder reads a dead instance's accounts or places.
    torn = inst.dir / f"torn-{inst.project}"
    torn.mkdir()
    for entry in inst.dir.iterdir():
        if entry.name != "fixtures" and not entry.name.startswith("torn-"):
            entry.rename(torn / entry.name)
    say(f"teardown: {inst.project} removed with its volumes; its files are in {torn}")


def invite_limit(inst: Instance, limit: str) -> None:
    """Decision 9, through the instance's environment as an operator sets it: the override file `veyqctl` lays over
    every command gains (or loses) the app's invite-limit variable, and `veyqctl start` recreates the app with it."""
    override = inst.home / "compose.override.yml"
    doc = yaml.safe_load(override.read_text(encoding="utf-8"))
    app = doc.setdefault("services", {}).setdefault("app", {})
    environment = app.setdefault("environment", {})
    if limit == "default":
        environment.pop(INVITE_ENV, None)
    else:
        environment[INVITE_ENV] = str(int(limit))
    override.write_text(yaml.safe_dump(doc, sort_keys=False), encoding="utf-8")
    veyqctl = inst.home / "releases" / inst.state["release"] / "veyqctl"
    run([sys.executable, str(veyqctl), "--home", str(inst.home), "start"], capture=False, stdin=subprocess.DEVNULL,
        env={**name_only_env(), "VEYQ_UNSIGNED_LOCAL": "1"})
    inst.wait_healthy()
    env = json.loads(inst.docker("inspect", inst.container("app")).stdout)[0]["Config"]["Env"]
    seen = [e for e in env if e.startswith(f"{INVITE_ENV}=")]
    say(f"invite limit: {seen[0] if seen else f'{INVITE_ENV} unset (the default, 20 per tenant per hour)'}; app healthy")


# ---------------------------------------------------------------- the sampler


def parse_bytes(text: str) -> int:
    m = re.match(r"^\s*([0-9.]+)\s*([KMGT]?i?B)\s*$", text)
    if not m:
        return 0
    unit = {"B": 1, "KiB": 1024, "MiB": 1024 ** 2, "GiB": GIB, "TiB": 1024 ** 4,
            "kB": 1000, "KB": 1000, "MB": 1000 ** 2, "GB": 1000 ** 3, "TB": 1000 ** 4}[m.group(2)]
    return int(float(m.group(1)) * unit)


class Sampler:
    """docker stats, docker events and a host probe, each a stream into a JSONL file of `out`."""

    def __init__(self, inst: Instance, out: Path, local: bool = True):
        self.inst, self.out, self.local = inst, out, local
        self.procs: list[subprocess.Popen] = []
        self.threads: list[threading.Thread] = []
        self.stopping = threading.Event()
        self.services = inst.names(RECIPE + DOUBLES)
        self.service_of = {name: s for s, name in self.services.items()}
        out.mkdir(parents=True, exist_ok=True)

    def _stream(self, cmd: list[str], file: str, parse) -> None:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1)
        self.procs.append(proc)

        def pump() -> None:
            with (self.out / file).open("a", encoding="utf-8") as f:
                assert proc.stdout is not None
                for line in proc.stdout:
                    record = parse(line)
                    if record is not None:
                        f.write(json.dumps(record) + "\n")
                        f.flush()

        t = threading.Thread(target=pump, daemon=True)
        t.start()
        self.threads.append(t)

    def _stats(self, line: str) -> dict | None:
        m = re.search(r"\{.*\}", ANSI.sub("", line))
        if not m:
            return None
        s = json.loads(m.group(0))
        used, _, limit = s.get("MemUsage", "").partition("/")
        return {"t": time.time(), "service": self.service_of.get(s.get("Name"), s.get("Name")),
                "cpu": float(s.get("CPUPerc", "0%").rstrip("%") or 0), "mem": parse_bytes(used),
                "limit": parse_bytes(limit), "pids": s.get("PIDs")}

    @staticmethod
    def _event(line: str) -> dict | None:
        try:
            e = json.loads(line)
        except ValueError:
            return None
        attrs = (e.get("Actor") or {}).get("Attributes") or {}
        return {"t": time.time(), "action": e.get("Action") or e.get("status"),
                "service": attrs.get("com.docker.compose.service"), "exitCode": attrs.get("exitCode")}

    @staticmethod
    def _host(line: str) -> dict | None:
        parts = line.split()
        if len(parts) < 6:
            return None
        total, available = int(parts[4]) * 1024, int(parts[5]) * 1024
        return {"t": time.time(), "load1": float(parts[1]), "load5": float(parts[2]), "memTotal": total,
                "memUsed": total - available}

    def start(self) -> "Sampler":
        prefix = self.inst.prefix
        self._stream([*prefix, "docker", "stats", "--format", "{{json .}}", *self.services.values()], "stats.jsonl",
                     self._stats)
        self._stream([*prefix, "docker", "events", "--filter", f"label=com.docker.compose.project={self.inst.project}",
                      "--format", "{{json .}}"], "events.jsonl", self._event)
        if self.local:
            probe = [*prefix, "docker", "run", "--rm", "-i", "--network", "none", "--cpuset-cpus",
                     self.inst.state["outside"], "--name", f"veyq-vol-probe-{os.getpid()}-{int(time.time())}",
                     "--label", "veyq.measure=probe", PROBE_IMAGE, "sh", "-c", PROBE_LOOP]
        else:
            probe = [*prefix, "sh", "-c", shlex.quote(PROBE_LOOP)]
        self._stream(probe, "host.jsonl", self._host)
        if self.local:
            def mac() -> None:
                with (self.out / "mac.jsonl").open("a", encoding="utf-8") as f:
                    while not self.stopping.wait(1.0):
                        f.write(json.dumps({"t": time.time(), "load1": mac_load()}) + "\n")
                        f.flush()
            t = threading.Thread(target=mac, daemon=True)
            t.start()
            self.threads.append(t)
        time.sleep(3)  # the first stats frame
        return self

    def stop(self) -> dict:
        self.stopping.set()
        for p in self.procs:
            p.terminate()
        for p in self.procs:
            try:
                p.wait(timeout=10)
            except subprocess.TimeoutExpired:
                p.kill()
        if self.local:  # the probe container dies with its `docker run -i`, but a stuck one would hold a core
            for cid in self.inst.docker("ps", "-q", "--filter", "label=veyq.measure=probe", check=False).stdout.split():
                self.inst.docker("rm", "-f", cid, check=False)
        summary = summarise(self.out, ram=self.inst.state.get("ram_bytes", 4 * GIB))
        (self.out / "sampler.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
        return summary


def read_jsonl(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def summarise(out: Path, ram: int) -> dict:
    stats = read_jsonl(out / "stats.jsonl")
    per: dict[str, dict] = {}
    by_second: dict[int, dict[str, int]] = {}
    for s in stats:
        p = per.setdefault(s["service"], {"samples": 0, "peakMem": 0, "peakCpu": 0.0, "cpuSum": 0.0, "limit": s["limit"]})
        p["samples"] += 1
        p["peakMem"] = max(p["peakMem"], s["mem"])
        p["peakCpu"] = max(p["peakCpu"], s["cpu"])
        p["cpuSum"] += s["cpu"]
        if s["service"] in RECIPE:
            by_second.setdefault(int(s["t"]), {})[s["service"]] = s["mem"]
    for p in per.values():
        p["meanCpu"] = round(p.pop("cpuSum") / max(p["samples"], 1), 1)
    # The recipe's memory as one number per second: each service's latest sample in that second, carried forward.
    last: dict[str, int] = {}
    sums = []
    for second in sorted(by_second):
        last.update(by_second[second])
        if all(s in last for s in RECIPE):
            sums.append(sum(last[s] for s in RECIPE))
    events = [e for e in read_jsonl(out / "events.jsonl") if e["action"] and (
        e["action"].startswith(("oom", "die", "kill", "restart")) or e["action"] == "start")]
    host = read_jsonl(out / "host.jsonl")
    mac = read_jsonl(out / "mac.jsonl")
    recipe_events = [e for e in events if e["service"] in RECIPE]
    # The cold calls restart the app on purpose (`docker restart`: kill with SIGTERM, die 143, restart). What Q1 counts is
    # every other end of a container: an OOM kill (`oom`, then die 137) or any other exit — the restart policy's own
    # start after it is not an event of its own beyond `start`.
    unbidden = [e for e in recipe_events if e["action"].startswith("oom") or (e["action"] == "die" and e["exitCode"] != "143")]
    return {
        "services": per,
        "recipePeakMem": max(sums) if sums else None,
        "recipeBudget": int(0.8 * ram),
        "ram": ram,
        "events": recipe_events,
        "harnessRestarts": sum(1 for e in recipe_events if e["action"] == "restart"),
        "oomOrRestart": unbidden,
        "hostPeakLoad1": max((h["load1"] for h in host), default=None),
        "hostPeakMemUsed": max((h["memUsed"] for h in host), default=None),
        "hostMemTotal": host[0]["memTotal"] if host else None,
        "macLoad1": {"max": max((m["load1"] for m in mac), default=None),
                     "mean": round(statistics.mean(m["load1"] for m in mac), 2) if mac else None},
        "seconds": len(sums),
    }


def quietness(inst: Instance) -> dict:
    """This host's load and every container that is NOT the instance, with what it is using — for the reading's record."""
    others = run([*inst.prefix, "docker", "stats", "--no-stream", "--format", "{{json .}}"], check=False).stdout
    mine = set(inst.names(RECIPE + DOUBLES).values())
    rows = []
    for line in others.splitlines():
        m = re.search(r"\{.*\}", line)
        if m:
            s = json.loads(m.group(0))
            if s.get("Name") not in mine:
                rows.append({"name": s.get("Name"), "cpu": s.get("CPUPerc"), "mem": s.get("MemUsage")})
    return {"t": time.strftime("%Y-%m-%dT%H:%M:%S"), "uptime": uptime_line(inst.prefix), "load1": mac_load(),
            "otherContainers": rows}


def readings_dir(label: str) -> Path:
    path = READINGS / f"{time.strftime('%Y%m%d-%H%M%S')}-{label}"
    path.mkdir(parents=True)
    return path


# ---------------------------------------------------------------- the server leg


class Client:
    """web/scripts/measure-volume.mts as a child: signed in once, one call per command (its header says the protocol)."""

    def __init__(self, inst: Instance, axis: str):
        self.proc = subprocess.Popen(["npx", "tsx", "scripts/measure-volume.mts", axis], cwd=WEB, env=inst.env(),
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
        self.ready = self._read()
        if not self.ready.get("ready"):
            raise Refused(f"measure-volume.mts did not get ready: {self.ready}")

    def _read(self) -> dict:
        assert self.proc.stdout is not None
        for line in self.proc.stdout:
            if line.startswith("VOL "):
                return json.loads(line[4:])
            sys.stdout.write(f"  | {line}")
        raise Refused(f"measure-volume.mts ended (exit {self.proc.wait()}) without an answer")

    def call(self, cmd: str) -> dict:
        assert self.proc.stdin is not None
        self.proc.stdin.write(cmd + "\n")
        self.proc.stdin.flush()
        return self._read()

    def close(self) -> None:
        try:
            self.call_quit()
        finally:
            self.proc.wait(timeout=30)

    def call_quit(self) -> None:
        assert self.proc.stdin is not None
        self.proc.stdin.write("quit\n")
        self.proc.stdin.flush()


def counted(inst: Instance, client: Client, cmd: str) -> dict:
    before = inst.com_select()
    reading = client.call(cmd)
    reading["selects"] = inst.com_select() - before
    return reading


def drift(inst: Instance, seconds: float) -> int:
    before = inst.com_select()
    time.sleep(seconds)
    return inst.com_select() - before


def spread(values: list[float]) -> dict:
    return {"median": round(statistics.median(values), 2), "min": round(min(values), 2), "max": round(max(values), 2)}


DRIFT_WINDOW_SECS = 60  # three live polls of an open Chapter (every 20 s); a 30 s window let one slip by (measured)
DRIFT_MARGIN = 10  # SELECTs above the idle baseline's worst window before a reading is refused


def baseline(inst: Instance, windows: int = 3) -> int:
    """The idle instance's SELECTs per drift window, after a restart with nobody connected: the background sweeps alone.
    Its worst window plus DRIFT_MARGIN is the tolerance the server leg holds its drift windows to. Kept in instance.json."""
    inst.restart_app()
    time.sleep(20)  # the boot's own first sweeps
    seen = [drift(inst, DRIFT_WINDOW_SECS) for _ in range(windows)]
    inst.state["idle_selects_per_window"] = max(seen)
    inst.state["drift_window_secs"] = DRIFT_WINDOW_SECS
    (inst.dir / "instance.json").write_text(json.dumps(inst.state, indent=2), encoding="utf-8")
    say(f"baseline: {seen} SELECTs per {DRIFT_WINDOW_SECS}s window with nobody connected")
    return max(seen)


def server_leg(inst: Instance, axis: str, out: Path, calls: int = 5) -> dict:
    """Decision 5: cold (each after an app restart) and warm, five calls each; the 304 call; the SELECTs of each;
    and a drift window before and after the warm series, refused above the idle baseline."""
    if inst.state.get("drift_window_secs") != DRIFT_WINDOW_SECS:
        baseline(inst)
    window = inst.state["drift_window_secs"]
    tolerance = inst.state["idle_selects_per_window"] + DRIFT_MARGIN
    cold = []
    for k in range(calls):
        restart = inst.restart_app()
        client = Client(inst, axis)
        try:
            r = counted(inst, client, "full")
        finally:
            client.close()
        r["restartSecs"] = round(restart, 1)
        cold.append(r)
        say(f"  cold {k + 1}: {r['status']} ttfb {r['ttfbMs']} ms, total {r['totalMs']} ms, {r['selects']} SELECTs")
    client = Client(inst, axis)
    try:
        client.call("full")  # the first warm call warms what the cold ones did not reach (and is not kept)
        pre = drift(inst, window)
        warm = [counted(inst, client, "full") for _ in range(calls)]
        conditional = counted(inst, client, "again")
        post = drift(inst, window)
    finally:
        client.close()
    for k, r in enumerate(warm):
        say(f"  warm {k + 1}: {r['status']} ttfb {r['ttfbMs']} ms, total {r['totalMs']} ms, {r['selects']} SELECTs"
            f" in {r.get('pages', 1)} page(s)")
    say(f"  304: {conditional['status']} total {conditional['totalMs']} ms, {conditional['selects']} SELECTs; "
        f"drift {pre} / {post} SELECTs per {window:.0f}s (tolerance {tolerance})")
    failed = [r["status"] for r in cold + warm + [conditional] if r["status"] not in (200, 304)]
    refused = []
    if max(pre, post) > tolerance:
        refused.append(f"the SELECT counter drifted {max(pre, post)} in {window:.0f}s with no call running "
                       f"(idle baseline {inst.state['idle_selects_per_window']}): something else is talking to the "
                       f"instance — a browser left open? The reading is refused.")
    reading = {
        "axis": axis, "items": warm[-1]["items"], "cold": cold, "warm": warm, "conditional": conditional,
        "coldTtfb": spread([r["ttfbMs"] for r in cold]), "coldTotal": spread([r["totalMs"] for r in cold]),
        "warmTtfb": spread([r["ttfbMs"] for r in warm]), "warmTotal": spread([r["totalMs"] for r in warm]),
        "selects": {"cold": [r["selects"] for r in cold], "warm": [r["selects"] for r in warm],
                    "conditional": conditional["selects"]},
        "bytes": {k: warm[-1][k] for k in ("wireBytes", "bodyBytes", "gzipBytes", "contentEncoding")},
        # A media list is read in pages (cr-gallery-list-paging): its SELECTs per page, the reading that must not grow.
        "pages": warm[-1].get("pages", 1), "pageLimit": warm[-1].get("pageLimit"),
        "selectsPerPage": [round(r["selects"] / r.get("pages", 1), 1) for r in warm],
        "drift": {"pre": pre, "post": post, "windowSecs": window, "tolerance": tolerance},
        "failedStatuses": failed, "refused": refused,
    }
    (out / f"server-{axis}.json").write_text(json.dumps(reading, indent=2), encoding="utf-8")
    return reading


# ---------------------------------------------------------------- the browser leg


def browser_leg(inst: Instance, spec: dict, out: Path) -> dict:
    """e2e/measure/volume.ts in the overlay's Playwright container, on the run's own networks and the outside cores.
    `spec` is what the test reads (/out/browser-spec.json); it writes /out/browser-result.json."""
    result = inst.dir / "browser-result.json"
    if result.exists():
        result.unlink()
    (inst.dir / "browser-spec.json").write_text(json.dumps(spec, indent=2), encoding="utf-8")
    name = f"veyq-vol-browser-{os.getpid()}-{int(time.time())}"
    cmd = [*inst.compose, "--profile", "browser", "run", "--rm", "--name", name, "browser", "-c", "measure/playwright.config.ts"]
    try:
        status = subprocess.run(cmd, cwd=inst.dir, env=name_only_env()).returncode
    finally:  # a stopped driver must not leave a browser polling the instance (it would void the next reading)
        inst.docker("rm", "-f", name, check=False)
    if not result.is_file():
        raise Refused(f"the browser leg wrote no result (playwright exited {status})")
    reading = json.loads(result.read_text(encoding="utf-8"))
    reading["playwrightExit"] = status
    (out / f"browser-{spec['label']}.json").write_text(json.dumps(reading, indent=2), encoding="utf-8")
    return reading


def specs(inst: Instance, axis: str, items: int, runs: int, throttles: list[int], stall: int) -> list[dict]:
    """The browser legs of one axis, from what the seeders recorded (manifest.json, accounts.json)."""
    manifest = json.loads((inst.dir / "manifest.json").read_text(encoding="utf-8"))
    accounts = json.loads((inst.dir / "accounts.json").read_text(encoding="utf-8"))
    owner = {k: accounts["owner"][k] for k in ("email", "password", "secretKey")}
    base = {"domain": DOMAIN, "runs": runs, "throttles": throttles, "stallSecs": stall, "accounts": [owner]}
    if axis in ("photos", "bare"):
        place = manifest[axis]
        return [{**base, "label": f"{axis}-{items}", "axis": axis, "hash": place["hash"], "items": items,
                 "listPath": f"/nodes/{place['nodeId']}/media"}]
    if axis in ("tree", "wide"):
        group, deep = manifest[axis], manifest[f"{axis}Deep"]
        return [
            {**base, "label": f"{axis}-root-{items}", "axis": "tree", "hash": group["hash"], "items": items,
             "text": group.get("folderName") or group.get("memoryName"), "listPath": f"/scopes/{group['scopeId']}/nodes"},
            {**base, "label": f"{axis}-deep-{items}", "axis": "deep", "hash": deep["hash"], "items": items,
             "text": deep["name"], "listPath": f"/scopes/{group['scopeId']}/nodes"},
        ]
    if axis == "members":
        profiled = [m for m in accounts["members"] if m["stage"] == "profiled"]
        pictures = sum(1 for m in profiled if int(re.match(r"^m(\d{4})-", m["email"]).group(1)) % 5 == 0)
        return [{**base, "label": f"members-{items}", "axis": "members", "hash": "", "items": items,
                 "avatars": pictures, "listPath": f"/scopes/{accounts['tenantId']}/members"}]
    if axis == "five":
        members = [m for m in accounts["members"] if m["stage"] == "profiled"][:5]
        if len(members) < 5:
            raise Refused("five-at-once needs five seeded members")
        place = manifest["photos"]
        return [{**base, "label": f"five-{items}", "axis": "five", "hash": place["hash"], "items": items, "runs": 1,
                 "throttles": [1], "accounts": [{k: m[k] for k in ("email", "password", "secretKey")} for m in members],
                 "listPath": f"/nodes/{place['nodeId']}/media"}]
    raise Refused(f"no browser leg for {axis}")


# ---------------------------------------------------------------- scenarios and stages


def wait_quiet(max_load: float, patience_min: float) -> float:
    """Wait until this host's 1-minute load is at or below `max_load`; refuse after `patience_min` minutes."""
    deadline = time.monotonic() + patience_min * 60
    while mac_load() > max_load:
        if time.monotonic() > deadline:
            raise Refused(f"the host's load stayed above {max_load} for {patience_min:.0f} min ({mac_load():.1f}) — "
                          f"a reading taken now would measure the neighbours")
        say(f"  waiting for a quiet host: load {mac_load():.1f} > {max_load}")
        time.sleep(30)
    return mac_load()


class Session:
    """One readings directory with the quietness recorded at both ends and the sampler running in between."""

    def __init__(self, inst: Instance, label: str):
        self.inst, self.out = inst, readings_dir(label)

    def __enter__(self) -> "Session":
        (self.out / "quiet-start.json").write_text(json.dumps(quietness(self.inst), indent=2), encoding="utf-8")
        self.sampler = Sampler(self.inst, self.out, local=not self.inst.prefix).start()
        return self

    def __exit__(self, *exc: object) -> None:
        self.summary = self.sampler.stop()
        (self.out / "quiet-end.json").write_text(json.dumps(quietness(self.inst), indent=2), encoding="utf-8")


def idle_control(out: Path) -> dict:
    """The idle scenario's control: the app's memory after its first five minutes should be flat; a climb is a finding."""
    app = [s for s in read_jsonl(out / "stats.jsonl") if s["service"] == "app"]
    if not app:
        return {}
    t0 = app[0]["t"]
    late = [s for s in app if s["t"] - t0 >= 300]
    if len(late) < 2:
        return {"note": "shorter than five minutes: no control"}
    first, last = late[: max(1, len(late) // 10)], late[-max(1, len(late) // 10):]
    a, b = statistics.mean(s["mem"] for s in first), statistics.mean(s["mem"] for s in last)
    return {"appMemAfter5Min": int(a), "appMemAtEnd": int(b), "climb": int(b - a), "climbPct": round(100 * (b - a) / a, 1)}


def scenario(inst: Instance, name: str, minutes: float, items: int, count: int) -> Path:
    with Session(inst, f"scenario-{name}") as s:
        if name == "idle":
            say(f"idle: {minutes:.0f} min with nobody connected")
            time.sleep(minutes * 60)
        elif name == "five":
            browser_leg(inst, specs(inst, "five", items, 1, [1], 120)[0], s.out)
        elif name == "upload":
            started = time.monotonic()
            status = seed(inst, ["upload", "--count", str(count)])
            (s.out / "upload.json").write_text(json.dumps({"count": count, "exit": status,
                                                           "secs": round(time.monotonic() - started, 1)}))
            if status != 0:
                raise Refused(f"the upload scenario's seeder exited {status}")
    if name == "idle":
        (s.out / "idle-control.json").write_text(json.dumps(idle_control(s.out), indent=2), encoding="utf-8")
    say(json.dumps(judge(s.out), indent=2))
    return s.out


def stage(inst: Instance, axis: str, n: int, max_load: float, patience: float, browser: bool) -> Path:
    """Phase 8's unit: seed to N, then — on a quiet host, sampler running — the server leg and the browser leg."""
    if axis == "members":
        invite_limit(inst, "100000")
        try:
            if seed(inst, ["members", "--target", str(n)]) != 0:
                raise Refused("the member seeder did not reach its target")
        finally:
            invite_limit(inst, "default")  # restarted with the defaults before anything is measured
    else:
        args = {"photos": ["photos", "--target", str(n)], "bare": ["photos", "--bare", "--target", str(n)],
                "tree": ["tree", "--target", str(n)], "wide": ["tree", "--wide", str(n)]}[axis]
        if seed(inst, args) != 0:
            raise Refused(f"the {axis} seeder did not reach {n}")
    load = wait_quiet(max_load, patience)
    with Session(inst, f"stage-{axis}-{n}") as s:
        (s.out / "stage.json").write_text(json.dumps({"axis": axis, "n": n, "loadAtStart": load}), encoding="utf-8")
        server_leg(inst, axis, s.out)
        if browser:
            for spec in specs(inst, axis, n if axis != "wide" else n + 1, 3, [1, 4], 120):
                browser_leg(inst, spec, s.out)
    say(json.dumps(judge(s.out), indent=2))
    return s.out


def oom_bite(inst: Instance) -> bool:
    """The sampler's bite: the app's memory limit dropped, live, below what it uses — the kernel kills it, the restart
    policy brings it back — and the sampler must REPORT that, not show a quiet gap. The limit is restored after."""
    app = inst.container("app")
    host = json.loads(inst.docker("inspect", app).stdout)[0]["HostConfig"]
    memory, swap = host["Memory"], host["MemorySwap"]
    with Session(inst, "oom-bite") as s:
        inst.docker("update", "--memory", "64m", "--memory-swap", "64m", app)
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            events = read_jsonl(s.out / "events.jsonl")
            if any(e["service"] == "app" and e["action"] and e["action"].startswith(("oom", "die")) for e in events):
                break
            time.sleep(2)
        inst.docker("update", "--memory", str(memory), "--memory-swap", str(swap), app)
        time.sleep(5)
    inst.docker("restart", app)
    inst.wait_healthy()
    after = json.loads(inst.docker("inspect", app).stdout)[0]["HostConfig"]
    restored = (after["Memory"], after["MemorySwap"]) == (memory, swap)
    reported = s.summary["oomOrRestart"]
    say(f"oom-bite: the sampler reported {reported}; the limit restored to {memory} ({restored})")
    return bool(reported) and restored


# ---------------------------------------------------------------- judging (Q1)


# The windowed gallery's targets ([ADR 0105](docs/decisions/0105-a-gallery-renders-what-is-near-the-viewport-and-opens-its-listing-in-a-worker.md) § Targets, owner 2026-10-19), on the local recipe, Chromium.
# Desktop, per Chapter size: the grid shown (its order final) after the list, viewport complete after navigation, end
# complete after the jump — in ms. Under the 4x throttle at 1,000 and 3,000: viewport complete, the longest task after
# it, the long tasks of the scroll-through; 10,000 under the throttle is reported, not judged. Every stage: the live DOM
# under DOM_NODES_MAX at both moments, independent of the Chapter's size.
DOM_NODES_MAX = 3000
DESKTOP_TARGETS = {1000: (2000, 3000, 2000), 3000: (2000, 3000, 2000), 10000: (4000, 5000, 2000)}
THROTTLED_TARGETS = {1000: (8000, 500, 5000), 3000: (8000, 500, 5000)}


def gallery_findings(items: int, run_: dict) -> list[str]:
    """Q2's clauses over one gallery run that ended (a stopped run is already a finding of its own). A clause whose
    reading is missing is a finding too: a metric the run never produced proves nothing, least of all a pass."""
    out: list[str] = []

    def over(label: str, value: Any, limit: float, strict: bool = False) -> None:
        if value is None:
            out.append(f"{label}: not measured")
        elif value > limit or (strict and value >= limit):
            out.append(f"{label} {value} (> {limit})" if not strict else f"{label} {value} (not under {limit})")

    over("DOM nodes at viewport complete", run_.get("viewportDomNodes"), DOM_NODES_MAX, strict=True)
    over("DOM nodes at end complete", run_.get("endDomNodes"), DOM_NODES_MAX, strict=True)
    if run_.get("throttle") == 1 and items in DESKTOP_TARGETS:
        shown, viewport, end = DESKTOP_TARGETS[items]
        first, listed = run_.get("firstTileMs"), run_.get("listMs")
        over("grid shown after the list, ms", None if first is None or listed is None else round(first - listed), shown)
        over("viewport complete, ms", run_.get("viewportCompleteMs"), viewport)
        over("end complete after the jump, ms", run_.get("endCompleteMs"), end)
    elif run_.get("throttle") == 4 and items in THROTTLED_TARGETS:
        viewport, longest, scroll = THROTTLED_TARGETS[items]
        over("viewport complete, ms", run_.get("viewportCompleteMs"), viewport)
        over("longest task after viewport complete, ms", (run_.get("longtasksAfterViewport") or {}).get("maxMs"), longest)
        over("long tasks in the scroll-through, ms",
             ((run_.get("scrollThrough") or {}).get("longtasks") or {}).get("totalMs"), scroll, strict=True)
    return out


def judge(directory: Path) -> dict:
    """Q1, as the owner decided it: a host carries a stage when no container is OOM-killed or restarted, the peak memory
    stays at or below 80 % of the host's RAM, no request fails, and on a desktop browser a 3,000-photo Chapter's list
    arrives within 2 s. Its "last thumbnail within 15 s" was a promise about an unwindowed grid; a windowed one is held
    to ADR 0105 § Targets instead ([gallery_findings]). Reads every sampler.json, server-*.json and
    browser-*.json."""
    findings, notes = [], []
    for sampler in directory.glob("**/sampler.json"):
        s = json.loads(sampler.read_text(encoding="utf-8"))
        if s["oomOrRestart"]:
            findings.append(f"{sampler.parent.name}: a recipe container was OOM-killed or restarted: {s['oomOrRestart']}")
        if s["recipePeakMem"] is not None and s["recipePeakMem"] > s["recipeBudget"]:
            findings.append(f"{sampler.parent.name}: the recipe's memory peaked at {s['recipePeakMem'] / GIB:.2f} GiB, "
                            f"above 80 % of {s['ram'] / GIB:.0f} GiB")
        # The quiet gate is the load BEFORE the readings start: during them the measurement's own browsers and seeders
        # load this host too, which says nothing about the neighbours.
        start = sampler.parent / "quiet-start.json"
        load = json.loads(start.read_text(encoding="utf-8"))["load1"] if start.is_file() else s["macLoad1"]["max"]
        if load is not None and load > 3:
            notes.append(f"{sampler.parent.name}: started under load {load:.1f} — not a reading")
    for server in directory.glob("**/server-*.json"):
        r = json.loads(server.read_text(encoding="utf-8"))
        if r["failedStatuses"]:
            findings.append(f"{server.name}: failed statuses {r['failedStatuses']}")
        if r["refused"]:
            findings.append(f"{server.name}: REFUSED — {' '.join(r['refused'])}")
    for browser in directory.glob("**/browser-*.json"):
        r = json.loads(browser.read_text(encoding="utf-8"))
        for run_ in r.get("runs", []):
            if run_.get("failedRequests"):
                findings.append(f"{browser.name}: {len(run_['failedRequests'])} failed request(s) in a run")
            if run_.get("stopped"):
                stop = f"{browser.name}: a run stopped: {run_['stopped']}"
                # 10,000 photos under the 4x throttle is reported, not judged (Q2): its stall is a note, not a finding.
                reported_only = r.get("axis") == "photos" and r.get("items") == 10000 and run_.get("throttle") == 4
                (notes if reported_only else findings).append(stop)
            if r.get("axis") == "photos" and r.get("items") == 3000 and run_.get("throttle") == 1:
                if (run_.get("listMs") or 1e9) > 2000:
                    findings.append(f"{browser.name}: the 3,000 list arrived after {run_.get('listMs')} ms (> 2 s)")
            if r.get("axis") == "photos" and not run_.get("stopped"):
                findings.extend(f"{browser.name} x{run_.get('throttle')} run {run_.get('run')}: {f}"
                                for f in gallery_findings(int(r.get("items") or 0), run_))
    verdict = {"directory": str(directory), "carries": not findings, "findings": findings, "notes": notes}
    (directory / "verdict.json").write_text(json.dumps(verdict, indent=2), encoding="utf-8")
    return verdict


# ---------------------------------------------------------------- main


def seed(inst: Instance, seed_args: list[str]) -> int:
    return subprocess.run(["npx", "tsx", "scripts/seed-volume.mts", *seed_args], cwd=WEB, env=inst.env()).returncode


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--instance", type=Path, help="the instance directory (generated credentials live there)")
    parser.add_argument("--prefix", default="", help="run docker and the host probe through this (e.g. 'ssh cax')")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("fixtures")
    b = sub.add_parser("boot")
    b.add_argument("--app", required=True)
    b.add_argument("--admin", required=True)
    b.add_argument("--cpuset", default="2,3", help="the Docker VM's vCPUs the recipe is pinned to")
    b.add_argument("--outside", default="6-13", help="the vCPUs the doubles and the browsers run on")
    sub.add_parser("status")
    sub.add_parser("teardown")
    s = sub.add_parser("seed")
    s.add_argument("args", nargs=argparse.REMAINDER)
    il = sub.add_parser("invite-limit")
    il.add_argument("limit")
    sub.add_parser("baseline")
    sv = sub.add_parser("server")
    sv.add_argument("axis", choices=("photos", "bare", "tree", "wide", "members"))
    sv.add_argument("--calls", type=int, default=5)
    br = sub.add_parser("browser")
    br.add_argument("axis", choices=("photos", "bare", "tree", "wide", "members", "five"))
    br.add_argument("--items", type=int, required=True, help="what the target holds (photos, nodes, members)")
    br.add_argument("--runs", type=int, default=3)
    br.add_argument("--throttles", default="1,4")
    br.add_argument("--stall", type=int, default=120, help="seconds without progress before a wait stops")
    br.add_argument("--hold", type=int, default=0, help="seconds to keep the opened target on screen (the drift bite)")
    sc = sub.add_parser("scenario")
    sc.add_argument("name", choices=("idle", "five", "upload"))
    sc.add_argument("--minutes", type=float, default=30)
    sc.add_argument("--items", type=int, default=10000, help="five: the Chapter's photo count")
    sc.add_argument("--count", type=int, default=1000, help="upload: how many phone photos")
    st = sub.add_parser("stage")
    st.add_argument("axis", choices=("photos", "bare", "tree", "wide", "members"))
    st.add_argument("n", type=int)
    st.add_argument("--max-load", type=float, default=3.0)
    st.add_argument("--patience", type=float, default=60, help="minutes to wait for a quiet host")
    st.add_argument("--no-browser", action="store_true")
    sub.add_parser("oom-bite")
    sm = sub.add_parser("sample")
    sm.add_argument("--seconds", type=int, required=True)
    j = sub.add_parser("judge")
    j.add_argument("directory", type=Path)
    args = parser.parse_args()
    # A SIGTERM runs the `finally`s (the browser container removed, the sampler's streams ended); a SIGKILL cannot —
    # after one, `docker ps --filter name=veyq-vol-browser` shows what is left.
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    prefix = shlex.split(args.prefix)
    try:
        if args.cmd == "judge":
            say(json.dumps(judge(args.directory), indent=2))
            return 0
        if args.instance is None:
            raise Refused("--instance DIR is required")
        directory = args.instance.resolve()
        if args.cmd == "fixtures":
            fixtures(directory)
        elif args.cmd == "boot":
            boot(directory, args.app, args.admin, args.cpuset, args.outside)
        else:
            inst = Instance(directory, prefix)
            if args.cmd == "status":
                say(json.dumps(status(inst), indent=2))
            elif args.cmd == "teardown":
                teardown(inst)
            elif args.cmd == "seed":
                return seed(inst, args.args)
            elif args.cmd == "invite-limit":
                invite_limit(inst, args.limit)
            elif args.cmd == "baseline":
                baseline(inst)
            elif args.cmd == "server":
                out = readings_dir(f"server-{args.axis}")
                (out / "quiet-start.json").write_text(json.dumps(quietness(inst), indent=2))
                sampler = Sampler(inst, out, local=not prefix).start()
                try:
                    reading = server_leg(inst, args.axis, out, args.calls)
                finally:
                    summary = sampler.stop()
                    (out / "quiet-end.json").write_text(json.dumps(quietness(inst), indent=2))
                say(f"server {args.axis}: {reading['items']} items; warm total {reading['warmTotal']} ms; "
                    f"recipe peak {summary['recipePeakMem']} B; readings in {out}")
                if reading["refused"]:
                    say("REFUSED: " + " ".join(reading["refused"]))
                    return 3
            elif args.cmd == "browser":
                out = readings_dir(f"browser-{args.axis}")
                throttles = [int(x) for x in args.throttles.split(",")]
                for spec in specs(inst, args.axis, args.items, args.runs, throttles, args.stall):
                    spec["holdSecs"] = args.hold
                    reading = browser_leg(inst, spec, out)
                    for r in reading["runs"]:
                        say(f"  {spec['label']} x{r['throttle']} run {r['run']}: list {r['listMs']} ms "
                            f"(request {r['listRequestMs']} ms), first {r['firstTileMs']} ms, done {r['doneMs']} ms"
                            + (f", end {r.get('endCompleteMs')} ms, DOM {r.get('viewportDomNodes')}/{r.get('endDomNodes')}"
                               if r.get("viewportCompleteMs") is not None else "")
                            + (f" — STOPPED: {r['stopped']}" if r["stopped"] else ""))
                say(f"readings in {out}")
            elif args.cmd == "scenario":
                scenario(inst, args.name, args.minutes, args.items, args.count)
            elif args.cmd == "stage":
                stage(inst, args.axis, args.n, args.max_load, args.patience, not args.no_browser)
            elif args.cmd == "oom-bite":
                return 0 if oom_bite(inst) else 1
            elif args.cmd == "sample":
                out = readings_dir("sample")
                sampler = Sampler(inst, out, local=not prefix).start()
                time.sleep(args.seconds)
                say(json.dumps(sampler.stop(), indent=2))
    except Refused as error:
        say(f"measure_volume: {error}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
