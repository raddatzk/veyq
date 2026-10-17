#!/usr/bin/env python3
"""Pin who owns the config-secret key a restore places, without Docker (inbox #188).

`scripts/instance_backup.py restore` used to write the key as whoever ran it. On the production recipe that is
root, writing into the `veyq-config-secret` volume Docker seeded from the image as `10001:10001` — and the app,
running as uid 10001, cannot read a `root:root 0600` file: `ConfigSecretSeal.readOrGenerate` swallows the
`AccessDeniedException`, the state reads `ABSENT`, and invites and resets are refused with the key sitting right
there. The seal generates its key lazily, so a freshly installed target usually holds no key at all and the
restore took exactly the creating branch that bites. The other half: placement ran after the database and the
objects were loaded, so a refusal there left a half-restored instance the A5 gate then refused to retry.

This asserts, with no daemon and no root:
  1. every branch of `key_owner_plan` — the key takes the owner of its directory; root hands it over, a non-root
     invoker facing a directory it does not own is refused;
  2. one real `place_key` into a temporary directory this process owns — `0600`, owned by the invoker, the
     bytes intact, a looser existing mode tightened;
  3. that `cmd_restore` refuses an unplaceable key BEFORE it constructs its `Database`, the seam in front of the
     first write — with a positive control on the same fixture that does reach that seam, so a refusal cannot be
     a fixture that never got that far;
  4. that `cmd_capture` run by a user who cannot read the key (inbox #250: on the production recipe it lives on
     the app's uid-10001 volume) refuses with a message naming `sudo -E`, not a `PermissionError` traceback, and
     before the capture directory exists — for a key whose directory it cannot search and for one it cannot
     read — with the same kind of positive control. Skipped as root, which reads both;
  5. the restore's version gate (ADR 0056 §7): Flyway's version order where string order disagrees (`9` vs
     `44`, a longer number that sorts first as a string, trailing zeros), that a NEWER backup is refused with a
     message naming the release it came from (and saying so when the manifest records none), that the same and
     an older one are accepted, that a target with no applied migration is refused, and the closing log an older
     backup gets — that it will be migrated up, and that a rollback needs the backup's own release;
  6. the emptiness gate's exclusions (`NOT_DATA`): the object-store setting is among them, every one still names
     a table a migration creates, and every table a migration seeds with literal rows is among them;
  7. the store's credentials for a run (ADR 0089): the bundled store's key pair derived from its admin token — one
     pinned vector, the one `BundledStoreCredentialsTest` pins on the backend's side — an operator's own pair winning
     over a token, half a pair refused, and a run that names no store refused naming both ways;
  8. the cluster's gate (G1–G8, behind fake Database and Store seams), and that every capture is the index (G6);
  9. the object pool (P1–P9, ADR 0056 §3 and §7): the REAL Store and Pool driving an rclone interpreted in memory
     (`Rclone`, through `--rclone-binary`, so every argv and environment the tool builds is the one it would run) —
     the top-up of exactly the index's keys, a short pool failing the capture, the prune keeping exactly what the
     kept captures' indexes name and never running on a ledger without the new entry, the builder's copy-only shape,
     no pool credential in the manifest, and the restore's empty-store gate, source check and copy-before-load.

What it cannot show: an actual `fchown` to another uid (that needs root), and that uid 10001 then reads the key
in a running container — that is the live proof on the production recipe, recorded where the coverage of
`uc.admin.back-up-and-restore-instance` says. Not a `validate_*.py`: it asserts nothing about the repository,
only about the tool, and it runs beside the tool's own rehearsal in `ci.yml`'s `web-e2e` job. Runnable locally:

    python3 scripts/instance_backup_selftest.py
"""
from __future__ import annotations  # the repo's scripts run on Python 3.9

import argparse
import contextlib
import hashlib
import io
import json
import os
import pathlib
import re
import stat
import subprocess
import sys
import tempfile
import types

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import instance_backup as ib  # noqa: E402

failures: list[str] = []


def check(label: str, ok: bool, detail: str = "") -> None:
    print(f"{'ok  ' if ok else 'FAIL'} {label}{(' — ' + detail) if detail and not ok else ''}")
    if not ok:
        failures.append(label)


def plan_cases() -> None:
    P = ib.key_owner_plan
    cases = [
        # (label, args, expected action, expected uid:gid or None)
        ("root over a 10001-owned volume, no key yet (the #188 case)", (0, 10001, 10001, None), ib.CHOWN, (10001, 10001)),
        ("root over a 10001-owned volume, the app's own key there", (0, 10001, 10001, 10001), ib.AS_IS, None),
        ("root over a 10001-owned volume, a root-owned key from an earlier restore", (0, 10001, 10001, 0), ib.CHOWN, (10001, 10001)),
        ("root into a root:root directory", (0, 0, 0, None), ib.AS_IS, None),
        ("the dev loop: invoker owns the directory, no key", (501, 501, 20, None), ib.AS_IS, None),
        ("the dev loop: invoker owns the directory and the key", (501, 501, 20, 501), ib.AS_IS, None),
        ("non-root invoker, directory owned by 10001", (501, 10001, 10001, None), ib.REFUSE, None),
        ("non-root invoker, own directory, key owned by 10001", (501, 501, 20, 10001), ib.REFUSE, None),
    ]
    for label, args, action, owner in cases:
        plan = P(*args)
        ok = plan.action == action and (owner is None or (plan.uid, plan.gid) == owner)
        check(f"plan: {label} → {action}{' ' + str(owner) if owner else ''}", ok, f"got {plan}")
    refused = P(501, 10001, 10001, None)
    check("plan: the refusal names both ways out (root, or copy through the app container)",
          "root" in refused.reason and "docker cp" in refused.reason and "kubectl cp" in refused.reason, refused.reason)


def place_cases(tmp: pathlib.Path) -> None:
    source = tmp / "kept.key"
    source.write_bytes(b"restored-key-bytes\n")
    target_dir = tmp / "target"
    target_dir.mkdir()
    target = target_dir / ib.KEY_NAME
    plan = ib.place_key(source, target)
    st = target.stat()
    check("place: a fresh key is born 0600, owned by the invoker, bytes intact",
          plan.action == ib.AS_IS and stat.S_IMODE(st.st_mode) == 0o600 and st.st_uid == os.geteuid()
          and target.read_bytes() == source.read_bytes(), f"plan={plan} mode={oct(st.st_mode)} uid={st.st_uid}")
    target.write_bytes(b"generated-by-a-fresh-install")
    os.chmod(target, 0o644)
    ib.place_key(source, target)
    check("place: an existing key with a looser mode is overwritten and tightened to 0600",
          stat.S_IMODE(target.stat().st_mode) == 0o600 and target.read_bytes() == source.read_bytes())
    # A restore Job runs as the application's uid on a claim whose directory is root-owned (fsGroup gives a group,
    # never an owner): --key-as-runtime-user places the key as this process, and refuses root and a stranger's key.
    runtime_dir = tmp / "claim"
    runtime_dir.mkdir()
    runtime_key = runtime_dir / ib.KEY_NAME
    check("place --key-as-runtime-user: the key is born to this process's uid, 0600",
          ib.place_key(source, runtime_key, True).action == ib.AS_IS and runtime_key.stat().st_uid == os.geteuid()
          and stat.S_IMODE(runtime_key.stat().st_mode) == 0o600)
    saved = ib.effective_uid
    for label, euid in (("as root, which would make the key root's", 0), ("over a key another uid owns", 4242)):
        ib.effective_uid = lambda euid=euid: euid
        try:
            ib.key_gate(runtime_key, True)
            check(f"gate --key-as-runtime-user: refused {label}", False, "no BackupError")
        except ib.BackupError as e:
            check(f"gate --key-as-runtime-user: refused {label}", True, str(e))
        finally:
            ib.effective_uid = saved
    try:
        ib.key_gate(tmp / "missing-dir" / ib.KEY_NAME)
        check("gate: a missing directory is refused, not created", False, "no BackupError")
    except ib.BackupError as e:
        check("gate: a missing directory is refused, not created",
              "does not exist" in str(e) and not (tmp / "missing-dir").exists(), str(e))


class ReachedDatabase(Exception):
    """Raised by the stub `Database` — the restore got past every gate to the seam in front of its first write."""


