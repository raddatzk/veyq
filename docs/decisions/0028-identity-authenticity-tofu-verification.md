# ADR 0028 — Identity authenticity: TOFU pinning + safety-number verification

- Status: Accepted; amended 2026-10-16 by [ADR 0086](0086-one-word-per-meaning.md): the pin store's and the device
  register's counter is a **revision** (`revision`, was `version`); by [ADR 0104](0104-the-identity-pin-store-is-one-sealed-entry-per-pin-under-a-sealed-head.md)
  (2026-10-19): the pin store is one sealed entry per pin under a sealed head — Decision 1's *what is pinned* and
  Decision 7's *which revision is marked* say so in place.
- Date: 2026-07-07

## Context

Wrapping a scope key or an invite to a member needs that member's **identity public key**, which the
operator-blind server relays from its directory. An actively malicious operator can **substitute a key it
controls** (MITM) at the moment of a wrap and read what is sealed to it. Until this record veyq left that
**Plain** — the wrapper trusted the returned key, no pin, no gate ([ADR 0020](0020-grant-distribution-invite-wrap.md),
[ADR 0014](0014-group-scopes-and-governance.md) §5 — deferred to this slice). The **fingerprint / safety
number** primitive already exists (`fingerprint()` — a Signal-style digest of the full identity public bundle
`lp(kem) || lp(sign)`, vector-pinned + conformance-tested) but was, when this record was
written, **dormant** — no UI, no pin store. It is now the safety number the changed-key review renders and
the check number device pairing compares ([ADR 0059](0059-device-linking.md) §5).

Three surfaces wrap to a **foreign** identity key, and all three were Plain when this was decided: (1) rotation
re-grants, (2) the **automated grant-delivery worker** (no human in the loop), (3) the group invite name-key
wrap. All three now run the gate below. A fourth surface sits on the *receiving* side: opening a grant means
trusting whoever sealed it (Decision 6).

The identity keypair is **stable per `userId`** — the master key survives password changes / recovery / new
devices, and it wraps the identity private key — **except** one legitimate case: **rotation after a compromise**
(a new identity, [en.account.key-model](../features/account/enablers/en.account.key-model.md)). So a key change
is "**attack OR legitimate rotation**", indistinguishable on-wire. And the whole scheme must stay
**operator-blind**: a server-side record of who-trusts-whom would leak the social graph.

## Decision

Adopt **trust-on-first-use (TOFU) pinning + out-of-band safety-number verification**, with the change-response
split by whether a human is present. Key Transparency stays the (out-of-scope) end-state.

1. **Pin on first use.** The first time a client wraps to a member's identity key it **pins** it (state:
   *pinned, unverified*) and proceeds. What is pinned is the **fingerprint hash of the full identity public
   bundle** (`kem`, the X-Wing encapsulation key over X25519 + ML-KEM-768, + `sign` Ed25519) — the domain-separated
   SHA-256 the safety number and its QR already are (Decision 3) — keyed by `userId` (identity is account-global, so
   one pin per contact across all tenants). Every reader of a pin compares it for equality with a served key or shows
   its safety number, both functions of that hash, and equality of the hash is equality of the bundle up to a SHA-256
   second preimage ([ADR 0104](0104-the-identity-pin-store-is-one-sealed-entry-per-pin-under-a-sealed-head.md)
   decision 3). The **pin store is client-side, synced across the user's devices, and sealed under the master key**
   — one sealed entry per pin at an opaque id under a sealed head (ADR 0104) — so the server stores only ciphertext
   and never sees a pin, a contact or the trust graph (operator-blind). It does see how many pins there are, as it
   always could (ADR 0104 decision 5).

2. **A key ≠ pin is surfaced, not silently trusted — response depends on the path:**
   - **Human path (UI):** a **notice**, *not* a hard block (a legitimate rotation must not lock a contact out).
     The user reviews and, on **accept**, re-pins (`pin := key`). The accept decision is informed by (3).
   - **Automated path (the grant-delivery worker — no human):** **block** — do **not** wrap `K_SC` to a changed
     key (delivery is irreversible). The worker **declines the job back to the server** ("do not re-offer this
     job to me") and raises a **human-review flag**; a human resolves it in the UI (accept → re-pin → the wrap
     then proceeds).

