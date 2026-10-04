#!/usr/bin/env python3
"""Guard the still-duplicated shared OpenAPI components against cross-spec drift (audit #76 / P3f, ADR 0038) —
and every example against its own schema's required set (assertion 4, inbox #307).

`ErrorResponse` was extracted to `contracts/api/_common.openapi.yaml` and is now `$ref`'d by all 9 specs — a
single source that cannot drift, so it is no longer guarded here. What stays copied across the per-domain specs
(each still a self-contained document a generator consumes on its own): the `bearerAuth` scheme, every path
parameter more than one spec defines, and several shared enums. This guard keeps *those* copies byte-consistent —
the single-source guarantee for the components not worth an external `$ref` (bearerAuth's `description` carries
real per-domain nuance; the enums are inline, so `$ref`-ing them would change the generated type). `_common`
itself is excluded from the copy scans — it is the source, not a copy — and is read by the enum walk (rule 3),
which checks a family's values wherever they are spelled. The specs are found by `contract_specs` (recursively:
core's at the top of `contracts/api`, an application's in its own directory).

What it asserts:
  1. Every spec's `components.securitySchemes.bearerAuth` has the same `type`+`scheme` (ignoring the per-domain
     `description`).
  2. Every path parameter (`components.parameters`) that **more than one spec** defines is structurally identical
     across them (ignoring example/description). The set is **derived** on every run, never listed: it was
     `ScopeId`/`TenantId` once, which left the `NodeId` and `MediaId` copies free to drift unseen — and the
     contracts split by owner puts each such pair into two owners' specs, where a drift is a wire change between a
     core and an application route. A parameter defined once is no copy and is not checked.
  3. Every copy of a shared enum (node-kind, derivative-kind, membership-role, credential-method, suite-version)
     carries the SAME values. An enum that shares **≥1** value with a canonical family but is not exactly it —
     nor a declared intentional variant — is drift. **Principled limit:** a *full rename* (0 shared values) is
     undetectable by value — it is indistinguishable from an unrelated domain enum, so no value-based check can
     see it. This ≥1-overlap rule is the hardening over the original ≥2 heuristic, which silently missed a drop
     on a 2-value family (e.g. `[OWNER]` for membership-role, overlap 1) and every drift of 1-value
     `suite-version` (F6, review-2026-07-14).
  4. **Every request/response `example` carries its schema's required properties** — the other drift a spec holds
     against itself: a schema grows a required field and the example beside it never learns. Walked through `$ref`
     (across files), `properties` and array `items`, so a nested item's required set counts too. Bought by inbox
     #307: the `resendTenantInvite`/`reassignTenantInvite` 200 examples lacked the `bootstrapSecret` and
     `delivery` their schema requires, and the first run named 16 such examples across three specs. **Its limit:**
     presence only — never a value's type, enum, pattern or bound; never the `examples:` map, a parameter's or a
     property's own `example`; and not through `allOf`/`oneOf`/`anyOf` (no example in the specs needs it today).
     A required field whose example is `null` passes, which is right for a nullable one and wrong for no other.

Deliberately NOT asserted: collection-wrapping style (bare array vs `{results}`/`{items}` vs a domain key) — that
mixes intentional batch-vs-list conventions, so it is a design call, not a drift bug (see the inbox #76 note).
"""
from __future__ import annotations

import pathlib
import sys

import yaml

import contract_specs

ROOT = pathlib.Path(__file__).resolve().parent.parent
API = contract_specs.API

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
    # blob.openapi.yaml BlobRole — the role a blob's K_blob wrap is sealed bound to (ADR 0072): the derivative
    # kinds plus the ORIGINAL and a node's COVER. A superset of derivative-kind, not a copy of it.
    ("COVER", "ORIGINAL", "PREVIEW", "THUMBNAIL"),
    # sharing.openapi.yaml ShareBlobUploadRequestItem.role — the same, minus COVER: a contributor uploads media only.
    ("ORIGINAL", "PREVIEW", "THUMBNAIL"),
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
    # `_common` is the shared-component source, not a per-domain copy — never a drift target.
    return {contract_specs.key(p): yaml.safe_load(p.read_text()) or {} for p in contract_specs.specs(common=False)}


