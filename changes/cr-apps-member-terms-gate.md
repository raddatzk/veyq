---
id: cr-apps-member-terms-gate
title: The apps hold a member at the tenant's terms gate and show the tenant's terms in Settings — the web's rule in the Kotlin core, each platform drawing it
status: in-progress
satisfies: ["uc.account.accept-terms"]
adrs: ["0035", "0161", "0123", "0122", "0156"]
invariants:
  - "the gate's rule (terms set and the current pair not accepted; fail-open on a read that fails) and the accept's outcomes are the Kotlin core's alone; no platform decides when the gate stands"
  - "the gate is decided before the shell is shown, and while it stands no platform draws the shell; the offer to stay signed in waits behind it"
  - "the terms are read and accepted through the client generated from contracts/api/tenants.openapi.yaml, never a hand-written DTO"
  - "every word is the shared catalogue's, EN and DE"
---

## Goal

Close the apps' gap against the web for a tenant's terms (owner 2026-11-26, approved): a member using only an app is held
at the tenant's terms gate when the terms changed, reads them with the operator section, agrees or signs out — as on the
web ([uc.account.accept-terms](../docs/features/account/use-cases/uc.account.accept-terms.md)) — and reads the space's
terms in Settings at any time. Editing the terms stays the owner's, on the web.

## Context & constraints

- The web: `router.ts`' gate over `activeSpace.termsStale()`, `TermsAcceptView.vue` (accept or sign out; a `409
  terms_revision_changed` re-reads and re-presents), `TenantTermsPanel.vue` (member-readable, the operator section below),
  `OperatorSectionView.vue` (the record's pointer with its privacy-notice link). API: `getTenantTerms`,
  `acceptTenantTerms` (`tenants.openapi.yaml`).
- ADR 0161: the logic in the core, each platform draws its own UI; ADR 0035 decisions 2 and 6.

## Phases

1. Core: `session/MemberTerms` (read, accept, Settings' page over the generated client), `SessionModel`'s gate
   (`Gate.Signed.terms`, `acceptTerms`), `SettingsSection.TERMS`, the words (EN+DE, web twins), the bridge's calls.
2. Platforms: Android (phone and TV), Apple (iOS, iPadOS, macOS, tvOS), Linux, Windows.
3. Docs: ADR 0035 § The apps' gate, the use case, native-apps.md, the Use pages EN+DE; this CR deleted.

## Decision log

- **The gate rides `Gate.Signed`, not a gate of its own.** Every platform closes its tenant on any gate but `Signed`
  (src/mobile/CLAUDE.md), and the web keeps the scope entered beneath its `/terms` route; a field like the offer keeps
  the tenant open and the platforms draw the gate in place of the shell.
- **Decided before the shell shows.** `openTenant` reads the terms before it shows `Signed`, so no list is drawn ahead of
  the gate (the web bounces after the shell mounted; the apps can do better at no cost).
- **The offer waits behind the terms** (`Signed.offer` null while the gate stands): one thing asked at a time, the
  terms first, as they gate the shell the offer sits over.
- **Fail-open, as the web's** (`refreshTenantTerms`): a read that fails shows no gate; legal reading in ADR 0035.
- **Re-checked at each open of a tenant** (start, unlock, switch, sign-in, a followed account); the web re-checks at
  each scope entry. An app left open sees a new revision at its next open of that tenant.
- **A moved pair accepted elsewhere meanwhile lets the member in** (the `409`'s re-read says `accepted`).
- **The text in the app's language**: `SessionModel` takes the platform's language (Android's per-app locale, Apple's
  bundle localization, the bridge's `Config.language`), the core picks, the other language where one is empty.
- **The object is `MemberTerms`**, not `TenantTerms`: the generated model of that name made Swift's `TenantTerms_`.
- **Settings' section `TERMS`** after the personal sections, before Sign out — the web lists it in its tenant category
  after them (`PlansAreTheWebsTest`). Read-only for everyone, the note saying the owner edits them in the browser.
- **A TV draws the gate too** (Android TV through the phone's composable, tvOS its own view); the privacy notice as a
  QR code and address on tvOS, as its report does. Its *Sign out* is the TV's *Remove from this TV* (`signOut`), the one
  sign-out a TV has. No Settings section on a TV page.
- **The bridge's two calls go through the sign-in entries' dispatch** (the main one is at the JVM's method-size limit).
