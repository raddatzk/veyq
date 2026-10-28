---
id: cr-join-records-for-existing-members
title: An owner's client signs a join record for each member who joined before the join record existed (stub)
status: planned
satisfies: []
adrs: ["0111", "0028", "0023"]
invariants:
  - a migration-signed join record anchors a member exactly as an invite-signed one does, and is marked as signed over a pin rather than an invite
  - two owners signing different keys for one member make that member's key not anchored - an anomaly, never a block on anyone's write
---

## Goal

[ADR 0111](../docs/decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
decision 18: for every member who joined a tenant before join records existed, an owner's client signs a join record
over the key it pins for that member today and that member's Secret-Key proof public key, so every member gets an
owner-rooted anchor at once.

## Stub: what blocks it

- **Real dependencies.** The join-record format and its writer are built (ADR 0111 decision 3, 2026-10-28:
  `src/packages/crypto/src/invite.ts`, `signJoinRecord`/`sealJoinRecord`, the statement `join-record` signed by the
  invite key and the new identity, field purpose `member-join-record`, column `veyq_tenant_profile.sealed_join_record`,
  written only at an invite's accept). Its migration variant is signed by an owner instead of an invite key, so it is
  a second shape of that record.
- **What it feeds is built** (2026-10-28, ADR 0111 decisions 12 and 15). A member's standing in a tenant is
  `loadTenantView`'s (`src/web/src/features/account/tenantTrust.ts`): a join record counts only when
  `invite.joinRecordVerifies` passes (the owner's signature over an invite key, the invite key's and the newcomer's
  over the record), its signer is an owner the server lists and that owner's key in it is anchored in the tenant; an
  owner without a record is a root, trusted on first use; **a non-owner without one is an anomaly** — never wrapped to,
  declared pending in a rotation. So a member from before the join record, if one existed, would be an anomaly to every
  co-member, and a migration-signed record would need a second verify path there (an owner's identity signature over
  the record instead of the owner → invite chain), plus the contradiction rule of the invariants above.
- **Whether there is anything to migrate.** veyq has no release (`scripts/applied_migrations.sha256`, `release: none`)
  and every dev database is recreated. The join record landed before the first release (2026-10-28), so the only
  members without one are root owners, which ADR 0111 decision 15 trusts on first use — and this CR has nothing to do.

**Recommendation (assistant, 2026-10-27, for owner review):** check `release:`. If there still is none, delete this
CR unbuilt and mark ADR 0111 decision 18 in place as not needed (no member predates the join record). If a release has shipped with members on it, refine this CR then, against the
built format: the owner-side batch signing (who signs when several owners exist — the first owner to open the members
panel), the `proof public key from the server, once` step (the proof key `getKeyChain` serves in the
chain's genesis, built 2026-10-28), and the contradiction rule.

## Retrospective

*(filled at implement time)*
