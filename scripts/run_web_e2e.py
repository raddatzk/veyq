#!/usr/bin/env python3
"""Run web's `VEYQ_E2E=1` suites against a live backend + a real object store, one file at a time.

**What it does.** Brings up the bundled stack from `infra/docker/docker-compose.yml` (`--profile s3 --profile mail`:
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

The hook is what a group exists for: `restore.*` captures the instance with the app already stopped — the database,
the store's index, and the objects into an object pool in the group directory (ADR 0056 §3) — **destroys the source**
(database dropped, bucket purged, key file removed), restores onto a structurally empty one with `--objects-from` that
pool, and boots the next member against *that*. Destroying the source is not tidiness — it is the only way "the
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

**The upgrade proof — two builds in one run** (ADR 0057 Decision 7, `--upgrade` / `--upgrade-from <ref>`). The
`upgrade` group's first member, the SEEDER, runs from a detached worktree of a PREDECESSOR ref built in the run
directory ([prepare_predecessor]: its own lockfile install, which generates its client, and its own fast-jar) and boots on the
predecessor's jar; the group's hook captures that instance, destroys it and restores it onto a target installed by THIS
checkout's jar ([upgrade_hook]); the READER, this tree's last member of the group, boots on this jar — which migrates
the predecessor's schema up — and must read `configSecretState: OK` before it asserts every item of the record the
seeder wrote (`src/web/src/test/upgradeRecord.ts`). The predecessor defaults to the first parent of `HEAD` while the pin
file says `release: none`, and to the newest `v<major>.<minor>` release tag once it names a release
([default_predecessor] — the one switch); a ref without the seeder is refused before anything is built. A release is
proved a second time, `--upgrade-major-entry`, from its MAJOR ENTRY ([major_entry]: the newest release of the previous
major among all the repository's tags, a `release/<old>.x` branch's included, ADR 0085 decision 10) — when there is one
and it is not the default predecessor; otherwise that run says so and runs nothing. The run's
summary ends with the proof's own lines ([upgrade_verdict]): the predecessor, both builds' highest migration, the
migrations changed or removed between them, the reader's `configSecretState`, and GREEN or RED with its cause — a
Flyway refusal read out of the reader's boot log is named as such ([flyway_cause]). Red exits non-zero; before the
first release that informs and gates nothing. Every wire sweep runs as in any run, the predecessor's suite included —
with one known edge: its HTTP recording is matched against THIS tree's contracts, so a call the candidate no longer
declares fails the token derivation, naming the call. In an ordinary run the `upgrade` group runs like any other, both
members from this tree, which keeps the pair working between proofs. Its pure parts are pinned, without Docker, by
`scripts/upgrade_proof_selftest.py`.

**Why a fresh instance per file.** Every suite cold-births its own Server Owner and asserts
`setupRequired === true`, and a server is single-owner-per-instance. `pipeline.yml` used to call this "a fresh in-mem
DB per file", which does not work: `quarkus.datasource.db-kind` is a BUILD-TIME property baked at augmentation
under `%prod`, so a runtime `QUARKUS_DATASOURCE_DB_KIND=h2` is ignored and the *MariaDB* driver rejects the H2
URL. One MariaDB with a fresh `veyq_e2e_<n>` database per file is a fresh instance in the only sense the suites
care about — at one container, and with `pipeline.yml`'s "build the jar once" intact.

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
    python3 scripts/run_web_e2e.py --upgrade         # the upgrade proof against the default predecessor
    python3 scripts/run_web_e2e.py --upgrade-from <git ref>
    python3 scripts/run_web_e2e.py --upgrade-major-entry   # the second proof, from the major entry (none: no run)
    python3 scripts/run_web_e2e.py --upgrade-major-entry --resolve   # its predecessor (`predecessor=<ref>`), run nothing

With no arguments it runs every `src/web/src/**/*.e2e.test.ts` — that is how CI invokes it — except the
**measurements**, `*.measure.e2e.test.ts`: a suite that takes a reading rather than holding a claim CI must keep (the
rotation of a full identity-pin store, inbox #538). One runs only when asked for, with `VEYQ_E2E_MEASURE=1` set — named
as an argument, or with no arguments beside every other suite — and its own `describe.skipIf` reads the same variable.
Left in the default set, a skipped suite would still boot an instance and fail its sweeps for recording no canary.
Paths may be given relative to the repo root or to `src/web/`, which is a local debugging convenience only.

Prerequisite: the fast-jar exists (`cd src/backend && ./gradlew quarkusBuild`). CI builds it once, before this runs.
**And a STALE jar fails as a wrong answer, not as an error** (cost one run in cr-account-recovery): this boots
whatever jar is on disk, so backend changes made since the last build are simply absent — a new endpoint 404s, a
new migration never applies. Both look like a broken suite. The tell is in the boot log: `Successfully applied N
migrations, now at version vN` names the schema the jar carries, so compare it against the highest
`V<yyyymmddHHMM>__*.sql` in the tree before believing a failure. Rebuild after ANY backend change; `./gradlew check` does
not do it for you (it builds test classes, not the fast-jar). The web mirror of this trap is in `src/web/CLAUDE.md`
— `pnpm run check` not regenerating the API client — and it is the same failure in both directions: a green or
red that was measured against code the run never saw.

**The run directory survives the run** (`.e2e-out/<stamp>/`, gitignored). It holds each boot's log, its
per-boot config-secret file, its canary file, its HTTP recording and the dump its database sweep read
(`database-<tag>.sql`), and — once the run-level sweep
has read them — every container's log over the run's window (`container-<service>.log`), the database's
statement log (`mariadb-general.log`) and every mail of the run (`mailbox/<id>.eml`). It used to be a `tempfile.TemporaryDirectory` discarded on
exit, which left the boot log — the surface where an error `message` or a stack trace would carry plaintext —
not merely unswept but unavailable (ADR 0053 § Decision 4). It is a developer artifact and CI throws the runner away, but a
developer's disk does not: a run left ~15 GB behind - almost all of it one full mirror of the object store per suite
([sweep_store]) - and 28 runs filled a laptop (170 GB, found 2026-10-14). So two things now bound it: a mirror whose
sweep came back clean is deleted at once (it is only ever read BY that sweep; one that found something is kept, because
that is the one a reader must open to explain the hit), and a new run first prunes the older run directories down to
`VEYQ_E2E_KEEP_RUNS` (default 2, counting the new one).

**Runs side by side.** Two runs against one stack (two worktrees, two agents) used to share everything a run makes on
it — `veyq_e2e_1`, the statement log, the ports — and one of them failed. Each run now claims a SLOT on the stack
(`e2e_sweeps.claim_run_slot`, a registration in the mariadb container that a killed run's successor clears): a run
alone holds slot 0 and everything above is unchanged for it, while a run that finds another live takes the next slot,
which names its databases (`veyq_e2e_s<slot>_<n>`, …) and buckets, gets free ports where `VEYQ_PORT`/`VEYQ_ADMIN_PORT`
are unset, and a stamp no live run holds. The statement log is one window both runs share
(`e2e_sweeps.StatementLog`), and `.e2e-out/` prunes no directory of a run still alive. The shared `blobs` bucket and the
one Mailpit stay shared: every object and every address carries its run's own ids.

Environment (all optional):
    VEYQ_JAR              Backend fast-jar. Default: src/backend/build/quarkus-app/quarkus-run.jar
    VEYQ_PORT             Backend HTTP port. Default: 8080 (a free one for a run beside another, see above)
    VEYQ_ADMIN_PORT       Backend admin-listener port (ADR 0065). Default: 8090 (likewise)
    VEYQ_INFRA_DIR        The directory holding the Compose files whose stack to use (a checkout's `DOCKER_DIR` in
                          `scripts/repo_layout.py`). Default: this checkout's (see [compose_up] for a worktree)
    VEYQ_HOST             Tenant domain + fetch Host. Default: veyq.localhost
    VEYQ_BOOT_SECS        Readiness timeout per boot. Default: 120
    VEYQ_TEST_MS          Default per-test timeout passed to vitest. Default: 120000
    VEYQ_E2E_KEEP_RUNS    How many run directories `.e2e-out/` keeps, the new one included (at least 1). Default: 2
    VEYQ_DB_ROOT_PASSWORD MariaDB root password. Default: the documented compose throwaway.
    VEYQ_E2E_MEASURE      `1` runs the `*.measure.e2e.test.ts` measurements too (see above). Default: unset.
"""
from __future__ import annotations

import atexit
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Iterable, NamedTuple

# A group's hook shells out to the operator tool for capture/restore, but reuses its rclone plumbing for the one
# thing the CLI deliberately does not offer (purging a bucket). Importable because Python puts the running
# script's own directory — `scripts/` — on `sys.path`, which also holds when `validate_e2e_discovery.py`
# imports this module by path; `repo_layout` then puts the tools' directory beside it.
import repo_layout
repo_layout.tools_importable()
import instance_backup
# The ADR 0053 sweeps, shared with `run_stack_e2e.py` and bound to this file's dev stack below ([DEV]). The pure
# parts are re-exported under the names this module always had: the guards that import it by path call them here.
import e2e_sweeps as sweeps
from e2e_sweeps import (  # noqa: F401 - re-exported for scripts/validate_wire_log_matcher.py
    NO_TOKENS, RunTokens, SweepError, Token, canary_needles, db_token_findings, dump_escape, kinds_of, literal_hits,
    log, mail_texts, needle_hits, set_cookie_values, shown, sql_literal, sweep_http, sweep_statements, token_needle,
)

REPO = Path(__file__).resolve().parent.parent
WEB = repo_layout.WEB
# `VEYQ_INFRA_DIR` names the directory holding the Compose files (ADR 0106: `infra/docker/` of a checkout), and pins
# the stack to another checkout's — see the worktree note in [compose_up].
INFRA = Path(os.environ.get("VEYQ_INFRA_DIR", repo_layout.DOCKER_DIR))
COMPOSE_FILE = INFRA / "docker-compose.yml"
COMPOSE = ["docker", "compose", "-f", str(COMPOSE_FILE), "--profile", "s3", "--profile", "mail"]


def compose_project(compose_file: Path) -> str:
    """The project a Compose file runs as, by Compose's own rule: the file's top-level `name:`, else its directory's
    name. Read rather than hardcoded, because the rule is the authority and the string only today's answer to it."""
    try:
        text = compose_file.read_text()
    except OSError:
        return compose_file.parent.name
    declared = re.search(r"^name:\s*['\"]?([^'\"\s#]+)", text, re.MULTILINE)
    return declared.group(1) if declared else compose_file.parent.name


# The network a group's capture/restore containers join to reach the store by its INTERNAL name: the dev project's
# default network. The dev file declares its project (`name: infra`, ADR 0106 decision 2), so this is
# `infra_default` wherever the file sits.
DOCKER_NETWORK = f"{compose_project(COMPOSE_FILE)}_default"

