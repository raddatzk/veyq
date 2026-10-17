#!/usr/bin/env python3
"""Capture an instance's three durable legs, check a capture, and restore it onto an empty instance.

The executable form of [uc.admin.back-up-and-restore-instance] and
[ADR 0056](../docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md): an instance's durable
state is exactly **(a)** the database, **(b)** the object store's *contents*, **(c)** the config-secret key
file — and a procedure that captures fewer than three produces an instance that boots, serves pages, and is
still broken. The commands live in the docs site's [Backup and restore](../site/src/content/docs/run/backup-and-restore.md); this file is what
they call.

**Not a `validate_*.py`.** The trust-chain job runs that glob buildlessly on every push; this needs Docker, a
running database and a running store, so it could never join it. It is an **operator tool**, not a guard: it
makes no claim about the repository and nothing in CI depends on its exit code. The same split
`scripts/mutation_meta_check*.py` makes for the opposite reason (they need a built report).

**The capture is cold, and cold means the *writer* is stopped** (ADR 0056 §2). Stop the application; leave the
database and the store running — the capture reads through their own interfaces. That single act is the whole
consistency argument: with the app down the database cannot gain a row, so a row whose object was never
captured is structurally impossible, and there is no ordering to get right. This tool **cannot check that the
app is stopped** (it would have to know what "the app" is on this host) and does not pretend to; it is the one
precondition the operator owns.

Four subcommands, one file, because they share the layout, the manifest and the credential plumbing:

    python3 scripts/instance_backup.py capture  [--into DIR] (--key-into FILE | --key-beside-dump | --key-kept-apart)
                                                [--store-leg mirror|index] [--app-version R]
    python3 scripts/instance_backup.py verify   DIR [--key-from FILE] [--against-store]
    python3 scripts/instance_backup.py restore  DIR --domain <d> [--key-from FILE | --without-key]
                                                [--accept-missing-objects] [--record DIR]
    python3 scripts/instance_backup.py gate     (in the app pod; every input from its environment)

A backup is a directory, defaulting to `infra/backups/<UTC-ISO8601>/`:

    manifest.json      # domains, app+schema version, object count/bytes, the blob index, capture window,
                       # and where leg (c) went: `configSecret.placement` = separate | beside | absent
    database.sql       # mariadb-dump WITH DDL and the flyway history
    objects/           # the rclone mirror of the bucket, one file per object key

**Two formats, by the store leg.** What Compose and `veyqctl` write is the one above, `backupFormat` 2, unchanged.
An **index capture** (`--store-leg index`, `backupFormat` 3, ADR 0098 decision 4) has no `objects/`: its manifest's
`store.index` lists every object as `[key, size, md5 | null]` — one listing, the objects left in the store, whose own
versioning, replication or snapshots keep them. Its `verify` answers A4 from that index (and, `--against-store`, from
the live store); its `restore` never writes to the store: in place of A5's empty-store gate it checks A4 against the
live store before anything is written, and refuses a missing or changed object by name unless
`--accept-missing-objects` knowingly restores without it. An older tool refuses format 3 by its number.

**The cluster's gate** (`gate`, ADR 0098) runs in the app pod's init container `backup-gate`, in the `veyq-tools`
image, before the container that migrates — under `Recreate`, so the old app has stopped and the capture is cold by
construction. The **in-pod mode** it uses: the database by the app's own JDBC URL (`--db-url`, `VEYQ_BACKUP_DB_URL`,
`jdbc:mariadb://host[:port]/database` and no other form) and user, the client binaries and `rclone` on `PATH`, no
Docker and no fallback; the release named by the caller, never asked of Docker. It keeps one record on the backup
volume, `state.json` — the release whose data the database holds and the last request it served — and in this order:
a database holding no data records the target and passes (the first install, and the documented rollback, which
empties the database); the target and request the record's pass; then the refusals, which write nothing — a
downgrade, more than one major, a major entered from any release but the one this image bakes as tested (`majorEntry`
in `/opt/veyq/release.json`), a target that is no release or not this image's own, an unreadable record; otherwise an
index capture into `captures/<UTC>.partial/`, verified and renamed, the record moved, and the complete captures beyond
`--keep` pruned with every `.partial`. **It never reads the key**: leg (c) is recorded as `separate`, unread, so a
capture on the volume cannot hold the KEK. A capture `capture` writes for Compose is untouched by all of this.

**Leg (c) is captured, but never beside legs (a) and (b)** (ADR 0056 §1). ADR 0050's key file defends a leaked
database backup only while the key is somewhere else: the dump plus the key opens every tenant's SMTP password.
So `capture` writes the key to `--key-into FILE`, a destination of its own, and refuses one that resolves inside
the capture directory — before anything is dumped, so a refused run leaves no half-capture. `--key-beside-dump`
is the explicit, old placement (`config-secret.key` in the capture). `restore` reads the key from wherever the
manifest says it went; a `separate` key that is not supplied is refused at the gates, before any write, unless
`--without-key` knowingly accepts A2.

**Everything this tool writes is owner-only.** The whole run is under `umask 077`, and the capture root is
chmodded `0700` even when it pre-existed empty — which also means the key is born `0600` rather than copied and
chmodded after a world-readable moment. `verify` checks the root, the files the tool wrote, and the key per the
manifest. What it does **not** check is inside `objects/`: that tree is written by the rclone container through a
bind mount, so its owner and modes are the container's, not this process's. Measured with the pinned image: it
runs as root (the image declares no `USER`) under the container's own `umask 022`, so on a Linux filesystem the
mirror is `root:root`, files `0644`, directories `0755` — which this process can neither chmod nor rely on. On
Docker Desktop for macOS the bind mount maps the owner to the invoking user, with the same modes. Either way the
capture root's `0700` is what keeps another local user out of `objects/`, and it is checked.

**Owner-only means the application's owner for the one file `restore` writes outside the capture: the key.** It
takes the owner of the directory it lands in (`key_owner_plan`) — on the production recipe the volume Docker
seeded from the image as `10001:10001`, the uid the app runs as — because a key born `root:root 0600` there is a
key the app cannot read, and the instance then refuses invites and resets as if leg (c) were missing (inbox
#188). Only root can give a file away, so a non-root invoker facing a directory it does not own is refused at the
gates, before the first write, and told to run as root or to copy the key in through the app container.
`scripts/instance_backup_selftest.py` exercises every branch without Docker.

**Why the manifest is not decoration.** It is what lets `verify` and `restore` refuse before doing damage:
`restore` demands `--domain` and refuses unless it names a tenant this backup actually held (ADR 0056 §4 —
a restore onto a different hostname yields an instance nobody can reach, holding mailed links nobody can
follow), and `verify` answers A4 (*objects missing relative to the rows that reference them*) from the blob
index without needing a database at all. That check is the one thing a volume copy cannot offer, and it is the
whole reason the store leg is captured logically (ADR 0056 §3).

**`verify` is deliberately asymmetric, and the asymmetry is the read path's.** An object with no row is an
ordinary steady state — the row delete and its `veyq_store_reclaim` entry share one transaction and a single
sweep drains that list, so a capture taken between them holds objects whose rows are gone (ADR 0062) — so
orphans are reported and tolerated. (Abandoned upload sessions themselves are reclaimed, by
`UploadSessionPurge`.) A row with no object is
fatal: it presigns a valid URL that 404s, with no server-side error and a broken picture as the only symptom
(ADR 0056 § Context 2).

**Credentials never reach `argv`.** The store remote is defined entirely through `RCLONE_CONFIG_<REMOTE>_*` in
the child environment and the database password through `MYSQL_PWD`, both passed as bare `-e NAME` flags so
`ps` never sees a value and no config file is written. They are read from **this process's environment** (the
variables below), never from the database: the app keeps a store of your own sealed there under the config-secret
key, and the capture is cold, so nothing could open it.

**`sync` out, `copy` in — never the reverse.** `sync` deletes at the destination, which is right for a capture
directory (a re-run must not silently become the union of two captures) and catastrophic against a live
bucket. `restore` refuses a non-empty target outright rather than reconciling it (A5).

**What this does not do.** It does not collect the instance's garbage: abandoned upload sessions and orphaned
objects are captured as they are, because they are the running system's steady state and a restore is not
where they get collected. It does not change a domain (ADR 0056 §4). It never runs a migration itself: the
dump carries `flyway_schema_history`, so a restored instance holds the schema it was captured at, and a target
whose release is newer migrates it up at its next start — the same forward migration every upgrade runs. A
backup NEWER than the target is refused before anything is written (ADR 0056 §7). And it does not verify the
*database* leg beyond structure: the dump's rows are ciphertext this tool cannot read, by design.

**The store is a Server-Owner setting, not configuration** (ADR 0089), and the capture is cold, so this tool cannot ask
the app which store it runs: the operator tells it, for this run. Two ways, the first being the recipe's own case:

  the bundled store     The recipe's environment already holds its hint — `VEYQ_BUNDLED_STORE_ENDPOINT` (the
                        internal name, the very one this tool's container must reach) and
                        `VEYQ_BUNDLED_STORE_ADMIN_TOKEN` (compose: `GARAGE_ADMIN_TOKEN` of `infra/.env`). The S3 key
                        pair is DERIVED from that token, exactly as the backend derives it
                        ([derive_bundled_credentials]; `BundledStoreCredentialsTest` and this tool's self-test pin
                        one vector on both sides), so nothing is typed.
  a store of your own   `VEYQ_BACKUP_STORE_ENDPOINT` plus the key pair below — the credentials you entered in the
                        admin console, which the console never shows again and this tool therefore cannot read.

Environment:
    VEYQ_BACKUP_DB_PASSWORD         Database password. Required by `capture`, `restore` and `gate`.
    VEYQ_BACKUP_DB_URL              The in-pod mode's database, the app's JDBC URL, and its user (default root).
    VEYQ_BACKUP_DB_USER
    VEYQ_BACKUP_DIR, VEYQ_BACKUP_TARGET_RELEASE, VEYQ_BACKUP_REQUEST, VEYQ_BACKUP_KEEP
                                    The gate's volume, target, request and retention — the chart renders them.
    VEYQ_BUNDLED_STORE_ENDPOINT     The bundled store's S3 endpoint the *rclone container* must reach — an internal
    VEYQ_BUNDLED_STORE_ADMIN_TOKEN  name such as `http://garage:3900`, not a host-published port — and its admin
                                    token (environment only, never a flag).
    VEYQ_BACKUP_STORE_ENDPOINT      A store of your own: its endpoint as the container reaches it (wins over the
    VEYQ_BACKUP_STORE_ACCESS_KEY    bundled endpoint), and its key pair (both or neither; a pair wins over the
    VEYQ_BACKUP_STORE_SECRET_KEY    token).
    VEYQ_BACKUP_STORE_BUCKET        Default `blobs`, the bundled store's fixed bucket.
    VEYQ_BACKUP_STORE_REGION        Default `us-east-1`, the bundled store's fixed region.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import NamedTuple

REPO =Path(__file__).resolve().parent.parent
DEFAULT_COMPOSE = REPO / "infra" / "docker-compose.yml"
DEFAULT_BACKUP_ROOT = REPO / "infra" / "backups"

# Pinned by tag AND multi-arch index digest, like every other moving part of the bundled stack (mariadb:11.4,
# dxflrs/garage:v2.0.0, traefik:v3.6, axllent/mailpit:v1.20.0) — and DB_IMAGE to the very digest the compose files
# run, which scripts/validate_image_pins.py holds (one `repo:tag`, one digest). Pinning is what makes an OLD backup's restorability independent of whatever the
# host happens to carry years later — and neither tool is installed on a typical dev host anyway, so a host
# install would be a new prerequisite for the runbook and for CI.
RCLONE_IMAGE = "rclone/rclone:1.75.0@sha256:b06aed988cf5967de7c25be5925240983981c757f4ed1ac9d2fa659d51d60548"
DB_IMAGE = "mariadb:11.4@sha256:70cc072b29b4a89ae07abb2d4da2c64678a7f2dfe092751bb51c87d67dc1338b"

# The rclone remote's name. Only ever appears in `RCLONE_CONFIG_<NAME>_*` and in `<name>:<bucket>` — never in a
# config file, because none is written. rclone logs `NOTICE: Config file ... not found - using defaults` on
# every invocation and proceeds; that line on stderr is normal and is not an error.
REMOTE = "STORE"

MANIFEST_NAME = "manifest.json"
DUMP_NAME = "database.sql"
OBJECTS_DIR = "objects"
KEY_NAME = "config-secret.key"

# Where leg (c) went, as `manifest.json` records it (ADR 0056 §1). `separate` is the default and the only one
# that keeps ADR 0050's leaked-backup defence true; `beside` is the explicit `--key-beside-dump`; `absent` is a
# capture that found no key to take.
SEPARATE, BESIDE, ABSENT = "separate", "beside", "absent"
PLACEMENTS = (SEPARATE, BESIDE, ABSENT)

# Bumped only when an older directory can no longer be read by this file. `verify` and `restore` refuse an
# unknown one rather than guessing, because guessing is how a restore half-works. 2: `configSecret.present`
# became `configSecret.placement`.
BACKUP_FORMAT = 2
# An INDEX capture (ADR 0098 decision 4): no `objects/`, `store.leg = "index"` and `store.index` instead. A capture
# of the store's objects (Compose, `veyqctl`) stays format 2, byte for byte. Not every capture is bumped to 3, because
# `veyqctl` restores with the RUNNING release's tool, which after a Compose rollback is the older release's: it must
# keep reading what Compose writes. An index capture is the one shape an older tool must refuse, and this number is
# what makes it refuse instead of failing at a missing `objects/`.
INDEX_FORMAT = 3
FORMATS = (BACKUP_FORMAT, INDEX_FORMAT)
MIRROR, INDEX = "mirror", "index"

# Owner-only, for everything this process creates. Set once in [main], so no write in any subcommand can be born
# group- or world-readable — including the key, which a copy-then-chmod would leave readable for a moment.
OWNER_ONLY_UMASK = 0o077


class BackupError(RuntimeError):
    """A refusal or a failure. Always carries what to do about it — an operator is reading this at a shell."""


def log(msg: str) -> None:
    print(f"[backup] {msg}", flush=True)


def utc_stamp() -> str:
    """`2026-08-10T07-42-11Z` — ISO 8601 with `:` swapped out, because a colon is not a portable path char."""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


# --------------------------------------------------------------------------------------------------------
# The database leg
# --------------------------------------------------------------------------------------------------------


# The tables a freshly installed or freshly configured instance legitimately holds rows in, and therefore the ones the
# emptiness gate must not read as data. Each is excluded for its own reason — see [Database.populated_tables] — and the
# list is short and named rather than pattern-matched, because "which tables are not data" is a judgement about
# this schema and not something a regex can decide.
NOT_DATA = (
    "flyway_schema_history",
    "veyq_instance",
    "veyq_store_setting",
    "veyq_capability_switch",
    "veyq_external_service",
    "veyq_release_check",
    "veyq_release_notice",
)
NOT_DATA_SQL = ", ".join(f"'{t}'" for t in NOT_DATA)


class DbUrl(NamedTuple):
    host: str
    port: int
    name: str


class StoreRow(NamedTuple):
    """The plaintext of `veyq_store_setting`'s active row — what a tool without the KEK can know of the store."""
    bundled: bool
    endpoint: str | None
    bucket: str | None
    region: str | None


