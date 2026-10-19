#!/usr/bin/env python3
"""Install the release candidate as a Server Owner would, through `veyqctl install`, and drive it in a browser (ADR 0090, lane L1 of ADR 0091).

**What it does: lane L1 — a boot, first run, a Memory round trip and the feature scenarios in a browser, and the
stack swept for every value the browser typed.** `ci.yml`'s `stack` job runs it per architecture. Takes the two candidate images (`--app`,
`--admin`: a local build's bare `name:tag` — `veyqctl --unsigned-local` refuses anything else) and from there
installs it the way the Run area does, through the release's own `veyqctl`, never through this repository's copy of
the recipe:

  0. **preflight** — ports 80, 443 and the admin port free, the Playwright library the image's version, the host's
     containers and volumes recorded; then **browser image** — the Playwright image the overlay pins, pulled by its
     digest when the host does not hold it yet, timed on its own (a fresh CI runner always pulls it).
  1. **assets** — the candidate becomes a release: `scripts/release_assets.py` (the publish's producer) writes it into
     the run's mirror directory `src/` — the recipe set it derives from the compose file (`garage.toml` included),
     `veyqctl`, `instance_backup.py` and the manifest naming both images. What is installed is what a release ships.
     A value this run gives as a template value (`VEYQ_DOMAIN`, `VEYQ_ACME_EMAIL`) that the release's template
     (`env.example`) no longer assigns is red here.
  1b. **client port** — the release's own Traefik and client-address plugin (ADR 0094 decision 7), read from the
     release's compose file — the image digest, the `--experimental.*` flags, the plugin's two read-only mounts, the
     service's user and posture; never a second copy of them — started on a run-private `--internal` network (no route
     out, so the plugin loads with nothing to fetch) with a router through the plugin and a control router without it
     to one echo (the overlay's Playwright image, `node`). A sender in a container forges the header on two lines in
     two casings: through the plugin the echo must show exactly ONE line equal to the sender's own `localAddress:
     localPort`; through the control it must show the forged values, or the oracle could not see a forgery. Its
     containers and network carry the run's project label and are removed by the stage itself; Traefik's log is
     `client-port-traefik.log`.
  1c. **release mirror** — the release check's test double (inbox #464, owner's option (a)): the run's instance is
     stamped with the release `veyqctl` installs (`VEYQ_VERSION` in the overlay, overriding the image's `-SNAPSHOT`
     for this container only), and `release_assets.py`'s own `produce` writes four releases into the run's `mirror/`,
     which the overlay's `release-mirror` serves on `admin`: that release, the next minor, the next major (planned)
     and the one after (marked unscheduled) — so the console has a release notice, a dated planned-major notice whose
     mail carries an `.ics`, and an urgent one to show. `stack.json` names them for the suite; nothing else reads them.
  2. **pebble** — Pebble alone first, under the run's own project, `veyq-e2e-<stamp>`, so it cannot address an
     instance on this host. Its root is generated when it starts, and the app must trust it before IT starts, while
     `veyqctl install` starts everything at once: so this stage brings Pebble up from the release's recipe and the
     overlay before there is an instance (`pebble.env`: the run's values, and a placeholder for each secret and image
     line `install` writes later, none of which Pebble or its network reads), reads the root off Pebble's management
     port and writes the app's truststore with it — Pebble's root and nothing else, written by the candidate image's
     own `keytool` (the overlay says why the app needs it). Pebble's minica root is copied out of the overlay's pinned
     Pebble image first, for Traefik's ACME client to trust Pebble's listener.
  3. **install** — `veyqctl install --unsigned-local --source src/` into the run's `home/`, the release's own
     `veyqctl` as the operator fetches it, with `infra/e2e/docker-compose.e2e.yml` as the instance's override file
     `compose.override.yml` (Pebble, the browser behind a profile, and the DNS name and CA trust the internet would
     give the instance) and the run's values through `--set`; `veyqctl` generates the secrets, writes `.env` from the
     release's template and the image lines from its manifest, and waits until every service is healthy. stdin is
     closed, so a key the template gains that this run does not give is refused by name, not asked for. Red unless
     `veyqctl` named the run's project and left Pebble's container as the pebble stage started it.
  5. **certificate** — opens TLS to `:443` for `veyq.test` (SNI, no `/etc/hosts`) with the run's Pebble root as the
     ONLY trusted CA; then `GET /api/setup` over the
     verified connection must answer 200. Traefik serves its default certificate until ACME has finished, so this
     polls until `VEYQ_STACK_CERT_SECS`; at the deadline it names the certificate Traefik served instead. A Traefik
     that cannot write `/acme` (ADR 0071's silent failure) is red here, not up.
  6. **window** — opened before the browser types anything: MariaDB's statement log switched on for the run's window
     (`e2e_sweeps.StatementLog`, a sentinel statement at each end), and one unsigned request for a run-unique key
     sent through Traefik to the bucket, which Garage's log must then carry percent-encoded (the container sweep's
     control). The stack's own containers only, through the run's compose project.
  7. **browser** — `e2e/`'s Playwright suite, whole, in the Playwright image the overlay pins, started with
     `docker compose run --rm browser` on the run's own `edge` and `admin` networks — never on the host, so a laptop
     runs what CI runs. The container first trusts the run's Pebble root (and nothing more) in Chromium's NSS db
     (`infra/e2e/nss_trust_root.py`); the suite reaches the domain through Traefik and the admin console through an
     in-container loopback tunnel to `admin:8080` (`e2e/src/tunnel.ts`), and everything it may reach is named in
     `stack.json`, which this stage writes. Its exit code is the verdict; the JUnit report's tests and times are
     printed. Preflight refuses a run whose installed `@playwright/test` is not exactly the image's version (the
     browser is baked into the image; a library one patch apart fails at launch) or is not installed at all. **A
     green needs at least one EXECUTED test** (the JUnit report): a selection that matched nothing, or a run whose
     every test was skipped, is red, never "nothing failed". Arguments after `--` go to `playwright test` as they are.
  7b. **guest-write record** — the stack scenario ADR 0094 § For the moderation CR asks for, the half the `client port`
     stage leaves open (that one reads an echo): `e2e/tests/features/guest-write-record.spec.ts` has a guest create a
     folder through the release's Traefik and writes, from the browser container's own TCP table, the sockets it holds to
     Traefik's :443 and the folder's id (`guest-write-record.json`). This stage reads the app's record of that write off
     the instance's database: exactly one row, the browser's own address, and a port that is one of the browser's own
     sockets' — the one Traefik accepted and its plugin stated (ADR 0094 decision 7). Skipped, saying so, when the
     selection ran no test of that spec; red when it ran one and left no file.
  8. **sweep** — the ADR 0053 wire assertion, with the sensors `run_web_e2e.py` uses (`scripts/e2e_sweeps.py`,
     parameterised by compose project): every value the suite typed (`canaries-<spec>.json`, `e2e/src/canaries.ts`)
     must be absent from the instance's database, its bucket (keys and bytes), every container log of the project
     since the run began, and every statement the database received in the window — each sensor armed by a positive
     control, and every spec file that executed a test must have recorded canaries. And every address an account was
     signed up with (`pii-<spec>.json`, `recordPii`) must be absent from every container log, and present in the
     statement log (ADR 0092 § 4). [sweep] lists what is not swept.
  9. **evidence** — `compose.log` (every container, timestamped) and `ps.json` into the run directory, always.
  10. **teardown** — `docker compose down -v` by the run's project name (unless `--keep`).
  11. **host** — the run touched nothing that is not its own: every container that existed before it still exists
     with the same state and start time, every volume still exists, and nothing of the run's project is left.

The summary prints each stage's wall time — the budget ADR 0091 decision 1 holds the lane to (15 min, the job's
`timeout-minutes`) — and under `browser` each spec file's, i.e. each Playwright project's; then the release and the
assets `veyqctl` installed, and the verdict. Building or pulling the two candidate images happens before this script,
in the job's own steps, which the CI log times.

**The run directory survives the run** (`.e2e-stack-out/<stamp>/`, gitignored): the release (`src/`, `assets.log`),
the instance's home (`home/`: `.env` with every secret of an instance that no longer exists, `state.json`, the
override file, `releases/<release>/`), `pebble.env`, Pebble's root, `install.log`, `compose.log`, `ps.json`, and the
browser's: `stack.json`, `browser.log`, `junit.xml`, `report/` (Playwright's HTML report), `playwright/` (a trace per
test, `npx playwright show-trace <zip>`), `instance.json` (the owner's credentials and Secret Key, the invite link);
and the sweeps': `canaries-<spec>.json` and their union `swept-canaries.json`, `database-veyq.sql` (the dump the
database sweep read), `container-<service>.log`, `mariadb-general.log` (the window's statements), and a mirror of the
bucket only where the store sweep found something.

**Not a `validate_*` guard.** It needs Docker, two images, host ports 80 and 443 and the root `npm ci`, so it
cannot join the buildless trust-chain glob; lane L1 calls it directly. Nothing is verified: the images are local
builds, so `install` runs `--unsigned-local` and says so on every line — a published candidate's signatures are
`veyqctl verify`'s, which this lane does not exercise.

**Its limits — what a green run does NOT prove.** Pebble is told every challenge is valid (the overlay says why), so
Traefik's HTTP-01 router on :80 is not exercised. The certificate stage asserts the bootstrap domain; the tenant's
domain is asserted by the browser, which opens the invite link over it. The client-port stage hands the plugin the
app's default header name and attaches it as the app's routing file does, but writes that routing itself. The
app's own routers, plugin attached, are the ones every later stage reaches the instance through — a middleware Traefik
cannot build answers 404 there — but nothing asserts the port the app then resolves (before the moderation CR nothing
keeps or returns it). The browser reaches Traefik on the run's
`edge` network, not through the host's published :443 — the published ports themselves are exercised by the
certificate stage only. The host check compares before and after: it
cannot tell this run's touch from another process's on the same host in the same window, so a red there names the
container and leaves the attribution to the reader; and a container created AND removed by someone during the run is
invisible to it. The sweep proves absence for what the suite TYPED (and the text inside the photo it uploaded), not for
everything the page sealed: a value the browser derived or unsealed and nobody typed is not a canary here — web's
API-level suites, which record what they unseal, cover that half (`run_web_e2e.py`). And the round trip creates one
tenant, one Memory, one photo and one link; a leak on a path no scenario walks is not seen.

Usage:
    python3 scripts/run_stack_e2e.py --app veyq:local --admin veyq-admin:local [--keep] [-- <playwright test args>]

Environment (all optional):
    VEYQ_STACK_WAIT_SECS   How long `veyqctl install` waits for a healthy stack (its VEYQ_WAIT_SECS). Default: 300
    VEYQ_STACK_CERT_SECS   How long the certificate stage polls for Pebble's certificate. Default: 120
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import socket
import ssl
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ElementTree
from pathlib import Path
from typing import NamedTuple

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

# The ADR 0053 sweeps, shared with run_web_e2e.py and parameterised by compose project (ADR 0090 decision 5).
import e2e_sweeps as sweeps  # noqa: E402 - after the PyYAML check, which it needs too

ROOT = Path(__file__).resolve().parent.parent
OVERLAY = ROOT / "infra" / "e2e" / "docker-compose.e2e.yml"
# The publish's producer: the candidate becomes a release in the mirror layout `veyqctl --source` reads (ADR 0096).
RELEASE_ASSETS = ROOT / "scripts" / "release_assets.py"
RUN_ROOT = ROOT / ".e2e-stack-out"
E2E_PACKAGE = ROOT / "e2e" / "package.json"
INSTALLED_PLAYWRIGHT = ROOT / "node_modules" / "@playwright" / "test" / "package.json"
# What the browser container is told it may reach (e2e/src/stack.ts). Paths are the container's: the run directory
# is mounted at /out.
ADMIN_UPSTREAM = "admin:8080"
TRAEFIK_SERVICE = "traefik"
# The overlay's Mailpit on the run's `admin` network, by the alias a Server Owner types as the SMTP host: its HTTP API,
# which the suite reads the mail the instance sent from (infra/e2e/docker-compose.e2e.yml says why it is there).
MAIL_API = "http://mail.relay.test:8025"
# The overlay's stub release mirror on the run's `admin` network, by the address a Server Owner types as the release
# check's source (infra/e2e/docker-compose.e2e.yml says why it is there).
RELEASE_MIRROR_SOURCE = "http://releases.mirror.test:8080"
CONTAINER_OUT = "/out"
APP_TRUSTSTORE = "app-truststore.p12"
# Not a secret: the store holds one public certificate, and Java asks for a password (the overlay names it too).
APP_TRUSTSTORE_PASSWORD = "veyq-e2e"
PEBBLE_MINICA_PATH = "/test/certs/pebble.minica.pem"
PEBBLE_MANAGEMENT_PORT = 15000
DOMAIN = "veyq.test"
# Not the recipe's default 8081: on a developer's machine that port is the dev backend's, and a run must never answer
# on a port the developer's own instance uses.
ADMIN_PORT = 18081
# The recipe publishes Traefik on these, so they belong to the run (ADR 0090 § Consequences).
HOST_PORTS = (80, 443, ADMIN_PORT)
WAIT_SECS = int(os.environ.get("VEYQ_STACK_WAIT_SECS", "300"))
CERT_SECS = int(os.environ.get("VEYQ_STACK_CERT_SECS", "120"))
ASSIGNMENT = re.compile(r"^([A-Z][A-Z0-9_]*)=(.*)$")
# The operator's values the release's template assigns and this run gives (`--set`); `veyqctl` generates the secrets
# and writes the image lines from the manifest. A template that stops assigning one of these is red at `assets`.
TEMPLATE_VALUES = {"VEYQ_DOMAIN": DOMAIN, "VEYQ_ACME_EMAIL": f"e2e@{DOMAIN}"}
# What the sweeps read in the recipe's instance. The database is the one the recipe names (`MARIADB_DATABASE: veyq`,
# the app's JDBC URL); the bucket is the backend's default `veyq.bundled-store.bucket`, which the recipe does not
# override; the store is reached the way the backup tool reaches it, by its internal name on the network the recipe
# puts it on (`edge`, ADR 0071 §3).
DATABASE = "veyq"
STORE_BUCKET = "blobs"
STORE_SERVICE = "garage"
STORE_INTERNAL = "http://garage:3900"
STORE_NETWORK = "edge"
APP_SERVICE = "app"


class Red(Exception):
    """A stage's verdict: the run is red, and this is why."""


