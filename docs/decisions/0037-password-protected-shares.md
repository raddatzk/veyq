# ADR 0037 — Password-protected public shares (operator-blind access-control gate)

- Status: Accepted
- Built: yes
- Date: 2026-07-10

## Context

A curator hands out a public share link `/s/{shareId}#k=<K_share>` ([ADR 0029](0029-public-viewer-share.md)).
Anyone with the link decrypts client-side. We want an **optional password** on a share so that a **leaked link is
not enough** — the recipient must also know the password. It must stay **operator-blind** (the server never learns
the password or gains the ability to read content), be **set / changed / removed / revealed** in place without
minting a new link, and apply to **all roles** (READ / READ_WRITE / WRITE_ONLY).

Two shapes were on the table:

- **Crypto-gate** — fold `Argon2id(passphrase)` into the decryption key so the password is *cryptographically*
  required to read anything. Strong (protects the content even against a malicious operator colluding with a
  link-holder), but it forces the link fragment to carry a separate **link key** (so the Memory name/note aren't
  readable with the bare fragment), plus a recoverable copy of that key for in-place edits, nested wraps and a
  migration — a large, intricate slice.
- **Access-control gate (chosen)** — the password gates **what the server serves**, exactly like the existing
  **`contributorToken`** ([ADR 0030](0030-contributor-public-share.md)): the content stays E2EE via the unchanged
  `K_share` fragment; the server refuses to serve the ciphertext (wrap, sealed name/note, media) without a valid
  password token.

## Decision

**A password is a server-checked access-control gate on the share's guest endpoints — not a decryption factor.**

**Tokens (all client-side, the server never sees the raw password).**
- `pw_salt` — random 16 B, per share.
- `pwToken = Argon2id(password, pw_salt)` — 32 B, derived on the client (reuses the conformance-pinned Argon2id
  in `src/packages/crypto/src/v1.ts`). This is the **bearer credential** the guest presents.
- `pw_verifier = SHA-256(pwToken)` — what the **server stores** and checks (`SHA-256(presented) == stored`). The
  server does **no** Argon2id (the KDF stays client-side); a DB leak yields only the verifier, so recovering a
  usable token still costs an Argon2id-per-guess brute-force of the password.

**Stored on the share (opaque operational metadata + one ciphertext):** `password_required` (bool, plaintext —
coarse metadata, like `enabled`), `pw_salt`, `pw_verifier`, and **`sealed_password = symWrap(K_share, password)`**
(AAD `share-password`) — the plaintext password sealed under `K_share` so the **curator can reveal it later**
(management-only, never served to a guest). The server holds only ciphertext + the verifier; never the password.

**Guest gating.** When `password_required`, **every guest endpoint** (`resolveShare`, the gated blob GET, and the
contributor/uploader `identities`/`blob-uploads`/`media`/`delete` endpoints) requires the `pwToken` (header
`X-Share-Password: base64(pwToken)`):
- `resolveShare` with **no** token → a **locked** `PublicShare` carrying only `passwordRequired: true` + `pw_salt`
  (so the client can prompt); `wrap` / `sealedName` / `sealedNote` / nodes / media / identities are **omitted**.
- with a **valid** token → the full share (as today).
- with a **wrong** token → `403 share_password_incorrect`.
So **nothing** (not even the Memory name or note ciphertext) leaves the server without the password — "without the
password, nothing is readable" holds at the access-control layer.

**Set / change / remove / reveal, all in place (same link).** The fragment (`K_share`) is **never touched**, so:
- **Create** with a password: send `passwordRequired: true` + `pw_salt` + `pw_verifier` + `sealed_password`.
- **Edit** via `updateShare` (owner-gated, [ADR 0036](0036-editable-share-note-recoverable-share-key.md)):
  `passwordRequired` is a **tri-state** — `true` sets/changes it (with fresh `pw_salt`/`pw_verifier`/`sealed_password`),
  `false` removes it (clears the fields), **omitted** leaves it untouched. Adding a password to a share created
  without one, or removing one, is just toggling the server-side gate — **no new link**, unlike the crypto-gate.
- **Reveal:** the curator — any member of the Memory's scope — recovers `K_share` from `sealed_share_key`, sealed
  under `K_SC` at its epoch ([ADR 0036](0036-editable-share-note-recoverable-share-key.md) point 4), and opens
  `sealed_password` → a 👁 "show password" in the manage UI. Never under `K_node`: every READ/READ_WRITE link of
  the Memory carries that key, so such a copy would hand this password to another link's holder with a dump.

**All roles.** The gate sits in front of every guest endpoint, so it protects a READ greeting page, a
READ_WRITE album, and a WRITE_ONLY drop-box alike; the password field lives in the shared `ShareCreateDialog`.

## Operator-blindness & threat model — stated honestly

- **The server never reads content.** Content is E2EE under `K_share` (fragment); the password only gates
  *serving*. The server stores a verifier + salt (opaque, like the `contributorToken` hash and the blinded slug
  tokens) + one ciphertext (`sealed_password`). The raw password never reaches the server (the client sends
  `Argon2id(pw)`, not `pw`), so password reuse isn't exposed to the operator — nor, with a dump,
  to the holder of another link to the same Memory: `sealed_password` opens only through a `K_share` recovered
  under `K_SC`, which no link carries ([ADR 0036](0036-editable-share-note-recoverable-share-key.md) point 4).