# `jdbc:mariadb://host[:port]/database[?…]` — the one shape the chart renders for the app (one helper for the app's
# QUARKUS_DATASOURCE_JDBC_URL and the gate's VEYQ_BACKUP_DB_URL) and the one an operator's `externalDatabase.jdbcUrl`
# is documented in. A multi-host list (`h1,h2`), a failover scheme (`jdbc:mariadb:replication:`) or another driver's
# URL is refused, naming the form: guessing which host to dump is how a backup reads a replica that lags.
JDBC_URL = re.compile(r"jdbc:mariadb://([A-Za-z0-9._-]+|\[[0-9A-Fa-f:.]+\])(?::(\d{1,5}))?/([A-Za-z0-9_$-]+)(?:\?.*)?")


def parse_jdbc_url(url: str) -> DbUrl:
    """The database the app reaches, read from its JDBC URL. The query string is never echoed: it may carry a password."""
    match = JDBC_URL.fullmatch(url.strip())
    if not match:
        shown = url.split("?", 1)[0] + ("?…" if "?" in url else "")
        raise BackupError(f"the database URL {shown!r} is not of the form jdbc:mariadb://host[:port]/database[?…], the "
                          f"one this tool parses — a multi-host URL or another driver's is refused rather than guessed at")
    host, port, name = match.groups()
    return DbUrl(host.strip("[]"), int(port) if port else 3306, name)


class Database:
    """The database leg's three connection modes — the runbook has exactly three audiences and no more.

    **compose** (default) — `docker compose -f <file> exec -T <service> …`, the bundled stack, the form
    `scripts/run_web_e2e.py` already proves works against `mariadb:11.4`.

    **direct** (`--db-host`) — an operator's own database. The client binary is preferred from the host `PATH`
    and otherwise run inside the pinned [DB_IMAGE], because neither `mariadb-dump` nor `mysqldump` is present
    on a typical dev host — so a host install cannot be a prerequisite of the procedure.

    **pod** (`--db-url`, ADR 0098) — inside the cluster, the database the app's own JDBC URL names, with the client
    binaries of the `veyq-tools` image on `PATH`. There is no Docker in a pod, so a missing binary is a refusal naming
    it, never a fallback.
    """

    def __init__(self, args: argparse.Namespace, password: str) -> None:
        self.name = args.db_name
        self.user = args.db_user
        self.password = password
        self.host = args.db_host
        self.port = args.db_port
        self.compose_file = Path(args.compose_file)
        self.service = args.db_service
        self.image = args.db_image
        self.network = args.docker_network
        self.mode = "direct" if args.db_host else "compose"
        url = getattr(args, "db_url", None)
        if url:
            self.host, self.port, self.name = parse_jdbc_url(url)
            self.mode = "pod"

    def _wrap(self, binary: str) -> list[str]:
        """The invocation prefix that puts `binary` in front of a database, with MYSQL_PWD in its environment.

        `-e MYSQL_PWD` is passed WITHOUT a value on purpose: both `docker compose exec` and `docker run` then
        take it from this process's environment, so the password is in no `argv` anywhere. `-i` keeps stdin
        attached, which `restore` needs to feed the dump.
        """
        if self.mode == "compose":
            return ["docker", "compose", "-f", str(self.compose_file), "exec", "-T",
                    "-e", "MYSQL_PWD", self.service, binary]
        if self.mode == "pod":
            if not shutil.which(binary):
                raise BackupError(f"{binary} is not on PATH. The in-pod mode (--db-url) runs the client binaries of the "
                                  f"veyq-tools image and has no Docker to fall back to — run it in that image.")
            return [binary]
        if shutil.which(binary):
            return [binary]
        if not self.network:
            raise BackupError(
                f"--db-host was given but {binary} is not on PATH, so the client must run in a container — "
                f"which needs a docker network that can reach {self.host}. Pass --docker-network "
                f"(list them with: docker network ls)."
            )
        return ["docker", "run", "--rm", "-i", "--network", self.network, "-e", "MYSQL_PWD", self.image, binary]

    def _conn(self) -> list[str]:
        args = [f"-u{self.user}"]
        if self.mode in ("direct", "pod"):
            args += ["-h", self.host, "-P", str(self.port)]
        return args

    def _env(self) -> dict[str, str]:
        return {**os.environ, "MYSQL_PWD": self.password}

    def query(self, sql: str) -> list[list[str]]:
        """Rows as raw columns. `-N` drops the header, `--batch` makes it TAB-separated and NULL-explicit."""
        cmd = self._wrap("mariadb") + self._conn() + ["-N", "--batch", "-e", sql, self.name]
        proc = subprocess.run(cmd, capture_output=True, text=True, env=self._env())
        if proc.returncode != 0:
            raise BackupError(f"query failed against {self.describe()}: {proc.stderr.strip()[-500:]}")
        return [line.split("\t") for line in proc.stdout.splitlines() if line]

    def dump(self, target: Path) -> None:
        """A FULL dump: DDL and data, including `flyway_schema_history`.

        Deliberately **not** the `--no-create-info` form the canary sweep uses (`run_web_e2e.py`): that one
        exists to compare literals, this one to restore. The history travelling with the dump is what makes UC
        step 7 true — the restored instance boots at the version it was captured at, so
        `quarkus.flyway.migrate-at-start=true` is a no-op rather than a re-derivation against an empty schema.

        And deliberately **without** `--databases`: that form bakes `CREATE DATABASE`/`USE <source name>` into
        the file, which would make the target database's name a property of the backup. The restore rehearsal
        loads into a differently-named database on purpose, and an operator restoring onto fresh hardware has
        no reason to be bound to the old name either.
        """
        cmd = self._wrap("mariadb-dump") + self._conn() + [
            "--single-transaction", "--default-character-set=utf8mb4", self.name,
        ]
        with target.open("wb") as out:
            proc = subprocess.run(cmd, stdout=out, stderr=subprocess.PIPE, env=self._env())
        if proc.returncode != 0:
            target.unlink(missing_ok=True)
            raise BackupError(f"mariadb-dump of {self.describe()} failed: {proc.stderr.decode(errors='replace').strip()[-500:]}")

    def load(self, source: Path) -> None:
        cmd = self._wrap("mariadb") + self._conn() + [self.name]
        with source.open("rb") as body:
            proc = subprocess.run(cmd, stdin=body, capture_output=True, env=self._env())
        if proc.returncode != 0:
            raise BackupError(f"loading the dump into {self.describe()} failed: {proc.stderr.decode(errors='replace').strip()[-500:]}")

    def table_count(self) -> int:
        """How many tables the target database holds."""
        rows = self.query(
            f"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '{self.name}';"
        )
        return int(rows[0][0])

    def populated_tables(self) -> list[tuple[str, int]]:
        """Every table in the target that holds DATA, and how much — `[]` means the instance is empty (A5).

        **Empty means "holds no data", not "holds no tables", and that changed for a reason.** Until ADR 0067 a
        restore target was prepared by a compose sidecar that made the store usable while the database stayed
        untouched, so *zero tables* was a shape an operator could actually produce. With the backend as the only
        thing that can provision a store, the two preconditions became mutually unreachable: the bucket exists
        only after the app has started, and the same start migrates the schema —
        `quarkus.flyway.migrate-at-start=true` carries no profile gate, so the app has no mode that does one
        without the other. A freshly installed, freshly migrated instance IS the shape a restore targets now,
        and refusing it was always the wrong reading of A5.

        **A5's purpose is untouched: nothing here accepts a POPULATED instance.** The predicate is every table
        holding zero rows, with these exclusions, each for a stated reason:

          - `flyway_schema_history` is the SCHEMA's own record, not data. A migrated instance necessarily has
            rows here and an unmigrated one cannot be a target at all.
          - `veyq_instance` is the `id = 1` singleton that "exists from first boot" (its entity KDoc), holding
            the public instance salt. A fresh install always has exactly this one row, and the dump replaces it
            wholesale — see below.
          - `veyq_store_setting` is the object-store setting (ADR 0089): one row once the Server Owner has chosen a
            store in the console, and no data of anybody's — a Server Owner who connected the new install's store
            before restoring (which is the order the restore runbook gives, so the bundled bucket exists) must not
            find the restore refused for it. The dump replaces it wholesale too: the restored setting wins, its
            secrets sealed under the restored config-secret key.
          - `veyq_capability_switch` is the capability switches (ADR 0088 decision 12): one row per register entry
            the Server Owner switched off in the console, instance configuration and no member's data. A switch set
            on the new install before the restore must not refuse it either; the dump replaces the table, so the
            restored instance's switches win.
          - `veyq_external_service` is the outside services a member's browser calls (ADR 0088 decision 7, ADR
            0089): instance configuration and no member's data, and unlike the two above it is never empty — its
            migration seeds the three defaults (map tiles, place search, the breached-password range API), so every
            fresh install holds rows here. The dump replaces it wholesale, so the restored instance's services win.
          - `veyq_release_check` and `veyq_release_notice` are the release check's switch and what it found (ADR 0097):
            the switch is a seeded singleton, and a stamped install checks on its own from its first start, so a
            fresh target can hold notices before anyone restores onto it. Neither is anyone's data; the dump replaces
            both, so the restored instance's switch and notices win.

        The table set is read out of `information_schema` rather than listed here, so a table a future migration
        adds is covered without anyone remembering to add it (changes/README.md § a guard derives its anchor set
        from the code).

        **And the load onto an already-migrated schema is safe because the restore drops every table first**
        ([drop_all_tables], ADR 0056 §7). [dump] runs `mariadb-dump` with `--add-drop-table` on by default
        (`mariadb-dump --help` on the pinned mariadb:11.4: *"Defaults to on; use --skip-add-drop-table to
        disable"*), but that drops and recreates only the tables the dump NAMES — so a table a later migration
        added to the target, absent from an older backup, would survive under the backup's older history and the
        next start would fail re-creating it. Dropping is safe only because this gate has just proven the target
        holds no data.
        """
        rows = self.query(
            f"SELECT table_name FROM information_schema.tables WHERE table_schema = '{self.name}' "
            f"AND table_type = 'BASE TABLE' AND table_name NOT IN ({NOT_DATA_SQL}) ORDER BY table_name;"
        )
        names = [r[0] for r in rows if r and r[0]]
        if not names:
            return []
        # COUNT(*), never information_schema.table_rows: that column is an ESTIMATE for InnoDB and can read 0
        # for a table holding rows, which would turn this gate into one that lets a populated instance through.
        counts = self.query(
            " UNION ALL ".join(f"SELECT '{n}', COUNT(*) FROM `{n}`" for n in names) + ";"
        )
        return [(r[0], int(r[1])) for r in counts if len(r) > 1 and int(r[1]) > 0]

    def base_tables(self) -> list[str]:
        """Every base table of the target — the same `information_schema` derivation as [populated_tables], with
        no exclusions, so a table a future migration adds is covered with nothing to maintain."""
        rows = self.query(
            f"SELECT table_name FROM information_schema.tables WHERE table_schema = '{self.name}' "
            f"AND table_type = 'BASE TABLE' ORDER BY table_name;"
        )
        return [r[0] for r in rows if r and r[0]]

    def drop_all_tables(self) -> int:
        """Drop every base table of the target, in one session with foreign-key checks off (ADR 0056 §7).

        Called only after the A5 gate proved the target holds no data and the version gate accepted the backup:
        the target's schema is then the one thing it holds, and the dump brings its own.
        """
        names = self.base_tables()
        if names:
            self.query("SET FOREIGN_KEY_CHECKS = 0; DROP TABLE " + ", ".join(f"`{n}`" for n in names) + ";")
        return len(names)

    def domains(self) -> list[str]:
        return sorted(row[0] for row in self.query("SELECT domain FROM veyq_tenant;"))

    def schema_version(self) -> str | None:
        """The Flyway version the instance is at. Ordered by `installed_rank`, NOT by `version`.

        `version` is a VARCHAR, so `MAX(version)` is lexicographic and answers `9` on a schema at `44` —
        measured against this instance, not assumed.
        """
        rows = self.query(
            "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL "
            "ORDER BY installed_rank DESC LIMIT 1;"
        )
        return rows[0][0] if rows else None

    def blob_index(self) -> list[list]:
        """`[[storage_key, chunk_count], …]` — the row side of A4, compact enough to live in the manifest.

        The object keys are `<storage_key>/<i>` for `i` in `0 … chunk_count-1` (measured: a blob with
        `chunk_count = 70` owns exactly the keys `0 … 69`). Only `veyq_blob` and `veyq_upload_session` carry a
        `storage_key` in the whole migration set, and an open upload session's chunks are by definition
        incomplete — so only completed blobs are the *required* set. Sessions are counted, not required.
        """
        return [[row[0], int(row[1])] for row in
                self.query("SELECT storage_key, chunk_count FROM veyq_blob ORDER BY storage_key;")]

    def open_upload_sessions(self) -> int:
        return int(self.query("SELECT COUNT(*) FROM veyq_upload_session;")[0][0])

    def store_setting(self) -> StoreRow | None:
        """The store in use, as far as its PLAINTEXT says (ADR 0089): the active row (`id = 1`) or None when no store was
        chosen. A bundled row names nothing but `bundled` — its endpoint and bucket are the bundled-store hint of the
        environment (measured, `StoreHandler.build`: `StoreSettingRow(true, null, …)`); a store of the operator's own
        names its endpoint, bucket and region here, while its secret key is sealed under the config-secret key."""
        rows = self.query("SELECT bundled, endpoint, bucket, region FROM veyq_store_setting WHERE id = 1;")
        if not rows:
            return None
        bundled, endpoint, bucket, region = (rows[0] + ["NULL"] * 4)[:4]
        plain = [None if value == "NULL" else value for value in (endpoint, bucket, region)]
        return StoreRow(bundled in ("1", "TRUE", "true"), *plain)

    def describe(self) -> str:
        where = f"{self.host}:{self.port}" if self.mode in ("direct", "pod") else f"compose service {self.service}"
        return f"database {self.name} ({where})"