def restore_reaches_seam(tmp: pathlib.Path, euid: int) -> tuple[str, bool]:
    """Drive `cmd_restore` with the effective uid planned as `euid`; report how it ended and whether it wrote."""
    backup = tmp / f"backup-{euid}"
    backup.mkdir()
    (backup / ib.MANIFEST_NAME).write_text(json.dumps({
        "backupFormat": ib.INDEX_FORMAT, "domains": ["veyq.test"], "configSecret": {"placement": ib.SEPARATE},
        "store": {"leg": ib.INDEX, "index": [], "blobs": []}}))
    kept = tmp / f"kept-{euid}.key"
    kept.write_bytes(b"k")
    target_dir = tmp / f"volume-{euid}"
    target_dir.mkdir()
    target = target_dir / ib.KEY_NAME
    args = argparse.Namespace(backup=str(backup), domain="veyq.test", key_from=str(kept), without_key=False,
                              config_secret_file=str(target))

    class StubDatabase:
        def __init__(self, *_a, **_k):
            raise ReachedDatabase()

    saved = (ib.Database, ib.require_db_password, ib.effective_uid)
    ib.Database, ib.require_db_password, ib.effective_uid = StubDatabase, (lambda: "x"), (lambda: euid)
    try:
        ib.cmd_restore(args)
        outcome = "returned"
    except ReachedDatabase:
        outcome = "reached-database"
    except ib.BackupError as e:
        outcome = f"refused: {e}"
    finally:
        ib.Database, ib.require_db_password, ib.effective_uid = saved
    return outcome, target.exists()


def restore_cases(tmp: pathlib.Path) -> None:
    me = os.geteuid()
    stranger = 4242 if me != 4242 else 4243
    outcome, wrote = restore_reaches_seam(tmp, me)
    check("restore (control): a placeable key passes the gates and reaches the database seam",
          outcome == "reached-database" and not wrote, f"{outcome}, wrote={wrote}")
    outcome, wrote = restore_reaches_seam(tmp, stranger)
    check("restore: an unplaceable key is refused at the gates, before the database is touched",
          outcome.startswith("refused:") and "cannot hand" in outcome and not wrote, f"{outcome}, wrote={wrote}")


def capture_reaches_seam(tmp: pathlib.Path, label: str, lock) -> tuple[str, bool]:
    """Drive `cmd_capture` against a key `lock` makes unreadable (or not); report how it ended and whether the
    capture directory was created."""
    volume = tmp / f"volume-{label}"
    volume.mkdir()
    key = volume / ib.KEY_NAME
    key.write_bytes(b"k")
    into = tmp / f"capture-{label}"
    args = argparse.Namespace(into=str(into), config_secret_file=str(key), key_beside_dump=False,
                              key_into=str(tmp / f"kept-{label}.key"))

    class StubDatabase:
        def __init__(self, *_a, **_k):
            pass

        def describe(self):
            return "stub"

        def domains(self):
            raise ReachedDatabase()

    class StubStore:
        def __init__(self, *_a, **_k):
            pass

    saved = (ib.Database, ib.Store, ib.require_db_password)
    ib.Database, ib.Store, ib.require_db_password = StubDatabase, StubStore, (lambda: "x")
    lock(volume, key)
    try:
        ib.cmd_capture(args)
        outcome = "returned"
    except ReachedDatabase:
        outcome = "reached-database"
    except ib.BackupError as e:
        outcome = f"refused: {e}"
    except OSError as e:
        outcome = f"traceback: {e!r}"
    finally:
        os.chmod(volume, 0o700)
        os.chmod(key, 0o600)
        ib.Database, ib.Store, ib.require_db_password = saved
    return outcome, into.exists()


def capture_cases(tmp: pathlib.Path) -> None:
    if os.geteuid() == 0:
        print("skip capture: running as root, which reads a key whatever its mode")
        return
    outcome, wrote = capture_reaches_seam(tmp, "readable", lambda volume, key: None)
    check("capture (control): a readable key passes the gate and reaches the database",
          outcome == "reached-database", f"{outcome}, wrote={wrote}")
    for label, lock in [
        ("an unsearchable volume", lambda volume, key: os.chmod(volume, 0o000)),
        ("an unreadable key", lambda volume, key: os.chmod(key, 0o000)),
    ]:
        outcome, wrote = capture_reaches_seam(tmp, label.replace(" ", "-"), lock)
        check(f"capture: {label} is refused naming `sudo -E`, before the capture directory exists",
              outcome.startswith("refused:") and "sudo -E" in outcome and not wrote, f"{outcome}, wrote={wrote}")


def version_cases() -> None:
    order = ib.flyway_order
    check("flyway order: 44 is newer than 9 (string order says the opposite)", order("44") > order("9"))
    check("flyway order: 1000000000000 is newer than 202610041201 (string order says the opposite)",
          order("1000000000000") > order("202610041201"))
    check("flyway order: 1.10 is newer than 1.9", order("1.10") > order("1.9"))
    check("flyway order: 1.0 equals 1, and 2_1 equals 2.1", order("1.0") == order("1") and order("2_1") == order("2.1"))

    def manifest(schema, release):
        return {"database": {"schemaVersion": schema}, "app": {"version": release}}

    def outcome(m, target):
        try:
            return ib.backup_against_target(m, target)
        except ib.BackupError as e:
            return f"refused: {e}"

    newer = outcome(manifest("44", "0.17"), "9")
    check("a backup at V44 onto a target at V9 is refused, naming its release 0.17",
          newer.startswith("refused:") and "NEWER" in newer and "release 0.17" in newer, newer)
    unnamed = outcome(manifest("44", None), "9")
    check("a newer backup whose manifest names no release says so",
          unnamed.startswith("refused:") and "unknown release" in unnamed, unnamed)
    check("a backup at V9 onto a target at V44 is older, not refused (string order would refuse it)",
          outcome(manifest("9", "0.3"), "44") == ib.OLDER)
    check("the same schema is accepted as the same", outcome(manifest("44", "0.3"), "44.0") == ib.SAME)
    unmigrated = outcome(manifest("44", "0.3"), None)
    check("a target with no applied migration is refused, naming the first start",
          unmigrated.startswith("refused:") and "Start the application once" in unmigrated, unmigrated)
    notice = ib.older_backup_notice(manifest("9", "0.3"), "44")
    check("the older-backup closing log says the data is migrated up at the next start",
          "migrate the data to V44 at its next start" in notice, notice)
    check("the older-backup closing log says a rollback installs the backup's own release",
          "ROLLBACK" in notice and "release 0.3" in notice and "A7" in notice, notice)


def not_data_cases() -> None:
    """The restore's emptiness gate (A5) must not read a table a fresh install holds rows in as data — and must not
    name a table that no longer exists, which would be a gate with a hole nobody sees. Both halves are read from the
    migrations, not recalled (ADR 0089: the object-store setting is one row once a Server Owner has chosen a store)."""
    root = pathlib.Path(__file__).resolve().parent.parent
    created, seeded = set(), set()
    migrations = list(root.glob("backend/**/src/main/resources/db/migration/*.sql"))
    if not (root / "backend").is_dir():
        # The veyq-tools image ships the tool and this self-test, not the backend: there no migration is beside it,
        # and the repository's run (ci.yml's web-e2e job) is the one that reads them. A checkout always has backend/,
        # so a migration set that vanished from one is a failure below, never this skip.
        print(f"skip not-data: no {root / 'backend'} — this copy of the tool ships without the backend")
        return
    check("the migrations are found", bool(migrations), str(root / "backend"))
    for sql in migrations:
        text = sql.read_text()
        created.update(re.findall(r"CREATE TABLE (\w+)", text))
        # A literal seed (`INSERT INTO t (…) VALUES`) puts rows into every fresh install; an `INSERT … SELECT`
        # backfill copies rows that a fresh install does not have, so only the VALUES form makes a table non-empty.
        seeded.update(re.findall(r"INSERT\s+(?:IGNORE\s+)?INTO\s+(\w+)\s*(?:\([^)]*\))?\s*VALUES", text, re.I))
    check("the gate excludes the object-store setting, which a Server Owner fills before a restore",
          "veyq_store_setting" in ib.NOT_DATA)
    check("every table the gate excludes is one a migration creates (flyway's own record aside)",
          all(t in created for t in ib.NOT_DATA if t != "flyway_schema_history"),
          str(sorted(t for t in ib.NOT_DATA if t != "flyway_schema_history" and t not in created)))
    check("the gate excludes the capability switches, which a Server Owner may set before a restore",
          "veyq_capability_switch" in ib.NOT_DATA)
    check("every table a migration seeds is excluded — a fresh install holds those rows, so a restore onto it "
          "would be refused (veyq_external_service did exactly that, 2026-10-16)",
          seeded <= set(ib.NOT_DATA), str(sorted(seeded - set(ib.NOT_DATA))))
    check("the exclusion list is the seven named tables, no pattern", len(ib.NOT_DATA) == 7, str(ib.NOT_DATA))


