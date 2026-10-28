# ADR 0058 — A revoke never takes key material; removing a device's wrap is a named escalation

- Status: Accepted
- Built: yes
- Date: 2026-08-12
- Model: [en.account.key-model](../features/account/enablers/en.account.key-model.md)

## Context

Ending a session and removing a device's convenience wrap are two different cuts, and until now nothing decided
how they relate. [ADR 0004](0004-session-tokens.md) decides session families and revocation and does not contain
the word *wrap*. [ADR 0005](0005-unlock-methods-and-device-trust.md) decides the wraps and says nothing about
sessions. The only place the two are coupled is [ADR 0054](0054-password-recovery-two-factor-release.md) §3,
where a password reset deletes **every** `DEVICE` and `DEVICE_KEY` wrap in the same transaction as
`revokeAllForUser` — account-wide, in one exceptional flow, for a reason that generalises: a `DEVICE_KEY` wrap
takes no password, so a device trusted before the reset would keep password-less access afterwards.

The same argument reaches the ordinary case and nobody had drawn it. A revoked device keeps whatever wrap it
enrolled: it can no longer reach the server, but the next time it signs in — which needs only the password it
already had — its `DEVICE` wrap opens the Master Key with **no Secret Key**. So "sign this device out" is a
weaker act than the person clicking it believes, and `uc.account.sessions` already promises the missing half in
its published section (*"if it is truly gone, also remove its saved key"*) without a surface that can do it.

What made that surface impossible was a missing fact, not a missing endpoint. `DELETE /api/account/wraps/{id}`
is account-scoped, every wrap id is served to every device, and session revoke is account-scoped too — but
nothing said **which wrap belongs to which device**. That join is now available
([en.account.device-register](../features/account/enablers/en.account.device-register.md)), so the question is
live: given the ability to delete another device's wrap, when should a revoke do it?

## Decision

**1. A revoke, on its own, never touches key material.** Revoking a session family removes *server access*. It
does not delete a wrap, and it does not reach into the revoked device. This keeps the two layers the product is
built on — auth ≠ unlock ([en.account.key-model](../features/account/enablers/en.account.key-model.md)) —
separate at the one control where fusing them would be most tempting.

**2. Removing the device's wrap is a separate, explicitly named action**, offered beside the revoke and only
where the register can name the device's wrap. It is not a checkbox on the revoke, and not a default: the
consequence belongs in the name of the thing the user clicks, because it is a consequence for a *person on
another device* — that device's legitimate owner must find their Emergency Kit again.

**3. What the escalation buys, stated exactly: it restores the Secret-Key floor for that one device.** This is
ADR 0054 §3 at per-device granularity. After a plain revoke, a stolen laptop needs the password. After the
escalation, it needs the password **and** the Secret Key — which is the whole point of the Emergency Kit
existing. For a `DEVICE_KEY` (remember-me) device the gap is wider still, since that wrap takes no password at
all.

**4. The escalation cuts the session first, then the wrap** — and it accepts a real cost for it. The family
revoke is instant and total against an attacker who is *online now*; the wrap only matters at a *future* sign-in.
So the urgent half must not be blocked by the other, and it runs first even though success there destroys the
handle the second half was offered from (the family row leaves the list). The consequence is not hidden: a
half-succeeded escalation needs a **second surface to retry from**, and that surface is therefore part of the
feature rather than a nicety — see
[uc.account.sessions](../features/account/use-cases/uc.account.sessions.md) § Devices with a saved key.

**5. The client never presents the server's account of which wraps exist as something to act on.** A screen
saying *"these N wraps are recognised by no device — delete them?"* cannot tell a genuine orphan from a device
that simply has not signed in since the join shipped, and it teaches exactly the reflex the operator-blind model
forbids: treating the server's list as authority over the user's own key material. Where the user needs to reach
wraps the register cannot name, the action is defined by **exclusion of what the client can verify locally** —
*"remove every saved key except this device's"* — never by a claim about the others.

## Consequences

- **`uc.account.sessions`' published promise becomes satisfiable and gets corrected.** It currently tells the
  user to remove the saved key "on the device page", which only ever reached *this* device. The escalation is
  what it should have described.
- **`uc.account.remember-this-device`'s claim that the remember-me wrap is *"portable and server-revocable"*
  loses half of itself.** *Server-revocable* becomes true here. *Portable* was never true and is removed: a
  `DEVICE_KEY` wrap is ciphertext that only the device-local key `K` opens, so it travels nowhere.
- **The revoked device is left holding an unusable handle, and that is correct.** Its local key material is
  untouched by anything done from another device — deleting it remotely is not possible and must not be
  simulated. On that device the next boot without a session drops the local tiers on a locally-verifiable fact,
  and a still-live boot finds no matching wrap and *keeps* the local key
  ([uc.account.trust-device](../features/account/use-cases/uc.account.trust-device.md) E1): the anti-withholding
  rule is unchanged by this ADR, and nothing here adds a path where a missing server wrap destroys local key
  material.
- **Rejected — coupling the two by default** (every revoke also deletes the wrap). It reads as safer and is
  worse: the common revoke is housekeeping ("I signed in somewhere I don't use any more"), and silently charging
  it an Emergency Kit on another device teaches people not to revoke. It also collapses a distinction the product
  depends on everywhere else.
- **Rejected — leaving it as it is** (revoke only, as before this ADR). That keeps a control whose name promises
  more than it does, in the one flow — a lost or stolen device — the whole use-case exists for.
- **The operator learns nothing new.** Both halves are calls the account could already make; the join that makes
  the escalation possible lives inside the Master-Key-sealed register and never reaches the server. The
  temptation this creates — a cleartext wrap-id list on the register, or a `deviceId` on the wrap, "so the server
  can prune" — would hand a blind operator the device↔wrap correlation while everything kept working, which no
  test would catch. That is a shape obligation on the contract, owed to a guard rather than to prose.
