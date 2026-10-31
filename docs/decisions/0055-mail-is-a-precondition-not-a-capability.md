# ADR 0055 — A one-time link travels by mail or not at all: a mailer is a precondition, not a capability

- Status: Accepted
- Built: yes
- Date: 2026-08-06
- Model: [cc.mail](../crosscutting/cc.mail.md)

## Context

[ADR 0054 § Decision 4](0054-password-recovery-two-factor-release.md) concluded that a token minted for one named
person must not be routed through a third party — not because that party could open it, but because a secret
addressed to someone else has no business passing through their hands. It named the member-invite copy-link as the
same case. This ADR decides that case; 0054 § 4 owns the rule.

What is new is the operational consequence. Recovery never had a copy-link, so requiring mail there took nothing away.
The member invite had one, so retiring it makes a working mailer a **precondition for admitting a member** — on a
product whose invariant is *self-hostable by default*.

Two facts shape the answer:

1. **Tenant mail is per-tenant runtime data, so no startup check can reach it.** Mail settings belong to a tenant and
   change while the process runs ([ADR 0048](0048-provider-neutral-smtp-mail-transport.md) §§ 2, 4); at boot there is
   no tenant to ask.
2. **A capability flag would assert the opposite of this decision.** A capability gates a feature that is optional per
   instance ([uc.platform.capability-discovery](../features/platform/use-cases/uc.platform.capability-discovery.md) § 4):
   present, offered; absent, hidden. Advertising "this instance can mail" would adopt a mailer-less instance as a
   supported configuration. The object store, likewise required, likewise carries no client-facing flag.

## Decision

**1. Mail is a base function of a veyq instance, not an optional feature.** No capability, no manifest entry, nothing
for a client to ask in advance. Since no startup check can see tenant mail, each action that needs it refuses loudly
at the moment it cannot work.

**2. A member invite is mail-only.** The invite response carries no `bootstrapSecret` and no member surface offers a
link to copy. Inviting — first mint and re-issue — is refused before any mint when the tenant cannot send, rather than
minting an invitation that reaches nobody.

**3. The Server Owner's owner invites are the surviving copy-link.** Only the Server Owner can mint them, and each
must work when the tenant cannot send: a `PENDING` tenant has no mail settings and no owner yet to configure them
([ADR 0048](0048-provider-neutral-smtp-mail-transport.md) § 5), and an ownerless `ACTIVE` tenant
([ADR 0101](0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md) decision 9) has nobody left who
may repair its relay. So the invite is mailed through the tenant's own relay where that relay can send, and is returned
to the Server Owner to hand over either way. The exception is bounded by that construction: the first owner's invite of
a `PENDING` tenant (`POST /api/tenants`, its resend and its reassign) and the owner invite into an ownerless `ACTIVE`
tenant, nothing else. The instance's own mail ([ADR 0097](0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md) decision 3)
does not carry it (ADR 0097 decision 6): instance mail is optional, so the bootstrap must work without it.

**4. A recovery request answers `503` when the tenant cannot send**, beside the one answer
[ADR 0054 § Decision 6](0054-password-recovery-two-factor-release.md) gives every address. Non-enumerability protects
*"does this address have an account here"*; *"can this tenant send mail"* is a property of the deployment, scoped to the
tenant the request's `Host` resolves to, and which hosts map to tenants is public. So the sign-in screen may keep
offering the reset and let it fail honestly.

**5. The transport check runs before the rate limiter.** The limiter is charged before the account lookup so that a
known and an unknown address stay indistinguishable. But once a mailer-less request answers `503` and a rate-limited
one `202`, the pair would tell *"this address was asked about recently"*. The transport check depends on nothing about
the address, so it runs first — which also keeps a tenant that cannot send from spending limiter budget.

**6. A send that fails compensates the mint.** Without a copy-link a live, undeliverable token is the dangling artifact
ADR 0054 § 4 refused (*"no transport, no token"*). Three properties are chosen:

- **A compensation, not a rollback.** The mint commits before the send so a hung SMTP server never holds a pooled
  database connection; the failure path runs a second, short transaction.
- **It soft-revokes (`SUPERSEDED`), never deletes inline**, the invite model's rule; the purge job reaps the row, and a
  process that dies before compensating leaves a token that dies at its own expiry.
- **A failed re-issue leaves no live invitation.** The outstanding invite was superseded before the mint, and reviving
  a token the owner was replacing is worse than an honest error to retry. The rate-limit budget is not refunded: the
  limiter bounds send attempts.

**7. `delivery` stays on the member-invite response.** Decisions 2 and 6 leave `SENT` as its only reachable value
there; the field keeps the member response shaped like the Server Owner's, where all three values occur, and the
contract states per endpoint which values can occur.

## Consequences

- **The cost, stated plainly: a tenant with no working mailer cannot admit members.** It is accepted for three reasons.
  The bar is low — an auth-less LAN relay needs neither a stored password nor an openable config-secret key. The thing
  protected is real — the alternative routes a named person's admission token through whoever stands at the owner's
  screen. And an instance without mail is already one without password recovery
  ([ADR 0054 § 4](0054-password-recovery-two-factor-release.md)), where a forgotten password is permanent loss; this
  makes that state loud instead of latent.
- **Recovery is unchanged:** it already required mail; only its silence becomes a status code.
- **Exercising member invites in development and tests needs a mailer** ([cc.mail](../crosscutting/cc.mail.md)
  § Development).
- **Rejected — a `mail` capability on the setup manifest.** A capability is an off-switch for an optional feature, so
  shipping one would bless the configuration this decision refuses; and the client needs no advance answer.
- **Rejected — a server-level SMTP fallback for tenant mail.** It would remove the last copy-link but put an
  instance-wide secret behind every tenant's mail, which ADR 0048 § 4 refuses.
- **Rejected — keeping the copy-link behind a warning.** A control documented as unsafe but present is taken by whoever
  is in a hurry; the copy-link's whole problem is that it works.
