# ADR 0122 — One app holds every application: the drawer chooses, the bar belongs to what is chosen

- Status: Accepted
- Built: partly (not built: decision 3's New, decision 7's index, each object's own views)
- Date: 2026-11-07

## Context

V1 is the web client; V2 is the native apps, iOS, macOS and Android, a shared Kotlin Multiplatform base under a
native UI on each platform (owner 2026-11-07; the build is [ADR 0123](0123-the-apps-share-a-kotlin-multiplatform-core-under-native-ui.md)).
[ADR 0086](0086-one-word-per-meaning.md) § The shape of the apps had sketched one app per application: the veyq app as
core plus Depot, each further application its own app, reached by deep link.

The owner's reason to want dedicated apps is a task, not a product line: entering an expense must not mean navigating
spaces and folders first, and each application should present itself in its own way. Both can be had inside one app.
What a split costs is fixed and large: one sign-in shared across apps (a shared Keychain group on iOS,
`AccountManager` with a signature-permission service on Android), a device wrap per app
([ADR 0059](0059-device-linking.md)), a share link routed to the right app although its address lives in the URL
fragment ([ADR 0015](0015-operator-blind-readable-urls.md)), a space whose Memory and Pot belong together split across
two apps, and a store listing, review and signing setup per app for one maintainer.

## Decision

1. **One app holds every application,** core and each application compiled in, as on the web
   ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md)). One sign-in, one device per install.
   An application the operator or the tenant owner switched off ([ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md))
   is absent from the navigation, as it is from the web's.

2. **The drawer chooses what the app is in:** **New** (create, in the application last used or one chosen), the
   member's **Favorites**, **Recent**, then the applications, then **Spaces** (the tree, which belongs to every
   application), then account, instance and settings. The drawer is a list, so it holds any number of applications.
   On a wide screen (iPad, Mac, a large Android window) it stays open as the sidebar.

3. **The bar at the bottom belongs to the chosen application,** and it ends with **Search** in every application.
   Each application decides whose views the bar holds: its own (Memories: its memories, the map; Depot: the files),
   or those of its object open in it (Expenses: an open Pot's entries, overview and settings, as the web's Pot has
   them — so the app opens on a Pot and adding an entry is one tap). An application whose bar is its object's shows
   the list of its objects while none is open. On iOS Search is the system's search tab: it turns the bar into a button
   back and a search field, its results above. Android places search where Material places it; the logic behind both
   is the shared base's.

4. **Every list follows one rule: Favorites, then Recent, then All,** each ordered by last use, an object listed once.
   An application's list holds its objects **across every space as one flat list**, never the tree; the tree is
   Spaces'. The app opens on the object last used; with none yet, it opens the drawer.

5. **Recent stays on the device.** It is per install, never synced and never sent: which object was opened when is
   behaviour, and a synced list would be a new sealed datum with no use the device's own list does not serve. The
   device stores the opened objects' ids and times, never their names, so nothing decrypted rests on it. Favorites
   are the existing sealed set per member and scope ([ADR 0068](0068-favorites-two-confidentiality-levels.md)), read
   from every scope the member belongs to.

6. **How an object presents itself is its application's;** the drawer, the bar's place for Search and the list rule
   around it are the same for all. Choosing an object of another application — from Favorites, Recent or Search —
   switches the bar to that application.

7. **Search runs on the device only;** the server holds ciphertext and searches nothing. The first stage searches
   the names the app decrypted. A persistent index of them, to search without reading every tree first, is sealed at
   rest under a key the platform's Keychain or Keystore holds and erased on sign-out. Searching content (a Pot's
   entries, a Memory's text) is a later decision of its own: a full-text index kept with every sync, at a storage cost
   the first stage does not have.

## Consequences

- ADR 0086 § The shape of the apps is replaced: there is the veyq app, and no app per application. Its "open in the
  app" banner on a share page stays, now always naming the one app.
- **Not decided here:** quick capture outside the app (widgets, shortcuts, the share sheet for a receipt); how a
  share link's `#k=` key reaches the app without being exposed on the way (inbox #435, #438).
- **A platform question it raises:** decision 4 lists an application's objects across every scope, and the drawer
  reads Favorites from every scope. The API serves a scope's tree and a scope's favorites, not a kind across scopes,
  so the app reads every scope (`listNodes` answers `304` for an unchanged tree). Whether that stays enough for a
  member of many spaces is measured once there are such members.
- The web keeps its navigation. Giving it the same start (Favorites, then Recent) is inbox #831, not V2.

## Alternatives considered

- **One app per application** (ADR 0086's sketch). Rejected for the costs in the context; the focus it promised comes
  from decision 3 (an application's own bar, a Pot one tap from its new entry) and decision 4 (open where you left).
- **One bottom tab per application.** Rejected: a bar holds three to five places, there are three applications and
  more to come, and the set changes with what the operator switches off.
- **Pinned applications in the bar, an object's views as segments at the top.** Rejected after it was written down
  first: two navigation levels compete for the screen, and the bar no longer says what the screen is about.
- **A drawer alone, no bar.** Rejected: an application's views and Search would always cost the drawer's extra tap.