# --------------------------------------------------------------------------------------------------------
# The store leg
# --------------------------------------------------------------------------------------------------------


_BUNDLED_ACCESS_KEY_INFO = b"veyq/v1/bundled-store/access-key-id"
_BUNDLED_SECRET_KEY_INFO = b"veyq/v1/bundled-store/secret-access-key"


def _hkdf_sha256(ikm: bytes, info: bytes, length: int) -> bytes:
    """HKDF-SHA256 (RFC 5869) with an empty salt — the same extract-then-expand as `ConfigSecretSeal.hkdfSha256`."""
    prk = hmac.new(b"\0" * hashlib.sha256().digest_size, ikm, hashlib.sha256).digest()
    out, block, counter = b"", b"", 1
    while len(out) < length:
        block = hmac.new(prk, block + info + bytes([counter]), hashlib.sha256).digest()
        out += block
        counter += 1
    return out[:length]


def derive_bundled_credentials(admin_token: str) -> tuple[str, str]:
    """The bundled store's S3 key pair, derived from its admin token (ADR 0089 decision 3) — `GK` + 24 hex for the id,
    64 hex for the secret, the shapes Garage accepts. A change here without `BundledStoreCredentials` (backend, core)
    makes every bundled capture fail its first rclone call; one vector pins both sides."""
    ikm = admin_token.encode("utf-8")
    return ("GK" + _hkdf_sha256(ikm, _BUNDLED_ACCESS_KEY_INFO, 12).hex(),
            _hkdf_sha256(ikm, _BUNDLED_SECRET_KEY_INFO, 32).hex())


class Store:
    """The object store leg, driven through `rclone` — invoked, never vendored (ADR 0056 §3).

    Default is the pinned container on the stack's own docker network, talking to the store's **internal**
    endpoint; `--rclone-binary` is the escape hatch for an operator with a host install and their own remote.
    A container is the default rather than the fallback because `rclone` is on neither a typical dev host nor
    a GitHub runner, so a host install would be a new prerequisite for both the runbook and CI.
    """

    def __init__(self, args: argparse.Namespace) -> None:
        self.endpoint = args.store_endpoint
        self.access_key = args.store_access_key
        self.secret_key = args.store_secret_key
        self.bucket = args.store_bucket
        self.region = args.store_region
        self.image = args.rclone_image
        self.binary = args.rclone_binary
        self.network = args.docker_network

    def _config(self) -> dict[str, str]:
        return {
            f"RCLONE_CONFIG_{REMOTE}_TYPE": "s3",
            # `Other` is the generic S3 provider — measured working against Garage v2.0.0. It is also the
            # correct value for "any S3-compatible store", which is the whole point of capturing logically
            # (ADR 0056 §3: the backup restores onto a store that is not the one it came from).
            f"RCLONE_CONFIG_{REMOTE}_PROVIDER": "Other",
            f"RCLONE_CONFIG_{REMOTE}_ENDPOINT": self.endpoint,
            f"RCLONE_CONFIG_{REMOTE}_ACCESS_KEY_ID": self.access_key,
            f"RCLONE_CONFIG_{REMOTE}_SECRET_ACCESS_KEY": self.secret_key,
            f"RCLONE_CONFIG_{REMOTE}_REGION": self.region,
            f"RCLONE_CONFIG_{REMOTE}_FORCE_PATH_STYLE": "true",
        }

    def _run(self, rclone_args: list[str], mount: Path | None = None) -> subprocess.CompletedProcess:
        config = self._config()
        env = {**os.environ, **config}
        if self.binary:
            cmd = [self.binary, *rclone_args]
        else:
            if not self.network:
                raise BackupError(
                    "the rclone container needs a docker network that can reach the store. Pass "
                    "--docker-network (list them with: docker network ls), or --rclone-binary to use a host "
                    "install instead."
                )
            # ABSOLUTE, always: `docker run -v foo/bar:/data` does not read `foo/bar` as a host directory, it
            # creates a NAMED VOLUME — or, when the path holds a `/`, fails with "invalid characters for a
            # local volume name". Measured the hard way, by handing `restore` a repo-relative backup path.
            mount_args = ["-v", f"{mount.resolve()}:/data"] if mount else []
            # Bare `-e NAME`: docker takes the VALUE from this process's environment, so no credential is ever
            # an argv element that `ps` could show.
            env_args = [flag for name in config for flag in ("-e", name)]
            cmd = ["docker", "run", "--rm", "--network", self.network, *env_args, *mount_args,
                   self.image, *rclone_args]
        return subprocess.run(cmd, capture_output=True, text=True, env=env)

    def size(self) -> tuple[int, int]:
        proc = self._run(["size", f"{REMOTE}:{self.bucket}", "--json"])
        if proc.returncode != 0:
            raise BackupError(f"rclone could not read {self.describe()}: {proc.stderr.strip()[-500:]}")
        payload = json.loads(proc.stdout)
        return int(payload["count"]), int(payload["bytes"])

    def index(self) -> list[list]:
        """`[[key, size, md5 | None], …]`, sorted by key — the store leg of an index capture (ADR 0098 decision 4).

        One listing, never a download: `lsjson -R --hash` reads keys, sizes and the S3 MD5, which rclone takes from a
        single-part object's ETag (measured against the pinned Garage: the raw `<ETag>` of the ListObjectsV2 body and
        rclone's `md5` were the body's MD5 — and rclone reports no raw ETag). Every object veyq writes is one presigned
        PUT, so the two coincide. Where a listing gives no hash (a multipart object a hand copy left), the entry
        records None and A4 compares it by key and size only, saying so.
        """
        proc = self._run(["lsjson", "-R", "--hash", "--files-only", f"{REMOTE}:{self.bucket}"])
        if proc.returncode != 0:
            raise BackupError(f"rclone could not list {self.describe()}: {proc.stderr.strip()[-500:]}")
        try:
            entries = json.loads(proc.stdout or "[]")
        except json.JSONDecodeError as e:
            raise BackupError(f"rclone's listing of {self.describe()} is not JSON: {e}") from e
        return sorted([entry["Path"], int(entry["Size"]), (entry.get("Hashes") or {}).get("md5") or None]
                      for entry in entries)

    def mirror_out(self, dest: Path) -> None:
        """`sync` — the destination becomes exactly the bucket, so a re-run is a capture and not a union."""
        proc = self._run(["sync", f"{REMOTE}:{self.bucket}", "/data" if not self.binary else str(dest),
                          "--stats-one-line", "--stats", "0"], mount=dest)
        if proc.returncode != 0:
            raise BackupError(f"rclone sync from {self.describe()} failed: {proc.stderr.strip()[-800:]}")

    def mirror_in(self, source: Path) -> None:
        """`copy` — never `sync`. Against a live bucket, `sync` deletes whatever the backup does not hold."""
        proc = self._run(["copy", "/data" if not self.binary else str(source), f"{REMOTE}:{self.bucket}",
                          "--stats-one-line", "--stats", "0"], mount=source)
        if proc.returncode != 0:
            raise BackupError(f"rclone copy into {self.describe()} failed: {proc.stderr.strip()[-800:]}")

    def purge(self) -> None:
        """Delete the bucket and everything in it. NOT part of capture or restore — it exists for a harness
        that has to destroy a source instance to prove a restore actually restored something."""
        proc = self._run(["purge", f"{REMOTE}:{self.bucket}"])
        if proc.returncode != 0:
            raise BackupError(f"rclone purge of {self.describe()} failed: {proc.stderr.strip()[-500:]}")

    def describe(self) -> str:
        return f"bucket {self.bucket} at {self.endpoint}"


# --------------------------------------------------------------------------------------------------------
# The backup directory
# --------------------------------------------------------------------------------------------------------


