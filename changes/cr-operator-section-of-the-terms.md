---
id: cr-operator-section-of-the-terms
title: A fixed operator section beside every space's terms, filled with the instance's values
status: planned
satisfies: ["uc.admin.tenant-terms", "uc.account.accept-terms", "uc.sharing.share-accept"]
adrs: ["0035", "0093", "0099", "0092", "0087"]
invariants:
  - the operator section is fixed text shipped with a release; nobody edits it (ADR 0035 decision 6, Rejected: an operator-editable section)
  - the terms stay public operational content, never E2EE content
  - a space's own terms stay the space owner's (admin-web gains no terms editor)
---

## Goal

Build [ADR 0035](../docs/decisions/0035-server-terms-of-service.md) decision 6: a **fixed operator section**, DE and
EN, shipped by veyq and filled with values the Server Owner sets (the instance contact and its languages, the provider
identification, the supervisory authority — `cr-instance-contact-and-provider-identification`; the guest-write record's
N and, where it applies, the backup period B — `cr-guest-write-record`). It states the notice path, what is kept, the
measures, the statement and its deferral, the objection, the authorities, and the guest-write record's GDPR Art. 13
information with the right to object (Art. 21) as a notice of its own. It is served **after** the space's own text by
the endpoints that serve the terms, shown at the member gate, the share gate and (once it exists) the login page; its
revision moves only with its text, a bump reopens the gate, a changed value renders without reopening it; a space with
no terms (revision 0) shows the section without a gate. The template's clause 9 then points here, and clauses 2 and 7
lose what they said about the host's duties. The section comes in **two variants** chosen by the operating mode
(ADR 0093 § 14, ADR 0035 decision 6): **full** for *club, organisation or company*, **reduced** for *private*. This
is **last** in the moderation order because the section describes
what the other CRs build: written earlier, it would promise a report path that does not exist.

## Context & constraints

