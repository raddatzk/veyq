# ADR 0059 — Device linking: the grant carries key *and* session, and each direction earns its own binding step

- Status: Accepted
- Date: 2026-08-12

## Context

[ADR 0005](0005-unlock-methods-and-device-trust.md) reserved cross-device onboarding as its own use-case and
ADR, and named three constraints it would have to carry: E2E unlock-transfer, QR-login phishing, and
non-enumerability. This is that ADR. The crypto it needs is almost entirely built — one Master Key per account
([en.account.key-model](../features/account/enablers/en.account.key-model.md)), `asymWrap` to a foreign public
bundle, the `DEVICE_KEY` wrap envelope, a QR decoder — so what had to be decided was never the sealing. It was
the ceremony around it.

**Linking is the only mechanism in veyq that converts an auth-layer fact into an unlock-layer one.** Everything
else holds the two apart on purpose: a valid session fetches ciphertext and never opens it
([ADR 0003](0003-operator-blind-password-auth.md)), and a revoke takes server access and never key material
([ADR 0058](0058-revoke-never-takes-key-material.md)). Linking hands a device the Master Key on the strength of
one human confirmation. That single fact sets everything below.

**And the reason this feature was sequenced behind the session manager needs correcting, because the corrected
version is what sets the ceremony's floor.** It was deferred so that a phished grant could be taken back. It
cannot be. A revoke cuts the granted device off the server; the Master Key it already holds stays, and the
account's identity private key travels with it, so a phished grant is an identity takeover and not merely a
read. The cure is master-key **and** identity rotation
([ADR 0060](0060-key-rotation-after-compromise.md)) — and it is a cure in one direction only: it **ends** the
compromise, it does not undo it, because what was already read stays read. So what the session manager
actually bought this feature is **containment and discovery**, and what rotation adds is **an end date**;
neither is reversal. The grant is visible, nameable and severable. Ship no copy that implies otherwise.

## Decision

**1. Two directions, both built, each with its own name and its own entry point.** They answer different
questions and must not be collapsed into one screen:

| | **Add a new device** | **Sign in with a QR code** |
|---|---|---|
| Premise | you are at a device you are signed in on | you are at the new device and want in |
| Who displays / who scans | trusted displays, new scans | new displays, trusted scans |
| Entry point | settings, on the unlocked device | the sign-in form, on the locked device |
| Binding step | **compare** a code (§5) | **transcribe** a code (§5) |

Each device offers both *show a code* and *scan a code*, so whichever device has a camera is the one that
scans. That is the whole answer to the hardware question — no direction is the "camera" one.

**2. The grant carries the Master Key *and* a session; no password is typed anywhere.** veyq's auth keypair is
derived from the password ([ADR 0003](0003-operator-blind-password-auth.md)), so a device that never learns the
password cannot obtain a session by itself — a grant of the key alone would leave it holding a vault it cannot
reach a server with. **An unlocked session may therefore mint one further session family for its own account**,
and hand that family's refresh token to the device it is granting. This is a second origin for a family beside
the login ([ADR 0004](0004-session-tokens.md)); the family is ordinary in every other respect — it is listed,
it is revocable, it rotates.

**And the granted device keeps that family the way every other device keeps one.** The refresh token travels in
the envelope because there is no other way into a device that holds no cookie — but it is not what that device
holds afterwards. On arrival it spends the token once against [ADR 0004](0004-session-tokens.md)'s adoption
verb, which rotates it and returns the successor in the ordinary httpOnly cookie; the handed-over value is dead
before the ceremony ends. Two orderings follow, and both are load-bearing rather than stylistic: the device
**adopts before it unlocks, registers or enrols**, because that whole tail runs on an ordinary authenticated
session; and the *"stay signed in"* question is answered **on the new device, before its session exists**,
because the lifetime is fixed at adoption and no later refresh can move it. A grant whose token were simply
kept in script would satisfy every visible step of this ceremony and lose the session on the next page load.

**3. One envelope, sealed to the new device's ephemeral transport key.** The new device mints a single-use
keypair; the trusted device `asymWrap`s **both** the Master Key and the refresh token to its public key and
posts the result. **Pickup is unauthenticated on purpose**: the envelope is worthless without the ephemeral
private key, so gating the fetch would add a check that proves nothing and would force the new device to
identify an account before it has one. What the envelope *is* authenticated by is the channel key of §11 — a
property of the envelope, not of the fetch. Nothing in the request names a user, so there is no oracle to build
([ADR 0003](0003-operator-blind-password-auth.md) non-enumerability); the pairing key's entropy is what makes
the row unfindable.

**4. The QR never carries a server address, and no client ever follows one out of a scan.** Each side talks
only to the instance it is already pointed at. A code that could name an endpoint would let an attacker's QR
redirect a trusted device's *seal* to a server they control — no password, no session, nothing else needed —
and because veyq is self-hostable, "scan this and your new device is configured" is the convenience someone
will reach for. This is the one rule of this ADR that a reviewer should look for by name.