def run(*cmd: str, check: bool = True, capture: bool = True, **kw) -> subprocess.CompletedProcess:
    print("+", " ".join(cmd), flush=True)
    result = subprocess.run(cmd, text=True, capture_output=capture, **kw)
    if check and result.returncode != 0:
        raise Red(f"`{' '.join(cmd[:4])} …` exited {result.returncode}: {(result.stderr or '').strip()[-2000:]}")
    return result


# ---------------------------------------------------------------- the host, before and after


def host_snapshot() -> dict:
    """Every container (with its state and start time) and every volume on the host."""
    ids = run("docker", "ps", "-aq", "--no-trunc").stdout.split()
    containers = {}
    if ids:
        for c in json.loads(run("docker", "inspect", *ids).stdout):
            containers[c["Id"]] = {
                "name": c["Name"].lstrip("/"),
                "project": (c["Config"].get("Labels") or {}).get("com.docker.compose.project", ""),
                "state": c["State"]["Status"],
                "started": c["State"]["StartedAt"],
            }
    volumes = run("docker", "volume", "ls", "-q").stdout.split()
    return {"containers": containers, "volumes": set(volumes)}


def project_leftovers(project: str) -> list[str]:
    label = f"label=com.docker.compose.project={project}"
    left = []
    for kind, cmd in (("container", ["docker", "ps", "-aq", "--filter", label]),
                      ("volume", ["docker", "volume", "ls", "-q", "--filter", label]),
                      ("network", ["docker", "network", "ls", "-q", "--filter", label])):
        left += [f"{kind} {x}" for x in run(*cmd).stdout.split()]
    return left


