"""Where the backend's sources live — the one place a script learns it (cr-platform-gradle-modules, step 2).

Every guard that reads Kotlin, a test or a migration asks this module instead of spelling
`backend/src/main/kotlin/com/veyq/…` itself. The roots are **found by glob** over
`backend/**/src/{main,test,testFixtures}/…`, never listed: today that is the one Gradle project's `backend/src`, and
when the module split adds `backend/core/src` and `backend/apps/memories/src` every guard sees them without an edit.
Build output, Gradle caches and `node_modules` are not sources and are skipped by path segment.

A file inside the tree is named by **its package path**, dotted (`com.veyq.x.y.Name`, the file `Name.kt`) or slashed
(`com/veyq/x/y/Name.kt`), and `source_file` resolves it under whichever root holds it — so a guard anchored on a
class keeps working when the class changes module, and goes red (the path does not exist) when it changes package.
A name no root holds resolves to its spot under the first root, a path that does not exist: the guard's own
"missing" line then fires, as it did when the path was hard-coded.

Not a guard (no `validate_` prefix): it asserts nothing and is imported by the guards that use it.
"""
from __future__ import annotations

import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
BACKEND = ROOT / "backend"
# Path segments that mark generated or cached trees, never sources.
_SKIP = {"build", ".gradle", "node_modules", "out", "bin"}


def _roots(source_set: str, tail: str) -> list[pathlib.Path]:
    found = []
    for d in BACKEND.glob(f"**/src/{source_set}/{tail}"):
        if d.is_dir() and not (_SKIP & set(d.relative_to(BACKEND).parts)):
            found.append(d)
    return sorted(found)


def main_roots() -> list[pathlib.Path]:
    """Every `src/main/kotlin` under `backend/`."""
    return _roots("main", "kotlin")


def test_roots() -> list[pathlib.Path]:
    """Every `src/test/kotlin` under `backend/`."""
    return _roots("test", "kotlin")


def fixture_roots() -> list[pathlib.Path]:
    """Every `src/testFixtures/kotlin` under `backend/` — none until a module applies `java-test-fixtures`."""
    return _roots("testFixtures", "kotlin")


def migration_roots() -> list[pathlib.Path]:
    """Every `src/main/resources/db/migration` under `backend/`."""
    return _roots("main", "resources/db/migration")


def kotlin_files(roots: list[pathlib.Path], pattern: str = "*.kt") -> list[pathlib.Path]:
    """Every file matching `pattern` below `roots`, sorted by path."""
    return sorted(p for r in roots for p in r.rglob(pattern))


def main_kotlin(pattern: str = "*.kt") -> list[pathlib.Path]:
    return kotlin_files(main_roots(), pattern)


def test_kotlin(pattern: str = "*.kt") -> list[pathlib.Path]:
    return kotlin_files(test_roots() + fixture_roots(), pattern)


def migrations() -> list[pathlib.Path]:
    """Every `*.sql` in every migration root, sorted by file name (the version prefix), then path."""
    return sorted((p for r in migration_roots() for p in r.glob("*.sql")), key=lambda p: (p.name, str(p)))


def _relative(name: str) -> pathlib.Path:
    if "/" in name:
        return pathlib.Path(name)
    parts = name.split(".")
    return pathlib.Path(*parts[:-1], parts[-1] + ".kt")


def package_dirs(package: str, roots: list[pathlib.Path] | None = None) -> list[pathlib.Path]:
    """The directories that hold `package` (dotted or slashed), one per root that has it; [] when none does."""
    rel = pathlib.Path(*package.replace("/", ".").split("."))
    return [r / rel for r in (main_roots() if roots is None else roots) if (r / rel).is_dir()]


def source_file(name: str, roots: list[pathlib.Path] | None = None) -> pathlib.Path:
    """The file a dotted FQN (`com.veyq.x.Name` → `Name.kt`) or a slash path (`com/veyq/x/Name.kt`) names, under
    whichever of `roots` (default: the main roots) holds it. If none does, its spot under the first root — a path
    that does not exist, so the caller's existence check is what reports it."""
    rel = _relative(name)
    candidates = main_roots() if roots is None else roots
    for r in candidates:
        if (r / rel).is_file():
            return r / rel
    return (candidates[0] if candidates else BACKEND / "src" / "main" / "kotlin") / rel


def test_file(name: str) -> pathlib.Path:
    """`source_file` over the test and fixtures roots."""
    return source_file(name, test_roots() + fixture_roots())
