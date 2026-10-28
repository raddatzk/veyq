# ADR 0060 — Key rotation after a compromise: one mode, a cold-credential gate, a signed step that keeps every space

- Status: Accepted
- Built: yes
- Date: 2026-08-15
- Model: [keys-grants-and-trust](../crosscutting/keys-grants-and-trust.md)
- Replaced in part: Decision 1, Decision 2, Decision 4, Decision 5, Decision 6, Decision 7 by ADR 0111 (built)

## Context

[ADR 0059](0059-device-linking.md) shipped device linking and named what it created: a device receives the
Master Key **and** the identity private key on one human confirmation, and a revoke takes neither back
([ADR 0058](0058-revoke-never-takes-key-material.md)). A phished grant is therefore an identity takeover,
containable and visible but **not reversible**. That ADR calls rotation *"the live obligation this feature
creates"*; [ADR 0028](0028-identity-authenticity-tofu-verification.md) Decision 5 deferred the mechanics to
this record and left three things standing: identity rotation is the core, the Master Key rotates **only** if
it was itself exposed, and the other members must rotate every shared `K_SC` to exclude the old identity.

**What forced the decision was that only the account-side operation was missing.** What it drives was built: the
epoch chain and `POST /api/scopes/{scopeId}/epochs`, the grant-delivery *stale-epoch* reconcile, the TOFU pin check
at every foreign-wrap surface and the worker's decline seam. So this is a trigger and a ceremony more than a
mechanism — and the design questions it has to settle are about **who may do it** and **what it honestly buys**.
The key chain and the stage of self-grants it rests on are
[ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)'s
(decisions 6 and 7). The product calls the operation **Secure your account**.

Two facts from the key model decide most of what follows. The identity private key exists **only** wrapped
under the Master Key ([en.account.key-model](../features/account/enablers/en.account.key-model.md)), and a
linked device is born at the password-less tier and has never seen the password or the Secret Key
([ADR 0059](0059-device-linking.md) §10).

## Decision

**1. One mode: the Master Key, the identity keypair and the Secret Key rotate together, always, as one signed step of
the account's key chain.** ADR 0028 Decision 5's
conditional — identity-only when the Master Key was never exposed — is retired, because its case has no
population. Every path to the identity private key runs through the Master Key, so an identity compromise
*implies* a Master-Key compromise; and the driver, a phished pairing grant, hands the Master Key over by
construction. An identity-only mode is [ADR 0028](0028-identity-authenticity-tofu-verification.md)'s
rejected-alternative (d) with a button in front of it: a new identity re-wrapped under the leaked Master Key is
still readable. Offering the choice would ask the user a question they cannot answer and let them pick the
useless half. The Secret Key rotates with them because someone who holds it and the password is otherwise
indistinguishable from the member, so a leaked Secret Key would have no remedy.

