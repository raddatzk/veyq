---
id: cr-setup-asks-for-applications
title: First-run setup asks which applications the instance offers; an application off where nothing of it exists is quiet
status: done
satisfies: ["uc.admin.setup-server", "uc.platform.switch-application-off", "uc.platform.erase-switched-off-application"]
adrs: ["0088", "0079", "0065", "0093"]
invariants:
  - "operator-blind: the setup body gains only application ids (instance configuration), never member data; the new pre-auth read serves catalog words identical on every instance of a build"
  - "one source of truth: an application left out at setup is the instance switch the console writes (veyq_capability_switch), never a preset or a second flag (ADR 0088 decision 14's lesson)"
  - "the window, its banner and its mails exist only where the tenant holds a node of the application, trashed ones included (ADR 0088 decision 5 as amended 2026-11-25); nobody's content is ever erased without its day"
  - "the setup post stays all-or-nothing: an invalid applicationsOff stores no owner"
  - "the catalog's words are served, never copied into a client (ADR 0088 decision 13)"
---

## Goal

When a Server Owner sets up a fresh instance in the admin console, after the Emergency Kit and the operator-duties
note and before the owner is posted, the console asks once which applications this server offers (Memories, Depot,
Expenses — every application root of the register), all ticked by default. What is unticked is switched off for the
instance in the same transaction that creates the owner. For that to be harmless, "off" must stay quiet where there is
nothing to save: today every tenant created while an application is off gets an erasure window, a banner on every page
and, after each window, a fresh one with a new notice mail — forever, for an application that never held anything.
After this CR a tenant gets a window, a banner and mails only while it holds a node of the application.

## Context & constraints

The refined state, already written:

- [ADR 0088](../docs/decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) decision 5
  (the paragraph *The window, its banner and its mails exist only where there is something to erase*) and decision 12
  (*At first-run setup*). Its `Built:` line names this CR; the last phase removes that clause.
- [uc.admin.setup-server](../docs/features/admin/use-cases/uc.admin.setup-server.md) step 5, step 6, A1, E2, the sequence.
- [uc.platform.switch-application-off](../docs/features/platform/use-cases/uc.platform.switch-application-off.md) step 2.
- [uc.platform.erase-switched-off-application](../docs/features/platform/use-cases/uc.platform.erase-switched-off-application.md)
  preconditions, steps 1 and 6, A3, A4.
