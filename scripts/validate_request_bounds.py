#!/usr/bin/env python3
"""Guard that every request-body bound in the contracts reaches the server (inbox #153).

**What it asserts.** The backend's Kotlin models are generated with no schema-derived bean validation, so a
bound in a request schema is enforced only by the `x-field-extra-annotation` someone hand-writes beside it
(`developer-styleguide.md`, the request-body bullet). This guard holds the two together. Its subject is every
property of every schema reachable from any operation's `requestBody` in `contracts/api/*.openapi.yaml` —
transitively through `$ref` (across spec files), inline objects, `items`, `allOf`, and the OpenAPI 3.1 nullable
`oneOf`/`anyOf` spellings — derived from the specs on every run, never listed. A property that `$ref`s a **scalar**
component schema (`NodeKind`, `CapabilityTag`) is read as that scalar: the generator inlines it as a String on the
field and emits none of its bounds, so the target's `pattern`/`maxLength` hold only through an annotation: the one
beside the `$ref`, or else the target's own `x-field-extra-annotation`, which the generator puts on every field that
`$ref`s it with none of its own (measured). For each such property:

  1. **bound → annotation, same value.** `maxLength`/`maxItems` → `@Size(max = N)`; `minLength`/`minItems` →
     `@Size(min = N)` (a `@NotBlank`/`@NotEmpty` also satisfies N = 1); `minimum` → `@Min(N)`; `maximum` →
     `@Max(N)`; `pattern` → a `@Pattern` with the same regexp (anchors normalised: bean validation matches the
     whole value either way); `contentEncoding: base64` → the base64 `@Pattern` the specs use; `format: email`
     → `@Email`.
  2. **annotation → bound.** No `@Size`/`@Min`/`@Max`/`@Pattern`/`@Email` enforces something the schema does
     not declare. The base64 `@Pattern` counts as declared by `contentEncoding: base64`.
  3. **cascade.** A property whose target is an object schema, or a list of one, carries
     `@field:jakarta.validation.Valid` whenever that target carries any constraint at any depth — validation
     does not descend without it, so every annotation below would be dead.
  4. **no string bound on a type-changing `format`.** `format: uuid` generates `java.util.UUID` (`date-time` an
     `OffsetDateTime`): a String validator there is an `UnexpectedTypeException` — a 500 on a well-formed request
     — and a string bound in the schema is dead text, since the length is the type's (`backend/CLAUDE.md`).
  5. **a required primitive refuses its absence.** A required `integer`/`number`/`boolean` generates a Kotlin
     primitive, which Jackson fills with 0/false when the field is missing — so a missing field passes as that
     default unless something refuses it. Either its `minimum` already excludes the default (a missing
     `chunkCount` is 0 < `@Min(1)`), or it is typed nullable (`[integer, "null"]`) and annotated `@NotNull`.
     Measured twice before this rule: `updateMediaMeta` without `metaVersion`, then `putIdentityPins` /
     `putDeviceRegister` without `version`, `updateTenantStorage` without its limit (0 = link uploads off) and
     `updateTenantMailSettings` without `tls` (false = STARTTLS off) — every one answered 200, not 400.
     There is no exception: a null that would itself mean something cannot be told from absence once Jackson
     bound it, so that meaning gets an operation of its own — `setTenantStorageQuota` read null as "no quota", so
     a request without the field cleared the quota, until `clearTenantStorageQuota` took that meaning (inbox #304).

  6. **a list of scalars bounds its elements.** The `kotlin` generator types such a list `List<String>` and cannot
     annotate the type argument, so the bounds its `items` declare (`maxLength`/`minLength`/`pattern`, read through
     a `$ref`'d scalar like `CapabilityTag`) ride on the list as `@field:me.raddatz.veyq.core.http.EachElement(...)`
     — value for value, both ways, as rules 1 and 2 hold a field (inbox #354: `ClaimRequest.capabilityTags` took any
     string as a tag). An element bound with no `EachElement` argument to carry it (`format`, `contentEncoding`, a
     number's range) is named too, rather than passed unseen, and so is an `EachElement` on anything but such a list.
     A type-changing element `format` (a list of `uuid`s) needs no carrier: the element's type refuses the rest.

The failure names the list — spec, schema, property, rule — never a count.

**The defect that bought it.** `BlobUploadsBatchRequest.items` declared `maxItems: 256` with no `@Size` and no
`@Valid`, so one request could open any number of upload sessions, and every `wrappedBlobKey` check below it
(`@NotBlank @Size @Pattern`) was dead — the anonymous share-contributor path included. The first run of this
guard, on the tree the fix landed on, named 59 such gaps across eight specs. The `$ref`'d scalar was a blind
spot until cr-platform-contract-split slice 4 opened `NodeKind` to a patterned token: its first run named
`EnqueueRequest.capabilityTag`, whose `CapabilityTag` bounds no annotation carried (an upper-case tag was enqueued).

**Its limit.** It proves an annotation is **present** and agrees with its schema, never that it has an
**effect**: that the generator emits it verbatim onto the field, that Quarkus validates the body, and that the
`@Valid` cascade descends are proven by the slice tests `MediaCoreSliceTest.startBlobUploadsOverItsItemBoundIs400`,
`MediaCoreSliceTest.startBlobUploadsValidatesInsideEachItem` and
`ShareContributorSliceTest.shareUploadOverItsItemBoundIs400` — one per mechanism, not per field; `EachElement`'s by
`MalformedRequestSliceTest.aListElementOutsideItsBoundsIsInvalidRequest`. It does not see
path/query/header **parameters** (the `jaxrs-spec` generator derives their annotations itself, and no test sends
an out-of-range one), **response** schemas, **presence** beyond rule 5 (a missing object, enum or uuid field is
refused by deserialization on its own; `@NotBlank` beyond N = 1). And it ties the annotation to the schema only — never either to the
DB column (`validate_maxlength_storage.py` does that for base64 caps) or to what a client sends, so a bound that
is the *wrong value* is green here.
"""
from __future__ import annotations

