# ADR 0028 — Identity authenticity: TOFU pinning + safety-number verification

- Status: Accepted; amended 2026-10-16 by [ADR 0086](0086-one-word-per-meaning.md): the pin store's and the device
  register's counter is a **revision** (`revision`, was `version`); by [ADR 0104](0104-the-identity-pin-store-is-one-sealed-entry-per-pin-under-a-sealed-head.md)
  (2026-10-19): the pin store is one sealed entry per pin under a sealed head — Decision 1's *what is pinned* and
  Decision 7's *which revision is marked* say so in place. [ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) (accepted 2026-10-27) decides anew how a
  key change is judged and who re-grants after a rotation. **Built** (2026-10-28): the pin holds the contact's key-chain
  head, a key is accepted where the doubly signed chain reaches it from that head, no human reviews a change, and the
  rotator re-grants itself (Decisions 1, 2, 5, 6 say so in place). **Not built:** the owner-rooted join record as the
  anchor and the *verified* mark as a signed statement per tenant; until they are, the pinned head is the anchor and
  Decision 3's personal mark is the verification.
- Date: 2026-07-07

## Context

Wrapping a scope key or an invite to a member needs that member's **identity public key**, which the
operator-blind server relays from its directory. An actively malicious operator can **substitute a key it
controls** (MITM) at the moment of a wrap and read what is sealed to it. Until this record veyq left that
**Plain** — the wrapper trusted the returned key, no pin, no gate ([ADR 0020](0020-grant-distribution-invite-wrap.md),
[ADR 0014](0014-group-scopes-and-governance.md) §5 — deferred to this slice). The **fingerprint / safety
number** primitive already exists (`fingerprint()` — a Signal-style digest of the full identity public bundle
`lp(kem) || lp(sign)`, vector-pinned + conformance-tested) but was, when this record was
written, **dormant** — no UI, no pin store. It is now the safety number a member's profile shows and
the check number device pairing compares ([ADR 0059](0059-device-linking.md) §5).

Three surfaces wrap to a **foreign** identity key, and all three were Plain when this was decided: (1) rotation
re-grants, (2) the **automated grant-delivery worker** (no human in the loop), (3) the group invite name-key
wrap. All three now run the gate below. A fourth surface sits on the *receiving* side: opening a grant means
trusting whoever sealed it (Decision 6).

The identity keypair is **stable per `userId`** — the master key survives password changes / recovery / new
devices, and it wraps the identity private key — **except** one legitimate case: **rotation after a compromise**
(a new identity, [en.account.key-model](../features/account/enablers/en.account.key-model.md)). So a key change
was "**attack OR legitimate rotation**", indistinguishable on-wire — until a rotation became a step of the account's
key chain, signed by the old identity **and** the old Secret-Key proof key, which tells the two apart (Decision 2).
And the whole scheme must stay
**operator-blind**: a server-side record of who-trusts-whom would leak the social graph.

## Decision

Adopt **trust-on-first-use (TOFU) pinning + out-of-band safety-number verification**, with a key change judged
by the contact's signed key chain. Key Transparency stays the (out-of-scope) end-state.

1. **Pin on first use.** The first time a client wraps to a member's identity key, or accepts a grant from them, it
   **pins** them (state: *pinned, unverified*) and proceeds. A pin holds the **head of the contact's key chain**
   ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
   decisions 7 and 16) and the fingerprint of the identity at that head. The head pins the identity **and** the
   Secret-Key proof key: with only the identity pinned, an operator could serve an invented chain whose last step names
   the pinned identity with a proof key of its choosing, and a device thief holding the old identity could then sign a
   step every member accepts. The fingerprint is the hash of the full identity public
   bundle (`kem`, the X-Wing encapsulation key over X25519 + ML-KEM-768, + `sign` Ed25519) — the domain-separated
   SHA-256 the safety number and its QR already are (Decision 3) — keyed by `userId` (identity is account-global, so
   one pin per contact across all tenants). Every reader of a pin compares it for equality with a served key or shows
   its safety number, both functions of that hash, and equality of the hash is equality of the bundle up to a SHA-256
   second preimage ([ADR 0104](0104-the-identity-pin-store-is-one-sealed-entry-per-pin-under-a-sealed-head.md)
   decision 3). The **pin store is client-side, synced across the user's devices, and sealed under the master key**
   — one sealed entry per pin at an opaque id under a sealed head (ADR 0104) — so the server stores only ciphertext
   and never sees a pin, a contact or the trust graph (operator-blind). It does see how many pins there are, as it
   always could (ADR 0104 decision 5).