# The production stack is a SECOND compose project on the same host. Both files sit in one directory, and only the
# production file's own `name: veyq` keeps a capture pointed at it from resolving the dev stack this orchestrator
# just brought up. See [rehearse_production_project].
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
# The backend's fast-jar, relative to a backend directory: this checkout's, or a predecessor's ([prepare_predecessor]).
JAR_IN_BACKEND = "build/quarkus-app/quarkus-run.jar"
JAR = Path(os.environ.get("VEYQ_JAR", repo_layout.BACKEND / JAR_IN_BACKEND))
BOOT_SECS = int(os.environ.get("VEYQ_BOOT_SECS", "120"))
TEST_MS = os.environ.get("VEYQ_TEST_MS", "120000")

# Documented throwaway credentials from infra/docker/docker-compose.yml — not secrets. The app user `veyq` holds grants
# on the `veyq` database ONLY, so each e2e database is created as root and then granted to it.
DB_ROOT_PASSWORD = os.environ.get("VEYQ_DB_ROOT_PASSWORD", "change-me-root")
DB_APP_USER = "veyq"

# This run's slot on the stack ([use_slot], `e2e_sweeps.claim_run_slot`): 0 for a run alone, whose names are the ones
# a run always had; a run beside another names everything it makes on the shared stack with its slot.
SLOT = 0


def _db_ns() -> str:
    return f"_s{SLOT}" if SLOT else ""


def _bucket_ns() -> str:
    return f"-s{SLOT}" if SLOT else ""


# Every database a boot of this run is pointed at, named in ONE place: the boots use these, and [sweep_logs]
# derives from a boot log's file name which of them that boot's log must name (its armed-sensor control).
def store_init_database() -> str:
    """The provisioning boot's scratch database ([provision_store])."""
    return f"veyq_e2e{_db_ns()}_store_init"


def suite_database(index: int) -> str:
    """A group's first instance — the database its first member runs against."""
    return f"veyq_e2e{_db_ns()}_{index}"


def restore_database(index: int) -> str:
    """The instance a group's hook restores onto — every later member of the group runs against it."""
    return f"veyq_e2e{_db_ns()}_restore_{index}"


def group_bucket(index: int) -> str:
    """A real group's own bucket — the one its hook destroys ([run_group])."""
    return f"veyq-e2e{_bucket_ns()}-src-{index}"


# The bundled store's ADMIN plane (a different port from the S3 one), published local-only by
# infra/docker/docker-compose.yml. This script READS it to confirm the backend provisioned the store; it never writes
# through it. Documented throwaway dev token, not a secret.
GARAGE_ADMIN = os.environ.get("VEYQ_GARAGE_ADMIN", "http://127.0.0.1:19003")
GARAGE_ADMIN_TOKEN = os.environ.get("VEYQ_GARAGE_ADMIN_TOKEN", "veyq-dev-admin-token")
# The bucket `%e2e` boots with — the backend's default `veyq.bundled-store.bucket`, which `%e2e` does not override — and
# the key alias the backend imports it under (GarageStoreProvisioner.KEY_ALIAS). `fresh_bucket` grants against that
# exact alias, so the name is a contract between this file and that class.
STORE_BUCKET = "blobs"
STORE_KEY_ALIAS = "veyq-app"
# The store's compose service, and its S3 API as `infra/docker/docker-compose.yml` publishes it to this host's loopback
# (`127.0.0.1:19000:3900`) — the endpoint `%e2e` hands the jar, so every presigned URL of a run goes through it. [plant_store_sentinel]
# sends the run's log control there.
STORE_SERVICE = "garage"
STORE_S3 = "http://127.0.0.1:19000"
# The same store by its INTERNAL name, as a container on `DOCKER_NETWORK` reaches it — the backup tool's rclone and
# the store sweep's mirror ([backup_env], [DEV]).
STORE_INTERNAL = "http://garage:3900"

# The throwaway mailbox, published by `infra/docker/docker-compose.yml`'s `mail` profile. It is EXPORTED to each suite as
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


def free_port(taken: Iterable[int] = ()) -> int:
    """A port nothing on this host listens on now, as the OS hands one out — for a run beside another ([use_slot])."""
    for _ in range(20):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.bind(("", 0))
            port = probe.getsockname()[1]
        if port not in taken:
            return port
    raise SystemExit("[e2e] found no free port for a run beside another")


def use_slot(slot: int) -> None:
    """Bind this run to its slot: the names [_db_ns] derives, and for a run beside another, free ports where none is
    set. Rebinds every module name built from a port, so the readiness probe, the suites' bases and the token
    derivation's origins ([DEV]) follow."""
    global SLOT, PORT, ADMIN_PORT, BASE, ADMIN_BASE, READY_URL, DEV
    SLOT = slot
    if slot and "VEYQ_PORT" not in os.environ:
        PORT = free_port()
    if slot and "VEYQ_ADMIN_PORT" not in os.environ:
        ADMIN_PORT = free_port(taken=(PORT,))
    BASE = f"http://{HOST}:{PORT}"
    ADMIN_BASE = f"http://127.0.0.1:{ADMIN_PORT}"
    READY_URL = f"http://127.0.0.1:{PORT}/api/setup"
    DEV = DEV._replace(backend_origins=(BASE, ADMIN_BASE))


class NoE2eFilesError(RuntimeError):
    """Discovery matched no e2e file. A hard error, never an empty-but-successful run.

    This is the false negative the whole job exists to remove: an orchestrator that reports green because it ran
    nothing looks exactly like an orchestrator whose suites all passed. `validate_e2e_discovery.py` pins it.
    """


def discover(root: Path = WEB) -> list[Path]:
    """Every web e2e suite, sorted. Raises [NoE2eFilesError] rather than returning an empty list."""
    files = sorted(root.glob("src/**/*.e2e.test.ts"))
    if not files:
        raise NoE2eFilesError(f"no *.e2e.test.ts found under {root}/src — refusing to report success on zero files")
    return files


MEASUREMENT_SUFFIX = ".measure.e2e.test.ts"


def opted_in(files: list[Path], named: bool) -> list[Path]:
    """The run's files without the measurements, unless `VEYQ_E2E_MEASURE=1` asks for them (the module docstring says why).

    A measurement named as an argument without the variable is refused rather than run: its suite would skip, and a
    skipped suite's sweeps fail on the canary it never recorded — a red that says nothing about the measurement.
    """
    if os.environ.get("VEYQ_E2E_MEASURE") == "1":
        return files
    measurements = [f for f in files if f.name.endswith(MEASUREMENT_SUFFIX)]
    if named and measurements:
        raise SystemExit(f"[e2e] {', '.join(f.name for f in measurements)}: a measurement runs only with VEYQ_E2E_MEASURE=1")
    for f in measurements:
        log(f"leaving out the measurement {f.name} (VEYQ_E2E_MEASURE=1 runs it)")
    return [f for f in files if f not in measurements]


