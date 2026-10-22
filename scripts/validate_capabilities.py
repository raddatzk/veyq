#!/usr/bin/env python3
"""Validate the platform capability catalog (contracts/platform/capabilities.json, ADR 0032).

A capability is a COARSER projection over use-cases (many-to-many, not a 1:1 rename). This is the deterministic
guard that the catalog stays wired to the use-case graph instead of drifting into a parallel list — the same
guarantee validate_feature_wiring gives the feature graph:

  Every capability's `serves: [uc.…]` must resolve to a REAL, BUILT use-case:
    - real  — the use-case doc docs/features/<feature>/use-cases/<id>.md exists (catches a typo / a renamed uc),
    - built — its coverage YAML carries no `status: planned` (a capability is advertised active only once its
              use-case(s) are built, ADR 0032 §6).

It also checks the catalog is well-formed: a lowercase-dotted id, a non-empty `serves`, and a known `source`
(`operator-policy`, `infra-preflight`, or `external-service`: an outside service the browser calls, active only while
its address is configured).

And it checks the catalog is a TREE (ADR 0088 decisions 1 and 2):
    - root  — every id's first segment is in `ROOTS`: `core`, and every application the backend composes, derived
              from its module directories (`src/backend/apps/<name>` with a `build.gradle.kts`), never typed here. The
              first application entries (`memories.places`, `memories.map`) came with cr-external-services-as-settings.
    - parent — every id of three or more segments has its parent (the id minus its last segment) as a catalog
              entry. The server's effective state is an AND along the path, and an entry whose parent is missing
              has nothing to AND with. A root (`core`) is not an entry, so a two-segment id has no parent to need.

And it checks every entry names who may switch it (`levels`, ADR 0088 decisions 2 and 12): a list holding
`instance` (the Server Owner always may) and `tenant` where a tenant owner may narrow it for their tenant, nothing else
and nothing twice. The backend generates `CapabilityIds.TENANT_SWITCHABLE` from it, and the tenant's write refuses any
id outside it, so a missing or misspelt level would quietly take a switch away from tenant owners or hand one out.
The defect that bought the check: no incident. Built with cr-tenant-capability-switches; run against the untouched
catalog, which carried no `levels`, it was red seven times, once per entry.

And it checks every requirement (ADR 0088 decision 8): a `flag` or `days` `kind`, its `title` and `whenSet` in both
languages, a name no other entry uses, and that the name is a property of the wire's `Capability`
(`contracts/api/_common.openapi.yaml`) of the matching type (a flag boolean, days integer), because both deliveries carry
the values as `[{ id, …values }]`. A requirement missing there would be stored, enforced and invisible to every client.
The defect that bought it: no incident. Built with cr-capability-requirements; run against a catalog with the four
requirements and the untouched `Capability`, it was red four times, once per name.

And it checks every entry carries its words (ADR 0088 decision 13): `title`, `purpose` and `whenOff`, each an
object with a non-empty `en` and a non-empty `de` string (`TEXT_FIELDS`, `LANGUAGES`). They are what the Server
Owner reads before switching an entry off, and the catalog is the one place they are written: the API serves them
and no client keeps its own copy. The dev-facing `description` stays and is not read.

The defect that bought the text check: no incident. Built with cr-operator-capability-switches, because the
console and the CLI render an entry from these fields and a missing one would be a blank line where the Server
Owner decides whether to switch it off. Run against the untouched catalog, which carried none of them, it was red
21 times: three fields on each of seven entries.

The defect that bought the tree checks: no incident. Built with cr-capability-register-recut, because the catalog
was four flat ids under no common root, and run against that untouched catalog the root check was red once per
id. The re-cut fixed it; these checks keep a flat id or an orphaned child from re-entering.

Its limit: it reads the catalog alone. It does not prove that the server gates an id, that it ANDs along the path,
or that the clients follow the catalog: the backend's ids and texts are generated from it (buildSrc's
`GenerateCapabilityRegister`, which also refuses an entry without its six texts, and whose wiring
`CapabilityRegistryTest` reads back), the arithmetic is `CapabilityRegistryTest`'s, and the web's constants are held
by its `capabilities.test.ts`. Nor does it see an inheritance that is not a path prefix (an application's share entry
inheriting `core.public-shares`): no such entry exists yet, and the CR that adds one owns that rule. Of the texts it
proves only that both languages are there and not blank: not that `whenOff` matches what the server does when the
entry is off, and not that the German says what the English says. Those are read by a person, against the gate
and the key-material handler the entry names.

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

import repo_layout  # where the code surfaces live (ADR 0106)

ROOT = pathlib.Path(__file__).resolve().parent.parent
CATALOG = ROOT / "contracts" / "platform" / "capabilities.json"
FEATURES = ROOT / "docs" / "features"
COVERAGE = ROOT / "docs" / "coverage" / "features"

ID_RE = re.compile(r"^[a-z0-9]+(\.[a-z0-9-]+)+$")
UC_RE = re.compile(r"^uc\.([a-z0-9-]+)\.[a-z0-9-]+$")
KNOWN_SOURCES = {"operator-policy", "infra-preflight", "external-service"}
# Who may switch an entry off (ADR 0088 decisions 2 and 12): the Server Owner always, a tenant owner where listed.
LEVELS = ("instance", "tenant")
# A requirement's kind (ADR 0088 decision 8) and the type its property has on the wire's `Capability`.
REQUIREMENT_KINDS = {"flag": "boolean", "days": "integer"}
REQUIREMENT_TEXTS = ("title", "whenSet")
COMMON = ROOT / "contracts" / "api" / "_common.openapi.yaml"
# The roots an id may hang from (ADR 0088 decision 1): `core`, and every application the backend composes, derived from
# its module directories (`<backend>/apps/<name>`, the `:apps:<name>` projects) rather than typed here.
APPS = repo_layout.BACKEND / "apps"
ROOTS = {"core"} | {d.name for d in APPS.iterdir() if d.is_dir() and (d / "build.gradle.kts").exists()}
# The words every entry carries for the Server Owner (ADR 0088 decision 13), each in every language below.
TEXT_FIELDS = ("title", "purpose", "whenOff")
LANGUAGES = ("en", "de")

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
    segments = cid.split(".")
    if segments[0] not in ROOTS:
        errors.append(f"{cid}: root segment '{segments[0]}' is not one of {sorted(ROOTS)} (ADR 0088 decision 1)")
    if len(segments) >= 3:
        parent = ".".join(segments[:-1])
        if parent not in caps:
            errors.append(
                f"{cid}: parent '{parent}' is not a catalog entry — the AND along the path has nothing to AND with "
                f"(ADR 0088 decision 2)")
    for field in TEXT_FIELDS:
        text = entry.get(field)
        if not isinstance(text, dict):
            errors.append(f"{cid}: {field} must be an object with {' and '.join(LANGUAGES)} (ADR 0088 decision 13)")
            continue
        for lang in LANGUAGES:
            value = text.get(lang)
            if not isinstance(value, str) or not value.strip():
                errors.append(f"{cid}: {field}.{lang} must be a non-empty string (ADR 0088 decision 13)")
    levels = entry.get("levels")
    if (not isinstance(levels, list) or "instance" not in levels or len(set(levels)) != len(levels)
            or any(level not in LEVELS for level in levels)):
        errors.append(
            f"{cid}: levels must list 'instance', and 'tenant' where a tenant owner may switch it too, nothing else "
            f"(ADR 0088 decisions 2 and 12), got {levels!r}")
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

# Requirements (ADR 0088 decision 8): each a flag or a number of days with its words, and each name a property of the
# wire's `Capability` of the matching type, because both deliveries carry the values as `[{ id, …values }]`.
capability_schema = (yaml.safe_load(COMMON.read_text()) or {}).get("components", {}).get("schemas", {}).get("Capability", {})
wire_properties = capability_schema.get("properties", {})
seen_requirements: dict[str, str] = {}
for cid, entry in caps.items():
    requirements = entry.get("requirements", {})
    if not isinstance(requirements, dict):
        errors.append(f"{cid}: requirements must be an object of name -> requirement")
        continue
    for name, req in requirements.items():
        where = f"{cid}: requirement {name}"
        if name in seen_requirements:
            errors.append(f"{where} is also {seen_requirements[name]}'s: a name is one property of the wire's Capability")
        seen_requirements[name] = cid
        kind = req.get("kind") if isinstance(req, dict) else None
        if kind not in REQUIREMENT_KINDS:
            errors.append(f"{where}: kind must be one of {sorted(REQUIREMENT_KINDS)}, got {kind!r}")
        for field in REQUIREMENT_TEXTS:
            text = req.get(field) if isinstance(req, dict) else None
            if not isinstance(text, dict) or any(not isinstance(text.get(lang), str) or not text[lang].strip() for lang in LANGUAGES):
                errors.append(f"{where}: {field} must carry a non-empty {' and '.join(LANGUAGES)} (ADR 0088 decision 13)")
        prop = wire_properties.get(name)
        if prop is None:
            errors.append(f"{where} is no property of Capability in {COMMON.relative_to(ROOT)}: no client could read it")
        elif kind in REQUIREMENT_KINDS and REQUIREMENT_KINDS[kind] not in (
            [prop.get("type")] if isinstance(prop.get("type"), str) else (prop.get("type") or [])
        ):
            errors.append(f"{where}: Capability.{name} is {prop.get('type')!r}, a {kind} needs {REQUIREMENT_KINDS[kind]}")

if errors:
    print("CAPABILITY CATALOG VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

n_serves = sum(len(e.get("serves", [])) for e in caps.values())
print(f"capability catalog OK: {len(caps)} capabilit(ies) serving {n_serves} built use-case(s)")
