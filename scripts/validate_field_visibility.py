#!/usr/bin/env python3
"""Guard that every contract field declares what the server may read of it (inbox #97, ADR 0044).

Operator-blindness is veyq's first hard invariant — *"no path may break this"* (`CLAUDE.md`) — and it was the
only major claim in the repo that nothing checked. Nine validators guarded doc links, feature format, coverage
refs, shared components, ADR quoting and scope vocabulary; none guarded the invariant the product exists for.
`security-model.md` § *What is encrypted vs. server-visible* drew the line in **kinds** ("sizes",
"timestamps"); the contracts are written in **field names**. Nothing connected the two, so the enumeration was
advice to whoever remembered to read it — and a plaintext `title: {type: string}` added to a spec would land
silently, with the client filling it a phase later.

This inverts the default. Per ADR 0044, for every `components.schemas.*.properties.*` in
`contracts/api/*.openapi.yaml`:

  1. `contentEncoding: base64` ⇒ **opaque** — the server derives no meaning from the bytes; needs no marker.
  2. A **container** (a `$ref`, an array of `$ref`, or either made nullable) needs none — its target's own
     properties carry theirs.
  3. **Every other property carries `x-veyq-visibility: <class>`** from ADR 0044's class table (the classes are
     `security-model.md`'s own kinds, not a new taxonomy). An unmarked scalar fails. An unknown class fails.
  4. **`x-veyq-visibility: pii` carries a non-empty `x-veyq-rationale`** naming the mechanism that forces the
     plaintext — ADR 0023's *"only the plaintext the mechanism requires stays plaintext"*, made testable at the
     field, in the diff that adds it. The OK line prints that set: it is a standing, greppable budget.
  5. Opaque fields and containers must **not** carry a marker — base64 already says "opaque", and a second way
     to say it is a way to say the opposite (`{contentEncoding: base64, x-veyq-visibility: public}` is a lie no
     one would catch).

And beyond the body properties, by the same rules 3 and 4:

  6. **A scalar component schema** (a `components.schemas.*` that is neither an object, a container nor opaque —
     an enum, a bare `type: string`) carries a marker. Rule 2 exempts a `$ref` property because "its target
     carries its own" — and a scalar target has no properties to carry one, so without this rule the
     `bootstrapSecret` that `$ref`s `InviteBootstrapSecret` was classified by nobody.
  7. **Every `in: header` parameter** carries a marker — operation-level, path-level or in
     `components.parameters`; a `$ref` to one is a container. `X-Contributor-Token` and `X-Share-Password` are
     capability secrets that cross the wire outside any schema.
  8. **Every declared response header** (`responses.*.headers.*`, also in `components.responses` /
     `components.headers`) carries a marker. The web refresh token travels ONLY as a `Set-Cookie` (ADR 0004).

The `token` class is load-bearing beyond this guard: `scripts/run_web_e2e.py` derives its second needle set —
every capability token a run handled — by reading values at `token`-marked properties, scalar components and
headers out of its HTTP recording (cc.security.wire-assertion § Capability tokens). A token left unmarked here
is a token that sweep never looks for.

**The defect that bought rules 6–8.** No leak; a classification hole found while refining the token sweep
(cr-wire-assertion-capability-tokens): the response copy of the invite secret, both capability headers and the
refresh-token cookie were unclassified, so a derivation by marker would have skipped exactly the tokens a database
dump most needs to be searched for.

**Scope — read this before quoting a green run.** It checks the *contract*, not the *traffic*: JSON body
properties, scalar component schemas, header parameters and declared response headers. It does not see: path
params, query params, presigned-URL query strings, DB columns with no contract, the free text a server actually
puts in an error `message`, a header the server sends WITHOUT declaring it, or **the S3 object key** — the one
surface the operator literally lists (that has its own guard, in the backend, because it needs one and this
cannot be it). Nor does it observe whether a client put ciphertext into a field marked opaque: a marker
*permits*, it does not verify. Green means **"no unclassified field in a JSON body, and no unclassified declared
header"**. It never means "operator-blind, verified". Anyone who reads it that way has been misled by this file.
"""
from __future__ import annotations

import pathlib
import sys
from typing import Iterator

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
API = ROOT / "contracts" / "api"

