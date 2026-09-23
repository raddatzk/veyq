# ADR 0060 — Key rotation after a compromise: one mode, a cold-credential gate, re-admission by human review

- Status: Accepted
- Date: 2026-08-15

## Context

[ADR 0059](0059-device-linking.md) shipped device linking and named what it created: a device receives the
Master Key **and** the identity private key on one human confirmation, and a revoke takes neither back
([ADR 0058](0058-revoke-never-takes-key-material.md)). A phished grant is therefore an identity takeover,
containable and visible but **not reversible**. That ADR calls rotation *"the live obligation this feature
creates"*; [ADR 0028](0028-identity-authenticity-tofu-verification.md) Decision 5 deferred the mechanics to
this record and left three things standing: identity rotation is the core, the Master Key rotates **only** if
it was itself exposed, and the other members must rotate every shared `K_SC` to exclude the old identity.

**What forces the decision now is that only the account-side operation is missing.** Everything it drives is
built: the epoch chain and `POST /api/scopes/{scopeId}/epochs` (owner-gated), the grant-delivery *stale-epoch*
reconcile, the `SCOPE_ROTATION` job for when no key-holder is online, the TOFU pin check at all three
foreign-wrap surfaces, the worker's decline seam, and the changed-key review with its accept path. So this is a
trigger and a ceremony, not a mechanism — and the design questions it has to settle are about **who may do it**
and **what it honestly buys**, not about crypto.

Two facts from the key model decide most of what follows. The identity private key exists **only** wrapped
under the Master Key ([en.account.key-model](../features/account/enablers/en.account.key-model.md)), and a
linked device is born at the password-less tier and has never seen the password or the Secret Key
([ADR 0059](0059-device-linking.md) §10).

## Decision

**1. One mode: the Master Key and the identity keypair rotate together, always.** ADR 0028 Decision 5's
conditional — identity-only when the Master Key was never exposed — is retired, because its case has no
population. Every path to the identity private key runs through the Master Key, so an identity compromise
*implies* a Master-Key compromise; and the driver, a phished pairing grant, hands the Master Key over by
construction. An identity-only mode is [ADR 0028](0028-identity-authenticity-tofu-verification.md)'s
rejected-alternative (d) with a button in front of it: a new identity re-wrapped under the leaked Master Key is
still readable. Offering the choice would ask the user a question they cannot answer and let them pick the
useless half.

**2. Rotation requires the Secret Key — a cold unlock, never a device wrap or a restored session.** The
`PASSWORD` floor takes password + Secret Key; the `RECOVERY` wrap takes the Secret Key alone, released against
address control ([ADR 0054](0054-password-recovery-two-factor-release.md) — there is **no** separate recovery
code, so both cold paths run through the Secret Key). A `DEVICE`, `DEVICE_KEY` or session-restored unlock may
not rotate.

**The gate is a live proof, not a memory, and the difference decides what the client has to store.** Rewriting
the two cold wraps needs the password and the Secret Key as *material* — no KEK survives the unlock — so every
path through the screen supplies them anyway. Re-deriving the floor KEK from what was just typed, opening the
stored `PASSWORD` wrap with it, and requiring the result to equal the Master Key the vault already holds is
therefore free, and it proves possession **now** rather than at some earlier point in the session. That is the
client's refusal.

**The server enforces the half it can check, and that half is the Secret Key, which both attackers lack.** A
client-side refusal binds only the client that runs it, and the attacker this gate exists for runs their own.
A device-link holder has the Master Key and the identity key and can call `rotateAccountKeys` or
`POST /api/account/password` directly. So can someone who holds only the password, because a session is all
those endpoints used to ask for. Therefore every account carries a **Secret-Key proof key**: an Ed25519 keypair
derived from the Secret Key alone, whose public half is registered at account birth. Every flow that mints a
cold credential — rotation, change-password, and recovery completion ([ADR 0054](0054-password-recovery-two-factor-release.md))
— carries a signature of that key over what the flow installs and the state it replaces. The server verifies
the signature against the stored public key and refuses the request otherwise.
- **The public key reveals nothing.** The Secret Key is 256 random bits.
- **It never changes in these flows.** None of them rotates the Secret Key. Re-issuing an Emergency Kit, a
  capability that does not exist yet (ADR 0054 § Consequences), would register a new proof key under a
  signature of the old one.
- **It does not replace the client's refusal.** That refusal proves the password half, now. The server's check
  proves the half the attacker most plausibly lacks.

