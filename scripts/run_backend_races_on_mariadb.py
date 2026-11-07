#!/usr/bin/env python3
"""Run the backend's race tests against a throwaway MariaDB of the image production pins.

**Why.** `./gradlew check` runs on H2, and H2 cannot show what the production engine does when two transactions
overlap: at MariaDB's default REPEATABLE READ the first plain read of a transaction fixed its snapshot, so a check
made after a later row lock read the state from before it. Every H2 race test was green on exactly those defects
(ADR 0078). This runner points the race classes at the real engine, so the suite's race evidence is evidence
about production too.

**What it runs.** Every test class that races — the files in every backend test root (`backend_tree.test_roots()`)
that call the race harness (`race(`, `whileUncommitted(` or `holdForOverlap(` from `testing/Race.kt`, in `:core`'s test fixtures) or build their own overlap from a
latch or a barrier. Derived from the code, not listed, so a new race test is in the next run without anyone
registering it ([race_classes]). Whole classes run, not only their race methods: the non-race methods of a race
class are cheap and prove the class still boots on MariaDB. Each class runs in the Gradle project that owns its
test root (`:core:test --tests A`, `:apps:memories:test --tests B`, [project_filters]): a bare `test --tests X` fails
with "No tests found" in every project without a match. The class → project map is printed before the run, and a test
root no project owns or a filter no class matches ends the run before Docker starts.

**One database per project, one Gradle run per project.** A project's test JVM migrates with the migrations on ITS
classpath — `:core`'s holds the core baseline only, `:apps:memories`' the core and the Memories baselines. One shared
database fails the second project to boot: measured (cr-platform-gradle-modules step 8a), after the Memories run had
applied both baselines, every `:core` class failed Flyway's validate on the Memories baseline it does not have
(`FlywayValidateException`, 1 failed, 245 skipped). So the container holds a fresh database per project
([database_of]) and each project runs in its own Gradle invocation, pointed at its own. The split costs little: the
runs after the first reuse its daemon and configured in 2–3 s each in the first CI run (2026-11-05). One invocation
could run the projects in parallel only by routing each test task to its own database inside the build.

**Also the schema check.** The default run adds `*EntitySchemaValidationTest` ([schema_classes]): Hibernate's own
`validate` of every entity against the migrated MariaDB schema (a `UUID` column an entity maps without
`columnDefinition = "uuid"` is red there and in a dev boot's log, nowhere else) — and `*StoredTimeSliceTest`, whose
column-type and session-zone checks only the production engine can answer (inbox #509: H2 has no `TIMESTAMP` range and
no session zone that moves a value).

**The race user may read lock waits.** The throwaway user gets `PROCESS` beside its databases: the race harness
proves each round's order from InnoDB's lock-wait record (`holdForOverlap` in `testing/Race.kt`, inbox #512), which
MariaDB hides without it, and fails by name rather than counting an unchecked round.

**What it never touches.** The owner's dev database (`veyq-mariadb`, port 3306). The container here has its own
name, binds a free 127.0.0.1 port, is created fresh per run (`--rm`, a fresh database each time) and is stopped
in a `finally`. Its image is read from `infra/docker/docker-compose.yml`, so the pin has one home.

**Not a `validate_*` guard; a CI job, not yet an admitted one.** It needs Docker, so it runs as `pipeline.yml`'s
`backend-races` job, not in the buildless glob. Its first CI run (2026-11-05) ran three of its four projects green
and was cancelled at the job's budget inside `:core`'s, so until a run's log shows every project's race classes
executed it admits nothing (ADR 0040), and a hand run stays the proof.

Usage:
    python3 scripts/run_backend_races_on_mariadb.py [--tests PATTERN]...   # from the repo root

With `--tests`, only those Gradle test filters run (e.g. `'*ShareSharedScopeSliceTest'`). Each filter takes its own
`--tests` (`--tests A --tests B`): a second pattern after one `--tests` is read as nothing and silently skipped, so
`--tests A B` runs `A` alone. Exit code: the first
failing project's Gradle exit code, 0 when every project is green.
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

import repo_layout  # noqa: E402 - where the deployment files and the test harnesses live (ADR 0106)

REPO = Path(__file__).resolve().parent.parent
BACKEND = repo_layout.BACKEND
COMPOSE_FILE = repo_layout.DEV_COMPOSE
NAME = f"veyq-race-mariadb-{os.getpid()}"
PASSWORD = "race"  # a throwaway container's credential, not a secret
RACE_CALL = re.compile(r"\b(race|whileUncommitted|holdForOverlap)\(|\bCountDownLatch\b|\bCyclicBarrier\b")


def pinned_image() -> str:
    """The MariaDB image `infra/docker/docker-compose.yml` pins — the one production runs."""
    for line in COMPOSE_FILE.read_text().splitlines():
        m = re.match(r"\s*image:\s*(mariadb:\S+)", line)
        if m:
            return m.group(1)
    sys.exit(f"no mariadb image in {COMPOSE_FILE}")


def race_classes() -> list[str]:
    """Every test class that calls the race harness, as Gradle `--tests` filters. Only the test roots are read: the
    harness itself (`testing/Race.kt`) and every other shared helper live in a fixtures root (`:core`'s `src/testFixtures`),
    which holds no test class, so no file is skipped by name. Fails loudly on an empty set: a run of nothing would read as a
    green proof."""
    found = []
    for f in backend_tree.kotlin_files(backend_tree.test_roots()):
        if RACE_CALL.search(f.read_text()):
            found.append(f"*{f.stem}")
    if not found:
        sys.exit("no race test class found — refusing to report a run of nothing")
    return found


# The schema checks the default run adds: Hibernate's `validate` per project, and the stored time's type and zone.
ENGINE_SUFFIXES = ("EntitySchemaValidationTest", "StoredTimeSliceTest")


def schema_classes() -> list[str]:
    """The schema-validation classes (`*EntitySchemaValidationTest`, one per project that holds entities): Hibernate's
    `validate` against what Flyway built. They are no race, but the default run is the one place the production engine
    is asked whether an entity still matches its column (H2 names column types differently, and `%prod` runs
    `schema-management.strategy=none`, so a drift would otherwise show only as an ERROR in a dev boot)."""
    found = [f"*{f.stem}" for f in backend_tree.kotlin_files(backend_tree.test_roots())
             if f.stem.endswith(ENGINE_SUFFIXES)]
    for suffix in ENGINE_SUFFIXES:
        if not any(f.endswith(suffix) for f in found):
            sys.exit(f"no {suffix} found — the engine's schema check would silently not run")
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
            sys.exit(f"{root.relative_to(REPO)} is a test root no project in src/backend/settings.gradle.kts owns — "
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


def gradle_command(project: str, filters: list[str]) -> list[str]:
    """One project's Gradle run: `<project>:test --rerun --tests A …`, the root as `:test`."""
    cmd = ["./gradlew", f"{project.rstrip(':')}:test", "--rerun"]
    for f in filters:
        cmd += ["--tests", f]
    return cmd


def database_of(project: str) -> str:
    """The project's own database in the throwaway container: `veyq` for the root, `veyq_core`, `veyq_apps_memories`."""
    slug = project.strip(":").replace(":", "_")
    return f"veyq_{slug}" if slug else "veyq"


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def start(image: str, port: int, databases: list[str]) -> None:
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
            for db in databases:
                subprocess.run(
                    ["docker", "exec", NAME, "mariadb", "-uroot", f"-p{PASSWORD}", "-e",
                     f"CREATE DATABASE IF NOT EXISTS `{db}`; GRANT ALL ON `{db}`.* TO 'veyq'@'%';"],
                    check=True, capture_output=True, text=True, timeout=60,
                )
            # The race harness reads InnoDB's lock waits to prove each round's order was forced (Race.kt
            # `holdForOverlap`, inbox #512); information_schema.INNODB_LOCK_WAITS / INNODB_TRX answer error 1227
            # without PROCESS (measured, MariaDB 11.4). A grant on this throwaway container only.
            subprocess.run(
                ["docker", "exec", NAME, "mariadb", "-uroot", f"-p{PASSWORD}", "-e",
                 "GRANT PROCESS ON *.* TO 'veyq'@'%';"],
                check=True, capture_output=True, text=True, timeout=60,
            )
            return
        time.sleep(2)
    sys.exit("the throwaway MariaDB did not become ready")


def main(argv: list[str]) -> int:
    filters = [argv[i + 1] for i, a in enumerate(argv) if a == "--tests" and i + 1 < len(argv)] or race_classes() + schema_classes()
    by_project = project_filters(filters)
    for project in sorted(by_project):
        print(f"{project}: {' '.join(by_project[project])}", flush=True)
    image, port = pinned_image(), free_port()
    print(f"race run on {image} at 127.0.0.1:{port}: {len(filters)} filter(s) in {len(by_project)} project(s)", flush=True)
    start(image, port, [database_of(p) for p in by_project])
    try:
        timeout = int(os.environ.get("VEYQ_TEST_TIMEOUT_S", "2400"))
        failed = 0
        for project in sorted(by_project):
            env = dict(os.environ)
            env["VEYQ_TEST_DB_KIND"] = "mariadb"
            env["VEYQ_TEST_DB_URL"] = (
                f"jdbc:mariadb://127.0.0.1:{port}/{database_of(project)}?user=veyq&password={PASSWORD}")
            print(f"{project}: database {database_of(project)}", flush=True)
            rc = subprocess.run(gradle_command(project, by_project[project]), cwd=BACKEND, env=env,
                                timeout=timeout).returncode
            failed = failed or rc
        return failed
    finally:
        subprocess.run(["docker", "stop", NAME], capture_output=True, timeout=120)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
