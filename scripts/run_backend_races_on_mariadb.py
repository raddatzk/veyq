#!/usr/bin/env python3
"""Run the backend's race tests against a throwaway MariaDB of the image production pins.

**Why.** `./gradlew check` runs on H2, and H2 cannot show what the production engine does when two transactions
overlap: at MariaDB's default REPEATABLE READ the first plain read of a transaction fixed its snapshot, so a check
made after a later row lock read the state from before it. Every H2 race test was green on exactly those defects
(ADR 0078). This runner points the race classes at the real engine, so the suite's race evidence is evidence
about production too.

**What it runs.** Every test class that races — the files under `backend/src/test` that call the race harness
(`race(` or `whileUncommitted(` from `testing/Race.kt`) or build their own overlap from a latch or a barrier. Derived from the code, not listed, so a new race test is
in the next run without anyone registering it ([race_classes]). Whole classes run, not only their race methods:
the non-race methods of a race class are cheap and prove the class still boots on MariaDB.

**What it never touches.** The owner's dev database (`veyq-mariadb`, port 3306). The container here has its own
name, binds a free 127.0.0.1 port, is created fresh per run (`--rm`, a fresh database each time) and is stopped
in a `finally`. Its image is read from `infra/docker-compose.yml`, so the pin has one home.

**Not a `validate_*` guard; a CI job, not yet an admitted one.** It needs Docker, so it runs as `ci.yml`'s
`backend-races` job, not in the buildless glob. No run has executed that job yet (the repository has no remote), so
until a run's log shows the race classes executed it admits nothing (ADR 0040), and a hand run stays the proof.

Usage:
    python3 scripts/run_backend_races_on_mariadb.py [--tests PATTERN ...]   # from the repo root

With `--tests`, only those Gradle test filters run (e.g. `'*ShareSharedScopeSliceTest'`). Exit code: Gradle's.
`VEYQ_TEST_TIMEOUT_S` caps the Gradle run (default 2400).
"""
from __future__ import annotations

import os
import re
import socket
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
BACKEND = REPO / "backend"
TEST_ROOT = BACKEND / "src" / "test" / "kotlin"
COMPOSE_FILE = REPO / "infra" / "docker-compose.yml"
NAME = f"veyq-race-mariadb-{os.getpid()}"
PASSWORD = "race"  # a throwaway container's credential, not a secret
RACE_CALL = re.compile(r"\b(race|whileUncommitted)\(|\bCountDownLatch\b|\bCyclicBarrier\b")


def pinned_image() -> str:
    """The MariaDB image `infra/docker-compose.yml` pins — the one production runs."""
    for line in COMPOSE_FILE.read_text().splitlines():
        m = re.match(r"\s*image:\s*(mariadb:\S+)", line)
        if m:
            return m.group(1)
    sys.exit(f"no mariadb image in {COMPOSE_FILE}")


def race_classes() -> list[str]:
    """Every test class that calls the race harness, as Gradle `--tests` filters. The harness file itself is no
    test. Fails loudly on an empty set: a run of nothing would read as a green proof."""
    found = []
    for f in sorted(TEST_ROOT.rglob("*.kt")):
        if f.name == "Race.kt":
            continue
        if RACE_CALL.search(f.read_text()):
            found.append(f"*{f.stem}")
    if not found:
        sys.exit("no race test class found — refusing to report a run of nothing")
    return found


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def start(image: str, port: int) -> None:
    subprocess.run(
        ["docker", "run", "--rm", "-d", "--name", NAME, "-p", f"127.0.0.1:{port}:3306",
         "-e", f"MARIADB_ROOT_PASSWORD={PASSWORD}", "-e", "MARIADB_DATABASE=veyq",
         "-e", "MARIADB_USER=veyq", "-e", f"MARIADB_PASSWORD={PASSWORD}", image],
        check=True, capture_output=True, text=True, timeout=600,
    )
    for _ in range(90):
        ready = subprocess.run(
            ["docker", "exec", NAME, "mariadb", "-uveyq", f"-p{PASSWORD}", "veyq", "-e", "select 1"],
            capture_output=True, text=True, timeout=30,
        )
        if ready.returncode == 0:
            return
        time.sleep(2)
    sys.exit("the throwaway MariaDB did not become ready")


def main(argv: list[str]) -> int:
    filters = [argv[i + 1] for i, a in enumerate(argv) if a == "--tests" and i + 1 < len(argv)] or race_classes()
    image, port = pinned_image(), free_port()
    print(f"race run on {image} at 127.0.0.1:{port}: {len(filters)} filter(s)", flush=True)
    start(image, port)
    try:
        env = dict(os.environ)
        env["VEYQ_TEST_DB_KIND"] = "mariadb"
        env["VEYQ_TEST_DB_URL"] = f"jdbc:mariadb://127.0.0.1:{port}/veyq?user=veyq&password={PASSWORD}"
        cmd = ["./gradlew", "test", "--rerun"]
        for f in filters:
            cmd += ["--tests", f]
        timeout = int(os.environ.get("VEYQ_TEST_TIMEOUT_S", "2400"))
        return subprocess.run(cmd, cwd=BACKEND, env=env, timeout=timeout).returncode
    finally:
        subprocess.run(["docker", "stop", NAME], capture_output=True, timeout=120)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
