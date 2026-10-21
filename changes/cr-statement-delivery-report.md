---
id: cr-statement-delivery-report
title: A Server Owner's step over content reports whom its statement reached
status: in-progress
satisfies: ["uc.admin.suspend-tenant", "uc.admin.delete-tenant", "uc.moderation.object-to-a-measure", "uc.moderation.take-a-measure"]
adrs: ["0093"]
invariants:
  - a step over content is never refused, delayed or rolled back for want of mail — the report is about the mail, not the step
  - the report is the answer's alone; nothing new is stored (each recipient's `mailedAt` stays the only stored trace)
  - the server keeps no clear copy of the facts to report or resend; the words the Server Owner sees again are the ones their own client still holds
  - the addresses not reached travel only on the admin channel, to the Server Owner, who already reads every owner's address there
---

## Goal

A Server Owner's own step over content — a tenant taken offline, a tenant deleted, a member's sign-in blocked — mails its
statement of reasons once, after the step committed (ADR 0093 § 7), and today **nobody learns whether it went out**:
`SignInBlocks.mailStatement` drops the count `StatementMail.statementSent` returns, the suspension answers `204`, the
deletion `{dueAt}`, the block `ModerationSteps`. Without instance mail, or when the send fails, the step succeeds and the
tenant's owners or the blocked member are told nothing — and neither is the Server Owner, who could tell them through the
instance contact. DSA Art. 17 wants the statement with the measure, and the Server Owner's own words are gone from the
server once the send was tried.

After this CR the step's answer says what the mail reached, admin-web says it where it stays on screen with the words
still in view, and `veyq-admin` prints it and warns. Decided with the owner 2026-10-21; the durable state is ADR 0093 § 7
(the "So the step reports what the mail reached" paragraph), uc.admin.suspend-tenant A5–A6, uc.admin.delete-tenant step 4,
uc.moderation.take-a-measure step 5 and uc.moderation.object-to-a-measure A7.

## Context & constraints

Measured 2026-10-21 in worktree `admin-web-settings-checkboxes-bc60f5` (a read-only sweep; re-run the locator below, line
numbers move):

- **Contracts.** `suspendTenant` (`contracts/api/tenants.openapi.yaml` ~972) answers `204`; `deleteTenant` (~796) `200
  TenantDeletion` = `{dueAt}`; `blockAccountSignIn` (`contracts/api/instance.openapi.yaml` ~1534) `200 ModerationSteps`.
  The statement input is `ServerOwnerStatementInput` in `_common.openapi.yaml` (~440), `$ref`'d by both. The one
  delivery schema, `DeliveryResult` (`SENT | FALLBACK_COPYLINK | FAILED`), lives in `tenants.openapi.yaml` only and is
  the invite's; it is not reused here (its `FALLBACK_COPYLINK` has no meaning for a statement).
- **Backend.** `TenantHandler.suspend` / `delete` call `moderationSteps.mailStatement(statement)` after the commit and
  discard it; `SignInBlocks.mailStatement` (`core/moderation/cases/SignInBlocks.kt` ~241) returns `Unit`;
  `StatementMail.statementSent` (`core/moderation/statement/StatementMail.kt` ~70) returns the number sent, catches every
  exception into `0` and logs "the hourly cleanup retries it" — **false for a Server Owner's step**, which `retryDue`
  filters out (`isServerOwnerStep`). `InstanceNoticeMailer.mailTo` returns `SENT` or `FAILED` (no settings, no host, no
  from, not sendable, or a send exception) and never `FALLBACK_COPYLINK`. `StepStatements.recipientsOf` gives the
  blocked account, or the tenant's `OWNER` memberships, filtered to accounts that still exist — so a tenant without an
  owner writes **no** recipient row, `sentAt` stays null, and nothing is tried.
- **Clients.** `tenants.ts` `suspendTenant: Promise<void>`, `deleteTenant: Promise<TenantDeletion>`; `moderation.ts`
  `blockSignIn: Promise<ModerationSteps>`. `TenantDetailPanel.vue` toasts `tenant.suspended` / `tenant.deletionStarted`
  and resets the statement draft (`resetStatement`); `ModerationPanel.vue`'s block goes through `actOnSteps` with no
  success message. The CLI prints each answer as JSON (`cli/output.ts` `renderResult`; a `204` prints `{"ok": true}`).
- **Tests.** `ServerOwnerStatementSliceTest` runs a real GreenMail (`TestMailbox`, "deliberately not a stubbed mailer")
  and covers only the mail that went out; nothing covers no mail, a failed send, or nobody to tell.
