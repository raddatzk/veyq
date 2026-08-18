#!/usr/bin/env python3
"""Capture an instance's three durable legs, check a capture, and restore it onto an empty instance.

The executable form of [uc.admin.back-up-and-restore-instance] and
[ADR 0056](../docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md): an instance's durable
state is exactly **(a)** the database, **(b)** the object store's *contents*, **(c)** the config-secret key
file — and a procedure that captures fewer than three produces an instance that boots, serves pages, and is
still broken. The commands live in [infra/README](../infra/README.md) § Backup & restore; this file is what
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

Three subcommands, one file, because they share the layout, the manifest and the credential plumbing:

    python3 scripts/instance_backup.py capture  [--into DIR]
    python3 scripts/instance_backup.py verify   DIR
    python3 scripts/instance_backup.py restore  DIR --domain <d>

A backup is a directory, defaulting to `infra/backups/<UTC-ISO8601>/`:

    manifest.json      # domains, app+schema version, object count/bytes, the blob index, capture window
    database.sql       # mariadb-dump WITH DDL and the flyway history
    objects/           # the rclone mirror of the bucket, one file per object key
    config-secret.key  # leg (c), 0600

**Why the manifest is not decoration.** It is what lets `verify` and `restore` refuse before doing damage:
`restore` demands `--domain` and refuses unless it names a tenant this backup actually held (ADR 0056 §4 —
a restore onto a different hostname yields an instance nobody can reach, holding mailed links nobody can
follow), and `verify` answers A4 (*objects missing relative to the rows that reference them*) from the blob
index without needing a database at all. That check is the one thing a volume copy cannot offer, and it is the
whole reason the store leg is captured logically (ADR 0056 §3).

**`verify` is deliberately asymmetric, and the asymmetry is the read path's.** An object with no row is an
ordinary steady state — the row delete and its `veyq_store_reclaim` entry share one transaction and a single
sweep drains that list, so a capture taken between them holds objects whose rows are gone (ADR 0062) — so
orphans are reported and tolerated. (Abandoned upload sessions themselves are reclaimed;
`V11__media.sql:9`'s "a later slice" note is a historical artifact of that migration.) A row with no object is
fatal: it presigns a valid URL that 404s, with no server-side error and a broken picture as the only symptom
(ADR 0056 § Context 2).

**Credentials never reach `argv`.** The store remote is defined entirely through `RCLONE_CONFIG_<REMOTE>_*` in
the child environment and the database password through `MYSQL_PWD`, both passed as bare `-e NAME` flags so
`ps` never sees a value and no config file is written. They are read from **the same variables the backend
already reads**, so an operator who can start the app already holds everything `capture` needs.

**`sync` out, `copy` in — never the reverse.** `sync` deletes at the destination, which is right for a capture
directory (a re-run must not silently become the union of two captures) and catastrophic against a live
bucket. `restore` refuses a non-empty target outright rather than reconciling it (A5).

**What this does not do.** It does not collect the instance's garbage: abandoned upload sessions and orphaned
objects are captured as they are, because they are the running system's steady state and a restore is not
where they get collected. It does not change a domain (ADR 0056 §4). It does not upgrade or downgrade a
schema — the dump carries `flyway_schema_history`, so a restored instance boots at the version it was captured
at and `quarkus.flyway.migrate-at-start=true` is a no-op rather than a re-derivation. And it does not verify
the *database* leg beyond structure: the dump's rows are ciphertext this tool cannot read, by design.

Environment (the app's own variables — nothing new to configure):
    VEYQ_BACKUP_DB_PASSWORD     Database password. Required by `capture` and `restore`.
    VEYQ_BLOB_STORE_ENDPOINT    S3 endpoint the *rclone container* must reach — an internal name such as
                                `http://garage:3900`, not a host-published port.
    VEYQ_BLOB_STORE_ACCESS_KEY  Store credentials.
    VEYQ_BLOB_STORE_SECRET_KEY
    VEYQ_BLOB_STORE_BUCKET      Default `veyq`.
    VEYQ_BLOB_STORE_REGION      Default `us-east-1`.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_COMPOSE = REPO / "infra" / "docker-compose.yml"
DEFAULT_BACKUP_ROOT = REPO / "infra" / "backups"

# Pinned, like every other moving part of the bundled stack (mariadb:11.4, dxflrs/garage:v2.0.0, traefik:v3.3,
# axllent/mailpit:v1.20.0). Pinning is what makes an OLD backup's restorability independent of whatever the
# host happens to carry years later — and neither tool is installed on a typical dev host anyway, so a host
# install would be a new prerequisite for the runbook and for CI.
RCLONE_IMAGE = "rclone/rclone:1.75.0"
DB_IMAGE = "mariadb:11.4"

# The rclone remote's name. Only ever appears in `RCLONE_CONFIG_<NAME>_*` and in `<name>:<bucket>` — never in a
# config file, because none is written. rclone logs `NOTICE: Config file ... not found - using defaults` on
# every invocation and proceeds; that line on stderr is normal and is not an error.
REMOTE = "STORE"

MANIFEST_NAME = "manifest.json"
DUMP_NAME = "database.sql"
OBJECTS_DIR = "objects"
KEY_NAME = "config-secret.key"

# Bumped only when an older directory can no longer be read by this file. `verify` and `restore` refuse an
# unknown one rather than guessing, because guessing is how a restore half-works.
BACKUP_FORMAT = 1


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


class Database:
    """The database leg's two connection modes — the runbook has exactly two audiences and no more.

    **compose** (default) — `docker compose -f <file> exec -T <service> …`, the bundled stack, the form
    `scripts/run_web_e2e.py` already proves works against `mariadb:11.4`.

    **direct** (`--db-host`) — an operator's own database. The client binary is preferred from the host `PATH`
    and otherwise run inside the pinned [DB_IMAGE], because neither `mariadb-dump` nor `mysqldump` is present
    on a typical dev host — so a host install cannot be a prerequisite of the procedure.
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

    def _wrap(self, binary: str) -> list[str]:
        """The invocation prefix that puts `binary` in front of a database, with MYSQL_PWD in its environment.

        `-e MYSQL_PWD` is passed WITHOUT a value on purpose: both `docker compose exec` and `docker run` then
        take it from this process's environment, so the password is in no `argv` anywhere. `-i` keeps stdin
        attached, which `restore` needs to feed the dump.
        """
        if self.mode == "compose":
            return ["docker", "compose", "-f", str(self.compose_file), "exec", "-T",
                    "-e", "MYSQL_PWD", self.service, binary]
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
        if self.mode == "direct":
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
        """How many tables the target database holds. A restore target must hold zero (A5)."""
        rows = self.query(
            f"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '{self.name}';"
        )
        return int(rows[0][0])

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

    def describe(self) -> str:
        where = f"{self.host}:{self.port}" if self.mode == "direct" else f"compose service {self.service}"
        return f"database {self.name} ({where})"


