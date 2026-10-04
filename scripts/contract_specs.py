"""Where the OpenAPI specs live — the one place a script learns it (cr-platform-gradle-modules, step 5).

The contracts name their owner: core's specs sit directly in `contracts/api/`, an application's in a directory of
its own (`contracts/api/memories/`), and `_common.openapi.yaml` holds the components several specs share. A reader
that globs `contracts/api/*.openapi.yaml` non-recursively stops seeing an application's specs without an error, so
every script that reads the specs asks this module instead: the specs are **found by recursive glob**, never listed.

Two shapes, so no reader changes what it reads: `specs()` holds `_common` too (what a `*.openapi.yaml` glob returned),
`specs(common=False)` leaves it out (the readers that compare per-domain copies). A spec is named by its **key**, its
path relative to `contracts/api` in POSIX form (`blob.openapi.yaml`, `memories/media.openapi.yaml`), and a `$ref`'s
file part is resolved against the directory of the spec that holds it (`ref_key`), so `../_common.openapi.yaml`
from `memories/` and `_common.openapi.yaml` from the top level name the same file.

**The canary.** `specs()` raises when it finds no spec under `contracts/api/memories/`: a reader that silently lost
the application's specs would otherwise stay green, because no rule of any guard is Memories-only by *name* (every
opaque property name a Memories schema carries is also carried by a core one). The opaque-name readers additionally
assert a *location* only `memories/` holds, `OPAQUE_LOCATION_CANARY`.

Not a guard (no `validate_` prefix): it asserts nothing about the contracts and is imported by the scripts that read them.
"""
from __future__ import annotations

import pathlib
import posixpath

ROOT = pathlib.Path(__file__).resolve().parent.parent
API = ROOT / "contracts" / "api"
COMMON = API / "_common.openapi.yaml"
APPLICATIONS: tuple[str, ...] = ("memories",)  # the directories below contracts/api that hold an application's specs
# An opaque property at a location only `memories/` holds — both opaque-name oracles assert they read it, because a
# check on the NAME alone would stay green if `memories/` vanished (each opaque name a Memories schema carries is
# carried by a core spec too).
OPAQUE_LOCATION_CANARY = "memories/media.openapi.yaml#MediaExifResponse.wrappedExif"


class SpecsMissing(RuntimeError):
    """The spec tree is not where this module says it is — a reader must not go on with a partial set."""


def key(path: pathlib.Path) -> str:
    """A spec's name: its path relative to `contracts/api`, POSIX form."""
    return path.resolve().relative_to(API.resolve()).as_posix()


def specs(*, common: bool = True) -> list[pathlib.Path]:
    """Every `*.openapi.yaml` under `contracts/api`, recursively, sorted by key; `_common` unless `common=False`."""
    found = sorted((p for p in API.rglob("*.openapi.yaml") if p.is_file()), key=key)
    if not found:
        raise SpecsMissing(f"no spec found under {API.relative_to(ROOT)}")
    for app in APPLICATIONS:
        if not any(key(p).startswith(f"{app}/") for p in found):
            raise SpecsMissing(f"no spec found under {(API / app).relative_to(ROOT)} — a reader that misses an "
                               "application's specs would stay green on the rest")
    return found if common else [p for p in found if p.resolve() != COMMON.resolve()]


def load(spec_key: str) -> dict:
    """A spec, by key, parsed."""
    import yaml  # local: a reader that only globs needs no YAML parser

    return yaml.safe_load((API / spec_key).read_text(encoding="utf-8")) or {}


def schema(spec_key: str, name: str) -> tuple[str, dict | None]:
    """`components.schemas.<name>` as `spec_key` uses it — through an alias (`Name: {$ref: …}`, the single-source
    pattern of ADR 0038) to wherever it is defined. Returns the defining spec's key and the schema (None if absent)."""
    node = ((load(spec_key).get("components") or {}).get("schemas") or {}).get(name)
    for _ in range(8):  # an alias chain this long is a cycle
        if not (isinstance(node, dict) and set(node) == {"$ref"}):
            return spec_key, node if isinstance(node, dict) else None
        target, _, pointer = node["$ref"].partition("#")
        spec_key = ref_key(spec_key, target)
        node = load(spec_key)
        for part in pointer.strip("/").split("/"):
            node = (node or {}).get(part) if isinstance(node, dict) else None
    raise SpecsMissing(f"{spec_key}: the alias chain of {name} does not end")


def ref_key(from_key: str, target: str) -> str:
    """The key of the file a `$ref` names (`target` is its part before `#`), resolved against `from_key`'s
    directory; an empty target is the spec itself."""
    if not target:
        return from_key
    return posixpath.normpath(posixpath.join(posixpath.dirname(from_key), target))
