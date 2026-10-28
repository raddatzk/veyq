# ADR 0104 — The identity-pin store is one sealed entry per pin, held together by a sealed head

- Status: Accepted 2026-10-19, built. The per-entry shape is the owner's decision (instead of raising the cap); so are
  the fingerprint hash per entry (decision 3), the count named openly (decision 5), no read path for the single-blob
  store (decision 7: veyq was not live) and keeping the cap and the rotation's one request once it was measured
  (§ Consequences, 2026-10-26). [ADR 0028](0028-identity-authenticity-tofu-verification.md) Decisions 1 and 7
  say what is pinned and which revision is marked in place. [ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) keeps this
  store's shape and its rollback rules and changes what an entry holds: per person, the highest key-chain head this
  account has seen (decision 2, built 2026-10-28). The personal *verified* state stays in the entry until ADR 0111
  decision 12's signed verifications are built.
- Date: 2026-10-19

Provenance: **(M)** measured when the store was built (`IdentityPinsSliceTest`, on H2 and on MariaDB); **(R)**
remembered.

## Context

A key-holder's pins live in **one** sealed blob (`symWrap(masterKey, {pins}, "identity-pins")`, bound to its revision),
capped by the contract at 1,048,576 base64 characters (M). A pin holds the contact's full identity bundle — 1,256 bytes,
base64 inside JSON, then base64 again around the seal — and costs **2,361 bytes** of the cap (M: 444 pins seal to
exactly the 1,048,484 the volume measurement read in `veyq_identity_pins`). So the 445th member a key-holder admits is
refused with `400 invalid_request`, the grant-delivery worker cannot wrap `K_tenant` to anyone after that, and the error
was swallowed (`grantDeliveryPolling.ts`, M): the member stayed *awaiting access* with no sign why (inbox #491). The
instance run for a club, an organisation or a company stops at about 445 members per key-holder. Every pin
written today also re-uploads the whole store — 1 MiB per new member at the cap.

Raising the cap moves the wall and keeps the O(N) write; a store per scope splits one contact's trust across scopes
(identity is account-global, ADR 0023); sharding the blob in k pieces is per-entry storage with a worse granularity.
**The owner chose per-entry storage** (2026-10-19).

Per-entry storage meets ADR 0028 Decision 7 head-on: the device's high-water mark protects **one** revision of **one**
document. With rows, the server could withhold one pin — which turns a detected key change back into a silent first
use — or serve an older copy of one row, reinstating a retired key as a match, while the store's revision stays honest.

## Decision

1. **One row per pin, under a keyed id.** An entry is stored at `pinId = HMAC-SHA256(K_pinIndex, utf8(userId))`, with
   `K_pinIndex = HKDF-SHA256(masterKey, "", "veyq/v1/identity-pin-index")` (vector `identity_pin`). The server sees an
   opaque 32-byte id per entry — 64 lowercase hex characters on the wire and in its column, because the database's
   default collation ignores case and a base64 id could meet another in case alone — stable while the Master Key is,
   unlinkable across accounts. Two devices that pin the same contact address the same row, so
   a concurrent first use is a revision conflict, not two entries.
2. **An entry is sealed under the Master Key with its own purpose, `identity-pin`, bound to its `pinId`** (ADR 0072).
   Its plaintext names the `userId`, and a reader refuses an entry whose `userId` does not derive its `pinId`, so the
   server cannot move an entry to another contact's slot. The plaintext is compact JSON `{u, f, h, s, t}` — the
   `userId`, the fingerprint, the contact's key-chain **head** (ADR 0111 decision 16), the state and `firstSeenAt` —
   padded with spaces to 192 bytes, so `pinned` and `verified` (and any two contacts) seal to the same size — 221
   bytes, 296 base64 characters. The keys are single letters so the head fits: the entry is 179 bytes (182 at the
   largest `firstSeenAt`), where the spelled-out keys would make it 211 and push it to the next padding size, 428 bytes
   a pin on the wire, and 25,000 of those past the request limit below. An entry without a 32-byte head does not
   open.
3. **What an entry pins is the key-chain head and the identity's `fingerprintHash`** — the 32-byte, domain-separated
   SHA-256 over the full identity bundle that the safety number and its QR already are ([ADR 0028](0028-identity-authenticity-tofu-verification.md)
   Decision 3) — with `state` and `firstSeenAt`, not the bundle itself (Q1). The head is compared with a served one and
   is where the client walks the contact's chain from (ADR 0028 Decision 2). **This keeps the security claim:** every
   reader of a pin compares it for equality with a key the directory serves or shows its safety number (M: five reads of a
   pin's key in `src/web/` — three equality comparisons, and two safety numbers, one of them also the input of the QR
   compare), and both are functions of the hash; nothing wraps to,
   or verifies a signature with, the pinned bytes — the served key is what is used once it matches. Equality of the
   hash is equality of the bundle up to a SHA-256 second preimage, and a collision helps only a member against their
   own key. An entry shrinks from about 2,361 to 296 bytes (M).
4. **The head holds the set together.** The existing store row becomes the **head**: sealed under the Master Key with the
   `identity-pins` purpose bound to its revision, as today, its plaintext a version marker, the entry count and a
   **digest of the whole set** — SHA-256 over `utf8("veyq/v1/identity-pin-set")` and, for every entry in `pinId` order,
   `lp(pinId) ‖ lp(SHA-256(entry ciphertext))`. Every write — one entry, many, a reset, a rotation — writes its entries
   and a new head in **one** transaction, compare-and-set on the head's revision, under the account row's lock so two
   racing first writes answer one success and one `409`. A reader opens the head under ADR 0028
   Decision 7 unchanged (one high-water mark, the head's revision), lists the entries, and recomputes the digest; a
   mismatch is `trust_state_rolled_back` when the head did not move meanwhile, a re-read when it did. So a withheld,
   added, rolled-back or swapped entry is refused exactly as a rolled-back store is today.
   **Never an incremental digest** (XOR or sum of entry hashes): the server keeps every ciphertext an account ever
   wrote, and with a few hundred of them a combination matching a given XOR is a linear-algebra exercise.
5. **The count is server-visible, and always was** (Q2). The single blob was never padded (M: its length is 29 + 1,771 ×
   n before base64), so its size told the operator the number of pins; the server also holds every grant's granter and
   recipient, from which it can count the identities a key-holder wrapped to. ADR 0028's and the enabler's "nor the
   count" is corrected to that, not defended with dummy entries.
6. **Reads stay whole-set, cached by the head.** A device lists every entry once per head revision and keeps them for
   the session; a later read is the head alone, and the entries are re-listed only when its revision moved. A per-entry
   read with an inclusion proof (a Merkle tree over the ciphertext hashes the server can compute) would make a single
   lookup O(log N), and is rejected until a key-holder's list is large enough to be felt (about 300 KB at 1,000 pins).
7. **No migration: the single-blob store is dropped** (owner, 2026-10-19). veyq was not live, so no instance held pins
   worth converting, and a read path kept for them would have been code with no reader; [ADR
   0085](0085-one-major-moved-on-a-yearly-train.md)'s major rule did not apply yet. The schema migration deletes the
   single-blob rows; a device that held one refuses the missing store as rolled back (ADR 0028 Decision 7) until its
   person starts the list over, and a head of any version but 2 does not open.

## Consequences

- **The ceiling moves from about 444 pins to the request size of the one write that carries all of them** — a rotation
  (ADR 0060 re-seals the store inside its transaction) or a reset. The contract bounds that write, and the store, at
  25,000 entries, against Quarkus 3.36's default body limit of 10,485,760 bytes (`quarkus.http.limits.max-body-size` is
  set nowhere; `RequestTooLargeRoutes` answers it alike on every path). **Measured 2026-10-26** (M, the real
  `rotateKeys` against a live backend, `rotationAtPinCap.measure.e2e.test.ts`): a rotation costs **384 bytes a pin and
  about 2,700 beside them**, so at the cap it sends **9,602,700 bytes (91.6 %)** and is answered `200`. With every
  other field at its contract maximum — the identity bundle and its wrapped private key, both cold wraps, the
  signature, the device register, the head — it would send about **9.70 MB (92.5 %)**, so the limit carries
  **≈ 27,000 pins** even then (`IdentityPinsSliceTest` holds that worst case against the configured limit). A body past
  the limit is `413 request_too_large`, answered on its `Content-Length` before authentication, and nothing is written.
  A browser reads that answer while it is still sending (**measured 2026-10-27**, inbox #553: headless Chrome 156 over
  HTTP/1.1, bodies of 1.1×, 2× and 10× the limit, 30 of 30 direct to the jar, 29 of 30 through a Traefik v3.6 whose
  thirtieth was its own `502`; no `fetch` rejected), so the client maps it to a refusal of its own that names the Server
  Owner (uc.account.rotate-keys E10). undici, still sending, sees `ECONNRESET` instead.
  **The cap and the one request stay** (owner, 2026-10-26): the measured margin is 883 KB. Securing the account
  ([ADR 0060](0060-key-rotation-after-compromise.md)) adds fixed fields of 2,424 bytes and 39 bytes per scope this
  device cannot open (at most 10,000), measured with the real encoders on 2026-10-28: about 9,605,224 bytes at the cap
  with nothing unreadable, and about 10,092,524 with every field at its contract cap — still under the limit. Its
  self-grants do not ride in this request; they are staged before it (ADR 0111 decision 6). Above the cap a rotation
  would need the pin store staged as well (inbox #569). One new pin writes about 400 bytes instead of the whole store.
- **What the operator learns per write changes shape, not amount.** It sees which entry was written; today it sees that
  the store was. Linking an entry to a contact by timing (a write right after a directory read of that contact) works
  for both shapes alike, and the server already relays the grant the pin precedes.
- **A rotation now re-keys every entry**: a new Master Key is a new `K_pinIndex`, so new ids, re-sealed entries and a new
  head in the rotation's one request — the same work it does today on one blob.
- **New state:** a table of `(account_id, pin_id, sealed_entry)` rows, erased with the account
  ([ADR 0101](0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md)) — it needs no erasure-ledger
  kind of its own, because a restored row of it is erased again with its account; a new seal purpose and a new key
  label, each with its vector; a head plaintext of version 2 under the existing purpose.
- **Rejected:** a bigger cap (moves the wall, keeps the O(N) write); one store per scope (one contact's trust split per
  scope, against ADR 0023); fingerprint pins in the single blob (lifts the cap about eightfold and keeps the O(N) write —
  the owner chose per-entry); rows without a head (ADR 0028 Decision 7 lost per entry); dummy entries to hide the count
  (it was never hidden, decision 5); an incremental digest (decision 4). And, once the cap's rotation was measured
  (owner, 2026-10-26): a lower cap (margin nobody needs, at the price of fewer members per key-holder); a chunked
  rotation (a staged set the server holds, cleans up and erases, against ADR 0060's one request, for a ceiling not
  reached); a body limit for that one endpoint (Quarkus has one global limit, so every other path would need a rule of
  its own in a pre-authentication filter).

Explained for security researchers on the docs site: [What the server sees](../../site/src/content/docs/security/what-the-server-sees.md), [Key hierarchy](../../site/src/content/docs/security/key-hierarchy.md), [Protocols](../../site/src/content/docs/security/protocols.mdx), [Threat model](../../site/src/content/docs/security/threat-model.md), [Known limitations](../../site/src/content/docs/security/known-limitations.md).

For Server Owners, why the request limit carries the cap: [Troubleshooting](../../site/src/content/docs/run/troubleshooting.md).
