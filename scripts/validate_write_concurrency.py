#!/usr/bin/env python3
"""Guard that every edit operation in the contracts declares how it handles a concurrent writer (ADR 0077).

**What it asserts.** Its subject is derived from `contracts/api/*.openapi.yaml` on every run, never listed:
every operation whose method is PUT or PATCH, plus every POST whose `operationId` starts with an edit verb
(`rename|set|update|change|commit|move|put`). Each must carry an operation-level `x-veyq-concurrency` mapping
with a `mode` of one of two values:

  - `version` — the write carries the version it read and is compare-and-set. The mapping names the request
    `field` (an integer property of the request schema, resolved through `$ref` across spec files); the field is
    `required`, or the mapping's `requiredWith` names the property whose presence makes it required (the server
    enforces that half). The operation's `409` response — inline or a `$ref` into `_common` — names the
    mapping's `conflictCode` (default `stale_version`) in its description or example.
  - `last-writer-wins` — with a non-empty `reason`: why no version is needed (one writer, or the write carries
    only what its writer typed).

The reverse angle: any non-GET operation whose request schema carries a version-shaped property (`version`, or
a name ending in `Version`) must be declared `version` with that property as its `field` — subject or not, so a
version-checked write outside the verb list is still held to its wire form.

The failure names the list — spec, operation, rule — never a count.

**The defect that bought it.** No incident of the guard's own — built with
`cr-meta-writes-carry-the-version-they-read` (inbox #271), because the stale-view overwrite it exists against is
silent by construction: a member who only opened a Memory re-sealed its date, place and cover from a tree cached
at space entry, erasing a peer's newer place, and nobody saw anything. What made that possible was that "can a
second writer lose data here?" was never asked per operation. Its first run, before any annotation landed,
named the 20 subject operations and `acceptTenantTerms` (a POST outside the verb list carrying `version`).

**Its limit.** It proves a **declaration** and its **wire form**, never behaviour: that the server actually
compares the version is proven by the red-first slice tests (`NodesSliceTest`, `MediaSliceTest`,
`IdentityPinsSliceTest`), and that a `last-writer-wins` reason is *true* is a reviewer's call. A POST edit whose
`operationId` starts with a verb outside the list slips through unless it carries a version-shaped field. A
`requiredWith` is not checked against the server's enforcement. The first shape reached for was keyed on the
method alone (PUT/PATCH), which misses six POST edits (`renameGroup`, `setScopeIcon`, `moveBucketMedia`,
`commitCoverBlob`, `changeTenantDomain`, `changePassword`); the verb list is what closes that.
"""
from __future__ import annotations

import pathlib
import re
import sys

import yaml

import contract_specs

ROOT = pathlib.Path(__file__).resolve().parent.parent
API = contract_specs.API

EDIT_VERB = re.compile(r"^(rename|set|update|change|commit|move|put)[A-Z]")
VERSION_SHAPED = re.compile(r"^(version|[a-z][A-Za-z0-9]*Version)$")
MODES = ("version", "last-writer-wins")
DEFAULT_CONFLICT = "stale_version"
METHODS = ("get", "put", "post", "patch", "delete")


def load_specs() -> dict[str, dict]:
    return {contract_specs.key(p): yaml.safe_load(p.read_text(encoding="utf-8")) for p in contract_specs.specs()}


def resolve(specs: dict[str, dict], ref: str, file: str) -> tuple[dict, str]:
    target, _, fragment = ref.partition("#")
    name = contract_specs.ref_key(file, target)
    node = specs[name]
    for part in fragment.strip("/").split("/"):
        node = node[part]
    return node, name


def request_properties(specs: dict[str, dict], schema: dict | None, file: str) -> tuple[dict, set[str]]:
    """The request schema's properties and required set, through `$ref` and `allOf`/`oneOf`/`anyOf`."""
    props: dict = {}
    required: set[str] = set()
    seen: set[tuple[str, str]] = set()

    def walk(s: dict | None, f: str) -> None:
        if not isinstance(s, dict):
            return
        if "$ref" in s:
            key = (s["$ref"], f)
            if key in seen:
                return
            seen.add(key)
            walk(*resolve(specs, s["$ref"], f))
            return
        for combinator in ("allOf", "oneOf", "anyOf"):
            for sub in s.get(combinator) or []:
                walk(sub, f)
        props.update(s.get("properties") or {})
        required.update(s.get("required") or [])

    walk(schema, file)
    return props, required


