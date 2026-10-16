# UC: Accept terms — a member accepts the tenant's current terms to enter (re-accept on change)

- **ID:** uc.account.accept-terms
- **Primary actor:** A **member** with a session, entering a tenant whose **current `terms_revision`** they have
  not yet accepted (first entry, or after the operator bumped the revision).
- **Goal:** Read and **accept** the tenant's current terms to enter; if the operator later changes them (a higher
  `terms_revision`), a **re-accept** gate re-appears.
- **Preconditions:** An authenticated **session** — acceptance is **operational metadata**, so it needs a session,
  **not** an unlocked vault (parallels the operational Server-Owner settings). The tenant has terms set
  (`terms_revision > 0`).
- **Postconditions (success):** the member's **`accepted_terms_revision`** = the tenant's current `terms_revision`
  (a row for that revision joins their earlier ones); the gate clears and the shell renders. A later revision bump makes it stale → the gate re-appears.

## The gate here

A **hard gate**. When a member's `accepted_terms_revision` is **below** the tenant's current `terms_revision` (or
absent), the web **router guard** redirects them to a `/terms` accept route — **after** the session/unlock check
(it needs a session, not an unlocked vault). The route shows the tenant's terms (the current locale, DE/EN
fallback) and an **Accept** action; accepting records **`(user_id, tenant_id, accepted_revision, accepted_at)`**
server-side (operational metadata, [ADR 0035](../../../decisions/0035-server-terms-of-service.md) §4) — one row per
accepted revision, so the earlier acceptances stay beside it. `accepted_at` is the server's own time of the
acceptance — audit evidence of when which revision was accepted, also for a dispute about a superseded revision; read
by operators from the database and served by no API; re-accepting a recorded revision keeps its first time. The
gate reads the highest revision accepted. No entry until accepted. **Anonymous public-share visitors are out of scope** — they have no account, so their acceptance is
**client-side** (localStorage) on the share page, a later slice ([sharing](../../sharing/_index.md)).

## Main flow

1. A member with a session navigates into the tenant shell.
2. The router guard compares their `accepted_terms_revision` to the tenant's `terms_revision`; **stale/absent** →
   redirect to `/terms`.
3. They read the terms (current locale) and press **Accept**.
4. The server adds a row for `accepted_terms_revision = terms_revision` (+ `accepted_at`, its own clock); the guard now passes → the shell
   renders (honouring the remembered post-unlock route).

## Alternate / exception flows

- **The operator bumps the revision** while the member is signed in → on the next guarded navigation the gate
  re-appears (re-accept); already-open tabs re-gate on their next route change.
- **A tenant with no terms** (`terms_revision = 0`) → nothing to accept; the gate never triggers.
- **Decline** → no acceptance recorded; the member stays out of the shell (they can sign out). No content is
  exposed by the gate itself.

## What this means for you / Was das für dich bedeutet

**EN —** Before you enter a shared space, you see the rules its operator set and agree to them once. If the
operator later changes the rules, you're asked to agree again — so you always know what you've signed up to.
Agreeing is just a small note the server keeps ("accepted revision 3") — never anything about your photos.

**DE —** Bevor du einen geteilten Bereich betrittst, siehst du die Regeln, die dessen Betreiber gesetzt hat, und
stimmst ihnen einmal zu. Ändert der Betreiber die Regeln später, wirst du erneut um Zustimmung gebeten — so weißt
du immer, worauf du dich eingelassen hast. Die Zustimmung ist nur eine kleine Notiz beim Server („Fassung 3
akzeptiert") — nie etwas über deine Fotos.

## Related

- **Feature:** [account](../_index.md)
- **Use-cases:** [uc.admin.tenant-terms](../../admin/use-cases/uc.admin.tenant-terms.md) (the operator sets the
  revision this gate checks), [uc.account.unlock](uc.account.unlock.md) (the layer below — the gate sits on top of a
  session, above unlock), [uc.account.login](uc.account.login.md).
- **Cross-cutting:** [security-model](../../../crosscutting/security-model.md) (`accepted_terms_revision` = coarse
  operational metadata; the terms text is public).
- **Decisions:** [ADR 0035](../../../decisions/0035-server-terms-of-service.md) (the acceptance model + the
  member-server-side / anonymous-client-side split).
- End-user view (docs site): [Terms of use](../../../../site/src/content/docs/space-owners/terms.mdx)
