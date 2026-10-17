# Enabler: The instance moderation key

- **ID:** en.moderation.moderation-key
- **Type:** **Enabler** — structural foundation, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
  A **model** enabler: it states what is derived from what, who holds it and what proves it.
- **Status:** **built** — decided in [ADR 0099](../../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
  § 2, built by cr-moderators-and-the-moderation-key (2026-10-17): `@veyq/crypto`'s `moderationBundle`,
  `moderationSign`/`moderationVerify` and `sealTo` purpose `moderation-grant`; the server's `ModerationProof` and
  `ModerationProofGate` (`backend/core/.../moderation/`).
- **Provides:** one key per epoch that every report seals to, held by every accepted moderator and by nobody else;
  a proof of that key every moderation call carries.
- **Serves (use-cases):** [uc.moderation.appoint-moderators](../use-cases/uc.moderation.appoint-moderators.md),
  [uc.moderation.serve-as-moderator](../use-cases/uc.moderation.serve-as-moderator.md); later the report and the review
  (ADR 0093 §§ 1–3).
- **Depends on:** [en.account.key-model](../../account/enablers/en.account.key-model.md) (the identity a grant is
  sealed to), [en.account.identity-pinning](../../account/enablers/en.account.identity-pinning.md) (the pin check before
  a holder delivers).

## What is derived from what

Per epoch `n` (1, 2, …), a 32-byte random `M_n`, drawn by admin-web or `veyq-admin` and never stored by the server:

| Key | Derivation | Used for |
|---|---|---|
| X-Wing key | seed = `HKDF-SHA256(M_n, salt = "", info = "veyq/v1/moderation/kem", 32)` | reports, objections and the log's free text seal to its public half (`sealTo`) |
| Ed25519 key | seed = `HKDF-SHA256(M_n, salt = "", info = "veyq/v1/moderation/sign", 32)` | the key proof |

The **public bundle** of an epoch is the X-Wing encapsulation key and the Ed25519 public key. The server stores it per
epoch with who minted it, when, and why (initial, removal, identity rotation, compromise, by hand), and serves the
current one to anyone (a reporter, mostly a guest, seals to it). Pinned by `moderation_key` in
[`v1.json`](../../../../contracts/crypto/vectors/v1.json).

## Grants

A grant is `M_n` sealed to a moderator's identity public key, `sealTo` purpose `moderation-grant` [epoch] — the shape of
a scope grant. One per moderator and live epoch; no chain link between epochs (ADR 0099 § Rejected: admin-web never
holds `M_n` to write one). **Unsigned** (owner decision Q4): the residual is stated in ADR 0099 § 3, and the moderator's
client compares each epoch's derived public bundle with the published one on every unlock.

- **Minted** by admin-web for every accepted moderator at once (epoch 1 to the Server Owner alone); the server refuses
  a mint whose grants do not cover exactly the accepted moderators.
- **Delivered** by a holder's client to an accepted moderator missing an epoch, after the pin check; only by an
  account holding a grant of that epoch, only to an accepted moderator, idempotent.
- **Deleted** for every epoch at once when the moderator is removed, steps down, or rotates their identity (then the
  delivery becomes pending again at the new identity).

## The key proof

Every key-proved moderation call carries a signature with the **current** epoch's Ed25519 key over the statement
`moderation-proof` = `lp("moderation-proof") ‖ lp(challenge) ‖ lp(utf8(operationId)) ‖ lp(caseId or empty)` — the
canonical statement encoding of `packages/crypto` — in two headers, the challenge and the signature. The challenge is
32 random bytes the server issues to a session with the `MODERATOR` role, single use, five minutes. The server rebuilds
the statement from the operation it matched and the case id in the path, and verifies against the current epoch's
stored public key; it then needs the role too (`ModerationProofGate`). Pinned by `moderation_proof` in `v1.json`, which
both stacks read.

Which operations carry the proof is the contract's: an operation that declares the two proof headers is gated, and
`ModerationProofPostureSweepTest` holds every operation of `moderation.openapi.yaml` to either the proof or a listed
reason why it cannot carry one (a newcomer holds no key yet).

## Rules

1. **The server never holds `M_n` or a private half.** It stores bundles and sealed grants only.
2. **Only the current epoch proves.** admin-web mints every rotation for all accepted moderators at once, so every
   holder holds it.
3. **Old epochs stay granted** to the moderators who hold them; a report stays sealed to the epoch it was made under,
   and nothing is re-sealed on a rotation (ADR 0099 § 4).

## Related

- [ADR 0099](../../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) §§ 2–4,
  [ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md) (the typed seal),
  [ADR 0024](../../../decisions/0024-scope-key-rotation.md) (forward-only rotation)
- [en.moderation.log](en.moderation.log.md)
- Docs site: [What the server sees](../../../../site/src/content/docs/security/what-the-server-sees.md)
