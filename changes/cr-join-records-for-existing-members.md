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

- **Real dependencies.** The join-record format and its writer (`changes/cr-invite-is-a-placeholder-member.md`, planned)
  and the anchor evaluation with its anomaly display (`changes/cr-trust-records-in-the-tenant.md`, planned). Refining
  the record's migration variant before the format is built would freeze a guess.
- **Whether there is anything to migrate.** veyq has no release (`scripts/applied_migrations.sha256`, `release: none`)
  and every dev database is recreated. If `cr-invite-is-a-placeholder-member` lands before the first release, the only
  members without a join record are root owners, which ADR 0111 decision 15 and `cr-trust-records-in-the-tenant`
  decision 1 trust on first use — and this CR has nothing to do.

**Recommendation (assistant, 2026-10-27, for owner review):** when `cr-trust-records-in-the-tenant` is done, check
`release:`. If there still is none, delete this CR unbuilt and mark ADR 0111 decision 18 in place as not needed
(no member predates the join record). If a release has shipped with members on it, refine this CR then, against the
built format: the owner-side batch signing (who signs when several owners exist — the first owner to open the members
panel), the `proof public key from the server, once` step (the directory field `cr-secure-the-account` serves through
`getKeyChain`'s step 0), and the contradiction rule.

## Retrospective

*(filled at implement time)*
