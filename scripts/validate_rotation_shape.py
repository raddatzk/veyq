#!/usr/bin/env python3
"""Guard that key rotation keeps exactly one mode — the request cannot ask for less (ADR 0060 Decision 1).

**What it asserts.** In `contracts/api/account.openapi.yaml`:

  1. `components.schemas.RotateAccountKeysRequest` has exactly the property set
     {identityPublicKey, wrappedIdentityPrivateKey, passwordWrap, recoveryWrap, secretKeyProofSignature}.
  2. Its `required` list equals that same set — **every** property is mandatory. An optional field is a
     discriminator spelled by omission: leave `recoveryWrap` out and you have asked for a rotation that
     rewrites one cold wrap and leaves the other holding the old Master Key, which is identity-only rotation
     wearing a different hat.
  3. `passwordWrap` and `recoveryWrap` are `$ref`s to `RotatedWrap`, and `RotatedWrap` has exactly
     {wrappedKey, kdfParams} — no `method`. Which cold wrap is being rewritten is the *field it arrives in*,
     never a value inside it, so a `wraps: [{method, …}]` array cannot come back one level down and carry a
     subset again.

ADR 0060 Decision 1 is that the Master Key and the identity keypair rotate **together, always**: every path to
the identity private key runs through the Master Key, so an identity compromise implies a Master-Key
compromise, and a new identity re-wrapped under a leaked Master Key is still readable — ADR 0028's
rejected-alternative (d) with a button in front of it. The decision is a **negative**, and the shortcut that
undoes a negative is additive: a `mode`, an `identityOnly`, a `scopes` subset, an optional field. Every one of
those would leave the whole suite green while the useless half became selectable again.

**The defect that bought it.** No incident — the endpoint is born with this guard. It is built with
cr-account-key-rotation-after-compromise because ADR 0060 Decision 1 is a negative that nothing else in the
repo can hold: a test asserts what the code *does*, and no test fails because a request grew a field nobody
sends yet. The prose lives in the ADR, the use-case and the operation's own description, and prose that three
documents agree on is still prose.

**Its limit.** This is a *contract* sensor over *two schemas in one file*, and it checks the **request shape**,
nothing else. A green run does **not** prove that rotation rotates both halves: the handler could ignore a
field, the client could send the old Master Key re-wrapped, and this would still pass. It does not look at the
response, at path/query parameters, at headers, or inside the opaque base64 fields — a mode smuggled into a
ciphertext blob is invisible here and always will be. Of the cold-credential gate (ADR 0060 Decision 2) it pins
only that `secretKeyProofSignature` is present and required; whether the server *verifies* it is the backend's
bite tests' job, and the password half is client-side and has no contract surface at all. Green means exactly:
**the rotation request still has these five mandatory fields and no sixth, and its wraps still carry no
method.**

Two narrower predicates were reached for first and are wrong; recorded so the next reader does not re-derive
them (§ *run the predicate against the untouched tree*, `changes/README.md`):

  - **"No property named mode/kind/type/variant anywhere in `contracts/api/*.openapi.yaml`."** Red on arrival:
    nine legitimate `kind` properties across `content`, `media`, `scopes` and `sharing` name a node's,
    a derivative's or a scope's *type*, which is not a mode selector at all.
  - **"No discriminator-ish property anywhere in `account.openapi.yaml`."** The file-scoped shape is the
    easiest to express and it is also wrong: `MasterKeyWrap.method` and `AddWrapRequest.method` live in that
    very file and are correct there — a wrap genuinely has a method. The predicate that holds is
    schema-scoped, which is why this guard names two schemas and enumerates their properties rather than
    banning a vocabulary.
"""
from __future__ import annotations

import pathlib
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
SPEC = ROOT / "contracts" / "api" / "account.openapi.yaml"

REQUEST = "RotateAccountKeysRequest"
WRAP = "RotatedWrap"

# ADR 0060 Decision 1 + § Consequences: the new identity, the private key under the new Master Key, both cold
# wraps rewritten, and the Secret-Key proof over all of it (Decision 2). Nothing optional, nothing selectable.
REQUEST_PROPS = {
    "identityPublicKey",
    "wrappedIdentityPrivateKey",
    "passwordWrap",
    "recoveryWrap",
    "secretKeyProofSignature",
}
WRAP_PROPS = {"wrappedKey", "kdfParams"}
WRAP_REFS = ("passwordWrap", "recoveryWrap")

