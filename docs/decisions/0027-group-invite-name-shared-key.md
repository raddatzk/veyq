# ADR 0027 — Group invite name via a name key derived from the name's epoch key, not a one-off owner seal

- Status: Accepted
- Built: yes
- Date: 2026-07-06
- Model: [scopes-and-tenancy](../crosscutting/scopes-and-tenancy.md)

## Context

The consent-add flow ([ADR 0014](0014-group-scopes-and-governance.md) §5) shows a pending invitee the group's name
so they "decide informed", **before** they hold `K_SC`. Its first form did this with a **one-off seal**: the owner's device reads the plaintext
group name and `asymWrap`s it to the invitee's identity under the `group-name` AAD context. A security review
surfaced the weakness: this sealed name is **arbitrary owner-supplied plaintext, not bound to the group's real
`encryptedName`**. Nothing enforces that the two agree, and the invitee *cannot* verify it — they hold no `K_SC`,
so they can't decrypt the canonical `encryptedName` to cross-check. A modified owner client can therefore privately
show the invitee a **different name than the group actually has**, defeating the feature's own purpose (protect the
person being added) in exactly its threat: an adder misrepresenting what you are joining.

A cryptographic *binding* of the one-off seal to `encryptedName` cannot fix this cheaply — the invitee has no key
to check a binding against pre-accept. The only robust construction that lets a pending invitee read a
**group-consistent** name is to give them a key that opens the *same canonical* name ciphertext the members read.

## Decision

Seal the group name under a dedicated **name key `K_name`** derived from the epoch it was set under, and hand
`K_name` (not a name copy) to a pending invitee so they read the **canonical** name ciphertext. This replaces the
one-off name seal of [ADR 0014](0014-group-scopes-and-governance.md) §5; that seal is gone.

1. **`K_name` is derived from the name's content-epoch key** ([ADR 0024](0024-scope-key-rotation.md)):
   `K_name_e = HKDF-SHA256(E_e, salt = "", info = utf8("veyq/v1/group-name-key"), 32)`, where `E_e` is the epoch
   the name was last **set or renamed** under. The derivation, the name's seal and the key's wrap are pinned as
   conformance vectors ([ADR 0017](0017-crypto-primitives-and-post-quantum-strategy.md): `group_name_key`,
   `seal.sym.group-name`, `seal.asym.group-name-key`). The scope row carries a small **server-visible name-epoch index** — exactly like every
   node name already does (ADR 0024 §2). `E_e` is not recoverable from `K_name_e` (HKDF one-way), so handing out
   `K_name_e` leaks the name and nothing else (no content, no dedup key).
2. **The group name seals under its epoch's `K_name`:** `encryptedName = symEncrypt(K_name_e, name)` on the scope
   row (not under the birth `K_SC`), under a seal purpose of its own bound to the group
   ([ADR 0072](0072-a-seal-binds-its-object.md)). A **rename re-seals under the current epoch** and updates
   the name-epoch index — its own operation, [uc.spaces.rename-group](../features/spaces/use-cases/uc.spaces.rename-group.md):
   owner-gated, name-only (no `K_SC` rotation), and the server rejects a stale `nameEpoch` with `409`; a **plain
   rotation does not touch the name** (it keeps its epoch). Members hold the latest
   epoch, peel the chain to `E_e`, derive `K_name_e` and read. The blind server is **agnostic** to which key
   `encryptedName` is under — the column stays opaque base64; only the small epoch index is new.
3. **A pending invitee is handed `K_name_e`, and reads the canonical `encryptedName`:** the owner's device derives
   `K_name_e` for the name's current epoch and `asymWrap`s **the derived key bytes** to the invitee
   (`asymWrap(inviteePub, K_name_e, "group-name-key")`); the server returns the scope's `encryptedName` in the
   invitee's invitation payload. The invitee unwraps `K_name_e` and decrypts the **same** blob the members see — so
   the owner **cannot privately show a divergent name**. (The invitee needs no epoch index — it gets the key bytes.)

**Forward-secret, like every other name.** A removed member (holding ≤ `E_{n-1}`) can derive `K_name` for epochs up
to their removal, but **not** for a later rename sealed under `E_n`+ — so they cannot read a post-removal rename.
This makes the group's own name forward-secret **consistently** with the node names ADR 0024 §2 already protects,
rather than the lone exception. (An `E_0`-anchored static key was rejected — see Consequences.)

**Migration = clean break.** veyq is pre-production; there is no group data to preserve, so new groups seal the
name under `K_name` from birth (epoch 0) and the backend needs **no** dual-shape / lazy-migration support.

## Consequences

- **Closes the private-lie threat.** The invitee sees the group-consistent name, not an owner-typed claim. The
  owner can still *rename the group for everyone* (they are the name authority), but that is visible to all members
  and not a private per-invitee deception — a strictly weaker attack.
- **The group name is forward-secret, consistently with node names.** No new special case: the name rides the same
  "sealed under its epoch, carries an epoch index" model ADR 0024 §2 defines for the tree.
- **Near-zero new server surface.** No stored name key (derivation, not storage); `encryptedName`'s key change is
  invisible to the blind server. New server state: a small **name-epoch index** on the scope row; the membership's
  `sealed_invite_name` column is repurposed to the wrapped `K_name` (`sealed_name_key`); `encryptedName` is
  returned in the invitation payload (a bounded, ciphertext-only read-path widening to the pending invitee). The
  one-off per-invitee name-seal and `inviteName.ts`'s name-sealing are removed.
- **One fail-safe edge:** if the name is **renamed** between an invite and its accept, the invitee's `K_name_e`
  no longer opens the re-sealed name — the preview shows "unnamed" and they can still accept (then see the real
  current name). A plain rotation does **not** trigger this (the name keeps its epoch). Rare and harmless.
- **Orthogonal to the fingerprint/TOFU seam** (ADR 0014 §5): that closes an operator-key MITM, not the
  modified-owner name-lie. The name-key wrap runs [ADR 0028](0028-identity-authenticity-tofu-verification.md)'s pin
  gate like every other foreign wrap.
- **Alternatives rejected:** (a) *one-off seal, framed as owner-claimed* (the first form) — an unverifiable claim
  that looks like assurance; (b) *drop the pre-accept name, decide on `addedBy`* — honest and cheapest, but loses
  the name preview as a product feature (we chose to keep it); (c) *`K_name` derived from `E₀`, static forever* —
  simpler (no name-epoch index), but makes the group name the **one name that is never forward-secret** (any past
  member reads all future renames), an inconsistency with node names we chose not to bake in; (d) *stored random
  `K_name` wrapped `symWrap(K_SC, K_name)`* — a needless extra column + ciphertext; (e) *bind the one-off seal to
  `encryptedName`* — unverifiable pre-accept without a name key, i.e. it collapses into this decision.
