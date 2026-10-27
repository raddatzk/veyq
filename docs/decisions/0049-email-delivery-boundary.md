# ADR 0049 — Sending mail is a send-time metadata + third-party boundary; a mail carries only delivery essentials

- Status: Accepted
- Built: yes
- Date: 2026-07-24

## Context

veyq is operator-blind: the server stores only ciphertext + opaque operational metadata
([security-model](../crosscutting/security-model.md)). Introducing a mail-send path
([ADR 0048](0048-provider-neutral-smtp-mail-transport.md)) creates two new exposures the model must state
honestly rather than pretend mail is free:

1. **The veyq operator, at send time.** The recipient address is already stored in plaintext — it is the login
   identifier and the source of the deterministic auth salt ([ADR 0003](0003-operator-blind-password-auth.md)).
   But *sending* adds a new access-pattern: the operator learns *who is contacted, when* (invite / verify
   traffic). The account email is, in fact, not yet listed in the security-model's server-visible metadata
   inventory — a pre-existing doc gap (inbox #43) this ADR closes for the email field.
2. **A mail provider — a different, consented boundary.** The SMTP server / relay learns recipient + timing too.
   This is the same shape already drawn for reverse-geocoding ([ADR 0016](0016-geocoding-provider-neutrality.md)):
   "operator-blind" names the **veyq** operator; a chosen external service is a **separate, consented** boundary,
   fenced so it can never widen the veyq operator's view.

## Decision

**A mail carries only what delivery needs, never confidential metadata; and the mail provider is a fenced,
consented third party.**

1. **A mail carries:** the accept/verify **link** (the secret rides the URL **fragment**, never a header or a
   path segment beyond the fragment), the tenant **domain**, an **expiry**, and neutral bilingual (EN + DE) copy.
   **From the tenant's own address** (`noreply@<tenant.domain>` or the configured `from`,
   [ADR 0048](0048-provider-neutral-smtp-mail-transport.md)) — the recipient sees the domain they are invited to,
   and no cross-tenant identity leaks.
2. **veyq's machinery injects no confidential metadata; owner-authored template prose is a consented
   disclosure.** The subject + body are rendered over a **closed, non-secret variable set** (`{{link}}`,
   `{{domain}}`, `{{expiry}}`, [ADR 0048](0048-provider-neutral-smtp-mail-transport.md) §6): veyq itself never
   interpolates a scope / group / memory name, a member list, a real name, or any content — those are sealed
   under `K_tenant` and the renderer cannot reach them, so the server never turns a mail into a side-channel for
   what the database seals. What a tenant **owner types** into their own template is different: it is a
   **consented disclosure by the owner**, of the owner's *own* tenant, to a recipient the owner is inviting to
   that tenant — and it is operator- and provider-visible like the rest of the mail. veyq therefore does **not**
   guarantee the body is content-free where the owner authored it; it guarantees only that *its own machinery*
   adds nothing sealed. The shipped default template is content-free — it says only that *someone* invited you to
   *this domain*.
3. **The SMTP provider is fenced like ADR 0016:** it receives only the recipient address + the message
   (link / domain / expiry) — never a veyq session, user-id, or content. In a **self-hosted** deployment the
   operator runs their own SMTP, so this boundary collapses to the operator themselves (moot, as with the
   self-hosted operator-blind case).
4. **Send-time recipient + the time of sending is conceded operational metadata, like membership** — stated in the
   security-model inventory (which also now lists the account email and the owner-authored template text, a
   server-held operator-visible field), never treated as content.

## Consequences

- **Honest model.** The send-time recipient/timing concession and the provider boundary are named, not hidden;
  the security-model metadata inventory gains the account email + the mail send-fact + the owner-authored
  template text (server-held, operator-visible).
- **A hard rule on the renderer, now and later.** Invite mail today; verification and (a future topic) recovery
  mail tomorrow — every mail is rendered over the same **closed, non-secret variable set**; no sealed field is
  ever reachable by interpolation. This is a **promoted guard**: a check that the renderer's variable set is
  closed to `{{link}}` / `{{domain}}` / `{{expiry}}` and that no sealed field (scope / member / real-name /
  content) is wired into a mail render context. The guard binds *veyq's machinery*, not the owner's literal
  prose — which is a consented disclosure (Decision §2), out of the guard's reach by decision, not by oversight.
- **Self-hosted vs SaaS.** Self-hosted: the provider boundary collapses to the operator. SaaS: the operator's
  chosen relay is a documented third party, feeding the SaaS-ops (#38) and legal (#40) topics.
- **The SMTP credential is now a server-held secret.** Per-tenant SMTP means the operator holds each tenant's
  SMTP password (encrypted at rest, [ADR 0050](0050-operator-config-secret-encryption.md)) — inherent to sending
  on the tenant's behalf; self-hosted, moot.
- **Rejected — veyq *auto-injecting* richer content** ("You've been invited to the *Smith Family* album by
  *Alice*", assembled by the server from sealed fields). The server never reads scope / member / relationship
  metadata into a mail — that would leak, to the operator's logs *and* the provider, what veyq otherwise seals,
  with nobody consenting. This is the line that stays hard: an owner who *types* their tenant's name into their
  own template discloses it themselves (Decision §2); veyq assembling it from sealed data does not, and remains
  rejected. The mechanism enforcing it is the closed variable set — the renderer is never handed a sealed field.
- **Rejected — proxying mail through the veyq backend to "hide" the provider.** The backend already holds the
  address, and the provider is downstream regardless; proxying buys nothing. The fence is *minimal content + no
  veyq identity to the provider*, not proxying.
- **Rejected — sealing/hashing the address to shrink at-rest exposure as part of this ADR.** Out of scope: the
  address must be plaintext at send time and for login (ADR 0003), and at-rest hardening belongs to the
  multi-ServerOwner / server-scope-key work (inbox #43). This ADR only *documents* the existing plaintext field.