import pathlib
import re
import sys

import yaml

import contract_specs

ROOT = pathlib.Path(__file__).resolve().parent.parent
API = contract_specs.API

# The base64 alphabet check every opaque field carries (`contentEncoding: base64` ⇒ this `@Pattern`).
BASE64_REGEXP = "[A-Za-z0-9+/]*={0,2}"
# A `format` that makes the generator emit a non-String type, so a String validator on it is a 500.
TYPE_CHANGING_FORMATS = {"uuid", "date-time", "date"}

CONSTRAINT = r"@field:jakarta\.validation\.constraints\."
VALID_RE = re.compile(r"@field:jakarta\.validation\.Valid\b")
SIZE_RE = re.compile(CONSTRAINT + r"Size\(([^)]*)\)")
MIN_RE = re.compile(CONSTRAINT + r"Min\((-?\d+)\)")
MAX_RE = re.compile(CONSTRAINT + r"Max\((-?\d+)\)")
# `regexp = "<Kotlin string literal>"` — the literal may contain `\"`, so match escapes explicitly.
PATTERN_RE = re.compile(CONSTRAINT + r'Pattern\(regexp\s*=\s*"((?:[^"\\]|\\.)*)"\)')
EMAIL_RE = re.compile(CONSTRAINT + r"Email\b")
# Rule 6: the elements' bounds of a list of scalars, carried on the list (core's own constraint, `core/http/EachElement.kt`).
EACH_RE = re.compile(r'@field:me\.raddatz\.veyq\.core\.http\.EachElement\(((?:[^()"]|"(?:[^"\\]|\\.)*")*)\)')
EACH_ARG_RE = re.compile(r'(minLength|maxLength)\s*=\s*(\d+)|pattern\s*=\s*"((?:[^"\\]|\\.)*)"')
PRESENCE_MIN1_RE = re.compile(CONSTRAINT + r"(?:NotBlank|NotEmpty)\b")
NOT_NULL_RE = re.compile(CONSTRAINT + r"NotNull\b")
# The schema types the generator turns into a Kotlin primitive, with the default Jackson binds a missing one to.
PRIMITIVE_DEFAULTS = {"integer": 0, "number": 0, "boolean": False}
ANY_CONSTRAINT_RE = re.compile(CONSTRAINT + r"\w+")

errors: list[str] = []