3. **Verification (elevate *pinned* → *verified*).** Two users compare the **safety number** out-of-band; the
   ergonomic form is **QR** — scan the other device's fingerprint, the app **compares it against the pin and gives
   the user feedback** (matches / differs). Verification is optional trust elevation in the default flow, and it
   is the **way to accept a legitimately rotated key** (re-verify → re-pin as *verified*). Reuses the existing
   QR-scan infrastructure (emergency kit). Its form is owner-decided (2026-10-14,
   [uc.spaces.enforce-verified-identities](../features/spaces/use-cases/uc.spaces.enforce-verified-identities.md)
   main flow):
   - **Show and compare.** Every member sees **their own** safety number (the per-identity 6x5 digits of the pinned
     `fingerprint` vector — no pairwise number, no new crypto) and a QR of it on their own profile page; on another
     member's profile they see that member's number, can **scan** the other's QR or compare the digits read aloud,
     and **mark verified**. The QR carries a veyq marker plus the fingerprint hash, never the identity bundle (the
     `jsqr` exception in `web/CLAUDE.md` is bounded by what our own payloads carry).
   - **Verifying pins.** A member with no pin yet is pinned **at** verification, with the key the human compare
     just confirmed; nothing else pins more than Decision 1 does (no pin on a directory read or a profile view).
   - **A verified contact's key change is louder.** The changed-key review names it as a **verified** contact's
     key; accepting re-pins as `pinned`, and `verified` returns only through a new compare — shown, never silent.
   - **Where it shows:** the person's profile and the tenant's and groups' member lists (a "verified" mark beside
     the existing "key changed").
   - **It does not wait for strict mode (4).** A "verified" mark that nothing enforces is not advisory noise:
     TOFU (2) detects only a key that changes **after** the first pin, so an operator that serves a substituted
     key **at first contact** goes unnoticed — the first-use gap below — and an out-of-band compare is the one
     thing in the tree that closes it until Key Transparency exists.

4. **Opt-in strict mode — decided in principle, not built.** A user (or a group owner) may require
   **verified-before-first-share** and turn the change-notice into a **hard block**. Under strict mode the worker
   **skips unverified members** (they stay *awaiting access*) until a human verifies them in the UI. This gives
   security-maximalists the pre-first-wrap gate without imposing its friction — or breaking the async
   "awaiting-access" model — on the default flow. It needs redesigning before it is wired (§ What is still open),
   on its own use-case,
   [uc.spaces.require-verified-identities](../features/spaces/use-cases/uc.spaces.require-verified-identities.md).

5. **Rotation is the compromise-recovery operation this model enables.** A rotated identity is a key change
   that contacts accept via (2)+(3) — which is what lets a legitimate rotation exist at all without a blind
   server adjudicating it. Its mechanics are settled in [ADR 0060](0060-key-rotation-after-compromise.md),
   which corrects the conditional this decision first carried: there is **one mode**, and the Master Key and the
   identity rotate **together**, because the identity private key exists only under the Master Key and so an
   identity-only rotation is (d) below. What this record still owns is the half rotation depends on — the
   **other members must rotate every shared `K_SC`** to exclude the old identity, and the rotator may **not**
   drive that re-grant themselves, or an attacker holding the old identity would re-admit only their new one.

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
   - **Shared scope: the granter's key is pinned like any contact's.** The recipient runs the Decision 1–2 gate
     on the granter: a first-seen key is pinned, a changed key is refused and surfaced. This is a **fourth pin
     surface**, the first on the receiving side.
   - **What it buys on a shared scope, honestly.** A brand-new member's first grant is a first-use pin of its
     granter, so an operator that fabricates both the granter and the key is not detected on the spot. It is
     detected by the next verification of that granter's safety number. What the signature does rule out is a
     grant presented under the identity of someone the recipient has already pinned.
   - The server stores and relays the signature and does not verify it. A blind server does not adjudicate a
     crypto claim (§ Consequences), and the recipient is the party that needs the answer.

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
  client holds a pin, so a later swap is surfaced (UI) or refused (worker). Verification closes it fully for those
  who compare.
- **The first-use gap remains (honest).** If the operator substitutes a key **before** a client ever pinned the
  real one — and re-wraps genuine content too — nothing looks wrong until **manual verification** (the compare of
  Decision 3) or Key Transparency. TOFU cannot detect a lie told before first sight. The gap is narrower since two later changes.
  It no longer lets the operator *invent* a member: every wrap to someone else needs an admission tag that only
  a key-holder can compute ([ADR 0020](0020-grant-distribution-invite-wrap.md) move 6). And it no longer reaches
  the personal scope at all (Decision 6).
