#!/usr/bin/env python3
"""Guard that every operation taking a request date-time declares the `422` its out-of-range instant answers.

**What it asserts.** The server refuses a well-formed date-time no time column holds — before 1582-10-15T00:00:00Z,
past 9999-12-31T23:59:59.999Z, or with an offset past ±18:00 — with `422 ErrorResponse{code: date_time_out_of_range}`
(`StrictJsonTypes.StringOnly`, `DateTimeOutOfRangeMapper`), a bound no date-time schema states, so the contract owes
it on each operation that can meet it (inbox #509). For every operation of every spec under `contracts/api/` (found
by `contract_specs.specs()`, recursively) whose **request** carries a `format: date-time` anywhere:

  1. **it declares a `422`** — under the key `422` exactly, inline or by `$ref` to `components/responses`;
  2. **that `422` names `date_time_out_of_range`** in its description (read through its `$ref`), so a client meeting
     the code finds it documented on the operation, not only a sibling refusal (an `ApplicationSwitchedOff` alone).

Its request is the request body (any media type, through a `$ref` to `components/requestBodies`) and every
parameter — path, query, header, cookie; the operation's own and its path item's, through a `$ref` to
`components/parameters`, by `schema` or by `content`. A schema is followed through `$ref` (across spec files, resolved
by `contract_specs.ref_key`), `allOf`/`oneOf`/`anyOf`, `properties`, `patternProperties`, an `additionalProperties`
schema, `items`, `prefixItems`, `then`/`else` and `dependentSchemas`, each `$ref` target read once per operation (a
recursive schema ends); a `$ref` that resolves to nothing is red, not skipped. A date-time only in a **response** is no
subject: the server binds no response. The failure names each operation — spec, method, path, `operationId` — and the
request location of its first date-time, never a count; a run that finds no subject at all is red (the walk broke).

**The defect that bought it.** The V1 heavy stage's API fuzzing (2026-11-02) found `inviteTenantMember` and
`reissueMemberInvite` taking a request `expiresAt` (since 9f9ce8e0a) with no `422` declared: the server's
`date_time_out_of_range` was undocumented (Schemathesis' `status_code_conformance`, ST103), and a malformed `tenantId`
beside an out-of-range expiry read as accepted (ST201) — two of the ten operations taking a request date-time, fixed
in 4d3ad8a02. Only a fuzz run, which samples, noticed; this makes it a rule over the whole tree.

**Its limit.** It proves the `422` is declared and names the code, never that the server **answers** it there: the
range is checked while Jackson binds a JSON body (`StrictJsonTypes`, for `OffsetDateTime` and `Instant`), so a
date-time in a path, query or header parameter — none exists today — is held to a `422` the parameter converters
(`StrictParamConverters`) do not raise; the first such parameter is the moment to give them the range or to narrow
this rule (`validate_guard_gaps.py` pins it as an open gap). Rule 2 reads the description as text: a description
that mentions the code to say it does NOT apply is green. And it reads only the JSON Schema keywords listed above: a
date-time reached only through `not` or `if` (which constrain the instance, never carry a value), a `format: date`,
or a date-time the server parses out of a string of another format (a sealed body, a token) is invisible to it.
"""
from __future__ import annotations

import functools
import sys

import contract_specs

# Each spec parsed once per run: an operation's walk reads `_common` and its own spec many times over.
load = functools.lru_cache(maxsize=None)(contract_specs.load)

CODE = "date_time_out_of_range"
METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")
# The keywords whose subschemas describe a value the request carries (module docstring § Its limit names the rest).
SUBSCHEMA_LISTS = ("allOf", "oneOf", "anyOf", "prefixItems")
SUBSCHEMA_ONE = ("items", "additionalProperties", "then", "else")
SUBSCHEMA_MAPS = ("properties", "patternProperties", "dependentSchemas")


