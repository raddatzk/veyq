#!/usr/bin/env python3
"""Offline self-test of `measure_volume.py seed members`: the invite limit is raised for the member seeder and put
back as it was on every way out (heavy stage H1). TEMPORARY, deleted with the harness it tests.

No Docker, no instance: `invite_limit` (which rewrites the override and restarts the app through `veyqctl`) and the
seeder are replaced by recorders, and the override file is a real one in a scratch directory, so the value read
before the raise is the file's. Each case states the calls it must see; a case whose seeder fails, raises, is
interrupted or exits must still see the restore — the bite is removing the `finally` in `invites_raised`, which turns
every one of those red.

The process-tree cases (H1b) run a real seeder stand-in through `run_child`: a Python child that starts a grandchild
and waits, as `npx` -> `tsx` -> node does. The recorder notes, at the moment of the restore, whether the grandchild is
still alive — it must not be, on a SIGTERM (a real one, to this process, under `main`'s handler), on a grandchild that
ignores SIGTERM (the SIGKILL after the grace), and on a seeder that exits leaving one behind. The bite is `run_child`
on `subprocess.run` again, which kills only the direct child: all three turn red.

    python3 scripts/measure_volume_selftest.py
"""
from __future__ import annotations

import os
import signal
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent))
import measure_volume as mv  # noqa: E402

failures: list[str] = []


def check(label: str, ok: bool, detail: str = "") -> None:
    print(f"{'ok  ' if ok else 'FAIL'} {label}{(' — ' + detail) if detail and not ok else ''}")
    if not ok:
        failures.append(label)


def case(label: str, *, seed_args: list[str], before: str | None, seeder, raise_fails: bool = False,
         expect_rc: int | None = None, expect_exc: type[BaseException] | None = None,
         expect_calls: list[str]) -> None:
    with tempfile.TemporaryDirectory(prefix="measure_volume_selftest_") as tmp:
        home = Path(tmp)
        env = {} if before is None else {mv.INVITE_ENV: before}
        (home / "compose.override.yml").write_text(
            mv.yaml.safe_dump({"services": {"app": {"environment": env}}}), encoding="utf-8")
        calls: list[str] = []

        def invite_limit(_inst, limit: str) -> None:
            calls.append(limit)
            if raise_fails and limit == mv.SEEDING_INVITE_LIMIT:
                raise mv.Refused("the app did not come back healthy")

        mv.invite_limit, mv.run_seeder = invite_limit, lambda _inst, _args: seeder()
        rc, exc = None, None
        try:
            rc = mv.seed(SimpleNamespace(home=home), seed_args)
        except BaseException as e:  # noqa: BLE001 - KeyboardInterrupt and SystemExit are cases here
            exc = e
        ok = calls == expect_calls and rc == expect_rc and (type(exc) is expect_exc if expect_exc else exc is None)
        check(label, ok, f"calls {calls}, rc {rc}, raised {exc!r}")


def raises(e: BaseException):
    def seeder() -> int:
        raise e
    return seeder


def alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    return True


# The stand-in: a child that starts a grandchild (which writes its pid, then lives on - ignoring SIGTERM when asked),
# then waits for it, or exits at once and leaves it behind.
TREE = r"""
import subprocess, sys, time
pidfile, ignore_term, leave = sys.argv[1], sys.argv[2] == "1", sys.argv[3] == "1"
grandchild = ("import os, signal, sys, time\n"
              + ("signal.signal(signal.SIGTERM, signal.SIG_IGN)\n" if ignore_term else "")
              + "open(sys.argv[1] + '.tmp', 'w').write(str(os.getpid()))\n"
              + "os.replace(sys.argv[1] + '.tmp', sys.argv[1])\n"
              + "time.sleep(60)\n")
g = subprocess.Popen([sys.executable, "-c", grandchild, pidfile])
while True:
    try:
        open(pidfile).close()
        break
    except FileNotFoundError:
        time.sleep(0.02)
sys.exit(0) if leave else g.wait()
"""