- [en.platform.capability-register](../docs/features/platform/enablers/en.platform.capability-register.md) (the row rule, the setup paragraph).
- Contracts: `contracts/api/setup.openapi.yaml` (`createServerOwner`, `CreateServerOwnerRequest`),
  `contracts/platform/capabilities.json` (the three application roots' `whenOff`).
- Admin channel: [ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md), [uc.admin.admin-channel](../docs/features/admin/use-cases/uc.admin.admin-channel.md).

## Decisions

1. **Where the question sits.** admin-web's `SetupView`, a step after the operator-duties note (step 4) and before the
   post. The account exists only in the browser until the post (uc.admin.setup-server E1), so the selection rides the
   same `POST /api/setup/owner` and lands in its transaction; no session is needed and no "was asked" flag exists.
   *Rejected:* a one-time card after the first login — it needs a persisted "asked" flag and adds nothing.
2. **The words before sign-in.** New `GET /api/setup/applications` (`x-veyq-channel: admin`, no auth, like
   `createServerOwner`): the register's application roots in catalog order, each `{ id, title, purpose }` with `title`
   and `purpose` as `{ en, de }` exactly as `listInstanceCapabilities` serves them (reuse its schema pieces, no `whenOff`
   — nothing exists yet, so the effect on existing data is noise here). It answers `409 setup_already_complete` once
   setup is done, as the post does. *Rejected:* adding the words to `GET /api/setup` (served on the public origin to
   every client on every load); a copy in admin-web (decision 13).
3. **The body.** `CreateServerOwnerRequest.applicationsOff`: an optional array of ids, `maxItems: 8` (the contract
   cannot know how many roots a build carries; the server checks each id). Absent or empty = everything on (today's behaviour; e2e bootstrap scripts send nothing and stay green).
   Each id must be an application root of the register (a one-segment entry the instance can switch); a child entry,
   a core entry, an unknown id or a duplicate → `400 invalid_request` and nothing is stored, the owner included.
   The server does **not** refuse "every application out": the console prevents it (Continue disabled with none
   ticked), as a UX guard only — the console's switches allow it later.
4. **The write.** `SetupResource` switches each id off through the same store the console's `setInstanceCapability`
   uses (`CapabilitySwitchStore`), inside the owner's transaction. No tenant exists yet, so no erasure row arises —
   and with decision 5 none would anyway.
5. **Quiet off — the row rule.** `ApplicationErasureStore.reconcile` makes (or, at a window's end, renews) a row only
   while the tenant holds a node whose kind `NodeKindRules.applicationOf` names the application, in any of the
   tenant's scopes, **trashed ones included** (a trashed node can be restored while off, so it must count). The writers
   of a row's existence are exactly those its KDoc lists; each goes through the rule:
   - the tenant switch (`TenantCapabilitySwitchStore.set`) and the instance switch (`CapabilitySwitchStore.set`): off
     and nothing held → no row;
   - a tenant's creation (`TenantStore.createPendingTenant`, today `reconcile(…, noticeOwed = false)`): a new tenant
     holds nothing → no row. `noticeOwed` then has no caller with `false`; remove the parameter if so;
   - the purge's `openMissing`: opens a row only where something is held;
   - the purge's `end`: still off **and** something still held (a node made after a switch-on, A2) → a fresh window as
     today; otherwise the row goes.
   An on application is unchanged (a row not yet due goes; a due row stays until the purge is done). The "held" check
   runs under the same locks the KDoc names, so a switch and a write cannot interleave; writes are refused while off,
   so nothing can appear afterwards that would need a row. Core still names no application.
6. **The banner.** `SpaceView`'s `applicationOffBanners` shows a banner only for an application with an erasure day.
   One off without a day is simply absent (its kinds are already missing from the create menu). The day-less text
   `applications.switchedOff` then has no reader: remove it from `src/web/src/i18n/index.ts` (EN and DE) and from the
   golden snapshot `folder-memories-off.txt` the way the golden test regenerates it. The tenant owner's
   `TenantCapabilitiesPanel` keeps showing a switch's state; with no row it shows no day, which is true.
7. **The catalog's words.** The three application roots' `whenOff` (`memories`, `depot`, `expenses`, EN and DE) say the
   two cases. Template for Memories, the other two in the same shape and in their own nouns:
   - EN: "Nothing new can be added to Memories or changed in them. Where Memories already exist, everything there can
     still be opened and downloaded until the day shown at the top of veyq; then it is erased, unless Memories is
     switched on again before. Where there are none, Memories is simply not offered."
   - DE: "In Erinnerungen kann nichts Neues dazukommen und nichts geändert werden. Wo es schon Erinnerungen gibt, lässt
     sich alles darin bis zu dem Tag öffnen und herunterladen, den veyq oben anzeigt; dann wird es gelöscht, wenn
     Erinnerungen nicht vorher wieder eingeschaltet wird. Wo es keine gibt, wird Erinnerungen einfach nicht angeboten."
   The `description` (catalog-only) gains one sentence on the row rule.
8. **The setup step's own words** (admin-web i18n, EN and DE, ADR 0086's word rules — *application*, *Anwendung*): a
   heading ("Which applications does this server offer?" / "Welche Anwendungen bietet dieser Server an?"), one
   checkbox per application with its served title and purpose, and one line: each can be switched on or off later
   under Server → Capabilities (use the console's own label for that page, whatever it is in i18n). Continue disabled
   with none ticked.

## Inventory (run the locators; they are the list)

| Surface | Locator |
|---|---|
| Setup contract + its callers | `git grep -n "createServerOwner\|CreateServerOwnerRequest\|setup/owner" -- contracts src tests` |
| Admin-channel posture / rate limits / operator-blind probes a new endpoint must be classified in | `git grep -ln "setup/owner" -- src/backend/src/test src/backend/core/src/test` |
| Row writers | `git grep -n "reconcile(\|openMissing\|noticeOwed" -- src/backend` |
| Banner | `git grep -n "applicationOffBanners\|applications.switchedOff\b\|switchedOffUntil" -- src/web tests/e2e` |
| Tests that assume a row for a tenant born while off, or a fresh window after an empty end | `git grep -ln "createPendingTenant\|ApplicationErasure\|erasureDay\|application-erasure" -- src/backend/core/src/test src/backend/src/test src/web/src tests/e2e` |
| Site pages | `git grep -ln "uc.admin.setup-server\|uc.platform.switch-application-off\|uc.platform.erase-switched-off-application\|en.platform.capability-register" -- site/src/content/docs` (measured at refine: `run/first-run.mdx`, `run/capabilities.mdx`, `run/storage-and-email.md`, `applications/index.mdx` EN+DE, `space-owners/capabilities.mdx` EN+DE) |

## Phases

- [x] 1 contracts — `setup.openapi.yaml`: `GET /api/setup/applications` (decision 2) and `applicationsOff` with its
      `400` case in `createServerOwner`'s description (decision 3); `capabilities.json` words (decision 7).
      · Done when: the contract guards in the trust chain and `./gradlew check`'s contract generation are green, and
      `pnpm run generate:api` (or the surface's equivalent) yields the new types in `src/admin-web/`.
- [x] 2 backend — decisions 2–5. Tests: the setup slice (ids switched off, the manifest of `GET /api/setup` lacks
      them; each `400` case stores no owner — anchored on `invalid_request`, each with a positive control that the
      same body without the bad id succeeds); `GET /api/setup/applications` serves the roots, answers 409 after setup,
      is admin-channel only; the row rule — for each writer a case where the tenant holds a node (row made, as today)
      **and** one where it holds none (no row), plus a trashed-only node counting as held, and `end` with nothing left
      deleting the row while still off. · Done when: `./gradlew check` in `src/backend/` green.
- [x] 3 web + admin-web — decision 6 in `src/web/`; decisions 1, 8 in `src/admin-web/` (`SetupView`, `setupServer.ts`
      carries `applicationsOff`). · Done when: `pnpm run check` green in both, and a **browser pass**: a fresh instance
      set up through the console with Depot unticked, then a tenant created and opened in `src/web/` — no Depot banner,
      no Depot kind in the create menu; the console's Capabilities page shows Depot off. Screenshot of the setup step as
      the proof.
- [x] 4 docs, site, coverage — the site pages from the inventory (Use pages EN+DE together); `docs/coverage/` for the
      three `satisfies:` use cases (the new criteria: setup step + body + read; the row rule per writer; banner only
      with a day); ADR 0088's `Built:` clause for this CR removed. · Done when: the trust chain
      (`scripts/validate_*.py`, run with `/usr/bin/python3`) and `pnpm run check` in `site/` are green.

## Definition of done

All four phases' gates green on the branch; one squashed PR to `main` with a `## Release note` (Run area: the setup
asks which applications to offer; Use area: a switched-off application with nothing in it no longer shows a banner or
sends erasure mails). The CR is deleted in the same PR after its lessons moved where they bind.

## Decision log

- 2026-11-25 (refine, with the owner): question placed before the post rather than after login (decision 1) — the
  owner asked whether "before the admin is created" meant the tenant admin; it means the Server Owner's post, which
  happens after the kit.
- 2026-11-25 (refine): the quiet-off rule keyed on "the tenant holds a node of the application, trashed included"
  rather than "the tenant was born while off" — one rule covers setup, tenant creation and the purge's empty end.
- 2026-11-25 (implement, phase 1): the setup read's words are local schemas `SetupApplication` / `SetupApplicationText`
  in `setup.openapi.yaml`, the shape of `instance.openapi.yaml`'s `InstanceCapabilityText` — each core spec is generated
  on its own and `$ref`s nothing but `_common` (`validate_contracts_common.py`'s docstring), and a same-named copy would
  collide in core's one generated package; `tenants.openapi.yaml`'s `CapabilityText` is the precedent.
- 2026-11-25 (implement, phase 1): `applicationsOff`'s element pattern is the register id's dotted shape, not one
  segment — so an entry below an application or a core entry reaches the server's register check (decision 3), and the
  boundary refuses only what is no id at all.
- 2026-11-25 (implement, phase 2): a tenant's creation is no writer of a row any more — its loop, its instance lock and
  `reconcile`'s `noticeOwed` parameter are gone, since a new tenant has no scope and so holds nothing.
- 2026-11-25 (implement, phase 2): decision 5's "a switch and a write cannot interleave" does not hold: a write's gate
  reads the cached switch set, not under the tenant's row lock, so a node can commit after a switch-off found nothing
  held. Rather than lock every write, the purge's `openMissing` (every tick) opens that tenant's row from then — never a
  day earlier than members are told; `ApplicationErasureSliceTest.aMissingRowIsOpenedByTheTickFromNowNeverEarlier` pins
  it. Its first read narrows the candidates to tenants that hold something, without locks, so a tenant that holds
  nothing of an application off costs no lock per tick.
- 2026-11-25 (implement, phase 2): "held" is one query — the distinct node kinds in the tenant's scopes, trashed ones
  included, each asked through `NodeKindRules.applicationOf` (`ApplicationErasureStore.holds`); core names no kind.
- 2026-11-25 (implement, phase 2): a row that exists stays while the application is off even once its last node is gone
  (a member purged the trash): its day was told, and the purge's end lets it go then.
- 2026-11-25 (implement, phase 2): `:core`'s erasure tests get a test-scope stand-in, `CoreTestApplicationKinds` (one
  kind per application, placed by no rule, excluded by `CoreAloneSliceTest`'s profile like the other stand-ins), rather
  than moving the two classes to the root — core alone has no application kind, and a row now needs one.
- 2026-11-25 (implement, phase 2): `ApplicationErasureRaceSliceTest.aTenantCreatedOverlappingAnInstanceSwitchOffGetsItsRow`
  is deleted with the writer it raced; its coverage ref goes with it.
- 2026-11-25 (implement, phase 3): the setup step reads the applications when the owner continues from the duties
  note; a failed read says so with **Try again** and posts nothing (no silent "all on"). Beside the disabled button with
  none ticked, one line says to keep one ticked (`setup.applicationsAtLeastOne`). The "later" line composes the console's
  own labels `admin.server` → `capabilities.navTitle` (EN "Server → Capabilities", DE "Server → Funktionen").
- 2026-11-25 (implement, phase 3): lane L1's `application-erasure.spec.ts` step 5 now asserts no next day, banner or
  third notice once nothing of Depot is left (it asserted a fresh window); not run here — L1 runs at V1's end.

## Retrospective

- **What landed.** The setup asks once, before the post, which applications the server offers; what is unticked rides
  `applicationsOff` into the owner's transaction as the console's own instance switch. An erasure row, its banner and
  its mails exist only where the tenant holds a node of the application (trashed ones included), for every writer;
  tenant creation is no writer any more. The day-less banner and its text are gone.
- **Published docs touched:** Run — `run/first-run.mdx` (the new step 5), `run/capabilities.mdx` (absent where nothing
  exists), `run/storage-and-email.md` (who is mailed); Use, EN+DE together — `applications/index.mdx` and
  `space-owners/capabilities.mdx` (no banner, no day, no mail where the space holds nothing).
- **Decision 5's lock claim did not hold** ("a switch and a write cannot interleave"): write gates read the cached
  switch, not under the tenant lock. The purge's `openMissing` closes it from the next tick; the refine could have
  caught it by asking which lock each *write* path takes, not only each *row writer*.
- **`:core` alone cannot make a row any more.** The rule keys on an application's kind, and `:core`'s tests boot no
  application — every erasure slice test there went from "a row" to "no row" without a stand-in. A CR that keys a
  core rule on an application seam names in its inventory how core's own tests reach that seam
  (`CoreTestApplicationKinds`, the stand-in pattern `CoreAloneSliceTest` already excludes).
- **The e2e locator in the CR missed the setup scenario.** The inventory's `setup/owner` and banner locators did not
  find `tests/e2e/tests/setup/first-run.setup.ts`, which clicks "Create the account and sign in" right after the duties
  note — the label that moved one step on. The changes/README rule (grep `tests/e2e` for the visible labels of what
  moved) found it; the CR's own locators did not include it.
- **Two reds only the full `./gradlew check` showed:** the root's `OperatorBlindProbeTest` wanted the new pre-auth read
  classified (it sits with `getSetupStatus` / `createServerOwner` in the public exclusions), and the root's
  `ApplicationErasureSweepTest` still asserted the fresh window after a Memories-only erasure — the CR's locator listed
  the class, but the "fresh window after an empty end" assertion sat at the end of a content-sweep test and was missed
  by reading for row writers. Targeted `:core` runs were green throughout; the root's `:test` is where both live.
- **Bites (snapshot-and-restore, `:core` tests):** round A — `reconcile` without `holds`, the purge's end without
  `holds`, the setup handler without its id check and without the cache invalidation — turned exactly the eight new or
  changed negatives red, each by its own message; round B — `holds` ignoring trashed nodes — turned
  `aTrashedNodeOfTheApplicationCountsAsHeld` red. `openMissing`'s held pre-filter is not bitten on its own: it is a
  lock-saving pre-filter that `reconcile` repeats under the locks.
- **Machine.** The browser pass ran on an isolated stack of its own (scratchpad compose project `veyq-appsel`, MariaDB
  33306 and Garage 33900/33903, a worktree `quarkusDev` on 8096/8097, Vite on 8095/5185) in headless system Chrome.
  The dummy keystore that keeps the JFrog one away from the AWS SDK also makes the MariaDB driver offer a client
  certificate, which the server refuses (`unknown_ca`): the JDBC URL needs `?sslMode=disable` with it. A screenshot
  right after a checkbox click caught the box's 0.2 s transition; wait it out before a proof shot.
