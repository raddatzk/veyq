# UC: Browse & organize the Memory tree (shell)

- **ID:** uc.memory.browse
- **Primary actor:** Account holder with an unlocked vault, inside one of their spaces (this slice: their
  **personal space**).
- **Goal:** See and organize their **Memory tree** — navigate folders, open Memories, and
  create / rename / delete folders and Memories — through the app **shell** (sidebar + breadcrumb + content),
  with every name decrypted **on device**.
- **Preconditions:** The user is logged in ([uc.account.login](../../account/use-cases/uc.account.login.md))
  **and** has unlocked the vault (the identity key + Master Key are in memory). The user belongs to a tenant
  (so a personal scope can exist there); their **personal scope is born** ([en.spaces.personal-scope](../../spaces/enablers/en.spaces.personal-scope.md)) — auto-birthed on first unlock if absent.
- **Postconditions (success):** The shell shows the personal space's tree at the current level; the user can
  drill into folders, open a Memory, and mutate structure (create folder / Memory, rename, delete-subtree).
  The server only ever saw ciphertext names + blinded slug tokens + opaque shape.

## Main flow

1. After unlock, the client **opens the personal scope** → the scope content key `K_SC`
   ([en.spaces.personal-scope](../../spaces/enablers/en.spaces.personal-scope.md)); if no personal scope
   exists yet, it is **auto-birthed** first (operator-blind: a fresh `K_SC` sealed to the user's own
   identity).
2. The client **lists the whole tree** ([en.platform.node-tree](../../platform/enablers/en.platform.node-tree.md)) and **decrypts
   names** on device down the key chain — a scope-root entry with `K_SC`, every other with its parent's key
   ([ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)) — assembling
   the tree from the opaque rows (`id`, `parentId`, `kind`).
3. The **shell** renders: a sidebar (the personal space + profile menu), a **breadcrumb** built
   **client-side** from the local tree (the blind server cannot compute it), and a content area showing the
   **current level**. A **display toggle** at the right end of the level's tools row picks how that level is shown — **grid** (cards, the
   default), **list** (a compact list), or **map** ("Karte": the level's located Memories/Chapters plotted as
   pins, [uc.memory.place](uc.memory.place.md)) — one **app-wide, persisted** preference. In grid/list the
   child **folders** come first as a group of their own, then the **contents** — Memories, files, drop boxes, mixed by
   name — the two headed "Folders" / "Contents" where both are there (owner 2026-11-04); creating is the head's "New"
   menu, never a card. In map the subtree's located nodes
   render as clickable pins (folders are navigated from grid/list). Where the level shows two or more
   application sections (Memories, Depot, drop boxes), a **tab bar** `All | Memories | Depot | …` sits on the left of
   that tools row, right under the folder's header ([Application tabs](#application-tabs-a-facet-of-browsing)).
4. **Navigate:** clicking a folder drills into it (new level + breadcrumb); clicking a Memory opens it (its
   detail view, [uc.memory.gallery](uc.memory.gallery.md)). The breadcrumb walks back up the local tree.
5. **Organize:** *create folder* / *create Memory* seal the name (under a field key derived from the parent's key) + blinded slug (under the parent's key) (`K_SC` at
   the scope root, the folder's key inside one) and POST only ciphertext (every node, a folder too, births its random
   key wrapped under the same parent key — ADR 0080); *rename* re-seals the name + recomputes the slug under it; *delete* takes the node and its whole subtree into the space's recoverable trash
   ([uc.memory.delete-node](uc.memory.delete-node.md)). All via [en.platform.node-tree](../../platform/enablers/en.platform.node-tree.md).