2. **A key ≠ pin is judged by the key chain, never by a human.** Every read that serves another user's key to a
   check serves their key-chain head beside it, and the client walks the chain (`getKeyChain`) from the pinned head:
   - **`match`** — the served head is the pinned one and the served key the key at it;
   - **`advanced`** — every step from the pinned head to the served one verifies (both signatures, the predecessor
     hashes, the key at the pinned head equal to the pinned fingerprint) and the chain ends at the served key: the pin
     moves to the new head, *verified* drops, and the person's profile says *new identity, signed by their previous
     key* with the step's date;
   - **`firstUse`** — no pin: the chain's head is the served one and its newest key the served key, and the head is
     pinned (Decision 1);
   - **`anomaly`** — anything else, a served head older than the pinned one (a rollback) and a chain the server does
     not serve included.

   An anomaly is **never wrapped to and never accepted as a granter**: a rotation declares that member pending, the
   grant-delivery worker **declines the job back to the server** (`identity_unanchored`, "do not re-offer this job to
   me"), and the member lists and the group add show *Can't confirm this key* with a note to compare safety numbers in
   person. There is **no accept**: a legitimate rotation is always signed, so the gate never fires for one, and it never
   blocks anybody's write. A key served without a head — the per-grant snapshot of a granter's key (Decision 6) — is
   judged by whether the chain this account walked from its pin holds it.

3. **Verification (elevate *pinned* → *verified*)** (under [ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) decisions 12–14, not built, a verification is a
   statement signed by the verifier in the tenant, naming the chain head it confirms, and the compare shows that
   head). Two users compare the **safety number** out-of-band; the
   ergonomic form is **QR** — scan the other device's fingerprint, the app **compares it against the pin and gives
   the user feedback** (matches / differs). Verification is optional trust elevation in the default flow; after a
   contact's rotation it returns only through a new compare. Reuses the existing
   QR-scan infrastructure (emergency kit). Its form is owner-decided (2026-10-14,
   [uc.spaces.enforce-verified-identities](../features/spaces/use-cases/uc.spaces.enforce-verified-identities.md)
   main flow):
   - **Show and compare.** Every member sees **their own** safety number (the per-identity 6x5 digits of the pinned
     `fingerprint` vector — no pairwise number, no new crypto) and a QR of it on their own profile page; on another
     member's profile they see that member's number, can **scan** the other's QR or compare the digits read aloud,
     and **mark verified**. The QR carries a veyq marker plus the fingerprint hash, never the identity bundle (the
     `jsqr` exception in `src/web/CLAUDE.md` is bounded by what our own payloads carry).
   - **Verifying pins.** A member with no pin yet is pinned **at** verification, with the key the human compare
     just confirmed; nothing else pins more than Decision 1 does (no pin on a directory read or a profile view).
   - **A verified contact's rotation drops *verified*.** The pin moves with the chain (Decision 2) as `pinned`, and
     `verified` returns only through a new compare — shown, never silent.
   - **Where it shows:** the person's profile and the tenant's and groups' member lists (a "verified" mark beside
     the anomaly's *Can't confirm this key*).
   - **It does not wait for strict mode (4).** A "verified" mark that nothing enforces is not advisory noise:
     TOFU (2) detects only a key that changes **after** the first pin, so an operator that serves a substituted
     key **at first contact** goes unnoticed — the first-use gap below — and an out-of-band compare is the one
     thing in the tree that closes it until Key Transparency exists.

4. **Opt-in strict mode — decided in principle, not built.** A user (or a group owner) may require
   **verified-before-first-share**. Under strict mode the worker
   **skips unverified members** (they stay without the current key) until a human verifies them in the UI. This
   gives security-maximalists the pre-first-wrap gate without imposing its friction on the default flow. It needs redesigning before it is wired (§ What is still open),
   on its own use-case,
   [uc.spaces.require-verified-identities](../features/spaces/use-cases/uc.spaces.require-verified-identities.md).