# Spellings a mode would plausibly arrive under. Not the enforcement — the property-set pin above is — but a
# named hit earns a message that says *why* rather than "unexpected field".
DISCRIMINATOR_SPELLINGS = {
    "mode", "identityonly", "identity_only", "masterkeyonly", "master_key_only",
    "scopes", "scopeids", "scope", "only", "variant", "kind", "type", "partial", "method",
}


def schema_of(spec: dict, name: str) -> dict | None:
    node = spec.get("components", {}).get("schemas", {}).get(name)
    return node if isinstance(node, dict) else None


def main() -> int:
    errors: list[str] = []

    if not SPEC.exists():
        print(f"ROTATION SHAPE VALIDATION FAILED:\n  - missing spec: {SPEC}")
        return 1

    spec = yaml.safe_load(SPEC.read_text(encoding="utf-8"))

    request = schema_of(spec, REQUEST)
    wrap = schema_of(spec, WRAP)

    # A missing schema is a failure, not a pass: the guard must not go quiet because what it watches was
    # renamed out from under it.
    for name, node in ((REQUEST, request), (WRAP, wrap)):
        if node is None:
            errors.append(
                f"components.schemas.{name} is missing from account.openapi.yaml. If the rotation request was "
                f"renamed, point this guard at the new name — do not delete the guard; ADR 0060 Decision 1 has "
                f"nothing else holding it."
            )
    if errors:
        print("ROTATION SHAPE VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1

    assert request is not None and wrap is not None  # narrowed by the check above
    props = set(request.get("properties") or {})
    required = set(request.get("required") or [])

    for extra in sorted(props - REQUEST_PROPS):
        if extra.lower() in DISCRIMINATOR_SPELLINGS:
            errors.append(
                f"{REQUEST}.{extra}: this is a mode selector. ADR 0060 Decision 1 retired the conditional — the "
                f"Master Key and the identity keypair rotate together, always, because every path to the "
                f"identity private key runs through the Master Key. A field like this lets identity-only "
                f"rotation return as a flag, and it is the half ADR 0028 already rejected as useless: a new "
                f"identity re-wrapped under a leaked Master Key is still readable."
            )
        else:
            errors.append(
                f"{REQUEST}.{extra}: not part of the pinned rotation request. If the rotation genuinely needs "
                f"to carry more, add it here **and** say in ADR 0060 why it is not a mode in disguise."
            )

    for missing in sorted(REQUEST_PROPS - props):
        errors.append(
            f"{REQUEST}.{missing}: gone. Every one of {sorted(REQUEST_PROPS)} is part of one rotation; dropping "
            f"one leaves an account that cannot be unlocked, recovered, or read by its own contacts."
        )

    for optional in sorted(props & REQUEST_PROPS - required):
        errors.append(
            f"{REQUEST}.{optional}: present but not in `required`. An optional field is a discriminator spelled "
            f"by omission — omitting a cold wrap asks for a rotation that leaves the other one holding the old "
            f"Master Key, which is identity-only rotation under another name."
        )

    for name in WRAP_REFS:
        prop = (request.get("properties") or {}).get(name)
        if isinstance(prop, dict) and prop.get("$ref") != f"#/components/schemas/{WRAP}":
            errors.append(
                f"{REQUEST}.{name}: must be a $ref to {WRAP}. Each cold wrap arrives in its own named field so "
                f"that 'rewrite one and leave the other' is unsayable; an inline shape or a list re-opens it."
            )

    wrap_props = set(wrap.get("properties") or {})
    for extra in sorted(wrap_props - WRAP_PROPS):
        errors.append(
            f"{WRAP}.{extra}: a rewritten cold wrap carries only {sorted(WRAP_PROPS)}. Which wrap this is, is "
            f"the field it arrives in — a `method` here would let one array of wraps describe a subset again, "
            f"one level below the pin above."
        )
    for missing in sorted(WRAP_PROPS - wrap_props):
        errors.append(f"{WRAP}.{missing}: gone. A rewritten wrap needs both its ciphertext and its fresh salt.")

    if errors:
        print("ROTATION SHAPE VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1

    print(
        f"OK: {REQUEST} carries exactly {sorted(REQUEST_PROPS)}, all required, "
        f"and {WRAP} carries {sorted(WRAP_PROPS)} with no method — one rotation mode."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
