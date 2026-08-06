#!/usr/bin/env python3
"""Guard the still-duplicated shared OpenAPI components against cross-spec drift (audit #76 / P3f, ADR 0038).

`ErrorResponse` was extracted to `contracts/api/_common.openapi.yaml` and is now `$ref`'d by all 9 specs — a
single source that cannot drift, so it is no longer guarded here. What stays copied across the per-domain specs
(each still a self-contained document a generator consumes on its own): the `bearerAuth` scheme (×8), the
`ScopeId`/`TenantId` path-params, and several shared enums. This guard keeps *those* copies byte-consistent —
the single-source guarantee for the components not worth an external `$ref` (bearerAuth's `description` carries
real per-domain nuance; the enums are inline, so `$ref`-ing them would change the generated type). `_common`
itself is excluded from the scan — it is the source, not a copy.

What it asserts:
  1. Every spec's `components.securitySchemes.bearerAuth` has the same `type`+`scheme` (ignoring the per-domain
     `description`).
  2. Every spec's shared path-params (`ScopeId`, `TenantId`) are structurally identical (ignoring example/description).
  3. Every copy of a shared enum (node-kind, derivative-kind, membership-role, credential-method, suite-version)
     carries the SAME values. An enum that shares **≥1** value with a canonical family but is not exactly it —
     nor a declared intentional variant — is drift. **Principled limit:** a *full rename* (0 shared values) is
     undetectable by value — it is indistinguishable from an unrelated domain enum, so no value-based check can
     see it. This ≥1-overlap rule is the hardening over the original ≥2 heuristic, which silently missed a drop
     on a 2-value family (e.g. `[OWNER]` for membership-role, overlap 1) and every drift of 1-value
     `suite-version` (F6, review-2026-07-14).

Deliberately NOT asserted: collection-wrapping style (bare array vs `{results}`/`{items}` vs a domain key) — that
mixes intentional batch-vs-list conventions, so it is a design call, not a drift bug (see the inbox #76 note).
"""
from __future__ import annotations

import pathlib
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
API = ROOT / "contracts" / "api"

# Shared enum families: canonical (sorted) value set. An enum that shares >=1 value with a family but is not
# exactly it — nor a declared intentional variant — is drift. (A 0-overlap full rename is a principled blind spot.)
CANONICAL_ENUMS: dict[str, tuple[str, ...]] = {
    "node-kind": ("CHAPTER", "FOLDER", "MEMORY"),
    "derivative-kind": ("PREVIEW", "THUMBNAIL"),
    "membership-role": ("MEMBER", "OWNER"),
    "credential-method": ("DEVICE", "DEVICE_KEY", "PASSKEY", "PASSWORD", "RECOVERY"),
    "suite-version": ("v1",),
}
# Value sets that overlap a canonical family on purpose (documented, not drift).
INTENTIONAL_ENUM_VARIANTS: set[tuple[str, ...]] = {
    ("DEVICE", "DEVICE_KEY"),  # account.openapi.yaml — the device-only wrap-rotation subset of credential-method
}

errors: list[str] = []


def strip_keys(obj: object, keys: set[str]) -> object:
    """Recursively drop `keys` (doc/example noise) so structural equality ignores per-domain flavour text."""
    if isinstance(obj, dict):
        return {k: strip_keys(v, keys) for k, v in obj.items() if k not in keys}
    if isinstance(obj, list):
        return [strip_keys(v, keys) for v in obj]
    return obj


def load_specs() -> dict[str, dict]:
    specs = {}
    for path in sorted(API.glob("*.openapi.yaml")):
        if path.name == "_common.openapi.yaml":
            continue  # the shared-component source, not a per-domain copy — never a drift target
        specs[path.name] = yaml.safe_load(path.read_text()) or {}
    return specs


def check_named_component(specs: dict[str, dict], dotted: str, ignore: set[str], label: str) -> None:
    """Assert a named component (e.g. securitySchemes.bearerAuth) is identical across every spec that defines it."""
    section, name = dotted.split(".")
    seen: dict[str, tuple[str, ...]] = {}  # normalized-yaml -> [files]
    for fname, spec in specs.items():
        comp = (spec.get("components", {}) or {}).get(section, {}) or {}
        if name not in comp:
            continue
        norm = yaml.safe_dump(strip_keys(comp[name], ignore), sort_keys=True)
        seen.setdefault(norm, []).append(fname)
    if len(seen) <= 1:
        if not seen:
            errors.append(f"{label}: '{dotted}' defined in no spec — stale guard entry")
        return
    errors.append(
        f"{label}: '{dotted}' has {len(seen)} diverging definitions across specs (must be identical):\n"
        + "\n".join(f"      variant in {sorted(files)}" for files in seen.values()))


def walk_enums(obj: object, path: str, out: list[tuple[str, tuple[str, ...]]]) -> None:
    """Collect every `enum:` list as (json-ish path, sorted value tuple)."""
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k == "enum" and isinstance(v, list) and all(isinstance(x, str) for x in v):
                out.append((path, tuple(sorted(v))))
            else:
                walk_enums(v, f"{path}.{k}", out)
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            walk_enums(v, f"{path}[{i}]", out)


def check_shared_enums(specs: dict[str, dict]) -> None:
    families_seen: set[str] = set()
    for fname, spec in specs.items():
        found: list[tuple[str, tuple[str, ...]]] = []
        walk_enums(spec, fname, found)
        for path, values in found:
            for fam, canon in CANONICAL_ENUMS.items():
                if values == canon:
                    families_seen.add(fam)
                    break
                # ≥1 shared value ⇒ this enum is a (drifted) copy of the family unless it exactly matches it or is
                # a declared variant. Lowered from ≥2 so a 2-value drop / a 1-value suite-version drift is caught.
                overlap = len(set(values) & set(canon))
                if overlap >= 1 and values not in INTENTIONAL_ENUM_VARIANTS:
                    errors.append(
                        f"shared-enum: {path} = {list(values)} shares values with the '{fam}' family "
                        f"{list(canon)} but is not exactly it — align it, or declare it an intentional variant")
                    break
    for fam in CANONICAL_ENUMS:
        if fam not in families_seen:
            errors.append(f"shared-enum: canonical family '{fam}' matched no enum in any spec — stale guard entry")


def main() -> int:
    specs = load_specs()
    if not specs:
        print(f"no specs found under {API.relative_to(ROOT)}")
        return 1
    check_named_component(specs, "securitySchemes.bearerAuth", {"description"}, "bearer-auth")
    check_named_component(specs, "parameters.ScopeId", {"example", "description"}, "path-param")
    check_named_component(specs, "parameters.TenantId", {"example", "description"}, "path-param")
    check_shared_enums(specs)

    if errors:
        print("CONTRACTS COMMON-COMPONENT VALIDATION FAILED:")
        for e in errors:
            print("  -", e)
        return 1
    print(
        f"contracts common components OK: {len(specs)} specs — bearerAuth/path-params consistent, "
        f"{len(CANONICAL_ENUMS)} shared enum families aligned (ErrorResponse is single-sourced in _common)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