5. **Rotation is the compromise-recovery operation this model enables.** A rotated identity is a key change
   that contacts accept through the key chain (2) — which is what lets a legitimate rotation exist at all without a
   blind server adjudicating it. Its mechanics are settled in [ADR 0060](0060-key-rotation-after-compromise.md),
   which corrects the conditional this decision first carried: there is **one mode**, and the Master Key, the
   identity and the Secret Key rotate **together**, because the identity private key exists only under the Master Key
   and so an identity-only rotation is (d) below. Every shared `K_SC` still rotates to exclude the old identity, and
   the rotator drives it: a rotation step signed by the old Secret-Key proof key is one an attacker holding the old
   identity cannot make, so that attacker cannot re-admit a new key of their own
   ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
   decision 8).

6. **Every grant is signed by its granter, and the recipient checks the signature before opening it.** The
   decisions above protect the *wrapper*, who needs to know that the key it wraps to is the right one. They say
   nothing to the *recipient*, who opens whatever grant the server lists, sealed to them by anyone. Because a
   seal to a public key needs only that key, the server can plant a scope key of its own choosing, and the
   recipient's next write goes under it. So a grant carries the granter's user id and an **Ed25519 signature of
   the granter's identity key** over the scope id, the scope's kind, the epoch, the recipient's user id, a
   digest of the recipient's identity public bundle, and a digest of the grant ciphertext.
   - **Personal scope: only oneself.** A grant whose signed kind is `PERSONAL` opens only if the granter is the
     recipient and the signature verifies under the recipient's own signing key, which comes from the vault and
     never from the directory. The server can no longer plant a personal scope key, and it cannot present a
     group grant as the personal scope, because the kind is signed.
   - **Shared scope: the granter's key is judged like any contact's.** The recipient runs the Decision 1–2 gate on
     the granter: a first-seen key is pinned through its chain, one the chain reaches is accepted, and an anomaly is
     refused (`granter_key_unanchored`) and surfaced. A grant signed by a **retired** key of the granter's chain opens
     only at an epoch at or below this device's epoch floor for the scope (`granter_key_retired`): such grants exist
     from before the rotation, and a new epoch from a retired key is what a thief with the old identity would forge,
     while the honest rotator signs every new epoch with the new key. This is a **fourth pin surface**, the first on
     the receiving side.
   - **What it buys on a shared scope, honestly.** A brand-new member's first grant is a first-use pin of its
     granter, so an operator that fabricates both the granter and the key is not detected on the spot. It is
     detected by the next verification of that granter's safety number. What the signature does rule out is a
     grant presented under the identity of someone the recipient has already pinned.
   - The server stores and relays the signature and does not verify it. A blind server does not adjudicate a
     crypto claim (§ Consequences), and the recipient is the party that needs the answer. One exception: an account
     rotation verifies the self-grants it applies, at the upload of its stage, under the identity the same rotation
     publishes ([ADR 0060](0060-key-rotation-after-compromise.md)). That is a consistency check of the request against
     itself, under the Secret Key's proof, not a trust judgment about a key.

