---
id: cr-passes-bugs-block-a
title: The passes' bugs (V2 block A) — the web's picker keeps a polled peer's addition, an open Memory follows a peer's meta, a landscape phone keeps one pane, a rotation keeps the kit scan, a guest's deleted folder is in the apps' trash
status: in-progress
satisfies: ["uc.memory.participants", "uc.memory.live-updates", "uc.depot.drop-box", "uc.memory.delete-node"]
adrs: ["0144", "0083", "0081", "0080", "0122", "0136", "0033"]
invariants:
  - "the server sees nothing new: the apps' tree check is the conditional `listNodes` the web's level poll already sends (ADR 0081), and a trashed identity folder opens on the member's device through the drop key chain, as its live twin does"
  - "a merge keeps what the editor opened with: a removal is only what the editor took out of the list it was shown (ADR 0144 decision 1, ADR 0083)"
  - "nothing is asked of the server while no screen shows the page (the live poll's visibility gate, core's LivePoll)"
---

## Goal

Five bugs the curation, maps and Depot passes found (inbox #952, #953, #951, #937, #904), fixed where they live, each with
the failing test first where a harness exists. Rows #943, #944, #946 of the same block are another CR's (not here).

## Context & constraints

- The rows (`docs/inbox.md`) are the symptoms; each was re-derived against the tree at `79f1f3c94` below.
- Machine (remembered, `src/mobile/CLAUDE.md` § Prove your work and the memory notes): the worktree needs
  `ANDROID_HOME=$HOME/Library/Android/sdk`; guards under `/usr/bin/python3`; no iOS simulator boots here, Apple's click is
  the owner's (#925); the dev backend at `veyq.localhost:8080`, the web at `veyq.localhost:8081`.

## Re-derived (measured at `79f1f3c94`)

- **#952** holds as written: `useMemoryParticipants.onPickerSave` passes `nodeMetaState(ctx.node())` — the node at the
  save —, and `setMemoryParticipants` derives `openedWith` from that state
  (`grep -n openedWith src/web/src/features/memories/memoryParticipants.ts`), so an id the page's poll added while the
  picker was open counts as removed. The dialog copies `selected` at mount (`MemoryParticipantsDialog.vue`: `ref([...props.selected])`).
- **#953**: the web follows a peer's change of an open node's meta through the **level poll** (`core/node/useLevelLive.ts`,
  the scope's conditional `listNodes`, ADR 0081, ~5 s), not through the media poll. The apps' gallery poll
  (`GalleryModel.live`) asks only `MemoryMedia.moved`; its header's facts come from `TenantSession.trees()`, cached until
  `ShellModel.refresh()` (`grep -n "fun refresh\|forgetTree" src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/navigation/ShellModel.kt`).
  Every platform runs that one poll (`grep -rn "startLive\|\.live()" src/mobile --include=*.kt --include=*.swift`: Android's
  `MemoryScreen`, Apple's `AppModel`, the bridge's `Watched.GalleryLive` for Linux and Windows). The phones have no refresh
  for the shell's lists (only the Pot page's `PullToRefreshBox` / `.refreshable`).
- **#951** holds: `ShellScreen.kt` keys `wide` on `screenWidthDp >= 840` alone.
- **#937** holds: `AppRoot.kt`'s sign-in keeps `scan` in `remember`, and `KitScanner`'s `onDispose` closes it; the device
  link is `AppModel._link`, a `ViewModel`'s, which a rotation keeps.
- **#904 — the row's premise is half wrong.** The web's `listTrash` opens a trashed identity folder through the drop key
  chain (`childEntryKey`; unit case `tree.identityFolder.test.ts` "the trash opens a trashed identity folder through the same
  chain"), and its `restoreOnce` derives the token under that entry key. ADR 0080 decision 11 says *"a member's restore brings
  the folder back"*. Only the **core** leaves them out: `NodeTrash.list` drops every row with `shareIdentityId`
  (`grep -n shareIdentityId src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/session/NodeTrash.kt`), and its restore
  takes the slug token under the parent's key. So the confirm's promise is the decided behaviour; the apps are what breaks it.
  Whether the web's restore really runs end to end is measured live (phase 6), not assumed.

## Decisions (technical, settled here)

1. **#952** — the composable takes the ids **when the picker opens** (`openPicker`) and hands them to the save;
   `setMemoryParticipants` gets `openedWith` as a parameter instead of deriving it from `current`. `current` stays the node
   at the save (the freshest revision: no needless 409). The same shape as the apps' `ParticipantsPicker` (ADR 0144 decision 1).
2. **#953, the follow** — the gallery's live tick also asks whether the open node's **scope tree moved**: a new
   `GalleryModel.Door.treeMoved()`, which `ShellModel` answers with `TenantSession.treeMoved(scopeId)` — `listNodes` with the
   `ETag` the last tree read was answered with as `If-None-Match` (a `304` is no) — and, where it moved, `refresh()`. The
   refresh rebuilds the shell's lists, whose new revision re-reads the page (`Door.revisions` → `read()`: the facts, the
   people line, the banner). One conditional request per tick, at the gallery's cadence (`LivePoll.ITEMS`, ~20 s): the web's
   level poll runs every ~5 s, but an app's open Memory already polls at 20 s, and one loop per page keeps the visibility gate
   in one place. The revision is kept per scope by `TenantSession` from each tree read (the `ETag` of a `200`, the sent one on
   a `304`); a scope with none known (offline, never read) is not asked. Rejected: reading the node's meta from the media
   listing (it carries no node revision, `MediaListPage`); a second poll loop per page (two visibility gates).
