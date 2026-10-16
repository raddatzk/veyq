#!/usr/bin/env python3
"""Boot the release candidate as a Server Owner would, from the recipe inside its image, and drive it in a browser (ADR 0090, lane L1 of ADR 0091).

**What it does — phases 1 and 2 of `changes/cr-stack-e2e.md`: a boot, then first run in a browser.** Takes the two candidate images (`--app`,
`--admin`: a registry reference pinned by digest, or a local tag for a local run) and from there follows the Run area
of the docs site rather than this repository:

  1. **extract** — the recipe comes out of the app image (`/opt/veyq/deploy/`, ADR 0057 §3), never
     `infra/docker-compose.prod.yml` from this checkout: what is tested is the file that ships. One file still comes
     from the checkout, `garage.toml`, because the image does not carry it — the limit `run/new-instance.mdx` states
     for every operator; the summary says so on every run. Pebble's minica root is copied out of the overlay's
     pinned Pebble image, for Traefik's ACME client to trust Pebble's listener.
  2. **env** — the run's `.env` is the image's own `.env.example`, line by line, with every value this run knows
     filled in and every secret random. A key the template gains and this script does not fill is LEFT as the
     template has it, so a required one fails the `up` with the recipe's own `${VAR:?…}` message, named — the drift
     shows up as a red run, not a silent default. The other direction is refused here: a value this script fills
     that the template no longer names is red at this stage.
  3. **pebble** — Pebble alone first, under a project of its own, `veyq-e2e-<stamp>`, so it cannot address an
     instance on this host. Its root is generated when it starts, and the app must trust it before IT starts: this
     stage reads the root off Pebble's management port and writes the app's truststore with it — Pebble's root and
     nothing else, written by the candidate image's own `keytool` (the overlay says why the app needs it).
  4. **up** — `docker compose up -d --wait` with `infra/e2e/docker-compose.e2e.yml` laid over the recipe (Pebble,
     the browser behind a profile, and the DNS name and CA trust the internet would give the instance).
  5. **certificate** — opens TLS to `:443` for `veyq.test` (SNI, no `/etc/hosts`) with the run's Pebble root as the
     ONLY trusted CA; then `GET /api/setup` over the
     verified connection must answer 200. Traefik serves its default certificate until ACME has finished, so this
     polls until `VEYQ_STACK_CERT_SECS`; at the deadline it names the certificate Traefik served instead. A Traefik
     that cannot write `/acme` (ADR 0071's silent failure) is red here, not up.
  6. **browser** — `e2e/`'s Playwright suite, whole, in the Playwright image the overlay pins, started with
     `docker compose run --rm browser` on the run's own `edge` and `admin` networks — never on the host, so a laptop
     runs what CI runs. The container first trusts the run's Pebble root (and nothing more) in Chromium's NSS db
     (`infra/e2e/nss_trust_root.py`); the suite reaches the domain through Traefik and the admin console through an
     in-container loopback tunnel to `admin:8080` (`e2e/src/tunnel.ts`), and everything it may reach is named in
     `stack.json`, which this stage writes. Its exit code is the verdict; the JUnit report's tests and times are
     printed. Preflight refuses a run whose installed `@playwright/test` is not exactly the image's version (the
     browser is baked into the image; a library one patch apart fails at launch) or is not installed at all.
  7. **evidence** — `compose.log` (every container, timestamped) and `ps.json` into the run directory, always.
  8. **teardown** — `docker compose down -v` (unless `--keep`).
  9. **host** — the run touched nothing that is not its own: every container that existed before it still exists
     with the same state and start time, every volume still exists, and nothing of the run's project is left.

The summary prints each stage's wall time — the budget ADR 0091 decision 1 holds the lane to — and the verdict.
The sweeps of ADR 0053 are a later phase of the CR.

**The run directory survives the run** (`.e2e-stack-out/<stamp>/`, gitignored): the extracted recipe, the `.env`
(every secret of an instance that no longer exists), Pebble's root, `up.log`, `compose.log`, `ps.json`, and the
browser's: `stack.json`, `browser.log`, `junit.xml`, `report/` (Playwright's HTML report), `playwright/` (a trace per
test, `npx playwright show-trace <zip>`), `instance.json` (the owner's credentials and Secret Key, the invite link).

**Not a `validate_*` guard.** It needs Docker, two images, host ports 80 and 443 and the root `npm ci`, so it
cannot join the buildless trust-chain glob; lane L1 calls it directly.

**Its limits — what a green run does NOT prove.** Pebble is told every challenge is valid (the overlay says why), so
Traefik's HTTP-01 router on :80 is not exercised. The certificate stage asserts the bootstrap domain; the tenant's
domain is asserted by the browser, which opens the invite link over it. The browser reaches Traefik on the run's
`edge` network, not through the host's published :443 — the published ports themselves are exercised by the
certificate stage only. The host check compares before and after: it
cannot tell this run's touch from another process's on the same host in the same window, so a red there names the
container and leaves the attribution to the reader; and a container created AND removed by someone during the run is
invisible to it.

Usage:
    python3 scripts/run_stack_e2e.py --app veyq:local --admin veyq-admin:local [--keep]

Environment (all optional):
    VEYQ_STACK_WAIT_SECS   `up --wait` timeout. Default: 300
    VEYQ_STACK_CERT_SECS   How long the certificate stage polls for Pebble's certificate. Default: 120
"""
from __future__ import annotations

