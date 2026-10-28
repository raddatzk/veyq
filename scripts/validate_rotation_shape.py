#!/usr/bin/env python3
"""Guard that key rotation keeps exactly one mode — the request cannot ask for less (ADR 0060 Decision 1).

**What it asserts.** In `contracts/api/account.openapi.yaml`:

  1. `components.schemas.RotateAccountKeysRequest` has exactly the property set
     {identityPublicKey, wrappedIdentityPrivateKey, passwordWrap, recoveryWrap, secretKeyProofSignature,
     identityPins, deviceRegister, keyChainStep, stageId, stagedGrantsDigest, unreadableScopeIds, authPublicKey,
     attestationSignature, secretKeyProofPublicKey, secretKeyProofPossession}.
  2. Its `required` list equals that same set — **every** property is mandatory. An optional field is a
     discriminator spelled by omission: leave `recoveryWrap` out and you have asked for a rotation that
     rewrites one cold wrap and leaves the other holding the old Master Key, which is identity-only rotation
     wearing a different hat. The eight fields securing the account added (cr-secure-the-account, ADR 0111
     decisions 6-7), and why none of them is a mode:
       - `keyChainStep` — the step's two signatures. Every rotation is a step of the key chain; without it no
         contact's client can reach the new identity, so "rotate without a step" would be a rotation every member
         reads as an anomaly.
       - `stageId`, `stagedGrantsDigest` — name the one stage of self-grants the publish applies and bind it into the
         Secret Key's proof. Neither selects anything: the server applies the stage only if it covers its OWN set of
         the account's grant-carrying memberships (`409 rotation_plan_changed`), so a smaller stage is refused, not
         a lesser rotation.
       - `unreadableScopeIds` — the scopes this device cannot open, declared so the completeness check can tell
         "left out on purpose" from "forgotten" (`changes/README.md` § A server that insists on completeness). It
         does not narrow what rotates: the identity, the Master Key and the Secret Key rotate whatever it lists,
         and a listed scope only keeps its grant as it is. Required and possibly empty, never absent.
       - `authPublicKey`, `attestationSignature` — the auth key the account keeps and the current auth key's
         signature over it. Keeping the password derives the same key and the attestation still verifies, so both
         are always sent: "the password stays" is a value, not an omission (ADR 0060 Decision 1).
       - `secretKeyProofPublicKey`, `secretKeyProofPossession` — the new Secret Key's proof key and the new key's
         signature over the rotation statement. The Secret Key always rotates (ADR 0111 decision 6, inbox #559);
         leaving them out would be "keep the Secret Key", the variant that left a leaked Secret Key's holder
         indistinguishable from the member.
  3. `passwordWrap` and `recoveryWrap` are `$ref`s to `RotatedWrap`, and `RotatedWrap` has exactly
     {wrappedKey, kdfParams} — no `method`. Which cold wrap is being rewritten is the *field it arrives in*,
     never a value inside it, so a `wraps: [{method, …}]` array cannot come back one level down and carry a
     subset again.
  4. `identityPins` and `deviceRegister` are `$ref`s to the documents' own writes, `IdentityPinsWrite` /
     `DeviceRegisterUpdate`. The rotation re-seals both inside its one transaction (ADR 0060 § Consequences):
     re-sealed after the publish, a failed re-seal left a document under a key that no longer exists, which every
     device then refuses (ADR 0028 Decision 7). Required like the rest — "this rotation leaves the pin store
     behind" is the omission this guard exists to make unsayable, and an account with no document yet sends it
     empty rather than leaving the field out.
  5. `keyChainStep` is a `$ref` to `KeyChainStepSignatures`, and that schema has exactly {identitySignature,
     proofSignature}. The step's statement is the server's to rebuild from the stored head and the request's own
     `identityPublicKey` and `secretKeyProofPublicKey`; a step object that carried keys, an index or a predecessor of
     its own would be a second way to name what the rotation installs — and a way to name something else.

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

  6. **The whole body has an upper bound, and it fits the server's request limit.** The guard computes the longest
     JSON `RotateAccountKeysRequest` admits from the contract's own caps — every string's `maxLength` (a `uuid`'s 36,
     a `date-time`'s 35), every array's `maxItems`, an integer's 20 characters, `$ref`s resolved, a nullable at its
     longer branch — and fails when a string or array in it carries **no** cap (a list without a cap has no upper
     bound) or when the total exceeds the limit: `quarkus.http.limits.max-body-size` as `application.properties` sets
     it, else the default `RequestTooLargeRoutes` reads (10240K = 10,485,760 bytes). It assumes no value needs a JSON
     escape, which holds for what the request carries (base64, hex, uuids, integers). The rotation re-seals the whole
     pin store inside its one body (ADR 0104), so this is where its 25,000-entry cap meets the limit; the self-grants
     moved to a stage because memberships have no cap (ADR 0111 decision 6). The defect that bought it: the slice test
     that claimed this bound kept a hand-written overhead that counted seven fewer fields than the request had grown
     (inbox #569, 2026-10-28) — green by a 3.7 % margin nobody had measured.

**Its limit.** This is a *contract* sensor over *two schemas in one file*, and it checks the **request shape**,
nothing else. A green run does **not** prove that rotation rotates both halves: the handler could ignore a
field, the client could send the old Master Key re-wrapped, and this would still pass. It does not look at the
response, at path/query parameters, at headers, or inside the opaque base64 fields — a mode smuggled into a
ciphertext blob is invisible here and always will be. Of the cold-credential gate (ADR 0060 Decision 2) it pins
only that `secretKeyProofSignature`, `secretKeyProofPossession`, `attestationSignature` and the step's two
signatures are present and required; whether the server *verifies* them, and whether it refuses a stage that does
not cover its own set, is the backend's bite tests' job. Green means exactly: **the rotation request still has
these fifteen mandatory fields and no sixteenth, its wraps still carry no method, its two documents still arrive
as their own update shapes, and its step carries two signatures and nothing else.** That the server writes those
two documents inside the rotation's transaction is `RotationSliceTest`'s to prove, not this guard's.

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
# wraps rewritten, the Secret-Key proof (Decision 2), and the two Master-Key-sealed account documents re-sealed
# under the new key. ADR 0111 decisions 6-7 (cr-secure-the-account): the key-chain step, the stage it applies and
# what this device declares unreadable, the auth key and its attestation, the new Secret Key's proof key and its
# possession signature. Nothing optional, nothing selectable — the docstring says per field why it is not a mode.
REQUEST_PROPS = {
    "identityPublicKey",
    "wrappedIdentityPrivateKey",
    "passwordWrap",
    "recoveryWrap",
    "secretKeyProofSignature",
    "identityPins",
    "deviceRegister",
    "keyChainStep",
    "stageId",
    "stagedGrantsDigest",
    "unreadableScopeIds",
    "authPublicKey",
    "attestationSignature",
    "secretKeyProofPublicKey",
    "secretKeyProofPossession",
}
STEP = "KeyChainStepSignatures"
STEP_PROPS = {"identitySignature", "proofSignature"}
WRAP_PROPS = {"wrappedKey", "kdfParams"}
WRAP_REFS = ("passwordWrap", "recoveryWrap")
# Each document arrives in its own named field, as the shape its own PUT already takes.
DOCUMENT_REFS = {"identityPins": "IdentityPinsWrite", "deviceRegister": "DeviceRegisterUpdate"}

# Spellings a mode would plausibly arrive under. Not the enforcement — the property-set pin above is — but a
# named hit earns a message that says *why* rather than "unexpected field".
DISCRIMINATOR_SPELLINGS = {
    "mode", "identityonly", "identity_only", "masterkeyonly", "master_key_only",
    "scopes", "scopeids", "scope", "only", "variant", "kind", "type", "partial", "method",
}


def schema_of(spec: dict, name: str) -> dict | None:
    node = spec.get("components", {}).get("schemas", {}).get(name)
    return node if isinstance(node, dict) else None


APP_PROPERTIES = ROOT / "src" / "backend" / "core" / "src" / "main" / "resources" / "application.properties"
LIMIT_ROUTES = (
    ROOT / "src" / "backend" / "core" / "src" / "main" / "kotlin" / "me" / "raddatz" / "veyq" / "core" / "http"
    / "RequestTooLargeRoutes.kt"
)
LIMIT_KEY = "quarkus.http.limits.max-body-size"
# What a string of a known format is at most, where the contract gives it no `maxLength`.
FORMAT_LENGTH = {"uuid": 36, "date-time": 35}
INTEGER_LENGTH = 20  # a signed int64: 19 digits and its sign
# Documents the rotation sends whole, by the flag that says so: the server refuses `identityPins` without
# `replaceAll` (`400 invalid_request`, `rotateAccountKeys`), and `IdentityPinsWrite`'s `dependentSchemas` caps a whole
# write's `remove` at 0 — without this, 25,000 removes beside 25,000 puts would count, which no rotation can send.
WHOLE_WRITES = {f"{REQUEST}.identityPins": "replaceAll"}


def memory_size(text: str) -> int | None:
    """Quarkus' MemorySize spelling (`10240K`, `10M`, `512`) in bytes, or None."""
    t = text.strip().upper()
    factor = {"K": 1024, "M": 1024 ** 2, "G": 1024 ** 3}.get(t[-1:], 1)
    digits = t[:-1] if t[-1:] in ("K", "M", "G") else t
    return int(digits) * factor if digits.isdigit() else None