def app_release(compose_file: Path, service: str) -> tuple[str | None, str]:
    """The release version of **the instance being captured**, with how it was determined (ADR 0057 §5).

    This used to read `os.environ["VEYQ_VERSION"]` — the *capture process's* environment, on the **host**,
    outside the container, where that variable is never set. So the one field that would name a rollback target
    was structurally `null` in every manifest ever written, sitting beside a `database.schemaVersion` genuinely
    read from the instance. A field that cannot be true is not data; it is a claim with nothing behind it, in
    the one artifact whose entire job is to still be trustworthy a year later.

    It is read from the app **container**, because that is the instance: `docker inspect` reports the effective
    environment of a container whether it is running or not, which matters here — a capture is *cold*, so the
    app is stopped by the time this runs, and `ps` without `-a` would find nothing and report every capture as
    unstamped.

    Returns `(version, source)`. `version` is `None` for a genuine absence, and `source` says **which** absence
    it is — they are different facts and an operator choosing an image to restore under needs them apart:
    `unstamped-build` (the instance runs an image the release pipeline never stamped — A3, the same shape as
    A1), `no-app-service` (this compose file declares none, as the dev stack does not — the backend runs
    outside it), `no-app-container` (declared, never created), and the three that are about this host rather
    than about the instance: `no-compose-file`, `compose-unresolvable` (an `infra/.env` that is not in reach)
    and `docker-unavailable`.

    The service is looked up in **two** steps on purpose. `docker compose ps <service>` exits non-zero both
    when docker is unreachable and when the file simply has no such service, so a single call reports the dev
    stack — whose compose file deliberately has no `app` — as a broken host. Measured, not assumed: that is
    exactly what the first version of this function did. `--no-interpolate` for the same reason one step later:
    without it, `config` on the production compose file fails whenever `infra/.env` is not in reach, and a
    missing variable would again be reported as a missing docker.
    """
    if not compose_file.is_file():
        return None, "no-compose-file"
    try:
        declared = subprocess.run(
            ["docker", "compose", "-f", str(compose_file), "config", "--no-interpolate", "--services"],
            capture_output=True, text=True,
        )
    except FileNotFoundError:
        return None, "docker-unavailable"
    if declared.returncode != 0:
        return None, "docker-unavailable"
    if service not in declared.stdout.split():
        return None, "no-app-service"

    found = subprocess.run(
        ["docker", "compose", "-f", str(compose_file), "ps", "-aq", service],
        capture_output=True, text=True,
    )
    if found.returncode != 0:
        # `ps` DOES interpolate, so an unresolved `${VAR:?…}` — an `infra/.env` that is not in reach — fails
        # here in exactly the same way an unreachable daemon does. Ask docker itself which it was.
        alive = subprocess.run(["docker", "version", "--format", "{{.Server.Version}}"], capture_output=True, text=True)
        return None, ("compose-unresolvable" if alive.returncode == 0 else "docker-unavailable")
    containers = [line for line in found.stdout.split() if line]
    if not containers:
        return None, "no-app-container"

    inspected = subprocess.run(
        ["docker", "inspect", "--format", "{{range .Config.Env}}{{println .}}{{end}}", containers[0]],
        capture_output=True, text=True,
    )
    if inspected.returncode != 0:
        return None, "docker-unavailable"
    for line in inspected.stdout.splitlines():
        name, _, value = line.partition("=")
        if name == "VEYQ_VERSION":
            return (value.strip() or None), ("container-env" if value.strip() else "unstamped-build")
    return None, "unstamped-build"


def expected_keys(blobs: list[list]) -> list[str]:
    """Every object key the rows require, expanded from the compact blob index."""
    return [f"{key}/{i}" for key, chunks in blobs for i in range(chunks)]


class Comparison(NamedTuple):
    """A4 of an index capture against a listing: what the rows require that the listing lacks or holds differently."""
    missing: list[str]
    changed: list[str]
    by_size_only: int


def compare_index(required: list[str], indexed: list[list], listing: list[list]) -> Comparison:
    """Every key the blob rows require, held against a listing of the store: missing, or present with another size
    (or another MD5, where both sides have one). An object the listing holds and the rows do not is tolerated —
    an upload after the capture, the same asymmetry `verify` has (module docstring)."""
    want = {key: (size, digest) for key, size, digest in indexed}
    have = {key: (size, digest) for key, size, digest in listing}
    missing, changed, by_size_only = [], [], 0
    for key in required:
        if key not in have:
            missing.append(key)
            continue
        size, digest = want.get(key, (None, None))
        live_size, live_digest = have[key]
        if size is not None and live_size != size:
            changed.append(f"{key} (size {live_size}, the capture indexed {size})")
        elif digest and live_digest:
            if digest != live_digest:
                changed.append(f"{key} (md5 {live_digest}, the capture indexed {digest})")
        else:
            by_size_only += 1
    return Comparison(missing, changed, by_size_only)


def listed(problems: list[str], keys: list[str], label: str) -> None:
    """Every key, not a count, up to fifty: the resolution is per object, and a count cannot be acted on."""
    for key in keys[:50]:
        problems.append(f"    {label}  {key}")
    if len(keys) > 50:
        problems.append(f"    … and {len(keys) - 50} more")


def present_keys(objects: Path) -> set[str]:
    return {p.relative_to(objects).as_posix() for p in objects.rglob("*") if p.is_file()}


def is_within(path: Path, root: Path) -> bool:
    """Whether `path` resolves to `root` or anything under it — symlinks and `..` included, existing or not."""
    path, root = path.resolve(), root.resolve()
    return path == root or root in path.parents


def loose_bits(path: Path) -> int:
    """The group/other permission bits `path` carries — `0` is owner-only."""
    return path.stat().st_mode & 0o077