import argparse
import json
import os
import re
import secrets
import socket
import ssl
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ElementTree
from pathlib import Path

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

ROOT = Path(__file__).resolve().parent.parent
OVERLAY = ROOT / "infra" / "e2e" / "docker-compose.e2e.yml"
# The one file the image's recipe mounts and does not carry (site/src/content/docs/run/new-instance.mdx).
GARAGE_TOML = ROOT / "infra" / "garage.toml"
RUN_ROOT = ROOT / ".e2e-stack-out"
RECIPE_DIR = "/opt/veyq/deploy"
RECIPE_FILES = ("docker-compose.prod.yml", ".env.example")
E2E_PACKAGE = ROOT / "e2e" / "package.json"
INSTALLED_PLAYWRIGHT = ROOT / "node_modules" / "@playwright" / "test" / "package.json"
# What the browser container is told it may reach (e2e/src/stack.ts). Paths are the container's: the run directory
# is mounted at /out.
ADMIN_UPSTREAM = "admin:8080"
TRAEFIK_SERVICE = "traefik"
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
MISSING_VARIABLE = re.compile(r"required variable (\w+) is missing a value")


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


# ---------------------------------------------------------------- extract, env


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


def extract(app_image: str, workdir: Path) -> Path:
    recipe = workdir / "recipe"
    recipe.mkdir(parents=True)
    copy_out(app_image, {f"{RECIPE_DIR}/{name}": recipe / name for name in RECIPE_FILES})
    (recipe / "garage.toml").write_bytes(GARAGE_TOML.read_bytes())
    print(f"recipe: {', '.join(RECIPE_FILES)} from {app_image}; garage.toml from this checkout (the image does not "
          "carry it — run/new-instance.mdx's stated limit)", flush=True)
    minica = workdir / "pebble-minica.pem"
    copy_out(pebble_image(), {PEBBLE_MINICA_PATH: minica})
    minica.chmod(0o644)  # Traefik reads it as uid 65532
    return recipe


def write_env(recipe: Path, app_image: str, admin_image: str, minica: Path) -> Path:
    """The template, filled: every value this run knows, every secret random; anything else left as the template has it."""
    fill = {
        "VEYQ_DOMAIN": DOMAIN,
        "VEYQ_IMAGE": app_image,
        "VEYQ_ADMIN_IMAGE": admin_image,
        "MARIADB_PASSWORD": secrets.token_urlsafe(24),
        "MARIADB_ROOT_PASSWORD": secrets.token_urlsafe(24),
        "GARAGE_RPC_SECRET": secrets.token_hex(32),
        "GARAGE_ADMIN_TOKEN": secrets.token_urlsafe(32),
        "VEYQ_ACME_EMAIL": f"e2e@{DOMAIN}",
    }
    # The template's commented-out optionals this run sets, and the overlay's own variable.
    own = {
        "ADMIN_PORT": str(ADMIN_PORT),
        "VEYQ_ACME_CASERVER": "https://pebble:14000/dir",
        "VEYQ_E2E_PEBBLE_MINICA": str(minica),
        "VEYQ_E2E_APP_TRUSTSTORE": str(minica.parent / APP_TRUSTSTORE),
        "VEYQ_E2E_REPO": str(ROOT),
        "VEYQ_E2E_RUN_DIR": str(minica.parent),
        "VEYQ_E2E_UID": str(os.getuid()),
        "VEYQ_E2E_GID": str(os.getgid()),
    }
    lines, named, left = [], set(), []
    for line in (recipe / ".env.example").read_text(encoding="utf-8").splitlines():
        m = ASSIGNMENT.match(line)
        if m and m.group(1) in fill:
            named.add(m.group(1))
            line = f"{m.group(1)}={fill[m.group(1)]}"
        elif m and m.group(1) not in own:
            left.append(m.group(1) if m.group(2) else f"{m.group(1)} (empty)")
        lines.append(line)
    gone = sorted(set(fill) - named)
    if gone:
        raise Red(f"this run fills {gone}, which the image's .env.example no longer names — the recipe moved on")
    lines += ["", "# ---- set by scripts/run_stack_e2e.py, not by the template ----"]
    lines += [f"{k}={v}" for k, v in own.items()]
    env = recipe / ".env"
    env.write_text("\n".join(lines) + "\n", encoding="utf-8")
    env.chmod(0o600)
    if left:
        print(f"env: left as the template has them: {', '.join(left)}", flush=True)
    return env


