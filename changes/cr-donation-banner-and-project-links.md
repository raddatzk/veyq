---
id: cr-donation-banner-and-project-links
title: A one-time donation banner, the footer's project links, and the donation page
status: in-progress
satisfies: ["uc.platform.support-the-project", "uc.admin.operator-details"]
adrs: ["0122", "0087", "0121"]
invariants:
  - the account flag is server-visible operational metadata, never content, served only to the account holder
  - a share page calls no authenticated client for the banner; its dismissal never leaves the browser
  - nothing is loaded from a third party — not by the client, not by the website
  - no banner on the login, invite, join or recovery pages, nor in admin-web; one banner per page
---

## Goal

Owner decisions of 2026-11-08, recorded in [ADR 0122](../docs/decisions/0122-veyq-asks-once-for-a-donation-on-every-instance.md)
and [uc.platform.support-the-project](../docs/features/platform/use-cases/uc.platform.support-the-project.md): a
dismissible donation banner in the signed-in shell and on share pages, dismissed forever (browser; plus the account for
a member); the instance footer gains "Why is veyq secure?" and "Donate"; the donation page on veyq.app. The one way to
donate today is GitHub Sponsors (`https://github.com/sponsors/raddatzk`, owner 2026-11-08), as a plain link.

## Context & constraints

- The member flag follows `requireGroupAddConsent` (`git grep -n requireGroupAddConsent`): `MemberProfile` (required,
  `x-veyq-visibility: flag`) and `ProfileUpdateRequest` (nullable, omit = unchanged), a column on `veyq_user`,
  `AccountProfileHandler`, `AccountProfileResource`.
- A new migration, not a baseline edit: the baseline may still change before the release, but that recreates every
  existing database, dev ones included (`src/backend/CLAUDE.md` § Migrations); an `ALTER TABLE … ADD COLUMN … DEFAULT
  FALSE` keeps them.
- The footer is `core/operator/OperatorIdentification.vue`, mounted by `AppShell.vue`, `ShareView.vue`, `LoginView.vue`,
  `OperatorTermsView.vue` and `PrivacyNoticeView.vue` (`git grep -n "OperatorIdentification.vue" -- src/web/src`). The
  share page's own security link (`securityDocUrl`, `share.securityFooter`) moves into it.
- A bookmark's embedded share (`core/bookmark/BookmarkEmbed.vue`) draws `ShareGates`, not `ShareView`, so a banner in
  `ShareView` alone is one banner per page.
- Predicted reds: the contracts phase adds a required response field, so `AccountProfileResource.memberProfile`
  stops compiling and the web fixtures typed as `MemberProfile` (`git grep -n "requireGroupAddConsent: false" --
  src/web/src`) miss the field until the web phase.

## Phases

### Phase 1 — docs and the donation page
The use case, ADR 0122, its Topics row; `site/src/content/docs/donate.md` + `de/donate.md` (one marked list of ways to
donate), linked from the website footer and the Use sidebar. **Done when:** `pnpm run check` in `site/`, the guards.

### Phase 2 — contracts
`donationBannerDismissed` on `MemberProfile` and `ProfileUpdateRequest`. **Done when:** `validate_field_visibility.py`,
`validate_contracts_common.py`; the backend is red at `memberProfile(...)` until phase 3.

### Phase 3 — backend
Migration + pin line, `VeyqUser.donationBannerDismissed`, handler and resource, `DonationBannerSliceTest` (stored,
served, omitted = unchanged, account-global across tenants). **Done when:** `./gradlew check`.

### Phase 4 — web
`core/project/projectLinks.ts` (the two URLs), `core/project/donationDismissal.ts` (browser flag, guarded),
`core/project/DonationBanner.vue` (member: account flag OR browser; guest: browser only), mounted in `AppShell.vue`'s
workspace and `ShareView.vue`; the footer links in `OperatorIdentification.vue`; `share.securityFooter` and
`securityDocUrl` removed. Tests with bites. **Done when:** `pnpm run generate:api && pnpm run check`.

### Phase 5 — docs close
Coverage file, security ledgers (`security-model.md`, `site/…/security/what-the-server-sees.md`), the footer's Use
page line, this CR deleted.