def write_key(source: Path, dest: Path) -> None:
    """Copy leg (c) to `dest`, born `0600` and never over an existing file.

    `O_EXCL` because a key destination that already holds a file is most likely an earlier capture's key, and
    silently replacing it would leave that capture unrestorable. The mode is given at creation, not chmodded
    after, so there is no moment at which the key is readable by anyone else.
    """
    dest.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(dest, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as out:
        out.write(source.read_bytes())


AS_IS, CHOWN, REFUSE = "as-is", "chown", "refuse"


class KeyOwnerPlan(NamedTuple):
    """Who the restored key will belong to: leave the owner the open gives it, hand it to `uid:gid`, or refuse."""
    action: str
    uid: int = -1
    gid: int = -1
    reason: str = ""


def effective_uid() -> int:
    """The euid `key_gate` plans for — a function of its own so the self-test can plan as another user."""
    return os.geteuid()


def key_owner_plan(euid: int, parent_uid: int, parent_gid: int, existing_uid: int | None) -> KeyOwnerPlan:
    """Decide who must own the restored key, from the owner of the directory it lands in (inbox #188).

    The directory is the authority because it is the one thing every target already gets right: Docker seeds the
    production `veyq-config-secret` volume from the image as `10001:10001` (validate_app_volume_ownership.py), a
    pod's `fsGroup` owns its claim, and the dev loop's directory is the invoking user's. A key the app cannot read
    is caught by `ConfigSecretSeal.readOrGenerate` and reads as ABSENT — the instance refuses invites and resets
    with the key sitting right there. Only root can give a file away, and `O_TRUNC` keeps an existing file's owner,
    so a root restore over a missing key would otherwise leave it `root:root 0600`.
    """
    if euid == 0:
        owner_after_open = 0 if existing_uid is None else existing_uid
        if owner_after_open == parent_uid and (existing_uid is not None or parent_gid == 0):
            return KeyOwnerPlan(AS_IS)
        return KeyOwnerPlan(CHOWN, parent_uid, parent_gid)
    if parent_uid != euid:
        return KeyOwnerPlan(REFUSE, reason=(
            f"the key's directory belongs to uid {parent_uid}, and this tool runs as uid {euid}, which cannot hand "
            f"a file to another user — the key would be born yours, and an application running as uid "
            f"{parent_uid} could not read it (configSecretState: ABSENT). Run the restore as root (`sudo -E`), or "
            f"restore the key to a file of your own and copy it in through the application container "
            f"(`docker cp`, `kubectl cp`), as https://raddatzk.github.io/veyq/run/backup-and-restore/ shows."))
    if existing_uid is not None and existing_uid != euid:
        return KeyOwnerPlan(REFUSE, reason=(
            f"a key already there belongs to uid {existing_uid}; overwriting it keeps that owner, which this tool "
            f"(uid {euid}) can neither write as nor change. Remove it, or run the restore as root."))
    return KeyOwnerPlan(AS_IS)


def key_gate(target: Path, as_runtime_user: bool = False) -> KeyOwnerPlan:
    """The key's placement, decided and checked before the restore writes anything (A5 would refuse a retry).

    `as_runtime_user` is the cluster's case: a restore Job runs as the application's own uid (its `runAsUser`), and
    a claim's directory is root-owned — `fsGroup` gives it a group, never an owner — so the directory names nobody
    to hand the key to, and the key born to this process IS born to the application's uid."""
    parent = target.parent
    if not parent.is_dir():
        raise BackupError(f"--config-secret-file {target}: its directory {parent} does not exist. On the production "
                          f"recipe it is the veyq-config-secret volume's mountpoint, created by the first `up`; "
                          f"a missing one is almost always a wrong path, so this tool does not create it.")
    st = parent.stat()
    existing_uid = target.stat().st_uid if target.exists() else None
    euid = effective_uid()
    if as_runtime_user:
        if euid == 0:
            raise BackupError(f"--key-as-runtime-user: this tool runs as root, so the key would be born root's — run it "
                              f"as the application's uid (a Kubernetes Job's runAsUser, 10001 for the veyq image)")
        if existing_uid not in (None, euid):
            raise BackupError(f"--config-secret-file {target}: a key already there belongs to uid {existing_uid}, not to "
                              f"this tool's uid {euid}, the application's — overwriting it would keep that owner")
        if not os.access(parent, os.W_OK | os.X_OK):
            raise BackupError(f"--config-secret-file {target}: its directory is not writable by uid {euid}")
        return KeyOwnerPlan(AS_IS)
    plan = key_owner_plan(euid, st.st_uid, st.st_gid, existing_uid)
    if plan.action == REFUSE:
        raise BackupError(f"--config-secret-file {target}: {plan.reason}")
    if euid != 0 and existing_uid is not None and not os.access(target, os.W_OK):
        raise BackupError(f"--config-secret-file {target} exists and is not writable by this tool")
    return plan


def place_key(source: Path, target: Path, as_runtime_user: bool = False) -> KeyOwnerPlan:
    """Write leg (c) at `target`, `0600`, owned per `key_gate` — owner and mode set BEFORE the bytes land, so the
    restored key is never readable by a user it was not meant for, not even for a moment."""
    plan = key_gate(target, as_runtime_user)
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        if plan.action == CHOWN:
            os.fchown(fd, plan.uid, plan.gid)
        os.fchmod(fd, 0o600)
    except BaseException:
        os.close(fd)
        raise
    with os.fdopen(fd, "wb") as out:
        out.write(source.read_bytes())
    return plan


def require_key_from_fits(args: argparse.Namespace, manifest: dict) -> None:
    """`--key-from` names where a SEPARATELY kept key is. For any other placement it would be a second source
    competing with the one the manifest names, so it is refused rather than silently preferred or ignored."""
    placement = manifest["configSecret"]["placement"]
    if args.key_from and placement != SEPARATE:
        raise BackupError(f"--key-from is for a capture that kept its key separately; this one's leg (c) is "
                          f"{placement!r} (manifest configSecret.placement)")


def flyway_order(version: str) -> tuple[int, ...]:
    """A Flyway version as Flyway orders it: dot-separated numeric parts (`_` is a dot in a file name), compared
    as numbers, trailing zeros insignificant (`1.0` is `1`). Never a string: `"9" > "44"` as strings."""
    parts = [int(part) for part in version.replace("_", ".").split(".")]
    while len(parts) > 1 and parts[-1] == 0:
        parts.pop()
    return tuple(parts)


NEWER, SAME, OLDER = "newer", "same", "older"


def backup_against_target(manifest: dict, target_version: str | None) -> str:
    """Where the backup's schema stands against the target's (ADR 0056 §7) — or why the restore must not start.

    A newer backup is refused naming the release it came from: the target's image would refuse to start on a
    history holding migrations it does not know, so loading first would leave the operator with neither instance.
    """
    backup_version = (manifest.get("database") or {}).get("schemaVersion")
    release = (manifest.get("app") or {}).get("version")
    named = f"release {release}" if release else "an unknown release (the manifest records no app.version)"
    if not backup_version:
        raise BackupError("the manifest records no database.schemaVersion, so the restore cannot tell whether "
                          "this instance can run the backup's schema — refusing rather than guessing")
    if not target_version:
        raise BackupError("the target has no applied Flyway migration, so it is not a freshly installed instance "
                          "and its schema cannot be compared with the backup's. Start the application once so it "
                          "migrates and provisions the store, stop it, then restore.")
    try:
        ours, theirs = flyway_order(target_version), flyway_order(str(backup_version))
    except ValueError as e:
        raise BackupError(f"a schema version is not a Flyway version (backup {backup_version!r}, target "
                          f"{target_version!r}): {e}") from e
    if theirs > ours:
        raise BackupError(
            f"this backup is at schema V{backup_version}, NEWER than this instance's V{target_version}: it came "
            f"from {named}. This instance's release would refuse to start on a history holding migrations it does "
            f"not know (ADR 0056 §7). Install {('release ' + release) if release else 'the release the backup came from'} "
            f"(or a newer one), start it once, stop it, then restore. Nothing was written."
        )
    return OLDER if theirs < ours else SAME


def older_backup_notice(manifest: dict, target_version: str) -> str:
    """The closing log for a backup older than its target — an upgrade, which a rollback is not (uc A7, A9)."""
    backup_version = manifest["database"]["schemaVersion"]
    release = (manifest.get("app") or {}).get("version")
    came_from = f"release {release}" if release else "the release it came from (the manifest records none)"
    return (
        f"this backup is at schema V{backup_version}, OLDER than this instance's V{target_version}: the "
        f"application will migrate the data to V{target_version} at its next start — the same forward migration "
        f"every upgrade runs. If you meant a ROLLBACK, this is not one: it repeats the upgrade. Install "
        f"{came_from} instead and restore onto that (uc.admin.back-up-and-restore-instance A7)."
    )


def read_manifest(backup: Path) -> dict:
    path = backup / MANIFEST_NAME
    if not path.is_file():
        raise BackupError(f"{path} is missing — this is not a backup directory this tool wrote")
    try:
        manifest = json.loads(path.read_text())
    except json.JSONDecodeError as e:
        raise BackupError(f"{path} is not readable JSON: {e}") from e
    fmt = manifest.get("backupFormat")
    if fmt not in FORMATS:
        raise BackupError(
            f"{path} declares backupFormat {fmt!r}; this tool writes and reads {BACKUP_FORMAT} (a capture of the "
            f"store's objects) and {INDEX_FORMAT} (an index capture). Refusing to guess at a layout it does not know."
        )
    store = manifest.get("store") or {}
    if fmt == INDEX_FORMAT and (store.get("leg") != INDEX or not isinstance(store.get("index"), list)):
        raise BackupError(f"{path} declares backupFormat {INDEX_FORMAT} but records no store.index — not an index "
                          f"capture this tool wrote")
    placement = (manifest.get("configSecret") or {}).get("placement")
    if placement not in PLACEMENTS:
        raise BackupError(f"{path} records configSecret.placement {placement!r}, which is none of {PLACEMENTS}")
    return manifest


# --------------------------------------------------------------------------------------------------------
# capture
# --------------------------------------------------------------------------------------------------------


def key_readable(source: Path) -> bool:
    """Whether leg (c) is there to capture — False when absent, a refusal when this user cannot read it.

    On the production recipe the key lives on the `veyq-config-secret` volume, owned by the app's uid 10001 and
    `0600`, so only that uid and root read it (inbox #250). Read here, before anything is written: found
    unreadable only at the copy, the dump and the mirror would already lie on disk beside a `PermissionError`.
    """
    try:
        if not source.is_file():
            return False
        with source.open("rb"):
            return True
    except PermissionError:
        raise BackupError(
            f"--config-secret-file {source}: this tool (uid {effective_uid()}) cannot read it. On the production "
            f"recipe it lives on the veyq-config-secret volume, owned by the application's uid and readable only "
            f"by it and root. Run the capture as root with your environment kept (`sudo -E`), so the database and "
            f"store credentials come along.") from None


def cmd_capture(args: argparse.Namespace) -> int:
    db = Database(args, require_db_password())
    store = Store(args)
    backup = Path(args.into) if args.into else DEFAULT_BACKUP_ROOT / utc_stamp()
    if backup.exists() and any(backup.iterdir()):
        raise BackupError(f"{backup} already exists and is not empty — refusing to write a second capture into it")

    # Leg (c)'s destination, decided BEFORE anything is written: a refusal here leaves no half-capture behind.
    # `--key-kept-apart` reads no key at all: the caller keeps it elsewhere (the cluster gate, ADR 0098 decision 4c).
    key_source = Path(args.config_secret_file)
    kept_apart = getattr(args, "key_kept_apart", False)
    key_present = False if kept_apart else key_readable(key_source)
    if args.key_beside_dump:
        key_dest: Path | None = backup / KEY_NAME
    elif args.key_into:
        key_dest = Path(args.key_into)
        if is_within(key_dest, backup):
            raise BackupError(
                f"--key-into {key_dest} is inside the capture directory {backup}. The dump plus the key opens "
                f"every tenant's SMTP password, so a capture directory holding both is exactly the leaked backup "
                f"the key file exists to defend against (ADR 0056 §1, ADR 0050). Give it a destination of its "
                f"own, ideally on a different medium, or pass --key-beside-dump to put it there knowingly."
            )
        if key_dest.exists():
            raise BackupError(
                f"--key-into {key_dest} already exists. It is most likely an earlier capture's key, and "
                f"replacing it would leave that capture unrestorable. Name a new file."
            )
    else:
        key_dest = None
        if key_present:
            raise BackupError(
                f"leg (c) needs a destination of its own, and none was given. Pass --key-into FILE, outside the "
                f"capture directory and ideally on a different medium: the dump plus the key opens every "
                f"tenant's SMTP password (ADR 0056 §1). --key-beside-dump puts it in the capture directory, "
                f"knowingly. --key-kept-apart records it as kept elsewhere without reading it."
            )

    def release_of() -> tuple[str | None, str]:
        if getattr(args, "app_version", None):
            return args.app_version, "caller"
        return app_release(Path(args.compose_file), args.app_service)

    take_capture(backup, db, store, leg=getattr(args, "store_leg", MIRROR), release_of=release_of,
                 kept_apart=kept_apart, key_source=key_source, key_present=key_present, key_dest=key_dest,
                 key_beside_dump=args.key_beside_dump)
    return 0


def take_capture(backup: Path, db: Database, store: Store | None, *, leg: str, release_of,
                 kept_apart: bool = False, key_source: Path | None = None,
                 key_present: bool = False, key_dest: Path | None = None, key_beside_dump: bool = False,
                 tool: str | None = None, allow_no_tenant: bool = False) -> dict:
    """Write one capture into `backup`: the dump, the store leg (`mirror`: the objects; `index`: their listing), leg
    (c) per the caller's decision, and the manifest — last, so a directory without one is no capture. `store` is None
    only for an index capture of an instance that never chose a store (nothing can have been uploaded)."""
    started = utc_now()
    log(f"capturing into {backup}")
    log("NOTE: the application must be STOPPED. With it running, a client can complete an upload between the "
        "dump and the store leg, producing a row whose object was never captured (ADR 0056 §2, A1).")
    backup.mkdir(parents=True, exist_ok=True)
    # Even when it pre-existed empty: its mode is what keeps other local users out of `objects/`, whose files
    # the rclone container writes with its own owner and modes (module docstring).
    backup.chmod(0o700)

    log(f"reading instance facts from {db.describe()}")
    domains = db.domains()
    schema = db.schema_version()
    blobs = db.blob_index()
    sessions = db.open_upload_sessions()
    if not domains and not allow_no_tenant:
        raise BackupError(
            f"{db.describe()} holds no tenant, so this instance has no domain to restore onto. Either the "
            f"database name is wrong or the instance was never set up."
        )
    if not domains:
        log("WARNING: the instance holds no tenant yet, so this capture names no domain; a restore of it accepts any.")

    log(f"dumping {db.describe()} (DDL + data + flyway history)")
    db.dump(backup / DUMP_NAME)

    if leg == INDEX:
        if store is None:
            log("no store was ever chosen, so the instance holds no object: the index is empty")
            index: list[list] = []
            bucket = None
        else:
            log(f"listing {store.describe()} (keys, sizes and MD5s — the objects stay where they are)")
            index = store.index()
            bucket = store.bucket
        count, byte_total = len(index), sum(size for _, size, _ in index)
    else:
        log(f"mirroring {store.describe()}")
        objects = backup / OBJECTS_DIR
        objects.mkdir(exist_ok=True)
        store.mirror_out(objects)
        count, byte_total = store.size()

    release, release_source = release_of()
    if release:
        log(f"the instance being captured runs release {release} — the image a restore should be started under")
    else:
        # Named, never silent: a backup that cannot name its source release is still a valid backup; what it
        # cannot do is tell you which image to start it under, and it says so instead of implying one (A3).
        log(f"WARNING: this capture cannot name the release it came from ({release_source}). The backup is "
            f"still valid; what it cannot tell you is which image to restore it under.")

    if kept_apart:
        placement = SEPARATE
        log("leg (c) is kept apart by the caller and was not read: a restore asks for it (--key-from)")
    elif key_present and key_dest is not None:
        placement = BESIDE if key_beside_dump else SEPARATE
        where = "BESIDE THE DUMP, as asked" if placement == BESIDE else "apart from the capture"
        log(f"copying the config-secret key from {key_source} to {key_dest} ({where})")
        write_key(key_source, key_dest)
    else:
        placement = ABSENT
        # Not fatal, and loudly not fatal: a capture that refused here would leave the operator with nothing,
        # where a capture that records the absence leaves them with two thirds and a stated defect.
        log(f"WARNING: no config-secret key at {key_source} — this backup has TWO legs, not three. A restore "
            f"from it will boot, report configSecretState: MISMATCH, and refuse member invites and password "
            f"resets with 503 mail_unavailable (ADR 0055, A2); a store of your own cannot be opened either, so "
            f"media stay unreachable until its credentials are entered again (ADR 0089 decision 4). Point "
            f"--config-secret-file at the real path and capture again.")

    if leg == INDEX:
        store_leg = {"leg": INDEX, "bucket": bucket, "objects": count, "bytes": byte_total, "blobs": blobs,
                     "openUploadSessions": sessions, "index": index}
    else:
        store_leg = {"bucket": store.bucket, "objects": count, "bytes": byte_total, "blobs": blobs,
                     "openUploadSessions": sessions}
    capture = {"startedAt": started, "finishedAt": utc_now()}
    if tool:
        capture["tool"] = tool
    manifest = {
        "backupFormat": INDEX_FORMAT if leg == INDEX else BACKUP_FORMAT,
        "domains": domains,
        "app": {"version": release, "versionSource": release_source},
        "database": {"name": db.name, "schemaVersion": schema, "bytes": (backup / DUMP_NAME).stat().st_size},
        "store": store_leg,
        "configSecret": {"placement": placement},
        "capture": capture,
    }
    (backup / MANIFEST_NAME).write_text(json.dumps(manifest, indent=2) + "\n")

    log(f"captured: {len(domains)} domain(s) {domains}, schema V{schema}, {count} object(s) / {byte_total} B "
        f"{'indexed' if leg == INDEX else 'mirrored'}, {len(blobs)} blob row(s), leg (c) "
        f"{'MISSING' if placement == ABSENT else placement}")
    if placement == SEPARATE and key_dest is not None:
        log(f"the key is at {key_dest}. Keep it apart from this capture: a copy of both together is a copy of "
            f"every tenant's SMTP password. Encrypt the capture before it leaves this host.")
        log(f"now check both:  python3 scripts/instance_backup.py verify {backup} --key-from {key_dest}")
    elif not kept_apart:
        log(f"now check it:  python3 scripts/instance_backup.py verify {backup}")
    return manifest


# --------------------------------------------------------------------------------------------------------
# verify
# --------------------------------------------------------------------------------------------------------


def cmd_verify(args: argparse.Namespace) -> int:
    backup = Path(args.backup)
    manifest = read_manifest(backup)
    require_key_from_fits(args, manifest)
    problems: list[str] = []

    dump = backup / DUMP_NAME
    if not dump.is_file() or dump.stat().st_size == 0:
        problems.append(f"{DUMP_NAME} is missing or empty — leg (a) is not in this backup")
    else:
        # Structural, not semantic: the rows are ciphertext this tool cannot read. What IS checkable is the one
        # property D3 rests on — that the dump carries DDL and the migration history, so a restore does not
        # re-derive the schema from an empty database.
        head = dump.read_text(errors="replace")[:200_000]
        if "CREATE TABLE" not in head:
            problems.append(f"{DUMP_NAME} carries no CREATE TABLE — it looks like a data-only dump, which "
                            f"restores onto nothing")
        if "flyway_schema_history" not in head:
            problems.append(f"{DUMP_NAME} carries no flyway_schema_history — a restore from it would re-run "
                            f"every migration against an empty database instead of arriving at the captured "
                            f"schema version")

    blobs = manifest["store"]["blobs"]
    required = expected_keys(blobs)
    indexed = manifest["backupFormat"] == INDEX_FORMAT
    live_note = ""
    if indexed:
        # An index capture holds no object: A4 is answered from the listing the capture took — every key the rows
        # require must be in it — and, with --against-store, from a listing of the live store (ADR 0098 decision 4).
        present = {entry[0] for entry in manifest["store"]["index"]}
        where = "the capture's index"
    else:
        objects = backup / OBJECTS_DIR
        if not objects.is_dir():
            problems.append(f"{OBJECTS_DIR}/ is missing — leg (b) is not in this backup")
            present = set()
        else:
            present = present_keys(objects)
        where = f"{OBJECTS_DIR}/"

    missing = [key for key in required if key not in present]
    if missing:
        # Every missing key, not a count: the resolution is per-object, and a count cannot be acted on. This is
        # A4 — a row whose object is absent restores as a valid presigned URL that 404s, with no server-side
        # error anywhere. It is the failure a volume copy could not have shown at all.
        problems.append(
            f"{len(missing)} object(s) required by a blob row are missing from {where} "
            f"(A4 — each restores as a Memory whose picture yields a valid URL and a 404):"
        )
        listed(problems, missing, "missing object")

    orphans = len(present) - (len(required) - len(missing))
    recorded = manifest["store"]["objects"]
    if len(present) != recorded:
        problems.append(
            f"{OBJECTS_DIR}/ holds {len(present)} file(s) but the manifest recorded {recorded} object(s) — "
            f"the mirror is not the one this manifest describes" if not indexed else
            f"the capture's index holds {len(present)} entr(ies) but the manifest recorded {recorded} object(s) — "
            f"the index is not the one this manifest describes"
        )

    if indexed and getattr(args, "against_store", False):
        store = Store(args)
        found = compare_index(required, manifest["store"]["index"], store.index())
        if found.missing:
            problems.append(f"{len(found.missing)} object(s) the rows require are missing from the live store, "
                            f"{store.describe()} (A4):")
            listed(problems, found.missing, "missing object")
        if found.changed:
            problems.append(f"{len(found.changed)} object(s) the rows require differ in the live store from what "
                            f"the capture indexed (A4):")
            listed(problems, found.changed, "changed object")
        live_note = (f"all {len(required)} required present in {store.describe()}"
                     + (f", {found.by_size_only} compared by key and size only (no MD5 on one side)"
                        if found.by_size_only else ""))

    # Owner-only: the root, and every file the tool itself wrote. Not what is inside `objects/` — the rclone
    # container writes that with its own owner and modes, which is why the root's 0700 is the check that counts.
    if loose_bits(backup):
        problems.append(f"the capture directory is group/world-accessible "
                        f"({backup.stat().st_mode & 0o777:o}) — it must be 0700")
    for name in (MANIFEST_NAME, DUMP_NAME, OBJECTS_DIR, KEY_NAME):
        written = backup / name
        if written.exists() and loose_bits(written):
            problems.append(f"{name} is group/world-readable ({written.stat().st_mode & 0o777:o}) — it must be "
                            f"{'0700' if written.is_dir() else '0600'}")

    placement = manifest["configSecret"]["placement"]
    key_file = backup / KEY_NAME
    key_note = ""
    if placement == BESIDE:
        if not key_file.is_file():
            problems.append(f"the manifest records leg (c) as captured beside the dump but {KEY_NAME} is not here")
    elif key_file.exists():
        problems.append(f"{KEY_NAME} sits beside the dump, but the manifest records leg (c) as {placement} — the "
                        f"dump plus the key opens every tenant's SMTP password (ADR 0056 §1)")
    if placement == SEPARATE:
        if args.key_from:
            kept = Path(args.key_from)
            if not kept.is_file() or kept.stat().st_size == 0:
                problems.append(f"--key-from {kept} is missing or empty — leg (c) is not where you said")
            elif loose_bits(kept):
                problems.append(f"--key-from {kept} is group/world-readable ({kept.stat().st_mode & 0o777:o}) — "
                                f"it must be 0600")
            key_note = f"kept separately, checked at {kept}"
        else:
            key_note = "kept separately — NOT checked here; pass --key-from FILE to check it"
    elif placement == BESIDE:
        key_note = "beside the dump, as the capture was told — keep this directory as secret as the key"
    else:
        key_note = "ABSENT — this backup has two legs, not three"

    if problems:
        print(f"BACKUP VERIFY FAILED for {backup}:")
        for p in problems:
            print("  -", p)
        return 1

    # Two headlines, two exit codes, one predicate: leg (c). "Structurally intact" and "restorable" are not the
    # same claim, and a backup missing the key file is exactly the one that looks fine and is not (A2).
    complete = placement != ABSENT
    log(f"backup OK: {backup}" if complete else f"backup INCOMPLETE (leg (c) missing): {backup}")
    log(f"  domains            {manifest['domains']}")
    log(f"  schema             V{manifest['database']['schemaVersion']}")
    # The rollback target, stated or explicitly absent — never a bare `null` that reads as data (ADR 0057 §5).
    app = manifest.get("app") or {}
    if app.get("version"):
        log(f"  release            {app['version']} — restore this backup under that image")
    else:
        log(f"  release            unknown ({app.get('versionSource') or 'not recorded'}) — still a valid "
            f"backup, but it cannot tell you which image to start it under (A3)")
    log(f"  objects            {len(present)} {'indexed' if indexed else 'present'}, {len(required)} required by "
        f"{len(blobs)} blob row(s)")
    if indexed:
        log(f"  live store         {live_note or 'NOT checked here; pass --against-store to check it'}")
    # Tolerated and named, never silent: an object with no row is the running system's steady state (a capture
    # taken between a row delete and the next reclaim sweep holds exactly these — ADR 0062). Reporting it is
    # what keeps "0 orphans" from being the only shape a reader recognises as healthy.
    log(f"  orphaned objects   {orphans} (tolerated — ADR 0056 § Context 2)")
    log(f"  open upload sess.  {manifest['store']['openUploadSessions']}")
    log(f"  config-secret key  {key_note}")
    log(f"  permissions        owner-only (the root and every file this tool wrote)")
    return 0 if complete else 2


# --------------------------------------------------------------------------------------------------------
# restore
# --------------------------------------------------------------------------------------------------------


def cmd_restore(args: argparse.Namespace) -> int:
    backup = Path(args.backup)
    manifest = read_manifest(backup)

    # The domain gate, before anything is touched. ADR 0056 §4 declined to document a hand-written UPDATE; a
    # refusal is the shape that decision implies. `veyq_tenant.domain` is UNIQUE and is baked into every
    # invitation and recovery link the instance mails, so a silent host change produces an instance nobody can
    # reach holding links nobody can follow.
    domains = manifest["domains"]
    if not domains:
        # Only a gate capture of an instance that had no tenant yet names none (`take_capture`): no link was mailed
        # for any domain, so there is no domain to keep.
        log(f"the backup names no domain (the instance had no tenant when it was captured); restoring for {args.domain}")
    elif args.domain not in domains:
        raise BackupError(
            f"this backup holds {domains}; you asked to restore onto {args.domain!r}. A restore targets the "
            f"SAME domain (ADR 0056 §4) — the tenant's domain is unique and is written into every invitation "
            f"and recovery link already mailed. Restore onto one of {domains}, or treat the target as a new "
            f"instance."
        )

    # The key gate, also before anything is touched: leg (c) comes from wherever the manifest says it went
    # (ADR 0056 §1). A key kept separately that is not supplied is refused, never warned about — reaching A2 is
    # the operator's knowing choice (--without-key), not what a forgotten flag produces.
    require_key_from_fits(args, manifest)
    placement = manifest["configSecret"]["placement"]
    key_source: Path | None = None
    if placement == SEPARATE and args.key_from:
        key_source = Path(args.key_from)
        if not key_source.is_file():
            raise BackupError(f"--key-from {key_source} is not a file — leg (c) is not where you said")
    elif placement == SEPARATE and not args.without_key:
        raise BackupError(
            f"this capture kept its config-secret key apart from the dump (manifest configSecret.placement = "
            f"{SEPARATE!r}), and no --key-from was given. Pass --key-from FILE with the key you kept for it. "
            f"Without it the instance starts, serves content, reports configSecretState: MISMATCH, and refuses "
            f"member invites and password resets with 503 mail_unavailable (ADR 0055, A2) — if that is what you "
            f"want, say so with --without-key."
        )
    elif placement == BESIDE:
        key_source = backup / KEY_NAME
        if not key_source.is_file() and not args.without_key:
            raise BackupError(
                f"the manifest records leg (c) as captured beside the dump, but {key_source} is not there. "
                f"Find the key, or restore without it knowingly with --without-key (A2)."
            )
        if not key_source.is_file():
            key_source = None

    # Where the key lands is checked HERE, not where it is written: placement runs after the database and the
    # objects are in, so a refusal there would leave a half-restored instance the A5 gate then refuses to retry.
    target_key = Path(args.config_secret_file)
    if key_source is not None:
        key_gate(target_key, getattr(args, "key_as_runtime_user", False))

    indexed = manifest["backupFormat"] == INDEX_FORMAT
    accept_missing = getattr(args, "accept_missing_objects", False)
    if accept_missing and not indexed:
        raise BackupError("--accept-missing-objects is for an index capture, whose objects stay in the live store; "
                          "this capture holds its objects itself")
    record_dir = Path(args.record) if getattr(args, "record", None) else None
    if record_dir is not None and not record_dir.is_dir():
        raise BackupError(f"--record {record_dir} is not a directory — on a cluster it is the backup claim's mount")
    # Read now, so an unreadable record refuses before the first write rather than after the load.
    previous = read_record(record_dir) if record_dir is not None else None

    db = Database(args, require_db_password())
    store = Store(args)

    # A5 — the restore is defined onto an EMPTY instance and nothing reconciles two populated ones. *Empty*
    # means holding no DATA: since ADR 0067 the target's store is provisioned by the app, and the same start
    # migrates the schema, so a freshly installed instance is the only shape a target can have.
    populated = db.populated_tables()
    if populated:
        held = ", ".join(f"{name} ({count} row(s))" for name, count in populated)
        raise BackupError(
            f"{db.describe()} already holds data: {held}. A restore is defined onto an empty instance and "
            f"nothing reconciles two populated ones (A5) — loading a dump over live rows produces a state no "
            f"decision describes. A freshly installed instance IS an acceptable target; one that has been used "
            f"is not. Drop and recreate the database, start the app once so it migrates and provisions the "
            f"store, then restore."
        )
    broken: list[str] = []
    if indexed:
        # An index capture restores onto the store it indexed and writes nothing to it (ADR 0098 decision 4): in place
        # of A5's empty-store gate, A4 against the live store — every object the dump's rows require is there, with
        # the indexed size (and MD5 where both sides have one), before anything is written. An object the store
        # holds and the index does not is tolerated: an upload after the capture.
        found = compare_index(expected_keys(manifest["store"]["blobs"]), manifest["store"]["index"], store.index())
        broken = found.missing + found.changed
        if broken and not accept_missing:
            problems = [f"{len(found.missing)} missing and {len(found.changed)} changed object(s) the backup's rows "
                        f"require, in {store.describe()}:"]
            listed(problems, found.missing, "missing object")
            listed(problems, found.changed, "changed object")
            raise BackupError(
                "\n".join(problems) + "\n[backup] Restored as they are, each would be a Memory whose picture yields a "
                "valid URL and a 404 (A4). Bring them back from the store's own versioning, replication or snapshot "
                "and restore again — or pass --accept-missing-objects to restore without them, knowingly. Nothing "
                "was written.")
        if found.by_size_only:
            log(f"{found.by_size_only} object(s) compared by key and size only (no MD5 on one side)")
    else:
        count, _ = store.size()
        if count:
            raise BackupError(
                f"{store.describe()} already holds {count} object(s). A restore is defined onto an empty store "
                f"(A5) — this tool copies INTO a bucket and never deletes from one. Empty it first, then restore."
            )

    # ADR 0056 §7 — the backup's schema against the target's, in Flyway's order, before the first write. A newer
    # backup raises here; the same or an older one is loaded over a target emptied of every table, because the
    # dump drops and recreates only the tables it names.
    target_version = db.schema_version()
    standing = backup_against_target(manifest, target_version)
    dropped = db.drop_all_tables()
    log(f"dropped the {dropped} table(s) of the freshly installed target (it holds no data, A5)")

    log(f"loading {DUMP_NAME} into {db.describe()}")
    db.load(backup / DUMP_NAME)

    try:
        if indexed:
            log(f"the objects stay where they are: {store.describe()} holds every one the rows require (A4)")
        else:
            log(f"pushing objects into {store.describe()}")
            store.mirror_in(backup / OBJECTS_DIR)
    except BackupError as e:
        # The database leg is already in. Say so, because the emptiness gates above will now refuse the retry
        # for a reason that reads like a different problem ("already holds N tables") if nobody said why.
        raise BackupError(
            f"{e}\n[backup] The database leg IS already loaded, so the target is half-restored and this tool "
            f"will refuse to run against it again (A5) — the rows it just wrote are exactly the data the "
            f"emptiness gate looks for. Start over from a fresh instance: drop and recreate the target "
            f"database, empty the bucket, start the app once so it migrates and provisions the store, then "
            f"restore. A partial restore is not repaired in place."
        ) from e

    if key_source is not None:
        log(f"placing the config-secret key from {key_source} at {target_key}")
        # Over the key a fresh install generated, which is expected (or onto none: the seal generates its key
        # lazily, so a fresh install's one start usually leaves the file absent).
        try:
            plan = place_key(key_source, target_key, getattr(args, "key_as_runtime_user", False))
        except (BackupError, OSError) as e:
            # The gate above passed, so the target changed under the run. Legs (a) and (b) are in; say so.
            raise BackupError(f"{e}\n[backup] Legs (a) and (b) ARE already loaded; only the key failed. Place it "
                              f"by hand at {target_key}, readable by the application's user, then start the "
                              f"application.") from e
        if plan.action == CHOWN:
            log(f"the key belongs to {plan.uid}:{plan.gid}, the owner of its directory — the application's user")
    else:
        log(f"WARNING: this restore places no config-secret key (leg (c) {placement}"
            f"{', and --without-key was given' if args.without_key else ''}). The instance will start, report "
            f"configSecretState: MISMATCH, and refuse member invites and password resets with 503 "
            f"mail_unavailable until the key is restored or each tenant owner re-enters their SMTP password "
            f"(ADR 0055, A2); a store of your own stays unreadable, and media with it, until the key is restored "
            f"or its credentials are entered again in Server → Object store (ADR 0089 decision 4).")

    if indexed:
        log(f"restored: schema V{manifest['database']['schemaVersion']}, against {manifest['store']['objects']} "
            f"indexed object(s) left in place")
    else:
        restored, restored_bytes = store.size()
        log(f"restored: schema V{manifest['database']['schemaVersion']}, {restored} object(s) / {restored_bytes} B")
    if broken:
        log(f"WARNING: restored WITHOUT {len(broken)} object(s) the rows require (--accept-missing-objects): each is "
            f"a Memory whose picture yields a valid URL and a 404 (A4):")
        for entry in broken:
            log(f"    broken object  {entry}")
    if standing == OLDER:
        log(older_backup_notice(manifest, target_version))
    if record_dir is not None:
        # The last step (ADR 0098 decision 9): the next start of the backup's release finds its own release in the
        # record and passes the gate instead of capturing the restored database again or refusing it as a downgrade.
        release = (manifest.get("app") or {}).get("version")
        write_record(record_dir, release, (previous or {}).get("request", ""), backup.name)
        log(f"the record {record_dir / STATE_NAME} now names release {release or 'unknown (the manifest records none)'}"
            f" — start that release")
    log("Now start the application, then read its config-secret state as the Server Owner — that is the step "
        "that separates a restore from a hope (ADR 0056 §5): `veyq-admin config-secret` (or the admin console's "
        "Server → Config-secret key) must report OK; `veyq-admin store check` (Server → Object store) must pass; "
        "and a person must be able to sign in and open a Memory that has media.")
    return 0


# --------------------------------------------------------------------------------------------------------
# gate — the cluster's backup before a release change (ADR 0098)
# --------------------------------------------------------------------------------------------------------

STATE_NAME = "state.json"
CAPTURES_DIR = "captures"
PARTIAL = ".partial"
# What the `veyq-tools` image bakes at build (ADR 0098, cr-kubernetes-backup-gate O3): its own release and the
# release a new major is entered from — the predecessor the release's own upgrade proof and chart check ran from
# (`release_assets.py --major-entry`), or null.
RELEASE_FILE = Path("/opt/veyq/release.json")
# ADR 0057's release grammar, `<major>.<minor>` with an optional `-SNAPSHOT` (charts/veyq/Chart.yaml says the same).
RELEASE = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-SNAPSHOT)?")