def load_specs() -> dict[str, dict]:
    return {contract_specs.key(p): (yaml.safe_load(p.read_text()) or {}) for p in contract_specs.specs()}


def resolve(specs: dict[str, dict], ref: str, file: str) -> tuple[str, str, dict]:
    """A `$ref` → (file, schema name, schema). Handles same-file `#/…` and cross-file `x.yaml#/…` refs."""
    target, _, pointer = ref.partition("#")
    rfile = contract_specs.ref_key(file, target)
    node = specs[rfile]
    for part in pointer.strip("/").split("/"):
        node = node[part]
    return rfile, pointer.rsplit("/", 1)[-1], node


def unwrap_nullable(prop: dict) -> dict:
    """Collapse the OpenAPI 3.1 nullable `oneOf`/`anyOf` spelling to the one real schema it wraps."""
    for key in ("oneOf", "anyOf"):
        alts = prop.get(key)
        if isinstance(alts, list):
            real = [a for a in alts if isinstance(a, dict) and a.get("type") != "null"]
            if len(real) == 1:
                merged = {k: v for k, v in prop.items() if k != key}
                merged.update(real[0])
                return merged
    return prop


def types_of(schema: dict) -> set[str]:
    t = schema.get("type")
    return set(t) if isinstance(t, list) else ({t} if isinstance(t, str) else set())


class Walker:
    """Visits every object schema reachable from a request body and checks each of its properties."""

    def __init__(self, specs: dict[str, dict]):
        self.specs = specs
        self.visited: set[tuple[str, str]] = set()
        self.constrained_memo: dict[tuple[str, str], bool] = {}
        self.n_props = 0

    # -- the target of a property (what `@Valid` would cascade into) ------------------------------------------

    def object_target(self, schema: dict, file: str, label: str) -> tuple[str, str, dict] | None:
        """The object schema a property's value is — directly, or as the element of a list — else None."""
        s = unwrap_nullable(schema)
        if "items" in s and isinstance(s["items"], dict):
            s, label = unwrap_nullable(s["items"]), label + "[]"
        if "$ref" in s:
            file, label, s = resolve(self.specs, s["$ref"], file)
            s = unwrap_nullable(s)
        if "properties" in s or "allOf" in s:
            return file, label, s
        return None

    def scalar_ref(self, prop: dict, file: str) -> dict:
        """A property that `$ref`s a scalar component schema, seen as the scalar it generates: the target's keys under
        the property's own. The generator inlines such a target (`NodeKind` is a `kotlin.String` on the field, no class
        of its own) and emits none of its bounds; the field gets the target's `x-field-extra-annotation`, unless the
        property carries one beside the `$ref`, which replaces it (measured on `CapabilityTag`, cr-platform-contract-
        split slice 4) — the same precedence as this merge. Any other property is returned as it is."""
        s = unwrap_nullable(prop)
        if "$ref" not in s:
            return prop
        tfile, _, target = resolve(self.specs, s["$ref"], file)
        for _ in range(16):  # a spec's local alias of a `_common` schema is a `$ref` to a `$ref`
            if "$ref" not in target:
                break
            tfile, _, target = resolve(self.specs, target["$ref"], tfile)
        target = unwrap_nullable(target)
        if "properties" in target or "allOf" in target or "items" in target or "object" in types_of(target):
            return prop
        merged = dict(target)
        merged.update({k: v for k, v in s.items() if k != "$ref"})
        if s is not prop:  # the nullable `oneOf`/`anyOf` spelling: the property stays nullable once inlined
            merged["type"] = sorted(types_of(target) | {"null"})
        return merged

    def scalar_element(self, prop: dict, file: str) -> dict | None:
        """The element schema of a list of scalars (a `$ref`'d scalar read as that scalar), else None."""
        s = unwrap_nullable(prop)
        items = s.get("items")
        if not isinstance(items, dict) or self.object_target(prop, file, "") is not None:
            return None
        return unwrap_nullable(self.scalar_ref(items, file))

    def constrained(self, file: str, label: str, schema: dict) -> bool:
        """Whether an object schema carries any constraint at any depth (so `@Valid` into it is load-bearing)."""
        key = (file, label)
        if key in self.constrained_memo:
            return self.constrained_memo[key]
        self.constrained_memo[key] = False  # cycle guard
        result = False
        for pname, prop, _ in self.properties(schema, file):
            ann = annotation_of(prop)
            if ANY_CONSTRAINT_RE.search(ann) or declared_bounds(unwrap_nullable(prop)):
                result = True
                break
            target = self.object_target(prop, file, f"{label}.{pname}")
            if target and self.constrained(*target):
                result = True
                break
        self.constrained_memo[key] = result
        return result

    def properties(self, schema: dict, file: str):
        s = unwrap_nullable(schema)
        for part in s.get("allOf") or []:
            if "$ref" in part:
                rfile, _, part = resolve(self.specs, part["$ref"], file)
                yield from self.properties(part, rfile)
            else:
                yield from self.properties(part, file)
        required = set(s.get("required") or [])
        for pname, prop in (s.get("properties") or {}).items():
            yield pname, prop or {}, pname in required

    # -- the walk ---------------------------------------------------------------------------------------------

    def walk(self, file: str, label: str, schema: dict) -> None:
        if (file, label) in self.visited:
            return
        self.visited.add((file, label))
        for pname, prop, required in self.properties(schema, file):
            self.n_props += 1
            where = f"{file}:{label}.{pname}"
            effective = self.scalar_ref(prop, file)
            check_property(where, effective)
            element = self.scalar_element(prop, file)
            if element is not None:
                check_elements(where, prop, element)
            elif EACH_RE.search(annotation_of(prop)):
                errors.append(f"{where} [elements] — `EachElement` on a property that is no list of scalars: it "
                              f"enforces element bounds nothing declares (and fails on a non-collection at request time).")
            if required:
                check_presence(where, effective)
            target = self.object_target(prop, file, f"{label}.{pname}")
            if target is None:
                continue
            if self.constrained(*target) and not VALID_RE.search(annotation_of(prop)):
                errors.append(
                    f"{where} [cascade] — its target `{target[1]}` carries constraints, but the property has no "
                    f"`@field:jakarta.validation.Valid`, so validation never descends into it and every "
                    f"annotation below is dead.")
            self.walk(*target)


