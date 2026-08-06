#!/usr/bin/env python3
"""Guard that every contract field declares what the server may read of it (inbox #97, ADR 0044).

Operator-blindness is veyq's first hard invariant — *"no path may break this"* (`CLAUDE.md`) — and it was the
only major claim in the repo that nothing checked. Nine validators guarded doc links, feature format, coverage
refs, shared components, ADR quoting and scope vocabulary; none guarded the invariant the product exists for.
`security-model.md` § *What is encrypted vs. server-visible* drew the line in **kinds** ("sizes", "coarse
timestamps"); the contracts are written in **field names**. Nothing connected the two, so the enumeration was
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

**Scope — read this before quoting a green run.** This is a **body-surface** sensor, and it checks the
*contract*, not the *traffic*. It does not see: path params, query params, `X-Contributor-Token` /
`X-Share-Password` (capability secrets that cross the wire outside any schema), presigned-URL query strings, DB
columns with no contract, the free text a server actually puts in an error `message`, or **the S3 object key** —
the one surface the operator literally lists (that has its own guard, in the backend, because it needs one and
this cannot be it). Nor does it observe whether a client put ciphertext into a field marked opaque: a marker
*permits*, it does not verify. Green means **"no unclassified field in a JSON body"**. It never means
"operator-blind, verified". Anyone who reads it that way has been misled by this file.
"""
from __future__ import annotations

import pathlib
import sys

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
    "time": "coarse timestamps",
    "enum": "a closed set the contract itself declares",
    "flag": "a boolean the server acts on",
    "token": "a bearer or capability secret in transit",
    "public": "the server's own words — operator-authored text, codes and self-description; never user data",
    "pii": "deliberate sensitive plaintext",
}

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


def main() -> int:
    specs = sorted(API.glob("*.openapi.yaml"))
    if not specs:
        print(f"no specs found under {API.relative_to(ROOT)}")
        return 1

    counts: dict[str, int] = dict.fromkeys(CLASSES, 0)
    pii: list[str] = []
    n_schemas = n_props = n_opaque = n_containers = n_scalars = 0

    for path in specs:
        spec = yaml.safe_load(path.read_text()) or {}
        schemas = ((spec.get("components") or {}).get("schemas") or {})
        for sname, schema in schemas.items():
            n_schemas += 1
            props = ((schema or {}).get("properties") or {})
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
                if marker is None:
                    errors.append(
                        f"{where} declares no `{MARKER}` — say what the server may read of it "
                        f"({'/'.join(CLASSES)}), or seal it with `contentEncoding: base64` (ADR 0044)")
                    continue
                if marker not in CLASSES:
                    errors.append(
                        f"{where} has `{MARKER}: {marker}` — not a class. ADR 0044's table is the whole set: "
                        f"{', '.join(CLASSES)}")
                    continue

                counts[marker] += 1
                if marker == "pii":
                    why = prop.get(RATIONALE)
                    if not (isinstance(why, str) and why.strip()):
                        errors.append(
                            f"{where} is `{MARKER}: pii` with no `{RATIONALE}` — name the mechanism that "
                            f"forces this plaintext. Only the plaintext the mechanism requires stays "
                            f"plaintext (ADR 0023)")
                        continue
                    pii.append(where)

    if errors:
        print("FIELD VISIBILITY VALIDATION FAILED:")
        for e in errors:
            print("  -", e)
        return 1

    spread = " · ".join(f"{c}={counts[c]}" for c in CLASSES if counts[c])
    print(
        f"field visibility OK: {len(specs)} specs · {n_schemas} schemas · {n_props} properties — "
        f"{n_opaque} opaque (base64) · {n_containers} containers · {n_scalars} classified ({spread})")
    print(f"  deliberate-plaintext budget: {len(pii)} `pii` field(s), each with a rationale:")
    for p in pii:
        print(f"    - {p}")
    print("  scope: no unclassified field in a JSON body. NOT 'operator-blind, verified' — see the docstring.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
