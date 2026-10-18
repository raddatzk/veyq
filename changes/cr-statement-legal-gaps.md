---
id: cr-statement-legal-gaps
title: The statement's legal gaps — the reporter's redress, the Server Owner's own steps, the provision relied on, and a guest's notice of a removal order
status: planned
satisfies: ["uc.moderation.object-to-a-measure", "uc.moderation.take-a-measure", "uc.moderation.authority-order"]
adrs: ["0093", "0099", "0066", "0101"]
invariants:
  - operator-blind - a member's facts stay sealed to the moderation key and the member's identity key; only the Server Owner's own words reach a mail in the clear, and only for the steps where the recipient has no other channel
  - the template stays one file in contracts/, rendered by the backend for mail and by web/ in the app, pinned by the conformance tests on both sides
  - a guest learns of a removal only on its own uploads, only through the contributor token it already holds, and never while an authority's deferral runs
---

## Goal

Close four gaps the legal audit of 2026-10-18 found in the statement of reasons, as
[ADR 0093](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
§§ 7 and 12 now decide them: **(a)** the reporter's decision mail gains a fixed bilingual redress passage (DSA
Art. 16(5)); **(b)** the Server Owner's own steps — a member's sign-in block, a tenant taken offline (and, once
`cr-account-erasure` builds it, a tenant deleted) — carry a statement in the same template when they are taken over
content (Art. 17(1)(c), (d)), by mail with the facts, the objection going to the instance contact; **(c)** the ground
names the provision of the law or the clause of the terms relied on, required in the full form, and the statement says
where and how long the measure applies (Art. 17(3)(a), (d), (e)); **(d)** a guest whose upload an authority's removal
order blocked is told so on the link it came through — date, and where to ask for the reasons or a copy — unless an
authority's deferral runs (TCO Art. 11). **(d) overrides an earlier owner answer** ("a guest is told nothing, as the ADR
says") for an authority's removal order only, by the owner rule that a legal duty wins over what was decided; for a
moderator's measure the DSA Art. 17(2) reasoning stands and a guest is still told nothing.

## Context & constraints