def release_order(release: str | None) -> tuple[int, int, int] | None:
    """`(major, minor, 1)` for a release, `(major, minor, 0)` for its snapshot — so a snapshot sorts below the release
    it becomes — or None for anything that is not ADR 0057's grammar (a checkout's `0.0-dev`, a `candidate-*` tag)."""
    match = RELEASE.fullmatch(release or "")
    if not match:
        return None
    return int(match.group(1)), int(match.group(2)), 0 if match.group(3) else 1


def read_record(root: Path) -> dict | None:
    """The gate's record, `state.json` on the backup volume — or None when there is none. A record that is not
    readable JSON FAILS, naming the file: "absent" means *capture and move on*, which would silently skip the
    downgrade refusal (cr-kubernetes-backup-gate decision 7)."""
    path = root / STATE_NAME
    if not path.exists():
        return None
    try:
        record = json.loads(path.read_text())
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as e:
        raise BackupError(f"{path} is not a readable record ({e}). It is the gate's memory of which release this "
                          f"database holds; read it, repair or remove it by hand — the gate never guesses past it.") from e
    if not isinstance(record, dict) or not isinstance(record.get("release"), (str, type(None))) \
            or not isinstance(record.get("request", ""), str):
        raise BackupError(f"{path} is not a record the gate wrote (want an object with a string or null `release` and "
                          f"a string `request`) — read it, repair or remove it by hand.")
    return record


