# UC: Tenant terms — the operator sets their server's legal terms (a new revision on each change)

- **ID:** uc.admin.tenant-terms
- **Primary actor:** The **tenant owner** (the operator of this server), signed in — a session, no unlocked vault
  needed (terms are operational, not content).
- **Goal:** Set and edit the tenant's **legal terms** (acceptable use, prohibited/illegal content, review &
  removal, contact, an Impressum) as **per-locale markdown**, written per language — **the languages the terms
  carry are the languages the owner added**, and a starter template can fill any one of them on request; a
  change **bumps the revision**, which re-gates members ([uc.account.accept-terms](../../account/use-cases/uc.account.accept-terms.md)).
- **Preconditions:** A session as the **tenant owner** (`isOwner`); the tenant is ACTIVE.
- **Postconditions (success):** the tenant's **`terms_markdown_by_locale`** + **`terms_revision`** are updated;
  a *normalized* change bumps `terms_revision` (idempotent edits don't); the higher revision re-gates every member.

## What the terms are here

The operator's **public policy** — served to everyone (including **pre-auth on the login page**), **not** E2EE
content. Each tenant carries **`terms_markdown_by_locale`** (a locale→markdown map, DE+EN) + an int
**`terms_revision`**. The owner **writes the markdown**, per language. A **starter template** is available for
each language — the pre-E2EE 11-clause set (purpose, privacy, rights-to-content, **children**, **illegal
content** incl. CSAM, share-links, guest-uploads, availability, **review & removal**, external services,
**contact & deletion** with a `{contact}` placeholder) — inserted **whole**, on request, into the one language
it was asked for: it is a draft to edit, not a structure the terms are assembled out of. Clauses are **not**
individually toggled; the clause numbers are literal in the template text, so any subset would print a gap in
the numbering. Absent a template insert, a language is free markdown from the first character.

This is where the content-blind operator states policy + the honest-operator caveat
+ the public-share **decryptable-on-report** ([security-model](../../../crosscutting/security-model.md),
[ADR 0035](../../../decisions/0035-server-terms-of-service.md)). Self-hosted operators own their terms; the central
infra ships a default.

## Main flow

1. The owner opens the **terms editor** (admin/settings), owner-gated.
2. The editor lists **only the languages that already carry terms** — one row each, opening a markdown editor.
   A language with none is **added by an explicit act naming it**, never rendered as an empty box. Within a
   language the owner writes markdown freely, may ask for the **starter template** (which asks first, and names
   the replacement when there is already text to lose), and fills the `{contact}` placeholder.
3. On **save** the server **normalizes** the markdown and, **only if it changed**, stores it and **bumps
   `terms_revision`** (an idempotent re-save doesn't bump — matching the pre-E2EE logic).
4. A bump means every member's `accepted_terms_revision` is now stale → each is re-gated on next entry
   ([uc.account.accept-terms](../../account/use-cases/uc.account.accept-terms.md)).

## Alternate / exception flows

- **A non-owner tries to edit** → refused (owner-gated, `isOwner`); a member can only **read** the terms.
- **No terms set yet** (a fresh tenant) → the editor lists **no languages at all** and offers to add one; it
  does not start from the template, and nothing is written until the owner asks for it. Until the owner
  publishes, the tenant's terms are empty/`revision 0` (the member gate treats "no terms" as nothing to accept —
  decided in the CR).
- **A language added but left empty** → the server drops a blank locale on normalize, so it does not survive the
  save. The editor says so while the language sits there empty, rather than letting the row disappear
  unexplained on the next load.
- **A language removed** → the whole map is rewritten, so the removal both deletes that language's terms and
  **bumps the revision, re-gating every member**. The editor asks first and names both consequences.
- **Only one locale filled** → the other locale falls back to the filled one at display time (no empty ToS shown).

## What this means for you / Was das für dich bedeutet

**EN —** If you run a veyq server, this is where you write the rules for your space — what's allowed, what isn't
(illegal content, others' privacy), and how to reach you. You add a language, write the rules in it, and add a
second language if you want one; the page shows you the languages you have written, not the ones you haven't. If
you would rather not start from a blank page, ask for the ready-made text and it fills that one language — it
asks first, and tells you if there is something it would replace. When you change the rules, everyone is asked to
agree again. Your server, your terms — veyq still can't read anyone's private content.

**DE —** Wenn du einen veyq-Server betreibst, legst du hier die Regeln für deinen Bereich fest — was erlaubt ist,
was nicht (illegale Inhalte, die Privatsphäre anderer) und wie man dich erreicht. Du fügst eine Sprache hinzu,
schreibst die Regeln darin und fügst eine zweite hinzu, wenn du möchtest; die Seite zeigt dir die Sprachen, die
du geschrieben hast — nicht die, die du nicht geschrieben hast. Wenn du nicht mit einer leeren Seite anfangen
möchtest, kannst du den fertigen Text anfordern: Er füllt genau diese eine Sprache, fragt vorher nach und sagt
dir, wenn er etwas ersetzen würde. Änderst du die Regeln, wird allen erneut die Zustimmung abverlangt. Dein
Server, deine Regeln — veyq kann die privaten Inhalte trotzdem nicht lesen.

## Related

- **Feature:** [admin](../_index.md)
- **Use-cases:** [uc.account.accept-terms](../../account/use-cases/uc.account.accept-terms.md) (the member gate that
  consumes the revision), [uc.admin.create-tenant](uc.admin.create-tenant.md) (the tenant + owner this edits).
- **Cross-cutting:** [security-model](../../../crosscutting/security-model.md) (terms = public operational content;
  the T&S decryptable-on-report corollary), [cc.inclusive-language](../../../crosscutting/cc.inclusive-language.md)
  (the DE terms voice).
- **Decisions:** [ADR 0035](../../../decisions/0035-server-terms-of-service.md) (the terms + acceptance model),
  [ADR 0007](../../../decisions/0007-membership-role-grant-relation.md) (the owner/membership relation).
- End-user view (docs site): [Terms of use](../../../../site/src/content/docs/space-owners/terms.mdx)