def body_limit() -> tuple[int | None, str]:
    """The server's request limit: the application's setting, else the default the limit's route reads."""
    if APP_PROPERTIES.exists():
        for line in APP_PROPERTIES.read_text(encoding="utf-8").splitlines():
            key, _, value = line.partition("=")
            if key.strip() == LIMIT_KEY:
                return memory_size(value), f"{LIMIT_KEY}={value.strip()} in application.properties"
    if LIMIT_ROUTES.exists():
        text = LIMIT_ROUTES.read_text(encoding="utf-8")
        marker = f'name = "{LIMIT_KEY}", defaultValue = "'
        at = text.find(marker)
        if at >= 0:
            value = text[at + len(marker):].split('"', 1)[0]
            return memory_size(value), f"the default {value} RequestTooLargeRoutes reads"
    return None, "nothing"


def longest_json(spec: dict, node: dict, path: str, unbounded: list[str]) -> int:
    """The longest JSON text `node` admits, from the contract's caps. A string or array with no cap is recorded in
    `unbounded` and counted as nothing."""
    ref = node.get("$ref")
    if isinstance(ref, str):
        target = schema_of(spec, ref.rsplit("/", 1)[-1])
        if target is None:
            unbounded.append(f"{path}: $ref {ref} does not resolve")
            return 0
        return longest_json(spec, target, path, unbounded)
    types = node.get("type")
    kinds = types if isinstance(types, list) else [types]
    longest = 0
    for kind in kinds:
        if kind == "null":
            longest = max(longest, 4)
        elif kind == "boolean":
            longest = max(longest, 5)
        elif kind == "integer":
            longest = max(longest, INTEGER_LENGTH)
        elif kind == "string":
            cap = node.get("maxLength", FORMAT_LENGTH.get(node.get("format")))
            if not isinstance(cap, int):
                unbounded.append(f"{path}: a string with no maxLength")
                continue
            longest = max(longest, cap + 2)
        elif kind == "array":
            cap = node.get("maxItems")
            if not isinstance(cap, int):
                unbounded.append(f"{path}: an array with no maxItems")
                continue
            item = longest_json(spec, node.get("items") or {}, f"{path}[]", unbounded)
            longest = max(longest, 2 + cap * item + max(cap - 1, 0))
        elif kind == "object":
            if node.get("additionalProperties") not in (None, False):
                unbounded.append(f"{path}: an object with additionalProperties")
                continue
            fields = dict(node.get("properties") or {})
            flag = WHOLE_WRITES.get(path)
            if flag:
                # The rotation sends this document whole (`flag` true, else 400), so the schema's own conditional
                # for that case applies: its `then` caps override the general ones.
                then = (((node.get("dependentSchemas") or {}).get(flag) or {}).get("then") or {}).get("properties") or {}
                if not then:
                    unbounded.append(f"{path}: no `dependentSchemas.{flag}.then` caps a whole write")
                for name, caps in then.items():
                    fields[name] = {**(fields.get(name) or {}), **caps}
            inner = sum(
                len(f'"{name}":') + longest_json(spec, sub, f"{path}.{name}", unbounded) for name, sub in fields.items()
            )
            longest = max(longest, 2 + inner + max(len(fields) - 1, 0))
        else:
            unbounded.append(f"{path}: no type this guard can bound ({kind!r})")
    return longest


