# ADR 0048 — Tenant invites are sent over per-tenant SMTP, configured as runtime settings

- Status: Accepted
- Date: 2026-07-24

## Context

veyq has no mail-send capability. Tenant/member invites are minted and the raw accept link is **copy-pasted**
by the owner ([`InviteBootstrapSecret`](../../contracts/api/tenants.openapi.yaml) — "works without any email
infrastructure, the mailer is optional", cr-0080) or written to a dev-only file for the seed loop (cr-0046).
The `TenantInviteMailer` port exists with a single `LoggingTenantInviteMailer` impl; its own doc says "a real
SMTP-backed mailer must be wired". The Email theme needs that real send path.

**A veyq instance is multi-tenant under a single Server Owner** (measured): one Server Owner
(`POST /api/setup/owner`, 409 on a second) hosts **many tenants, each with its own unique `domain` and its own
tenant-owner** (`createTenant`, `veyq_tenant.domain UNIQUE`). So each tenant is a distinct sending identity — an
invite to `family.example.com` should come from *that* domain, not a shared instance address. An instance-wide
static SMTP config cannot express that. The owner's decision: **mail is configured per-tenant, as runtime
settings, not as deployment properties** — a tenant-owner sets up their own SMTP the way they already set up
their [Terms of Service](0035-server-terms-of-service.md).

**Self-hostable by default is also the security guarantee** ([ADR 0026](0026-client-trust-tiers.md)): the
transport must work for a self-hoster's own SMTP, not force a hosted provider — the same stance as the
vendor-neutral S3 SDK (root `CLAUDE.md`) and geocoding ([ADR 0016](0016-geocoding-provider-neutrality.md)).

*What* a mail carries is [ADR 0049](0049-email-delivery-boundary.md); *how the stored SMTP password is protected
at rest* is [ADR 0050](0050-operator-config-secret-encryption.md). This ADR fixes the **transport + config
model**. (It supersedes an earlier draft direction of this same ADR — instance-wide static `quarkus.mailer.*` +
env — which could not give a tenant its own sending identity; see Rejected.)

## Decision

**Each tenant configures its own SMTP as runtime settings; a mail is sent by building that tenant's SMTP client
at send time. No sendable tenant SMTP ⇒ the mail-bound action is refused (§4); only the owner-bootstrap invite
keeps the copy-link (§5).**

1. **Generic SMTP, no vendor SDK.** Transport is plain SMTP (host / port / username / password / STARTTLS /
   from) over the Vert.x `MailClient` that rides in transitively with the core `quarkus-mailer` extension. No
   SendGrid/SES/Postmark SDK.
2. **Config is per-tenant runtime data, via the ADR-0035 settings pattern** — not deployment properties. A
   per-tenant mail-settings record (host, port, from-address, username, TLS, enabled + the encrypted password)
   is read/written through an **owner-gated** resource, exactly mirroring `updateTenantTerms` (membership-gate →
   `isOwner` → store). There is **no** `quarkus.mailer.*` env transport config and **no** `veyq.mail.*` group.
3. **The password is a server-held secret, encrypted at rest** ([ADR 0050](0050-operator-config-secret-encryption.md)):
   the DB holds only ciphertext + nonce, the field is **write-only** over the API (never returned or logged —
   reads expose only a `passwordSet` boolean), and it is decrypted only at send time to build the client.
4. **Per-tenant, no server-level default.** Mail settings exist only at tenant scope (the owner's choice), so
   there is no instance-wide secret. The `LoggingTenantInviteMailer` copy-link fallback (+ the cr-0046 dev-file)
   that a tenant without settings once fell back to now serves **only the owner-bootstrap invite** of §5 —
   [ADR 0055](0055-mail-is-a-precondition-not-a-capability.md) made a working mailer a precondition for every
   other mail-bound action, so a tenant with no sendable settings is refused rather than degraded.
5. **The owner-bootstrap invite stays copy-link.** A PENDING tenant has no owner yet to configure SMTP and there
   is no server-level fallback (per-tenant only), so the Server-Owner → tenant-owner bootstrap invite uses the
   copy-link (as today). It is the **only** surviving copy-link ([ADR 0055 §3](0055-mail-is-a-precondition-not-a-capability.md)):
   **member** invites are emailed once the tenant owner has configured SMTP, and refused until then.
6. **Owner-authored bilingual body over a closed variable set, from the tenant's own address.** The invite
   subject + body are rendered from a per-tenant, owner-authored template (optional; veyq ships a bilingual
   EN + DE default) via a **minimal, logic-less `{{variable}}` substitution over a closed, non-secret variable
   set** — `{{link}}`, `{{domain}}`, `{{expiry}}` only. No sections, no partials, no lambdas, and **no** variable
   that resolves to a sealed field: the renderer is handed only those three values and can reach nothing else
   (the mechanical half of the [ADR 0049](0049-email-delivery-boundary.md) boundary). What an owner *types* into
   their own template is a consented, operator/provider-visible disclosure — [ADR 0049](0049-email-delivery-boundary.md)
   §2. The from-address is the tenant's (`noreply@<tenant.domain>` or the configured `from`), reinforcing the
   delivery boundary.

## Consequences

- **Per-tenant sending identity, runtime-editable.** Each tenant's invites come from its own domain; the owner
  reconfigures SMTP with no redeploy. Self-hostable: any SMTP server works.
- **The dev loop is untouched.** No tenant has mail settings in dev ⇒ the logging mailer + dev-file run ⇒
  `seed:dev` still reads the token. A **Mailpit** (`mail` profile) container is available for a tenant to point
  at for smoke. (MailHog archived → Mailpit, per no-EOL-deps.)
- **New machinery:** the first server-held secret at rest ([ADR 0050](0050-operator-config-secret-encryption.md));
  a per-tenant `MailClient` built at send time (a per-tenant client cache is a deferred optimization — invites are
  low-frequency, so build-per-send keeps correctness simple with no stale-config window); an owner-gated settings
  resource (a contract change — the earlier "no contract change" held only for the static variant).
- **Shared-instance trust (stated honestly).** Configuring per-tenant SMTP entrusts the **Server Operator** with
  that credential — the operator holds the [ADR 0050](0050-operator-config-secret-encryption.md) KEK *and* runs
  the send path, so it can decrypt the tenant's SMTP password. This is inherent to "the server sends mail on the
  tenant's behalf," not a new leak. **Self-hosted: operator = tenant, moot.**
- **Deliverability alignment is a SaaS-ops concern (#38).** A per-tenant from-address needs the tenant's DNS
  (SPF/DKIM) to authorize the sending server; tuning that is #38, not this ADR.
- **Rejected — instance-wide static `quarkus.mailer.*` + env (the earlier draft direction).** Simplest, but one
  SMTP identity for all tenants on a multi-tenant server, and reconfigure = redeploy. Superseded here.
- **Rejected — a hosted provider SDK.** Breaks self-hostability (the security guarantee) + vendor lock;
  deliverability ops are #38, not a transport decision.
- **Rejected — a server-level default + per-tenant override.** The owner chose per-tenant only; a default
  reintroduces an instance-wide server-held secret and more config surface, for the one case the copy-link still
  covers (the bootstrap invite); an unconfigured tenant is refused instead ([ADR 0055](0055-mail-is-a-precondition-not-a-capability.md)).
- **Rejected — plaintext password in the DB.** Breaks the "a DB dump yields no usable secret" property; encrypted
  at rest instead ([ADR 0050](0050-operator-config-secret-encryption.md)).
- **Rejected — a full templating engine (Qute/`MailTemplate`/jmustache).** Sections, partials, lambdas and
  model reflection are ceremony for a three-variable invite body — and every extra capability is a wider surface
  to audit against the [ADR 0049](0049-email-delivery-boundary.md) boundary. A minimal, logic-less `{{variable}}`
  substitution over the closed set is the proportionate mechanism (no new dependency, and the closed set is a
  property *by construction* rather than one a general engine must be fenced into); a real engine is reconsidered
  the day a mail needs real layout.
