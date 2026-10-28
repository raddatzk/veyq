# UC: Require verified identities before sharing (strict mode)

- **ID:** uc.spaces.require-verified-identities
- **Primary actor:** **an account holder** (or, in a scope-wide design, a scope owner) who wants a verified identity
  *required* before a scope key reaches anyone.
- **Goal:** an unverified member is never silently trusted with a share: no wrap to them — a group add, a
  rotation's re-grant, a grant delivery — happens until someone verifies them. (A key the contact's key chain does not
  reach is already never wrapped to, strict mode or not; what strict mode adds is the unverified member.)
- **Preconditions:** an **authenticated, unlocked** caller; the verification ceremony this gates on exists —
  [uc.spaces.enforce-verified-identities](uc.spaces.enforce-verified-identities.md) (compare a member's safety
  number and chain code on their profile, which writes a verification every member of the tenant reads).
- **Status:** **planned, and not buildable as specified** — its shape is open (A1).
- **Postconditions (success):** with strict mode on, the caller's client wraps no scope key to an **unverified**
  member; it waits until a human verifies them. Operator-blind: the
  setting never leaves the client as anything a server could key on.

## Main flow

1. **Turn it on.** The caller switches on "require verification before sharing" ([ADR 0028](../../../decisions/0028-identity-authenticity-tofu-verification.md)
   Decision 4). Off by default: the default flow is TOFU with the key chain ([uc.spaces.verify-identity](uc.spaces.verify-identity.md)).
2. **A wrap waits for verification.** Where the caller's client would wrap a scope key to a member no verification
   counts for in the tenant — adding them to a group, re-granting them in a rotation (declared pending), delivering to
   them — it does not.
3. **A rotation keeps the gate.** A verified contact who secures their account moves to a new head, which no
   verification names yet, so the next wrap to them waits for a new compare.
4. **Verify to release.** Once the caller verifies the member
   ([uc.spaces.enforce-verified-identities](uc.spaces.enforce-verified-identities.md)), the next wrap goes ahead.

## Alternate / exception flows

- **A1 — the shape is open.** A *per-account* toggle cannot protect shared-scope content: any co-member can key an
  unverified identity regardless of the caller's own setting. The mechanism has to be **scope-wide**, and that
  redesign is undecided ([ADR 0028](../../../decisions/0028-identity-authenticity-tofu-verification.md) § What is
  still open). This use-case states the intent; it is not a design.
- **A2 — it collides with "never a block".** [ADR 0111](../../../decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decision 15 shows *verified* as display only and lets nothing about another member's identity block anybody's write;
  its one gate is the anomaly, never an unverified member. Strict mode would be a second gate, so it needs a decision
  against that record, not only a design — which is why A1 stands and this stays planned. Since verifications are
  shared in the tenant, a scope-wide gate would at least have one record every member reads.

## Quality notes

- **Operator-blind.** Like the pin store ([en.account.identity-pinning](../../account/enablers/en.account.identity-pinning.md)),
  whatever holds the setting must not hand the server a who-must-be-verified graph.
- **Tier-consistent ([ADR 0026](../../../decisions/0026-client-trust-tiers.md)).** Enforced on the client, so it
  holds against an active operator only on a client that operator does not serve.

## User impact

Planned: you could refuse to share with anyone you have not verified, so an unchecked key never receives your content;
today you can verify people, but nothing enforces it.

## Related

- Feature: [Spaces (scopes)](../_index.md)
- Enablers: [en.account.identity-pinning](../../account/enablers/en.account.identity-pinning.md) (the anchor check
  this would sit beside)
- Use-cases: [uc.spaces.enforce-verified-identities](uc.spaces.enforce-verified-identities.md) (the verification it
  requires), [uc.spaces.verify-identity](uc.spaces.verify-identity.md) (the advisory default it would harden)
- Decisions: [ADR 0028](../../../decisions/0028-identity-authenticity-tofu-verification.md) (Decision 4, strict
  mode), [ADR 0026](../../../decisions/0026-client-trust-tiers.md) (the trust tier this rides on)
- End-user view (docs site): [Is my data safe?](../../../../site/src/content/docs/privacy/is-my-data-safe.md)
- Security area of the docs site: [Roadmap](../../../../site/src/content/docs/security/roadmap.md)
