#!/usr/bin/env python3
"""Guard that no base64 contract cap declares more than its DB column can hold (inbox #94).

Every opaque field in the schema is stored as `TEXT`: **65 535 bytes on MariaDB**, effectively unbounded on H2,
which is the *test* engine. So an oversize value passes the whole hermetic suite and fails only in production —
under the shipped `sql_mode` (STRICT_TRANS_TABLES) as a hard `ERROR 1406` → a 500 on a contract-valid body, or,
with strict off, as a silent truncation of the ciphertext. Nothing connected a contract `maxLength` to the
column that receives it: `identity_pins.sealed_blob` declared `@Size(max = 1048576)` over a `TEXT` column, and
`favorites.ciphertext` `maxLength: 65536` over a 65 535-byte one — off by one, and no guard saw either. Found
while sizing the #58 space icon (`sealedIcon`, 21 884), which carried the only hand-written proof that a cap fit
its column ("provably inside the TEXT column"); this makes that reasoning enforced for every base64 field.

base64 is ASCII: **1 char == 1 byte**, and a padded base64 string's length is always a **multiple of 4**. So a
`contentEncoding: base64` field's `maxLength` (chars) *is* its byte cost, and the largest value a `TEXT` column
holds is **65 532** — the greatest multiple of 4 ≤ 65 535 (65 535 itself is not a valid base64 length, which is
exactly why "just lower the cap to 65 535" is the wrong fix). For every `components.schemas.*.properties.*` in
`contracts/api/*.openapi.yaml` that is `contentEncoding: base64` and carries a `maxLength`:

  1. `maxLength` must be a **multiple of 4** — a non-multiple permits lengths no valid base64 value can reach,
     the tell of a cap picked without base64 in mind (the `65535` trap).
  2. `maxLength` must be **≤ 65 532** (fits `TEXT`) — UNLESS the field is on the `MEDIUMTEXT_BACKED` allowlist
     below, whose columns are widened to MEDIUMTEXT (16 MiB) by a migration.
  3. Each allowlisted column is **verified MEDIUMTEXT** against the migrations, so the allowlist cannot lie: an
     entry claiming a wider column that no `V*.sql` actually widened fails here. And each allowlist entry must
     match a real field, so a removed field leaves a loud stale entry, not silent rot.

**Scope — read this before quoting a green run.** This checks the *contract cap* against the repo's storage
convention (`TEXT` for opaque base64, per `backend/CLAUDE.md`), not the *actual* field→column wiring. It does
**not** see: free-text (non-base64) fields, whose byte width is `chars × up-to-4` under utf8mb4 and whose
serialized form may co-locate several capped fields in one column — the `terms_markdown_by_locale` class, where
no single `maxLength` exceeds `TEXT` yet the column can (that field is kept safe by *widening the column* in the
same CR, not by this guard, and the gap is pinned open in `validate_guard_gaps.py`); a base64 field mapped onto
a column *narrower* than `TEXT` (a `VARCHAR`/`TINYTEXT`), which this guard would wave through at any cap ≤ 65 532;
path/query params; or whether a client actually respects the cap. Green means **"no base64 contract cap exceeds
what the TEXT default (or an allowlisted MEDIUMTEXT column) holds"**. It never means "every field fits its
column, verified".
"""
from __future__ import annotations

import pathlib
import re
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
API = ROOT / "contracts" / "api"
MIGRATIONS = ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration"

# The largest value a MariaDB TEXT column (65 535 bytes) can hold that is also a valid (multiple-of-4) base64
# length. A base64 char is one ASCII byte, so chars == bytes for these fields.
TEXT_MAX_B64 = 65532

# base64 fields whose column is widened to MEDIUMTEXT (16 MiB) by a migration — the ONLY fields allowed a cap
# above TEXT_MAX_B64. Keyed (spec, schema, property) → the DB column that must be provably MEDIUMTEXT. Adding a
# field here is a deliberate "this column is wider than TEXT" claim, verified below against the migrations.
MEDIUMTEXT_BACKED: dict[tuple[str, str, str], str] = {
    ("account.openapi.yaml", "IdentityPins", "blob"): "sealed_blob",
    ("account.openapi.yaml", "IdentityPinsUpdate", "blob"): "sealed_blob",
    ("content.openapi.yaml", "Favorites", "ciphertext"): "ciphertext",
    ("content.openapi.yaml", "PutFavoritesRequest", "ciphertext"): "ciphertext",
    ("tasks.openapi.yaml", "ClaimedJob", "sealedBody"): "sealed_body",
    ("tasks.openapi.yaml", "EnqueueRequest", "sealedBody"): "sealed_body",
    ("tasks.openapi.yaml", "CompleteRequest", "sealedResult"): "sealed_result",
}

errors: list[str] = []


