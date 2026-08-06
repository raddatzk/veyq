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
2. The client **lists the whole tree** ([en.memory.tree](../enablers/en.memory.tree.md)) and **decrypts
   names** under `K_SC` on device, assembling the tree from the opaque rows (`id`, `parentId`, `kind`).
3. The **shell** renders: a sidebar (the personal space + profile menu), a **breadcrumb** built
   **client-side** from the local tree (the blind server cannot compute it), and a content area showing the
   **current level**. A toolbar **display toggle** picks how that level is shown — **grid** (cards, the
   default), **list** (a compact list), or **map** ("Karte": the level's located Memories/Chapters plotted as
   pins, [uc.memory.place](uc.memory.place.md)) — one **app-wide, persisted** preference. In grid/list the
   child **folders** and **Memories** render with a *create* card each; in map the subtree's located nodes
   render as clickable pins (folders are navigated from grid/list).
4. **Navigate:** clicking a folder drills into it (new level + breadcrumb); clicking a Memory opens it (its
   detail view is a later slice). The breadcrumb walks back up the local tree.
5. **Organize:** *create folder* / *create Memory* seal the name + blinded slug under `K_SC` and POST only
   ciphertext (a Memory also births its random `K_mem` wrapped under `K_SC`); *rename* re-seals the name +
   recomputes the slug; *delete* removes the node and its subtree. All via [en.memory.tree](../enablers/en.memory.tree.md).

## Alternate / exception flows

- **A1 — no personal scope yet:** first unlock auto-births it before listing (idempotent; a no-op
  afterwards).
- **E1 — no resolvable tenant:** scope operations resolve the active tenant from the request Host
  (`X-Forwarded-Host`/`Host`, **port stripped**, exact `domain` match); if none matches, they fail and the
  shell shows an empty/blocked state. This is **normal** behaviour, not a special case — in local dev you
  simply create a tenant whose `domain` equals the host the backend sees (with the default Vite proxy that is
  **`127.0.0.1`**, since `changeOrigin` rewrites the Host to the proxy target and the port is stripped). No
  dev-only code path.
- **E2 — slug collision:** creating/renaming to a name whose blinded slug already exists under the parent is
  rejected (the server enforces `UNIQUE(parentId, slugToken)` without seeing the name).
- **E3 — illegal structure:** a CHAPTER cannot live at a folder level (it belongs inside a Memory); the
  server rejects it. CHAPTER creation is part of the Memory-detail slice, not this browse.

## Quality notes

- **Operator-blind navigation.** Names are decrypted **on device** under `K_SC`; the **breadcrumb and the
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
- **Display modes are a facet of browsing.** How the current level is shown — **grid / list / map** — is one
  app-wide, persisted toggle (`useViewMode`, `ViewModeToggle`), not a separate goal: grid/list shipped with
  cr-0063, the map view with cr-0071. The **map** stays operator-blind — it plots each node's sealed
  `location` unsealed on device from what `listNodes` already returns, behind the personal tiles opt-in (ADR
  0018); there is no locations API. It is **browse-only** (the Chapters-tab grid offers grid/list only).

## What this means for you / Was das für dich bedeutet

**EN —** Once you unlock veyq, you land in your personal space and can browse your Memories and the folders
that organize them — create, rename, move into, and delete them. Every folder and Memory **name is
decrypted on your own device**; the server only ever stores scrambled names and the shape of your tree, and
it can't even work out the path you're looking at — your app builds that locally. Opening a Memory to see its
photos and journal comes in a later step; for now this is the home that makes your encrypted space navigable.

**DE —** Sobald du veyq entsperrst, landest du in deinem persönlichen Bereich und kannst deine Erinnerungen
und die Ordner, die sie organisieren, durchstöbern — anlegen, umbenennen, hinein­navigieren und löschen.
Jeder Ordner- und Erinnerungs­name wird **auf deinem eigenen Gerät entschlüsselt**; der Server speichert nur
verschlüsselte Namen und die Form deines Baums und kann nicht einmal den Pfad ableiten, den du gerade
ansiehst — das baut deine App lokal. Eine Erinnerung zu öffnen, um Fotos und Journal zu sehen, kommt in einem
späteren Schritt; vorerst ist dies das Zuhause, das deinen verschlüsselten Bereich navigierbar macht.

## Related

- Feature: [Memory](../_index.md)
- Enablers: [en.memory.tree](../enablers/en.memory.tree.md) (the encrypted node tree this browses),
  [en.spaces.personal-scope](../../spaces/enablers/en.spaces.personal-scope.md) (`K_SC`, auto-birth),
  [en.memory.media](../enablers/en.memory.media.md) (media — display is a later slice),
  [en.memory.favorites](../enablers/en.memory.favorites.md) (the sidebar Favorites section)
- Decisions: [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (`K_SC`→`K_mem`)
- Related use-cases: [login](../../account/use-cases/uc.account.login.md), unlock — done; the **Memory detail
  view** (gallery / journal / chapters) — planned; multi-space switching, search — planned