- Decisions: ADR 0093 § 7 (the provision, the territorial sentence, the Server Owner's statement, the reporter's
  redress), § 12 (the guest's notice), § 14 (what the *private* mode drops), Still open 10 (not this CR's, named so the
  text is not read as settling it); [ADR 0099](../docs/decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
  § 6 (the Server Owner's steps live on the admin channel); [ADR 0066](../docs/decisions/0066-tenant-suspension.md).
- **Verified primary text (2026-10-18, EUR-Lex):** DSA Art. 16(5) ("providing information on the possibilities for
  redress in respect of that decision"); Art. 17(1)(c), (d); Art. 17(2) (only where contact details are known);
  Art. 17(3)(a) ("where relevant, the territorial scope of the decision and its duration"), (d) ("a reference to the
  legal ground relied on" and why), (e) ("a reference to the contractual ground relied on"), (f); **Art. 53** (a
  complaint to the Digital Services Coordinator of the Member State where the recipient is located or established);
  § 12(1) DDG names the Bundesnetzagentur the competent authority under DSA Art. 49(1). TCO Art. 11 is the audit's
  reading of the primary text.
- **No entry condition.** Independent of the other three CRs of this audit. `cr-account-erasure` adds the tenant
  deletion: whichever of the two lands second wires the statement into it (both CRs say so).
- Sibling: `changes/cr-operator-section-of-the-terms.md` (in implementation as this is written) describes the
  statement in the operator section; this CR's phase 4 re-reads it on main and adds the provision and the Server
  Owner's statement to that text only if it already describes the statement's content — the two CRs split the text
  by passage, never edit the same sentence.

### Measured substrate (worktree at `a1898747`, clean)

| Fact | Locator |
|---|---|
| The reporter's mail carries only the outcome and the reference | `sed -n 160,190p backend/core/src/main/kotlin/me/raddatz/veyq/core/moderation/statement/StatementMail.kt` |
| The ground is a category enum, no provision; no territorial sentence | `sed -n 28,34p contracts/moderation/statement-of-reasons.json`; `grep -ni 'territorial\|Member State' contracts/moderation/statement-of-reasons.json` (none) |
| The template's passages and which are `full` | `grep -n '"id"\|"full"\|"when"' contracts/moderation/statement-of-reasons.json` |
| Where the ground is rendered (backend, web) | `grep -n '{ground}' backend/core/src/main/kotlin/me/raddatz/veyq/core/moderation/statement/StatementTemplate.kt web/src/features/moderation/statement/template.ts` |
| The moderator's form has a ground select and facts, no provision | `grep -n 'moderation-statement-ground\|facts' web/src/features/moderation/CaseDetail.vue` |
| The statement row (clear ground, sealed facts) | `grep -n 'Column' backend/core/src/main/kotlin/me/raddatz/veyq/core/moderation/statement/StatementEntities.kt` |
| The Server Owner's steps produce no statement | `grep -n 'Statement\|statement' backend/core/src/main/kotlin/me/raddatz/veyq/core/moderation/cases/SignInBlocks.kt backend/core/src/main/kotlin/me/raddatz/veyq/core/admin/tenant/TenantHandler.kt` (none) |
| Their doors: admin channel, admin-web, CLI | `grep -n 'operationId: blockAccountSignIn\|operationId: suspendTenant' contracts/api/*.yaml`; `grep -n 'blockSignIn\|setSuspended' admin-web/src/features/instance/ModerationPanel.vue admin-web/src/features/tenants/TenantDetailPanel.vue`; `grep -n "'account block'\|'tenant suspend'" admin-web/src/cli/commands.ts` |
| An order's statement (ground, basis, no facts) and the deferral | `sed -n 245,290p backend/core/src/main/kotlin/me/raddatz/veyq/core/moderation/order/AuthorityOrderHandler.kt`; `grep -n 'fun ' backend/core/src/main/kotlin/me/raddatz/veyq/core/moderation/statement/StatementDeferrals.kt` |
| Order kinds; a removal order is filed as terrorist content | `grep -n 'enum class OrderKind' backend/core/src/main/kotlin/me/raddatz/veyq/core/moderation/order/OrderEntities.kt`; `grep -n 'TERRORIST_CONTENT' backend/core/src/main/kotlin/me/raddatz/veyq/core/moderation/order/AuthorityOrderHandler.kt` |
| A guest is a statement recipient row but never told; a WRITE_ONLY guest sees no uploads after reload | `grep -n 'GUEST' backend/core/src/main/kotlin/me/raddatz/veyq/core/moderation/statement/StatementRecipients.kt`; `sed -n 1,15p web/src/features/memory/components/MemoryShareBody.vue` |
| What a blocked item serves (a flag, 451 on bytes) and what a blocked link answers | `grep -n 'blockedByOperator\|blocked_by_operator' contracts/api/sharing.openapi.yaml contracts/api/share-media.openapi.yaml contracts/api/content.openapi.yaml`; `grep -n 'fun shareBlocked\|fun identityBlocked' backend/core/src/main/kotlin/me/raddatz/veyq/core/moderation/block/ModerationBlocks.kt` |
| The guest's credential is its contributor token | `grep -n 'contributorToken' backend/core/src/main/kotlin/me/raddatz/veyq/core/sharing/ShareGuestGate.kt` |
| The instance contact the texts name | `grep -n 'fun contactOf\|fun publicValues' -r backend/core/src/main/kotlin/me/raddatz/veyq/core` |
| Pages that speak for the statement | `grep -rlni 'statement of reasons\|objection\|Widerspruch\|Begründung' site/src/content/docs` |
| The Run page's "not built" note this closes | `grep -n 'A statement for your own steps' site/src/content/docs/run/moderation.md` |

## Decisions this CR takes

1. **The reporter's redress passage lives in the template file**, as a `reporterDecision` block of passages (DE and EN):
   *you may report again with more information*; *the instance contact* (`when: contact`); *you may go to court*
   (`full`); *you may complain to the Digital Services Coordinator of the Member State where you live or are
   established* (`full`). Generic wording, no authority's name: an instance's reporters live anywhere, and Art. 53 points
   at their own state. `StatementMail.decisionBody` renders it after the outcome, in the mode's form.
2. **The provision is a short clear text on the statement** (`provision`, at most a line: "§ 86a StGB", "Space terms,
   clause 4"), stored beside the ground and rendered after it — never the facts, which stay sealed and are where the
   moderator explains *why* the content falls under it (the facts' hint says so). Required in the full form
   (`statement_provision_required`), optional in the reduced. An order's statement sets it itself: *Regulation (EU)
   2021/784, Art. 3 (removal order)* for `REMOVAL`, matching the handler's terrorist-content filing.
3. **The territorial sentence is a fixed `full` passage**: the measure applies wherever this instance is reached and
   lasts until it is lifted; for an order, the hold's end is the order's.
4. **A Server Owner's step is "over content" when it comes from a case's proposal**, always; a step without a case
   asks the Server Owner (a tick in admin-web, `--over-content` in the CLI), and only then needs a statement. A tenant
   suspended for any other reason (unpaid, maintenance) carries none — Art. 17(1) reaches only restrictions over
   content.
5. **Its statement is entered with the step**, in the same request: ground, provision, facts. admin-web holds no vault,
   so it seals the facts' log copy to the moderation key's **public** half (`sealTo`, the shared crypto package) and
   sends the facts in the clear for the mail (Q24). The server writes a statement row, its recipients and the log
   events in the step's transaction, as a moderator's measure does.
6. **A guest's order notice is a read on the link, keyed by the contributor token** the identity's writes already
   carry: it answers, for that identity only, the removal orders that blocked something it uploaded (date of
   receipt, the instance contact), and is the one thing a link blocked by that order still answers, and only to such
   an identity. Nothing for a deferred statement; nothing for a moderator's measure. web/ asks it when it holds a local
   identity for the link and the link or an own item answers blocked.

## Who is told what, through which channel

| Recipient | What | Channel |
|---|---|---|
| A reporter who left a contact | the decision and the redress passage (full or reduced) | instance mail, at the address the deciding moderator's browser opened (as built) |
| A reporter with no contact | nothing | none |
| A member a moderator's measure affects | the statement with the provision and the territorial sentence | in the app (`StatementNotice`) and instance mail, facts in the app only (as built) |
| A member the Server Owner blocks from sign-in, over content | the statement, facts included | instance mail at the account address (`VeyqUser.email`); never in the app — they cannot sign in |
| The owners of a tenant taken offline (or deleted) over content | the statement, facts included | instance mail at each owner's account address (Q25) |
| The other members of that tenant | nothing beyond today's "offline" mark in the switcher | none (Q25) |
| A guest uploader on an authority's removal order | removed on an order of `<date>`; reasons or a copy from `<contact>` | on the link, to the client holding the identity's contributor token |
| A guest on a moderator's measure | nothing (DSA Art. 17(2)) | none |
| Anyone, while an authority's deferral runs | nothing of the above for that order | none |
| No instance mail set up | the mailed rows above are not sent; admin-web already warns, and the step's form says the recipient cannot be told | none |

Objection: a moderator's measure as built (in the app, once); a Server Owner's step through the instance contact, which
the statement names — the Server Owner lifts with the existing unblock or reactivate.

## Open questions (owner) and legal items

**Decided (Q24–Q25).** Owner 2026-10-18, one by one: both as recommended.

- **Q24 — How does a member blocked from sign-in read the facts?** **Recommendation: by mail, facts included**, the
  Server Owner's own words. The alternative — a sign-in that, after the password proof, shows only the member's
  statements — keeps the facts out of mail but builds a second, restricted session kind for one case. The facts of a
  moderator's measure stay out of mail as built.
- **Q25 — Who receives the statement for a tenant taken offline or deleted?** **Recommendation: the tenant's owners
  only.** Every member loses the service, but the measure is over content the owners answer for, and facts mailed to
  every member could expose one member's content to all.
- **Legal:** every text is a draft until a qualified review (ADR 0093 § Legal basis). Whether the Bundesnetzagentur is
  Germany's Digital Services Coordinator is read from § 12(1) DDG's competence for Art. 49(1), not from a designation
  act; the passage names no authority (decision 1), so nothing ships on that reading.

## Phases

- [ ] **0 docs** — the three use-cases' flows: object-to-a-measure (step 7 the redress passage; A7 rewritten: the
  Server Owner's steps over content carry a statement by mail, objection to the contact), take-a-measure (the
  provision; the Server Owner's statement), authority-order (step 5–6: the guest's notice, its deferral). Their new
  acceptance criteria join the coverage files in the phases that bring their refs (a built file holds no uncovered
  criterion, `docs/coverage/README.md`). · Done when: feature-format, doc-link, doc-citation, change guards green.
- [ ] **1 contracts** — `statement-of-reasons.json`: `reporterDecision`, `{provision}` in the ground passage, the
  `scope` passage; the measure request gains `provision`; `blockAccountSignIn` and `suspendTenant` gain an optional
  statement (over-content flag, ground, provision, facts, the sealed log copy); the guest's order-notice read (new
  operation; classify it in `OperatorBlindProbeTest`). · Done when: `npm run generate:api`; the contracts guards;
  `npm run check` in `web/` and `admin-web/` once, naming the typed lines the reshaped requests redden — find them by
  a field the reshaped requests already carry, across the whole tree (`grep -rln 'sealedFacts' web/src admin-web/src backend/core/src/test e2e`; for the admin requests `grep -rln 'blockAccountSignIn\|suspendTenant' admin-web/src backend/core/src/test e2e`), not by the operation's name alone.
- [ ] **2 backend** — the reporter's passage per mode; the provision stored, required in the full form, set by the
  order handler; the territorial passage; a statement, recipients, log events and the mail for a Server Owner's step
  over content; the notice read. · Done when: `./gradlew check` green. **Bites, with controls:** a dismissal's mail in
  *organisation* holds the courts and the coordinator lines, in *private* neither (control: both hold "report again");
  a full-form measure without a provision → `statement_provision_required` (control: the reduced form takes it); a
  sign-in block from a proposal without a statement → refused (control: a block with no case and no over-content flag
  is taken without one); the block's mail holds the facts and the log holds them only sealed (`grep` the event row for
  the facts' text → none); the notice answers the uploading identity's token on a link a removal order blocked
  (control: another identity's token on the same link → the link's ordinary unknown answer; the same upload blocked by
  a moderator's measure → nothing); a deferred order → nothing until its end (bite: drop the deferral check → red).
- [ ] **3 web + admin-web** — CaseDetail's provision input (required in the full form) and the rendered provision and
  territorial line in `StatementNotice`; admin-web's block form and tenant suspension gain the statement fields (shown
  when over content), the CLI their flags; web's share page and drop pages ask the notice when the client holds an
  identity for the link and something answers blocked, and show it. · Done when: `npm run check` green in both; a
  browser pass of a moderator's full-form decision, a Server Owner's sign-in block with its mail (in the dev mail
  catcher), and a guest's notice on a blocked link in DE and EN.
- [ ] **4 docs** — ADR 0093's status line (the four gaps built); `site/src/content/docs/run/moderation.md` (the "not
  built" note on the Server Owner's statement goes; the statement's provision; **who owes a transparency report and by
  when**: DSA Art. 15 for a medium or large enterprise, TCO Art. 7(2) after acting on terrorist content, by 1 March —
  ADR 0093 § Consequences); `site/src/content/docs/account/moderate.md` and its DE twin (the provision field);
  `help/report-something.mdx` and its DE twin (what a reporter may do after a decision); `sharing/what-guests-see.mdx`
  and its DE twin (the order notice); coverage refs for the new criteria. · Done when: coverage, wiki-sources and the
  site check green.

## Definition of done

Every phase's Done-when; the three use-cases' new criteria covered on `backend` and `web` (admin-web where the Server
Owner acts); every `scripts/validate_*.py` except `validate_guard_gaps.py` green. Implementation waits for the owner's
go; a legal item above blocks only the wording it names.

## Retrospective

_Filled at implementation._
