# ADR 0028 — Identity authenticity: TOFU pinning + safety-number verification

- Status: Accepted
- Date: 2026-07-07

## Context

Wrapping a scope key or an invite to a member needs that member's **identity public key**, which the
operator-blind server relays from its directory. An actively malicious operator can **substitute a key it
controls** (MITM) at the moment of a wrap and read what is sealed to it. veyq has so far left this **Plain**:
the wrapper trusts the returned key, no pin, no gate ([ADR 0020](0020-grant-distribution-invite-wrap.md),
[ADR 0014](0014-group-scopes-and-governance.md) §5 — deferred to this slice). The **fingerprint / safety
number** primitive already exists (`fingerprint()` — a Signal-style digest of the full identity public bundle
`lp(kemX) || lp(kemM) || lp(sign)`, vector-pinned + conformance-tested) but is **dormant**:
never called in production, no UI, no pin store.

Three surfaces wrap to a **foreign** identity key today, all Plain: (1) rotation re-grants, (2) the **automated
grant-delivery worker** (no human in the loop), (3) the group invite name-key wrap.

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

5. **Rotation seam (not built here).** **Identity rotation** — a new keypair, re-wrapping every grant to it — is
   already a named capability ([en.account.key-model](../features/account/enablers/en.account.key-model.md)
   §Identity rotation, master key kept); it is the **compromise-recovery** operation this model *enables*, because
   a rotated identity is a key change that contacts accept via (2)+(3). Whether the **master key also** rotates
   depends on the compromise: a password/device leak needs **identity-only** rotation (the master key bytes were
   never exposed), whereas a leak of the **master key itself** needs **both** (a new identity re-wrapped under the
   old, leaked master key would still be readable). Either way the **other members must rotate every shared `K_SC`**
   to exclude the old identity. The full mechanics are a **separate follow-up refine** that builds on this one.

## Consequences

- **Closes the silent-swap-after-first-contact hole** for anyone who has interacted with a contact before: their
  client holds a pin, so a later swap is surfaced (UI) or refused (worker). Verification closes it fully for those
  who compare.
- **The first-use gap remains (honest).** If the operator substitutes a key **before** a client ever pinned the
  real one — and re-wraps genuine content too — nothing looks wrong until **manual verification** or Key
  Transparency. TOFU cannot detect a lie told before first sight.
- **Grant-delivery is first-online-wins across key-holders, so a worker block is per-device, not a global veto.**
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
- **Tier-consistent ([ADR 0026](0026-client-trust-tiers.md)).** Enforcement is crypto-grade on a trusted client
  (self-hosted / trusted-web) and honest-operator on someone else's instance (a tampered client could ignore its own pin) —
  the same tier as all veyq E2EE, no authenticity-specific weakness.
- **Alternatives rejected:** (a) *hard-block every change by default* — locks out a legitimate rotation and adds
  friction; the human path is advisory instead (strict mode keeps the hard gate opt-in). (b) *verify-before-first-wrap
  as the global default* — impossible for the human-less worker and breaks the async awaiting-access model; kept as
  opt-in strict. (c) *server-side pin/verification store* — a who-trusts-whom graph is a metadata leak that breaks
  operator-blindness; the store is client-side + sealed. (d) *master-key rotation alone for compromise recovery* —
  useless without identity rotation (the master-key holder already holds the identity private key); identity
  rotation is the core, and the master key rotates too only when it was itself exposed (Decision 5) — in the
  follow-up refine. (e) *server-side global
  quarantine on a worker's decline* — DoS + the blind server cannot adjudicate the claim; a decline only removes
  that worker from the candidate set. (f) *Key Transparency now* — the correct end-state but far heavier; TOFU +
  verification is the near-term step it later subsumes.

## Open questions for the CR(s)

- The **sealed pin-store** shape + its sync (a new enabler): the wire model (one sealed blob vs per-contact rows),
  how it rides account sync, and merge across devices. It mirrors the vault/wraps sync — operator-blind.
- The **grant-delivery decline** contract/backend seam (a worker refusing + not being re-offered a job) and its
  human-review surface.
- The **verification ceremony** as a use-case (`uc.*.verify-identity`?) or folded into member-add — the QR
  compare + the *pinned* / *verified* / *changed* states in the directory UI.
- Which **E2 paragraphs** to rewrite (uc.spaces.add-group-member, uc.spaces.consent-group-add, uc.spaces.add-member)
  from "Plain" to this model, and where **strict mode** is configured (account-global vs per-group).