# The vector `backend/core/.../BundledStoreCredentialsTest` pins from the other side: both halves must move together.
VECTOR_TOKEN = "veyq-dev-admin-token"
VECTOR_ACCESS_KEY = "GKd14f469abb39c11f4f86a0e3"
VECTOR_SECRET_KEY = "db5a958fff2b57263c0cb0f6963fb047ee2d21fc487250082d035ff35cb79b66"


def store_cases() -> None:
    """What `check_store_args` settles for a run: derived bundled credentials, an operator's pair, or a refusal."""
    check("bundled: the pair derived from the pinned token is the pinned vector (the backend's side pins the same)",
          ib.derive_bundled_credentials(VECTOR_TOKEN) == (VECTOR_ACCESS_KEY, VECTOR_SECRET_KEY),
          str(ib.derive_bundled_credentials(VECTOR_TOKEN)))

    def args(**kw):
        base = dict(store_endpoint="http://garage:3900", store_access_key=None, store_secret_key=None)
        return argparse.Namespace(**{**base, **kw})

    def settle(a, env):
        try:
            ib.check_store_args(a, env)
            return None
        except ib.BackupError as e:
            return str(e)

    a = args()
    refused = settle(a, {"VEYQ_BUNDLED_STORE_ADMIN_TOKEN": VECTOR_TOKEN})
    check("bundled: a token and an endpoint, no typed credential, settles the derived pair",
          refused is None and (a.store_access_key, a.store_secret_key) == (VECTOR_ACCESS_KEY, VECTOR_SECRET_KEY), str(refused))
    a = args(store_access_key="GKown", store_secret_key="own-secret")
    refused = settle(a, {"VEYQ_BUNDLED_STORE_ADMIN_TOKEN": VECTOR_TOKEN})
    check("own store: the operator's pair wins over the bundled token",
          refused is None and (a.store_access_key, a.store_secret_key) == ("GKown", "own-secret"), str(refused))
    check("own store: half a pair is refused", settle(args(store_access_key="GKown"), {}) is not None)
    named = settle(args(), {}) or ""
    check("no store named: refused, naming the bundled way and the own-store way",
          "VEYQ_BUNDLED_STORE_ADMIN_TOKEN" in named and "VEYQ_BACKUP_STORE_ACCESS_KEY" in named, named)
    check("no endpoint: refused naming it",
          "ENDPOINT" in (settle(args(store_endpoint=None), {"VEYQ_BUNDLED_STORE_ADMIN_TOKEN": VECTOR_TOKEN}) or ""))


# --------------------------------------------------------------------------------------------------------
# The cluster's gate and the index capture (ADR 0098) — G1–G8, behind fake Database and Store seams
# --------------------------------------------------------------------------------------------------------

SCHEMA = "202610161746"
KEY_BYTES = b"the-config-secret-key-bytes\n"


class World:
    """What the fakes answer: a database that holds data or not, its blob rows, its store setting, the live listing."""

    def __init__(self, data: bool = True, blobs=(("sk1", 2),), listing=None, setting=None, dump_fails=False):
        self.data = data
        self.blobs = [list(b) for b in blobs]
        self.listing = [list(e) for e in (listing if listing is not None else
                                          [["sk1/0", 10, "a" * 32], ["sk1/1", 20, "b" * 32]])]
        self.setting = setting if setting is not None else ib.StoreRow(True, None, None, None)
        self.dump_fails = dump_fails
        self.calls: list[str] = []


class FakeDatabase:
    def __init__(self, world: World):
        self.w, self.name = world, "veyq"

    def populated_tables(self):
        return [("veyq_tenant", 1)] if self.w.data else []

    def domains(self):
        return ["veyq.test"]

    def schema_version(self):
        return SCHEMA

    def blob_index(self):
        return [list(b) for b in self.w.blobs]

    def open_upload_sessions(self):
        return 0

    def store_setting(self):
        return self.w.setting

    def dump(self, target):
        if self.w.dump_fails:
            raise ib.BackupError("mariadb-dump failed: No space left on device")
        target.write_text("CREATE TABLE `veyq_tenant` (id int);\nCREATE TABLE `flyway_schema_history` (v int);\n")

    def drop_all_tables(self):
        self.w.calls.append("drop")
        return 1

    def load(self, source):
        self.w.calls.append("load")

    def describe(self):
        return "the fake database"


class FakeStore:
    def __init__(self, world: World):
        self.w, self.bucket = world, "blobs"

    def index(self):
        self.w.calls.append("index")
        return sorted([list(e) for e in self.w.listing])

    def data_keys(self):
        return [e[0] for e in self.w.listing if e[0] != PROBE]

    def size(self):
        return len(self.w.listing), sum(e[1] for e in self.w.listing)

    def describe(self):
        return "the fake store"


class Seams:
    """Swap the tool's Database and Store for the fakes, and the password for a constant; restore on exit."""

    def __init__(self, world: World):
        self.world = world

    def __enter__(self):
        self.saved = (ib.Database, ib.Store, ib.require_db_password)
        ib.Database = lambda *_a, **_k: FakeDatabase(self.world)
        ib.Store = lambda *_a, **_k: FakeStore(self.world)
        ib.require_db_password = lambda: "x"
        return self.world

    def __exit__(self, *_exc):
        ib.Database, ib.Store, ib.require_db_password = self.saved


GATE_ENV = {"VEYQ_BUNDLED_STORE_ENDPOINT": "http://veyq-garage:3900", "VEYQ_BUNDLED_STORE_ADMIN_TOKEN": VECTOR_TOKEN}


def volume_state(root: pathlib.Path) -> list:
    """The volume as a refusal must leave it: every path with its size, and the record's exact bytes."""
    listing = sorted((p.relative_to(root).as_posix(), p.stat().st_size if p.is_file() else -1) for p in root.rglob("*"))
    record = (root / ib.STATE_NAME).read_bytes() if (root / ib.STATE_NAME).exists() else None
    return [listing, record]


def gate(tmp: pathlib.Path, name: str, world: World, *, target="0.5", image=None, entry=None, request="", keep=3,
         record=None, prepare=None):
    """One run of `cmd_gate` against a fresh volume: (outcome, volume root, the volume before the run)."""
    root = tmp / name
    root.mkdir()
    release_file = tmp / f"{name}-release.json"
    release_file.write_text(json.dumps({"release": image or target, "majorEntry": entry}))
    if isinstance(record, (bytes, str)):
        (root / ib.STATE_NAME).write_bytes(record if isinstance(record, bytes) else record.encode())
    elif record is not None:
        (root / ib.STATE_NAME).write_text(json.dumps(record))
    if prepare:
        prepare(root)
    before = volume_state(root)
    args = argparse.Namespace(dir=str(root), target_release=target, request=request, keep=keep,
                              release_file=str(release_file), db_url="jdbc:mariadb://veyq-mariadb:3306/veyq",
                              db_user="veyq", rclone_binary="rclone", store_endpoint=None, store_access_key=None,
                              store_secret_key=None, store_bucket="blobs", store_region="us-east-1")
    with Seams(world):
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                outcome = f"ok {ib.cmd_gate(args, GATE_ENV)}"
        except ib.BackupError as e:
            outcome = f"refused: {e}"
    return outcome, root, before


def captures(root: pathlib.Path) -> list[str]:
    folder = root / ib.CAPTURES_DIR
    return sorted(p.name for p in folder.iterdir()) if folder.is_dir() else []


def record_of(root: pathlib.Path) -> dict:
    return json.loads((root / ib.STATE_NAME).read_text())


def manifest_of(root: pathlib.Path, name: str) -> dict:
    return json.loads((root / ib.CAPTURES_DIR / name / ib.MANIFEST_NAME).read_text())


def guarded(group):
    """A group that cannot run (the untouched tree has no `gate`) reports one FAIL instead of ending the self-test."""
    def run(*a):
        try:
            group(*a)
        except Exception as e:  # noqa: BLE001 — every failure of a group is that group's red
            check(f"{group.__name__}: runs", False, repr(e))
    return run


