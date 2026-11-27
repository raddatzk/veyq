---
id: cr-apps-invites-and-join
title: The apps invite members (a link or a mail, with a label) and join a space from an invite, the web's crypto ported into the Kotlin core
status: planned
satisfies: ["uc.spaces.add-member", "uc.spaces.manage-member-invites"]
adrs: ["0111", "0129", "0028", "0161", "0148"]
invariants:
  - "every byte an invite signs, seals or derives (invite key, sealed link, accept proof, join record, self-grant, label) is held to contracts/crypto/vectors/v1.json before a flow uses it"
  - "the link's sealed part and its code never reach the server; the server sees only ciphertext and the invite's public key"
  - "a link shown once and a code shown once are secrets on screen (src/mobile/CLAUDE.md § Secrets on screen); the bridge sends neither in an update"
  - "every word is the shared catalogue's, EN and DE, twinned with the web's"
---

## Goal

Close the apps' next gap against the web (owner 2026-11-26). An owner invites a member from Settings' Members: a link
(shown once, with an optional label only owners see) or a mail (a code or a chosen password passed on outside veyq). The
owner lists the pending invites, reissues one, revokes one, or edits its label. A person joins a space from an invite in
the app: an owner invite, a mailed member invite, or a link invite with an existing account or a new one (the web's
`/tenant/accept`).

## Context & constraints

- The web: `src/web/src/features/spaces/{memberInvite,members,inviteLabel}.ts`, `InviteMemberDialog.vue`,
  `InviteLinkResult.vue`, `TenantMembersPanel.vue`' pending list; `src/web/src/views/TenantJoinView.vue` over
  `features/tenant/tenantInvite.ts`; `@veyq/crypto`'s `invite.ts` (its pins: `invite_key`, `invite_link`, `join_record`,
  `join_endorsement`, `grant_signature`, `scope_succession`, `key_chain`, `value_key`).
- The core has the join record's statements and the field seals (`crypto/JoinRecords.kt`), the grant seal
  (`VeyqAccount.sealGrant`/`sealSelfGrant`), the re-grant of open invites at a rotation (`ScopeRotation.carryOpenInvites`)
  and the cold account birth only as far as the sign-in needs it. Missing: `inviteKeyFromSeed`, the invite code and
  `inviteLinkSecret`, `seal/open/parseSealedInviteLink` with the KDF parameters, the fragment's parse,
  `checkInvite` (anchor grant, carried grant, succession path), `signInviteAccept`, `signJoinRecord`/`sealJoinRecord`,
  the register-and-accept birth (`buildAccount` with the kit record), and `invite_link` in `crypto/Conformance.kt`.
- Generated: `TenantsApi` (`previewTenantInvite`, `acceptTenantInvite`, `acceptTenantOwnership`,
  `registerAndAcceptTenantInvite`, `createMemberInviteLink`, `inviteTenantMember`, `listMemberInvites`,
  `setMemberInviteLabel`, `reissueMemberInvite(Link)`, `revokeMemberInvite`).
- An app is reached by a link through `veyq:` (ADR 0150 § O). How an invite link opens the app (the web's route form,
  inbox #1415) is decided in phase 3's refinement, not here.

## Phases

1. Crypto: the missing `invite.ts` pieces ported into `crypto/`, `invite_link` and the rest run by the conformance
   runners (JVM, libsodium, core-js), each bitten.
2. Core: inviting (`TenantMembers.invite`, the pending list, reissue, revoke, label) and joining (`session/TenantJoin`,
   the web's phases), the bridge's calls, the words.
3. Platforms: Android, Apple, Linux, Windows, including the invite's entry into the app.
4. Docs: ADR 0129's and ADR 0111's sections, native-apps.md, the Use pages EN+DE. Then this CR is deleted.

## Decision log

- **Split from cr-apps-members-and-terms-editing** (2026-11-27): the roster and the terms need no new crypto, but this
  needs the invite's whole suite. Porting the suite first and holding it to the vectors is the sound order.
- **The crypto is `crypto/Invites.kt`, beside `JoinRecords`** (2026-11-27, implement): ported line for line from
  `invite.ts`; `invite-accept` added to the statements' schema; `Conformance.inviteLink` runs `#invite_key`'s derivation,
  `#invite_link` whole (the KEK at production cost only) and `#join_record`'s accept proof and signing on all three
  runners; `InviteCheckTest` ports `inviteCheck.test.ts`. A sealed part with another profile is refused as
  `InviteLinkInvalid("profile")` (the web's `KdfProfileRefused`): the same refusal before any derivation.
- **The invite link and code are not covered as "secrets on screen"** (corrects the invariant): they are made to be handed
  on through another app, as a share link and its password are, which § Secrets on screen leaves out on purpose. They are
  answered once, to the call that made them (`Answer.Invited`), never in an update, never kept by the core; the join's
  typed password and Secret Key fields are masked as the sign-in's are.
- **An invite link enters the app through *Open a link*, never through `veyq:`** (phase 3's question, decided now because
  the core decides it): `ReceivedLink.inviteOf` reads `<origin>/tenant/accept#…`, `OpenLink` offers it as an invite
  (`State.invite`, `invite()`), `SharedText.linkIn` counts it, `Received.Open` answers `JoinOpened`. A `veyq:` link carries
  nothing (ADR 0150 § O1), and #1415's deep links exclude invites (owner 2026-11-27). So a signed-out person pastes it on
  the sign-in screen, and any person shares it into the app (Android, iOS) — the desktops' signed-in way in is a sign-out
  or the share sheet they do not have: the residual is named in ADR 0129.
- **The join signs in on its own, then hands over** (2026-11-27): an existing account is signed in by the join itself
  (cold, password and Secret Key, as the web's page) to accept and to hold the accepted key, then let go (`retire`); a new
  account is registered in one request and signed in once after it to hold the key (ADR 0111 decision 23) and, for a link
  invite, to ask for its confirmation mail. *Open the space* then signs in through the app's own `SessionModel` with what
  the join holds in memory (`TenantJoin.openSpace`), so the account opens as any sign-in does. Rejected: handing the
  join's account to the session (the session model is generic over its door; one more sign-in costs a second Argon2id).
- **No picture at the join** (2026-11-27): the web's optional join picture is set afterwards in Settings' profile, which
  the apps have; the real name is sealed at the join as on the web.
- **The inviting owner is pinned from the served chain** (`Anchoring.anchorServedChain`, the web's `anchorServedChain`),
  a pinned foreign granter judged against its own chain (`anchorChainKey`), as `memberInviteAnchored` does.
