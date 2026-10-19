#!/usr/bin/env python3
"""Guard that every id a request carries is a `format: uuid` in the contracts (inbox #387).

**What it asserts.** Every id column of the schema is MariaDB's native `UUID` type (inbox #387), which takes a
canonical UUID and nothing else: an `INSERT` of any other string fails (error 1292, a 500 if it ever got that far),
and a `SELECT` by one silently matches nothing. What keeps a malformed client id from reaching a query is the
contract: a `format: uuid` field generates as `java.util.UUID`, so Jackson (body) or JAX-RS (path/query) refuses a
non-UUID before any handler runs — `400 invalid_request` for a body (`MismatchedInputExceptionMapper`), 404 for a
path segment — and the handler's `toString()` of the parsed value is the canonical lowercase form the column
prints, which is also where an upper-case id is normalised (`IdBoundarySliceTest`).

So the subject is every **request input** of every operation in `contracts/api/**/*.openapi.yaml` — each path,
query and header parameter and each property of every schema reachable from a `requestBody` (transitively through
`$ref`, `items`, `allOf`/`oneOf`/`anyOf`, across spec files) — derived from the specs on every run, never listed.
One whose name says it is an id (`id`, `ids`, or ending in `Id`/`Ids`) and whose value is a string (or a list of
strings) must say `format: uuid`. The failure names the list — spec, location, input — never a count.

**The exceptions are named, each with its reason** (`NOT_A_ROW_ID`): an input that is called an id but is not a
UUID column. Adding one is a decision this file records; a new id-named input is a UUID until it is argued otherwise.

**Its limit.** It proves the contract says `uuid`, not that the handler reads the generated `UUID` (a handler that
takes `String` from a `uuid` field and uses it raw is still canonical, since the generated parameter already parsed
and re-printed it), nor that an id-typed column is only ever written from such an input — the audit of #387 did
that by hand, and a column written from a server-minted or derived value is outside this guard. It does not see
an input whose name does not say id (`owner`, `account`): the audit found none, and a new one is on the reviewer.
"""
from __future__ import annotations

import re
import sys

import yaml

import contract_specs

ID_NAME = re.compile(r"(^|[a-z0-9])(Id|Ids)$|^(id|ids)$")
HTTP_METHODS = {"get", "put", "post", "delete", "patch", "options", "head"}

# input name -> why it is not a UUID column. A share id is a 256-bit token; a suite id names a crypto suite; a
# capability id is a path in the catalog's tree.
NOT_A_ROW_ID = {
    "shareId": "the share id is a 256-bit URL-safe token (ADR 0029), column veyq_share.id VARCHAR(64), not a UUID",
    "suiteId": "names a crypto suite (e.g. `v1`), a label the conformance vectors pin, not a row id",
    "capabilityId": "a capability register id, the lowercase-dotted catalog path (e.g. `core.remember-device`, "
                    "contracts/platform/capabilities.json), not a row id",
    "pinId": "an identity pin's keyed id, HMAC-SHA256 under a Master-Key-derived key as 64 lowercase hex characters "
             "(ADR 0104 decision 1), column veyq_identity_pin_entry.pin_id VARCHAR(64), not a UUID",
}

errors: list[str] = []


def load_specs() -> dict[str, dict]:
    return {contract_specs.key(p): (yaml.safe_load(p.read_text()) or {}) for p in contract_specs.specs()}


def resolve(specs: dict[str, dict], ref: str, file: str) -> tuple[str, dict]:
    target, _, pointer = ref.partition("#")
    rfile = contract_specs.ref_key(file, target)
    node = specs[rfile]
    for part in pointer.strip("/").split("/"):
        node = node[part]
    return rfile, node


def deref(specs: dict[str, dict], schema: dict, file: str) -> tuple[str, dict]:
    for _ in range(16):
        if isinstance(schema, dict) and "$ref" in schema:
            file, schema = resolve(specs, schema["$ref"], file)
        else:
            break
    return file, schema if isinstance(schema, dict) else {}


