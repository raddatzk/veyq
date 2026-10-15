# ADR 0076 — A guest write proves it holds the link's key

- Status: Accepted
- Date: 2026-10-02

## Context

A public link is `/s/{shareId}#k=K_share`. [ADR 0029](0029-public-viewer-share.md) §2 calls the id *half the
capability* and the fragment key the other half — *"one without the other is inert"*. For **writes** that was not
true: the server checked only the id.

- **The share id is the one bearer secret stored raw.** Sessions, invites, recovery tokens, pairing handles,
  contributor tokens and share-password verifiers are all kept as hashes ([ADR 0004](0004-session-tokens.md)'s
  shape); `veyq_share.id` is the primary key in clear.
- **Exactly one guest call is opened by the id alone: naming yourself** (`createShareIdentity`). It mints a
  contributor token, and every later guest write needs that token. A password-protected link was already closed —
  its gate checks `SHA-256(token)` against a stored verifier ([ADR 0037](0037-password-protected-shares.md)).
- So **a database copy plus the live instance** named a guest on every enabled, password-less READ_WRITE or
  WRITE_ONLY link and uploaded. And the id rides every guest request path (`/s/{id}`, `/api/shares/{id}/…`), so any
  reverse-proxy access log an operator turns on holds it too (none is on in the bundled stack or the chart).
- **What that bought was junk and spent quota, never a read.** Without `K_share` the writer can seal neither under
  `K_node` (READ_WRITE) nor to the link's drop key (WRITE_ONLY, [ADR 0031](0031-uploader-public-share.md) point 7),
  so its uploads open for nobody. Bounded per link ([ADR 0073](0073-storage-quota-belongs-to-whoever-pays-for-the-store.md)
  §4) and by the per-share identity rate limit — integrity and availability, not confidentiality.

## Decision

1. **A write token derived from the link's key.** `writeToken = HKDF-SHA256(K_share, salt = ∅, info =
   "veyq/v1/share-write:" + shareId, 32)` — a label of its own, and the share id in the info so a token is bound
   to the link it was derived for ([ADR 0072](0072-a-seal-binds-its-object.md)). Pinned by the `share_write_token`
   conformance vector.
2. **The server keeps only its hash.** Every create and every re-issue ([ADR 0075](0075-a-link-a-departed-member-could-copy-is-reissued.md))
   carries `writeVerifier = SHA-256(writeToken)`, for **every** role — a READ link can be flipped to READ_WRITE
   later and its key does not change, so an edit never takes a verifier. A deleted link's tombstone clears it with
   the other key-derived columns (ADR 0029 §7).
3. **Naming yourself presents the token** as `X-Share-Write`. The check runs after the share is found live and
   after the password gate, before the role check — a missing, malformed or wrong token, and a link with **no**
   verifier, answer the same non-enumerable `404 share_not_found` a password-protected link gives a write without
   its password; a real guest always holds the key, so no guest ever sees it. Constant-time compare; no attempt
   limiter — the token has 256 bits.
4. **Only that call is gated.** Every later guest write already needs the contributor token, whose hash only is
   stored. A read by an id holder yields ciphertext a database copy already holds — gating the resolve and blob
   reads too stays a cheap later step if a read-side reason appears.
5. **A link with no verifier fails closed.** The column is nullable; a link minted before this decision takes no
   new guest until a curator re-issues it. An existing guest's token keeps working — only naming oneself is gated.
   The manage list says which links those are: each guest-taking row carries `newGuestsRefused`, derived from the
   missing verifier and the role — the same two facts the refusal decides on — and the panel marks it beside the
   renew. Every row also carries the missing verifier on its own (`writeVerifierMissing`), because a READ link
   without one becomes such a link the moment its role edit picks READ_WRITE, and point 2 keeps the edit from
   fixing it: the role edit warns and offers the renew instead.

## Consequences

- **ADR 0029 §2's "one without the other is inert" holds for writes.** A database copy, an access log or a leaked
  id without the fragment names no guest, so it uploads nothing.
- **The server learns nothing new.** `writeToken` is an HKDF output under its own label: it reveals nothing about
  `K_share` and opens no seal. An *active* operator can record it and gains the power to write — which it has
  anyway, being the server. This is a layer-1 control against a database copy and a log holder, not against the
  operator ([security model](../crosscutting/security-model.md) § What holds today on a client the operator does
  not serve).
- **Reads by an id holder stay possible** and yield ciphertext only (point 4).

**Alternatives rejected.**

- **Hash the lookup id** (`id = SHA-256(shareId)`). The raw id is not only a lookup key: the manage list returns it
  so members rebuild the link, and two ADR 0072 seal contexts bind it — the recovery copy of `K_share` and every
  guest's sealed name, which the bucket list serves with the raw id so members can open it. A hashed id needs a
  third, `K_SC`-sealed copy of the id on every share and both read models, and a share without a recovery copy could
  never get one. It also leaves the raw id in every request path, so an access log still holds it. More change for
  less closed.
- **Accept and document.** Bounded by the per-link limit and nothing readable — but it leaves the one raw bearer
  column in a schema that hashes every other, and a quota anyone with a dump can spend.
