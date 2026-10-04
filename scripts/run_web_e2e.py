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
gets its own boot, keeps the shared `blobs` bucket, and never touches a hook. Only a real group gets its own
bucket, because only a real group has a hook that destroys one.

The hook is what a group exists for: `restore.*` captures the instance with the app already stopped,
**destroys the source** (database dropped, bucket purged, key file removed), restores onto a structurally empty
one, and boots the next member against *that*. Destroying the source is not tidiness — it is the only way "the
restore worked" and "nothing was restored" can be told apart from outside. Members exchange whatever they need
through `$VEYQ_GROUP_DIR`, a directory that outlives the boot.

**The wire assertion (ADR 0053).** Each suite writes the plaintext it recovered by *unsealing the server's own
responses* to `$VEYQ_CANARY_FILE`; this side then proves none of it is readable where an operator could read it —
per suite in the database ([sweep]), the HTTP recording ([sweep_http]) and the object store ([sweep_store]), and
once per run, after every group, in every boot log and container log ([sweep_logs], [sweep_containers]), in every
statement the database received ([sweep_statements]) and in every mail the run sent ([sweep_mailbox]). The two
halves are deliberately split by what each can reach: only the suite holds the keys, only this process holds
Docker and the database root credential. Every one of those sweeps also searches a **second needle set** — every
capability token the run handled, read out of the HTTP recording by the contract's `token` marker
([token_needles]) — and the database sweep proves it armed by finding the tokens' hashes ([db_token_findings]).

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
`V<yyyymmddHHMM>__*.sql` in the tree before believing a failure. Rebuild after ANY backend change; `./gradlew check` does
not do it for you (it builds test classes, not the fast-jar). The web mirror of this trap is in `web/CLAUDE.md`
— `npm run check` not regenerating the API client — and it is the same failure in both directions: a green or
red that was measured against code the run never saw.

**The run directory survives the run** (`.e2e-out/<stamp>/`, gitignored). It holds each boot's log, its
per-boot config-secret file, its canary file, its HTTP recording and the dump its database sweep read
(`database-<tag>.sql`), and — once the run-level sweep
has read them — every container's log over the run's window (`container-<service>.log`), the database's
statement log (`mariadb-general.log`) and every mail of the run (`mailbox/<id>.eml`). It used to be a `tempfile.TemporaryDirectory` discarded on
exit, which left the boot log — the surface where an error `message` or a stack trace would carry plaintext —
not merely unswept but unavailable (ADR 0053 § Decision 4). Nothing prunes it; it is a developer artifact and
CI throws the runner away.

Environment (all optional):
    VEYQ_JAR              Backend fast-jar. Default: backend/build/quarkus-app/quarkus-run.jar
    VEYQ_PORT             Backend HTTP port. Default: 8080
    VEYQ_ADMIN_PORT       Backend admin-listener port (ADR 0065). Default: 8090
    VEYQ_INFRA_DIR        The `infra/` whose compose stack to use. Default: this checkout's (see [compose_up] for a worktree)
    VEYQ_HOST             Tenant domain + fetch Host. Default: veyq.localhost
    VEYQ_BOOT_SECS        Readiness timeout per boot. Default: 120
    VEYQ_TEST_MS          Default per-test timeout passed to vitest. Default: 120000
    VEYQ_DB_ROOT_PASSWORD MariaDB root password. Default: the documented compose throwaway.
"""
from __future__ import annotations

import base64
import binascii
import bisect
import calendar
import email
import email.message
import email.policy
import hashlib
import json
import os
import re
import shutil
import signal
import smtplib
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from types import SimpleNamespace
from typing import Callable, Iterable, Iterator, NamedTuple

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
# `VEYQ_INFRA_DIR` pins the stack to another checkout's `infra/` — see the worktree note in [compose_up].
INFRA = Path(os.environ.get("VEYQ_INFRA_DIR", REPO / "infra"))
COMPOSE_FILE = INFRA / "docker-compose.yml"
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
PROD_COMPOSE_FILE = INFRA / "docker-compose.prod.yml"

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

# Every database a boot of this run is pointed at, named in ONE place: the boots use these, and [sweep_logs]
# derives from a boot log's file name which of them that boot's log must name (its armed-sensor control).
STORE_INIT_DATABASE = "veyq_e2e_store_init"


def suite_database(index: int) -> str:
    """A group's first instance — the database its first member runs against."""
    return f"veyq_e2e_{index}"


def restore_database(index: int) -> str:
    """The instance a group's hook restores onto — every later member of the group runs against it."""
    return f"veyq_e2e_restore_{index}"


# The bundled store's ADMIN plane (a different port from the S3 one), published local-only by
# infra/docker-compose.yml. This script READS it to confirm the backend provisioned the store; it never writes
# through it. Documented throwaway dev token, not a secret.
GARAGE_ADMIN = os.environ.get("VEYQ_GARAGE_ADMIN", "http://127.0.0.1:19003")
GARAGE_ADMIN_TOKEN = os.environ.get("VEYQ_GARAGE_ADMIN_TOKEN", "veyq-dev-admin-token")
# The bucket `%e2e` boots with — the backend's default `veyq.blob-store.bucket`, which `%e2e` does not override — and
# the key alias the backend imports it under (GarageStoreProvisioner.KEY_ALIAS). `fresh_bucket` grants against that
# exact alias, so the name is a contract between this file and that class.
STORE_BUCKET = "blobs"
STORE_KEY_ALIAS = "veyq-app"
# The store's compose service, and its S3 API as `infra/docker-compose.yml` publishes it to this host (`19000:3900`)
# — the endpoint `%e2e` hands the jar, so every presigned URL of a run goes through it. [plant_store_sentinel]
# sends the run's log control there.
STORE_SERVICE = "garage"
STORE_S3 = "http://127.0.0.1:19000"

# The throwaway mailbox, published by `infra/docker-compose.yml`'s `mail` profile. It is EXPORTED to each suite as
# `VEYQ_MAILPIT` rather than left to the suite's own default: mail is no longer one feature's substrate — a member
# invite's token travels by mail only (ADR 0055 §2) — and the thing that starts a substrate is the thing that
# should name its address.
MAILPIT = "http://127.0.0.1:18025"
# Its SMTP port as the same compose file publishes it (`11025 -> 1025`) — where every suite's tenant sends, and where
# [plant_mail_sentinel] delivers the mailbox sweep's control.
MAILPIT_SMTP = ("127.0.0.1", 11025)

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


def shown(path: Path) -> Path:
    """A path for a log line: relative to this checkout where it is inside it — `VEYQ_INFRA_DIR` may point outside."""
    try:
        return path.relative_to(REPO)
    except ValueError:
        return path


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
    """Bring up the bundled stack, then make every half it provides actually USABLE.

    The wait is per-service rather than one `up --wait` on the whole project: `--wait` scores any container exit
    as a failure, so a project holding a one-shot would fail on its clean exit. There is no one-shot left here —
    ADR 0067 deleted the init sidecar — but the habit is worth keeping, because each dependency is then waited on
    for its own reason: mariadb's healthcheck (Flyway needs it accepting connections) and mailpit's
    (`/mailpit readyz`, declared by the image rather than by our compose file).

    **Then the store, which is the half that changed.** A `--profile s3 up` now leaves Garage *running but not
    usable*: no layout, no bucket, no key, no grant. Nothing but a backend can close that gap any more, so `main()`
    follows this by booting one whose only job is to do it ([provision_store]) — after it has switched the statement
    log on ([StatementLog]), so that boot's statements are inside the window like every other's.

    Mailpit is unconditional, not a flag: every suite's tenant is now pointed at it (`e2eBootstrap.birthInstance`)
    because a member invite travels by mail only (ADR 0055 §2). Gating that behind an argument would mean gating
    something that must always be on — and this script has no argparse at all; every argv entry is a test path.
    Note that mailpit's healthcheck declares a 15 s interval and no `start_period`, so a cold `--wait` genuinely
    blocks about that long. That is the right trade against a first-suite flake, and the reason not to substitute
    a snappier poll of our own.
    """
    log(f"compose up (--profile s3 --profile mail) from {COMPOSE_FILE}")
    # Run from a git WORKTREE, this is the same compose project (`infra`) from a different directory: bind mounts
    # resolve against THIS checkout, so every service with one gets a new config hash and is recreated onto the
    # worktree's files — the developer's own dev containers, left mounting a directory that is deleted with the
    # worktree (measured 2026-09-24: `veyq-garage` rebound to the worktree's `garage.toml`). From a worktree, run
    # THIS checkout's copy with `VEYQ_INFRA_DIR=<main checkout>/infra`: the stack stays the main checkout's while
    # the suites, contracts and jar are the worktree's. Running the MAIN checkout's copy instead (the earlier advice
    # here) runs the main checkout's SUITES — wrong whenever the web code changed along with the backend.
    run(COMPOSE + ["up", "-d"])
    log("waiting for mariadb to report healthy")
    run(COMPOSE + ["up", "-d", "--wait", "mariadb"])
    log("waiting for mailpit to report healthy")
    run(COMPOSE + ["up", "-d", "--wait", "mailpit"])
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
    database = STORE_INIT_DATABASE
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


