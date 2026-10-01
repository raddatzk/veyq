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

## Alternate / exception flows

- **A non-owner tries to read or edit** → refused (owner-gated, `403 not_tenant_owner`); a non-member is `404`
  (indistinguishable, ADR 0042).
- **No SMTP configured, or `enabled` off, or the KEK absent** → inviting a member is refused with
  `503 mail_unavailable` and a recovery request with the same code. It is not degraded to a copy-link: a token
  minted for a named other person must not be routed through the owner
  ([ADR 0055](../../../decisions/0055-mail-is-a-precondition-not-a-capability.md)). The bar is deliberately low —
  an auth-less LAN relay is enough.
- **The owner-bootstrap invite** ([uc.admin.create-tenant](uc.admin.create-tenant.md)) stays copy-link, and is the
  only one that does: a PENDING tenant has no owner yet to configure SMTP, and there is no server-level default.
- **A template references an unknown `{{tag}}`** → rejected at save (`400 invalid_mail_template`), never stored, so
  a malformed template can never reach send time.

## What this means for you / Was das für dich bedeutet

**EN —** If you run a veyq space, point it at an email server so invitations arrive as real emails from your own
domain. This is a setup step you cannot skip: without it you cannot invite anyone, and nobody in your space can
reset a forgotten password. A simple relay that needs no login is enough. You can word the invitation yourself,
dropping in the link, your domain and the expiry date. Your email server and ours only ever see that
invite: never the names of your groups, memories or members. What you type into the invitation, you're choosing to
share — so keep it about the invite.

**DE —** Wenn du einen veyq-Bereich betreibst, verbinde ihn mit einem E-Mail-Server, damit Einladungen als echte
E-Mails von deiner eigenen Domain ankommen. Das ist ein Einrichtungsschritt, den du nicht auslassen kannst: ohne
ihn kannst du niemanden einladen, und niemand in deinem Bereich kann ein vergessenes Passwort zurücksetzen. Ein
einfaches Relais ohne Anmeldung genügt. Den Einladungstext kannst du selbst formulieren und Link, Domain und
Ablaufdatum einsetzen. Dein E-Mail-Server und unserer sehen immer nur diese Einladung: nie die Namen deiner
Gruppen, Erinnerungen oder Mitglieder. Was du in die Einladung schreibst, gibst du bewusst preis — halte es also
bei der Einladung.

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
  [ADR 0020](../../../decisions/0020-grant-distribution-invite-wrap.md) (the invite link is an admission token,
  not a key).
- Operator view (wiki): [Running your own instance](../../../../wiki/en/running-your-instance.md)
