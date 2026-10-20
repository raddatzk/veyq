#!/usr/bin/env python3
"""The docs site's configuration reference names every setting the deployment recipes take.

**What it asserts.** `site/src/content/docs/run/configuration.md` carries, as inline code (`` `NAME` ``), every
  - variable the production compose file interpolates (`${NAME…}` in `infra/docker-compose.prod.yml`, comment lines
    left out — a variable named only in a comment is no input);
  - variable `infra/.env.example` sets (`NAME=` at the start of a line) or offers commented out (`#   NAME=`);
  - leaf of the chart's `charts/veyq/values.yaml`, as its dotted path (`gateway.tls.acme.email`); an empty map or a
    list is a leaf of its own (`gateway.annotations`, `networkPolicy.ingressFrom`).
Each source must yield at least one name, so a moved or renamed file reports red instead of checking nothing.

**The defect that bought it — no incident.** Built with the page (cr-run-area-content phase 4). The recipes change
whenever a CR touches deployment — `cr-store-connection-in-admin-web` removed seven store variables from the compose
file and two from `.env.example` in one phase — and a reference page that silently keeps the old list, or lacks a new
required variable, sends a Server Owner to an `up` that dies on a missing name.

**Its limits — what a green run does NOT prove.** Presence only: that the page mentions each name, not that its
"required", default or meaning is right, nor that a name the page lists still exists (a removed variable left on the
page passes). The backend's own `veyq.*` keys are not held to the page: they are tuning, and the page names a chosen
few. The admin image's and the CLI's variables are not read either. A name mentioned in backticks anywhere on the
page counts, inside a sentence as much as in a table row.

Buildless and diff-free, so it belongs in the trust-chain glob. Runnable locally:

    python3 scripts/validate_run_config_reference.py
"""
from __future__ import annotations  # the repo's guards run on Python 3.9

import pathlib
import re
import sys

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the other guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

import repo_layout  # noqa: E402 - where the deployment files and the test harnesses live (ADR 0106)

ROOT = pathlib.Path(__file__).resolve().parent.parent
PAGE = ROOT / "site/src/content/docs/run/configuration.md"
COMPOSE = repo_layout.PROD_COMPOSE
ENV_EXAMPLE = repo_layout.ENV_TEMPLATE
VALUES = repo_layout.CHART_DIR / "values.yaml"

INTERPOLATION = re.compile(r"(?<!\$)\$\{([A-Z_][A-Z0-9_]*)")
ENV_SET = re.compile(r"^([A-Z_][A-Z0-9_]*)=")
ENV_OFFERED = re.compile(r"^#\s+([A-Z_][A-Z0-9_]*)=")

errors: list[str] = []


def compose_variables() -> set[str]:
    lines = COMPOSE.read_text().splitlines()
    return {m for line in lines if not line.lstrip().startswith("#") for m in INTERPOLATION.findall(line)}


def env_example_variables() -> set[str]:
    found = set()
    for line in ENV_EXAMPLE.read_text().splitlines():
        m = ENV_SET.match(line) or ENV_OFFERED.match(line)
        if m:
            found.add(m.group(1))
    return found


def value_paths(node, prefix: str = "") -> set[str]:
    if isinstance(node, dict) and node:
        return {p for key, child in node.items() for p in value_paths(child, f"{prefix}.{key}" if prefix else str(key))}
    return {prefix} if prefix else set()


for path in (PAGE, COMPOSE, ENV_EXAMPLE, VALUES):
    if not path.is_file():
        errors.append(f"{path.relative_to(ROOT)} is missing — this guard reads it")

if not errors:
    page = PAGE.read_text()
    named = set(re.findall(r"`([^`\n]+)`", page))
    sources = {
        COMPOSE.relative_to(ROOT): compose_variables(),
        ENV_EXAMPLE.relative_to(ROOT): env_example_variables(),
        VALUES.relative_to(ROOT): value_paths(yaml.safe_load(VALUES.read_text()) or {}),
    }
    for source, names in sources.items():
        if not names:
            errors.append(f"{source} yields no setting at all — its format changed, so this guard checked nothing")
        for name in sorted(names - named):
            errors.append(f"`{name}` ({source}) is not on {PAGE.relative_to(ROOT)} — a Server Owner reading the "
                          f"reference does not learn that it exists")

if errors:
    print("RUN CONFIG REFERENCE VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

counts = ", ".join(f"{len(n)} from {s}" for s, n in sources.items())
print(f"run config reference OK: the page names every setting ({counts})")