@guarded
def g1_decision_table(tmp: pathlib.Path) -> None:
    outcome, root, _ = gate(tmp, "g1-empty", World(data=False))
    check("G1 gate: an empty database records the target and captures nothing",
          outcome == "ok 0" and captures(root) == [] and record_of(root)["release"] == "0.5", outcome)
    same = {"release": "0.5", "request": "r1", "capture": None, "writtenAt": "2026-01-01T00:00:00Z"}
    outcome, root, before = gate(tmp, "g1-same", World(), request="r1", record=same)
    check("G1 gate: the same release and request change nothing — a restart passes",
          outcome == "ok 0" and volume_state(root) == before, outcome)
    outcome, root, _ = gate(tmp, "g1-new", World(), record={"release": "0.4", "request": ""})
    done = captures(root)
    manifest = manifest_of(root, done[0]) if len(done) == 1 else {}
    check("G1 gate: a new release captures (format 3, the index, app.version the record's), then moves the record",
          outcome == "ok 0" and len(done) == 1 and manifest.get("backupFormat") == ib.INDEX_FORMAT
          and manifest["store"]["index"] == [["sk1/0", 10, "a" * 32], ["sk1/1", 20, "b" * 32]]
          and manifest["app"] == {"version": "0.4", "versionSource": "record"}
          and manifest["capture"].get("tool") == "0.5"
          and record_of(root)["release"] == "0.5" and record_of(root)["capture"] == done[0], f"{outcome} {done}")
    outcome, root, _ = gate(tmp, "g1-request", World(), request="r2", record={"release": "0.5", "request": "r1"})
    check("G1 gate: a new request alone captures and records the request",
          outcome == "ok 0" and len(captures(root)) == 1 and record_of(root)["request"] == "r2", outcome)
    outcome, root, _ = gate(tmp, "g1-norecord", World())
    done = captures(root)
    check("G1 gate: data with no record captures, the release named unknown",
          outcome == "ok 0" and len(done) == 1
          and manifest_of(root, done[0])["app"] == {"version": None, "versionSource": "no-record"}, outcome)


@guarded
def g2_refusals(tmp: pathlib.Path) -> None:
    cases = [
        # (label, refused run, its message fragment, the control one field away)
        ("a downgrade", dict(target="0.5", record={"release": "0.6", "request": ""}), "OLDER",
         dict(target="0.5", record={"release": "0.4", "request": ""})),
        ("two majors at once", dict(target="3.0", record={"release": "1.2", "request": ""}), "more than one major",
         dict(target="2.0", entry="1.2", record={"release": "1.2", "request": ""})),
        ("a major entered from another release than the baked one",
         dict(target="2.0", entry="1.2", record={"release": "1.1", "request": ""}), "enters major 2",
         dict(target="2.0", entry="1.2", record={"release": "1.2", "request": ""})),
        ("a target that is not this image's release", dict(target="0.6", image="0.5", record={"release": "0.4"}),
         "this veyq-tools image is release 0.5", dict(target="0.5", image="0.5", record={"release": "0.4"})),
        ("a target that is no release", dict(target="0.0-dev", record={"release": "0.4"}), "backup.enabled: false",
         dict(target="0.5", record={"release": "0.4"})),
        ("an unreadable record", dict(target="0.5", record=b"{not json"), ib.STATE_NAME,
         dict(target="0.5", record={"release": "0.4"})),
    ]
    for n, (label, refused, fragment, control) in enumerate(cases):
        outcome, root, before = gate(tmp, f"g2-{n}", World(), **refused)
        check(f"G2 gate refuses {label}, by its own message, writing nothing",
              outcome.startswith("refused:") and fragment in outcome and volume_state(root) == before, outcome)
        outcome, root, _ = gate(tmp, f"g2-{n}-control", World(), **control)
        check(f"G2 control: one field away from {label}, the gate captures",
              outcome == "ok 0" and len(captures(root)) == 1, outcome)


    def two_captures(root: pathlib.Path) -> None:
        for name, release in (("2020-01-01T00-00-00Z", "0.5"), ("2020-01-02T00-00-00Z", "0.6")):
            (root / ib.CAPTURES_DIR / name).mkdir(parents=True)
            (root / ib.CAPTURES_DIR / name / ib.MANIFEST_NAME).write_text(json.dumps({"app": {"version": release}}))
    outcome, _, _ = gate(tmp, "g2-way-back", World(), target="0.5", record={"release": "0.6"}, prepare=two_captures)
    check("G2 gate: a refused downgrade names the capture taken FROM the target release as the way back, not the newest",
          "restore the capture 2020-01-01T00-00-00Z, taken from release 0.5" in outcome, outcome)


@guarded
def g3_order(tmp: pathlib.Path) -> None:
    outcome, root, _ = gate(tmp, "g3", World(data=False), target="0.5", record={"release": "0.6", "request": ""})
    check("G3 gate: an emptied database under a record naming a newer release passes and records the target — the "
          "documented rollback, which the downgrade refusal must not reach",
          outcome == "ok 0" and record_of(root)["release"] == "0.5" and captures(root) == [], outcome)


def old_captures(root: pathlib.Path) -> None:
    for day in range(1, 6):
        (root / ib.CAPTURES_DIR / f"2020-01-0{day}T00-00-00Z").mkdir(parents=True)
    for day in (6, 7):
        (root / ib.CAPTURES_DIR / f"2020-01-0{day}T00-00-00Z{ib.PARTIAL}").mkdir(parents=True)


@guarded
def g4_retention(tmp: pathlib.Path) -> None:
    outcome, root, _ = gate(tmp, "g4", World(), keep=3, record={"release": "0.4"}, prepare=old_captures)
    left = captures(root)
    check("G4 retention: keep 3 over five complete and two .partial leaves the three newest, no .partial",
          outcome == "ok 0" and len(left) == 3 and left[:2] == ["2020-01-04T00-00-00Z", "2020-01-05T00-00-00Z"]
          and not any(name.endswith(ib.PARTIAL) for name in left), f"{outcome} {left}")
    record = {"release": "0.4", "request": "", "capture": "2020-01-05T00-00-00Z", "writtenAt": "2020-01-05T00:00:00Z"}
    outcome, root, _ = gate(tmp, "g4-fail", World(dump_fails=True), record=record, prepare=old_captures)
    left = captures(root)
    check("G4 retention: a failed capture prunes nothing, keeps its .partial and leaves the record",
          outcome.startswith("refused:") and sum(not n.endswith(ib.PARTIAL) for n in left) == 5
          and sum(n.endswith(ib.PARTIAL) for n in left) == 3 and record_of(root) == record, f"{outcome} {left}")
    outcome, root, before = gate(tmp, "g4-zero", World(), keep=0, record={"release": "0.4"})
    check("G4 retention: keep 0 is refused, writing nothing",
          outcome.startswith("refused:") and "keeps no capture" in outcome and volume_state(root) == before, outcome)


def index_capture(tmp: pathlib.Path, name: str, index) -> pathlib.Path:
    backup = tmp / name
    backup.mkdir(mode=0o700)
    (backup / ib.DUMP_NAME).write_text("CREATE TABLE a (b int);\n-- flyway_schema_history\n")
    (backup / ib.MANIFEST_NAME).write_text(json.dumps({
        "backupFormat": ib.INDEX_FORMAT, "domains": ["veyq.test"], "app": {"version": "0.4", "versionSource": "record"},
        "database": {"name": "veyq", "schemaVersion": SCHEMA, "bytes": 1},
        "store": {"leg": ib.INDEX, "bucket": "blobs", "objects": len(index), "bytes": 30, "blobs": [["sk1", 2]],
                  "openUploadSessions": 0, "index": index},
        "configSecret": {"placement": ib.SEPARATE}, "capture": {"startedAt": "x", "finishedAt": "y", "tool": "0.5"}}))
    return backup


def restore_index(tmp: pathlib.Path, name: str, listing, accept=False, record=None):
    """`cmd_restore` of a format-3 capture against a fake live listing: (outcome, calls, closing log)."""
    backup = index_capture(tmp, name, [["sk1/0", 10, "a" * 32], ["sk1/1", 20, "b" * 32]])
    world = World(data=False, listing=listing)
    args = argparse.Namespace(backup=str(backup), domain="veyq.test", key_from=None, without_key=True,
                              config_secret_file=str(tmp / f"{name}.key"), accept_missing_objects=accept,
                              record=record)
    out = io.StringIO()
    with Seams(world):
        try:
            with contextlib.redirect_stdout(out):
                outcome = f"ok {ib.cmd_restore(args)}"
        except ib.BackupError as e:
            outcome = f"refused: {e}"
    return outcome, world.calls, out.getvalue()