def write_record(root: Path, release: str | None, request: str, capture: str | None) -> None:
    """Written to a temporary name and renamed, so an interrupted write leaves the previous record."""
    body = json.dumps({"release": release, "request": request, "capture": capture, "writtenAt": utc_now()},
                      indent=2) + "\n"
    tmp = root / (STATE_NAME + ".tmp")
    tmp.write_text(body)
    os.replace(tmp, root / STATE_NAME)


def read_image_release(path: Path) -> tuple[str, str | None]:
    """`(release, majorEntry)` the tools image was built for."""
    try:
        baked = json.loads(path.read_text())
        return str(baked["release"]), baked.get("majorEntry")
    except (OSError, ValueError, KeyError, TypeError) as e:
        raise BackupError(f"{path} names no release ({e}) — the gate runs in the veyq-tools image, which bakes it") from e


def complete_captures(root: Path) -> list[Path]:
    """The complete captures, oldest first: their names are UTC stamps, so the name order is the time order."""
    folder = root / CAPTURES_DIR
    if not folder.is_dir():
        return []
    return sorted(p for p in folder.iterdir() if p.is_dir() and not p.name.endswith(PARTIAL))


def prune(root: Path, keep: int) -> list[str]:
    """After a complete capture: every `.partial` (an interrupted run's) and the complete ones beyond `keep`, never the
    newest. Runs only after a capture succeeded — a full volume fails the gate, it is never pruned to make room."""
    folder = root / CAPTURES_DIR
    gone = [p for p in folder.iterdir() if p.is_dir() and p.name.endswith(PARTIAL)] if folder.is_dir() else []
    complete = complete_captures(root)
    gone += complete[:-max(keep, 1)]
    for path in gone:
        shutil.rmtree(path)
    return sorted(p.name for p in gone)


def gate_refusal(record: dict | None, target: str, major_entry: str | None, root: Path) -> str | None:
    """ADR 0098 decision 3 — the upgrades no tested path covers. None when the move is one the release was proven on.
    No record, or a record whose release does not parse, orders nothing: the running release is unknown."""
    held = (record or {}).get("release")
    ours, theirs = release_order(target), release_order(held)
    if theirs is None:
        return None
    if ours < theirs:
        # The way back to `target` is a capture of ITS data — one a gate took when `target` was left — restored under
        # `target` (ADR 0098 decision 9). Named by its manifest, newest first; not guessed when there is none here.
        taken = [p.name for p in reversed(complete_captures(root))
                 if ((json.loads((p / MANIFEST_NAME).read_text()) if (p / MANIFEST_NAME).is_file() else {})
                     .get("app") or {}).get("version") == target]
        restore = (f"empty the database, start release {target} once, then restore the capture {taken[0]}, taken "
                   f"from release {target}" if taken else
                   f"no capture on this volume was taken from release {target}; restore one you keep elsewhere")
        return (f"release {target} is OLDER than release {held}, whose data this database holds — a downgrade. "
                f"Release {target} refuses to start on a schema history holding a migration it does not know (ADR 0056 "
                f"§7), and nothing here can tell which this one holds; a way back is the backup's release plus a "
                f"restore: {restore} "
                f"(https://raddatzk.github.io/veyq/run/backup-and-restore/), or set the image back to {held}. "
                f"Nothing was written.")
    if ours[0] > theirs[0] + 1:
        return (f"release {target} is more than one major ahead of release {held}: no run crosses more than one major "
                f"(ADR 0096 decision 5). Upgrade to the newest release of major {theirs[0] + 1} first. Nothing was "
                f"written.")
    if ours[0] == theirs[0] + 1 and held != major_entry:
        entry = (f"release {major_entry}" if major_entry else "no release (this image names none)")
        return (f"release {target} enters major {ours[0]}, and its upgrade was proven from {entry}, not from release "
                f"{held}. Upgrade to {major_entry} first, then to {target}. Nothing was written."
                if major_entry else
                f"release {target} enters major {ours[0]}, and this image names no release its upgrade was proven "
                f"from, so no entry is a tested path. Nothing was written.")
    return None


def gate_store(args: argparse.Namespace, db: Database, env: dict[str, str]) -> Store | None:
    """The store the gate lists, from what the app itself reads (cr-kubernetes-backup-gate decision 5, O2): the bundled
    store's hint, or — for a store of the operator's own, whose secret key is sealed under the KEK the gate never
    reads — the Secret `backup.store.existingSecret` names, held against the plaintext of the setting in use."""
    if not args.rclone_binary:
        raise BackupError("rclone is not on PATH. The gate lists the store with the veyq-tools image's rclone and has "
                          "no Docker to fall back to — run it in that image.")
    row = db.store_setting()
    if row is None:
        if db.blob_index():
            raise BackupError("the database holds blob rows but no object-store setting in use, so the gate cannot "
                              "tell which store to list")
        return None
    if row.bundled:
        endpoint, token = env.get("VEYQ_BUNDLED_STORE_ENDPOINT", ""), env.get("VEYQ_BUNDLED_STORE_ADMIN_TOKEN", "")
        if not endpoint or not token.strip():
            raise BackupError("the instance uses the bundled store, and VEYQ_BUNDLED_STORE_ENDPOINT / "
                              "VEYQ_BUNDLED_STORE_ADMIN_TOKEN are not both set — the chart passes the app's own")
        args.store_endpoint = endpoint
        args.store_access_key, args.store_secret_key = derive_bundled_credentials(token)
        args.store_bucket = env.get("VEYQ_BUNDLED_STORE_BUCKET") or "blobs"
        args.store_region = env.get("VEYQ_BUNDLED_STORE_REGION") or "us-east-1"
    else:
        wanted = {name: env.get(f"VEYQ_BACKUP_STORE_{name.upper()}", "")
                  for name in ("endpoint", "bucket", "access_key", "secret_key")}
        absent = [f"VEYQ_BACKUP_STORE_{name.upper()}" for name, value in wanted.items() if not value]
        if absent:
            raise BackupError(f"the instance uses a store of its own ({row.endpoint}, bucket {row.bucket}), whose key "
                              f"the gate cannot open (it never reads the KEK), and {', '.join(absent)} "
                              f"{'is' if len(absent) == 1 else 'are'} unset. Name a Secret with endpoint, bucket and a "
                              f"key pair that can list the bucket in backup.store.existingSecret.")
        for name, actual in (("endpoint", row.endpoint), ("bucket", row.bucket)):
            if wanted[name] != actual:
                raise BackupError(f"backup.store.existingSecret names {name} {wanted[name]!r}, and the store in use "
                                  f"is at {name} {actual!r} (veyq_store_setting) — a listing of another store "
                                  f"would be an index of the wrong objects.")
        args.store_endpoint, args.store_bucket = wanted["endpoint"], wanted["bucket"]
        args.store_access_key, args.store_secret_key = wanted["access_key"], wanted["secret_key"]
        args.store_region = env.get("VEYQ_BACKUP_STORE_REGION") or row.region or "us-east-1"
    return Store(args)


def cmd_gate(args: argparse.Namespace, env: dict[str, str] | None = None) -> int:
    """ADR 0098 decisions 2, 3, 5 and 7, in the order cr-kubernetes-backup-gate decision 8 fixes (and the self-test
    pins): the inputs; (1) the record; (2) a database holding no data — record the target, pass; (3) the target and
    the request the record's — pass; (4) the refusals, writing nothing; (5) capture into `captures/<UTC>.partial/`,
    verify it, rename; (6) move the record; (7) prune."""
    env = os.environ if env is None else env
    if args.keep < 1:
        raise BackupError(f"backup.keep {args.keep} keeps no capture; the newest is the way back, so at least 1")
    image_release, major_entry = read_image_release(Path(args.release_file))
    target = args.target_release or ""
    if release_order(target) is None:
        raise BackupError(f"the target release {target!r} is not a release (<major>.<minor>, optionally -SNAPSHOT, "
                          f"ADR 0057), so no upgrade path can be checked. A checkout's dev tag or a candidate runs "
                          f"with backup.enabled: false.")
    if target != image_release:
        raise BackupError(f"the app image is release {target} and this veyq-tools image is release {image_release}: "
                          f"the entry rule and the capture format are the app's release's, so the gate runs only in "
                          f"its own release's tools image. Leave backup.image.tag unset, or set it to {target}.")
    root = Path(args.dir)
    if not root.is_dir():
        raise BackupError(f"{root} is not a directory — the backup claim (backup.existingClaim) is not mounted there")
    if not getattr(args, "db_url", None):
        raise BackupError("VEYQ_BACKUP_DB_URL is unset: the gate reaches the database by the app's own JDBC URL only")
    request = args.request or ""

    record = read_record(root)                                                          # (1)
    db = Database(args, require_db_password())
    if not db.populated_tables():                                                       # (2)
        write_record(root, target, request, None)
        log(f"the database holds no data: nothing to capture. The record names release {target}.")
        return 0
    if record is not None and record.get("release") == target and record.get("request", "") == request:  # (3)
        log(f"release {target} already holds this database and no new request is pending: nothing to capture")
        return 0
    refusal = gate_refusal(record, target, major_entry, root)                          # (4)
    if refusal:
        raise BackupError(refusal)

    held = (record or {}).get("release")
    if record is None:
        release, source = None, "no-record"
        log("no record on the backup volume and the database holds data: the running release is unknown")
    elif held is None:
        release, source = None, "record-names-none"
        log("the record names no release (a restore of a capture that named none): the running release is unknown")
    elif release_order(held) is None:
        release, source = None, f"record-unparsable: {held!r}"
        log(f"the record names {held!r}, which is not a release: the running release is unknown")
    else:
        release, source = held, "record"
    why = ("no record" if record is None else "the record names no release" if held is None else
           f"release {held} → {target}" if held != target else f"a new request, {request!r}")
    log(f"capturing before the start that would migrate ({why})")
    store = gate_store(args, db, env)                                                   # (5)
    stamp = utc_stamp()
    partial = root / CAPTURES_DIR / f"{stamp}{PARTIAL}"
    final = root / CAPTURES_DIR / stamp
    if final.exists():
        raise BackupError(f"{final} already exists — a capture of this second; start again")
    (root / CAPTURES_DIR).mkdir(exist_ok=True)
    if partial.exists():
        shutil.rmtree(partial)  # an interrupted run of this same second; ours, and never a complete capture
    take_capture(partial, db, store, leg=INDEX, release_of=lambda: (release, source), kept_apart=True,
                 tool=image_release, allow_no_tenant=True)
    if cmd_verify(argparse.Namespace(backup=str(partial), key_from=None, against_store=False)) != 0:
        raise BackupError(f"the capture {partial} failed its own verify; it stays as it is, the record is unmoved")
    os.replace(partial, final)
    write_record(root, target, request, stamp)                                          # (6)
    gone = prune(root, args.keep)                                                       # (7)
    log(f"captured {final}; the record names release {target}"
        + (f"; pruned {', '.join(gone)}" if gone else ""))
    return 0