def tree_case(label: str, *, ignore_term: bool = False, leave: bool = False, sigterm: bool = False,
              expect_rc: int | None = None, expect_exc: type[BaseException] | None = None) -> None:
    """`seed members` with a real process tree as its seeder: at the restore, the grandchild is gone."""
    with tempfile.TemporaryDirectory(prefix="measure_volume_selftest_") as tmp:
        home, pidfile = Path(tmp), Path(tmp) / "grandchild.pid"
        (home / "compose.override.yml").write_text(mv.yaml.safe_dump({"services": {"app": {"environment": {}}}}),
                                                  encoding="utf-8")
        calls: list[str] = []

        def grandchild() -> int | None:
            try:
                return int(pidfile.read_text())
            except FileNotFoundError:
                return None

        def invite_limit(_inst, limit: str) -> None:
            pid = grandchild()
            still = limit != mv.SEEDING_INVITE_LIMIT and pid is not None and alive(pid)
            calls.append(f"{limit}{' (seeder alive)' if still else ''}")

        def seeder(_inst, _args) -> int:
            if sigterm:  # a real SIGTERM to this process once the grandchild is up, under main's own handler
                def fire() -> None:
                    while grandchild() is None:
                        time.sleep(0.02)
                    os.kill(os.getpid(), signal.SIGTERM)
                threading.Thread(target=fire, daemon=True).start()
            return mv.run_child([sys.executable, "-c", TREE, str(pidfile), "1" if ignore_term else "0",
                                 "1" if leave else "0"], stdin=subprocess.DEVNULL)

        previous = signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
        grace = mv.SEEDER_GRACE
        mv.invite_limit, mv.run_seeder, mv.SEEDER_GRACE = invite_limit, seeder, 1.0
        rc, exc = None, None
        started = time.monotonic()
        try:
            rc = mv.seed(SimpleNamespace(home=home), ["members"])
        except BaseException as e:  # noqa: BLE001 - SystemExit is a case here
            exc = e
        finally:
            took = time.monotonic() - started
            signal.signal(signal.SIGTERM, previous)
            mv.SEEDER_GRACE = grace
            pid = grandchild()
            if pid is not None and alive(pid):
                os.kill(pid, signal.SIGKILL)  # what the bite leaves behind, never outliving this test
        ok = (calls == [mv.SEEDING_INVITE_LIMIT, "default"] and rc == expect_rc
              and (type(exc) is expect_exc if expect_exc else exc is None) and took < 15)
        check(label, ok, f"calls {calls}, rc {rc}, raised {exc!r}, {took:.1f} s")


RAISED = mv.SEEDING_INVITE_LIMIT
case("seed members, green: raised, then the default back", seed_args=["members", "--target", "1000"], before=None,
     seeder=lambda: 0, expect_rc=0, expect_calls=[RAISED, "default"])
case("seed members, red seeder: the default back", seed_args=["members", "--target", "1000"], before=None,
     seeder=lambda: 1, expect_rc=1, expect_calls=[RAISED, "default"])
case("seed members, seeder raises: the default back", seed_args=["members"], before=None,
     seeder=raises(RuntimeError("boom")), expect_exc=RuntimeError, expect_calls=[RAISED, "default"])
case("seed members, Ctrl-C: the default back", seed_args=["members"], before=None,
     seeder=raises(KeyboardInterrupt()), expect_exc=KeyboardInterrupt, expect_calls=[RAISED, "default"])
case("seed members, SIGTERM (main's exit 143): the default back", seed_args=["members"], before=None,
     seeder=raises(SystemExit(143)), expect_exc=SystemExit, expect_calls=[RAISED, "default"])
case("seed members, an operator's own limit: that limit back, not the default", seed_args=["members"], before="50",
     seeder=lambda: 0, expect_rc=0, expect_calls=[RAISED, "50"])
case("seed members, the raise fails after writing: put back anyway", seed_args=["members"], before=None,
     seeder=lambda: 0, raise_fails=True, expect_exc=mv.Refused, expect_calls=[RAISED, "default"])
case("seed photos: the limit untouched", seed_args=["photos", "--target", "10"], before=None,
     seeder=lambda: 0, expect_rc=0, expect_calls=[])
tree_case("seed members, SIGTERM while the seeder's tree runs: the tree is gone before the default is back",
          sigterm=True, expect_exc=SystemExit)
tree_case("seed members, SIGTERM and a grandchild that ignores it: killed after the grace, then the default back",
          sigterm=True, ignore_term=True, expect_exc=SystemExit)
tree_case("seed members, a seeder that exits leaving a grandchild: it is ended before the default is back",
          leave=True, expect_rc=0)

if failures:
    print(f"measure_volume self-test FAILED: {len(failures)} case(s)")
    sys.exit(1)
print("measure_volume self-test OK: `seed members` raises the invite limit and puts it back on every way out, "
      "once the seeder's process tree is gone")
