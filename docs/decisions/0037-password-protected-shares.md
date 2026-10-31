# ADR 0037 — Password-protected public shares (operator-blind access-control gate)

- Status: Accepted
- Built: yes
- Date: 2026-07-10
- Model: [en.sharing.public-link](../features/sharing/enablers/en.sharing.public-link.md)

## Context

A curator hands out a public share link `/s/{shareId}#k=<K_share>` ([ADR 0029](0029-public-viewer-share.md)).
Anyone with the link decrypts client-side. We want an **optional password** on a share so that a **leaked link is
not enough** — the recipient must also know the password. It must stay **operator-blind** (the server never learns
the password or gains the ability to read content), be **set / changed / removed / revealed** in place without
minting a new link, and apply to **all roles** (READ / READ_WRITE / WRITE_ONLY).

Two shapes were on the table:

- **Crypto-gate** — fold `Argon2id(passphrase)` into the decryption key so the password is *cryptographically*
  required to read anything. Strong (protects the content even against a malicious operator colluding with a
  link-holder), but it forces the link fragment to carry a separate **link key** (so the root's name and the note
  aren't readable with the bare fragment), plus a recoverable copy of that key for in-place edits, nested wraps and a
  migration — a large, intricate slice.
- **Access-control gate (chosen)** — the password gates **what the server serves**, exactly like the existing
  **`contributorToken`** ([ADR 0030](0030-contributor-public-share.md)): the content stays E2EE via the unchanged
  `K_share` fragment; the server refuses to serve the ciphertext (wrap, sealed name/note, media) without a valid
  password token.

## Decision

**A password is a server-checked access-control gate on the share's guest paths — not a decryption factor.** The
fields, the header and the answers as built are the model,
[en.sharing.public-link](../features/sharing/enablers/en.sharing.public-link.md) § Gates.

1. **The guest presents a token, never the password.** The client derives `pwToken = Argon2id(password, salt)` with a
   random per-share salt; that token is the **bearer credential** the guest presents. The server stores only its
   `SHA-256` verifier and compares; it runs **no** Argon2id itself (the KDF stays client-side), so a database leak
   yields only the verifier, and recovering a usable token still costs an Argon2id per guessed password.

2. **The share keeps the gate's state plus one ciphertext.** A plaintext "password required" flag (coarse metadata,
   like `enabled`), the salt, the verifier, and the password itself sealed under `K_share`, so a member can reveal it
   later — management-only, never served to a guest. The server holds no password.

3. **Every guest path is gated.** While a password is required, every guest path refuses without a valid token.
   Without one the resolve answers a **locked** challenge that carries only what the client needs to prompt (the flag
   and the salt) — no wrap, no sealed name or note, no node, medium or identity; a wrong token is refused. So
   **nothing**, not even the root's name or the note ciphertext, leaves the server without the password — "without the
   password, nothing is readable" holds at the access-control layer.

4. **Set, change, remove and reveal, all in place.** The fragment (`K_share`) is never touched: a password is set at
   create or later, changed (a fresh salt, verifier and sealed copy) or removed (the fields cleared) through the
   owner-gated edit ([ADR 0036](0036-editable-share-note-recoverable-share-key.md) point 3) — toggling the
   server-side gate, **no new link**, unlike the crypto-gate. **Reveal:** any member of the scope recovers `K_share`
   from its recovery copy under `K_SC` ([ADR 0036](0036-editable-share-note-recoverable-share-key.md) point 4) and
   opens the sealed password. Never under `K_node`: every `READ`/`READ_WRITE` link of the node carries that key, so
   such a copy would hand this password to another link's holder with a dump.

5. **All roles.** The gate sits in front of every guest path, so it protects a `READ` greeting page, a `READ_WRITE`
   Memory and a `WRITE_ONLY` drop box alike.

## Consequences

- **The server never reads content.** Content is E2EE under `K_share` (fragment); the password only gates
  *serving*. The server stores a verifier and a salt (opaque, like the contributor token's hash and the blinded slug
  tokens) and one ciphertext. The raw password never reaches the server (the client sends `Argon2id(pw)`, not `pw`),
  so password reuse isn't exposed to the operator — nor, with a dump, to the holder of another link to the same
  node: the sealed password opens only through a `K_share` recovered under `K_SC`, which no link carries
  ([ADR 0036](0036-editable-share-note-recoverable-share-key.md) point 4).
- **This is an access-control gate, not a cryptographic one — and we say so.** A password defends against a
  **leaked link reaching the wrong person while the operator is honest**: the honest server refuses to serve
  without the password. It does **not** defend against a **malicious operator**, who can ignore the check and serve
  the ciphertext to a colluding link-holder (who then decrypts with the fragment). That residual is **consistent
  with the share model's existing posture** — the no-install web viewer is already only *passive*-operator-safe
  ([ADR 0026](0026-client-trust-tiers.md)). For the realistic self-hosted or trusted case (operator = curator, or a
  trusted instance) the gate is fully effective. The stronger crypto-gate is a possible future upgrade for the
  malicious-operator case; it is deliberately not built here.
- **Brute-force is bounded per share, on wrong attempts.** A link-holder holds the salt from the locked challenge but
  not the verifier, so it can guess only online, against the server. Each wrong attempt spends from a per-share
  budget and over the cap the guest is throttled instead of told "wrong"; the bound is guess **throughput**, the
  limiter's ([ADR 0052](0052-token-bucket-rate-limit-core.md),
  [cc.security.rate-limit](../crosscutting/cc.security.rate-limit.md)). **Per share, not per IP** — behind a reverse
  proxy every anonymous guest shares one source address, so a per-IP bound would collapse to a global cap on the
  share (throttling its honest visitors) while an attacker rotating IPs would evade it; the attack targets one
  share, so that is the axis the counter keys on. Only a *wrong* attempt spends budget — a correct password is
  admitted first, so a legitimate guest is never locked out, and the tokenless challenge never counts. A database-leak
  attacker who obtains the verifier can still brute-force offline, but each guess costs an **Argon2id**. Not a
  substitute for **revoke**.
- **Password strength is client-enforced and the blind server cannot verify it — which is *why* the online bound
  matters.** The server never sees the password or its length, so it cannot reject a weak one. The minimum length
  is a client-side convention; a hand-written or future client could set a shorter one. The per-share attempt bound
  is therefore the one guarantee the operator-blind server can make on its own, independent of how strong the client
  made the password.
- **Small, in-place, no fragment change.** A few columns and a token check on the guest paths; set, change, remove
  and reveal all keep the same link — none of the crypto gate's link-key machinery.
- **The same shape as the contributor token** ([ADR 0030](0030-contributor-public-share.md)): a server-checked,
  share-scoped bearer credential whose hash only is stored.
- What a report hands the moderators is
  [ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 1's, password or
  not.

## Alternatives considered

- **(A) Crypto-gate (`Argon2id` folded into the key).** Strongest — protects content even against a malicious
  operator. Rejected for v1: forces a link-key fragment + a recoverable link key + nested wraps + a migration for a
  threat the share model already concedes (operator-served web viewer). A future upgrade path, not the first slice.
- **(B, chosen) Access-control gate.** Matches the realistic threat (link-leak, honest operator), reuses the
  contributor-token pattern, and is small + fully in-place. Its honest limit (malicious operator) is documented.
- **(C) Server stores the raw password / a fast hash.** The conventional shape. Rejected: the operator would see (or
  trivially crack) the password. The `Argon2id(pw)` token + `SHA-256` verifier keeps the raw password client-side.

What this holds against a malicious operator is on the docs site's [Roadmap](../../site/src/content/docs/security/roadmap.md).
