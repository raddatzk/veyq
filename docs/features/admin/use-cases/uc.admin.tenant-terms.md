# UC: Tenant terms — the space owner sets the space's legal terms (a new revision on each change)

- **ID:** uc.admin.tenant-terms
- **Primary actor:** The **tenant owner** (the space owner — not the Server Owner who runs the server), signed in —
  a session, no unlocked vault needed (terms are operational, not content).
- **Goal:** Set and edit the tenant's **legal terms** (acceptable use, prohibited/illegal content, review &
  removal, contact, an Impressum) as **per-locale markdown**, written per language — **the languages the terms
  carry are the languages the owner added**, and a starter template can fill any one of them on request; a
  change **bumps the revision**, which re-gates members ([uc.account.accept-terms](../../account/use-cases/uc.account.accept-terms.md)).
- **Preconditions:** A session as the **tenant owner** (`isOwner`); the tenant is ACTIVE.
- **Postconditions (success):** the tenant's **`terms_markdown_by_locale`** + **`terms_revision`** are updated;
  a *normalized* change bumps `terms_revision` (idempotent edits don't); the higher revision re-gates every member,
  and every guest who opens one of the space's shares (a guest's acceptance is client-only and per space,
  `recordTermsAccepted` in `web/src/core/sharing/resolvePublicShare.ts`).

## What the terms are here

The space's **public policy** — served to everyone (including **pre-auth on the login page**), **not** E2EE
content. Each tenant carries **`terms_markdown_by_locale`** (a locale→markdown map, DE+EN) + an int
**`terms_revision`**. The owner **writes the markdown**, per language. A **starter template** is available for
each language — the pre-E2EE 11-clause set (purpose, privacy, rights-to-content, **children**, **illegal
content** incl. CSAM, share-links, guest-uploads, availability, **review & removal**, external services,
**contact & deletion** with a `{contact}` placeholder) — inserted **whole**, on request, into the one language
it was asked for: it is a draft to edit, not a structure the terms are assembled out of. Clauses are **not**
individually toggled; the clause numbers are literal in the template text, so any subset would print a gap in
the numbering. Absent a template insert, a language is free markdown from the first character.

## The operator section beside them

Every space's terms are followed by a fixed **operator section** ([ADR 0035](../../../decisions/0035-server-terms-of-service.md)
decision 6): veyq's own text, DE and EN, filled with the values the Server Owner sets in admin-web and the CLI
([uc.admin.operator-details](uc.admin.operator-details.md)) — the notice path, what is kept, the measures, the statement
and the objection, and, in the *club, organisation or company* mode, the authorities; the guest-write record's
information while that record is on. It is served by the same endpoints, after the space's text, in the variant the
operating mode picks (**full** or **reduced**). The space owner cannot edit or remove it; the terms panel shows it,
read-only, below their own text, and so does a member's. Its revision moves only with a veyq release, so the space's
`terms_revision` stays the owner's alone; what the members' gate compares is
[uc.account.accept-terms](../../account/use-cases/uc.account.accept-terms.md)'s.

This is where the space owner states policy + the honest-operator caveat
+ the public-share **decryptable-on-report** ([security-model](../../../crosscutting/security-model.md),
[ADR 0035](../../../decisions/0035-server-terms-of-service.md)). Every space writes its own terms, on any server: no
server ships default terms (a new tenant has none, revision 0), only the starter template the owner may insert.

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

## User impact

The owner of a space sets its own rules of use in their own words and languages, and everyone agrees to them — again
after every change — before they go on.

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