# ---------------------------------------------------------------- up, certificate


def compose_cmd(project: str, recipe: Path, env: Path) -> list[str]:
    return ["docker", "compose", "-p", project, "-f", str(recipe / "docker-compose.prod.yml"), "-f", str(OVERLAY),
            "--env-file", str(env)]


def up(compose: list[str], workdir: Path) -> None:
    result = run(*compose, "up", "-d", "--wait", "--wait-timeout", str(WAIT_SECS), check=False)
    (workdir / "up.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode != 0:
        missing = MISSING_VARIABLE.search(result.stderr)
        if missing:
            raise Red(f"required variable {missing.group(1)} has no value — the image's .env.example asks for "
                      f"something this run does not fill: {result.stderr.strip().splitlines()[-1]}")
        raise Red(f"`up --wait` exited {result.returncode}: {result.stderr.strip()[-2000:]}")


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


def pebble(compose: list[str], workdir: Path, app_image: str) -> None:
    run(*compose, "up", "-d", "--wait", "pebble")
    root = pebble_root(compose, workdir)
    # The candidate's own keytool, as the uid of this run, writing into the run directory; no network.
    run("docker", "run", "--rm", "--network", "none", "--user", f"{os.getuid()}:{os.getgid()}",
        "-v", f"{workdir}:/w", "--entrypoint", "keytool", app_image,
        "-importcert", "-noprompt", "-alias", "veyq-e2e-pebble-root", "-file", f"/w/{root.name}",
        "-keystore", f"/w/{APP_TRUSTSTORE}", "-storetype", "PKCS12", "-storepass", APP_TRUSTSTORE_PASSWORD)
    print(f"pebble: the app trusts {describe(ssl.PEM_cert_to_DER_cert(root.read_text()))} and nothing else", flush=True)


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


def browser(compose: list[str], workdir: Path) -> None:
    (workdir / "stack.json").write_text(json.dumps({
        "domain": DOMAIN,
        "appOrigin": f"https://{DOMAIN}",
        "traefikHost": TRAEFIK_SERVICE,
        # The same port number an operator's tunnel listens on, inside the container (e2e/src/tunnel.ts).
        "adminOrigin": f"http://localhost:{ADMIN_PORT}",
        "adminUpstream": ADMIN_UPSTREAM,
        "outDir": CONTAINER_OUT,
    }, indent=2) + "\n", encoding="utf-8")
    cmd = [*compose, "run", "--rm", "--no-deps", "-T", "browser"]
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
    if status != 0:
        failed = [name for name, _, verdict in tests if verdict == "failed"]
        raise Red(f"Playwright exited {status}" + (f"; failed: {'; '.join(failed)}" if failed else "")
                  + " — browser.log, report/ and the traces under playwright/ in the run directory say where")


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


# ---------------------------------------------------------------- the run


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--app", required=True, help="the candidate app image")
    parser.add_argument("--admin", required=True, help="the candidate admin image")
    parser.add_argument("--keep", action="store_true", help="leave the stack running (no teardown)")
    args = parser.parse_args()

    stamp = time.strftime("%Y%m%d-%H%M%S")
    workdir = RUN_ROOT / stamp
    workdir.mkdir(parents=True)
    project = f"veyq-e2e-{stamp}"
    stages: list[tuple[str, float, str]] = []
    red: list[str] = []

    def stage(name: str, fn, *fargs):
        if red and name not in ("evidence", "teardown", "host"):
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
    recipe = stage("extract", extract, args.app, workdir)
    env = stage("env", write_env, recipe, args.app, args.admin, workdir / "pebble-minica.pem") if recipe else None
    compose = compose_cmd(project, recipe, env) if env else None
    started = False
    try:
        if compose:
            started = not red
            stage("pebble", pebble, compose, workdir, args.app)
            stage("up", up, compose, workdir)
            stage("certificate", certificate, workdir)
            stage("browser", browser, compose, workdir)
    finally:
        if started:
            stage("evidence", evidence, project, workdir)
            if not args.keep:
                stage("teardown", teardown, project, workdir)
        if before is not None:
            stage("host", lambda: host_untouched(before, host_snapshot(), project, started and not args.keep))

    print(f"\n== summary — {project}, run directory {workdir.relative_to(ROOT)}")
    for name, secs, verdict in stages:
        print(f"  {name:<12} {secs:7.1f}s  {verdict}")
    print(f"  {'total':<12} {sum(s for _, s, _ in stages):7.1f}s")
    if red:
        print("RED\n  " + "\n  ".join(red))
        return 1
    print("GREEN")
    return 0


if __name__ == "__main__":
    sys.exit(main())
