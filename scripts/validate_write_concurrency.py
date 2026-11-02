#!/usr/bin/env python3
"""Guard that every edit operation in the contracts declares how it handles a concurrent writer (ADR 0077).

**What it asserts.** Its subject is derived from `contracts/api/*.openapi.yaml` on every run, never listed:
every operation whose method is PUT or PATCH, plus every POST whose `operationId` starts with an edit verb
(`rename|set|update|change|commit|move|put`). Each must carry an operation-level `x-veyq-concurrency` mapping
with a `mode` of one of two values:

  - `revision` — the write carries the revision it read and is compare-and-set. The mapping's `fields` is a
    non-empty LIST of declarations, one per revision the operation checks — an operation may guard two independent
    things on one row (`updateNode`: the sealed payload's `metaRevision`, the placement's `placementRevision`, ADR
    0080), and each conflicts on its own. A declaration names the request `field` (an integer property of the
    request schema, resolved through `$ref` across spec files, declared once); the field is `required`, or the
    declaration's `requiredWith` names the property — or the list of properties — whose presence makes it required
    (the server enforces that half). A batch whose every item is compare-and-set on its own declares the per-item
    field as `<array>.<name>` (`appendPotRevisions`' `revisions.revision`): read in the array's item schema, one level
    deep as for epochs below, and held there to the same rules. The operation's `409` response — inline or a `$ref` into `_common` — names each
    declaration's `conflictCode` (default `stale_revision`) in its description or example. A declared field is
    named as a revision (`revision` or `…Revision`), never as a version: ADR 0086 keeps "version" for
    compatibility, so a counter that says version is a word error the contract would carry onto every client.
  - `last-writer-wins` — with a non-empty `reason`: why no revision is needed (one writer, or the write carries
    only what its writer typed).
  - `node-epoch` — a write whose only guard is the node epoch it states (below), with no revision and no reason to
    give: a create, a restore, a guest's write, a batch completion.

**Node epochs (ADR 0109 point 6, ADR 0114 decision 3).** A write that seals anything under a node's key states the
epoch of that node key itself as it read it — `nodeEpoch` (the node's own key) or `parentEpoch` (an entry under its
parent's key); a node key's epoch is 0 at its mint and one more with every replacement, a rotation's re-key or a link
close's — so the server can refuse a key replaced since (`409 stale_epoch`) and a node marked due whose re-key has not
reached it (`409 node_rekey_pending`). The declaration lists them under `epochs` beside any mode: each names an epoch-shaped request
property — top-level, or one level into a batch's items as `<array>.<name>` (`items.nodeEpoch`) — an integer, required
or `requiredWith` another property, and the operation's `409` names both refusals. An epoch-shaped property makes its
write a subject whatever its verb, and every one must be declared (the reverse angle). The bite cases are
`validate_guard_gaps.py` 91 (a batch's per-item declaration dropped) and 92 (a refusal the `409` does not name).

The reverse angle: any non-GET operation whose request schema carries a counter-shaped property (`revision` or
`version`, or a name ending in `Revision` or `Version`) must be declared `revision` with that property as the `field`
of one of its declarations — subject or not, so a revision-checked write outside the verb list is still held to its
wire form, and a second counter added beside a declared one is not carried silently. The shape keeps reading both
words although every counter now says revision (inbox #452): a new counter named version must still be found, so the
naming rule above can refuse it rather than the reverse angle never seeing it.

The failure names the list — spec, operation, rule — never a count.

**The defect that bought it.** No incident of the guard's own — built with
`cr-meta-writes-carry-the-version-they-read` (inbox #271), because the stale-view overwrite it exists against is
silent by construction: a member who only opened a Memory re-sealed its date, place and cover from a tree cached
at space entry, erasing a peer's newer place, and nobody saw anything. What made that possible was that "can a
second writer lose data here?" was never asked per operation. Its first run, before any annotation landed,
named the 20 subject operations and `acceptTenantTerms` (a POST outside the verb list carrying `version`).
When inbox #437 renamed the node counters to revisions, the shape still read `Version` only: run on the renamed
contract it was red on `removeNodeCover` (a write the counter alone makes a subject), and an undeclared
`placementRevision` would have passed unseen. The shape learned `Revision` in the same change. When inbox #452
renamed the rest (the pins, the register, the terms), nothing held the word itself: a counter could be re-added
as `version`, consistently declared, and pass — so the naming rule came with it.

**Its limit.** It proves a **declaration** and its **wire form**, never behaviour: that the server actually
compares the revision is proven by the red-first slice tests (`NodesSliceTest`, `MediaSliceTest`,
`IdentityPinsSliceTest`, the placement's `NodePlacementCoreSliceTest`), and that a `last-writer-wins` reason is *true* is a reviewer's call. A POST edit whose
`operationId` starts with a verb outside the list slips through unless it carries a counter-shaped field. A
`requiredWith` is not checked against the server's enforcement. The first shape reached for was keyed on the
method alone (PUT/PATCH), which misses six POST edits (`renameGroup`, `setScopeIcon`, `moveBucketMedia`,
`commitCoverBlob`, `changeTenantDomain`, `changePassword`); the verb list is what closes that. The declaration
was one `field` per operation until ADR 0080 gave `updateNode` a second, independent counter (`placementRevision`
beside `metaRevision`); a single slot would have forced one of them undeclared, so `fields` became a list with a
conflict code each. Its bite cases are `validate_guard_gaps.py` 59–61 (a declaration dropped, a second
counter-shaped field undeclared, a conflict code the `409` does not name), 82 (a declared field named version) and 94 (a
batch's declared per-item revision its item schema no longer requires). The reverse angle reads top-level counters
only: a counter one level into a batch's items is held only once declared (`stageMediaRekey`'s `media.metaRevision`
is not — a re-key's stage is checked by the commit, not compare-and-set on its own).
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
COUNTER_SHAPED = re.compile(r"^(revision|version|[a-z][A-Za-z0-9]*(Revision|Version))$")
MODES = ("revision", "last-writer-wins", "node-epoch")
# A write that seals under a node's key states that node key's own epoch as it read it (ADR 0114 decision 3): the
# node's (`nodeEpoch`) or, for an entry sealed under a parent's key, the parent's (`parentEpoch`). Not a counter: it is
# compared, never compare-and-set, and it has two refusals of its own.
EPOCH_SHAPED = re.compile(r"^(node|parent)Epoch$")
EPOCH_CONFLICTS = ("stale_epoch", "node_rekey_pending")
VERSION_SHAPED = re.compile(r"^(version|[a-z][A-Za-z0-9]*Version)$")
DEFAULT_CONFLICT = "stale_revision"
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


def epoch_properties(specs: dict[str, dict], props: dict, file: str) -> dict[str, dict]:
    """Every epoch-shaped property of the request: a top-level one by its name, one inside an array's item schema
    (a batch, `completeMedia`'s `items`) as `<array>.<name>`, one level deep — the shape every batch has."""
    found: dict[str, dict] = {n: p for n, p in props.items() if EPOCH_SHAPED.match(n)}
    for name, prop in props.items():
        if not isinstance(prop, dict):
            continue
        if "$ref" in prop:
            prop, _ = resolve(specs, prop["$ref"], file)
        t = prop.get("type")
        if t == "array" or (isinstance(t, list) and "array" in t):
            inner, _ = request_properties(specs, prop.get("items"), file)
            found.update({f"{name}.{n}": p for n, p in inner.items() if EPOCH_SHAPED.match(n)})
    return found


def check_epochs(where: str, entries: object, epochs: dict[str, dict], props: dict, required: set[str],
                 conflict: str) -> list[str]:
    """The `epochs` list: each names an epoch-shaped field the request carries, an integer, required or required with
    another property, and the `409` names both of its refusals; every epoch-shaped field is declared."""
    errors: list[str] = []
    declared: list[str] = []
    if entries is not None and (not isinstance(entries, list) or not entries):
        return [f"{where}: x-veyq-concurrency.epochs must be a non-empty list of declarations"]
    for entry in entries or []:
        if not isinstance(entry, dict):
            errors.append(f"{where}: an epochs entry is {entry!r}, expected a mapping")
            continue
        field = entry.get("field")
        if field in declared:
            errors.append(f"{where}: epoch field {field!r} is declared twice")
            continue
        if field not in epochs:
            errors.append(f"{where}: epoch field {field!r} is not an epoch-shaped property of the request "
                          f"(`nodeEpoch` / `parentEpoch`, top-level or in a batch's items)")
            continue
        declared.append(field)
        if not is_integer(epochs[field]):
            errors.append(f"{where}: epoch field {field!r} is not an integer")
        with_ = entry.get("requiredWith")
        with_list = [with_] if isinstance(with_, str) else list(with_ or [])
        top = field.split(".")[0]
        if "." not in field and field not in required:
            if not with_list:
                errors.append(f"{where}: epoch field {field!r} is neither required nor `requiredWith` another property")
            for w in with_list:
                if w not in props:
                    errors.append(f"{where}: {field!r} requiredWith {w!r} is not a property of the request schema")
        if "." in field and top not in props:
            errors.append(f"{where}: epoch field {field!r} names no array property {top!r}")
        for code in EPOCH_CONFLICTS:
            if code not in conflict:
                errors.append(f"{where}: its 409 response does not name `{code}` (a refusal of {field!r})")
    for other in sorted(epochs):
        if other not in declared:
            errors.append(f"{where}: carries epoch-shaped {other!r} that no epochs declaration names "
                          f"(declared: {', '.join(declared) or 'none'})")
    return errors


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


def batch_item_properties(specs: dict[str, dict], props: dict, top: str, file: str) -> tuple[dict, set[str]] | None:
    """The item schema's properties and required set of the request's array property `top`, or None when `top` is no
    array property — the one level a batch has, as `epoch_properties` reads it."""
    prop = props.get(top)
    if not isinstance(prop, dict):
        return None
    if "$ref" in prop:
        prop, _ = resolve(specs, prop["$ref"], file)
    t = prop.get("type")
    if not (t == "array" or (isinstance(t, list) and "array" in t)):
        return None
    return request_properties(specs, prop.get("items"), file)


def check_declaration(
    where: str, entry: dict, props: dict, required: set[str], conflict: str, declared: list[str],
    specs: dict[str, dict] | None = None, file: str = "",
) -> list[str]:
    """One `fields` entry: its revision field, what makes it required, and its conflict code in the `409`. A field
    `<array>.<name>` is a batch's per-item revision (`appendPotRevisions`' `revisions.revision`): read in the array's
    item schema, required there, one level deep."""
    errors: list[str] = []
    field = entry.get("field")
    if field in declared:
        return [f"{where}: revision field {field!r} is declared twice"]
    if isinstance(field, str) and "." in field:
        top, _, name = field.partition(".")
        inner = batch_item_properties(specs or {}, props, top, file)
        if inner is None:
            return [f"{where}: revision field {field!r} names no array property {top!r} of the request schema"]
        props, required = inner
        field_label, field = field, name
    else:
        field_label = field
    if field not in props:
        return [f"{where}: revision field {field_label!r} is not a property of the request schema"]
    declared.append(field_label)
    if VERSION_SHAPED.match(field):
        errors.append(f"{where}: revision field {field_label!r} is named as a version — a counter is a revision "
                      f"(ADR 0086)")
    if not is_integer(props[field]):
        errors.append(f"{where}: revision field {field_label!r} is not an integer")
    with_ = entry.get("requiredWith")
    with_list = [with_] if isinstance(with_, str) else list(with_ or [])
    if field not in required:
        if not with_list:
            errors.append(f"{where}: revision field {field_label!r} is neither required nor `requiredWith` "
                          f"another property")
        for w in with_list:
            if w not in props:
                errors.append(f"{where}: {field_label!r} requiredWith {w!r} is not a property of the request schema")
    code = entry.get("conflictCode") or DEFAULT_CONFLICT
    if code not in conflict:
        errors.append(f"{where}: its 409 response does not name `{code}` (the conflict of {field_label!r})")
    return errors


def main() -> int:
    specs = load_specs()
    errors: list[str] = []
    n_subject = n_revision = n_lww = n_epoch = 0
    for file, spec in specs.items():
        for path, item in (spec.get("paths") or {}).items():
            for method, op in (item or {}).items():
                if method not in METHODS or not isinstance(op, dict):
                    continue
                oid = op.get("operationId") or f"{method.upper()} {path}"
                where = f"{file} {oid}"
                body = (((op.get("requestBody") or {}).get("content") or {}).get("application/json") or {}).get("schema")
                props, required = request_properties(specs, body, file) if body else ({}, set())
                counters = sorted(n for n in props if COUNTER_SHAPED.match(n))
                epochs = epoch_properties(specs, props, file) if body else {}
                subject = method in ("put", "patch") or (method == "post" and EDIT_VERB.match(oid) is not None)
                decl = op.get("x-veyq-concurrency")
                if not subject and not (method != "get" and (counters or epochs)):
                    if decl is not None:
                        errors.append(f"{where}: declares x-veyq-concurrency but is no edit operation")
                    continue
                n_subject += 1
                if not isinstance(decl, dict):
                    why = "an edit operation" if subject else f"a write carrying {', '.join(counters or sorted(epochs))}"
                    errors.append(f"{where}: {why} with no x-veyq-concurrency declaration (ADR 0077)")
                    continue
                mode = decl.get("mode")
                if mode not in MODES:
                    errors.append(f"{where}: x-veyq-concurrency.mode is {mode!r}, expected one of {MODES}")
                    continue
                if "epochs" in decl or epochs:
                    n_epoch += 1
                    errors.extend(check_epochs(where, decl.get("epochs"), epochs, props, required,
                                               conflict_text(specs, op, file)))
                if mode == "node-epoch":
                    if not epochs:
                        errors.append(f"{where}: declared node-epoch but its request carries no epoch-shaped field")
                    if counters:
                        errors.append(f"{where}: declared node-epoch but its request carries {', '.join(counters)} — "
                                      f"a counter-carrying write is declared `revision`")
                    if "fields" in decl:
                        errors.append(f"{where}: a node-epoch declaration has no `fields` (revision) list")
                    continue
                if mode == "last-writer-wins":
                    n_lww += 1
                    if not str(decl.get("reason") or "").strip():
                        errors.append(f"{where}: last-writer-wins without a reason")
                    if counters:
                        errors.append(f"{where}: declared last-writer-wins but its request carries "
                                      f"{', '.join(counters)} — a counter-carrying write is declared `revision`")
                    continue
                n_revision += 1
                if "field" in decl or "requiredWith" in decl or "conflictCode" in decl:
                    errors.append(f"{where}: a revision declaration lists its fields under `fields` "
                                  f"(a list of {{field, requiredWith, conflictCode}}), not at the top level")
                    continue
                fields = decl.get("fields")
                if not isinstance(fields, list) or not fields:
                    errors.append(f"{where}: x-veyq-concurrency.fields must be a non-empty list of declarations")
                    continue
                text = conflict_text(specs, op, file)
                declared: list[str] = []
                for entry in fields:
                    if not isinstance(entry, dict):
                        errors.append(f"{where}: a fields entry is {entry!r}, expected a mapping")
                        continue
                    errors.extend(check_declaration(where, entry, props, required, text, declared, specs, file))
                for other in counters:
                    if other not in declared:
                        errors.append(f"{where}: carries counter-shaped {other!r} that no declaration names "
                                      f"(declared: {', '.join(declared) or 'none'})")

    if errors:
        print(f"WRITE-CONCURRENCY VALIDATION FAILED ({len(errors)}):")
        for e in errors:
            print("  -", e)
        return 1
    print(f"write concurrency OK: {n_subject} edit operation(s) declared — {n_revision} revision-checked, "
          f"{n_lww} last-writer-wins with a reason, {n_epoch} stating a node epoch.")
    print("  scope: the DECLARATION and its wire form, never that the server compares — see the docstring.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
