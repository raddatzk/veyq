# UC: An application left switched off is erased

- **ID:** uc.platform.erase-switched-off-application
- **Status:** **planned** (`changes/cr-switched-off-application-is-erased.md`, inbox #539).
- **Primary actor:** Every **member** of a tenant where an application — Memories, Depot, Expenses — is switched off:
  the people whose content in it goes.
- **Secondary actor:** The **tenant owner** (for the tenant, [uc.admin.tenant-switch-capabilities](../../admin/use-cases/uc.admin.tenant-switch-capabilities.md))
  or the **Server Owner** (for the instance, [uc.admin.switch-capabilities](../../admin/use-cases/uc.admin.switch-capabilities.md)),
  who switched it off; the Server Owner also sets the window, `veyq.application.off.retention` (default `P30D`), as a
  configuration property like every other erasure window.
- **Trigger:** time. The application has been off for the tenant, at either level, for the window.
- **Goal:** Switching an application off does not keep its sealed data on the server for good (GDPR Art. 5(1)(e)).
  After a window the members were told about by its day, the application's content in the tenant is erased.
  Switched on again before that day, nothing is lost ([ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)
  decision 8, [ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)
  decision 5; owner 2026-10-24 and 2026-10-25).
- **Preconditions:** The application's own entry (`memories`, `depot`, `expenses`) is switched off for the tenant, or
  for the instance ([uc.platform.switch-application-off](uc.platform.switch-application-off.md)). A child entry
  (`memories.places`) erases nothing.
- **Postconditions (success):** On the day, every node of the application's kinds that was made before the day is
  erased in every scope of the tenant, with everything that hangs off it. The erasure is in the erasure ledger, its
  blobs are on the store reclaim, and every moderation hold keeps its copy. Core folders, scope keys and memberships
  stay.

## Main flow

1. **The day is set when the application goes off.** The server records, for the tenant and the application, when it
   went off and the day it will be erased: that moment plus the window. A second level switching it off keeps the day
   that was set, so a day members were told is never moved earlier.
2. **Every member is told the day.**
   - The banner at the top of veyq names the day ([uc.platform.switch-application-off](uc.platform.switch-application-off.md)
     step 2).
   - The tenant owner's Settings → Capabilities row shows *Erased on {day}* beside the entry.
   - Within one tick of the purge, the instance mail sends every member (`pendingConsent = false`) a notice: who
     switched it off, the day, and that what is there can be downloaded until then.
3. **Seven days before the day, every member is mailed a reminder.** A window of seven days or less has no reminder.
   A member who joins in the window sees the banner and gets the reminder.
4. **In the window, members save what they want to keep.** What exists opens and downloads as before, and the
   download is the export ([uc.platform.export-import](uc.platform.export-import.md)).
5. **On the day, the application's content is erased.** In every scope of the tenant (each member's personal space,
   every group, and a group in its grace), the purge selects every node of the application's kinds made before the day,
   trashed ones included. It hands them to the trash purge's erase:
   - each node's subtree goes, with its media, derivatives, covers, records, item marks and guest-write records;
   - its links are tombstoned;
   - one line per node goes into the erasure ledger;
   - the bytes go to the store reclaim, the one deleter of objects.

   Each scope is its own transaction.
6. **The tenant's record of the window goes when every scope is done.** If the application is still, or again, off for
   the tenant at that moment, a fresh window starts from then, and the notice is sent again.

## Alternate / exception flows

- **A1 — switched on before the day:** nothing is erased, the day is cancelled and the banner goes.
- **A2 — switched on after the day:** the erasure was committed on the day and completes. What members make after the
  switch-on stays, because only what was made before the day is erased.
- **A3 — switched off for the instance:**
  - every tenant gets its own window from that moment, and a tenant already off keeps its earlier day;
  - every member of each tenant is mailed;
  - the text names the operator as the one who switched it off, and as the only one who can switch it on again.

  Switched on for the instance, each window ends, except where the tenant's own switch still holds the application off.
  Two levels switching at once leave the record the final state implies.
- **A4 — a tenant created while the instance holds the application off:** it gets its own window from its creation. It
  has no members to mail yet, so no notice goes.
- **A5 — no instance mail:** nothing is mailed. The banner and the owner's panel carry the day, the switch is not
  refused, and the window does not grow.
- **A6 — a member who has not accepted the terms yet** (`pendingConsent`): gets no mail.
- **A7 — a link guest:** is not told; the link's resolve carries no tenant setting
  ([uc.platform.switch-application-off](uc.platform.switch-application-off.md) E6). The link serves what exists until
  the erasure tombstones it.
- **A8 — content under a moderation hold:** the hold's copy and its pinned blobs stay, because the erase never reads a
  hold and the reclaim skips a pinned key
  ([ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 4).
- **A9 — a restore from a backup taken before the day:** the erasure ledger re-erases each node with its current subtree
  before the instance serves ([ADR 0056](../../../decisions/0056-instance-durable-state-and-cold-logical-backup.md)
  decision 8).
- **A10 — a scope that fails:** it is logged and taken again at the next tick; a scope already erased finds nothing the
  second time. The record stays until every scope succeeded.

## Quality notes

- Operator-blind: nothing is decrypted to erase. The server learns nothing it does not already hold: the switch, the
  node kinds and the members' addresses.
- Core never names an application. It finds an application's nodes by asking each kind which application owns it
  (`NodeKindRules.applicationOf`).
- The window is the Server Owner's, and a tenant owner has no lever over it. The tenant owner already decides *whether*
  the application is off, and a second lever over *how soon* other members' content goes would need a floor someone
  must police (owner 2026-10-25). A tenant only narrows what the instance allows (ADR 0088 decision 2).
- Personal spaces are erased too: a switch-off covers the whole tenant, as the read-only switch and a tenant's deletion
  do (owner 2026-10-25).
- The day is the rule, not the hourly tick: switching on cancels up to the day the members were told, never up to when
  the purge happens to start (owner 2026-10-25).
- An erasure is the host's to announce, so its mails go through the instance mail, not the space's
  ([ADR 0097](../../../decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md) decision 3).
- The privacy notice names how long content is kept (GDPR Art. 13(2)(a)). Art. 17 is met by the ledger, and Art. 20 by
  the download the window keeps. Three questions stay with counsel: a basis in the terms, the notice's adequacy, and
  whether a switch-off is DSA moderation
  ([ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § Legal basis, Still open 12–14).

## User impact

A space that stops offering an application does not keep its members' content in it for good. Every member is told the
day by banner and, where the instance sends mail, by mail, and has until then to download what they want to keep.

## Related

- **Use-cases:** [uc.platform.switch-application-off](uc.platform.switch-application-off.md) (the window this ends),
  [uc.admin.switch-capabilities](../../admin/use-cases/uc.admin.switch-capabilities.md),
  [uc.admin.tenant-switch-capabilities](../../admin/use-cases/uc.admin.tenant-switch-capabilities.md),
  [uc.admin.delete-tenant](../../admin/use-cases/uc.admin.delete-tenant.md) (the same window, over a whole tenant).
- **Decisions:** [ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) decision 8,
  [ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) decision 5,
  [ADR 0056](../../../decisions/0056-instance-durable-state-and-cold-logical-backup.md) decision 8,
  [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 4 and § Still open, [ADR 0097](../../../decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md)
  decision 3.
- **On the docs site:** nothing yet; the pages that say nothing is erased change when it is built (the CR's phase 4).
