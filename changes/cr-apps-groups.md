---
id: cr-apps-groups
title: The apps create, rename, delete and restore groups, manage a group's members and answer group invitations, as the web does
status: planned
satisfies: ["uc.spaces.add-group-member", "uc.spaces.consent-group-add", "uc.spaces.manage-group-members", "uc.spaces.rename-group", "uc.spaces.delete-group"]
adrs: ["0111", "0025", "0161", "0148"]
invariants:
  - "a group's name opens on the device only (group-name under its value key; group-name-key for an invitee), held to the vectors"
  - "a group member's removal is the group's removal-rotation (commitRotation with removesUserId), never a plain delete"
  - "a member is added only once anchored (anchorIdentity); an anomaly sends nothing"
  - "every word is the shared catalogue's, EN and DE, twinned with the web's"
---

## Goal

Close the apps' gap for groups (owner 2026-11-26). A member creates a group (where `CORE_GROUP_SPACE` is on). An owner
renames, deletes and restores it, adds a tenant member by username (a consent proposal where the member asks for one),
changes a role, removes a member (by rotation) and rescinds a proposal. An invitee accepts or declines on *Group
invitations*.

## Context & constraints

- The web: `src/web/src/features/spaces/{CreateGroupDialog,GroupMembersPanel}.vue`, `scopes.ts` (`createGroup`,
  `renameGroup`, `deleteGroup`, `restoreGroup`, `wrapGroupNameKeyForInvitee`, `rotateGroupForRemoval`), `members.ts`,
  `inviteName.ts`, `invitations.ts`, `views/GroupInvitationsView.vue`; the settings' `groups` category in `sections.ts`.
- The core rotates GROUP scopes (`ScopeRotation`, `FinishRotation`) and has the `group-name` and `group-name-key`
  contexts, but `Account.scopeName` names every group "Group": a group's name is not opened yet. That is phase 1.
- Generated: `ScopesApi` (`createGroup`, `renameGroup`, `deleteGroup`, `restoreGroup`, `resolveGroupMember`,
  `addGroupMember`, `setMemberRole`, `removeMember`, `listMyGroupInvitations`, `accept/declineGroupInvitation`,
  `rotateScope`).

## Phases

1. Core: a group's name opened (`scopeName`, the shell's space list, the switcher), held to `value_key` and `seal.sym`
   `group-name`.
2. Core: create, rename, delete and restore, members, invitations; the settings' `groups` sections; the bridge; the words.
3. Platforms: Android, Apple, Linux, Windows.
4. Docs: ADR 0111's section, native-apps.md, the Use pages EN+DE. Then this CR is deleted.

## Decision log

- **Split from cr-apps-members-and-terms-editing** (2026-11-27): the apps do not open group names yet, and every group
  screen needs them.
- **A group's name is `session/GroupNames`** (2026-11-27, implement): sealed (`group-name`, SEAL schema) under a fresh
  value key whose wrap's address is `group-name`'s in the SEAL schema, as `encodeValueContext` reads it; the invitee's
  `group-name-key` seal. Held by `GroupNamesTest` over a fixture the web's `@veyq/crypto` wrote (bitten by the FIELD
  schema's address). `Account.scopeName` opens it for every open GROUP scope, so the shell and switcher name groups.
- **Groups live in one Settings section, `GROUPS` (`groups`), and invitations in another, `GROUP_INVITATIONS`**
  (2026-11-27): the web's `groups` category holds one section per owned group and its `/invitations` page sits in the
  sidebar; the apps' Settings page is a fixed plan over one tenant, so *Groups* lists the caller's groups (owned first)
  and opens each group's page, and *Group invitations* is the inbox. Both after the tenant's sections.
- **The removal is `ScopeRotation.commitRotation(removes, revokeLinks)`** (2026-11-27): the existing rotation gained the
  web's `removes`/`revokeLinks` options (the removed member carried in no grant, on every rebuild too); the session then
  reopens what moved and resumes the re-keys, as the tenant removal does. Live-bitten: without `removes` the member stays.
- **Not in the apps (gaps, as for the tenant):** a group's icon, its new lock and *turn off every link*, which are the
  same panel's parts the tenant's Members also leaves to the browser.