- **This is an access-control gate, not a cryptographic one — and we say so.** A password defends against a
  **leaked link reaching the wrong person while the operator is honest**: the honest server refuses to serve
  without the password. It does **not** defend against a **malicious operator**, who can ignore the check and serve
  the ciphertext to a colluding link-holder (who then decrypts with the fragment). That residual is **consistent
  with the share model's existing posture** — the no-install web viewer is already only *passive*-operator-safe
  ([ADR 0026](0026-client-trust-tiers.md)), and a reported share is operator-decryptable by design (the T&S
  corollary in the [security model](../crosscutting/security-model.md)). For the realistic self-hosted / trusted
  case (operator = curator, or a trusted instance) the gate is fully effective. The stronger crypto-gate is a
  possible future upgrade for the malicious-operator case; it is deliberately not built here.
- **Brute-force is honestly bounded — per share, on wrong attempts.** A link-holder (who has `pw_salt`
  from the locked resolve) can brute-force the password offline against nothing — they'd have to guess against the
  *server* (online), since the verifier isn't served. That online path is bounded by a **per-share limit on wrong
  attempts** ([cc.security.rate-limit](../crosscutting/cc.security.rate-limit.md)): each `share_password_incorrect`
  spends one token of a per-`shareId` bucket ([ADR 0052](0052-token-bucket-rate-limit-core.md)); over the cap the
  guest gets `429` instead of the `403` oracle. What bounds the attack is guess **throughput**, which the bucket
  keeps at the configured `max / window` — an exhausted guesser regains one attempt per `window / max` seconds
  rather than facing a wall, and the window model's 2× slot-edge burst is gone. **Per share, not per IP** — behind a reverse proxy every anonymous guest shares one source address, so a
  per-IP bound would collapse to a global cap on the share (throttling its honest visitors) while an attacker
  rotating IPs would evade it; the attack targets one `shareId`, so that is the axis the counter keys on. Only a
  *wrong* attempt spends budget — a correct password is admitted first, so a legitimate guest is never locked out,
  and the tokenless challenge-fetch never counts. A DB-leak attacker who obtains `pw_verifier` can still
  brute-force offline, but each guess costs an **Argon2id**. Not a substitute for **revoke**.
- **Password strength is client-enforced and the blind server cannot verify it — which is *why* the online bound
  matters.** The server only ever sees `pw_verifier = SHA-256(pwToken)`; it never sees the password or its length,
  so it cannot reject a weak one. The minimum length is a **client-side convention** (the web dialog enforces ten
  characters); a hand-written or future client could set a shorter one. The per-share attempt bound above is
  therefore the one guarantee the operator-blind server can make on its own, independent of how strong the client
  made the password.

## Consequences

- **Small, in-place, no fragment change.** A few nullable columns (`password_required`, `pw_salt`, `pw_verifier`,
  `sealed_password`) + a token check on the guest endpoints. Set / change / remove / add-to-an-existing-share /
  reveal all keep the same link — none of the crypto-gate's link-key machinery, recoverable second key, nested
  wraps, or migration pain.
- **Reuses the `contributorToken` shape.** A server-checked, share-scoped bearer credential is already how
  contribution is gated ([ADR 0030](0030-contributor-public-share.md)); the password token is the same pattern, so it slots into `activeShare(...)`.
- **`updateShare` grows a tri-state password patch** on top of ADR 0036's optional `role` — the op now edits note +
  name (full replace) + role (optional) + password (tri-state). All owner-gated, all leaving `K_share`/the link
  intact.

## Alternatives considered

- **(A) Crypto-gate (`Argon2id` folded into the key).** Strongest — protects content even against a malicious
  operator. Rejected for v1: forces a link-key fragment + a recoverable link key + nested wraps + a migration for a
  threat the share model already concedes (operator-served web viewer). A future upgrade path, not the first slice.
- **(B, chosen) Access-control gate.** Matches the realistic threat (link-leak, honest operator), reuses the
  contributor-token pattern, and is small + fully in-place. Its honest limit (malicious operator) is documented.
- **(C) Server stores the raw password / a fast hash.** The conventional shape. Rejected: the operator would see (or
  trivially crack) the password. The `Argon2id(pw)` token + `SHA-256` verifier keeps the raw password client-side.

## Related

- [ADR 0029](0029-public-viewer-share.md) (the share model + `K_share` fragment), [ADR 0030](0030-contributor-public-share.md)
  (the `contributorToken` this reuses), [ADR 0036](0036-editable-share-note-recoverable-share-key.md) (the
  owner-gated `updateShare` this extends + the member-recoverable `K_share` the reveal uses),
  [ADR 0003](0003-operator-blind-password-auth.md) (the "raw password never reaches the server" stance),
  [ADR 0026](0026-client-trust-tiers.md) (the client-integrity tiers that bound this gate).
- Use-case: [uc.sharing.share-password](../features/sharing/use-cases/uc.sharing.share-password.md).
- Cross-cutting: [security-model](../crosscutting/security-model.md) (the operational-metadata list gains
  `password_required` + the verifier/salt; `sealed_password` is one more opaque ciphertext),
  [cc.security.rate-limit](../crosscutting/cc.security.rate-limit.md) (the online brute-force bound).
- Security area of the docs site: [Roadmap](../../site/src/content/docs/security/roadmap.md)