- Entry conditions: `cr-instance-contact-and-provider-identification`, `cr-guest-write-record` **done** (the values,
  and the operating mode that picks the variant);
  `cr-report-and-hold`, `cr-moderation-review-and-measures`, `cr-statement-of-reasons-and-objection`,
  `cr-authority-orders-and-art18-reports` **done** (what the text describes) — or the owner decides that the section
  ships describing only what is built, and the later CRs each extend it (then this CR's entry is the first two only).
- Decisions: ADR 0035 decisions 3 and 6 (and its Consequences; decision 6 defines both variants), ADR 0093 § 14 (the
  mode, read through `cr-instance-contact-and-provider-identification`'s `operating_mode`); [ADR 0093](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 10's disclosure list, § Consequences ("Docs" bullet: the clauses 2, 7 and 9, ADR 0035 decisions 3, 6 and 7);
  [ADR 0087](../docs/decisions/0087-published-docs-are-a-starlight-site-docs-is-for-development.md) (the site pages).
- **ADR 0092 is not built here.** ADR 0093 § Consequences and ADR 0092 § Inventory both say the docs both ADRs change
  land in "one CR shared with 0092"; ADR 0092 is still proposed and its CR comes after the moderation work. This CR
  takes ADR 0093's share (clause 9, clause 7's guest uploads, clause 2's sentence on what the host keeps about a guest
  write) and leaves clause 2's log sentence to the logging CR — phase 0 edits that one sentence in both ADRs so they
  stop promising a joint CR (Q15).

### Measured substrate (worktree at `61aa7756`, clean)

| Fact | Locator |
|---|---|
| The terms endpoints (member read, edit, accept; the guest read keyed by share) | `grep -rn 'operationId: .*[Tt]erms' contracts/api` |
| The member gate and the share gate (inline in `ShareView`) | `grep -n 'needsTerms\|loadTerms' web/src/views/ShareView.vue`; `grep -n "path: '/terms'" web/src/router.ts` |
| The template's clauses 2, 7, 9 (DE and EN) | `grep -n '^## ' web/src/features/tenant/termsTemplate.ts` |
| The guest's acceptance is client-side, keyed per tenant + revision (an operator-section revision joins the key) | `grep -rn 'termsRevision' web/src/views/ShareView.vue web/src/core/sharing \| head` |
| The pages that speak for the terms | `git grep -ln 'uc.admin.tenant-terms\|uc.account.accept-terms\|uc.sharing.share-accept' -- site/src/content/docs` |

## Decisions this CR takes

1. **The section's text lives in `contracts/`** (one file, DE and EN, with named placeholders and its own revision),
   read by the backend to serve it and by nothing else — clients render what the endpoints serve. The drafts of the
   2026-10-16 research are the starting text (ADR 0035 decision 6: "written in the CR that builds this").
2. **The gate compares a triple** (space revision, operator revision, variant): any of them moving reopens it; the
   member's accepted history keeps one row per triple; the guest's local key becomes tenant + the triple.
3. **A missing value renders as a visible gap** ("not stated by this operator") in the full variant, never as an
   empty sentence; admin-web already warns for the contact and the identification there. In the reduced variant an
   empty value is simply absent (ADR 0035 decision 6), and nothing warns.
4. **Two variants, one file, one revision.** The text file marks each passage `both`, `full` or `record` (the
   guest-write record's Art. 13 information, rendered exactly while `core.public-shares.guest-write-record` is
   effectively on, in either variant). The **reduced** variant is the full one without the `full` passages, which
   are exactly what ADR 0035 decision 6 lists under *drops* (`grep -n 'drops:' docs/decisions/0035-*.md`). The
   variant is part of what the gate compares (decision 2), so a mode change reopens the gate and a record switch
   does not.

## Open questions (owner) and legal items

**Decided (Q14–Q15).** Owner 2026-10-17 ("Ja so übernehmen"): every recommendation in this section is taken, and implementation has the owner's go in the order of inbox #462. The legal blockers named here stay open; they are not owner decisions. The variant per operating mode is the owner's of the same day ("Ja", ADR 0093 § 14).

- **Q14 — Ship the section complete, last, or growing with each CR?** **Recommendation: last and complete** (this CR's
  order): a section that grows with each CR changes the gate's revision up to five times before the first release for
  no reader, since nothing is released (`grep -n '^release:' scripts/applied_migrations.sha256` → `none`).
- **Q15 — The joint docs CR with ADR 0092.** **Recommendation:** edit the "one CR shared with 0092" sentences of ADR 0093
  § Consequences and ADR 0092 § Inventory to "each ADR's CR carries its own share", as phase 0 does here.
- **Legal:** every text of the section is a draft until a qualified review (ADR 0093 § Legal basis: "Each answer needs a
  qualified review before anything ships"); the guest-write record's text also waits on Still open 4 (the port).

## Phases

- [ ] **0 docs** — Q15's two sentences; the three use-cases gain the operator section in their flows (the gate's triple, the two variants,
  the section without a gate at revision 0); coverage criteria added as planned gaps. · Done when: feature-format,
  coverage, doc-link, doc-citation, change guards green.
- [ ] **1 contracts** — the section's text file; the terms responses gain the rendered section and its revision; the
  accept request names both revisions. · Done when: `npm run generate:api`; the contracts guards; `npm run check` in
  `web/` once to see the typed lines a reshaped accept request reddens (name them).
- [ ] **2 backend** — render the mode's variant with the current values; the gate on the triple; the history row per triple. · Done when:
  `./gradlew check` green. **Bites, with controls:** a value changed in admin-web → the served text changes and an
  accepted member is not gated (control: an operator-revision bump gates them); a space at revision 0 → the section
  served, no gate; *private* → the reduced variant: no authorities passage, no deferral passage, the notice path
  present (control: *organisation* → all three present); the mode changed → an accepted member is gated again
  (control: switching the guest-write record is not); the record's passage present exactly while its switch is on, in
  both variants. Bite: render the record passage by mode instead of by switch → the private-with-record-on test is
  red.
- [ ] **3 web** — both gates render the section after the space's text, the right to object set apart; the guest's local
  key on the triple; the template's clause 9 points to the section, clauses 2 and 7 trimmed (EN and DE). · Done when:
  `npm run check` green; a browser pass of the member gate and the share gate in DE and EN, in both modes.
- [ ] **4 docs** — ADR 0035's status line (decisions 6 and 7 built); `site/src/content/docs/space-owners/terms.mdx`
  and its DE twin (the operator section exists, the space owner cannot remove it), `sharing/what-guests-see.mdx` and
  its DE twin, `run/moderation.md` (what the section says in each mode and which values feed it); coverage refs. · Done when:
  coverage, wiki-sources and the site check green.

## Definition of done

Every phase's Done-when; coverage for the three use-cases with `backend` and `web` refs for the new criteria; every
`scripts/validate_*.py` except `validate_guard_gaps.py` green.

Implementation has the owner's go (2026-10-17), in the order of inbox #462; a legal blocker named above still blocks the part it names.

## Retrospective

_Filled at implementation._
