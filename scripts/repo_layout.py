"""Where the code surfaces, the deployment files and the test harnesses live — the one place a script learns it
(ADR 0106).

Every script that reads a code surface (the backend, the web client, the admin surface, the crypto package), the app
image's Dockerfile, a Compose file, the `.env` template, the Helm chart, the tools image's Dockerfile, the browser
suite, lane L1's overlay or the API fuzzer asks this module instead of spelling the path itself. A path split over
`/` (`ROOT / "infra" / "fuzz"`) is exactly what a grep for the joined path misses, which is why none is spelled
outside this file (cr-repo-layout-infra-and-tests).

Two kinds of reader keep their own literal on purpose, each saying so where it does:
- `instance_backup.py` (and its selftest) ship alone — into the tools image's `/opt/veyq/` and as a release asset —
  where this module is not beside them; the selftest compares their defaults with this module when it runs in the
  repository.
- `validate_guard_gaps.py`'s forgeries, where a forgery must spell the path it forges.

Not a guard (no `validate_` prefix): it asserts nothing and is imported by the scripts that use it.
"""
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

# The code surfaces (ADR 0106 decision 5), under `src/` since stage 2. Not to be confused with a surface's own `src/`
# (`src/web/src`, a Gradle module's `src/main`), which is the surface's business.
SRC = ROOT / "src"
BACKEND = SRC / "backend"
WEB = SRC / "web"
ADMIN_WEB = SRC / "admin-web"
CRYPTO = SRC / "packages/crypto"
# Every surface, for the guards that iterate them.
SURFACES = (BACKEND, WEB, ADMIN_WEB, CRYPTO)
# Where a tree from before stage 2 carries each surface (repository-relative): the upgrade proof builds a predecessor
# that may be of either layout (ADR 0106 decision 7). Never read for this checkout - only for another tree.
LEGACY_SURFACES = {BACKEND: "backend", WEB: "web", ADMIN_WEB: "admin-web", CRYPTO: "packages/crypto"}
# The two operator tools a release ships as assets, with their selftests (ADR 0106 decision 8). Not a surface: no
# reader takes them from a predecessor's tree, so they need no legacy path.
TOOLS_DIR = SRC / "tools"
VEYQCTL = TOOLS_DIR / "veyqctl.py"
INSTANCE_BACKUP = TOOLS_DIR / "instance_backup.py"

# A released image's Dockerfile lives beside the code it ships (ADR 0106 decision 9), and every one is built from the
# repository root (`docker build -f src/backend/Dockerfile .`). The app image is the backend's — the web's SPA is baked
# into its resources —; the tools image (ADR 0098), which only the chart runs, packages `instance_backup.py`.
APP_DOCKERFILE = BACKEND / "Dockerfile"
TOOLS_DOCKERFILE = TOOLS_DIR / "Dockerfile"

INFRA_DIR = ROOT / "infra"
# What Docker builds and Compose runs. The dev file declares its project name (`infra`), so the directory's name is
# not the project's (ADR 0106 decision 2).
DOCKER_DIR = INFRA_DIR / "docker"
DEV_COMPOSE = DOCKER_DIR / "docker-compose.yml"
PROD_COMPOSE = DOCKER_DIR / "docker-compose.prod.yml"
# The Compose files a guard over "every compose file" reads - named, never globbed: lane L1's overlay is not one of
# them, and a directory scan's reach would change whenever a file moved in or out of it.
COMPOSE_FILES = (DEV_COMPOSE, PROD_COMPOSE)
# Beside the file that reads `.env`: Compose reads `.env` from the project directory.
ENV_TEMPLATE = DOCKER_DIR / ".env.example"
# Where `instance_backup.py capture` writes by default (gitignored).
BACKUP_ROOT = INFRA_DIR / "backups"

# The Helm chart.
KUBERNETES_DIR = INFRA_DIR / "kubernetes"
CHART_DIR = KUBERNETES_DIR / "charts/veyq"
# Where a release before ADR 0106 carries its chart: `chart_upgrade_check.py` reads a predecessor's tree, which may
# be of either layout (ADR 0106 decision 3).
LEGACY_CHART_PATH = "charts/veyq"

# The test harnesses: the browser suite (the npm workspace `@veyq/e2e`), lane L1's Compose overlay, the API fuzzer.
TESTS_DIR = ROOT / "tests"
E2E_DIR = TESTS_DIR / "e2e"
E2E_STACK_DIR = E2E_DIR / "stack"
FUZZ_DIR = TESTS_DIR / "fuzz"


def rel(path: pathlib.Path) -> str:
    """`path` relative to the repository root, `/`-separated — the form git, a workflow and a message spell."""
    return path.relative_to(ROOT).as_posix()


CHART_PATH = rel(CHART_DIR)


def tools_importable() -> None:
    """Put `TOOLS_DIR` on `sys.path`, so a script here can `import instance_backup` or `import veyqctl`: Python puts
    only the running script's own directory there, and the tools live in another one."""
    if str(TOOLS_DIR) not in sys.path:
        sys.path.insert(0, str(TOOLS_DIR))


def surface_paths(surface: pathlib.Path) -> tuple[str, str]:
    """(`surface` in this layout, in the layout before stage 2) - repository-relative, the forms `git ls-tree` takes."""
    return rel(surface), LEGACY_SURFACES[surface]


def surface_in(tree: pathlib.Path, surface: pathlib.Path) -> pathlib.Path:
    """`surface` in another checkout `tree` - a predecessor's worktree - in this layout where that tree has it, else
    where a tree from before stage 2 carries it (ADR 0106 decision 7). A tree with neither gets the legacy path, which
    then fails loudly where it is read (no jar, no `package.json`)."""
    current, legacy = surface_paths(surface)
    return tree / current if (tree / current).is_dir() else tree / legacy
