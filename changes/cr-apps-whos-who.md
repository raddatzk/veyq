---
id: cr-apps-whos-who
title: The apps show a space's Who's who — the web's overview in the Kotlin core, each platform drawing it in Settings
status: in-progress
satisfies: ["uc.spaces.enforce-verified-identities"]
adrs: ["0111", "0148", "0161", "0155", "0159"]
invariants:
  - "who is listed, the one state each shows, whose verification counts, new, rotated, replaced and the order are the Kotlin core's alone; no platform decides a state"
  - "an anomaly never reads as verified, whatever rows the server serves for it"
  - "a member whose check failed for a request shows no state, never a false anomaly; a pin store this device refuses is said, never read as nobody"
  - "everything is read through the client generated from contracts/api, never a hand-written DTO; nothing new reaches the server"
  - "every word is the shared catalogue's, EN and DE, paired with the web's where the web says the same"
---

## Goal

Close the apps' gap against the web for *Who's who* (inbox #967, owner 2026-11-26, approved): a member using only an app
sees, for the space open, every other member with the one state their key has there — can't confirm, verified (by whom),
not verified —, *New* while first seen under two weeks ago, a new identity signed by the previous key while under two
weeks old, and the note when an invitation replaced a key seen before; a row opens the person's profile, where the
compare lives ([uc.spaces.enforce-verified-identities](../docs/features/spaces/use-cases/uc.spaces.enforce-verified-identities.md)
main flow 5). As on the web: Settings → the space's sections → Who's who.

## Context & constraints

- The web: `features/account/peopleOverview.ts` (`loadPeopleOverview`, `orderPeople`), `features/spaces/PeopleOverviewPanel.vue`,
  the tenant section `tenant-people` in `features/settings/sections.ts` (after Members, before Terms).
- The core: `session/PersonTrust` (the person page's state, `VerificationCount`), `session/Anchoring`, `TenantView`.
- ADR 0161 (the logic the core's), ADR 0111 decision 15, ADR 0148 decision 3, ADR 0155 decision 11.

## Phases

1. Core: `PersonTrust.whosWho()` and `session/WhosWho` (the rule as a pure assembly, tested), `SettingsSection.WHOS_WHO`,
   the words (EN+DE, web twins), the bridge's call. Live case over a throwaway.
2. Platforms: Android, Apple (iOS, iPadOS, macOS), Linux, Windows.
3. Docs: ADR 0148 decision 3's section, the use case, native-apps.md, the Use pages EN+DE; inbox #967 deleted; this CR deleted.

## Decision log

- **The overview is `PersonTrust.whosWho()`, the rule a pure `WhosWho.assemble`.** The reads (members anew, each other
  member's anchor check, the pins once, the verifications once, the chains for a rotation's date) sit beside the person
  page's in `PersonTrust`, which already holds `view()` and `verifiedBy()`; who is listed, each state, *new*, rotated,
  replaced and the order are one function over plain data, so `WhosWhoTest` holds them without a played server (the web
  tests them through its fakes). Live, `LiveAccountFlowsTest.whosWhoListsTheOtherMemberAsTheirPageShowsThem`.
- **The core says the words, the platforms join names.** A row carries its badge (`stateWord`), its note, `subtitle`
  (`@username`, the web's list; not named `handle` — the bridge's secret-free schema refuses a field of that name) and
  `compare`; the page's one line in place of a list is `said` with `retry`. The verifiers' names are joined "you" first,
  as every app's person page already joins them (the web joins in the rows' order) — kept as the apps' page does it.
- **A row and its *Compare safety numbers* open the person's profile**, as the web's link does (the compare lives
  there). On the phones and the Mac Settings closes first (on Android and iOS Settings stands in place of the shell; on
  the Mac it is its own window): the profile is the shell's, so its back returns to the shell, not to Settings — the
  apps' Settings is no page of the shell's history. Reopening Who's who is two taps; the list re-reads at each open.
- **Settings' section `WHOS_WHO` (`tenant-people`) after the personal sections, before the terms** — the web's tenant
  category's order, held by `PlansAreTheWebsTest.theTenantSectionsAreTheWebsInTheirOrder`. Shown for every member, as
  on the web (no role changes it). Each platform opens a page of its own over Settings: Android a full-screen dialog as
  Terms, iOS a pushed list in Settings' navigation stack, the Mac a sheet, Linux a subpage of the preferences dialog,
  Windows a `ContentDialog` as Terms.
- **Cost, against the web's:** the core anchors members one after another under the anchoring lock, as the person page
  does, and each first sight writes its pin at once — the web's `PinPass` (inbox #710/#741: one pin-head read and one
  write per pass) has no port; the pins are read once (`VeyqAccount.pinsOfAccount`) and the rotation chains one at a
  time. Correct, more requests on a cold large tenant; filed as inbox #1414.
- **No move of the web's TypeScript** (ADR 0155 decision 11): no *on demand* row covers the overview; the web keeps
  `peopleOverview.ts` and the apps get the core's port.
- **A refused pin store says the web's `verify.trust*` words, without the trust reset's link**: the trust reset is not
  in the apps yet (native-apps' list); the words stand alone.
- **No Who's who on a TV** (ADR 0159/0160): a TV's settings are its own few, it shows no safety number and compares
  nobody; a list whose one action opens a compare it does not offer is no viewing. Nothing drawn on Android TV or tvOS.