def annotation_of(prop: dict) -> str:
    ann = prop.get("x-field-extra-annotation")
    if ann is None:
        ann = unwrap_nullable(prop).get("x-field-extra-annotation")
    return ann if isinstance(ann, str) else ""


def declared_bounds(s: dict) -> list[str]:
    """The bound keywords a (nullable-unwrapped) property schema declares."""
    keys = ["maxLength", "minLength", "maxItems", "minItems", "minimum", "maximum", "pattern"]
    out = [k for k in keys if k in s]
    if s.get("contentEncoding") == "base64":
        out.append("contentEncoding")
    if s.get("format") == "email":
        out.append("format: email")
    return out


def kotlin_unescape(literal: str) -> str:
    """The value of a Kotlin string literal's body: `\\\\` → `\\`, `\\"` → `"`, `\\$` → `$`."""
    return re.sub(r'\\([\\"$])', r"\1", literal)


def normalise_regexp(rx: str) -> str:
    """Drop one leading `^` and one trailing unescaped `$`: `@Pattern` matches the whole value regardless."""
    if rx.startswith("^"):
        rx = rx[1:]
    if rx.endswith("$") and not rx.endswith("\\$"):
        rx = rx[:-1]
    return rx


def size_args(args: str) -> dict[str, int]:
    return {k: int(v) for k, v in re.findall(r"(min|max)\s*=\s*(\d+)", args)}


