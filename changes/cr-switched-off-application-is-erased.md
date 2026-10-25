---
id: cr-switched-off-application-is-erased
title: An application left switched off is erased in the tenant after a window members are told about
status: planned
satisfies: ["uc.platform.switch-application-off", "uc.platform.erase-switched-off-application"]
adrs: ["0079", "0088", "0056", "0093", "0097", "0101"]
invariants:
  - core never calls an application and names none; it finds an application's nodes by asking NodeKindRules.applicationOf per kind
  - the erasure is the trash purge's erase (NodeSubtreePurge), so the erasure ledger, the store reclaim and every moderation hold apply unchanged
  - nothing is decrypted to erase; the server learns nothing it does not already hold (the switch, the node kinds, the members' addresses)
  - switched on again before the day, nothing is lost; the day a member was told is never moved earlier
  - a tenant only narrows what the instance allows (ADR 0088 decision 2)
  - every text follows docs/crosscutting/ui-web-styleguide.md § Copy and docs/crosscutting/cc.inclusive-language.md
---

## Goal

Inbox #539 (owner 2026-10-24: the data goes after the window; 2026-10-25: on the V1 line). Switching an application off
makes it read-only ([uc.platform.switch-application-off](../docs/features/platform/use-cases/uc.platform.switch-application-off.md)),
and that window has no end: the sealed data stays on the server for good, which is neither data minimisation
(GDPR Art. 5(1)(e)) nor what a tenant owner expects when they switch an application off. This CR gives the window an end.
When an application has been off in a tenant for the window, every node of its kinds in that tenant is erased, together
with everything that hangs off it, through the same erase the trash purge runs. Members are told the day by the banner
and by mail. Switching the application on again before the day cancels the erasure.

**The refine is closed** (2026-10-25). The owner answered § Open questions with every recommendation ("Passt"), so the
decisions below, written on the recommended answers, stand as written. Phase 0 wrote the answers into state
(`docs/ai/README.md` § Refined state comes before the CR): the use case
[uc.platform.erase-switched-off-application](../docs/features/platform/use-cases/uc.platform.erase-switched-off-application.md)
with its planned coverage, ADR 0079 decision 8, ADR 0088 decision 5 and ADR 0093 § Legal basis, Still open 12–14.
Implementation waits for the owner's go on this CR; phase 1 is next.

## Context & constraints

Measured at refine in worktree `agent-ac01d6c309339d5c9` at `1199b733b` (= `main`), with nothing changed yet. Two
Explore sweeps read the code; the pointers I relied on I re-read myself and mark (re-read).

- **The switch keeps no time.** `veyq_capability_switch` has only `id`, and `veyq_tenant_capability_switch` has `(id,
  tenant_id, capability_id)`. A row exists while the entry is off, and switching off twice keeps the one row
  (`CapabilitySwitches.kt:31-66`, re-read; `:140-157`, re-read). Instance writes lock the instance singleton row;
  tenant writes lock the tenant row (`CapabilitySwitches.kt:59` and `:147`, re-read). No event fires on a change
  (`CapabilitySwitchHandler.kt:61-67`, `TenantCapabilitySwitchHandler.kt:96-105`).
- **Levels.** `memories`, `depot` and `expenses` are all `["instance","tenant"]` (`contracts/platform/capabilities.json`,
  re-read). Each is a root, its one-segment id the only one a root may be (ADR 0088 decision 5), so only the application's
  own entry decides. A child (`memories.places`) erases nothing.
- **Kind → application** goes through `NodeKindRules.applicationOf(rules, kind)` (`NodeKindRules.kt:56-66`, re-read).
  Memories owns `MEMORY`/`CHAPTER`, Depot `FILE`/`DROPBOX`, Expenses `POT`. Each sits at the root or under a core
  `FOLDER`, and a Chapter only under a Memory (each `parentAllows`, re-read). A drop box's guest identity folders sit
  under it. There is no inverse (kinds of an application) and no index on `veyq_node.kind`.
- **The erase to reuse.** `NodeSubtreePurge.eraseNodes(scopeId, nodeIds)` (`content/NodeSubtreePurge.kt:110-123`, re-read)
  takes the scope row lock, widens to each node's whole subtree and calls `erase` (`:131-172`, re-read). In order, `erase`:
  - locks the stored objects;
  - runs every `NodeSubtreeEraser` in phase order: media with its derivatives and buckets (100), a Pot's revisions and
    receipts (150), attachments, which carry Depot's files and every cover (200), item revisions (300), re-key stages
    (350), guest-write records (400), item marks (610);
  - tombstones the links;
  - writes one `NODE` line per node to the erasure ledger and deletes the node rows, whose wrapped content key goes
    with them;
  - ends a content block on the erased nodes (`MeasureTargets.erased`);
  - drops dead guest identities;
  - bumps the tree revision.
  The erasure ledger's restore replay re-erases a `NODE` line with its current subtree (`ErasureReapplication.kt:107`).
  So a restore does not bring the data back, and no new `ErasureKind` is needed.
- **Holds survive by construction.** No erasure path reads a hold. Blobs go only through `StoreReclaimStore.record`,
  whose `due` skips a key with a live hold pin (`StoreReclaim.kt:163-169`), and a hold keeps its own copy of the rows
  (`veyq_hold_row`). An erase that reuses `erase` honours ADR 0093 § 4 unchanged.
- **Staged erasure precedent.** Tenant deletion and account erasure store a due date and run an hourly `@Scheduled`
  purge, one `requiringNew` transaction per unit, re-checked under the row lock. Every window is `P30D`:
  `veyq.scope.delete.retention`, `veyq.node.trash.retention` (`application.properties:127,146`, re-read). Every replica
  runs every job; row locks keep it safe (`TenantPurge.kt:78-80`).
- **Mail precedent.** `TenantDeletionMail` (`admin/tenant/TenantDeletionMail.kt:28-57`, re-read) mails every member of a
  tenant (`pendingConsent = false`) through instance mail. It runs after the commit, tries once, writes one EN/DE body,
  and counts how many went. `InstanceNoticeMailer.mailTo` answers `FAILED` without instance mail. Nothing queues and
  nothing refuses. `AccountErasureMail` adds a `mailed_at` claim with an hourly retry for 7 days.
- **What a member's client is handed.** The banner is `MemoryHomeView.vue:417-419`, keyed by
  `applications.switchedOff` (`src/web/src/i18n/index.ts:1399` EN, `:3333` DE). It is driven by the effective set of
  `GET /api/tenants/{id}/capabilities` (`TenantCapabilities`, `contracts/api/tenants.openapi.yaml:3042`, re-read).
  That set carries no time. The dated banner to reuse is `groups.pendingDeletion` ("Scheduled for deletion on {day}…",
  `index.ts:1440`). A link guest's resolve carries no tenant setting (uc E6).
- **The privacy notice has no content retention** beyond the account. Its `mail` passage says a space's notices go
  through the space's relay (`contracts/terms/privacy-notice.json` passage `mail`, re-read). The operator section says
  nothing about applications. `draft` stays true until a qualified legal review.
- **The site says today, truly, that nothing is deleted.** `run/capabilities.mdx:55` and `:212-218` (which also names
  only Memories and Depot), `space-owners/capabilities.mdx:17,27-28` (EN; DE `:19,30`), `applications/index.mdx:29-31`
  (EN and DE). Each becomes false with this CR, in the phase that builds it.
- **No L1 spec switches an application off today.** Only the exchange rates are switched (`privacy-notice.spec.ts:196`,
  `expenses-pot.spec.ts:272`), found by `grep -rn "Switch.*off\|capabilit" tests/e2e/tests`. A short window in the e2e
  overlay therefore erases nothing another spec relies on.

## Decisions this CR takes

1. **One erasure row per tenant and application while it is off.** A new core table, `veyq_application_erasure`:
   - `tenant_id` (FK `veyq_tenant` `ON DELETE CASCADE`), `application` (the register id), `switched_by` (`TENANT` or
     `INSTANCE`, the level that made the row, for the notice's wording), `off_since`, `due_at`, `notice_mailed_at`,
     `reminder_mailed_at`;
   - unique on `(tenant_id, application)`.

   A row exists exactly while the application is off for the tenant at either level, plus while a due erasure is
   still running (decision 4). It is the purge's work list, the banner's day and the mails' claims. Its writers, each
   under the **instance singleton row's lock first, then the tenant row's**, so that two levels switching at once cannot
   leave an off application without its row:
   - the tenant switch: off makes the row if none exists. On deletes it unless the instance still holds it off, or it
     is due;
   - the instance switch: off makes the row in every tenant that has none. On deletes it in every tenant whose own
     switch is on, unless it is due;
   - tenant creation while the instance holds an application off: makes the row, sends no mail;
   - the purge (decision 4).

   A row that exists keeps its `off_since` and `due_at`, so a second level switching off never moves a day members
   were told.
2. **The window is one instance-wide duration, `veyq.application.off.retention`, default `P30D`** (Q1). It works like
   every other erasure window: a configuration property the Server Owner can change, not a screen setting, with nothing
   for a tenant owner to set. The texts never name the number: the banner, the tenant owner's panel and the mails name
   the **day** (`due_at`), and the privacy notice names the window through a placeholder, the way `{erasureWindow}`
   works. The e2e overlay shortens it (phase 4).
3. **What is erased.** `ApplicationErasurePurge`, a core `@Scheduled(every = "{veyq.application.erasure.purge.every}")`
   job (default `1h`), takes each row with `due_at <= now` and handles every scope of the tenant (`ScopeEntity.tenantId`,
   personal spaces, groups and groups in their grace included) in its own `requiringNew` transaction. In each scope it:
   - takes the row's lock and re-checks that the row is still there and due;
   - selects the scope's nodes whose kind `applicationOf` names the row's application and whose `created_at` is
     before `due_at`, trashed ones included;
   - hands them to `NodeSubtreePurge.eraseNodes`.

   A failed scope is logged and retried at the next tick, and erased scopes find nothing the second time. When every
   scope succeeded, the row goes (decision 4). Core folders stay, because they organise only. Scope keys and
   memberships stay, because they are the scope's. Holds, the ledger and the reclaim come with `erase` (§ Context).
   The completeness proof is derived, not listed (phase 2).
4. **Switching on cancels up to the day** (Q3). Before `due_at`, switching the application on deletes the row, and
   nothing is erased. From `due_at` the erasure is committed. The row stays until the purge has handled every scope,
   even if the application is switched on meanwhile. The `created_at < due_at` filter keeps what members create after
   that switch-on. When the purge finishes and the application is still, or again, off for the tenant (read under the
   same two locks as decision 1's writers), it makes a fresh row (`off_since` = now, `due_at` = now + window) and sends the notice again. The day the banner names is final, and
   the switch is never refused.
5. **Who is told, per recipient** (Q2, Q4; `changes/README.md` § A CR that tells "the affected"):

   | Recipient | At switch-off | Seven days before the day | Channel |
   |---|---|---|---|
   | every member of the tenant (`pendingConsent = false`), the tenant owner among them | the notice | the reminder | the banner at the top of veyq (from the row, dated) and instance mail to the account address |
   | the tenant owner, additionally | — | — | their Settings → Capabilities row shows "Erased on {day}" beside the entry |
   | the Server Owner | — | — | the `whenOff` text of admin-web's confirmation; nothing per tenant |
   | a link guest | nobody | nobody | not told (uc E6); the link serves until the erasure tombstones it |
   | a member who joins in the window | — | the reminder | the banner |

   Both mails go through instance mail (`InstanceNoticeMailer.mailTo`), one try per member, as the tenant deletion's
   does, and both are sent by the purge's tick, not by the switch's request: the tick claims a row whose
   `notice_mailed_at` is empty (or whose reminder is due) under the row's lock, stamps it and mails after that commit.
   So no switch request waits on a relay, an instance switch over many tenants included, and the notice reaches
   members within one tick. An erasure is the host's to announce (ADR 0097 decision 3, ADR 0101), and a tenant without its own
   relay is the usual case. `notice_mailed_at` and `reminder_mailed_at` record that the attempt was made, so each goes out
   once. A window of seven days or less has no
   reminder.

   **Without instance mail, nothing is mailed.** The banner and the owner's panel carry the day, the switch is not
   refused, and the window does not grow. An instance-level switch makes one row and one notice per tenant. The text
   names who switched: the owner of the space, or the operator of the instance. For an instance switch the text says
   the tenant owner cannot switch it back on and the operator can.
6. **Export in the window** is the read path the switch already keeps (uc step 3; download is the export). Nothing
   changes.
7. **Legal texts.**
   - The privacy notice's `storage` passages gain one line on how long content is kept. The line names the trash and
     the switched-off window through the new placeholder `{applicationOffWindow}`, and `revision` moves.
   - The `mail` passage changes, so that erasure notices go through the instance's relay and not the space's.
   - The operator section of the terms does not change (Q6).
   - The legal blockers of Q6 are ADR 0093 § Legal basis, Still open 12–14.

## Inventory

| Area | What changes | Locator |
|---|---|---|
| Register | `memories`, `depot`, `expenses`: `whenOff` (EN/DE) and `description` say what is erased, and when | `python3 -c "import json;d=json.load(open('contracts/platform/capabilities.json'))['capabilities'];[print(k,d[k]['whenOff']['en']) for k in ('memories','depot','expenses')]"` |
| Contracts | `TenantCapabilities.applicationErasures` (required, `[{id, dueAt}]`); `TenantCapabilitySwitch.erasureDueAt` (optional); the instance mail settings' description, stale today because it says "only … to the Server Owner" while erasure mails already go to members | `grep -n "TenantCapabilities:\|TenantCapabilitySwitch:" contracts/api/tenants.openapi.yaml`; `grep -n "only\*\* instance notices" contracts/api/instance.openapi.yaml` |
| Builders of the response | the server's construction and every fixture that types it | `grep -rn "TenantCapabilities(" src/backend --include='*.kt'`; `grep -rln "capabilities: \[" src/web/src --include='*.test.ts'` |
| Switch writes | lock order, row upkeep (decision 1) | `grep -n "fun set" src/backend/core/src/main/kotlin/me/raddatz/veyq/core/platform/capabilities/CapabilitySwitches.kt`; `grep -rn "fun create" src/backend/core/src/main/kotlin/me/raddatz/veyq/core/admin/tenant/TenantHandler.kt` |
| Purge and erase | the new job; `eraseNodes`; kind → application | `grep -n "fun eraseNodes\|fun erase(" src/backend/core/src/main/kotlin/me/raddatz/veyq/core/content/NodeSubtreePurge.kt`; `grep -rn "applicationOf" src/backend --include='*.kt' \| grep -v /test/` |
| Mail | notice and reminder, beside `TenantDeletionMail` | `grep -rn "mailer.mailTo(" src/backend/core/src/main/kotlin --include='*.kt'` |
| Stored times | the new `Instant` columns need readers | `grep -n "READERS" src/backend/src/test/kotlin/me/raddatz/veyq/core/persistence/EveryStoredTimeHasAReaderTest.kt` |
| Tables reached from a tenant | the cascade holds | `python3 scripts/validate_account_erasure_arity.py` |
| Config | `veyq.application.off.retention`, `veyq.application.erasure.purge.every`, declared beside the others | `grep -n "retention\|purge.every" src/backend/core/src/main/resources/application.properties`; `grep -n "fun trash\|fun retention" src/backend/core/src/main/kotlin/me/raddatz/veyq/core/config/VeyqConfig.kt` |
| Privacy notice | `storage`, `storage-external`, `mail`; the placeholder in the renderer | `grep -n "erasureWindow" -r contracts/terms src/backend/core/src/main/kotlin` |
| Web | the dated banner; the owner panel's day | `grep -n "switchedOff\|pendingDeletion" src/web/src/i18n/index.ts`; `grep -n "application-off-banner\|isSwitchedOff" src/web/src/views/MemoryHomeView.vue`; `grep -n "whenOff\|effective" src/web/src/features/tenant/TenantCapabilitiesPanel.vue` |
| L1 | a new spec; the overlay's two env values | `grep -n "VEYQ_RELEASE_CHECK_EVERY" tests/e2e/stack/docker-compose.e2e.yml`; `grep -rn "Switch.*off\|capabilit" tests/e2e/tests` |
| Site | each page below, EN+DE where it has both; `run/configuration.md`, `run/data-protection.md` (retention tables) | `git grep -n -i "never deletes\|nothing is deleted\|nichts gelöscht\|time to save\|Zeit" -- site/src/content/docs/run/capabilities.mdx site/src/content/docs/space-owners site/src/content/docs/de/space-owners site/src/content/docs/applications site/src/content/docs/de/applications` |
| Docs | the use cases; ADR 0079 decision 8; ADR 0088 decision 5; ADR 0093 § Still open; security model § Deletion & erasure | `grep -n "Off is a window\|#539" docs/decisions/0079-*.md docs/decisions/0088-*.md docs/features/platform/use-cases/*.md`; `grep -n "^## Deletion" docs/crosscutting/security-model.md` |

## Texts

| Key | EN | DE |
|---|---|---|
| register `memories.whenOff` | Nothing new can be added to Memories or changed in them. Everything there can still be opened and downloaded until the day shown at the top of veyq; then it is erased, unless Memories is switched on again before. | In Erinnerungen kann nichts Neues dazukommen und nichts geändert werden. Alles darin lässt sich bis zu dem Tag öffnen und herunterladen, den veyq oben anzeigt; dann wird es gelöscht, wenn Erinnerungen nicht vorher wieder eingeschaltet wird. |
| register `depot.whenOff` | No files can be added or changed. Everything there can still be opened and downloaded until the day shown at the top of veyq; then it is erased, unless Depot is switched on again before. | Dateien können weder dazukommen noch geändert werden. Alles lässt sich bis zu dem Tag öffnen und herunterladen, den veyq oben anzeigt; dann wird es gelöscht, wenn Depot nicht vorher wieder eingeschaltet wird. |
| register `expenses.whenOff` | No Pot can be created and no entry added or changed. Every Pot can still be opened and downloaded until the day shown at the top of veyq; then it is erased, unless Expenses is switched on again before. | Keine Kasse kann angelegt und kein Eintrag hinzugefügt oder geändert werden. Jede Kasse lässt sich bis zu dem Tag öffnen und herunterladen, den veyq oben anzeigt; dann wird sie gelöscht, wenn Ausgaben nicht vorher wieder eingeschaltet wird. |
| web `applications.switchedOffUntil` | {application} is switched off here. You can open and download what’s here until {day}; then it is erased. Save what you want to keep. | {application} ist hier abgeschaltet. Was da ist, kannst du bis zum {day} öffnen und herunterladen; dann wird es gelöscht. Sichere, was du behalten willst. |
| web, the owner's panel | Erased on {day} unless switched on again | Wird am {day} gelöscht, wenn es nicht wieder eingeschaltet wird |
| mail subject (notice) | veyq: {application} is switched off in a space you belong to | {application} ist in einem Bereich abgeschaltet, dem du angehörst |
| mail body (notice) | {who} switched {application} off in the space "{space}". Everything in it can still be opened and downloaded until {day}; then it is erased, unless {application} is switched on again before. Download what you want to keep. | {who} hat {application} im Bereich „{space}“ abgeschaltet. Alles darin lässt sich bis zum {day} öffnen und herunterladen; dann wird es gelöscht, wenn {application} nicht vorher wieder eingeschaltet wird. Lade herunter, was du behalten willst. |
| mail subject (reminder) | veyq: {application} in "{space}" is erased on {day} | {application} in „{space}“ wird am {day} gelöscht |
| mail body (reminder) | {application} is still switched off in the space "{space}". On {day} everything in it is erased. Download what you want to keep before then. | {application} ist im Bereich „{space}“ weiter abgeschaltet. Am {day} wird alles darin gelöscht. Lade vorher herunter, was du behalten willst. |
| `{who}` | The owner of the space / The operator of this veyq instance (the latter adds: "Only the operator can switch it back on.") | Die Person, der der Bereich gehört / Die Person, die diese veyq-Instanz betreibt (dazu: „Wieder einschalten kann es nur die Betreiberseite.“) |

Each mail is one body, EN above DE, with an "EN / DE" subject, as `TenantDeletionMail.kt:45-57` writes it. The
application's name is the catalog's title in each language. The day is `yyyy-MM-dd` in mail and the web's day format
on screen. `applications.switchedOff` stays as the fallback for an off application with no day served.

## Phases

0. **Refine close (the refining conversation, not the implementer). Done 2026-10-25.** Once the owner has answered:
   - Write `uc.platform.erase-switched-off-application`, with `status: planned` coverage that gaps `backend` and
     `web`. It is a use case of its own: the time is its trigger, and its postcondition (the data gone) and its failure
     modes (no mail, switched on in time, an instance switch, a hold) belong to no operation of
     `uc.platform.switch-application-off`.
   - Point `uc.platform.switch-application-off`'s quality note and postcondition at it.
   - Edit ADR 0079 decision 8 ("Off is a window, not an end … open (inbox #539)") and ADR 0088 decision 5 in place.
   - Add the legal blockers of Q6 to ADR 0093 § Still open, under its "taken to be met until veyq is offered as a
     service" treatment.
   - Add the new use case to this CR's `satisfies:`.

   **Done when:** every `scripts/validate_*.py` exits `0`.
1. **Contracts.** Before the first edit, record the exit codes of `./gradlew check` and `npm run check` (web,
   admin-web) on `main`, and the guard baseline.
   - The register texts above and the three descriptions.
   - `TenantCapabilities.applicationErasures` and `TenantCapabilitySwitch.erasureDueAt`.
   - The instance mail settings' description: today's recipients, erasure mails included.
   - The privacy notice: the lines and placeholder of decision 7, and `revision` moves.

   One use of each new schema in each generated language (`changes/README.md` § Phases).

   **Done when:** `npm run generate:api` runs and the contracts guards are green. The predicted reds are named and
   measured once: the backend's `TenantCapabilities(...)` at `TenantsResource.kt:372` (a required response field), and
   the web fixtures the inventory's grep lists. They clear in phases 2 and 3. Phases 1 to 3 land on `main` in one merge.
2. **Backend.** Decisions 1 to 5 and 7: the migration, the store and its row upkeep at the three writers and the
   creation, the purge, the two mails, the response fields, the placeholder, the config.

   **Done when** `./gradlew check` is green, and slice tests drive the clock through a `now` parameter as
   `TenantHandler.delete(now = …)` does. Each negative has a positive control on the same predicate:
   - (a) Memories is off for a tenant past its day. Every node of its kinds is gone, in a personal space and a group,
     trashed ones too, with their media, derivatives, covers, item marks, guest-write records and links. A core folder
     that held a Memory stays. **Control:** the same tenant's Depot file and a second tenant's Memory stay.
   - (b) **The derived completeness sweep.** For every table the applications' migrations create
     (`src/backend/apps/*/src/main/resources/db/migration`) and every core table a `NodeSubtreeEraser` writes, no row
     names an erased node or its scope's application rows. It is bitten by dropping one eraser from the erase: the sweep
     goes red and names the table. Restore, and it is green.
   - (c) The erasure ledger holds one `NODE` line per erased node. `ErasureReapplication` over a re-inserted node erases
     it again.
   - (d) A held medium's blob stays in the store and its hold rows stay. **Control:** an unheld medium's blob is
     reclaimed.
   - (e) Switched on one tick before the day, nothing is erased and the row is gone. Switched on after the day, what
     existed goes, and a Memory made after the switch-on stays. Off again after that, a fresh row and a fresh notice.
   - (f) The instance switches off: every tenant has a row, and a tenant already off keeps its earlier day. The
     instance switches on: the rows go except where the tenant's own switch is off. A race of the two levels on one
     tenant leaves the row the final state implies. This is found red first, not argued from the read
     (`changes/README.md` § A race the refine only read).
   - (g) Mails: Mailpit is not available here, so a recording `InstanceNoticeMailer`. Every member gets the notice once,
     and the reminder once seven days before the day. **Control:** a member with `pendingConsent` gets none, and with
     no instance mail nothing is sent and the row stands.
   - (h) `getTenantCapabilities` serves the day to a member, and `listTenantCapabilitySwitches` serves it to the owner.

   `validate_account_erasure_arity.py` is green, and `EveryStoredTimeHasAReaderTest` names each new column's reader.
3. **Web.** The dated banner, reading `applicationErasures` through the host's `Access` as `applicationsOff` does
   (`src/web/CLAUDE.md`: no rights function reads `hasCapability` inline). The owner panel's day. The texts in EN and DE.

   **Done when:** `npm run check` (web, admin-web) is green, and there is a **browser pass** on the dev instance:
   - A tenant owner switches Depot off. The banner names the day, the owner's row names it, and the confirmation showed
     the new `whenOff`.
   - Switched on, the banner is gone. With the dev backend started with a two-minute window, a file is gone after it.
4. **L1, site, docs, coverage.**
   - **L1:** the e2e overlay sets `VEYQ_APPLICATION_OFF_RETENTION` to a few minutes and
     `VEYQ_APPLICATION_ERASURE_PURGE_EVERY` to seconds, beside `VEYQ_RELEASE_CHECK_EVERY`. A new spec,
     `application-erasure.spec.ts`, runs after `instance-mail.spec.ts`, in the `features` project, last:
     1. a tenant owner uploads a Depot file and makes a link, then switches Depot off;
     2. a member's banner names the day, and Mailpit holds the notice for the member;
     3. a switch-on removes the banner and a second switch-off starts again;
     4. after the window the file is gone from the member's Depot and the link answers *not available*;
     5. **control:** a Memory of the same tenant is unchanged.

     The 30-day reminder is not crossed in L1; phase 2's (g) owns it.
   - **Site,** EN+DE where the page has both: `run/capabilities.mdx` (`:55`, `:212-218`, Expenses added), the window's
     property in `run/configuration.md`'s table (beside `veyq.node.trash.retention`, `:216`), the retention in
     `run/data-protection.md`'s table; `space-owners/capabilities.mdx`; `applications/index.mdx`, walked in
     the client before it counts as written.
   - **Docs:** security model § Deletion & erasure, one sentence.
   - **Coverage:** the new use case's criteria from `planned` to refs.

   **Done when:** `npm run check` in `site/` is green, every `scripts/validate_*.py` exits `0`, and lane L1 is green
   with the new spec executed. Read the log to confirm it ran.

## Definition of done

- An application off for its window in a tenant is erased there through the trash purge's erase. The ledger, the
  reclaim and the holds apply, and a derived sweep proves nothing of it is left.
- Switched on before the day, nothing is lost.
- Every member was told the day by banner and, where the instance sends mail, by mail.
- The site and the privacy notice say so.
- Inbox #539 is deleted.

## Open questions (owner)

All six answered by the owner on 2026-10-25 ("Passt"): each recommendation was taken, and decisions 1 to 7 are written
on them.

1. **The window: how long, and who sets it?**
   - (a) a fixed 30 days;
   - (b) a configuration property of the Server Owner, default 30 days;
   - (c) the Server Owner in admin-web, per instance, as a days value;
   - (d) the tenant owner per tenant, within a floor the Server Owner sets.

   **Answered 2026-10-25: (b).** `veyq.application.off.retention`, default `P30D`, set by the Server Owner; no tenant
   lever (decision 2).

   **Recommendation: (b).** It is how every other erasure window works today: trash, group, tenant and account, all
   `P30D` properties. The tenant owner already decides *whether* the application is off. Letting them also decide *how
   soon* other members' content goes adds a second lever over other people's data, and with it a floor that someone
   must police. Unlike (a), (b) lets L1 prove the real erasure with a short window. (c) is UI for a value nobody has
   asked to change.
2. **How are members told, and when?**
   - (a) the banner with the day, a mail at switch-off to every member, and one reminder mail seven days before the
     day;
   - (b) the same without the reminder, as the tenant deletion does today.

   In both, without instance mail it is the banner alone, and the switch is neither refused nor delayed.

   **Answered 2026-10-25: (a).** The banner, a notice at switch-off and a reminder seven days before the day; without
   instance mail, the banner alone (decision 5).

   **Recommendation: (a).** Erased content cannot be restored, and the member most likely to miss the banner is the
   one who has not opened veyq for weeks. The reminder costs one claim column and one branch in the purge's tick.
3. **Up to when does switching on cancel?**
   - (a) up to the day the banner names: from then the erasure is committed and finishes, keeping what is made after a
     switch-on;
   - (b) up to the moment the purge actually starts.

   **Answered 2026-10-25: (a).** Switching on cancels up to the named day; after it, the erasure completes and spares
   what was made after the switch-on (decision 4).

   **Recommendation: (a).** The day members were told is the rule, and nobody depends on the hourly tick. Under (b),
   "switched on at 10:05 when the day began at 10:00" sometimes saves everything and sometimes saves nothing.
4. **An instance-level switch: does each tenant get its own window?** **Answered 2026-10-25: yes**, a tenant already
   off keeps its earlier day, and every member is mailed that only the operator can switch it on again (decisions 1
   and 5).

   **Recommendation: yes.** Each tenant gets a row from the moment of the instance switch, and a tenant that was already
   off keeps its earlier day. Every member of each tenant is mailed, and the text names the operator as the one who
   switched it and the only one who can switch it back on. The Server Owner is told by the `whenOff` text of admin-web's confirmation and gets no per-tenant view.
5. **Personal spaces too?** **Answered 2026-10-25: yes** (decision 3).

   **Recommendation: yes.** A switch-off covers the whole tenant, a member's personal space included. That is what the read-only switch already does, and what a tenant deletion erases after the same 30 days.
   The alternative, keeping personal-space content, leaves exactly the "kept for good" data #539 exists to end.
6. **The legal texts, and what stays open.** **Answered 2026-10-25: as recommended.** The privacy notice gains the
   storage-period line and its `mail` passage is corrected; the operator section stays; the three blockers below are
   ADR 0093 § Legal basis, Still open 12 (i), 13 (ii) and 14 (iii), met while veyq is not offered as a service
   (decision 7).
   - **Recommendation:** add the privacy notice line of decision 7 (GDPR Art. 13(2)(a): the storage period or its
     criteria) and correct its `mail` passage. Leave the operator section of the terms unchanged.
   - The following are named as **legal blockers**, under ADR 0093's treatment: taken as met while veyq is not offered
     as a service, and answered by counsel before it is.
     - **(i) Does the erasure need a basis in the terms?** That is, does erasing content members created, on the
       decision of the tenant owner or the operator after a notice period, need such a basis? If so, is it the
       operator section's (veyq's fixed text) or the space's own terms? It is connected to ADR 0093 Still open 6:
       who is the controller.
     - **(ii) Is the notice adequate?** Is a banner and a best-effort mail, with no mail at all where the instance has
       none, adequate notice for that erasure?
     - **(iii) Is a switch-off a moderation decision?** My reading: a switch-off is not content moderation in the
       DSA's sense (Art. 3(t)), because it does not act on content for being illegal or against the terms. If so, it
       owes no statement of reasons under Art. 17. I remember this reading and have not had it checked.

   GDPR Art. 5(1)(e) supports the erasure itself. Art. 17 is met by the ledger. Art. 20 is met by the download the
   window keeps.

## Retrospective

_Filled by the implementer._
