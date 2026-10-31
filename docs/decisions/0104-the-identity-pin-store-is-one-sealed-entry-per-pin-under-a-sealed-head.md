# ADR 0104 — The identity-pin store is one sealed entry per pin, held together by a sealed head

- Status: Accepted
- Built: yes
- Date: 2026-10-19
- Model: [keys-grants-and-trust](../crosscutting/keys-grants-and-trust.md)
- Replaced in part: decision 2, decision 3 by ADR 0111 (built)

## Context

A key-holder's pins lived in **one** sealed blob (`symWrap(masterKey, {pins}, "identity-pins")`, bound to its revision),
capped by the contract at 1,048,576 base64 characters. A pin held the contact's full identity bundle — 1,256 bytes,
base64 inside JSON, then base64 again around the seal — and cost **2,361 bytes** of the cap (444 pins seal to
1,048,484). So the 445th member a key-holder admitted was refused with `400 invalid_request`, the grant-delivery
worker could not wrap `K_tenant` to anyone after that, and the error was swallowed: the member stayed *awaiting
access* with no sign why. An instance run for a club, an organisation or a company stopped at about 445 members per
key-holder, and every pin written re-uploaded the whole store — 1 MiB per new member at the cap.

Raising the cap moves the wall and keeps the O(N) write; a store per scope splits one contact's trust across scopes
(identity is account-global, ADR 0023); sharding the blob in k pieces is per-entry storage with a worse granularity.
The owner chose per-entry storage.

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
   server cannot move an entry to another contact's slot. The plaintext is compact JSON — what it holds beside the
   `userId` and the fingerprint (the contact's key-chain head, its index, the replacement mark) is ADR 0111 decisions
   15 and 16, its layout [en.account.identity-pinning](../features/account/enablers/en.account.identity-pinning.md) —
   padded to one fixed size, so any two contacts seal to the same size. There is no state: whether someone is
   verified is a signed statement in the tenant (ADR 0111 decision 12). The keys are single letters so the head fits
   inside that padding: spelled out, they would push an entry to the next padding size and the cap's whole store past
   the request limit below. An entry without a 32-byte head does not open.
3. **What an entry pins is the key-chain head and the identity's `fingerprintHash`** — the 32-byte, domain-separated
   SHA-256 over the full identity bundle that the safety number and its QR already are ([ADR 0028](0028-identity-authenticity-tofu-verification.md)
   Decision 3) — with the head's index, `firstSeenAt` and the replacement mark, not the bundle itself. The head is compared with a served one and
   is where the client walks the contact's chain from (ADR 0028 Decision 2). **This keeps the security claim:** every
   reader of a pin compares it for equality with a key the directory serves or shows its safety number, and both
   are functions of the hash; nothing wraps to, or verifies a signature with, the pinned bytes — the served key is
   what is used once it matches. Equality of the hash is equality of the bundle up to a SHA-256 second preimage, and
   a collision helps only a member against their own key. An entry shrinks to about an eighth of a bundle pin.
4. **The head holds the set together.** The existing store row becomes the **head**: sealed under the Master Key with the
   `identity-pins` purpose bound to its revision, as before, its plaintext a version marker, the entry count and a
   **digest of the whole set** — SHA-256 over `utf8("veyq/v1/identity-pin-set")` and, for every entry in `pinId` order,
   `lp(pinId) ‖ lp(SHA-256(entry ciphertext))`. Every write — one entry, many, a reset, a rotation — writes its entries
   and a new head in **one** transaction, compare-and-set on the head's revision, under the account row's lock so two
   racing first writes answer one success and one `409`. A reader opens the head under ADR 0028
   Decision 7 unchanged (one high-water mark, the head's revision), lists the entries, and recomputes the digest; a
   mismatch is a rollback when the head did not move meanwhile, a re-read when it did. So a withheld,
   added, rolled-back or swapped entry is refused exactly as a rolled-back store is.
   **Never an incremental digest** (XOR or sum of entry hashes): the server keeps every ciphertext an account ever
   wrote, and with a few hundred of them a combination matching a given XOR is a linear-algebra exercise.
5. **The count is server-visible, and always was.** The single blob was never padded, so its length told the
   operator the number of pins; the server also holds every grant's granter and recipient, from which it can count
   the identities a key-holder wrapped to. The count is stated as visible, not defended with dummy entries.
6. **Reads stay whole-set, cached by the head.** A device lists every entry once per head revision and keeps them for
   the session; a later read is the head alone, and the entries are re-listed only when its revision moved. A per-entry
   read with an inclusion proof (a Merkle tree over the ciphertext hashes the server can compute) would make a single
   lookup O(log N), and is rejected until a key-holder's list is large enough to be felt (about 300 KB at 1,000 pins).
7. **No migration: the single-blob store is dropped.** veyq was not live, so no instance held pins
   worth converting, and a read path kept for them would have been code with no reader; [ADR
   0085](0085-one-major-moved-on-a-yearly-train.md)'s major rule did not apply yet. The schema migration deletes the
   single-blob rows; a device that held one refuses the missing store as rolled back (ADR 0028 Decision 7) until its
   person starts the list over, and a head of any version but 2 does not open.

## Consequences

- **The ceiling moves from about 444 pins to the request size of the one write that carries all of them** — a rotation
  (ADR 0060 re-seals the store inside its transaction) or a reset. **The cap and the one request stay:** the contract
  bounds that write, and the store, at 25,000 entries, chosen so that the whole store fits the server's one body
  limit, which is global — every path answers a body past it alike, before authentication, and nothing is written.
  A rotation refused that way is a refusal the client names to its person as the Server Owner's limit
  (uc.account.rotate-keys E10). What securing the account adds beside the store fits too; its self-grants, re-signed
  invites and join-record endorsements do not ride in this request but are staged before it (ADR 0111 decisions 6,
  20 and 21). **The bound is guarded, not measured once:** `scripts/validate_rotation_shape.py` computes the longest
  body the rotation request admits from the contract's own caps and fails on a field without a cap or a total above
  the server's limit ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decision 22); the readings at the cap are the measuring suite's. The pin store stays in the publish: staging it
  would only matter for a higher cap. One new pin writes one entry instead of the whole store, and a pass over a
  member list writes all the pins it took in one write at its end.
- **What the operator learns per write changes shape, not amount.** It sees which entry was written, where it saw that
  the store was. Linking an entry to a contact by timing (a write right after a directory read of that contact) works
  for both shapes alike — for a single check; a pass's one write names its entries together — and the server already
  relays the grant the pin precedes.
- **A rotation now re-keys every entry**: a new Master Key is a new `K_pinIndex`, so new ids, re-sealed entries and a new
  head in the rotation's one request — the same work it did on one blob.
- **New state:** one row per entry, erased with the account
  ([ADR 0101](0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md)) — it needs no erasure-ledger
  kind of its own, because a restored row of it is erased again with its account; a new seal purpose and a new key
  label, each with its vector; a head plaintext of version 2 under the existing purpose.
- **Rejected:** a bigger cap (moves the wall, keeps the O(N) write); one store per scope (one contact's trust split per
  scope, against ADR 0023); fingerprint pins in the single blob (lifts the cap about eightfold and keeps the O(N) write —
  the owner chose per-entry); rows without a head (ADR 0028 Decision 7 lost per entry); dummy entries to hide the count
  (it was never hidden, decision 5); an incremental digest (decision 4); a lower cap (margin nobody needs, at the
  price of fewer members per key-holder); a chunked
  rotation (a staged set the server holds, cleans up and erases, against ADR 0060's one request, for a ceiling not
  reached); a body limit for that one endpoint (Quarkus has one global limit, so every other path would need a rule of
  its own in a pre-authentication filter).

Explained for security researchers on the docs site: [What the server sees](../../site/src/content/docs/security/what-the-server-sees.md), [Key hierarchy](../../site/src/content/docs/security/key-hierarchy.md), [Protocols](../../site/src/content/docs/security/protocols.mdx), [Threat model](../../site/src/content/docs/security/threat-model.md), [Known limitations](../../site/src/content/docs/security/known-limitations.md).

For Server Owners, why the request limit carries the cap: [Troubleshooting](../../site/src/content/docs/run/troubleshooting.md).
