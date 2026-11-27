#!/usr/bin/env python3
"""The stack lanes' shared primitives: an instance installed from a release's own recipe by its own `veyqctl`, on a
run-private compose project, and the browser container on its networks (ADR 0090, ADR 0091 decision 3).

Lifted out of lane L1's orchestrator (`scripts/run_stack_e2e.py`) when lane L2 (`scripts/run_stack_upgrade.py`)
needed the same stack: the run directory, the host's before and after, Pebble and the app's truststore, `veyqctl
install` with the overlay as the instance's override file, the certificate over `:443`, the browser container, the
evidence and the teardown. Each lane keeps its own stages, docstring and summary; what they share is here, once, so
L2 installs and drives an instance exactly as L1 does. Nothing here knows which release it installs: a `Release` is a
mirror directory and the tag in it, whether `release_assets.py` produced it from the candidate (L1, `--unsigned-local`)
or `gh release download` fetched a published one (L2, verified under the release identity).

**Not a `validate_*` guard**, and runs nothing on import: it needs Docker, which the lanes that import it check for.
"""
from __future__ import annotations

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
from typing import Callable, NamedTuple

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

import repo_layout  # noqa: E402 - where the deployment files and the test harnesses live (ADR 0106)

ROOT = Path(__file__).resolve().parent.parent
OVERLAY = repo_layout.E2E_STACK_DIR / "docker-compose.e2e.yml"
RUN_ROOT = ROOT / ".e2e-stack-out"
E2E_PACKAGE = repo_layout.E2E_DIR / "package.json"
# pnpm links a package only into the node_modules of the workspace member that declares it — tests/e2e/ here.
INSTALLED_PLAYWRIGHT = repo_layout.E2E_DIR / "node_modules" / "@playwright" / "test" / "package.json"
# What the browser container is told it may reach (tests/e2e/src/stack.ts). Paths are the container's: the run directory
# is mounted at /out.
ADMIN_UPSTREAM = "admin:8080"
TRAEFIK_SERVICE = "traefik"
# The overlay's Mailpit on the run's `admin` network, by the alias a Server Owner types as the SMTP host: its HTTP API,
# which the suite reads the mail the instance sent from (tests/e2e/stack/docker-compose.e2e.yml says why it is there).
MAIL_API = "http://mail.relay.test:8025"
CONTAINER_OUT = "/out"
APP_TRUSTSTORE = "app-truststore.p12"
# Not a secret: the store holds one public certificate, and Java asks for a password (the overlay names it too).
# keytool reads it from the variable TRUSTSTORE_VARIABLE names (`-storepass:env`), handed to `docker run -e` by name,
# so no command line the run prints carries it. That name holds no word CodeQL's clear-text-logging query takes for a
# password, since it is printed (alert 43 on #111 traced the flow from the variable's name, not from the value).
APP_TRUSTSTORE_PASSWORD = "veyq-e2e"
TRUSTSTORE_VARIABLE = "VEYQ_E2E_TRUSTSTORE_STOREPASS"
PEBBLE_MINICA_PATH = "/test/certs/pebble.minica.pem"
PEBBLE_MANAGEMENT_PORT = 15000
DOMAIN = "veyq.test"
# Not the recipe's default 8081: on a developer's machine that port is the dev backend's, and a run must never answer
# on a port the developer's own instance uses.
ADMIN_PORT = int(os.environ.get("VEYQ_STACK_ADMIN_PORT", "18081"))
# The recipe publishes Traefik on these, so they belong to the run (ADR 0090 § Consequences).
HOST_PORTS = (80, 443, ADMIN_PORT)
WAIT_SECS = int(os.environ.get("VEYQ_STACK_WAIT_SECS", "300"))
CERT_SECS = int(os.environ.get("VEYQ_STACK_CERT_SECS", "120"))
ASSIGNMENT = re.compile(r"^([A-Z][A-Z0-9_]*)=(.*)$")


class Red(Exception):
    """A stage's verdict: the run is red, and this is why."""


def run(*cmd: str, check: bool = True, capture: bool = True, **kw) -> subprocess.CompletedProcess:
    print("+", " ".join(cmd), flush=True)
    result = subprocess.run(cmd, text=True, capture_output=capture, **kw)
    if check and result.returncode != 0:
        raise Red(f"`{' '.join(cmd[:4])} …` exited {result.returncode}: {(result.stderr or '').strip()[-2000:]}")
    return result


def tls_context(cafile: Path | None = None) -> ssl.SSLContext:
    """Every TLS client context of this run: TLS 1.2 at the least on each Python it runs on. 3.10+ sets that floor
    itself (bpo-43998); 3.9 takes its TLS library's, and macOS's system 3.9 (LibreSSL 2.8.3) allows TLS 1.0."""
    ctx = ssl.create_default_context(cafile=None if cafile is None else str(cafile))
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    return ctx



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