**5. Each direction's binding step, and the rule underneath.** Neither is decoration; each is what proves the
device being granted is one the user is holding.

- **Add a new device — compare.** After the new device answers, it shows a short code and the trusted device
  shows the same one: *"your new device shows 4821 — correct?"* Without it, anyone who can see the displayed
  QR (a screen share, a photo, a person in the room) can answer faster than the real device and be approved by
  a user who cannot tell the difference. Comparing works **here** because both screens are in front of the
  user.
- **Sign in with a QR code — transcribe, in the reverse direction.** After the scan the trusted device shows a
  code that must be typed **into the new device**, and it seals only once the new device has proven it knows
  that code. Comparing would be worthless here: the attacker authors the QR, therefore the key, therefore any
  code derived from it, and their page displays the matching digits before the victim ever scans. What the
  attacker cannot do is read a code off the victim's own screen.
- **The general rule, which is what carries to the next reader: comparing a code is theatre unless you already
  know the other screen is yours. Where you do not, the code must travel in the direction the attacker does
  not control.**

**6. The proof of the transcribed code gates the sealing, never the release.** The new device proves the code
together with its public key, under the channel key (§11); the trusted device checks that proof against the code
it displayed and only then seals. There is exactly **one** attempt, and that is structural rather than a policy: the proof
travels in the channel's single relayed answer, that slot is write-once, and the channel carries no status — so
nothing can tell the new device its proof was refused, and a retry would need a field this relay must not have.
The transcription is therefore editable on the new device until it is sent; a sent wrong proof abandons the
pairing, and retrying is a fresh ceremony. That is the shape
[uc.account.onboard-device](../features/account/use-cases/uc.account.onboard-device.md) E2 already required of
the other direction's mismatch, for the same reason: offering "try again" in place invites the user to grant
past the one signal they have.

The two tempting alternatives are both wrong: letting the **server** release the envelope on a correct code
puts a malicious operator past the only barrier ([ADR 0026](0026-client-trust-tiers.md)), and mixing the code
into the envelope's encryption hands the holder of the ephemeral private key — the attacker, in the case this
defends — a six-digit offline search.

**7. Both confirmations are a deliberate gesture, not a tap, and they name the consequence.** A slide rather
than a button, the device's name in the sentence, and the truth § Context establishes: signing this device out
later will not take the key back. The reflex is the attack; the copy and the gesture are what stand against it.

**8. Short-lived, single-use, one outstanding pairing per account.** The shape is
`AccountRecoveryEntity`'s — an opaque row with an expiry, a terminal status, enforcement at read *and* a
scheduled sweep — not [ADR 0021](0021-operator-blind-background-tasks.md)'s job queue, whose routing is
scope-membership-based and therefore cannot address a device that has no membership yet.

**9. The trusted device *suggests* the label; the new device *writes* it.** The name the user types during the
ceremony travels inside the sealed envelope and is written to the register by the device it names, on that
device's ordinary post-unlock write. This keeps
[en.account.device-register](../features/account/enablers/en.account.device-register.md)'s single-writer rule
intact and adds **no fourth write moment** — do not add one.

**10. A linked device enrols a `DEVICE_KEY` wrap, never a `DEVICE` one.** A `DEVICE` wrap takes the password,
which this device has never seen ([ADR 0005](0005-unlock-methods-and-device-trust.md) §9, §17–21). It is
therefore born at the password-less tier, and the consequence belongs in the copy: if it ever loses that saved
key — or another device removes it via [ADR 0058](0058-revoke-never-takes-key-material.md)'s escalation — it
needs the password or the Emergency Kit, neither of which was ever typed on it.

**11. The QR carries a secret the relay never sees, and that secret authenticates both messages the relay
carries.** The code's payload is 32 random bytes, never the routing handle itself. Both devices derive the handle
and a **channel key** from it (two HKDF labels); the server only ever receives the handle. The answer carries a
MAC under the channel key over the transport key — and, when *signing in with a QR code*, over the transcribed
code too — and the envelope carries one over itself. The trusted device seals only to an answer whose MAC
verifies; the new device opens only an envelope whose MAC verifies.

- **Why.** Without it the relay is the one party that sees both halves of each binding step. When *signing in
  with a QR code*, the proof `sha256(domain ‖ key ‖ code)` crosses the relay in the clear, so the relay tries all
  10⁶ codes offline, learns the code, and proves it for a key of its own. When *adding a new device*, the
  compared number is five digits of one hash over a bundle whose Ed25519 half the wrap never uses, so the relay
  varies that half until its own bundle shows the same number — about 10⁵ hashes, well under a second. Either
  way the trusted device seals the Master Key to the relay.