def check_property(where: str, prop: dict) -> None:
    s = unwrap_nullable(prop)
    ann = annotation_of(prop)
    is_array = "array" in types_of(s) or "items" in s

    sizes = [size_args(a) for a in SIZE_RE.findall(ann)]
    size_max = [d["max"] for d in sizes if "max" in d]
    size_min = [d["min"] for d in sizes if "min" in d]
    mins = [int(v) for v in MIN_RE.findall(ann)]
    maxs = [int(v) for v in MAX_RE.findall(ann)]
    patterns = [normalise_regexp(kotlin_unescape(p)) for p in PATTERN_RE.findall(ann)]
    has_email = bool(EMAIL_RE.search(ann))
    presence_min1 = bool(PRESENCE_MIN1_RE.search(ann))

    def fail(rule: str, msg: str) -> None:
        errors.append(f"{where} [{rule}] — {msg}")

    # rule 4 — a type-changing format takes no string bound, in the schema or the annotation
    fmt = s.get("format")
    if fmt in TYPE_CHANGING_FORMATS:
        dead = [k for k in ("maxLength", "minLength", "pattern") if k in s]
        if dead:
            fail("type-changing format", f"`format: {fmt}` generates a non-String type; its {', '.join(dead)} is "
                 f"dead text — remove it (the length is the type's).")
        if sizes or patterns or has_email:
            fail("type-changing format", f"`format: {fmt}` generates a non-String type; a String validator on it "
                 f"is a 500 on a well-formed request. Only `@NotNull` belongs there.")
        return

    max_kw, min_kw = ("maxItems", "minItems") if is_array else ("maxLength", "minLength")
    # rule 1 — every declared bound is annotated with the same value
    if max_kw in s and s[max_kw] not in size_max:
        fail("bound→annotation", f"`{max_kw}: {s[max_kw]}` has no `@Size(max = {s[max_kw]})`"
             + (f" (annotated max {size_max})" if size_max else "") + ".")
    if min_kw in s:
        n = s[min_kw]
        if n not in size_min and not (n == 1 and presence_min1):
            fail("bound→annotation", f"`{min_kw}: {n}` has no `@Size(min = {n})`"
                 + (" (or `@NotBlank`/`@NotEmpty`)" if n == 1 else "")
                 + (f" (annotated min {size_min})" if size_min else "") + ".")
    if "minimum" in s and s["minimum"] not in mins:
        fail("bound→annotation", f"`minimum: {s['minimum']}` has no `@Min({s['minimum']})`"
             + (f" (annotated {mins})" if mins else "") + ".")
    if "maximum" in s and s["maximum"] not in maxs:
        fail("bound→annotation", f"`maximum: {s['maximum']}` has no `@Max({s['maximum']})`"
             + (f" (annotated {maxs})" if maxs else "") + ".")
    declared_patterns = []
    if "pattern" in s:
        declared_patterns.append(normalise_regexp(s["pattern"]))
    if s.get("contentEncoding") == "base64":
        declared_patterns.append(BASE64_REGEXP)
    for rx in declared_patterns:
        if rx not in patterns:
            what = "`contentEncoding: base64`" if rx == BASE64_REGEXP and "pattern" not in s else f"`pattern: {rx}`"
            fail("bound→annotation", f"{what} has no `@Pattern(regexp = \"{rx}\")`"
                 + (f" (annotated {patterns})" if patterns else "") + ".")
    if s.get("format") == "email" and not has_email:
        fail("bound→annotation", "`format: email` has no `@Email`.")

    # rule 2 — nothing is annotated that the schema does not declare
    for n in size_max:
        if s.get(max_kw) != n:
            fail("annotation→bound", f"`@Size(max = {n})` enforces a bound the schema does not declare "
                 f"(`{max_kw}` is {s.get(max_kw, 'absent')}).")
    for n in size_min:
        if s.get(min_kw) != n:
            fail("annotation→bound", f"`@Size(min = {n})` enforces a bound the schema does not declare "
                 f"(`{min_kw}` is {s.get(min_kw, 'absent')}).")
    for n in mins:
        if s.get("minimum") != n:
            fail("annotation→bound", f"`@Min({n})` enforces a bound the schema does not declare "
                 f"(`minimum` is {s.get('minimum', 'absent')}).")
    for n in maxs:
        if s.get("maximum") != n:
            fail("annotation→bound", f"`@Max({n})` enforces a bound the schema does not declare "
                 f"(`maximum` is {s.get('maximum', 'absent')}).")
    for rx in patterns:
        if rx not in declared_patterns:
            fail("annotation→bound", f"`@Pattern(regexp = \"{rx}\")` enforces a pattern the schema does not "
                 f"declare (schema: {declared_patterns or 'none'}).")
    if has_email and s.get("format") != "email":
        fail("annotation→bound", "`@Email` enforces a format the schema does not declare (`format: email`).")