MARKER = "x-veyq-visibility"
RATIONALE = "x-veyq-rationale"

# ADR 0044's class table. The values are `security-model.md` § What is encrypted vs. server-visible's own kinds.
CLASSES: dict[str, str] = {
    "id": "an opaque identifier the server routes and authorizes on",
    "structure": "scope, membership, parentage, bucket shape, ordering index, epoch",
    "measure": "sizes, counts, offsets",
    "time": "server timestamps, as written (nothing rounds them)",
    "enum": "a closed set the contract itself declares",
    "flag": "a boolean the server acts on",
    "token": "a bearer or capability secret in transit",
    "public": "the server's own words — operator-authored text, codes and self-description; never user data",
    "pii": "deliberate sensitive plaintext",
}

HTTP_METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")

errors: list[str] = []


def unwrap_nullable(prop: dict) -> dict:
    """Collapse the nullable spellings to the schema they wrap.

    OpenAPI 3.1 has three ways to say "X or null", and all three appear in these specs:
    `type: ['array','null']`, `oneOf: [{$ref: X}, {type: null}]`, and a plain non-null schema. A container
    must stay a container through every one of them — otherwise the guard demands a marker on a `$ref`, which
    ADR 0044 says carries none.
    """
    for key in ("oneOf", "anyOf"):
        alts = prop.get(key)
        if isinstance(alts, list):
            real = [a for a in alts if isinstance(a, dict) and a.get("type") != "null"]
            if len(real) == 1:
                merged = {k: v for k, v in prop.items() if k not in ("oneOf", "anyOf")}
                merged.update(real[0])
                return merged
    return prop


def type_names(schema: dict) -> set[str]:
    t = schema.get("type")
    if isinstance(t, list):
        return {x for x in t if x != "null"}
    return {t} if t else set()


def is_opaque(prop: dict) -> bool:
    """base64 bytes — ciphertext, a wrapped key, a public key, a signature, a salt, a blinded token."""
    schema = unwrap_nullable(prop)
    if schema.get("contentEncoding") == "base64":
        return True
    if "array" in type_names(schema):
        items = schema.get("items") or {}
        if isinstance(items, dict) and items.get("contentEncoding") == "base64":
            return True
    return False


def is_container(prop: dict) -> bool:
    """A `$ref`, or an array of `$ref` — the target's properties carry the classification."""
    schema = unwrap_nullable(prop)
    if "$ref" in schema:
        return True
    if "array" in type_names(schema):
        items = schema.get("items") or {}
        if isinstance(items, dict) and "$ref" in unwrap_nullable(items):
            return True
    return False


def is_scalar_component(schema: dict) -> bool:
    """A component schema with no properties of its own to carry a class: not an object, container or opaque."""
    if is_opaque(schema) or is_container(schema):
        return False
    if "properties" in schema or "object" in type_names(schema):
        return False
    return not any(key in schema for key in ("allOf", "oneOf", "anyOf"))


def header_declarations(spec: dict) -> Iterator[tuple[str, dict]]:
    """Every header a spec declares outside a body — `in: header` parameters and response headers — as `(where, decl)`.

    A `$ref` is skipped: it points at a declaration this walk visits where it is defined (rule 2's container logic,
    applied to parameters and headers).
    """
    components = spec.get("components") or {}
    for name, param in (components.get("parameters") or {}).items():
        if (param or {}).get("in") == "header":
            yield f"components.parameters.{name} (header {param.get('name')})", param
    for name, header in (components.get("headers") or {}).items():
        if "$ref" not in (header or {}):
            yield f"components.headers.{name}", header or {}
    for name, response in (components.get("responses") or {}).items():
        for header_name, header in ((response or {}).get("headers") or {}).items():
            if "$ref" not in (header or {}):
                yield f"components.responses.{name} response header {header_name}", header or {}
    for path, item in (spec.get("paths") or {}).items():
        item = item or {}
        operations = [(m, item[m]) for m in HTTP_METHODS if isinstance(item.get(m), dict)]
        for param in item.get("parameters") or []:
            if "$ref" not in param and param.get("in") == "header":
                yield f"{path} header {param.get('name')}", param
        for method, operation in operations:
            for param in operation.get("parameters") or []:
                if "$ref" not in param and param.get("in") == "header":
                    yield f"{method.upper()} {path} header {param.get('name')}", param
            for status, response in (operation.get("responses") or {}).items():
                for header_name, header in ((response or {}).get("headers") or {}).items():
                    if "$ref" not in (header or {}):
                        yield f"{method.upper()} {path} {status} response header {header_name}", header or {}