# --------------------------------------------------------------------------------------------------------
# The store leg
# --------------------------------------------------------------------------------------------------------


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


def present_keys(objects: Path) -> set[str]:
    return {p.relative_to(objects).as_posix() for p in objects.rglob("*") if p.is_file()}


def read_manifest(backup: Path) -> dict:
    path = backup / MANIFEST_NAME
    if not path.is_file():
        raise BackupError(f"{path} is missing — this is not a backup directory this tool wrote")
    try:
        manifest = json.loads(path.read_text())
    except json.JSONDecodeError as e:
        raise BackupError(f"{path} is not readable JSON: {e}") from e
    fmt = manifest.get("backupFormat")
    if fmt != BACKUP_FORMAT:
        raise BackupError(
            f"{path} declares backupFormat {fmt!r}; this tool writes and reads {BACKUP_FORMAT}. Refusing to "
            f"guess at a layout it does not know."
        )
    return manifest


# --------------------------------------------------------------------------------------------------------
# capture
# --------------------------------------------------------------------------------------------------------


def cmd_capture(args: argparse.Namespace) -> int:
    db = Database(args, require_db_password())
    store = Store(args)
    backup = Path(args.into) if args.into else DEFAULT_BACKUP_ROOT / utc_stamp()
    if backup.exists() and any(backup.iterdir()):
        raise BackupError(f"{backup} already exists and is not empty — refusing to write a second capture into it")

    started = utc_now()
    log(f"capturing into {backup}")
    log("NOTE: the application must be STOPPED. With it running, a client can complete an upload between the "
        "dump and the mirror, producing a row whose object was never captured (ADR 0056 §2, A1).")
    backup.mkdir(parents=True, exist_ok=True)

    log(f"reading instance facts from {db.describe()}")
    domains = db.domains()
    schema = db.schema_version()
    blobs = db.blob_index()
    sessions = db.open_upload_sessions()
    if not domains:
        raise BackupError(
            f"{db.describe()} holds no tenant, so this instance has no domain to restore onto. Either the "
            f"database name is wrong or the instance was never set up."
        )

    log(f"dumping {db.describe()} (DDL + data + flyway history)")
    db.dump(backup / DUMP_NAME)

    log(f"mirroring {store.describe()}")
    objects = backup / OBJECTS_DIR
    objects.mkdir(exist_ok=True)
    store.mirror_out(objects)
    count, byte_total = store.size()

    release, release_source = app_release(Path(args.compose_file), args.app_service)
    if release:
        log(f"the instance being captured runs release {release} — the image a restore should be started under")
    else:
        # Named, never silent: a backup that cannot name its source release is still a valid backup; what it
        # cannot do is tell you which image to start it under, and it says so instead of implying one (A3).
        log(f"WARNING: this capture cannot name the release it came from ({release_source}). The backup is "
            f"still valid; what it cannot tell you is which image to restore it under.")

    key_source = Path(args.config_secret_file)
    key_present = key_source.is_file()
    if key_present:
        log(f"copying the config-secret key from {key_source}")
        shutil.copyfile(key_source, backup / KEY_NAME)
        (backup / KEY_NAME).chmod(0o600)
    else:
        # Not fatal, and loudly not fatal: a capture that refused here would leave the operator with nothing,
        # where a capture that records the absence leaves them with two thirds and a stated defect.
        log(f"WARNING: no config-secret key at {key_source} — this backup has TWO legs, not three. A restore "
            f"from it will boot, serve content, report configSecretState: MISMATCH, and refuse member invites "
            f"and password resets with 503 mail_unavailable (ADR 0055, A2). Point --config-secret-file at the "
            f"real path and capture again.")

    manifest = {
        "backupFormat": BACKUP_FORMAT,
        "domains": domains,
        "app": {"version": release, "versionSource": release_source},
        "database": {"name": db.name, "schemaVersion": schema, "bytes": (backup / DUMP_NAME).stat().st_size},
        "store": {
            "bucket": store.bucket,
            "objects": count,
            "bytes": byte_total,
            "blobs": blobs,
            "openUploadSessions": sessions,
        },
        "configSecret": {"present": key_present},
        "capture": {"startedAt": started, "finishedAt": utc_now()},
    }
    (backup / MANIFEST_NAME).write_text(json.dumps(manifest, indent=2) + "\n")

    log(f"captured: {len(domains)} domain(s) {domains}, schema V{schema}, {count} object(s) / {byte_total} B, "
        f"{len(blobs)} blob row(s), leg (c) {'present' if key_present else 'MISSING'}")
    log(f"now check it:  python3 scripts/instance_backup.py verify {backup}")
    return 0