# --------------------------------------------------------------------------------------------------------
# wiring
# --------------------------------------------------------------------------------------------------------


def require_db_password() -> str:
    password = os.environ.get("VEYQ_BACKUP_DB_PASSWORD")
    if not password:
        raise BackupError(
            "VEYQ_BACKUP_DB_PASSWORD is unset. It is read from the environment rather than taken as a flag so "
            "the password is in no argv and no shell history."
        )
    return password


def add_common(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--compose-file", default=str(DEFAULT_COMPOSE),
                        help=f"compose file for the bundled stack (default: {DEFAULT_COMPOSE.relative_to(REPO)})")
    parser.add_argument("--db-service", default="mariadb", help="compose service holding the database")
    parser.add_argument("--app-service", default="app",
                        help="compose service running the application — inspected (stopped or not) for the "
                             "release version the manifest records as the rollback target")
    parser.add_argument("--db-host", default=None,
                        help="switch to direct mode: connect to this database host instead of the compose service")
    parser.add_argument("--db-port", type=int, default=3306)
    parser.add_argument("--db-name", default="veyq")
    parser.add_argument("--db-image", default=DB_IMAGE,
                        help="pinned client image used in direct mode when no host client is installed")
    parser.add_argument("--docker-network", default=None,
                        help="docker network the rclone (and direct-mode database) container joins — it must "
                             "reach the store's endpoint. e.g. infra_default for the bundled stack.")
    add_pod_db(parser)
    add_store(parser)
    parser.add_argument("--config-secret-file", default="config-secret.key",
                        help="leg (c): where the key file lives on the app's filesystem "
                             "(veyq.security.config-secret-file / VEYQ_SECURITY_CONFIG_SECRET_FILE)")


def add_pod_db(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--db-url", default=os.environ.get("VEYQ_BACKUP_DB_URL") or None,
                        help="the in-pod mode: the database the app's own JDBC URL names, "
                             "jdbc:mariadb://host[:port]/database (env VEYQ_BACKUP_DB_URL); the client binaries "
                             "must be on PATH")
    parser.add_argument("--db-user", default=os.environ.get("VEYQ_BACKUP_DB_USER") or "root",
                        help="database user (env VEYQ_BACKUP_DB_USER; default root)")


def add_store(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--store-endpoint",
                        default=os.environ.get("VEYQ_BACKUP_STORE_ENDPOINT") or os.environ.get("VEYQ_BUNDLED_STORE_ENDPOINT"),
                        help="S3 endpoint AS THE CONTAINER SEES IT (e.g. http://garage:3900), not a published port")
    parser.add_argument("--store-access-key", default=os.environ.get("VEYQ_BACKUP_STORE_ACCESS_KEY"),
                        help="a store of your own: its access key (the bundled store's pair is derived from its admin token)")
    parser.add_argument("--store-secret-key", default=os.environ.get("VEYQ_BACKUP_STORE_SECRET_KEY"))
    parser.add_argument("--store-bucket", default=os.environ.get("VEYQ_BACKUP_STORE_BUCKET", "blobs"))
    parser.add_argument("--store-region", default=os.environ.get("VEYQ_BACKUP_STORE_REGION", "us-east-1"))
    parser.add_argument("--rclone-image", default=RCLONE_IMAGE)
    parser.add_argument("--rclone-binary", default=None,
                        help="use a host rclone install instead of the pinned container")


def check_store_args(args: argparse.Namespace, env: dict[str, str] | None = None) -> None:
    """Settle the store's key pair for this run: the operator's own pair, else the bundled one derived from the
    recipe's admin token (ADR 0089). Fills `args.store_access_key` / `args.store_secret_key` in the second case."""
    env = os.environ if env is None else env
    if bool(args.store_access_key) != bool(args.store_secret_key):
        raise BackupError("a store key pair is both halves or neither: give --store-access-key AND --store-secret-key "
                          "(VEYQ_BACKUP_STORE_ACCESS_KEY / VEYQ_BACKUP_STORE_SECRET_KEY), or neither")
    if not args.store_access_key:
        token = env.get("VEYQ_BUNDLED_STORE_ADMIN_TOKEN", "")
        if token.strip():
            args.store_access_key, args.store_secret_key = derive_bundled_credentials(token)
    missing = [name for name, value in (
        ("--store-endpoint / VEYQ_BACKUP_STORE_ENDPOINT or VEYQ_BUNDLED_STORE_ENDPOINT", args.store_endpoint),
        ("a key pair: VEYQ_BACKUP_STORE_ACCESS_KEY + VEYQ_BACKUP_STORE_SECRET_KEY, or the bundled store's "
         "VEYQ_BUNDLED_STORE_ADMIN_TOKEN", args.store_access_key and args.store_secret_key),
    ) if not value]
    if missing:
        raise BackupError(
            "the store leg needs to know the store, because the capture is cold and cannot ask the app (ADR 0089): "
            "the bundled store from the recipe's environment, or your own store's endpoint and key pair. Missing: "
            + "; ".join(missing)
        )


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        prog="instance_backup.py",
        description="Capture, check and restore an instance's three durable legs (ADR 0056).",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    capture = sub.add_parser("capture", help="capture the three legs into a backup directory (app STOPPED)")
    capture.add_argument("--into", default=None,
                         help=f"backup directory (default: {DEFAULT_BACKUP_ROOT.relative_to(REPO)}/<UTC-ISO8601>)")
    key_to = capture.add_mutually_exclusive_group()
    key_to.add_argument("--key-into", default=None, metavar="FILE",
                        help="where leg (c) is copied: a new file OUTSIDE the capture directory, ideally on another "
                             "medium (ADR 0056 §1). Required whenever a key is found, unless --key-beside-dump")
    key_to.add_argument("--key-beside-dump", action="store_true",
                        help="put leg (c) in the capture directory, beside the dump — which together open every "
                             "tenant's SMTP password. Only knowingly")
    key_to.add_argument("--key-kept-apart", action="store_true",
                        help="record leg (c) as kept elsewhere (placement separate) WITHOUT reading any key — the "
                             "cluster gate's form (ADR 0098 decision 4c); a restore then asks for --key-from")
    capture.add_argument("--store-leg", choices=(MIRROR, INDEX), default=MIRROR,
                         help="mirror: the store's objects into objects/ (backupFormat 2, the default); index: their "
                              "keys, sizes and MD5s only, the objects left in the store (backupFormat 3, ADR 0098)")
    capture.add_argument("--app-version", default=None,
                         help="the release the instance runs, as the caller knows it — instead of asking Docker")
    add_common(capture)
    capture.set_defaults(func=cmd_capture, needs_store=True)

    verify = sub.add_parser("verify", help="check a backup directory without touching any instance")
    verify.add_argument("backup")
    verify.add_argument("--key-from", default=None, metavar="FILE",
                        help="the separately kept key of this capture, to check it too")
    verify.add_argument("--against-store", action="store_true",
                        help="an index capture: also list the live store and check every object the rows require "
                             "is there, as indexed (A4)")
    add_store(verify)
    verify.add_argument("--docker-network", default=None, help="docker network the rclone container joins")
    verify.set_defaults(func=cmd_verify, needs_store=False)

    restore = sub.add_parser("restore", help="restore a backup onto an EMPTY instance")
    restore.add_argument("backup")
    restore.add_argument("--domain", required=True,
                         help="the domain this instance will serve — must be one the backup held (ADR 0056 §4)")
    key_from = restore.add_mutually_exclusive_group()
    key_from.add_argument("--key-from", default=None, metavar="FILE",
                          help="the separately kept key of this capture (manifest configSecret.placement = separate)")
    key_from.add_argument("--without-key", action="store_true",
                          help="restore without leg (c), knowingly: the instance reports MISMATCH and refuses "
                               "invites and resets until the key or each SMTP password is back (A2)")
    restore.add_argument("--accept-missing-objects", action="store_true",
                         help="an index capture: restore although objects the rows require are missing from (or "
                              "changed in) the live store — each is a broken picture (A4), named at the end. Only "
                              "knowingly, when the store's versioning cannot bring them back")
    restore.add_argument("--key-as-runtime-user", action="store_true",
                         help="this restore runs as the application's own uid (a Kubernetes Job's runAsUser): the "
                              "key is born to it, whoever owns the claim's directory (fsGroup gives it a group, "
                              "never an owner)")
    restore.add_argument("--record", default=None, metavar="DIR",
                         help="the cluster's backup volume: as the last step, write its state.json naming the "
                              "backup's release, so that release's next start passes the gate (ADR 0098 decision 9)")
    add_common(restore)
    restore.set_defaults(func=cmd_restore, needs_store=True)

    gate = sub.add_parser("gate", help="in the app pod, before the container that migrates: capture when the pod "
                                       "starts a different release or a new request (ADR 0098)")
    gate.add_argument("--dir", default=os.environ.get("VEYQ_BACKUP_DIR") or "/backup",
                      help="the backup volume (env VEYQ_BACKUP_DIR; default /backup)")
    gate.add_argument("--target-release", default=os.environ.get("VEYQ_BACKUP_TARGET_RELEASE"),
                      help="the release the app container is about to start (env VEYQ_BACKUP_TARGET_RELEASE)")
    gate.add_argument("--request", default=os.environ.get("VEYQ_BACKUP_REQUEST", ""),
                      help="the capture request, an opaque value; a new one captures (env VEYQ_BACKUP_REQUEST)")
    gate.add_argument("--keep", type=int, default=os.environ.get("VEYQ_BACKUP_KEEP") or "3",
                      help="complete captures kept, the newest always (env VEYQ_BACKUP_KEEP; default 3)")
    gate.add_argument("--release-file", default=str(RELEASE_FILE),
                      help=f"what the tools image bakes (default {RELEASE_FILE})")
    add_pod_db(gate)
    gate.add_argument("--rclone-binary", default=shutil.which("rclone"), help=argparse.SUPPRESS)
    gate.set_defaults(func=cmd_gate, needs_store=False, db_name="veyq", db_host=None, db_port=3306,
                      compose_file=str(DEFAULT_COMPOSE), db_service="mariadb", db_image=DB_IMAGE, docker_network=None,
                      rclone_image=RCLONE_IMAGE, store_endpoint=None, store_access_key=None, store_secret_key=None,
                      store_bucket="blobs", store_region="us-east-1")

    args = parser.parse_args(argv)
    os.umask(OWNER_ONLY_UMASK)
    try:
        if getattr(args, "db_url", None) and not getattr(args, "rclone_binary", None) and args.command != "gate":
            # The in-pod mode is one mode: the database by URL AND the store by the image's own rclone, both on PATH.
            args.rclone_binary = shutil.which("rclone")
            if args.needs_store and not args.rclone_binary:
                raise BackupError("rclone is not on PATH. The in-pod mode (--db-url) runs the veyq-tools image's "
                                  "binaries and has no Docker to fall back to — run it in that image.")
        if args.needs_store or getattr(args, "against_store", False):
            check_store_args(args)
        return args.func(args)
    except BackupError as e:
        print(f"[backup] {e}", file=sys.stderr, flush=True)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