- **Grant-delivery is first-online-wins across key-holders, so a worker block is per key-holder — keyed on the
  declining account, not on the browser it declined from — and not a global veto.**
  A key-holder who pinned the old key refuses + flags; a *different* key-holder who never saw the contact has no
  conflicting pin → TOFU-accepts the new key and delivers. This is deliberate: the blind server **must not**
  adjudicate a crypto claim, and a server-side **global quarantine** on one worker's say-so would be a **DoS**
  (any device — including an attacker's — could veto delivery). The value is raised detection: everyone who knew
  the contact refuses, so the target stays *awaiting access* from them — a visible anomaly the flag surfaces.
- **The advisory default trusts the human to actually check.** A user who reflexively accepts a change notice is
  no safer than Plain for that contact; strict mode (4), once built, is the answer for those who want enforcement.
  Stated, not hidden.
- **Operator-blindness preserved.** The pin store is sealed under the master key; the server never learns who
  pinned or verified whom — only how many pins an account holds, which the grant rows already let it count. It gains only a small **grant-delivery decline** status (a worker refusing a job) —
  operational metadata, no trust graph.
- **Tier-consistent ([ADR 0026](0026-client-trust-tiers.md)).** Enforcement is client-side, so it holds against
  an active operator only on a client that operator does not serve, and there only as far as the pin store is
  fresh (Decision 7); on operator-served web it is honest-operator (a tampered client could ignore its own pin) —
  the same tier as all veyq E2EE, no authenticity-specific weakness.
- **Alternatives rejected:** (a) *hard-block every change by default* — locks out a legitimate rotation and adds
  friction; the human path is advisory instead (strict mode keeps the hard gate opt-in). (b) *verify-before-first-wrap
  as the global default* — impossible for the human-less worker and breaks the async awaiting-access model; kept as
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
([en.account.identity-pinning](../features/account/enablers/en.account.identity-pinning.md)), the TOFU gate at
all three foreign-wrap surfaces, the grant-delivery **decline** seam, the changed-key review, and the compare on a
member's profile (`web/src/core/people/`) with the member lists' "verified" mark. Decision 5 is
settled by [ADR 0060](0060-key-rotation-after-compromise.md). Decision 6 is built: every grant producer signs,
each grant row stores the signature beside a copy of the granter's identity public bundle **as it was when the
server wrote the grant**, the reads serve that copy, and the one unwrap path and the switcher's icon read verify
before the key is used. The served key decides nothing on its own — the recipient's pin of it does, exactly as for
any other key the server serves. What the copy buys is that a granter who rotates their account keys
([ADR 0060](0060-key-rotation-after-compromise.md)) leaves the grants they signed verifiable under each
recipient's existing pin until the covering rotation replaces them. Serving the granter's *current* key would fail
every one of those grants as `bad_signature` before any pin is read, so an owner holding one could not open the
scope to perform the very rotation that replaces it. Two limits stay:
- **A recipient who accepts the granter's new key before the covering rotation reached them** refuses those grants
  as `granter_key_changed`, because their pin no longer holds the key that signed them. The retired key is never
  trusted again for them. Accepting a changed key therefore first runs one pass of the scope-rotation worker, so an
  owner who is online covers the scope before their pin moves
  ([uc.account.rotate-keys](../features/account/use-cases/uc.account.rotate-keys.md) E9). What that pass cannot
  cover waits for another member to complete it.
- **A grant whose granter had already rotated before the copy existed** was filled with the granter's key at that
  time, which did not sign it. It stays refused until a rotation or a delivery replaces it. No history of retired
  keys exists to do better.

Decision 7 is built for the pin store and the device register (`web/src/features/account/trustMarks.ts`): each
refusal is named (`trust_state_rolled_back`, `trust_state_unreadable`), the grant-delivery worker pauses on one
rather than declining, and the nearest existing notice names it and links to the settings hub's one reset.
Accepting a changed key clears the accepting account's own grant-delivery declines against that contact, so
Decision 2's *"the wrap then proceeds"* holds ([ADR 0060](0060-key-rotation-after-compromise.md) Decision 7). One
thing remains:

- **Decision 4, strict mode.** It needs redesigning before it is wired: an *account-global* toggle cannot protect
  shared-scope content, because any co-member may key an unverified identity. The verification ceremony does not
  wait for it (Decision 3).

Explained for security researchers on the docs site: [Threat model](../../site/src/content/docs/security/threat-model.md), [Known limitations](../../site/src/content/docs/security/known-limitations.md).