def unwrap_nullable(prop: dict) -> dict:
    """Collapse the OpenAPI 3.1 nullable spellings to the schema they wrap (as in validate_field_visibility)."""
    for key in ("oneOf", "anyOf"):
        alts = prop.get(key)
        if isinstance(alts, list):
            real = [a for a in alts if isinstance(a, dict) and a.get("type") != "null"]
            if len(real) == 1:
                merged = {k: v for k, v in prop.items() if k not in ("oneOf", "anyOf")}
                merged.update(real[0])
                return merged
    return prop


def base64_maxlength(prop: dict) -> int | None:
    """The maxLength of a `contentEncoding: base64` scalar (or base64-array items), else None."""
    schema = unwrap_nullable(prop)
    if schema.get("contentEncoding") == "base64":
        ml = schema.get("maxLength")
        return ml if isinstance(ml, int) else None
    items = schema.get("items")
    if isinstance(items, dict) and unwrap_nullable(items).get("contentEncoding") == "base64":
        ml = unwrap_nullable(items).get("maxLength")
        return ml if isinstance(ml, int) else None
    return None


def column_is_mediumtext(column: str) -> bool:
    """True if some migration declares `column` MEDIUMTEXT (comments stripped so a mention can't match)."""
    pat = re.compile(rf"\b{re.escape(column)}\b\s+MEDIUMTEXT\b", re.IGNORECASE)
    for sql in MIGRATIONS.glob("V*.sql"):
        for line in sql.read_text().splitlines():
            code = line.split("--", 1)[0]  # drop the trailing SQL comment
            if pat.search(code):
                return True
    return False


def main() -> int:
    specs = sorted(API.glob("*.openapi.yaml"))
    if not specs:
        print(f"no specs found under {API.relative_to(ROOT)}")
        return 1

    n_b64 = n_capped = 0
    seen_allowlist: set[tuple[str, str, str]] = set()

    for path in specs:
        spec = yaml.safe_load(path.read_text()) or {}
        schemas = ((spec.get("components") or {}).get("schemas") or {})
        for sname, schema in schemas.items():
            for pname, prop in ((schema or {}).get("properties") or {}).items():
                cap = base64_maxlength(prop or {})
                if cap is None:
                    # Either not base64, or base64 without a cap. A base64 field with no maxLength is a
                    # different, unbounded-write concern; this guard only reasons about declared caps.
                    if unwrap_nullable(prop or {}).get("contentEncoding") == "base64":
                        n_b64 += 1
                    continue
                n_b64 += 1
                n_capped += 1
                key = (path.name, sname, pname)
                where = f"{path.name}:{sname}.{pname}"

                if cap % 4 != 0:
                    errors.append(
                        f"{where} is base64 with maxLength {cap}, not a multiple of 4 — no valid base64 value "
                        f"reaches that length. A base64 cap is picked in multiples of 4 (the `65535` trap: it "
                        f"looks like it fits TEXT, but 65532 is the largest valid one).")
                    continue

                if key in MEDIUMTEXT_BACKED:
                    seen_allowlist.add(key)
                    continue  # ceiling waived; the column is verified MEDIUMTEXT after the walk

                if cap > TEXT_MAX_B64:
                    errors.append(
                        f"{where} is base64 with maxLength {cap} > {TEXT_MAX_B64} — more than a TEXT column "
                        f"holds (65 535 bytes; base64 char == byte). Either lower the cap to ≤ {TEXT_MAX_B64}, "
                        f"or widen the column to MEDIUMTEXT (migration) and add this field to MEDIUMTEXT_BACKED "
                        f"in {pathlib.Path(__file__).name}.")

    # The allowlist must not lie in either direction: every claimed column is really MEDIUMTEXT, and every
    # entry still names a live field.
    for column in sorted(set(MEDIUMTEXT_BACKED.values())):
        if not column_is_mediumtext(column):
            errors.append(
                f"MEDIUMTEXT_BACKED claims column `{column}` is wider than TEXT, but no migration declares it "
                f"MEDIUMTEXT — the allowlist lies. Add the widening migration, or remove the entry.")
    for key in MEDIUMTEXT_BACKED:
        if key not in seen_allowlist:
            errors.append(
                f"MEDIUMTEXT_BACKED entry {key} matched no base64 field with a cap > {TEXT_MAX_B64} — a stale "
                f"allowlist entry (field renamed/removed, or its cap lowered). Remove it.")

    if errors:
        print("MAXLENGTH-STORAGE VALIDATION FAILED:")
        for e in errors:
            print("  -", e)
        return 1

    print(
        f"maxlength storage OK: {len(specs)} specs · {n_b64} base64 field(s) · {n_capped} with a cap — "
        f"all ≤ {TEXT_MAX_B64} (TEXT) except {len(MEDIUMTEXT_BACKED)} MEDIUMTEXT-backed:")
    for (spec, sname, pname), col in sorted(MEDIUMTEXT_BACKED.items()):
        print(f"    - {spec}:{sname}.{pname} → {col} (MEDIUMTEXT)")
    print("  scope: base64 caps vs the TEXT default. NOT 'every field fits its column' — see the docstring.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