So the request has one shape and **every field is mandatory**, because an optional field is a mode spelled by
omission (`scripts/validate_rotation_shape.py` pins the set; the fields are
[keys-grants-and-trust § Securing the account](../crosscutting/keys-grants-and-trust.md#securing-the-account)). It
carries the **identity-pin store and the device register re-sealed under the new Master Key**, empty for an account
that never wrote either. The two documents are not part of the proof's statement: a session holder without the Secret
Key can already write both through their own endpoints, so binding them would defend no case.

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
derived from the Secret Key alone, whose public half is registered at account birth and replaced when the account is secured. Every flow that mints a
cold credential — rotation, change-password, recovery completion ([ADR 0054](0054-password-recovery-two-factor-release.md)),
and a change of the sign-in address ([ADR 0074](0074-address-control-is-recorded-where-nothing-else-proves-it.md) Decision 8) — carries a signature of that key over what the flow installs and the state it replaces. The server verifies
the signature against the stored public key and refuses the request otherwise.
- **The public key reveals nothing.** The Secret Key is 256 random bits.
- **It changes only when the account is secured.** The publish registers the new proof key under a signature of the
  current one, and a signature of the new one proves it is held; the key-chain step names it. Change-password,
  recovery and a change of the sign-in address leave it as it is.
- **It does not replace the client's refusal.** That refusal proves the password half, now. The server's check
  proves the half the attacker most plausibly lacks. A rotation also carries the current auth key's attestation over
  the auth key it installs, verified as change-password verifies its own (`403 attestation_failed`), so the server
  checks the password half of a rotation too.

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

**4. Access moves first, security after: the rotator re-grants itself and mints every next epoch, and an attacker
cannot.** In its one transaction the publish appends the key-chain step and re-grants every scope the account holds a
grant on — personal, tenant and group, across every tenant — at its **current** epoch to the new identity, from
self-grants staged before it ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
decision 6 holds the stage and why it exists). Then the account mints each scope's next epoch itself, from any of its
unlocked devices (ADR 0111 decisions 8–9), and any member re-keys the nodes (ADR 0111 decision 11). No retired private
key is kept, and no state after the publish can lose access: a lost answer, a closed tab, a failed next epoch or a
space in another tenant leaves each space readable by the published identity, and the next device that unlocks
finishes it. A scope this device cannot open is declared and keeps its grant; it was already closed to the account.

The danger of a self-service re-grant is an attacker holding the old identity minting the new epoch for their **own**
new key and excluding the legitimate owner. The key-chain step closes it: it is signed by the old identity **and** the
old Secret-Key proof key, so a device-link holder, who lacks the Secret Key, cannot publish (Decision 2); a contact
accepts a new key only where the chain reaches it from the head it pinned
([ADR 0028](0028-identity-authenticity-tofu-verification.md) Decision 2); and the server takes no grant to a key that is
not its recipient's current one (ADR 0111 decision 10), so a stale directory read cannot wrap to the old identity. A
non-owner commits a scope's next epoch only with the pending row the publish left for it, and never with a removal.
The mechanism is [keys-grants-and-trust § Securing the account](../crosscutting/keys-grants-and-trust.md#securing-the-account).

**5. Every shared scope rotates, the tenant included — and this is not a new policy.**
[ADR 0025](0025-forward-secrecy-policy-per-scope.md) §2 already reserves an **explicit "re-key this space"** for
exactly the compromise case. Account rotation is one caller, a tenant owner's own action the other; nothing about
routine removal changes. The wraps (one per
member per scope) land on the rotator's devices, and the far larger node re-key is spread across every member's
(ADR 0111 § Consequences). Personal scopes rotate too, self-served (one member, who is the owner): on a removal there
is nobody to rotate away from, on a compromise the old identity is exactly that somebody.

**6. Each space's progress is stated, not hidden, and none waits for anyone else.** The rotator is never outside a
scope. What follows the publish is each scope's next epoch and its node re-key, and the honest surface is a per-scope
status, not a spinner: the **server's**, derived from the pending rows the publish left, re-readable at any time and
Host-independent, so a space in another tenant reads too (`waiting`, `rekeying`, `secured` or `blocked`). There is no
job, so nothing stalls or fails: `waiting` ends with the next device of the account that unlocks.

**7. The publish clears the declines that target the rotator.** A delivery worker that cannot anchor a member's key
declines the job and is excluded from it, and no human accepts a key (ADR 0028 Decision 2). Without clearing, a member
a worker once declined would stay excluded from that worker after every rotation; cleared, a worker that could not
anchor the old key tries the new one, which the chain reaches.

## Consequences

- **Rotation is a re-onboarding ceremony, not a button, and its cost is stated up front.** Every `DEVICE` and
  `DEVICE_KEY` wrap dies — their key material is device-held and cannot be rewritten — so every other device
  needs the password (or a fresh link) again. The pin store and the device register are Master-Key-sealed and
  are re-sealed inside the same transaction as the rewrite: a rotation that cannot re-seal them does not
  happen. Re-sealing them after the publish would leave both under a key that no longer exists whenever the
  re-seal failed, and every device refuses a document that does not open
  ([ADR 0028](0028-identity-authenticity-tofu-verification.md) Decision 7). The Secret-Key proof key is replaced in
  the same transaction (Decision 2), so change-password, recovery, a change of the sign-in address and the next
  rotation verify under the new one from the commit on. The publish voids every open address change, whose proof was
  taken under the old key.
- **Securing the account replaces the Emergency Kit.** The Secret Key is rotated with the rest, because someone who
  holds it and the password is otherwise indistinguishable from the member. The new kit is saved and confirmed before
  anything is published, so the ceremony that needs the old kit cannot leave the member with none; until the result
  says the account is secured, the old kit is the one that works. Whoever holds the password and the current Secret
  Key and secures the account first owns it. That is not a new power: the same holder can already move the sign-in
  address and change the password, and with them take recovery ([ADR 0054](0054-password-recovery-two-factor-release.md)).
- **Losing the Emergency Kit means losing the ability to rotate** — accepted, and the honest form of the same
  no-escrow property that makes losing every path terminal. The alternative, a warmer gate, hands the operation
  to the attacker it defends against.
- **The tenant re-key is `O(N)` and lands on a compromise, which is when it is worth paying**
  ([ADR 0025](0025-forward-secrecy-policy-per-scope.md) already reasoned this through).
- **Every rotated scope re-keys its nodes**, so the old identity reads nothing added afterwards, inside an existing
  folder or Memory included ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)): `O(nodes + media)`
  symmetric re-seals, which any member's device runs ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decision 11), and nothing re-encrypted in the store. A space reads *secured* only once its re-key is done. What
  stays in the old identity's reach is what existed at the rotation and every link it could rebuild, until renewed;
  the result screen lists the account's links to renew.
