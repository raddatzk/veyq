#!/usr/bin/env python3
"""Run web's `VEYQ_E2E=1` suites against a live backend + a real object store, one file at a time.

**What it does.** Brings up the bundled stack from `infra/docker-compose.yml` (`--profile s3 --profile mail`:
MariaDB, Garage, Traefik, Mailpit) and — because ADR 0067 made the backend the only thing that can turn a
running store into a usable one — boots one throwaway backend whose sole job is to provision it. Then per e2e
file: gives it its OWN database, boots a fresh
fast-jar under `-Dquarkus.profile=e2e` against that database, waits for the readiness probe (`GET /api/setup` —
see the comment above `READY_URL`, and note this summary once said `/q/health/ready`, an endpoint this backend has
never served), runs exactly that one file, sweeps that file's database for leaked plaintext, and tears the boot
down. It aggregates: every failing file is reported and the exit code is non-zero if ANY failed, never
short-circuiting on the first.

**Groups — several suites against ONE instance session, with a hook between them.** A file named
`<stem>.<member>.e2e.test.ts` joins the group `<stem>`; its members run in filename order against the same
database, bucket and config-secret file, and the orchestrator runs a **hook** between them. Everything else is
unchanged, and a **group of one is byte-for-byte the old behaviour** — an ordinary `media.e2e.test.ts` still
gets its own boot, keeps the shared `veyq` bucket, and never touches a hook. Only a real group gets its own
bucket, because only a real group has a hook that destroys one.

The hook is what a group exists for: `restore.*` captures the instance with the app already stopped,
**destroys the source** (database dropped, bucket purged, key file removed), restores onto a structurally empty
one, and boots the next member against *that*. Destroying the source is not tidiness — it is the only way "the
restore worked" and "nothing was restored" can be told apart from outside. Members exchange whatever they need
through `$VEYQ_GROUP_DIR`, a directory that outlives the boot.

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
(service containers start before checkout) and its bootstrap is a mandatory multi-step sequence a service
container has nowhere to run. So the stack comes up through compose, which every self-hoster already has —
nothing here depends on anything the bundled stack does not ship. (That bootstrap used to need a locally built
init image too, which was a third reason and the strongest; ADR 0067 removed it, and the remaining two still
hold on their own.)

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
    VEYQ_ADMIN_PORT       Backend admin-listener port (ADR 0065). Default: 8090
    VEYQ_HOST             Tenant domain + fetch Host. Default: veyq.localhost
    VEYQ_BOOT_SECS        Readiness timeout per boot. Default: 120
    VEYQ_TEST_MS          Default per-test timeout passed to vitest. Default: 120000
    VEYQ_DB_ROOT_PASSWORD MariaDB root password. Default: the documented compose throwaway.
"""
from __future__ import annotations

import base64
import binascii
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
from types import SimpleNamespace

import yaml

# A group's hook shells out to the operator tool for capture/restore, but reuses its rclone plumbing for the one
# thing the CLI deliberately does not offer (purging a bucket). Importable because Python puts the running
# script's own directory — `scripts/` — on `sys.path`, which also holds when `validate_e2e_discovery.py`
# imports this module by path.
import instance_backup
# The opacity oracle needs the set of properties a spec DECLARES opaque. It is derived from the
# contracts here, never listed, by reusing the guard's own predicate — one definition of "opaque",
# not two that agree today (changes/README § a guard derives its anchor set from the code).
import validate_field_visibility

REPO = Path(__file__).resolve().parent.parent
WEB = REPO / "web"
COMPOSE_FILE = REPO / "infra" / "docker-compose.yml"
COMPOSE = ["docker", "compose", "-f", str(COMPOSE_FILE), "--profile", "s3", "--profile", "mail"]
# The network a group's capture/restore containers join to reach the store by its INTERNAL name. Compose derives
# a project name from the compose file's DIRECTORY unless the file declares one, and this one deliberately does
# not (a `name:` here would change the real network while this derivation kept naming the old one) — so this is
# `infra_default`, derived rather than hardcoded, because the derivation is the rule and the string is only
# today's answer to it.
DOCKER_NETWORK = f"{COMPOSE_FILE.parent.name}_default"

# The production stack is a SECOND compose project on the same host, and the line above is exactly why that is a
# hazard rather than a detail: the derivation reads the compose file's DIRECTORY, both files live in `infra/`,
# and a capture pointed at the production file therefore resolves whatever is running out of `infra/` — which on
# any developer's host is the stack this orchestrator just brought up. See [rehearse_production_project].
PROD_COMPOSE_FILE = REPO / "infra" / "docker-compose.prod.yml"

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
# The backend's admin listener: the only door the Server-Owner operations answer on, so every suite births its
# instance and tenant through it (ADR 0065) and the public BASE 404s those calls. Loopback by IP, never HOST — a
# Server-Owner call is tenant-independent, and the listener's peer rule admits a loopback caller.
ADMIN_PORT = int(os.environ.get("VEYQ_ADMIN_PORT", "8090"))
ADMIN_BASE = f"http://127.0.0.1:{ADMIN_PORT}"
JAR = Path(os.environ.get("VEYQ_JAR", REPO / "backend/build/quarkus-app/quarkus-run.jar"))
BOOT_SECS = int(os.environ.get("VEYQ_BOOT_SECS", "120"))
TEST_MS = os.environ.get("VEYQ_TEST_MS", "120000")

# Documented throwaway credentials from infra/docker-compose.yml — not secrets. The app user `veyq` holds grants
# on the `veyq` database ONLY, so each e2e database is created as root and then granted to it.
DB_ROOT_PASSWORD = os.environ.get("VEYQ_DB_ROOT_PASSWORD", "change-me-root")
DB_APP_USER = "veyq"

# The bundled store's ADMIN plane (a different port from the S3 one), published local-only by
# infra/docker-compose.yml. This script READS it to confirm the backend provisioned the store; it never writes
# through it. Documented throwaway dev token, not a secret.
GARAGE_ADMIN = os.environ.get("VEYQ_GARAGE_ADMIN", "http://127.0.0.1:19003")
GARAGE_ADMIN_TOKEN = os.environ.get("VEYQ_GARAGE_ADMIN_TOKEN", "veyq-dev-admin-token")
# The bucket `%e2e` boots with, and the key alias the backend imports it under (GarageStoreProvisioner.KEY_ALIAS).
# `fresh_bucket` grants against that exact alias, so the name is a contract between this file and that class.
STORE_BUCKET = "veyq"
STORE_KEY_ALIAS = "veyq-app"

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


