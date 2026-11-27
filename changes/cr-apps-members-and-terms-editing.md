---
id: cr-apps-members-and-terms-editing
title: The apps list a tenant's members, let an owner change a role or remove a member, and let an owner edit the tenant's terms, as the web does
status: in-progress
satisfies: ["uc.spaces.remove-tenant-member", "uc.admin.tenant-terms"]
adrs: ["0035", "0101", "0161", "0123", "0122", "0156", "0114"]
invariants:
  - "who sees which action (promote, demote, remove, the editor), the outcomes and their words are the Kotlin core's alone; no platform decides them"
  - "the members, the roles, the removal and the terms go through the client generated from contracts/api, never a hand-written DTO"
  - "no key crosses: a role change and a removal move no key (the server's cascade); a name opens on the device under the tenant key, as the directory's does"
  - "every word is the shared catalogue's, EN and DE, twinned with the web's where the web says it"
---

## Goal

Close two of the apps' gaps against the web (owner 2026-11-26: every app does what the web does). In Settings, a tenant's
**Members** section lists its members with their role, and lets an owner make a member a co-owner, take that back, or
remove a member, with the option to turn off every link of the space in the same act. This is the web's
`TenantMembersPanel` roster. The **Terms** section gives an owner the web's editor (`TenantTermsPanel`): one text per
language that has terms, adding and removing a language, the starter template, the blank-language warning, a preview,
and Save. A member keeps the read-only page.

## Context & constraints

- The web: `src/web/src/features/spaces/TenantMembersPanel.vue` with `members.ts` (`listScopeMembers`,
  `setTenantMemberRole`, `removeTenantMember`, then `rekeyClosedScopes`), and `src/web/src/features/tenant/TenantTermsPanel.vue`
  with `tenantTerms.ts` and `termsTemplate.ts`. API: `tenants.openapi.yaml` (`setTenantMemberRole`, `removeTenantMember`,
  `updateTenantTerms`) and `scopes.openapi.yaml` (`listScopeMembers`, `listMyTenants` for the caller's role).
- The core already reads the member list (`VeyqAccount.members`), opens names (`People`), reopens moved scopes
  (`TenantSession.reopenMoved`) and finishes re-keys (`resumeRekeys`, ADR 0114 decision 9).

## Phases

1. Core and bridge: `session/TenantMembers`, `MemberTerms`' editor and save, `TermsTemplate` held to the web's file,
   `SettingsSection.MEMBERS`, the words, the bridge's calls and tests.
2. Platforms: Android, Apple (iOS, macOS), Linux, Windows.
3. Docs: the ADRs' sections, native-apps.md, the Use pages EN+DE. Then this CR is deleted.

## Decision log

- **Two CRs follow this one, and this one goes first.** Inviting and joining (`cr-apps-invites-and-join`) need the invite
  link's crypto, which the core does not have yet (`inviteKeyFromSeed`, the sealed link, `checkInvite`, held to
  `invite_link`). Groups (`cr-apps-groups`) need group names opened, which the core does not do yet. The roster, the roles
  and the terms need neither, so they land first.
- **"Leaving a space" is not a gap.** The web has no self-leave (no operation in `scopes`/`tenants`, no use case). A
  member leaves only when an owner removes them. native-apps.md is corrected, nothing is built.
- **The role shows as a word** ("Owner", "Member"). The web's row prints the enum as it is (`OWNER`). The apps name it in
  the app's language and the pair stays `apart` in web.json.
- **The roster's trust badge stays in Who's who.** The web's row also carries `MemberTrustBadge`. The apps already show
  each member's trust state in *Who's who*, the section after this one, so the roster leaves it out. This is noted in
  native-apps.md.
- **The space lock, revoking every link, and the tenant icon wait for their own pass.** The web's panel also offers a new
  lock (stand-alone, and as the removal's second option), "turn off every link", and the tenant icon. These go to
  native-apps.md as gaps. The removal keeps its first option (revoke links), which is a parameter of the same call.
- **Owner is read from `listMyTenants`' role**, as the web's panel reads it. The scope summary's role is not used.
- **After a removal the session reopens and finishes re-keys** (`reopenMoved`, then `resumeRekeys`). This is the web's
  `rekeyClosedScopes`: the member's links closed, and what lay below them is due.
- **The starter template is ported into the core as text** (`TermsTemplate`). A JVM test reads the web's
  `termsTemplate.ts` and holds the two equal, so a second copy cannot drift. Reading the TypeScript file at build time was
  rejected because a template literal parser in Gradle would be fragile.
- **The editor's rules are pure functions on a serialisable draft** (`TermsDraft`: which languages can be added, which
  are blank, whether a template overwrites). Kotlin hosts call them directly. The desktops call `Terms.Draft` over the
  bridge at each edit, an in-process call. No host derives a rule.
- **A language's name is its own** ("English", "Deutsch", the web's `LOCALE_LABELS`), so it is a core constant and not
  a catalogue word.
- **The bridge's new calls go through a dispatch of their own** (`members`), since the main one is at the JVM's
  method-size limit.
