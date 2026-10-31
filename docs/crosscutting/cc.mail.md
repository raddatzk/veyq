# Concern: Mail

- **ID:** cc.mail
- **Type:** **Cross-cutting concern** — every mail veyq sends, for account, spaces, admin, moderation and the
  platform. It has no end-user goal of its own (the use-cases that send are linked per mail below) and serves no
  single feature.
- **Status:** built — both relays, every mail in [§ Which mails exist](#which-mails-exist-and-who-receives-them), the
  address-control record. What is decided and not built is in [§ Decided, not built](#decided-not-built).
- **Decisions:** [ADR 0048](../decisions/0048-provider-neutral-smtp-mail-transport.md) (transport, per-tenant
  settings), [ADR 0049](../decisions/0049-email-delivery-boundary.md) (what a mail may carry),
  [ADR 0055](../decisions/0055-mail-is-a-precondition-not-a-capability.md) (mail is a precondition),
  [ADR 0074](../decisions/0074-address-control-is-recorded-where-nothing-else-proves-it.md) (address control),
  [ADR 0097](../decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md) decisions 3–6 (the
  instance's own mail), [ADR 0050](../decisions/0050-operator-config-secret-encryption.md) (the sealed password).
- **Operators read:** [Storage and email](../../site/src/content/docs/run/storage-and-email.md) on the docs site.

## Two relays, one wire

veyq speaks plain SMTP (host, port, username, password, STARTTLS, from) over the Vert.x `MailClient` that the core
`quarkus-mailer` extension brings; no vendor SDK, no `quarkus.mailer.*` transport and no `veyq.mail.*` group. Both
relays hand their transport to one wire, `core/mail/SmtpDelivery`, which decides whether a transport can send and puts
one message on it; it knows neither whose transport it is nor what the message says. A client is built per send, so a
changed setting needs no restart.

| Relay | Where it lives | Who sets it | Sends |
|---|---|---|---|
| **A tenant's** | `veyq_tenant_mail_settings`, `/api/tenants/{tenantId}/mail-settings` (owner-gated, as the tenant's terms are) | the Tenant Owner ([uc.admin.tenant-mail-settings](../features/admin/use-cases/uc.admin.tenant-mail-settings.md)) | the tenant's mail to its members, from the configured `from` or `noreply@<tenant domain>` |
| **The instance's** | `veyq_instance_mail_settings`, `/api/instance/mail-settings` (admin channel) | the Server Owner, in admin-web | the host's own notices only — never a tenant's default or fallback |

Both rows hold the same fields; the password is sealed under the config-secret key with the fingerprint of the key
that sealed it ([ADR 0050](../decisions/0050-operator-config-secret-encryption.md)), write-only on every surface (a
read shows only `passwordSet`), opened only at send time. A relay is **sendable** when it is enabled and has a host;
with a username the stored password must open under the current config-secret key, while an auth-less relay (a LAN
relay) needs no password and no openable key (`TenantMailSender.sendable`, `InstanceNoticeMailer.sendable`).

The operator who holds the config-secret key and runs the send path can open every stored SMTP password; that is
inherent to sending on a tenant's behalf. Self-hosted, the operator and the Tenant Owner are the same person.

## Which mails exist, and who receives them

Each kind's recipients are decided by the ADR or use-case that owns the duty; a new kind or recipient is decided there
and added here.

**Through a tenant's relay** — only to a member of the tenant the request's `Host` resolves to, and only when that
tenant can send:

| Mail | To | Decided by |
|---|---|---|
| member invite | the invited address | [ADR 0055](../decisions/0055-mail-is-a-precondition-not-a-capability.md) § 2, [uc.spaces.add-member](../features/spaces/use-cases/uc.spaces.add-member.md) |
| password reset | the account's address | [ADR 0054](../decisions/0054-password-recovery-two-factor-release.md) § 4 |
| address confirmation | the account's address | [ADR 0074](../decisions/0074-address-control-is-recorded-where-nothing-else-proves-it.md) § 6, [uc.account.confirm-email-address](../features/account/use-cases/uc.account.confirm-email-address.md) |
| address change: the link, and the notice | the new address; the old address | ADR 0074 § 8, [uc.account.change-email](../features/account/use-cases/uc.account.change-email.md) |

**Through a tenant's relay, sent by the Server Owner:** the owner invite into an ownerless `ACTIVE` tenant
(`POST /api/tenants/{tenantId}/owner-invite`, [ADR 0101](../decisions/0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md)
decision 9), to the address of one of the tenant's members. It goes through that tenant's relay when the relay can send
— never through the instance's ([ADR 0097](../decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md)
decision 3) — and the response always carries `bootstrapSecret` as well, so the Server Owner can hand the link over when
`delivery` is `FALLBACK_COPYLINK` or `FAILED`: an ownerless tenant has nobody left who may repair its relay, so the
invite cannot wait for mail ([ADR 0055](../decisions/0055-mail-is-a-precondition-not-a-capability.md) § 3).

**Through the instance's relay** (`InstanceNoticeMailer`):

| Mail | To | Decided by |
|---|---|---|
| a one-time code that confirms an address | the Server Owner's account address on saving the settings; a moderator's on accepting the appointment; the instance contact's notification address on saving it | ADR 0097 decision 5, [ADR 0099](../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 7, [ADR 0093](../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 11 |
| release notices (a `.ics` event for a dated one), rotation due | the Server Owner | ADR 0097 decisions 2, 4, 6; ADR 0099 § 4 |
| a new case (the urgent categories at once, the rest bundled), case reminders, an objection received | each accepted moderator and the instance contact | ADR 0099 § 7; ADR 0093 §§ 2, 11 |
| a report's receipt, and its decision with the reporter's redress | the reporter who left a contact — mailed from memory, the address never stored in the clear | ADR 0093 §§ 2, 7 |
| a statement of reasons, an objection's answer | each affected member | ADR 0093 § 7 |
| an account's erasure | the account being erased | [ADR 0101](../decisions/0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md) decision 2 |
| a tenant's deletion | every member of the tenant | ADR 0101 decision 10 |
| an application left switched off | every member of the tenant | [uc.platform.erase-switched-off-application](../features/platform/use-cases/uc.platform.erase-switched-off-application.md) |

Release, rotation and moderation notices reach an address only once its code confirmed it; a changed address or
changed settings ask again. Without instance mail admin-web shows the release notices, and a moderation step still
goes ahead: what must happen does not wait for a mail, and a Server Owner's step answers what its mail reached
(`statementDelivery`, ADR 0093 § 7).

**No mail at all:** the Server Owner's invite to a tenant's first owner. A `PENDING` tenant has no relay and no owner
to set one, and instance mail does not carry it (ADR 0097 decision 6), so it travels as a copy-link, the other half of
[ADR 0055](../decisions/0055-mail-is-a-precondition-not-a-capability.md) § 3's exception: `POST /api/tenants` and the
resend or reassign of that invite answer with `bootstrapSecret`, and `LoggingTenantInviteMailer` logs only that nothing
was delivered — never the token, and it writes no file.

## What a mail may carry

The rule is [ADR 0049](../decisions/0049-email-delivery-boundary.md)'s: **veyq's machinery never puts a sealed field
into a mail** — no scope, group, node or member name, no member list, no content. Recipient and time of sending are
conceded operational metadata, listed in the [security model](security-model.md)'s server-visible inventory with the
account address and an owner's template text. The SMTP provider receives the recipient and the message, never a
session, a user id or content.

- **A tenant's mail** is rendered over the closed `MailContext` — `{{link}}`, `{{domain}}`, `{{expiry}}` and nothing
  else, by a logic-less substitution (`InviteMailRenderer`). The link's secret rides the URL fragment. Only the invite
  has an owner-authored subject and body (optional; the shipped default is content-free and bilingual); a template
  naming any other tag is refused on write (`400 invalid_mail_template`). Reset, confirmation and address-change copy
  is veyq-fixed. `InviteMailRendererTest` pins the context to those three fields.
- **The instance's mail** is veyq-fixed bilingual text — the statement's from `contracts/moderation/` — filled only
  with values the server already holds in the clear: a release and its dates, a code, a case's category and age, a
  tenant's name, a deletion date. A moderation notice says where the moderation page is as plain text, never a key, a
  host or a link.
- **Words an author typed** are a consented disclosure by that author, operator- and provider-visible like the rest of
  the mail: a Tenant Owner's invite template, and a Server Owner's facts on their own step (a sign-in block, a tenant
  taken offline), mailed once from memory. A moderator's facts are sealed and never mailed: the member reads them in
  the app.

## Mail is a precondition, not a capability

No capability, manifest entry or setup flag says whether an instance can mail
([ADR 0055](../decisions/0055-mail-is-a-precondition-not-a-capability.md)). Each action that needs a tenant's mail
refuses at the moment it cannot work:

- **A member invite** — first mint and re-issue — is refused before any mint when the tenant cannot send. The mint
  commits before the send, so a hung SMTP server holds no database connection; a send that fails compensates in a
  second short transaction by soft-revoking the invite (`SUPERSEDED`, reaped by `TenantInvitePurge`). A failed
  re-issue leaves no live invite, and the rate-limit budget is not refunded. The response's `delivery` keeps its
  three values for the Server Owner's endpoints; on the member path only `SENT` occurs.
- **A password reset, an address confirmation and an address change** answer `503 mail_unavailable` when the tenant
  addressed by the `Host` cannot send. On the reset this check runs before the per-address rate limiter, so a `503`
  and a `202` never tell apart an address asked about recently.

## Address control

`veyq_user.email_confirmed_at` records that the account holder showed, at that time, that they read the mail sent to
this address ([ADR 0074](../decisions/0074-address-control-is-recorded-where-nothing-else-proves-it.md)). It is set by
redeeming a `MEMBER` invite, completing a password reset, opening a confirmation link, and confirming an address
change (which stamps the new address); a path that changes the address otherwise clears it in the same write. The
bootstrap owner's redemption and first-run setup set nothing. **It gates nothing**; it is shown in the account's
security settings and offered once in the tenant's mail settings after a save that leaves the relay able to send.

A confirmation token (`veyq_email_confirmation`) is a 256-bit random value stored only as its SHA-256, bound to the
account and the address it was mailed to; it expires after `PT24H`, a fresh request supersedes it, consumption is a
compare-and-set on `PENDING`, `EmailConfirmationPurge` expires and purges it, and a per-account send budget bounds
requests. Confirming needs the account's own session and an address still equal to the one mailed. A pending address
change (`veyq_email_change`) follows the same lifecycle and spends the same budget; its flow is
[uc.account.change-email](../features/account/use-cases/uc.account.change-email.md).

The instance's addresses — the Server Owner's, a moderator's and the instance contact's notification address — are
confirmed by the typed code of the instance relay instead (ADR 0097 decision 5): a link would reach nothing, because
admin-web is localhost-only.

## Development

No tenant has mail settings in a fresh dev stack. The seed reads the owner invite's `bootstrapSecret` from
`createTenant`'s response; a member invite needs a relay, so the dev stack offers **Mailpit** (`--profile mail`,
`infra/docker/docker-compose.yml`) for a tenant to point at, and the web e2e harness reads invites and reset links from
it. Backend slice tests send through an in-JVM GreenMail server.

## Decided, not built

- **A gate on open self-registration.** No such path exists. If one is added, it must prove address control before the
  account exists (ADR 0074 § 3).
- **Deliverability** — SPF/DKIM alignment of a tenant's own from-address on a shared server — is SaaS operations,
  not a transport question.
