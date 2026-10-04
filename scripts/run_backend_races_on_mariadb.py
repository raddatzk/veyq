#!/usr/bin/env python3
"""Run the backend's race tests against a throwaway MariaDB of the image production pins.

**Why.** `./gradlew check` runs on H2, and H2 cannot show what the production engine does when two transactions
overlap: at MariaDB's default REPEATABLE READ the first plain read of a transaction fixed its snapshot, so a check
made after a later row lock read the state from before it. Every H2 race test was green on exactly those defects
(ADR 0078). This runner points the race classes at the real engine, so the suite's race evidence is evidence
about production too.

**What it runs.** Every test class that races — the files in every backend test root (`backend_tree.test_roots()`)
that call the race harness (`race(` or `whileUncommitted(` from `testing/Race.kt`, in `:test-support`) or build their own overlap from a
latch or a barrier. Derived from the code, not listed, so a new race test is in the next run without anyone
registering it ([race_classes]). Whole classes run, not only their race methods: the non-race methods of a race
class are cheap and prove the class still boots on MariaDB. Each class runs in the Gradle project that owns its
test root (`:core:test --tests A :test --tests B`, [project_filters]): a bare `test --tests X` fails with "No tests
found" in every project without a match. The class → project map is printed before the run, and a test root no
project owns or a filter no class matches ends the run before Docker starts.

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

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here

REPO = Path(__file__).resolve().parent.parent
BACKEND = REPO / "backend"
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
    """Every test class that calls the race harness, as Gradle `--tests` filters. Only the test roots are read: the
    harness itself (`testing/Race.kt`) and every other shared helper live in a fixtures root (`:test-support`), which
    holds no test class, so no file is skipped by name. Fails loudly on an empty set: a run of nothing would read as a
    green proof."""
    found = []
    for f in backend_tree.kotlin_files(backend_tree.test_roots()):
        if RACE_CALL.search(f.read_text()):
            found.append(f"*{f.stem}")
    if not found:
        sys.exit("no race test class found — refusing to report a run of nothing")
    return found


def _test_classes() -> list[tuple[str, str, Path]]:
    """`(fully-qualified name, simple name, file)` of every Kotlin file in a test root (fixtures hold no tests)."""
    out = []
    for f in backend_tree.kotlin_files(backend_tree.test_roots()):
        m = re.search(r"^package\s+([\w.]+)", f.read_text(), re.MULTILINE)
        out.append((f"{m.group(1)}.{f.stem}" if m else f.stem, f.stem, f))
    return out


def _matches(pattern: str, fqn: str, simple: str) -> bool:
    """Gradle's `--tests` semantics, as far as a class is concerned: `*` is a wildcard, a pattern that starts with
    an upper-case letter names the simple class name, and a trailing `.method` part may follow the class."""
    name = simple if pattern[:1].isupper() else fqn
    rx = "^" + re.escape(pattern).replace(r"\*", ".*") + "$"
    return re.match(rx, name) is not None or any(
        re.match("^" + re.escape(pattern[:i]).replace(r"\*", ".*") + "$", name)
        for i, c in enumerate(pattern) if c == "."
    )


def project_filters(filters: list[str]) -> dict[str, list[str]]:
    """`{Gradle project: [--tests filter]}` — each filter goes to every project whose test root holds a class it
    matches, because `gradlew test --tests X` fails with "No tests found" in a project with no match (measured).
    The map is total: a test root no included project owns, or a filter no test class matches, ends the run here,
    before Docker starts — a filter that cannot be placed is never skipped."""
    for root in backend_tree.test_roots():
        if backend_tree.project_of(root) is None:
            sys.exit(f"{root.relative_to(REPO)} is a test root no project in backend/settings.gradle.kts owns — "
                     f"its race classes could not be run; include the module or move the tests")
    classes = _test_classes()
    by_project: dict[str, list[str]] = {}
    for flt in filters:
        owners = sorted({backend_tree.project_of(f) for fqn, simple, f in classes if _matches(flt, fqn, simple)})
        if not owners:
            sys.exit(f"--tests {flt!r} matches no test class in any test root")
        for p in owners:
            by_project.setdefault(p, []).append(flt)
    return by_project


def gradle_command(by_project: dict[str, list[str]]) -> list[str]:
    """One Gradle run: `<project>:test --rerun --tests A …` per project that holds a filter, root as `:test`."""
    cmd = ["./gradlew"]
    for project in sorted(by_project):
        cmd += [f"{project.rstrip(':')}:test", "--rerun"]
        for f in by_project[project]:
            cmd += ["--tests", f]
    return cmd


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
    by_project = project_filters(filters)
    for project in sorted(by_project):
        print(f"{project}: {' '.join(by_project[project])}", flush=True)
    image, port = pinned_image(), free_port()
    print(f"race run on {image} at 127.0.0.1:{port}: {len(filters)} filter(s) in {len(by_project)} project(s)", flush=True)
    start(image, port)
    try:
        env = dict(os.environ)
        env["VEYQ_TEST_DB_KIND"] = "mariadb"
        env["VEYQ_TEST_DB_URL"] = f"jdbc:mariadb://127.0.0.1:{port}/veyq?user=veyq&password={PASSWORD}"
        cmd = gradle_command(by_project)
        timeout = int(os.environ.get("VEYQ_TEST_TIMEOUT_S", "2400"))
        return subprocess.run(cmd, cwd=BACKEND, env=env, timeout=timeout).returncode
    finally:
        subprocess.run(["docker", "stop", NAME], capture_output=True, timeout=120)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
