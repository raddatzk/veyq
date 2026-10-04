#!/usr/bin/env python3
"""Guard that no property name is declared opaque in one spec and plaintext in another (ADR 0044, ADR 0053).

**What it asserts.** Across every spec under `contracts/api/` (`contract_specs`, recursive, `_common` included), the set of property names carrying
`contentEncoding: base64` and the set of property names not carrying it are **disjoint**. It reuses
`validate_field_visibility.is_opaque` rather than re-deciding what "opaque" means, so there is one definition
and not two that agree today.

**The defect that bought it.** No incident — this is a *precondition* promoted to a guard, which
`changes/README.md` § promote one-shot proofs is about. `scripts/run_web_e2e.py`'s HTTP opacity sweep
(cc.security.wire-assertion § Surfaces) matches recorded JSON bodies against the opaque set **by property
name**, because a recorded body arrives with no schema attached and mapping a URL back to a spec path would be
a second, drifting copy of the router. That match is exact only while the two sets stay disjoint; it was
measured disjoint when the sweep was written (38 opaque names against 126 plain, intersection empty). If a
name ever landed in both, the sweep would start decoding a legitimately-plaintext field and reporting it — or,
in the direction that matters, a reader would "fix" the false alarm by dropping the name from the oracle and
silently stop checking the opaque one. A security sensor whose precision depends on an unstated property of
the contracts is one spec edit from being weaker than it reads.

**Its limit.** Disjointness is about *names*, not about correctness of the classification: a field wrongly
declared `contentEncoding: base64` in every spec is consistent and still wrong, and that is
`validate_field_visibility.py`'s question, not this one. Nor does a green run say the sweep is armed, that
any suite exercised the field, or that the value in it is actually sealed — those are the e2e run's
answers. Green means exactly: **a property name means one thing across the whole contract surface.** It also
asserts it read `contract_specs.OPAQUE_LOCATION_CANARY`, an opaque property at a location only an application's
spec directory holds: no opaque *name* is application-only, so a reader that stopped seeing that directory would
keep the same name sets and stay green — only a location can tell.
"""
from __future__ import annotations

import pathlib
import sys

import yaml

import contract_specs
import validate_field_visibility

ROOT = pathlib.Path(__file__).resolve().parent.parent


def main() -> int:
    opaque: dict[str, str] = {}
    plain: dict[str, str] = {}
    opaque_locations: set[str] = set()
    specs = contract_specs.specs()
    for spec in specs:
        doc = yaml.safe_load(spec.read_text()) or {}
        for schema_name, schema in ((doc.get("components") or {}).get("schemas") or {}).items():
            for prop, body in (schema.get("properties") or {}).items():
                where = f"{contract_specs.key(spec)}#{schema_name}.{prop}"
                if validate_field_visibility.is_opaque(body):
                    opaque_locations.add(where)
                (opaque if validate_field_visibility.is_opaque(body) else plain).setdefault(prop, where)

    if contract_specs.OPAQUE_LOCATION_CANARY not in opaque_locations:
        print(f"OPAQUE-NAME DISJOINTNESS FAILED — the canary {contract_specs.OPAQUE_LOCATION_CANARY} was not read "
              "as opaque: this reader no longer sees every spec directory (or the canary moved — re-point it).")
        return 1

    both = sorted(set(opaque) & set(plain))
    if both:
        print("OPAQUE-NAME DISJOINTNESS FAILED — a property name means two things:")
        for prop in both:
            print(f"  - {prop!r}: opaque at {opaque[prop]}, plaintext at {plain[prop]}")
        print("The HTTP opacity sweep in scripts/run_web_e2e.py matches by name; give one of them a distinct "
              "name, or teach that sweep to match by operation instead (a much larger change).")
        return 1

    print(f"opaque names OK: {len(opaque)} opaque and {len(plain)} plaintext property name(s) across "
          f"{len(specs)} spec(s), disjoint (canary {contract_specs.OPAQUE_LOCATION_CANARY} read)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
