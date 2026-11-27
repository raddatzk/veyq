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
