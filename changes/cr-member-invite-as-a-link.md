---
id: cr-member-invite-as-a-link
title: A tenant owner invites a member as a link that opens by itself, beside the mailed invite; unconfirmed accounts carry a banner
status: planned
satisfies: ["uc.spaces.add-member", "uc.spaces.manage-member-invites", "uc.account.confirm-email-address"]
adrs: ["0129", "0111", "0074", "0055"]
invariants:
  - "the server never receives a link invite's sealed part or its code, and never mails a link that carries a code (ADR 0129 decision 1, Rejected § the code in the server-mailed link)"
  - "the mailed invite's operation still answers no secret; only the link operation answers the token, to the owner who minted it"
  - "a tenant that cannot send refuses both ways before any mint (ADR 0129 decision 3)"
  - "a link redemption never marks an address confirmed (ADR 0129 decision 4)"
  - "the banner shows on every signed-in page while the account's address is unconfirmed and cannot be dismissed (ADR 0129 decision 5); no share or guest page shows it"
---

## Goal

Build [ADR 0129](../docs/decisions/0129-a-member-invite-is-mailed-or-handed-over-as-a-link.md): the invite dialog offers
**Share a link** (first, the default) beside **Invite by email** (today's flow). A link invite has no address; the
server mints it and answers the token, the owner's client builds `…/tenant/accept#<token>.<sealed>.<code>` and offers
it as an editable message to copy, to the system share sheet and to the owner's mail program. Whoever opens it joins
without typing a code, registering with an address they type or signing in with any account; a new account starts
unconfirmed, asks for its confirmation mail at once, and — like every unconfirmed account — carries a persistent
banner. A consumed invite's preview answers `410 invite_redeemed` so a taken link is noticed.

## Context & constraints

- ADR 0129 holds every decision and its rejected alternatives; ADR 0111 (placeholder member, seal, self-grant), ADR
  0074 (address control), ADR 0055 § 1 (mail is a precondition — still true for both ways).
- Measured 2026-11-12 on `main` (`2bbd25e33`), the facts the design leans on — **spot-check the first one before
  phase 2**:
  - `veyq_tenant_invite.owner_email` is `NOT NULL` (`core_baseline.sql`), and the address is the key of: supersede-on-mint
    (`TenantStore.mintMemberInvite`), the per-recipient rate bucket (`InviteMailRateLimiter`), `already_member` at mint
    (`TenantHandler.inviteMember`), the accept match (`TenantAcceptHandler` `NotInvitedOwner`), the new account's address
    and `confirmAddress` (`registerAndAcceptOnce`, `acceptOnce`), erasure (`AccountErasureHandler`, `AccountPurge`).
  - The preview answers 404 `invite_not_found` for an unknown token and 410 `invite_no_longer_valid` for every
    non-live row (`TenantAcceptHandler.isLive`); terminal rows are kept until `TenantInvitePurge` (retention P30D after
    expiry, TTL P7D).
  - `parseInviteFragment` (`src/packages/crypto/src/invite.ts`) splits on the first `.` and hands the rest to `sealed`;
    a third segment breaks it today. Base64url and Crockford carry no `.`.
  - `TenantJoinView.vue` strips the fragment with `history.replaceState` and never asks for an address (it takes
    `ownerEmail` from the preview for register, sign-in and the kit).
  - The client side of address confirmation exists: `features/account/email/emailConfirmation.ts`,
    `EmailAddressSection.vue`, `TenantMailSettingsPanel.vue`.

## Decisions (technical, settled here)

1. **Contract — two new operations on the tenants spec, the mailed ones unchanged in shape:**
   `createMemberInviteLink` (`POST /api/tenants/{tenantId}/member-invite-links`) and `reissueMemberInviteLink`
   (`POST …/member-invite-links/{inviteId}/reissue`). Their request is `MemberInviteRequest` without `email` and
   `sealedLink` (share the eight fields through `allOf` only if both generators name it cleanly — write one use in each
   language before committing, changes/README § Phases; else a schema of its own). Their answer carries `inviteId`,
   the token as `bootstrapSecret` (`InviteBootstrapSecret`) and `inviteExpiresAt`. Refusals as the mailed mint's, minus
   the address ones (`already_member` moves to accept for a link), plus `503 mail_unavailable` (ADR 0129 decision 3).
   Revoke stays the one operation for both. Classify both in `OperatorBlindProbeTest`.
2. **Changed shapes:** `PendingMemberInvite.email` and `InvitePreview.ownerEmail` become optional (absent ⇔ link
   invite; `hasAccount` absent too); `registerAndAcceptTenantInvite` gains an optional `email`, required for a link
   invite and refused for a mailed one (`422`, a code of its own); `previewTenantInvite` gains `410 invite_redeemed` for a
   consumed row. Update the operation texts that say "mail is the only channel" so they speak of the mailed operation.
3. **Storage:** `owner_email` nullable through a **new** migration (not a baseline edit — instances exist since V1);
   null means a link invite, and every reader above takes a branch for it: no supersede, tenant bucket only, no address
   match, the typed address for a new account, no `confirmAddress`, accept refuses `already_member` (`409`) for an
   account already in the tenant. Rate limit: a link mint or reissue counts in the per-tenant bucket only.
4. **Link format:** `parseInviteFragment` returns `{ token, sealed, code }` (code `null` when absent, normalised through
   `normalizeInviteCode`, an invalid one is `null` — the page then asks for it as today); a builder beside it writes the
   three segments. Only mode `code` links carry one. `src/packages/crypto` changes → the commit records
   `pnpm run mutation --force`'s exit code (src/web/CLAUDE.md).
5. **The dialog** (`InviteMemberDialog.vue`): a two-way switch, **Share a link** first. Link way: no input, one button;
   result view (shown once): a textarea with the message prefilled in the UI language — the space's name, the link, the
   expiry and "only once" — editable, and three actions over the textarea's text: copy, share
   (`navigator.share`, shown only where it exists) and open in mail program (`mailto:` with subject and body). One
   warning line: whoever has this link can join; give it only to the person you invite. Mail way: today's form and
   result. Copy uses the layer's components (`VeyqButton`, …), never new UI primitives.
6. **The pending list** (`TenantMembersPanel.vue`): a link invite's row reads "Link invitation" with its expiry;
   re-issue opens the same link result view; revoke unchanged.
7. **The join page** (`TenantJoinView.vue`): a code in the fragment skips the code prompt; a link invite's register
   form asks for the address (`VeyqInput`, the contract's mailbox rule) before password, and its sign-in asks for it
   too; `email_taken` on register points at signing in. After a new account joined through a link, the client asks
   for the confirmation mail once (`emailConfirmation.ts`); a failure there is not an error of the join. `410
   invite_redeemed` has its own message ("already used — tell whoever invited you").
8. **The banner:** core, in the app shell of the signed-in app, while the account's address is unconfirmed: names the
   address, "Send confirmation mail" (the existing request, then "sent"), `503 mail_unavailable` → the "an owner must
   set up mail" text; no close button. Not on share pages, guest pages, the join page or before unlock-free pages the
   shell does not draw. Its source of truth is the same read `EmailAddressSection.vue` uses.

## Inventory (locators — run, read as lists)

| What | Command |
|---|---|
| Backend readers of the invite address | `git grep -n -e ownerEmail -e owner_email -- src/backend` |
| Web/e2e readers of the invite address and preview | `git grep -n -e ownerEmail -e PendingMemberInvite -e 'invite\.email' -- src/web src/admin-web tests` |
| Fragment parser users | `git grep -n parseInviteFragment -- src tests` |
| Mailed-only wording in contract and code | `git grep -n -i -e "only channel" -e "copy-link" -e "no secret" -- contracts/api/tenants.openapi.yaml src/backend/core/src/main src/web/src/features/spaces` |
| Tests of invite/accept (incl. e2e) | `git grep -l -i -e memberInvite -e member-invite -e TenantJoinView -e acceptTenantInvite -- src tests` |
| Confirmation client + its surfaces | `git grep -ln -i -e emailConfirm -e confirmAddress -- src/web/src src/backend` |
| Site pages to move | `git grep -ln -e uc.spaces.add-member -e uc.spaces.manage-member-invites -e uc.account.confirm-email-address -e cc.mail -- site/src/content/docs` plus `git grep -ln -i -e "invite" -e "einlad" -- site/src/content/docs` |
| State docs to move | `uc.spaces.add-member`, `uc.spaces.manage-member-invites`, `uc.account.confirm-email-address`, `cc.mail` (table row, § refusals), `security-model` (operator holds every mailed link; the invite rows), `scopes-and-tenancy` ("by email, the only out-of-band channel"), their coverage yamls |

## Phases

**Phase 1 — contracts + crypto package.** Decisions 1, 2, 4. Done when: `pnpm run generate:api` (web, admin-web),
the contracts guards and `src/packages/crypto` `pnpm run check` green; `pnpm run mutation --force` exit code in the
commit message; the predicted reds named (web reads of `ownerEmail`/`email` as required; the backend `…Resource` gains
the new methods — state which tests are red, do not stub).

**Phase 2 — backend.** Decisions 1–3 and the preview's `invite_redeemed` for every consumed invite. Tests: link
mint answers a token and sends nothing; refused without sendable mail; tenant bucket only; several open link invites
coexist and a mailed mint does not supersede them; accept by any account, `already_member` at accept, register with
the typed address, no `confirmAddress` on either path while a mailed redemption still sets it; reissue and revoke of a
link invite; a consumed token's preview `410 invite_redeemed` with a revoked and an expired control on the same token
shape; a rotation re-grants an open link invite; erasure of the signer ends it. Every negative with a positive control
(docs/ai § Refinement checklist). Done when: `./gradlew check` green.

**Phase 3 — web.** Decisions 4–8, EN+DE in `src/i18n/index.ts`. Unit tests per decision, including: the dialog's
link result never calls the mailed operation; the message holds the link with the code; the join page with a code in
the fragment asks for none and strips it; link register sends the typed address and requests confirmation once;
`invite_redeemed` message; banner shown/hidden by the confirmation state, absent on a share page, no dismiss. An e2e
spec in `tests/e2e`: owner creates a link, a second browser context opens it, registers with a typed address, is a
member; opening the same link again shows "already used". Done when: `pnpm run check` (web) green, the e2e spec green
once, and a **browser pass** on the dev instance (`veyq.localhost`, Mailpit): create a link invite, open it in a
second profile, join, see the banner, send the confirmation from it and confirm through Mailpit, banner gone; the
mail way still sends and shows its code.

**Phase 4 — docs, site, coverage.** The state docs and site pages from the inventory (Use pages EN+DE together:
who-gets-into-your-space, accept-an-invitation, getting-started; Security: protocols, what-the-server-sees,
threat-model, known-limitations; Run only where a sentence became false), the criteria into the three coverage files
beside their tests, ADR 0129 and the three `Replaced in part` lines to `built`. Done when: the trust-chain guards
(`scripts/validate_*.py`, `/usr/bin/python3`) and the site's own check green.

## Definition of done

The four phases' done-whens; `git grep -n -i "only channel" -- contracts src docs site` returns only lines about the
mailed operation; this CR's lessons moved to where they bind and the file deleted.

## Retrospective

_(filled by the implementer)_
