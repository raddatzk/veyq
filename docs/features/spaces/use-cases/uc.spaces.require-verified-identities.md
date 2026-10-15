# UC: Require verified identities before sharing (strict mode)

- **ID:** uc.spaces.require-verified-identities
- **Primary actor:** **an account holder** (or, in a scope-wide design, a scope owner) who wants a verified identity
  *required* before a scope key reaches anyone.
- **Goal:** an unverified member is never silently trusted with a share: the grant-delivery worker skips them (they
  stay *awaiting access*) until someone verifies them, and a changed key is a hard block rather than a notice.
- **Preconditions:** an **authenticated, unlocked** caller; the verification ceremony this gates on exists —
  [uc.spaces.enforce-verified-identities](uc.spaces.enforce-verified-identities.md) (compare a member's safety
  number on their profile, mark them *verified*).
- **Status:** **planned, and not buildable as specified** — its shape is open (A1).
- **Postconditions (success):** with strict mode on, the worker delivers no scope key to an **unverified** member and
  wraps nothing to a **changed** key; both stay *awaiting access* until a human verifies them. Operator-blind: the
  setting never leaves the client as anything a server could key on.

## Main flow

1. **Turn it on.** The caller switches on "require verification before sharing" ([ADR 0028](../../../decisions/0028-identity-authenticity-tofu-verification.md)
   Decision 4). Off by default: the default flow is advisory TOFU ([uc.spaces.verify-identity](uc.spaces.verify-identity.md)).
2. **Delivery waits for verification.** When the grant-delivery worker would wrap a scope key to a member whose pin
   is not `verified`, it skips the job instead; the member stays *awaiting access*.
3. **A changed key is a hard block.** A directory key that differs from the pin blocks the wrap on every path, the
   UI's included, until the member is verified again on their profile.
4. **Verify to release.** Once the caller verifies the member
   ([uc.spaces.enforce-verified-identities](uc.spaces.enforce-verified-identities.md)), the next claim delivers.

## Alternate / exception flows

- **A1 — the shape is open.** A *per-account* toggle cannot protect shared-scope content: any co-member can key an
  unverified identity regardless of the caller's own setting. The mechanism has to be **scope-wide**, and that
  redesign is undecided ([ADR 0028](../../../decisions/0028-identity-authenticity-tofu-verification.md) § What is
  still open). This use-case states the intent; it is not a design.

## Quality notes

- **Operator-blind.** Like the pin store ([en.account.identity-pinning](../../account/enablers/en.account.identity-pinning.md)),
  whatever holds the setting must not hand the server a who-must-be-verified graph.
- **Tier-consistent ([ADR 0026](../../../decisions/0026-client-trust-tiers.md)).** Enforced on the client, so it
  holds against an active operator only on a client that operator does not serve.

## What this means for you / Was das für dich bedeutet

**EN —** **Planned — not in the app today.** You can already compare a member's safety number on their profile and
mark them "verified". What does not exist yet is a setting that refuses to share with anyone you have not verified:
how it could protect a space other members also share into is still open.

**DE —** **Geplant — heute noch nicht in der App.** Die Sicherheitsnummer eines Mitglieds kannst du schon heute in
seinem Profil vergleichen und es als „verifiziert" markieren. Was es noch nicht gibt, ist eine Einstellung, die das
Teilen mit allen verweigert, die du nicht verifiziert hast: Wie sie einen Raum schützen soll, in den auch andere
Mitglieder teilen, ist noch offen.

## Related

- Feature: [Spaces (scopes)](../_index.md)
- Enablers: [en.account.identity-pinning](../../account/enablers/en.account.identity-pinning.md) (the pin store whose
  `verified` state this would gate on)
- Use-cases: [uc.spaces.enforce-verified-identities](uc.spaces.enforce-verified-identities.md) (the verification it
  requires), [uc.spaces.verify-identity](uc.spaces.verify-identity.md) (the advisory default it would harden)
- Decisions: [ADR 0028](../../../decisions/0028-identity-authenticity-tofu-verification.md) (Decision 4, strict
  mode), [ADR 0026](../../../decisions/0026-client-trust-tiers.md) (the trust tier this rides on)
- End-user view (docs site): [Is my data safe?](../../../../site/src/content/docs/privacy/is-my-data-safe.md)
- Security area of the docs site: [Roadmap](../../../../site/src/content/docs/security/roadmap.md)