def is_integer(prop: dict) -> bool:
    t = prop.get("type")
    return t == "integer" or (isinstance(t, list) and "integer" in t)


def conflict_text(specs: dict[str, dict], op: dict, file: str) -> str:
    """The `409` response's description + example code, resolved through a `$ref` into another spec."""
    resp = (op.get("responses") or {}).get("409") or (op.get("responses") or {}).get(409)
    if not isinstance(resp, dict):
        return ""
    if "$ref" in resp:
        resp, _ = resolve(specs, resp["$ref"], file)
    parts = [str(resp.get("description") or "")]
    for media in (resp.get("content") or {}).values():
        parts.append(str((media or {}).get("example") or ""))
    return " ".join(parts)


def main() -> int:
    specs = load_specs()
    errors: list[str] = []
    n_subject = n_version = n_lww = 0
    for file, spec in specs.items():
        for path, item in (spec.get("paths") or {}).items():
            for method, op in (item or {}).items():
                if method not in METHODS or not isinstance(op, dict):
                    continue
                oid = op.get("operationId") or f"{method.upper()} {path}"
                where = f"{file} {oid}"
                body = (((op.get("requestBody") or {}).get("content") or {}).get("application/json") or {}).get("schema")
                props, required = request_properties(specs, body, file) if body else ({}, set())
                versioned = sorted(n for n in props if VERSION_SHAPED.match(n))
                subject = method in ("put", "patch") or (method == "post" and EDIT_VERB.match(oid) is not None)
                decl = op.get("x-veyq-concurrency")
                if not subject and not (method != "get" and versioned):
                    if decl is not None:
                        errors.append(f"{where}: declares x-veyq-concurrency but is no edit operation")
                    continue
                n_subject += 1
                if not isinstance(decl, dict):
                    why = "an edit operation" if subject else f"a write carrying {', '.join(versioned)}"
                    errors.append(f"{where}: {why} with no x-veyq-concurrency declaration (ADR 0077)")
                    continue
                mode = decl.get("mode")
                if mode not in MODES:
                    errors.append(f"{where}: x-veyq-concurrency.mode is {mode!r}, expected one of {MODES}")
                    continue
                if mode == "last-writer-wins":
                    n_lww += 1
                    if not str(decl.get("reason") or "").strip():
                        errors.append(f"{where}: last-writer-wins without a reason")
                    if versioned:
                        errors.append(f"{where}: declared last-writer-wins but its request carries "
                                      f"{', '.join(versioned)} — a version-carrying write is declared `version`")
                    continue
                n_version += 1
                field = decl.get("field")
                if field not in props:
                    errors.append(f"{where}: version field {field!r} is not a property of the request schema")
                    continue
                if not is_integer(props[field]):
                    errors.append(f"{where}: version field {field!r} is not an integer")
                with_ = decl.get("requiredWith")
                if field not in required:
                    if not with_:
                        errors.append(f"{where}: version field {field!r} is neither required nor `requiredWith` "
                                      f"another property")
                    elif with_ not in props:
                        errors.append(f"{where}: requiredWith {with_!r} is not a property of the request schema")
                code = decl.get("conflictCode") or DEFAULT_CONFLICT
                if code not in conflict_text(specs, op, file):
                    errors.append(f"{where}: its 409 response does not name `{code}`")
                for other in versioned:
                    if other != field:
                        errors.append(f"{where}: carries version-shaped {other!r} beside the declared field {field!r}")

    if errors:
        print(f"WRITE-CONCURRENCY VALIDATION FAILED ({len(errors)}):")
        for e in errors:
            print("  -", e)
        return 1
    print(f"write concurrency OK: {n_subject} edit operation(s) declared — {n_version} version-checked, "
          f"{n_lww} last-writer-wins with a reason.")
    print("  scope: the DECLARATION and its wire form, never that the server compares — see the docstring.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