def host_untouched(before: dict, after: dict, project: str, torn_down: bool) -> None:
    # No exemption for the run's own project: whatever existed before the run is by definition not the run's, so a
    # project name that collides with a running one is exactly what this must see.
    findings = []
    for cid, was in before["containers"].items():
        now = after["containers"].get(cid)
        if now is None:
            findings.append(f"container {was['name']} (project {was['project'] or '-'}) is gone")
        elif (now["state"], now["started"]) != (was["state"], was["started"]):
            findings.append(f"container {was['name']} (project {was['project'] or '-'}) went "
                            f"{was['state']}@{was['started']} -> {now['state']}@{now['started']}")
    findings += [f"volume {v} is gone" for v in sorted(before["volumes"] - after["volumes"])]
    if torn_down:
        findings += [f"{x} of {project} survived the teardown" for x in project_leftovers(project)]
    if findings:
        raise Red("the run touched what is not its own: " + "; ".join(findings))
    print(f"host: {len(before['containers'])} pre-existing containers and {len(before['volumes'])} volumes unchanged",
          flush=True)


def ports_free() -> None:
    taken = []
    for port in HOST_PORTS:
        with socket.socket() as s:
            s.settimeout(1)
            if s.connect_ex(("127.0.0.1", port)) == 0:
                taken.append(port)
    if taken:
        raise Red(f"host port(s) {taken} already answer; the recipe's Traefik needs 80/443 and the run's admin port "
                  f"is {ADMIN_PORT}")


# ---------------------------------------------------------------- assets, pebble, install


def copy_out(image: str, files: dict[str, Path]) -> None:
    """`docker cp` each in-image path to its destination, from a container that is created and never started."""
    cid = run("docker", "create", image).stdout.strip()
    try:
        for src, dest in files.items():
            run("docker", "cp", f"{cid}:{src}", str(dest))
    finally:
        run("docker", "rm", cid, check=False)


def overlay_image(service: str) -> str:
    return yaml.safe_load(OVERLAY.read_text(encoding="utf-8"))["services"][service]["image"]


def pebble_image() -> str:
    return overlay_image("pebble")


def browser_pins() -> None:
    """The browser is baked into the pinned Playwright image; the library that drives it is the host's install."""
    pinned = json.loads(E2E_PACKAGE.read_text(encoding="utf-8"))["devDependencies"]["@playwright/test"]
    if not INSTALLED_PLAYWRIGHT.is_file():
        raise Red("@playwright/test is not installed — run `python3 scripts/npm_ci_no_deprecated.py` at the repository "
                  "root first (the browser container installs nothing; it mounts that install read-only)")
    installed = json.loads(INSTALLED_PLAYWRIGHT.read_text(encoding="utf-8"))["version"]
    image = overlay_image("browser")
    tag = re.search(r":v(\d+\.\d+\.\d+)-", image)
    versions = {"e2e/package.json": pinned, "node_modules": installed, "the overlay's image": tag.group(1) if tag else image}
    if len(set(versions.values())) != 1:
        raise Red(f"Playwright versions disagree: {versions} — the library must be exactly the image's browser's")
    print(f"browser: Playwright {installed} (library and image agree)", flush=True)


def browser_image() -> None:
    """The pinned Playwright image, pulled by its digest when this host does not hold it yet — its own stage, so the
    summary shows what the browser costs before a test runs (in CI, on every run: a fresh runner holds no image)."""
    image = overlay_image("browser")
    if run("docker", "image", "inspect", image, check=False).returncode == 0:
        print(f"browser image: {image} is already on this host", flush=True)
        return
    run("docker", "pull", image, capture=False)
    print(f"browser image: pulled {image}", flush=True)


class Release(NamedTuple):
    """The candidate as files: the mirror directory `release_assets.py` wrote, and the one release in it."""
    source: Path
    tag: Path  # <source>/<tag>/, the release's assets