7. **A device never accepts older trust state than it has held, and a store that does not open is an error,
   not an empty store.** The pin store lives on the server, so the server decides which copy a device reads.
   Binding the store's revision into its seal ([ADR 0072](0072-a-seal-binds-its-object.md)) makes that revision
   authentic, not fresh. So each device keeps a **local high-water mark** — the highest revision of the pin
   store's head it has opened — and refuses a served store below it, on every read **and before every write**,
   because a write
   re-seals what it read and would otherwise make the rollback its own. It refuses a missing store once it has
   held one, and it refuses a store that fails to open instead of reading it as empty: an empty store is exactly
   what turns a detected key change back into a silent first use, and a rolled-back one can reinstate a retired
   key as a match.
   - **Per entry, the head carries the set.** The pins are entries the server could withhold, add, roll back or
     move one at a time while the head's revision stays honest; the head's sealed digest over every entry turns
     each of those into the same refusal as a rolled-back store (`trust_state_rolled_back`), and a head that moved
     during the listing is read again ([ADR 0104](0104-the-identity-pin-store-is-one-sealed-entry-per-pin-under-a-sealed-head.md)
     decision 4).
   - **The same rule covers the other sealed account document**, the device register
     ([en.account.device-register](../features/account/enablers/en.account.device-register.md)), and the
     per-scope epoch floor of [ADR 0024](0024-scope-key-rotation.md) Decision 6 uses the same mechanism.
   - **The marks are per device and local, never synced.** A synced mark is one more thing the server can roll
     back. A device linked by another ([ADR 0059](0059-device-linking.md)) inherits that device's marks inside
     the authenticated pairing envelope.
   - **They are filed under the account's Master Key, never under an id the server names.** A mark filed under
     the account id the server reports is a mark the server can point elsewhere — report another id and the
     device reads an empty slot, which is the no-mark case on demand. The slot is a one-way derivation of the
     Master Key instead. So an account-key rotation ([ADR 0060](0060-key-rotation-after-compromise.md)) moves the
     marks: the rotating device carries its own across; every other device must unlock afresh, and does so
     without marks when it uses the password and Secret Key (the no-mark case below), or with its linker's when
     it is linked again.
   - **A refused document has one way back: the user starts it over on a device, knowingly, through one
     confirmed action.** That write is the only thing that lowers a mark — to the revision it just wrote itself,
     which is the one copy the mark cannot be protecting the device from — and no server response reaches it.
     It writes the empty document, so it costs what an empty store costs (every contact first-seen again, every
     verification gone; every device unnamed), and the confirmation says so. The server can therefore withhold
     a document until the user resets it: a downgrade that is now consented, not silent. Another device heals
     by reading where its mark sits at or below the reset's revision; one whose mark sits above it (after a
     backup restore) refuses it as rolled back and is offered the same reset, because a device cannot tell a
     restore from an attack.
   - **What it cannot see:** a device with no mark — one that signed in with the password and Secret Key, or
     lost its local storage — trusts the first store it is served, which is the first-use gap applied to trust
     state; and a newer store the server keeps from a device that never held it (a fork).

## Consequences

- **Closes the silent-swap-after-first-contact hole** for anyone who has interacted with a contact before: their
  client holds a pin, so a later swap the key chain does not reach is refused at every wrap and grant, and shown.
  Verification closes it fully for those who compare.
- **The first-use gap remains (honest).** If the operator substitutes a key **before** a client ever pinned the
  real one — and re-wraps genuine content too — nothing looks wrong until **manual verification** (the compare of
  Decision 3) or Key Transparency. TOFU cannot detect a lie told before first sight. The gap is narrower since two later changes.
  A newcomer's first contact with its inviting owner is anchored by the owner's fingerprint inside the invite link's
  seal, which the operator that mails the link cannot open without the code
  ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decision 1, built 2026-10-28). And it no longer reaches the personal scope at all (Decision 6). The other
  direction widened: with the admission tag gone (ADR 0111 decision 5), a membership the operator invents is, to a
  key-holder that never pinned it, a first use like any other, wrapped to by a rotation or a delivery, until the
  owner-rooted join record each newcomer writes is read as an anchor (ADR 0111 decisions 12 and 15, not built).
