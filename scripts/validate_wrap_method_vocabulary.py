#!/usr/bin/env python3
"""Guard that backend main spells no Master Key wrap method outside the one classification (cr-wrap-methods-survivor-whitelist).

**What it asserts.** No string literal whose whole content is a wrap method name appears in
`backend/src/main/kotlin` outside `shared/identity/WrapMethods.kt`. "String literal" covers both a Kotlin
double-quoted literal and a single-quoted SQL/JPQL literal inside a query string (Kotlin has no multi-character
char literal, so a single-quoted method name in a `.kt` file can only be query text). The method set is read
from `contracts/api/account.openapi.yaml` `MasterKeyWrap.method.enum` and never listed here, so a method added
to the contract is covered the day it lands. Every store therefore reaches a method through `WrapMethod`, whose
constants declare — with no default — whether a method survives a credential change and whether it is
device-local, and which `WrapMethodTest` pins to the same contract enum.

**The defect that bought it** (inbox #172). Three flows rewrite credentials, and each kept its own list of
methods. Rotation deleted everything but a whitelist of survivors; password change and reset deleted a
blacklist of the two device methods. The contract already names a fifth method, the passkey, so the day a
passkey wrap could be written it would have survived a reset holding the old Master Key — and every test that
existed would have stayed green, because each test seeded only the methods its own flow listed. A new flow
typing its own list of device methods would pass them all again; this is what fails it.

**Its limit.** It sees literals, not meaning:
  - a method name built by concatenation or case conversion is invisible;
  - the test trees are not scanned (a list typed in a test is legitimate there, and invisible here);
  - the web client's own handling of methods is out of scope — it is not backend main;
  - it cannot tell a wrap method from the same word used for something else, so a legitimate non-wrap literal
    equal to a method name in backend main is red. At the time it was written there was none; the fix for such
    a red is to route the use through `WrapMethod` if it is a wrap, and to rename the other thing if it is not.
"""
from __future__ import annotations

import pathlib
import re
import sys

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
CLASSIFICATION = backend_tree.source_file("com.veyq.core.identity.WrapMethods")
CONTRACT = ROOT / "contracts" / "api" / "account.openapi.yaml"
SCHEMA = "MasterKeyWrap"


def contract_methods() -> list[str] | None:
    schema = yaml.safe_load(CONTRACT.read_text()).get("components", {}).get("schemas", {}).get(SCHEMA, {})
    enum = schema.get("properties", {}).get("method", {}).get("enum")
    return [str(m) for m in enum] if enum else None


def main() -> int:
    for anchor in (CLASSIFICATION, CONTRACT):
        if not anchor.exists():
            print(f"WRAP METHOD VOCABULARY VALIDATION FAILED:\n  - missing {anchor.relative_to(ROOT)} — this "
                  f"guard's anchor moved")
            return 1

    methods = contract_methods()
    if not methods:
        print(f"WRAP METHOD VOCABULARY VALIDATION FAILED:\n  - {CONTRACT.relative_to(ROOT)} has no "
              f"{SCHEMA}.properties.method.enum — this guard's contract anchor is gone")
        return 1

    names = "|".join(re.escape(m) for m in sorted(methods, key=len, reverse=True))
    literal = re.compile(rf"""(["'])({names})\1""")

    errors: list[str] = []
    files = backend_tree.main_kotlin()
    for path in files:
        if path == CLASSIFICATION:
            continue
        rel = path.relative_to(ROOT)
        for lineno, line in enumerate(path.read_text().splitlines(), start=1):
            for hit in literal.finditer(line):
                errors.append(
                    f"{rel}:{lineno} spells the wrap method {hit.group(0)} — reach it through "
                    f"`WrapMethod` ({CLASSIFICATION.relative_to(ROOT)}). A local list of methods is how password "
                    f"change and reset came to delete a blacklist while rotation kept a whitelist (inbox #172)")

    if errors:
        print("WRAP METHOD VOCABULARY VALIDATION FAILED:")
        for e in errors:
            print("  -", e)
        return 1

    print(f"wrap method vocabulary OK: {len(files)} backend main file(s) spell none of the contract's "
          f"{SCHEMA}.method values ({len(methods)}) outside {CLASSIFICATION.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
