#!/usr/bin/env python3
"""Guard that the device-linking wire never grows a field that names a server — or anything else.

**What it asserts.** In `contracts/api/auth.openapi.yaml`, the seven `pairing` schemas carry exactly these
property sets, exact in both directions:

  - `PairingOpenRequest{handle}`, `PairingCollectRequest{handle}`, `PairingDelegateRequest{handle}` — the
    routing handle and nothing else.
  - `PairingAnswerRequest{handle, answer}` and `PairingSealRequest{handle, envelope}` — the handle plus the
    one opaque value that step relays.
  - `PairingDelegateResult{refreshToken}` — the delegated family's token, handed to the trusted device only.
  - `PairingChannel{answer, envelope}` — what the single read returns, and deliberately no status.

A missing schema is a **failure, not a pass**: the guard must not go quiet because the thing it watches was
renamed away from under it.

**The rule behind it.** ADR 0059 §4 — *the QR never carries a server address, and no client ever follows one
out of a scan* — is the one rule of that ADR *"a reviewer should look for by name"*. veyq is self-hostable, so
"scan this and your new device is configured" is the convenience someone will reach for, and a code that could
name an endpoint would let an attacker's QR redirect a trusted device's **seal** to a server they control: no
password, no session, nothing else needed. The field that does it is small and reads as a courtesy —
`serverUrl`, `instanceUrl`, `apiBase` on the open or answer request, *"so the new device knows where to talk"*.
Everything keeps working; the pairing simply becomes redirectable, and no test would notice.

The exact-set form guards the second half too, which is why it is a property allowlist rather than a name
pattern: `PairingChannel` must not grow a `status`, no request may grow a `userId` or an `email` (the device
being onboarded names no account — ADR 0059 §3), and `PairingDelegateResult` must not grow anything the
onboarded device could be handed outside the sealed grant.

**The defect that bought it.** No incident — written at the moment the temptation goes live rather than after
it is taken, the same posture as `validate_device_shapes.py` (the built precedent, one flow over) and
`validate_session_family_shape.py`. `docs/coverage/features/account/use-cases/uc.account.onboard-device.yaml`
recorded `no-code-ever-names-a-server` as owed to a guard *"the same shape as `validate_device_shapes.py`, one
schema over"*; this is that guard, landed with the schemas it pins (cr-device-pairing-channel-and-session-
delegation phase 1). Before it, the rule existed in prose in ADR 0059 §4, in the use-case's § Quality notes and
in the CR's invariant list — three documents agreeing is still prose.

**A predicate reached for first, and wrong — recorded so it is not reached for again.** The tempting
*file-scoped* shape *"no property whose name matches `url|uri|host|endpoint|server|origin|instance` anywhere in
`contracts/api/*.openapi.yaml`"* is **red on arrival: 8 hits on the untouched tree** —
`auth.openapi.yaml:LoginChallenge.instanceSalt`, `setup.openapi.yaml:SetupStatus.instanceSalt`,
`media.openapi.yaml:PresignedUrl.url` and `MediaRequestItem.originalUploadId`, their two
`sharing.openapi.yaml` twins, and `tenants.openapi.yaml:TenantMailSettings{,Update}.host` — every one of them
legitimate. A guard that is red before its first injection makes every later red unreadable, since
red-because-broken and red-because-wrong look identical. Only the schema-scoped allowlist below is green on the
tree as it stands. This is the same trap `validate_device_shapes.py` records in its own docstring, met a second
time.

**Its limits.**
  - This is a *contract* sensor over *seven schemas*. It cannot see a server address smuggled through a **path
    or query parameter**, a **header**, an error `message`'s free text, or a **DB column with no contract
    field** at all. It also says nothing about the other schemas in this or any other spec.
  - It says nothing about the **contents** of `handle`, `answer` or `envelope`. Those are opaque by
    construction and a client could encode a URL inside one; what stops that is the client, not this guard —
    and ADR 0026 is why no server-side check could be the barrier anyway.
  - It does not observe the **backend**: an endpoint that returns a server address in a body the contract does
    not describe would pass here. That is what `PairingSliceTest` pins, over the real HTTP stack.

Green means exactly: **the five pairing requests, the delegation result and the channel read still carry the
handle, the two opaque relayed values and the delegated token — and nothing that could name a server.**
"""
from __future__ import annotations

import pathlib
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
SPEC = ROOT / "contracts" / "api" / "auth.openapi.yaml"

# schema name → the exact property set it may carry, and why that set is closed.
EXPECTED = {
    # The routing handle alone. No account (the onboarding device has none to give — ADR 0059 §3) and, above
    # all, nothing URL-shaped: a field naming a server here is §4's attack, written as a courtesy.
    "PairingOpenRequest": {"handle"},
    "PairingCollectRequest": {"handle"},
    "PairingDelegateRequest": {"handle"},
    # The handle plus the one opaque value that step relays. `answer` is an ephemeral transport key in one
    # direction and that key plus a code commitment in the other, and the server must not be able to tell the
    # two forms apart (§6). Only the property SET is pinned here — the value's shape is the client's.
    "PairingAnswerRequest": {"handle", "answer"},
    "PairingSealRequest": {"handle", "envelope"},
    # The delegated family's token, returned to the TRUSTED device only; it reaches the onboarded one solely
    # inside the sealed grant.
    "PairingDelegateResult": {"refreshToken"},
    # The single read, used by both sides. No status field: presence is the whole state either side needs, and
    # a status is one more fact readable off a relay that is supposed to know nothing.
    "PairingChannel": {"answer", "envelope"},
}

WHY = (
    "The pairing wire relays a handle and two opaque values and nothing else. No field here may name a "
    "server (ADR 0059 §4 — an endpoint out of a scan redirects the trusted device's seal), name an account "
    "(§3), or report the channel's state."
)


def main() -> int:
    if not SPEC.exists():
        print(f"PAIRING SHAPES VALIDATION FAILED:\n  - missing spec: {SPEC}")
        return 1

    spec = yaml.safe_load(SPEC.read_text(encoding="utf-8"))
    schemas = (spec.get("components") or {}).get("schemas") or {}

    errors: list[str] = []

    for name, allowed in sorted(EXPECTED.items()):
        schema = schemas.get(name)
        if not isinstance(schema, dict):
            errors.append(
                f"components.schemas.{name} is missing from auth.openapi.yaml. If it was renamed, point this "
                f"guard at the new name — do not delete the guard."
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
        print("PAIRING SHAPES VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1

    shapes = "; ".join(f"{n} = {sorted(p)}" for n, p in sorted(EXPECTED.items()))
    print(f"OK: {shapes}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