def assets(app_image: str, admin_image: str, workdir: Path) -> Release:
    """The candidate's release, produced by the publish's own producer into a mirror directory of the run."""
    source = workdir / "src"
    result = run(sys.executable, str(RELEASE_ASSETS), str(source), "--app", app_image, "--admin", admin_image,
                 check=False)
    (workdir / "assets.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode != 0:
        raise Red(f"release_assets.py exited {result.returncode}: {result.stderr.strip()[-2000:]}")
    listed = json.loads((source / "releases.json").read_text(encoding="utf-8"))["releases"]
    if len(listed) != 1:
        raise Red(f"the run's mirror lists {len(listed)} releases, not the one it just produced")
    tag = source / listed[0]["tag"]
    template = {m.group(1) for m in map(ASSIGNMENT.match, (tag / "env.example").read_text().splitlines()) if m}
    gone = sorted(set(TEMPLATE_VALUES) - template)
    if gone:
        raise Red(f"this run gives {gone} as template values, which the release's env.example no longer assigns — "
                  "the recipe moved on")
    names = sorted(json.loads((tag / "manifest.json").read_text(encoding="utf-8"))["assets"])
    print(f"assets: {tag.name} in {source.relative_to(ROOT)}: {', '.join(names)}", flush=True)
    return Release(source, tag)


# ---------------------------------------------------------------- release mirror (ADR 0097, inbox #464)


class Mirror(NamedTuple):
    """The release check's test double: the release the run's instance is stamped with, and the stub mirror's newer
    ones, each a `<major>.<minor>` as the console and the mail name it."""
    path: Path
    running: str
    newer: str  # the next minor of the running major: a release notice
    planned: str  # the first release of the next major, not marked: a planned major, dated
    planned_date: str  # the day its manifest and releases.json carry (UTC), from which the window's end follows
    unscheduled: str  # the first release of the major after that, marked unscheduled: urgent


def release_mirror(release: Release, app_image: str, admin_image: str, workdir: Path) -> Mirror:
    """Four releases in the mirror layout, written by the publish's producer itself (`release_assets.produce`) so the
    files the app reads are the files a publish writes: the candidate's own release (the stamp), and three newer."""
    import release_assets  # the publish's producer; its CLI names only the tree's release, `produce` takes another

    running = release.tag.name[1:]
    major, minor = (int(part) for part in running.split("."))
    mirror = Mirror(workdir / "mirror", running, f"{major}.{minor + 1}", f"{major + 1}.0", "", f"{major + 2}.0")
    try:
        for name, unscheduled in ((running, False), (mirror.newer, False), (mirror.planned, False),
                                  (mirror.unscheduled, True)):
            release_assets.produce(mirror.path, app_image, admin_image, unscheduled, release=name)
    except release_assets.ReleaseError as error:
        raise Red(f"release_assets could not produce the stub mirror: {error}")
    listed = json.loads((mirror.path / "releases.json").read_text(encoding="utf-8"))["releases"]
    tags = [entry["tag"] for entry in listed]
    expected = [f"v{name}" for name in (running, mirror.newer, mirror.planned, mirror.unscheduled)]
    if tags != expected:
        raise Red(f"the stub mirror lists {tags}, not {expected}")
    planned_date = next(entry["date"] for entry in listed if entry["tag"] == f"v{mirror.planned}")
    print(f"release mirror: the instance is stamped {running}; {mirror.path.relative_to(ROOT)} lists "
          f"{', '.join(tags)} ({mirror.unscheduled} unscheduled), served at {RELEASE_MIRROR_SOURCE}", flush=True)
    return mirror._replace(planned_date=planned_date)


# ---------------------------------------------------------------- client port (ADR 0094 decision 7)

# Where Traefik reads a local plugin from (its working directory is `/`), and the static flag that declares one.
PLUGIN_ROOT = "/plugins-local/src/"
LOCAL_PLUGIN_FLAG = re.compile(r"^--experimental\.localplugins\.([A-Za-z0-9_-]+)\.modulename=(\S+)$", re.IGNORECASE)
# The header the app reads by default (`ClientAddress.PORT_HEADER`, veyq.http.client-port.header). The stage hands it
# to the plugin in the middleware's `header` option, as the routing file the app renders does.
CLIENT_ADDR_HEADER = "X-Veyq-Client-Addr"
# What a client writes to name another subscriber behind its own address: two lines, two casings.
FORGED = (("X-Veyq-Client-Addr", "203.0.113.77:4711"), ("x-veyq-client-addr", "198.51.100.66:4712"))
CLIENT_PORT_SECS = 60
ECHO = (
    "require('http').createServer((q, s) => { const b = JSON.stringify({headers: q.headers, raw: q.rawHeaders}); "
    "s.writeHead(200, {'content-type': 'application/json', 'content-length': Buffer.byteLength(b)}); s.end(b); })"
    ".listen(8080)"
)
# One raw connection per route, so the sender knows the exact local address and port Traefik accepted; it polls until
# both routes answer 200 (Traefik loads the file provider after it starts) and prints what each echoed.
SENDER = r"""
const net = require('net');
const cfg = JSON.parse(process.env.CFG);
const once = (path) => new Promise((resolve) => {
  const s = net.connect(cfg.port, cfg.host);
  let local = null, buf = '';
  s.setTimeout(5000, () => s.destroy());
  s.on('connect', () => {
    local = s.localAddress + ':' + s.localPort;
    let req = 'GET ' + path + ' HTTP/1.1\r\nHost: echo.test\r\nConnection: close\r\n';
    for (const [name, value] of cfg.forged) req += name + ': ' + value + '\r\n';
    s.write(req + '\r\n');
  });
  s.on('data', (d) => { buf += d; });
  s.on('close', () => resolve({local, raw: buf}));
  s.on('error', () => {});
});
(async () => {
  const deadline = Date.now() + cfg.secs * 1000;
  let last = {};
  while (Date.now() < deadline) {
    last = {through: await once('/through'), control: await once('/control')};
    if (last.through.raw.startsWith('HTTP/1.1 200') && last.control.raw.startsWith('HTTP/1.1 200')) break;
    await new Promise((r) => setTimeout(r, 1000));
  }
  console.log(JSON.stringify(last));
})();
"""


class TraefikPlugin(NamedTuple):
    """The release's Traefik as its compose file runs it, reduced to what loads the local plugin."""
    image: str
    posture: list[str]  # `docker run` flags for the service's user, capabilities and read-only root
    flags: list[str]  # the `--experimental.localplugins.*` flags, verbatim
    plugin: str  # the name a middleware refers to the plugin by
    mounts: list[tuple[Path, str]]  # (file in the release, target in the container), each mounted read-only


def traefik_plugin(compose_file: Path) -> TraefikPlugin:
    """Read the Traefik service, its local-plugin flag and the plugin's mounts out of the release's compose file — the
    flags are never written a second time here. No Docker: this half runs anywhere."""
    doc = yaml.safe_load(compose_file.read_text(encoding="utf-8")) or {}
    service = (doc.get("services") or {}).get(TRAEFIK_SERVICE) or {}
    if not service.get("image"):
        raise Red(f"{compose_file.name} has no `{TRAEFIK_SERVICE}` service with an image")
    command = [str(c) for c in service.get("command") or []]
    declared = [(m.group(1), m.group(2), c) for c in command for m in [LOCAL_PLUGIN_FLAG.match(c)] if m]
    if len(declared) != 1:
        raise Red(f"{compose_file.name}'s Traefik declares {len(declared)} local plugins, not the one client-address "
                  f"middleware: {[c for _, _, c in declared]}")
    plugin, module, _ = declared[0]
    flags = [c for c in command if c.lower().startswith("--experimental.")]
    mounts: list[tuple[Path, str]] = []
    for entry in service.get("volumes") or []:
        parts = str(entry).split(":")
        if len(parts) < 2 or not parts[1].startswith(PLUGIN_ROOT):
            continue
        if not parts[1].startswith(f"{PLUGIN_ROOT}{module}/") or "ro" not in parts[2:]:
            raise Red(f"{compose_file.name} mounts `{entry}` under {PLUGIN_ROOT} but not read-only under the module {module}")
        mounts.append(((compose_file.parent / parts[0]).resolve(), parts[1]))
    targets = [t for _, t in mounts]
    if not any(t.endswith(".go") for t in targets) or not any(t.endswith("/.traefik.yml") for t in targets):
        raise Red(f"{compose_file.name} must mount both the plugin's .go file and its .traefik.yml under "
                  f"{PLUGIN_ROOT}{module}/; it mounts {targets}")
    missing = [str(s) for s, _ in mounts if not s.is_file()]
    if missing:
        raise Red(f"the release does not carry the plugin file(s) its compose file mounts: {missing}")
    posture: list[str] = []
    if service.get("user"):
        posture += ["--user", str(service["user"])]
    if service.get("read_only"):
        posture.append("--read-only")
    posture += [f"--cap-drop={c}" for c in service.get("cap_drop") or []]
    posture += [f"--security-opt={o}" for o in service.get("security_opt") or []]
    return TraefikPlugin(str(service["image"]), posture, flags, plugin, mounts)


def client_port_routes(plugin: str) -> str:
    """Two routers to one echo: `/through` with the plugin as the app's routing file attaches it, `/control` without."""
    return (
        "http:\n"
        "  routers:\n"
        "    through:\n"
        "      rule: \"PathPrefix(`/through`)\"\n"
        "      entryPoints: [web]\n"
        "      service: echo\n"
        "      middlewares: [client-addr]\n"
        "    control:\n"
        "      rule: \"PathPrefix(`/control`)\"\n"
        "      entryPoints: [web]\n"
        "      service: echo\n"
        "  services:\n"
        "    echo:\n"
        "      loadBalancer:\n"
        "        servers:\n"
        "          - url: \"http://echo:8080\"\n"
        "  middlewares:\n"
        "    client-addr:\n"
        "      plugin:\n"
        f"        {plugin}:\n"
        f"          header: \"{CLIENT_ADDR_HEADER}\"\n"
    )


def echoed(raw: str) -> dict:
    """The echo's JSON body from a raw HTTP/1.1 response, or a Red naming what came back instead."""
    head, _, body = raw.partition("\r\n\r\n")
    if not head.startswith("HTTP/1.1 200"):
        raise Red(f"the route answered {head.splitlines()[0] if head else 'nothing'!r}, not 200")
    try:
        return json.loads(body)
    except json.JSONDecodeError:
        raise Red(f"the echo's body is not JSON: {body[:200]!r}") from None


def client_port(release: Release, project: str, workdir: Path) -> None:
    """The release's own Traefik and plugin, hardened as its compose file runs them, on a run-private network with no
    route out: a client that forges the header must see it replaced by its own address and port, exactly."""
    spec = traefik_plugin(release.tag / "docker-compose.prod.yml")
    node = overlay_image("browser")
    out = workdir / "client-port"
    out.mkdir(mode=0o755)
    routes = out / "routes.yml"
    routes.write_text(client_port_routes(spec.plugin), encoding="utf-8")
    routes.chmod(0o644)  # Traefik reads it as its own non-root uid
    label = ["--label", f"com.docker.compose.project={project}"]
    network = f"{project}-client-port"
    traefik, echo = f"{project}-client-port-traefik", f"{project}-client-port-echo"
    hardened = ["--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges:true"]
    try:
        run("docker", "network", "create", "--internal", *label, network)
        run("docker", "run", "-d", "--name", echo, "--network", network, "--network-alias", "echo", *label, *hardened,
            node, "node", "-e", ECHO)
        run("docker", "run", "-d", "--name", traefik, "--network", network, "--network-alias", "traefik", *label,
            *spec.posture, "-v", f"{out}:/etc/traefik/dynamic:ro",
            *(a for source, target in spec.mounts for a in ("-v", f"{source}:{target}:ro")),
            spec.image, *spec.flags, "--providers.file.directory=/etc/traefik/dynamic", "--entrypoints.web.address=:8000",
            "--log.level=INFO")
        cfg = {"host": "traefik", "port": 8000, "forged": FORGED, "secs": CLIENT_PORT_SECS}
        sent = run("docker", "run", "--rm", "--network", network, *label, *hardened, "-e", f"CFG={json.dumps(cfg)}",
                   node, "node", "-e", SENDER).stdout
        (out / "sender.json").write_text(sent, encoding="utf-8")
        result = json.loads(sent.strip().splitlines()[-1])
    finally:
        logs = run("docker", "logs", traefik, check=False)
        (workdir / "client-port-traefik.log").write_text(logs.stdout + logs.stderr, encoding="utf-8")
        run("docker", "rm", "-f", traefik, echo, check=False)
        run("docker", "network", "rm", network, check=False)

    name = CLIENT_ADDR_HEADER.lower()
    forged = [value for _, value in FORGED]
    through, control = result.get("through") or {}, result.get("control") or {}
    # The control first: without the plugin the forged lines arrive, or the oracle below could not see a forgery.
    seen = str((echoed(control.get("raw", "")).get("headers") or {}).get(name, ""))
    if not all(value in seen for value in forged):
        raise Red(f"control: the route without the plugin echoed {name}={seen!r}, not the forged {forged} — the echo "
                  "would not show a forgery either")
    body = echoed(through.get("raw", ""))
    raw_names = [str(h).lower() for h in (body.get("raw") or [])[0::2]]
    value = (body.get("headers") or {}).get(name)
    if through.get("local") is None or value != through["local"] or raw_names.count(name) != 1:
        raise Red(f"the plugin let {name}={value!r} through ({raw_names.count(name)} line(s)) for a client at "
                  f"{through.get('local')!r} that forged {forged} — it must be exactly the client's own address and port")
    print(f"client port: through the release's Traefik and plugin, a forged {name} became the sender's own "
          f"{value}; without the plugin it arrived as {seen!r}", flush=True)


def run_values(project: str, workdir: Path, mirror: Mirror) -> dict[str, str]:
    """What this run gives the instance's `.env` through `veyqctl install --set`: the operator's values the template
    asks for, its commented-out optionals this run sets, the project, and the overlay's own variables."""
    return {
        "VEYQ_E2E_RELEASE_STAMP": mirror.running,
        "VEYQ_E2E_RELEASE_MIRROR": str(mirror.path),
        **TEMPLATE_VALUES,
        "COMPOSE_PROJECT_NAME": project,
        "ADMIN_PORT": str(ADMIN_PORT),
        "VEYQ_ACME_CASERVER": "https://pebble:14000/dir",
        "VEYQ_E2E_PEBBLE_MINICA": str(workdir / "pebble-minica.pem"),
        "VEYQ_E2E_APP_TRUSTSTORE": str(workdir / APP_TRUSTSTORE),
        "VEYQ_E2E_REPO": str(ROOT),
        "VEYQ_E2E_RUN_DIR": str(workdir),
        "VEYQ_E2E_UID": str(os.getuid()),
        "VEYQ_E2E_GID": str(os.getgid()),
    }


def pebble_compose(release: Release, workdir: Path, values: dict[str, str]) -> list[str]:
    """Pebble's own command: the release's recipe and the overlay, under the run's project, BEFORE there is a `.env`.

    Compose interpolates the whole model even to start one service, so every variable the two files require gets a
    value: the run's own, and for the image lines and the secrets `veyqctl install` writes later a placeholder —
    nothing Pebble's service or the `edge` network reads, so `install`'s `up` finds both as this stage left them (the
    install stage holds it to that: Pebble's container must survive it, or its root is not the one the app trusts).
    """
    import veyqctl  # the one reading of which variables a compose file requires

    recipe = release.tag / "docker-compose.prod.yml"
    required = veyqctl.required_names(recipe.read_text(encoding="utf-8"), OVERLAY.read_text(encoding="utf-8"))
    env = workdir / "pebble.env"
    env.write_text("".join(f"{k}={values.get(k, 'unused-by-the-pebble-stage')}\n"
                           for k in dict.fromkeys([*values, *required])), encoding="utf-8")
    return ["docker", "compose", "-p", values["COMPOSE_PROJECT_NAME"], "-f", str(recipe), "-f", str(OVERLAY),
            "--env-file", str(env)]


def pebble_container(project: str, workdir: Path) -> str:
    return run(*by_name(project), "ps", "-q", "pebble", cwd=workdir, env=name_only_env()).stdout.strip()


class Installed(NamedTuple):
    """What `veyqctl install` left: the instance's home, the release it runs, and the command that addresses it."""
    home: Path
    release: str
    assets: list[str]
    env: Path
    compose: list[str]


def install(release: Release, workdir: Path, values: dict[str, str], pebble_id: str) -> Installed:
    """`veyqctl install --unsigned-local` with the release's own `veyqctl`, as a Server Owner runs it (ADR 0096).

    The overlay is the instance's operator override file (`compose.override.yml`), so every command `veyqctl` runs
    lays it over the recipe; the run's values go in through `--set`; the secrets are `veyqctl`'s. stdin is closed,
    so a value the template asks for that this run does not give is refused by name instead of asked for.
    """
    home = workdir / "home"
    project = values["COMPOSE_PROJECT_NAME"]
    home.mkdir(mode=0o700)
    shutil.copyfile(OVERLAY, home / "compose.override.yml")
    cmd = [sys.executable, str(release.tag / "veyqctl"), "--home", str(home), "--source", str(release.source),
           "install", "--unsigned-local", *(f"--set={k}={v}" for k, v in values.items())]
    print("+", " ".join(cmd), flush=True)
    with (workdir / "install.log").open("w", encoding="utf-8") as log:
        proc = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, env={**os.environ, "VEYQ_WAIT_SECS": str(WAIT_SECS)})
        assert proc.stdout is not None
        for line in proc.stdout:
            print(f"  | {line}", end="", flush=True)
            log.write(line)
        status = proc.wait()
    if status != 0:
        raise Red(f"`veyqctl install` exited {status} — install.log in the run directory says why")
    state = json.loads((home / "state.json").read_text(encoding="utf-8"))
    if state["project"] != project:
        raise Red(f"veyqctl named the instance's project {state['project']!r}, not the run's {project!r}")
    if pebble_container(project, workdir) != pebble_id:
        raise Red("`veyqctl install` recreated Pebble: the root the app trusts is not the one Pebble now issues under")
    installed = home / "releases" / state["release"]
    names = sorted(json.loads((installed / "manifest.json").read_text(encoding="utf-8"))["assets"])
    compose = ["docker", "compose", "-p", project, "-f", str(installed / "docker-compose.prod.yml"),
               "-f", str(home / "compose.override.yml"), "--env-file", str(home / ".env")]
    return Installed(home, state["release"], names, home / ".env", compose)