def check_elements(where: str, prop: dict, element: dict) -> None:
    """Rule 6 — a list of scalars carries its elements' bounds as one `EachElement`, value for value, both ways."""
    eaches = EACH_RE.findall(annotation_of(prop))
    declared: dict[str, object] = {k: element[k] for k in ("minLength", "maxLength") if k in element}
    if "pattern" in element:
        declared["pattern"] = normalise_regexp(element["pattern"])
    # A type-changing `format` (`uuid`) needs no carrier: the element's generated type refuses what it cannot bind.
    unsupported = [k for k in ("minimum", "maximum", "contentEncoding") if k in element]
    if "format" in element and element["format"] not in TYPE_CHANGING_FORMATS:
        unsupported.append(f"format: {element['format']}")
    if unsupported:
        errors.append(f"{where} [elements] — its items declare {', '.join(unsupported)}, which `EachElement` cannot "
                      f"carry: widen it (and this rule) before declaring such an element bound.")
    if len(eaches) > 1:
        errors.append(f"{where} [elements] — {len(eaches)} `EachElement` annotations; one carries every bound.")
        return
    annotated: dict[str, object] = {}
    for args in eaches:
        for key, num, rx in EACH_ARG_RE.findall(args):
            if key:
                annotated[key] = int(num)
            else:
                annotated["pattern"] = normalise_regexp(kotlin_unescape(rx))
    if declared == annotated:
        return
    if not eaches:
        errors.append(f"{where} [elements] — its items declare {declared}, but the list has no "
                      f"`@field:me.raddatz.veyq.core.http.EachElement(...)`, so no element bound is enforced.")
        return
    errors.append(f"{where} [elements] — `EachElement` carries {annotated}, its items declare {declared}: the two "
                  f"must agree value for value.")


def check_presence(where: str, prop: dict) -> None:
    """Rule 5 — a required primitive whose absence would bind to the type's default must be refused."""
    s = unwrap_nullable(prop)
    kinds = types_of(s) - {"null"}
    if len(kinds) != 1 or next(iter(kinds)) not in PRIMITIVE_DEFAULTS:
        return
    kind = next(iter(kinds))
    nullable = "null" in types_of(s) or s is not prop
    if nullable:
        if not NOT_NULL_RE.search(annotation_of(prop)):
            errors.append(f"{where} [presence] — a required nullable `{kind}` without `@NotNull`: a missing field "
                          f"reaches the handler as null.")
        return
    default = PRIMITIVE_DEFAULTS[kind]
    if kind != "boolean" and "minimum" in s and s["minimum"] > default:
        return  # a missing field binds to 0, which the `@Min` (rule 1) already refuses
    errors.append(f"{where} [presence] — a required `{kind}` generates a Kotlin primitive, so a missing field binds "
                  f"to {str(default).lower()} and passes. Type it `[{kind}, \"null\"]` and annotate `@NotNull`.")


def main() -> int:
    specs = load_specs()
    if not specs:
        print(f"no specs found under {API.relative_to(ROOT)}")
        return 1
    walker = Walker(specs)
    n_bodies = 0
    for file, spec in specs.items():
        for path, ops in (spec.get("paths") or {}).items():
            for method, op in (ops or {}).items():
                body = op.get("requestBody") if isinstance(op, dict) else None
                if not body:
                    continue
                for media in (body.get("content") or {}).values():
                    schema = (media or {}).get("schema") or {}
                    n_bodies += 1
                    if "$ref" in schema:
                        walker.walk(*resolve(specs, schema["$ref"], file))
                    else:
                        walker.walk(file, f"{method.upper()} {path} body", schema)

    if errors:
        print(f"REQUEST-BOUNDS VALIDATION FAILED ({len(errors)}):")
        for e in errors:
            print("  -", e)
        return 1
    print(
        f"request bounds OK: {n_bodies} request bodies · {len(walker.visited)} reachable object schemas · "
        f"{walker.n_props} properties — every bound annotated value for value, nothing annotated beyond the "
        f"schema, every constrained nested object cascaded with @Valid, every required primitive refusing its absence, every "
        f"bounded list element carried by EachElement.")
    print("  scope: annotation PRESENT and equal to its schema. NOT its effect, parameters, responses — see the "
          "docstring.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
