#!/usr/bin/env python3
"""Run web's `VEYQ_E2E=1` suites against a live backend + a real object store, one file at a time.

**What it does.** Brings up the bundled stack from `infra/docker-compose.yml` (`--profile s3 --profile mail`:
MariaDB, Garage, the init sidecar, Traefik, Mailpit), then per e2e file: gives it its OWN database, boots a fresh
fast-jar under `-Dquarkus.profile=e2e` against that database, waits for the readiness probe (`GET /api/setup` —
see the comment above `READY_URL`, and note this summary once said `/q/health/ready`, an endpoint this backend has
never served), runs exactly that one file, sweeps that file's database for leaked plaintext, and tears the boot
down. It aggregates: every failing file is reported and the exit code is non-zero if ANY failed, never
short-circuiting on the first.

**The wire assertion (ADR 0053).** Each suite writes the plaintext it recovered by *unsealing the server's own
responses* to `$VEYQ_CANARY_FILE`; this side then proves none of it is readable in the database — see
[sweep_database]. The two halves are deliberately split by what each can reach: only the suite holds the keys,
only this process holds Docker and the database root credential.

**Why a fresh instance per file.** Every suite cold-births its own Server Owner and asserts
`setupRequired === true`, and a server is single-owner-per-instance. `ci.yml` used to call this "a fresh in-mem
DB per file", which does not work: `quarkus.datasource.db-kind` is a BUILD-TIME property baked at augmentation
under `%prod`, so a runtime `QUARKUS_DATASOURCE_DB_KIND=h2` is ignored and the *MariaDB* driver rejects the H2
URL. One MariaDB with a fresh `veyq_e2e_<n>` database per file is a fresh instance in the only sense the suites
care about — at one container, and with `ci.yml`'s "build the jar once" intact.

**Why the store cannot be a GH Actions service container.** Garage bind-mounts `/etc/garage.toml` from the repo
(service containers start before checkout), its official image is shell-less `scratch`, its bootstrap is a
mandatory multi-step sequence, and the init sidecar is built locally from the repo. So the stack comes up through
compose, which every self-hoster already has — nothing here depends on anything the bundled stack does not ship.

**Not a `validate_*` guard.** It needs Docker and a built jar, so it cannot join the buildless trust-chain glob;
CI calls it directly. The one part of its contract that IS decidable without either — that it cannot report green
having run nothing — is guarded by `scripts/validate_e2e_discovery.py`, which imports `discover()` from here.

Usage:
    python3 scripts/run_web_e2e.py [FILE ...]        # from the repo root

With no arguments it runs every `web/src/**/*.e2e.test.ts` — that is how CI invokes it. Paths may be given
relative to the repo root or to `web/`, which is a local debugging convenience only.

Prerequisite: the fast-jar exists (`cd backend && ./gradlew quarkusBuild`). CI builds it once, before this runs.
**And a STALE jar fails as a wrong answer, not as an error** (cost one run in cr-account-recovery): this boots
whatever jar is on disk, so backend changes made since the last build are simply absent — a new endpoint 404s, a
new migration never applies. Both look like a broken suite. The tell is in the boot log: `Successfully applied N
migrations, now at version vN` names the schema the jar carries, so compare it against the highest
`V<n>__*.sql` in the tree before believing a failure. Rebuild after ANY backend change; `./gradlew check` does
not do it for you (it builds test classes, not the fast-jar). The web mirror of this trap is in `web/CLAUDE.md`
— `npm run check` not regenerating the API client — and it is the same failure in both directions: a green or
red that was measured against code the run never saw.

**The run directory survives the run** (`.e2e-out/<stamp>/`, gitignored). It holds each boot's log, its
per-boot config-secret file and its canary file. It used to be a `tempfile.TemporaryDirectory` discarded on
exit, which left the boot log — the surface where an error `message` or a stack trace would carry plaintext —
not merely unswept but unavailable (ADR 0053 § Decision 4). Nothing prunes it; it is a developer artifact and
CI throws the runner away.

Environment (all optional):
    VEYQ_JAR              Backend fast-jar. Default: backend/build/quarkus-app/quarkus-run.jar
    VEYQ_PORT             Backend HTTP port. Default: 8080
    VEYQ_HOST             Tenant domain + fetch Host. Default: veyq.localhost
    VEYQ_BOOT_SECS        Readiness timeout per boot. Default: 120
    VEYQ_TEST_MS          Default per-test timeout passed to vitest. Default: 120000
    VEYQ_DB_ROOT_PASSWORD MariaDB root password. Default: the documented compose throwaway.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
WEB = REPO / "web"
COMPOSE_FILE = REPO / "infra" / "docker-compose.yml"
COMPOSE = ["docker", "compose", "-f", str(COMPOSE_FILE), "--profile", "s3", "--profile", "mail"]

# Where a run's artifacts land. A CONSTANT only — `main()` creates it, never module scope:
# `scripts/validate_e2e_discovery.py` imports this module with `importlib` and *executes* it, so a module-level
# mkdir would have every buildless trust-chain run leave a directory behind in the repo.
RUN_ROOT = REPO / ".e2e-out"

PORT = int(os.environ.get("VEYQ_PORT", "8080"))
# The tenant domain AND the fetch Host: `veyq.localhost` is a valid dotted domain (so POST /api/tenants and the
# invite mailer's hostname check accept it) and an RFC 6761 loopback name. It must be real in the URL, not a
# spoofed header — tenant resolution is server-side from `Host`, and fetch/undici strips a manually set one.
HOST = os.environ.get("VEYQ_HOST", "veyq.localhost")
BASE = f"http://{HOST}:{PORT}"
JAR = Path(os.environ.get("VEYQ_JAR", REPO / "backend/build/quarkus-app/quarkus-run.jar"))
BOOT_SECS = int(os.environ.get("VEYQ_BOOT_SECS", "120"))
TEST_MS = os.environ.get("VEYQ_TEST_MS", "120000")

# Documented throwaway credentials from infra/docker-compose.yml — not secrets. The app user `veyq` holds grants
# on the `veyq` database ONLY, so each e2e database is created as root and then granted to it.
DB_ROOT_PASSWORD = os.environ.get("VEYQ_DB_ROOT_PASSWORD", "change-me-root")
DB_APP_USER = "veyq"

GARAGE_INIT_CONTAINER = "veyq-garage-init"

# The throwaway mailbox, published by `infra/docker-compose.yml`'s `mail` profile. It is EXPORTED to each suite as
# `VEYQ_MAILPIT` rather than left to the suite's own default: mail is no longer one feature's substrate — a member
# invite's token travels by mail only (ADR 0055 §2) — and the thing that starts a substrate is the thing that
# should name its address.
MAILPIT = "http://127.0.0.1:18025"

# Readiness is probed on `GET /api/setup`, NOT on `/q/health/ready`. This backend has no `smallrye-health`
# extension — grep the repo for `q/health` and you get nothing — so the health endpoint the recovered skeleton
# polled has never existed here, and polling it just times out behind a backend that started fine 118s earlier.
# `/api/setup` is the better probe anyway: unauthenticated, reachable whether or not setup is complete, served
# only once Flyway has migrated, and the first call every suite makes.
READY_URL = f"http://127.0.0.1:{PORT}/api/setup"


class NoE2eFilesError(RuntimeError):
    """Discovery matched no e2e file. A hard error, never an empty-but-successful run.

    This is the false negative the whole job exists to remove: an orchestrator that reports green because it ran
    nothing looks exactly like an orchestrator whose suites all passed. `validate_e2e_discovery.py` pins it.
    """


class SweepError(RuntimeError):
    """The wire-assertion sweep could not be performed at all (the dump failed).

    Never a skip: an unperformed sweep is scored exactly like a failed one, for the same reason [NoE2eFilesError]
    exists — "we proved nothing" and "we proved absence" must not render identically.
    """


def log(msg: str) -> None:
    """A silent orchestrator is unreviewable in CI — every step announces itself."""
    print(f"[e2e] {msg}", flush=True)


def discover(root: Path = WEB) -> list[Path]:
    """Every web e2e suite, sorted. Raises [NoE2eFilesError] rather than returning an empty list."""
    files = sorted(root.glob("src/**/*.e2e.test.ts"))
    if not files:
        raise NoE2eFilesError(f"no *.e2e.test.ts found under {root}/src — refusing to report success on zero files")
    return files


def normalize(arg: str) -> Path:
    """Accept a path relative to the repo root or to web/ (or absolute)."""
    p = Path(arg)
    for candidate in (p, REPO / p, WEB / p):
        if candidate.is_file():
            return candidate.resolve()

    raise SystemExit(f"[e2e] e2e file not found: {arg}")


def run(cmd: list[str], **kw: object) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, check=True, **kw)  # type: ignore[arg-type]


def compose_up() -> None:
    """Bring up the bundled stack, then wait on EVERY half it provides.

    The wait is per-service on purpose: a single `up --wait` on the whole project exits 1, because `garage-init`
    is a one-shot sidecar that exits even on success and `--wait` scores any exit as failure. So step one starts
    everything, then each long-lived dependency is waited on by name — mariadb's healthcheck (Flyway needs it
    accepting connections), mailpit's (`/mailpit readyz`, declared by the image rather than by our compose file),
    and the init sidecar's exit 0, which is what makes the BUCKET ready rather than just the store process.
    Skipping the last one is how `media.e2e` fails in the PUT on a cold start.

    Mailpit is unconditional, not a flag: every suite's tenant is now pointed at it (`e2eBootstrap.birthInstance`)
    because a member invite travels by mail only (ADR 0055 §2). Gating that behind an argument would mean gating
    something that must always be on — and this script has no argparse at all; every argv entry is a test path.
    Note that mailpit's healthcheck declares a 15 s interval and no `start_period`, so a cold `--wait` genuinely
    blocks about that long. That is the right trade against a first-suite flake, and the reason not to substitute
    a snappier poll of our own.
    """
    log(f"compose up (--profile s3 --profile mail) from {COMPOSE_FILE.relative_to(REPO)}")
    run(COMPOSE + ["up", "-d"])
    log("waiting for mariadb to report healthy")
    run(COMPOSE + ["up", "-d", "--wait", "mariadb"])
    log("waiting for mailpit to report healthy")
    run(COMPOSE + ["up", "-d", "--wait", "mailpit"])
    wait_garage_init()
    wait_mailbox()


def wait_mailbox(timeout: int = 30) -> None:
    """Insist that the mailbox address we EXPORT is the one that answers.

    Not a second readiness wait — `--wait mailpit` already did that against the container's own healthcheck, from
    inside the container. This checks the other thing, which that cannot see: that [MAILPIT] — the address handed
    to every suite — reaches it from here, through the published port. The two can disagree (a wrong port, a
    mapping that lost the race, a stale container holding the port), and when they do the failure without this
    check surfaces as an undefined token deep inside a suite instead of as a named, unreachable mailbox.
    """
    log(f"confirming the mailbox answers on {MAILPIT}")
    deadline = time.time() + timeout
    last = "nothing answered"
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(f"{MAILPIT}/api/v1/messages?limit=1", timeout=2) as r:
                if r.status == 200:
                    log("mailbox is reachable")
                    return
                last = f"answered {r.status}, expected 200"
        except urllib.error.HTTPError as e:
            last = f"answered {e.code}, expected 200"
        except (urllib.error.URLError, OSError) as e:
            last = f"{type(e).__name__}: {e}"
        time.sleep(1)
    raise SystemExit(
        f"[e2e] MAILBOX UNREACHABLE: {MAILPIT} did not serve the Mailpit API within {timeout}s ({last}). "
        f"Every suite reads a member invite out of this mailbox — without it there is nothing to run."
    )


def wait_garage_init(timeout: int = 120) -> None:
    """Wait for the one-shot init sidecar to have exited, and insist it exited 0."""
    log(f"waiting for {GARAGE_INIT_CONTAINER} to finish bucket init")
    deadline = time.time() + timeout
    while time.time() < deadline:
        probe = subprocess.run(
            ["docker", "inspect", "-f", "{{.State.Status}}|{{.State.ExitCode}}", GARAGE_INIT_CONTAINER],
            capture_output=True, text=True,
        )
        if probe.returncode == 0:
            status, _, code = probe.stdout.strip().partition("|")
            if status == "exited":
                if code != "0":
                    raise SystemExit(
                        f"[e2e] STORE INIT FAILED: {GARAGE_INIT_CONTAINER} exited {code}. "
                        f"Inspect it with: docker logs {GARAGE_INIT_CONTAINER}"
                    )
                log("store bucket is initialized")
                return
        time.sleep(1)
    raise SystemExit(f"[e2e] STORE INIT TIMEOUT: {GARAGE_INIT_CONTAINER} did not exit within {timeout}s")


def mysql(sql: str) -> None:
    """Run SQL as root inside the compose mariadb container."""
    run(COMPOSE + ["exec", "-T", "mariadb", "mariadb", "-uroot", f"-p{DB_ROOT_PASSWORD}", "-e", sql])


def fresh_database(name: str) -> None:
    """Drop and recreate this file's database, then grant it to the app user.

    DROP-then-CREATE rather than a run-unique name, so a second run in a row is just as fresh as the first
    without leaking a database per run. The GRANT is not optional: the compose `veyq` user is provisioned for the
    `veyq` database alone, and without it the boot fails with an access-denied rather than a missing-database
    error — the more confusing of the two.
    """
    log(f"provisioning a fresh database {name}")
    mysql(
        f"DROP DATABASE IF EXISTS {name}; CREATE DATABASE {name}; "
        f"GRANT ALL PRIVILEGES ON {name}.* TO '{DB_APP_USER}'@'%';"
    )


def port_is_taken() -> bool:
    """Something already answering on our port would be readiness-polled as if it were ours."""
    try:
        with urllib.request.urlopen(READY_URL, timeout=2):
            return True
    except urllib.error.HTTPError:
        return True  # answering, just not 200 — still not ours to use
    except (urllib.error.URLError, OSError):
        return False


def wait_ready(deadline: float, proc: subprocess.Popen) -> str | None:
    """Poll until the backend serves the readiness probe. Returns None when ready, else why it never was.

    A wrong status is reported rather than retried into the timeout. That distinction is not hypothetical: polling
    a path this backend does not serve looks exactly like a slow boot, and a 404 retried for 120s reads as "the
    backend never came up" while the log says it started in 2.3s.
    """
    last_status: int | None = None
    while time.time() < deadline:
        if proc.poll() is not None:
            return f"the JVM exited with code {proc.returncode} before becoming ready"
        try:
            with urllib.request.urlopen(READY_URL, timeout=2) as r:
                if r.status == 200:
                    return None
                last_status = r.status
        except urllib.error.HTTPError as e:
            last_status = e.code
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(0.5)
    if last_status is not None:
        return f"the readiness probe {READY_URL} answered {last_status}, expected 200"
    return f"nothing answered the readiness probe {READY_URL} within {BOOT_SECS}s"


def boot(database: str, secret_file: Path, log_path: Path) -> subprocess.Popen:
    """Boot the fast-jar under `%e2e` against this file's own database.

    Two per-boot overrides carry state that must NOT be shared between boots:
      - the datasource URL, so each file gets its own schema;
      - `veyq.security.config-secret-file`, whose default resolves against the process CWD. N boots from the repo
        root would share one KEK file and leave an untracked `0600` secret beside the repo (ADR 0050 amendment).
    """
    env = {
        **os.environ,
        "QUARKUS_DATASOURCE_JDBC_URL": f"jdbc:mariadb://127.0.0.1:3306/{database}",
        "QUARKUS_HTTP_PORT": str(PORT),
        "VEYQ_SECURITY_CONFIG_SECRET_FILE": str(secret_file),
    }
    handle = log_path.open("w")
    return subprocess.Popen(
        ["java", "-Dquarkus.profile=e2e", "-jar", str(JAR)],
        cwd=str(REPO), env=env, stdout=handle, stderr=subprocess.STDOUT,
    )


def kill(proc: subprocess.Popen) -> None:
    if proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=20)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=10)


def tail(path: Path, lines: int = 40) -> None:
    """Print the end of a boot log. In CI this is the only window into a failed boot."""
    if not path.is_file():
        return
    body = path.read_text(errors="replace").splitlines()
    print(f"[e2e] --- last {min(lines, len(body))} lines of {path.name} ---", flush=True)
    for line in body[-lines:]:
        print(f"[e2e]   {line}", flush=True)


# How `mariadb-dump` writes a string literal, MEASURED against mariadb:11.4 rather than recalled from the
# `mysql_real_escape_string` docs — a wrong entry here is a silent miss, which is the one failure this whole
# sensor exists to remove. Backslash is first: it must not re-escape the backslashes the later rules introduce.
# TAB and `;` are deliberately absent — the dump leaves both raw.
DUMP_ESCAPES = [("\\", "\\\\"), ("'", "\\'"), ('"', '\\"'), ("\n", "\\n"), ("\r", "\\r"), ("\0", "\\0"), ("\x1a", "\\Z")]

# Every data row of a data-only dump is one of these; nothing else in the file carries a value. The rest is
# comments and `ALTER TABLE ... DISABLE KEYS` — DDL, which cannot carry a leak (the table and column names are
# fixed by the migrations) but DOES contain table names, so a canary like "media" would match `veyq_media` there
# and read as a hit. Anchoring on the INSERT is what scopes the search to the surface decision 3 actually names.
INSERT_LINE = re.compile(r"^INSERT INTO `([^`]+)` VALUES ")


def dump_escape(value: str) -> str:
    """Render a canary the way `mariadb-dump` would render it inside a string literal.

    Escaping the needle rather than un-escaping the haystack is the lossless direction: un-escaping a dump means
    parsing SQL well enough to know which backslashes are ours, and getting that wrong loses a hit. This way the
    transformation is total and one-directional.
    """
    for raw, escaped in DUMP_ESCAPES:
        value = value.replace(raw, escaped)
    return value


def dump_database(database: str) -> str:
    """A data-only dump of one e2e database, one row per line.

    `--no-create-info` removes the entire DDL noise class in one flag and costs nothing, because table and column
    names are fixed by the migrations and can never carry user plaintext (`name` occurs in 8 column names of this
    schema, `cover` in 3 — all of them false hits a full dump would have produced). `--skip-extended-insert` puts
    each row on its own line so a hit can name its table AND its row; rows never span lines, because the dump
    escapes newlines inside literals — the same fact `dump_escape` rests on. Nothing is EXCLUDED beyond that:
    dropping known-noisy rows (a pristine dump holds only `flyway_schema_history`) would make a genuine leak of
    exactly that string invisible, and a security sensor must fail toward noise, never toward silence.
    """
    proc = subprocess.run(
        COMPOSE + ["exec", "-T", "mariadb", "mariadb-dump", "-uroot", f"-p{DB_ROOT_PASSWORD}",
                   "--no-create-info", "--skip-extended-insert", "--single-transaction",
                   "--default-character-set=utf8mb4", database],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        raise SweepError(f"mariadb-dump of {database} failed ({proc.returncode}): {proc.stderr.strip()[-400:]}")
    return proc.stdout


def sweep(database: str, canary_file: Path, rel: str) -> str | None:
    """Prove that nothing this suite sealed is readable in the database it ran against. Returns None, else why not.

    **What it asserts** (cc.security.wire-assertion, ADR 0053). For the values the suite recovered by unsealing
    the server's own responses — its canaries — no plaintext copy exists in any row of any table of that file's
    own `veyq_e2e_<n>`. The oracle is *absence of specific values*, not conformance of everything observable:
    ADR 0053 measured 67 DB columns with no contract at all, so the mapping an allowlist check would need cannot
    be built for the surface that most needs one.

    **The defect that bought it.** No incident on this surface — because until now nothing had ever looked at it.
    `validate_field_visibility.py` (ADR 0044) checks the *contract*, and says so in one line: a field carrying
    `contentEncoding: base64` is PERMITTED to be opaque, and a client that writes plaintext into it satisfies
    that guard completely. The regression this catches is a client that stops sealing something, in a build that
    passes every other check — invisible to a unit test (which sees the client's own intent), invisible to the
    contract guards (which see the schema), and visible only in the stored bytes. The spike that preceded
    ADR 0053 is the closest thing to an incident: it found `e2e.test` three times in a live instance database,
    all of it declared plaintext (`owner_email` is `pii`, the tenant name is `public`) — which is exactly why the
    oracle is derived-canary absence and not "no readable strings".

    **Its limit — what a green sweep does NOT prove.** It proves absence *for the values this flow sealed, on the
    surface this sensor reads*. It does not prove the flow sealed everything it should have: a value the client
    never sealed is not a canary, correctly, and is therefore invisible here — that case belongs to the opacity
    check over the `contentEncoding: base64` fields and to `validate_field_visibility.py`. It does not prove an
    unswept surface is clean: the object store and the boot log are separate change-requests, and the log is
    merely *retained* here. Its residual gap is coverage, not correctness — the sweep sees only flows some suite
    drives, and four e2e suites exist. And passing cannot be arranged from inside the client (the bytes are in a
    database this process does not write), but a canary can still be *removed* — a visible diff, exactly like
    widening an allowlist would be, which is weaker than tamper-proof and should not be stated as more.
    """
    if not canary_file.is_file():
        return (f"the suite left no canary file at {canary_file.name} — the sweep would have proved the absence "
                f"of nothing. Every suite must call recordCanaries(); scripts/validate_e2e_canaries.py pins it.")
    canaries = json.loads(canary_file.read_text())
    if not canaries:
        return (f"{canary_file.name} is empty — {rel} recorded no canary, so a green sweep here would mean "
                f"nothing. This is a hard failure, never a skip.")

    try:
        dump = dump_database(database)
    except SweepError as e:
        return str(e)
    needles = [(c, dump_escape(c)) for c in canaries]
    hits: list[tuple[str, str]] = []
    seen: set[tuple[str, str]] = set()
    for line in dump.splitlines():
        match = INSERT_LINE.match(line)
        if not match:
            continue
        table = match.group(1)
        for canary, needle in needles:
            if needle in line and (canary, table) not in seen:
                seen.add((canary, table))
                hits.append((canary, table))

    if hits:
        # Print every hit, not a count: a count cannot be acted on, and the resolution is per-value. A hit means
        # either a real leak, or a derived canary that collides with DECLARED plaintext — in which case the sweep
        # is right and the test data is wrong (make the sealed value distinctive). Never an allowlist.
        log(f"WIRE ASSERTION FAILED for {rel} — {len(hits)} canary/table hit(s) in {database}:")
        for canary, table in hits:
            log(f"    {table:<28} <- {canary!r}")
        return f"the wire assertion failed: {len(hits)} canary/table hit(s) in {database} (listed above)"

    log(f"wire assertion: {len(canaries)} canary/canaries from {rel} appear nowhere in {database}")
    return None


def run_file(index: int, test_path: Path, workdir: Path, stamp: str) -> str | None:
    """Run one e2e file against its own fresh backend. Returns None on success, else a failure reason."""
    rel = test_path.relative_to(WEB).as_posix()
    database = f"veyq_e2e_{index}"
    log(f"=== {rel} (database {database}) ===")
    fresh_database(database)

    secret_file = workdir / f"config-secret-{index}.key"
    boot_log = workdir / f"backend-{index}.log"
    canary_file = workdir / f"canaries-{index}.json"
    proc = boot(database, secret_file, boot_log)
    try:
        log(f"waiting up to {BOOT_SECS}s for the backend to become ready")
        not_ready = wait_ready(time.time() + BOOT_SECS, proc)
        if not_ready:
            tail(boot_log)
            return f"{not_ready} (boot log above)"
        log(f"backend ready on {BASE} — running the suite")
        # VEYQ_GEOCODER is dropped rather than inherited: set, memoryLocation.e2e makes a real third-party
        # Nominatim call, which is a flake source in CI and reaches the public internet from a harness meant to
        # run self-hosted. Unset, exactly one assertion drops and every assertion about veyq still runs. To
        # exercise that leg, run the file by hand with the variable set.
        env = {k: v for k, v in os.environ.items() if k != "VEYQ_GEOCODER"}
        env.update({
            "VEYQ_E2E": "1", "VEYQ_BASE": BASE, "VEYQ_HOST": HOST, "VEYQ_STAMP": stamp,
            "VEYQ_MAILPIT": MAILPIT,
            # The wire-assertion seam (ADR 0053): the suite writes here what it recovered by unsealing; this
            # side reads it back and sweeps. Absolute, because vitest runs with cwd=web/.
            "VEYQ_CANARY_FILE": str(canary_file),
        })
        result = subprocess.run(
            ["npx", "vitest", "run", rel, "--testTimeout", TEST_MS],
            cwd=str(WEB), env=env,
        )
        if result.returncode != 0:
            # No sweep on a failed suite: the canary set is incomplete by construction, so a hit or a miss would
            # both be unreadable. The run already fails on this.
            return f"the suite failed (vitest exit {result.returncode})"
        return sweep(database, canary_file, rel)
    finally:
        kill(proc)


def main(argv: list[str]) -> int:
    if not shutil.which("docker"):
        raise SystemExit("[e2e] docker is required (the bundled stack supplies the DB + object store)")
    if not JAR.is_file():
        raise SystemExit(f"[e2e] backend jar missing: {JAR}\nBuild it first: (cd backend && ./gradlew quarkusBuild)")

    files = [normalize(a) for a in argv] if argv else discover()
    if port_is_taken():
        raise SystemExit(
            f"[e2e] port {PORT} is already serving — another backend (an IntelliJ quarkusDev?) is running. "
            f"Stop it, or set VEYQ_PORT to a free port."
        )

    compose_up()
    # One stamp for the whole run: it varies emails/usernames so a re-run against a warm limiter sees a new
    # invite recipient, and the per-file database makes cross-file collisions impossible anyway. Digits only —
    # the username pattern is [A-Za-z0-9_.-] with a 32-char cap.
    stamp = str(int(time.time()))
    log(f"running {len(files)} e2e file(s), one fresh backend each, stamp={stamp}")

    # Retained, not a tempdir: the boot log is itself a sweep surface (ADR 0053 § Decision 4), and a canary file
    # discarded on exit cannot be re-read when a sweep hit needs explaining.
    workdir = RUN_ROOT / stamp
    workdir.mkdir(parents=True, exist_ok=True)
    log(f"run artifacts (boot logs, canary files) land in {workdir.relative_to(REPO)}/")

    failures: list[tuple[str, str]] = []
    for index, path in enumerate(files, start=1):
        reason = run_file(index, path, workdir, stamp)
        if reason:
            # Never short-circuit: one broken suite must not hide the state of the others.
            failures.append((path.relative_to(WEB).as_posix(), reason))

    print("", flush=True)
    log("==================== summary ====================")
    log(f"total: {len(files)}  passed: {len(files) - len(failures)}  failed: {len(failures)}")
    for name, reason in failures:
        log(f"  FAILED  {name}: {reason}")
    return 1 if failures else 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except NoE2eFilesError as e:
        raise SystemExit(f"[e2e] NO FILES MATCHED: {e}") from e
