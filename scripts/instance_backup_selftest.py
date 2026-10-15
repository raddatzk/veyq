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
  6. the emptiness gate's exclusions (`NOT_DATA`): the object-store setting is among them, and every one still names
     a table a migration creates;
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
    created = set()
    for sql in root.glob("backend/**/src/main/resources/db/migration/*.sql"):
        created.update(re.findall(r"CREATE TABLE (\w+)", sql.read_text()))
    check("the gate excludes the object-store setting, which a Server Owner fills before a restore",
          "veyq_store_setting" in ib.NOT_DATA)
    check("every table the gate excludes is one a migration creates (flyway's own record aside)",
          all(t in created for t in ib.NOT_DATA if t != "flyway_schema_history"),
          str(sorted(t for t in ib.NOT_DATA if t != "flyway_schema_history" and t not in created)))
    check("the gate excludes the capability switches, which a Server Owner may set before a restore",
          "veyq_capability_switch" in ib.NOT_DATA)
    check("the exclusion list is the four named tables, no pattern", len(ib.NOT_DATA) == 4, str(ib.NOT_DATA))


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


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="veyq-backup-selftest-") as tmp:
        tmp_path = pathlib.Path(tmp)
        plan_cases()
        place_cases(tmp_path)
        restore_cases(tmp_path)
        capture_cases(tmp_path)
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