def shared_parameters(specs: dict[str, dict]) -> list[str]:
    """Every `components.parameters` name more than one spec defines — the copies rule 2 holds identical."""
    owners: dict[str, int] = {}
    for spec in specs.values():
        for name in ((spec.get("components") or {}).get("parameters") or {}):
            owners[name] = owners.get(name, 0) + 1
    return sorted(name for name, count in owners.items() if count > 1)


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
    common = yaml.safe_load(contract_specs.COMMON.read_text()) or {}
    for fname, spec in [*specs.items(), (contract_specs.key(contract_specs.COMMON), common)]:
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


def check_examples_carry_required(specs: dict[str, dict]) -> int:
    """Assertion 4: every request/response media-type `example` carries its schema's required properties — at every
    depth the example reaches through `$ref` (across spec files, `_common` included), `properties` and array `items`.
    Returns the number of object examples checked, so the summary can say the walk reached something."""
    every = dict(specs)
    every[contract_specs.key(contract_specs.COMMON)] = yaml.safe_load(contract_specs.COMMON.read_text()) or {}

    def deref(fname: str, schema: object) -> tuple[str, object]:
        for _ in range(16):  # a $ref chain this deep is a cycle, not a schema
            if not (isinstance(schema, dict) and "$ref" in schema):
                break
            target, _, pointer = schema["$ref"].partition("#")
            fname = contract_specs.ref_key(fname, target)
            schema = every[fname]
            for part in pointer.strip("/").split("/"):
                schema = schema[part]
        return fname, schema

    checked = 0

    def walk(fname: str, schema: object, example: object, where: str) -> None:
        nonlocal checked
        fname, schema = deref(fname, schema)
        if not isinstance(schema, dict):
            return
        if isinstance(example, list) and isinstance(schema.get("items"), dict):
            for i, item in enumerate(example):
                walk(fname, schema["items"], item, f"{where}[{i}]")
        elif isinstance(example, dict):
            props = schema.get("properties") or {}
            if "required" in schema:
                checked += 1
                missing = [k for k in schema["required"] if k not in example]
                if missing:
                    errors.append(f"example: {where} omits required {missing}")
            for key, value in example.items():
                if key in props:
                    walk(fname, props[key], value, f"{where}.{key}")

    for fname, spec in specs.items():
        for path, item in (spec.get("paths") or {}).items():
            for method, op in (item or {}).items():
                if not isinstance(op, dict):
                    continue
                name = f"{fname} {op.get('operationId', method + ' ' + path)}"
                bodies = [("request", (op.get("requestBody") or {}).get("content"))]
                bodies += [(code, (resp or {}).get("content")) for code, resp in (op.get("responses") or {}).items()]
                for label, content in bodies:
                    for media in (content or {}).values():
                        if isinstance(media, dict) and "example" in media:
                            walk(fname, media.get("schema"), media["example"], f"{name} {label}")
    return checked


def main() -> int:
    specs = load_specs()
    if not specs:
        print(f"no specs found under {API.relative_to(ROOT)}")
        return 1
    check_named_component(specs, "securitySchemes.bearerAuth", {"description"}, "bearer-auth")
    params = shared_parameters(specs)
    for name in params:
        check_named_component(specs, f"parameters.{name}", {"example", "description"}, "path-param")
    check_shared_enums(specs)
    examples = check_examples_carry_required(specs)
    if not examples:
        errors.append("example: no object example with a required set was reached — the walk is broken")

    if errors:
        print("CONTRACTS COMMON-COMPONENT VALIDATION FAILED:")
        for e in errors:
            print("  -", e)
        return 1
    print(
        f"contracts common components OK: {len(specs)} specs — bearerAuth and the {len(params)} path-params "
        f"several specs define ({', '.join(params)}) consistent, "
        f"{len(CANONICAL_ENUMS)} shared enum families aligned (ErrorResponse is single-sourced in _common), "
        f"{examples} object examples carry their required properties")
    return 0


if __name__ == "__main__":
    sys.exit(main())