def alternatives(schema: dict) -> list[dict]:
    """The nullable `oneOf`/`anyOf` spelling collapsed to its real branches (the schema itself otherwise)."""
    for key in ("oneOf", "anyOf"):
        alts = schema.get(key)
        if isinstance(alts, list):
            return [a for a in alts if isinstance(a, dict) and a.get("type") != "null"] or [schema]
    return [schema]


def string_without_uuid(specs: dict[str, dict], schema: dict, file: str) -> bool:
    """Whether a value schema is a string (or a list of strings) that does not say `format: uuid`."""
    file, schema = deref(specs, schema, file)
    for alt in alternatives(schema):
        file2, alt = deref(specs, alt, file)
        if alt.get("type") == "array" or "items" in alt:
            if string_without_uuid(specs, alt.get("items") or {}, file2):
                return True
            continue
        t = alt.get("type")
        types = set(t) if isinstance(t, list) else {t}
        if "string" in types and alt.get("format") != "uuid":
            return True
    return False


class Checker:
    def __init__(self, specs: dict[str, dict]):
        self.specs = specs
        self.seen: set[tuple[str, int]] = set()
        self.n_inputs = 0

    def check(self, where: str, name: str, schema: dict, file: str) -> None:
        self.n_inputs += 1
        if not ID_NAME.search(name) or name in NOT_A_ROW_ID:
            return
        if string_without_uuid(self.specs, schema, file):
            errors.append(f"{where} — `{name}` is an id but not `format: uuid`: a non-UUID string would reach a UUID "
                          f"column (error 1292 on write, a silent miss on read).")

    def schema(self, where: str, schema: dict, file: str) -> None:
        file, schema = deref(self.specs, schema, file)
        if (file, id(schema)) in self.seen:
            return
        self.seen.add((file, id(schema)))
        for part in schema.get("allOf") or []:
            self.schema(where, part, file)
        for key in ("oneOf", "anyOf"):
            for part in schema.get(key) or []:
                self.schema(where, part, file)
        if isinstance(schema.get("items"), dict):
            self.schema(where, schema["items"], file)
        for pname, prop in (schema.get("properties") or {}).items():
            prop = prop or {}
            self.check(f"{where}.{pname}", pname, prop, file)
            self.schema(f"{where}.{pname}", prop, file)

    def parameter(self, where: str, param: dict, file: str) -> None:
        file, param = deref(self.specs, param, file)
        name = param.get("name", "")
        self.check(f"{where} parameter", name, param.get("schema") or {}, file)

    def run(self) -> None:
        for file, spec in self.specs.items():
            for path, item in (spec.get("paths") or {}).items():
                for param in (item or {}).get("parameters") or []:
                    self.parameter(f"{file}:{path}", param, file)
                for method, op in (item or {}).items():
                    if method not in HTTP_METHODS or not isinstance(op, dict):
                        continue
                    where = f"{file}:{op.get('operationId', method + ' ' + path)}"
                    for param in op.get("parameters") or []:
                        self.parameter(where, param, file)
                    body = deref(self.specs, op.get("requestBody") or {}, file)
                    for media in (body[1].get("content") or {}).values():
                        self.schema(f"{where} body", (media or {}).get("schema") or {}, body[0])


def main() -> int:
    specs = load_specs()
    checker = Checker(specs)
    checker.run()
    if checker.n_inputs < 100:  # a walk that read almost nothing would be green for the wrong reason
        errors.append(f"only {checker.n_inputs} request inputs were read — the walk lost the specs")
    if errors:
        print("id contract format: FAIL", file=sys.stderr)
        for e in sorted(set(errors)):
            print(f"  - {e}", file=sys.stderr)
        return 1
    print(f"id contract format: ok ({checker.n_inputs} request inputs read)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
