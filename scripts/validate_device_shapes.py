#!/usr/bin/env python3
"""Guard that the device register and the Master Key wrap never grow a cleartext device↔wrap correlation.

**What it asserts.** In `contracts/api/account.openapi.yaml`:

  - `components.schemas.DeviceRegister` and `components.schemas.DeviceRegisterUpdate` carry exactly
    `{blob, version}` — one opaque ciphertext and the monotonic optimistic-concurrency counter, nothing else.
  - `components.schemas.MasterKeyWrap` carries exactly `{id, method, wrappedKey, kdfParams}` — a handle, which
    method opens it, the ciphertext and the opaque KDF params, nothing else.

A missing schema is a **failure, not a pass**: the guard must not go quiet because the thing it watches was
renamed away from under it.

**The rule behind it.** The device register joins a device to its session families *and* to its convenience
wrap ([en.account.device-register](../docs/features/account/enablers/en.account.device-register.md)), and both
joins live **inside** the sealed blob. The server relays one ciphertext per account and learns no device name,
no device count and — the one that matters — no device↔family and no device↔wrap link. A blind operator that
learned the wrap link would newly cluster N anonymous wraps into per-device groups, which is exactly what
ADR 0004's unnamed families and ADR 0005's per-device wraps exist to prevent.

The shortcut that breaks it is small, plausible and invisible to every test: a cleartext `wrapIds` list on the
register's wire shape, or a `deviceId` / `createdAt` / `label` on `MasterKeyWrap`, *"so the server can prune or
count"*. Everything keeps working; the correlation is simply handed over. `ADR 0058`'s closing paragraph names
that temptation and says explicitly it is *"a shape obligation on the contract, owed to a guard rather than to
prose"* — this is that guard, built **before** the code that feels the temptation (cr-device-wrap-join-and-
remote-cutoff phase 1).

**The defect that bought it.** No incident: like `validate_session_family_shape.py` (the built precedent, one
schema over) it is written at the moment the temptation goes live rather than after it is taken. The rule
existed in prose in three places — ADR 0058, the enabler's § Quality notes, and the CR's own invariant list —
and nothing enforced it. Prose that three documents agree on is still prose.

**A predicate reached for first, and wrong — recorded so it is not reached for again.** The *file-scoped*
shape *"no non-base64 cleartext property in `account.openapi.yaml`"* is **red on arrival**: a parse of the
untouched tree returns **14** such properties, among them `MasterKeyWrap.id`, `AddWrapResult.id` and
`IdentityPins.version`, all of which are legitimate. Only the schema-scoped allowlists below are green on the
tree as it stands, which is what makes a later red mean *"someone added a field"* rather than *"this guard was
always red"*.

**Its limits.**
  - This is a *contract* sensor over *three schemas*. It says nothing about any other schema, and it cannot see
    a correlation smuggled through a path or query parameter, a header, an error `message`, or a DB column with
    no contract field at all.
  - It says **nothing about the blob's plaintext**. What is inside the register is ciphertext by construction —
    that is the seal's job (`symWrap(masterKey, blob, "device-register")`), and no contract guard can or should
    look there.
  - It does **not** replace `DeviceRegisterSliceTest.theRegisterCarriesNothingButTheSealedBlobAndItsVersion`.
    That test pins the response *instance* the backend actually serves; this pins the *contract*, including the
    `PUT` request body (`DeviceRegisterUpdate`) the test never sees, and the wrap schema no slice test covers.

Green means exactly: **the register's two wire shapes still carry only a blob and a version, and a wrap still
carries only its handle, method and ciphertext.**
"""
from __future__ import annotations

import pathlib
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
SPEC = ROOT / "contracts" / "api" / "account.openapi.yaml"

# schema name → the exact property set it may carry, and why that set is closed.
EXPECTED = {
    # One opaque ciphertext the server relays verbatim + the counter it compares. Anything else here is a fact
    # about the account's devices the operator did not have before.
    "DeviceRegister": {"blob", "version"},
    "DeviceRegisterUpdate": {"blob", "version"},
    # A handle, which method opens it, the ciphertext, and the opaque KDF params. A `deviceId`, `label` or
    # `createdAt` here would join the wrap to its device server-side — the correlation the sealed register
    # exists to keep client-only.
    "MasterKeyWrap": {"id", "method", "wrappedKey", "kdfParams"},
}

WHY = (
    "The device↔wrap and device↔family joins live INSIDE the Master-Key-sealed register "
    "(en.account.device-register); a cleartext field here hands a blind operator the correlation while "
    "everything keeps working, which no test would catch (ADR 0058)."
)


def main() -> int:
    if not SPEC.exists():
        print(f"DEVICE SHAPES VALIDATION FAILED:\n  - missing spec: {SPEC}")
        return 1

    spec = yaml.safe_load(SPEC.read_text(encoding="utf-8"))
    schemas = (spec.get("components") or {}).get("schemas") or {}

    errors: list[str] = []

    for name, allowed in sorted(EXPECTED.items()):
        schema = schemas.get(name)
        if not isinstance(schema, dict):
            errors.append(
                f"components.schemas.{name} is missing from account.openapi.yaml. If it was renamed, point "
                f"this guard at the new name — do not delete the guard."
            )
            continue

        props = set(schema.get("properties") or {})
        for extra in sorted(props - allowed):
            errors.append(f"{name}.{extra}: may carry only {sorted(allowed)}. {WHY}")
        for missing in sorted(allowed - props):
            errors.append(
                f"{name}.{missing} is gone. This guard pins an EXACT set, so a removal is a red too — "
                f"update the expected set here deliberately if the shape really changed."
            )

    if errors:
        print("DEVICE SHAPES VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1

    shapes = "; ".join(f"{n} = {sorted(p)}" for n, p in sorted(EXPECTED.items()))
    print(f"OK: {shapes}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