- **Grant-delivery is first-online-wins across key-holders, so a worker block is per key-holder — keyed on the
  declining account, not on the browser it declined from — and not a global veto.**
  A key-holder whose pin the chain does not reach the served key from declines; a *different* key-holder who never
  saw the contact has no pin → pins at first use and delivers. This is deliberate: the blind server **must not**
  adjudicate a crypto claim, and a server-side **global quarantine** on one worker's say-so would be a **DoS**
  (any device — including an attacker's — could veto delivery). The value is raised detection: everyone who knew
  the contact refuses, so the target stays behind the current key from them — a visible anomaly. A legitimate
  rotation is reached by every pin, so no key-holder declines it, and the rotation's publish clears the declines that
  targeted the rotator ([ADR 0060](0060-key-rotation-after-compromise.md) Decision 7).
- **Operator-blindness preserved.** The pin store is sealed under the master key; the server never learns who
  pinned or verified whom — only how many pins an account holds, which the grant rows already let it count. It gains only a small **grant-delivery decline** status (a worker refusing a job) —
  operational metadata, no trust graph — and the key chain, which records rotations the server already knew of and
  is read the same way by every co-member.
- **Tier-consistent ([ADR 0026](0026-client-trust-tiers.md)).** Enforcement is client-side, so it holds against
  an active operator only on a client that operator does not serve, and there only as far as the pin store is
  fresh (Decision 7); on operator-served web it is honest-operator (a tampered client could ignore its own pin) —
  the same tier as all veyq E2EE, no authenticity-specific weakness.
- **Alternatives rejected:** (a) *a human accept of a changed key* — a legitimate rotation is a signed chain step,
  so it needs no human, and a change the chain does not reach is one no human can judge better than the signatures
  can; an accept button is what a substituted key would ask for. (b) *verify-before-first-wrap
  as the global default* — impossible for the human-less worker and would hold every wrap for a human; kept as
  opt-in strict. (c) *server-side pin/verification store* — a who-trusts-whom graph is a metadata leak that breaks
  operator-blindness; the store is client-side + sealed. (d) *master-key rotation alone for compromise recovery* —
  useless without identity rotation (the master-key holder already holds the identity private key). Its mirror
  image, *identity-only rotation*, is rejected for the same reason and is not a selectable mode: a new identity
  re-wrapped under a leaked master key is still readable, and the two are not independently reachable
  ([ADR 0060](0060-key-rotation-after-compromise.md) Decision 1). (e) *server-side global
  quarantine on a worker's decline* — DoS + the blind server cannot adjudicate the claim; a decline only removes
  that worker from the candidate set. (f) *Key Transparency now* — the correct end-state but far heavier; TOFU +
  verification is the near-term step it later subsumes. (g) *holding the verification ceremony back until strict
  mode exists* — it would leave the first-contact substitution, which TOFU cannot see, with no detector at all
  (Decision 3).

## What is still open

Decisions 1–3 are built: the sealed pin store and its sync
([en.account.identity-pinning](../features/account/enablers/en.account.identity-pinning.md)), the anchor gate at
every wrap to another account and every grant from one, the grant-delivery **decline** seam, and the compare on a
member's profile (`src/web/src/core/people/`) with the member lists' "verified" and anomaly marks. Decision 5 is
settled by [ADR 0060](0060-key-rotation-after-compromise.md). Decision 6 is built: every grant producer signs,
each grant row stores the signature beside a copy of the granter's identity public bundle **as it was when the
server wrote the grant**, the reads serve that copy, and the one unwrap path and the switcher's icon read verify
before the key is used. The served key decides nothing on its own — the recipient's chain walk does. What the copy
buys is that a granter who rotates their account keys leaves the grants they signed verifiable under the retired key
the chain still holds, at or below the recipient's floor. Serving the granter's *current* key would fail every one of
those grants as `bad_signature` before any chain is read. One limit stays:
- **A key is retired only in the chain this device walked.** A pin still at the old head matches the old key with no
  chain read, because the per-grant copy carries no head to say otherwise; the pin moves when a directory read serves
  the new head.

Decision 7 is built for the pin store and the device register (`src/web/src/features/account/trustMarks.ts`): each
refusal is named (`trust_state_rolled_back`, `trust_state_unreadable`), the grant-delivery worker pauses on one
rather than declining, and the nearest existing notice names it and links to the settings hub's one reset. The pin
store also holds the highest key-chain head seen per contact, so a served head older than the pinned one is an anomaly
(Decision 2). One thing remains:

- **Decision 4, strict mode.** It needs redesigning before it is wired: an *account-global* toggle cannot protect
  shared-scope content, because any co-member may key an unverified identity. The verification ceremony does not
  wait for it (Decision 3).

Explained for security researchers on the docs site: [Threat model](../../site/src/content/docs/security/threat-model.md), [Known limitations](../../site/src/content/docs/security/known-limitations.md).
