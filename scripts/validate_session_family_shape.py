#!/usr/bin/env python3
"""Guard that a session family never grows a name — or any per-family ciphertext (ADR 0004, uc.account.sessions).

**What it asserts.** In `contracts/api/auth.openapi.yaml`, `components.schemas.SessionFamily` has exactly the
property set {id, createdAt, lastUsedAt, current}, and none of those properties is `contentEncoding: base64`.
A family may carry an opaque id, two timestamps and the server-set current marker — and nothing else.

The rule behind it is a *removed mechanism*, not a guarded one. An earlier design stored a client-encrypted
device name on the family; a name blob re-sent unchanged across logins lets a blind operator **re-link a
device's re-logins**, re-joining families that are otherwise unlinkable, so it carried a standing obligation to
re-encrypt with a fresh nonce every single login. ADR 0004 deleted the field instead: with no per-family
ciphertext there is nothing to correlate. Display names live only in the client's Master-Key-sealed device
register (en.account.device-register), which the server relays as one blob per **account**. This guard is what
keeps that deletion from being quietly undone — the schema is where it would come back.

**The defect that bought it.** No incident: the field was removed before it ever shipped. It is built with
cr-account-sealed-device-register because that CR is the moment the temptation is live — it implements naming,
and the family is the shorter road to a name than a separate sealed blob with its own merge discipline. The
rule existed in prose in three places (ADR 0004, uc.account.sessions § Quality notes, and a comment on the
schema itself) and nothing enforced it. Prose that three documents agree on is still prose.

Two adjacent guards were considered and do not cover this:
  - `validate_field_visibility.py` asks *"is this field declared?"* — a `sealedName` that declared a
    visibility class would pass it. This asks *"may the field exist at all?"*.
  - "no base64 field anywhere in auth.openapi.yaml" was the first predicate reached for while designing this
    guard, and it is **wrong**: that spec legitimately carries base64 for the login challenge/response and for
    recovery material, so the guard would have been red on arrival. It was caught by running the predicate
    before writing it down. Recorded here so it is not reached for again.

**Its limit.** This is a *contract* sensor over *one schema*. A green run does NOT prove that no name reaches
the server — it cannot see a name smuggled through a path or query param, an error `message`, a header, or a
DB column with no contract field, and it says nothing about any other schema. Nor does it look inside the
device register's own blob: that is ciphertext by construction, and its contents are the client's business.
Green means exactly: **`SessionFamily` still carries the four plaintext metadata fields and no ciphertext.**
"""
from __future__ import annotations

import pathlib
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
SPEC = ROOT / "contracts" / "api" / "auth.openapi.yaml"

# ADR 0004: an opaque id, two timestamps, and the marker the SERVER sets for the caller's own family.
ALLOWED = {"id", "createdAt", "lastUsedAt", "current"}


def main() -> int:
    errors: list[str] = []

    if not SPEC.exists():
        print(f"SESSION-FAMILY SHAPE VALIDATION FAILED:\n  - missing spec: {SPEC}")
        return 1

    spec = yaml.safe_load(SPEC.read_text(encoding="utf-8"))
    schema = spec.get("components", {}).get("schemas", {}).get("SessionFamily")

    # A missing schema is a failure, not a pass: the guard must not go quiet because the thing it watches was
    # renamed away from under it.
    if not isinstance(schema, dict):
        print(
            "SESSION-FAMILY SHAPE VALIDATION FAILED:\n"
            "  - components.schemas.SessionFamily is missing from auth.openapi.yaml. If a session family was "
            "renamed, point this guard at the new name — do not delete the guard."
        )
        return 1

    props = schema.get("properties") or {}

    for extra in sorted(set(props) - ALLOWED):
        errors.append(
            f"SessionFamily.{extra}: a family may carry only {sorted(ALLOWED)}. A per-family field is exactly "
            f"the mechanism ADR 0004 removed — a display name belongs in the Master-Key-sealed device register "
            f"(en.account.device-register), which is per ACCOUNT and therefore gives the operator nothing to "
            f"link re-logins by."
        )

    for name in sorted(set(props) & ALLOWED):
        if (props[name] or {}).get("contentEncoding") == "base64":
            errors.append(
                f"SessionFamily.{name}: carries contentEncoding: base64. Per-family ciphertext is the "
                f"linkability regression ADR 0004 removed the mechanism for — a blob re-sent unchanged across "
                f"logins re-joins families that are otherwise unlinkable."
            )

    if errors:
        print("SESSION-FAMILY SHAPE VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1

    print(f"OK: SessionFamily carries exactly {sorted(ALLOWED)} and no per-family ciphertext.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
