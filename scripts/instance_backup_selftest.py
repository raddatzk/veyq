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
     read — with the same kind of positive control. Skipped as root, which reads both.

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


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="veyq-backup-selftest-") as tmp:
        tmp_path = pathlib.Path(tmp)
        plan_cases()
        place_cases(tmp_path)
        restore_cases(tmp_path)
        capture_cases(tmp_path)
    if failures:
        print(f"[selftest] {len(failures)} failure(s)", file=sys.stderr)
        return 1
    print("[selftest] the restored key takes its directory's owner, and an unplaceable or unreadable one is refused "
          "before the first write")
    return 0


if __name__ == "__main__":
    sys.exit(main())
