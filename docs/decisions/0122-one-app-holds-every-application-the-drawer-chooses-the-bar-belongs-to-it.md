# ADR 0122 — One app holds every application: the drawer chooses, the bar belongs to what is chosen

- Status: Accepted
- Built: partly (not built: decision 7's index, some objects' own views — [native-apps](../crosscutting/native-apps.md) keeps the picture)
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

2. **The drawer chooses what the app is in.** At its top the **switcher**, spaces and tenants in one (owner
   2026-11-10: a space is switched more often than a tenant): the shown space, its tenant and server under it (the
   server alone under the tenant's own space); behind it first the tenant's spaces — a space chosen opens its Home —,
   then every server the app is signed in to with the tenants the account holds there (owner 2026-11-08: one
   server can give several tenants, and the app connects to several servers), and **Manage servers**: a page of its
   own to add a server, sign out of one, and show or hide tenants and spaces. Hiding is the member's view on this
   device only — like Recent (decision 6) never synced, never sent, ids only: a hidden tenant leaves the switcher, a
   hidden space leaves the whole app (the switcher's spaces, the applications' lists, Favorites, Recent, Search); the
   current tenant and a tenant's last visible space cannot be hidden. Below the switcher **New** (create, in the
   application last used or one chosen), **Home**, the member's **Favorites**, **Recent**, then the applications, then
   settings. The drawer is a list, so it holds any number of applications. On a wide screen (iPad, Mac, a large
   Android window) it stays open as the sidebar.

3. **Home is one space's tree,** as the web's level view shows it (owner 2026-11-08): the folders, then the objects
   of every application side by side, the folders apart from the rest — each group under its heading, Folders and
   Contents, where both are there (the web's level groups, owner 2026-11-10); a folder opens in place, back goes up.
   Its title is the space's name; the space is switched in the drawer's switcher (decision 2), no longer from the
   title (owner 2026-11-10: a title menu was not found on an iPad and a Mac). Its **filters sit at the top** (All,
   then each application left on, the web's level tabs; the folders stay in every filter), not in the bar, which
   would run out of places as applications come. **Depot has no drawer entry:** its files are what Home's folders
   hold, so a flat list of them across spaces would be Home without the folders.

4. **The bar at the bottom belongs to the chosen application,** and it ends with **Search** everywhere; Home's bar is
   Search alone. Each application decides whose views the bar holds: its own (Memories: its memories, the map), or
   those of its object open in it (Expenses: an open Pot's entries, overview and settings, as the web's Pot has them —
   so the app opens on a Pot and adding an entry is one tap; Depot: an open file). An application whose bar is its
   object's shows the list of its objects while none is open. On iOS Search is the system's search tab: it turns the
   bar into a button back and a search field, its results above. Android places search where Material places it;
   the logic behind both is the shared base's.

5. **Every list follows one rule: Favorites, then Recent, then All,** each ordered by last use, an object listed once.
   An application's list holds its objects **across every space of the tenant as one flat list**, never the tree; the
   tree is Home's. The app opens on the object last used; with none yet, on Home with the drawer open.

6. **Recent stays on the device.** It is per install, never synced and never sent: which object was opened when is
   behaviour, and a synced list would be a new sealed datum with no use the device's own list does not serve. The
   device stores the opened objects' ids and times, never their names, so nothing decrypted rests on it. Favorites
   are the existing sealed set per member and scope ([ADR 0068](0068-favorites-two-confidentiality-levels.md)), read
   from every scope the member belongs to.

7. **How an object presents itself is its application's;** the drawer, the bar's place for Search and the list rule
   around it are the same for all. Choosing an object of another application — from Favorites, Recent, Home or
   Search — switches the bar to that application, and back returns to where it was chosen (Home stays Home, in the
   folder it was opened from); an object the app opened on goes back to its application's list. **An object chosen
   from the sidebar a wide window keeps open** is a top-level destination as Home and the applications are, with no
   back: the sidebar beside it is the way elsewhere (owner 2026-11-10, `Shell.openAtTop`). A phone's drawer, which
   closes over what it opened, keeps back.

8. **Search runs on the device only;** the server holds ciphertext and searches nothing. The first stage searches
   the names the app decrypted. A persistent index of them, to search without reading every tree first, is sealed at
   rest under a key the platform's Keychain or Keystore holds and erased on sign-out. Searching content (a Pot's
   entries, a Memory's text) is a later decision of its own: a full-text index kept with every sync, at a storage cost
   the first stage does not have.

## Consequences

- ADR 0086 § The shape of the apps is replaced: there is the veyq app, and no app per application. Its "open in the
  app" banner on a share page stays, now always naming the one app.
- **Not decided here:** quick capture outside the app (widgets, shortcuts, the share sheet for a receipt); how a
  share link's `#k=` key reaches the app without being exposed on the way (inbox #435, #438).
- **A platform question it raises:** decision 5 lists an application's objects across every scope, and the drawer
  reads Favorites from every scope. The API serves a scope's tree and a scope's favorites, not a kind across scopes,
  so the app reads every scope (`listNodes` answers `304` for an unchanged tree). Whether that stays enough for a
  member of many spaces is measured once there are such members.
- The web keeps its navigation. Giving it the same start (Favorites, then Recent) is inbox #835, not V2.

## Alternatives considered

- **One app per application** (ADR 0086's sketch). Rejected for the costs in the context; the focus it promised comes
  from decision 3 (an application's own bar, a Pot one tap from its new entry) and decision 4 (open where you left).
- **One bottom tab per application.** Rejected: a bar holds three to five places, there are three applications and
  more to come, and the set changes with what the operator switches off.
- **Pinned applications in the bar, an object's views as segments at the top.** Rejected after it was written down
  first: two navigation levels compete for the screen, and the bar no longer says what the screen is about.
- **A drawer alone, no bar.** Rejected: an application's views and Search would always cost the drawer's extra tap.
