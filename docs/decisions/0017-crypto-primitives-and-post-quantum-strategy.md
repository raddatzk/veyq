# ADR 0017 — Cryptographic primitives & post-quantum strategy

- Status: Accepted
- Date: 2026-07-01

## Context

veyq stores memories meant to live for **decades**, end-to-end encrypted, under an operator-blind server. The
choice of **which cryptographic primitives** to use and **what posture to take toward quantum computers** is a
durable, foundational decision that outlives any single feature — and it had been stated only inside the
[Security Model](../crosscutting/security-model.md), a descriptive concern doc, not recorded as a decision.

This ADR records the **strategy**. The **concrete byte contents** of a suite (e.g. `v1`) are settled in the
first crypto change-request and pinned as conformance vectors (rule 4 below) — the vectors *realize* this
strategy; they do not choose it.

## Decision

1. **Standard, well-reviewed primitives only — no home-grown crypto.** Symmetric content encryption is an
   AES-256-GCM-class AEAD (already quantum-resistant at 256-bit); key wrapping is per-recipient, HPKE-style
   multi-recipient.
2. **Post-quantum from the start, by intent** — because decade-lived memories make **harvest-now-decrypt-later
   (HNDL)** a real threat. Key wrapping targets a **hybrid KEM (X25519 + ML-KEM-768)** — secure if *either*
   component holds. **Signatures** (login auth, key-directory, derivative-signing) are **not** HNDL-urgent —
   there is no harvest-now-decrypt-later for a one-time verification — so PQ urgency is deliberately asymmetric:
   **high for encryption (now)**, **low for signatures (Ed25519 now → hybrid ML-DSA later)**.
3. **Crypto-agility is non-negotiable.** Every wrapped key and encrypted artifact carries a **versioned suite
   id**. New artifacts use the current suite; old artifacts stay readable under their stored suite id — this is
   what makes adopting, and later upgrading, post-quantum crypto safe without breaking the model or forcing a
   re-encryption migration.
4. **The conformance vectors are a suite's byte definition, and they come first.**
   `contracts/crypto/vectors/v1.json` is the single source of truth for what a suite *is* on the wire, and every
   seal purpose has exactly one vector ([ADR 0072](0072-a-seal-binds-its-object.md) Decision 6).
   `packages/crypto/src/v1.ts` is pinned to it by `packages/crypto/src/v1.conformance.test.ts`. Since the
   native surfaces were dropped (commit `7a1c4e9f`), that web test is the **only** stack asserting the file's
   seals, so for them it is a single-implementation **regression pin** — it stops a refactor from silently
   moving a suite's bytes, not a cross-stack parity proof. The backend performs no content crypto
   (`backend/CLAUDE.md`). Its Ed25519 login-auth check runs against the stored `authPublicKey` and never against
   this file. The one statement it asserts too is the **Secret-Key proof** over a cold-credential request
   ([ADR 0060](0060-key-rotation-after-compromise.md) Decision 2): both stacks build its signed bytes, so a
   vector pins them. Pinning a new primitive still means **adding a vector first, then making the
   implementation green** — never the reverse.

## Consequences

- **HNDL is mitigated for *content* from day one** — a hybrid KEM means an eventual quantum adversary who
  harvested today's ciphertext still faces X25519 *and* ML-KEM-768.
- **Signatures may stay classical now and go hybrid later** without HNDL risk — a deliberate,
  documented asymmetry that avoids paying the PQ-signature size/perf cost before it buys anything.
- **The versioned suite id decouples the format from the algorithm** — upgrades are additive, old artifacts
  never break, and no flag-day re-encryption is needed.
- **The vectors are what makes rule 3 enforceable.** A versioned suite id only keeps old artifacts readable if
  the bytes behind a version cannot quietly move. The vector file is that pin: a refactor that shifts them
  fails a red test instead of silently orphaning stored ciphertext. It was also meant as the seam between two
  stacks that must agree on those bytes; since the native drop the backend asserts against it only for the
  Secret-Key proof statement (rule 4), so for everything else that cross-stack-parity role is dormant until a
  second independent implementation exists.
- **Rejected — home-grown crypto:** unreviewed constructions are a foot-gun; rejected outright.
- **Rejected — classical-only / defer PQ entirely:** rejected for *encryption*, because decade-lived memories
  make HNDL a real present threat; accepted (for now) only for *signatures*, per the asymmetry above.
- **Applied by [ADR 0012](0012-content-key-hierarchy.md)** (the key hierarchy this crypto protects); the
  low-urgency-PQ signature case is the auth keypair of [ADR 0003](0003-operator-blind-password-auth.md).

## Related

- [Security Model](../crosscutting/security-model.md) (the concern doc; now points here for the stance),
  [Account & Key Model](../features/account/enablers/en.account.key-model.md).
- [ADR 0003](0003-operator-blind-password-auth.md), [ADR 0012](0012-content-key-hierarchy.md).