3. **#953, the phones' pull-to-refresh** (the owner's call 2026-11-18) — Android (`PullToRefreshBox`, Material 3) and iOS
   (`.refreshable`) offer it on the shell's lists (Home, an application's list, a folder level) and on a Memory's or a
   chapter's page; it calls the platform's existing lists refresh (`AppModel.refreshLists`: `ShellModel.refresh()` and the
   rows' facts), which the open page follows through its revisions. The Pot page keeps its own (`refreshPot`). The desktops
   keep Refresh (F5); they gain the follow of decision 2 through the core.
4. **#951** — the expanded layout (the permanent drawer and the list beside) needs an expanded width **and** a height that is
   not compact (≥ 480 dp, Material 3's height class): a phone in landscape (≈ 914 × 411 dp) gets the rail and one pane. The
   rail stays keyed on width (Material's navigation suite puts a rail on a medium or expanded width).
5. **#937** — the sign-in's kit scan is the Android `AppModel`'s (`kitScan: StateFlow<QrScan<*>?>`, opened by `scanKit`,
   ended by `closeKitScan`, which closes it, and in `onCleared`), as the device link is; `KitScanner`'s dispose stops only its
   watch. Apple has no configuration change that drops its view state; it is untouched.
6. **#904** — `NodeTrash.list` opens an identity folder's entry through `Tree.childEntryKey` over its live parent (the drop box),
   and leaves out only one that does not open (the web's rule: a guest-sealed entry never fails the trash); `Trashed` carries
   `shareIdentityId` and `wrappedIdentityKey`; the restore derives the slug token (and a new name's seal) under that entry key,
   as the web's `restoreOnce` does. `dropbox_delete_folder_confirm` stays. The web is changed only if its live pass fails.

## Inventory (locators — run, read as lists)

| What | Locator |
|---|---|
| #952 web | `grep -rn "setParticipants\|setMemoryParticipants\|openedWith" src/web/src` |
| #953 core poll + door | `grep -rn "GalleryModel.Door\|fun moved\|fun live" src/mobile/core/src` |
| #953 phones' lists | `grep -rn "HomeScreen(\|MemoriesList(\|ExpensesList(\|ArrangedListScreen(" src/mobile/android/src`; `grep -n "List {\|List(" src/mobile/apple/Veyq/ShellView.swift` |
| #951 | `grep -n "EXPANDED_WIDTH_DP\|wide" src/mobile/android/src/main/kotlin/me/raddatz/veyq/mobile/android/ShellScreen.kt` |
| #937 | `grep -rn "scanKit\|KitScanner(" src/mobile/android/src` |
| #904 | `grep -rn "NodeTrash\.\(list\|restoreFromTrash\|restore\)\|class Trashed" src/mobile --include=*.kt --include=*.swift --include=*.rs --include=*.cs` |
| docs naming the old state | `grep -rn "#904\|#937\|#951\|#952\|#953\|only a refresh forgets\|restorable only by Undo\|kept in .remember" docs src/mobile/CLAUDE.md site/src/content` |

## Phases

- [ ] **1 web (#952)** — red first: `memoryParticipants.test.ts` gains "a peer's addition the page polled between the
  picker's open and its save survives" (`openedWith` = [A, B], the node at the save holding [A, B, P], wanted [A] → [A, P]),
  and `MemoryParticipants.test.ts` a case that the save hands the ids the picker opened with. Then decision 1.
  · Done when: both red before, green after; `pnpm run check` in `src/web` green.
- [ ] **2 core (#953 follow, #904 trash)** — red first, over `PlayedTenant` (extended: a drop box with a guest's identity
  folder, the scope's trash, `restoreNode` recorded): (a) a Memory's page over a `ShellModel` follows a participants change
  of the tree within a live tick, and asks `listNodes` conditionally (a `304` while nothing moved); (b) `NodeTrash.list` lists
  a trashed identity folder by its guest's name, and leaves out one whose `K_up` does not open; (c) its restore sends a token
  derived under the identity key. Then decisions 2 and 6. · Done when: each case red before, green after;
  `./gradlew :core:jvmTest :bridge:jvmTest` green.
- [ ] **3 Android (#953 pull-to-refresh, #951, #937)** — decisions 3–5. · Done when: `./gradlew :android:assembleDebug` green.
- [ ] **4 Apple (#953 pull-to-refresh)** — decision 3. · Done when: the iOS simulator and macOS builds green (the click is
  the owner's, #925).
- [ ] **5 desktops** — no code (the follow and the trash are the core's). · Done when: `linux/run.py build` and
  `windows/run.py build` green.
- [ ] **6 passes** — web (headless system Chrome, `?devlogin`): #952 with a second session adding a participant while the
  picker is open; #904 a guest's folder deleted, in the trash, restored. Android (a throwaway AVD): #953 a web edit of an open
  Memory's participants shown without a touch, and the pull-to-refresh on a list and a Memory; #951 landscape; #937 a
  rotation with the scanner open; #904 the trash lists and restores a guest's folder. Linux and Windows: #953 the follow,
  #904 the trash. · Done when: each observed, or named as not run with why.
- [ ] **7 harvest** — docs (`native-apps.md` § Open, `src/mobile/CLAUDE.md`'s traps, uc.memory.live-updates' apps line,
  `docs/apps/depot.md` if it names the gap), the site where a user notices (the phones' pull-to-refresh), the five inbox rows
  deleted, lessons placed, this CR deleted. · Done when: every `/usr/bin/python3 scripts/validate_*.py` but guard_gaps exits 0.

## Definition of done

Phases 1–7 done; the five rows gone from the inbox; nothing of this CR left in the tree.

## Retrospective
