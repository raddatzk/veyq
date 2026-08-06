# UC: Tenant terms — the operator sets their server's legal terms (versioned)

- **ID:** uc.admin.tenant-terms
- **Primary actor:** The **tenant owner** (the operator of this server), signed in — a session, no unlocked vault
  needed (terms are operational, not content).
- **Goal:** Set and edit the tenant's **legal terms** (acceptable use, prohibited/illegal content, review &
  removal, contact, an Impressum) as **per-locale markdown** (DE+EN), assembled from a **clause template** or
  edited freely; a change **bumps the version**, which re-gates members ([uc.account.accept-terms](../../account/use-cases/uc.account.accept-terms.md)).
- **Preconditions:** A session as the **tenant owner** (`isOwner`); the tenant is ACTIVE.
- **Postconditions (success):** the tenant's **`terms_markdown_by_locale`** + **`terms_version`** are updated;
  a *normalized* change bumps `terms_version` (idempotent edits don't); the higher version re-gates every member.

## What the terms are here

The operator's **public policy** — served to everyone (including **pre-auth on the login page**), **not** E2EE
content. Each tenant carries **`terms_markdown_by_locale`** (a locale→markdown map, DE+EN) + an int
**`terms_version`**. The owner assembles them from a **clause template** — the pre-E2EE 11-clause set (purpose,
privacy, rights-to-content, **children**, **illegal content** incl. CSAM, share-links, guest-uploads,
availability, **review & removal**, external services, **contact & deletion** with a `{contact}` placeholder) —
or edits the markdown freely. This is where the content-blind operator states policy + the honest-operator caveat
+ the public-share **decryptable-on-report** ([security-model](../../../crosscutting/security-model.md),
[ADR 0035](../../../decisions/0035-server-terms-of-service.md)). Self-hosted operators own their terms; the central
infra ships a default.

## Main flow

1. The owner opens the **terms editor** (admin/settings), owner-gated.
2. They pick/toggle clauses from the template (or edit the markdown), **per locale** (DE + EN), and fill the
   `{contact}` placeholder.
3. On **save** the server **normalizes** the markdown and, **only if it changed**, stores it and **bumps
   `terms_version`** (an idempotent re-save doesn't bump — matching the pre-E2EE logic).
4. A bump means every member's `accepted_terms_version` is now stale → each is re-gated on next entry
   ([uc.account.accept-terms](../../account/use-cases/uc.account.accept-terms.md)).

## Alternate / exception flows

- **A non-owner tries to edit** → refused (owner-gated, `isOwner`); a member can only **read** the terms.
- **No terms set yet** (a fresh tenant) → the editor starts from the clause template; until the owner publishes,
  the tenant's terms are empty/`version 0` (the member gate treats "no terms" as nothing to accept — decided in
  the CR).
- **Only one locale filled** → the other locale falls back to the filled one at display time (no empty ToS shown).

## What this means for you / Was das für dich bedeutet

**EN —** If you run a veyq server, this is where you write the rules for your space — what's allowed, what isn't
(illegal content, others' privacy), and how to reach you. You start from ready-made building blocks and edit them
in German and English. When you change the rules, everyone is asked to agree again. Your server, your terms — veyq
still can't read anyone's private content.

**DE —** Wenn du einen veyq-Server betreibst, legst du hier die Regeln für deinen Bereich fest — was erlaubt ist,
was nicht (illegale Inhalte, die Privatsphäre anderer) und wie man dich erreicht. Du startest mit fertigen
Bausteinen und passt sie auf Deutsch und Englisch an. Änderst du die Regeln, wird allen erneut die Zustimmung
abverlangt. Dein Server, deine Regeln — veyq kann die privaten Inhalte trotzdem nicht lesen.

## Related

- **Feature:** [admin](../_index.md)
- **Use-cases:** [uc.account.accept-terms](../../account/use-cases/uc.account.accept-terms.md) (the member gate that
  consumes the version), [uc.admin.create-tenant](uc.admin.create-tenant.md) (the tenant + owner this edits).
- **Cross-cutting:** [security-model](../../../crosscutting/security-model.md) (terms = public operational content;
  the T&S decryptable-on-report corollary), [cc.inclusive-language](../../../crosscutting/cc.inclusive-language.md)
  (the DE terms voice).
- **Decisions:** [ADR 0035](../../../decisions/0035-server-terms-of-service.md) (the terms + acceptance model),
  [ADR 0007](../../../decisions/0007-membership-role-grant-relation.md) (the owner/membership relation).
