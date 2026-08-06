# ADR 0055 — A one-time link travels by mail or not at all: a mailer is a precondition, not a capability

- Status: Accepted
- Date: 2026-08-06

## Context

[ADR 0054 § Decision 4](0054-password-recovery-two-factor-release.md) sorted one-time links by *whose thing they
open*, and concluded that a token minted for one named person must not be routed through a third party — not
because that party could open it, but because a secret addressed to someone else has no business passing through
their hands at all. It named the member-invite copy-link as the same case and deferred it to inbox #118. This
ADR is that case, decided. The rule itself is not restated here; 0054 §4 owns it.

What is new is the **operational** consequence, which 0054 could sidestep and this cannot. Recovery never
shipped a copy-link, so requiring mail there took nothing away. The member invite *has* one, and retiring it
makes a working mailer a **precondition for admitting a member** — on a product whose hard invariant is
*self-hostable by default*, and whose own coverage justifies the copy-link with that exact phrase
(`docs/coverage/features/spaces/use-cases/uc.spaces.add-member.yaml`, AC `invite-link-handoff`).

Two measured facts constrain the shape of the answer.

**1. SMTP is per-tenant, so no startup check can reach it.** [ADR 0048 §2/§4](0048-provider-neutral-smtp-mail-transport.md)
made mail settings owner-gated *runtime data*, scoped to a tenant, with no server-level default —
`TenantMailSender.settings(domain)` resolves them per send. At boot there is no tenant to ask, and a tenant's
settings change while the process runs. The blob store, the nearest precedent for a non-optional dependency,
can refuse at startup (`BlobStoreStartupCheck`: *"an instance without a store is misconfigured, not gracefully
degraded"*). Mail cannot be checked there.

**2. A capability flag would assert the opposite of this decision.** [uc.platform.capability-discovery](../features/platform/use-cases/uc.platform.capability-discovery.md) §4
defines a capability as the gate for a feature that is **optional per instance**: present → offered, absent →
hidden. Advertising "this instance can send mail" would therefore adopt a mailer-less instance as a *supported
configuration* — precisely what this ADR declines to do. The blob store is likewise not optional and likewise
carries no client-facing flag.

## Decision

**1. Mail is a base function of a veyq instance, not an optional feature.** There is no capability, no manifest
entry and nothing for a client to ask in advance. Because a startup check cannot see per-tenant SMTP (Context 1),
the refusal moves into the **actions** that need mail, and each one refuses loudly at the moment it cannot work.
This amends [ADR 0048 §4/§5](0048-provider-neutral-smtp-mail-transport.md), which made the mailer optional and
the copy-link the standing fallback.

**2. A member invite is mail-only.** The invite response no longer carries `bootstrapSecret`, and the two
member surfaces no longer offer a link to copy. Inviting — first mint and re-issue alike — is **refused before
any mint** when the tenant has no sendable mail settings, rather than minting an invitation that reaches nobody.

**3. The ServerOwner's invite to a tenant's first owner is the one surviving copy-link.** A `PENDING` tenant has
no mail settings by construction and no owner yet to configure them, so no channel can exist ([ADR 0048 §5](0048-provider-neutral-smtp-mail-transport.md)).
The exception is bounded by that construction, not by convenience: it covers `POST /api/tenants` and the
resend/reassign of that same bootstrap invite, and nothing else. A **server-level SMTP configuration** would
dissolve even this one — it is the obvious later simplification, and it is deliberately not taken here, because
it would reintroduce the instance-wide mail secret that ADR 0048 §4 removed.

**4. `POST /api/recovery/request` answers `503 mail_unavailable` when it cannot send.** This narrows
[ADR 0054 § Decision 6](0054-password-recovery-two-factor-release.md), which made every outcome answer an
identical `202`. Non-enumerability protects *"does this address have an account here"*; *"can this tenant send
mail"* is a property of the deployment, so answering it plainly leaks nothing about any address, and the
sign-in screen may keep offering the reset link and let the request fail honestly.

The answer is scoped to **the tenant addressed by the request's `Host`**, not to the instance — mail settings
are per-tenant (Context 1), and `RecoveryResource.activeHost()` is what selects them. The precise claim is "this
tenant cannot send mail". Which hosts map to tenants is public by construction, so that is not a disclosure.

**5. The transport check runs *before* the rate limiter.** `RecoveryHandler.request` charges the per-address
limiter first, so that a known and an unknown address stay indistinguishable in response *and* timing. That
ordering rule is about the **account lookup** and stays. But once a mailer-less request answers `503` while a
rate-limited one answers `202`, the pair becomes an oracle for *"this address was asked about recently"* — an
address-level leak, the very class §4 above is careful not to open. So the transport check is hoisted above the
limiter: it depends on nothing about the address, and answering it first also stops a tenant that cannot send
from burning limiter budget.

**6. A send that fails compensates the mint.** An invite whose SMTP send errors leaves no invitation behind:
without a copy-link there is nobody the live token could reach, and a minted-but-undeliverable secret is exactly
the dangling artifact [ADR 0054 §4](0054-password-recovery-two-factor-release.md) refused for recovery
(*"no transport, no token"*). Three properties of that compensation are chosen, not incidental:

- **It is a compensation, not a transaction rollback.** The mint deliberately commits *before* the send so a
  hung SMTP server never holds a pooled DB connection (`cr-email-invite-send-hardening`, `TenantHandler.commitThenSend`).
  Pulling the send back inside the transaction would reintroduce that fault. The failure path instead runs a
  second, short transaction.
- **It soft-revokes (`→ SUPERSEDED`), it does not delete.** *Soft-invalidate now, hard-delete later, never
  inline* is the invite model's existing rule (`TenantInvitePurge`); the token is dead immediately either way,
  and the row is reaped by the job that already exists. If the process dies between the failed send and the
  compensation, the token survives only until its own expiry, which the same job enforces.
- **A failed re-issue leaves no live invitation at all.** `TenantStore.mintMemberInvite` supersedes the
  outstanding invite *before* minting, so compensating the new one does not restore the old. Reviving a token
  the owner was in the middle of replacing is worse than an honest error the owner can retry, so the previous
  invite stays dead. The rate-limit budget is likewise **not** refunded: what the limiter bounds is send
  attempts, and a failed send was one.

**7. `delivery` stays on the member-invite response, with a narrower reachable range.** Decisions 2 and 6
together remove every non-`SENT` outcome from that path — `FALLBACK_COPYLINK` is refused before the mint and
`FAILED` is no longer a success. Both surface as errors instead. The field is kept rather than dropped: it keeps
the member response shaped like the ServerOwner ones, where all three values remain reachable, and removing it
would churn the contract for no gain. Its documentation states per endpoint which values can actually occur, so
the contract does not list two outcomes a client will never see.

## Consequences

- **The cost, stated plainly: a tenant with no working mailer cannot admit members.** That is a real
  self-hostability cost and it is the reason this ADR exists rather than a code comment. It is accepted for
  three reasons. The bar is low — `TenantMailSender.sendable` keys SMTP auth on `username`, so an auth-less LAN
  relay needs neither a stored password nor an openable config KEK. The thing being protected is not
  hypothetical — the alternative is routing a named person's admission token through whoever is standing at the
  owner's screen. And the alternative posture is worse than it looks: an instance without mail is already an
  instance without password recovery ([ADR 0054 §4](0054-password-recovery-two-factor-release.md)), i.e. one
  where a forgotten password is permanent data loss. This decision makes that state loud instead of latent.
- **What does *not* change:** recovery already required mail. Only its silence becomes a status code, and #113's
  one open item shrinks from a flag threaded through three layers to a status code and its display.
- **The dev and test loops need a mailer to exercise member invites.** `LoggingTenantInviteMailer`'s copy-link
  path no longer serves them. Backend slice tests use the in-JVM GreenMail server already wired for
  `SmtpInviteMailSendTest`; the web e2e harness moves to Mailpit (`--profile mail`), which
  `web/src/features/account/recovery/recovery.e2e.test.ts` already reads. The ServerOwner bootstrap keeps its
  dev-invite file, so the local seed loop is untouched.
- **Rejected — a `mail` capability on the setup manifest.** It was the first cut of inbox #118 and it is wrong
  for the reason in Context 2: a capability is an off-switch for an optional feature, so shipping one would
  bless the configuration this decision refuses. It also bought nothing the status code does not, since the
  client needs no advance answer to show a link that may fail honestly.
- **Rejected — a server-level SMTP fallback.** It would remove the last copy-link (Decision 3) but reintroduce
  the instance-wide mail secret ADR 0048 §4 deliberately removed. Left as a possible future simplification, to
  be decided on its own merits rather than as a side effect of this one.
- **Rejected — keeping the copy-link behind a warning.** A control that is documented as unsafe but still
  present is taken by whoever is in a hurry; the copy-link's whole problem is that it works.
