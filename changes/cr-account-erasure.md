---
id: cr-account-erasure
title: An account is erased on request, and an active tenant can be deleted, with only what the law keeps staying
status: planned
satisfies: ["uc.account.erase-account", "uc.admin.delete-tenant"]
adrs: ["0101", "0035", "0056", "0066", "0093", "0099"]
invariants:
  - operator-blind - nothing is decrypted to erase it; what goes is ciphertext and metadata, and the bytes go only through the store reclaim
  - an erasure is pending or complete, never partial - the purge erases every row keyed on the account or the tenant in one transaction, held by a guard derived from the migrations
  - what the law keeps (holds, moderation records) survives the erasure to its own end, never longer
---

## Goal

Build [ADR 0101](../docs/decisions/0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md): a member
erases their own account in the settings, the Server Owner erases one by address in admin-web and `veyq-admin`, and the
Server Owner deletes an `ACTIVE` or `SUSPENDED` tenant. Today nothing deletes an account and only a `PENDING` tenant can
be revoked, which the legal audit of 2026-10-18 found against GDPR Art. 17(1) and 12(2); by the owner rule that a legal
duty wins over what was decided or built, the duty is not open — the shape is. At the request the account stops at
once (sessions, sign-in, the member removal in every tenant, the roster); after the scope trash's 30 days one purge
erases it with its own scopes and writes the erasure into the erasure ledger; holds and the moderation records stay to
their own ends, and with them the terms acceptance rows while a case names the account. A tenant goes offline at once
and is erased after the same window. Two guards, twins of the scope's, hold that every table keyed on an account or a
tenant is erased or cascades.

## Context & constraints