- **Docs.** `site/src/content/docs/run/moderation.md` says it for the sign-in block only ("without instance mail, nobody
  is told, and you tell them through the contact yourself", ~183); the tenant sections (~207, ~220) point back to it.

### Locator (run it; the counts are the 2026-10-21 run)

```
git grep -n -e 'mailStatement(' -e 'statementSent(' -- src            # 4 + 6 sites, 5 files
git grep -n -e 'suspendTenant(' -e 'TenantDeletion(' -- src tests      # admin-web facade, panel, CLI, resource
git grep -n -e 'blockAccountSignIn' -e 'blockSignIn(' -- src tests contracts
git grep -n -e '/suspend"' -e 'sign-in-blocks"' -- src/backend tests  # TenantSuspendSliceTest, ModerationMeasuresSliceTest,
                                                                       # ServerOwnerStatementSliceTest, TenantIngressProjectorTest
git grep -n 'ModerationSteps' -- src/admin-web                         # cli.test, commands, ModerationPanel(+test), moderation(+test),
                                                                       # TenantDetailPanel(+test)
git grep -n -i -e 'over content' -e 'statement' -- site/src/content/docs/run tests/e2e
```

`tests/e2e/tests/features/server-owner-statement.spec.ts` drives the block with mail set up; it reads the block's
answer only through the UI.

## Decisions this CR takes

1. **One schema, `StatementDelivery`, in `_common`** (both specs answer it), request-scoped and never stored:
   - `outcome` — `x-veyq-visibility: enum`, one of
     - `REACHED` — every recipient's mail went out;
     - `NOT_ALL_REACHED` — at least one did not (no instance mail, or the send failed);
     - `NOBODY_TO_TELL` — the step had no recipient (a tenant without an owner; an account erased before the send);
     - `UNKNOWN` — building the recipient list failed after the commit, so the server cannot say.
   - `notReached` — the addresses whose mail did not go out, `x-veyq-visibility: pii` with its `x-veyq-rationale`
     (the admin channel's Server Owner, who must tell them through the contact; they already read these addresses as
     owner addresses and in the sign-in block form). Empty unless `NOT_ALL_REACHED`.
   An enum rather than counts: the client never derives a state, and `UNKNOWN` has no honest count.
2. **The three answers carry it, absent when the step carried no statement.**
   - `suspendTenant`: **`200 TenantSuspension { statementDelivery? }`**, replacing `204` — one success shape whether or
     not over content. `reactivateTenant` keeps `204`.
   - `deleteTenant`: `TenantDeletion` gains `statementDelivery?`.
   - `blockAccountSignIn`: **`200 SignInBlockResult { steps: ModerationSteps, statementDelivery? }`**. `ModerationSteps`
     itself stays as every other moderation step answers it; a field only one operation fills does not go on it.
     `unblockAccountSignIn` keeps answering `ModerationSteps`.
3. **The backend reports per recipient.** `StatementMail.statementSent` returns a domain result (each recipient's
   address and whether `mailTo` answered `SENT`) instead of an `Int`; `outcome` is derived from it by one pure function
   (unit-tested on every case, the partial one included — GreenMail cannot refuse one of two recipients). The callers that
   only counted (`retryDue`, `ModerationPurge`, `CaseDecisions`, `AuthorityOrderHandler`) keep counting from it.
   `SignInBlocks.mailStatement` returns it; `TenantHandler` and the resources map it to the generated model. The wrong log
   line is split: a Server Owner's step logs that it is **not** retried.
4. **No refusal for want of mail** (owner 2026-10-21). The step stands; the report follows it.
5. **admin-web keeps the words in view.** On `NOT_ALL_REACHED` / `UNKNOWN` / `NOBODY_TO_TELL` the panel shows a standing
   `Message` (warn; info for nobody-to-tell), not a toast, under the tenant's header — or in the moderation panel for a
   block — naming the addresses not reached and saying to tell them through the instance contact, with the statement
   as entered (ground, provision, facts) read-only beside it and a copy button. It stays until dismissed or the panel
   loads another tenant. The editable draft is still reset, so a following delete starts empty. `REACHED` keeps today's
   toast.
6. **admin-web warns before the step.** Where the over-content checkbox is (tenant measures, the block form), a line
   says the statement cannot be mailed while the instance mail is not set up — read through `instanceMail.ts`
   `getInstanceMail`, best effort (a failed read shows nothing).
7. **CLI: exit 0, a warning on stderr.** The JSON carries `statementDelivery`; when its outcome is not `REACHED`,
   `tenant suspend`, `tenant delete` and `account block` add one stderr line naming the addresses not reached and the
   contact. `account block`'s stdout becomes the `SignInBlockResult`.
8. **Out of scope, named:** an instance mail that is set up but not yet confirmed still sends (`mailTo` does not check
   `ready`); the tenant-deletion notice to members (`TenantDeletionMail.tenantDeleted`'s ignored count) is not a
   statement of reasons; a moderator's measure keeps its in-app statement and its retry.

## Phases

### Phase 1 — contracts

`StatementDelivery` in `_common`; `TenantSuspension`, the `TenantDeletion` field and `SignInBlockResult` as decided;
every new field annotated (`scripts/validate_field_visibility.py` decides what a `$ref` property takes). Descriptions of
the three operations and of `ServerOwnerStatementInput` say what the answer reports. One use of each new schema written
in each generated language before committing (§ Phases only above real size: generator names).
**Predicted reds:** the `204 → 200` and the new block answer reshape responses only, so the backend's resources still
compile unless the generated interface's return type changes — run `./gradlew check` once and state what is red.
`OperatorBlindProbeTest` classifies no new operation (none is added).
**Done when:** contracts guards green, `npm run generate:api` in `src/admin-web/`, the backend generation runs.

### Phase 2 — backend

Decision 3 end to end; the resources fill `statementDelivery`. Slice tests in `ServerOwnerStatementSliceTest` (real
GreenMail):
- each of the three steps over content with mail set up answers `REACHED`, and each recipient's `mailedAt` is set;
- with the instance mail settings removed, each answers `NOT_ALL_REACHED` naming every recipient, and the step stands
  (the tenant is SUSPENDED / PENDING_DELETION, the block is in force);
- an ownerless tenant (ADR 0101 decision 9) taken offline over content answers `NOBODY_TO_TELL`;
- a step without a statement answers no `statementDelivery`.
Unit test of the outcome function: all sent, none sent, one of two sent, no recipient, list failure.
**Bite:** injection, snapshot-and-restore — make `SignInBlocks.mailStatement` report `REACHED` regardless; the
no-mail slice test must go red for all three steps. Write which test went red into the retrospective.
**Done when:** `./gradlew check` green.

### Phase 3 — admin-web + CLI

Facades return the new answers (`suspendTenant: Promise<TenantSuspension>`, `blockSignIn: Promise<SignInBlockResult>`;
`ModerationPanel` reads `.steps`). Decisions 5–7. EN + DE strings. Tests: `TenantDetailPanel.test.ts` and
`ModerationPanel.test.ts` — each outcome renders its message and the words, `REACHED` only the toast, the warning before
the step with and without mail; `cli.test.ts` — the stderr line and exit 0.
**Bite:** injection — drop the `NOT_ALL_REACHED` branch of the panel's message; its test must go red.
**Done when:** `npm run check` green in `src/admin-web/`; the container build (`docker build -f src/admin-web/Dockerfile .`)
only if an import was added (`src/admin-web/CLAUDE.md`).

### Phase 4 — docs and coverage

- `site/src/content/docs/run/moderation.md`: the sign-in-block passage (~183) and both tenant passages (~207, ~220) say
  what the console and `veyq-admin` report and what to do; `first-run.mdx` § Tenants points there.
- ADR 0093: the § 7 paragraph and the Status drop "not built"; the four use cases drop theirs.
- Coverage (`docs/coverage/features/…`): new acceptance criteria with their refs —
  `uc.admin.suspend-tenant`: `suspend-over-content-reports-its-delivery`, `suspend-stands-without-mail`;
  `uc.admin.delete-tenant`: `delete-over-content-reports-its-delivery`;
  `uc.moderation.object-to-a-measure`: `a-server-owners-step-reports-whom-its-statement-reached`.
- `tests/e2e/tests/features/server-owner-statement.spec.ts` gains the block's `REACHED` path assertion; run with the
  heavy tests at V1's end (owner 2026-10-19), not in this CR's loop.
**Done when:** the trust-chain guards green (`validate_guard_gaps.py` only if a guard changed).

## Definition of done

- The three steps over content answer `statementDelivery`, and a step without a statement answers none.
- No mail, a failed send, and nobody to tell are each told apart in the answer, in admin-web and in `veyq-admin`; the
  step stands in every case.
- The phase 2 and phase 3 bites went red and are recorded.
- `./gradlew check`, `npm run check` (admin-web) and the trust-chain guards are green; ADR 0093, the four use cases, the
  run docs and coverage say it as built.

## Retrospective

_(filled in by the implementer)_