*Which* `MasterKeyWrap` opened this vault is recorded too — new client state, three-valued, because the
session-restore path opens no wrap at all and `none` is its only honest entry (a boolean would have to label it
with a method it never used). But it **gates nothing**: a session opened by a device wrap upgrades *in place*
by supplying the cold credential, which is the same proof a cold session gives. Turning it away would add
nothing over the proof and would send somebody already holding their Emergency Kit back to the sign-in screen.
What the record earns is the *sentence on the screen* — why the Secret Key is being asked for here — so the
demand reads as the defence it is rather than as an unexplained hurdle.

**The gate is not rotation's — it belongs to every flow that MINTS a cold credential, and change-password is
the other one.** `POST /api/account/password` writes a new `PASSWORD` floor plus the `auth_public_key` derived
from it: exactly the material this decision is about. It shipped without the gate and with a *"I don't remember
my current password"* entry point that waived it, which made it the bypass of this record — a `DEVICE_KEY`
holder minted a cold credential for themselves and then passed the gate below for the victim's account. Both
screens now run one shared implementation of the proof
([uc.account.change-password](../features/account/use-cases/uc.account.change-password.md) § *One entry
point*); a third screen that mints one inherits the same obligation.

This gate is what keeps the feature from being net-negative, and it is the sharpest thing in this record. The
attacker this exists for holds the Master Key and the identity key and can therefore rotate too — **first**,
locking the legitimate holder out of their own account permanently. What that attacker does *not* hold is the
Secret Key, which a linked device never receives. Gating on the material the attacker lacks is the whole
defence; without it, building rotation makes veyq worse than not having it.

**3. Rotation is forward secrecy, not repair.** What the attacker already read stays read, and nothing here
retracts it — the same shape [ADR 0058](0058-revoke-never-takes-key-material.md) states for a revoke and
[ADR 0025](0025-forward-secrecy-policy-per-scope.md) for a removal, one layer up. No copy on any surface may
imply that rotating undoes a compromise; it ends it.

**4. The rotator does not re-admit themselves.** The scope rotation that excludes the old identity must be
performed by a **different** key-holder, and per-scope re-admission runs through the human change-review
([ADR 0028](0028-identity-authenticity-tofu-verification.md) (2)+(3)). Two independent reasons, either
sufficient: `rotateScope` is owner-gated, so a rotating plain member cannot drive it at all; and if the rotator
could, an attacker holding the old identity would mint the new epoch, wrap it to their **own** new identity and
exclude the legitimate owner — turning the exclusion mechanism into the takeover's final step. This is
ADR 0028 Decision 5's *"the other members must rotate"* with its reason attached rather than asserted.

**5. Every shared scope rotates, the tenant included — and this is not a new policy.**
[ADR 0025](0025-forward-secrecy-policy-per-scope.md) §2 already reserves an **explicit, owner-triggered
"re-key this space"** for exactly the compromise case, running async over grant-delivery so no single device
bears the `O(N)` burst. Rotation is its first caller; nothing about routine removal changes. Personal scopes
rotate too, self-served (one member, who is the owner) — the *"personal scopes never rotate"* line in
[en.spaces.scope-key-rotation](../features/spaces/enablers/en.spaces.scope-key-rotation.md) was written about
removal, where there is nobody to rotate away from, and does not survive a compromise, where the old identity
is exactly that somebody.

**6. The state in between is stated, not hidden.** Between rotating and being re-admitted, the rotator is
**outside every shared scope**: their new key is `changed` for every contact, the worker declines delivery to
them, and the re-grant loop skips them while the rotation proceeds. That is not a defect to smooth over — it is
the property that stops an attacker rotating their way back in, and the honest surface is a per-scope status,
not a spinner.

**7. Accepting a changed key must clear that worker's decline.** A worker that declined `identity_changed` is
excluded from the job **permanently** — no code deletes a decline row — so ADR 0028 (2)'s promise that on accept
*"the wrap then proceeds"* does not hold today. Nothing exercised it before, because a changed key could until
now only be an attack. Rotation is what makes a legitimate change routine, so this is on the critical path: a
rotation whose re-admission cannot be delivered is a rotation that strands the account.

## Consequences

- **Rotation is a re-onboarding ceremony, not a button, and its cost is stated up front.** Every `DEVICE` and
  `DEVICE_KEY` wrap dies — their key material is device-held and cannot be rewritten — so every other device
  needs the password (or a fresh link) again. The pin store and the device register are Master-Key-sealed and
  must be re-sealed. The Secret-Key proof key survives unchanged (Decision 2), so recovery keeps working without
  a re-registration.
- **The Emergency Kit stays valid.** The Secret Key is not rotated: the `PASSWORD` and `RECOVERY` wraps are
  rewritten under the *same* KEK. Rotating the Secret Key is a separate operation
  ([ADR 0054](0054-password-recovery-two-factor-release.md)), and folding it in would make the one operation
  that requires the kit also invalidate it.
