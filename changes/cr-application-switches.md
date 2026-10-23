---
id: cr-application-switches
title: Every application is an entry of the capability register, and off it is read-only
status: planned
satisfies: ["uc.platform.switch-application-off"]
adrs: ["0079", "0088", "0109", "0093"]
invariants:
  - core never calls an application and names none; an application's kinds and operations reach the switch through a core seam
  - off, nothing of the application is written in the tenant; every read, download, delete to the trash and restore stays
  - a rotation's re-key, an erasure and a moderation measure are never refused by an application switch
  - a tenant only narrows what the instance allows (the AND along the path and across both levels)
  - every text follows docs/crosscutting/ui-web-styleguide.md § Copy
---

## Goal

Inbox #532 (owner 2026-10-23): [ADR 0079](../docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)
decision 8 promised that the Server Owner and a tenant owner can switch an application off; nothing built it. This CR
gives `memories` and `depot` an entry each in the capability register and makes an application that is off
**read-only** — every write of it refused, every read, download and removal kept
([uc.platform.switch-application-off](../docs/features/platform/use-cases/uc.platform.switch-application-off.md),
[ADR 0088](../docs/decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) decision 5).
`cr-expenses-ledger` adds `expenses` to the same seam, so this CR lands before it.

## Context & constraints

- **Measured at refine (worktree `expenses-use-cases-and-cr-d2c6d0` at `e6d0a659`, = `main`):**
  `contracts/platform/capabilities.json` holds 10 entries and none is an application's own; the one kind gated by a
  switch is `DEPOT_DROPBOX` (`NodeKindRules.capabilityToCreate`, asked by `NodesHandler.create` and `ShareNodesHandler`,
  `grep -rn "capabilityToCreate\|isActiveInScope" src/backend --include='*.kt' | grep -v /test/`); the share switches are
  asked in `SharingHandler` (create, role change, reactivate, reissue). Application write operations (non-GET):
  Memories 14 (`completeMedia`, `createBucket`, `renameBucket`, `deleteBucket`, `moveBucketMedia`, `updateMediaMeta`,
  `deleteMedia`, `restoreMedia`, `purgeMedia`, `putItemFavorites`, `setMemoryBanner`, `removeMemoryBanner`,
  `completeShareMedia`, `deleteShareMedia`), Depot 3 (`completeFile`, `completeShareFile`, `ensureShareDropFolder`).
- **The switching screens are register-driven** (admin-web's Server → Capabilities, the tenant owner's in web): an
  entry's title, purpose and `whenOff` texts come from the register, so a new entry needs no new screen.
- **`scripts/validate_capabilities.py` refuses an entry whose `serves` names a planned use-case**, so the two entries
  and the use-case's coverage flip land in one merge (phase 1's red is named, phase 3 clears it).
- **Re-key:** if `cr-scope-rotation-rekeys-nodes` has landed, its `rekey` operations and each application's re-key stage
  are exempt (E1); if not, the exemption is a line the re-key CR adds, and this CR says so in its phase 2 report.

## Decisions this CR takes

1. **The seam.** Core asks each application, through a bean it already resolves per kind (`NodeKindRules`), which
   application a kind belongs to; `NodeKindRules.capabilityToCreate` becomes the more general `applicationOf(kind)`,
   and the drop box's own switch stays its leaf (`depot.drop-box` is not this CR's; `core.public-shares.drop-box` stays
   where it is). One core helper, `ApplicationSwitch.requireWritable(application, scopeId)`, answers
   `422 capability_disabled`; core's node writes ask it by the node's kind, each application's write handlers ask it by
   name.
2. **What is a write** is every non-GET operation of an application's spec and every core write whose target is a node
   of the application's kinds: create (copy and import are creates), update (rename, move, meta), cover, blob upload
   start and completion for an owner node of the kinds, a share rooted on one created, reactivated, re-issued or given
   a write role, and every guest write. **Exempt, each by name and reason:** delete to the trash and restore (core's
   and Memories' `deleteMedia`, `restoreMedia`, `deleteShareMedia`, `deleteBucket`) — a restored node is read-only like
   the rest —, the purge (`purgeMedia`), a member's own item marks (`putItemFavorites`: the member's blob, which a
   Master-Key rotation re-tags through the same write, ADR 0068 § 9), the re-key, erasures, moderation measures.
3. **The completeness proof is a sweep, not a list.** A slice test derives every non-GET operation from each
   application's generated API package (as `OperatorBlindProbeTest` does) and asserts each answers
   `422 capability_disabled` with its application off, or is in the exemption list with its reason; a new operation
   nobody classified is red.
4. **The client** reads `hasCapability('memories' | 'depot')` in the host, which fills an `Access` boolean the rights
   functions read (`src/web/CLAUDE.md`: no rights function reads `hasCapability` inline); a kind's `createEntry` is not
   offered; the node's page shows the one line below.
5. **Levels:** `instance` and `tenant` for both entries.

## Inventory