- Decisions: ADR 0101 (all of it); [ADR 0035](../docs/decisions/0035-server-terms-of-service.md) decision 4 (the
  acceptance rows); [ADR 0056](../docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md) decision 8 (the
  ledger, built by `cr-backup-erasure-and-retention`); [ADR 0066](../docs/decisions/0066-tenant-suspension.md) (offline
  is a suspension's lockout); [ADR 0093](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  §§ 4, 7, 11 (what moderation keeps; a deletion over content carries a statement); [ADR 0099](../docs/decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
  (a moderator's removal rotates the epoch).
- **Order with the sibling CRs of this audit.** `cr-backup-erasure-and-retention` builds the ledger; this CR writes its
  account and tenant entries. Either order works: whichever lands second wires the account and tenant kinds into the
  ledger (both CRs name the step). `cr-statement-legal-gaps` adds the Server Owner's statement; whichever lands second
  wires it into the tenant deletion. `cr-instance-privacy-notice` describes what this CR builds and comes after it.
- **Pre-release baseline:** no existing rows to migrate; every dev database is recreated
  (`grep -n '^release:' scripts/applied_migrations.sha256`).

### Measured substrate (worktree at `a1898747`, clean)

| Fact | Locator |
|---|---|
| No path deletes an account, no contract operation erases one | `sed -n 33,36p backend/core/src/main/kotlin/me/raddatz/veyq/core/account/email/AccountEmailResource.kt`; `grep -rn 'VeyqUser\.delete\|delete from veyq_user' backend/core/src/main backend/apps/*/src/main` (none); `grep -n 'operationId: delete' contracts/api/account.openapi.yaml` (a device wrap only) |
| Tables that reference an account by foreign key, none cascading | `grep -rn 'REFERENCES veyq_user' backend/core/src/main/resources/db/migration backend/apps/*/src/main/resources/db/migration` |
| **Second angle**: columns that carry an account id without a key (editors, uploaders, `user:{id}` owners, moderation actors) | `grep -rn '\(user_id\|account_id\|_account\|personal_owner\|owner_key\|created_by\|deleted_by\|lease_holder\|worker_id\|_by\) ' backend/core/src/main/resources/db/migration backend/apps/*/src/main/resources/db/migration` — every hit not in the first list is a column the eraser classifies (decision 6) |
| Tables below a tenant | `grep -rn 'REFERENCES veyq_tenant' backend/core/src/main/resources/db/migration backend/apps/*/src/main/resources/db/migration` |
| The member removal this reuses, and what it leaves (profile, acceptance rows) | `sed -n 48,66p backend/core/src/main/kotlin/me/raddatz/veyq/core/admin/tenant/RemovalPolicy.kt`; `grep -n 'outlives it' backend/core/src/main/kotlin/me/raddatz/veyq/core/admin/tenant/TenantHandler.kt` |
| Only a `PENDING` tenant can be revoked | `sed -n 700,735p contracts/api/tenants.openapi.yaml`; `grep -n 'fun revoke\|NotPending' backend/core/src/main/kotlin/me/raddatz/veyq/core/admin/tenant/TenantHandler.kt` |
| The scope purge and its window the erasure rides | `grep -n 'scope.delete' backend/core/src/main/resources/application.properties`; `grep -n 'fun eraseScope\|@Scheduled' backend/core/src/main/kotlin/me/raddatz/veyq/core/spaces/scope/ScopePurge.kt` |
| Every session of an account ends | `grep -n 'fun revokeAllForUser' backend/core/src/main/kotlin/me/raddatz/veyq/core/account/login/SessionService.kt` |
| A moderator's removal (grants, epoch) | `grep -n 'fun remove' backend/core/src/main/kotlin/me/raddatz/veyq/core/moderation/ModerationHandler.kt` |
| One owner per active tenant: owner invites only while pending, role changes for groups only, the last owner refused | `grep -n 'operationId: inviteTenantMember' -A3 contracts/api/tenants.openapi.yaml`; `grep -n 'GROUP' backend/core/src/main/kotlin/me/raddatz/veyq/core/spaces/scope/ScopesHandler.kt \| grep -i role`; `grep -n 'LastOwner' backend/core/src/main/kotlin/me/raddatz/veyq/core/admin/tenant/RemovalPolicy.kt` |
| The Server Owner's role | `grep -n 'SERVER_OWNER' backend/core/src/main/kotlin/me/raddatz/veyq/core/identity/ServerRoles.kt` |
| Holds and reports hang on cases, not on tenants or accounts | `grep -n 'REFERENCES' backend/core/src/main/resources/db/migration/V202610172000__core_moderation_holds.sql backend/core/src/main/resources/db/migration/V202610171935__core_moderation_reports.sql` |
| A former member in the client | `grep -n 'former' web/src/core/people/directory.ts` |
| The settings hub and the browser's records keyed by account | `grep -n "personal" web/src/views/SettingsHubView.vue`; `grep -rn 'veyq-session\|veyq-remember\|veyq-trust' web/src/core web/src/features/account \| grep -v test` |
| admin-web has no account view; the CLI's account commands | `grep -n "'account " admin-web/src/cli/commands.ts`; `ls admin-web/src/features/instance` |
| The guard to mirror | `sed -n 1,40p scripts/validate_scope_erasure_arity.py` |
| Stored times need a reader | `grep -n 'class EveryStoredTimeHasAReaderTest' -r backend` |
| Pages that speak for removal, tenants and suspension | `grep -rln -i 'remove a member\|Mitglied entfernen\|suspend\|Take a tenant offline' site/src/content/docs` |

## Decisions this CR takes

1. **The account carries a pending erasure** (requested at, due at, requested by: self or the Server Owner) — a new
   table, not columns on `veyq_user`, so the purge's work list is one query. Sign-in refuses an account with one
   (`account_erasure_pending`, answering as the sign-in block does to anyone else).
2. **One eraser per account and per tenant**, in core, with the same phase mechanism the scope erasure has: core lists
   its tables, an application contributes an eraser for its own (Memories: its item favorites; buckets keep their
   `user:{id}`). The purge for an account runs after its own scopes' purge in the same tick.
3. **Kept columns are classified in code, not prose.** The eraser carries a map from every account-id column without a
   foreign key to `ERASED` or `KEPT(reason)` — kept: content attribution (`created_by`, `uploader_user_id`, the editor
   columns, `owner_key`), the moderation tables' actors and subjects, the authority orders' `entered_by`; erased: the
   favorites, a job's lease, an upload session's opener. The guard reads the map.
4. **The tenant deletion reuses the suspension's lockout** for its first step and adds `PENDING_DELETION` beside
   `SUSPENDED`; stopping it returns the tenant to `SUSPENDED`. `revokeTenant` stays for `PENDING`.
5. **A tenant owner can make another member an owner** (a role change on the TENANT scope, owner-gated), and the Server
   Owner can mint an owner invite into an active tenant (the setup's invite, reused) — the two ways out of decision 9 of
   the ADR.
6. **The erasure mail** goes through the instance mail (ADR 0097 decision 3) to the account address before the
   address is erased, retried as the statement mail is.

## Open questions (owner) and legal items

**Decided (Q16–Q20).** Owner 2026-10-18, one by one: Q16, Q17 and Q19 as recommended. **Q18 differs:** the last owner of a tenant with other members is not refused at the self-service door; the dialog offers a choice — **delete the tenant along** (decision 10's deletion, its 30-day window) **or name a new owner** from the tenant's members first — and the erasure proceeds after either; the Server Owner's door still never refuses. **Q20 differs:** accounts a tenant deletion leaves in no tenant **stay**; nothing erases an account its person did not ask to erase (they may erase it themselves).

- **Q16 — A window before the erasure, or none?** **Recommendation: 30 days, the scope trash's retention**, which the
  Server Owner alone can stop; the person cannot sign in meanwhile. It costs a pending state; it buys a way back from a
  coerced or mistaken request, and the own scopes wait 30 days in the trash anyway.
- **Q17 — What the person put into shared scopes.** **Recommendation: it stays with the scope** — content keeps its
  opaque attribution and shows as a former member's, links in shared scopes are switched off as at a removal — and the
  dialog tells the person to delete what they want gone first. The alternative, erasing their uploads everywhere, takes
  other members' memories with one person's request.
- **Q18 — The last owner of a tenant.** **Recommendation:** self-service refuses while the tenant has other members and
  no other owner, and offers to delete a tenant with no other member along; the Server Owner's door never refuses and
  leaves the tenant ownerless; a tenant owner gains "make owner", the Server Owner an owner invite into an active
  tenant. The cheaper alternative — handing the tenant to the oldest member, as a group is — makes someone answer for a
  space without asking them.
- **Q19 — A window before a tenant is erased.** **Recommendation: the same 30 days**, offline at once, stoppable by the
  Server Owner.
- **Q20 — Accounts a tenant deletion leaves in no tenant.** **Recommendation: erased with the tenant**, told by mail at
  the deletion — an account in no tenant reaches nothing and keeps an address for no purpose. A server role is never
  erased this way.
- **Legal:** whether a tenant owner is a joint controller (ADR 0093 Still open 6) does not change what is built here —
  both doors exist either way — but may change who must answer a request; the Run page says the Server Owner answers,
  as the privacy notice will.

## Phases

- [ ] **0 docs** — the two use-cases and ADR 0101 corrected to the owner's answers where they differ from the
  recommendations. · Done when: feature-format, coverage, doc-link, doc-citation, change guards green.
- [ ] **1 contracts** — `eraseOwnAccount` (signed in, password proof), `eraseAccount` and `stopAccountErasure` (admin
  channel, by address), `deleteTenant` and `stopTenantDeletion` (admin channel), the tenant member role change,
  the owner invite into an active tenant, `PENDING_DELETION` in `TenantStatus`; each new operation classified in
  `OperatorBlindProbeTest`, each admin one in the admin-channel sweep. · Done when: `npm run generate:api`; the
  contracts guards; `./gradlew check` red only on the tests that see a new operation on day one (name them), and
  `npm run check` in `web/` and `admin-web/` once, naming the typed lines `TenantStatus` reddens
  (`grep -rn "'SUSPENDED'" web/src admin-web/src`).
- [ ] **2 guards** — `scripts/validate_account_erasure_arity.py`: the FK closure below `veyq_user` and below
  `veyq_tenant` from the migrations, each table erased by its eraser or cascading; every account-id column without a key
  (the second-angle locator's spellings) present in the eraser's map. Bite against the untouched tree first: it must be
  red today, naming every table the first two locators list (`changes/README.md` § run a new guard's predicate against
  the UNTOUCHED tree); then a forged table `REFERENCES veyq_user` turns it red after phase 3 (control: the same table
  with `ON DELETE CASCADE` → green). · Done when: the guard is red on this phase's tree for the reason it names, and
  its docstring carries the required fields.
- [ ] **3 backend** — the pending erasure, its two doors and its stop; the lockout at the request (sessions, sign-in,
  pendings, the member removal per tenant, the roster); the mail; the purge after the window; the acceptance rows kept
  while a case names the account; the tenant deletion, its stop and its purge, the accounts it leaves in no tenant;
  the role change and the owner invite; the ledger entries if `cr-backup-erasure-and-retention` has landed. · Done
  when: `./gradlew check` green and phase 2's guard green. **Bites, with controls:** erase a member who owns a personal
  scope, a group alone and uploads into a shared Memory → after the window every row of the first two and the account
  are gone, the shared Memory's media keep `created_by` (control: the account's favorites in that Memory are gone); an
  account a measure is in force on keeps its acceptance rows to the case date (control: an account no case names keeps
  none); sign-in during the window → refused (control: after a stop → admitted); the Server Owner's account →
  `server_owner_account`; the last owner with members at the self door → refused (control: the Server Owner's door →
  erased, the tenant listed ownerless); a deleted tenant's hold survives the purge (control: an ended hold does not);
  `EveryStoredTimeHasAReaderTest` names the purge as the reader of the new times.
- [ ] **4 web + admin-web** — the settings' erase dialog (what goes, what stays, the shared-scope advice, the last-owner
  refusal and the sole-member tenant), clearing the browser's records for the address; "make owner" in the tenant's
  member list; admin-web: an Accounts panel with erase and stop by address, the tenant panel's delete (typed domain)
  and stop, an ownerless tenant marked with its two ways on; the CLI verbs. · Done when: `npm run check` green in both;
  a browser pass of a self-erasure through to the refused sign-in, and of a tenant deletion and its stop, in DE and EN.
- [ ] **5 docs** — ADR 0101's status (built), ADR 0035 decision 4's sentence, the security model's § Deletion &
  erasure; site: a new Use page on erasing your account (`site/src/content/docs/account/`, EN and DE), the space owners'
  page on members (`space-owners/who-gets-into-your-space.mdx`, EN and DE: "make owner", the last owner), the Run pages
  (`run/moderation.md` § your steps, `run/first-run.mdx` § Tenants: deleting a tenant, answering an erasure request);
  coverage for both use-cases with `backend`, `web`, `admin-web` refs. · Done when: coverage, wiki-sources and the site
  check green.

## Definition of done

Every phase's Done-when; both use-cases covered (`status: planned` dropped) on `backend` and `web`, `admin-web` where
the Server Owner acts; the new guard in the glob, bitten; every `scripts/validate_*.py` except
`validate_guard_gaps.py` green (that one too, run with `--force`, since a guard was added). Implementation waits for the
owner's go.

## Retrospective

_Filled at implementation._
