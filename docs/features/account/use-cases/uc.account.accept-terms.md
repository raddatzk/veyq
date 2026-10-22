# UC: Accept terms — a member accepts the tenant's current terms to enter (re-accept on change)

- **ID:** uc.account.accept-terms
- **Primary actor:** A **member** with a session, entering a tenant whose **current `terms_revision`** they have
  not yet accepted (first entry, or after the space owner bumped the revision).
- **Goal:** Read and **accept** the tenant's current terms to enter; if the space owner later changes them (a higher
  `terms_revision`), a **re-accept** gate re-appears.
- **Preconditions:** An authenticated **session** — acceptance is **operational metadata**, so it needs a session,
  **not** an unlocked vault (parallels the operational Server-Owner settings). The tenant has terms set
  (`terms_revision > 0`).
- **Postconditions (success):** the member's history holds a row for the current pair — the tenant's
  `terms_revision` and the operator section's revision (the row joins their earlier ones); the gate clears and the shell renders. A later revision bump makes it stale → the gate re-appears.

## The gate here

**What is accepted is a pair**: the space's `terms_revision` and the operator section's revision —
[ADR 0035](../../../decisions/0035-server-terms-of-service.md) decision 6. The section has one form on every instance
(ADR 0093 § 14). The space's text comes first, the operator section after it, the guest-write record's pointer set
apart. Either part moving reopens the gate: the space owner's edit, a veyq release that changed the section's text. A
value changed in admin-web (a contact, the provider identification, N) renders at once and reopens nothing. A space with **no terms** (revision 0) never gates; its members read the operator section in the space's
terms panel.

A **hard gate**. When a member's history holds **no row for the current pair**, the web **router guard** redirects
them to a `/terms` accept route — **after** the session/unlock check
(it needs a session, not an unlocked vault). The route shows the tenant's terms (the current locale, DE/EN
fallback) and an **Accept** action naming the pair it showed; accepting records **`(user_id, tenant_id, accepted_revision,
operator_revision, accepted_at)`** server-side (operational metadata, [ADR 0035](../../../decisions/0035-server-terms-of-service.md) §4) — one row per
accepted pair, so the earlier acceptances stay beside it. `accepted_at` is the server's own time of the
acceptance — audit evidence of when which revision was accepted, also for a dispute about a superseded revision; read
by operators from the database and served by no API; re-accepting a recorded revision keeps its first time. The
gate asks whether a row for the current pair exists. No entry until accepted. **Anonymous public-share visitors are out of scope** — they have no account, so their acceptance is
**client-side** on the share page: localStorage keeps the last two pairs the device accepted and asks the same
question of them
([uc.sharing.share-accept](../../sharing/use-cases/uc.sharing.share-accept.md)).

## Main flow

1. A member with a session navigates into the tenant shell.
2. The router guard asks whether they accepted the current pair; **not yet** → redirect to `/terms`.
3. They read the terms and the operator section after them (current locale) and press **Accept**.
4. The server adds a row for the pair (+ `accepted_at`, its own clock); the guard now passes → the shell renders
   (honouring the remembered post-unlock route).

## Alternate / exception flows

- **The operator bumps the revision** while the member is signed in → on the next guarded navigation the gate
  re-appears (re-accept); already-open tabs re-gate on their next route change.
- **A tenant with no terms** (`terms_revision = 0`) → nothing to accept; the gate never triggers.
- **A release changes the section's text** → the pair moved, the gate re-appears. A changed value or N does not.
- **The pair moved while the gate was open** → the accept is refused (`409 terms_revision_changed`), the view
  re-reads and re-presents.
- **Decline** → no acceptance recorded; the member stays out of the shell (they can sign out). No content is
  exposed by the gate itself.

## User impact

You see and agree to the rules a shared space's owner set before you enter, and again whenever they change, so you
always know what you have agreed to.

## Related

- **Feature:** [account](../_index.md)
- **Use-cases:** [uc.admin.tenant-terms](../../admin/use-cases/uc.admin.tenant-terms.md) (the space owner sets
  the revision this gate checks), [uc.account.unlock](uc.account.unlock.md) (the layer below — the gate sits on top of a
  session, above unlock), [uc.account.login](uc.account.login.md).
- **Cross-cutting:** [security-model](../../../crosscutting/security-model.md) (`accepted_terms_revision` = coarse
  operational metadata; the terms text is public).
- **Decisions:** [ADR 0035](../../../decisions/0035-server-terms-of-service.md) (the acceptance model + the
  member-server-side / anonymous-client-side split).
- End-user view (docs site): [Terms of use](../../../../site/src/content/docs/space-owners/terms.mdx)