# --------------------------------------------------------------------------------------------------------
# verify
# --------------------------------------------------------------------------------------------------------


def cmd_verify(args: argparse.Namespace) -> int:
    backup = Path(args.backup)
    manifest = read_manifest(backup)
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

    objects = backup / OBJECTS_DIR
    if not objects.is_dir():
        problems.append(f"{OBJECTS_DIR}/ is missing — leg (b) is not in this backup")
        present: set[str] = set()
    else:
        present = present_keys(objects)

    blobs = manifest["store"]["blobs"]
    required = expected_keys(blobs)
    missing = [key for key in required if key not in present]
    if missing:
        # Every missing key, not a count: the resolution is per-object, and a count cannot be acted on. This is
        # A4 — a row whose object is absent restores as a valid presigned URL that 404s, with no server-side
        # error anywhere. It is the failure a volume copy could not have shown at all.
        problems.append(
            f"{len(missing)} object(s) required by a blob row are missing from {OBJECTS_DIR}/ "
            f"(A4 — each restores as a Memory whose picture yields a valid URL and a 404):"
        )
        for key in missing[:50]:
            problems.append(f"    missing object  {key}")
        if len(missing) > 50:
            problems.append(f"    … and {len(missing) - 50} more")

    orphans = len(present) - (len(required) - len(missing))
    recorded = manifest["store"]["objects"]
    if len(present) != recorded:
        problems.append(
            f"{OBJECTS_DIR}/ holds {len(present)} file(s) but the manifest recorded {recorded} object(s) — "
            f"the mirror is not the one this manifest describes"
        )

    key_file = backup / KEY_NAME
    claims_key = manifest["configSecret"]["present"]
    if claims_key and not key_file.is_file():
        problems.append(f"the manifest records leg (c) as captured but {KEY_NAME} is not here")
    if key_file.is_file() and (key_file.stat().st_mode & 0o077):
        problems.append(f"{KEY_NAME} is group/world-readable ({key_file.stat().st_mode & 0o777:o}) — it must be 0600")

    if problems:
        print(f"BACKUP VERIFY FAILED for {backup}:")
        for p in problems:
            print("  -", p)
        return 1

    # Two headlines, two exit codes, one predicate: leg (c). "Structurally intact" and "restorable" are not the
    # same claim, and a backup missing the key file is exactly the one that looks fine and is not (A2).
    log(f"backup OK: {backup}" if claims_key else f"backup INCOMPLETE (leg (c) missing): {backup}")
    log(f"  domains            {manifest['domains']}")
    log(f"  schema             V{manifest['database']['schemaVersion']}")
    # The rollback target, stated or explicitly absent — never a bare `null` that reads as data (ADR 0057 §5).
    app = manifest.get("app") or {}
    if app.get("version"):
        log(f"  release            {app['version']} — restore this backup under that image")
    else:
        log(f"  release            unknown ({app.get('versionSource') or 'not recorded'}) — still a valid "
            f"backup, but it cannot tell you which image to start it under (A3)")
    log(f"  objects            {len(present)} present, {len(required)} required by {len(blobs)} blob row(s)")
    # Tolerated and named, never silent: an object with no row is the running system's steady state (a capture
    # taken between a row delete and the next reclaim sweep holds exactly these — ADR 0062). Reporting it is
    # what keeps "0 orphans" from being the only shape a reader recognises as healthy.
    log(f"  orphaned objects   {orphans} (tolerated — ADR 0056 § Context 2)")
    log(f"  open upload sess.  {manifest['store']['openUploadSessions']}")
    log(f"  config-secret key  {'present' if claims_key else 'ABSENT — this backup has two legs, not three'}")
    return 0 if claims_key else 2


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
    if args.domain not in domains:
        raise BackupError(
            f"this backup holds {domains}; you asked to restore onto {args.domain!r}. A restore targets the "
            f"SAME domain (ADR 0056 §4) — the tenant's domain is unique and is written into every invitation "
            f"and recovery link already mailed. Restore onto one of {domains}, or treat the target as a new "
            f"instance."
        )

    db = Database(args, require_db_password())
    store = Store(args)

    # A5 — the restore is defined onto an EMPTY instance and nothing reconciles two populated ones.
    tables = db.table_count()
    if tables:
        raise BackupError(
            f"{db.describe()} already holds {tables} table(s). A restore is defined onto an empty instance "
            f"(A5); loading a dump onto an existing schema produces a state no decision describes. Empty it "
            f"first, then restore."
        )
    count, _ = store.size()
    if count:
        raise BackupError(
            f"{store.describe()} already holds {count} object(s). A restore is defined onto an empty store "
            f"(A5) — this tool copies INTO a bucket and never deletes from one. Empty it first, then restore."
        )

    log(f"loading {DUMP_NAME} into {db.describe()}")
    db.load(backup / DUMP_NAME)

    log(f"pushing objects into {store.describe()}")
    try:
        store.mirror_in(backup / OBJECTS_DIR)
    except BackupError as e:
        # The database leg is already in. Say so, because the emptiness gates above will now refuse the retry
        # for a reason that reads like a different problem ("already holds N tables") if nobody said why.
        raise BackupError(
            f"{e}\n[backup] The database leg IS already loaded, so the target is half-restored and this tool "
            f"will refuse to run against it again (A5). Empty the target database and bucket, then restore "
            f"from the start — a partial restore is not repaired in place."
        ) from e

    key_file = backup / KEY_NAME
    target_key = Path(args.config_secret_file)
    if key_file.is_file():
        log(f"placing the config-secret key at {target_key}")
        target_key.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(key_file, target_key)
        target_key.chmod(0o600)
    else:
        log(f"WARNING: this backup carries no {KEY_NAME}. The instance will start and serve content, report "
            f"configSecretState: MISMATCH, and refuse member invites and password resets with "
            f"503 mail_unavailable until the key is restored or each tenant owner re-enters their SMTP "
            f"password (ADR 0055, A2).")

    restored, restored_bytes = store.size()
    log(f"restored: schema V{manifest['database']['schemaVersion']}, {restored} object(s) / {restored_bytes} B")
    log("Now start the application, then read its config-secret state — that is the step that separates a "
        "restore from a hope (ADR 0056 §5): GET /api/tenants/{id}/mail-settings must report "
        "configSecretState: OK, and a person must be able to sign in and open a Memory that has media.")
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
    parser.add_argument("--db-user", default="root")
    parser.add_argument("--db-image", default=DB_IMAGE,
                        help="pinned client image used in direct mode when no host client is installed")
    parser.add_argument("--docker-network", default=None,
                        help="docker network the rclone (and direct-mode database) container joins — it must "
                             "reach the store's endpoint. e.g. infra_default for the bundled stack.")
    parser.add_argument("--store-endpoint", default=os.environ.get("VEYQ_BLOB_STORE_ENDPOINT"),
                        help="S3 endpoint AS THE CONTAINER SEES IT (e.g. http://garage:3900), not a published port")
    parser.add_argument("--store-access-key", default=os.environ.get("VEYQ_BLOB_STORE_ACCESS_KEY"))
    parser.add_argument("--store-secret-key", default=os.environ.get("VEYQ_BLOB_STORE_SECRET_KEY"))
    parser.add_argument("--store-bucket", default=os.environ.get("VEYQ_BLOB_STORE_BUCKET", "veyq"))
    parser.add_argument("--store-region", default=os.environ.get("VEYQ_BLOB_STORE_REGION", "us-east-1"))
    parser.add_argument("--rclone-image", default=RCLONE_IMAGE)
    parser.add_argument("--rclone-binary", default=None,
                        help="use a host rclone install instead of the pinned container")
    parser.add_argument("--config-secret-file", default="config-secret.key",
                        help="leg (c): where the key file lives on the app's filesystem "
                             "(veyq.security.config-secret-file / VEYQ_SECURITY_CONFIG_SECRET_FILE)")