# ---------------------------------------------------------------- certificate


def by_name(project: str) -> list[str]:
    """The project addressed by its name alone, read from its labels — never through the recipe and overlay.

    Through the files, every command parses what `up` parsed, so a recipe `up` refused (a `${VAR:?…}` with no value)
    is one `logs` and `down` refuse too, and whatever `up` did create would be left running and unread.
    """
    return ["docker", "compose", "-p", project]


def name_only_env() -> dict[str, str]:
    # Run from the run directory with no COMPOSE_FILE, so no compose file is picked up from the environment.
    return {k: v for k, v in os.environ.items() if k not in ("COMPOSE_FILE", "COMPOSE_PROJECT_NAME")}


def evidence(project: str, workdir: Path) -> None:
    with (workdir / "compose.log").open("w", encoding="utf-8") as log:
        subprocess.run([*by_name(project), "logs", "--no-color", "--timestamps"], stdout=log, stderr=subprocess.STDOUT,
                       text=True, cwd=workdir, env=name_only_env())
    ps = run(*by_name(project), "ps", "-a", "--format", "json", check=False, cwd=workdir, env=name_only_env()).stdout
    (workdir / "ps.json").write_text(ps, encoding="utf-8")


def teardown(project: str, workdir: Path) -> None:
    """`down -v` by name (see [by_name]): it removes what carries the project's label, and the host stage proves
    nothing of it is left."""
    run(*by_name(project), "down", "-v", cwd=workdir, env=name_only_env())