def normalize(arg: str) -> Path:
    """Accept a path relative to the repo root or to src/web/ (or absolute)."""
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
    # THIS checkout's copy with `VEYQ_INFRA_DIR=<main checkout>/infra/docker`: the stack stays the main checkout's while
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
    provisioner works (ADR 0067 decision 4). So the harness asserts the outcome and
    produces none of it.

    It also proves more than the Server-Owner read could. That read is `@RolesAllowed(SERVER_OWNER)` and the
    throwaway instance below has no owner at all — it exists precisely to be pre-setup — so the read is not
    reachable from here. Reading the store directly is the better oracle anyway: a state field saying
    PROVISIONED while the bucket was not actually usable is exactly the failure this needs to catch.

    **A cold store has two "not yet" answers, not one.** Without a layout, Garage cannot serve a bucket call at
    all: `GetBucketInfo` answers 503 `ServiceUnavailable` ("Could not reach quorum of 1"), and only once the
    provisioner's first step has applied a role does it answer the 404 `NoSuchBucket` a missing bucket gets
    (measured on the pinned v2.0.0, 2026-11-05: 503 on a fresh node, 404 right after `ApplyClusterLayout`). Neither
    the compose file's `--wait` nor Garage's own `/health` can say the layout is there — `/health` answers 200 on a
    layout-less node, which is exactly why the chart probes it (no readiness may wait on the backend's bring-up). So
    a 503 is read as "not provisioned" only when the node demonstrably has no layout role ([store_has_layout]); a
    503 from a node that has one is a store that is broken, and still refused. Reading the 503 as a refusal outright
    failed the first CI run on its first look at a cold store (`run_api_fuzz.provision_store` asks before booting).
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
        said = _admin_error(e)
        if e.code == 503 and not store_has_layout():
            return False  # a node with no layout yet: the provisioner's first step has not run
        raise SystemExit(f"[e2e] STORE ADMIN API REFUSED: {GARAGE_ADMIN} answered {e.code} for {STORE_BUCKET}: {said}")
    except (urllib.error.URLError, OSError) as e:
        raise SystemExit(f"[e2e] STORE ADMIN API UNREACHABLE at {GARAGE_ADMIN}: {e}") from e
    return any(k.get("name") == STORE_KEY_ALIAS and (k.get("permissions") or {}).get("owner")
               for k in info.get("keys") or [])


def store_has_layout() -> bool:
    """Does the store's node carry an APPLIED layout role? `GetClusterLayout` answers on a layout-less node too
    (measured: version 0, `roles: []`); a role only staged by `UpdateClusterLayout` sits in `stagedRoleChanges`, not
    in `roles`, so it counts as none — a staged role serves no bucket call either."""
    request = urllib.request.Request(
        f"{GARAGE_ADMIN}/v2/GetClusterLayout",
        headers={"Authorization": f"Bearer {GARAGE_ADMIN_TOKEN}"},
    )
    try:
        with urllib.request.urlopen(request, timeout=5) as r:
            layout = json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        raise SystemExit(f"[e2e] STORE ADMIN API REFUSED: {GARAGE_ADMIN} answered {e.code} for its cluster layout: "
                         f"{_admin_error(e)}")
    except (urllib.error.URLError, OSError) as e:
        raise SystemExit(f"[e2e] STORE ADMIN API UNREACHABLE at {GARAGE_ADMIN}: {e}") from e
    return bool(layout.get("roles"))


def _admin_error(e: urllib.error.HTTPError) -> str:
    """Garage's own `code: message` for a refused admin call — the half of the answer the status alone hides (the
    503 above read only as "answered 503" until its body said "Could not reach quorum")."""
    try:
        body = json.loads(e.read().decode())
        return f"{body.get('code')}: {body.get('message')}"[:400]
    except (ValueError, OSError, AttributeError):
        return "(no readable body)"


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
    database = store_init_database()
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


def mysql_rows(sql: str) -> list[list[str]]:
    """Run one SELECT as root and return every row's columns (batch mode, no header)."""
    proc = run(COMPOSE + ["exec", "-T", "mariadb", "mariadb", "-uroot", f"-p{DB_ROOT_PASSWORD}", "-N", "-B", "-e", sql],
               capture_output=True, text=True)
    return [line.split("\t") for line in proc.stdout.splitlines() if line]


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


def port_is_taken(url: str | None = None) -> bool:
    """Something already answering on our port would be readiness-polled as if it were ours."""
    url = url or READY_URL
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


def boot(database: str, secret_file: Path, log_path: Path, bucket: str | None = None,
         jar: Path = JAR) -> subprocess.Popen:
    """Boot the fast-jar under `%e2e` against this file's own database — this checkout's jar unless the upgrade proof
    hands the predecessor's ([Build]).

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
        env["VEYQ_BUNDLED_STORE_BUCKET"] = bucket
    handle = log_path.open("w")
    # The log is a sweep surface ([sweep_logs]), so the bytes it holds must not depend on the host: with stdout a
    # FILE, the JVM encodes it in the host locale's charset. Measured (on Java 21): without these two flags, under
    # `LC_ALL=C`, the boot log's `—` came out as `?` — and a non-ASCII canary would too, which the needles then miss.
    # Pinned here; [sweep_logs] reads the file as UTF-8.
    return subprocess.Popen(
        ["java", "-Dquarkus.profile=e2e", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar", str(jar)],
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


# ----------------------------------------------------------------------------------------------------------
# The wire assertion (ADR 0053) — the sensors live in scripts/e2e_sweeps.py, shared with run_stack_e2e.py
# ----------------------------------------------------------------------------------------------------------

# The dev stack as the shared sweeps address it (`e2e_sweeps.Stack`): this file's compose command and root
# credential, the store as the backup tool's rclone container reaches it (its INTERNAL name, on `DOCKER_NETWORK`), the
# backend's two listeners the token derivation matches a recording against, and the mailbox. The entry points below
# keep the names and signatures this file always had — its own run and the guards that import it by path
# (`validate_wire_log_matcher.py`) call them — and only bind this stack.
DEV = sweeps.Stack(
    compose=tuple(COMPOSE), db_root_password=DB_ROOT_PASSWORD, store_service=STORE_SERVICE,
    store_endpoint=STORE_INTERNAL, store_admin_token=GARAGE_ADMIN_TOKEN, docker_network=DOCKER_NETWORK,
    backend_origins=(BASE, ADMIN_BASE), mailpit=MAILPIT,
)


def sweep(database: str, canary_file: Path, rel: str, tokens: dict[str, Token] | None = None,
          retain: Path | None = None) -> str | None:
    """[e2e_sweeps.sweep] over one suite's own database of the dev stack."""
    return sweeps.sweep(DEV, database, canary_file, rel, tokens, retain)


def sweep_store(bucket: str, database: str, canary_file: Path, rel: str, workdir: Path,
                tokens: dict[str, Token] | None = None) -> str | None:
    """[e2e_sweeps.sweep_store] over one bucket of the dev stack's Garage."""
    return sweeps.sweep_store(DEV, bucket, database, canary_file, rel, workdir, tokens)


def token_needles(record_file: Path) -> tuple[dict[str, Token], list[str], set[str]]:
    """[e2e_sweeps.token_needles] with the dev backend's two listeners and Mailpit."""
    return sweeps.token_needles(DEV, record_file)


def run_tokens(workdir: Path) -> RunTokens:
    """[e2e_sweeps.run_tokens] over every recording of this run."""
    return sweeps.run_tokens(DEV, workdir)


# `backend-<n>.log` — a group's first member; `backend-<n>-<p>.log` — a later member, after the hook restored;
# `backend-install-<n>.log` — the restore target's install boot; `backend-store-init.log` — the provisioning boot.
BOOT_LOG = re.compile(r"^backend-(?:(?P<suite>\d+)(?:-(?P<member>\d+))?|install-(?P<install>\d+)|store-init)\.log$")


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
    return store_init_database()


def booted_databases(workdir: Path) -> set[str]:
    """Every database a boot of this run was pointed at — derived from the boot logs the run wrote, never listed."""
    return {db for db in (booted_database(p) for p in workdir.rglob("backend-*.log")) if db}


def sweep_logs(workdir: Path, canary_files: Iterable[Path], tokens: RunTokens = NO_TOKENS,
               pii: dict[str, list[str]] | None = None) -> str | None:
    """[e2e_sweeps.sweep_logs] over this run's boot logs, each armed by the database [booted_database] derives."""
    return sweeps.sweep_logs(workdir, canary_files, booted_database, tokens, pii)


def plant_store_sentinel(stamp: str) -> str:
    """Send the dev store one unsigned request for the run's sentinel key ([e2e_sweeps.store_sentinel] says why)."""
    sentinel, key = sweeps.store_sentinel(stamp)
    url = f"{STORE_S3}/{STORE_BUCKET}/{key}"
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


def sweep_containers(workdir: Path, canary_files: Iterable[Path], since: str, sentinel: str,
                     tokens: RunTokens = NO_TOKENS, pii: dict[str, list[str]] | None = None) -> str | None:
    """[e2e_sweeps.sweep_containers] over every service of the dev compose project."""
    return sweeps.sweep_containers(DEV, workdir, canary_files, since, sentinel, tokens, pii=pii)


class StatementLog(sweeps.StatementLog):
    """[e2e_sweeps.StatementLog] on the dev stack's `veyq-mariadb` — the developer's own, whence its healing rules."""

    def __init__(self, stamp: str, workdir: Path) -> None:
        super().__init__(DEV, stamp, workdir)


def plant_mail_sentinel(stamp: str) -> str:
    """[e2e_sweeps.plant_mail_sentinel] into the dev stack's Mailpit."""
    return sweeps.plant_mail_sentinel(MAILPIT_SMTP, stamp)


def sweep_mailbox(workdir: Path, canary_files: Iterable[Path], since: float, sentinel: str,
                  tokens: RunTokens = NO_TOKENS) -> str | None:
    """[e2e_sweeps.sweep_mailbox] over the dev stack's Mailpit."""
    return sweeps.sweep_mailbox(DEV, workdir, canary_files, since, sentinel, tokens)


class Instance:
    """The three things a boot is pointed at, and the one thing a group's hook replaces wholesale."""

    def __init__(self, database: str, secret_file: Path, bucket: str | None = None) -> None:
        self.database = database
        self.secret_file = secret_file
        self.bucket = bucket

    def describe(self) -> str:
        return f"database {self.database}" + (f", bucket {self.bucket}" if self.bucket else "")


class Build(NamedTuple):
    """What a group member runs FROM: a tree (its suites, its generated client, its `node_modules`) and that tree's jar.

    Every ordinary run has one, [CANDIDATE] — this checkout. The upgrade proof has two: the predecessor's, built in a
    worktree of its ref ([prepare_predecessor]), seeds; this checkout's reads. A suite is only ever run by the build it
    belongs to, so a member's tree and the jar it is booted on can never be mixed.
    """
    label: str
    root: Path
    jar: Path

    @property
    def web(self) -> Path:
        """The tree's web client, in this layout or - a predecessor from before ADR 0106 stage 2 - at the root."""
        return repo_layout.surface_in(self.root, repo_layout.WEB)


CANDIDATE = Build("candidate", REPO, JAR)


def member_name(path: Path, build: Build) -> str:
    """A member as the summary names it: its path in its own `src/web/`, prefixed by the build when it is not this one."""
    rel = path.relative_to(build.web).as_posix()
    return rel if build is CANDIDATE else f"[{build.label}] {rel}"


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
    """What `src/tools/instance_backup.py` reads — the app's OWN variables, pointed at the store's INTERNAL name.

    `%e2e` gives the jar `http://localhost:19000`, the published port; the tool's rclone runs in a container on
    the compose network, where the store is `garage:3900`. Two addresses for one store, and handing the wrong
    one over is a connection refused deep inside a capture.
    """
    return {
        **os.environ,
        "VEYQ_BACKUP_DB_PASSWORD": DB_ROOT_PASSWORD,
        "VEYQ_BUNDLED_STORE_ENDPOINT": STORE_INTERNAL,
        "VEYQ_BUNDLED_STORE_ADMIN_TOKEN": GARAGE_ADMIN_TOKEN,
    }


def backup_tool_result(*args: str, env_extra: dict[str, str] | None = None) -> subprocess.CompletedProcess:
    """Run the DOCUMENTED tool, as an operator would, and hand back how it went.

    Split out of [backup_tool] for the one rehearsal whose subject is the tool's *failure*
    ([rehearse_production_project]): a capture that must not resolve a stack cannot be run through a wrapper
    whose only outcome for a non-zero exit is to end the process. `env_extra` is what the operator's
    `infra/docker/.env` would supply — see [production_compose_env] for why its absence is not a neutral difference.
    """
    # `verify` touches no instance, so it takes no network.
    network = [] if args[0] == "verify" else ["--docker-network", DOCKER_NETWORK]
    cmd = [sys.executable, str(repo_layout.INSTANCE_BACKUP), *args, *network]
    result = subprocess.run(cmd, env={**backup_env(), **(env_extra or {})}, capture_output=True, text=True)
    for line in (result.stdout + result.stderr).splitlines():
        log(f"  {line}")
    return result


def backup_tool(*args: str) -> None:
    """Run the DOCUMENTED tool, as an operator would, rather than reaching into its internals.

    That is the point of the rehearsal: what the suites prove restorable is a procedure `site/src/content/docs/run/backup-and-restore.md`
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
    """An `instance_backup.Store` bound to this bucket of the dev store ([e2e_sweeps.Stack.store])."""
    return DEV.store(bucket)


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
    backup, kept_key, target, restore = capture_destroy_install(index, source, group_dir, CANDIDATE)
    assert_unsupplied_key_is_refused(restore, target)
    assert_store_holding_an_object_is_refused(restore, target)
    if with_key:
        assert_newer_backup_is_refused(restore, backup, group_dir, target)
        # The restore of this group is ALSO the older-backup rehearsal (ADR 0056 §7): the target is made to look
        # one release ahead of the capture before the restore runs, and the restore must leave exactly the
        # capture's schema behind. It is the same restore the suite then runs against, so the suite going green
        # is the "and the app starts on it" half.
        history = simulate_newer_target(target)
        result = backup_tool_result(*restore, "--key-from", str(kept_key))
        if result.returncode != 0:
            raise SystemExit(f"[e2e] instance_backup.py restore failed with exit {result.returncode}")
        assert_older_backup_restored(target, history, result.stdout + result.stderr)
    else:
        log("leg-3 rehearsal: restoring knowingly without the separately kept key")
        backup_tool(*restore, "--without-key")
    assert_populated_target_is_refused(restore)
    return target


def capture_destroy_install(index: int, source: Instance, group_dir: Path,
                            build: Build) -> tuple[Path, Path, Instance, list[str]]:
    """The first half every hook shares: capture the stopped source (key kept apart), verify it, destroy the source, and
    install the target the restore goes onto — with the jar of `build`, the build of the member that runs next. Returns
    the capture, the kept key, the target and the restore command without its key flag: each hook adds the flag its
    rehearsal is about.

    **The objects travel through an object pool** (ADR 0056 §3): a capture holds the database and the store's index,
    never the objects, and `destroy` purges the source bucket — so a restore without a source would find the target's
    store empty and refuse (ADR 0056 §7; the tool's self-test case P9). The capture names a pool in the group directory
    and every restore built here names it with `--objects-from`, the restore onto a fresh instance an operator runs."""
    backup = group_dir / "backup"
    kept_key = group_dir / "kept-apart" / instance_backup.KEY_NAME
    pool = (group_dir / "pool").resolve()
    shutil.rmtree(pool, ignore_errors=True)  # a pool is kept across captures; this rehearsal's starts empty
    backup_tool("capture", "--into", str(backup), "--key-into", str(kept_key), "--db-name", source.database,
                "--store-bucket", source.bucket or STORE_BUCKET, "--config-secret-file", str(source.secret_file),
                "--objects-to", str(pool))
    verify_capture(backup, kept_key, pool)

    destroy(source)

    target = Instance(restore_database(index), group_dir / "restored-config-secret.key",
                      f"veyq-e2e-dst-{index}")
    fresh_database(target.database)
    fresh_bucket(target.bucket)
    install(target, group_dir / f"backend-install-{index}.log", build)
    restore = ["restore", str(backup), "--domain", HOST, "--db-name", target.database,
               "--store-bucket", target.bucket, "--config-secret-file", str(target.secret_file),
               "--objects-from", str(pool)]
    return backup, kept_key, target, restore


def upgrade_hook(index: int, source: Instance, group_dir: Path, build: Build) -> Instance:
    """The `upgrade` group's hook: the documented capture -> destroy -> install -> restore, and nothing else.

    The seeder's instance is restored WITH its separately kept key onto a target freshly installed by `build` — the
    reader's: under `--upgrade` the CANDIDATE, so the reader's boot is the candidate migrating the predecessor's schema
    up (ADR 0056 §7), exactly the "restore, start" an operator's upgrade is. The gates the restore rehearsal proves around
    the same tool (the key gate, A5, the newer and older backup) are not repeated: this group's subject is the CONTENT
    surviving the restore and the upgrade, which only its reader can assert. Before anything is captured the seeder's
    record must be one the reader can read ([upgrade_record_problems]) — a seeder that wrote none is named as that, not
    as a failed upgrade. A refused restore is a [HookFailure]: in the upgrade proof that is an outcome (a backup NEWER
    than the candidate, say), which the summary names, not a crash of the harness.
    """
    record = group_dir / UPGRADE_RECORD_FILE
    try:
        problems = upgrade_record_problems(json.loads(record.read_text()))
    except (OSError, ValueError) as e:
        problems = [f"unreadable: {e}"]
    if problems:
        raise HookFailure(f"the seeder's record {shown(record)} is not one the reader can read: {'; '.join(problems)}")
    _backup, kept_key, target, restore = capture_destroy_install(index, source, group_dir, build)
    result = backup_tool_result(*restore, "--key-from", str(kept_key))
    if result.returncode != 0:
        body = (result.stdout + result.stderr).strip().splitlines()
        raise HookFailure(f"the restore onto the {build.label} was refused (exit {result.returncode}): "
                          f"{body[-1] if body else 'no output'}")
    return target


def verify_capture(backup: Path, kept_key: Path, pool: Path | None = None) -> None:
    """Every capture is checked by the tool's own `verify`, and must hold no key file of its own.

    Exit 0 is the whole verdict: a structurally broken capture exits 1 and one missing leg (c) exits 2 — and so
    would one whose dump or directory had loosened to group/world-readable, which is the leak `verify` exists to
    catch now. The `test ! -e` beside it is the property the run cares about stated directly, so a `verify` that
    stopped looking could not pass it by itself.
    """
    backup_tool("verify", str(backup), "--key-from", str(kept_key))
    if pool is not None:
        recorded = (json.loads((backup / instance_backup.MANIFEST_NAME).read_text())["store"].get("pool") or {})
        if recorded.get("target") != str(pool) or not pool.is_dir():
            raise SystemExit(f"[e2e] CAPTURE NAMES ANOTHER POOL: its manifest's store.pool.target is "
                             f"{recorded.get('target')!r}, the capture was told {shown(pool)} (ADR 0056 §3)")
    beside = backup / instance_backup.KEY_NAME
    if beside.exists():
        raise SystemExit(f"[e2e] CAPTURE LEAKS: {shown(beside)} sits beside the dump — the capture was told to keep "
                         f"the key at {shown(kept_key)} (ADR 0056 §1)")


def install(target: Instance, boot_log: Path, build: Build) -> None:
    """Start the app once against [target] and stop it — which is what "a freshly installed instance" IS.

    One boot migrates the schema (`quarkus.flyway.migrate-at-start=true`, no profile gate) and runs the store
    provisioning. That is the operator's step 1 of a restore now, so it is this rehearsal's step 1 too.
    """
    log(f"installing the restore target ({target.describe()}): one boot to migrate and provision")
    proc = boot(target.database, target.secret_file, boot_log, target.bucket, build.jar)
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


def assert_store_holding_an_object_is_refused(restore: list[str], target: Instance) -> None:
    """The store's half of A5 (ADR 0056 §7): objects are copied only into an EMPTY store.

    One object is put into the freshly installed target's bucket, and the restore — the same command the hook runs
    next, `--without-key` so the key gate cannot be what refuses — must be refused naming the store; the object is then
    removed, and the hook's own restore that follows is this negative's positive control on the same target. Asserted
    on the message, never on a bare non-zero exit. Put and removed with the tool's own rclone plumbing (`Store`).
    """
    store = _store_for(target.bucket)
    decoy = f"{instance_backup.REMOTE}:{target.bucket}/veyq-e2e-decoy/0"
    log("A5 store control: the restore onto a target whose store holds one object — must be refused, naming it")
    if store._run(["touch", decoy]).returncode != 0:
        raise SystemExit(f"[e2e] could not put the decoy object into {target.bucket}")
    try:
        result = backup_tool_result(*restore, "--without-key")
        body = result.stdout + result.stderr
    finally:
        removed = store._run(["deletefile", decoy])
    if removed.returncode != 0:
        raise SystemExit(f"[e2e] could not remove the decoy object from {target.bucket}: {removed.stderr[-300:]}")
    if result.returncode == 0:
        raise SystemExit("[e2e] A5 STORE GATE OPEN: a restore copied objects into a store that held one already")
    if "already holds 1 object" not in body:
        raise SystemExit(f"[e2e] A5 STORE GATE REFUSED FOR THE WRONG REASON: the restore failed without naming the "
                         f"object the store held. Output was: {body.strip()[-500:]}")
    log("A5 store control: refused, naming the object the store held; removed again for the restore that follows")


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


# A table no migration of this tree creates and a history row above every one of them: what a target one release
# ahead of the capture holds that the capture does not (ADR 0056 §7). Named so no real migration can collide.
FUTURE_TABLE = "veyq_e2e_future_table"
FUTURE_VERSION = "999999999999"


def flyway_history(database: str) -> list[list[str]]:
    """The schema's own record, minus what differs between two installs of one release (rank, time, user)."""
    return mysql_rows(
        f"SELECT version, description, type, script, checksum, success FROM {database}.flyway_schema_history "
        f"ORDER BY installed_rank;"
    )


def simulate_newer_target(target: Instance) -> list[list[str]]:
    """Make the freshly installed target look one release AHEAD of the capture, and return its real history.

    Both halves of what a newer release leaves on a fresh install: a table a later migration added, and a
    successful history row above every migration the capture knows. Neither is data, so the A5 gate still
    accepts the target. The history read BEFORE the forgery is the capture's: both instances ran this tree's
    migrations, so the restore must leave exactly that behind.
    """
    history = flyway_history(target.database)
    if not history:
        raise SystemExit(f"[e2e] the installed restore target {target.database} has no Flyway history to extend")
    log(f"older-backup rehearsal: {target.database} gains {FUTURE_TABLE} and a V{FUTURE_VERSION} history row")
    mysql(
        f"CREATE TABLE {target.database}.{FUTURE_TABLE} (id BIGINT PRIMARY KEY); "
        f"INSERT INTO {target.database}.flyway_schema_history (installed_rank, version, description, type, script, "
        f"checksum, installed_by, execution_time, success) SELECT MAX(installed_rank) + 1, '{FUTURE_VERSION}', "
        f"'e2e future', 'SQL', 'V{FUTURE_VERSION}__e2e_future.sql', 0, 'e2e', 0, 1 "
        f"FROM {target.database}.flyway_schema_history;"
    )
    if [FUTURE_TABLE] not in mysql_rows(f"SHOW TABLES FROM {target.database} LIKE '{FUTURE_TABLE}';"):
        raise SystemExit("[e2e] the simulated newer target did not take: the forged table is not there")
    return history


def assert_older_backup_restored(target: Instance, history: list[list[str]], output: str) -> None:
    """The older-backup case (ADR 0056 §7, uc A9): accepted, every table of the target gone, the capture's history.

    The forged table surviving is the defect this exists for — the dump drops and recreates only the tables it
    names, so without the restore's own drop the table outlives the backup's older history and the next start
    fails re-creating it. Asserted on the table and on the history, never on the exit code alone; and the closing
    log must say the data is migrated up and that a rollback is something else.
    """
    leftover = mysql_rows(f"SHOW TABLES FROM {target.database} LIKE '{FUTURE_TABLE}';")
    if leftover:
        raise SystemExit(
            f"[e2e] OLDER BACKUP LEFT THE TARGET'S TABLE: {FUTURE_TABLE} survived the restore. The dump recreates "
            f"only the tables it names, so the restore must drop every table of the target first (ADR 0056 §7)."
        )
    restored = flyway_history(target.database)
    if restored != history:
        raise SystemExit(
            f"[e2e] OLDER BACKUP: the restored history is not the capture's ({len(restored)} row(s), expected "
            f"{len(history)}; first difference at "
            f"{next((i for i, (a, b) in enumerate(zip(restored, history)) if a != b), min(len(restored), len(history)))})"
        )
    if "OLDER than this instance" not in output or "migrate the data" not in output or "ROLLBACK" not in output:
        raise SystemExit(
            f"[e2e] OLDER BACKUP: the restore did not say the data will be migrated up and that a rollback is "
            f"something else. Output was: {output.strip()[-600:]}"
        )
    log(f"older-backup rehearsal: accepted, {FUTURE_TABLE} gone, the capture's {len(history)}-row history restored")


def assert_newer_backup_is_refused(restore: list[str], backup: Path, group_dir: Path, target: Instance) -> None:
    """The newer-backup case (ADR 0056 §7, uc A10): refused by its own message, and nothing written.

    A copy of the capture whose manifest claims a schema above the target's, and a release to name. The version
    is chosen so that STRING order calls it older (it begins with `1`), so a gate comparing strings would let it
    through. The refusal is asserted on its message naming the release — a connection failure exits non-zero
    too — and "nothing written" on the target's tables, history and key, compared with what they were before.
    """
    forged = group_dir / "backup-newer"
    shutil.rmtree(forged, ignore_errors=True)
    shutil.copytree(backup, forged)
    manifest_path = forged / instance_backup.MANIFEST_NAME
    manifest = json.loads(manifest_path.read_text())
    newer = "1" + "0" * (len(str(manifest["database"]["schemaVersion"])) + 1)
    manifest["database"]["schemaVersion"] = newer
    manifest["app"]["version"] = "0.9999"
    manifest_path.write_text(json.dumps(manifest, indent=2))
    before = (mysql_rows(f"SHOW TABLES FROM {target.database};"), flyway_history(target.database))
    log(f"A10 control: a capture claiming schema V{newer} from release 0.9999 — must be refused, writing nothing")
    result = backup_tool_result(*[str(forged) if arg == str(backup) else arg for arg in restore], "--without-key")
    body = result.stdout + result.stderr
    shutil.rmtree(forged, ignore_errors=True)
    if result.returncode == 0:
        raise SystemExit("[e2e] NEWER BACKUP RESTORED: a capture newer than its target was loaded (ADR 0056 §7)")
    if "NEWER than this instance" not in body or "release 0.9999" not in body:
        raise SystemExit(
            f"[e2e] NEWER BACKUP REFUSED FOR THE WRONG REASON: the restore failed without naming the newer schema "
            f"and its release. Output was: {body.strip()[-500:]}"
        )
    after = (mysql_rows(f"SHOW TABLES FROM {target.database};"), flyway_history(target.database))
    if after != before or target.secret_file.exists():
        raise SystemExit("[e2e] NEWER BACKUP REFUSED, BUT WROTE: the target's tables, history or key changed")
    log("A10 control: refused before the first write, naming the release the backup came from")


# Group stem → the hook that runs between its members. A multi-member group with no entry here is a hard error:
# it would otherwise run its second file against the first one's instance and pass for the wrong reason.
HOOKS = {
    "restore": lambda index, source, group_dir, _build: restore_hook(index, source, group_dir, with_key=True),
    "legthree": lambda index, source, group_dir, _build: restore_hook(index, source, group_dir, with_key=False),
    "upgrade": upgrade_hook,
}


class HookFailure(RuntimeError):
    """A hook could not establish the next member's precondition, for a reason the run reports rather than dies of.

    [run_group] scores it as the next member's failure and runs no later member, exactly like a failed member. Only the
    upgrade hook raises it today; the restore rehearsals' refusals stay `SystemExit`, because there a refusal is the
    harness or the tool being broken, never an outcome.
    """


# ----------------------------------------------------------------------------------------------------------
# The production-project rehearsal — which stack does a PRODUCTION capture resolve?
# ----------------------------------------------------------------------------------------------------------

# Instance-SHAPED and deliberately nothing more. The decoy exists so that "which project did the capture
# resolve?" has an exit code instead of a wording: with it in place the dev-file capture completes and the
# production-file capture cannot, and the difference is the one flag between them.
def decoy_db() -> str:
    return f"veyq_prod_project_decoy{_db_ns()}"


def decoy_bucket() -> str:
    return f"veyq-prod-project-decoy{_bucket_ns()}"


DECOY_DOMAIN = "decoy.invalid"


def production_compose_env() -> dict[str, str]:
    """Stand in for the operator's `infra/docker/.env` — DERIVED from the file, never listed beside it.

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
    """The six tables `capture` reads — the smallest thing the tool accepts as an instance.

    Not a copy of veyq's schema and not a claim about one. Every column here exists because
    `instance_backup.Database` selects it: `veyq_tenant.domain`, the flyway history's three, `veyq_blob`'s two
    and `veyq_hold_pin`'s same two (the blob index reads both), a row count off `veyq_upload_session`, and B's row
    (`veyq_backup_retention`, ADR 0056 decision 8 — read for a pool's prune, and 30 where the table is missing, so its
    absence would not fail the capture; it is here so the decoy stays what the tool reads). If the
    tool learns to read a seventh table, this stops satisfying
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
        "CREATE TABLE veyq_hold_pin (storage_key VARCHAR(255), chunk_count INT); "
        "CREATE TABLE veyq_upload_session (id BIGINT); "
        "CREATE TABLE veyq_backup_retention (id INT, retention_days INT);"
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

    seed_project_decoy(decoy_db())
    fresh_bucket(decoy_bucket())
    shared = ["capture", "--db-name", decoy_db(), "--store-bucket", decoy_bucket(),
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
            f"{decoy_db()}, domains {captured} — an instance living in the DEV project. The production file names "
            f"no project, so Compose derived one from its directory (`{PROD_COMPOSE_FILE.parent.name}`) and "
            f"handed the production procedure whatever happens to run out of it. On a production host that is a "
            f"backup of the wrong instance, taken and reported as a success. Declare the project in the file "
            f"(`name:`) so the procedure cannot be pointed at the wrong stack by standing in the wrong directory."
        )
    log(f"the production capture did not resolve the dev project (exit {subject.returncode}), "
        f"while the control through {shown(COMPOSE_FILE)} reached {decoy_db()} — the project is a "
        f"property of the file, not of the directory it sits in")


# ----------------------------------------------------------------------------------------------------------
# The upgrade proof — the predecessor's build seeds, the candidate's reads (ADR 0057 Decision 7)
# ----------------------------------------------------------------------------------------------------------

# The group the proof runs: its FIRST member is the seeder (run from the predecessor's tree), its LAST the reader (from
# this one). Found by name in each tree ([upgrade_seeder]), never by a path, so either file may move.
UPGRADE_GROUP = "upgrade"
# The record the two members share, in the group directory (`src/web/src/test/upgradeRecord.ts` writes and reads it).
UPGRADE_RECORD_FILE = "upgrade-record.json"
UPGRADE_READING_FILE = "upgrade-reading.json"
# What the record's `version` may be — the versions the CANDIDATE's reader reads. Mirrors `UPGRADE_RECORD_VERSION` and
# the reader's own check; `upgrade_proof_selftest.py` and `upgradeRecord.test.ts` hold both to one fixture.
UPGRADE_RECORD_VERSIONS = (1,)
PIN_FILE = "scripts/applied_migrations.sha256"
RELEASE_TAG = re.compile(r"^v(\d+)\.(\d+)$")
MIGRATION_VERSION = re.compile(r"/V(\d+)__[^/]+\.sql$")
SHA256_HEX = re.compile(r"^[0-9a-f]{64}$")
# The `ref` [upgrade_request] returns for `--upgrade-major-entry`: resolved by [main] through [major_entry_proof_ref]. No
# git ref collides with it — `--upgrade-from` refuses a ref that starts with `--`.
MAJOR_ENTRY_REQUEST = "--upgrade-major-entry"
# With an upgrade flag: print the predecessor it resolves to and run nothing ([main]).
RESOLVE_REQUEST = "--resolve"


def upgrade_request(argv: list[str]) -> tuple[bool, str | None, list[str]]:
    """Split the proof's flags off argv: (`is the proof asked for`, `the predecessor ref or None for the default`, rest).

    `--upgrade` asks for the default predecessor ([default_predecessor]); `--upgrade-from <ref>` (or `=<ref>`) for that
    ref; `--upgrade-major-entry` for the major entry ([major_entry]), returned as [MAJOR_ENTRY_REQUEST]. Every other
    argument is a test path, as it always was — and the proof refuses those itself ([main]).
    """
    upgrade, ref, rest = False, None, []
    args = iter(argv)
    for arg in args:
        if arg in ("--upgrade", "--upgrade-from", MAJOR_ENTRY_REQUEST) or arg.startswith("--upgrade-from="):
            if upgrade:
                raise ValueError("--upgrade, --upgrade-from and --upgrade-major-entry name one predecessor between "
                                 "them; pass one of them once")
            upgrade = True
            if arg == MAJOR_ENTRY_REQUEST:
                ref = MAJOR_ENTRY_REQUEST
            elif arg != "--upgrade":
                ref = arg.split("=", 1)[1] if "=" in arg else next(args, "")
                if not ref or ref.startswith("--"):
                    raise ValueError("--upgrade-from names the predecessor: --upgrade-from <git ref>")
        else:
            rest.append(arg)
    return upgrade, ref, rest


def read_release(pin_text: str) -> str:
    """The pin file's `release:` value — `none` until the first release, then the highest migration it shipped."""
    for line in pin_text.splitlines():
        if line.startswith("release:"):
            return line.split(":", 1)[1].strip()
    raise ValueError(f"{PIN_FILE} carries no `release:` line")


def default_predecessor(release: str, tags: list[str]) -> str:
    """The predecessor an unqualified `--upgrade` proves against — the ONE place the switch to the release happens.

    While `release: none` it is the first parent of `HEAD`: the commit before the one under test (on a merge, the
    branch it was merged into). Once a release is set it is the newest release ref `v<major>.<minor>` the caller found
    reachable from that first parent ([main] passes `git tag --merged HEAD^1`), by version order — the release this
    candidate would upgrade an instance of. The pin line names the release's highest migration, not its tag, so the tag
    is found, never derived; a release line with no release tag to find is refused rather than guessed.
    """
    if release == "none":
        return "HEAD^1"
    found = sorted(((int(m.group(1)), int(m.group(2))), tag) for tag in tags if (m := RELEASE_TAG.match(tag)))
    if not found:
        raise ValueError(f"{PIN_FILE} says `release: {release}`, but no v<major>.<minor> tag is reachable from HEAD^1 "
                         f"— fetch the tags, or name the predecessor with --upgrade-from <ref>")
    return found[-1][1]


def major_entry(major: int, tags: list[str]) -> str | None:
    """The release a release of `major` enters its major from — the newest `v<major-1>.<minor>` among `tags`, by version
    order; None where major `major-1` has no release tag, which major 0 (no major before it) never has. ADR 0085
    decision 10.

    The caller passes EVERY release tag of the repository ([major_entry_ref]: `git tag --list`), never only the ones the
    candidate reaches as [default_predecessor]'s are: a fix release of the previous major is tagged on its
    `release/<major-1>.x` branch, which `main` never merges, and it is the release that major's operators run — so it is
    the one the new major is entered from, proved ([second_predecessor]) and baked into the tools image
    (`release_assets.py --major-entry`), whose gate admits no other (ADR 0098 decision 3).
    """
    found = sorted((int(m.group(2)), tag) for tag in tags
                   if (m := RELEASE_TAG.match(tag)) and int(m.group(1)) == major - 1)
    return found[-1][1] if found else None


def second_predecessor(default: str, entry: str | None) -> str | None:
    """The predecessor a release is proved from BESIDE its default one: its major entry, when there is one and it is not
    the default predecessor — a major's first release, cut before any fix release, enters from the release `main`
    reaches, which its default proof already covers, so it is proved once, not twice. None: no second proof."""
    return entry if entry is not None and entry != default else None


def pinned_migrations(pin_text: str) -> dict[str, str]:
    """Every pin line of the pin file: repo-relative migration path -> its SHA-256."""
    pins: dict[str, str] = {}
    for line in pin_text.splitlines():
        parts = line.split()
        if len(parts) == 2 and not line.startswith(("#", "release:")):
            pins[parts[1]] = parts[0]
    return pins


def highest_migration(pins: dict[str, str]) -> str | None:
    """The highest Flyway version a build carries (`V<version>__…`), compared as Flyway does — numerically."""
    versions = [m.group(1) for path in pins if (m := MIGRATION_VERSION.search("/" + path))]
    return max(versions, key=int) if versions else None


def changed_migrations(before: dict[str, str], after: dict[str, str]) -> list[str]:
    """The migrations a restored database would refuse to start on: one the candidate CHANGED (its checksum differs from
    the one the predecessor applied) or REMOVED (applied, and no longer there). An added one is the upgrade itself.

    Compared by FILE NAME, never by repository path: Flyway knows a migration by its version and checksum, not by where
    the file sat, so a migration that only moved (ADR 0106 stage 2 put the backend under `src/`) is neither changed nor
    removed. Each is named by the candidate's path where it has one, else by the predecessor's."""
    after_by_name = {Path(path).name: (path, sha) for path, sha in after.items()}
    changed, removed = [], []
    for path in sorted(before):
        now = after_by_name.get(Path(path).name)
        if now is None:
            removed.append(f"removed: {path}")
        elif now[1] != before[path]:
            changed.append(f"changed: {now[0]}")
    return changed + removed


# Flyway's own words for refusing a restored schema at the candidate's start (`migrate-at-start` validates first). The
# boot log is where they land; each is named so a red proof says WHICH migration and why, not "the JVM exited".
FLYWAY_REFUSALS = (
    (re.compile(r"Migration checksum mismatch for migration version (\S+?)\.?$", re.M),
     "Flyway refused the restored schema: migration V{} was applied by the predecessor with another checksum than "
     "the candidate's file — a migration changed between the two builds"),
    (re.compile(r"Detected applied migration not resolved locally: (\S+?)\.?$", re.M),
     "Flyway refused the restored schema: migration V{} was applied by the predecessor and the candidate no longer "
     "carries it"),
    (re.compile(r"Detected resolved migration not applied to database: (\S+?)\.?$", re.M),
     "Flyway refused the restored schema: the candidate's migration V{} sorts below the restored history, which "
     "outOfOrder=false refuses"),
)


def flyway_cause(log_text: str) -> str | None:
    """Name Flyway's refusal of a restored schema from a boot log, or None when the log holds none."""
    for pattern, sentence in FLYWAY_REFUSALS:
        found = pattern.search(log_text)
        if found:
            return sentence.format(found.group(1))
    if "Validate failed" in log_text:
        line = next(line for line in log_text.splitlines() if "Validate failed" in line)
        return f"Flyway refused the restored schema: {line.strip()[-300:]}"
    return None


def upgrade_seeder(paths: Iterable[str]) -> str | None:
    """The seeder among a tree's e2e files: the `upgrade` group's first member in filename order, or None."""
    members = sorted(p for p in paths if p.endswith(".e2e.test.ts") and group_of(Path(p)) == UPGRADE_GROUP)
    return members[0] if members else None


def upgrade_record_problems(raw: object) -> list[str]:
    """Every problem with `raw` as the upgrade record, or none — the orchestrator's half of the record's contract.

    The same shape `src/web/src/test/upgradeRecord.ts`'s `upgradeRecordProblems` checks, structural only: the reader then
    asserts the content. Both are held to `src/web/src/test/upgradeRecord.fixture.json` (accepted, and every edit listed
    there refused with exactly one problem). Every list must be non-empty — a record without an item of one application
    would make the reader's "every item" vacuous for it.
    """
    problems: list[str] = []
    is_obj = lambda v: isinstance(v, dict)  # noqa: E731

    def string(o: dict, key: str, at: str) -> None:
        if not isinstance(o.get(key), str) or not o[key]:
            problems.append(f"{at}.{key} is not a non-empty string")

    def number(o: dict, key: str, at: str) -> None:
        if isinstance(o.get(key), bool) or not isinstance(o.get(key), (int, float)):
            problems.append(f"{at}.{key} is not a number")

    def obj(o: dict, key: str, at: str) -> dict | None:
        if is_obj(o.get(key)):
            return o[key]
        problems.append(f"{at}.{key} is not an object")
        return None

    def items(o: dict, key: str, at: str) -> list[dict]:
        value = o.get(key)
        if not isinstance(value, list) or not value:
            problems.append(f"{at}.{key} is not a non-empty list")
            return []
        out = []
        for i, item in enumerate(value):
            if not is_obj(item):
                problems.append(f"{at}.{key}[{i}] is not an object")
            out.append(item if is_obj(item) else {})
        return out

    def stream(o: dict, at: str) -> None:
        size = o.get("size")
        if isinstance(size, bool) or not isinstance(size, int) or size <= 0:
            problems.append(f"{at}.size is not a positive integer")
        if not isinstance(o.get("sha256"), str) or not SHA256_HEX.match(o["sha256"]):
            problems.append(f"{at}.sha256 is not a hex SHA-256")

    def media(o: dict, key: str, at: str) -> None:
        for i, m in enumerate(items(o, key, at)):
            string(m, "mediaId", f"{at}.{key}[{i}]")
            stream(m, f"{at}.{key}[{i}]")

    def files(o: dict, key: str, at: str) -> None:
        for i, f in enumerate(items(o, key, at)):
            here = f"{at}.{key}[{i}]"
            for k in ("nodeId", "name", "type"):
                string(f, k, here)
            number(f, "modifiedAt", here)
            stream(f, here)

    if not is_obj(raw):
        return ["the record is not a JSON object"]
    record: dict = raw  # type: ignore[assignment]
    if record.get("version") not in UPGRADE_RECORD_VERSIONS or isinstance(record.get("version"), bool):
        return [f"version {json.dumps(record.get('version'))} is not one the reader knows "
                f"(it reads {', '.join(map(str, UPGRADE_RECORD_VERSIONS))})"]
    owner = obj(record, "owner", "record")
    if owner is not None:
        for k in ("email", "password", "secretKey", "tenantId"):
            string(owner, k, "owner")
    string(record, "scopeId", "record")

    memory = obj(record, "memory", "record")
    if memory is not None:
        for k in ("nodeId", "name", "place"):
            string(memory, k, "memory")
        date = obj(memory, "date", "memory")
        if date is not None:
            if date.get("mode") != "range":
                problems.append('memory.date.mode is not "range"')
            string(date, "from", "memory.date")
            string(date, "to", "memory.date")
        cover = obj(memory, "cover", "memory")
        if cover is not None:
            # `blobId`: the uploaded image a cover is set by now; `mediaId`: a seed from before, which named one of its
            # photos. One of the two, as upgradeRecord.ts reads it (the fixture holds the two to each other).
            if not any(isinstance(cover.get(k), str) and cover[k] for k in ("blobId", "mediaId")):
                problems.append("memory.cover names neither a blob nor a medium")
            crop = obj(cover, "crop", "memory.cover")
            if crop is not None:
                for k in ("x", "y", "width", "height"):
                    number(crop, k, "memory.cover.crop")
        media(memory, "media", "memory")
        chapter = obj(memory, "chapter", "memory")
        if chapter is not None:
            string(chapter, "nodeId", "memory.chapter")
            string(chapter, "name", "memory.chapter")
            media(chapter, "media", "memory.chapter")

    section = obj(record, "files", "record")
    if section is not None:
        string(section, "folderId", "files")
        string(section, "name", "files")
        files(section, "files", "files")

    box = obj(record, "dropBox", "record")
    if box is not None:
        string(box, "nodeId", "dropBox")
        string(box, "name", "dropBox")
        for i, guest in enumerate(items(box, "guests", "dropBox")):
            string(guest, "name", f"dropBox.guests[{i}]")
            string(guest, "folderId", f"dropBox.guests[{i}]")
            files(guest, "files", f"dropBox.guests[{i}]")

    share = obj(record, "share", "record")
    if share is not None:
        for k in ("shareId", "fragmentKey", "name"):
            string(share, k, "share")
        for i, m in enumerate(items(share, "media", "share")):
            stream(m, f"share.media[{i}]")
    return problems


def git(*args: str, cwd: Path = REPO) -> str:
    """One git command against this checkout; its stdout, stripped. A failure ends the run naming the command."""
    result = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit(f"[e2e] git {' '.join(args)} failed: {result.stderr.strip()[-400:]}")
    return result.stdout.strip()


class Predecessor(NamedTuple):
    """The build the proof upgrades FROM: how it was asked for, the commit it resolved to, its pin file, its build."""
    asked: str      # the ref as given, or how the default chose it
    commit: str
    pins: str       # its `scripts/applied_migrations.sha256`
    build: Build


def default_predecessor_ref(root: Path = REPO) -> tuple[str, str]:
    """(the default predecessor's ref, how it was chosen): [default_predecessor] fed with this checkout's pin file and
    the release tags `HEAD^1` contains. Raises ValueError where that refuses. `chart_upgrade_check.py` resolves its
    predecessor here too, so the chart's upgrade check and this proof never compare against different builds. `root`
    is another checkout only for `upgrade_proof_selftest.py`'s fabricated history."""
    release = read_release((root / PIN_FILE).read_text())
    tags = git("tag", "--merged", "HEAD^1", "--list", "v*", cwd=root).splitlines() if release != "none" else []
    ref = default_predecessor(release, tags)
    return ref, f"{ref} (the default while `release: {release}`)"


def candidate_major(root: Path = REPO) -> int:
    """The major this checkout builds: `VERSION`, which holds the major alone (ADR 0057 § 1)."""
    raw = (root / "VERSION").read_text().strip()
    if not re.fullmatch(r"[0-9]+", raw):
        raise ValueError(f"VERSION must hold the major alone, got {raw!r}")
    return int(raw)


def major_entry_ref(major: int | None = None, root: Path = REPO) -> str | None:
    """The major entry of a release of `major` (default: this checkout's, [candidate_major]) as a tag, or None:
    [major_entry] fed with EVERY `v*` tag of the repository — `git tag --list`, not `--merged`, so a fix release on a
    `release/<old>.x` branch counts. While the pin file says `release: none` no tag is read and there is none, the
    same switch [default_predecessor_ref] makes. `release_assets.py --major-entry` (the tools image's stamp) and
    `chart_upgrade_check.py --upgrade-major-entry` resolve it here: ONE derivation, so the gate never admits an entry
    the pipeline did not prove. A checkout without the tags (a shallow one) finds none — CI checks out with
    `fetch-depth: 0`, which fetches every tag."""
    release = read_release((root / PIN_FILE).read_text())
    tags = git("tag", "--list", "v*", cwd=root).splitlines() if release != "none" else []
    return major_entry(candidate_major(root) if major is None else major, tags)


def major_entry_proof_ref(root: Path = REPO) -> tuple[str | None, str]:
    """(the second predecessor's ref or None, the line saying which and why): [second_predecessor] of this checkout's
    default predecessor and its major entry. Raises ValueError where either refuses. The upgrade proof's
    `--upgrade-major-entry` and the chart upgrade check's resolve it here alike."""
    default, _ = default_predecessor_ref(root)
    major = candidate_major(root)
    entry = major_entry_ref(major, root)
    second = second_predecessor(default, entry)
    if second is not None:
        return second, (f"{second} (major {major}'s entry: the newest release of major {major - 1} among every tag, "
                        f"beside the default predecessor {default})")
    if entry is None:
        release = read_release((root / PIN_FILE).read_text())
        cause = (f"the pin file says `release: {release}`" if release == "none" else
                 "there is no major before it" if major < 1 else f"no release tag of major {major - 1} exists")
        return None, f"major {major} has no entry ({cause}), so there is no second predecessor"
    return None, f"major {major}'s entry {entry} is the default predecessor, which the default run covers"


def resolve_predecessor(ref: str | None) -> tuple[str, str]:
    """(how the predecessor was chosen, its commit). Refuses a ref that is no commit, and one WITHOUT the seeder — a
    predecessor older than the seeder cannot seed anything (Decision 2 of cr-upgrade-proof)."""
    if ref is None:
        try:
            ref, asked = default_predecessor_ref()
        except ValueError as e:
            raise SystemExit(f"[e2e] UPGRADE PROOF: {e}") from e
    else:
        asked = ref
    found = subprocess.run(["git", "rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}"], cwd=str(REPO),
                           capture_output=True, text=True)
    if found.returncode != 0:
        raise SystemExit(f"[e2e] UPGRADE PROOF: the predecessor {asked} names no commit of this repository")
    commit = found.stdout.strip()
    seeder = upgrade_seeder(predecessor_web_sources(commit))
    if seeder is None:
        raise SystemExit(
            f"[e2e] UPGRADE PROOF: the predecessor {asked} = {commit[:12]} carries no seeder (no `{UPGRADE_GROUP}.*` "
            f"e2e group under the web client's src/), so it cannot seed an instance to upgrade. A predecessor must be a commit at or "
            f"after the one that added the seeder; name one with --upgrade-from <ref>."
        )
    return asked, commit


def predecessor_web_sources(commit: str) -> list[str]:
    """Every path under a commit's web `src/`, in either layout (ADR 0106 decision 7): the predecessor may be a commit
    from before the code surfaces moved under `src/`, so both places are listed and the one that exists answers."""
    return git("ls-tree", "-r", "--name-only", commit, "--",
               *(f"{path}/src" for path in repo_layout.surface_paths(repo_layout.WEB))).splitlines()


def prepare_predecessor(ref: str | None, workdir: Path) -> Predecessor:
    """A detached worktree of the predecessor in the run directory, built as its own CI would: its web dependencies
    installed (which generates its client — `web`'s `prepare`) and its fast-jar built. Its suites run from there, never
    from this tree ([Build]); [remove_predecessor] deletes it once the group has run.

    The install is a plain install of the predecessor's lockfile, not `pnpm_install_no_deprecated.py`: that lockfile
    passed the gate when it was the candidate, and a package deprecated SINCE would turn an upgrade proof red for a
    reason that is not the upgrade. It is the predecessor's own package manager: `pnpm install --frozen-lockfile` where
    it carries a `pnpm-lock.yaml`, `npm ci` where it predates the move to pnpm (2026-11-03) and carries a
    `package-lock.json` — so a proof across that move installs each side as its own CI did. Its output goes to
    `predecessor-install.log` / `predecessor-gradle.log` in the run directory.
    """
    asked, commit = resolve_predecessor(ref)
    tree = workdir / "predecessor"
    log(f"upgrade proof: predecessor {asked} = {commit[:12]}; a detached worktree at {shown(tree)}")
    git("worktree", "add", "--detach", str(tree), commit)
    # Removed however the run ends — a failed build, a `SystemExit` of a hook, a SIGTERM (which `main` turns into one) —
    # so no stray worktree stays registered; [remove_predecessor] removes it earlier on the ordinary path.
    atexit.register(remove_worktree, tree)
    # The predecessor's surfaces where ITS layout puts them, which may be the one before ADR 0106 stage 2.
    backend, web = repo_layout.surface_in(tree, repo_layout.BACKEND), repo_layout.surface_in(tree, repo_layout.WEB)
    install = (["pnpm", "install", "--frozen-lockfile"] if (tree / "pnpm-lock.yaml").is_file()
               else ["npm", "ci", "--no-audit", "--no-fund"])
    for label, cmd, cwd in (("install", install, tree),
                            ("gradle", ["./gradlew", "--no-daemon", "--console=plain", "quarkusBuild"], backend)):
        out = workdir / f"predecessor-{label}.log"
        log(f"upgrade proof: building the predecessor ({' '.join(cmd)}) -> {shown(out)}")
        with out.open("w") as handle:
            result = subprocess.run(cmd, cwd=str(cwd), stdout=handle, stderr=subprocess.STDOUT)
        if result.returncode != 0:
            tail(out)
            raise SystemExit(f"[e2e] UPGRADE PROOF: the predecessor's {label} step failed (exit {result.returncode})")
    jar = backend / JAR_IN_BACKEND
    if not jar.is_file() or not (web / "src" / "api" / "generated").is_dir():
        raise SystemExit(f"[e2e] UPGRADE PROOF: the predecessor built no jar at {shown(jar)} or generated no client")
    return Predecessor(asked, commit, git("show", f"{commit}:{PIN_FILE}"),
                       Build(f"predecessor {commit[:12]}", tree, jar))


def remove_predecessor(predecessor: Predecessor | None) -> None:
    """Delete the predecessor's worktree — before the run-level sweeps, which glob the run directory recursively."""
    if predecessor is not None:
        remove_worktree(predecessor.build.root)


def remove_worktree(tree: Path) -> None:
    """Remove a worktree this run added, and its registration. Idempotent: the second call finds nothing."""
    if tree.exists():
        log(f"upgrade proof: removing the predecessor's worktree {shown(tree)}")
        subprocess.run(["git", "worktree", "remove", "--force", str(tree)], cwd=str(REPO), capture_output=True, text=True)
        shutil.rmtree(tree, ignore_errors=True)
        subprocess.run(["git", "worktree", "prune"], cwd=str(REPO), capture_output=True, text=True)


def upgrade_members(predecessor: Predecessor, candidate_files: list[Path]) -> tuple[list[Path], list[Build]]:
    """The proof's one group: the predecessor's seeder, then the candidate's reader (the group's LAST member here)."""
    seeder = upgrade_seeder(p.relative_to(predecessor.build.web).as_posix() for p in discover(predecessor.build.web))
    readers = [p for p in candidate_files if group_of(p) == UPGRADE_GROUP]
    if seeder is None or len(readers) < 2:
        raise SystemExit(f"[e2e] UPGRADE PROOF: no seeder in the predecessor or no reader in this tree "
                         f"(the `{UPGRADE_GROUP}` group needs both)")
    return [predecessor.build.web / seeder, sorted(readers)[-1]], [predecessor.build, CANDIDATE]


def upgrade_verdict(predecessor: Predecessor, failures: list[tuple[str, str]], group_dir: Path,
                    reader_log: Path) -> list[str]:
    """The proof's own summary lines (Decision 5): which predecessor, both builds' highest migration, what changed
    between them, what the candidate reported, and the outcome with its cause named."""
    before, after = pinned_migrations(predecessor.pins), pinned_migrations((REPO / PIN_FILE).read_text())
    release = read_release((REPO / PIN_FILE).read_text())
    changed = changed_migrations(before, after)
    reading: dict = {}
    if (group_dir / UPGRADE_READING_FILE).is_file():
        reading = json.loads((group_dir / UPGRADE_READING_FILE).read_text())
    lines = [
        f"predecessor: {predecessor.asked} = {predecessor.commit[:12]}",
        f"highest migration: predecessor V{highest_migration(before)}, candidate V{highest_migration(after)}",
        f"migrations changed or removed since the predecessor: {', '.join(changed) or 'none'}",
        f"candidate's configSecretState: {reading.get('configSecretState', '(never read - the reader did not get there)')}",
    ]
    if not failures:
        # Said as it happened: with no migration between the two builds the candidate started on the restored schema
        # as it was, and a green proves the restore and the reading, not a migration.
        migrated = ("migrated the predecessor's backup up" if highest_migration(before) != highest_migration(after)
                    else "started on the predecessor's backup (no migration lies between the two builds)")
        lines.append(f"outcome: GREEN — the candidate {migrated} and opened all {reading.get('items', '?')} recorded "
                     f"item(s)")
        return lines
    cause = flyway_cause(reader_log.read_text(errors="replace")) if reader_log.is_file() else None
    if cause is None and reading.get("configSecretState") not in (None, "OK"):
        cause = (f"the candidate reports configSecretState {reading['configSecretState']} on the restored instance - the "
                 f"key that came with the backup is not the one its rows were sealed for")
    if cause is None and changed:
        cause = f"probably the changed migration(s) above ({len(changed)}); the failures below say what broke"
    lines.append(f"outcome: RED — {cause or failures[0][1]}")
    if release == "none":
        lines.append("before the first release (`release: none`) a baseline may change, so this red INFORMS and gates "
                     "nothing (src/backend/CLAUDE.md § Migrations)")
    return lines


def run_group(index: int, members: list[Path], workdir: Path, stamp: str,
              builds: list[Build] | None = None) -> list[tuple[str, str]]:
    """Run a group's members in order against one instance session. Returns every member's failure, if any.

    A group of ONE takes exactly the old path: one fresh database, no bucket override, no hook. `builds` names the
    build each member runs from — this checkout for all of them, unless the upgrade proof hands two.
    """
    builds = builds or [CANDIDATE] * len(members)
    name = group_of(members[0])
    inst = Instance(suite_database(index), workdir / f"config-secret-{index}.key",
                    group_bucket(index) if len(members) > 1 else None)
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
    for position, (path, build) in enumerate(zip(members, builds), start=1):
        reason = None
        if position > 1:
            try:
                inst = HOOKS[name](index, inst, group_dir, build)
            except HookFailure as e:
                reason = f"not run: the hook before it failed — {e}"
                log(f"HOOK FAILED before {member_name(path, build)}: {e}")
        reason = reason or run_member(index, position, path, inst, group_dir if len(members) > 1 else None, workdir,
                                      stamp, build)
        if reason:
            # Never short-circuit ACROSS groups; within one, a failed member makes every later member
            # meaningless — it would run against an instance whose precondition was never established.
            failures.append((member_name(path, build), reason))
            if position < len(members):
                for skipped, skipped_build in zip(members[position:], builds[position:]):
                    failures.append((member_name(skipped, skipped_build),
                                     f"not run: an earlier member of group `{name}` failed"))
            break
    return failures


def run_member(index: int, position: int, test_path: Path, inst: Instance, group_dir: Path | None,
               workdir: Path, stamp: str, build: Build = CANDIDATE) -> str | None:
    """Run one e2e file against its own fresh backend. Returns None on success, else a failure reason."""
    rel = test_path.relative_to(build.web).as_posix()
    tag = f"{index}" if position == 1 else f"{index}-{position}"
    log(f"=== {member_name(test_path, build)} ({inst.describe()}) ===")

    secret_file = inst.secret_file
    boot_log = workdir / f"backend-{tag}.log"
    canary_file = workdir / f"canaries-{tag}.json"
    pii_file = workdir / f"pii-{tag}.json"
    record_file = workdir / f"http-{tag}.jsonl"
    database = inst.database
    proc = boot(database, secret_file, boot_log, inst.bucket, build.jar)
    try:
        log(f"waiting up to {BOOT_SECS}s for the backend ({build.label}) to become ready")
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
            # side reads it back and sweeps. Absolute, because vitest runs with cwd=src/web/.
            "VEYQ_CANARY_FILE": str(canary_file),
            # The third needle set (ADR 0092 § 4): every address the suite signed up with, searched in the logs only.
            "VEYQ_PII_FILE": str(pii_file),
            # The HTTP half of the same seam: the suite records every request it made and every response it
            # got, this side reads the file back and sweeps it. Absolute, for the same reason.
            "VEYQ_HTTP_RECORD_FILE": str(record_file),
        })
        if group_dir is not None:
            # The seam between a group's members: whatever part A must hand part B (an actor's password, the id
            # of the Memory to reopen, the bytes to compare) goes through a directory that outlives the boot.
            env["VEYQ_GROUP_DIR"] = str(group_dir)
        result = subprocess.run(
            ["pnpm", "exec", "vitest", "run", rel, "--testTimeout", TEST_MS],
            cwd=str(build.web), env=env,
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


def prune_old_runs(keep: int) -> list[str]:
    """Delete the oldest `.e2e-out/<stamp>/` directories until `keep` remain (the new run's own, made right after, is one of
    them, so call this with `keep - 1` kept here). Only directories named by a unix-time stamp are touched - never a file or
    anything else a developer put in `.e2e-out/` - and an undeletable one is reported, not fatal. Returns the names removed."""
    if not RUN_ROOT.is_dir():
        return []
    runs = sorted((d for d in RUN_ROOT.iterdir() if d.is_dir() and d.name.isdigit() and not run_is_live(d)),
                  key=lambda d: int(d.name))
    doomed = runs[: max(0, len(runs) - keep)]
    removed: list[str] = []
    for d in doomed:
        try:
            shutil.rmtree(d)
            removed.append(d.name)
        except OSError as e:
            log(f"could not prune old run {d.name}: {e}")
    return removed


# A run's directory holds this file while the run lives — `<host> <pid>` — so a run beside it never prunes it.
LIVE_MARK = ".live"


def run_is_live(run_dir: Path) -> bool:
    mark = run_dir / LIVE_MARK
    return mark.is_file() and sweeps._owner_alive(mark.read_text())


def keep_runs() -> int:
    """`VEYQ_E2E_KEEP_RUNS` (default 2, at least 1): how many run directories survive, the new one included."""
    raw = os.environ.get("VEYQ_E2E_KEEP_RUNS", "2")
    try:
        return max(1, int(raw))
    except ValueError:
        raise SystemExit(f"[e2e] VEYQ_E2E_KEEP_RUNS must be a whole number, got {raw!r}")


def main(argv: list[str]) -> int:
    # `--resolve`: name the predecessor this proof would run from, as its last line `predecessor=<ref>` (empty: no run),
    # and start nothing — `pipeline.yml`'s `upgrade-proof` asks it before it builds anything, one leg per predecessor.
    resolve = RESOLVE_REQUEST in argv
    argv = [a for a in argv if a != RESOLVE_REQUEST]
    try:
        upgrade, upgrade_from, argv = upgrade_request(argv)
        if resolve and not upgrade:
            raise ValueError(f"{RESOLVE_REQUEST} names an upgrade proof's predecessor: pass it with --upgrade, "
                             f"--upgrade-from or {MAJOR_ENTRY_REQUEST}")
        if upgrade_from == MAJOR_ENTRY_REQUEST:
            upgrade_from, why = major_entry_proof_ref()
            log(f"upgrade proof from the major entry: {why}")
            if upgrade_from is None:
                log("no second upgrade proof to run (ADR 0085 decision 10) — nothing was started")
                if resolve:
                    print("predecessor=", flush=True)
                return 0
        if resolve:
            print(f"predecessor={upgrade_from if upgrade_from is not None else default_predecessor_ref()[0]}", flush=True)
            return 0
    except ValueError as e:
        raise SystemExit(f"[e2e] {e}") from e
    if upgrade and argv:
        raise SystemExit("[e2e] the upgrade proof runs the `upgrade` group of two builds and takes no test files")
    if not shutil.which("docker"):
        raise SystemExit("[e2e] docker is required (the bundled stack supplies the DB + object store)")
    if not JAR.is_file():
        raise SystemExit(f"[e2e] backend jar missing: {JAR}\nBuild it first: "
                         f"(cd {repo_layout.rel(repo_layout.BACKEND)} && ./gradlew quarkusBuild)")

    files = [normalize(a) for a in argv] if argv else discover()
    files = opted_in(files, named=bool(argv))
    groups = into_groups(files)
    if upgrade:
        # Refused before anything is built: a ref that is no commit, or one older than the seeder.
        resolve_predecessor(upgrade_from)
    # The stack first: the slot is claimed in its mariadb container, and the slot decides the names and ports below.
    compose_up()
    # One stamp for the whole run: it varies emails/usernames so a re-run against a warm limiter sees a new
    # invite recipient, and the per-file database makes cross-file collisions impossible anyway. Digits only —
    # the username pattern is [A-Za-z0-9_.-] with a 32-char cap. No live run on the stack holds the same one.
    slot, stamp = sweeps.claim_run_slot(DEV)
    atexit.register(sweeps.release_run_slot, DEV, slot)
    use_slot(slot)
    if slot:
        log(f"another run is live on this stack: this one is slot {slot} — databases {suite_database(1)}, …, "
            f"ports {PORT}/{ADMIN_PORT}")
    for port, variable, url in ((PORT, "VEYQ_PORT", READY_URL), (ADMIN_PORT, "VEYQ_ADMIN_PORT", f"{ADMIN_BASE}/api/setup")):
        if port_is_taken(url):
            raise SystemExit(
                f"[e2e] port {port} is already serving — another backend (an IntelliJ quarkusDev?) is running. "
                f"Stop it, or set {variable} to a free port."
            )

    log(f"running the upgrade proof (the `{UPGRADE_GROUP}` group of two builds), stamp={stamp}" if upgrade else
        f"running {len(files)} e2e file(s) in {len(groups)} group(s), stamp={stamp}")

    # Retained, not a tempdir: the boot log is itself a sweep surface (ADR 0053 § Decision 4), and a canary file
    # discarded on exit cannot be re-read when a sweep hit needs explaining. It is created BEFORE the store is
    # provisioned because the run's first backend boot ([provision_store]) writes its log here with the rest.
    pruned = prune_old_runs(keep_runs() - 1)
    if pruned:
        log(f"pruned {len(pruned)} older run director{'y' if len(pruned) == 1 else 'ies'} from {RUN_ROOT.relative_to(REPO)}/ "
            f"(keeping {keep_runs()}, VEYQ_E2E_KEEP_RUNS)")
    workdir = RUN_ROOT / stamp
    workdir.mkdir(parents=True, exist_ok=True)
    live_mark = workdir / LIVE_MARK
    live_mark.write_text(f"{socket.gethostname()} {os.getpid()}")
    atexit.register(lambda: live_mark.unlink(missing_ok=True))
    log(f"run artifacts (boot logs, canary files) land in {workdir.relative_to(REPO)}/")

    # The upgrade proof's predecessor is built BEFORE the statement log opens — minutes of installs and Gradle that a failed build
    # should not spend holding the shared database's statement log open — and removed after its group ran.
    predecessor = prepare_predecessor(upgrade_from, workdir) if upgrade else None
    builds: list[list[Build] | None] = [None] * len(groups)
    if predecessor is not None:
        members, member_builds = upgrade_members(predecessor, files)
        files, groups, builds = members, [members], [member_builds]

    # A `kill` (SIGTERM) or a closed terminal (SIGHUP) would otherwise end the process without running a single
    # `finally` — and the statement log's `finally` is what switches a shared database's query log back off.
    for signum in (signal.SIGTERM, signal.SIGHUP):
        signal.signal(signum, lambda received, _frame: sys.exit(128 + received))

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
        try:
            for index, (members, member_builds) in enumerate(zip(groups, builds), start=1):
                # Never short-circuit across groups: one broken suite must not hide the state of the others.
                failures.extend(run_group(index, members, workdir, stamp, member_builds))
        finally:
            remove_predecessor(predecessor)

        # The run-level sweeps: after EVERY group, so each log is complete (its JVM has exited) and a restored
        # instance's log is swept for the member before it too. They run even when suites failed — a failed suite's
        # log is the one most likely to hold a stack trace, and a hit there only adds to a run that is already red.
        canary_files = sorted(workdir.rglob("canaries-*.json"))
        # The second needle set, run-wide: every capability token any suite's recording shows it handled.
        tokens = run_tokens(workdir)
        log(f"capability tokens of the run: {len(tokens.by_needle)} ({kinds_of(tokens.by_needle.values())}), "
            f"{len(tokens.mailed)} first read out of a mail")
        # The third, `pii`: every address a suite signed up with (`recordPii`), searched in the boot and container
        # logs only — the dumps, the recordings, the statement log and the mailbox hold an account's address by design.
        pii_files = sorted(workdir.rglob("pii-*.json"))
        pii, pii_problems = sweeps.load_pii(pii_files)
        log(f"pii addresses of the run: {len(pii)} from {len(pii_files)} file(s)")
        run_failures = [(name, reason) for name, reason in (
            ("(run) server-log sweep", sweep_logs(workdir, canary_files, tokens, pii)),
            # Bounded below by the run's own start (the stamp is a unix time): a developer's earlier traffic on these
            # long-lived containers is not this run's to answer for.
            ("(run) container-log sweep", sweep_containers(workdir, canary_files, stamp, sentinel, tokens, pii)),
            ("(run) mailbox sweep", sweep_mailbox(workdir, canary_files, float(stamp), mail_sentinel, tokens)),
            # Its control, after the mailbox sweep retained the mails: each address is one the instance handled — in a
            # mail it sent, or in a suite's database. Zero addresses is a failure.
            ("(run) pii control", "; ".join(pii_problems) or sweeps.pii_control(
                pii_files, [*workdir.rglob("mailbox/*.eml"), *workdir.rglob("database-*.sql")],
                "the run's mails and database dumps")),
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
    if predecessor is not None:
        log("==================== upgrade proof ====================")
        for line in upgrade_verdict(predecessor, failures, workdir / f"group-1-{UPGRADE_GROUP}",
                                    workdir / "backend-1-2.log"):
            log(line)
    return 1 if failures or run_failures else 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except NoE2eFilesError as e:
        raise SystemExit(f"[e2e] NO FILES MATCHED: {e}") from e
