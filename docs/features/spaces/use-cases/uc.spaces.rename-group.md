# UC: Rename a group

- **ID:** uc.spaces.rename-group
- **Primary actor:** **GroupOwner** (`OWNER` on the GROUP scope) — the group's **name authority**
  ([ADR 0027](../../../decisions/0027-group-invite-name-shared-key.md)).
- **Goal:** let a GroupOwner **change a group's display name** for everyone, operator-blind, and **forward-secret** —
  a member removed before the rename cannot read the new name.
- **Preconditions:** an **authenticated, unlocked** `OWNER` of the group who holds a grant at the scope's **current
  content-key epoch** (the write key). The scope is a **GROUP** (personal/tenant scopes carry no per-scope name)
  and is **active** (not pending-deletion — a rename is a name-write, blocked during the grace period like any
  content write, [uc.spaces.delete-group](uc.spaces.delete-group.md) A4).
- **Postconditions (success):** the scope's `encryptedName` is sealed under a **fresh** value key `K_v`, `K_v` is
  wrapped under the **current** epoch key, and `nameEpoch` is set to that epoch; every pending consent invitee holds
  the new `K_v`, sealed to their identity, or — one the owner's device could not anchor — no name key
  ([ADR 0115](../../../decisions/0115-a-scope-key-is-replaced-never-chained.md)). Every current member sees the new
  name on their next `listMyScopes` (they open `K_v` from its wrap with the one scope key they hold); a member a
  rotation removed never held the new `K_v`, so they never read the post-removal name. The server saw new opaque
  ciphertext, a new wrap and sealed keys + a bumped small integer — never the plaintext name and no key.

This use-case realizes the **rename mechanism** [ADR 0027](../../../decisions/0027-group-invite-name-shared-key.md)
defines. It reuses the group's own name-seal
([en.spaces.group-scope](../enablers/en.spaces.group-scope.md)) — a rename is the *write* counterpart of the read
every member already does. It is a **name-only** change: it does **not** rotate `K_SC` and re-grants no one.

## Main flow

1. **The owner edits the name.** In the group settings the `OWNER` types a new name (plaintext, on device). Names
   are **not** unique — a duplicate is allowed ([ADR 0014](../../../decisions/0014-group-scopes-and-governance.md), same as create).
2. **Seal under a fresh value key.** The client takes the scope's **current epoch** `e = currentEpoch` (the scope key
   `E_e` its grant carries), mints a fresh value key `K_v` and seals the new name: `encryptedName = symEncrypt(K_v,
   newName)` (the group name's own seal purpose, bound to the group and `e` —
   [ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md)), and wraps `K_v` under `E_e` (`value-key`,
   `wrappedNameValueKey`).
3. **Hand the invitees the new key.** For every pending consent invitee the members list serves, the client anchors
   the invitee's key as the add did and seals `K_v` to it (`group-name-key`); one it holds as an anomaly gets
   `null`/`null`, which clears its name key.
4. **POST the rename.** `POST /api/scopes/{scopeId}/name { encryptedName, nameEpoch: e, wrappedNameValueKey,
   inviteeNameKeys }`.
