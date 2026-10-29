# ADR 0028 — Identity authenticity: TOFU pinning + safety-number verification

- Status: Accepted
- Built: partly (not built: Decision 4)
- Date: 2026-07-07
- Model: [keys-grants-and-trust](../crosscutting/keys-grants-and-trust.md)
- Replaced in part: Decision 1, Decision 7 by ADR 0104 (built); Decision 1, Decision 2, Decision 3, Decision 5, Decision 6 by ADR 0111 (built)

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
   **pins** them and proceeds. A pin holds the **head of the contact's key chain**
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
     moves to the new head, every verification of the old head stops counting (Decision 3), and the person's profile
     says *new identity, signed by their previous key* with the step's date;
   - **`firstUse`** — no pin: the chain's head is the served one and its newest key the served key, and the head is
     pinned (Decision 1);
   - **`anomaly`** — anything else, a served head older than the pinned one (a rollback) and a chain the server does
     not serve included.

   **In a tenant the join record decides first** ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
   decisions 12 and 15). A member whose owner-rooted join record verifies is walked from the step that installs the
   identity and proof key the record names, whatever prefix the server serves before it, and the pin stays the
   counter: a pinned head off that chain is **`replaced`** when the anchored chain is at least as long as the pinned one
   was, an anomaly when it is shorter (a possible rollback). An owner the server lists with no join record is a
   **root**, judged by the pin alone. **Any other member is an anomaly** — a non-owner without a join record is a
   membership only the server can have made. Without a shared tenant the pin alone decides — for the moderation
   delivery, and for the invite accept against the inviting owner's chain the invite serves, walked from the head
   sealed in the link ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
   decision 19). The standings and the walk are
   [keys-grants-and-trust § The anchor, per tenant](../crosscutting/keys-grants-and-trust.md#the-anchor-per-tenant).

   An anomaly is **never wrapped to and never accepted as a granter**: a rotation declares that member pending, the
   grant-delivery worker **declines the job back to the server** ("do not re-offer this job to me"), and the member
   lists and the group add show *Can't confirm this key* with a note to compare safety numbers in person. There is
   **no accept**: a legitimate rotation is always signed, so the gate never fires for one, and it never blocks
   anybody's write. A key served without a head — the per-grant snapshot of a granter's key (Decision 6) — is
   judged by whether the chain this account walked from its pin holds it.

3. **Verification: a signed statement in the tenant, after an out-of-band compare.** Two users compare the
   **safety number** (over the identity) and the **chain code** (over the key-chain head) out-of-band; the ergonomic
   form is **QR** — scan the other device's code, and the app compares it against the key and head it anchors and
   gives feedback (matches / differs). A match writes a **verification**: a statement the verifier's identity signs —
   *in this tenant, I at my head confirmed you at yours* — sealed under the tenant key and read by every member of that
   tenant, so *verified by …* counts for everyone there and only there
   ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
   decisions 12–15 hold the record, when it counts and how a rotation of the verifier re-signs it; the compare and the
   badges are [keys-grants-and-trust § Verifications](../crosscutting/keys-grants-and-trust.md#verifications-and-what-a-member-sees)).
   Verification is optional trust elevation in the default flow, and its form is
   [uc.spaces.enforce-verified-identities](../features/spaces/use-cases/uc.spaces.enforce-verified-identities.md)'s
   main flow:
   - **Each member shows their own number, never a pairwise one.** The QR carries a veyq marker, the fingerprint hash
     and the head, never the identity bundle (the `jsqr` exception in `src/web/CLAUDE.md` is bounded by what our own
     payloads carry).
   - **Only an anchored key is verified.** The verification is written only while the compared key and head are the
     ones the directory serves and this device anchors in the tenant (Decision 2); nothing pins more than Decision 1
     does (no pin on a directory read).
   - **A contact's rotation drops *verified*.** A verification names the head it confirmed, so when the chain moves the
     contact's head (Decision 2) it stops counting by itself, and *verified* returns only through a new compare —
     shown, never silent. The verifier's own rotation does not drop theirs: it re-signs them with the new key.
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
   blind server adjudicating it. Its mechanics are [ADR 0060](0060-key-rotation-after-compromise.md)'s: **one mode**,
   the Master Key, the identity and the Secret Key rotating **together**, because the identity private key exists only
   under the Master Key and an identity-only rotation is (d) below. Every shared `K_SC` still rotates to exclude the
   old identity, and the rotator drives it, because an attacker holding only the old identity cannot make the chain
   step ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
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
     refused (`granter_key_unanchored`) and surfaced. A group's grant is judged in its tenant (the granter's join
     record, Decision 2) when that tenant is the active one, by the pin alone otherwise; the tenant scope's own grant
     from another member is judged by the pin alone, because the tenant's join records are read with the key that grant
     carries. A grant signed by a **retired** key of the granter's chain opens
     only at an epoch at or below this device's epoch floor for the scope (`granter_key_retired`): such grants exist
     from before the rotation, and a new epoch from a retired key is what a thief with the old identity would forge,
     while the honest rotator signs every new epoch with the new key. This is a **fourth pin surface**, the first on
     the receiving side.
   - **What it buys on a shared scope, honestly.** Where the pin alone decides, a first grant is a first-use pin of
     its granter, so an operator that fabricates both the granter and the key is not detected on the spot — only by
     the next compare of that granter's safety number — in a device's first grant of the scope: a later epoch must
     link down to the key the device held (ADR 0111 decision 23). In the active tenant a fabricated granter has no join record an
     owner signed and is refused as an anomaly, unless the server presents it as an owner (ADR 0111 § What stays
     open). What the signature rules out everywhere is a grant presented under the identity of someone the recipient
     has already pinned.
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
     It writes the empty document, so it costs what an empty store costs (every contact first-seen again; every
     device unnamed), and the confirmation says so. Verifications are not in it: they live in the tenant. The server can therefore withhold
     a document until the user resets it: a downgrade that is now consented, not silent. Another device heals
     by reading where its mark sits at or below the reset's revision; one whose mark sits above it (after a
     backup restore) refuses it as rolled back and is offered the same reset, because a device cannot tell a
     restore from an attack.
   - **A refusal is named and pauses, never declines.** A refused store is `trust_state_rolled_back` or
     `trust_state_unreadable`; the grant-delivery worker pauses on one rather than declining a job, and the nearest
     notice names it and links to the one reset.
   - **What it cannot see:** a device with no mark — one that signed in with the password and Secret Key, or
     lost its local storage — trusts the first store it is served, which is the first-use gap applied to trust
     state; and a newer store the server keeps from a device that never held it (a fork).