- **The codes of §5 and the channel key defend against different parties, and neither replaces the other.**
  The codes stop someone who is not the relay (a photographed QR, a phisher's QR); the channel key stops the
  relay.
- **The envelope's MAC matters beyond its seal.** Anyone can seal to the new device's public key, so an
  unauthenticated envelope would let the relay hand the device a session and a Master Key of its own making —
  an account that looks like the user's and takes their next uploads. The envelope also carries the linking
  device's trust-state marks ([ADR 0028](0028-identity-authenticity-tofu-verification.md) Decision 7), which only
  an authenticated envelope may set.
- **Unchanged:** the QR stays the same size and still names no server (§4), and a handle derived from a random
  secret is as unfindable as a drawn one.
- **What it does not cover:** an attacker who both reads the QR and runs the relay. They hold the channel key,
  so they grind the transcribed code or the compared number again.

## Consequences

**What it buys.** The E2EE-native answer to "sign in on my other device": one flow that solves auth *and*
unlock, with the Secret Key kept for the case it exists for. It also delivers recovery path #1 ("another
enrolled device still holds the keys") — the Recovery topic should consume this rather than reinvent it.

**What it costs.** Session delegation is a genuinely new server capability, and it is account-scoped: an
unlocked session can mint a session. That adds no class of exposure (an unlocked session already holds
everything) but it is new surface, and both pairing endpoints are unauthenticated, so each owes an explicit
rate-limit posture rather than the wraps resource's exemption, whose reasoning does not transfer.

**The obligation this feature created, and what discharging it does not buy.** A phished grant was
unrecoverable until [ADR 0060](0060-key-rotation-after-compromise.md); with rotation it becomes *stoppable*.
It never becomes reversible, and rotation depends on the **Secret Key** — which a device linked here has never
seen (§10), and which the account holder may not have to hand. So §5–§7 and §11 carry the same weight after
rotation exists as before: they are the only barrier that acts *before* the key leaves.

**Rejected alternatives.**

- **A matched code as the anti-phishing measure in the QR-sign-in direction** — the shape the scoping for this
  feature originally proposed. It fails for the reason in §5: the attacker owns both sides of the comparison.
  Kept as *compare* only in the direction where the user holds both screens.
- **Requiring the password on the new device first** (pickup gated on a session). It works, and it gives two
  independent barriers instead of one. Rejected because it makes "sign in with a QR code" begin with typing a
  password — and because the transcribed code is the stronger of the two barriers: a password can leak in a
  breach and be used silently, while the code requires the attacker to reach the user live. Re-adding it is a
  cheap hardening if the tradeoff ever looks different; removing §5's code is not.
- **Server-side release of the envelope on a correct code**, and **mixing the code into the envelope's
  encryption** — both defeated in §6.
- **A PAKE (CPace, SPAKE2) over the transcribed code, in place of §11.** It would close the QR-sign-in direction
  even against an attacker who also reads the QR — and it closes nothing in the *add a device* direction, where
  no code is typed and the compared number is what the relay grinds. It also needs a construction the suite does
  not have and no maintained library ships, which is home-grown crypto by
  [ADR 0017](0017-crypto-primitives-and-post-quantum-strategy.md) rule 1. The QR secret closes both directions
  with HKDF and HMAC, already pinned.
- **Commit-then-reveal (a short-authentication-string protocol).** That is the answer when no secret channel
  exists and only a few digits can be compared. Here the QR is such a channel, and it carries a full-strength
  key rather than a few digits.
- **[ADR 0021](0021-operator-blind-background-tasks.md)'s job queue as the transport** — §8.
- **True push, or waking a trusted device to ask it for approval.** Not needed: in both directions the user
  picks up the second device themselves. It is also the shape worth *not* building — a remotely triggerable
  approval prompt on an unlocked device is the confirmation-fatigue surface, and the two directions here
  deliberately have no inbound prompt at all.
- **A partial grant** (link a device to some scopes only). The key model has one Master Key and one wrap per
  device; a partial grant would be a different key model, not a smaller ceremony.

## Related

- [uc.account.onboard-device](../features/account/use-cases/uc.account.onboard-device.md) — the flow this ADR
  governs
- [uc.account.sessions](../features/account/use-cases/uc.account.sessions.md) — where a linked device becomes
  visible, nameable and severable; its **E3** is the entry point the QR-sign-in direction lives on
- [ADR 0004](0004-session-tokens.md) (families, and the second origin §2 adds),
  [ADR 0005](0005-unlock-methods-and-device-trust.md) (the wraps, and the reservation this fills),
  [ADR 0028](0028-identity-authenticity-tofu-verification.md) (the safety-number digest reused for the codes),
  [ADR 0060](0060-key-rotation-after-compromise.md) (the rotation §Context leans on),
  [ADR 0058](0058-revoke-never-takes-key-material.md) (why a revoke cannot undo a grant), [ADR 0026](0026-client-trust-tiers.md) (why §6 refuses a server-side gate)