def pebble_root(compose: list[str], workdir: Path) -> Path:
    published = run(*compose, "port", "pebble", str(PEBBLE_MANAGEMENT_PORT)).stdout.strip()
    ctx = ssl.create_default_context(cafile=str(workdir / "pebble-minica.pem"))
    url = f"https://{published}/roots/0"
    deadline = time.monotonic() + 30
    while True:
        try:
            with urllib.request.urlopen(url, context=ctx, timeout=5) as response:
                pem = response.read()
            break
        except OSError as error:
            if time.monotonic() > deadline:
                raise Red(f"Pebble's root could not be read from {url}: {error}")
            time.sleep(1)
    root = workdir / "pebble-root.pem"
    root.write_bytes(pem)
    return root


def describe(der: bytes) -> str:
    out = subprocess.run(["openssl", "x509", "-noout", "-subject", "-issuer"], input=ssl.DER_cert_to_PEM_cert(der),
                         text=True, capture_output=True)
    return " / ".join(out.stdout.split("\n")).strip(" /") or "(unreadable)"


def served_certificate() -> str:
    """What :443 serves for the domain, unverified — for the red message only."""
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        with socket.create_connection(("127.0.0.1", 443), timeout=5) as raw:
            with ctx.wrap_socket(raw, server_hostname=DOMAIN) as tls:
                return describe(tls.getpeercert(binary_form=True))
    except OSError as error:
        return f"nothing ({error})"


def pebble(release: Release, workdir: Path, values: dict[str, str], app_image: str) -> str:
    """Pebble first, alone: its root is generated when it starts, and the app must trust it before IT starts."""
    minica = workdir / "pebble-minica.pem"
    copy_out(pebble_image(), {PEBBLE_MINICA_PATH: minica})
    minica.chmod(0o644)  # Traefik reads it as uid 65532
    compose = pebble_compose(release, workdir, values)
    run(*compose, "up", "-d", "--wait", "pebble")
    root = pebble_root(compose, workdir)
    # The candidate's own keytool, as the uid of this run, writing into the run directory; no network.
    run("docker", "run", "--rm", "--network", "none", "--user", f"{os.getuid()}:{os.getgid()}",
        "-v", f"{workdir}:/w", "--entrypoint", "keytool", app_image,
        "-importcert", "-noprompt", "-alias", "veyq-e2e-pebble-root", "-file", f"/w/{root.name}",
        "-keystore", f"/w/{APP_TRUSTSTORE}", "-storetype", "PKCS12", "-storepass", APP_TRUSTSTORE_PASSWORD)
    print(f"pebble: the app trusts {describe(ssl.PEM_cert_to_DER_cert(root.read_text()))} and nothing else", flush=True)
    return pebble_container(values["COMPOSE_PROJECT_NAME"], workdir)


def certificate(workdir: Path) -> None:
    root = workdir / "pebble-root.pem"
    # cafile given → the default trust store is NOT loaded: the run's Pebble root is the only CA.
    ctx = ssl.create_default_context(cafile=str(root))
    deadline = time.monotonic() + CERT_SECS
    while True:
        try:
            with socket.create_connection(("127.0.0.1", 443), timeout=5) as raw:
                with ctx.wrap_socket(raw, server_hostname=DOMAIN) as tls:
                    # A Pebble leaf has an empty subject and names its domain only as a SAN.
                    peer = tls.getpeercert()
                    leaf = (f"SAN {', '.join(v for _, v in peer.get('subjectAltName', ()))} issued by "
                            f"{', '.join('='.join(kv) for rdn in peer['issuer'] for kv in rdn)}")
                    tls.sendall(f"GET /api/setup HTTP/1.1\r\nHost: {DOMAIN}\r\nConnection: close\r\n\r\n".encode())
                    status = tls.recv(64).decode("latin-1").split("\r\n", 1)[0]
            break
        except (OSError, ssl.SSLError) as error:
            if time.monotonic() > deadline:
                raise Red(f"no certificate for {DOMAIN} chaining to the run's Pebble root within {CERT_SECS}s "
                          f"(last: {error}); :443 serves {served_certificate()}")
            time.sleep(2)
    print(f"certificate: {leaf}, verified against {describe(ssl.PEM_cert_to_DER_cert(root.read_text()))}", flush=True)
    if " 200 " not in f"{status} ":
        raise Red(f"the certificate verifies, but GET https://{DOMAIN}/api/setup answered `{status}`, not 200")
    print(f"GET https://{DOMAIN}/api/setup: {status}", flush=True)


# ---------------------------------------------------------------- browser


def browser(compose: list[str], workdir: Path, mirror: Mirror, selection: list[str]) -> None:
    (workdir / "stack.json").write_text(json.dumps({
        "domain": DOMAIN,
        "appOrigin": f"https://{DOMAIN}",
        "traefikHost": TRAEFIK_SERVICE,
        # The same port number an operator's tunnel listens on, inside the container (e2e/src/tunnel.ts).
        "adminOrigin": f"http://localhost:{ADMIN_PORT}",
        "adminUpstream": ADMIN_UPSTREAM,
        "mailApi": MAIL_API,
        "releases": {
            "source": RELEASE_MIRROR_SOURCE,
            "running": mirror.running,
            "newer": mirror.newer,
            "planned": mirror.planned,
            "plannedDate": mirror.planned_date,
            "unscheduled": mirror.unscheduled,
        },
        "outDir": CONTAINER_OUT,
    }, indent=2) + "\n", encoding="utf-8")
    # `selection` is handed to `playwright test` as it is (a local run's `-- --grep …`); CI passes none.
    cmd = [*compose, "run", "--rm", "--no-deps", "-T", "browser", *selection]
    print("+", " ".join(cmd), flush=True)
    with (workdir / "browser.log").open("w", encoding="utf-8") as log:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        assert proc.stdout is not None
        for line in proc.stdout:
            print(f"  | {line}", end="", flush=True)
            log.write(line)
        status = proc.wait()
    tests = junit(workdir / "junit.xml")
    for name, secs, verdict in tests:
        print(f"browser: {secs:6.1f}s  {verdict:<7} {name}", flush=True)
    for name, secs, ran in junit_times(workdir / "junit.xml"):
        print(f"browser: {secs:6.1f}s  {ran} test(s) in {name}", flush=True)
    if status != 0:
        failed = [name for name, _, verdict in tests if verdict == "failed"]
        raise Red(f"Playwright exited {status}" + (f"; failed: {'; '.join(failed)}" if failed else "")
                  + " — browser.log, report/ and the traces under playwright/ in the run directory say where")
    executed = [name for name, _, verdict in tests if verdict in ("passed", "failed")]
    if not executed:
        # Playwright exits 0 on a selection that matches nothing when asked to (`--pass-with-no-tests`), and on a run
        # whose every test was skipped. "Nothing failed" is not "the instance works": a lane that ran no test proves
        # nothing, so it must not render like one whose tests passed (the same refusal run_web_e2e.py makes on zero
        # files, scripts/validate_e2e_discovery.py).
        raise Red(f"Playwright exited 0 but executed no test ({len(tests)} in the JUnit report, none passed or failed)"
                  " — refusing a green that proves nothing")
    print(f"browser: {len(executed)} test(s) executed", flush=True)


