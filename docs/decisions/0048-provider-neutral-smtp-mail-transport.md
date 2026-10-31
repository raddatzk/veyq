# ADR 0048 — A tenant's mail is sent over its own SMTP relay, configured as runtime settings

- Status: Accepted
- Built: yes
- Date: 2026-07-24
- Model: [cc.mail](../crosscutting/cc.mail.md)
- Replaced in part: § 4, § 5 by ADR 0055 (built); § 4 by ADR 0097 (built)

## Context

A veyq instance is multi-tenant under one Server Owner: each tenant has its own domain and its own owner, so each is
a distinct sending identity — an invite to `family.example.com` should come from that domain, not from a shared
instance address. An instance-wide static SMTP configuration cannot express that. And *self-hostable by default* is
also the security guarantee ([ADR 0026](0026-client-trust-tiers.md)): the transport must work with a self-hoster's own
SMTP server, as the store works with any S3 store and geocoding with any provider
([ADR 0016](0016-geocoding-provider-neutrality.md)).

*What* a mail carries is [ADR 0049](0049-email-delivery-boundary.md); how the stored password is protected is
[ADR 0050](0050-operator-config-secret-encryption.md). This ADR fixes the transport and where its configuration lives.

## Decision

**Each tenant configures its own SMTP as runtime settings; a mail is built from that tenant's settings at send time.**

1. **Generic SMTP, no vendor SDK.** Host, port, username, password, STARTTLS and from — over the `MailClient` the core
   `quarkus-mailer` extension brings.
2. **Configuration is per-tenant runtime data**, written through an owner-gated resource as the tenant's terms are
   ([ADR 0035](0035-server-terms-of-service.md)) — not deployment properties. A tenant owner reconfigures without a
   redeploy.
3. **The password is a server-held secret, encrypted at rest** ([ADR 0050](0050-operator-config-secret-encryption.md)),
   write-only over the API and opened only at send time.
4. **Per-tenant, with no server-level default for tenant mail.** A tenant's mail never falls back to another relay, so
   there is no instance-wide secret for tenant mail. The instance's own mail settings
   ([ADR 0097](0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md) decision 3) carry only the host's
   notices and are never a tenant's default. What a tenant that cannot send gets instead is
   [ADR 0055](0055-mail-is-a-precondition-not-a-capability.md) § 1.
5. **The owner-bootstrap invite cannot be mailed.** A `PENDING` tenant has no owner yet to configure SMTP and there is
   no server-level fallback; how that invite travels instead is
   [ADR 0055](0055-mail-is-a-precondition-not-a-capability.md) § 3.
6. **An owner-authored bilingual body over a closed variable set, from the tenant's own address.** The invite's subject
   and body come from an optional owner-authored template (veyq ships a content-free EN + DE default) through a
   logic-less `{{variable}}` substitution over `{{link}}`, `{{domain}}` and `{{expiry}}` only — the mechanical half of
   [ADR 0049](0049-email-delivery-boundary.md)'s boundary. The from-address is the tenant's.

## Consequences

- **Each tenant's invites come from its own domain**, and any SMTP server works.
- **Shared-instance trust, stated honestly.** The operator holds the config-secret key and runs the send path, so it can
  open a tenant's SMTP password. That is inherent to sending on a tenant's behalf; self-hosted, operator and tenant
  owner are one person.
- **Deliverability** — a per-tenant from-address needs the tenant's SPF/DKIM to authorize the server — is SaaS
  operations, not this decision.
- **Rejected — an instance-wide static `quarkus.mailer.*` configuration.** Simplest, but one sending identity for every
  tenant, and a redeploy to change it.
- **Rejected — a hosted provider's SDK.** Breaks self-hostability and locks to a vendor.
- **Rejected — a server-level default with a per-tenant override.** It reintroduces an instance-wide secret for tenant
  mail and more configuration, for the one case the copy-link covers.
- **Rejected — a plaintext password in the database.** A database dump must yield no usable secret.
- **Rejected — a templating engine (Qute, jmustache).** Sections, partials and reflection are a wider surface to audit
  against ADR 0049 for a three-variable body; a closed substitution makes the closed set a property by construction. A
  real engine is reconsidered the day a mail needs layout.