class Walk:
    """One operation's request, read once per `$ref` target — `seen` ends a recursive schema."""

    def __init__(self) -> None:
        self.seen: set[tuple[str, str]] = set()

    def deref(self, spec: str, node: object) -> tuple[str, object]:
        """Follow `node`'s `$ref` chain to what it names, and the spec that holds it."""
        for _ in range(16):
            if not (isinstance(node, dict) and isinstance(node.get("$ref"), str)):
                return spec, node
            target, _, pointer = node["$ref"].partition("#")
            spec = contract_specs.ref_key(spec, target)
            if (spec, pointer) in self.seen:
                return spec, None
            self.seen.add((spec, pointer))
            node = load(spec)
            for part in pointer.strip("/").split("/") if pointer.strip("/") else ():
                node = node.get(part.replace("~1", "/").replace("~0", "~")) if isinstance(node, dict) else None
            if node is None:
                raise LookupError(f"{spec}#{pointer}: a $ref that resolves to nothing")
        raise LookupError(f"{spec}: a $ref chain that does not end")

    def date_time(self, spec: str, schema: object, where: str) -> str | None:
        """The location of the first `format: date-time` in `schema`, or None."""
        spec, schema = self.deref(spec, schema)
        if not isinstance(schema, dict):
            return None
        if schema.get("format") == "date-time":
            return where
        for key in SUBSCHEMA_LISTS:
            for i, sub in enumerate(schema.get(key) or ()):
                found = self.date_time(spec, sub, f"{where}.{key}[{i}]")
                if found:
                    return found
        for key in SUBSCHEMA_ONE:
            sub = schema.get(key)
            if isinstance(sub, dict):
                found = self.date_time(spec, sub, f"{where}[]" if key == "items" else f"{where}.{key}")
                if found:
                    return found
        for key in SUBSCHEMA_MAPS:
            for name, sub in (schema.get(key) or {}).items():
                found = self.date_time(spec, sub, f"{where}.{name}")
                if found:
                    return found
        return None

    def in_content(self, spec: str, content: object, where: str) -> str | None:
        for media, entry in (content or {}).items() if isinstance(content, dict) else ():
            if isinstance(entry, dict) and "schema" in entry:
                found = self.date_time(spec, entry["schema"], f"{where} ({media})")
                if found:
                    return found
        return None

    def request(self, spec: str, path_item: dict, op: dict) -> str | None:
        """Where `op`'s request first carries a date-time, or None."""
        body_spec, body = self.deref(spec, op.get("requestBody"))
        if isinstance(body, dict):
            found = self.in_content(body_spec, body.get("content"), "requestBody")
            if found:
                return found
        for param in list(path_item.get("parameters") or ()) + list(op.get("parameters") or ()):
            p_spec, p = self.deref(spec, param)
            if not isinstance(p, dict):
                continue
            where = f"{p.get('in', '?')} parameter {p.get('name', '?')}"
            found = self.date_time(p_spec, p["schema"], where) if "schema" in p else None
            found = found or self.in_content(p_spec, p.get("content"), where)
            if found:
                return found
        return None


def check() -> tuple[list[str], int]:
    """Every operation's refusal, and how many operations take a request date-time."""
    errors: list[str] = []
    subjects = 0
    for path in contract_specs.specs():
        spec = contract_specs.key(path)
        for route, item in (load(spec).get("paths") or {}).items():
            if not isinstance(item, dict):
                continue
            for method in METHODS:
                op = item.get(method)
                if not isinstance(op, dict):
                    continue
                try:
                    where = Walk().request(spec, item, op)
                except LookupError as e:
                    errors.append(f"{spec} {method.upper()} {route}: {e}")
                    continue
                if not where:
                    continue
                subjects += 1
                name = f"{spec} {method.upper()} {route} ({op.get('operationId', 'no operationId')})"
                declared = {str(k): v for k, v in (op.get("responses") or {}).items()}
                if "422" not in declared:
                    errors.append(
                        f"{name}: its request carries a date-time at {where} and it declares no 422 — the server "
                        f"answers an instant no time column holds `422 date_time_out_of_range` (inbox #509)")
                    continue
                try:
                    _, response = Walk().deref(spec, declared["422"])
                except LookupError as e:
                    errors.append(f"{name}: its 422 — {e}")
                    continue
                text = response.get("description", "") if isinstance(response, dict) else ""
                if CODE not in str(text):
                    errors.append(
                        f"{name}: its request carries a date-time at {where} and its 422 does not name `{CODE}` — "
                        f"a client meeting it would find only the operation's other 422s documented")
    return errors, subjects


def main() -> int:
    errors, subjects = check()
    if errors:
        print("validate_date_time_422: an operation taking a request date-time does not declare its 422:")
        for e in errors:
            print(f"  - {e}")
        return 1
    if not subjects:  # the walk lost the request date-times: a green over nothing would prove nothing
        print("validate_date_time_422: no operation takes a request date-time — the walk is broken, not the contracts")
        return 1
    print(f"validate_date_time_422: all {subjects} operations taking a request date-time declare their 422 naming {CODE}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