def check_store_args(args: argparse.Namespace) -> None:
    missing = [name for name, value in (
        ("--store-endpoint / VEYQ_BLOB_STORE_ENDPOINT", args.store_endpoint),
        ("--store-access-key / VEYQ_BLOB_STORE_ACCESS_KEY", args.store_access_key),
        ("--store-secret-key / VEYQ_BLOB_STORE_SECRET_KEY", args.store_secret_key),
    ) if not value]
    if missing:
        raise BackupError(
            "the store leg needs credentials, and they are the SAME ones the backend already reads — nothing "
            "new to configure. Missing: " + ", ".join(missing)
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
    add_common(capture)
    capture.set_defaults(func=cmd_capture, needs_store=True)

    verify = sub.add_parser("verify", help="check a backup directory without touching any instance")
    verify.add_argument("backup")
    verify.set_defaults(func=cmd_verify, needs_store=False)

    restore = sub.add_parser("restore", help="restore a backup onto an EMPTY instance")
    restore.add_argument("backup")
    restore.add_argument("--domain", required=True,
                         help="the domain this instance will serve — must be one the backup held (ADR 0056 §4)")
    add_common(restore)
    restore.set_defaults(func=cmd_restore, needs_store=True)

    args = parser.parse_args(argv)
    try:
        if args.needs_store:
            check_store_args(args)
        return args.func(args)
    except BackupError as e:
        print(f"[backup] {e}", file=sys.stderr, flush=True)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
