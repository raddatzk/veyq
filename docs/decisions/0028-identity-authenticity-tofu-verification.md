# ADR 0028 — Identity authenticity: TOFU pinning + safety-number verification

- Status: Accepted
- Date: 2026-07-07

## Context

Wrapping a scope key or an invite to a member needs that member's **identity public key**, which the
operator-blind server relays from its directory. An actively malicious operator can **substitute a key it
controls** (MITM) at the moment of a wrap and read what is sealed to it. Until this record veyq left that
**Plain** — the wrapper trusted the returned key, no pin, no gate ([ADR 0020](0020-grant-distribution-invite-wrap.md),
[ADR 0014](0014-group-scopes-and-governance.md) §5 — deferred to this slice). The **fingerprint / safety
number** primitive already exists (`fingerprint()` — a Signal-style digest of the full identity public bundle
`lp(kemX) || lp(kemM) || lp(sign)`, vector-pinned + conformance-tested) but was, when this record was
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
   *pinned, unverified*) and proceeds. What is pinned is the **full identity public bundle** (`kemX` X25519 +
   `kemM` ML-KEM-768 + `sign` Ed25519), keyed by `userId` (identity is account-global, so one pin per contact
   across all tenants). The **pin store is client-side, synced across the user's devices, and sealed under the
   master key** — the server stores only ciphertext and never sees a pin or the trust graph (operator-blind).

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
   QR-scan infrastructure (emergency kit).

4. **Opt-in strict mode.** A user (or a group owner) may require **verified-before-first-share** and turn the
   change-notice into a **hard block**. Under strict mode the worker **skips unverified members** (they stay
   *awaiting access*) until a human verifies them in the UI. This gives security-maximalists the pre-first-wrap
   gate without imposing its friction — or breaking the async "awaiting-access" model — on the default flow.

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
   Binding the store's version into its seal ([ADR 0072](0072-a-seal-binds-its-object.md)) makes that version
   authentic, not fresh. So each device keeps a **local high-water mark** — the highest pin-store version it has
   opened — and refuses a served store below it, on every read **and before every write**, because a write
   re-seals what it read and would otherwise make the rollback its own. It refuses a missing store once it has
   held one, and it refuses a store that fails to open instead of reading it as empty: an empty store is exactly
   what turns a detected key change back into a silent first use, and a rolled-back one can reinstate a retired
   key as a match.
   - **The same rule covers the other sealed account document**, the device register
     ([en.account.device-register](../features/account/enablers/en.account.device-register.md)), and the
     per-scope epoch floor of [ADR 0024](0024-scope-key-rotation.md) Decision 6 uses the same mechanism.
   - **The marks are per device and local, never synced.** A synced mark is one more thing the server can roll
     back. A device linked by another ([ADR 0059](0059-device-linking.md)) inherits that device's marks inside
     the authenticated pairing envelope.
   - **What it cannot see:** a device with no mark — one that signed in with the password and Secret Key, or
     lost its local storage — trusts the first store it is served, which is the first-use gap applied to trust
     state; and a newer store the server keeps from a device that never held it (a fork).

## Consequences

- **Closes the silent-swap-after-first-contact hole** for anyone who has interacted with a contact before: their
  client holds a pin, so a later swap is surfaced (UI) or refused (worker). Verification closes it fully for those
  who compare.
- **The first-use gap remains (honest).** If the operator substitutes a key **before** a client ever pinned the
  real one — and re-wraps genuine content too — nothing looks wrong until **manual verification** or Key
  Transparency. TOFU cannot detect a lie told before first sight. The gap is narrower since two later changes.
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
  no safer than Plain for that contact; strict mode (4) is the answer for those who want enforcement. Stated, not
  hidden.
- **Operator-blindness preserved.** The pin store is sealed under the master key; the server never learns who
  pinned or verified whom. It gains only a small **grant-delivery decline** status (a worker refusing a job) —
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
  verification is the near-term step it later subsumes.

## What is still open

Decisions 1–3 are built: the sealed pin store and its sync
([en.account.identity-pinning](../features/account/enablers/en.account.identity-pinning.md)), the TOFU gate at
all three foreign-wrap surfaces, the grant-delivery **decline** seam, and the changed-key review. Decision 5 is
settled by [ADR 0060](0060-key-rotation-after-compromise.md). Decision 6 is built: every grant producer signs,
the grant rows store and serve the signature with the granter's current key, and the one unwrap path and the
switcher's icon read verify before the key is used. Two consequences of verifying under the granter's *current*
key are deliberate: a granter who rotates their account keys leaves the grants they signed unverifiable until
the rotation's re-grant reaches the recipient, and the recipient's pin of them reads `changed` until a human
accepts it — both are the Decision 1–2 behaviour for a changed key, not new failure modes. Decision 7 is decided and not yet built (inbox #161 (c)): today a device keeps no mark, reads a
store that does not open as empty, and re-seals whatever copy it was served, so the server can wipe or roll
back any account's pins — every detected key change becomes a first use again, and a rolled-back pin makes a
retired key match. Two further things remain:

- **Decision 4, strict mode** — and with it the proactive verification ceremony's UI, deliberately unbuilt
  ([uc.spaces.enforce-verified-identities](../features/spaces/use-cases/uc.spaces.enforce-verified-identities.md)
  is refined and its pin-store primitives ship; the entry point does not). It needs redesigning before it is wired: an
  *account-global* toggle cannot protect shared-scope content, because any co-member may key an unverified
  identity.
- **Accepting a changed key does not clear the declining worker's exclusion**, so Decision 2's *"the wrap then
  proceeds"* does not hold yet. Harmless while every changed key is an attack; on the critical path the moment
  a legitimate rotation exists ([ADR 0060](0060-key-rotation-after-compromise.md) Decision 7).