def mysql_row(sql: str) -> list[str]:
    """Run one SELECT as root and return its single row's columns (batch mode, no header)."""
    proc = run(COMPOSE + ["exec", "-T", "mariadb", "mariadb", "-uroot", f"-p{DB_ROOT_PASSWORD}", "-N", "-B", "-e", sql],
               capture_output=True, text=True)
    return proc.stdout.rstrip("\n").split("\t")


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
        root would share one KEK file and leave an untracked `0600` secret beside the repo (ADR 0050).

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
    # The log is a sweep surface ([sweep_logs]), so the bytes it holds must not depend on the host: with stdout a
    # FILE, Java 21 encodes it in the host locale's charset. Measured: without these two flags, under `LC_ALL=C`,
    # the boot log's `—` came out as `?` — and a non-ASCII canary would too, which the needles then miss.
    # Pinned here; [sweep_logs] reads the file as UTF-8.
    return subprocess.Popen(
        ["java", "-Dquarkus.profile=e2e", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar", str(JAR)],
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


def sweep(database: str, canary_file: Path, rel: str, tokens: dict[str, Token] | None = None,
          retain: Path | None = None) -> str | None:
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
    unswept surface is clean: the store, the HTTP recording and the logs are their own sweeps ([sweep_store],
    [sweep_http], [sweep_logs], [sweep_containers]). Its residual gap is coverage, not correctness — the sweep
    sees only flows some suite drives. And passing cannot be arranged from inside the client (the bytes are in a
    database this process does not write), but a canary can still be *removed* — a visible diff, exactly like
    widening an allowlist would be, which is weaker than tamper-proof and should not be stated as more.

    **The second needle set — capability tokens** (`tokens`, [token_needles]). The same rows are searched for every
    token the suite's recording shows it handled, raw, reported as `token:<kind>`; and the hash control
    ([db_token_findings]) must find at least one token's hex SHA-256, or the token half proves nothing. The dump is
    retained (`retain`) so a hit, or a control that failed, can be re-read after the run.
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
    if retain is not None:
        retain.write_text(dump, encoding="utf-8")
    tokens = tokens or {}
    token_hits, hashed = db_token_findings(dump, tokens)
    token_failure = None
    if token_hits or (tokens and not hashed):
        log(f"WIRE ASSERTION FAILED for {rel} on capability tokens in {database} — {len(token_hits)} raw hit(s); "
            f"hash control {'met' if hashed else 'NOT met'}:")
        for value, table in token_hits:
            log(f"    {table:<28} <- token:{tokens[value].kind} {value!r}")
        if not hashed:
            log(f"    hash control: none of the {len(tokens)} token(s) ({kinds_of(tokens.values())}) has its "
                f"{TOKEN_HASH().name} in {database} — the dump is not the database these tokens were minted in, or "
                f"was not read, so 0 raw hits proves nothing")
        token_failure = (f"the wire assertion failed on capability tokens: {len(token_hits)} raw hit(s) in "
                         f"{database}{'' if hashed else ', hash control NOT met'} (listed above)")
    elif tokens:
        log(f"token sweep: {len(tokens)} capability token(s) from {rel} ({kinds_of(tokens.values())}) appear raw "
            f"nowhere in {database}; hash control met — {len(hashed)} found by {TOKEN_HASH().name} "
            f"({kinds_of(hashed)})")
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
        return "; ".join(filter(None, [
            f"the wire assertion failed: {len(hits)} canary/table hit(s) in {database} (listed above)", token_failure]))

    log(f"wire assertion: {len(canaries)} canary/canaries from {rel} appear nowhere in {database}")
    return token_failure


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
    `kdfParams`, `authPublicKey`, `secretKeyProofPublicKey`, `slugToken`), and the shortest an AEAD *can* be is
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

    **A canary is searched in every spelling [canary_needles] models, not only raw.** The recorder stores a URL as
    the client BUILT it — percent-encoded — so a raw match never saw a canary with a space in a path or a query
    string, and most canaries have one. Measured, not read: a suite that sent a space-bearing canary to
    `GET /api/setup?lever=…` passed the raw-only version of this sweep green.
    """
    if not record_file.is_file():
        return (f"{rel} recorded no HTTP traffic at {record_file.name} — the recorder did not arm, so this "
                f"sweep would prove nothing. VEYQ_HTTP_RECORD_FILE must reach the suite process.")
    # `split("\n")`, never `splitlines()`: Python splits the latter on U+0085 and U+2028 as well, which a
    # recorded body can legitimately contain — it cut a JSON record in half the first time this ran.
    entries = [json.loads(line) for line in record_file.read_text().split("\n") if line.strip()]
    if not entries:
        return f"{record_file.name} is empty — {rel} made no request, so a green HTTP sweep here means nothing."

    # A recorder that stopped recording response headers would hide every refresh token (it travels only as a
    # `Set-Cookie`) and every header-borne canary, while every other check here stayed green — so it is a failure.
    headerless = sum(1 for entry in entries if "responseHeaders" not in entry)
    if headerless:
        return (f"{headerless} of {len(entries)} entr(y/ies) in {record_file.name} carry no `responseHeaders` — the "
                f"recorder lost its response-header half, and no Set-Cookie token or header canary can be swept")
    cookies = sum(len(set_cookie_values(entry)) for entry in entries)

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
            entry.get("requestBody") or "", json.dumps(entry.get("responseHeaders") or {}),
            entry.get("responseBody") or "",
        ]))
        for canary in canaries:
            for variant, needle in canary_needles(canary):
                if needle in haystack:
                    leaks.append((where, canary, f"url/header/body [{variant}]"))
                    break

    if opacity or leaks:
        log(f"WIRE ASSERTION FAILED for {rel} on HTTP — {len(opacity)} opacity, {len(leaks)} canary hit(s):")
        for where, prop, why in opacity:
            log(f"    {where:<44} {prop} {why}")
        for where, canary, kind in leaks:
            log(f"    {where:<44} {kind} <- {canary!r}")
        return (f"the wire assertion failed on HTTP: {len(opacity)} declared-opaque field(s) were not opaque, "
                f"{len(leaks)} canary hit(s) (listed above)")

    log(f"wire assertion: {len(entries)} request(s) from {rel} carry no readable opaque field and no canary "
        f"(response headers included; {cookies} non-empty Set-Cookie value(s) recorded)")
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


def sweep_store(bucket: str, database: str, canary_file: Path, rel: str, workdir: Path,
                tokens: dict[str, Token] | None = None) -> str | None:
    """Prove that nothing the suite sealed is readable in the object store — keys included.

    **The orchestrator's surface, not the suite's** (ADR 0053 §3): only this side holds the store credentials.
    A client-side hook would see what the client *sent* and never what is *stored* — not an object the server
    wrote itself, and not the object key, which is a name the operator reads without opening anything.

    Mirrors with the operator tool's own rclone plumbing rather than a second copy of it, so the sweep reads the
    bucket the documented backup would capture. Keys and bytes are searched for the suite's capability tokens too
    (`tokens`, [token_needles]), reported as `token:<kind>`.
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
        for value, token in (tokens or {}).items():
            if token.needle in key:
                hits.append((key, f"token:{token.kind} {value}", "object key"))
            if token.needle.encode("utf-8") in blob:
                hits.append((key, f"token:{token.kind} {value}", "object bytes"))

    if hits:
        log(f"WIRE ASSERTION FAILED for {rel} on the store — {len(hits)} hit(s) in {bucket}:")
        for key, canary, where in hits:
            log(f"    {key:<44} {where} <- {canary!r}")
        return f"the wire assertion failed on the store: {len(hits)} hit(s) in {bucket} (listed above)"

    log(f"wire assertion: {len(canaries)} canary/canaries and {len(tokens or {})} capability token(s) from {rel} "
        f"appear in none of {len(objects)} object(s) of {bucket}, key or bytes")
    return None


# ----------------------------------------------------------------------------------------------------------
# Capability tokens — the second needle set: every bearer or capability secret the run handled
# ----------------------------------------------------------------------------------------------------------

# What `Tokens.hash` stores and looks a token up by: the hex SHA-256 of its UTF-8 bytes (ADR 0004). The hash control
# finds it in a suite's dump, which proves the dump was read and the token set belongs to that database.
TOKEN_HASH = hashlib.sha256

HTTP_METHODS = validate_field_visibility.HTTP_METHODS
TOKEN_CLASS = "token"
# The kind a bearer credential is reported under when no body or header marker named it first.
BEARER_KIND = "Authorization"


class Operation(NamedTuple):
    method: str
    pattern: re.Pattern
    literal: int  # characters of the path that are not a `{param}` — the more, the more specific the match
    spec: str
    operation: dict
    parameters: list


class Contract:
    """Every operation of `contracts/api/`, indexed to match a recorded call, and the `$ref` resolution its walk needs.

    Read from the contracts, never listed: the token set is whatever the `token` class marks (ADR 0044) — body
    properties, scalar component schemas (`InviteBootstrapSecret`), header parameters and response headers — plus
    the bearer of `Authorization`, the one `securityScheme` shape (`type: http, scheme: bearer`).
    """

    def __init__(self, api: Path) -> None:
        self.specs = {p.name: (yaml.safe_load(p.read_text()) or {}) for p in sorted(api.glob("*.openapi.yaml"))}
        self.operations: list[Operation] = []
        self.bearer = False
        for name, spec in self.specs.items():
            for scheme in ((spec.get("components") or {}).get("securitySchemes") or {}).values():
                if scheme.get("type") == "http" and str(scheme.get("scheme", "")).lower() == "bearer":
                    self.bearer = True
            for path, item in (spec.get("paths") or {}).items():
                for method in HTTP_METHODS:
                    operation = (item or {}).get(method)
                    if not isinstance(operation, dict):
                        continue
                    pattern = re.compile("^" + re.sub(r"\\\{[^}]*\\\}", "[^/]+", re.escape(path)) + "$")
                    literal = len(re.sub(r"\{[^}]*\}", "", path))
                    parameters = list(item.get("parameters") or []) + list(operation.get("parameters") or [])
                    self.operations.append(Operation(method.upper(), pattern, literal, name, operation, parameters))

    def resolve(self, node, spec: str) -> tuple[dict, str]:
        """Follow `$ref`s — same-file `#/…` and cross-file `x.yaml#/…` — to the node they name, and its spec."""
        while isinstance(node, dict) and "$ref" in node:
            target, _, pointer = node["$ref"].partition("#")
            spec = Path(target).name if target else spec
            node = self.specs[spec]
            for part in pointer.strip("/").split("/"):
                node = node[part]
        return (node if isinstance(node, dict) else {}), spec

    def match(self, method: str, path: str) -> Operation | None:
        """The operation a recorded call hit: the most literal path template that matches, so `/x/mine` beats `/x/{id}`."""
        found = [op for op in self.operations if op.method == method and op.pattern.match(path)]
        return max(found, key=lambda op: op.literal) if found else None


_CONTRACT: Contract | None = None


def contract() -> Contract:
    """The repo's contracts, loaded once per run. `__file__`-relative, so a copied tree reads its own copy."""
    global _CONTRACT
    if _CONTRACT is None:
        _CONTRACT = Contract(REPO / "contracts" / "api")
    return _CONTRACT


class Token(NamedTuple):
    """One capability token the run handled: what the contract calls it, and what a sweep searches for."""
    kind: str
    needle: str
    weak: bool = False  # named only by the bearer rule; a marker that names it later wins
    url: bool = False  # a presigned URL: its needle is its request target, and its addressee (the store) verifies it


def token_needle(value: str) -> str:
    """What a sweep searches for: the token itself, or for a token that is a URL, its request target.

    A presigned URL (`PresignedUrl.url`) is the one URL-valued token. Its capability is its query (the signature),
    and the party it addresses logs the URI it was handed, not the origin the client used — so the request target
    (`/bucket/key?X-Amz-…`) is the needle. It is a substring of the URL, so a copy of the whole URL is found too.
    """
    parts = urllib.parse.urlsplit(value)
    if parts.scheme in ("http", "https") and parts.netloc:
        return parts.path + (f"?{parts.query}" if parts.query else "")
    return value


def set_cookie_values(entry: dict) -> list[str]:
    """Every NON-EMPTY cookie value a recorded response set: each `Set-Cookie` cut at the first `;`, then at the first `=`.

    A clearing cookie (`veyq_refresh=; Max-Age=0`) yields nothing, which is why `logout`'s marker is harmless. The
    values are base64url (`Tokens.random()`), so no unquoting is modelled — asserted by
    `scripts/validate_wire_log_matcher.py`, not assumed.
    """
    cookies = (entry.get("responseHeaders") or {}).get("set-cookie") or []
    if isinstance(cookies, str):
        cookies = [cookies]
    values = []
    for cookie in cookies:
        _, sep, value = cookie.split(";", 1)[0].partition("=")
        if sep and value.strip():
            values.append(value.strip())
    return values


def header_value(headers: dict, name: str):
    """A recorded header by name, case-insensitively — the client sends `Authorization`, `Headers` lowercases it."""
    wanted = name.lower()
    return next((value for key, value in (headers or {}).items() if key.lower() == wanted), None)


def walk_tokens(value, schema, spec: str, kind: str, add: Callable[[str, str], None]) -> None:
    """Collect every value a `token` marker covers, walking a recorded JSON value against the schema that declares it."""
    schema, spec = contract().resolve(schema, spec)
    if schema.get(validate_field_visibility.MARKER) == TOKEN_CLASS:
        if isinstance(value, str) and value:
            add(value, kind)
        return
    for key in ("allOf", "oneOf", "anyOf"):
        for alternative in schema.get(key) or []:
            walk_tokens(value, alternative, spec, kind, add)
    if isinstance(value, dict):
        properties = schema.get("properties") or {}
        for name, entry in value.items():
            if name in properties:
                walk_tokens(entry, properties[name], spec, name, add)
    elif isinstance(value, list) and isinstance(schema.get("items"), dict):
        for entry in value:
            walk_tokens(entry, schema["items"], spec, kind, add)


def json_body(text) -> object:
    try:
        return json.loads(text) if text else None
    except (json.JSONDecodeError, TypeError):
        return None


def origin_of(url: str) -> str:
    parts = urllib.parse.urlsplit(url)
    return f"{parts.scheme}://{parts.netloc}"


# The calls the token derivation reads: the backend's two listeners. The store and Mailpit are not the contract's.
BACKEND_ORIGINS = (BASE, ADMIN_BASE)


def token_needles(record_file: Path) -> tuple[dict[str, Token], list[str], set[str]]:
    """Every capability token one suite's recording shows it handled, derived by marker. Returns (tokens, problems, mailed).

    **What it derives** (cc.security.wire-assertion § Capability tokens). Every backend call of the recording is
    matched to its contract operation; its request and response JSON bodies are walked against the declared schemas
    and every `token`-classed value is taken ([walk_tokens]); so is every request header the operation declares
    `token` (`X-Contributor-Token`, `X-Share-Password`), every response header it declares `token` — a `Set-Cookie`
    contributes its non-empty cookie values ([set_cookie_values]) — and the bearer of `Authorization`. The kind is the
    name of the marked thing. Needles are derived, never listed (ADR 0053 §2): a name list would forget the 20th.

    **`problems`** names every backend call no operation matches — its tokens would be invisible, so the caller
    fails on it. **`mailed`** is the tokens the suite first read out of a mail (the earliest recorded call carrying
    it is a Mailpit read): mail is their channel (ADR 0049/0054), and the mailbox sweep exempts exactly those.
    """
    entries = [json.loads(line) for line in record_file.read_text().split("\n") if line.strip()]
    tokens: dict[str, Token] = {}
    problems: list[str] = []

    def add(value: str, kind: str, weak: bool = False) -> None:
        if value not in tokens or (tokens[value].weak and not weak):
            needle = token_needle(value)
            tokens[value] = Token(kind, needle, weak, needle != value)

    for entry in entries:
        if origin_of(entry["url"]) not in BACKEND_ORIGINS:
            continue
        path = urllib.parse.urlsplit(entry["url"]).path
        op = contract().match(entry["method"], path)
        if op is None:
            problems.append(f"{entry['method']} {path} matches no operation in contracts/api — its tokens are unseen")
            continue
        request_headers = entry.get("requestHeaders") or {}
        if contract().bearer:
            authorization = header_value(request_headers, "Authorization") or ""
            if authorization.lower().startswith("bearer ") and authorization[7:].strip():
                add(authorization[7:].strip(), BEARER_KIND, weak=True)
        for parameter in op.parameters:
            parameter, _ = contract().resolve(parameter, op.spec)
            if parameter.get("in") == "header" and parameter.get(validate_field_visibility.MARKER) == TOKEN_CLASS:
                value = header_value(request_headers, parameter["name"])
                if isinstance(value, str) and value:
                    add(value, parameter["name"])
        body, spec = contract().resolve(op.operation.get("requestBody") or {}, op.spec)
        schema = ((body.get("content") or {}).get("application/json") or {}).get("schema")
        if schema is not None:
            walk_tokens(json_body(entry.get("requestBody")), schema, spec, "requestBody", add)
        responses = op.operation.get("responses") or {}
        status = entry.get("status")
        response = next((responses[key] for key in (str(status), f"{str(status)[:1]}XX", "default") if key in responses),
                        None)
        if response is None:
            continue
        response, spec = contract().resolve(response, op.spec)
        schema = ((response.get("content") or {}).get("application/json") or {}).get("schema")
        if schema is not None:
            walk_tokens(json_body(entry.get("responseBody")), schema, spec, "responseBody", add)
        for name, header in (response.get("headers") or {}).items():
            header, _ = contract().resolve(header, spec)
            if header.get(validate_field_visibility.MARKER) != TOKEN_CLASS:
                continue
            if name.lower() == "set-cookie":
                for value in set_cookie_values(entry):
                    add(value, name)
            else:
                value = header_value(entry.get("responseHeaders") or {}, name)
                if isinstance(value, str) and value:
                    add(value, name)

    mailed: set[str] = set()
    texts = [json.dumps(entry, ensure_ascii=False) for entry in entries]
    for value, token in tokens.items():
        first = next((i for i, text in enumerate(texts) if token.needle in text), None)
        if first is not None and origin_of(entries[first]["url"]) == MAILPIT:
            mailed.add(value)
    return tokens, problems, mailed


def kinds_of(tokens: Iterable[Token]) -> str:
    """`accessToken ×3, Set-Cookie ×2` — the printed count by kind a reader compares across runs."""
    counts: dict[str, int] = {}
    for token in tokens:
        counts[token.kind] = counts.get(token.kind, 0) + 1
    return ", ".join(f"{kind} ×{n}" for kind, n in sorted(counts.items())) or "none"


class RunTokens(NamedTuple):
    """The UNION of a run's token sets, for the run-level surfaces — by needle, with the kind and the recordings."""
    by_needle: dict[str, Token]
    carriers: dict[str, list[str]]
    mailed: set[str]  # needles of tokens some suite first read out of a mail


def run_tokens(workdir: Path) -> RunTokens:
    """Every recording of the run, derived ([token_needles]) and merged — a recording is found by a recursive glob."""
    by_needle: dict[str, Token] = {}
    carriers: dict[str, list[str]] = {}
    mailed: set[str] = set()
    for record in sorted(workdir.rglob("http-*.jsonl")):
        tokens, _, first_mailed = token_needles(record)
        for value, token in tokens.items():
            if token.needle not in by_needle or (by_needle[token.needle].weak and not token.weak):
                by_needle[token.needle] = token
            carriers.setdefault(token.needle, []).append(f"token:{token.kind} from {record.name}")
            if value in first_mailed:
                mailed.add(token.needle)
    return RunTokens(by_needle, carriers, mailed)


NO_TOKENS = RunTokens({}, {}, set())


def with_tokens(carriers: dict[str, list[str]], tokens: RunTokens) -> dict[str, list[str]]:
    """One needle → carriers map for a run-level surface: the canaries and the tokens, each labelled by its source."""
    merged = {needle: list(labels) for needle, labels in carriers.items()}
    for needle, labels in tokens.carriers.items():
        merged.setdefault(needle, []).extend(labels)
    return merged


def db_token_findings(dump: str, tokens: dict[str, Token]) -> tuple[list[tuple[str, str]], list[Token]]:
    """Over a dump's data rows: every (token, table) a raw token lands on, and the tokens whose HASH the dump holds.

    The hash is the control ([TOKEN_HASH]): ADR 0004's "a database dump yields no usable session" means the server
    keeps the hash where it would otherwise keep the token, so finding hashes proves this dump is the one the
    tokens' rows were written to — and that it was read at all. A count of zero raw hits proves neither.
    """
    needles = [(value, dump_escape(token.needle)) for value, token in tokens.items()]
    hashes = {TOKEN_HASH(value.encode("utf-8")).hexdigest(): value for value in tokens}
    hits: list[tuple[str, str]] = []
    seen: set[tuple[str, str]] = set()
    hashed: set[str] = set()
    for line in dump.splitlines():
        match = INSERT_LINE.match(line)
        if not match:
            continue
        table = match.group(1)
        for value, needle in needles:
            if needle in line and (value, table) not in seen:
                seen.add((value, table))
                hits.append((value, table))
        for digest, value in hashes.items():
            if value not in hashed and digest in line:
                hashed.add(value)
    return hits, [tokens[value] for value in tokens if value in hashed]


# ----------------------------------------------------------------------------------------------------------
# The log surface — what an operator of this stack reads without opening anything
# ----------------------------------------------------------------------------------------------------------

# The two encoders a URL in this stack is built with, as `quote` safe-sets. Every URL a suite sends is built by the
# JavaScript client: `encodeURIComponent` leaves `A-Za-z0-9` and `-_.!~*'()` alone, and the form serializer behind
# `URLSearchParams` leaves only `A-Za-z0-9` and `*-._`, writing a space as `+`. Both write upper-case hex, as
# `quote` does. A store logs the URI as it was SENT (Garage: `<addr> <method> <uri>`, measured on a refused request),
# so a canary crossing a URL is found only in the spelling its encoder chose — which is what these model.
URI_COMPONENT_SAFE = "-_.!~*'()"
FORM_SAFE = "-_.*"


def canary_needles(canary: str) -> list[tuple[str, str]]:
    """Every spelling in which a canary can reach a text surface, as `(variant, needle)`, de-duplicated.

    **One definition of "how a canary can appear", shared by every text surface** — the boot logs, the container
    logs and the HTTP recording. The database keeps [dump_escape] (it models the dump, not a transport) and the
    store keeps raw bytes; everything that is TEXT someone else encoded uses this.

    - `raw` — written as-is (a log line interpolating a value, a body).
    - `json` / `json-ascii` — inside a JSON string, in both of Python's `ensure_ascii` spellings: the escaped
      quote/backslash/control characters, with non-ASCII left as UTF-8 or written as lower-case `\\uXXXX`.
    - `pct` / `pct-plus` — inside a URL, a space as `%20` (`encodeURIComponent`) or as `+` (a form).

    **No base64 variant**: base64 of a plaintext reaches the server only when a client sends an unsealed value in
    a base64 field, which is the HTTP opacity oracle's case ([opacity_failure]); the server never base64-encodes
    plaintext it does not have. A variant equal to an earlier one is dropped — an ASCII canary with no space or
    quote yields `raw` alone, which is what lets a token-shaped value reuse this unchanged.
    """
    forms = (
        ("raw", canary),
        ("json", json.dumps(canary, ensure_ascii=False)[1:-1]),
        ("json-ascii", json.dumps(canary)[1:-1]),
        ("pct", urllib.parse.quote(canary, safe=URI_COMPONENT_SAFE)),
        ("pct-plus", urllib.parse.quote_plus(canary, safe=FORM_SAFE)),
    )
    needles: list[tuple[str, str]] = []
    for variant, needle in forms:
        if needle and all(needle != seen for _, seen in needles):
            needles.append((variant, needle))
    return needles


# How MariaDB Connector/J writes a string parameter into the statement text it sends — it prepares client-side by
# default, so a bound value reaches the server (and its statement log) as a quoted SQL literal. MEASURED from the
# driver the backend ships (3.5.8, `org.mariadb.jdbc.client.socket.Writer.writeStringEscaped`, `javap -c`): with
# backslash escapes on — the server's `sql_mode` carries no `NO_BACKSLASH_ESCAPES` — exactly these four characters
# get a backslash in front, NUL included as the raw byte; a newline, a tab and every non-ASCII character go raw.
# Backslash first, for the reason [DUMP_ESCAPES] gives.
SQL_LITERAL_ESCAPES = [("\\", "\\\\"), ("'", "\\'"), ('"', '\\"'), ("\0", "\\\0")]


def sql_literal(value: str) -> str:
    """Render a value the way the driver writes it inside a string literal (the body, without the quotes)."""
    for raw, escaped in SQL_LITERAL_ESCAPES:
        value = value.replace(raw, escaped)
    return value


def statement_needles(canary: str) -> list[tuple[str, str]]:
    """The statement log's needles: every text-surface spelling ([canary_needles]) plus the driver's literal (`sql-literal`).

    The `sql` form is a needle of its own rather than an un-escaping of the log, for [dump_escape]'s reason: escaping
    the needle is total and one-directional, un-escaping a haystack means knowing which backslashes are whose.
    """
    needles = canary_needles(canary)
    escaped = sql_literal(canary)
    if all(escaped != seen for _, seen in needles):
        needles.append(("sql-literal", escaped))
    return needles


class Hit(NamedTuple):
    """One canary found on one line of one log — everything a reader needs to act on it, from the job log alone."""
    source: str
    line_no: int
    variant: str
    canary: str
    line: str


def occurrences(text: str, needle: str) -> Iterator[int]:
    """Every offset of `needle` in `text`: a plain, case-sensitive substring — the one matcher of the log surface.

    Over the WHOLE text rather than per line, so a needle holding a newline is still found; its line is computed
    from the offset. No word boundary and no length floor: the canary set holds envelope vocabulary (`auto`,
    `range`) that a boundary rule would be the obvious guard against, and a threshold added against false positives
    is where the false negatives hide (changes/README). A collision is explained, never excluded.
    """
    at = text.find(needle)
    while at != -1:
        yield at
        at = text.find(needle, at + 1)


def needle_hits(source: str, text: str, canaries: Iterable[str],
                needles_of: Callable[[str], list[tuple[str, str]]] = canary_needles) -> list[Hit]:
    """Every (line, canary) of `text` some variant of a canary lands on, first variant winning; in line order."""
    starts = [0] + [m.end() for m in re.finditer("\n", text)]
    hits: dict[tuple[int, str], Hit] = {}
    for canary in canaries:
        for variant, needle in needles_of(canary):
            for at in occurrences(text, needle):
                line_no = bisect.bisect_right(starts, at)
                if (line_no, canary) in hits:
                    continue
                begin = starts[line_no - 1]
                end = text.find("\n", begin)
                hits[(line_no, canary)] = Hit(source, line_no, variant, canary,
                                              text[begin:end if end != -1 else len(text)])
    return sorted(hits.values(), key=lambda h: (h.line_no, h.canary))


ANSI = re.compile(r"\x1b\[[0-9;]*m")


def report_hits(hits: list[Hit], carriers: dict[str, list[str]]) -> None:
    """Print each hit in full. CI uploads nothing, so the job log is the only evidence of a hit that survives."""
    for hit in hits:
        shown_line = ANSI.sub("", hit.line).strip()
        labels = carriers.get(hit.canary) or ["?"]
        # A token leads with its kind, so a reader never takes a leaked capability for a leaked canary.
        what = f"{labels[0].split(' from ')[0]} " if labels[0].startswith("token:") else ""
        log(f"    {hit.source}:{hit.line_no}  [{hit.variant}]  {what}{hit.canary!r}  "
            f"(carried by {', '.join(labels)})")
        log(f"        | {shown_line[:240]}")


def load_canaries(canary_files: Iterable[Path]) -> tuple[dict[str, list[str]], list[str]]:
    """The UNION of a run's canary files — canary → the files that carry it — and why any file could not be read.

    A union, because the run-level surfaces have no per-suite owner: a restored instance's boot log owes the
    previous member's canaries, and every container is shared by every suite. Attribution is per canary instead.
    """
    carriers: dict[str, list[str]] = {}
    problems: list[str] = []
    for path in sorted(canary_files):
        try:
            values = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as e:
            problems.append(f"{path.name} is unreadable ({e}) — its canaries are not swept for")
            continue
        for canary in values:
            carriers.setdefault(canary, []).append(path.name)
    return carriers, problems


# `backend-<n>.log` — a group's first member; `backend-<n>-<p>.log` — a later member, after the hook restored;
# `backend-install-<n>.log` — the restore target's install boot; `backend-store-init.log` — the provisioning boot.
BOOT_LOG = re.compile(r"^backend-(?:(?P<suite>\d+)(?:-(?P<member>\d+))?|install-(?P<install>\d+)|store-init)\.log$")

# Quarkus' own shutdown line (`veyq-backend stopped in 0.018s`). A framework string, anchored on knowingly: an
# upgrade that rewords it fails this control LOUD, which is the direction this sensor fails in.
SHUTDOWN_NEEDLE = "stopped in"


def booted_database(log_path: Path) -> str | None:
    """Which database the boot that wrote this log was pointed at — derived from its name, never listed."""
    match = BOOT_LOG.match(log_path.name)
    if not match:
        return None
    if match.group("install"):
        return restore_database(int(match.group("install")))
    if match.group("member"):
        return restore_database(int(match.group("suite")))
    if match.group("suite"):
        return suite_database(int(match.group("suite")))
    return STORE_INIT_DATABASE


def names_database(text: str, database: str) -> bool:
    """The armed-sensor control: the JDBC URL Flyway prints at boot (`Database: jdbc:mariadb://…/veyq_e2e_1?…`).

    Matched as `/<name>` followed by a non-identifier character — so `veyq_e2e_1` is not satisfied by a log that
    only names `veyq_e2e_10`, and the schema-qualified lines (`` `veyq_e2e_1` ``) that a restored member also
    prints cannot stand in for the URL. Same matcher as the canaries ([occurrences]).
    """
    needle = f"/{database}"
    for at in occurrences(text, needle):
        after = text[at + len(needle):at + len(needle) + 1]
        if not (after.isalnum() or after == "_"):
            return True
    return False


def read_log(path: Path) -> tuple[str, str | None]:
    """The log as UTF-8 — the encoding [boot] pins — and, if it is not, a reason: a broken pin is never silent."""
    raw = path.read_bytes()
    try:
        return raw.decode("utf-8"), None
    except UnicodeDecodeError as e:
        return raw.decode("utf-8", errors="replace"), f"{shown(path)} is not UTF-8 ({e}) — the encoding pin did not hold"


def sweep_logs(workdir: Path, canary_files: Iterable[Path], tokens: RunTokens = NO_TOKENS) -> str | None:
    """Prove no canary of the run is readable in any boot log the run retains. Returns None, else why not.

    **What it asserts** (cc.security.wire-assertion, ADR 0053). Run-level, after every group: every
    `backend-*.log` under the run directory (derived by a recursive glob, never listed) is searched for every
    canary of the run's canary files (their union), in every spelling [canary_needles] models. And each log
    must PROVE the sensor was armed, by three positive controls on the same matcher: it is non-empty, it names the
    database its boot was pointed at ([booted_database], [names_database]), and it carries Quarkus' shutdown line
    — so it was read complete, after its JVM exited, which a per-suite sweep inside `run_member` could not be.
    A count of zero hits is never read as success on its own; zero logs is a failure.

    **The defect that bought it.** No incident: until this ran, the log was the one surface ADR 0053 names that
    nothing read. A log is written server-side, where a value is DECODED (a percent-encoded path segment, a
    JSON-escaped string) and then written raw — so a leak there crosses no sensor the other three own.

    **Its limit.** A log records only what the process chose to write, at the level it shipped with (INFO); no
    veyq statement echoes request input today, so a green here is mostly the proof that none started to. The
    attribution of a hit is per canary file, not per log: a restored instance's log owes the previous member's
    values too. And the encoding pin is the e2e boot's — an operator's own locale is theirs.

    The run's capability tokens ([run_tokens]) are searched alongside the canaries, each hit led by `token:<kind>`.
    """
    carriers, problems = load_canaries(canary_files)
    needles = with_tokens(carriers, tokens)
    logs = sorted(workdir.rglob("backend-*.log"))
    if not logs:
        return (f"no boot log under {shown(workdir)} — the log sweep would have proved the absence of nothing "
                f"(0 logs, 0 hits is not a pass)")
    if not carriers:
        return "the run's canary files hold no canary — a green log sweep would mean nothing"

    hits: list[Hit] = []
    unmet: list[str] = list(problems)
    for path in logs:
        text, not_utf8 = read_log(path)
        if not_utf8:
            unmet.append(not_utf8)
        database = booted_database(path)
        controls = []
        if not text:
            unmet.append(f"{shown(path)} is EMPTY — nothing was written, so nothing could be found")
        else:
            controls.append("non-empty")
        if database is None:
            unmet.append(f"{shown(path)}: no rule derives which database this boot log belongs to — a new boot "
                         f"log name needs its rule in BOOT_LOG")
        elif names_database(text, database):
            controls.append(f"database {database}")
        else:
            unmet.append(f"{shown(path)} never names its database {database} — not the log of the boot it is "
                         f"swept as, or not the whole of it")
        if any(True for _ in occurrences(text, SHUTDOWN_NEEDLE)):
            controls.append(f"shutdown line {SHUTDOWN_NEEDLE!r}")
        else:
            unmet.append(f"{shown(path)} has no shutdown line ({SHUTDOWN_NEEDLE!r}) — it was read before its JVM "
                         f"stopped, or the JVM was killed, so it may be incomplete")
        found = needle_hits(shown(path).as_posix(), text, needles)
        hits.extend(found)
        log(f"log sweep: {shown(path)} — {text.count(chr(10))} line(s), {len(carriers)} canary/canaries, "
            f"{len(tokens.by_needle)} token(s), {len(found)} hit(s); armed: {', '.join(controls) or 'NOTHING'}")

    if hits or unmet:
        log(f"WIRE ASSERTION FAILED on the server log — {len(hits)} hit(s), {len(unmet)} unmet control(s):")
        report_hits(hits, needles)
        for reason in unmet:
            log(f"    control: {reason}")
        return (f"the wire assertion failed on the server log: {len(hits)} canary or token hit(s), {len(unmet)} unmet "
                f"control(s) across {len(logs)} boot log(s) (listed above)")
    log(f"wire assertion: {len(carriers)} canary/canaries and {len(tokens.by_needle)} capability token(s) "
        f"({kinds_of(tokens.by_needle.values())}) of the run appear in none of {len(logs)} boot log(s), each armed")
    return None


def plant_store_sentinel(stamp: str) -> str:
    """Send the store one unsigned request for a run-unique key; its log must then carry it, percent-encoded.

    The container sweep's positive control ([sweep_containers]): a sensor that cannot find the value it was handed
    cannot be trusted to find one it was not. The sentinel holds a space and a non-ASCII character so the store
    logs it ENCODED — measured on Garage v2.0.0, which logs `<addr> <method> <uri>` at INFO for a request it then
    refuses (`403 AccessDenied`, anonymous access unsupported) — and the sweep can only find it through the `pct`
    needle. Unsigned on purpose: refused, it reads and writes nothing. Planted at the START of the run, so finding
    it also proves the log window reaches back to where the run began.
    """
    sentinel = f"veyq wire-log sentinel {stamp} Größe"
    url = f"{STORE_S3}/{STORE_BUCKET}/{urllib.parse.quote(sentinel, safe=URI_COMPONENT_SAFE)}"
    try:
        with urllib.request.urlopen(url, timeout=5) as r:
            status = r.status
    except urllib.error.HTTPError as e:
        status = e.code
    except (urllib.error.URLError, OSError) as e:
        raise SystemExit(f"[e2e] STORE UNREACHABLE at {STORE_S3}: the log sweep's control could not be planted "
                         f"({e}) — and every presigned URL of the run goes to the same address") from e
    log(f"planted the store-log sentinel {sentinel!r}: one unsigned GET, answered {status}")
    return sentinel


def compose_services() -> list[str]:
    """Every service of the compose project the run brought up — derived from Compose, never listed."""
    proc = subprocess.run(COMPOSE + ["ps", "--all", "--services"], capture_output=True, text=True)
    if proc.returncode != 0:
        raise SweepError(f"`docker compose ps` failed ({proc.returncode}): {proc.stderr.strip()[-300:]}")
    services = sorted(line.strip() for line in proc.stdout.splitlines() if line.strip())
    if not services:
        raise SweepError("`docker compose ps` reported no service — there is no container log to sweep")
    return services


def container_log(service: str, since: str) -> str:
    """One service's log from `since` (the run's start, a unix time) to now. A non-zero exit is an error, never ''."""
    proc = subprocess.run(
        COMPOSE + ["logs", "--no-color", "--no-log-prefix", "--timestamps", "--since", since, service],
        capture_output=True,
    )
    if proc.returncode != 0:
        raise SweepError(f"`docker compose logs {service}` failed ({proc.returncode}): "
                         f"{proc.stderr.decode('utf-8', errors='replace').strip()[-300:]}")
    return proc.stdout.decode("utf-8", errors="replace")


def sweep_containers(workdir: Path, canary_files: Iterable[Path], since: str, sentinel: str,
                     tokens: RunTokens = NO_TOKENS) -> str | None:
    """Prove no canary of the run is readable in any container log of the stack, over the run's window.

    **What it asserts.** Every service `docker compose ps` reports (MariaDB, Garage, Traefik, Mailpit today) has its
    log read from the run's start to now, retained as `container-<service>.log`, and searched for the union of the
    run's canaries through [canary_needles]. The store is the container that matters — Garage logs every request
    URI, query string included, so its log is the only surviving record of an object key whose object was deleted
    or never completed — and it is armed by the sentinel [plant_store_sentinel] sent, which must be found through
    the `pct` needle.

    **The defect that bought it.** No incident; the same gap as the boot log's — nothing read these logs at all.

    **Its limit.** Only the store carries a control. Traefik carries no e2e traffic (the suites call the backend
    and the store directly) and has no access log; MariaDB writes its general log to a file ([sweep_statements])
    and Mailpit logs deliveries, not bodies ([sweep_mailbox]) — those three are swept because the rule that includes them is derived, not because anything proves
    they could show a leak. The logs are shared by every suite of the run and by a developer's own traffic in the
    same window, so a hit's suite is inferred from the canary, not from the log.

    **Tokens, with one exemption.** The run's capability tokens are searched too ([run_tokens]) — except a presigned
    URL in the STORE's own log: the store is that URL's verifier and logs the request target it was handed, and the
    capability expires with the presign TTL. Such hits are printed as exempted, never dropped; the same URL is a hit
    in every other container's log.
    """
    carriers, problems = load_canaries(canary_files)
    if not carriers:
        return "the run's canary files hold no canary — a green container-log sweep would mean nothing"
    needles = with_tokens(carriers, tokens)
    exempted: list[Hit] = []
    try:
        services = compose_services()
    except SweepError as e:
        return str(e)

    hits: list[Hit] = []
    unmet: list[str] = list(problems)
    if STORE_SERVICE not in services:
        unmet.append(f"the store's service `{STORE_SERVICE}` is not among {services} — its control cannot be met")
    for service in services:
        try:
            text = container_log(service, since)
        except SweepError as e:
            unmet.append(str(e))
            continue
        (workdir / f"container-{service}.log").write_text(text, encoding="utf-8")
        found = needle_hits(f"container {service}", text, needles)
        if service == STORE_SERVICE:
            verified = [h for h in found if h.canary in tokens.by_needle and tokens.by_needle[h.canary].url]
            exempted.extend(verified)
            found = [h for h in found if h not in verified]
        hits.extend(found)
        armed = "no control (see the concern's § What a green sweep does not prove)"
        if service == STORE_SERVICE:
            planted = needle_hits(f"container {service}", text, [sentinel])
            if not planted:
                unmet.append(f"the planted sentinel {sentinel!r} is NOT in the `{service}` log — the window, the "
                             f"log level or the matcher is wrong, and this sweep proves nothing about the store")
                armed = "planted sentinel NOT found"
            elif planted[0].variant != "pct":
                unmet.append(f"the planted sentinel was found through `{planted[0].variant}`, not `pct` — the store "
                             f"no longer logs the URI as sent, so the percent-encoded needle is unproven this run")
                armed = f"planted sentinel found only through {planted[0].variant}"
            else:
                armed = f"planted sentinel found through pct (line {planted[0].line_no})"
        log(f"container log sweep: {service} — {text.count(chr(10))} line(s) since {since}, {len(found)} hit(s); "
            f"{armed}")

    if hits or unmet:
        log(f"WIRE ASSERTION FAILED on the container logs — {len(hits)} hit(s), {len(unmet)} unmet control(s):")
        report_hits(hits, needles)
        for reason in unmet:
            log(f"    control: {reason}")
        return (f"the wire assertion failed on the container logs: {len(hits)} canary or token hit(s), {len(unmet)} unmet "
                f"control(s) across {len(services)} service(s) (listed above)")
    exempt_urls = {h.canary for h in exempted}
    log(f"container log sweep: {len(exempt_urls)} presigned-URL token(s) found in the `{STORE_SERVICE}` log, its own "
        f"verifier, and exempted there ({len(exempted)} line(s)) — swept on every other surface")
    log(f"wire assertion: {len(carriers)} canary/canaries and {len(tokens.by_needle)} capability token(s) of the run "
        f"appear in none of {len(services)} container log(s); the store's armed by its planted sentinel")
    return None


# ----------------------------------------------------------------------------------------------------------
# The statement log — every SQL statement the database received during the run, not only what it ended with
# ----------------------------------------------------------------------------------------------------------

# MariaDB's general query log is written INSIDE the container, under `/tmp` — outside the data volume
# (`/var/lib/mysql`), so it never grows the developer's data and is gone with the container. The run's own name
# is in it, which is what lets a later run recognise a log a killed run left switched on ([StatementLog.open]).
STATEMENT_LOG_PREFIX = "/tmp/veyq-e2e-"


class StatementLog:
    """MariaDB's general query log, on for EXACTLY the run's window, restored on every exit path Python can see.

    `general_log`, `general_log_file` and `log_output` are dynamic globals (measured on mariadb:11.4: set at runtime,
    no restart, nothing in either compose file or the chart), so the window is three `SET GLOBAL`s at its start and
    their inverse at its end. The container is the developer's own `veyq-mariadb`, so the log also records whatever
    else reaches it in the window — their IntelliJ backend, a database tool — and two rules contain that:
      - **[close] runs from a `finally`** (and SIGTERM/SIGHUP are turned into an exit that runs it), switching the
        log off, copying the file into the run's own directory and DELETING it in the container. Only a SIGKILL
        skips it; a container restart resets the globals to off on its own.
      - **[open] heals a killed run's leftovers**: a log found ON writing one of this harness's files is switched
        off, the file deleted, and OFF taken as the state to restore — never restored back to ON, which would keep
        recording the developer's statements into a file nobody reads.

    The retained copy (`mariadb-general.log`) is the developer's data on their disk, in a gitignored directory —
    stated in the concern, not filtered: a filter by connection would be a second parser to be wrong in.
    """

    def __init__(self, stamp: str, workdir: Path) -> None:
        self.stamp = stamp
        self.path = f"{STATEMENT_LOG_PREFIX}{stamp}.log"
        self.retained = workdir / "mariadb-general.log"
        self.prior: list[str] | None = None
        self.closed = False
        # What [close] could not do, reported by the sweep as an unmet control rather than raised mid-cleanup.
        self.problems: list[str] = []

    def open(self) -> None:
        general_log, log_file, log_output = mysql_row(
            "SELECT @@global.general_log, @@global.general_log_file, @@global.log_output")
        self.prior = [general_log, log_file, log_output]
        if general_log == "1" and log_file.startswith(STATEMENT_LOG_PREFIX):
            log(f"found the statement log ALREADY ON, writing {log_file} — a run was killed before it could switch "
                f"it off. Switching it off, deleting that file, and restoring OFF at the end of this run")
            mysql("SET GLOBAL general_log = OFF; SET GLOBAL general_log_file = DEFAULT")
            self.prior = ["0"] + mysql_row("SELECT @@global.general_log_file, @@global.log_output")
        elif general_log == "1":
            log(f"found the statement log already ON, writing {log_file} — not this harness's file; it is restored "
                f"to exactly that at the end of the run")
        # A file a killed run left behind after switching off holds the same developer statements: delete it too.
        subprocess.run(COMPOSE + ["exec", "-T", "mariadb", "sh", "-c", f"rm -f {STATEMENT_LOG_PREFIX}*.log"],
                       capture_output=True)
        mysql(f"SET GLOBAL log_output = 'FILE'; SET GLOBAL general_log_file = '{self.path}'; "
              f"SET GLOBAL general_log = ON")
        log(f"statement log ON for the run's window: {self.path} in the mariadb container "
            f"(prior: general_log={self.prior[0]}, file={self.prior[1]}, output={self.prior[2]})")

    def plant(self, where: str) -> str:
        """One statement of the orchestrator's own, carrying a run-unique sentinel — [sweep_statements]' control.

        The sentinel holds an apostrophe, written the way the driver writes one (`\\'`), so it can only be found
        through the `sql-literal` needle: a sentinel any spelling found would arm nothing in particular.
        """
        sentinel = f"veyq statement-log sentinel {self.stamp} {where}: it's Größe"
        mysql(f"DO '{sql_literal(sentinel)}'")
        log(f"planted the statement-log sentinel {sentinel!r}")
        return sentinel

    def close(self) -> None:
        """Switch the log off and restore the prior globals FIRST, then retain the file and delete it. Idempotent."""
        if self.closed or self.prior is None:
            return
        self.closed = True
        general_log, log_file, log_output = self.prior
        try:
            mysql(f"SET GLOBAL general_log = OFF; SET GLOBAL general_log_file = '{log_file}'; "
                  f"SET GLOBAL log_output = '{log_output}'; SET GLOBAL general_log = {'ON' if general_log == '1' else 'OFF'}")
            log(f"statement log restored: general_log={general_log}, file={log_file}, output={log_output}")
        except subprocess.CalledProcessError as e:
            self.problems.append(f"restoring the statement-log globals failed ({e}) — check "
                                 f"`SELECT @@global.general_log` on the mariadb container by hand")
            log(f"RESTORING THE STATEMENT LOG FAILED: {e}")
        copied = subprocess.run(COMPOSE + ["exec", "-T", "mariadb", "cat", self.path], capture_output=True)
        if copied.returncode == 0:
            self.retained.write_bytes(copied.stdout)
            log(f"statement log retained as {shown(self.retained)} ({len(copied.stdout)} bytes)")
        else:
            self.problems.append(f"the container holds no statement log at {self.path} — it was never switched on, "
                                 f"or something removed it")
        removed = subprocess.run(COMPOSE + ["exec", "-T", "mariadb", "rm", "-f", self.path], capture_output=True)
        if removed.returncode != 0:
            self.problems.append(f"deleting {self.path} in the mariadb container failed: "
                                 f"{removed.stderr.decode('utf-8', errors='replace').strip()[-200:]}")


# One entry of the general log: `<YYMMDD HH:MM:SS>` on the first entry of a second, else empty, then a tab, the
# connection id right-aligned, the command, a tab, and the argument — which runs on, raw newlines and all, until the
# next entry's header. MEASURED on mariadb:11.4 (the phase that built this pasted the lines into the concern).
STATEMENT_HEADER = re.compile(r"^(?:\d{6} [ \d]\d:\d\d:\d\d)?\t+ *(?P<id>\d+) (?P<command>[A-Z][A-Za-z_ ]*?)\t", re.M)
# `<user>@<host> on <schema> using <transport>` — the schema is empty for a session that names none.
CONNECT_ARGUMENT = re.compile(r"\S+ on (?P<schema>\S*) using ")

# The tokens of MariaDB's lexer that can contain a quote without opening a string: a literal of either quote (no
# `ANSI_QUOTES`, so `"` delimits a string too), a back-ticked identifier, and the three comment forms. A literal or
# comment left open runs to the end of its entry — never past it, so a malformed statement cannot desynchronise
# the rest of the file.
SQL_TOKEN = re.compile(r"""
      '(?P<single>(?:[^'\\]+|\\.|'')*)(?:'|\Z)
    | "(?P<double>(?:[^"\\]+|\\.|"")*)(?:"|\Z)
    | `[^`]*(?:`|\Z)
    | /\*.*?(?:\*/|\Z)
    | (?:\#|--(?=[ \t\r\n]|\Z))[^\n]*
""", re.S | re.X)

# Joins the literals into one haystack. A needle can only straddle two literals if it contains this character.
LITERAL_JOIN = "\x00"


def literal_spans(text: str) -> list[tuple[int, int]]:
    """Every string literal's body in a general log, as `(start, end)` offsets into the whole text.

    **Why only literals** — the statement log's answer to [INSERT_LINE]. A value the backend sends the database
    travels as a literal (the driver prepares client-side, [SQL_LITERAL_ESCAPES]); everything outside one is SQL
    the code wrote: keywords, table and column names, the driver's own session statements, and every comment of
    every Flyway migration. Searched whole, that is noise a canary cannot avoid — measured: `set autocommit=0`
    precedes every transaction and the run's canaries include `auto`, and the migrations' comments carry `range`
    and `auto`. Scoping to literals removes the noise class without excluding a single value.
    """
    heads = list(STATEMENT_HEADER.finditer(text))
    spans: list[tuple[int, int]] = []
    for i, head in enumerate(heads):
        end = heads[i + 1].start() if i + 1 < len(heads) else len(text)
        for token in SQL_TOKEN.finditer(text, head.end(), end):
            for group in ("single", "double"):
                if token.group(group) is not None:
                    spans.append(token.span(group))
    return spans


def literal_hits(source: str, text: str, canaries: Iterable[str]) -> tuple[list[Hit], int]:
    """Every (line, canary) of a general log some [statement_needles] spelling lands on INSIDE a literal.

    Returns the hits in line order and how many literals were searched — a count the sweep prints, because a log
    whose literals were never found would otherwise sweep nothing and pass.
    """
    spans = literal_spans(text)
    starts: list[int] = []
    pieces: list[str] = []
    at = 0
    for begin, end in spans:
        starts.append(at)
        pieces.append(text[begin:end])
        at += end - begin + len(LITERAL_JOIN)
    joined = LITERAL_JOIN.join(pieces)
    line_starts = [0] + [m.end() for m in re.finditer("\n", text)]
    hits: dict[tuple[int, str], Hit] = {}
    for canary in canaries:
        for variant, needle in statement_needles(canary):
            for found in occurrences(joined, needle):
                k = bisect.bisect_right(starts, found) - 1
                offset = spans[k][0] + (found - starts[k])
                line_no = bisect.bisect_right(line_starts, offset)
                if (line_no, canary) in hits:
                    continue
                begin = line_starts[line_no - 1]
                end = text.find("\n", begin)
                hits[(line_no, canary)] = Hit(source, line_no, variant, canary,
                                              text[begin:end if end != -1 else len(text)])
    return sorted(hits.values(), key=lambda h: (h.line_no, h.canary)), len(spans)


def connected_schemas(text: str) -> set[str]:
    """Every schema a `Connect` entry of the log names — the backend's pool opens its connections ON its database."""
    schemas: set[str] = set()
    for head in STATEMENT_HEADER.finditer(text):
        if head.group("command") == "Connect":
            argument = CONNECT_ARGUMENT.match(text, head.end())
            if argument and argument.group("schema"):
                schemas.add(argument.group("schema"))
    return schemas


def booted_databases(workdir: Path) -> set[str]:
    """Every database a boot of this run was pointed at — derived from the boot logs the run wrote, never listed."""
    return {db for db in (booted_database(p) for p in workdir.rglob("backend-*.log")) if db}


def sweep_statements(retained: Path, canary_files: Iterable[Path], sentinels: Iterable[str],
                     databases: Iterable[str], problems: Iterable[str] = (),
                     tokens: RunTokens = NO_TOKENS) -> str | None:
    """Prove no canary of the run was ever SENT to the database — every statement of the run, not its end state.

    **What it asserts** (cc.security.wire-assertion, ADR 0053). MariaDB's general log, on for the run's window
    ([StatementLog]), holds every statement the server received — writes, reads, and the writes a later statement
    undid. Each string literal in it ([literal_spans]) is searched for the union of the run's canaries in every
    spelling [statement_needles] models, the driver's own literal escaping included. Armed by two controls on the
    same matcher, never by a count of zero: the run's sentinel statements — planted at the window's start and just
    before it closed — must both be found, through `sql-literal`, so the file is the one being written and nothing
    switched the log off in between; and every database a boot of the run was pointed at must appear in a
    `Connect` entry ([booted_databases], [connected_schemas]), so the BACKEND's traffic was captured, not only the
    orchestrator's own.

    **The defect that bought it.** No incident; the gap the database sweep states about itself — it reads each
    suite's end state, and a plaintext written and then overwritten or deleted inside the suite leaves no trace
    there. Measured before this was built: one run carries 7 node PATCHes, 18 PUTs of upserted state and 2
    DELETEs, before what the server rewrites on its own.

    **Its limit.** It sees statements, so a value the backend computes and never sends to the database is not
    there by construction; it searches literals, so a value the code splices into SQL text outside a quote is
    not searched (nothing does today — every value is a bound parameter). A literal NUL-joined to its neighbour
    can produce a hit only for a canary that itself contains NUL. And the window is shared: it also holds the
    statements of anything else that reached the same MariaDB during the run, a developer's own backend included.

    **Tokens.** The run's capability tokens are searched in the same literals ([run_tokens]). This is the one surface
    that sees a lookup BY the raw token — a query whose `WHERE` carries it — which no end-state dump can show.
    """
    carriers, unreadable = load_canaries(canary_files)
    if not carriers:
        return "the run's canary files hold no canary — a green statement-log sweep would mean nothing"
    needles = with_tokens(carriers, tokens)
    unmet: list[str] = list(unreadable) + list(problems)
    if retained.is_file():
        text = retained.read_bytes().decode("utf-8", errors="replace")
    else:
        text = ""
        unmet.append(f"no statement log was retained at {shown(retained)}")
    source = shown(retained).as_posix()
    hits, literals = literal_hits(source, text, needles)

    controls: list[str] = []
    for sentinel in sentinels:
        planted, _ = literal_hits(source, text, [sentinel])
        if planted:
            controls.append(f"sentinel line {planted[0].line_no} through {planted[0].variant}")
        else:
            # Its apostrophe makes `sql-literal` the only needle that can find it, so this also fails when the
            # server stops writing a literal the way the needles model — `NO_BACKSLASH_ESCAPES` doubles the quote.
            unmet.append(f"the planted sentinel {sentinel!r} is NOT in the statement log — the log was not on, was "
                         f"switched off or moved mid-run, or a literal is no longer escaped the way `sql-literal` "
                         f"models; this sweep proves nothing")
    connected = connected_schemas(text)
    required = sorted(databases)
    for database in required:
        if database not in connected:
            unmet.append(f"no `Connect` entry names {database} — the boot pointed at it ran outside the window, "
                         f"or its connections were not captured")
    if required and all(db in connected for db in required):
        controls.append(f"a Connect for each of {len(required)} database(s)")
    log(f"statement-log sweep: {source} — {text.count(chr(10))} line(s), {len(STATEMENT_HEADER.findall(text))} "
        f"entr(y/ies), {literals} literal(s), {len(carriers)} canary/canaries, {len(tokens.by_needle)} token(s), "
        f"{len(hits)} hit(s); armed: "
        f"{', '.join(controls) or 'NOTHING'}")

    if hits or unmet:
        log(f"WIRE ASSERTION FAILED on the statement log — {len(hits)} hit(s), {len(unmet)} unmet control(s):")
        report_hits(hits, needles)
        for reason in unmet:
            log(f"    control: {reason}")
        return (f"the wire assertion failed on the statement log: {len(hits)} canary or token hit(s), {len(unmet)} unmet "
                f"control(s) (listed above)")
    log(f"wire assertion: {len(carriers)} canary/canaries and {len(tokens.by_needle)} capability token(s) "
        f"({kinds_of(tokens.by_needle.values())}) of the run appear in none of {literals} literal(s) the database "
        f"received during the run; armed by both sentinels and every database's Connect")
    return None


# ----------------------------------------------------------------------------------------------------------
# The mailbox — every mail the run sent, the one surface a third party (the tenant's SMTP provider) reads
# ----------------------------------------------------------------------------------------------------------

# The page size the mailbox is read with — explicit, because a default page is exactly the number that truncates a
# read silently.
MAILBOX_PAGE = 100


def plant_mail_sentinel(stamp: str) -> str:
    """Deliver one mail of the orchestrator's own whose subject and body carry a run-unique sentinel.

    [sweep_mailbox]'s positive control. The sentinel holds a space and a non-ASCII character, and travels the way
    the product's own mail does (measured on a recovery mail: Vert.x writes the subject as a `Q` encoded-word and
    the body quoted-printable, soft line breaks included) — so a raw substring search over the message source
    cannot find it, and only the DECODED read can. Delivered at the start, so finding it also proves the read
    reaches back to where the run began.
    """
    sentinel = f"veyq mailbox sentinel {stamp} Größe"
    message = email.message.EmailMessage()
    message["From"] = "e2e-orchestrator@veyq.localhost"
    message["To"] = f"mailbox-sentinel-{stamp}@veyq.localhost"
    message["Subject"] = sentinel
    message.set_content(f"The e2e run's mailbox control.\n{sentinel}\n", cte="quoted-printable")
    try:
        with smtplib.SMTP(*MAILPIT_SMTP, timeout=10) as smtp:
            smtp.send_message(message)
    except (OSError, smtplib.SMTPException) as e:
        raise SystemExit(f"[e2e] MAILBOX SMTP UNREACHABLE at {MAILPIT_SMTP[0]}:{MAILPIT_SMTP[1]}: the mailbox "
                         f"sweep's control could not be planted ({e})") from e
    log(f"planted the mailbox sentinel {sentinel!r}: one mail over SMTP, subject encoded-word, body quoted-printable")
    return sentinel


def mailpit_json(path: str) -> dict:
    with urllib.request.urlopen(f"{MAILPIT}{path}", timeout=10) as r:
        return json.loads(r.read().decode("utf-8"))


def run_mail_ids(since: float) -> list[str]:
    """Every message Mailpit received from `since` (a unix time) on — paged with an explicit size, newest first.

    Mailpit lists newest first, so paging stops at the first message older than the run; a page that comes back
    short is the end of the mailbox.
    """
    ids: list[str] = []
    start = 0
    while True:
        page = mailpit_json(f"/api/v1/messages?start={start}&limit={MAILBOX_PAGE}").get("messages") or []
        for message in page:
            # `Created` is UTC (`2026-10-01T18:09:34.732Z`); seconds are the grain the run's stamp has anyway.
            created = calendar.timegm(time.strptime(message["Created"][:19], "%Y-%m-%dT%H:%M:%S"))
            if created < since:
                return ids
            ids.append(message["ID"])
        if len(page) < MAILBOX_PAGE:
            return ids
        start += MAILBOX_PAGE


def mail_texts(raw: bytes) -> list[tuple[str, str]]:
    """A message's DECODED text, as `(where, text)`: each header, then each text part — what a reader would see.

    Parsed by the standard library (`email`, `policy.default`), so an encoded-word is decoded, a quoted-printable or
    base64 part is decoded, and a soft line break is joined back up; no hand-written MIME parser to be wrong in.
    """
    message = email.message_from_bytes(raw, policy=email.policy.default)
    texts = [(f"header {name}", str(value)) for name, value in message.items()]
    for number, part in enumerate(message.walk(), start=1):
        if part.get_content_maintype() == "text":
            try:
                texts.append((f"part {number} {part.get_content_type()}", part.get_content()))
            except (LookupError, ValueError) as e:
                texts.append((f"part {number} {part.get_content_type()} (undecodable: {e})",
                              part.get_payload(decode=True).decode("utf-8", errors="replace")))
    return texts


def sweep_mailbox(workdir: Path, canary_files: Iterable[Path], since: float, sentinel: str,
                  tokens: RunTokens = NO_TOKENS) -> str | None:
    """Prove no canary of the run is readable in any mail the run sent — read decoded, and raw.

    **What it asserts** (cc.security.wire-assertion, ADR 0053). Every message Mailpit received from the run's start
    ([run_mail_ids]) is fetched raw, retained under `mailbox/`, and searched for the union of the run's canaries
    through [canary_needles] — in each decoded header and text part ([mail_texts]) and in the raw source. Armed by
    a mail the run plants itself ([plant_mail_sentinel]) whose sentinel must be found in the DECODED text; that it
    is absent from the raw source is printed, because that absence is what makes the decoded read load-bearing.
    Nothing in a run deletes mail (the suites take a cursor, `web/src/test/mailpit.ts`), so the read is complete.

    **The defect that bought it.** No incident. Mail is closed by construction — both kinds interpolate only
    `MailContext(link, domain, expiry)` (ADR 0049) — and the HTTP sweep already searches every mail a suite reads
    back. What this closes is the rest: mail no suite reads, and the one surface ADR 0049's boundary has no
    traffic-level sensor for — the one a third party, the tenant's SMTP provider, reads. A future mail kind is
    where a sealed name would slip in.

    **Its limit.** It sees what reached Mailpit, not what a production relay adds or keeps. The mailbox is shared
    with a developer's own mail of the same window, and Mailpit prunes its oldest messages past its cap — a run
    that pushed its own plant out fails on the control, loud. Non-text parts are searched only raw.

    **Tokens, with one exemption.** The run's capability tokens are searched too ([run_tokens]) — except a token the
    recording shows the suite FIRST read out of a mail (`RunTokens.mailed`): mail is that token's channel
    (member invite, recovery; ADR 0049/0054). Those hits are printed as exempted with the tokens they exempted;
    every other token in a mail is a hit.
    """
    carriers, problems = load_canaries(canary_files)
    if not carriers:
        return "the run's canary files hold no canary — a green mailbox sweep would mean nothing"
    needles = with_tokens(carriers, tokens)
    exempted: list[Hit] = []
    unmet: list[str] = list(problems)
    try:
        ids = run_mail_ids(since)
    except (urllib.error.URLError, OSError, KeyError, ValueError) as e:
        return f"the mailbox could not be listed at {MAILPIT} ({e}) — the mailbox sweep proved nothing"
    retained = workdir / "mailbox"
    retained.mkdir(exist_ok=True)
    hits: list[Hit] = []
    sentinel_decoded: list[str] = []
    sentinel_raw = False
    for message_id in ids:
        try:
            with urllib.request.urlopen(f"{MAILPIT}/api/v1/message/{message_id}/raw", timeout=10) as r:
                raw = r.read()
        except (urllib.error.URLError, OSError) as e:
            unmet.append(f"mail {message_id} could not be read raw ({e}) — it was not swept")
            continue
        (retained / f"{message_id}.eml").write_bytes(raw)
        found: list[Hit] = []
        for where, text in mail_texts(raw):
            found.extend(needle_hits(f"mail {message_id} {where}", text, needles))
            if needle_hits(where, text, [sentinel]):
                sentinel_decoded.append(where)
        source = raw.decode("utf-8", errors="replace")
        found.extend(needle_hits(f"mail {message_id} raw", source, needles))
        exempted.extend(h for h in found if h.canary in tokens.mailed)
        hits.extend(h for h in found if h.canary not in tokens.mailed)
        sentinel_raw = sentinel_raw or bool(needle_hits("raw", source, [sentinel]))
    if sentinel_decoded:
        armed = (f"planted sentinel found decoded in {', '.join(sentinel_decoded)}; in the raw source: "
                 f"{'FOUND — the transport did not encode it' if sentinel_raw else 'not found (the decoding is load-bearing)'}")
    else:
        armed = "planted sentinel NOT found"
        unmet.append(f"the planted mail's sentinel {sentinel!r} is NOT in any decoded mail of the run — the read "
                     f"missed it (window, paging, pruning) or the decoding is broken; this sweep proves nothing")
    log(f"mailbox sweep: {len(ids)} mail(s) since {int(since)}, {len(carriers)} canary/canaries, "
        f"{len(tokens.by_needle)} token(s), {len(hits)} hit(s); {armed}")
    exempt = sorted({h.canary for h in exempted})
    log(f"mailbox sweep: {len(exempt)} token(s) exempted — first read out of a mail, which is their channel "
        f"({kinds_of(tokens.by_needle[n] for n in exempt)}; {len(tokens.mailed)} such token(s) in the run):")
    for needle in exempt:
        log(f"    exempted {tokens.by_needle[needle].kind} {needle!r} ({', '.join(tokens.carriers.get(needle, []))})")

    if hits or unmet:
        log(f"WIRE ASSERTION FAILED on the mailbox — {len(hits)} hit(s), {len(unmet)} unmet control(s):")
        report_hits(hits, needles)
        for reason in unmet:
            log(f"    control: {reason}")
        return (f"the wire assertion failed on the mailbox: {len(hits)} canary or token hit(s), {len(unmet)} unmet "
                f"control(s) across {len(ids)} mail(s) (listed above)")
    log(f"wire assertion: {len(carriers)} canary/canaries of the run appear in none of {len(ids)} mail(s), decoded "
        f"or raw; armed by the planted mail")
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
    return subprocess.run(COMPOSE + ["exec", "-T", STORE_SERVICE, "/garage", *args], capture_output=True, text=True)


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
    # `verify` touches no instance, so it takes no network.
    network = [] if args[0] == "verify" else ["--docker-network", DOCKER_NETWORK]
    cmd = [sys.executable, str(REPO / "scripts" / "instance_backup.py"), *args, *network]
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

    **Both groups capture the same way — the key kept apart from the dump, as the runbook says** (ADR 0056 §1),
    and both captures must pass `verify` with no key file in the capture directory. `with_key=False` is the
    leg-3 rehearsal: legs (a) and (b) arrive, leg (c) does not, and the instance that comes up boots, serves
    content, and reports `configSecretState: MISMATCH` while refusing member invites with
    `503 mail_unavailable`. It is reached the way an operator reaches A2 — a restore told `--without-key` — not
    by editing the capture, so the two groups differ in exactly one restore flag.
    """
    backup = group_dir / "backup"
    kept_key = group_dir / "kept-apart" / instance_backup.KEY_NAME
    backup_tool("capture", "--into", str(backup), "--key-into", str(kept_key), "--db-name", source.database,
                "--store-bucket", source.bucket or STORE_BUCKET, "--config-secret-file", str(source.secret_file))
    verify_capture(backup, kept_key)

    destroy(source)

    target = Instance(restore_database(index), group_dir / "restored-config-secret.key",
                      f"veyq-e2e-dst-{index}")
    fresh_database(target.database)
    fresh_bucket(target.bucket)
    install(target, group_dir / f"backend-install-{index}.log")
    restore = ["restore", str(backup), "--domain", HOST, "--db-name", target.database,
               "--store-bucket", target.bucket, "--config-secret-file", str(target.secret_file)]
    assert_unsupplied_key_is_refused(restore, target)
    if with_key:
        backup_tool(*restore, "--key-from", str(kept_key))
    else:
        log("leg-3 rehearsal: restoring knowingly without the separately kept key")
        backup_tool(*restore, "--without-key")
    assert_populated_target_is_refused(restore)
    return target


def verify_capture(backup: Path, kept_key: Path) -> None:
    """Every capture is checked by the tool's own `verify`, and must hold no key file of its own.

    Exit 0 is the whole verdict: a structurally broken capture exits 1 and one missing leg (c) exits 2 — and so
    would one whose dump or directory had loosened to group/world-readable, which is the leak `verify` exists to
    catch now. The `test ! -e` beside it is the property the run cares about stated directly, so a `verify` that
    stopped looking could not pass it by itself.
    """
    backup_tool("verify", str(backup), "--key-from", str(kept_key))
    beside = backup / instance_backup.KEY_NAME
    if beside.exists():
        raise SystemExit(f"[e2e] CAPTURE LEAKS: {shown(beside)} sits beside the dump — the capture was told to keep "
                         f"the key at {shown(kept_key)} (ADR 0056 §1)")


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


def assert_unsupplied_key_is_refused(restore: list[str], target: Instance) -> None:
    """A capture that kept its key apart is not restored without it by accident (ADR 0056 §1, A2).

    The restore `restore_hook` runs next, with nothing but the key flag added, is this negative's positive
    control: same capture, same freshly installed target. It also proves the refusal wrote nothing, because
    the emptiness gate would refuse it otherwise (A5). Asserted on the message naming the key, never on a bare
    non-zero exit — a connection failure exits the same way.
    """
    log("leg (c) control: the restore without the separately kept key — must be refused, naming the key")
    result = backup_tool_result(*restore)
    body = result.stdout + result.stderr
    if result.returncode == 0:
        raise SystemExit(
            "[e2e] KEY GATE OPEN: a capture that kept its config-secret key apart was restored without it and "
            "without --without-key. A2 is reachable only knowingly (ADR 0056 §1)."
        )
    if "--key-from" not in body or "kept its config-secret key apart" not in body:
        raise SystemExit(
            f"[e2e] KEY GATE REFUSED FOR THE WRONG REASON: the restore failed without naming the missing key. "
            f"Output was: {body.strip()[-500:]}"
        )
    if target.secret_file.exists():
        raise SystemExit(f"[e2e] KEY GATE WROTE: the refused restore placed {shown(target.secret_file)}")
    log("leg (c) control: refused at the gate, naming the key it needs")


def assert_populated_target_is_refused(restore: list[str]) -> None:
    """The other half of A5's gate, on the instance the restore just populated.

    A relaxation is only honest if the refusal it relaxed still happens. `restore_hook` above proves the gate
    now ACCEPTS a freshly installed instance; this proves the same gate still REFUSES a populated one — the
    instance one line up, holding real restored rows. Asserted on the specific message, never on "it threw": a
    bare non-zero exit is equally produced by a connection failure, which would turn this into a check that
    passes hardest when the tool is most broken. `--without-key` is passed so the key gate, which runs first,
    cannot be what refuses it.
    """
    log("A5 control: the same restore, again, onto the instance it just populated — must be refused")
    result = backup_tool_result(*restore, "--without-key")
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
    docker network, same credentials — only `--compose-file` differs (and `--into` and `--key-into`, which must,
    because a capture refuses to write into a non-empty directory or over a kept key). The **dev** file must reach the
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
            f"{shown(PROD_COMPOSE_FILE)} does not resolve even with every `${{VAR:?}}` it declares "
            f"supplied ({probe.stderr.strip()[-300:]}). A capture against it would fail here, before Compose "
            f"resolved any project — which is indistinguishable from the verdict this rehearsal reports, and "
            f"would be scored as a pass."
        )

    seed_project_decoy(DECOY_DB)
    fresh_bucket(DECOY_BUCKET)
    shared = ["capture", "--db-name", DECOY_DB, "--store-bucket", DECOY_BUCKET,
              "--config-secret-file", str(key)]

    log(f"control: the same capture through {shown(COMPOSE_FILE)} must reach the decoy")
    control = backup_tool_result(*shared, "--compose-file", str(COMPOSE_FILE), "--into", str(home / "control"),
                                 "--key-into", str(home / "control-kept.key"), env_extra=env_extra)
    if control.returncode != 0:
        raise SystemExit(
            f"[e2e] PRODUCTION-PROJECT REHEARSAL INCONCLUSIVE: the control capture — the same command through "
            f"{shown(COMPOSE_FILE)}, which resolves the project this stack is actually running as — "
            f"failed with exit {control.returncode}. Nothing was reachable, so the subject below could only "
            f"have proven that. Fix the control first; a rehearsal whose control is red reports 'the project is "
            f"correctly not resolved' and 'nothing ran' identically."
        )

    verify_capture(home / "control", home / "control-kept.key")

    log(f"subject: the same capture through {shown(PROD_COMPOSE_FILE)} must NOT reach it")
    subject = backup_tool_result(*shared, "--compose-file", str(PROD_COMPOSE_FILE), "--into",
                                 str(home / "production"), "--key-into", str(home / "production-kept.key"),
                                 env_extra=env_extra)
    manifest = home / "production" / instance_backup.MANIFEST_NAME
    if subject.returncode == 0 or manifest.is_file():
        captured = "(no manifest)"
        if manifest.is_file():
            captured = ", ".join(json.loads(manifest.read_text()).get("domains") or []) or "(no domain)"
        raise SystemExit(
            f"[e2e] PRODUCTION-PROJECT REHEARSAL FAILED: a capture pointed at "
            f"{shown(PROD_COMPOSE_FILE)} exited {subject.returncode} and captured database "
            f"{DECOY_DB}, domains {captured} — an instance living in the DEV project. The production file names "
            f"no project, so Compose derived one from its directory (`{PROD_COMPOSE_FILE.parent.name}`) and "
            f"handed the production procedure whatever happens to run out of it. On a production host that is a "
            f"backup of the wrong instance, taken and reported as a success. Declare the project in the file "
            f"(`name:`) so the procedure cannot be pointed at the wrong stack by standing in the wrong directory."
        )
    log(f"the production capture did not resolve the dev project (exit {subject.returncode}), "
        f"while the control through {shown(COMPOSE_FILE)} reached {DECOY_DB} — the project is a "
        f"property of the file, not of the directory it sits in")


def run_group(index: int, members: list[Path], workdir: Path, stamp: str) -> list[tuple[str, str]]:
    """Run a group's members in order against one instance session. Returns every member's failure, if any.

    A group of ONE takes exactly the old path: one fresh database, no bucket override, no hook.
    """
    name = group_of(members[0])
    inst = Instance(suite_database(index), workdir / f"config-secret-{index}.key",
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
        # The second needle set: every capability token the recording shows the suite handled, derived by the
        # contract's `token` marker. A backend call no operation matches would hide its tokens, so it fails.
        tokens, unmatched, mailed = token_needles(record_file) if record_file.is_file() else ({}, [], set())
        log(f"capability tokens: {len(tokens)} derived from {record_file.name} ({kinds_of(tokens.values())}); "
            f"{len(mailed)} first read out of a mail")
        token_failure = None
        if unmatched:
            for problem in unmatched:
                log(f"    token derivation: {problem}")
            token_failure = f"the token derivation matched {len(unmatched)} backend call(s) to no operation (above)"
        # Three surfaces, three sensors, one canary file (ADR 0053 §3). Run them all and report the FIRST
        # failure rather than stopping at the database: a run that leaks on two surfaces should say so on the
        # second one too, once the first is fixed, and a sweep skipped is a sweep nobody notices is missing.
        for failure in (
            token_failure,
            sweep(database, canary_file, rel, tokens, workdir / f"database-{tag}.sql"),
            sweep_http(record_file, canary_file, rel),
            sweep_store(inst.bucket or STORE_BUCKET, database, canary_file, rel, workdir, tokens),
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
    # up because the run's first backend boot ([provision_store]) writes its log here with the rest.
    workdir = RUN_ROOT / stamp
    workdir.mkdir(parents=True, exist_ok=True)
    log(f"run artifacts (boot logs, canary files) land in {workdir.relative_to(REPO)}/")

    # A `kill` (SIGTERM) or a closed terminal (SIGHUP) would otherwise end the process without running a single
    # `finally` — and the statement log's `finally` is what switches a shared database's query log back off.
    for signum in (signal.SIGTERM, signal.SIGHUP):
        signal.signal(signum, lambda received, _frame: sys.exit(128 + received))

    compose_up()
    statements = StatementLog(stamp, workdir)
    try:
        statements.open()
        sentinels = [statements.plant("start")]
        provision_store(workdir)
        # The container sweep's control, planted first so that finding it proves the log window reaches the run's start.
        sentinel = plant_store_sentinel(stamp)
        mail_sentinel = plant_mail_sentinel(stamp)

        # Before any suite: the operator procedure the suites later rehearse is only worth rehearsing if it can be
        # aimed. This one needs no jar and no instance — it runs here, on the stack `compose_up()` just brought up.
        rehearse_production_project(workdir)

        failures: list[tuple[str, str]] = []
        for index, members in enumerate(groups, start=1):
            # Never short-circuit across groups: one broken suite must not hide the state of the others.
            failures.extend(run_group(index, members, workdir, stamp))

        # The run-level sweeps: after EVERY group, so each log is complete (its JVM has exited) and a restored
        # instance's log is swept for the member before it too. They run even when suites failed — a failed suite's
        # log is the one most likely to hold a stack trace, and a hit there only adds to a run that is already red.
        canary_files = sorted(workdir.rglob("canaries-*.json"))
        # The second needle set, run-wide: every capability token any suite's recording shows it handled.
        tokens = run_tokens(workdir)
        log(f"capability tokens of the run: {len(tokens.by_needle)} ({kinds_of(tokens.by_needle.values())}), "
            f"{len(tokens.mailed)} first read out of a mail")
        run_failures = [(name, reason) for name, reason in (
            ("(run) server-log sweep", sweep_logs(workdir, canary_files, tokens)),
            # Bounded below by the run's own start (the stamp is a unix time): a developer's earlier traffic on these
            # long-lived containers is not this run's to answer for.
            ("(run) container-log sweep", sweep_containers(workdir, canary_files, stamp, sentinel, tokens)),
            ("(run) mailbox sweep", sweep_mailbox(workdir, canary_files, float(stamp), mail_sentinel, tokens)),
        ) if reason]
        sentinels.append(statements.plant("end"))
    finally:
        statements.close()
    # After the window closed: the sweep reads the retained copy, and the shared database is already back to normal.
    statement_failure = sweep_statements(statements.retained, canary_files, sentinels, booted_databases(workdir),
                                         statements.problems, tokens)
    if statement_failure:
        run_failures.append(("(run) statement-log sweep", statement_failure))

    print("", flush=True)
    log("==================== summary ====================")
    log(f"total: {len(files)}  passed: {len(files) - len(failures)}  failed: {len(failures)}  "
        f"run-level sweeps failed: {len(run_failures)}")
    for name, reason in failures + run_failures:
        log(f"  FAILED  {name}: {reason}")
    return 1 if failures or run_failures else 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except NoE2eFilesError as e:
        raise SystemExit(f"[e2e] NO FILES MATCHED: {e}") from e
