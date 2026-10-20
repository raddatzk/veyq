"""Where the deployment files and the test harnesses live — the one place a script learns it (ADR 0106).

Every script that reads the app image's Dockerfile, a Compose file, the `.env` template, the Helm chart, the tools
image's Dockerfile, the browser suite, lane L1's overlay or the API fuzzer asks this module instead of spelling the
path itself. A path split over `/` (`ROOT / "infra" / "fuzz"`) is exactly what a grep for the joined path misses,
which is why none is spelled outside this file (cr-repo-layout-infra-and-tests).

Two kinds of reader keep their own literal on purpose, each saying so where it does:
- `instance_backup.py` (and its selftest) ship alone — into the tools image's `/opt/veyq/` and as a release asset —
  where this module is not beside them; the selftest compares their defaults with this module when it runs in the
  repository.
- `validate_guard_gaps.py`'s forgeries, where a forgery must spell the path it forges.

Not a guard (no `validate_` prefix): it asserts nothing and is imported by the scripts that use it.
"""
from __future__ import annotations

import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent

INFRA_DIR = ROOT / "infra"
# What Docker builds and Compose runs. The dev file declares its project name (`infra`), so the directory's name is
# not the project's (ADR 0106 decision 2).
DOCKER_DIR = INFRA_DIR
APP_DOCKERFILE = ROOT / "Dockerfile"
DEV_COMPOSE = DOCKER_DIR / "docker-compose.yml"
PROD_COMPOSE = DOCKER_DIR / "docker-compose.prod.yml"
# The Compose files a guard over "every compose file" reads - named, never globbed: lane L1's overlay is not one of
# them, and a directory scan's reach would change whenever a file moved in or out of it.
COMPOSE_FILES = (DEV_COMPOSE, PROD_COMPOSE)
# Beside the file that reads `.env`: Compose reads `.env` from the project directory.
ENV_TEMPLATE = DOCKER_DIR / ".env.example"
# Where `instance_backup.py capture` writes by default (gitignored).
BACKUP_ROOT = INFRA_DIR / "backups"

# The Helm chart, and the image only the chart runs (ADR 0098).
CHART_DIR = ROOT / "charts/veyq"
TOOLS_DOCKERFILE = ROOT / "tools/Dockerfile"
# Where a release before ADR 0106 carries its chart: `chart_upgrade_check.py` reads a predecessor's tree, which may
# be of either layout (ADR 0106 decision 3).
LEGACY_CHART_PATH = "charts/veyq"

# The test harnesses: the browser suite (the npm workspace `@veyq/e2e`), lane L1's Compose overlay, the API fuzzer.
E2E_DIR = ROOT / "e2e"
E2E_STACK_DIR = INFRA_DIR / "e2e"
FUZZ_DIR = INFRA_DIR / "fuzz"


def rel(path: pathlib.Path) -> str:
    """`path` relative to the repository root, `/`-separated — the form git, a workflow and a message spell."""
    return path.relative_to(ROOT).as_posix()


CHART_PATH = rel(CHART_DIR)
