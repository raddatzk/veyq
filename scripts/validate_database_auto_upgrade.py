#!/usr/bin/env python3
"""Validate that every bundled MariaDB upgrades a data directory an older MariaDB wrote: `MARIADB_AUTO_UPGRADE=1`.

Why: the image's entrypoint runs `mariadb-upgrade` (after backing the system tables up into the data directory) only
when that variable is set. Without it a server of a newer major starts on the old directory and leaves the system
tables on the old layout — the state the bundled database moved into on 2026-11-07, when the chart, both Compose files
and the backup tool went from MariaDB 11.4 to 12.3 while cloud.raddatz.me held an 11.4 directory (owner 2026-11-08:
"we should be able to deal with that"). Every bundled database of both deployment targets runs it, so each sets it:

  - the Helm chart's StatefulSet (`templates/mariadb.yaml`): an `env` entry `MARIADB_AUTO_UPGRADE` = `"1"`;
  - every Compose file's `mariadb` service (`repo_layout.COMPOSE_FILES`): `environment.MARIADB_AUTO_UPGRADE` = `"1"`.

Limit: it reads the definitions, it does not prove an upgrade runs — that is the image's behaviour, measured once by
hand (an 11.4 directory started under 12.3 with the variable). A database an operator brings
(`mariadb.enabled: false`, or a Compose override) is theirs to upgrade, and this guard does not see it. The chart's
template is read as text for its one `env` entry, so an entry rendered from a value (`{{ … }}`) is not one it accepts.

Registers itself by being a `scripts/validate_*.py` (the trust-chain glob).
"""
from __future__ import annotations

import pathlib
import re
import sys

import yaml

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import repo_layout  # noqa: E402

VAR = "MARIADB_AUTO_UPGRADE"
CHART_TEMPLATE = repo_layout.CHART_DIR / "templates/mariadb.yaml"
# `- name: MARIADB_AUTO_UPGRADE` followed by its `value: "1"` (quoted: an env value is a string).
CHART_ENTRY = re.compile(rf'^\s*- name: {VAR}\s*\n\s*value: "1"\s*$', re.MULTILINE)


def compose_errors(path: pathlib.Path) -> list[str]:
    doc = yaml.safe_load(path.read_text()) or {}
    service = (doc.get("services") or {}).get("mariadb")
    if service is None:
        return [f"{repo_layout.rel(path)}: no `mariadb` service, so nothing says how its database is upgraded"]
    env = service.get("environment") or {}
    if isinstance(env, list):
        env = dict(item.split("=", 1) if "=" in item else (item, None) for item in env)
    if str(env.get(VAR)) != "1":
        return [f"{repo_layout.rel(path)}: service `mariadb` sets {VAR}={env.get(VAR)!r}, not \"1\" — a newer MariaDB "
                f"would leave an older data directory's system tables un-upgraded"]
    return []


def chart_errors(path: pathlib.Path) -> list[str]:
    if not CHART_ENTRY.search(path.read_text()):
        return [f"{repo_layout.rel(path)}: no env entry `{VAR}` with value \"1\" — a newer MariaDB would leave an "
                f"older data directory's system tables un-upgraded"]
    return []


def main() -> int:
    errors = chart_errors(CHART_TEMPLATE)
    for path in repo_layout.COMPOSE_FILES:
        errors += compose_errors(path)
    if errors:
        print("database auto-upgrade FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"database auto-upgrade OK: the chart and {len(repo_layout.COMPOSE_FILES)} Compose file(s) set {VAR}=1")
    return 0


if __name__ == "__main__":
    sys.exit(main())
