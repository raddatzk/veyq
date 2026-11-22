---
id: cr-guest-move-in-the-apps
title: A READ_WRITE link's guest moves within the link's folders in the apps, through the member's move sheet
status: in-progress
satisfies: ["uc.memory.move-node"]
adrs: ["0150", "0154", "0155", "0080"]
invariants:
  - "a guest's move is the web's: one updateShareNode per node, re-sealed under the destination's key, after the core's one naming flow"
  - "a guest is offered a move only where the web's rights grant it: a node a folder of the link holds, never the link's root, never in or of an identity folder, never a Pot, a chapter or a drop box"
  - "the guest's browser is the member's MoveSheet: its top the link's root, its places the link's folders only — no guest-only UI"
---

## Goal

Inbox #1063: on the web a contributor (`READ_WRITE`) link's guest moves a node within the link's folders
(`src/web/src/core/node/move.ts` `nodeMover` → `guestMover` over `placeShareNodeAsGuest`); the apps' core move
(`session/NodeMove.kt`, `navigation/MoveSheet.kt`) is a member's, and a guest's level offers none. Build the guest move in
the core and draw it on all four apps where the member's move is drawn: the row's menu and the level's Select.

## Phases

1. **Core** — `AppKinds.guestMoves` (each application's guest rights: a file and a Memory move); `NodeMove`/`MoveSheet`
   take a top (a guest's link root, `MovePlan`'s `guest`); `sharing/GuestMove.kt`: the rights, the placement write over
   `updateShareNode`, the sheet's door, the level's part (`GuestLevel.move`: row plan, Select, selection, sheets).
   Done when: `GuestMoveTest` green over the played link, bitten; `:core:jvmTest :composition:jvmTest`.
2. **Bridge + desktops** — `Received.Move`/`Received.Select*`, the rows' plan and the level's tools and selection in
   `ReceivedPage`; Linux `received.rs`, Windows `ReceivedView.cs` draw them with the member's move sheet and selection bar.
3. **Phones** — Android `GuestScreens.kt`, Apple `GuestLinks.swift`: the row menu's Move and Select, the member's sheet.
4. **Harvest** — ADR 0150's Consequences, `src/mobile/CLAUDE.md`, the inbox row; this CR deleted.

## Decision log

- **The browser is the member's `MoveSheet` with a top**, not a guest copy: `Door.top` (null for a member) is the link's
  root; `NodeMove` passes it to the platform's `MovePlan` as `guest = top != null`, so the places are the link's folders and
  the trail starts at the link's root, named as the link.
- **Guest rights per kind come from each application** (`ApplicationKinds.guestMoves`, folder core's): the web reads them
  from each application's rights function; a Kotlin hook keeps `sharing/` free of application names.
- **Naming**: the move asks through `GuestAccess.naming.request()` (the gate that exists today); a decline is a new
  `NodeMove.Outcome.DECLINED` — the sheet stays, says nothing, sends nothing more (the web's `MoveDeclined`).
- **A guest's selection offers Move only** (no copy, no delete: a guest has none; no selection report — the rows keep
  their own report where the host offers it). It lives in the level (`GuestLevelMoves`) and is cleared when the link is
  read anew after a write — a guest's level is redrawn then.
- **The write**: no floor of its own (a guest holds no scope epoch); `NodeEpochWrite` waits on `node_rekey_pending` and
  re-reads once on `stale_epoch`; one rebuild on `stale_placement` (the web's `PLACEMENT_RETRIES`); `403`/`404` gone,
  `422` (`illegal_structure`, `illegal_scope`) not allowed.