| Area | What changes | Locator |
|---|---|---|
| Register | `memories`, `depot` entries (EN/DE title, purpose, whenOff) | `python3 -c "import json;print(list(json.load(open('contracts/platform/capabilities.json'))['capabilities']))"` |
| Kind seam | `capabilityToCreate` → `applicationOf` | `grep -rn "capabilityToCreate" src/backend --include='*.kt'` |
| Core node writes | create, update, cover, copy/import (creates) | `grep -n "fun \(create\|update\|setCover\|removeCover\)" src/backend/core/src/main/kotlin/me/raddatz/veyq/core/content/NodesHandler.kt src/backend/core/src/main/kotlin/me/raddatz/veyq/core/sharing/ShareNodesHandler.kt` |
| Blob writes | upload start and completion by owner kind | `grep -rn "fun start\|UploadCompletions" src/backend/core/src/main/kotlin/me/raddatz/veyq/core/blob --include='*.kt'` |
| Share writes | the four sites that ask a share switch | `grep -n "isActiveInScope" src/backend/core/src/main/kotlin/me/raddatz/veyq/core/sharing/SharingHandler.kt` |
| Application writes | every non-GET operation | `python3 -c "import yaml,glob;[print(f,[o.get('operationId') for p in yaml.safe_load(open(f)).get('paths',{}).values() for m,o in p.items() if m in('post','put','patch','delete')]) for f in glob.glob('contracts/api/*/*.yaml')]"` |
| Web | capability ids, `Access`, rights, create entries | `grep -n "MEMORIES_\|DEPOT_\|export const" src/web/src/features/platform/capabilities.ts`; `git grep -n "depotRights(\|memoryRights(\|sourceRights(" -- src/web/src ':!*.test.ts'` |
| Existing tests that drive writes with a switch | a fixture with an application off | `grep -rln "capabilit" src/backend/*/src/test src/backend/apps/*/src/test src/web/src --include='*.kt' --include='*.ts'` |
| Site | the switch pages, Applications | `site/src/content/docs/run/capabilities.mdx`, `site/src/content/docs/space-owners/capabilities.mdx` (EN+DE), `site/src/content/docs/applications/index.mdx` (EN+DE) |
| Docs | ADR 0088's entry tree; § Built today | `grep -n "Built today\|^  \*" docs/decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md` |

## Texts

| Key | EN | DE |
|---|---|---|
| register `memories.whenOff` | Nothing new can be added to Memories or changed in them. Everything there can still be opened and downloaded. | In Erinnerungen kann nichts Neues dazukommen und nichts geändert werden. Alles darin lässt sich weiter öffnen und herunterladen. |
| register `depot.whenOff` | No files can be added or changed. Everything there can still be opened and downloaded. | Dateien können weder dazukommen noch geändert werden. Alles lässt sich weiter öffnen und herunterladen. |
| web `applications.switchedOff` | {application} is switched off here. You can still open and download what’s here. | {application} ist hier abgeschaltet. Was da ist, kannst du weiter öffnen und herunterladen. |

The titles and purposes are the implementer's, to the rule (§ Copy); the application names are the catalog's.

## Phases

1. **Contracts.** Record `./gradlew check` and `npm run check` exit codes on `main`, and the guard baseline on a detached
   worktree of `HEAD`. The two entries (decision 5, texts above), `serves: [uc.platform.switch-application-off]`;
   `422 capability_disabled` on every write operation of the inventory that does not yet declare it. **Done when:**
   `npm run generate:api` runs; the contracts guards green except `validate_capabilities.py`, whose red names only the
   two entries and the planned use-case (cleared in phase 3).
2. **Backend.** The seam and the helper (decision 1); every write of decision 2 asks it; the sweep (decision 3).
   **Done when:** `./gradlew check` green; the sweep green and bitten (remove the check from one Memories handler → red
   naming it; restore → green); slice tests, each negative with a positive control on the same predicate: with
   `memories` off a Memory's rename, a medium's completion and a `READ_WRITE` guest's upload answer
   `422 capability_disabled` (control: with it on they land), a Memory reads, downloads and goes to the trash and back,
   a rotation's re-key lands, and a heart on a medium is taken; with `memories` off for the instance and on for a tenant it is off; with `depot` off a
   file's completion is refused and its download presigns.
3. **Web, site, docs, coverage.** Decision 4; the line on the node's page; the texts in EN and DE. Site: the two
   capability pages and Applications (EN+DE). Docs: ADR 0088's entry tree and § Built today, ADR 0079 decision 8's *not
   built yet* goes, `docs/apps/README.md`'s rule. Coverage of `uc.platform.switch-application-off` from `planned` to
   refs, in the same merge as phase 1's entries. **Done when:** `npm run check`, admin-web's and `site`'s green; every
   `scripts/validate_*.py` exits `0`; a **browser pass** on the dev instance: the tenant owner switches Memories off;
   a member's create menu offers no Memory, an existing Memory opens with no upload, edit or share and with the line,
   downloads, and goes to the trash and back; a `READ_WRITE` link guest sees it read-only; switched on, the upload
   works; the Server Owner's console shows both entries with their texts.

## Definition of done

Both entries switch for the instance and the tenant; off, every write of the application is refused and the sweep
proves none is left out; reads, downloads and removals stay; the site says so in EN and DE; inbox #532 deleted.

## Open questions (owner)

None open. Answered (owner 2026-10-23): a delete to the trash and its restore stay, and a restored node is read-only
like every node of the application; a member's own item marks (`putItemFavorites`) are taken.

## Retrospective

_Filled by the implementer._