5. **The server updates the row (blind).** Owner-gated ([ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §4).
   It rejects a rename whose `nameEpoch != currentEpoch` (`409 stale_epoch` — a concurrent rotation moved the epoch
   under the caller; see A1), one that leaves out an invitee holding a name key (`409 rename_incomplete`) or seals to a
   key that is not the invitee's current one (`409 recipient_key_not_current`, A5), and a rename on a pending-deletion
   group (`409`, A2). Otherwise it overwrites `encrypted_name`, its wrap and `name_epoch`, replaces each invitee's name
   key in the same transaction, and returns `204`. The server never sees a key or reads the name.
6. **Members see the new name.** On their next `GET /api/scopes` each current member opens `K_v` from its wrap with
   the scope key it holds and decrypts `encryptedName` → the new name (the read path
   [uc.spaces.consent-group-add](uc.spaces.consent-group-add.md) / the switcher already use).

```mermaid
---
title: Rename a group — a fresh value key at the CURRENT epoch (forward-secret, operator-blind)
---
sequenceDiagram
  actor O as GroupOwner<br>(holds E_current)
  participant S as Server<br>(blind)
  actor R as Removed member<br>(holds only E_{n-1})
  O->>O: e = currentEpoch #59; K_v = fresh random key
  O->>O: encryptedName = seal(newName, K_v) #59; wrap = seal(K_v, E_e) #59; K_v sealed to each invitee
  O->>S: POST /api/scopes/{id}/name { encryptedName, nameEpoch: e, wrap, inviteeNameKeys }
  S->>S: owner-gate + active + nameEpoch == currentEpoch ? else 409
  S->>S: scope.encrypted_name = encryptedName #59; its wrap #59; scope.name_epoch = e #59; invitees' name keys
  S-->>O: 204
  Note right of R: holds no E_e and never held K_v<br>⇒ never reads the post-removal name
```

## Alternate / exception flows

- **A1 — stale epoch (a concurrent rotation):** between the owner's read and their rename POST another owner
  removed a member and rotated, so `currentEpoch` advanced. The POST's `nameEpoch` is now stale → `409 stale_epoch`.
  The client **refetches** the scope (grant-delivery keeps an owner at the current epoch), seals again under a fresh
  key at the fresh epoch, and retries once. Rare — only a multi-owner read-then-write race; a single-owner group never
  hits it. A rename refused `409 rename_incomplete` — a proposal made meanwhile — re-reads the members and retries the
  same way.
- **A2 — the group is pending-deletion:** a rename is a name-write, rejected during the grace period (`409 scope_deleting`,
  read-only until restore/purge, [uc.spaces.delete-group](uc.spaces.delete-group.md) A4).
- **A3 — the caller is not an OWNER:** `403 not_scope_owner` (a plain MEMBER is not the name authority).
- **A4 — blank / oversized name:** rejected at the contract boundary (`400` — non-blank, base64, `maxLength`), same
  validation as create.
- **A5 — an invitee's key moved:** the invitee secured their account since the owner's device read it →
  `409 recipient_key_not_current`, nothing written; the client re-reads and retries once.
- **E1 — a rename between a consent-invite and its accept:** the rename hands the pending invitee the new name's
  `K_v` in the same request, so their preview shows the new name. A rotation in between leaves the invitee's name key,
  the name's own `K_v`. A rename whose device cannot anchor the invitee's key clears it; a cleared key, or one that does
  not open the served name, shows the proposal **unnamed** — they can still accept and then see the current name
  ([uc.spaces.consent-group-add](uc.spaces.consent-group-add.md)).

## Quality notes

- **Forward-secret, by construction.** Every rename mints a fresh `K_v`, wrapped under the scope's current key, and
  every rotation re-wraps that `K_v` under the new key in its commit and leaves the name and its `nameEpoch`
  ([ADR 0115](../../../decisions/0115-a-scope-key-is-replaced-never-chained.md) decision 2). So only holders of the
  current key read it, and a removed member's key opens no name stored after their removal; the `K_v` of the name they
  saw opens that one name in a later dump until the next rename. A rotator can make the name unreadable, never another.
- **Name-only — cheap.** A rename does **not** rotate `K_SC` and re-grants no member (unlike a removal). It
  overwrites two fields on one row; no per-member wrap.
- **Operator-blind — no new surface.** The server overwrites the already-opaque `encrypted_name` (a key change it
  is agnostic to) + bumps the small `name_epoch` integer. It never sees the name or a key
  ([ADR 0027](../../../decisions/0027-group-invite-name-shared-key.md)).
- **Owner is the name authority — visibly, for everyone.** An owner renaming the group **for all members** is the
  intended power (and visible to all on next list); it is **not** the private per-invitee deception ADR 0027 closes
  (that was the one-off name-seal). A strictly weaker, legitimate action.
- **Consent's / rename's strength tracks the client tier ([ADR 0026](../../../decisions/0026-client-trust-tiers.md)).**
  The forward-secrecy holds as far as [security model § Revocation](../../../crosscutting/security-model.md#revocation--two-layers)
  states for a rotation, and honest-operator on served web — the same tier as all veyq E2EE, no rename-specific weakness.

## User impact

You can rename a group for everyone in it, and the name stays readable only to its current members, never to the
server.

## Related

- Feature: [Spaces (scopes)](../_index.md)
- Use-cases: [uc.spaces.consent-group-add](uc.spaces.consent-group-add.md) (the invitee reads the same sealed name;
  the rename-between-invite-and-accept edge), [uc.spaces.delete-group](uc.spaces.delete-group.md) (a rename is
  blocked while pending-deletion), [uc.spaces.manage-group-members](uc.spaces.manage-group-members.md) (removal +
  the rotation that advances the epoch a rename seals under)
- Enablers: [en.spaces.group-scope](../enablers/en.spaces.group-scope.md) (the group's sealed name + `nameEpoch`)
- Decisions: [ADR 0027](../../../decisions/0027-group-invite-name-shared-key.md) (the derived name-key + the
  rename-under-current-epoch mechanism), [ADR 0024](../../../decisions/0024-scope-key-rotation.md) (epochs),
  [ADR 0115](../../../decisions/0115-a-scope-key-is-replaced-never-chained.md) (the name moves to every new key), [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §4 (owner-gate)
- End-user view (docs site): [Rename, decorate and delete a group](../../../../site/src/content/docs/spaces/manage-a-group.mdx)
