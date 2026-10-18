# ADR 0104 — The identity-pin store is one sealed entry per pin, held together by a sealed head

- Status: Proposed 2026-10-19. The per-entry shape is the owner's decision (2026-10-19, instead of raising the cap); the
  head, the entry's content, the count and the legacy path carry `cr-identity-pins-per-entry`'s owner questions Q1–Q3
  as their recommendations, and the ADR becomes Accepted with the answers. Nothing is built. When built, it rewrites
  [ADR 0028](0028-identity-authenticity-tofu-verification.md) Decision 1's "what is pinned" (if Q1 is taken) and
  Decision 7's "the store's revision" (the head's), in place.
- Date: 2026-10-19

Provenance: **(M)** measured in the worktree `cr-identity-pins-per-entry` names — the command is in its grounding
table; **(R)** remembered, measured by the CR before it binds.

## Context

A key-holder's pins live in **one** sealed blob (`symWrap(masterKey, {pins}, "identity-pins")`, bound to its revision),
capped by the contract at 1,048,576 base64 characters (M). A pin holds the contact's full identity bundle — 1,256 bytes,
base64 inside JSON, then base64 again around the seal — and costs **2,361 bytes** of the cap (M: 444 pins seal to
exactly the 1,048,484 the volume measurement read in `veyq_identity_pins`). So the 445th member a key-holder admits is
refused with `400 invalid_request`, the grant-delivery worker cannot wrap `K_tenant` to anyone after that, and the error
is swallowed (`grantDeliveryPolling.ts`, M): the member stays *awaiting access* with no sign why (inbox #491). The
*club, organisation or company* operating mode (ADR 0093 § 14) stops at about 445 members per key-holder. Every pin
written today also re-uploads the whole store — 1 MiB per new member at the cap.

Raising the cap moves the wall and keeps the O(N) write; a store per scope splits one contact's trust across scopes
(identity is account-global, ADR 0023); sharding the blob in k pieces is per-entry storage with a worse granularity.
**The owner chose per-entry storage** (2026-10-19).

Per-entry storage meets ADR 0028 Decision 7 head-on: the device's high-water mark protects **one** revision of **one**
document. With rows, the server could withhold one pin — which turns a detected key change back into a silent first
use — or serve an older copy of one row, reinstating a retired key as a match, while the store's revision stays honest.

## Decision

1. **One row per pin, under a keyed id.** An entry is stored at `pinId = HMAC-SHA256(K_pinIndex, utf8(userId))`, with
   `K_pinIndex` derived from the Master Key under its own label. The server sees an opaque 32-byte id per entry, stable
   while the Master Key is, unlinkable across accounts. Two devices that pin the same contact address the same row, so
   a concurrent first use is a revision conflict, not two entries.
2. **An entry is sealed under the Master Key with its own purpose, `identity-pin`, bound to its `pinId`** (ADR 0072).
   Its plaintext names the `userId`, and a reader refuses an entry whose `userId` does not derive its `pinId`, so the
   server cannot move an entry to another contact's slot. The plaintext is padded to one constant length, so `pinned`
   and `verified` (and any two contacts) seal to the same size.
3. **What an entry pins is the identity's `fingerprintHash`** — the 32-byte, domain-separated SHA-256 over the full
   identity bundle that the safety number and its QR already are ([ADR 0028](0028-identity-authenticity-tofu-verification.md)
   Decision 3) — with `state` and `firstSeenAt`, not the bundle itself (Q1). **This keeps the security claim:** every
   reader of a pin compares it for equality with a key the directory serves or shows its safety number (M: five reads of a
   pin's key in `web/` — three equality comparisons, and two safety numbers, one of them also the input of the QR
   compare), and both are functions of the hash; nothing wraps to,
   or verifies a signature with, the pinned bytes — the served key is what is used once it matches. Equality of the
   hash is equality of the bundle up to a SHA-256 second preimage, and a collision helps only a member against their
   own key. An entry shrinks from about 2,361 to about 300 bytes.
4. **The head holds the set together.** The existing store row becomes the **head**: sealed under the Master Key with the
   `identity-pins` purpose bound to its revision, as today, its plaintext a version marker, the entry count and a
   **digest of the whole set** — SHA-256 over `utf8("veyq/v1/identity-pin-set")` and, for every entry in `pinId` order,
   `lp(pinId) ‖ lp(SHA-256(entry ciphertext))`. Every write — one entry, many, a reset, a rotation — writes its entries
   and a new head in **one** transaction, compare-and-set on the head's revision. A reader opens the head under ADR 0028
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
7. **Migration is the client's, on the next unlock** (Q3). A device that opens a version-1 store writes its pins as
   entries plus a version-2 head at the next revision, in one write, as it would any change; the server cannot, it
   holds no key. The read path for a version-1 store stays until the next major release
   ([ADR 0085](0085-one-major-moved-on-a-yearly-train.md)) and is then removed with its cap.

## Consequences

- **The ceiling moves from about 444 pins to the request size of the one write that carries all of them** — a rotation
  (ADR 0060 re-seals the store inside its transaction) or the migration: about 300 bytes an entry against the server's
  body limit, measured by the CR (M: `quarkus.http.limits.max-body-size` is not set, so Quarkus 3.36's
  default of 10,240 KiB holds — about 34,000 entries). One new pin writes about
  400 bytes instead of the whole store.
- **What the operator learns per write changes shape, not amount.** It sees which entry was written; today it sees that
  the store was. Linking an entry to a contact by timing (a write right after a directory read of that contact) works
  for both shapes alike, and the server already relays the grant the pin precedes.
- **A rotation now re-keys every entry**: a new Master Key is a new `K_pinIndex`, so new ids, re-sealed entries and a new
  head in the rotation's one request — the same work it does today on one blob.
- **New state:** a table of `(account_id, pin_id, sealed_entry)` rows, erased with the account
  ([ADR 0101](0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md)); a new seal purpose and a new
  key label, each with its vector; a head plaintext of version 2 under the existing purpose.
- **Rejected:** a bigger cap (moves the wall, keeps the O(N) write); one store per scope (one contact's trust split per
  scope, against ADR 0023); fingerprint pins in the single blob (lifts the cap about eightfold and keeps the O(N) write —
  the owner chose per-entry); rows without a head (ADR 0028 Decision 7 lost per entry); dummy entries to hide the count
  (it was never hidden, decision 5); an incremental digest (decision 4).
