# ADR 0049 — Sending mail is a send-time metadata + third-party boundary; a mail carries only delivery essentials

- Status: Accepted
- Built: yes
- Date: 2026-07-24
- Model: [cc.mail](../crosscutting/cc.mail.md)

## Context

veyq is operator-blind: the server stores only ciphertext and opaque operational metadata
([security-model](../crosscutting/security-model.md)). A mail-send path
([ADR 0048](0048-provider-neutral-smtp-mail-transport.md)) opens two exposures the model must state rather than
pretend mail is free:

1. **The veyq operator, at send time.** The address is already stored in plaintext — it is the login identifier and
   the input of the auth salt ([ADR 0003](0003-operator-blind-password-auth.md)). Sending adds an access pattern: the
   operator learns *who is contacted, when*.
2. **A mail provider — a different, consented boundary.** The relay learns recipient and timing too. This is the shape
   already drawn for reverse-geocoding ([ADR 0016](0016-geocoding-provider-neutrality.md)): operator-blind names the
   veyq operator; a chosen external service is a separate, consented boundary, fenced so it never widens the
   operator's view.

## Decision

**veyq's machinery puts nothing sealed into a mail, and the mail provider is a fenced, consented third party.**

1. **A mail carries what its delivery needs.** A link's secret rides the URL **fragment**, never a header or a path. A
   tenant's mail comes from the tenant's own address ([ADR 0048](0048-provider-neutral-smtp-mail-transport.md)), so the
   recipient sees the domain they are invited to and no cross-tenant identity leaks.
2. **No sealed field is reachable by a render; words an author types are that author's consented disclosure.** veyq
   never interpolates a scope, group or node name, a member list, a real name or any content — those are sealed and the
   renderer is never handed them, so a mail never becomes a side channel for what the database seals. A tenant's mail
   is rendered over the closed variable set of [ADR 0048](0048-provider-neutral-smtp-mail-transport.md) § 6; the
   instance's notices ([ADR 0097](0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md)
   decision 3) are veyq-fixed texts filled only with values the server already holds in the clear. What an author
   **types** is different: a tenant owner's invite template, and a Server Owner's facts on their own moderation step
   ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 7), are disclosed
   by their author, to the recipient the author addresses, and are operator- and provider-visible like the rest of the
   mail. veyq guarantees only that its own machinery adds nothing sealed; the shipped defaults are content-free.
3. **The provider is fenced like ADR 0016:** it receives the recipient and the message, never a veyq session, user id
   or content. Self-hosted, the operator runs the relay and the boundary collapses to them.
4. **The recipient and the time of sending are conceded operational metadata**, like membership — listed in the
   security model's inventory with the account address and the owner's template text, never treated as content.

## Consequences

- **The model is honest.** The send-time concession and the provider boundary are named, not hidden.
- **A hard rule on every renderer, now and later:** no sealed field is wired into a mail's render context, and a test
  pins the tenant render context to its closed set; the rule binds veyq's machinery, not an author's literal words,
  which are out of its reach by decision.
- **Self-hosted vs SaaS.** Self-hosted, the provider boundary is the operator's own. SaaS, the operator's relay is a
  documented third party.
- **Rejected — veyq assembling richer content from sealed data** ("invited to *the Smith Family* by *Alice*"). It would
  leak to the operator's logs and the provider what veyq otherwise seals, with nobody consenting. An author who types
  such a name discloses it themselves; veyq assembling it does not.
- **Rejected — proxying mail through the backend to hide the provider.** The backend already holds the address and the
  provider is downstream regardless; the fence is minimal content and no veyq identity, not proxying.
- **Rejected — sealing or hashing the stored address here.** The address must be plaintext at send time and for login
  (ADR 0003); at-rest hardening belongs to the multi-Server-Owner work (inbox #43).
