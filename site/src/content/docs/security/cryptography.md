---
title: Cryptography
description: The primitives of suite v1, the X-Wing hybrid KEM and the wrap above it, the key-derivation profile, chunked AEAD, what a seal binds, and how the suite can change.
sources:
  - docs/crosscutting/security-model.md
  - docs/decisions/0017-crypto-primitives-and-post-quantum-strategy.md
  - docs/decisions/0072-a-seal-binds-its-object.md
  - docs/decisions/0013-blob-storage-presigned-direct-to-store.md
---

veyq's cryptography is one versioned suite, `v1`, built from standard primitives only. This page explains what it
uses and why. It does not restate a byte layout: those are normative in
[`packages/crypto/src/v1.ts`](https://github.com/raddatzk/veyq/blob/main/src/packages/crypto/src/v1.ts), the raw
primitives in [`packages/crypto/src/primitives.ts`](https://github.com/raddatzk/veyq/blob/main/src/packages/crypto/src/primitives.ts),
and the conformance vectors in
[`contracts/crypto/vectors/v1.json`](https://github.com/raddatzk/veyq/blob/main/contracts/crypto/vectors/v1.json).
The decision behind all of it is
[ADR 0017](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0017-crypto-primitives-and-post-quantum-strategy.md).

:::caution[Strong crypto is not a client-integrity answer]
All of this runs in the client. On a web client the operator serves, an active operator can ship code that reads
keys after they are opened, so there veyq holds against a passive operator only
([Start here](/veyq/security/start-here/)).
:::

## The stance

- **No home-grown cryptography.** Every construction is a standard one, implemented by the `@noble` libraries
  (`@noble/ciphers`, `@noble/curves`, `@noble/hashes`, `@noble/post-quantum`).
- **Post-quantum for encryption from the start.** Memories are meant to live for decades, so ciphertext harvested
  today and decrypted by a future quantum computer (*harvest now, decrypt later*) is a real threat. Every wrap to a
  public key uses a hybrid KEM that stays secure if **either** of its two components holds.
- **Classical signatures, on purpose.** A signature is verified once; there is nothing to harvest and decrypt
  later. Post-quantum urgency is therefore high for encryption and low for signatures, and veyq signs with Ed25519
  today. Hybrid ML-DSA signatures are on the [Roadmap](/veyq/security/roadmap/).
- **Content is AES-256-GCM alone.** The hybrid KEM wraps keys; it never touches content. A 256-bit AES key is
  already quantum-resistant.

## Primitives

| Primitive | Used for |
|---|---|
| **X-Wing** (X25519 + ML-KEM-768) | Wrapping a key to a public key: scope grants to a member's identity or to a member invite's invite key, an uploader's key to a Memory's drop key, the device-linking envelope to the new device's single-use transport key |
| **HKDF-SHA256** | Turning a KEM shared secret into a wrap key; deriving sub-keys from a parent key (for example the slug-token key, an invite key's two halves from its seed, the share write token, and a field's own key, `veyq/v1/field/<purpose>` with a fresh salt per write) |
| **AES-256-GCM** | All symmetric encryption: content frames, sealed metadata, key wraps |
| **Argon2id** | Deriving an unlock key from a password (with the Secret Key or a device factor), the login auth key, and the key that seals a member invite's link from its code or password |
| **Ed25519** | Login challenge signatures, the identity signing key that signs every grant and every invite key an owner mints, the Secret-Key proof key, an invite key's proof at accept and its signature on the join record |
| **HMAC-SHA256** | Keyed tokens the server stores but cannot reverse: per-parent name tokens |
| **SHA-256** | The safety number (a digest of a member's full identity public key, shown as 6 × 5 digits), and the stored verifiers of share passwords and write tokens |

## The hybrid KEM is X-Wing

The KEM is **X-Wing**, as specified in
[draft-connolly-cfrg-xwing-kem-11](https://datatracker.ietf.org/doc/html/draft-connolly-cfrg-xwing-kem-11), taken
whole: its combiner, its key generation from one 32-byte seed, and its encodings.

**Its status, plainly:** X-Wing is an **individual Internet-Draft** (Independent Submission, Informational). It is
not an RFC and not adopted by the CFRG. The CFRG's own generic `draft-irtf-cfrg-hybrid-kems` describes the same
combiner shape and cites X-Wing for its analysis.

**Why it was chosen** (ADR 0017 decision 2):

- It is the specified and formally analysed hybrid for exactly this pair, X25519 and ML-KEM-768.
- It has independent implementations (CryptoKit, BoringSSL, CIRCL, RustCrypto, and `@noble/post-quantum`, which
  the suite already used).
- Its combiner leaves the ML-KEM ciphertext out, and that rests on a proven property of ML-KEM rather than on an
  argument of veyq's own.

**How a moving draft is kept from moving the suite.** The `xwing` vector group holds the draft's own Appendix C
test vectors, copied verbatim from revision 11. Other implementations produced them, so this group is a
**cross-implementation anchor**: if a later draft changed X-Wing, veyq's bytes would not follow silently, and an
implementation that disagrees with the draft fails the test. Every other vector group is a single-implementation
regression pin ([Verify it yourself](/veyq/security/verify-it-yourself/)).

**What it replaced.** Before V1 the suite's hybrid KEM used a combiner of its own over separately generated X25519
and ML-KEM keys. Nothing found it unsound, but no document recorded a reason for it or an analysis of it, which
broke the suite's own rule against home-grown constructions. It was replaced while every database could still be
recreated and suite `v1` could change in place; ADR 0017 § Consequences records it and why only swapping the
combiner was rejected.

## The wrap above the KEM

Wrapping a key to a public key is a **KEM-DEM** composition, per recipient: X-Wing encapsulates to the recipient's
public key, the shared secret goes through HKDF-SHA256 together with the seal's context into a fresh AES-256-GCM
key, and that key seals the wrapped key with the context as authenticated data.

This is **HPKE-like in shape and not RFC 9180 HPKE**. Standardising the wrap layer too, with HPKE over a
post-quantum KEM (`draft-ietf-hpke-pq`), was considered and deferred: it is a working-group draft that is still
moving, and the layer above X-Wing is the common composition. ADR 0017 says to re-ask when that draft is an RFC.

A symmetric wrap (a key under another key, for example a node key under its parent's) is AES-256-GCM directly,
with the same context-derived authenticated data.

## Key derivation: one fixed Argon2id profile

Every password-derived key uses Argon2id with **one profile: t = 3 passes, m = 32 MiB, p = 1**, a 32-byte output
and a 16-byte random salt per wrap. The unlock key mixes the password with the Secret Key (or, for a trusted
device, a device-bound factor), so a password cracked from a database dump opens nothing. The login auth key is a
separate Argon2id derivation of the password alone, over a deterministic per-account salt, so login and unlock
never share a key.

**The server never chooses a cost parameter** (ADR 0017 rule 5). A wrap carries its profile so that a later
profile stays readable, but a client derives only with the profile it would write itself and refuses any other
before deriving. A served profile accepted as sent would let a server make every unlock run near-forever. The
vectors pin the production profile for the recovery key, the auth key and a member invite's link key, so a client
shipping different parameters fails conformance.

## Chunked AEAD for content

A blob is encrypted under its own random key, `K_blob`, in **frames**: every frame but the last carries exactly
256 KiB of plaintext, the last carries the rest, and an empty blob is one empty frame. Each frame is
AES-256-GCM with a fresh random 96-bit nonce, and its authenticated data is the frame's index and the total frame
count. So a dropped, reordered, duplicated or appended frame fails to open, and a truncated blob is detected.

Frames are independent, which is what makes resumable uploads and range-streamed playback possible: a client
uploads, retries and decrypts frame by frame, straight to and from the object store
([ADR 0013](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0013-blob-storage-presigned-direct-to-store.md)
§3). The frame size is a contract constant in the vectors (`chunk_frame_size`), because the backend derives the
frame count from the declared size and range streaming seeks on it.

Each `K_blob` is random, so identical files become different ciphertexts; deduplication across a space is declined
because it would show the operator which blobs are equal (ADR 0013 §5). The one exception is inside a single Memory or
chapter: a cover or banner equal to one of its gallery items shares that item's stored object, and the operator sees
which one ([What the server sees](/veyq/security/what-the-server-sees/)).

## What a seal binds

Every seal binds its **purpose** and **the identity of the object it is stored as**: the scope, and the node,
media, blob, bucket, share or member it belongs to, plus the epoch or revision the wire stores it at
([ADR 0072](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0072-a-seal-binds-its-object.md)). The
opener supplies those values from the address it fetched the object by, never from the ciphertext, so a
ciphertext the server moves to another object or another purpose fails to open. Where a key is unique to one
object (a frame under its `K_blob`, a share's fields under keys derived from its `K_share`), the key itself is the
binding.

A **field** (a node's name and meta, a medium's metadata, a bucket's, a guest's or a share's name, a share's note, a
member's name and picture) seals under a key of its own, `K_field = HKDF-SHA256(K_parent, salt,
"veyq/v1/field/" + purpose)`, where `K_parent` is the key the field belongs under and the 16-byte salt is fresh on
every write. Its envelope is `0x03 ‖ salt ‖ nonce ‖ ciphertext`, a type byte of its own that sits in the
authenticated data, so a field never opens as a plain seal or a wrap. One revision's `K_field` opens that revision
of that field and nothing else, which is the key a report of a field will hand over
([ADR 0093](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
§ 9).

The typed seal is the only way to write one. `@veyq/crypto` exports a `seal`/`open` pair, and a field seal beside it, whose
context is a typed union, one variant per purpose with exactly its binding fields; the raw AEAD and wrap primitives are not
exported, so an unbound seal cannot be written from the web client at all. Every purpose has exactly one vector,
and a test compares the purposes in code with the vector set in both directions.

Two limits are part of the decision. **Parents are not bound**: the tree's shape is server-visible, and a move
re-seals only the moved node, never its subtree. **Binding makes an epoch or a revision authentic, not fresh**:
which authentic one the server serves is still its choice, except where a device keeps a mark of its own
([Known limitations](/veyq/security/known-limitations/)).

## Suite agility

The suite has a **versioned id**, `v1`, and its seals are bound to it: `v1` is in the authenticated data of
every seal and wrap, and every derivation label reads `veyq/v1/…`, so a seal written under one suite never opens
as another's (ADR 0017 decision 3).

What is built and what is not:

- The suite id is stored **per account**, not in each artifact's bytes. That is enough while exactly one suite
  exists.
- The suite that comes second has to bring a **per-artifact label** first, so a reader can tell which suite wrote
  a blob. Until then nothing re-wraps old data onto a newer suite.
- The goal is additive upgrades: new artifacts use the current suite, old ones stay readable under the suite that
  wrote them, with no flag-day re-encryption. With one suite and a per-account id, that is the design, not yet a
  property of the bytes.

The vectors are what makes this enforceable: a refactor that moved a suite's bytes would fail a test instead of
silently orphaning stored ciphertext.
