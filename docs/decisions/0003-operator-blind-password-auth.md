# ADR 0003 — Operator-blind password authentication (password-derived Ed25519 keypair + challenge-response)

- Status: Accepted
- Built: yes
- Date: 2026-06-25
- Model: [en.account.key-model](../features/account/enablers/en.account.key-model.md)

## Context

The server is operator-blind, so it should never see the user's **raw password** — a high-value,
often-reused secret. Two tempting designs fall short:

- **Model A (the conventional one):** the client sends the password; the server runs Argon2id and stores/compares a
  verifier. Simple, but the operator sees the raw password (reusable elsewhere) at every login/reset.
- **Model B-naive:** the client sends `V = Argon2id(password, salt)` and the server stores `H(V)`. But
  then `V` is a **transmitted, replayable bearer credential**, and the stored value is a credential
  hash — exactly the property we want to avoid.

We want: the server never sees the password; the stored value is **not** a credential; nothing
replayable is transmitted; only primitives already in the suite; minimal server-side crypto.

## Decision

Authenticate with a **password-derived signing keypair + challenge-response**:

- **Derive:** `seed = Argon2id(password, authSalt)`, then an **Ed25519** keypair `(authPriv, authPub)`
  from the seed (domain-separated from the unlock KEK, which is `Argon2id(password + Secret Key, …)`).
- **Register (setup):** the client sends only `authPublicKey`. The server stores only the public key.
- **Login:** challenge-response — the server issues a single-use nonce; the client signs it with
  `authPriv`; the server verifies against `authPublicKey` and issues a session.
- **Salt:** `authSalt` is derived deterministically (e.g. `HKDF(instanceSalt, normalizedEmail)`), so the
  server behaves identically for existing and non-existing accounts → **non-enumerable**.
- **PQ posture:** Ed25519 now; migrate to a PQ/hybrid signature (ML-DSA) later via a suite bump. There
  is **no harvest-now-decrypt-later for authentication** (a signature is a one-time live check), and
  operator-blindness bounds the blast radius of an auth break to a session over ciphertext. See the
  [Security Model](../crosscutting/security-model.md) cryptographic stance.

## Consequences

- **The server never sees the raw password** — at setup, login or reset. Strictly better than Model A
  (a malicious server learns nothing reusable on other services or other veyq instances).
- **The stored `authPublicKey` is not a credential.** A DB dump yields only a public key; forging a
  login means breaking Ed25519 (~2¹²⁸) or brute-forcing the password through Argon2id. So security =
  **password strength × Argon2id work factor**, and nothing cheaper leaks.
- **Nothing replayable is transmitted** (a signature over a fresh nonce, not a bearer secret).
- **No new primitive, minimal server crypto.** Uses Argon2id + Ed25519 (already in the suite); the
  server only stores a public key, issues a nonce and verifies a signature — no OPAQUE/PAKE state machine.
- **Weak passwords stay crackable** at Argon2id cost — inherent to any password scheme; mitigated by the
  Argon2id work factor, and content stays protected by the Secret Key regardless ([2SKD](../crosscutting/security-model.md)).
- **Password change / reset** re-derives the keypair and re-registers `authPublicKey` (plus re-wraps the
  PASSWORD Master-Key copy); the password is never sent.
- **Rejected:** Model A (server sees password); Model B-naive (`V` is a replayable bearer credential);
  OPAQUE/PAKE (cross-stack byte-identical conformance cost + server-side protocol crypto, marginal
  benefit when TLS is assumed).