6. **Select** (owner decisions 2026-10-14, inbox #421): **Select** in the level's tools row, an icon button beside the trash,
   turns on a selection mode: each row gets a labelled checkbox (a row's own click still opens it), the bar offers
   **All** / **None**, a count (announced to a screen reader) and the actions **Move to…** and **Delete**, and leaving
   the mode or the level clears it. The selection belongs to the level, not to a tab: switching tabs keeps it, "All"
   and "None" act on what the tab on screen shows, and the count says when part of the selection is hidden by the tab
   on screen; a node that leaves the level (moved, deleted) drops out of it. Every row is selectable; each action works
   on the part of the selection the session may act on (the applications' rights functions per node) and says
   "n of m cannot be moved / deleted" for the rest - a `READ_WRITE` guest selects and moves, and is offered no delete.
   Select is offered only where some node of the level allows an action (a `READ` guest has none), and the map
   presentation has no rows and offers no Select. Seen in a real browser on 2026-10-14 (the dev instance, a member and
   a `READ_WRITE` folder link): the checkboxes, the count "3 ausgewählt, davon 1 in diesem Tab nicht sichtbar" after a
   switch to the Depot tab, the guest's bar with Move and no Delete and its hint "1 von 4 kann nicht verschoben werden"
   for a drop box among the selected. Moving several: [uc.memory.move-node](uc.memory.move-node.md) §
   Several at once; deleting several: [uc.memory.delete-node](uc.memory.delete-node.md) step 1. Drag and drop of a
   selection is a later, separate step (inbox #421).

## Alternate / exception flows

- **A1 — no personal scope yet:** first unlock auto-births it before listing (idempotent; a no-op
  afterwards).
- **E1 — no resolvable tenant:** scope operations resolve the active tenant from the request Host
  (`X-Forwarded-Host`/`Host`, **port stripped**, exact `domain` match); if none matches, they fail and the
  shell shows an empty/blocked state. This is **normal** behaviour, not a special case — in local dev you
  simply create a tenant whose `domain` equals the host the backend sees (with the default Vite proxy that is
  **`127.0.0.1`**, since `changeOrigin` rewrites the Host to the proxy target and the port is stripped). No
  dev-only code path.
- **E2 — slug collision:** creating/renaming to a name whose blinded slug token already exists under the parent is
  rejected (the server enforces `UNIQUE(parentId, slugToken)` without seeing the name). A name is unique within its
  route group: a Memory and an Expenses Pot of one name, or a Memory and a folder, coexist (the token of an
  application's node HMACs `<routeSlug>/<slug>`, ADR 0080 decision 16).
- **E3 — illegal structure:** a CHAPTER cannot live at a folder level (it belongs inside a Memory); the
  server rejects it. CHAPTER creation is part of the Memory-detail slice, not this browse.

## Quality notes

- **Operator-blind navigation.** Names are decrypted **on device** down the key chain from `K_SC`; the **breadcrumb and the
  whole tree shape are assembled client-side** from opaque rows. The server never computes navigation, never
  sees a folder/Memory name, and learns only the tree's shape + blinded slug tokens. (The pre-E2EE original
  UI relied on a server-computed `MemoryNavigationContext`; that is deliberately re-derived client-side here.)
- **Reuse the existing UI/UX — never invent** ([ui-web-styleguide](../../../crosscutting/ui-web-styleguide.md)
  § Reuse first). The shell, the folder/Memory grid and the dialogs are existing components; only the **data
  layer** is client-side crypto over the contracts client.
- **Unlock-gated.** The shell needs `K_SC`, which needs the vaulted identity — so it is reachable only after
  **unlock**, not merely after login.
- **No media derivatives yet.** Cards show an icon + name; covers/thumbnails land with the media-display
  slice.
- <a id="application-tabs-a-facet-of-browsing"></a>**Application tabs are a facet of browsing** (inbox #348; core's
  `LevelView`, not Memories': every application's section gets one). `All` is the whole level — the folders, every
  section and the fallback for a kind this client cannot show. An application tab shows only that section's nodes of the
  **current folder** (a filter, never a subtree search: no new query, no server change); the sub-folders stay in every tab
  so one can navigate deeper. The bar appears only where **two or more sections have a tab** (one section is the whole
  level already: no bar, no filtering) and counts only the sections shown at that level (a guest's identity folder
  leaves its empty ones out). The tab is a persisted client setting **per user of the browser** (`useLevelTab`, localStorage like the
  view mode, not per folder): each signed-in account has its own (`veyq:level-tab:u:<userId>`, the account's opaque
  id), and every share-guest page in the browser shares one separate value (`veyq:level-tab:guest`), so a guest tab never
  flips a member's choice and two accounts on one browser never share one. It stays per **browser** — it is not synced
  to the account (a synced preference would need a sealed per-user store of its own). A stored tab that a level does not have is shown as `All` without overwriting the setting, so
  the next level that has it shows it again. The view switch offers the presentations of what the tab shows, and the
  **"New" menu what the tab adds** (owner 2026-11-09): "New folder" in every tab, beside it only the shown application's
  own entries; a kind with no tab (a bookmark) is offered under `All` only. Files or a folder **dropped** onto the level
  are still taken under any tab, and an upload that ran shows the tab of the application that took it (`All` for a kind
  with no tab), stored like a click on it - on the drop, never on a drag over the level, and never for a refused drop or a
  dropped download, which is offered as an import (its "Upload as a file" uploads it, and then shows that tab too). Which tabs are offered still counts every entry the viewer may use,
  not the filtered menu. A share guest gets the same bar: the guest's page is this very view.
- <a id="live-refresh"></a>**An open level stays current** (inbox #408). While a page of the tree is on screen and the tab
  is visible — a folder level, or an opened node's page of any kind (a Memory, a file, Depot's drop box with its guests'
  folders and their files; inbox #503) — core re-reads the level about every 5 s (jittered; doubling to 2 min after errors; at once when the tab
  becomes visible or focused; nothing while hidden or unmounted), so a share guest's upload or a peer's new Memory or
  folder appears without a reload — for a member (`SpaceView`, one `listNodes` per tick) and a share guest
  (`ShareView`, one `resolveShare` per tick) alike. Both reads are conditional ([ADR 0081](../../../decisions/0081-a-scopes-tree-revision-is-the-level-views-change-signal.md)):
  the scope carries a tree revision, bumped in the transaction of every node create, rename, move, trash (a deleted
  file included), restore and purge and every blob attached to or removed from a node (a file's upload or replace), and
  an idle level is a `304` — nothing parsed, decrypted or compared. A guest's tag is a digest of what its link serves,
  not that revision, so it never tells a guest when something outside the link changed. A `200` replaces the page's
  tree only when the rows' fingerprint differs (their ciphertext and the operator's block, which changes no ciphertext). An
  opened node's page stays on screen while its fragment does: a peer's rename or move of it, or of a folder above it,
  keeps it there and the fragment follows to the new path, and a moderator's block of it turns the page to *Blocked by
  the operator* at once, a lift back (uc.moderation.take-a-measure step 3). A refresh never overwrites a write of this tab (a read that
  began before the write's own re-read is dropped) and pauses while a dialog is open. When the scope moved, Depot's
  section re-reads its blob listing (an upload completes after its node is listed); a `304` re-reads nothing. A guest
  whose link was revoked, expired or deleted meets the same non-enumerable `404` as ever and the page shows its
  unavailable state. A Memory's own gallery has its own poll ([uc.memory.live-updates](uc.memory.live-updates.md)).
- **Display modes are a facet of browsing.** How the current level is shown — **grid / list / map** — is one
  persisted toggle per user of the browser (`useViewMode`, keyed like the tab: per account, one for the share guest; core's `ViewModeToggle` in the level view's tools row, the map offered
  because Memories' section names it among its `views` — inbox #370), not a separate goal: grid/list shipped with
  cr-0063, the map view with cr-0071. The **map** stays operator-blind — it plots each node's sealed
  `location` unsealed on device from what `listNodes` already returns, behind the personal tiles opt-in (ADR
  0018); there is no locations API. It is **browse-only** (the Chapters-tab grid offers grid/list only).

## User impact

Your encrypted space stays easy to find your way around: you browse, create and organize folders and Memories, and
every name is read only on your own device, never by the server.

## Related

- Feature: [Memory](../_index.md)
- Enablers: [en.platform.node-tree](../../platform/enablers/en.platform.node-tree.md) (the encrypted node tree this browses),
  [en.spaces.personal-scope](../../spaces/enablers/en.spaces.personal-scope.md) (`K_SC`, auto-birth),
  [en.memory.media](../enablers/en.memory.media.md) (media — displayed by [uc.memory.gallery](uc.memory.gallery.md)),
  [en.memory.favorites](../enablers/en.memory.favorites.md) (the heart on every row, and the sidebar Favorites section)
- Decisions: [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (`K_SC`→`K_node`)
- Related use-cases: [login](../../account/use-cases/uc.account.login.md), unlock — done; the **Memory detail
  view** — [gallery](uc.memory.gallery.md) and [chapters](uc.memory.chapters.md) built, the journal planned;
  [multi-space switching](../../spaces/use-cases/uc.spaces.switch-spaces.md) built; search — planned
- End-user view (docs site): [Views, tabs and favorites](../../../../site/src/content/docs/spaces/views-and-favorites.mdx)
- End-user view (docs site): [Folders](../../../../site/src/content/docs/spaces/folders.mdx)