def junit_files(path: Path) -> dict[str, int]:
    """Every spec file the JUnit report names, with how many of its tests executed (passed or failed)."""
    if not path.is_file():
        return {}
    files: dict[str, int] = {}
    for suite in ElementTree.parse(path).getroot().iter("testsuite"):
        ran = sum(1 for case in suite.iter("testcase") if case.find("skipped") is None)
        files[suite.get("name", "?")] = files.get(suite.get("name", "?"), 0) + ran
    return files


def junit_times(path: Path) -> list[tuple[str, float, int]]:
    """Per spec file — one per Playwright project here (`setup`, `scenarios`) — its wall time and executed tests."""
    if not path.is_file():
        return []
    times: dict[str, list] = {}
    for suite in ElementTree.parse(path).getroot().iter("testsuite"):
        entry = times.setdefault(suite.get("name", "?"), [0.0, 0])
        entry[0] += float(suite.get("time", "0"))
        entry[1] += sum(1 for case in suite.iter("testcase") if case.find("skipped") is None)
    return [(name, secs, ran) for name, (secs, ran) in times.items()]


def junit(path: Path) -> list[tuple[str, float, str]]:
    """Every test case the JUnit report names, with its time and verdict — empty when there is no report."""
    if not path.is_file():
        return []
    cases = []
    for case in ElementTree.parse(path).getroot().iter("testcase"):
        verdict = ("failed" if case.find("failure") is not None or case.find("error") is not None
                   else "skipped" if case.find("skipped") is not None else "passed")
        cases.append((case.get("name", "?"), float(case.get("time", "0")), verdict))
    return cases


# ---------------------------------------------------------------- the window and the sweeps (ADR 0053)


def env_value(env: Path, name: str) -> str:
    for line in env.read_text(encoding="utf-8").splitlines():
        m = ASSIGNMENT.match(line)
        if m and m.group(1) == name:
            return m.group(2)
    raise Red(f"{name} is not in the run's .env")


def sweep_stack(compose: list[str], project: str, env: Path) -> sweeps.Stack:
    """The run's instance as the sweeps address it: its own compose project, never the dev stack's containers."""
    return sweeps.Stack(
        compose=tuple(compose), db_root_password=env_value(env, "MARIADB_ROOT_PASSWORD"), store_service=STORE_SERVICE,
        store_endpoint=STORE_INTERNAL, store_admin_token=env_value(env, "GARAGE_ADMIN_TOKEN"),
        docker_network=f"{project}_{STORE_NETWORK}",
    )


class Window(NamedTuple):
    """What the window stage opened, and the sweeps read back: when it began, its sentinels, the statement log."""
    since: str
    store_sentinel: str
    statements: sweeps.StatementLog
    statement_sentinels: list[str]


def plant_store_sentinel(stamp: str, workdir: Path) -> str:
    """One unsigned GET for the run's sentinel key, the way a browser reaches the bucket: through Traefik on :443.

    The public origin routes `/<bucket>/…` to Garage (the recipe's ingress, ADR 0063), so this is the path every
    presigned URL of the run takes. Over TLS with the run's Pebble root as the only CA — the certificate stage's
    connection, by SNI, no `/etc/hosts`. Garage refuses it (anonymous access) and logs the URI as sent.
    """
    sentinel, key = sweeps.store_sentinel(stamp)
    ctx = ssl.create_default_context(cafile=str(workdir / "pebble-root.pem"))
    with socket.create_connection(("127.0.0.1", 443), timeout=10) as raw:
        with ctx.wrap_socket(raw, server_hostname=DOMAIN) as tls:
            tls.sendall(f"GET /{STORE_BUCKET}/{key} HTTP/1.1\r\nHost: {DOMAIN}\r\nConnection: close\r\n\r\n".encode())
            status = tls.recv(64).decode("latin-1").split("\r\n", 1)[0]
    print(f"window: planted the store-log sentinel {sentinel!r} through https://{DOMAIN}/{STORE_BUCKET}/…: {status}",
          flush=True)
    return sentinel


def window(stack: sweeps.Stack, stamp: str, workdir: Path, since: str) -> Window:
    """Open what must be open BEFORE the browser types anything: the statement log, and the store's log control."""
    sentinel = plant_store_sentinel(stamp, workdir)
    statements = sweeps.StatementLog(stack, stamp, workdir)
    statements.open()
    return Window(since, sentinel, statements, [statements.plant("window opened")])


def close_window(opened: Window) -> None:
    """The second sentinel, then the statement log off and retained. Idempotent; runs whether the browser was red."""
    if not opened.statements.closed:
        opened.statement_sentinels.append(opened.statements.plant("window closing"))
        opened.statements.close()


GUEST_WRITE_SPEC = "guest-write-record.spec.ts"
UUID_SHAPE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


def guest_write_record(stack: sweeps.Stack, workdir: Path) -> None:
    """The app's record of a guest's write equals what the browser's own socket was (stage 7b, ADR 0094)."""
    ran = {Path(name).name: n for name, n in junit_files(workdir / "junit.xml").items()}
    if not ran.get(GUEST_WRITE_SPEC):
        print(f"guest-write record: skipped — the selection ran no test of {GUEST_WRITE_SPEC}", flush=True)
        return
    expected_file = workdir / "guest-write-record.json"
    if not expected_file.is_file():
        raise Red(f"{GUEST_WRITE_SPEC} ran and left no {expected_file.name}: the write it records was never made")
    expected = json.loads(expected_file.read_text(encoding="utf-8"))
    object_id = str(expected.get("objectId", ""))
    if not UUID_SHAPE.match(object_id):
        raise Red(f"{expected_file.name} names no node id ({object_id!r})")
    sockets = expected.get("sockets") or []
    if not sockets:
        raise Red(f"{expected_file.name} holds no socket of the browser to Traefik")
    rows = subprocess.run(
        stack.mariadb("mariadb", "-N", "-B", "-e",
                      "SELECT object_kind, IFNULL(field_group, '-'), client_ip, IFNULL(client_port, '-') "
                      f"FROM veyq_guest_write_record WHERE object_id = '{object_id}';", DATABASE),
        check=True, capture_output=True, text=True,
    ).stdout.split("\n")
    rows = [r.split("\t") for r in rows if r.strip()]
    if len(rows) != 1:
        raise Red(f"the guest's create of {object_id} left {len(rows)} record row(s), not one: {rows}")
    kind, group, ip, port = rows[0]
    own = {(s["localIp"], int(s["localPort"])) for s in sockets}
    if (kind, group) != ("NODE", "name"):
        raise Red(f"the record names {kind}/{group}, not NODE/name")
    if ip not in {s_ip for s_ip, _ in own}:
        raise Red(f"the record holds address {ip}, not the browser's own ({sorted(own)})")
    if port == "-" or (ip, int(port)) not in own:
        raise Red(f"the record holds port {port}, not one of the browser's own sockets to Traefik ({sorted(own)})")
    print(f"guest-write record: the app recorded {ip}:{port} for the guest's create — the browser's own socket, of "
          f"{len(own)} it held to Traefik {expected.get('traefik')}:443", flush=True)


