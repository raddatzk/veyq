#!/usr/bin/env python3
"""Offline self-test of `measure_volume.py seed members`: the invite limit is raised for the member seeder and put
back as it was on every way out (heavy stage H1). TEMPORARY, deleted with the harness it tests.

No Docker, no instance: `invite_limit` (which rewrites the override and restarts the app through `veyqctl`) and the
seeder are replaced by recorders, and the override file is a real one in a scratch directory, so the value read
before the raise is the file's. Each case states the calls it must see; a case whose seeder fails, raises, is
interrupted or exits must still see the restore — the bite is removing the `finally` in `invites_raised`, which turns
every one of those red.

    python3 scripts/measure_volume_selftest.py
"""
from __future__ import annotations

import sys
import tempfile
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

if failures:
    print(f"measure_volume self-test FAILED: {len(failures)} case(s)")
    sys.exit(1)
print("measure_volume self-test OK: `seed members` raises the invite limit and puts it back on every way out")