def compose_up(workdir: Path) -> None:
    """Bring up the bundled stack, then make every half it provides actually USABLE.

    The wait is per-service rather than one `up --wait` on the whole project: `--wait` scores any container exit
    as a failure, so a project holding a one-shot would fail on its clean exit. There is no one-shot left here —
    ADR 0067 deleted the init sidecar — but the habit is worth keeping, because each dependency is then waited on
    for its own reason: mariadb's healthcheck (Flyway needs it accepting connections) and mailpit's
    (`/mailpit readyz`, declared by the image rather than by our compose file).

    **Then the store, which is the half that changed.** A `--profile s3 up` now leaves Garage *running but not
    usable*: no layout, no bucket, no key, no grant. Nothing but a backend can close that gap any more, so this
    ends by booting one whose only job is to do it ([provision_store]).

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
    wait_mailbox()
    provision_store(workdir)


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


def store_is_provisioned() -> bool:
    """Is the store usable — the bucket present, with the key alias [fresh_bucket] grants against on it?

    This is an OBSERVATION of the provisioner's output, never a second copy of it. That distinction is the whole
    design: re-implementing layout/bucket/key/grant here through the `garage()` helper is cheaper to write and
    puts a drifting second bring-up in the harness, after which a green e2e run would stop meaning the
    provisioner works (ADR 0067 § the orchestrator's own problem). So the harness asserts the outcome and
    produces none of it.

    It also proves more than the Server-Owner read could. That read is `@RolesAllowed(SERVER_OWNER)` and the
    throwaway instance below has no owner at all — it exists precisely to be pre-setup — so the read is not
    reachable from here. Reading the store directly is the better oracle anyway: a state field saying
    PROVISIONED while the bucket was not actually usable is exactly the failure this needs to catch.
    """
    request = urllib.request.Request(
        f"{GARAGE_ADMIN}/v2/GetBucketInfo?globalAlias={STORE_BUCKET}",
        headers={"Authorization": f"Bearer {GARAGE_ADMIN_TOKEN}"},
    )
    try:
        with urllib.request.urlopen(request, timeout=5) as r:
            info = json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return False  # the ordinary state of a cold store — not an error
        raise SystemExit(f"[e2e] STORE ADMIN API REFUSED: {GARAGE_ADMIN} answered {e.code} for {STORE_BUCKET}")
    except (urllib.error.URLError, OSError) as e:
        raise SystemExit(f"[e2e] STORE ADMIN API UNREACHABLE at {GARAGE_ADMIN}: {e}") from e
    return any(k.get("name") == STORE_KEY_ALIAS and (k.get("permissions") or {}).get("owner")
               for k in info.get("keys") or [])


def provision_store(workdir: Path, timeout: int = 120) -> None:
    """One throwaway backend boot whose ONLY job is to make the store usable.

    ADR 0067 moved the bring-up into the backend and deleted the init sidecar from every stack, which costs this
    script the one thing it depended on: `run_group` calls [fresh_bucket] BEFORE its first `boot(...)`, and that
    grants against the `veyq-app` alias only a provisioner now mints. So a backend has to run first.

    It costs seconds and a scratch database — this script already creates one per suite — and it buys the thing
    the sidecar never gave: **every e2e run exercises the real provisioner**, on a real Garage, from cold. The
    fresh path stops being something only a unit test sees.

    The instance is genuinely throwaway: its own database, its own KEK file, killed as soon as the store is up.
    Nothing downstream shares its state, and no suite's `setupRequired === true` is affected, because each suite
    still births its own instance on its own database.
    """
    # Deliberately NOT gated on `store_is_provisioned()` being false. Skipping the boot on a warm store is the
    # obvious saving and it costs the whole point: the reason this is a backend boot rather than four `garage`
    # commands is that **every** run then exercises the real provisioner. A short-circuit would mean the only
    # runs that prove anything are the ones nobody does twice — and it would also stop proving per-step
    # idempotence, which is what a second run against a provisioned store IS.
    log("booting a throwaway backend to provision the store")
    database = "veyq_e2e_store_init"
    secret_file = workdir / "config-secret-store-init.key"
    boot_log = workdir / "backend-store-init.log"
    fresh_database(database)
    proc = boot(database, secret_file, boot_log)
    try:
        not_ready = wait_ready(time.time() + BOOT_SECS, proc)
        if not_ready:
            tail(boot_log)
            raise SystemExit(f"[e2e] STORE BRING-UP BOOT FAILED: {not_ready} (boot log above)")
        # Provisioning is deliberately asynchronous and never gates the boot (ADR 0067 Decision 3), so a ready
        # backend is not yet a usable store. Poll the store itself.
        deadline = time.time() + timeout
        while time.time() < deadline:
            if store_is_provisioned():
                log(f"store provisioned by the backend: bucket `{STORE_BUCKET}` owned by `{STORE_KEY_ALIAS}`")
                return
            time.sleep(1)
        tail(boot_log)
        raise SystemExit(
            f"[e2e] STORE NOT PROVISIONED: bucket `{STORE_BUCKET}` still has no `{STORE_KEY_ALIAS}` owner grant "
            f"{timeout}s after the backend became ready. The backend is the only thing that provisions the store "
            f"now (ADR 0067) — the boot log above names the step that failed."
        )
    finally:
        kill(proc)
        secret_file.unlink(missing_ok=True)


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


def port_is_taken(url: str = READY_URL) -> bool:
    """Something already answering on our port would be readiness-polled as if it were ours."""
    try:
        with urllib.request.urlopen(url, timeout=2):
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


def boot(database: str, secret_file: Path, log_path: Path, bucket: str | None = None) -> subprocess.Popen:
    """Boot the fast-jar under `%e2e` against this file's own database.

    Two per-boot overrides carry state that must NOT be shared between boots:
      - the datasource URL, so each file gets its own schema;
      - `veyq.security.config-secret-file`, whose default resolves against the process CWD. N boots from the repo
        root would share one KEK file and leave an untracked `0600` secret beside the repo (ADR 0050 amendment).

    `bucket` is the third, and it is set ONLY for a group: every ordinary suite keeps sharing the one `veyq`
    bucket exactly as before, because only a group's hook ever destroys one.
    """
    env = {
        **os.environ,
        "QUARKUS_DATASOURCE_JDBC_URL": f"jdbc:mariadb://127.0.0.1:3306/{database}",
        "QUARKUS_HTTP_PORT": str(PORT),
        "VEYQ_ADMIN_CHANNEL_PORT": str(ADMIN_PORT),
        "VEYQ_SECURITY_CONFIG_SECRET_FILE": str(secret_file),
    }
    if bucket:
        env["VEYQ_BLOB_STORE_BUCKET"] = bucket
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


def opaque_property_names() -> set[str]:
    """Every property name some spec declares `contentEncoding: base64`.

    Matching the recording by NAME is exact here rather than approximate, and that is a measured property of the
    contracts, not an assumption: across all ten specs the opaque names and the plain ones are **disjoint**
    (38 against 126, intersection empty). `scripts/validate_opaque_names_disjoint.py` keeps it that way — if a
    name ever landed in both, this oracle would quietly stop checking the opaque one.
    """
    names: set[str] = set()
    for spec in sorted((REPO / "contracts" / "api").glob("*.yaml")):
        doc = yaml.safe_load(spec.read_text()) or {}
        for schema in ((doc.get("components") or {}).get("schemas") or {}).values():
            for prop, body in (schema.get("properties") or {}).items():
                if validate_field_visibility.is_opaque(body):
                    names.add(prop)
    return names


# The shortest thing `symWrap` can emit: a 12-byte nonce and a 16-byte tag, before a single byte of payload.
# Nothing a client seals can be below it, which is what makes a length check an oracle rather than a heuristic.
MIN_SEALED_BYTES = 28


def opacity_failure(value: str) -> str | None:
    """Why this declared-opaque value is not opaque, or None if it is fine.

    **Two orthogonal tests, because one of them alone has a hole this CR walked into.** The obvious test is
    readability: ciphertext does not decode to valid, printable UTF-8. But that test needs a length floor —
    short random strings do decode to printable UTF-8 now and then (measured: 7/5000 at 8 bytes, 2/5000 at 12,
    1/5000 at 16, and **0 in 80 000** at 24 and above) — and a floor is exactly where short *plaintext* hides.
    Biting it proved the point: an unsealed Memory name went over the wire as **23 bytes** and the readability
    test never looked at it.

    So length is the second test, and on this wire it is not a heuristic at all. Measured across every
    declared-opaque field a real suite sends, the shortest is **32 bytes** (`instanceSalt`, `nonce`,
    `kdfParams`, `authPublicKey`, `recoveryMasterKeyTag`, `slugToken`), and the shortest an AEAD *can* be is
    [MIN_SEALED_BYTES]. Anything below that is not a sealed value, whatever it decodes to.

    A value that is not base64 at all is **not** this sweep's business: what shape a field may take is the
    contract's question and `validate_field_visibility.py` answers it.
    """
    for decoder in (base64.b64decode, base64.urlsafe_b64decode):
        try:
            raw = decoder(value + "=" * (-len(value) % 4))
        except (binascii.Error, ValueError):
            continue
        if len(raw) < MIN_SEALED_BYTES:
            return (f"decodes to {len(raw)} bytes — too short to be sealed at all "
                    f"(a nonce and a tag are {MIN_SEALED_BYTES} before any payload)")
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            return None  # decoded, long enough, not text — the ordinary case
        printable = sum(1 for ch in text if ch.isprintable() or ch in "\n\t")
        if text and printable / len(text) >= 0.9:
            return f"decodes to readable text: {text[:120]!r}"
        return None
    return None


def walk_opaque(value, names: set[str], found: list[tuple[str, str]]) -> None:
    """Collect every (property, reason) pair where a declared-opaque field failed [opacity_failure]."""
    if isinstance(value, dict):
        for key, entry in value.items():
            if key in names and isinstance(entry, str):
                why = opacity_failure(entry)
                if why is not None:
                    found.append((key, why))
            walk_opaque(entry, names, found)
    elif isinstance(value, list):
        for entry in value:
            walk_opaque(entry, names, found)


def sweep_http(record_file: Path, canary_file: Path, rel: str) -> str | None:
    """Prove that what the suite SENT and RECEIVED carries no plaintext. Returns None, else why not.

    **Two oracles over one recording, because the surface has two ways to leak.** A declared-opaque field that
    decodes to readable text is a client that stopped sealing — the opacity check ADR 0044 named and assigned
    to nobody. A canary appearing anywhere in a URL, a header or a body is the same absence oracle the database
    sweep uses, and it is here because this is the only surface where path params, query strings, capability
    headers and presigned URLs exist at all (ADR 0053 §3): by the time anything reaches the store, the URL that
    carried it is gone.

    **A population of zero is a failure, never a pass.** A recorder that never armed and a suite that made no
    calls both produce "no leak found", which is indistinguishable from a clean run — the same trap
    `changes/README` § *a bite test must tell "the guard held" apart from "nothing ran"* records, arriving
    through a measurement instead of a test.
    """
    if not record_file.is_file():
        return (f"{rel} recorded no HTTP traffic at {record_file.name} — the recorder did not arm, so this "
                f"sweep would prove nothing. VEYQ_HTTP_RECORD_FILE must reach the suite process.")
    # `split("\n")`, never `splitlines()`: Python splits the latter on U+0085 and U+2028 as well, which a
    # recorded body can legitimately contain — it cut a JSON record in half the first time this ran.
    entries = [json.loads(line) for line in record_file.read_text().split("\n") if line.strip()]
    if not entries:
        return f"{record_file.name} is empty — {rel} made no request, so a green HTTP sweep here means nothing."

    canaries = json.loads(canary_file.read_text()) if canary_file.is_file() else []
    names = opaque_property_names()
    opacity: list[tuple[str, str, str]] = []
    leaks: list[tuple[str, str, str]] = []
    for entry in entries:
        where = f"{entry['method']} {entry['url'].split('?')[0]}"
        for body in (entry.get("requestBody"), entry.get("responseBody")):
            if not body:
                continue
            try:
                parsed = json.loads(body)
            except (json.JSONDecodeError, TypeError):
                continue
            found: list[tuple[str, str]] = []
            walk_opaque(parsed, names, found)
            for prop, why in found:
                opacity.append((where, prop, why))
        haystack = "\n".join(filter(None, [
            entry["url"], json.dumps(entry.get("requestHeaders") or {}),
            entry.get("requestBody") or "", entry.get("responseBody") or "",
        ]))
        for canary in canaries:
            if canary in haystack:
                leaks.append((where, canary, "url/header/body"))

    if opacity or leaks:
        log(f"WIRE ASSERTION FAILED for {rel} on HTTP — {len(opacity)} opacity, {len(leaks)} canary hit(s):")
        for where, prop, why in opacity:
            log(f"    {where:<44} {prop} {why}")
        for where, canary, kind in leaks:
            log(f"    {where:<44} {kind} <- {canary!r}")
        return (f"the wire assertion failed on HTTP: {len(opacity)} declared-opaque field(s) were not opaque, "
                f"{len(leaks)} canary hit(s) (listed above)")

    log(f"wire assertion: {len(entries)} request(s) from {rel} carry no readable opaque field and no canary")
    return None


def stored_blob_count(database: str) -> int:
    """How many completed blobs this suite's own database records — the row side of what the bucket should hold.

    Derived, never listed: it is what tells a suite that *stored nothing* (recovery, scope rotation) apart from
    one whose objects went missing, without this file carrying a list of which suites upload. `veyq_blob` is the
    only table a completed object is recorded in (`instance_backup.Database.blob_index`).
    """
    proc = subprocess.run(
        COMPOSE + ["exec", "-T", "mariadb", "mariadb", "-uroot", f"-p{DB_ROOT_PASSWORD}", "-N", "-B",
                   "-e", "SELECT COUNT(*) FROM veyq_blob;", database],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        raise SweepError(f"could not count blobs in {database}: {proc.stderr.strip()[-300:]}")
    return int(proc.stdout.strip() or 0)


def sweep_store(bucket: str, database: str, canary_file: Path, rel: str, workdir: Path) -> str | None:
    """Prove that nothing the suite sealed is readable in the object store — keys included.

    **The orchestrator's surface, not the suite's** (ADR 0053 §3): only this side holds the store credentials.
    A client-side hook would see what the client *sent* and never what is *stored* — not an object the server
    wrote itself, and not the object key, which is a name the operator reads without opening anything.

    Mirrors with the operator tool's own rclone plumbing rather than a second copy of it, so the sweep reads the
    bucket the documented backup would capture.
    """
    if not canary_file.is_file():
        return f"{rel} left no canary file — the store sweep would have proved the absence of nothing."
    canaries = json.loads(canary_file.read_text())
    if not canaries:
        return f"{rel} recorded no canary — a green store sweep would mean nothing."

    mirror = workdir / f"store-{bucket}-{rel.replace('/', '_')}"
    mirror.mkdir(parents=True, exist_ok=True)
    try:
        _store_for(bucket).mirror_out(mirror)
    except instance_backup.BackupError as e:
        return f"could not mirror {bucket} for the store sweep: {e}"

    objects = [path for path in mirror.rglob("*") if path.is_file()]
    if not objects:
        # **A suite that stores nothing is skipped OUT LOUD; a suite whose objects vanished is a failure.** The
        # two look identical from the bucket, so the discriminator comes from the suite's own database rather
        # than from a list of which suites upload — a list would go stale the first time a suite grew a file.
        blobs = stored_blob_count(database)
        if blobs:
            return (f"{rel} recorded {blobs} blob(s) in {database} but {bucket} mirrors empty — the objects are "
                    f"missing, or this sweep is pointed at the wrong bucket. Either way it proves nothing.")
        log(f"store sweep skipped for {rel}: it stored no object ({database}.veyq_blob is empty), so there is "
            f"nothing on this surface to prove absent")
        return None

    hits: list[tuple[str, str, str]] = []
    for path in objects:
        key = str(path.relative_to(mirror))
        blob = path.read_bytes()
        for canary in canaries:
            if canary in key:
                hits.append((key, canary, "object key"))
            if canary.encode("utf-8") in blob:
                hits.append((key, canary, "object bytes"))

    if hits:
        log(f"WIRE ASSERTION FAILED for {rel} on the store — {len(hits)} hit(s) in {bucket}:")
        for key, canary, where in hits:
            log(f"    {key:<44} {where} <- {canary!r}")
        return f"the wire assertion failed on the store: {len(hits)} hit(s) in {bucket} (listed above)"

    log(f"wire assertion: {len(canaries)} canary/canaries from {rel} appear in none of "
        f"{len(objects)} object(s) of {bucket}, key or bytes")
    return None


class Instance:
    """The three things a boot is pointed at, and the one thing a group's hook replaces wholesale."""

    def __init__(self, database: str, secret_file: Path, bucket: str | None = None) -> None:
        self.database = database
        self.secret_file = secret_file
        self.bucket = bucket

    def describe(self) -> str:
        return f"database {self.database}" + (f", bucket {self.bucket}" if self.bucket else "")


def group_of(path: Path) -> str:
    """`restore.a-capture.e2e.test.ts` → `restore`; `media.e2e.test.ts` → `media` (a group of one)."""
    return path.name[: -len(".e2e.test.ts")].split(".", 1)[0]


def into_groups(files: list[Path]) -> list[list[Path]]:
    """Group by stem, members in filename order, groups in first-appearance order.

    Applied to `files` AFTER discovery rather than inside `discover()`: `validate_e2e_discovery.py` asserts,
    textually, that `main()`'s no-argument path reads `else discover()` and that `discover()` returns a
    non-empty list of `.e2e.test.ts` paths. Reshaping it would have broken a guard that anchors on it — which
    is exactly what `changes/README.md` § before reshaping a symbol says to check first.
    """
    grouped: dict[str, list[Path]] = {}
    for path in files:
        grouped.setdefault(group_of(path), []).append(path)
    return [sorted(members) for members in grouped.values()]


def garage(*args: str) -> subprocess.CompletedProcess:
    """Drive the store's CLI. Its image is shell-less, so this execs the binary directly, never through `sh`."""
    return subprocess.run(COMPOSE + ["exec", "-T", "garage", "/garage", *args], capture_output=True, text=True)


def fresh_bucket(name: str) -> None:
    """An EMPTY bucket the dev key owns — purge-then-create, exactly as [fresh_database] drops-then-creates.

    "Create if absent" is not enough and the difference is not theoretical: bucket names are derived from the
    group index, so a second run reuses them, and `instance_backup.py restore` refuses a non-empty target
    outright (A5). It refused, correctly, on the leftovers of the previous run — which is the tool's gate doing
    its job and this function's bug, not the other way round.
    """
    try:
        _store_for(name).purge()
    except instance_backup.BackupError:
        pass  # not there yet — the ordinary case on a first run
    garage("bucket", "create", name)
    result = garage("bucket", "allow", name, "--key", "veyq-app", "--read", "--write", "--owner")
    if result.returncode != 0:
        raise SystemExit(f"[e2e] could not prepare bucket {name}: {result.stderr.strip()[-400:]}")


def backup_env() -> dict[str, str]:
    """What `scripts/instance_backup.py` reads — the app's OWN variables, pointed at the store's INTERNAL name.

    `%e2e` gives the jar `http://localhost:19000`, the published port; the tool's rclone runs in a container on
    the compose network, where the store is `garage:3900`. Two addresses for one store, and handing the wrong
    one over is a connection refused deep inside a capture.
    """
    return {
        **os.environ,
        "VEYQ_BACKUP_DB_PASSWORD": DB_ROOT_PASSWORD,
        "VEYQ_BLOB_STORE_ENDPOINT": "http://garage:3900",
        "VEYQ_BLOB_STORE_ACCESS_KEY": "GK0123456789abcdef01234567",
        "VEYQ_BLOB_STORE_SECRET_KEY": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    }


def backup_tool_result(*args: str, env_extra: dict[str, str] | None = None) -> subprocess.CompletedProcess:
    """Run the DOCUMENTED tool, as an operator would, and hand back how it went.

    Split out of [backup_tool] for the one rehearsal whose subject is the tool's *failure*
    ([rehearse_production_project]): a capture that must not resolve a stack cannot be run through a wrapper
    whose only outcome for a non-zero exit is to end the process. `env_extra` is what the operator's
    `infra/.env` would supply — see [production_compose_env] for why its absence is not a neutral difference.
    """
    cmd = [sys.executable, str(REPO / "scripts" / "instance_backup.py"), *args,
           "--docker-network", DOCKER_NETWORK]
    result = subprocess.run(cmd, env={**backup_env(), **(env_extra or {})}, capture_output=True, text=True)
    for line in (result.stdout + result.stderr).splitlines():
        log(f"  {line}")
    return result


def backup_tool(*args: str) -> None:
    """Run the DOCUMENTED tool, as an operator would, rather than reaching into its internals.

    That is the point of the rehearsal: what the suites prove restorable is a procedure `infra/README.md`
    prints, not a private path this harness happens to take. **Which one, precisely:** the group hooks capture
    and restore the *bundled dev* stack — that is the one they run against, and for a long time this line said
    "the procedure" as though there were only one, while the production procedure named a different project and
    a different network and was never exercised at all. [rehearse_production_project] covers the part of that
    gap a host without a production stack can cover; the rest is stated as a limit there.
    """
    result = backup_tool_result(*args)
    if result.returncode != 0:
        raise SystemExit(f"[e2e] instance_backup.py {args[0]} failed with exit {result.returncode}")


def destroy(inst: Instance) -> None:
    """Destroy the source instance: drop its database, purge its bucket, remove its key file.

    Without this the rehearsal proves nothing. A part-B run that silently kept pointing at the source would
    PASS — "everything was restored" and "nothing was restored" are indistinguishable while the source is still
    standing. `rclone purge` removes the bucket along with its objects, which is exactly what is wanted here.
    """
    log(f"destroying the source instance ({inst.describe()})")
    mysql(f"DROP DATABASE IF EXISTS {inst.database};")
    if inst.bucket:
        try:
            _store_for(inst.bucket).purge()
        except instance_backup.BackupError as e:
            raise SystemExit(f"[e2e] could not destroy the source bucket: {e}") from e
    inst.secret_file.unlink(missing_ok=True)


def _store_for(bucket: str):
    """An `instance_backup.Store` bound to this bucket — the tool's own rclone plumbing, not a second copy."""
    env = backup_env()
    return instance_backup.Store(SimpleNamespace(
        store_endpoint=env["VEYQ_BLOB_STORE_ENDPOINT"],
        store_access_key=env["VEYQ_BLOB_STORE_ACCESS_KEY"],
        store_secret_key=env["VEYQ_BLOB_STORE_SECRET_KEY"],
        store_bucket=bucket, store_region="us-east-1",
        rclone_image=instance_backup.RCLONE_IMAGE, rclone_binary=None, docker_network=DOCKER_NETWORK,
    ))


def restore_hook(index: int, source: Instance, group_dir: Path, with_key: bool) -> Instance:
    """Capture the (already stopped) source, destroy it, and restore onto a **freshly installed** instance.

    The app is stopped before this runs, which is the entire consistency argument (ADR 0056 §2) — with it down
    the database cannot gain a row, so a row whose object was never captured is impossible and there is no
    ordering to get right.

    **The target is installed, not merely created, and that is the half ADR 0067 changed.** This used to point
    the restore at a database that had never been migrated — a shape an operator could produce while a compose
    sidecar made the store usable. With the backend as the only provisioner, the two preconditions became
    mutually unreachable: the bucket exists only after the app has started, and the same start migrates the
    schema. So the procedure this rehearses is now *install, then restore*, and rehearsing anything else would
    rehearse a procedure nobody can follow. A5's gate moved with it, from **no tables** to **no data**, and
    [assert_populated_target_is_refused] is what keeps that relaxation honest in the same run.

    `with_key=False` is the leg-3 rehearsal: legs (a) and (b) arrive, leg (c) does not, and the instance that
    comes up boots, serves content, and reports `configSecretState: MISMATCH` while refusing member invites
    with `503 mail_unavailable`. It is produced by removing the key from the BACKUP after a full capture, not
    by capturing badly — so the two groups differ in exactly one artifact.
    """
    backup = group_dir / "backup"
    backup_tool("capture", "--into", str(backup), "--db-name", source.database,
                "--store-bucket", source.bucket or "veyq", "--config-secret-file", str(source.secret_file))
    if not with_key:
        log("leg-3 rehearsal: dropping config-secret.key from the capture")
        (backup / instance_backup.KEY_NAME).unlink(missing_ok=True)
        manifest = json.loads((backup / instance_backup.MANIFEST_NAME).read_text())
        manifest["configSecret"]["present"] = False
        (backup / instance_backup.MANIFEST_NAME).write_text(json.dumps(manifest, indent=2) + "\n")

    destroy(source)

    target = Instance(f"veyq_e2e_restore_{index}", group_dir / "restored-config-secret.key",
                      f"veyq-e2e-dst-{index}")
    fresh_database(target.database)
    fresh_bucket(target.bucket)
    install(target, group_dir / f"backend-install-{index}.log")
    backup_tool("restore", str(backup), "--domain", HOST, "--db-name", target.database,
                "--store-bucket", target.bucket, "--config-secret-file", str(target.secret_file))
    assert_populated_target_is_refused(backup, target)
    return target


def install(target: Instance, boot_log: Path) -> None:
    """Start the app once against [target] and stop it — which is what "a freshly installed instance" IS.

    One boot migrates the schema (`quarkus.flyway.migrate-at-start=true`, no profile gate) and runs the store
    provisioning. That is the operator's step 1 of a restore now, so it is this rehearsal's step 1 too.
    """
    log(f"installing the restore target ({target.describe()}): one boot to migrate and provision")
    proc = boot(target.database, target.secret_file, boot_log, target.bucket)
    try:
        not_ready = wait_ready(time.time() + BOOT_SECS, proc)
        if not_ready:
            tail(boot_log)
            raise SystemExit(f"[e2e] RESTORE TARGET INSTALL FAILED: {not_ready} (boot log above)")
    finally:
        kill(proc)
    # The key file the install generated is NOT the one the backup carries; leaving it in place would let a
    # restore that never wrote leg (c) look like one that did.
    target.secret_file.unlink(missing_ok=True)


def assert_populated_target_is_refused(backup: Path, target: Instance) -> None:
    """The other half of A5's gate, on the instance the restore just populated.

    A relaxation is only honest if the refusal it relaxed still happens. `restore_hook` above proves the gate
    now ACCEPTS a freshly installed instance; this proves the same gate still REFUSES a populated one — the
    instance one line up, holding real restored rows. Asserted on the specific message, never on "it threw": a
    bare non-zero exit is equally produced by a connection failure, which would turn this into a check that
    passes hardest when the tool is most broken.
    """
    log("A5 control: the same restore, again, onto the instance it just populated — must be refused")
    result = backup_tool_result("restore", str(backup), "--domain", HOST, "--db-name", target.database,
                                "--store-bucket", target.bucket, "--config-secret-file", str(target.secret_file))
    body = result.stdout + result.stderr
    if result.returncode == 0:
        raise SystemExit(
            "[e2e] A5 GATE OPEN: a restore onto a POPULATED instance succeeded. Nothing reconciles two "
            "populated instances, and the emptiness gate is the only thing that says so."
        )
    if "already holds data" not in body:
        raise SystemExit(
            f"[e2e] A5 GATE REFUSED FOR THE WRONG REASON: the restore failed without naming the data it found. "
            f"A connection failure produces the same exit code, so this run proves nothing about the gate. "
            f"Output was: {body.strip()[-500:]}"
        )
    log("A5 control: refused, by the emptiness gate, naming the rows it found")


# Group stem → the hook that runs between its members. A multi-member group with no entry here is a hard error:
# it would otherwise run its second file against the first one's instance and pass for the wrong reason.
HOOKS = {
    "restore": lambda index, source, group_dir: restore_hook(index, source, group_dir, with_key=True),
    "legthree": lambda index, source, group_dir: restore_hook(index, source, group_dir, with_key=False),
}


# ----------------------------------------------------------------------------------------------------------
# The production-project rehearsal — which stack does a PRODUCTION capture resolve?
# ----------------------------------------------------------------------------------------------------------

# Instance-SHAPED and deliberately nothing more. The decoy exists so that "which project did the capture
# resolve?" has an exit code instead of a wording: with it in place the dev-file capture completes and the
# production-file capture cannot, and the difference is the one flag between them.
DECOY_DB = "veyq_prod_project_decoy"
DECOY_BUCKET = "veyq-prod-project-decoy"
DECOY_DOMAIN = "decoy.invalid"


def production_compose_env() -> dict[str, str]:
    """Stand in for the operator's `infra/.env` — DERIVED from the file, never listed beside it.

    Compose interpolates the entire production file before it resolves anything, so a `${VAR:?…}` with no value
    kills the command with `required variable … is missing a value`. That is not a project-resolution failure
    and must never be read as one: on this rehearsal's very first run it produced a **green** that had proven
    nothing, because the subject "refused" without Compose ever reaching a project. Deriving the set by reading
    the file means a newly required variable cannot quietly turn this rehearsal into a test of its own
    environment. Nothing here runs `up`, so no image is pulled and no value reaches a container.
    """
    required = sorted(set(re.findall(r"\$\{([A-Z0-9_]+):\?", PROD_COMPOSE_FILE.read_text())))
    env = {name: "prod-project-rehearsal-placeholder" for name in required}
    # The three Compose or its consumers parse as a shape rather than merely read as present.
    for name, value in (("VEYQ_IMAGE", "ghcr.io/veyq/veyq:1.0"),
                        ("VEYQ_DOMAIN", DECOY_DOMAIN),
                        ("VEYQ_ACME_EMAIL", f"rehearsal@{DECOY_DOMAIN}")):
        if name in env:
            env[name] = value
    return env


def seed_project_decoy(name: str) -> None:
    """The four tables `capture` reads BEFORE it dumps — the smallest thing the tool accepts as an instance.

    Not a copy of veyq's schema and not a claim about one. Every column here exists because
    `instance_backup.Database` selects it: `veyq_tenant.domain`, the flyway history's three, `veyq_blob`'s two,
    and a row count off `veyq_upload_session`. If the tool learns to read a fifth table, this stops satisfying
    the capture and the **control** below goes red — which is the right failure, because a decoy that quietly
    stopped being instance-shaped would turn the control into "nothing ran" while it still reported green.
    """
    fresh_database(name)
    mysql(
        f"USE {name}; "
        "CREATE TABLE veyq_tenant (domain VARCHAR(255) NOT NULL); "
        f"INSERT INTO veyq_tenant (domain) VALUES ('{DECOY_DOMAIN}'); "
        "CREATE TABLE flyway_schema_history (installed_rank INT, version VARCHAR(50), success TINYINT(1)); "
        "INSERT INTO flyway_schema_history (installed_rank, version, success) VALUES (1, '1', 1); "
        "CREATE TABLE veyq_blob (storage_key VARCHAR(255), chunk_count INT); "
        "CREATE TABLE veyq_upload_session (id BIGINT);"
    )


def rehearse_production_project(workdir: Path) -> None:
    """A capture pointed at the PRODUCTION compose file must not resolve the DEV project.

    **What it asserts.** Two captures, one flag apart. Same decoy database, same bucket, same key file, same
    docker network, same credentials — only `--compose-file` differs (and `--into`, which must, because a
    capture refuses to write a second capture into a non-empty directory). The **dev** file must reach the
    decoy and complete; the **production** file must not reach it at all. That pairing is what makes a red
    readable: the control establishes that a database was reachable through this exact invocation at this exact
    moment, so a subject that fails anyway failed on the flag that differs, and a subject that *succeeds*
    resolved a project it has no business resolving.

    **The defect that bought it.** Compose derives a project name from the compose file's DIRECTORY unless the
    file declares one, `infra/` holds both files, and `instance_backup.py` emits no `-p`. So the documented
    production capture resolved the dev project. Measured 2026-08-17 on a host running the dev stack:
    `docker compose -f infra/docker-compose.prod.yml ps` listed all four dev containers as its own, and
    `… exec -T mariadb true` exited 0 against `veyq-mariadb`. Note what that means and what the work order
    written against this predicted instead: the production capture does **not** fail to find its database — it
    silently captures the wrong one, which is the worse of the two and the one no operator would notice.

    **Its limit.** A green says the production capture does not resolve the dev project. It does **not** say a
    real production stack under project `veyq` IS resolved: no production topology has ever been brought up
    here — not the app image, not traefik's `80:443`, not the six named volumes — so the positive direction is
    an explicitly stated gap, not an oversight. It also says nothing about the project an operator actually
    brought their stack up under; the file names one, and nothing here can see a `-p` on their shell.
    """
    log("=== rehearsal: a production capture must not resolve the dev project ===")
    home = workdir / "prod-project-rehearsal"
    home.mkdir(parents=True, exist_ok=True)
    key = home / "config-secret.key"
    key.write_bytes(b"rehearsal decoy - never an instance's key\n")
    env_extra = production_compose_env()

    # FIRST, because getting this wrong is invisible: Compose interpolates the whole production file before it
    # resolves a single container, so a missing variable fails the capture in a way that looks exactly like the
    # verdict this rehearsal exists to report. Observed here on the first run — the subject "correctly" refused
    # while Compose had in fact never reached project resolution at all, and the rehearsal called that green.
    probe = subprocess.run(["docker", "compose", "-f", str(PROD_COMPOSE_FILE), "config", "-q"],
                           capture_output=True, text=True, env={**os.environ, **env_extra})
    if probe.returncode != 0:
        raise SystemExit(
            f"[e2e] PRODUCTION-PROJECT REHEARSAL INCONCLUSIVE: "
            f"{PROD_COMPOSE_FILE.relative_to(REPO)} does not resolve even with every `${{VAR:?}}` it declares "
            f"supplied ({probe.stderr.strip()[-300:]}). A capture against it would fail here, before Compose "
            f"resolved any project — which is indistinguishable from the verdict this rehearsal reports, and "
            f"would be scored as a pass."
        )

    seed_project_decoy(DECOY_DB)
    fresh_bucket(DECOY_BUCKET)
    shared = ["capture", "--db-name", DECOY_DB, "--store-bucket", DECOY_BUCKET,
              "--config-secret-file", str(key)]

    log(f"control: the same capture through {COMPOSE_FILE.relative_to(REPO)} must reach the decoy")
    control = backup_tool_result(*shared, "--compose-file", str(COMPOSE_FILE),
                                 "--into", str(home / "control"), env_extra=env_extra)
    if control.returncode != 0:
        raise SystemExit(
            f"[e2e] PRODUCTION-PROJECT REHEARSAL INCONCLUSIVE: the control capture — the same command through "
            f"{COMPOSE_FILE.relative_to(REPO)}, which resolves the project this stack is actually running as — "
            f"failed with exit {control.returncode}. Nothing was reachable, so the subject below could only "
            f"have proven that. Fix the control first; a rehearsal whose control is red reports 'the project is "
            f"correctly not resolved' and 'nothing ran' identically."
        )

    log(f"subject: the same capture through {PROD_COMPOSE_FILE.relative_to(REPO)} must NOT reach it")
    subject = backup_tool_result(*shared, "--compose-file", str(PROD_COMPOSE_FILE),
                                 "--into", str(home / "production"), env_extra=env_extra)
    manifest = home / "production" / instance_backup.MANIFEST_NAME
    if subject.returncode == 0 or manifest.is_file():
        captured = "(no manifest)"
        if manifest.is_file():
            captured = ", ".join(json.loads(manifest.read_text()).get("domains") or []) or "(no domain)"
        raise SystemExit(
            f"[e2e] PRODUCTION-PROJECT REHEARSAL FAILED: a capture pointed at "
            f"{PROD_COMPOSE_FILE.relative_to(REPO)} exited {subject.returncode} and captured database "
            f"{DECOY_DB}, domains {captured} — an instance living in the DEV project. The production file names "
            f"no project, so Compose derived one from its directory (`{PROD_COMPOSE_FILE.parent.name}`) and "
            f"handed the production procedure whatever happens to run out of it. On a production host that is a "
            f"backup of the wrong instance, taken and reported as a success. Declare the project in the file "
            f"(`name:`) so the procedure cannot be pointed at the wrong stack by standing in the wrong directory."
        )
    log(f"the production capture did not resolve the dev project (exit {subject.returncode}), "
        f"while the control through {COMPOSE_FILE.relative_to(REPO)} reached {DECOY_DB} — the project is a "
        f"property of the file, not of the directory it sits in")


def run_group(index: int, members: list[Path], workdir: Path, stamp: str) -> list[tuple[str, str]]:
    """Run a group's members in order against one instance session. Returns every member's failure, if any.

    A group of ONE takes exactly the old path: one fresh database, no bucket override, no hook.
    """
    name = group_of(members[0])
    inst = Instance(f"veyq_e2e_{index}", workdir / f"config-secret-{index}.key",
                    f"veyq-e2e-src-{index}" if len(members) > 1 else None)
    group_dir = workdir / f"group-{index}-{name}"

    if len(members) > 1:
        if name not in HOOKS:
            raise SystemExit(
                f"[e2e] group `{name}` has {len(members)} members and no hook. A second member run against the "
                f"first one's instance would pass without proving anything — add a hook to HOOKS, or name the "
                f"files so they are separate groups."
            )
        group_dir.mkdir(parents=True, exist_ok=True)
        log(f"=== group {name}: {len(members)} member(s), one instance session ===")
        fresh_bucket(inst.bucket)

    fresh_database(inst.database)
    failures: list[tuple[str, str]] = []
    for position, path in enumerate(members, start=1):
        if position > 1:
            inst = HOOKS[name](index, inst, group_dir)
        reason = run_member(index, position, path, inst, group_dir if len(members) > 1 else None, workdir, stamp)
        if reason:
            # Never short-circuit ACROSS groups; within one, a failed member makes every later member
            # meaningless — it would run against an instance whose precondition was never established.
            failures.append((path.relative_to(WEB).as_posix(), reason))
            if position < len(members):
                for skipped in members[position:]:
                    failures.append((skipped.relative_to(WEB).as_posix(),
                                     f"not run: an earlier member of group `{name}` failed"))
            break
    return failures


def run_member(index: int, position: int, test_path: Path, inst: Instance, group_dir: Path | None,
               workdir: Path, stamp: str) -> str | None:
    """Run one e2e file against its own fresh backend. Returns None on success, else a failure reason."""
    rel = test_path.relative_to(WEB).as_posix()
    tag = f"{index}" if position == 1 else f"{index}-{position}"
    log(f"=== {rel} ({inst.describe()}) ===")

    secret_file = inst.secret_file
    boot_log = workdir / f"backend-{tag}.log"
    canary_file = workdir / f"canaries-{tag}.json"
    record_file = workdir / f"http-{tag}.jsonl"
    database = inst.database
    proc = boot(database, secret_file, boot_log, inst.bucket)
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
            "VEYQ_E2E": "1", "VEYQ_BASE": BASE, "VEYQ_ADMIN_BASE": ADMIN_BASE, "VEYQ_HOST": HOST, "VEYQ_STAMP": stamp,
            "VEYQ_MAILPIT": MAILPIT,
            # The wire-assertion seam (ADR 0053): the suite writes here what it recovered by unsealing; this
            # side reads it back and sweeps. Absolute, because vitest runs with cwd=web/.
            "VEYQ_CANARY_FILE": str(canary_file),
            # The HTTP half of the same seam: the suite records every request it made and every response it
            # got, this side reads the file back and sweeps it. Absolute, for the same reason.
            "VEYQ_HTTP_RECORD_FILE": str(record_file),
        })
        if group_dir is not None:
            # The seam between a group's members: whatever part A must hand part B (an actor's password, the id
            # of the Memory to reopen, the bytes to compare) goes through a directory that outlives the boot.
            env["VEYQ_GROUP_DIR"] = str(group_dir)
        result = subprocess.run(
            ["npx", "vitest", "run", rel, "--testTimeout", TEST_MS],
            cwd=str(WEB), env=env,
        )
        if result.returncode != 0:
            # No sweep on a failed suite: the canary set is incomplete by construction, so a hit or a miss would
            # both be unreadable. The run already fails on this.
            return f"the suite failed (vitest exit {result.returncode})"
        # Three surfaces, three sensors, one canary file (ADR 0053 §3). Run them all and report the FIRST
        # failure rather than stopping at the database: a run that leaks on two surfaces should say so on the
        # second one too, once the first is fixed, and a sweep skipped is a sweep nobody notices is missing.
        for failure in (
            sweep(database, canary_file, rel),
            sweep_http(record_file, canary_file, rel),
            sweep_store(inst.bucket or STORE_BUCKET, database, canary_file, rel, workdir),
        ):
            if failure:
                return failure
        return None
    finally:
        kill(proc)