def sweep(stack: sweeps.Stack, workdir: Path, opened: Window) -> None:
    """Every typed canary of the run, swept everywhere an operator of this instance could read it (ADR 0053).

    The browser suite records what it typed (`e2e/src/canaries.ts`), one `canaries-<spec>.json` per spec file; every
    spec file that executed a test must have left one, or that file's values were never looked for. Then: the
    instance's database (data-only dump), its bucket (keys and bytes), every container log of the project since the
    run began, and every statement the database received in the window. Each is armed by a positive control — the
    owner's email (declared plaintext the app wrote) found in the dump and in a statement literal, the planted sentinel
    in the store's log, the app's log naming its database, and the window's two sentinel statements — because 0 hits
    from a sensor that read nothing is not a pass.

    The `pii` set (ADR 0092 § 4): every address an account was signed up with (`freshOwner` records it,
    `e2e/src/accounts.ts`) is searched in every container log, and nowhere else — the dump, the statement log and the
    mailbox hold it by design. Its control ([sweeps.pii_control]): at least one, and each found in the statement log,
    which holds every account the instance created in the window; this lane reads no mailbox (below), so the mailbox
    control `run_web_e2e.py` uses is not open here.

    Not swept here, and why: the HTTP recording and the capability tokens derived from it (the browser suite records no
    HTTP; `run_web_e2e.py` sweeps both at API level), boot logs (the backend's log is the `app` container's, swept as
    one), and the mailbox: the overlay's Mailpit (the relay a provider would give the instance) is reached only from the
    run's own networks, and what reaches it is the instance mail a feature scenario sets up — a code or link the server
    minted, an invitation or a notice, never a value the suite typed.
    """
    close_window(opened)
    canary_files = sorted(workdir.glob("canaries-*.json"))
    silent = [name for name, ran in junit_files(workdir / "junit.xml").items()
              if ran and not (workdir / f"canaries-{Path(name).name.removesuffix('.ts')}.json").is_file()]
    if silent:
        raise Red(f"spec file(s) {silent} executed tests but recorded no canary — what they typed was never swept for "
                  "(every typed value goes through recordTyped, e2e/src/canaries.ts)")
    carriers, problems = sweeps.load_canaries(canary_files)
    if problems or not carriers:
        raise Red("the run's canaries could not be read: " + "; ".join(problems or ["the canary files hold none"]))
    union = workdir / "swept-canaries.json"
    union.write_text(json.dumps(sorted(carriers), indent=2, ensure_ascii=False), encoding="utf-8")
    instance = json.loads((workdir / "instance.json").read_text(encoding="utf-8"))
    email = instance["owner"]["email"]
    print(f"sweep: {len(carriers)} typed canary/canaries from {', '.join(p.name for p in canary_files)}", flush=True)
    # The third needle set: every address an account was signed up with (`recordPii`, e2e/src/accounts.ts), searched
    # in the container logs only — the database, the statement log and the mailbox hold it by design (ADR 0092 § 4).
    pii_files = sorted(workdir.glob("pii-*.json"))
    pii, pii_problems = sweeps.load_pii(pii_files)
    print(f"sweep: {len(pii)} pii address(es) from {', '.join(p.name for p in pii_files) or 'no file'}", flush=True)

    rel = "the browser suite"
    verdicts = [
        ("database", sweeps.sweep(stack, DATABASE, union, rel, retain=workdir / f"database-{DATABASE}.sql",
                                  present=[email])),
        ("store", sweeps.sweep_store(stack, STORE_BUCKET, DATABASE, union, rel, workdir)),
        ("container logs", sweeps.sweep_containers(
            stack, workdir, canary_files, opened.since, opened.store_sentinel,
            controls={APP_SERVICE: lambda text: None if sweeps.names_database(text, DATABASE) else
                      f"the app's log never names its database {DATABASE} (Flyway's JDBC line) — not the whole log"},
            pii=pii)),
        ("statement log", sweeps.sweep_statements(
            opened.statements.retained, canary_files, opened.statement_sentinels, databases=[],
            problems=opened.statements.problems, written=[email])),
        # The pii set's control: zero addresses is a failure, and each must be one the instance wrote — this lane reads
        # no mailbox (above), and the statement log holds every account the instance created during the run.
        ("pii control", "; ".join(pii_problems) or sweeps.pii_control(
            pii_files, [opened.statements.retained], "the statement log")),
    ]
    failed = [f"{surface}: {why}" for surface, why in verdicts if why]
    if failed:
        raise Red("the wire assertion failed — " + " | ".join(failed))
    print(f"sweep: GREEN on {', '.join(surface for surface, _ in verdicts)}", flush=True)


# ---------------------------------------------------------------- the run


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--app", required=True, help="the candidate app image")
    parser.add_argument("--admin", required=True, help="the candidate admin image")
    parser.add_argument("--keep", action="store_true", help="leave the stack running (no teardown)")
    parser.add_argument("selection", nargs=argparse.REMAINDER,
                        help="after `--`: handed to `playwright test` as it is (e.g. `-- --grep @uc.admin`)")
    args = parser.parse_args()
    selection = args.selection[1:] if args.selection[:1] == ["--"] else args.selection

    stamp = time.strftime("%Y%m%d-%H%M%S")
    since = str(int(time.time()))
    workdir = RUN_ROOT / stamp
    workdir.mkdir(parents=True)
    project = f"veyq-e2e-{stamp}"
    stages: list[tuple[str, float, str]] = []
    red: list[str] = []

    def stage(name: str, fn, *fargs, always: bool = False):
        if red and not always and name not in ("evidence", "teardown", "host"):
            stages.append((name, 0.0, "skipped"))
            return None
        print(f"\n== {name}", flush=True)
        start = time.monotonic()
        try:
            value = fn(*fargs)
            stages.append((name, time.monotonic() - start, "ok"))
            return value
        except Red as error:
            stages.append((name, time.monotonic() - start, "RED"))
            red.append(f"{name}: {error}")
            print(f"RED at {name}: {error}", flush=True)
            return None

    before = stage("preflight", lambda: (ports_free(), browser_pins(), host_snapshot())[2])
    stage("browser image", browser_image)
    release = stage("assets", assets, args.app, args.admin, workdir)
    mirror = stage("release mirror", release_mirror, release, args.app, args.admin, workdir) if release else None
    values = run_values(project, workdir, mirror) if mirror else {}
    started = False
    opened = None
    installed = None
    try:
        if release and mirror:
            started = not red
            stage("client port", client_port, release, project, workdir)
            pebble_id = stage("pebble", pebble, release, workdir, values, args.app)
            installed = stage("install", install, release, workdir, values, pebble_id)
            stage("certificate", certificate, workdir)
            compose = installed.compose if installed else []
            stack = sweep_stack(compose, project, installed.env) if installed else None
            opened = stage("window", window, stack, stamp, workdir, since)
            stage("browser", browser, compose, workdir, mirror, selection)
            stage("guest-write record", guest_write_record, stack, workdir)
            stage("sweep", sweep, stack, workdir, opened)
    finally:
        if opened is not None:
            # Retained even when the browser was red: the statement log is evidence either way.
            stage("window close", close_window, opened, always=True)
        if started:
            stage("evidence", evidence, project, workdir)
            if not args.keep:
                stage("teardown", teardown, project, workdir)
        if before is not None:
            stage("host", lambda: host_untouched(before, host_snapshot(), project, started and not args.keep))

    print(f"\n== summary — {project}, run directory {workdir.relative_to(ROOT)}")
    for name, secs, verdict in stages:
        print(f"  {name:<13} {secs:7.1f}s  {verdict}")
        if name == "browser":
            for spec, spec_secs, ran in junit_times(workdir / "junit.xml"):
                print(f"    {spec_secs:7.1f}s  {ran} test(s) in {spec}")
    print(f"  {'total':<13} {sum(s for _, s, _ in stages):7.1f}s")
    if installed:
        print(f"installed by veyqctl --unsigned-local (NOTHING VERIFIED): release {installed.release} into "
              f"{installed.home.relative_to(ROOT)}, assets {', '.join(installed.assets)}")
    if red:
        print("RED\n  " + "\n  ".join(red))
        return 1
    print("GREEN")
    return 0


if __name__ == "__main__":
    sys.exit(main())
