# UC: Tenant mail settings — the owner configures per-tenant SMTP + the invite template

- **ID:** uc.admin.tenant-mail-settings
- **Primary actor:** The **tenant owner**, signed in — a session, no unlocked vault needed (mail config is
  operational, not content).
- **Goal:** Configure the tenant's **own SMTP** (host, port, from, username, password, TLS) as runtime settings so
  **member** invites are emailed from the tenant's own domain, and optionally author the invite **subject/body
  template** — rendered over a closed, non-secret variable set. This is not optional decoration: with no sendable
  SMTP the tenant cannot admit members at all, and password recovery is unavailable to its people
  ([ADR 0055](../../../decisions/0055-mail-is-a-precondition-not-a-capability.md)).
- **Preconditions:** A session as the **tenant owner** (`isOwner`); the tenant is ACTIVE. To actually *send*, the
  operator's env carries the config-secret KEK ([ADR 0050](../../../decisions/0050-operator-config-secret-encryption.md));
  without it the password cannot be sealed — unless the relay needs no login at all, in which case no password and
  no KEK are required.
- **Postconditions (success):** the tenant's mail settings row is stored — the SMTP password **sealed at rest**
  (ciphertext + nonce, never plaintext), the templates stored as owner-authored text (null ⇒ veyq's default). Once
  `enabled` with a complete, openable config, member invites and password recovery start working; until then both
  are refused.

## What these settings are here

Per-tenant mail configuration ([ADR 0048](../../../decisions/0048-provider-neutral-smtp-mail-transport.md)):
generic SMTP (no vendor SDK), configured the way the owner already configures
[Terms](uc.admin.tenant-terms.md) — owner-gated runtime data, not deployment properties. The **password** is a
server-held secret encrypted at rest under the server-managed config-secret KEK (ADR 0050): the DB holds only
ciphertext + nonce, the field is **write-only** over the API (a read exposes only `passwordSet`), and it is
decrypted only at send time.

The **subject/body template** is owner-authored and rendered by a minimal, logic-less `{{variable}}` substituter
over a **closed set — `{{link}}`, `{{domain}}`, `{{expiry}}` only** (ADR 0048 §6). veyq's machinery interpolates
**nothing sealed** — no scope/group/memory name, member list, real name or content is ever reachable by the
renderer ([ADR 0049](../../../decisions/0049-email-delivery-boundary.md)). What the owner *types* into the template
is a **consented disclosure of the owner's own tenant**, operator- and provider-visible like the rest of the mail;
a template referencing any other `{{tag}}` is rejected at save. This is the operator-blind boundary of the mail
channel, stated honestly.

## Main flow

1. The owner opens the **mail settings** editor (admin/settings), owner-gated.
2. They enter SMTP host, port, from-address, username, password, and toggle TLS + `enabled`.
3. Optionally they edit the invite **subject/body** template, using `{{link}}`, `{{domain}}`, `{{expiry}}`.
4. On **save** the server validates the template against the closed variable set (an unknown `{{tag}}` ⇒
   `400 invalid_mail_template`), **seals** the password under the config-secret KEK (ADR 0050), and stores the
   row. The password is never returned or logged; a re-read shows only `passwordSet`.
5. From then on, a new **member** invite ([uc.spaces.add-member](../../spaces/use-cases/uc.spaces.add-member.md)) is
   rendered from the tenant's template (or veyq's default) over `{link, domain, expiry}` and sent from the tenant's
   own address; the raw link still rides the URL **fragment** ([ADR 0020](../../../decisions/0020-grant-distribution-invite-wrap.md)).
6. A save that leaves the relay able to send **offers, once, a confirmation mail to the owner's own address**
   ([uc.account.confirm-email-address](../../account/use-cases/uc.account.confirm-email-address.md)). It is offered,
   never sent unasked. The owner admitted by the bootstrap copy-link is the one account whose address nothing has
   proven, and a mail to themselves is also the first proof that this relay reaches anyone
   ([ADR 0074](../../../decisions/0074-address-control-is-recorded-where-nothing-else-proves-it.md)).

## Alternate / exception flows

- **A non-owner tries to read or edit** → refused (owner-gated, `403 not_tenant_owner`); a non-member is `404`
  (indistinguishable, ADR 0042). The settings rail lists the **mail** section for the tenant's owner only, and its address leads a member
  back to `/settings` without mounting the page, so a member is not offered a page that can only refuse them (the
  server's refusal stays the gate).
- **No SMTP configured, or `enabled` off, or the KEK absent** → inviting a member is refused with
  `503 mail_unavailable` and a recovery request with the same code. It is not degraded to a copy-link: a token
  minted for a named other person must not be routed through the owner
  ([ADR 0055](../../../decisions/0055-mail-is-a-precondition-not-a-capability.md)). The bar is deliberately low —
  an auth-less LAN relay is enough.
- **The owner-bootstrap invite** ([uc.admin.create-tenant](uc.admin.create-tenant.md)) stays copy-link, and is the
  only one that does: a PENDING tenant has no owner yet to configure SMTP, and there is no server-level default.
- **A template references an unknown `{{tag}}`** → rejected at save (`400 invalid_mail_template`), never stored, so
  a malformed template can never reach send time.
- **Host, port or TLS changes and the password field is left empty while one is stored** → refused at save
  (`400 mail_password_required`), nothing stored, and the form asks for the password again. A kept password only
  ever goes to the server it was entered for: otherwise one co-owner could point the relay at a host they run, with
  TLS off, and read another owner's password from the SMTP login.

## User impact

Invitations and password-reset links reach your members as real emails from your own domain, carrying only a link and
never anything you keep in your space.

## Related

- **Feature:** [admin](../_index.md)
- **Use-cases:** [uc.spaces.add-member](../../spaces/use-cases/uc.spaces.add-member.md) (the member invite this
  emails), [uc.admin.tenant-terms](uc.admin.tenant-terms.md) (the settings-CRUD pattern this mirrors),
  [uc.admin.create-tenant](uc.admin.create-tenant.md) (the tenant + owner this configures).
- **Cross-cutting:** [security-model](../../../crosscutting/security-model.md) (the mail send-fact + owner template
  text in the server-visible inventory; the operator-blind mail boundary),
  [cc.inclusive-language](../../../crosscutting/cc.inclusive-language.md) (the DE voice).
- **Decisions:** [ADR 0048](../../../decisions/0048-provider-neutral-smtp-mail-transport.md) (per-tenant SMTP as
  runtime settings), [ADR 0049](../../../decisions/0049-email-delivery-boundary.md) (what a mail carries),
  [ADR 0050](../../../decisions/0050-operator-config-secret-encryption.md) (the password sealed at rest),
  [ADR 0111](../../../decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) (the mailed invite link carries the
  invite key only sealed under a code the owner passes on outside mail).
- Operator view (docs site): [Running your own instance](../../../../site/src/content/docs/run/index.md)
- Run area of the docs site: [Storage and email](../../../../site/src/content/docs/run/storage-and-email.md), [Troubleshooting](../../../../site/src/content/docs/run/troubleshooting.md)
- End-user view (docs site): [Email for your space](../../../../site/src/content/docs/space-owners/email.mdx)