# ---------------------------------------------------------------- the release, pebble, install


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
        raise Red("@playwright/test is not installed — run `python3 scripts/pnpm_install_no_deprecated.py` at the repository "
                  "root first (the browser container installs nothing; it mounts that install read-only)")
    installed = json.loads(INSTALLED_PLAYWRIGHT.read_text(encoding="utf-8"))["version"]
    image = overlay_image("browser")
    tag = re.search(r":v(\d+\.\d+\.\d+)-", image)
    versions = {"tests/e2e/package.json": pinned, "node_modules": installed, "the overlay's image": tag.group(1) if tag else image}
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
    """A release as files: a mirror directory `veyqctl --source` reads, and the one release in it (`release_assets.py`
    wrote the candidate's, L1; `gh release download` fetched a published one, L2)."""
    source: Path
    tag: Path  # <source>/<tag>/, the release's assets


def pebble_compose(release: Release, workdir: Path, values: dict[str, str], overlay: Path = OVERLAY) -> list[str]:
    """Pebble's own command: the release's recipe and the overlay, under the run's project, BEFORE there is a `.env`.

    Compose interpolates the whole model even to start one service, so every variable the two files require gets a
    value: the run's own, and for the image lines and the secrets `veyqctl install` writes later a placeholder —
    nothing Pebble's service or the `edge` network reads, so `install`'s `up` finds both as this stage left them (the
    install stage holds it to that: Pebble's container must survive it, or its root is not the one the app trusts).
    """
    repo_layout.tools_importable()
    import veyqctl  # the one reading of which variables a compose file requires

    recipe = release.tag / "docker-compose.prod.yml"
    required = veyqctl.required_names(recipe.read_text(encoding="utf-8"), overlay.read_text(encoding="utf-8"))
    env = workdir / "pebble.env"
    env.write_text("".join(f"{k}={values.get(k, 'unused-by-the-pebble-stage')}\n"
                           for k in dict.fromkeys([*values, *required])), encoding="utf-8")
    return ["docker", "compose", "-p", values["COMPOSE_PROJECT_NAME"], "-f", str(recipe), "-f", str(overlay),
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
    project: str
    unsigned_local: bool  # `state.json`'s record of how the running release was installed


def install(release: Release, workdir: Path, values: dict[str, str], pebble_id: str, *, verified: bool,
            override: Path = OVERLAY, flags: tuple[str, ...] = ()) -> Installed:
    """`veyqctl install` with the release's own `veyqctl`, as a Server Owner runs it (ADR 0096): `--unsigned-local`
    for a candidate built here (L1), verified under the release identity for a published release (`verified`, L2).

    `override` (the overlay, or a lane's reading of it) is the instance's operator override file
    (`compose.override.yml`), so every command `veyqctl` runs lays it over the recipe; the run's values go in through
    `--set`; the secrets are `veyqctl`'s. stdin is closed, so a value the template asks for that this run does not
    give is refused by name instead of asked for. `flags` go to `install` as they are (L2's `-v`, where the release's
    `veyqctl` has it).
    """
    home = workdir / "home"
    project = values["COMPOSE_PROJECT_NAME"]
    home.mkdir(mode=0o700)
    shutil.copyfile(override, home / "compose.override.yml")
    cmd = [sys.executable, str(release.tag / "veyqctl"), "--home", str(home), "--source", str(release.source),
           "install", *flags, *([] if verified else ["--unsigned-local"]), *(f"--set={k}={v}" for k, v in values.items())]
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
    installed = addressed(home)
    if installed.unsigned_local == verified:
        raise Red(f"`veyqctl install` recorded unsignedLocal={installed.unsigned_local}, not what this run asked for")
    if installed.project != project:
        raise Red(f"veyqctl named the instance's project {installed.project!r}, not the run's {project!r}")
    if pebble_container(project, workdir) != pebble_id:
        raise Red("`veyqctl install` recreated Pebble: the root the app trusts is not the one Pebble now issues under")
    return installed


def addressed(home: Path) -> Installed:
    """The instance in `home` as `veyqctl` left it — after an install, an upgrade or a rollback alike: the release
    `state.json` names, and the command that addresses it as `veyqctl` does (the recipe of that release, the override
    file, `.env`)."""
    state = json.loads((home / "state.json").read_text(encoding="utf-8"))
    installed = home / "releases" / state["release"]
    names = sorted(json.loads((installed / "manifest.json").read_text(encoding="utf-8"))["assets"])
    compose = ["docker", "compose", "-p", state["project"], "-f", str(installed / "docker-compose.prod.yml"),
               "-f", str(home / "compose.override.yml"), "--env-file", str(home / ".env")]
    return Installed(home, state["release"], names, home / ".env", compose, state["project"],
                     bool(state.get("unsignedLocal")))


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
    ctx = tls_context(workdir / "pebble-minica.pem")
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
    ctx = tls_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        with socket.create_connection(("127.0.0.1", 443), timeout=5) as raw:
            with ctx.wrap_socket(raw, server_hostname=DOMAIN) as tls:
                return describe(tls.getpeercert(binary_form=True))
    except OSError as error:
        return f"nothing ({error})"


def pebble(release: Release, workdir: Path, values: dict[str, str], app_image: str, overlay: Path = OVERLAY) -> str:
    """Pebble first, alone: its root is generated when it starts, and the app must trust it before IT starts. The
    truststore is written by `app_image`'s own `keytool`: the image the instance runs first."""
    minica = workdir / "pebble-minica.pem"
    copy_out(pebble_image(), {PEBBLE_MINICA_PATH: minica})
    minica.chmod(0o644)  # Traefik reads it as uid 65532
    compose = pebble_compose(release, workdir, values, overlay)
    run(*compose, "up", "-d", "--wait", "pebble")
    root = pebble_root(compose, workdir)
    # The image's own keytool, as the uid of this run, writing into the run directory; no network. Through `java`:
    # the distroless image puts only `java` on the PATH (ADR 0118), and keytool's launcher is this main class of
    # java.base under an architecture-specific JRE path.
    run("docker", "run", "--rm", "--network", "none", "--user", f"{os.getuid()}:{os.getgid()}",
        "-e", TRUSTSTORE_VARIABLE,
        "-v", f"{workdir}:/w", "--entrypoint", "java", app_image, "-m", "java.base/sun.security.tools.keytool.Main",
        "-importcert", "-noprompt", "-alias", "veyq-e2e-pebble-root", "-file", f"/w/{root.name}",
        "-keystore", f"/w/{APP_TRUSTSTORE}", "-storetype", "PKCS12", "-storepass:env", TRUSTSTORE_VARIABLE,
        env={**os.environ, TRUSTSTORE_VARIABLE: APP_TRUSTSTORE_PASSWORD})
    print(f"pebble: the app trusts {describe(ssl.PEM_cert_to_DER_cert(root.read_text()))} and nothing else", flush=True)
    return pebble_container(values["COMPOSE_PROJECT_NAME"], workdir)


def certificate(workdir: Path) -> None:
    root = workdir / "pebble-root.pem"
    # cafile given → the default trust store is NOT loaded: the run's Pebble root is the only CA.
    ctx = tls_context(root)
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


def browser(compose: list[str], workdir: Path, document: dict, selection: list[str]) -> None:
    """`tests/e2e/`'s Playwright suite in the overlay's browser container, told what it may reach by `document`
    ([stack_document]); `selection` goes to `playwright test` as it is. Red unless it exited 0 having executed a test."""
    (workdir / "stack.json").write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    # `selection` is handed to `playwright test` as it is (a local run's `-- --grep …`, a CI leg's `-- --project <domain>`).
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


def env_value(env: Path, name: str) -> str:
    for line in env.read_text(encoding="utf-8").splitlines():
        m = ASSIGNMENT.match(line)
        if m and m.group(1) == name:
            return m.group(2)
    raise Red(f"{name} is not in the run's .env")


# ---------------------------------------------------------------- the stages of a run


class Stages:
    """A lane's stages, run in order: each one's wall time and verdict for the summary, and every red with its stage.

    A stage is skipped while [blocking] names a red that skips it — by default any earlier red — except `evidence`,
    `teardown` and `host`, which always run, and a stage called with `always=True`. Each red is `<stage>: <why>`."""

    ALWAYS = ("evidence", "teardown", "host")

    def __init__(self, blocking: Callable[[list[str], str], list[str]] = lambda red, name: list(red)) -> None:
        self.blocking = blocking
        self.stages: list[tuple[str, float, str]] = []
        self.red: list[str] = []

    def __call__(self, name: str, fn, *fargs, always: bool = False):
        if self.blocking(self.red, name) and not always and name not in self.ALWAYS:
            self.stages.append((name, 0.0, "skipped"))
            return None
        print(f"\n== {name}", flush=True)
        start = time.monotonic()
        try:
            value = fn(*fargs)
            self.stages.append((name, time.monotonic() - start, "ok"))
            return value
        except Red as error:
            self.stages.append((name, time.monotonic() - start, "RED"))
            self.red.append(f"{name}: {error}")
            print(f"RED at {name}: {error}", flush=True)
            return None

    def total(self) -> float:
        return sum(s for _, s, _ in self.stages)


def base_values(project: str, workdir: Path) -> dict[str, str]:
    """What every lane gives the instance's `.env` through `veyqctl install --set`, beside the operator's own values:
    the project, the admin port, the ACME server, and the overlay's own variables."""
    return {
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


def stack_document(**extra) -> dict:
    """What the browser container is told it may reach (`stack.json`, tests/e2e/src/stack.ts), with a lane's own keys."""
    return {
        "domain": DOMAIN,
        "appOrigin": f"https://{DOMAIN}",
        "traefikHost": TRAEFIK_SERVICE,
        # The same port number an operator's tunnel listens on, inside the container (tests/e2e/src/tunnel.ts).
        "adminOrigin": f"http://localhost:{ADMIN_PORT}",
        "adminUpstream": ADMIN_UPSTREAM,
        "mailApi": MAIL_API,
        **extra,
        "outDir": CONTAINER_OUT,
    }