## Consequences

- **Closes the silent-swap-after-first-contact hole** for anyone who has interacted with a contact before: their
  client holds a pin, so a later swap the key chain does not reach is refused at every wrap and grant, and shown.
  Verification closes it fully for those who compare.
- **The first-use gap remains (honest).** If the operator substitutes a key **before** a client ever pinned the
  real one — and re-wraps genuine content too — nothing looks wrong until **manual verification** (the compare of
  Decision 3) or Key Transparency. TOFU cannot detect a lie told before first sight. Three things narrow the gap.
  A newcomer's first contact with its inviting owner is anchored by the owner's key-chain head inside the invite
  link's seal, which the operator that mails the link cannot open without the code, and the tenant key it is handed
  by the owner's own grant beside the invite
  ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decision 19). It does not reach the personal scope at all (Decision 6). And inside a tenant it is left only for
  owners: every other member is anchored by the owner-rooted join record they wrote at their accept, and a non-owner
  without one — a membership the operator invents — is an anomaly nobody wraps to (Decision 2). A root owner is still
  trusted on first use, and who is an owner is the server's word
  ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  § What stays open).
- **Grant-delivery is first-online-wins across key-holders, so a worker block is per key-holder — keyed on the
  declining account, not on the browser it declined from — and not a global veto.**
  A key-holder whose pin the chain does not reach the served key from declines; a *different* key-holder who never
  saw the contact has no pin → pins at first use and delivers. This is deliberate: the blind server **must not**
  adjudicate a crypto claim, and a server-side **global quarantine** on one worker's say-so would be a **DoS**
  (any device — including an attacker's — could veto delivery). The value is raised detection: everyone who knew
  the contact refuses, so the target stays behind the current key from them — a visible anomaly. A legitimate
  rotation is reached by every pin, so no key-holder declines it, and the rotation's publish clears the declines that
  targeted the rotator ([ADR 0060](0060-key-rotation-after-compromise.md) Decision 7).
- **Operator-blindness preserved for content, not for who verified whom.** The pin store is sealed under the master
  key; the server never learns who pinned whom — only how many pins an account holds, which the grant rows already
  let it count. A verification's content is sealed under the tenant key, but its row is addressed by tenant, verifier
  and subject, so the server learns **who verified whom in a tenant, and when**, never the heads they confirmed or
  whether the verification still counts (ADR 0111 § Consequences). It also gains a small **grant-delivery decline**
  status (a worker refusing a job) — operational metadata — and the key chain, which records rotations the server
  already knew of and is read the same way by every co-member.
- **Tier-consistent ([ADR 0026](0026-client-trust-tiers.md)).** Enforcement is client-side, so it holds against
  an active operator only on a client that operator does not serve, and there only as far as the pin store is
  fresh (Decision 7); on operator-served web it is honest-operator (a tampered client could ignore its own pin) —
  the same tier as all veyq E2EE, no authenticity-specific weakness.
- **Alternatives rejected:** (a) *a human accept of a changed key* — a legitimate rotation is a signed chain step,
  so it needs no human, and a change the chain does not reach is one no human can judge better than the signatures
  can; an accept button is what a substituted key would ask for. (b) *verify-before-first-wrap
  as the global default* — impossible for the human-less worker and would hold every wrap for a human; kept as
  opt-in strict. (c) *server-side pin store* — a who-trusts-whom graph is a metadata leak that breaks
  operator-blindness; the store is client-side + sealed. A verification is the exception, knowingly: it lives in the
  tenant so every member there can count it, and its address is the price (§ Operator-blindness above). (d) *master-key rotation alone for compromise recovery* —
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

**The granter's key as it signed.** Each grant row stores the signature beside a copy of the granter's identity
public bundle **as it was when the server wrote the grant**, the reads serve that copy, and the one unwrap path and the
switcher's icon read verify before the key is used. The served key decides nothing on its own — the recipient's chain
walk does (Decision 6). What the copy buys is that a granter who rotates their account keys leaves the grants they
signed verifiable under the retired key the chain still holds, at or below the recipient's floor. Serving the granter's
*current* key would fail every one of those grants as `bad_signature` before any chain is read. One limit stays:
- **A key is retired only in the chain this device walked.** A pin still at the old head matches the old key with no
  chain read, because the per-grant copy carries no head to say otherwise; the pin moves when a directory read serves
  the new head.

And one decision is not built:

- **Decision 4, strict mode.** It needs redesigning before it is wired: an *account-global* toggle cannot protect
  shared-scope content, because any co-member may key an unverified identity. The verification ceremony does not
  wait for it (Decision 3).

Explained for security researchers on the docs site: [Threat model](../../site/src/content/docs/security/threat-model.md), [Known limitations](../../site/src/content/docs/security/known-limitations.md).