- **A pending group invitation keeps its access but not its name.** The publish re-grants it like every scope the
  account holds a grant on, so accepting it still opens the group. Its `sealed_name_key` is sealed to the old identity
  and nothing re-seals it, so the invitation is shown as an unnamed group.
- **Operator-blindness is unchanged.** The server learns that an account published a new identity public key
  and that scopes rotated — both already-visible classes of metadata (a directory value, an epoch index). The key
  chain it stores and serves is public keys and signatures of rotations it already knew of; the stage is ciphertext
  and scope ids it already holds as grant rows. No key, no trust graph, no new correlation.
- **Tier-consistent ([ADR 0026](0026-client-trust-tiers.md)), with the gate lifted off the tiers.** Both halves of
  the gate are server-enforced — the Secret-Key proof and the auth key's attestation — and hold against any caller
  that is not the operator. A contact's acceptance of the new key is client-enforced (the chain walked from its pinned
  head), so against an active operator it holds only on a client the operator does not serve, and only as far as pins
  cannot be rolled back ([ADR 0028](0028-identity-authenticity-tofu-verification.md) Decision 7); honest-operator
  elsewhere, the same tier as all veyq E2EE. ADR 0026's tiers describe the
  client the *victim* runs. They say nothing about an attacker who runs a client of their own, and that
  attacker is the one this gate names.

**Alternatives rejected.**

- **(a) Identity-only rotation as a selectable mode** — Decision 1. Its case is empty, and offering it lets the
  user pick the variant ADR 0028 already rejected as useless.
- **(b) Asking the user whether the Master Key was exposed** — they cannot know. A coin flip wearing consent.
- **(c) Gating on the password alone** — it does exclude the phishing attacker, who never typed one. Rejected
  because a password leaks in a breach and is used silently, which is ADR 0059's own reasoning for preferring
  the transcribed code over a password; for an operation this destructive the two-secret floor is the right bar.
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
  ([ADR 0028](0028-identity-authenticity-tofu-verification.md) § Consequences). The key-chain step makes acceptance
  automatic without that: every contact's client verifies the step itself.
- **(g) Key Transparency** — the correct end-state and materially heavier
  ([ADR 0028](0028-identity-authenticity-tofu-verification.md) (f)); it would witness the key chain against a fork
  ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) decision 17).
- **(j) Keeping the old identity, sealed under the new Master Key, until every space is re-keyed.** It keeps key
  material for no other reason, needs a seal purpose whose only job is never to be opened as the identity, and a later
  compromise of the new Master Key yields the old identity too. Re-granting the current epoch in the publish gives the
  same finishability and keeps nothing.
- **(k) Minting the new epoch inside the publish.** A space that cannot rotate at that moment (a suspended tenant, a
  group in deletion) would block the remedy or be stranded, and the scope rotation would move into the account's
  transaction.
- **(l) A resume record in the browser.** It helps one browser on one device and puts the old private key in local
  storage.

## Related

- [uc.account.rotate-keys](../features/account/use-cases/uc.account.rotate-keys.md) — the flow this ADR governs
- [ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) (the
  key chain, the stage and the self-service re-grant),
  [ADR 0028](0028-identity-authenticity-tofu-verification.md) (the trust model a contact accepts the new key through;
  its Decision 5 is settled here), [ADR 0059](0059-device-linking.md) (the obligation), [ADR 0058](0058-revoke-never-takes-key-material.md)
  (why a revoke cannot substitute), [ADR 0025](0025-forward-secrecy-policy-per-scope.md) (the tenant re-key this
  is the first caller of), [ADR 0024](0024-scope-key-rotation.md) (the epoch mechanism), [ADR 0054](0054-password-recovery-two-factor-release.md)
  (the cold paths and why there is no recovery code)
- [en.account.key-model](../features/account/enablers/en.account.key-model.md),
  [en.account.identity-pinning](../features/account/enablers/en.account.identity-pinning.md),
  [en.spaces.scope-key-rotation](../features/spaces/enablers/en.spaces.scope-key-rotation.md)