- **Losing the Emergency Kit means losing the ability to rotate** — accepted, and the honest form of the same
  no-escrow property that makes losing every path terminal. The alternative, a warmer gate, hands the operation
  to the attacker it defends against.
- **The tenant re-key is `O(N)` and lands on a compromise, which is when it is worth paying**
  ([ADR 0025](0025-forward-secrecy-policy-per-scope.md) already reasoned this through and chose async delivery
  for it).
- **A pending group invitation does not survive the rotation.** The invitee's `sealed_name_key` is wrapped to
  the old identity and has no re-mint path; the proposal must be withdrawn and re-issued.
- **Operator-blindness is unchanged.** The server learns that an account published a new identity public key
  and that scopes rotated — both already-visible classes of metadata (a directory value, an epoch index). No
  key, no trust graph, no new correlation.
- **Tier-consistent ([ADR 0026](0026-client-trust-tiers.md)), with one half lifted off the tiers.** The
  Secret-Key half of the gate is server-enforced, and it holds against any caller that is not the operator. The
  password half and the re-admission review are client-enforced, so they are crypto-grade on a self-hosted or
  trusted client and honest-operator elsewhere — the same tier as all veyq E2EE. ADR 0026's tiers describe the
  client the *victim* runs. They say nothing about an attacker who runs a client of their own, and that
  attacker is the one this gate names.

**Alternatives rejected.**

- **(a) Identity-only rotation as a selectable mode** — Decision 1. Its case is empty, and offering it lets the
  user pick the variant ADR 0028 already rejected as useless.
- **(b) Asking the user whether the Master Key was exposed** — they cannot know. A coin flip wearing consent.
- **(c) Gating on the password alone** — it does exclude the phishing attacker, who never typed one. Rejected
  because a password leaks in a breach and is used silently, which is ADR 0059's own reasoning for preferring
  the transcribed code over a password; for an operation this destructive the two-secret floor is the right bar.
- **(d) Letting the rotator drive their own scope re-grant** — Decision 4; it converts the exclusion into the
  attacker's finishing move.
- **(h) A signature of the identity key as the server's gate.** It proves an unlocked vault, and the
  device-link holder has one.
- **(i) A proof key derived from the `PASSWORD` floor's KEK** (password + Secret Key). That puts two factors in
  one signature. But it would have to be re-registered on every password change and every recovery. It also
  cannot exist on the `RECOVERY` path, which has no password by design (ADR 0054 Decision 1). The factor both
  attackers lack is the Secret Key, and a key derived from it alone is stable.
- **(e) Leaving the tenant scope out and stating it as an honest limit** — the tenant scope holds sealed content
  and a browsable Memory tree, and is veyq's *primary* shared home
  ([ADR 0025](0025-forward-secrecy-policy-per-scope.md) § Context). A rotation that clears the groups and leaves
  the attacker in the main space would be a limit nobody could sensibly explain, and ADR 0025 §2 had already
  decided the operation anyway.
- **(f) A server-side "this user rotated" broadcast that auto-accepts the new key** — it is precisely the
  key-substitution the whole TOFU model exists to catch, and a blind server must not adjudicate a crypto claim
  ([ADR 0028](0028-identity-authenticity-tofu-verification.md) § Consequences).
- **(g) Key Transparency** — the correct end-state and materially heavier
  ([ADR 0028](0028-identity-authenticity-tofu-verification.md) (f)); it subsumes the re-admission review rather
  than competing with it.

## Related

- [uc.account.rotate-keys](../features/account/use-cases/uc.account.rotate-keys.md) — the flow this ADR governs
- [ADR 0028](0028-identity-authenticity-tofu-verification.md) (the TOFU model this rotation is accepted through;
  its Decision 5 is settled here), [ADR 0059](0059-device-linking.md) (the obligation), [ADR 0058](0058-revoke-never-takes-key-material.md)
  (why a revoke cannot substitute), [ADR 0025](0025-forward-secrecy-policy-per-scope.md) (the tenant re-key this
  is the first caller of), [ADR 0024](0024-scope-key-rotation.md) (the epoch mechanism), [ADR 0054](0054-password-recovery-two-factor-release.md)
  (the cold paths and why there is no recovery code)
- [en.account.key-model](../features/account/enablers/en.account.key-model.md),
  [en.account.identity-pinning](../features/account/enablers/en.account.identity-pinning.md),
  [en.spaces.scope-key-rotation](../features/spaces/enablers/en.spaces.scope-key-rotation.md)