def main(argv: list[str]) -> int:
    if not shutil.which("docker"):
        raise SystemExit("[e2e] docker is required (the bundled stack supplies the DB + object store)")
    if not JAR.is_file():
        raise SystemExit(f"[e2e] backend jar missing: {JAR}\nBuild it first: (cd backend && ./gradlew quarkusBuild)")

    files = [normalize(a) for a in argv] if argv else discover()
    groups = into_groups(files)
    for port, variable, url in ((PORT, "VEYQ_PORT", READY_URL), (ADMIN_PORT, "VEYQ_ADMIN_PORT", f"{ADMIN_BASE}/api/setup")):
        if port_is_taken(url):
            raise SystemExit(
                f"[e2e] port {port} is already serving — another backend (an IntelliJ quarkusDev?) is running. "
                f"Stop it, or set {variable} to a free port."
            )

    # One stamp for the whole run: it varies emails/usernames so a re-run against a warm limiter sees a new
    # invite recipient, and the per-file database makes cross-file collisions impossible anyway. Digits only —
    # the username pattern is [A-Za-z0-9_.-] with a 32-char cap.
    stamp = str(int(time.time()))
    log(f"running {len(files)} e2e file(s) in {len(groups)} group(s), stamp={stamp}")

    # Retained, not a tempdir: the boot log is itself a sweep surface (ADR 0053 § Decision 4), and a canary file
    # discarded on exit cannot be re-read when a sweep hit needs explaining. It is created BEFORE the stack comes
    # up because `compose_up` now ends in a backend boot of its own, whose log belongs here with the rest.
    workdir = RUN_ROOT / stamp
    workdir.mkdir(parents=True, exist_ok=True)
    log(f"run artifacts (boot logs, canary files) land in {workdir.relative_to(REPO)}/")

    compose_up(workdir)

    # Before any suite: the operator procedure the suites later rehearse is only worth rehearsing if it can be
    # aimed. This one needs no jar and no instance — it runs here, on the stack `compose_up()` just brought up.
    rehearse_production_project(workdir)

    failures: list[tuple[str, str]] = []
    for index, members in enumerate(groups, start=1):
        # Never short-circuit across groups: one broken suite must not hide the state of the others.
        failures.extend(run_group(index, members, workdir, stamp))

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