def classify(where: str, decl: dict, counts: dict[str, int], pii: list[str], kind: str) -> None:
    """Rules 3 and 4 for one declaration that must carry a marker. `kind` names it in the failure."""
    marker = decl.get(MARKER)
    if marker is None:
        errors.append(
            f"{where} — {kind} — declares no `{MARKER}`; say what the server may read of it "
            f"({'/'.join(CLASSES)}) (ADR 0044)")
        return
    if marker not in CLASSES:
        errors.append(
            f"{where} has `{MARKER}: {marker}` — not a class. ADR 0044's table is the whole set: "
            f"{', '.join(CLASSES)}")
        return
    counts[marker] += 1
    if marker == "pii":
        why = decl.get(RATIONALE)
        if not (isinstance(why, str) and why.strip()):
            errors.append(
                f"{where} is `{MARKER}: pii` with no `{RATIONALE}` — name the mechanism that "
                f"forces this plaintext. Only the plaintext the mechanism requires stays "
                f"plaintext (ADR 0023)")
            return
        pii.append(where)


def main() -> int:
    specs = sorted(API.glob("*.openapi.yaml"))
    if not specs:
        print(f"no specs found under {API.relative_to(ROOT)}")
        return 1

    counts: dict[str, int] = dict.fromkeys(CLASSES, 0)
    pii: list[str] = []
    n_schemas = n_props = n_opaque = n_containers = n_scalars = 0
    n_scalar_components = n_headers = 0

    for path in specs:
        spec = yaml.safe_load(path.read_text()) or {}
        schemas = ((spec.get("components") or {}).get("schemas") or {})
        for sname, schema in schemas.items():
            n_schemas += 1
            schema = schema or {}
            if is_scalar_component(schema):
                n_scalar_components += 1
                classify(f"{path.name}:{sname}", schema, counts, pii, "a scalar component schema (rule 6)")
            props = (schema.get("properties") or {})
            for pname, prop in props.items():
                n_props += 1
                prop = prop or {}
                where = f"{path.name}:{sname}.{pname}"
                marker = prop.get(MARKER)

                if is_opaque(prop) or is_container(prop):
                    kind = "opaque (contentEncoding: base64)" if is_opaque(prop) else "a container ($ref)"
                    if is_opaque(prop):
                        n_opaque += 1
                    else:
                        n_containers += 1
                    if marker is not None:
                        errors.append(
                            f"{where} is {kind} and carries `{MARKER}: {marker}` — drop the marker. base64 "
                            f"already says opaque and a container's target carries its own classes; a second "
                            f"way to declare visibility is a way to declare the opposite (ADR 0044)")
                    continue

                n_scalars += 1
                classify(where, prop, counts, pii, "a scalar property; or seal it with `contentEncoding: base64`")

        for where, decl in header_declarations(spec):
            n_headers += 1
            classify(f"{path.name}:{where}", decl, counts, pii, "a declared header (rules 7, 8)")

    if errors:
        print("FIELD VISIBILITY VALIDATION FAILED:")
        for e in errors:
            print("  -", e)
        return 1

    spread = " · ".join(f"{c}={counts[c]}" for c in CLASSES if counts[c])
    print(
        f"field visibility OK: {len(specs)} specs · {n_schemas} schemas · {n_props} properties — "
        f"{n_opaque} opaque (base64) · {n_containers} containers · {n_scalars} classified properties, "
        f"{n_scalar_components} scalar component schemas, {n_headers} declared headers ({spread})")
    print(f"  deliberate-plaintext budget: {len(pii)} `pii` field(s), each with a rationale:")
    for p in pii:
        print(f"    - {p}")
    print("  scope: no unclassified field in a JSON body, and no unclassified declared header. NOT "
          "'operator-blind, verified' — see the docstring.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
