#!/usr/bin/env python3
"""Validate the platform capability catalog (contracts/platform/capabilities.json, ADR 0032).

A capability is a COARSER projection over use-cases (many-to-many, not a 1:1 rename). This is the deterministic
guard that the catalog stays wired to the use-case graph instead of drifting into a parallel list — the same
guarantee validate_feature_wiring gives the feature graph:

  Every capability's `serves: [uc.…]` must resolve to a REAL, BUILT use-case:
    - real  — the use-case doc docs/features/<feature>/use-cases/<id>.md exists (catches a typo / a renamed uc),
    - built — its coverage YAML carries no `status: planned` (a capability is advertised active only once its
              use-case(s) are built, ADR 0032 §6).

It also checks the catalog is well-formed: a lowercase-dotted id, a non-empty `serves`, and a known `source`.

Reserving an id ahead of its use-cases being built (advertised active only once built) is a deferred lifecycle
extension; when it lands, the built-check gains an explicit reserved-entry skip. For the lean core every catalog
entry is already built, so both checks are hard.
"""
from __future__ import annotations

import json
import pathlib
import re
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
CATALOG = ROOT / "contracts" / "platform" / "capabilities.json"
FEATURES = ROOT / "docs" / "features"
COVERAGE = ROOT / "docs" / "coverage" / "features"

ID_RE = re.compile(r"^[a-z0-9]+(\.[a-z0-9-]+)+$")
UC_RE = re.compile(r"^uc\.([a-z0-9-]+)\.[a-z0-9-]+$")
KNOWN_SOURCES = {"operator-policy", "infra-preflight"}

errors: list[str] = []


def is_built(uc_id: str, feature: str) -> bool | None:
    """True/False whether the use-case's coverage YAML is built (status != planned); None if the YAML is absent."""
    cov = COVERAGE / feature / "use-cases" / f"{uc_id}.yaml"
    if not cov.exists():
        return None
    data = yaml.safe_load(cov.read_text()) or {}
    return data.get("status") != "planned"


catalog = json.loads(CATALOG.read_text())
caps = catalog.get("capabilities", {})
if not caps:
    errors.append(f"{CATALOG.relative_to(ROOT)}: no capabilities defined")

for cid, entry in caps.items():
    if not ID_RE.match(cid):
        errors.append(f"capability id '{cid}' is not a lowercase-dotted token")
    if entry.get("source") not in KNOWN_SOURCES:
        errors.append(f"{cid}: source '{entry.get('source')}' is not one of {sorted(KNOWN_SOURCES)}")
    serves = entry.get("serves")
    if not isinstance(serves, list) or not serves:
        errors.append(f"{cid}: serves must be a non-empty list of use-case ids")
        continue
    for uc in serves:
        m = UC_RE.match(uc)
        if not m:
            errors.append(f"{cid}: serves '{uc}' is not a uc.<feature>.<name> id")
            continue
        feature = m.group(1)
        doc = FEATURES / feature / "use-cases" / f"{uc}.md"
        if not doc.exists():
            errors.append(f"{cid}: serves '{uc}' resolves to no use-case doc ({doc.relative_to(ROOT)} missing)")
            continue
        built = is_built(uc, feature)
        if built is None:
            errors.append(f"{cid}: serves '{uc}' has no coverage YAML — cannot confirm it is built")
        elif not built:
            errors.append(
                f"{cid}: serves '{uc}' is not built yet (coverage status: planned) — a capability is advertised "
                f"active only once its use-cases are built (ADR 0032 §6)")

if errors:
    print("CAPABILITY CATALOG VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

n_serves = sum(len(e.get("serves", [])) for e in caps.values())
print(f"capability catalog OK: {len(caps)} capabilit(ies) serving {n_serves} built use-case(s)")
