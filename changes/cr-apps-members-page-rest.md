---
id: cr-apps-members-page-rest
title: The apps give a space and a group a new lock, turn off every link, set its picture, show the trust badge and take a picture at join, as the web does
status: in-progress
satisfies: ["uc.spaces.remove-tenant-member", "uc.spaces.set-space-icon", "uc.spaces.manage-group-members", "uc.spaces.set-profile-picture", "uc.spaces.verify-identity"]
adrs: ["0025", "0075", "0084", "0115", "0148", "0111"]
invariants:
  - "a space's or group's new lock is ScopeRotation.commitRotation (no removesUserId on a TENANT), the removed member excluded from it by the owner's choice"
  - "a picture is sealed under a fresh value key (space-icon / member-avatar), padded to 16 KiB, its key wrapped under the scope key; opened only on the device"
  - "every word is the shared catalogue's, EN and DE, twinned with the web's"
---

## Goal

Close what docs/crosscutting/native-apps.md lists as missing from the web's Members panel and group page (owner
2026-11-26): the space's new lock (alone and as the removal's second option), turning off every link of the space, the
space's icon, the trust badge on each member row; a group's picture, new lock and turning off its links; the picture
picked at join. Also: the platforms draw the deleted group's day (`GroupRow.deletion`, `GroupPage.deletion`).

## Context & constraints

- The web: `features/spaces/TenantMembersPanel.vue`, `GroupMembersPanel.vue`, `MemberTrustBadge.vue`, `spaceIcon.ts`,
  `scopes.ts` (`setSpaceIcon`, `clearSpaceIcon`, `openMyTenantIcon`, `openGroupIcon`, `revokeScopeShares`),
  `members.ts` (`rekeySpace`), `account/tenantTrust.ts` (`tenantBadges`), `views/TenantJoinView.vue` + `JoinPicturePicker.vue`.
- The core has `ScopeRotation.commitRotation`, `ValueKeys`, `Pad`, `PersonTrust.whosWho`, `ProfileModel`'s WebP ladder.

## Phases

1. Core: `SpaceIcons` (seal/open, a web-made fixture), `ScopeIcons`, `TenantMembers` (new lock, links off, trust badge,
   removal's second option), `TenantGroups` (new lock, links off, badge), `TenantJoin` (picture), words, bridge.
2. Platforms: Android, Apple, Linux, Windows.
3. Docs; then this CR is deleted.

## Decision log

- **One CR, not two** (2026-11-27): every piece rides the same two screens and the same core seams (`ScopeRotation`,
  value-key seals, `PersonTrust.whosWho`); the join's picture is the profile picture's pad and seal at another moment.
- **The new lock is one core seam for both scopes, `session/Locks`** (2026-11-27): `commitRotation` over the directory read
  now, then `reopenMoved` and `resumeRekeys()` awaited — the web's `rekeySpace` then `rekeyScope`; the notice says
  `spaceLock.done` where this scope's re-key came out DONE, else `rotation.statusHint.rekeying` (the web shows that hint on
  the panel instead of the toast). A tenant removal's second option runs it after the removal with `excluding` the
  removed member — added to `commitRotation` as the web's `excluding` (dropped from every re-read, never a
  `removesUserId`, which a TENANT refuses); `ScopeRotation.listedFor` holds the filter (bitten).
- **The rekeying and resealing hints and the stuck compromise-rotation notice stay web-only** (2026-11-27): the gap list
  names the lock, the links, the icon and the badge; the hints are status the apps' shell does not show elsewhere either.
  Left as a gap in native-apps.md.
- **The icon is `session/SpaceIcons` (seal/open) and `ScopeIcons` (`TenantSession.icons(encoder)`)** (2026-11-27): the
  picture cropped square by the platform as the profile picture is ([ProfileModel.EDGE] RGBA; the bridge's
  `HostRequest.PictureSquare`), encoded by the profile's ladder (`ProfileModel.encodeLadder`, factored out), padded to
  16 KiB, sealed `space-icon` under a fresh `K_v`; a `409 stale_epoch` reads the scopes anew and seals once more. Held by
  `SpaceIconsTest` over a fixture the web's `spaceIcon.ts`/`valueKey.ts` wrote (bitten: another icon epoch opens nothing).
  The icon shows on the Members header and the group page; the switcher and the sidebar draw no space picture in the apps
  yet — a gap left in native-apps.md.
- **The trust badge is `MemberBadge.of(whosWho)`** (2026-11-27): the web's `badgesIn` over *Who's who*'s states (verified
  wins over new; not verified and unchecked show nothing), on the tenant roster and a group's page (the web's
  `GroupMembersPanel` draws it too). Bitten by testing *new* first.
- **The join's picture is `TenantJoin.choosePicture` / `removePicture` / `picture`** (2026-11-27): kept encoded in the flow
  only, sealed `member-avatar` beside the name in both the register and the sign-in accept, never at a handover (the web's
  `JoinPicturePicker` sits in the same `!handover` block); zeroed at the finish and the close. Live in `InvitesLiveTest`
  (bitten: no `sealedAvatar` sent, the owner opens nothing).
- **The bridge:** `Settings.MembersNewLock`, `.MembersRevokeLinks`, `.MemberRemove(newLock)`, `.Icon` / `.IconSet(path,
  pose)` / `.IconClear` (`Answer.IconChanged`), `.GroupNewLock`, `.GroupRevokeLinks`, `Join.Picture(path, pose)`,
  `.RemovePicture`, `.PictureBytes`.
