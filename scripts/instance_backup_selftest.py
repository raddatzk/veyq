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
     over a token, half a pair refused, and a run that names no store refused naming both ways.

What it cannot show: an actual `fchown` to another uid (that needs root), and that uid 10001 then reads the key
in a running container — that is the live proof on the production recipe, recorded where the coverage of
`uc.admin.back-up-and-restore-instance` says. Not a `validate_*.py`: it asserts nothing about the repository,
only about the tool, and it runs beside the tool's own rehearsal in `ci.yml`'s `web-e2e` job. Runnable locally:

    python3 scripts/instance_backup_selftest.py
"""
from __future__ import annotations  # the repo's scripts run on Python 3.9

import argparse
import contextlib
import io
import json
import os
import pathlib
import re
import stat
import sys
import tempfile

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
        "backupFormat": ib.BACKUP_FORMAT, "domains": ["veyq.test"], "configSecret": {"placement": ib.SEPARATE}}))
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

    def mirror_out(self, dest):
        for key, size, _ in self.w.listing:
            (dest / key).parent.mkdir(parents=True, exist_ok=True)
            (dest / key).write_bytes(b"x" * size)

    def mirror_in(self, source):
        self.w.calls.append("mirror_in")

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
          outcome == "ok 0" and "load" in calls and "mirror_in" not in calls, f"{outcome} {calls}")
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
def g6_compose_untouched(tmp: pathlib.Path) -> None:
    into, kept = tmp / "g6-capture", tmp / "g6-kept.key"
    key = tmp / "g6-config-secret.key"
    key.write_bytes(KEY_BYTES)
    world = World()
    with Seams(world), contextlib.redirect_stdout(io.StringIO()):
        rc = ib.main(["capture", "--into", str(into), "--key-into", str(kept), "--config-secret-file", str(key),
                      "--compose-file", str(tmp / "no-compose.yml"), "--store-endpoint", "http://garage:3900",
                      "--store-access-key", "GKx", "--store-secret-key", "s"])
    manifest = json.loads((into / ib.MANIFEST_NAME).read_text()) if (into / ib.MANIFEST_NAME).is_file() else {}
    check("G6 compose: a default capture is the mirror, backupFormat 2, with exactly the keys it always had",
          rc == 0 and manifest.get("backupFormat") == 2 and sorted(manifest) == sorted(
              ["backupFormat", "domains", "app", "database", "store", "configSecret", "capture"])
          and sorted(manifest["store"]) == sorted(["bucket", "objects", "bytes", "blobs", "openUploadSessions"])
          and sorted(manifest["capture"]) == ["finishedAt", "startedAt"]
          and manifest["configSecret"] == {"placement": ib.SEPARATE} and (into / ib.OBJECTS_DIR).is_dir(),
          json.dumps(manifest)[:300])
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        rc = ib.main(["verify", str(into), "--key-from", str(kept)])
    check("G6 compose: its verify is the format-2 verify — objects present, no live-store line",
          rc == 0 and "2 present, 2 required" in out.getvalue() and "live store" not in out.getvalue(),
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
    g6_compose_untouched(tmp)
    g7_url_parser()
    g8_no_key(tmp)


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