def main() -> int:
    errors: list[str] = []

    if not SPEC.exists():
        print(f"ROTATION SHAPE VALIDATION FAILED:\n  - missing spec: {SPEC}")
        return 1

    spec = yaml.safe_load(SPEC.read_text(encoding="utf-8"))

    request = schema_of(spec, REQUEST)
    wrap = schema_of(spec, WRAP)
    step = schema_of(spec, STEP)

    # A missing schema is a failure, not a pass: the guard must not go quiet because what it watches was
    # renamed out from under it.
    for name, node in ((REQUEST, request), (WRAP, wrap), (STEP, step)):
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

    assert request is not None and wrap is not None and step is not None  # narrowed by the check above
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

    for name, target in DOCUMENT_REFS.items():
        prop = (request.get("properties") or {}).get(name)
        if isinstance(prop, dict) and prop.get("$ref") != f"#/components/schemas/{target}":
            errors.append(
                f"{REQUEST}.{name}: must be a $ref to {target}, the shape the document's own write takes. A "
                f"rotation re-seals it under the new Master Key inside its one transaction (ADR 0060 "
                f"§ Consequences); an inline or list shape is how a subset of documents comes back."
            )

    step_prop = (request.get("properties") or {}).get("keyChainStep")
    if isinstance(step_prop, dict) and step_prop.get("$ref") != f"#/components/schemas/{STEP}":
        errors.append(
            f"{REQUEST}.keyChainStep: must be a $ref to {STEP}. The step's statement is the server's to rebuild "
            f"from the stored head and the request's own keys; an inline shape is how a step naming other keys "
            f"comes back."
        )
    step_props = set(step.get("properties") or {})
    step_required = set(step.get("required") or [])
    for extra in sorted(step_props - STEP_PROPS):
        errors.append(
            f"{STEP}.{extra}: a step carries only {sorted(STEP_PROPS)}. Its keys are the request's "
            f"`identityPublicKey` and `secretKeyProofPublicKey`, its index and predecessor the stored chain's — a "
            f"field here would be a second way to name what the rotation installs (ADR 0111 decision 7)."
        )
    for missing in sorted(STEP_PROPS - step_props):
        errors.append(
            f"{STEP}.{missing}: gone. A step is signed by the retired identity AND the retired proof key; one "
            f"signature alone is what a stolen device could forge (ADR 0111 decision 7)."
        )
    for optional in sorted(step_props & STEP_PROPS - step_required):
        errors.append(f"{STEP}.{optional}: present but not in `required` — a step with one signature is a forgeable step.")

    wrap_props = set(wrap.get("properties") or {})
    for extra in sorted(wrap_props - WRAP_PROPS):
        errors.append(
            f"{WRAP}.{extra}: a rewritten cold wrap carries only {sorted(WRAP_PROPS)}. Which wrap this is, is "
            f"the field it arrives in — a `method` here would let one array of wraps describe a subset again, "
            f"one level below the pin above."
        )
    for missing in sorted(WRAP_PROPS - wrap_props):
        errors.append(f"{WRAP}.{missing}: gone. A rewritten wrap needs both its ciphertext and its fresh salt.")

    unbounded: list[str] = []
    longest = longest_json(spec, request, REQUEST, unbounded)
    limit, limit_source = body_limit()
    for where in unbounded:
        errors.append(
            f"{where}: the rotation's one body then has no upper bound, so the account that grows past the server's "
            f"limit can no longer be secured (413). Give it a cap, or move what it carries to the stage (ADR 0111 "
            f"decision 6)."
        )
    if limit is None:
        errors.append(f"cannot read the server's request limit ({LIMIT_KEY} in {APP_PROPERTIES}, or {LIMIT_ROUTES})")
    elif not unbounded and longest > limit:
        errors.append(
            f"{REQUEST}: its longest body is {longest:,} bytes, over the server's limit of {limit:,} ({limit_source}). "
            f"A rotation at the caps would be refused with 413; lower a cap, or move what grew to the stage."
        )

    if errors:
        print("ROTATION SHAPE VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1

    print(f"OK: {REQUEST}'s longest body is {longest:,} bytes, under the server's limit of {limit:,} ({limit_source}).")
    print(
        f"OK: {REQUEST} carries exactly {sorted(REQUEST_PROPS)}, all required, "
        f"{WRAP} carries {sorted(WRAP_PROPS)} with no method, "
        f"and {STEP} carries {sorted(STEP_PROPS)}, both required — one rotation mode."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