@guarded
def g5_index_restore(tmp: pathlib.Path) -> None:
    whole = [["sk1/0", 10, "a" * 32], ["sk1/1", 20, "b" * 32]]
    outcome, calls, _ = restore_index(tmp, "g5-whole", whole)
    check("G5 restore: an index capture whose objects are all in the live store loads, and never writes to the store",
          outcome == "ok 0" and calls == ["index", "drop", "load"], f"{outcome} {calls}")
    outcome, calls, _ = restore_index(tmp, "g5-missing", whole[:1])
    check("G5 restore: a missing object refuses naming it, before the load",
          outcome.startswith("refused:") and "missing object  sk1/1" in outcome and "load" not in calls, outcome)
    outcome, calls, _ = restore_index(tmp, "g5-size", [whole[0], ["sk1/1", 21, "b" * 32]])
    check("G5 restore: an object of another size refuses naming it, before the load",
          outcome.startswith("refused:") and "changed object  sk1/1" in outcome and "load" not in calls, outcome)
    outcome, calls, _ = restore_index(tmp, "g5-extra", whole + [["sk2/0", 5, "c" * 32]])
    check("G5 restore: an object the index does not hold (an upload after the capture) is tolerated",
          outcome == "ok 0" and "load" in calls, outcome)
    outcome, calls, log = restore_index(tmp, "g5-accept", whole[:1], accept=True)
    check("G5 restore: --accept-missing-objects restores without it and the closing log names the broken object",
          outcome == "ok 0" and "load" in calls and "broken object  sk1/1" in log, f"{outcome} {log[-300:]}")
    volume = tmp / "g5-volume"
    volume.mkdir()
    (volume / ib.STATE_NAME).write_text(json.dumps({"release": "0.6", "request": "r1"}))
    outcome, _, _ = restore_index(tmp, "g5-record", whole, record=str(volume))
    check("G5 restore: --record writes the backup's release as the last step, keeping the request",
          outcome == "ok 0" and record_of(volume)["release"] == "0.4" and record_of(volume)["request"] == "r1"
          and record_of(volume)["capture"] == "g5-record", outcome)


@guarded
def g6_compose_is_the_index(tmp: pathlib.Path) -> None:
    """B1 — every capture is the index (ADR 0056 §3): the default capture, which Compose and `veyqctl` take, writes
    backupFormat 3 with the store's listing and no `objects/` (on the tree before this rule it wrote the mirror)."""
    into, kept = tmp / "g6-capture", tmp / "g6-kept.key"
    key = tmp / "g6-config-secret.key"
    key.write_bytes(KEY_BYTES)
    world = World()
    with Seams(world), contextlib.redirect_stdout(io.StringIO()):
        rc = ib.main(["capture", "--into", str(into), "--key-into", str(kept), "--config-secret-file", str(key),
                      "--compose-file", str(tmp / "no-compose.yml"), "--store-endpoint", "http://garage:3900",
                      "--store-access-key", "GKx", "--store-secret-key", "s"])
    manifest = json.loads((into / ib.MANIFEST_NAME).read_text()) if (into / ib.MANIFEST_NAME).is_file() else {}
    store = manifest.get("store") or {}
    check("G6 compose: a default capture is the index — backupFormat 3, store.leg index, the listing, no objects/, "
          "no pool",
          rc == 0 and manifest.get("backupFormat") == 3 and store.get("leg") == "index"
          and store.get("index") == [["sk1/0", 10, "a" * 32], ["sk1/1", 20, "b" * 32]] and "pool" not in store
          and not (into / "objects").exists() and manifest.get("configSecret") == {"placement": ib.SEPARATE},
          json.dumps(manifest)[:300])
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        rc = ib.main(["verify", str(into), "--key-from", str(kept)])
    check("G6 compose: its verify answers A4 from the index and says no pool was named",
          rc == 0 and "2 indexed, 2 required" in out.getvalue() and "none named" in out.getvalue(),
          out.getvalue()[-400:])


def g7_url_parser() -> None:
    try:
        parse = ib.parse_jdbc_url
    except AttributeError as e:
        check("G7 url: the parser exists", False, repr(e))
        return
    check("G7 url: host, port, database and a query", parse("jdbc:mariadb://h:3307/veyq?x=y") == ("h", 3307, "veyq"))
    check("G7 url: no port is 3306", parse("jdbc:mariadb://veyq-mariadb/veyq") == ("veyq-mariadb", 3306, "veyq"))
    for label, url in (("a multi-host URL", "jdbc:mariadb://h1:3306,h2:3306/veyq"),
                       ("another driver's URL", "jdbc:mysql://h:3306/veyq")):
        try:
            parse(url)
            check(f"G7 url: {label} is refused naming the form", False, "parsed")
        except ib.BackupError as e:
            check(f"G7 url: {label} is refused naming the form", "jdbc:mariadb://host[:port]/database" in str(e), str(e))
    try:
        parse("jdbc:mysql://h/veyq?password=hunter2")
    except ib.BackupError as e:
        check("G7 url: a refusal never echoes the query string, which may carry a password", "hunter2" not in str(e))


@guarded
def g8_no_key(tmp: pathlib.Path) -> None:
    here = os.getcwd()
    work = tmp / "g8-cwd"
    work.mkdir()
    (work / ib.KEY_NAME).write_bytes(KEY_BYTES)  # the default --config-secret-file, readable
    os.chdir(work)
    try:
        outcome, root, _ = gate(tmp, "g8", World(), record={"release": "0.4"})
    finally:
        os.chdir(here)
    done = captures(root)
    files = [p for p in root.rglob("*") if p.is_file()]
    check("G8 gate: with a readable key at the default path it records leg (c) separate and copies no key",
          outcome == "ok 0" and len(done) == 1 and manifest_of(root, done[0])["configSecret"] == {"placement": "separate"}
          and not any(p.name == ib.KEY_NAME or KEY_BYTES in p.read_bytes() for p in files), outcome)


def gate_cases(tmp: pathlib.Path) -> None:
    g1_decision_table(tmp)
    g2_refusals(tmp)
    g3_order(tmp)
    g4_retention(tmp)
    g5_index_restore(tmp)
    g6_compose_is_the_index(tmp)
    g7_url_parser()
    g8_no_key(tmp)


# --------------------------------------------------------------------------------------------------------
# The object pool (ADR 0056 §3, §7) — P1–P9, the real Store and Pool driving an rclone that runs in memory
# --------------------------------------------------------------------------------------------------------

