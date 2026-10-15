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
   AES-256-GCM-class AEAD (already quantum-resistant at 256-bit). Wrapping to a public key is a KEM-DEM
   composition per recipient: the KEM's shared secret goes through HKDF-SHA256 with the seal's context into an
   AES-256-GCM key. That is HPKE-like in shape and **not** RFC 9180 HPKE, and the docs say so.
2. **Post-quantum from the start, by intent** — because decade-lived memories make **harvest-now-decrypt-later
   (HNDL)** a real threat. Key wrapping uses a **hybrid KEM (X25519 + ML-KEM-768)** — secure if *either*
   component holds — and that KEM is **X-Wing** as specified in
   [draft-connolly-cfrg-xwing-kem-11](https://datatracker.ietf.org/doc/html/draft-connolly-cfrg-xwing-kem-11),
   whole: its combiner `SHA3-256(ss_M ‖ ss_X ‖ ct_X ‖ pk_X ‖ label)`, its key generation from one 32-byte seed
   (the decapsulation key), and its encodings (encapsulation key `pk_M ‖ pk_X`, 1216 bytes; ciphertext
   `ct_M ‖ ct_X`, 1120 bytes; shared secret 32 bytes). Why X-Wing: it is the specified and formally analysed
   hybrid for exactly this pair, with independent implementations (CryptoKit, BoringSSL, CIRCL, RustCrypto,
   and `@noble/post-quantum`, which this suite already uses), and its omission of the ML-KEM ciphertext from
   the combiner rests on a proven property of ML-KEM, not on an argument of ours. Its status is honest to state:
   an **individual Internet-Draft** (Independent Submission, Informational), not an RFC and not CFRG-adopted;
   CFRG's own generic `draft-irtf-cfrg-hybrid-kems` describes the same combiner shape and cites X-Wing for its
   analysis. A draft can still move, so the suite pins the draft's own Appendix C vectors (decision 4) and a
   change to the draft cannot silently change ours. **Signatures** (login auth, key-directory, derivative-signing) are **not** HNDL-urgent —
   there is no harvest-now-decrypt-later for a one-time verification — so PQ urgency is deliberately asymmetric:
   **high for encryption (now)**, **low for signatures (Ed25519 now → hybrid ML-DSA later)**.
3. **Crypto-agility is non-negotiable.** A suite is named by a **versioned suite id**, and its seals are bound to
   it: `wrapAad` (`packages/crypto/src/primitives.ts`) puts `v1` into the authenticated data of every seal and
   wrap, and every derivation label reads `veyq/v1/…`, so a seal written under one suite never opens as another's.
   A media frame (`sealFrame`) binds only its index and count — it is reached through a content key that is
   itself sealed under `v1`.
   The id is stored **per account** (`veyq_user.suite_id`, sent as `suiteId` when the account is born), not in
   the bytes of each artifact — enough while exactly one suite exists. The goal stands: new artifacts use the
   current suite and old ones stay readable under the suite that wrote them, which is what makes adopting, and
   later upgrading, post-quantum crypto safe without breaking the model or forcing a re-encryption migration.
   The suite that comes second therefore has to bring the per-artifact label that tells a reader which suite
   wrote a blob; until then nothing re-wraps old data onto a newer suite.
4. **The conformance vectors are a suite's byte definition, and they come first.**
   `contracts/crypto/vectors/v1.json` is the single source of truth for what a suite *is* on the wire, and every
   seal purpose has exactly one vector ([ADR 0072](0072-a-seal-binds-its-object.md) Decision 6).
   `packages/crypto/src/v1.ts` is pinned to it by `packages/crypto/src/v1.conformance.test.ts`. Since the
   native surfaces were dropped (commit `7a1c4e9f`), that TypeScript test is the **only** stack asserting the file's
   seals, so for them it is a single-implementation **regression pin** — it stops a refactor from silently
   moving a suite's bytes, not a cross-stack parity proof. The backend performs no content crypto
   (`backend/CLAUDE.md`). Its Ed25519 login-auth check runs against the stored `authPublicKey` and never against
   this file. The statements it asserts too are the **Secret-Key proof** over a cold-credential request
   ([ADR 0060](0060-key-rotation-after-compromise.md) Decision 2) and the change-email **auth attestation**
   (`auth_attestation`, ADR 0074 Decision 8): both stacks build their signed bytes, so a vector pins them. Pinning a new primitive still means **adding a vector first, then making the
   implementation green** — never the reverse. One group is a **cross-implementation** anchor rather than a
   regression pin: the hybrid KEM is held to X-Wing's published vectors, which other implementations produced.
5. **The server never chooses a cost parameter.** A key-derivation profile travels with each wrap so that a
   later profile stays readable (rule 3), but a client derives only with a profile it would itself write — today
   exactly one Argon2id profile, the one the vectors pin — and refuses any other before deriving. A new profile
   reaches the client first and is written only after that. A salt is not a cost parameter; it is per wrap by
   design. Why: a served profile accepted as sent lets a server make every unlock run near-unbounded Argon2id
   (gigabytes, billions of passes). A downgrade is not open — the password-derived auth key uses no served
   parameters, and every served KEK mixes in the Secret Key or a device factor — so a ceiling alone would do; an
   exact profile is simpler and closes both. The check is the one decode every served profile passes through,
   `decodeKdfParams` in `packages/crypto/src/v1.ts`, and its refusal is not a wrong password: a screen says the
   unlock failed without blaming the credential.

## Consequences

- **HNDL is mitigated for *content* from day one** — a hybrid KEM means an eventual quantum adversary who
  harvested today's ciphertext still faces X25519 *and* ML-KEM-768.
- **Signatures may stay classical now and go hybrid later** without HNDL risk — a deliberate,
  documented asymmetry that avoids paying the PQ-signature size/perf cost before it buys anything.
- **The versioned suite id decouples the format from the algorithm** — upgrades are additive, old artifacts
  never break, and no flag-day re-encryption is needed, once the second suite labels its artifacts (rule 3);
  with one suite and a per-account id, that is the design, not yet a property of the bytes.
- **The vectors are what makes rule 3 enforceable.** A versioned suite id only keeps old artifacts readable if
  the bytes behind a version cannot quietly move. The vector file is that pin: a refactor that shifts them
  fails a red test instead of silently orphaning stored ciphertext. It was also meant as the seam between two
  stacks that must agree on those bytes; since the native drop the backend asserts against it only for the
  Secret-Key proof statements and the change-email attestation (rule 4), so for everything else that cross-stack-parity role is dormant until a
  second independent implementation exists.
- **Rejected — home-grown crypto:** unreviewed constructions are a foot-gun; rejected outright. The suite's
  first hybrid KEM broke this rule without saying so: its own combiner,
  `HKDF-SHA256(ikm = lp(ss_M) ‖ lp(ss_X), salt = SHA-256(lp(ct_M) ‖ lp(ct_X) ‖ lp(pk_M) ‖ lp(pk_X)), "veyq/v1/kem")`,
  over separately generated X25519 and ML-KEM keys. Nothing found it unsound, and it bound more than X-Wing
  does, but no doc recorded a reason for it or an analysis of it, and the first question of anyone reviewing the
  suite is why it is not the specified construction. It was replaced **before V1**, while every database is
  still recreated and suite `v1` could change in place; after V1 the same move costs a second suite and the
  per-artifact suite label of decision 3.
- **Rejected — only X-Wing's combiner over our own keys:** keeping the two separately generated keys and
  swapping only the combiner is not X-Wing — its key generation, encodings and published vectors would not
  apply, and the cross-implementation anchor of decision 4 is lost.
- **Rejected for now — HPKE with a post-quantum KEM (`draft-ietf-hpke-pq`):** it would standardise the wrap
  layer as well, but it is a working-group draft still moving, and the wrap layer above X-Wing is the common
  KEM-DEM composition (HKDF, then AES-256-GCM). Worth re-asking when that draft is an RFC.
- **Rejected — classical-only / defer PQ entirely:** rejected for *encryption*, because decade-lived memories
  make HNDL a real present threat; accepted (for now) only for *signatures*, per the asymmetry above.
- **Applied by [ADR 0012](0012-content-key-hierarchy.md)** (the key hierarchy this crypto protects); the
  low-urgency-PQ signature case is the auth keypair of [ADR 0003](0003-operator-blind-password-auth.md).

## Related

- [Security Model](../crosscutting/security-model.md) (the concern doc; now points here for the stance),
  [Account & Key Model](../features/account/enablers/en.account.key-model.md).
- [ADR 0003](0003-operator-blind-password-auth.md), [ADR 0012](0012-content-key-hierarchy.md).
- Security area of the docs site: [Verify it yourself](../../site/src/content/docs/security/verify-it-yourself.md), [Roadmap](../../site/src/content/docs/security/roadmap.md)