def md5(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()  # noqa: S324 — rclone's own hash type, not a security use


class Rclone:
    """The rclone the tool runs (`--rclone-binary`, so every path is a host path), interpreted against in-memory
    remotes: the store's bucket (`STORE:blobs`) and every pool root registered here, each `{key: (size, md5, bytes)}`.
    Any other path is the real filesystem (a `--files-from` list, a ledger entry's folder, a format-2 `objects/`).
    Every call is recorded with its environment and the contents of its `--files-from` list."""

    def __init__(self, store: dict | None = None):
        self.store = store if store is not None else {}
        self.pools: dict[str, dict] = {}
        self.calls: list[dict] = []
        self.order: list[str] | None = None   # a World's call log, to interleave with the database's
        self.drop_on_copy: set[str] = set()   # keys a copy into a pool silently loses (B8)
        self.ledger_blind = False             # `cat` of a ledger answers nothing (the prune's refusal)

    def pool(self, root: str) -> dict:
        return self.pools.setdefault(root, {})

    def _where(self, path: str):
        if path.startswith(f"{ib.REMOTE}:"):
            bucket, _, prefix = path[len(ib.REMOTE) + 1:].partition("/")
            return self.store, prefix
        for root, content in self.pools.items():
            if path == root or path.startswith(root + "/"):
                return content, path[len(root) + 1:]
        return None, path

    def _entries(self, path: str) -> dict:
        content, prefix = self._where(path)
        if content is None:
            base = pathlib.Path(prefix)
            return {p.relative_to(base).as_posix(): (p.stat().st_size, md5(p.read_bytes()), p.read_bytes())
                    for p in base.rglob("*") if p.is_file()} if base.is_dir() else {}
        lead = prefix + "/" if prefix else ""
        return {key[len(lead):]: value for key, value in content.items() if key.startswith(lead)}

    def run(self, cmd, capture_output=True, text=True, env=None, **_kw):
        argv = list(cmd[1:])
        verb, flags, paths, files_from, i = argv[0], set(), [], None, 1
        while i < len(argv):
            arg = argv[i]
            if arg in ("--files-from", "--hash-type", "--stats"):
                if arg == "--files-from":
                    files_from = pathlib.Path(argv[i + 1]).read_text().split()
                i += 2
                continue
            (flags.add(arg) if arg.startswith("-") else paths.append(arg))
            i += 1
        self.calls.append({"argv": argv, "env": dict(env or {}), "files_from": files_from})
        out, err, rc = "", "", 0
        if verb == "lsjson":
            entries = self._entries(paths[0])
            out = json.dumps([{"Path": k, "Size": v[0], **({"Hashes": {"md5": v[1]}} if "--hash" in flags else {})}
                              for k, v in sorted(entries.items()) if files_from is None or k in files_from])
        elif verb == "size":
            entries = self._entries(paths[0])
            out = json.dumps({"count": len(entries), "bytes": sum(v[0] for v in entries.values())})
        elif verb == "cat":
            entries = self._entries(paths[0])
            out = "" if self.ledger_blind else "".join(
                v[2].decode() for _, v in sorted(entries.items(), reverse=True))  # any order: the reader sorts
        elif verb in ("copy", "sync"):
            src, (dst, prefix) = self._entries(paths[0]), self._where(paths[1])
            lead = prefix + "/" if prefix else ""
            for key, value in sorted(src.items()):
                if files_from is not None and key not in files_from:
                    continue
                if dst is not self.store and key in self.drop_on_copy:
                    continue
                held = dst.get(lead + key)
                if held is not None and held[:2] != value[:2]:
                    if "--immutable" in flags:
                        err, rc = f"ERROR : {key}: Source and destination exist but do not match: immutable file modified", 6
                        continue
                dst[lead + key] = value
                if self.order is not None and dst is self.store:
                    self.order.append(f"copy {key}")
            if verb == "sync":
                for key in [k for k in dst if k.startswith(lead) and k[len(lead):] not in src]:
                    del dst[key]
        elif verb == "delete":
            content, prefix = self._where(paths[0])
            lead = prefix + "/" if prefix else ""
            for key in files_from or []:
                content.pop(lead + key, None)
        else:
            err, rc = f"unknown verb {verb}", 1
        return subprocess.CompletedProcess(cmd, rc, out, err)


class Emulated:
    """The real Store and Pool over the in-memory rclone, the database faked; restored on exit."""

    def __init__(self, world: World, rclone: Rclone):
        self.world, self.rclone = world, rclone

    def __enter__(self):
        self.saved = (ib.Database, ib.require_db_password, ib.subprocess)
        ib.Database = lambda *_a, **_k: FakeDatabase(self.world)
        ib.require_db_password = lambda: "x"
        ib.subprocess = types.SimpleNamespace(run=self.rclone.run, CompletedProcess=subprocess.CompletedProcess,
                                              PIPE=subprocess.PIPE)
        self.rclone.order = self.world.calls
        return self.rclone

    def __exit__(self, *_exc):
        ib.Database, ib.require_db_password, ib.subprocess = self.saved


LAST_RCLONE: dict = {}  # the runs P5 reads every pool argv of
PROBE = "veyq-check/probe"  # StoreCheck.kt's PROBE_KEY, which the tool's own constant must equal

STORE_ARGS = ["--rclone-binary", "rclone", "--store-endpoint", "http://garage:3900", "--store-access-key", "GKx",
              "--store-secret-key", "s"]


def obj(data: bytes) -> tuple:
    return len(data), md5(data), data


def run_tool(world: World, rclone: Rclone, argv: list[str], env: dict | None = None) -> tuple[str, str]:
    """`ib.main(argv)` under the emulation: (`ok <rc>` or `refused: …`, everything it printed)."""
    out, err = io.StringIO(), io.StringIO()
    saved_env = {k: os.environ.get(k) for k in (env or {})}
    os.environ.update(env or {})
    try:
        with Emulated(world, rclone), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            try:
                rc = ib.main(argv)
            except SystemExit as e:  # argparse: a flag this tool does not know
                rc = e.code or 2
    finally:
        for k, v in saved_env.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
    text = out.getvalue() + err.getvalue()
    return (f"ok {rc}" if rc == 0 else f"refused: {err.getvalue().strip()}"), text


def pool_capture(tmp: pathlib.Path, world: World, rclone: Rclone, name: str, pool: str, *extra: str,
                 env: dict | None = None) -> tuple[str, str, pathlib.Path]:
    into = tmp / name
    outcome, text = run_tool(world, rclone, ["capture", "--into", str(into), "--key-kept-apart", "--app-version", "0.5",
                                             "--objects-to", pool, *extra, *STORE_ARGS], env)
    return outcome, text, into


def ledger_of(rclone: Rclone, root: str) -> list[str]:
    return sorted(k for k in rclone.pool(root) if k.startswith(ib.LEDGER + "/"))


def objects_of(rclone: Rclone, root: str) -> list[str]:
    return sorted(k for k in rclone.pool(root) if not k.startswith(ib.LEDGER + "/"))


def pool_argvs(rclone: Rclone, root: str) -> list[list[str]]:
    return [c["argv"] for c in rclone.calls if any(a == root or a.startswith(root + "/") for a in c["argv"])]


def a_store() -> dict:
    return {"sk1/0": obj(b"zero"), "sk1/1": obj(b"one!"), PROBE: obj(b"probe")}


@guarded
def p1_capture_into_a_pool(tmp: pathlib.Path) -> None:
    root = str(tmp / "p1-pool")
    rclone = LAST_RCLONE["p1-pool"] = Rclone(a_store())
    rclone.pool(root)
    outcome, text, into = pool_capture(tmp, World(), rclone, "p1-capture", root)
    manifest = json.loads((into / ib.MANIFEST_NAME).read_text()) if (into / ib.MANIFEST_NAME).is_file() else {}
    fill = [c for c in rclone.calls if c["argv"][0] == "copy" and c["argv"][-4] == root]
    check("P1 capture --objects-to: format 3, store.pool.target the directory, the pool holds every indexed key, one "
          "ledger entry, no objects/",
          outcome == "ok 0" and manifest.get("backupFormat") == 3 and manifest["store"]["pool"]["target"] == root
          and objects_of(rclone, root) == sorted(a_store()) and len(ledger_of(rclone, root)) == 1
          and manifest["store"]["pool"]["objects"] == 3 and not (into / "objects").exists(), f"{outcome} {text[-400:]}")
    check("P1 the top-up copies exactly the index's keys (--files-from), with --immutable",
          len(fill) == 1 and sorted(fill[0]["files_from"] or []) == sorted(a_store())
          and "--immutable" in fill[0]["argv"], str([c["argv"] for c in rclone.calls]))
    check("P1 the pool directory is created 0700 by the tool", oct(os.stat(root).st_mode & 0o777) == "0o700",
          oct(os.stat(root).st_mode & 0o777) if os.path.isdir(root) else "absent")
    # Incremental by construction: a second capture hands rclone the same keys and the pool's copies stand.
    rclone.store["sk2/0"] = obj(b"two")
    before = dict(rclone.pool(root))
    outcome, _, _ = pool_capture(tmp, World(blobs=(("sk1", 2), ("sk2", 1))), rclone, "p1-capture-2", root)
    check("P1 a second capture adds the new object and leaves every earlier copy as it was",
          outcome == "ok 0" and "sk2/0" in rclone.pool(root)
          and all(rclone.pool(root)[k] == v for k, v in before.items() if not k.startswith(ib.LEDGER)), outcome)


@guarded
def p2_pool_short_fails(tmp: pathlib.Path) -> None:
    """B8 — a pool that lacks an indexed key after the copy fails the capture and writes no manifest; and the prune
    never runs before the new capture's index is complete: no ledger entry, no delete."""
    root = str(tmp / "p2-pool")
    rclone = Rclone(a_store())
    rclone.pool(root)["stray/0"] = obj(b"stray")
    rclone.drop_on_copy = {"sk1/1"}
    outcome, _, into = pool_capture(tmp, World(), rclone, "p2-capture", root)
    check("P2 capture: a pool lacking one indexed key after the copy fails, naming it, and writes no manifest",
          outcome.startswith("refused:") and "missing in pool  sk1/1" in outcome
          and not (into / ib.MANIFEST_NAME).exists(), outcome)
    check("P2 prune: a failed pool step writes no ledger entry and deletes nothing (the stray object stays)",
          ledger_of(rclone, root) == [] and "stray/0" in rclone.pool(root)
          and not any(c["argv"][0] == "delete" for c in rclone.calls), str([c["argv"][0] for c in rclone.calls]))
    rclone2 = Rclone(a_store())
    rclone2.pool(root + "-b")
    rclone2.pool(root + "-b")["sk1/1"] = obj(b"ONE!")  # the pool's copy differs: objects are immutable
    outcome, _, into = pool_capture(tmp, World(), rclone2, "p2-capture-b", root + "-b")
    check("P2 capture: a pool copy whose bytes differ from the store's fails (rclone's immutable refusal), the copy kept",
          outcome.startswith("refused:") and "immutable file modified" in outcome
          and rclone2.pool(root + "-b")["sk1/1"][2] == b"ONE!" and not (into / ib.MANIFEST_NAME).exists(), outcome)


@guarded
def p3_prune(tmp: pathlib.Path) -> None:
    """The pool keeps exactly what its kept captures' indexes name (owner 2026-10-17)."""
    root = str(tmp / "p3-pool")
    rclone = LAST_RCLONE["p3-pool"] = Rclone(a_store())
    rclone.pool(root)
    pool_capture(tmp, World(), rclone, "p3-c1", root, "--pool-keep", "2")
    # The store loses sk1/1 (a purge); an early copy left a stray the next index never names.
    del rclone.store["sk1/1"]
    rclone.pool(root)["stray/0"] = obj(b"stray")
    outcome, text, _ = pool_capture(tmp, World(blobs=(("sk1", 1),)), rclone, "p3-c2", root, "--pool-keep", "2")
    check("P3 prune: an object a kept capture's index names is never pruned — sk1/1, which only the first capture "
          "indexes, stays",
          outcome == "ok 0" and "sk1/1" in rclone.pool(root), f"{outcome} {objects_of(rclone, root)}")
    check("P3 prune (control, same run): an object no kept index names is gone",
          "stray/0" not in rclone.pool(root), str(objects_of(rclone, root)))
    outcome, text, _ = pool_capture(tmp, World(blobs=(("sk1", 1),)), rclone, "p3-c3", root, "--pool-keep", "2")
    check("P3 prune: once the first capture rotates out (keep 2), the object only it indexed is gone, its ledger entry "
          "with it, and what the kept ones name stays",
          outcome == "ok 0" and "sk1/1" not in rclone.pool(root) and "sk1/0" in rclone.pool(root)
          and len(ledger_of(rclone, root)) == 2 and not any("p3-c1" in k for k in ledger_of(rclone, root)),
          f"{outcome} {sorted(rclone.pool(root))}")
    deletes = [c for c in rclone.calls if c["argv"][0] == "delete"]
    check("P3 prune: the pool's one delete is `delete --files-from` the computed list",
          deletes and all(c["argv"][:2] == ["delete", "--files-from"] for c in deletes), str(deletes))


@guarded
def p4_prune_waits_for_the_ledger(tmp: pathlib.Path) -> None:
    """A prune never runs on a ledger that does not hold the capture just written: an empty read would name nothing."""
    root = str(tmp / "p4-pool")
    rclone = Rclone(a_store())
    rclone.pool(root)["stray/0"] = obj(b"stray")
    rclone.ledger_blind = True
    outcome, text, into = pool_capture(tmp, World(), rclone, "p4-capture", root)
    check("P4 prune: a ledger read lacking the new entry refuses the prune — nothing is deleted, the capture stands "
          "and says the pool was not pruned",
          outcome == "ok 0" and (into / ib.MANIFEST_NAME).is_file() and "pool was NOT pruned" in text
          and sorted(objects_of(rclone, root)) == sorted([*a_store(), "stray/0"])
          and not any(c["argv"][0] == "delete" for c in rclone.calls), f"{outcome} {objects_of(rclone, root)}")


@guarded
def p5_builder_is_copy_only(tmp: pathlib.Path) -> None:
    """B4 — every rclone argv the pool builder makes is `copy --immutable`, a read, or the prune's `delete
    --files-from`: a pool is never synced, moved or purged."""
    store = ib.Store(argparse.Namespace(store_endpoint="e", store_access_key="a", store_secret_key="s",
                                        store_bucket="blobs", store_region="r", rclone_image="i", rclone_binary=None,
                                        docker_network="n"))
    for target in ("/srv/pool", "s3:bucket/prefix"):
        pool = ib.Pool(target, store, {"VEYQ_BACKUP_POOL_ENDPOINT": "http://p", "VEYQ_BACKUP_POOL_ACCESS_KEY": "a",
                                       "VEYQ_BACKUP_POOL_SECRET_KEY": "s"})
        built = {"fill (whole bucket)": pool.argv_fill(None), "fill (the index's keys)": pool.argv_fill("/s/k"),
                 "hashed listing": pool.argv_listing("/l", True), "listing": pool.argv_listing("/l", False),
                 "list all": pool.argv_list_all(), "copy into the store": pool.argv_empty_into_store("/l"),
                 "ledger write": pool.argv_record("/scratch/entry"), "ledger read": pool.argv_ledger(),
                 "prune": pool.argv_delete("/l")}
        for name, argv in built.items():
            verb, flag = argv[0], ib.POOL_VERBS.get(argv[0], "absent")
            check(f"P5 builder, {'S3' if target.startswith('s3:') else 'directory'} pool, {name}: copy --immutable, "
                  f"a read, or delete --files-from", verb in ib.POOL_VERBS and (flag is None or flag in argv),
                  " ".join(argv))
    used = []
    for name in ("p1-pool", "p3-pool"):
        used += pool_argvs(LAST_RCLONE.get(name, Rclone()), str(tmp / name))
    check("P5 every argv the runs above handed rclone against a pool is one of those shapes",
          bool(used) and all(a[0] in ib.POOL_VERBS and (ib.POOL_VERBS[a[0]] is None or ib.POOL_VERBS[a[0]] in a)
                             for a in used), str(sorted({a[0] for a in used})))



@guarded
def p6_s3_pool_keeps_no_credential(tmp: pathlib.Path) -> None:
    """B5 — the manifest of an S3-pool capture carries neither half of the pool's key pair."""
    env = {"VEYQ_BACKUP_POOL_ENDPOINT": "http://pool.example:9000", "VEYQ_BACKUP_POOL_ACCESS_KEY": "AKpoolACCESSid",
           "VEYQ_BACKUP_POOL_SECRET_KEY": "pool-SECRET-value", "VEYQ_BACKUP_POOL_REGION": "eu-pool-1"}
    rclone = Rclone(a_store())
    rclone.pool("POOL:pbucket/pre")
    outcome, _, into = pool_capture(tmp, World(), rclone, "p6-capture", "s3:pbucket/pre", env=env)
    text = (into / ib.MANIFEST_NAME).read_text() if (into / ib.MANIFEST_NAME).is_file() else ""
    pool_env = [c["env"] for c in rclone.calls if any(a.startswith("POOL:") for a in c["argv"])]
    check("P6 S3 pool (positive control): rclone's environment for the pool calls held its key pair",
          bool(pool_env) and all(e.get("RCLONE_CONFIG_POOL_SECRET_ACCESS_KEY") == "pool-SECRET-value"
                                 and e.get("RCLONE_CONFIG_POOL_ACCESS_KEY_ID") == "AKpoolACCESSid" for e in pool_env),
          outcome)
    check("P6 S3 pool: the manifest names the pool as s3:<endpoint>/<path> and carries neither key",
          outcome == "ok 0" and '"target": "s3:http://pool.example:9000/pbucket/pre"' in text
          and "AKpoolACCESSid" not in text and "pool-SECRET-value" not in text, outcome)
    check("P6 S3 pool: no argv carries a key", not any(v in " ".join(c["argv"]) for c in rclone.calls
                                                       for v in ("AKpoolACCESSid", "pool-SECRET-value")))
    outcome, _ = run_tool(World(), Rclone(a_store()), ["copy-objects", "--objects-to", "s3:pbucket", *STORE_ARGS])
    check("P6 S3 pool without its credentials is refused, naming the variables",
          outcome.startswith("refused:") and "VEYQ_BACKUP_POOL_SECRET_KEY" in outcome, outcome)


INDEX = [["sk1/0", 4, md5(b"zero")], ["sk1/1", 4, md5(b"one!")], [PROBE, 5, md5(b"probe")],
         ["orphan/0", 3, md5(b"orf")]]


def pooled_capture(tmp: pathlib.Path, name: str, pool: str | None) -> pathlib.Path:
    backup = index_capture(tmp, name, INDEX)
    manifest = json.loads((backup / ib.MANIFEST_NAME).read_text())
    manifest["store"].update({"objects": len(INDEX), "bytes": 16})
    if pool:
        manifest["store"]["pool"] = {"target": pool, "objects": 4, "bytes": 16}
    (backup / ib.MANIFEST_NAME).write_text(json.dumps(manifest))
    return backup


def pool_restore(tmp: pathlib.Path, name: str, store: dict, pool: dict | None, *extra: str, backup=None):
    root = str(tmp / f"{name}-pool")
    rclone = Rclone(store)
    if pool is not None:
        rclone.pool(root).update(pool)
        pathlib.Path(root).mkdir(exist_ok=True)
    backup = backup or pooled_capture(tmp, name, root)
    world = World(data=False)
    source = ["--objects-from", root] if pool is not None else []
    outcome, text = run_tool(world, rclone, ["restore", str(backup), "--domain", "veyq.test", "--without-key",
                                             "--config-secret-file", str(tmp / f"{name}.key"), *source, *extra,
                                             *STORE_ARGS])
    into_store = [c for c in rclone.calls if c["argv"][0] == "copy" and c["argv"][-4] == f"{ib.REMOTE}:blobs"]
    return outcome, text, world.calls, rclone, into_store


def full_pool() -> dict:
    return {"sk1/0": obj(b"zero"), "sk1/1": obj(b"one!"), PROBE: obj(b"probe"), "orphan/0": obj(b"orf"),
            "other/0": obj(b"another capture's")}


@guarded
def p7_restore_from_a_pool(tmp: pathlib.Path) -> None:
    """B2 — objects are copied only into an empty store; the store check's probe is not data."""
    root = pathlib.Path(__file__).resolve().parent.parent
    check_kt = root / "backend/core/src/main/kotlin/me/raddatz/veyq/core/blobstore/StoreCheck.kt"
    # The tools image ships no backend (as for not_data_cases): there only the tool's constant is checked.
    check("P7 the probe the empty-store gate spares is the store check's own key",
          ib.PROBE_KEY == PROBE and (not (root / "backend").is_dir() or f'"{PROBE}"' in check_kt.read_text()),
          ib.PROBE_KEY)
    outcome, _, calls, _, _ = pool_restore(tmp, "p7-held", {"someone/0": obj(b"x")}, full_pool())
    check("P7 restore --objects-from onto a store holding one object is refused before load, naming the count",
          outcome.startswith("refused:") and "already holds 1 object" in outcome and "load" not in calls
          and not any(c.startswith("copy") for c in calls), outcome)
    outcome, text, calls, rclone, into_store = pool_restore(tmp, "p7-probe", {PROBE: obj(b"probe")},
                                                            full_pool())
    check("P7 control: a store holding only the probe loads, copying exactly the index's keys the source holds (the "
          "probe aside), the copy before the load",
          outcome == "ok 0" and len(into_store) == 1 and sorted(into_store[0]["files_from"]) == ["orphan/0", "sk1/0",
                                                                                              "sk1/1"]
          and "--immutable" in into_store[0]["argv"] and "other/0" not in rclone.store
          and calls.index("load") > max(i for i, c in enumerate(calls) if c.startswith("copy")), f"{outcome} {calls}")


@guarded
def p8_restore_checks_the_source(tmp: pathlib.Path) -> None:
    """B3 — a source object whose MD5 differs from the index's refuses by name; a missing one too, until
    --accept-missing-objects restores without it and the closing log names it. Orphans are tolerated (O2)."""
    changed = {**full_pool(), "sk1/1": obj(b"ONE!")}
    outcome, _, calls, _, _ = pool_restore(tmp, "p8-md5", {}, changed)
    check("P8 restore: a source object of another MD5 refuses naming it, before anything is written",
          outcome.startswith("refused:") and "changed object  sk1/1 (md5" in outcome and "load" not in calls
          and not any(c.startswith("copy") for c in calls), outcome)
    outcome, _, calls, _, _ = pool_restore(tmp, "p8-equal", {}, full_pool())
    check("P8 control: equal MD5s load", outcome == "ok 0" and "load" in calls, outcome)
    missing = {k: v for k, v in full_pool().items() if k != "sk1/1"}
    outcome, _, calls, _, _ = pool_restore(tmp, "p8-missing", {}, missing)
    check("P8 restore: a required object the source lacks refuses naming it",
          outcome.startswith("refused:") and "missing object  sk1/1" in outcome and "load" not in calls, outcome)
    outcome, text, calls, _, into_store = pool_restore(tmp, "p8-accept", {}, missing, "--accept-missing-objects")
    check("P8 --accept-missing-objects copies what the source has, loads, and the closing log names the broken object",
          outcome == "ok 0" and "load" in calls and "broken object  sk1/1" in text
          and sorted(into_store[0]["files_from"]) == ["orphan/0", "sk1/0"], f"{outcome} {text[-300:]}")
    no_orphan = {k: v for k, v in full_pool().items() if k != "orphan/0"}
    outcome, text, calls, _, _ = pool_restore(tmp, "p8-orphan", {}, no_orphan)
    check("P8 an indexed object no row requires (an orphan) absent from the source is reported and tolerated (O2)",
          outcome == "ok 0" and "absent, not required  orphan/0" in text, f"{outcome} {text[-300:]}")
    check("P8 --objects-from with a format-2 capture is refused (it holds its objects)",
          pool_restore(tmp, "p8-fmt2", {}, full_pool(), backup=format2_capture(tmp, "p8-fmt2-capture"))[0]
          .startswith("refused:"))


def format2_capture(tmp: pathlib.Path, name: str) -> pathlib.Path:
    """What Compose wrote before every capture became the index: the objects themselves in `objects/`."""
    backup = tmp / name
    (backup / "objects" / "sk1").mkdir(parents=True)
    (backup / "objects" / "sk1" / "0").write_bytes(b"zero")
    (backup / "objects" / "sk1" / "1").write_bytes(b"one!")
    (backup / ib.DUMP_NAME).write_text("CREATE TABLE a (b int);\n-- flyway_schema_history\n")
    (backup / ib.MANIFEST_NAME).write_text(json.dumps({
        "backupFormat": 2, "domains": ["veyq.test"], "app": {"version": "0.4", "versionSource": "container-env"},
        "database": {"name": "veyq", "schemaVersion": SCHEMA, "bytes": 1},
        "store": {"bucket": "blobs", "objects": 2, "bytes": 8, "blobs": [["sk1", 2]], "openUploadSessions": 0},
        "configSecret": {"placement": ib.SEPARATE}, "capture": {"startedAt": "x", "finishedAt": "y"}}))
    return backup


@guarded
def p9_format2_and_the_empty_store(tmp: pathlib.Path) -> None:
    """B6 — a format-2 capture restores only into an empty store; B7 — a format-3 restore without a source onto an
    EMPTY store refuses naming --objects-from and the capture's pool."""
    backup = format2_capture(tmp, "p9-fmt2")
    outcome, _, calls, _, _ = pool_restore(tmp, "p9-held", {"someone/0": obj(b"x")}, None, backup=backup)
    check("P9 a format-2 restore into a store holding an object is refused before load",
          outcome.startswith("refused:") and "already holds 1 object" in outcome and "load" not in calls, outcome)
    outcome, _, calls, rclone, into_store = pool_restore(tmp, "p9-empty", {}, None, backup=backup)
    check("P9 control: a format-2 restore into an empty store copies its objects/ (the copy before the load) and loads",
          outcome == "ok 0" and sorted(rclone.store) == ["sk1/0", "sk1/1"] and len(into_store) == 1
          and calls.index("load") > max(i for i, c in enumerate(calls) if c.startswith("copy")), f"{outcome} {calls}")
    pooled = pooled_capture(tmp, "p9-pooled", "/srv/veyq-pool")
    outcome, _, calls, _, _ = pool_restore(tmp, "p9-b7", {PROBE: obj(b"probe")}, None, backup=pooled)
    check("P9 a format-3 restore without a source onto an empty store refuses naming --objects-from and the pool",
          outcome.startswith("refused:") and "the store is empty" in outcome
          and "--objects-from /srv/veyq-pool" in outcome and "load" not in calls, outcome)
    s3 = pooled_capture(tmp, "p9-s3", "s3:http://pool.example:9000/pbucket/pre")
    outcome, _, _, _, _ = pool_restore(tmp, "p9-b7-s3", {}, None, backup=s3)
    check("P9 the same for an S3 pool names s3:<path> and its endpoint",
          "--objects-from s3:pbucket/pre with VEYQ_BACKUP_POOL_ENDPOINT=http://pool.example:9000" in outcome, outcome)


def pool_cases(tmp: pathlib.Path) -> None:
    p1_capture_into_a_pool(tmp)
    p2_pool_short_fails(tmp)
    p3_prune(tmp)
    p4_prune_waits_for_the_ledger(tmp)
    p6_s3_pool_keeps_no_credential(tmp)
    p7_restore_from_a_pool(tmp)
    p8_restore_checks_the_source(tmp)
    p9_format2_and_the_empty_store(tmp)
    p5_builder_is_copy_only(tmp)


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="veyq-backup-selftest-") as tmp:
        tmp_path = pathlib.Path(tmp)
        plan_cases()
        place_cases(tmp_path)
        restore_cases(tmp_path)
        capture_cases(tmp_path)
        # As `main` runs the tool: under its owner-only umask, which `verify` holds every written file to.
        os.umask(ib.OWNER_ONLY_UMASK)
        gate_cases(tmp_path)
        pool_cases(tmp_path)
    version_cases()
    not_data_cases()
    store_cases()
    if failures:
        print(f"[selftest] {len(failures)} failure(s)", file=sys.stderr)
        return 1
    print("[selftest] the restored key takes its directory's owner, an unplaceable or unreadable one is refused "
          "before the first write, and a backup is ordered against its target the way Flyway orders versions")
    return 0


if __name__ == "__main__":
    sys.exit(main())
